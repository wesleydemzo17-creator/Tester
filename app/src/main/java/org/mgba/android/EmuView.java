package org.mgba.android;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.opengl.GLSurfaceView;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;

import java.util.ArrayList;
import java.util.Locale;
import java.util.concurrent.locks.LockSupport;

/**
 * The emulator screen: an OpenGL surface with the game picture (see ScreenRenderer), a
 * transparent overlay with the on-screen controls and the layout editor, and the emulation thread.
 */
public class EmuView extends FrameLayout implements Runnable {

    public interface Listener {
        void onMenuRequested();
        /** Called from the emulation thread about once a second while a game runs. */
        void onTick();
        /** Called from the emulation thread when the cartridge's rumble motor is running. */
        void onRumble(float strength);
    }

    public static final int LAYOUT_CUSTOM = 99;
    public static final String[] LAYOUT_NAMES = {
            "Ajustar à tela (proporção original)",
            "Escala inteira (pixels perfeitos)",
            "Esticar em tela cheia",
    };
    public static final String[] HUD_CORNERS = {
            "superior esquerdo", "superior direito", "inferior esquerdo", "inferior direito"};
    /** Speeds offered for fast-forward; 0 means "as fast as possible". */
    public static final int[] FF_SPEEDS = {2, 3, 4, 8, 0};

    private static final long FRAME_NS = 16_742_706L; // 59.7275 Hz, GBA and GB alike
    private static final int KEY_FF = 1 << 20, KEY_REWIND = 1 << 21; // overlay-only buttons

    private static final class Btn {
        final RectF r = new RectF();
        final int key;
        final String label;
        final boolean round;
        Btn(int key, String label, boolean round) { this.key = key; this.label = label; this.round = round; }
    }

    private final Listener listener;
    private final SharedPreferences prefs;
    private final GLSurfaceView glView;
    private final ScreenRenderer renderer = new ScreenRenderer();
    private final Overlay overlay;
    private final short[] audioBuf = new short[Native.SAMPLE_RATE / 4];

    /** Where the picture is drawn, in view pixels. Only touched on the UI thread. */
    private final RectF dst = new RectF();
    /** The default placement; the portrait controls are arranged under it. */
    private final RectF def = new RectF();
    private int viewW, viewH;
    private float unit = 1f;
    /** Size of the picture the core is producing (changes with Super Game Boy borders, hi-res). */
    private volatile int frameW = 240, frameH = 160;
    private int layoutW = 240, layoutH = 160;

    // Layout editor state (UI thread).
    private boolean editMode, pinching, resizing, editMoving;
    private float lastX, lastY, pinchStartDist, pinchStartW, pinchStartH;
    private final RectF editDone = new RectF(), editReset = new RectF(), editAspect = new RectF(), editFill = new RectF();

    private final RectF dpad = new RectF();
    private final RectF menuBtn = new RectF();
    private final ArrayList<Btn> buttons = new ArrayList<>();
    private Btn btnL, btnR;

    private final Paint ctrlFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ctrlStroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    // Performance monitor.
    private final PerfMonitor perf;
    private final float density;
    private final Paint hudText = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint hudBack = new Paint();
    private volatile String[] hudLines = new String[0];
    private volatile boolean hudDetails = true;
    private volatile boolean showHud = true;
    private float hudScale = 1f;
    private int hudAlpha = 204;
    private int hudCorner;

    private Thread thread;
    private volatile boolean running;
    private volatile boolean paused = true;
    private volatile boolean noRom = true;

    // Input state (written by the UI thread, read by the emulation thread).
    private volatile int touchKeys;
    private volatile int padKeys;

    // Settings.
    private volatile boolean soundEnabled = true;
    private volatile boolean fastForward;
    private volatile int ffSpeed = 3;
    private volatile boolean showControls = true;
    private volatile boolean hasShoulders = true;
    private volatile boolean rewindEnabled;
    private volatile String rendererLabel = "software";

    private AudioTrack track;

