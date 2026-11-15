package org.mgba.android;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.provider.OpenableColumns;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.KeyEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity implements EmuView.Listener, SensorEventListener {

    private static final int REQ_ROM = 1, REQ_BIOS = 2, REQ_PATCH = 3;
    private static final int STATE_SLOTS = 9;

    /** Original four-shade palettes for Game Boy games, lightest first (0xRRGGBB). */
    private static final String[] PALETTE_NAMES = {"Cinza (padrão)", "Verde clássico", "Sépia", "Azul gelo", "Alto contraste"};
    private static final int[][] PALETTES = {
            {0xF8F8F8, 0xA8A8A8, 0x505050, 0x000000},
            {0xC4CFA1, 0x8B956D, 0x4D533C, 0x1F1F1F},
            {0xF4E4C1, 0xC9A66B, 0x7A5230, 0x2B1B0E},
            {0xE8F4FF, 0x8FB8E0, 0x3E6A9E, 0x0B1F3A},
            {0xFFFFFF, 0xB0B0B0, 0x404040, 0x000000},
    };

    private EmuView view;
    private SharedPreferences prefs;
    private File baseDir;
    private String romPath;
    private int platform = -1;
    private List<Cheats.Cheat> cheats = new ArrayList<>();
    private boolean resumed;
    private int modalCount;
    private SensorManager sensors;
    private boolean sensorsOn;
    private Vibrator vibrator;
    private float accelX, accelY;
    private volatile boolean hiresGuard;
    private volatile int hiresGuardTicks;

    // ------------------------------------------------------------------ lifecycle
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        useWholeDisplay();
        prefs = getSharedPreferences("mgba", MODE_PRIVATE);

        File ext = getExternalFilesDir(null);
        baseDir = (ext != null) ? ext : getFilesDir();
        for (String d : new String[]{"roms", "bios"}) new File(baseDir, d).mkdirs();
        sensors = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        vibrator = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);

        view = new EmuView(this, this, prefs);
        setContentView(view);
        applyImmersive();

        // If the app died while the high-resolution renderer was starting, go back to software.
        if (prefs.getBoolean("hires_boot_pending", false)) {
            prefs.edit().putInt("hires", 1).putBoolean("hires_boot_pending", false).apply();
            toast("A resolução aumentada travou na última execução e voltou para 1x.");
        }

        Native.init(baseDir.getAbsolutePath());
        applyCoreOptions();
        applyViewSettings();
        if (Native.romLoaded()) {
            // The activity was recreated but the core kept running.
            romPath = prefs.getString("last_rom", null);
            platform = prefs.getInt("last_platform", Native.PLATFORM_GBA);
            view.setPlatform(platform);
            if (romPath != null) cheats = Cheats.load(baseDir, romPath);
        }
        view.start();
    }

    @Override
    protected void onResume() {
        super.onResume();
        applyImmersive();
        view.onHostResume();
        resumed = true;
        updatePaused();
        updateSensors();
    }

    @Override
    protected void onPause() {
        resumed = false;
        updatePaused();
        view.onHostPause();
        if (sensorsOn) {
            sensors.unregisterListener(this);
            sensorsOn = false;
        }
        if (hiresGuard) {
            hiresGuard = false;
            prefs.edit().putBoolean("hires_boot_pending", false).apply();
        }
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        view.stop();
        if (isFinishing()) Native.closeRom(); // also writes the battery save
        super.onDestroy();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) applyImmersive();
    }

    private void useWholeDisplay() {
        Window w = getWindow();
        if (Build.VERSION.SDK_INT >= 28) {
            WindowManager.LayoutParams lp = w.getAttributes();
            lp.layoutInDisplayCutoutMode = (Build.VERSION.SDK_INT >= 30)
                    ? WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                    : WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            w.setAttributes(lp);
        }
        if (Build.VERSION.SDK_INT >= 30) w.setDecorFitsSystemWindows(false);
    }

    @SuppressWarnings("deprecation")
    private void applyImmersive() {
        if (Build.VERSION.SDK_INT >= 30) {
            WindowInsetsController c = getWindow().getInsetsController();
            if (c != null) {
                c.hide(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
                c.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        }
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    }

    private void updatePaused() {
        view.setPaused(!resumed || modalCount > 0);
    }

    private void modal(boolean open) {
        modalCount = Math.max(0, modalCount + (open ? 1 : -1));
        updatePaused();
    }

    // ------------------------------------------------------------------ settings -> core / view
    private File biosFile() { return new File(new File(baseDir, "bios"), "gba_bios.bin"); }

    /** Sends every emulation option to the core. Safe to call at any time. */
    private void applyCoreOptions() {
        Native.setOption("frameskip", Integer.toString(prefs.getInt("frameskip", 0)));
        Native.setOption("idleOptimization", prefs.getString("idle", "detect"));
        Native.setOption("allowOpposingDirections", prefs.getBoolean("opposing", false) ? "1" : "0");
        Native.setOption("skipBios", prefs.getBoolean("skip_bios", true) ? "1" : "0");
        Native.setOption("gba.bios", biosFile().exists() ? biosFile().getAbsolutePath() : "");
        Native.setOption("volume", Integer.toString(prefs.getInt("volume", 100) * 256 / 100));
        Native.setOption("mute", prefs.getBoolean("sound", true) ? "0" : "1");
        String model = prefs.getString("gb_model", "");
        for (String k : new String[]{"gb.model", "sgb.model", "cgb.model", "cgb.hybridModel", "cgb.sgbModel"}) {
            Native.setOption(k, model);
        }
        Native.setOption("sgb.borders", prefs.getBoolean("sgb_borders", true) ? "1" : "0");
        Native.setOption("gb.colors", Integer.toString(prefs.getInt("gb_colors", 0)));
        int[] pal = PALETTES[Math.max(0, Math.min(PALETTES.length - 1, prefs.getInt("gb_palette", 0)))];
        for (int i = 0; i < 12; i++) {
            int c = pal[i % 4];
            // The core wants blue in the high byte.
            int bgr = ((c & 0xFF) << 16) | (c & 0xFF00) | ((c >> 16) & 0xFF);
            Native.setOption("gb.pal[" + i + "]", Integer.toString(bgr));
        }
        Native.setRewindSeconds(prefs.getInt("rewind", 0));
        Native.setLuminance(prefs.getInt("lux", 0));
    }

    private void applyViewSettings() {
        view.setSoundEnabled(prefs.getBoolean("sound", true));
        view.setShader(prefs.getInt("shader", 0));
        view.setShowHud(prefs.getBoolean("show_hud", true));
        view.setHud(prefs.getBoolean("hud_details", false), prefs.getInt("hud_scale", 100),
                prefs.getInt("hud_opacity", 80), prefs.getInt("hud_corner", 0));
        view.setShowControls(prefs.getBoolean("show_controls", true));
        view.setFastForwardSpeed(prefs.getInt("ff_speed", 3));
        view.setRewindEnabled(prefs.getInt("rewind", 0) > 0);
    }

    /** Applies the internal resolution. 1 = software renderer, 2..6 = OpenGL renderer (GBA only). */
    private void applyHires() {
        int scale = Native.hiresAvailable() ? prefs.getInt("hires", 1) : 1;
        if (scale > 1 && platform == Native.PLATFORM_GBA) {
            // Starting the OpenGL renderer is the risky moment: remember it in case the app dies here.
            prefs.edit().putBoolean("hires_boot_pending", true).commit();
            hiresGuardTicks = 0;
            hiresGuard = true;
        }
        Native.setHiresScale(scale);
        boolean active = Native.hiresActive();
        if (scale > 1 && platform == Native.PLATFORM_GBA && !active) {
            hiresGuard = false;
            prefs.edit().putInt("hires", 1).putBoolean("hires_boot_pending", false).apply();
            toast("Este aparelho não conseguiu iniciar a resolução aumentada. Usando 1x.");
        }
        view.setRendererLabel(active ? "OpenGL " + scale + "x" : "software");
    }

    private void updateSensors() {
        boolean want = resumed && prefs.getBoolean("motion", false);
        if (want == sensorsOn || sensors == null) return;
        if (want) {
            Sensor accel = sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
            Sensor gyro = sensors.getDefaultSensor(Sensor.TYPE_GYROSCOPE);
            if (accel != null) sensors.registerListener(this, accel, SensorManager.SENSOR_DELAY_GAME);
            if (gyro != null) sensors.registerListener(this, gyro, SensorManager.SENSOR_DELAY_GAME);
        } else {
            sensors.unregisterListener(this);
            Native.setMotion(0f, 0f, 0f);
        }
        sensorsOn = want;
    }

    @Override
    public void onSensorChanged(SensorEvent e) {
        if (e.sensor.getType() == Sensor.TYPE_ACCELEROMETER) {
            // Screen-relative axes for a phone held upright; in g.
            accelX = -e.values[0] / SensorManager.GRAVITY_EARTH;
            accelY = e.values[1] / SensorManager.GRAVITY_EARTH;
            Native.setMotion(accelX, accelY, 0f);
        } else if (e.sensor.getType() == Sensor.TYPE_GYROSCOPE) {
            Native.setMotion(accelX, accelY, e.values[2]);
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) { }

    // ------------------------------------------------------------------ EmuView.Listener
    @Override
    public void onMenuRequested() {
        runOnUiThread(this::showMenu);
    }

    @Override
    public void onTick() {
        // About five seconds of running without a crash: the renderer is fine.
        if (hiresGuard && ++hiresGuardTicks >= 5) {
            hiresGuard = false;
            prefs.edit().putBoolean("hires_boot_pending", false).apply();
        }
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onRumble(float strength) {
        if (vibrator == null || !prefs.getBoolean("rumble", true)) return;
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                int amp = Math.max(1, Math.min(255, (int) (strength * 255)));
                vibrator.vibrate(VibrationEffect.createOneShot(45, amp));
            } else {
                vibrator.vibrate(45);
            }
        } catch (RuntimeException ignored) {
            // No vibrator or no permission: rumble is optional.
        }
    }

    // ------------------------------------------------------------------ menus
    private interface Item { void run(); }

    private final ArrayList<String> menuLabels = new ArrayList<>();
    private final ArrayList<Item> menuActions = new ArrayList<>();

    private void item(String label, Item action) {
        menuLabels.add(label);
        menuActions.add(action);
    }

    /** Shows the items collected with item() as a list; picking one runs it after the list closes. */
    private void showItems(String title) {
        final String[] labels = menuLabels.toArray(new String[0]);
        final Item[] actions = menuActions.toArray(new Item[0]);
        menuLabels.clear();
        menuActions.clear();
        dialog(new AlertDialog.Builder(this)
                .setTitle(title)
                .setItems(labels, (d, which) -> view.post(actions[which]::run)));
    }

    private String onOff(boolean v) { return v ? "ligado" : "desligado"; }

    private void toggle(String key, boolean def) {
        prefs.edit().putBoolean(key, !prefs.getBoolean(key, def)).apply();
        applyCoreOptions();
        applyViewSettings();
    }

    private void showMenu() {
        if (modalCount > 0) return;
        final boolean loaded = Native.romLoaded();
        final String last = prefs.getString("last_rom", null);

        item("Abrir ROM (.gba, .gb, .gbc, .zip, .7z)", () -> pick(REQ_ROM));
        if (!loaded && last != null && new File(last).exists()) {
            item("Continuar: " + new File(last).getName(), () -> loadRom(last));
        }
        if (loaded) {
            item("Salvar estado", () -> showSlots(true));
            item("Carregar estado", () -> showSlots(false));
            int active = 0;
            for (Cheats.Cheat c : cheats) if (c.enabled) active++;
            item("Cheats" + (active > 0 ? " (" + active + " ativos)" : ""), this::showCheats);
            item("Avanço rápido: " + onOff(view.isFastForward()), () -> view.setFastForward(!view.isFastForward()));
            item("Captura de tela", this::takeScreenshot);
            item("Reiniciar jogo", () -> { Native.reset(); Cheats.apply(cheats); });
        }
        item("Vídeo", this::showVideo);
        item("Áudio", this::showAudio);
        item("Emulação", this::showEmulation);
        item("Game Boy", this::showGameBoy);
        item("Sensores e vibração", this::showSensors);
        item("Monitor de desempenho", this::showHud);
        item("Controles na tela: " + onOff(prefs.getBoolean("show_controls", true)), () -> toggle("show_controls", true));
        item("Sair", this::finish);
        showItems("mGBA");
    }

    // ---- video
    private void showVideo() {
        int hires = prefs.getInt("hires", 1);
        item("Resolução interna (GBA): " + hires + "x", this::showHires);
        item("Shader", this::showShaders);
        item("Layout da tela", this::showLayouts);
        if (Native.romLoaded()) item("Camadas de vídeo", () -> showChannels(true));
        showItems("Vídeo");
    }

    private void showHires() {
        if (!Native.hiresAvailable()) {
            toast("A resolução aumentada não está disponível nesta versão.");
            return;
        }
        final String[] labels = {"1x — 240×160 (software, mais compatível)", "2x — 480×320", "3x — 720×480",
                "4x — 960×640", "5x — 1200×800", "6x — 1440×960"};
        choose("Resolução interna (só GBA, experimental)", labels, prefs.getInt("hires", 1) - 1, which -> {
            prefs.edit().putInt("hires", which + 1).apply();
            applyHires();
        });
    }

    private void showShaders() {
        choose("Shader", Shaders.NAMES, prefs.getInt("shader", 0), which -> {
            prefs.edit().putInt("shader", which).apply();
            view.setShader(which);
        });
    }

    private void showLayouts() {
        final int n = EmuView.LAYOUT_NAMES.length;
        final String[] labels = new String[n + 1];
        System.arraycopy(EmuView.LAYOUT_NAMES, 0, labels, 0, n);
        labels[n] = "Personalizado: mover, redimensionar e esticar…";
        int cur = view.getLayoutPreset();
        choose("Layout (vale para a orientação atual)", labels, cur == EmuView.LAYOUT_CUSTOM ? n : cur, which -> {
            if (which < n) view.setLayoutPreset(which);
            else view.startLayoutEdit();
        });
    }

    /** Video layers or audio channels: tick to keep on. Resets when another game is loaded. */
    private void showChannels(final boolean video) {
        final String[] entries = video ? Native.videoLayers() : Native.audioChannels();
        if (entries.length == 0) return;
        final String[] names = new String[entries.length];
        final int[] ids = new int[entries.length];
        final boolean[] on = new boolean[entries.length];
        final String stateKey = video ? "layers_off" : "channels_off";
        final String off = "," + prefs.getString(stateKey, "") + ",";
        for (int i = 0; i < entries.length; i++) {
            int bar = entries[i].indexOf('|');
            ids[i] = Integer.parseInt(entries[i].substring(0, bar));
            names[i] = entries[i].substring(bar + 1);
            on[i] = !off.contains("," + ids[i] + ",");
        }
        dialog(new AlertDialog.Builder(this)
                .setTitle(video ? "Camadas de vídeo" : "Canais de áudio")
                .setMultiChoiceItems(names, on, (d, which, checked) -> on[which] = checked)
                .setPositiveButton("Aplicar", (d, w) -> {
                    StringBuilder sb = new StringBuilder();
                    for (int i = 0; i < ids.length; i++) {
                        if (video) Native.enableVideoLayer(ids[i], on[i]);
                        else Native.enableAudioChannel(ids[i], on[i]);
                        if (!on[i]) sb.append(sb.length() > 0 ? "," : "").append(ids[i]);
                    }
                    prefs.edit().putString(stateKey, sb.toString()).apply();
                })
                .setNegativeButton("Cancelar", null));
    }

    // ---- audio
    private void showAudio() {
        item("Som: " + onOff(prefs.getBoolean("sound", true)), () -> toggle("sound", true));
        item("Volume: " + prefs.getInt("volume", 100) + "%", () -> {
            final int[] values = {25, 50, 75, 100};
            final String[] labels = {"25%", "50%", "75%", "100%"};
            int cur = 3;
            for (int i = 0; i < values.length; i++) if (values[i] == prefs.getInt("volume", 100)) cur = i;
            choose("Volume", labels, cur, which -> {
                prefs.edit().putInt("volume", values[which]).apply();
                applyCoreOptions();
            });
        });
        if (Native.romLoaded()) item("Canais de áudio", () -> showChannels(false));
        showItems("Áudio");
    }

    // ---- emulation
    private void showEmulation() {
        int rewind = prefs.getInt("rewind", 0);
        item("Rebobinar: " + (rewind > 0 ? "últimos " + rewind + " s" : "desligado"), () -> {
            final int[] values = {0, 10, 30, 60};
            final String[] labels = {"Desligado", "10 segundos", "30 segundos", "60 segundos (usa mais memória)"};
            int cur = 0;
            for (int i = 0; i < values.length; i++) if (values[i] == prefs.getInt("rewind", 0)) cur = i;
            choose("Rebobinar (segure ◀◀ na tela)", labels, cur, which -> {
                prefs.edit().putInt("rewind", values[which]).apply();
                applyCoreOptions();
                applyViewSettings();
            });
        });
        int ff = prefs.getInt("ff_speed", 3);
        item("Velocidade do avanço rápido: " + (ff == 0 ? "máxima" : ff + "x"), () -> {
            final String[] labels = new String[EmuView.FF_SPEEDS.length];
            int cur = 1;
            for (int i = 0; i < labels.length; i++) {
                labels[i] = EmuView.FF_SPEEDS[i] == 0 ? "Máxima" : EmuView.FF_SPEEDS[i] + "x";
                if (EmuView.FF_SPEEDS[i] == prefs.getInt("ff_speed", 3)) cur = i;
            }
            choose("Velocidade do avanço rápido", labels, cur, which -> {
                prefs.edit().putInt("ff_speed", EmuView.FF_SPEEDS[which]).apply();
                applyViewSettings();
            });
        });
        item("Frameskip: " + prefs.getInt("frameskip", 0), () ->
                choose("Frameskip", new String[]{"0 (sem pular)", "1", "2", "3"}, prefs.getInt("frameskip", 0), which -> {
                    prefs.edit().putInt("frameskip", which).apply();
                    applyCoreOptions();
                }));
        final String[] idleValues = {"detect", "remove", "ignore"};
        item("Otimização de espera (idle loops)", () -> {
            int cur = 0;
            for (int i = 0; i < idleValues.length; i++) if (idleValues[i].equals(prefs.getString("idle", "detect"))) cur = i;
            choose("Otimização de espera (vale ao reabrir o jogo)",
                    new String[]{"Detectar e remover (mais rápido)", "Remover só as conhecidas", "Não remover (mais fiel)"},
                    cur, which -> {
                        prefs.edit().putString("idle", idleValues[which]).apply();
                        applyCoreOptions();
                    });
        });
        item("Permitir direções opostas: " + onOff(prefs.getBoolean("opposing", false)), () -> toggle("opposing", false));
        item("BIOS do GBA: " + (biosFile().exists() ? "importada" : "interna do mGBA"), this::showBios);
        if (Native.romLoaded()) item("Aplicar patch (.ips, .ups, .bps) a este jogo", () -> pick(REQ_PATCH));
        showItems("Emulação");
    }

    private void showBios() {
        item("Importar arquivo de BIOS", () -> pick(REQ_BIOS));
        if (biosFile().exists()) {
            item("Remover a BIOS importada", () -> {
                biosFile().delete();
                applyCoreOptions();
                toast("BIOS removida. Vale ao reabrir o jogo.");
            });
        }
        item("Pular a abertura da BIOS: " + onOff(prefs.getBoolean("skip_bios", true)), () -> toggle("skip_bios", true));
        showItems("BIOS do GBA (opcional)");
    }

    // ---- Game Boy
    private void showGameBoy() {
        final String[] modelValues = {"", "DMG", "MGB", "SGB", "CGB", "AGB"};
        final String[] modelLabels = {"Automático", "Game Boy (DMG)", "Game Boy Pocket", "Super Game Boy",
                "Game Boy Color", "Game Boy Advance"};
        int curModel = 0;
        for (int i = 0; i < modelValues.length; i++) if (modelValues[i].equals(prefs.getString("gb_model", ""))) curModel = i;
        final int cm = curModel;
        item("Modelo: " + modelLabels[cm], () -> choose("Modelo (vale ao reabrir o jogo)", modelLabels, cm, which -> {
            prefs.edit().putString("gb_model", modelValues[which]).apply();
            applyCoreOptions();
        }));
        item("Bordas do Super Game Boy: " + onOff(prefs.getBoolean("sgb_borders", true)), () -> toggle("sgb_borders", true));
        item("Paleta dos jogos em preto e branco", () ->
                choose("Paleta", PALETTE_NAMES, prefs.getInt("gb_palette", 0), which -> {
                    prefs.edit().putInt("gb_palette", which).apply();
                    applyCoreOptions();
                }));
        item("Colorir jogos de Game Boy", () ->
                choose("Colorir (vale ao reabrir o jogo)",
                        new String[]{"Não (usar a paleta acima)", "Cores do Game Boy Color", "Cores do Super Game Boy", "As duas, quando houver"},
                        prefs.getInt("gb_colors", 0), which -> {
                            prefs.edit().putInt("gb_colors", which).apply();
                            applyCoreOptions();
                        }));
        showItems("Game Boy / Game Boy Color");
    }

    // ---- sensors
    private void showSensors() {
        item("Sensor de movimento (inclinação e giro): " + onOff(prefs.getBoolean("motion", false)), () -> {
            toggle("motion", false);
            updateSensors();
        });
        item("Vibração (rumble): " + onOff(prefs.getBoolean("rumble", true)), () -> toggle("rumble", true));
        item("Sensor solar: nível " + prefs.getInt("lux", 0) + " de 10", () -> {
            final String[] labels = new String[11];
            for (int i = 0; i <= 10; i++) labels[i] = i == 0 ? "0 (escuro)" : i == 10 ? "10 (sol forte)" : Integer.toString(i);
            choose("Sensor solar (jogos Boktai)", labels, prefs.getInt("lux", 0), which -> {
                prefs.edit().putInt("lux", which).apply();
                applyCoreOptions();
            });
        });
        showItems("Sensores e vibração");
    }

    // ---- performance monitor
    private static int next(int[] values, int current) {
        for (int i = 0; i < values.length; i++) {
            if (values[i] == current) return values[(i + 1) % values.length];
        }
        return values[0];
    }

    private void showHud() {
        final boolean details = prefs.getBoolean("hud_details", false);
        final int scale = prefs.getInt("hud_scale", 100);
        final int opacity = prefs.getInt("hud_opacity", 80);
        final int corner = prefs.getInt("hud_corner", 0);
        item("Exibir: " + onOff(prefs.getBoolean("show_hud", true)), () -> { toggle("show_hud", true); showHud(); });
        item("Detalhes (CPU, GPU, RAM, clocks): " + (details ? "ligado" : "só FPS"), () -> { toggle("hud_details", false); showHud(); });
        item("Tamanho: " + scale + "%", () -> {
            prefs.edit().putInt("hud_scale", next(new int[]{70, 85, 100, 125, 150, 200}, scale)).apply();
            applyViewSettings();
            showHud();
        });
        item("Opacidade: " + opacity + "%", () -> {
            prefs.edit().putInt("hud_opacity", next(new int[]{100, 80, 60, 40, 25}, opacity)).apply();
            applyViewSettings();
            showHud();
        });
        item("Canto: " + EmuView.HUD_CORNERS[Math.max(0, Math.min(3, corner))], () -> {
            prefs.edit().putInt("hud_corner", (corner + 1) % 4).apply();
            applyViewSettings();
            showHud();
        });
        item("Fechar", () -> { });
        showItems("Monitor de desempenho");
    }

    // ---- save states / screenshots
    private String romBase() {
        String name = new File(romPath).getName();
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    private void showSlots(final boolean save) {
        final String[] labels = new String[STATE_SLOTS];
        for (int i = 0; i < STATE_SLOTS; i++) {
            int slot = i + 1;
            File f = new File(new File(baseDir, "states"), romBase() + ".ss" + slot);
            labels[i] = "Slot " + slot + (Native.stateExists(slot)
                    ? " — " + (f.exists() ? android.text.format.DateFormat.format("dd/MM HH:mm", f.lastModified()) : "salvo")
                    : " — vazio");
        }
        dialog(new AlertDialog.Builder(this)
                .setTitle(save ? "Salvar estado" : "Carregar estado")
                .setItems(labels, (d, which) -> {
                    boolean ok = save ? Native.saveState(which + 1) : Native.loadState(which + 1);
                    toast(ok ? (save ? "Estado salvo" : "Estado carregado") : (save ? "Não foi possível salvar" : "Este slot está vazio"));
                }));
    }

    private void takeScreenshot() {
        File dir = new File(baseDir, "screenshots");
        dir.mkdirs();
        String stamp = android.text.format.DateFormat.format("yyyyMMdd-HHmmss", System.currentTimeMillis()).toString();
        File f = new File(dir, romBase() + "-" + stamp + ".png");
        toast(Native.screenshot(f.getAbsolutePath())
                ? "Captura salva em " + f.getParentFile().getName() + "/" + f.getName()
                : "Não foi possível salvar a captura");
    }

    // ---- dialog helpers
    private interface Chosen { void on(int which); }

    private void choose(String title, String[] labels, int current, final Chosen chosen) {
        dialog(new AlertDialog.Builder(this)
                .setTitle(title)
                .setSingleChoiceItems(labels, current, (d, which) -> {
                    d.dismiss();
                    chosen.on(which);
                }));
    }

    /** Shows a dialog with emulation paused, resuming when it closes. */
    private void dialog(AlertDialog.Builder b) {
        modal(true);
        AlertDialog d = b.create();
        d.setOnDismissListener(x -> {
            modal(false);
            applyImmersive();
        });
        d.show();
    }

    private void message(String title, String text) {
        dialog(new AlertDialog.Builder(this).setTitle(title).setMessage(text).setPositiveButton("OK", null));
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
    }

    // ------------------------------------------------------------------ cheats
    private void saveCheats() {
        if (romPath == null) return;
        Cheats.save(baseDir, romPath, cheats);
        int accepted = Cheats.apply(cheats);
        int wanted = 0;
        for (Cheats.Cheat c : cheats) if (c.enabled) wanted++;
        if (accepted < wanted) toast((wanted - accepted) + " cheat(s) não foram aceitos pelo emulador (código inválido).");
    }

    private void showCheats() {
        item("Ativar / desativar (" + cheats.size() + " salvos)", this::showCheatToggles);
        item("Buscar no banco de cheats", this::openCheatDatabase);
        item("Adicionar código manualmente", this::showAddCheat);
        item("Apagar cheats", this::showDeleteCheats);
        item("Desativar todos", () -> {
            for (Cheats.Cheat c : cheats) c.enabled = false;
            saveCheats();
            toast("Todos os cheats foram desativados.");
        });
        showItems("Cheats");
    }

    private String[] cheatNames(List<Cheats.Cheat> list) {
        String[] out = new String[list.size()];
        for (int i = 0; i < out.length; i++) out[i] = list.get(i).name;
        return out;
    }

    private void showCheatToggles() {
        if (cheats.isEmpty()) {
            toast("Nenhum cheat salvo para este jogo. Use \"Buscar no banco\" ou \"Adicionar código\".");
            return;
        }
        final boolean[] on = new boolean[cheats.size()];
        for (int i = 0; i < on.length; i++) on[i] = cheats.get(i).enabled;
        dialog(new AlertDialog.Builder(this)
                .setTitle("Ativar / desativar")
                .setMultiChoiceItems(cheatNames(cheats), on, (d, which, checked) -> on[which] = checked)
                .setPositiveButton("Aplicar", (d, w) -> {
                    for (int i = 0; i < on.length; i++) cheats.get(i).enabled = on[i];
                    saveCheats();
                })
                .setNegativeButton("Cancelar", null));
    }

    private void showDeleteCheats() {
        if (cheats.isEmpty()) {
            toast("Nenhum cheat salvo para este jogo.");
            return;
        }
        final boolean[] del = new boolean[cheats.size()];
        dialog(new AlertDialog.Builder(this)
                .setTitle("Marque os cheats para apagar")
                .setMultiChoiceItems(cheatNames(cheats), del, (d, which, checked) -> del[which] = checked)
                .setPositiveButton("Apagar", (d, w) -> {
                    List<Cheats.Cheat> keep = new ArrayList<>();
                    for (int i = 0; i < del.length; i++) if (!del[i]) keep.add(cheats.get(i));
                    cheats = keep;
                    saveCheats();
                })
                .setNegativeButton("Cancelar", null));
    }

    private LinearLayout column() {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        col.setPadding(pad, pad / 2, pad, 0);
        return col;
    }

    private void showAddCheat() {
        LinearLayout col = column();
        final EditText name = new EditText(this);
        name.setHint("Nome do cheat");
        name.setSingleLine(true);
        final EditText code = new EditText(this);
        code.setHint(platform == Native.PLATFORM_GBA
                ? "GameShark, Action Replay ou CodeBreaker\nEx.: 02000000 0063"
                : "GameShark ou Game Genie\nEx.: 01FF56C1 ou 00A-17B-C49");
        code.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
        code.setMinLines(4);
        col.addView(name);
        col.addView(code);
        dialog(new AlertDialog.Builder(this)
                .setTitle("Adicionar código")
                .setView(col)
                .setPositiveButton("Adicionar", (d, w) -> {
                    String normalized = Cheats.normalizeCode(code.getText().toString());
                    if (normalized == null) {
                        toast("Código inválido: use só números, letras de A a F e hífens.");
                        return;
                    }
                    String n = name.getText().toString().trim();
                    cheats.add(new Cheats.Cheat(n.isEmpty() ? "Cheat " + (cheats.size() + 1) : n, normalized, true));
                    saveCheats();
                })
                .setNegativeButton("Cancelar", null));
    }

    private void openCheatDatabase() {
        final AlertDialog progress = new AlertDialog.Builder(this)
                .setMessage("Preparando o banco de cheats…").setCancelable(false).create();
        modal(true);
        progress.show();
        new Thread(() -> {
            String error = null;
            try {
                Cheats.openDatabase(this);
            } catch (Exception e) {
                error = e.getMessage();
            }
            final String message = error;
            runOnUiThread(() -> {
                progress.dismiss();
                modal(false);
                if (message != null) toast(message);
                else showCheatSearch();
            });
        }, "cheat-db").start();
    }

    private void showCheatSearch() {
        final String[] folders = platform == Native.PLATFORM_GBA ? new String[]{"gba/"} : new String[]{"gbc/", "gb/"};
        LinearLayout col = column();
        final EditText query = new EditText(this);
        query.setSingleLine(true);
        query.setHint("Nome do jogo");
        String initial = Cheats.queryFromFileName(new File(romPath).getName());
        if (initial.replaceAll("[^A-Za-z]", "").length() < 3) {
            String info = Native.gameInfo();
            initial = info.substring(0, Math.max(0, info.indexOf('|'))).trim();
        }
        query.setText(initial);
        TextView credit = new TextView(this);
        credit.setText(Cheats.databaseSize() + " jogos · banco libretro-database (CC BY-SA 4.0)");
        credit.setTextSize(11f);
        final ListView results = new ListView(this);
        final List<String> found = new ArrayList<>(Cheats.search(initial, folders, 60));
        final List<String> shown = new ArrayList<>();
        for (String f : found) shown.add(Cheats.displayName(f));
        final ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, shown);
        results.setAdapter(adapter);
        col.addView(query);
        col.addView(credit);
        col.addView(results, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                (int) (getResources().getDisplayMetrics().heightPixels * 0.5f)));
        query.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void afterTextChanged(Editable s) {
                found.clear();
                found.addAll(Cheats.search(s.toString(), folders, 60));
                shown.clear();
                for (String f : found) shown.add(Cheats.displayName(f));
                adapter.notifyDataSetChanged();
            }
        });
        final AlertDialog[] box = new AlertDialog[1];
        results.setOnItemClickListener((parent, v, position, id) -> {
            String entry = found.get(position);
            box[0].dismiss();
            view.post(() -> showDatabaseGame(entry));
        });
        modal(true);
        box[0] = new AlertDialog.Builder(this)
                .setTitle("Escolha o jogo (confira a região)")
                .setView(col)
                .setNegativeButton("Fechar", null)
                .create();
        box[0].setOnDismissListener(x -> { modal(false); applyImmersive(); });
        box[0].show();
    }

    private void showDatabaseGame(String entry) {
        final List<Cheats.Cheat> list;
        try {
            list = Cheats.read(entry);
        } catch (Exception e) {
            toast("Não foi possível ler os cheats deste jogo.");
            return;
        }
        if (list.isEmpty()) {
            toast("Este jogo não tem cheats utilizáveis no banco.");
            return;
        }
        final boolean[] pick = new boolean[list.size()];
        dialog(new AlertDialog.Builder(this)
                .setTitle(Cheats.displayName(entry))
                .setMultiChoiceItems(cheatNames(list), pick, (d, which, checked) -> pick[which] = checked)
                .setPositiveButton("Adicionar e ativar", (d, w) -> {
                    int added = 0;
                    for (int i = 0; i < pick.length; i++) {
                        if (!pick[i]) continue;
                        Cheats.Cheat c = list.get(i);
                        c.enabled = true;
                        cheats.add(c);
                        added++;
                    }
                    if (added == 0) return;
                    saveCheats();
                    toast(added + " cheat(s) adicionados. Se o jogo travar, desative-os no menu Cheats.");
                })
                .setNegativeButton("Cancelar", null));
    }

    // ------------------------------------------------------------------ files
    @SuppressWarnings("deprecation")
    private void pick(int request) {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        try {
            startActivityForResult(i, request);
        } catch (RuntimeException e) {
            toast("Nenhum seletor de arquivos disponível");
        }
    }

    @Override
    @SuppressWarnings("deprecation")
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
        final Uri uri = data.getData();
        final String name = displayName(uri);
        final String lower = name.toLowerCase(Locale.ROOT);

        if (requestCode == REQ_ROM) {
            if (lower.endsWith(".rar")) {
                message("Formato não suportado", "O mGBA não abre arquivos .rar. Reempacote como .zip ou .7z, ou extraia a ROM.");
                return;
            }
            copyThen(uri, new File(new File(baseDir, "roms"), name), "Importando " + name + "…",
                    f -> loadRom(f.getAbsolutePath()));
        } else if (requestCode == REQ_BIOS) {
            copyThen(uri, biosFile(), "Importando BIOS…", f -> {
                if (f.length() != 16384) {
                    f.delete();
                    message("Arquivo inválido", "A BIOS do GBA tem exatamente 16 KB. Este arquivo não parece ser uma.");
                } else {
                    toast("BIOS importada. Vale ao reabrir o jogo.");
                }
                applyCoreOptions();
            });
        } else if (requestCode == REQ_PATCH && romPath != null) {
            int dot = lower.lastIndexOf('.');
            String ext = dot >= 0 ? lower.substring(dot) : "";
            if (!ext.equals(".ips") && !ext.equals(".ups") && !ext.equals(".bps")) {
                message("Formato não suportado", "Escolha um patch .ips, .ups ou .bps.");
                return;
            }
            // The core applies a patch that sits beside the ROM with the same name.
            File dir = new File(romPath).getParentFile();
            for (String e : new String[]{".ips", ".ups", ".bps"}) new File(dir, romBase() + e).delete();
            copyThen(uri, new File(dir, romBase() + ext), "Aplicando patch…", f -> loadRom(romPath));
        }
    }

    private String displayName(Uri uri) {
        String name = null;
        try (Cursor c = getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) name = c.getString(0);
        } catch (RuntimeException ignored) { }
        if (name == null) name = uri.getLastPathSegment();
        if (name == null) name = "game.gba";
        return name.replaceAll("[\\\\/:*?\"<>|]", "_");
    }

    private interface Copied { void on(File f); }

    /** Copies the picked file into the app folder (the core needs a real file path), then continues. */
    private void copyThen(final Uri uri, final File dest, String label, final Copied then) {
        final AlertDialog progress = new AlertDialog.Builder(this).setMessage(label).setCancelable(false).create();
        modal(true);
        progress.show();
        new Thread(() -> {
            boolean ok = false;
            File tmp = new File(dest.getPath() + ".part");
            try (InputStream in = getContentResolver().openInputStream(uri);
                 OutputStream out = new FileOutputStream(tmp)) {
                if (in != null) {
                    byte[] buf = new byte[1 << 16];
                    int n;
                    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                    ok = true;
                }
            } catch (Exception ignored) { }
            if (ok) ok = (!dest.exists() || dest.delete()) && tmp.renameTo(dest);
            if (!ok) tmp.delete();
            final boolean success = ok;
            runOnUiThread(() -> {
                progress.dismiss();
                if (success) then.on(dest);
                else toast("Não foi possível copiar o arquivo");
                modal(false);
            });
        }, "import").start();
    }

    private void loadRom(String path) {
        applyCoreOptions();
        int p = Native.loadRom(path);
        if (p < 0) {
            romPath = null;
            platform = -1;
            message("Não foi possível abrir", "O mGBA não reconheceu este arquivo como um jogo de Game Boy, "
                    + "Game Boy Color ou Game Boy Advance. Se for um .zip ou .7z, ele precisa ter a ROM dentro.");
            return;
        }
        romPath = path;
        platform = p;
        prefs.edit().putString("last_rom", path).putInt("last_platform", p)
                .remove("layers_off").remove("channels_off").apply();
        view.setPlatform(p);
        view.setFastForward(false);
        applyHires();
        cheats = Cheats.load(baseDir, path);
        Cheats.apply(cheats);
    }

    // ------------------------------------------------------------------ physical controllers / keyboard
    private static int mapKey(int keyCode) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_BUTTON_A: case KeyEvent.KEYCODE_X: return Native.KEY_A;
            case KeyEvent.KEYCODE_BUTTON_B: case KeyEvent.KEYCODE_Z: return Native.KEY_B;
            case KeyEvent.KEYCODE_BUTTON_L1: case KeyEvent.KEYCODE_A: return Native.KEY_L;
            case KeyEvent.KEYCODE_BUTTON_R1: case KeyEvent.KEYCODE_S: return Native.KEY_R;
            case KeyEvent.KEYCODE_BUTTON_START: case KeyEvent.KEYCODE_ENTER: return Native.KEY_START;
            case KeyEvent.KEYCODE_BUTTON_SELECT: case KeyEvent.KEYCODE_DEL: return Native.KEY_SELECT;
            case KeyEvent.KEYCODE_DPAD_UP: return Native.KEY_UP;
            case KeyEvent.KEYCODE_DPAD_DOWN: return Native.KEY_DOWN;
            case KeyEvent.KEYCODE_DPAD_LEFT: return Native.KEY_LEFT;
            case KeyEvent.KEYCODE_DPAD_RIGHT: return Native.KEY_RIGHT;
            default: return 0;
        }
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        int code = event.getKeyCode();
        if (code == KeyEvent.KEYCODE_BACK) {
            if (event.getAction() == KeyEvent.ACTION_UP) {
                if (view.isEditingLayout()) view.finishLayoutEdit();
                else showMenu();
            }
            return true;
        }
        if (code == KeyEvent.KEYCODE_TAB || code == KeyEvent.KEYCODE_BUTTON_R2) {
            // Hold to fast-forward, like Tab on the desktop version.
            view.setFastForward(event.getAction() == KeyEvent.ACTION_DOWN);
            return true;
        }
        int k = mapKey(code);
        if (k != 0) {
            if (event.getAction() == KeyEvent.ACTION_DOWN) view.setPadKeys(view.getPadKeys() | k);
            else if (event.getAction() == KeyEvent.ACTION_UP) view.setPadKeys(view.getPadKeys() & ~k);
            return true;
        }
        return super.dispatchKeyEvent(event);
    }
}
