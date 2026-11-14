package org.desmume.android;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.concurrent.locks.LockSupport;

/**
 * Draws both DS screens plus the on-screen controls, and owns the emulation thread.
 * One thread runs the core, uploads the frame and feeds audio, so no extra copies or locks are needed.
 */
public class EmuView extends SurfaceView implements SurfaceHolder.Callback, Runnable {

    public interface Listener {
        void onMenuRequested();
        void onFramesRun(int totalFrames);
    }

    public static final int FRAMESKIP_AUTO = -1;
    private static final long FRAME_NS = 16_715_000L; // 59.8261 Hz

    private static final class Btn {
        final RectF r = new RectF();
        final int key;
        final String label;
        final boolean round;
        Btn(int key, String label, boolean round) { this.key = key; this.label = label; this.round = round; }
    }

    private final Listener listener;
    private final Bitmap bitmap = Bitmap.createBitmap(Native.SCREEN_W, Native.SCREEN_H * 2, Bitmap.Config.RGB_565);
    private final ByteBuffer pixels =
            ByteBuffer.allocateDirect(Native.SCREEN_W * Native.SCREEN_H * 2 * 2).order(ByteOrder.nativeOrder());
    private final short[] audioBuf = new short[Native.SAMPLE_RATE / 2];

    private final Rect srcTop = new Rect(0, 0, Native.SCREEN_W, Native.SCREEN_H);
    private final Rect srcBottom = new Rect(0, Native.SCREEN_H, Native.SCREEN_W, Native.SCREEN_H * 2);
    private final RectF dstTop = new RectF();
    private final RectF dstBottom = new RectF();
    private final RectF dpad = new RectF();
    private final RectF menuBtn = new RectF();
    private final ArrayList<Btn> buttons = new ArrayList<>();

    private final Paint screenPaint = new Paint();
    private final Paint ctrlFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ctrlStroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private Thread thread;
    private volatile boolean running;
    private volatile boolean surfaceReady;
    private volatile boolean paused = true;
    private volatile boolean layoutDirty = true;

    // Input state (written by the UI thread, read by the emulation thread).
    private volatile int touchKeys;
    private volatile int padKeys;
    private volatile boolean stylusDown;
    private volatile int stylusX, stylusY;
    private int stylusPointerId = -1;

    // Settings.
    private volatile int frameskip = FRAMESKIP_AUTO;
    private volatile boolean soundEnabled = true;
    private volatile boolean fastForward;
    private volatile boolean showFps = true;
    private volatile boolean showControls = true;

    private AudioTrack track;
    private int totalFrames;
    private volatile String fpsText = "";

    public EmuView(Context context, Listener listener) {
        super(context);
        this.listener = listener;
        getHolder().addCallback(this);
        setFocusable(true);
        setFocusableInTouchMode(true);
        ctrlFill.setStyle(Paint.Style.FILL);
        ctrlStroke.setStyle(Paint.Style.STROKE);
        ctrlStroke.setColor(Color.argb(150, 255, 255, 255));
        textPaint.setColor(Color.argb(210, 255, 255, 255));
        textPaint.setTextAlign(Paint.Align.CENTER);
        buttons.add(new Btn(Native.KEY_A, "A", true));
        buttons.add(new Btn(Native.KEY_B, "B", true));
        buttons.add(new Btn(Native.KEY_X, "X", true));
        buttons.add(new Btn(Native.KEY_Y, "Y", true));
        buttons.add(new Btn(Native.KEY_L, "L", false));
        buttons.add(new Btn(Native.KEY_R, "R", false));
        buttons.add(new Btn(Native.KEY_START, "START", false));
        buttons.add(new Btn(Native.KEY_SELECT, "SELECT", false));
    }