    public EmuView(Context context, Listener listener, SharedPreferences prefs) {
        super(context);
        this.listener = listener;
        this.prefs = prefs;

        glView = new GLSurfaceView(context);
        glView.setEGLContextClientVersion(2);
        glView.setPreserveEGLContextOnPause(true);
        glView.setRenderer(renderer);
        glView.setRenderMode(GLSurfaceView.RENDERMODE_WHEN_DIRTY);
        addView(glView);
        overlay = new Overlay(context);
        addView(overlay);

        perf = new PerfMonitor(context);
        density = context.getResources().getDisplayMetrics().density;
        hudText.setColor(Color.WHITE);
        hudBack.setStyle(Paint.Style.FILL);
        hudBack.setColor(Color.BLACK);

        setFocusable(true);
        setFocusableInTouchMode(true);
        ctrlFill.setStyle(Paint.Style.FILL);
        ctrlStroke.setStyle(Paint.Style.STROKE);
        ctrlStroke.setColor(Color.argb(150, 255, 255, 255));
        textPaint.setColor(Color.argb(210, 255, 255, 255));
        textPaint.setTextAlign(Paint.Align.CENTER);
        buttons.add(new Btn(Native.KEY_A, "A", true));
        buttons.add(new Btn(Native.KEY_B, "B", true));
        btnL = new Btn(Native.KEY_L, "L", false);
        btnR = new Btn(Native.KEY_R, "R", false);
        buttons.add(btnL);
        buttons.add(btnR);
        buttons.add(new Btn(Native.KEY_START, "START", false));
        buttons.add(new Btn(Native.KEY_SELECT, "SELECT", false));
        buttons.add(new Btn(KEY_FF, "▶▶", false));
        buttons.add(new Btn(KEY_REWIND, "◀◀", false));
    }

    // ------------------------------------------------------------------ settings
    public void setSoundEnabled(boolean v) { soundEnabled = v; }
    public void setFastForward(boolean v) { fastForward = v; }
    public boolean isFastForward() { return fastForward; }
    public void setFastForwardSpeed(int speed) { ffSpeed = speed; }
    public void setShader(int index) { renderer.setShader(index); glView.requestRender(); }
    public void setShowHud(boolean v) { showHud = v; overlay.invalidate(); }
    public void setShowControls(boolean v) { showControls = v; overlay.invalidate(); }
    public void setRewindEnabled(boolean v) { rewindEnabled = v; overlay.invalidate(); }
    public void setRendererLabel(String label) { rendererLabel = label; }
    public void setPaused(boolean v) { paused = v; }
    public void setPadKeys(int keys) { padKeys = keys; overlay.invalidate(); }
    public int getPadKeys() { return padKeys; }

    /** Tells the view which console is running: the Game Boy has no L and R buttons. */
    public void setPlatform(int platform) {
        hasShoulders = platform == Native.PLATFORM_GBA;
        renderer.setNativeSize(hasShoulders ? 240 : 160, hasShoulders ? 160 : 144);
        overlay.postInvalidate();
    }

    public void setHud(boolean details, int scalePercent, int opacityPercent, int corner) {
        hudDetails = details;
        hudScale = Math.max(50, Math.min(300, scalePercent)) / 100f;
        hudAlpha = Math.max(10, Math.min(100, opacityPercent)) * 255 / 100;
        hudCorner = Math.max(0, Math.min(3, corner));
        if (!details && hudLines.length > 1) hudLines = new String[]{hudLines[0]};
        overlay.invalidate();
    }

    public void onHostResume() { glView.onResume(); }
    public void onHostPause() { glView.onPause(); }

    // ------------------------------------------------------------------ per-game settings, preview
    private final org.emu.hub.Previews.Recorder recorder = new org.emu.hub.Previews.Recorder();

    /** Re-reads every layout setting (after switching to another game's settings). */
    public void reloadPrefs() {
        if (viewW > 0) relayout();
        overlay.invalidate();
    }

    /** Frames kept for the game list's moving preview since the last call. */
    public java.util.List<android.graphics.Bitmap> takePreview() { return recorder.take(); }

