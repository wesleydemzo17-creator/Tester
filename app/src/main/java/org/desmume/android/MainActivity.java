package org.desmume.android;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;

public class MainActivity extends Activity implements EmuView.Listener {

    private static final int REQ_OPEN_ROM = 1;
    private static final int JIT_BLOCK_SIZE = 12;
    /** Frames that must run before a JIT session counts as stable. */
    private static final int JIT_STABLE_FRAMES = 300;

    private EmuView view;
    private SharedPreferences prefs;
    private File baseDir;
    private String romPath;
    private boolean coreReady;
    private boolean resumed;
    private int modalCount;
    private volatile boolean jitGuardArmed;
    private volatile int jitGuardTicks;

    // ------------------------------------------------------------------ lifecycle
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        prefs = getSharedPreferences("desmume", MODE_PRIVATE);

        File ext = getExternalFilesDir(null);
        baseDir = (ext != null) ? ext : getFilesDir();
        new File(baseDir, "roms").mkdirs();
        new File(baseDir, "states").mkdirs();

        view = new EmuView(this, this);
        setContentView(view);
        applyImmersive();

        // If the previous session died while the JIT was starting up, fall back to the interpreter.
        if (prefs.getBoolean("jit_boot_pending", false)) {
            prefs.edit().putBoolean("jit", false).putBoolean("jit_boot_pending", false).apply();
            toast("O JIT travou na última execução e foi desligado. Reative em Configurações se quiser.");
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
        resumed = true;
        updatePaused();
    }

    @Override
    protected void onPause() {
        resumed = false;
        updatePaused();
        if (coreReady) Native.flushSaves();
        // A clean pause means the JIT did not crash the process.
        if (jitGuardArmed) {
            jitGuardArmed = false;
            prefs.edit().putBoolean("jit_boot_pending", false).apply();
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

    @SuppressWarnings("deprecation")
    private void applyImmersive() {
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
        view.setSmoothing(prefs.getBoolean("smooth", false));
        view.setShowFps(prefs.getBoolean("show_fps", true));
        view.setShowControls(prefs.getBoolean("show_controls", true));
        view.setFrameskip(prefs.getInt("frameskip", EmuView.FRAMESKIP_AUTO));
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
            prefs.edit().putBoolean("jit_boot_pending", false).apply();
        }
    }

    // ------------------------------------------------------------------ menu
    private void showMenu() {
        if (modalCount > 0) return;
        final boolean loaded = coreReady && Native.romLoaded();
        final String last = prefs.getString("last_rom", null);
        final ArrayList<String> labels = new ArrayList<>();
        final ArrayList<Runnable> actions = new ArrayList<>();

        labels.add("Abrir ROM (.nds)");
        actions.add(this::pickRom);
        if (!loaded && last != null && new File(last).exists()) {
            labels.add("Continuar: " + new File(last).getName());
            actions.add(() -> loadRom(last));
        }
        if (loaded) {
            labels.add("Salvar estado");
            actions.add(() -> showSlots(true));
            labels.add("Carregar estado");
            actions.add(() -> showSlots(false));
            labels.add(view.isFastForward() ? "Avanço rápido: LIGADO" : "Avanço rápido: desligado");
            actions.add(() -> view.setFastForward(!view.isFastForward()));
            labels.add("Reiniciar jogo");
            actions.add(() -> { applySettings(); armJitGuard(); Native.reset(); });
        }
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
        final String[] keys = {"jit", "sound", "advanced_timing", "smooth", "show_fps", "show_controls"};
        final boolean[] defaults = {true, true, true, false, true, true};
        final String[] labels = {
                Native.jitAvailable() ? "JIT (recompilador dinâmico) — mais rápido" : "JIT (indisponível neste aparelho)",
                "Som",
                "Timing avançado (mais compatível, mais lento)",
                "Suavizar imagem",
                "Mostrar FPS",
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

    /** Copies the picked file into the app folder (the core needs a real file path). */
    private void importRom(final Uri uri) {
        final String name = displayName(uri);
        if (!name.toLowerCase(java.util.Locale.ROOT).endsWith(".nds")) {
            toast("Escolha um arquivo .nds (descompacte o .zip/.7z antes)");
            return;
        }
        final File dest = new File(new File(baseDir, "roms"), name);
        final AlertDialog progress = new AlertDialog.Builder(this)
                .setMessage("Importando " + name + "…").setCancelable(false).create();
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
                if (success) {
                    loadRom(dest.getAbsolutePath());
                } else {
                    toast("Não foi possível copiar a ROM");
                }
                modal(false);
            });
        }, "rom-import").start();
    }

    private void armJitGuard() {
        if (prefs.getBoolean("jit", true) && Native.jitAvailable()) {
            // commit(): must hit the disk before native code can crash.
            prefs.edit().putBoolean("jit_boot_pending", true).commit();
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
        } else {
            jitGuardArmed = false;
            prefs.edit().putBoolean("jit_boot_pending", false).apply();
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
            if (event.getAction() == KeyEvent.ACTION_UP) showMenu();
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
