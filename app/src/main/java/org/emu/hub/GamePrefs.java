package org.emu.hub;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.File;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Settings in two layers: the emulator's general settings, and the settings of the game being
 * played. While a game has "own settings" turned on, every change is stored for that game only and
 * every read looks at the game first. Otherwise this behaves exactly like the general settings.
 *
 * Both emulators already read and write all their options through one SharedPreferences object,
 * so they get per-game settings just by being handed this one instead.
 */
public final class GamePrefs implements SharedPreferences {

    /** Marks a game file as having its own settings. */
    private static final String OWN = "__own";
    private static final String PREFIX = "game_";

    private final Context context;
    private final SharedPreferences global;
    private final String emulator;     // "ds" or "gb": keys differ between the two
    private final Set<String> globalOnly;
    private SharedPreferences game;
    private boolean own;

    /**
     * @param emulator   short name used in the game file names, so the two emulators never mix
     * @param globalOnly keys that always stay general (crash guards, last game, device identity...)
     */
    public GamePrefs(Context context, SharedPreferences global, String emulator, String... globalOnly) {
        this.context = context.getApplicationContext();
        this.global = global;
        this.emulator = emulator;
        this.globalOnly = new HashSet<>();
        for (String k : globalOnly) this.globalOnly.add(k);
    }

    // ------------------------------------------------------------------ which game
    private static String fileName(String emulator, File rom) {
        return PREFIX + emulator + "_" + Library.keyOf(rom);
    }

    /** Switches to the settings of this game (or back to only the general ones with null). */
    public synchronized void setGame(File rom) {
        if (rom == null) {
            game = null;
            own = false;
            return;
        }
        game = context.getSharedPreferences(fileName(emulator, rom), Context.MODE_PRIVATE);
        own = game.getBoolean(OWN, false);
    }

    /** True while changes are being stored for the current game only. */
    public synchronized boolean isOwn() { return game != null && own; }

    /** Turns "own settings" on or off for the current game. Turning it on starts from the general values. */
    public synchronized void setOwn(boolean on) {
        if (game == null) return;
        own = on;
        game.edit().putBoolean(OWN, on).apply();
    }

    /** Forgets every setting of the current game; it goes back to the general settings. */
    public synchronized void resetGame() {
        if (game == null) return;
        game.edit().clear().apply();
        own = false;
    }

    // Static helpers, for screens that are not inside an emulator (the game list).
    private static SharedPreferences[] filesOf(Context c, File rom) {
        return new SharedPreferences[]{
                c.getSharedPreferences(fileName("ds", rom), Context.MODE_PRIVATE),
                c.getSharedPreferences(fileName("gb", rom), Context.MODE_PRIVATE)};
    }

    public static boolean isOwn(Context c, File rom) {
        for (SharedPreferences p : filesOf(c, rom)) if (p.getBoolean(OWN, false)) return true;
        return false;
    }

    public static void setOwn(Context c, File rom, boolean on) {
        String emu = RomInfo.NDS.equals(RomInfo.systemOf(rom)) ? "ds" : "gb";
        c.getSharedPreferences(fileName(emu, rom), Context.MODE_PRIVATE).edit().putBoolean(OWN, on).apply();
    }

    public static void reset(Context c, File rom) {
        for (SharedPreferences p : filesOf(c, rom)) p.edit().clear().apply();
    }

    /** Removes one key from a game's own settings (used by the crash guards). */
    public static void clearKey(Context c, String emulator, File rom, String key) {
        if (rom == null) return;
        c.getSharedPreferences(fileName(emulator, rom), Context.MODE_PRIVATE).edit().remove(key).commit();
    }

    // ------------------------------------------------------------------ reading
    private synchronized SharedPreferences source(String key) {
        if (own && game != null && !globalOnly.contains(key) && game.contains(key)) return game;
        return global;
    }

    private synchronized SharedPreferences target(String key) {
        return (own && game != null && !globalOnly.contains(key)) ? game : global;
    }

    @Override public Map<String, ?> getAll() {
        Map<String, Object> all = new HashMap<>(global.getAll());
        synchronized (this) {
            if (own && game != null) {
                for (Map.Entry<String, ?> e : game.getAll().entrySet()) {
                    if (!globalOnly.contains(e.getKey()) && !OWN.equals(e.getKey())) all.put(e.getKey(), e.getValue());
                }
            }
        }
        return all;
    }

    @Override public String getString(String key, String def) { return source(key).getString(key, def); }
    @Override public Set<String> getStringSet(String key, Set<String> def) { return source(key).getStringSet(key, def); }
    @Override public int getInt(String key, int def) { return source(key).getInt(key, def); }
    @Override public long getLong(String key, long def) { return source(key).getLong(key, def); }
    @Override public float getFloat(String key, float def) { return source(key).getFloat(key, def); }
    @Override public boolean getBoolean(String key, boolean def) { return source(key).getBoolean(key, def); }
    @Override public boolean contains(String key) { return source(key).contains(key); }

    @Override public void registerOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener l) {
        global.registerOnSharedPreferenceChangeListener(l);
    }

    @Override public void unregisterOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener l) {
        global.unregisterOnSharedPreferenceChangeListener(l);
    }

    // ------------------------------------------------------------------ writing
    @Override public Editor edit() { return new Ed(); }

    /** Sends each change to the layer that owns its key. */
    private final class Ed implements Editor {
        private Editor g, p;

        private Editor to(String key) {
            if (target(key) == global) {
                if (g == null) g = global.edit();
                return g;
            }
            if (p == null) p = game.edit();
            return p;
        }

        @Override public Editor putString(String k, String v) { to(k).putString(k, v); return this; }
        @Override public Editor putStringSet(String k, Set<String> v) { to(k).putStringSet(k, v); return this; }
        @Override public Editor putInt(String k, int v) { to(k).putInt(k, v); return this; }
        @Override public Editor putLong(String k, long v) { to(k).putLong(k, v); return this; }
        @Override public Editor putFloat(String k, float v) { to(k).putFloat(k, v); return this; }
        @Override public Editor putBoolean(String k, boolean v) { to(k).putBoolean(k, v); return this; }
        @Override public Editor remove(String k) { to(k).remove(k); return this; }

        /** "Clear" only ever clears the layer being written to, never the other one. */
        @Override public Editor clear() {
            synchronized (GamePrefs.this) {
                if (own && game != null) {
                    if (p == null) p = game.edit();
                    p.clear().putBoolean(OWN, true);
                } else {
                    if (g == null) g = global.edit();
                    g.clear();
                }
            }
            return this;
        }

        @Override public boolean commit() {
            boolean ok = true;
            if (g != null) ok &= g.commit();
            if (p != null) ok &= p.commit();
            return ok;
        }

        @Override public void apply() {
            if (g != null) g.apply();
            if (p != null) p.apply();
        }
    }
}
