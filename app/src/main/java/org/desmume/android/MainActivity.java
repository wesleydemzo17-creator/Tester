package org.desmume.android;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.view.KeyEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
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

public class MainActivity extends Activity implements EmuView.Listener {

    private static final int REQ_OPEN_ROM = 1;
    private static final int JIT_BLOCK_SIZE = 12;
    /** Frames that must run before a JIT session counts as stable. */
    private static final int JIT_STABLE_FRAMES = 300;

    private EmuView view;
    private SharedPreferences prefs;
    private File baseDir;
    private String romPath;
    private List<Cheats.Cheat> cheats = new ArrayList<>();
    private boolean coreReady;
    private boolean resumed;
    private int modalCount;
    private boolean gpuActive;
    private boolean rendererApplied;
    private volatile boolean jitGuardArmed;
    private volatile int jitGuardTicks;

    // ------------------------------------------------------------------ lifecycle
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        useWholeDisplay();
        prefs = getSharedPreferences("desmume", MODE_PRIVATE);

        File ext = getExternalFilesDir(null);
        baseDir = (ext != null) ? ext : getFilesDir();
        new File(baseDir, "roms").mkdirs();
        new File(baseDir, "states").mkdirs();

        view = new EmuView(this, this, prefs);
        setContentView(view);
        applyImmersive();

        // If the previous session died while the JIT was starting up, fall back to the interpreter.
        if (prefs.getBoolean("jit_boot_pending", false)) {
            prefs.edit().putBoolean("jit", false).putBoolean("jit_boot_pending", false).apply();
            toast("O JIT travou na última execução e foi desligado. Reative em Configurações se quiser.");
        }
        // Same safety net for the GPU renderer.
        if (prefs.getBoolean("gpu_boot_pending", false)) {
            prefs.edit().putBoolean("gpu3d", false).putBoolean("gpu_boot_pending", false).apply();
            toast("O 3D pela GPU travou na última execução e voltou para a CPU.");
        }

