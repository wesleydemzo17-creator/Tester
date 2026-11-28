package org.emu.hub;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.zip.CRC32;

/**
 * What can be read from a ROM file itself: the system, the game code, the title stored in the
 * header and, for Nintendo DS games, the 32x32 icon from the banner. Plain Java on purpose, so it
 * can be tested off the phone.
 */
public final class RomInfo {

    public static final String NDS = "nds", GBA = "gba", GBC = "gbc", GB = "gb";

    /** One of NDS, GBA, GBC, GB. */
    public String system;
    /** 4-character game code from the header, or null. */
    public String serial;
    /** CRC32 of the whole file as 8 upper-case hex digits, or null when not computed. */
    public String crc;
    /** Title from the header (DS banner English title, or the short header title), or null. */
    public String headerTitle;
    /** DS banner icon, 32x32 ARGB pixels, or null. */
    public int[] icon;

    private RomInfo() {}

    public static String systemOf(File f) {
        String n = f.getName().toLowerCase(Locale.ROOT);
        if (n.endsWith(".nds")) return NDS;
        if (n.endsWith(".gba")) return GBA;
        if (n.endsWith(".gbc")) return GBC;
        return GB;
    }

    /** Reads the header. Never throws: whatever cannot be read is left null. */
    public static RomInfo read(File f) {
        RomInfo info = new RomInfo();
        info.system = systemOf(f);
        try (RandomAccessFile raf = new RandomAccessFile(f, "r")) {
            if (NDS.equals(info.system)) readNds(raf, info);
            else if (GBA.equals(info.system)) readGba(raf, info);
            else readGb(raf, info);
        } catch (IOException ignored) {
            // A damaged header only means less information.
        }
        // Game Boy games have no game code; small ROMs are identified by their CRC instead.
        if ((GB.equals(info.system) || GBC.equals(info.system) || (GBA.equals(info.system) && info.serial == null))
                && f.length() <= 64L * 1024 * 1024) {
            info.crc = crc32(f);
        }
        return info;
    }

    private static byte[] readAt(RandomAccessFile raf, long offset, int length) throws IOException {
        if (offset < 0 || offset + length > raf.length()) return null;
        byte[] b = new byte[length];
        raf.seek(offset);
        raf.readFully(b);
        return b;
    }

    private static int u16(byte[] b, int o) { return (b[o] & 0xFF) | ((b[o + 1] & 0xFF) << 8); }

    private static long u32(byte[] b, int o) {
        return (b[o] & 0xFFL) | ((b[o + 1] & 0xFFL) << 8) | ((b[o + 2] & 0xFFL) << 16) | ((b[o + 3] & 0xFFL) << 24);
    }

    /** Keeps the code only if it looks like one (letters and digits). */
    private static String code(byte[] b, int o) {
        String s = new String(b, o, 4, StandardCharsets.US_ASCII);
        return s.matches("[0-9A-Z]{4}") ? s : null;
    }

    private static String ascii(byte[] b, int o, int len) {
        StringBuilder sb = new StringBuilder();
        for (int i = o; i < o + len; i++) {
            int c = b[i] & 0xFF;
            if (c == 0) break;
            sb.append(c >= 0x20 && c < 0x7F ? (char) c : ' ');
        }
        String s = sb.toString().trim();
        return s.isEmpty() ? null : s;
    }

    private static void readNds(RandomAccessFile raf, RomInfo info) throws IOException {
        byte[] h = readAt(raf, 0, 0x70);
        if (h == null) return;
        info.serial = code(h, 0x0C);
        info.headerTitle = ascii(h, 0, 12);
        long banner = u32(h, 0x68);
        if (banner == 0) return;
        byte[] b = readAt(raf, banner, 0x440);
        if (b == null) return;

        // Icon: 4x4 tiles of 8x8 pixels, 4 bits per pixel; palette of 16 BGR555 colours, 0 = transparent.
        int[] pal = new int[16];
        for (int i = 0; i < 16; i++) {
            int c = u16(b, 0x220 + i * 2);
            int r = (c & 0x1F) * 255 / 31, g = ((c >> 5) & 0x1F) * 255 / 31, bl = ((c >> 10) & 0x1F) * 255 / 31;
            pal[i] = (i == 0 ? 0 : 0xFF000000) | (r << 16) | (g << 8) | bl;
        }
        int[] px = new int[32 * 32];
        for (int tile = 0; tile < 16; tile++) {
            int tx = (tile % 4) * 8, ty = (tile / 4) * 8;
            for (int i = 0; i < 32; i++) {
                int v = b[0x20 + tile * 32 + i] & 0xFF;
                int x = tx + (i % 4) * 2, y = ty + i / 4;
                px[y * 32 + x] = pal[v & 0x0F];
                px[y * 32 + x + 1] = pal[v >> 4];
            }
        }
        boolean any = false;
        for (int p : px) if (p != 0) { any = true; break; }
        if (any) info.icon = px;

        // English title (UTF-16LE, up to three lines: name, subtitle, publisher). Keep the first two.
        String t = new String(b, 0x340, 0x100, StandardCharsets.UTF_16LE);
        int nul = t.indexOf('\0');
        if (nul >= 0) t = t.substring(0, nul);
        String[] lines = t.split("\n");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lines.length && i < 2; i++) {
            String l = lines[i].trim();
            if (l.isEmpty()) continue;
            if (i == 1 && lines.length == 2) break; // two lines = name + publisher
            if (sb.length() > 0) sb.append(' ');
            sb.append(l);
        }
        if (sb.length() > 0) info.headerTitle = sb.toString();
    }

    private static void readGba(RandomAccessFile raf, RomInfo info) throws IOException {
        byte[] h = readAt(raf, 0xA0, 0x10);
        if (h == null) return;
        info.headerTitle = ascii(h, 0, 12);
        info.serial = code(h, 0x0C);
    }

    private static void readGb(RandomAccessFile raf, RomInfo info) throws IOException {
        byte[] h = readAt(raf, 0x134, 0x10);
        if (h == null) return;
        boolean cgb = (h[0x0F] & 0x80) != 0; // 0x143
        if (cgb) info.system = GBC;
        // Newer Color games keep a 4-character code at 0x13F; the title is then 11 characters.
        String c = cgb ? code(h, 0x0B) : null;
        info.serial = c;
        info.headerTitle = ascii(h, 0, c != null ? 11 : (cgb ? 15 : 16));
    }

    public static String crc32(File f) {
        CRC32 crc = new CRC32();
        byte[] buf = new byte[1 << 16];
        try (InputStream in = new FileInputStream(f)) {
            int n;
            while ((n = in.read(buf)) > 0) crc.update(buf, 0, n);
        } catch (IOException e) {
            return null;
        }
        return String.format(Locale.ROOT, "%08X", crc.getValue());
    }
}