    // ------------------------------------------------------------------ settings
    public void setFrameskip(int v) { frameskip = v; }
    public void setSoundEnabled(boolean v) { soundEnabled = v; Native.setSoundEnabled(v); }
    public void setFastForward(boolean v) { fastForward = v; }
    public boolean isFastForward() { return fastForward; }
    public void setSmoothing(boolean v) { screenPaint.setFilterBitmap(v); }
    public void setShowFps(boolean v) { showFps = v; }
    public void setShowControls(boolean v) { showControls = v; }
    public void setPaused(boolean v) { paused = v; }
    public void setPadKeys(int keys) { padKeys = keys; }
    public int getPadKeys() { return padKeys; }

    // ------------------------------------------------------------------ lifecycle
    public void start() {
        if (thread != null) return;
        running = true;
        thread = new Thread(this, "desmume-emu");
        thread.setPriority(Thread.MAX_PRIORITY);
        thread.start();
    }

    public void stop() {
        running = false;
        Thread t = thread;
        thread = null;
        if (t != null) {
            try { t.join(2000); } catch (InterruptedException ignored) { }
        }
    }

    @Override public void surfaceCreated(SurfaceHolder holder) { surfaceReady = true; }

    @Override public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        layoutDirty = true;
        surfaceReady = true;
    }

    @Override public void surfaceDestroyed(SurfaceHolder holder) { surfaceReady = false; }

    // ------------------------------------------------------------------ layout
    private void layout(int w, int h) {
        final float sw = Native.SCREEN_W, sh = Native.SCREEN_H;
        float unit; // base size for buttons
        if (h >= w) {
            // Portrait: screens stacked at the top, controls underneath.
            float scale = Math.min(w / sw, (h * 0.62f) / (sh * 2));
            float left = (w - sw * scale) / 2f;
            dstTop.set(left, 0, left + sw * scale, sh * scale);
            dstBottom.set(left, sh * scale, left + sw * scale, sh * scale * 2);
            float top = dstBottom.bottom;
            float areaH = h - top;
            unit = Math.min(w / 8f, areaH / 5.2f);
            float cy = top + unit * 0.9f + (areaH - unit * 0.9f) * 0.45f;
            placeControls(0, w, top, cy, unit, w / 2f, h - unit * 0.75f);
        } else {
            // Landscape: screens stacked in the centre, controls on both sides.
            float scale = Math.min(h / (sh * 2), (w * 0.5f) / sw);
            float left = (w - sw * scale) / 2f;
            float topY = (h - sh * scale * 2) / 2f;
            dstTop.set(left, topY, left + sw * scale, topY + sh * scale);
            dstBottom.set(left, dstTop.bottom, left + sw * scale, dstTop.bottom + sh * scale);
            float side = left;
            unit = Math.min(side / 4.2f, h / 6.5f);
            placeControls(0, w, 0, h * 0.58f, unit, -1, -1);
            // START / SELECT / MENU along the bottom of the side panels.
            float bw = unit * 1.5f, bh = unit * 0.6f;
            find(Native.KEY_SELECT).r.set(side / 2f - bw / 2f, h - bh * 1.5f, side / 2f + bw / 2f, h - bh * 0.5f);
            float rc = w - side / 2f;
            find(Native.KEY_START).r.set(rc - bw / 2f, h - bh * 1.5f, rc + bw / 2f, h - bh * 0.5f);
            menuBtn.set(rc - bw / 2f, bh * 2.2f, rc + bw / 2f, bh * 3.2f);
        }
        textPaint.setTextSize(unit * 0.42f);
        ctrlStroke.setStrokeWidth(Math.max(2f, unit * 0.04f));
    }

    /** D-pad on the left, ABXY on the right, L/R at the top corners of the control area. */
    private void placeControls(float x0, float x1, float top, float cy, float unit,
                               float midX, float midBottomY) {
        float dr = unit * 1.55f;
        float dcx = x0 + unit * 0.45f + dr;
        dpad.set(dcx - dr, cy - dr, dcx + dr, cy + dr);

        float br = unit * 0.62f;      // face button radius
        float spread = unit * 1.05f;
        float fcx = x1 - unit * 0.45f - spread - br;
        setRound(find(Native.KEY_A), fcx + spread, cy, br);
        setRound(find(Native.KEY_Y), fcx - spread, cy, br);
        setRound(find(Native.KEY_X), fcx, cy - spread, br);
        setRound(find(Native.KEY_B), fcx, cy + spread, br);

        float lw = unit * 1.9f, lh = unit * 0.75f, pad = unit * 0.15f;
        find(Native.KEY_L).r.set(x0 + pad, top + pad, x0 + pad + lw, top + pad + lh);
        find(Native.KEY_R).r.set(x1 - pad - lw, top + pad, x1 - pad, top + pad + lh);

        if (midX >= 0) {
            float bw = unit * 1.45f, bh = unit * 0.58f, gap = unit * 0.12f;
            find(Native.KEY_SELECT).r.set(midX - bw - gap, midBottomY - bh / 2f, midX - gap, midBottomY + bh / 2f);
            find(Native.KEY_START).r.set(midX + gap, midBottomY - bh / 2f, midX + gap + bw, midBottomY + bh / 2f);
            menuBtn.set(midX - bw / 2f, top + pad, midX + bw / 2f, top + pad + lh);
        }
    }

    private static void setRound(Btn b, float cx, float cy, float r) { b.r.set(cx - r, cy - r, cx + r, cy + r); }

    private Btn find(int key) {
        for (Btn b : buttons) if (b.key == key) return b;
        throw new IllegalStateException();
    }

    // ------------------------------------------------------------------ input
    @Override
    public boolean onTouchEvent(MotionEvent e) {
        int action = e.getActionMasked();
        int idx = e.getActionIndex();

        if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_POINTER_DOWN) {
            float x = e.getX(idx), y = e.getY(idx);
            if (menuBtn.contains(x, y)) {
                listener.onMenuRequested();
                return true;
            }
            if (stylusPointerId < 0 && dstBottom.contains(x, y)) stylusPointerId = e.getPointerId(idx);
        }

        boolean ended = action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL;
        int liftedId = (action == MotionEvent.ACTION_POINTER_UP || action == MotionEvent.ACTION_UP)
                ? e.getPointerId(idx) : -1;
        if (ended || (liftedId >= 0 && liftedId == stylusPointerId)) stylusPointerId = -1;

        int keys = 0;
        boolean stylus = false;
        if (!ended) {
            for (int i = 0; i < e.getPointerCount(); i++) {
                int id = e.getPointerId(i);
                if (id == liftedId) continue;
                float x = e.getX(i), y = e.getY(i);
                if (id == stylusPointerId) {
                    stylus = true;
                    float fx = (x - dstBottom.left) / dstBottom.width() * Native.SCREEN_W;
                    float fy = (y - dstBottom.top) / dstBottom.height() * Native.SCREEN_H;
                    stylusX = Math.max(0, Math.min(Native.SCREEN_W - 1, (int) fx));
                    stylusY = Math.max(0, Math.min(Native.SCREEN_H - 1, (int) fy));
                } else {
                    keys |= keysAt(x, y);
                }
            }
        }
        stylusDown = stylus;
        touchKeys = keys;
        return true;
    }

    private int keysAt(float x, float y) {
        if (!showControls) return 0;
        for (Btn b : buttons) {
            if (b.r.contains(x, y)) return b.key;
        }
        if (dpad.contains(x, y)) {
            float dx = x - dpad.centerX(), dy = y - dpad.centerY();
            float dead = dpad.width() * 0.12f;
            int k = 0;
            if (dx > dead && Math.abs(dy) < Math.abs(dx) * 2.2f) k |= Native.KEY_RIGHT;
            if (dx < -dead && Math.abs(dy) < Math.abs(dx) * 2.2f) k |= Native.KEY_LEFT;
            if (dy > dead && Math.abs(dx) < Math.abs(dy) * 2.2f) k |= Native.KEY_DOWN;
            if (dy < -dead && Math.abs(dx) < Math.abs(dy) * 2.2f) k |= Native.KEY_UP;
            return k;
        }
        for (Btn b : buttons) {
            float slop = b.r.height() * 0.15f;
            if (x >= b.r.left - slop && x <= b.r.right + slop && y >= b.r.top - slop && y <= b.r.bottom + slop)
                return b.key;
        }
        return 0;
    }

    // ------------------------------------------------------------------ drawing
    private void draw(boolean haveFrame) {
        if (!surfaceReady) return;
        SurfaceHolder holder = getHolder();
        Surface surface = holder.getSurface();
        if (surface == null || !surface.isValid()) return;
        Canvas c = null;
        try {
            c = surface.lockHardwareCanvas(); // GPU scaling: far cheaper than a software canvas
            if (c == null) return;
            if (layoutDirty) {
                layout(c.getWidth(), c.getHeight());
                layoutDirty = false;
            }
            c.drawColor(Color.BLACK);
            if (haveFrame) {
                c.drawBitmap(bitmap, srcTop, dstTop, screenPaint);
                c.drawBitmap(bitmap, srcBottom, dstBottom, screenPaint);
            } else {
                float ts = textPaint.getTextSize();
                c.drawText("DeSmuME", dstTop.centerX(), dstTop.centerY(), textPaint);
                c.drawText("MENU → Abrir ROM", dstBottom.centerX(), dstBottom.centerY() + ts, textPaint);
            }
            drawControls(c);
            if (showFps && haveFrame) {
                textPaint.setTextAlign(Paint.Align.LEFT);
                c.drawText(fpsText, dstTop.left + 8, dstTop.top + textPaint.getTextSize() + 4, textPaint);
                textPaint.setTextAlign(Paint.Align.CENTER);
            }
        } catch (RuntimeException ignored) {
            // Surface went away between the validity check and the lock.
        } finally {
            if (c != null) {
                try { surface.unlockCanvasAndPost(c); } catch (RuntimeException ignored) { }
            }
        }
    }

    private void drawControls(Canvas c) {
        int keys = touchKeys | padKeys;
        float txtOff = textPaint.getTextSize() * 0.35f;

        // MENU is always drawn so the app can never get stuck without it.
        ctrlFill.setColor(Color.argb(70, 255, 255, 255));
        float mr = menuBtn.height() / 2f;
        c.drawRoundRect(menuBtn, mr, mr, ctrlFill);
        c.drawRoundRect(menuBtn, mr, mr, ctrlStroke);
        c.drawText("MENU", menuBtn.centerX(), menuBtn.centerY() + txtOff, textPaint);
        if (!showControls) return;

        // D-pad
        float cx = dpad.centerX(), cy = dpad.centerY(), arm = dpad.width() / 2f, th = dpad.width() / 6f;
        ctrlFill.setColor(Color.argb(60, 255, 255, 255));
        c.drawRoundRect(cx - arm, cy - th, cx + arm, cy + th, th * 0.4f, th * 0.4f, ctrlFill);
        c.drawRoundRect(cx - th, cy - arm, cx + th, cy + arm, th * 0.4f, th * 0.4f, ctrlFill);
        ctrlFill.setColor(Color.argb(140, 255, 255, 255));
        if ((keys & Native.KEY_LEFT) != 0) c.drawRect(cx - arm, cy - th, cx - th, cy + th, ctrlFill);
        if ((keys & Native.KEY_RIGHT) != 0) c.drawRect(cx + th, cy - th, cx + arm, cy + th, ctrlFill);
        if ((keys & Native.KEY_UP) != 0) c.drawRect(cx - th, cy - arm, cx + th, cy - th, ctrlFill);
        if ((keys & Native.KEY_DOWN) != 0) c.drawRect(cx - th, cy + th, cx + th, cy + arm, ctrlFill);

        for (Btn b : buttons) {
            boolean down = (keys & b.key) != 0;
            ctrlFill.setColor(Color.argb(down ? 150 : 60, 255, 255, 255));
            if (b.round) {
                c.drawOval(b.r, ctrlFill);
                c.drawOval(b.r, ctrlStroke);
            } else {
                float r = b.r.height() / 2f;
                c.drawRoundRect(b.r, r, r, ctrlFill);
                c.drawRoundRect(b.r, r, r, ctrlStroke);
            }
            c.drawText(b.label, b.r.centerX(), b.r.centerY() + txtOff, textPaint);
        }
    }

    // ------------------------------------------------------------------ audio
    private void openAudio() {
        try {
            int min = AudioTrack.getMinBufferSize(Native.SAMPLE_RATE,
                    AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT);
            int want = Native.SAMPLE_RATE / 10 * 4; // ~100 ms of stereo 16-bit
            track = new AudioTrack.Builder()
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_GAME)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build())
                    .setAudioFormat(new AudioFormat.Builder()
                            .setSampleRate(Native.SAMPLE_RATE)
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                            .build())
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .setBufferSizeInBytes(Math.max(min, want))
                    .build();
            track.play();
        } catch (RuntimeException e) {
            track = null;
        }
    }

    private void closeAudio() {
        if (track != null) {
            try { track.pause(); track.flush(); track.release(); } catch (RuntimeException ignored) { }
            track = null;
        }
    }

    // ------------------------------------------------------------------ emulation loop
    @Override
    public void run() {
        openAudio();
        long next = System.nanoTime();
        int skipped = 0;
        boolean skipNext = false;
        boolean haveFrame = false;
        long fpsT0 = System.nanoTime();
        int fpsFrames = 0, fpsDrawn = 0;

        while (running) {
            if (paused || !surfaceReady || !Native.romLoaded()) {
                if (!Native.romLoaded()) haveFrame = false;
                draw(haveFrame);
                try { Thread.sleep(60); } catch (InterruptedException ignored) { }
                next = System.nanoTime();
                continue;
            }

            Native.runFrame(touchKeys | padKeys, stylusDown, stylusX, stylusY, skipNext);
            totalFrames++;
            fpsFrames++;
            if ((totalFrames & 63) == 0) listener.onFramesRun(totalFrames);

            if (!skipNext) {
                pixels.rewind();
                if (Native.copyFrame(pixels)) {
                    pixels.rewind();
                    bitmap.copyPixelsFromBuffer(pixels);
                    haveFrame = true;
                }
                draw(haveFrame);
                fpsDrawn++;
            }

            int n = Native.readAudio(audioBuf);
            if (n > 0 && track != null && soundEnabled && !fastForward) {
                track.write(audioBuf, 0, n * 2);
            }

            // Pacing + frameskip decision.
            next += FRAME_NS;
            long now = System.nanoTime();
            boolean behind = false;
            if (fastForward) {
                next = now;
            } else if (now < next) {
                LockSupport.parkNanos(next - now);
            } else {
                behind = (now - next) > FRAME_NS / 2;
                if (now - next > FRAME_NS * 6) next = now; // too far behind: resync instead of racing
            }

            int fs = frameskip;
            if (fastForward) {
                skipNext = skipped < 5;
            } else if (fs == FRAMESKIP_AUTO) {
                skipNext = behind && skipped < 3;
            } else {
                skipNext = skipped < fs;
            }
            skipped = skipNext ? skipped + 1 : 0;

            if (now - fpsT0 >= 1_000_000_000L) {
                double secs = (now - fpsT0) / 1e9;
                fpsText = String.format(java.util.Locale.US, "%.0f fps (%.0f desenhados)",
                        fpsFrames / secs, fpsDrawn / secs);
                fpsT0 = now;
                fpsFrames = 0;
                fpsDrawn = 0;
            }
        }
        closeAudio();
    }
}