    public void resetPreview() { recorder.reset(); }

    // ------------------------------------------------------------------ lifecycle
    public void start() {
        if (thread != null) return;
        running = true;
        thread = new Thread(this, "mgba-emu");
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

    // ------------------------------------------------------------------ layout
    private boolean portrait() { return viewH >= viewW; }
    private String presetKey() { return portrait() ? "layout_preset_p" : "layout_preset_l"; }
    private String customKey() { return portrait() ? "layout_custom_p" : "layout_custom_l"; }
    private float aspect() { return (float) layoutH / layoutW; } // height / width

    /** Recomputes everything for the current view size, picture size and saved layout choice. */
    private void relayout() {
        if (viewW <= 0 || viewH <= 0) return;
        layoutW = frameW;
        layoutH = frameH;
        final float w = viewW, h = viewH, a = aspect();

        // Default placement: as large as fits; at the top in portrait, centred in landscape.
        float dw = portrait() ? Math.min(w, h * 0.55f / a) : Math.min(w, h / a);
        float dl = (w - dw) / 2f, dt = portrait() ? 0f : (h - dw * a) / 2f;
        def.set(dl, dt, dl + dw, dt + dw * a);
        layoutControls(w, h);

        int preset = prefs.getInt(presetKey(), 0);
        if (preset == LAYOUT_CUSTOM && !loadCustom()) preset = 0;
        if (preset == 1) {
            // Largest whole multiple of the console's own resolution that fits.
            int nativeW = hasShoulders ? 240 : 160, nativeH = hasShoulders ? 160 : 144;
            if (layoutW > nativeW && !hasShoulders) { nativeW = layoutW; nativeH = layoutH; } // SGB border
            int k = (int) Math.max(1, Math.min(def.width() / nativeW, def.height() / nativeH));
            float iw = nativeW * k, ih = nativeH * k;
            float il = Math.round((w - iw) / 2f), it = portrait() ? 0f : Math.round((h - ih) / 2f);
            dst.set(il, it, il + iw, it + ih);
        } else if (preset == 2) {
            if (portrait()) dst.set(0, 0, w, def.height());
            else dst.set(0, 0, w, h);
        } else if (preset != LAYOUT_CUSTOM) {
            dst.set(def);
        }
        pushRect();
    }

    private boolean loadCustom() {
        String v = prefs.getString(customKey(), null);
        if (v == null) return false;
        String[] p = v.split(",");
        if (p.length != 4) return false;
        try {
            float[] f = new float[4];
            for (int i = 0; i < 4; i++) f[i] = Float.parseFloat(p[i]);
            dst.set(f[0] * viewW, f[1] * viewH, (f[0] + f[2]) * viewW, (f[1] + f[3]) * viewH);
            clampRect(dst);
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private void saveCustom() {
        String v = String.format(Locale.US, "%.5f,%.5f,%.5f,%.5f",
                dst.left / viewW, dst.top / viewH, dst.width() / viewW, dst.height() / viewH);
        prefs.edit().putString(customKey(), v).putInt(presetKey(), LAYOUT_CUSTOM).apply();
    }

    private float minW() { return Math.min(viewW, viewH) * 0.25f; }

    private void clampRect(RectF r) {
        float rw = Math.max(Math.min(minW(), viewW), Math.min(viewW, r.width()));
        float rh = Math.max(Math.min(minW() * aspect(), viewH), Math.min(viewH, r.height()));
        float left = Math.max(0f, Math.min(viewW - rw, r.centerX() - rw / 2f));
        float top = Math.max(0f, Math.min(viewH - rh, r.centerY() - rh / 2f));
        r.set(left, top, left + rw, top + rh);
    }

    private void pushRect() {
        renderer.setRect(dst.left, dst.top, dst.width(), dst.height());
        glView.requestRender();
        overlay.invalidate();
    }

    public void setLayoutPreset(int preset) {
        prefs.edit().putInt(presetKey(), preset).apply();
        relayout();
    }

    public int getLayoutPreset() { return prefs.getInt(presetKey(), 0); }

    public void startLayoutEdit() {
        editMode = true;
        pinching = resizing = editMoving = false;
        touchKeys = 0;
        overlay.invalidate();
    }

    public boolean isEditingLayout() { return editMode; }

    public void finishLayoutEdit() {
        if (!editMode) return;
        editMode = false;
        saveCustom();
        overlay.invalidate();
    }

    private static void setRound(Btn b, float cx, float cy, float r) { b.r.set(cx - r, cy - r, cx + r, cy + r); }

    private Btn find(int key) {
        for (Btn b : buttons) if (b.key == key) return b;
        throw new IllegalStateException();
    }

    private void layoutControls(float w, float h) {
        float top, cy;
        if (portrait()) {
            top = def.bottom;
            float areaH = h - top;
            unit = Math.min(w / 7.5f, areaH / 5.4f);
            cy = top + unit * 1.1f + (areaH - unit * 1.1f) * 0.42f;
        } else {
            top = 0f;
            unit = Math.min(w / 13f, h / 6.2f);
            cy = h * 0.62f;
        }
        float pad = unit * 0.2f;

        float dr = unit * 1.55f;
        float dcx = unit * 0.5f + dr;
        dpad.set(dcx - dr, cy - dr, dcx + dr, cy + dr);

        float br = unit * 0.72f;
        float fcx = w - unit * 0.6f - br * 2.6f;
        setRound(find(Native.KEY_A), fcx + br * 1.75f, cy - br * 0.55f, br);
        setRound(find(Native.KEY_B), fcx, cy + br * 0.55f, br);

        float lw = unit * 1.9f, lh = unit * 0.78f;
        btnL.r.set(pad, top + pad, pad + lw, top + pad + lh);
        btnR.r.set(w - pad - lw, top + pad, w - pad, top + pad + lh);

        // MENU, fast-forward and rewind in the middle of the top edge of the control area.
        float mw = unit * 1.45f, sw = unit * 1.05f, gap = unit * 0.14f, mx = w / 2f;
        menuBtn.set(mx - mw / 2f, top + pad, mx + mw / 2f, top + pad + lh);
        find(KEY_REWIND).r.set(menuBtn.left - gap - sw, top + pad, menuBtn.left - gap, top + pad + lh);
        find(KEY_FF).r.set(menuBtn.right + gap, top + pad, menuBtn.right + gap + sw, top + pad + lh);

        float bw = unit * 1.5f, bh = unit * 0.6f, by = h - bh * 1.5f;
        find(Native.KEY_SELECT).r.set(mx - bw - gap, by, mx - gap, by + bh);
        find(Native.KEY_START).r.set(mx + gap, by, mx + gap + bw, by + bh);

        float eg = unit * 0.15f;
        float ew = Math.min(unit * 2.6f, (w - eg * 5f) / 4f), eh = unit * 0.8f, ey = h - eh * 2.6f;
        float ex = (w - ew * 4f - eg * 3f) / 2f;
        editReset.set(ex, ey, ex + ew, ey + eh);
        editAspect.set(ex + (ew + eg), ey, ex + (ew + eg) + ew, ey + eh);
        editFill.set(ex + (ew + eg) * 2f, ey, ex + (ew + eg) * 2f + ew, ey + eh);
        editDone.set(ex + (ew + eg) * 3f, ey, ex + (ew + eg) * 3f + ew, ey + eh);

        textPaint.setTextSize(unit * 0.4f);
        ctrlStroke.setStrokeWidth(Math.max(2f, unit * 0.04f));
    }

    // ------------------------------------------------------------------ input
    private boolean onHandle(float x, float y) {
        float hs = Math.min(unit * 1.1f, Math.min(dst.width(), dst.height()) * 0.45f);
        return x >= dst.right - hs && x <= dst.right + hs * 0.3f && y >= dst.bottom - hs && y <= dst.bottom + hs * 0.3f;
    }

    private boolean editTouch(MotionEvent e) {
        int action = e.getActionMasked();
        switch (action) {
            case MotionEvent.ACTION_DOWN: {
                float x = e.getX(0), y = e.getY(0);
                if (editDone.contains(x, y)) {
                    finishLayoutEdit();
                    return true;
                }
                if (editReset.contains(x, y)) {
                    dst.set(def);
                } else if (editFill.contains(x, y)) {
                    dst.set(0, 0, viewW, viewH);
                } else if (editAspect.contains(x, y)) {
                    float nw = Math.min(dst.width(), viewH / aspect());
                    float cx = dst.centerX(), cy = dst.centerY();
                    dst.set(cx - nw / 2f, cy - nw * aspect() / 2f, cx + nw / 2f, cy + nw * aspect() / 2f);
                    clampRect(dst);
                } else {
                    resizing = onHandle(x, y);
                    editMoving = !resizing && dst.contains(x, y);
                    pinching = false;
                    lastX = x;
                    lastY = y;
                    return true;
                }
                pushRect();
                return true;
            }
            case MotionEvent.ACTION_POINTER_DOWN:
                if (e.getPointerCount() >= 2) {
                    pinchStartDist = Math.max(1f, (float) Math.hypot(e.getX(0) - e.getX(1), e.getY(0) - e.getY(1)));
                    pinchStartW = dst.width();
                    pinchStartH = dst.height();
                    pinching = true;
                    resizing = false;
                }
                break;
            case MotionEvent.ACTION_MOVE: {
                if (pinching && e.getPointerCount() >= 2) {
                    float d = (float) Math.hypot(e.getX(0) - e.getX(1), e.getY(0) - e.getY(1));
                    float k = Math.min(d / pinchStartDist, Math.min(viewW / pinchStartW, viewH / pinchStartH));
                    float nw = pinchStartW * k, nh = pinchStartH * k;
                    float cx = dst.centerX(), cy = dst.centerY();
                    dst.set(cx - nw / 2f, cy - nh / 2f, cx + nw / 2f, cy + nh / 2f);
                } else if (resizing) {
                    float x = e.getX(0), y = e.getY(0);
                    float right = Math.max(dst.left + minW(), Math.min(viewW, dst.right + (x - lastX)));
                    float bottom = Math.max(dst.top + minW() * aspect(), Math.min(viewH, dst.bottom + (y - lastY)));
                    dst.set(dst.left, dst.top, right, bottom);
                    lastX = x;
                    lastY = y;
                } else if (editMoving) {
                    float x = e.getX(0), y = e.getY(0);
                    dst.offset(x - lastX, y - lastY);
                    lastX = x;
                    lastY = y;
                } else {
                    break;
                }
                clampRect(dst);
                pushRect();
                break;
            }
            case MotionEvent.ACTION_POINTER_UP: {
                pinching = false;
                int keep = (e.getActionIndex() == 0) ? 1 : 0;
                if (keep < e.getPointerCount()) {
                    lastX = e.getX(keep);
                    lastY = e.getY(keep);
                }
                break;
            }
            default:
                if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                    pinching = resizing = editMoving = false;
                }
                break;
        }
        return true;
    }

    private boolean handleTouch(MotionEvent e) {
        if (editMode) return editTouch(e);
        int action = e.getActionMasked();
        int idx = e.getActionIndex();

        if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_POINTER_DOWN) {
            if (menuBtn.contains(e.getX(idx), e.getY(idx))) {
                listener.onMenuRequested();
                return true;
            }
        }
        boolean ended = action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL;
        int lifted = (action == MotionEvent.ACTION_POINTER_UP) ? idx : -1;

        int keys = 0;
        if (!ended) {
            for (int i = 0; i < e.getPointerCount(); i++) {
                if (i == lifted) continue;
                keys |= keysAt(e.getX(i), e.getY(i));
            }
        }
        if (keys != touchKeys) {
            touchKeys = keys;
            overlay.invalidate();
        }
        return true;
    }

    private boolean visible(Btn b) {
        if (b.key == KEY_REWIND) return rewindEnabled;
        if (b == btnL || b == btnR) return hasShoulders;
        return true;
    }

    private int keysAt(float x, float y) {
        if (!showControls) return 0;
        for (Btn b : buttons) {
            if (!visible(b)) continue;
            float slop = b.r.height() * 0.15f;
            if (x >= b.r.left - slop && x <= b.r.right + slop && y >= b.r.top - slop && y <= b.r.bottom + slop)
                return b.key;
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
        return 0;
    }

    // ------------------------------------------------------------------ overlay drawing
    private final class Overlay extends View {
        Overlay(Context context) { super(context); }

        @Override
        protected void onSizeChanged(int w, int h, int oldw, int oldh) {
            super.onSizeChanged(w, h, oldw, oldh);
            viewW = w;
            viewH = h;
            relayout();
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) { return handleTouch(e); }

        @Override
        protected void onDraw(Canvas c) {
            if (viewW <= 0) return;
            if (frameW != layoutW || frameH != layoutH) relayout(); // picture size changed
            if (editMode) {
                drawEditor(c);
                return;
            }
            if (noRom) {
                float ts = textPaint.getTextSize();
                c.drawText("mGBA", def.centerX(), def.centerY(), textPaint);
                c.drawText("MENU → Abrir ROM", def.centerX(), def.centerY() + ts * 1.6f, textPaint);
            }
            drawControls(c);
            if (showHud && !noRom) drawHud(c);
        }
    }

    private void drawPill(Canvas c, RectF r, String label, int color) {
        float rr = r.height() / 2f;
        ctrlFill.setColor(color);
        c.drawRoundRect(r, rr, rr, ctrlFill);
        c.drawRoundRect(r, rr, rr, ctrlStroke);
        c.drawText(label, r.centerX(), r.centerY() + textPaint.getTextSize() * 0.35f, textPaint);
    }

    private void drawControls(Canvas c) {
        int keys = touchKeys | padKeys;
        float txtOff = textPaint.getTextSize() * 0.35f;

        // MENU is always drawn so the app can never get stuck without it.
        drawPill(c, menuBtn, "MENU", Color.argb(70, 255, 255, 255));
        if (!showControls) return;

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
            if (!visible(b)) continue;
            boolean down = (keys & b.key) != 0 || (b.key == KEY_FF && fastForward);
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

    private void drawHud(Canvas c) {
        String[] lines = hudLines;
        if (lines.length == 0) return;
        float size = 11f * density * hudScale;
        float pad = size * 0.45f, lineH = size * 1.25f, margin = 4f * density;
        hudText.setTextSize(size);
        float boxW = 0f;
        for (String l : lines) boxW = Math.max(boxW, hudText.measureText(l));
        boxW += pad * 2f;
        float boxH = lineH * lines.length + pad * 1.4f;
        boolean right = (hudCorner & 1) != 0, bottom = (hudCorner & 2) != 0;
        float x = right ? viewW - margin - boxW : margin;
        float y = bottom ? viewH - margin - boxH : margin;
        hudBack.setAlpha(hudAlpha * 150 / 255);
        c.drawRect(x, y, x + boxW, y + boxH, hudBack);
        hudText.setAlpha(hudAlpha);
        for (int i = 0; i < lines.length; i++) {
            c.drawText(lines[i], x + pad, y + pad * 0.4f + lineH * (i + 1) - size * 0.22f, hudText);
        }
    }

    private void drawEditor(Canvas c) {
        ctrlFill.setColor(Color.argb(50, 255, 255, 255));
        c.drawRect(dst.left, dst.top, dst.right, dst.bottom, ctrlFill);
        float sw = ctrlStroke.getStrokeWidth() / 2f;
        c.drawRoundRect(dst.left + sw, dst.top + sw, dst.right - sw, dst.bottom - sw, 0f, 0f, ctrlStroke);
        float hs = Math.min(unit * 0.55f, Math.min(dst.width(), dst.height()) * 0.25f);
        ctrlFill.setColor(Color.argb(230, 255, 255, 255));
        c.drawRect(dst.right - hs, dst.bottom - hs, dst.right, dst.bottom, ctrlFill);
        float ts = textPaint.getTextSize();
        c.drawText(Math.round(dst.width()) + " × " + Math.round(dst.height()),
                dst.centerX(), dst.centerY() + ts * 0.35f, textPaint);
        c.drawText("Arraste: mover • Pinça: tamanho • Quadrado do canto: esticar",
                viewW / 2f, editDone.top - ts * 0.8f, textPaint);
        int dark = Color.argb(200, 30, 30, 30);
        drawPill(c, editReset, "Padrão", dark);
        drawPill(c, editAspect, "Proporção", dark);
        drawPill(c, editFill, "Tela cheia", dark);
        drawPill(c, editDone, "Concluir", Color.argb(220, 20, 110, 60));
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
        long lastDraw = 0, lastRumble = 0;
        long fpsT0 = System.nanoTime();
        int fpsFrames = 0, fpsDrawn = 0, idleTicks = 0;

        while (running) {
            boolean loaded = Native.romLoaded();
            if (loaded == noRom) {
                noRom = !loaded;
                if (!loaded) renderer.clearFrame();
                glView.requestRender();
                overlay.postInvalidate();
            }
            if (paused || !loaded) {
                if ((++idleTicks & 15) == 0) glView.requestRender();
                try { Thread.sleep(60); } catch (InterruptedException ignored) { }
                next = System.nanoTime();
                continue;
            }

            int keys = touchKeys | padKeys;
            boolean rewinding = rewindEnabled && (keys & KEY_REWIND) != 0;
            boolean fast = !rewinding && (fastForward || (keys & KEY_FF) != 0);
            int speed = fast ? ffSpeed : 1;

            Native.runFrame(keys & 0x3FF, rewinding);
            fpsFrames++;
            if (!rewinding && !fast && recorder.due()) {
                try {
                    recorder.offer(renderer.snapshot());
                } catch (RuntimeException | OutOfMemoryError ignored) {
                    // The preview is optional.
                }
            }

            // While fast-forwarding, drawing every frame would only slow things down.
            long now = System.nanoTime();
            if (!fast || now - lastDraw >= FRAME_NS) {
                int size = renderer.fetchFrame();
                if (size != 0) {
                    int w = size >>> 16, h = size & 0xFFFF;
                    if (w != frameW || h != frameH) {
                        frameW = w;
                        frameH = h;
                        overlay.postInvalidate();
                    }
                    glView.requestRender();
                }
                lastDraw = now;
                fpsDrawn++;
            }

            int n = Native.readAudio(audioBuf);
            if (n > 0 && track != null && soundEnabled && !fast && !rewinding) {
                track.write(audioBuf, 0, n * 2);
            }

            float rumble = Native.rumble();
            if (rumble > 0.05f && now - lastRumble > 40_000_000L) {
                lastRumble = now;
                listener.onRumble(rumble);
            }

            // Pacing.
            now = System.nanoTime();
            if (speed == 0) {
                next = now;
            } else {
                next += FRAME_NS / speed;
                if (now < next) LockSupport.parkNanos(next - now);
                else if (now - next > FRAME_NS * 6) next = now; // too far behind: resync
            }

            if (now - fpsT0 >= 1_000_000_000L) {
                double secs = (now - fpsT0) / 1e9;
                String fps = String.format(Locale.US, "%.0f fps (%.0f%%)", fpsFrames / secs,
                        fpsFrames / secs * 100.0 / 59.7275);
                if (showHud) {
                    hudLines = hudDetails
                            ? perf.sample(fps, "Vídeo: " + rendererLabel + " · " + frameW + "×" + frameH)
                            : new String[]{fps};
                    overlay.postInvalidate();
                }
                fpsT0 = now;
                fpsFrames = 0;
                fpsDrawn = 0;
                listener.onTick();
            }
        }
        closeAudio();
    }
}
