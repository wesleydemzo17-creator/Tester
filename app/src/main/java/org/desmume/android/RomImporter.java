package org.desmume.android;

import com.github.junrar.Archive;
import com.github.junrar.rarfile.FileHeader;

import net.sf.sevenzipjbinding.ExtractOperationResult;
import net.sf.sevenzipjbinding.IInArchive;
import net.sf.sevenzipjbinding.PropID;
import net.sf.sevenzipjbinding.SevenZip;
import net.sf.sevenzipjbinding.impl.RandomAccessFileInStream;

import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry;
import org.apache.commons.compress.archivers.sevenz.SevenZFile;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.util.Enumeration;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Turns a file the user picked into a .nds file inside the app's ROM folder.
 * Accepts a plain .nds, or a ZIP, 7z or RAR archive that contains one.
 */
final class RomImporter {

    /** Failure with a message that can be shown to the user. */
    static final class ImportException extends Exception {
        ImportException(String message) { super(message); }
    }

    private RomImporter() {}

    private static final int ZIP = 1, SEVENZ = 2, RAR = 3;

    /**
     * @param picked      temporary copy of the picked file (deleted or moved by this call)
     * @param displayName the name the file had where the user picked it
     * @param romsDir     destination folder
     * @return the .nds file ready to be loaded
     */
    static File importFile(File picked, String displayName, File romsDir) throws ImportException {
        try {
            int kind = sniff(picked);
            if (kind == 0) {
                if (!displayName.toLowerCase(Locale.ROOT).endsWith(".nds")) {
                    throw new ImportException("Formato não reconhecido. Use .nds, .zip, .7z ou .rar.");
                }
                return moveInto(picked, new File(romsDir, clean(displayName)));
            }
            File rom = extract(picked, kind, romsDir);
            if (rom == null) throw new ImportException("Nenhum arquivo .nds encontrado dentro do arquivo compactado.");
            return rom;
        } finally {
            picked.delete(); // no-op if it was moved
        }
    }

    private interface Extractor { File run() throws Exception; }

    /**
     * Tries each extractor that can handle this kind of archive, in order, until one works.
     * The native 7-Zip engine reads everything (including RAR5); the Java ones are kept as a
     * second chance in case it is not available on the device.
     */
    private static File extract(final File archive, int kind, final File romsDir) throws ImportException {
        Extractor nativeEngine = () -> fromSevenZipEngine(archive, romsDir);
        Extractor[] order;
        if (kind == ZIP) order = new Extractor[]{() -> fromZip(archive, romsDir), nativeEngine};
        else if (kind == SEVENZ) order = new Extractor[]{nativeEngine, () -> fromSevenZ(archive, romsDir)};
        else order = new Extractor[]{nativeEngine, () -> fromRar(archive, romsDir)};

        StringBuilder details = new StringBuilder();
        boolean password = false;
        for (Extractor e : order) {
            try {
                return e.run(); // null = opened fine but holds no .nds; no point in trying another engine
            } catch (ImportException ex) {
                throw ex;
            } catch (Throwable ex) { // includes a missing native library
                String m = ex.getClass().getSimpleName() + ": " + ex.getMessage();
                String lower = m.toLowerCase(Locale.ROOT);
                if (lower.contains("password") || lower.contains("encrypt")) password = true;
                if (details.length() > 0) details.append("\n");
                details.append(m);
            }
        }
        if (password) throw new ImportException("Arquivo protegido por senha não é suportado.");
        throw new ImportException("Não foi possível abrir o arquivo compactado. Ele pode estar incompleto "
                + "ou corrompido; tente baixar de novo ou descompactar em outro app e abrir o .nds.\n\n"
                + "Detalhes técnicos:\n" + details);
    }

