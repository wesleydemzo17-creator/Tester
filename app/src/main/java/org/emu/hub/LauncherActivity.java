package org.emu.hub;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.text.InputType;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Desdual's home: the XMB-style game list. Nintendo DS games run in DeSmuME, Game Boy, Color and
 * Advance games in mGBA; this screen finds the games, their names, icons, covers and previews.
 */
public class LauncherActivity extends Activity implements XmbView.Listener {

    private static final int REQ_ADD = 1, REQ_COVER = 2, REQ_BACKGROUND = 3;

    private XmbView xmb;
    private XmbTheme theme;
    private List<Library.Game> games = new ArrayList<>();
    private Library.Game coverTarget;
    private boolean busy, scanning;

    // ------------------------------------------------------------------ lifecycle
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        useWholeDisplay();
        xmb = new XmbView(this);
        xmb.setListener(this);
        theme = XmbTheme.load(this);
        xmb.setTheme(theme);
        setContentView(xmb);
        xmb.requestFocus();
        buildMenu(); // settings first, the games appear when the scan finishes
    }

    private boolean resumedOnce;

    @Override
    protected void onResume() {
        super.onResume();
        applyImmersive();
        rescan();
        if (resumedOnce) {
            // Back from a game: its new preview is being written right now; show it once it is there.
            xmb.postDelayed(() -> xmb.refreshImages(null), 1500);
        }
        resumedOnce = true;
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
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
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
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    }

    // ------------------------------------------------------------------ library
    /** Reads the roms folder in the background, then rebuilds the menu and fetches missing covers. */
    private void rescan() {
        if (scanning) return;
        scanning = true;
        new Thread(() -> {
            final List<Library.Game> found = Library.scan(this);
            runOnUiThread(() -> {
                scanning = false;
                games = found;
                buildMenu();
                if (theme.autoCovers) fetchCovers(false);
            });
        }, "scan").start();
    }

    private void fetchCovers(boolean force) {
        for (Library.Game g : games) {
            Covers.fetch(g, force, (game, got) -> {
                if (got) runOnUiThread(() -> xmb.refreshImages(game));
            });
        }
    }

    private void buildMenu() {
        List<Library.Game> sorted = new ArrayList<>(games);
        if (theme.sort == 1) {
            java.util.Collections.sort(sorted, (a, b) -> Long.compare(b.lastPlayed, a.lastPlayed));
        }
        XmbView.Category settings = new XmbView.Category("Personalizar", XmbView.ICON_SETTINGS);
        XmbView.Category recent = new XmbView.Category("Recentes", XmbView.ICON_RECENT);
        XmbView.Category ds = new XmbView.Category("Nintendo DS", XmbView.ICON_DS);
        XmbView.Category gba = new XmbView.Category("Game Boy Advance", XmbView.ICON_GBA);
        XmbView.Category gb = new XmbView.Category("Game Boy / Color", XmbView.ICON_GB);

        List<Library.Game> byRecent = new ArrayList<>();
        for (Library.Game g : games) if (g.lastPlayed > 0) byRecent.add(g);
        java.util.Collections.sort(byRecent, (a, b) -> Long.compare(b.lastPlayed, a.lastPlayed));
        for (Library.Game g : byRecent) recent.items.add(new XmbView.Item(g));
        for (Library.Game g : sorted) {
            XmbView.Category target = RomInfo.NDS.equals(g.system) ? ds : RomInfo.GBA.equals(g.system) ? gba : gb;
            target.items.add(new XmbView.Item(g));
        }
        for (XmbView.Category c : new XmbView.Category[]{ds, gba, gb}) {
            if (c.items.isEmpty()) c.items.add(new XmbView.Item("Adicionar jogos", "", this::pickGames));
        }
        if (recent.items.isEmpty()) recent.items.add(new XmbView.Item("Adicionar jogos", "", this::pickGames));
        addSettings(settings);

        List<XmbView.Category> list = new ArrayList<>();
        list.add(settings);
        list.add(recent);
        list.add(ds);
        list.add(gba);
        list.add(gb);
        xmb.setCategories(list, byRecent.isEmpty() ? 2 : 1);
    }

    // ------------------------------------------------------------------ customisation
    private SharedPreferences xp() { return XmbTheme.prefs(this); }

    private void themeChanged() {
        theme = XmbTheme.load(this);
        xmb.setTheme(theme);
        buildMenu();
    }

    private String yesNo(boolean v) { return v ? "Sim" : "Não"; }

    private void addSettings(XmbView.Category s) {
        s.items.add(new XmbView.Item("Adicionar jogos", ".nds .gba .gb .gbc · .zip .7z .rar", this::pickGames));
        s.items.add(new XmbView.Item("Tema de cor", XmbTheme.COLOR_NAMES[theme.colorIndex], () ->
                choose("Tema de cor", XmbTheme.COLOR_NAMES, theme.colorIndex, "color")));
        s.items.add(new XmbView.Item("Fundo", XmbTheme.BACKGROUND_NAMES[theme.background], () ->
                choose("Fundo", XmbTheme.BACKGROUND_NAMES, theme.background, "background")));
        s.items.add(new XmbView.Item("Escolher imagem de fundo", "", () -> pickImage(REQ_BACKGROUND)));
        s.items.add(new XmbView.Item("Capa do jogo como fundo", yesNo(theme.coverBackground), () -> toggle("cover_bg", true)));
        s.items.add(new XmbView.Item("Prévia em movimento", yesNo(theme.moving), () -> toggle("moving_preview", true)));
        s.items.add(new XmbView.Item("Escurecer o fundo", theme.dim + "%", () -> cycle("dim", XmbTheme.DIM_LEVELS, theme.dim)));
        s.items.add(new XmbView.Item("Tamanho dos ícones", theme.iconScale + "%", () ->
                cycle("icon_scale", XmbTheme.ICON_SCALES, theme.iconScale)));
        s.items.add(new XmbView.Item("Velocidade da animação", XmbTheme.SPEED_NAMES[theme.speed], () ->
                choose("Velocidade da animação", XmbTheme.SPEED_NAMES, theme.speed, "speed")));
        s.items.add(new XmbView.Item("Relógio", yesNo(theme.clock), () -> toggle("clock", true)));
        s.items.add(new XmbView.Item("Ordem dos jogos", XmbTheme.SORT_NAMES[theme.sort], () ->
                choose("Ordem dos jogos", XmbTheme.SORT_NAMES, theme.sort, "sort")));
        s.items.add(new XmbView.Item("Baixar capas automaticamente", yesNo(theme.autoCovers), () -> toggle("auto_covers", true)));
        s.items.add(new XmbView.Item("Baixar capas que faltam agora", "", () -> {
            fetchCovers(true);
            toast("Procurando capas para " + games.size() + " jogo(s)…");
        }));
        s.items.add(new XmbView.Item("Sobre o Desdual", "", this::about));
    }

    private void toggle(String key, boolean def) {
        xp().edit().putBoolean(key, !xp().getBoolean(key, def)).apply();
        themeChanged();
    }

    private void cycle(String key, int[] values, int current) {
        int next = values[0];
        for (int i = 0; i < values.length; i++) if (values[i] == current) next = values[(i + 1) % values.length];
        xp().edit().putInt(key, next).apply();
        themeChanged();
    }

    private void choose(String title, String[] names, int current, final String key) {
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setSingleChoiceItems(names, current, (d, which) -> {
                    d.dismiss();
                    xp().edit().putInt(key, which).apply();
                    themeChanged();
                    if (key.equals("background") && which == XmbTheme.BG_IMAGE
                            && !new File(getFilesDir(), "xmb_background.png").exists()) {
                        pickImage(REQ_BACKGROUND);
                    }
                })
                .show();
    }

    private void about() {
        new AlertDialog.Builder(this)
                .setTitle("Desdual")
                .setMessage("Nintendo DS com o DeSmuME (GPL v2+) e Game Boy, Color e Advance com o mGBA (MPL 2.0).\n\n"
                        + "Nomes dos jogos e cheats: libretro-database (CC BY-SA 4.0). Capas, telas de título e "
                        + "capturas são baixadas do libretro-thumbnails quando você usa essa opção.\n\n"
                        + "A prévia em movimento é montada com quadros do seu próprio jogo: aparece depois que você "
                        + "joga alguns minutos.\n\n"
                        + "Os arquivos ficam em Android/data/" + getPackageName() + "/files.")
                .setPositiveButton("OK", null)
                .show();
    }

    // ------------------------------------------------------------------ XmbView.Listener
    @Override
    public void onActivate(XmbView.Item item) {
        if (item.game != null) GameRouter.open(this, item.game.rom);
        else if (item.action != null) item.action.run();
    }

    @Override
    public void onOptions(XmbView.Item item) {
        if (item.game == null) {
            if (item.action != null) item.action.run();
            return;
        }
        final Library.Game g = item.game;
        final boolean own = GamePrefs.isOwn(this, g.rom);
        final List<String> labels = new ArrayList<>();
        final List<Runnable> actions = new ArrayList<>();
        labels.add("Jogar");
        actions.add(() -> GameRouter.open(this, g.rom));
        labels.add("Criar ícone na tela inicial");
        actions.add(() -> {
            if (!Shortcuts.pin(this, g)) toast("Seu Android não permite criar atalhos por aqui.");
        });
        labels.add(own ? "Configurações próprias: ligadas (desligar)" : "Usar configurações próprias neste jogo");
        actions.add(() -> {
            GamePrefs.setOwn(this, g.rom, !own);
            toast(!own ? "Ligado. Abra o jogo e mude o que quiser no MENU: vale só para ele."
                    : "Este jogo voltou a usar as configurações gerais.");
            rescan();
        });
        if (own) {
            labels.add("Apagar as configurações próprias");
            actions.add(() -> {
                GamePrefs.reset(this, g.rom);
                rescan();
            });
        }
        labels.add("Escolher capa da galeria");
        actions.add(() -> {
            coverTarget = g;
            pickImage(REQ_COVER);
        });
        if (g.userCover().exists()) {
            labels.add("Voltar para a capa automática");
            actions.add(() -> {
                g.userCover().delete();
                xmb.refreshImages(g);
                rescan();
            });
        }
        if (g.noIntro != null) {
            labels.add("Baixar capa e telas de novo");
            actions.add(() -> {
                Covers.fetch(g, true, (game, got) -> runOnUiThread(() -> {
                    toast(got ? "Imagens atualizadas." : "Nenhuma imagem encontrada para este jogo.");
                    xmb.refreshImages(game);
                }));
            });
        }
        labels.add("Apagar a prévia gravada");
        actions.add(() -> {
            Previews.clear(this, g.rom);
            xmb.refreshImages(g);
        });
        labels.add("Renomear");
        actions.add(() -> rename(g));
        labels.add("Apagar jogo do aparelho");
        actions.add(() -> new AlertDialog.Builder(this)
                .setMessage("Apagar \"" + g.title + "\" do aparelho? Os saves e estados salvos continuam guardados.")
                .setPositiveButton("Apagar", (d, w) -> {
                    Library.delete(this, g.rom);
                    rescan();
                })
                .setNegativeButton("Cancelar", null)
                .show());
        new AlertDialog.Builder(this)
                .setTitle(g.title)
                .setItems(labels.toArray(new String[0]), (d, which) -> actions.get(which).run())
                .show();
    }

    private void rename(final Library.Game g) {
        final EditText field = new EditText(this);
        field.setSingleLine(true);
        field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        field.setText(g.title);
        new AlertDialog.Builder(this)
                .setTitle("Nome na lista (vazio = automático)")
                .setView(field)
                .setPositiveButton("OK", (d, w) -> {
                    Library.setCustomTitle(this, g.rom, field.getText().toString());
                    rescan();
                })
                .setNegativeButton("Cancelar", null)
                .show();
    }

    // ------------------------------------------------------------------ files
    @SuppressWarnings("deprecation")
    private void pickGames() {
        if (busy) return;
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        try {
            startActivityForResult(i, REQ_ADD);
        } catch (RuntimeException e) {
            toast("Nenhum seletor de arquivos disponível");
        }
    }

    @SuppressWarnings("deprecation")
    private void pickImage(int request) {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("image/*");
        try {
            startActivityForResult(i, request);
        } catch (RuntimeException e) {
            toast("Nenhum seletor de imagens disponível");
        }
    }

    @Override
    @SuppressWarnings("deprecation")
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null) return;
        if (requestCode == REQ_ADD) {
            List<Uri> uris = new ArrayList<>();
            ClipData clip = data.getClipData();
            if (clip != null) for (int i = 0; i < clip.getItemCount(); i++) uris.add(clip.getItemAt(i).getUri());
            else if (data.getData() != null) uris.add(data.getData());
            if (!uris.isEmpty()) importGames(uris);
        } else if (requestCode == REQ_COVER && data.getData() != null && coverTarget != null) {
            final Library.Game g = coverTarget;
            try (InputStream in = getContentResolver().openInputStream(data.getData())) {
                if (in != null && Library.setUserCover(this, g.rom, in)) {
                    xmb.refreshImages(g);
                    rescan();
                } else {
                    toast("Não foi possível usar esta imagem.");
                }
            } catch (Exception e) {
                toast("Não foi possível usar esta imagem.");
            }
        } else if (requestCode == REQ_BACKGROUND && data.getData() != null) {
            File dest = new File(getFilesDir(), "xmb_background.png");
            try (InputStream in = getContentResolver().openInputStream(data.getData());
                 OutputStream out = new FileOutputStream(dest)) {
                byte[] buf = new byte[1 << 16];
                int n;
                while (in != null && (n = in.read(buf)) > 0) out.write(buf, 0, n);
            } catch (Exception e) {
                toast("Não foi possível usar esta imagem.");
                return;
            }
            xp().edit().putInt("background", XmbTheme.BG_IMAGE).apply();
            xmb.backgroundImageChanged();
            themeChanged();
        }
    }

    private String displayName(Uri uri) {
        String name = null;
        try (Cursor c = getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) name = c.getString(0);
        } catch (RuntimeException ignored) { }
        if (name == null) name = uri.getLastPathSegment();
        if (name == null) name = "game";
        return name.replaceAll("[\\\\/:*?\"<>|]", "_");
    }

    /** Copies each picked file into the roms folder, taking the game out of archives. */
    private void importGames(final List<Uri> uris) {
        final File romsDir = Library.romsDir(this);
        final AlertDialog progress = new AlertDialog.Builder(this)
                .setMessage("Importando " + uris.size() + " arquivo(s)…").setCancelable(false).create();
        busy = true;
        progress.show();
        new Thread(() -> {
            int ok = 0;
            final StringBuilder errors = new StringBuilder();
            File last = null;
            for (Uri uri : uris) {
                String name = displayName(uri);
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
                if (!copied) {
                    tmp.delete();
                    errors.append("\n• ").append(name).append(": não foi possível copiar");
                    continue;
                }
                try {
                    last = RomImporter.importFile(tmp, name, romsDir);
                    ok++;
                } catch (RomImporter.ImportException e) {
                    errors.append("\n• ").append(name).append(": ").append(e.getMessage());
                }
            }
            final int imported = ok;
            final File single = (uris.size() == 1) ? last : null;
            runOnUiThread(() -> {
                progress.dismiss();
                busy = false;
                if (errors.length() > 0) {
                    new AlertDialog.Builder(this)
                            .setTitle(imported + " jogo(s) importado(s)")
                            .setMessage("Não foi possível importar:" + errors)
                            .setPositiveButton("OK", null)
                            .show();
                } else if (single != null) {
                    // One game picked: open it right away.
                    GameRouter.open(this, single);
                } else {
                    toast(imported + " jogo(s) adicionados.");
                }
                rescan();
            });
        }, "import").start();
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
    }
}
