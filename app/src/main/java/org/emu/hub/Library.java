package org.emu.hub;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.zip.CRC32;

/**
 * The game library: every game in the roms folder, with what the app knows about it. Each game has
 * a folder under library/ holding meta.properties, icon.png, cover.png, title.png, snap.png and the
 * preview frames (preview_00.jpg ...).
 */
public final class Library {

    public static final class Game {
        public File rom;
        public String key;
        public String system;       // RomInfo.NDS / GBA / GBC / GB
        public String title;        // shown in the list
        public String subtitle;     // region, revision...
        public String noIntro;      // libretro-database name, or null
        public long lastPlayed;     // 0 = never
        public boolean ownSettings; // has its own emulator settings
        public File dir;

        public File icon() { return new File(dir, "icon.png"); }
        public File userCover() { return new File(dir, "cover_user.png"); }
        public File cover() { return new File(dir, "cover.png"); }
        public File titleShot() { return new File(dir, "title.png"); }
        public File snap() { return new File(dir, "snap.png"); }

        /** The cover to show: the one the user chose, else the downloaded one, else null. */
        public File bestCover() {
            if (userCover().exists()) return userCover();
            return cover().exists() ? cover() : null;
        }

        /** Frames for the moving preview: recorded gameplay first, then downloaded screenshots. */
        public List<File> previewFrames() {
            List<File> out = new ArrayList<>();
            File[] files = dir.listFiles((d, n) -> n.startsWith("preview_") && n.endsWith(".jpg"));
            if (files != null) {
                Arrays.sort(files);
                out.addAll(Arrays.asList(files));
            }
            if (out.isEmpty()) {
                if (titleShot().exists()) out.add(titleShot());
                if (snap().exists()) out.add(snap());
            }
            return out;
        }

        public String systemLabel() {
            switch (system) {
                case RomInfo.NDS: return "Nintendo DS";
                case RomInfo.GBA: return "Game Boy Advance";
                case RomInfo.GBC: return "Game Boy Color";
                default: return "Game Boy";
            }
        }
    }

    private Library() {}

    public static File romsDir(Context c) {
        File d = new File(GameRouter.baseDir(c), "roms");
        d.mkdirs();
        return d;
    }

    public static File libraryDir(Context c) {
        File d = new File(GameRouter.baseDir(c), "library");
        d.mkdirs();
        return d;
    }

    /** Stable, file-name-safe key for a game: its file name, cleaned, plus a short hash. */
    public static String keyOf(File rom) {
        String n = rom.getName();
        String clean = n.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "_");
        if (clean.length() > 60) clean = clean.substring(0, 60);
        CRC32 crc = new CRC32();
        crc.update(n.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return clean + "_" + String.format(Locale.ROOT, "%08x", crc.getValue());
    }

    public static File dirOf(Context c, File rom) {
        File d = new File(libraryDir(c), keyOf(rom));
        d.mkdirs();
        return d;
    }

    /** Finds a game in the roms folder by key, or null. */
    public static File findByKey(Context c, String key) {
        File[] files = romsDir(c).listFiles();
        if (files == null || key == null) return null;
        for (File f : files) {
            if (RomImporter.isRomName(f.getName()) && keyOf(f).equals(key)) return f;
        }
        return null;
    }

    /** Opens the name table bundled in the APK (only the first call does any work). */
    public static void loadDb(Context c) {
        if (GameDb.loaded()) return;
        try (InputStream in = c.getAssets().open("gamedb.tsv")) {
            GameDb.load(in);
        } catch (IOException ignored) {
            // Without the table, games keep the name from their header or file.
        }
    }

    /**
     * Lists every game. Reading a header and writing the icon happen once per game; later calls only
     * read meta.properties. Can take a while the first time: call it off the UI thread.
     */
    public static List<Game> scan(Context c) {
        loadDb(c);
        List<Game> out = new ArrayList<>();
        File[] files = romsDir(c).listFiles();
        if (files == null) return out;
        List<String> recent = GameRouter.recent(c);
        for (File f : files) {
            if (!f.isFile() || !RomImporter.isRomName(f.getName())) continue;
            Game g = describe(c, f);
            int r = recent.indexOf(f.getAbsolutePath());
            g.lastPlayed = r < 0 ? 0 : recent.size() - r;
            out.add(g);
        }
        Collections.sort(out, (a, b) -> a.title.compareToIgnoreCase(b.title));
        return out;
    }