    /** ZIP, 7z, RAR and RAR5 through the native 7-Zip engine. */
    private static File fromSevenZipEngine(File archive, File romsDir) throws Exception {
        try (RandomAccessFile raf = new RandomAccessFile(archive, "r")) {
            IInArchive in = SevenZip.openInArchive(null, new RandomAccessFileInStream(raf));
            try {
                int count = in.getNumberOfItems();
                for (int i = 0; i < count; i++) {
                    Object folder = in.getProperty(i, PropID.IS_FOLDER);
                    Object path = in.getProperty(i, PropID.PATH);
                    if (Boolean.TRUE.equals(folder) || !(path instanceof String) || !isRomName((String) path)) continue;

                    File dest = new File(romsDir, clean((String) path));
                    File part = new File(dest.getPath() + ".part");
                    boolean ok = false;
                    try (final OutputStream out = new FileOutputStream(part)) {
                        final IOException[] failure = new IOException[1];
                        ExtractOperationResult result = in.extractSlow(i, data -> {
                            try {
                                out.write(data);
                            } catch (IOException e) {
                                failure[0] = e;
                                return 0;
                            }
                            return data.length;
                        });
                        if (failure[0] != null) throw failure[0];
                        if (result != ExtractOperationResult.OK) throw new IOException("7-Zip: " + result);
                        ok = true;
                    } finally {
                        if (!ok) part.delete();
                    }
                    return moveInto(part, dest);
                }
            } finally {
                in.close();
            }
        }
        return null;
    }

    /** Identifies the container by its first bytes, not by the file name. */
    private static int sniff(File f) {
        byte[] h = new byte[8];
        int n = 0;
        try (InputStream in = new FileInputStream(f)) {
            while (n < h.length) {
                int r = in.read(h, n, h.length - n);
                if (r < 0) break;
                n += r;
            }
        } catch (IOException e) {
            return 0;
        }
        if (n >= 4 && h[0] == 'P' && h[1] == 'K' && h[2] == 3 && h[3] == 4) return ZIP;
        if (n >= 6 && h[0] == '7' && h[1] == 'z' && (h[2] & 0xFF) == 0xBC && (h[3] & 0xFF) == 0xAF
                && h[4] == 0x27 && h[5] == 0x1C) return SEVENZ;
        if (n >= 7 && h[0] == 'R' && h[1] == 'a' && h[2] == 'r' && h[3] == '!' && h[4] == 0x1A && h[5] == 0x07) return RAR;
        return 0;
    }

    private static boolean isRomName(String entryName) {
        return entryName != null && entryName.toLowerCase(Locale.ROOT).endsWith(".nds");
    }

    /** Drops any folder part and characters that are unsafe in file names. */
    private static String clean(String name) {
        int cut = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        String base = (cut >= 0) ? name.substring(cut + 1) : name;
        base = base.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_").trim();
        if (base.isEmpty() || base.startsWith(".")) base = "game" + base;
        return base;
    }

    private static File moveInto(File src, File dest) throws ImportException {
        if (dest.exists() && !dest.delete()) throw new ImportException("Não foi possível substituir a ROM existente.");
        if (!src.renameTo(dest)) throw new ImportException("Não foi possível salvar a ROM.");
        return dest;
    }

    private interface Source { int read(byte[] buf) throws Exception; }

    private static File write(Source src, String entryName, File romsDir) throws Exception {
        File dest = new File(romsDir, clean(entryName));
        File part = new File(dest.getPath() + ".part");
        boolean ok = false;
        try (OutputStream out = new FileOutputStream(part)) {
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = src.read(buf)) > 0) out.write(buf, 0, n);
            ok = true;
        } finally {
            if (!ok) part.delete();
        }
        return moveInto(part, dest);
    }

    private static File fromZip(File archive, File romsDir) throws Exception {
        try (ZipFile zip = new ZipFile(archive)) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry e = entries.nextElement();
                if (e.isDirectory() || !isRomName(e.getName())) continue;
                try (InputStream in = zip.getInputStream(e)) {
                    return write(in::read, e.getName(), romsDir);
                }
            }
        }
        return null;
    }

    private static File fromSevenZ(File archive, File romsDir) throws Exception {
        try (SevenZFile sz = new SevenZFile(archive)) {
            SevenZArchiveEntry e;
            while ((e = sz.getNextEntry()) != null) {
                if (e.isDirectory() || !isRomName(e.getName())) continue;
                return write(sz::read, e.getName(), romsDir);
            }
        }
        return null;
    }

    private static File fromRar(File archive, File romsDir) throws Exception {
        try (Archive rar = new Archive(archive)) {
            for (FileHeader h : rar.getFileHeaders()) {
                if (h.isDirectory() || !isRomName(h.getFileName())) continue;
                File dest = new File(romsDir, clean(h.getFileName()));
                File part = new File(dest.getPath() + ".part");
                boolean ok = false;
                try (OutputStream out = new FileOutputStream(part)) {
                    rar.extractFile(h, out);
                    ok = true;
                } finally {
                    if (!ok) part.delete();
                }
                return moveInto(part, dest);
            }
        }
        return null;
    }
}
