package org.emu.hub;

import android.content.Context;
import android.graphics.Bitmap;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The moving preview shown in the game list: while a game is played, a frame is kept every few
 * seconds; when the player leaves, the last ones are saved next to the game and the list plays
 * them back as a short loop.
 */
public final class Previews {

    /** Frames kept per game. */
    public static final int MAX_FRAMES = 16;
    /** Emulated frames between two kept frames (about 4 seconds), and frames skipped at boot. */
    public static final int EVERY = 240, SKIP_START = 900;
    private static final int MAX_WIDTH = 320;

    private Previews() {}

    /** Collects frames on the emulation thread. Cheap: a copy every few seconds. */
    public static final class Recorder {
        private final ArrayList<Bitmap> frames = new ArrayList<>();
        private int counter;

        /** Call once per emulated frame; returns true when the caller should hand over a frame now. */
        public boolean due() {
            counter++;
            return counter >= SKIP_START && (counter - SKIP_START) % EVERY == 0;
        }

        public void reset() {
            synchronized (frames) {
                frames.clear();
            }
            counter = 0;
        }

        /** Keeps a frame unless it is a blank (black, white, loading) screen. Takes ownership of b. */
        public void offer(Bitmap b) {
            if (b == null) return;
            if (b.getWidth() > MAX_WIDTH) {
                int h = Math.max(1, b.getHeight() * MAX_WIDTH / b.getWidth());
                Bitmap s = Bitmap.createScaledBitmap(b, MAX_WIDTH, h, true);
                if (s != b) b.recycle();
                b = s;
            }
            if (isBlank(b)) {
                b.recycle();
                return;
            }
            synchronized (frames) {
                frames.add(b);
                while (frames.size() > MAX_FRAMES) frames.remove(0).recycle();
            }
        }

        /** Hands over the collected frames and starts again. */
        public List<Bitmap> take() {
            synchronized (frames) {
                List<Bitmap> out = new ArrayList<>(frames);
                frames.clear();
                return out;
            }
        }
    }

    /** True if a sample of pixels is (almost) all the same brightness. */
    static boolean isBlank(Bitmap b) {
        int min = 255, max = 0;
        for (int y = 1; y < 8; y++) {
            for (int x = 1; x < 8; x++) {
                int c = b.getPixel(x * b.getWidth() / 8, y * b.getHeight() / 8);
                int l = (((c >> 16) & 0xFF) * 3 + ((c >> 8) & 0xFF) * 6 + (c & 0xFF)) / 10;
                min = Math.min(min, l);
                max = Math.max(max, l);
            }
        }
        return max - min < 12;
    }

    /**
     * Saves a session's frames for the game, in the background. A short session (fewer than four
     * good frames) keeps the preview of an earlier, longer one.
     */
    public static void save(final Context context, final File rom, final List<Bitmap> frames) {
        if (rom == null || frames.size() < 4) {
            for (Bitmap b : frames) b.recycle();
            return;
        }
        final Context app = context.getApplicationContext();
        new Thread(() -> {
            File dir = Library.dirOf(app, rom);
            File[] old = dir.listFiles((d, n) -> n.startsWith("preview_"));
            if (old != null) for (File f : old) f.delete();
            for (int i = 0; i < frames.size(); i++) {
                File f = new File(dir, String.format(Locale.ROOT, "preview_%02d.jpg", i));
                try (OutputStream out = new FileOutputStream(f)) {
                    frames.get(i).compress(Bitmap.CompressFormat.JPEG, 82, out);
                } catch (IOException ignored) { }
                frames.get(i).recycle();
            }
        }, "save-preview").start();
    }

    /** Deletes the recorded preview of a game. */
    public static void clear(Context c, File rom) {
        File[] old = Library.dirOf(c, rom).listFiles((d, n) -> n.startsWith("preview_"));
        if (old != null) for (File f : old) f.delete();
    }
}
