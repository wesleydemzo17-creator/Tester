package org.emu.hub;

import android.graphics.Bitmap;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Downloads box art, title screen and in-game screenshot for a game from libretro-thumbnails,
 * the same images RetroArch shows. Matching uses the No-Intro name found in GameDb.
 */
public final class Covers {

    public interface Done { void onDone(Library.Game game, boolean gotSomething); }

    private static final ExecutorService POOL = Executors.newSingleThreadExecutor();
    private static final long RETRY_MS = 7L * 24 * 3600 * 1000;

    private Covers() {}

    private static String repo(String system) {
        switch (system) {
            case RomInfo.NDS: return "Nintendo_-_Nintendo_DS";
            case RomInfo.GBA: return "Nintendo_-_Game_Boy_Advance";
            case RomInfo.GBC: return "Nintendo_-_Game_Boy_Color";
            default: return "Nintendo_-_Game_Boy";
        }
    }

    private static String server(String system) {
        switch (system) {
            case RomInfo.NDS: return "Nintendo - Nintendo DS";
            case RomInfo.GBA: return "Nintendo - Game Boy Advance";
            case RomInfo.GBC: return "Nintendo - Game Boy Color";
            default: return "Nintendo - Game Boy";
        }
    }

    /** libretro-thumbnails replaces these characters in file names. */
    static String fileName(String noIntro) {
        return noIntro.replaceAll("[&*/:`<>?\\\\|\"]", "_");
    }

    private static String enc(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8").replace("+", "%20");
        } catch (java.io.UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Download addresses for one kind of image, best first. */
    static String[] urls(String system, String noIntro, String kind) {
        String file = enc(fileName(noIntro)) + ".png";
        return new String[]{
                "https://raw.githubusercontent.com/libretro-thumbnails/" + repo(system) + "/master/" + kind + "/" + file,
                "https://thumbnails.libretro.com/" + enc(server(system)) + "/" + kind + "/" + file,
        };
    }

    /** True if the game has a name to look up and was not tried recently (or force is set). */
    public static boolean worthTrying(Library.Game g, boolean force) {
        if (g.noIntro == null) return false;
        if (force) return true;
        if (g.cover().exists()) return false;
        File mark = new File(g.dir, "covers.tried");
        return !mark.exists() || System.currentTimeMillis() - mark.lastModified() > RETRY_MS;
    }

    /** Queues the download. done runs on the download thread. */
    public static void fetch(final Library.Game g, final boolean force, final Done done) {
        if (!worthTrying(g, force)) return;
        POOL.execute(() -> {
            boolean any = false;
            any |= get(g, "Named_Boxarts", g.cover(), 768, force);
            any |= get(g, "Named_Titles", g.titleShot(), 512, force);
            any |= get(g, "Named_Snaps", g.snap(), 512, force);
            try {
                new FileOutputStream(new File(g.dir, "covers.tried")).close();
            } catch (IOException ignored) { }
            if (done != null) done.onDone(g, any);
        });
    }

    private static boolean get(Library.Game g, String kind, File dest, int maxSide, boolean force) {
        if (dest.exists() && !force) return false;
        for (String u : urls(g.system, g.noIntro, kind)) {
            File tmp = new File(dest.getPath() + ".part");
            HttpURLConnection c = null;
            try {
                c = (HttpURLConnection) new URL(u).openConnection();
                c.setConnectTimeout(8000);
                c.setReadTimeout(15000);
                c.setRequestProperty("User-Agent", "Desdual");
                if (c.getResponseCode() != 200) continue;
                try (InputStream in = c.getInputStream(); OutputStream out = new FileOutputStream(tmp)) {
                    byte[] buf = new byte[1 << 15];
                    int n;
                    long total = 0;
                    while ((n = in.read(buf)) > 0) {
                        total += n;
                        if (total > 8L * 1024 * 1024) throw new IOException("image too large");
                        out.write(buf, 0, n);
                    }
                }
                // Keep it small: the list only ever shows it at a few hundred pixels.
                Bitmap b = Library.decode(tmp, maxSide);
                tmp.delete();
                if (b == null) continue;
                try (OutputStream out = new FileOutputStream(dest)) {
                    b.compress(Bitmap.CompressFormat.PNG, 100, out);
                }
                return true;
            } catch (IOException e) {
                tmp.delete();
            } finally {
                if (c != null) c.disconnect();
            }
        }
        return false;
    }
}
