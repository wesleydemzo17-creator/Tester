package org.emu.hub;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.widget.Toast;

import java.io.File;

/**
 * Target of the home-screen icons. Finds the game by its key and opens it directly in its
 * emulator, which then applies that game's own settings. Shows nothing itself.
 */
public class ShortcutActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        String key = getIntent() != null ? getIntent().getStringExtra(Shortcuts.EXTRA_KEY) : null;
        File rom = Library.findByKey(this, key);
        if (rom == null) {
            Toast.makeText(this, "Este jogo não está mais no Desdual. Abra o app para adicioná-lo de novo.",
                    Toast.LENGTH_LONG).show();
            startActivity(new Intent(this, LauncherActivity.class));
        } else {
            GameRouter.openDirect(this, rom);
        }
        finish();
    }
}