    public static Game describe(Context c, File f) {
        Game g = new Game();
        g.rom = f;
        g.key = keyOf(f);
        g.dir = dirOf(c, f);
        File metaFile = new File(g.dir, "meta.properties");
        Properties meta = new Properties();
        boolean fresh = false;
        if (metaFile.exists()) {
            try (InputStream in = new FileInputStream(metaFile)) {
                meta.load(in);
            } catch (IOException ignored) { }
        }
        String stamp = f.length() + ":" + f.lastModified();
        if (!stamp.equals(meta.getProperty("stamp")) || !"3".equals(meta.getProperty("v"))) {
            meta.clear();
            RomInfo info = RomInfo.read(f);
            String name = GameDb.lookup(info);
            meta.setProperty("v", "3");
            meta.setProperty("stamp", stamp);
            meta.setProperty("system", info.system);
            if (info.serial != null) meta.setProperty("serial", info.serial);
            if (name != null) meta.setProperty("nointro", name);
            if (info.headerTitle != null) meta.setProperty("header", info.headerTitle);
            if (info.icon != null) saveIcon(info.icon, g.icon());
            fresh = true;
        }
        g.system = meta.getProperty("system", RomInfo.systemOf(f));
        g.noIntro = meta.getProperty("nointro");
        String custom = meta.getProperty("custom_title");
        String base = f.getName().replaceAll("\\.[A-Za-z0-9]{1,4}$", "");
        if (custom != null && !custom.isEmpty()) {
            g.title = custom;
            g.subtitle = g.noIntro != null ? GameDb.tags(g.noIntro) : "";
        } else if (g.noIntro != null) {
            g.title = GameDb.shortName(g.noIntro);
            g.subtitle = GameDb.tags(g.noIntro);
        } else {
            String header = meta.getProperty("header");
            g.title = (header != null && RomInfo.NDS.equals(g.system)) ? header : base;
            g.subtitle = "";
        }
        if (fresh) saveMeta(metaFile, meta);
        g.ownSettings = GamePrefs.isOwn(c, g.rom);
        return g;
    }

    /** Lets the user rename a game in the list. Empty text goes back to the automatic name. */
    public static void setCustomTitle(Context c, File rom, String title) {
        File metaFile = new File(dirOf(c, rom), "meta.properties");
        Properties meta = new Properties();
        try (InputStream in = new FileInputStream(metaFile)) {
            meta.load(in);
        } catch (IOException ignored) { }
        if (title == null || title.trim().isEmpty()) meta.remove("custom_title");
        else meta.setProperty("custom_title", title.trim());
        saveMeta(metaFile, meta);
    }

    private static void saveMeta(File f, Properties p) {
        try (OutputStream out = new FileOutputStream(f)) {
            p.store(out, null);
        } catch (IOException ignored) { }
    }

    private static void saveIcon(int[] px, File dest) {
        Bitmap b = Bitmap.createBitmap(px, 32, 32, Bitmap.Config.ARGB_8888);
        try (OutputStream out = new FileOutputStream(dest)) {
            b.compress(Bitmap.CompressFormat.PNG, 100, out);
        } catch (IOException ignored) { }
    }

    /** Decodes an image, shrinking it on the way in so it is at most about maxSide pixels on its long side. */
    public static Bitmap decode(File f, int maxSide) {
        if (f == null || !f.exists()) return null;
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(f.getPath(), o);
        int side = Math.max(o.outWidth, o.outHeight), sample = 1;
        while (side / (sample * 2) >= maxSide) sample *= 2;
        o = new BitmapFactory.Options();
        o.inSampleSize = sample;
        return BitmapFactory.decodeFile(f.getPath(), o);
    }

    /** Stores a picture the user picked as the game's cover (scaled to at most 768 pixels). */
    public static boolean setUserCover(Context c, File rom, InputStream in) throws IOException {
        File dir = dirOf(c, rom), tmp = new File(dir, "cover_user.tmp");
        try (OutputStream out = new FileOutputStream(tmp)) {
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        }
        Bitmap b = decode(tmp, 768);
        tmp.delete();
        if (b == null) return false;
        try (OutputStream out = new FileOutputStream(new File(dir, "cover_user.png"))) {
            b.compress(Bitmap.CompressFormat.PNG, 100, out);
        }
        return true;
    }

    /** Deletes the game file and everything the app keeps about it (saves and states stay). */
    public static void delete(Context c, File rom) {
        File dir = dirOf(c, rom);
        File[] files = dir.listFiles();
        if (files != null) for (File f : files) f.delete();
        dir.delete();
        rom.delete();
        GameRouter.forget(c, rom.getAbsolutePath());
        GamePrefs.reset(c, rom);
    }

    /** Remembers the last time each game was opened, for the "recent" order. */
    public static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("library", Context.MODE_PRIVATE);
    }
}
