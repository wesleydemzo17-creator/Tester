package org.emu.hub;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
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

/**
 * First screen of the app: open a game of any supported system, or pick a recent one.
 * Nintendo DS games go to DeSmuME, Game Boy / Color / Advance games go to mGBA.
 */
public class LauncherActivity extends Activity {

    private static final int REQ_OPEN = 1;

    private final List<String> recentPaths = new ArrayList<>();
    private ArrayAdapter<String> adapter;
    private TextView emptyText;
    private boolean busy;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        float d = getResources().getDisplayMetrics().density;
        int pad = (int) (16 * d);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(pad, pad * 2, pad, pad);

        TextView title = new TextView(this);
        title.setText("DS + GBA");
        title.setTextSize(28f);
        col.addView(title);

        TextView sub = new TextView(this);
        sub.setText("Nintendo DS com DeSmuME · Game Boy, Color e Advance com mGBA");
        sub.setTextSize(13f);
        sub.setPadding(0, 0, 0, pad);
        col.addView(sub);

        Button open = new Button(this);
        open.setText("Abrir jogo\n.nds  .gba  .gb  .gbc  ·  também dentro de .zip, .7z ou .rar");
        open.setAllCaps(false);
        open.setOnClickListener(v -> pick());
        col.addView(open, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView recentTitle = new TextView(this);
        recentTitle.setText("Jogos recentes (toque para jogar, segure para tirar da lista)");
        recentTitle.setTextSize(13f);
        recentTitle.setPadding(0, pad, 0, pad / 2);
        col.addView(recentTitle);

        emptyText = new TextView(this);
        emptyText.setText("Nenhum jogo aberto ainda.");
        emptyText.setGravity(Gravity.CENTER);
        emptyText.setPadding(0, pad, 0, pad);
        col.addView(emptyText);

        ListView list = new ListView(this);
        adapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, new ArrayList<>());
        list.setAdapter(adapter);
        list.setOnItemClickListener((p, v, pos, id) -> GameRouter.open(this, new File(recentPaths.get(pos))));
        list.setOnItemLongClickListener((p, v, pos, id) -> {
            GameRouter.forget(this, recentPaths.get(pos));
            refresh();
            return true;
        });
        col.addView(list, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        TextView about = new TextView(this);
        about.setText("Saves, estados e cheats ficam em Android/data/" + getPackageName() + "/files.\n"
                + "DeSmuME (GPL v2+) e mGBA (MPL 2.0). Cheats: libretro-database (CC BY-SA 4.0).");
        about.setTextSize(11f);
        about.setPadding(0, pad / 2, 0, 0);
        col.addView(about);

        setContentView(col);
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    private void refresh() {
        recentPaths.clear();
        recentPaths.addAll(GameRouter.recent(this));
        adapter.clear();
        for (String p : recentPaths) {
            File f = new File(p);
            adapter.add("[" + GameRouter.systemName(f) + "]  " + f.getName());
        }
        adapter.notifyDataSetChanged();
        emptyText.setVisibility(recentPaths.isEmpty() ? android.view.View.VISIBLE : android.view.View.GONE);
    }

    @SuppressWarnings("deprecation")
    private void pick() {
        if (busy) return;
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        try {
            startActivityForResult(i, REQ_OPEN);
        } catch (RuntimeException e) {
            toast("Nenhum seletor de arquivos disponível");
        }
    }

    @Override
    @SuppressWarnings("deprecation")
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_OPEN || resultCode != RESULT_OK || data == null || data.getData() == null) return;
        importAndOpen(data.getData());
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

    /** Copies the picked file into the app folder, takes the game out of an archive, then opens it. */
    private void importAndOpen(final Uri uri) {
        final String name = displayName(uri);
        final File romsDir = new File(GameRouter.baseDir(this), "roms");
        romsDir.mkdirs();
        final AlertDialog progress = new AlertDialog.Builder(this)
                .setMessage("Importando " + name + "…").setCancelable(false).create();
        busy = true;
        progress.show();
        new Thread(() -> {
            File rom = null;
            String error = "Não foi possível copiar o arquivo.";
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
                busy = false;
                if (result != null) {
                    GameRouter.open(this, result);
                } else {
                    new AlertDialog.Builder(this).setTitle("Não foi possível abrir")
                            .setMessage(message).setPositiveButton("OK", null).show();
                }
            });
        }, "import").start();
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
    }
}