        coreReady = Native.init(baseDir.getAbsolutePath(), 0);
        if (!coreReady) {
            toast("Falha ao iniciar o núcleo do DeSmuME");
            return;
        }
        applySettings();
        if (Native.romLoaded()) romPath = prefs.getString("last_rom", null);
        view.start();
    }

    @Override
    protected void onResume() {
        super.onResume();
        applyImmersive();
        view.onHostResume();
        resumed = true;
        updatePaused();
    }

    @Override
    protected void onPause() {
        resumed = false;
        updatePaused();
        view.onHostPause();
        if (coreReady) Native.flushSaves();
        // A clean pause means the JIT did not crash the process.
        if (jitGuardArmed) {
            jitGuardArmed = false;
            clearCrashGuards();
        }
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        view.stop();
        if (coreReady && isFinishing()) {
            Native.closeRom();
        }
        super.onDestroy();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) applyImmersive();
    }

    /**
     * Lets the game draw over the whole panel, including the strip around the front camera and
     * under the (hidden) status and navigation bars. Without this, Android keeps that strip black
     * and pushes the screens down.
     */
    private void useWholeDisplay() {
        Window w = getWindow();
        if (Build.VERSION.SDK_INT >= 28) {
            WindowManager.LayoutParams lp = w.getAttributes();
            lp.layoutInDisplayCutoutMode = (Build.VERSION.SDK_INT >= 30)
                    ? WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                    : WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            w.setAttributes(lp);
        }
        if (Build.VERSION.SDK_INT >= 30) {
            w.setDecorFitsSystemWindows(false);
        }
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

    private void applySettings() {
        boolean jit = prefs.getBoolean("jit", true) && Native.jitAvailable();
        Native.setJit(jit, JIT_BLOCK_SIZE);
        Native.setAdvancedTiming(prefs.getBoolean("advanced_timing", true));
        view.setSoundEnabled(prefs.getBoolean("sound", true));
        // "smooth" is the old on/off option; it maps to the bilinear shader.
        view.setShader(prefs.getInt("shader", prefs.getBoolean("smooth", false) ? 1 : 0));
        view.setResolutionScale(prefs.getInt("res_scale", 1));
        applyRenderer();
        view.setShowFps(prefs.getBoolean("show_fps", true));
        view.setHud(prefs.getBoolean("hud_details", true), prefs.getInt("hud_scale", 100),
                prefs.getInt("hud_opacity", 80), prefs.getInt("hud_corner", 0));
        view.setShowControls(prefs.getBoolean("show_controls", true));
        view.setFrameskip(prefs.getInt("frameskip", EmuView.FRAMESKIP_AUTO));
    }

    /** Switches the DS 3D graphics between the GPU (OpenGL ES 3) and the CPU renderer. */
    private void applyRenderer() {
        boolean want = prefs.getBoolean("gpu3d", false) && Native.gpuRendererAvailable();
        // The first call always goes to the core, which may still hold a renderer from before.
        if (!rendererApplied || want != gpuActive) {
            rendererApplied = true;
            if (want) {
                // Starting the GPU renderer is the risky moment: remember it in case the app dies here.
                prefs.edit().putBoolean("gpu_boot_pending", true).commit();
                jitGuardTicks = 0;
                jitGuardArmed = true;
            }
            boolean ok = Native.setGpuRenderer(want);
            if (want && !ok) {
                prefs.edit().putBoolean("gpu3d", false).putBoolean("gpu_boot_pending", false).apply();
                toast("Este aparelho não conseguiu iniciar o 3D pela GPU. Usando a CPU.");
                want = false;
            }
            gpuActive = want;
        }
        view.setRendererLabel(gpuActive ? "GPU (OpenGL ES 3)" : "CPU");
    }

    // ------------------------------------------------------------------ EmuView.Listener
    @Override
    public void onMenuRequested() {
        runOnUiThread(this::showMenu);
    }

    @Override
    public void onFramesRun(int totalFrames) {
        // Called from the emulation thread.
        if (jitGuardArmed && ++jitGuardTicks * 64 >= JIT_STABLE_FRAMES) {
            jitGuardArmed = false;
            clearCrashGuards();
        }
    }

    // ------------------------------------------------------------------ menu
    private void showMenu() {
        if (modalCount > 0) return;
        final boolean loaded = coreReady && Native.romLoaded();
        final String last = prefs.getString("last_rom", null);
        final ArrayList<String> labels = new ArrayList<>();
        final ArrayList<Runnable> actions = new ArrayList<>();

        labels.add("Abrir ROM (.nds, .zip, .7z, .rar)");
        actions.add(this::pickRom);
        if (!loaded && last != null && new File(last).exists()) {
            labels.add("Continuar: " + new File(last).getName());
            actions.add(() -> loadRom(last));
        }
        if (loaded) {
            int active = 0;
            for (Cheats.Cheat c : cheats) if (c.enabled) active++;
            labels.add("Cheats" + (active > 0 ? " (" + active + " ativos)" : ""));
            actions.add(this::showCheats);
            labels.add("Salvar estado");
            actions.add(() -> showSlots(true));
            labels.add("Carregar estado");
            actions.add(() -> showSlots(false));
            labels.add(view.isFastForward() ? "Avanço rápido: LIGADO" : "Avanço rápido: desligado");
            actions.add(() -> view.setFastForward(!view.isFastForward()));
            labels.add("Reiniciar jogo");
            actions.add(() -> { applySettings(); armJitGuard(); Native.reset(); });
        }
        labels.add("Resolução interna: " + prefs.getInt("res_scale", 1) + "x");
        actions.add(this::showResolution);
        labels.add("3D desenhado por: " + (gpuActive ? "GPU (experimental)" : "CPU"));
        actions.add(this::showRenderer);
        labels.add("Shader");
        actions.add(this::showShaders);
        labels.add("Layout das telas");
        actions.add(this::showLayouts);
        labels.add("Monitor de desempenho");
        actions.add(this::showHud);
        labels.add("Configurações");
        actions.add(this::showSettings);
        labels.add("Frameskip");
        actions.add(this::showFrameskip);
        labels.add("Sair");
        actions.add(this::finish);

        dialog(new AlertDialog.Builder(this)
                .setTitle("DeSmuME")
                .setItems(labels.toArray(new String[0]), (d, which) -> actions.get(which).run()));
    }

    private void showSettings() {
        final String[] keys = {"jit", "sound", "advanced_timing", "show_controls"};
        final boolean[] defaults = {true, true, true, true};
        final String[] labels = {
                Native.jitAvailable() ? "JIT (recompilador dinâmico) — mais rápido" : "JIT (indisponível neste aparelho)",
                "Som",
                "Timing avançado (mais compatível, mais lento)",
                "Mostrar controles na tela"};
        final boolean[] values = new boolean[keys.length];
        for (int i = 0; i < keys.length; i++) values[i] = prefs.getBoolean(keys[i], defaults[i]);
        final boolean oldJit = values[0];

        dialog(new AlertDialog.Builder(this)
                .setTitle("Configurações")
                .setMultiChoiceItems(labels, values, (d, which, checked) -> values[which] = checked)
                .setPositiveButton("OK", (d, w) -> {
                    SharedPreferences.Editor e = prefs.edit();
                    for (int i = 0; i < keys.length; i++) e.putBoolean(keys[i], values[i]);
                    e.apply();
                    applySettings();
                    if (values[0] != oldJit && Native.romLoaded()) {
                        toast("A mudança do JIT vale a partir de \"Reiniciar jogo\".");
                    }
                })
                .setNegativeButton("Cancelar", null));
    }

    // ------------------------------------------------------------------ cheats
    private void saveCheats() {
        if (romPath == null) return;
        Cheats.save(baseDir, romPath, cheats);
        int accepted = Cheats.apply(cheats);
        int wanted = 0;
        for (Cheats.Cheat c : cheats) if (c.enabled) wanted++;
        if (accepted < wanted) toast((wanted - accepted) + " cheat(s) com código inválido foram ignorados.");
    }

    private void showCheats() {
        final String[] labels = {
                "Ativar / desativar (" + cheats.size() + " salvos)",
                "Buscar no banco de cheats",
                "Adicionar código manualmente",
                "Apagar cheats",
                "Desativar todos"};
        dialog(new AlertDialog.Builder(this)
                .setTitle("Cheats")
                .setItems(labels, (d, which) -> {
                    if (which == 0) view.post(this::showCheatToggles);
                    else if (which == 1) view.post(this::openCheatDatabase);
                    else if (which == 2) view.post(this::showAddCheat);
                    else if (which == 3) view.post(this::showDeleteCheats);
                    else {
                        for (Cheats.Cheat c : cheats) c.enabled = false;
                        saveCheats();
                        toast("Todos os cheats foram desativados.");
                    }
                }));
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
                    toast(Native.cheatsActiveCount() + " cheat(s) ativos.");
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

    private void showAddCheat() {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        col.setPadding(pad, pad / 2, pad, 0);
        final EditText name = new EditText(this);
        name.setHint("Nome do cheat");
        name.setSingleLine(true);
        final EditText code = new EditText(this);
        code.setHint("Código Action Replay\nEx.: 12345678 9ABCDEF0");
        code.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
        code.setMinLines(4);
        col.addView(name);
        col.addView(code);
        dialog(new AlertDialog.Builder(this)
                .setTitle("Adicionar código (Action Replay)")
                .setView(col)
                .setPositiveButton("Adicionar", (d, w) -> {
                    String normalized = Cheats.normalizeCode(code.getText().toString());
                    if (normalized == null) {
                        toast("Código inválido: use pares de números hexadecimais de 8 dígitos.");
                        return;
                    }
                    String n = name.getText().toString().trim();
                    cheats.add(new Cheats.Cheat(n.isEmpty() ? "Cheat " + (cheats.size() + 1) : n, normalized, true));
                    saveCheats();
                    toast("Cheat adicionado e ativado.");
                })
                .setNegativeButton("Cancelar", null));
    }

    /** Opens the bundled database in the background (the first time it is copied out of the APK). */
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
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        col.setPadding(pad, pad / 2, pad, 0);
        final EditText query = new EditText(this);
        query.setSingleLine(true);
        query.setHint("Nome do jogo");
        String initial = Cheats.queryFromFileName(new File(romPath).getName());
        if (initial.replaceAll("[^A-Za-z]", "").length() < 3) initial = Native.romTitle().trim();
        query.setText(initial);
        TextView credit = new TextView(this);
        credit.setText(Cheats.databaseSize() + " jogos · banco libretro-database (CC BY-SA 4.0)");
        credit.setTextSize(11f);
        final ListView results = new ListView(this);
        final List<String> found = new ArrayList<>(Cheats.search(initial, 60));
        final ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, found);
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
                found.addAll(Cheats.search(s.toString(), 60));
                adapter.notifyDataSetChanged();
            }
        });
        final AlertDialog[] box = new AlertDialog[1];
        results.setOnItemClickListener((parent, v, position, id) -> {
            String title = found.get(position);
            box[0].dismiss();
            view.post(() -> showDatabaseGame(title));
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

    private void showDatabaseGame(String title) {
        final List<Cheats.Cheat> list;
        try {
            list = Cheats.read(title);
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
                .setTitle(title)
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

    private void showResolution() {
        final String[] labels = {
                "1x — 256×192 (nativa, mais rápida)",
                "2x — 512×384",
                "3x — 768×576",
                "4x — 1024×768 (bem pesada)"};
        int cur = Math.max(1, Math.min(4, prefs.getInt("res_scale", 1)));
        dialog(new AlertDialog.Builder(this)
                .setTitle("Resolução interna (só melhora gráficos 3D)")
                .setSingleChoiceItems(labels, cur - 1, (d, which) -> {
                    prefs.edit().putInt("res_scale", which + 1).apply();
                    view.setResolutionScale(which + 1);
                    d.dismiss();
                    if (which > 0) toast("Resoluções maiores deixam a emulação mais lenta. Confira o FPS.");
                }));
    }

    private void showRenderer() {
        if (!Native.gpuRendererAvailable()) {
            toast("O 3D pela GPU não está disponível nesta versão.");
            return;
        }
        final String[] labels = {
                "CPU (software) — mais compatível",
                "GPU (OpenGL ES 3) — experimental, melhor em 2x–4x"};
        dialog(new AlertDialog.Builder(this)
                .setTitle("Quem desenha o 3D")
                .setSingleChoiceItems(labels, prefs.getBoolean("gpu3d", false) ? 1 : 0, (d, which) -> {
                    d.dismiss();
                    prefs.edit().putBoolean("gpu3d", which == 1).apply();
                    applyRenderer();
                }));
    }

    private static int next(int[] values, int current) {
        for (int i = 0; i < values.length; i++) {
            if (values[i] == current) return values[(i + 1) % values.length];
        }
        return values[0];
    }

    /** Performance monitor options. Each tap changes one option and reopens the list. */
    private void showHud() {
        final boolean on = prefs.getBoolean("show_fps", true);
        final boolean details = prefs.getBoolean("hud_details", true);
        final int scale = prefs.getInt("hud_scale", 100);
        final int opacity = prefs.getInt("hud_opacity", 80);
        final int corner = prefs.getInt("hud_corner", 0);
        final String[] labels = {
                "Exibir: " + (on ? "ligado" : "desligado"),
                "Detalhes (CPU, GPU, RAM, clocks): " + (details ? "ligado" : "só FPS"),
                "Tamanho: " + scale + "%  (toque para mudar)",
                "Opacidade: " + opacity + "%  (toque para mudar)",
                "Canto: " + EmuView.HUD_CORNERS[Math.max(0, Math.min(3, corner))],
                "Fechar"};
        dialog(new AlertDialog.Builder(this)
                .setTitle("Monitor de desempenho")
                .setItems(labels, (d, which) -> {
                    SharedPreferences.Editor e = prefs.edit();
                    if (which == 0) e.putBoolean("show_fps", !on);
                    else if (which == 1) e.putBoolean("hud_details", !details);
                    else if (which == 2) e.putInt("hud_scale", next(new int[]{70, 85, 100, 125, 150, 200}, scale));
                    else if (which == 3) e.putInt("hud_opacity", next(new int[]{100, 80, 60, 40, 25}, opacity));
                    else if (which == 4) e.putInt("hud_corner", (corner + 1) % 4);
                    e.apply();
                    applySettings();
                    if (which < 5) showHudAgain();
                }));
    }

    private void showHudAgain() {
        // Runs after the current dialog has finished closing.
        view.post(this::showHud);
    }

    private void showShaders() {
        int cur = prefs.getInt("shader", prefs.getBoolean("smooth", false) ? 1 : 0);
        dialog(new AlertDialog.Builder(this)
                .setTitle("Shader")
                .setSingleChoiceItems(Shaders.NAMES, cur, (d, which) -> {
                    prefs.edit().putInt("shader", which).apply();
                    view.setShader(which);
                    d.dismiss();
                }));
    }

    private void showLayouts() {
        final int n = EmuView.LAYOUT_NAMES.length;
        final String[] labels = new String[n + 1];
        System.arraycopy(EmuView.LAYOUT_NAMES, 0, labels, 0, n);
        labels[n] = "Personalizado: mover e redimensionar…";
        int cur = view.getLayoutPreset();
        dialog(new AlertDialog.Builder(this)
                .setTitle("Layout das telas (vale para a orientação atual)")
                .setSingleChoiceItems(labels, cur == EmuView.LAYOUT_CUSTOM ? n : cur, (d, which) -> {
                    d.dismiss();
                    if (which < n) {
                        view.setLayoutPreset(which);
                        if (which >= 4 && prefs.getInt("shader", 0) == 0) {
                            toast("Dica: com a tela esticada, o shader \"Bicúbico\" e a resolução 2x ou mais evitam o serrilhado.");
                        }
                    } else {
                        view.startLayoutEdit();
                    }
                }));
    }

    private void showFrameskip() {
        final String[] labels = {"Automático", "0 (sem pular)", "1", "2", "3", "4"};
        int cur = prefs.getInt("frameskip", EmuView.FRAMESKIP_AUTO);
        dialog(new AlertDialog.Builder(this)
                .setTitle("Frameskip")
                .setSingleChoiceItems(labels, cur + 1, (d, which) -> {
                    prefs.edit().putInt("frameskip", which - 1).apply();
                    view.setFrameskip(which - 1);
                    d.dismiss();
                }));
    }

    private void showSlots(final boolean save) {
        final String[] labels = new String[5];
        for (int i = 0; i < labels.length; i++) {
            File f = stateFile(i);
            labels[i] = "Slot " + (i + 1) + (f.exists()
                    ? " — " + android.text.format.DateFormat.format("dd/MM HH:mm", f.lastModified())
                    : " — vazio");
        }
        dialog(new AlertDialog.Builder(this)
                .setTitle(save ? "Salvar estado" : "Carregar estado")
                .setItems(labels, (d, which) -> {
                    File f = stateFile(which);
                    boolean ok;
                    if (save) {
                        ok = Native.saveState(f.getAbsolutePath());
                    } else {
                        ok = f.exists() && Native.loadState(f.getAbsolutePath());
                    }
                    toast(ok ? (save ? "Estado salvo" : "Estado carregado") : "Não foi possível concluir");
                }));
    }

    private File stateFile(int slot) {
        String name = (romPath != null) ? new File(romPath).getName() : "rom";
        return new File(new File(baseDir, "states"), name + ".ds" + slot);
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

    // ------------------------------------------------------------------ ROM loading
    @SuppressWarnings("deprecation")
    private void pickRom() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        try {
            startActivityForResult(i, REQ_OPEN_ROM);
        } catch (RuntimeException e) {
            toast("Nenhum seletor de arquivos disponível");
        }
    }

    @Override
    @SuppressWarnings("deprecation")
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_OPEN_ROM || resultCode != RESULT_OK || data == null || data.getData() == null) return;
        importRom(data.getData());
    }

    private String displayName(Uri uri) {
        String name = null;
        try (Cursor c = getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) name = c.getString(0);
        } catch (RuntimeException ignored) { }
        if (name == null) name = uri.getLastPathSegment();
        if (name == null) name = "game.nds";
        return name.replaceAll("[\\\\/:*?\"<>|]", "_");
    }

    /**
     * Copies the picked file into the app folder (the core needs a real file path) and, if it is
     * a ZIP, 7z or RAR archive, takes the .nds out of it.
     */
    private void importRom(final Uri uri) {
        final String name = displayName(uri);
        final File romsDir = new File(baseDir, "roms");
        final AlertDialog progress = new AlertDialog.Builder(this)
                .setMessage("Importando " + name + "…").setCancelable(false).create();
        modal(true);
        progress.show();

        new Thread(() -> {
            File rom = null;
            String error = "Não foi possível copiar o arquivo";
            File tmp = new File(romsDir, "import.tmp");
            boolean copied = false;
            try (InputStream in = getContentResolver().openInputStream(uri);
                 OutputStream out = new FileOutputStream(tmp)) {
                if (in != null) {
                    byte[] buf = new byte[1 << 16];
                    int n;
                    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                    copied = true;
                }
            } catch (Exception ignored) { }
            if (copied) {
                try {
                    rom = RomImporter.importFile(tmp, name, romsDir);
                } catch (RomImporter.ImportException e) {
                    error = e.getMessage();
                }
            } else {
                tmp.delete();
            }
            final File result = rom;
            final String message = error;
            runOnUiThread(() -> {
                progress.dismiss();
                if (result != null) {
                    loadRom(result.getAbsolutePath());
                } else {
                    // A dialog, not a toast: the text can be long and the user may need to copy it.
                    dialog(new AlertDialog.Builder(MainActivity.this)
                            .setTitle("Não foi possível abrir")
                            .setMessage(message)
                            .setPositiveButton("OK", null));
                }
                modal(false);
            });
        }, "rom-import").start();
    }

    private void clearCrashGuards() {
        prefs.edit().putBoolean("jit_boot_pending", false).putBoolean("gpu_boot_pending", false).apply();
    }

    private void armJitGuard() {
        boolean jit = prefs.getBoolean("jit", true) && Native.jitAvailable();
        if (jit || gpuActive) {
            // commit(): must hit the disk before native code can crash.
            prefs.edit().putBoolean("jit_boot_pending", jit).putBoolean("gpu_boot_pending", gpuActive).commit();
            jitGuardTicks = 0;
            jitGuardArmed = true;
        }
    }

    private void loadRom(String path) {
        if (!coreReady) return;
        applySettings();
        armJitGuard();
        int r = Native.loadRom(path);
        if (r > 0) {
            romPath = path;
            prefs.edit().putString("last_rom", path).apply();
            view.setFastForward(false);
            cheats = Cheats.load(baseDir, path);
            Cheats.apply(cheats);
        } else {
            jitGuardArmed = false;
            clearCrashGuards();
            toast("Não foi possível carregar esta ROM");
        }
    }

    // ------------------------------------------------------------------ physical controllers / keyboard
    private static int mapKey(int keyCode) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_BUTTON_A: case KeyEvent.KEYCODE_X: return Native.KEY_A;
            case KeyEvent.KEYCODE_BUTTON_B: case KeyEvent.KEYCODE_Z: return Native.KEY_B;
            case KeyEvent.KEYCODE_BUTTON_X: case KeyEvent.KEYCODE_S: return Native.KEY_X;
            case KeyEvent.KEYCODE_BUTTON_Y: case KeyEvent.KEYCODE_A: return Native.KEY_Y;
            case KeyEvent.KEYCODE_BUTTON_L1: case KeyEvent.KEYCODE_Q: return Native.KEY_L;
            case KeyEvent.KEYCODE_BUTTON_R1: case KeyEvent.KEYCODE_W: return Native.KEY_R;
            case KeyEvent.KEYCODE_BUTTON_START: case KeyEvent.KEYCODE_ENTER: return Native.KEY_START;
            case KeyEvent.KEYCODE_BUTTON_SELECT: case KeyEvent.KEYCODE_SPACE: return Native.KEY_SELECT;
            case KeyEvent.KEYCODE_DPAD_UP: return Native.KEY_UP;
            case KeyEvent.KEYCODE_DPAD_DOWN: return Native.KEY_DOWN;
            case KeyEvent.KEYCODE_DPAD_LEFT: return Native.KEY_LEFT;
            case KeyEvent.KEYCODE_DPAD_RIGHT: return Native.KEY_RIGHT;
            default: return 0;
        }
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getKeyCode() == KeyEvent.KEYCODE_BACK) {
            if (event.getAction() == KeyEvent.ACTION_UP) {
                if (view.isEditingLayout()) view.finishLayoutEdit();
                else showMenu();
            }
            return true;
        }
        int k = mapKey(event.getKeyCode());
        if (k != 0) {
            if (event.getAction() == KeyEvent.ACTION_DOWN) view.setPadKeys(view.getPadKeys() | k);
            else if (event.getAction() == KeyEvent.ACTION_UP) view.setPadKeys(view.getPadKeys() & ~k);
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
    }
}
