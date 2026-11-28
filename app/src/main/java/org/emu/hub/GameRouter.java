package org.emu.hub;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Decides which emulator runs a game, starts it, and keeps the list of recent games. */
public final class GameRouter {

    /** Intent extra with the absolute path of the game to open. */
    public static final String EXTRA_ROM = "org.emu.hub.ROM";

    private static final String PREFS = "hub";
    private static final int MAX_RECENT = 12;

    private GameRouter() {}

    /** True for Nintendo DS games, false for Game Boy / Color / Advance. */
    public static boolean isDs(File rom) {
        return rom.getName().toLowerCase(Locale.ROOT).endsWith(".nds");
    }

    public static String systemName(File rom) {
        String n = rom.getName().toLowerCase(Locale.ROOT);
        if (n.endsWith(".nds")) return "DS";
        if (n.endsWith(".gba")) return "GBA";
        if (n.endsWith(".gbc")) return "GBC";
        return "GB";
    }

    /** Folder shared by both emulators: roms/, saves/, states/, cheats/... */
    public static File baseDir(Context context) {
        File ext = context.getExternalFilesDir(null);
        return (ext != null) ? ext : context.getFilesDir();
    }

    /**
     * Opens the game in the emulator that runs it. When called from inside the other emulator,
     * that one is closed first, so only one core is ever running.
     */
    public static void open(Activity from, File rom) {
        remember(from, rom);
        Class<?> target = isDs(rom) ? org.desmume.android.MainActivity.class : org.mgba.android.MainActivity.class;
        Intent i = new Intent(from, target);
        i.putExtra(EXTRA_ROM, rom.getAbsolutePath());
        i.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        from.startActivity(i);
        if (!(from instanceof LauncherActivity) && from.getClass() != target) from.finish();
    }

    /**
     * Opens a game from a home-screen icon: the emulator starts on its own, without the game list
     * underneath, so leaving the game goes back to the home screen.
     */
    public static void openDirect(Activity from, File rom) {
        remember(from, rom);
        Class<?> target = isDs(rom) ? org.desmume.android.MainActivity.class : org.mgba.android.MainActivity.class;
        Intent i = new Intent(from, target);
        i.putExtra(EXTRA_ROM, rom.getAbsolutePath());
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        from.startActivity(i);
    }

    // ------------------------------------------------------------------ recent games
    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static void remember(Context c, File rom) {
        List<String> list = recent(c);
        list.remove(rom.getAbsolutePath());
        list.add(0, rom.getAbsolutePath());
        while (list.size() > MAX_RECENT) list.remove(list.size() - 1);
        prefs(c).edit().putString("recent", String.join("\n", list)).apply();
    }

    /** Recent games that still exist, newest first. */
    public static List<String> recent(Context c) {
        List<String> out = new ArrayList<>();
        String v = prefs(c).getString("recent", "");
        for (String p : v.split("\n")) {
            if (!p.isEmpty() && new File(p).exists()) out.add(p);
        }
        return out;
    }

    public static void forget(Context c, String path) {
        List<String> list = recent(c);
        list.remove(path);
        prefs(c).edit().putString("recent", String.join("\n", list)).apply();
    }

    public static void clearRecent(Context c) {
        prefs(c).edit().remove("recent").apply();
    }
}
