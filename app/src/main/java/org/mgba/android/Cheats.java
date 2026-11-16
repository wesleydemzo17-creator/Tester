package org.mgba.android;

import android.content.Context;
import android.content.res.AssetManager;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Cheat support: the list of cheats saved for each game, and the bundled cheat database
 * (libretro-database, CC BY-SA 4.0), one RetroArch .cht file per game inside assets/cheats_gb.zip,
 * under gba/, gbc/ or gb/.
 */
final class Cheats {

    /** One cheat: GameShark, Action Replay, CodeBreaker or Game Genie; the core works out which. */
    static final class Cheat {
        String name;
        String code;   // code words joined with '+'
        boolean enabled;

        Cheat(String name, String code, boolean enabled) {
            this.name = name;
            this.code = code;
            this.enabled = enabled;
        }
    }

    private Cheats() {}

    // ------------------------------------------------------------------ per-game list
    private static File listFile(File baseDir, String romPath) {
        File dir = new File(baseDir, "cheats");
        dir.mkdirs();
        return new File(dir, new File(romPath).getName() + ".txt");
    }

    /** File format: one cheat per line, "1|0 <tab> name <tab> code". */
    static List<Cheat> load(File baseDir, String romPath) {
        List<Cheat> out = new ArrayList<>();
        File f = listFile(baseDir, romPath);
        if (!f.exists()) return out;
        try (BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                String[] p = line.split("\t", 3);
                if (p.length == 3 && !p[2].isEmpty()) out.add(new Cheat(p[1], p[2], "1".equals(p[0])));
            }
        } catch (IOException ignored) {
            // An unreadable list behaves like an empty one.
        }
        return out;
    }

    static void save(File baseDir, String romPath, List<Cheat> list) {
        File f = listFile(baseDir, romPath);
        File tmp = new File(f.getPath() + ".tmp");
        try (Writer w = new OutputStreamWriter(new FileOutputStream(tmp), StandardCharsets.UTF_8)) {
            for (Cheat c : list) {
                w.write((c.enabled ? "1" : "0") + "\t" + c.name.replace('\t', ' ').replace('\n', ' ') + "\t" + c.code + "\n");
            }
        } catch (IOException e) {
            tmp.delete();
            return;
        }
        if (!f.exists() || f.delete()) tmp.renameTo(f);
    }

    /** Sends the enabled cheats to the core. Returns how many the core accepted. */
    static int apply(List<Cheat> list) {
        Native.cheatsClear();
        int ok = 0;
        for (Cheat c : list) {
            if (c.enabled && Native.cheatsAdd(c.name, c.code) > 0) ok++;
        }
        return ok;
    }

    /** Turns typed text into the stored form; null if it holds nothing that looks like a code. */
    static String normalizeCode(String text) {
        List<String> words = new ArrayList<>();
        for (String t : text.toUpperCase(Locale.ROOT).split("[\\s+]+")) {
            if (t.isEmpty()) continue;
            if (!t.matches("[0-9A-F:-]{3,17}")) return null;
            words.add(t);
        }
        if (words.isEmpty()) return null;
        return String.join("+", words);
    }

    // ------------------------------------------------------------------ bundled database
    private static ZipFile db;
    private static List<String> titles;

    /** Opens the database, copying it out of the APK the first time (or after an update). */
    static synchronized void openDatabase(Context context) throws IOException {
        if (db != null) return;
        File dest = new File(context.getFilesDir(), "cheats_gb_db.zip");
        long stamp;
        try {
            stamp = context.getPackageManager().getPackageInfo(context.getPackageName(), 0).lastUpdateTime;
        } catch (Exception e) {
            stamp = 0;
        }
        File stampFile = new File(context.getFilesDir(), "cheats_gb_db.stamp");
        String want = Long.toString(stamp);
        if (!dest.exists() || !want.equals(readSmall(stampFile))) {
            AssetManager am = context.getAssets();
            File tmp = new File(dest.getPath() + ".part");
            try (InputStream in = am.open("cheats_gb.zip"); OutputStream out = new FileOutputStream(tmp)) {
                byte[] buf = new byte[1 << 16];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            } catch (IOException e) {
                tmp.delete();
                throw new IOException("O banco de cheats não está incluído neste APK.", e);
            }
            if (dest.exists()) dest.delete();
            if (!tmp.renameTo(dest)) throw new IOException("Não foi possível preparar o banco de cheats.");
            try (Writer w = new OutputStreamWriter(new FileOutputStream(stampFile), StandardCharsets.UTF_8)) {
                w.write(want);
            }
        }
        ZipFile z = new ZipFile(dest);
        List<String> names = new ArrayList<>();
        Enumeration<? extends ZipEntry> e = z.entries();
        while (e.hasMoreElements()) {
            String n = e.nextElement().getName();
            if (n.endsWith(".cht") && n.indexOf('/') > 0) names.add(n.substring(0, n.length() - 4));
        }
        Collections.sort(names, String.CASE_INSENSITIVE_ORDER);
        titles = names;
        db = z;
    }

    static synchronized int databaseSize() { return titles == null ? 0 : titles.size(); }

    private static String readSmall(File f) {
        if (!f.exists()) return "";
        try (BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8))) {
            String l = r.readLine();
            return l == null ? "" : l;
        } catch (IOException e) {
            return "";
        }
    }

    private static Set<String> words(String s) {
        Set<String> out = new HashSet<>();
        for (String w : s.toLowerCase(Locale.ROOT).replace("é", "e").split("[^a-z0-9]+")) {
            if (!w.isEmpty()) out.add(w);
        }
        return out;
    }

    /** Turns a ROM file name into search words: drops the extension, release numbers and [tags]. */
    static String queryFromFileName(String fileName) {
        String q = fileName.replaceAll("\\.[A-Za-z0-9]{1,4}$", "");
        q = q.replaceAll("\\[[^\\]]*\\]", " ");
        q = q.replaceAll("^\\s*\\d{3,5}\\s*[-_.]\\s*", "");
        q = q.replace('_', ' ').replace('.', ' ');
        return q.replaceAll("\\s+", " ").trim();
    }

    /** The part of a database entry shown to the user ("gba/Name" -> "Name"). */
    static String displayName(String entry) {
        return entry.substring(entry.indexOf('/') + 1);
    }

    /**
     * Database entries that best match the words typed, best first.
     * @param folders which systems to look in, e.g. {"gba/"} or {"gb/", "gbc/"}
     */
    static synchronized List<String> search(String query, String[] folders, int limit) {
        List<String> out = new ArrayList<>();
        if (titles == null) return out;
        List<String> pool = new ArrayList<>();
        for (String t : titles) {
            for (String f : folders) {
                if (t.startsWith(f)) { pool.add(t); break; }
            }
        }
        Set<String> q = words(query);
        if (q.isEmpty()) {
            for (int i = 0; i < pool.size() && out.size() < limit; i++) out.add(pool.get(i));
            return out;
        }
        final List<String> found = new ArrayList<>();
        final List<Integer> scores = new ArrayList<>();
        for (String t : pool) {
            Set<String> tw = words(displayName(t));
            int hit = 0;
            for (String w : q) if (tw.contains(w)) hit++;
            if (hit == 0) continue;
            // More matching words first; then fewer extra words (the plain release before ROM hacks).
            int score = hit * 1000 - Math.min(999, (tw.size() - hit) * 10 + t.length() / 10);
            found.add(t);
            scores.add(score);
        }
        Integer[] idx = new Integer[found.size()];
        for (int i = 0; i < idx.length; i++) idx[i] = i;
        java.util.Arrays.sort(idx, (a, b) -> Integer.compare(scores.get(b), scores.get(a)));
        for (int i = 0; i < idx.length && out.size() < limit; i++) out.add(found.get(idx[i]));
        return out;
    }

    /**
     * Reads the cheats of one database game. Folder entries become a prefix of the cheats under them.
     * Entries without a usable code (such as the master code marker) are skipped.
     */
    static synchronized List<Cheat> read(String title) throws IOException {
        List<Cheat> out = new ArrayList<>();
        if (db == null) return out;
        ZipEntry e = db.getEntry(title + ".cht");
        if (e == null) return out;
        List<String> desc = new ArrayList<>(), code = new ArrayList<>();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(db.getInputStream(e), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                int eq = line.indexOf(" = ");
                if (!line.startsWith("cheat") || eq < 0) continue;
                String key = line.substring(0, eq), value = unquote(line.substring(eq + 3));
                int us = key.indexOf('_');
                if (us < 0) continue;
                int n;
                try {
                    n = Integer.parseInt(key.substring(5, us));
                } catch (NumberFormatException ex) {
                    continue;
                }
                while (desc.size() <= n) { desc.add(""); code.add(""); }
                String field = key.substring(us + 1);
                if (field.equals("desc")) desc.set(n, value);
                else if (field.equals("code")) code.set(n, value);
            }
        }
        String folder = "";
        for (int i = 0; i < desc.size(); i++) {
            String c = code.get(i), d = desc.get(i);
            if (c.isEmpty() || c.equalsIgnoreCase("folder")) {
                folder = d;
                continue;
            }
            String normalized = normalizeCode(c);
            if (normalized == null) continue;
            String name = d.isEmpty() ? "Cheat " + (i + 1) : d;
            if (!folder.isEmpty()) name = folder + " › " + name;
            out.add(new Cheat(name, normalized, false));
        }
        return out;
    }

    private static String unquote(String v) {
        v = v.trim();
        if (v.length() >= 2 && v.startsWith("\"") && v.endsWith("\"")) v = v.substring(1, v.length() - 1);
        return v;
    }
}
