package org.emu.hub;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * Game names from libretro-database (assets/gamedb.tsv, built by tools/make_gamedb.py).
 * The names are the No-Intro ones, which are also the file names of the libretro-thumbnails covers.
 */
public final class GameDb {

    private static Map<String, String> names;

    private GameDb() {}

    /** Loads the table once. Safe to call from any thread. */
    public static synchronized void load(InputStream in) throws IOException {
        if (names != null) return;
        Map<String, String> m = new HashMap<>(32768);
        try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8), 1 << 16)) {
            String line;
            while ((line = r.readLine()) != null) {
                int a = line.indexOf('\t'), b = line.indexOf('\t', a + 1);
                if (a < 0 || b < 0) continue;
                m.put(line.substring(0, b), line.substring(b + 1));
            }
        }
        names = m;
    }

    public static synchronized boolean loaded() { return names != null; }

    /** The No-Intro name of a game, or null. Tries the game code first, then the CRC. */
    public static synchronized String lookup(RomInfo info) {
        if (names == null || info == null) return null;
        String[] systems = RomInfo.GBC.equals(info.system) ? new String[]{RomInfo.GBC, RomInfo.GB}
                : RomInfo.GB.equals(info.system) ? new String[]{RomInfo.GB, RomInfo.GBC}
                : new String[]{info.system};
        for (String sys : systems) {
            if (info.crc != null) {
                String n = names.get(sys + "\t" + info.crc);
                if (n != null) return n;
            }
            if (info.serial != null) {
                String n = names.get(sys + "\t" + info.serial);
                if (n != null) return n;
            }
        }
        return null;
    }

    /** "Pokemon - Platinum Version (USA) (Rev 1)" -> "Pokemon - Platinum Version". */
    public static String shortName(String noIntro) {
        int p = noIntro.indexOf(" (");
        return p > 0 ? noIntro.substring(0, p) : noIntro;
    }

    /** "Pokemon - Platinum Version (USA) (Rev 1)" -> "USA · Rev 1". */
    public static String tags(String noIntro) {
        StringBuilder sb = new StringBuilder();
        int i = noIntro.indexOf(" (");
        while (i >= 0) {
            int j = noIntro.indexOf(')', i);
            if (j < 0) break;
            if (sb.length() > 0) sb.append(" · ");
            sb.append(noIntro, i + 2, j);
            i = noIntro.indexOf(" (", j);
        }
        return sb.toString();
    }
}
