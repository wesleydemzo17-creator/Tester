package org.desmume.android;

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
 * The emulator screen: an OpenGL surface that draws the two DS screens (see ScreenRenderer), a
 * transparent overlay with the on-screen controls and the layout editor, and the emulation thread.
 */
public class EmuView extends FrameLayout implements Runnable {

    public interface Listener {
        void onMenuRequested();
        void onFramesRun(int totalFrames);
    }

    public static final int FRAMESKIP_AUTO = -1;

    public static final int LAYOUT_CUSTOM = 99;
    public static final String[] LAYOUT_NAMES = {
            "Padrão (uma sobre a outra)",
            "Lado a lado",
            "Tela de cima grande",
            "Tela de baixo grande",
    };
    private static final float ASPECT = (float) Native.SCREEN_H / Native.SCREEN_W; // height / width
    private static final long FRAME_NS = 16_715_000L; // 59.8261 Hz

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
    private final short[] audioBuf = new short[Native.SAMPLE_RATE / 2];

    /** Where the screens are drawn, in view pixels. Only touched on the UI thread. */
    private final RectF dstTop = new RectF();
    private final RectF dstBottom = new RectF();
    /** The default (stacked) placement; the controls are arranged around it. */
    private final RectF defTop = new RectF();
    private final RectF defBottom = new RectF();
    private int viewW, viewH;
    private float unit = 1f;

    // Layout editor state (UI thread).
    private boolean editMode;
    private int editSel = -1;
    private boolean pinching;
    private float lastX, lastY, pinchStartDist, pinchStartW;
    private final RectF editDone = new RectF();
    private final RectF editReset = new RectF();
    private final RectF dpad = new RectF();
    private final RectF menuBtn = new RectF();
    private final ArrayList<Btn> buttons = new ArrayList<>();

    private final Paint ctrlFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ctrlStroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private Thread thread;
    private volatile boolean running;
    private volatile boolean paused = true;
    private volatile boolean noRom = true;
    private volatile int resScale = 1;

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
    public void setShader(int index) { renderer.setShader(index); glView.requestRender(); }
    public void setShowFps(boolean v) { showFps = v; overlay.invalidate(); }
    public void setShowControls(boolean v) { showControls = v; overlay.invalidate(); }
    public void setPaused(boolean v) { paused = v; }
    public void setPadKeys(int keys) { padKeys = keys; overlay.invalidate(); }

    /** Internal rendering resolution: 1 (native) to 4. */
    public void setResolutionScale(int scale) {
        scale = Math.max(1, Math.min(4, scale));
        Native.setResolutionScale(scale);
        resScale = scale;
    }

    public void onHostResume() { glView.onResume(); }
    public void onHostPause() { glView.onPause(); }
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

    // ------------------------------------------------------------------ layout
    private boolean portrait() { return viewH >= viewW; }
    private String presetKey() { return portrait() ? "layout_preset_p" : "layout_preset_l"; }
    private String customKey() { return portrait() ? "layout_custom_p" : "layout_custom_l"; }

    private static void place(RectF r, float left, float top, float width) {
        r.set(left, top, left + width, top + width * ASPECT);
    }

    /** Recomputes everything for the current view size and saved layout choice. */
    private void relayout() {
        if (viewW <= 0 || viewH <= 0) return;
        layoutControls(viewW, viewH);
        int preset = prefs.getInt(presetKey(), 0);
        if (preset == LAYOUT_CUSTOM && !loadCustom()) preset = 0;
        if (preset != LAYOUT_CUSTOM) applyPreset(preset);
        pushRects();
    }

    private void applyPreset(int preset) {
        final float w = viewW, h = viewH;
        if (preset < 1 || preset > 3) {
            dstTop.set(defTop);
            dstBottom.set(defBottom);
            return;
        }
        if (portrait()) {
            if (preset == 1) {
                place(dstTop, 0, 0, w / 2f);
                place(dstBottom, w / 2f, 0, w / 2f);
            } else if (preset == 2) {
                place(dstTop, 0, 0, w);
                place(dstBottom, w / 4f, w * ASPECT, w / 2f);
            } else {
                place(dstTop, w / 4f, 0, w / 2f);
                place(dstBottom, 0, w / 2f * ASPECT, w);
            }
        } else {
            if (preset == 1) {
                float a = Math.min(w / 2f, h / ASPECT);
                place(dstTop, 0, 0, a);
                place(dstBottom, a, 0, a);
            } else {
                float big = Math.min(h / ASPECT, w * 0.66f);
                float small = Math.min(w - big, big);
                float dy = (big - small) * ASPECT / 2f;
                if (preset == 2) {
                    place(dstTop, 0, 0, big);
                    place(dstBottom, big, dy, small);
                } else {
                    place(dstTop, 0, dy, small);
                    place(dstBottom, small, 0, big);
                }
            }
        }
        // Shrink to fit if needed, then centre (portrait keeps the screens at the top).
        float uw = Math.max(dstTop.right, dstBottom.right);
        float uh = Math.max(dstTop.bottom, dstBottom.bottom);
        float k = Math.min(1f, Math.min(w / uw, h / uh));
        float ox = (w - uw * k) / 2f;
        float oy = portrait() ? 0f : (h - uh * k) / 2f;
        place(dstTop, dstTop.left * k + ox, dstTop.top * k + oy, dstTop.width() * k);
        place(dstBottom, dstBottom.left * k + ox, dstBottom.top * k + oy, dstBottom.width() * k);
    }

    private boolean loadCustom() {
        String v = prefs.getString(customKey(), null);
        if (v == null) return false;
        String[] p = v.split(",");
        if (p.length != 6) return false;
        try {
            float[] f = new float[6];
            for (int i = 0; i < 6; i++) f[i] = Float.parseFloat(p[i]);
            place(dstTop, f[0] * viewW, f[1] * viewH, f[2] * viewW);
            place(dstBottom, f[3] * viewW, f[4] * viewH, f[5] * viewW);
            clampRect(dstTop);
            clampRect(dstBottom);
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private void saveCustom() {
        String v = String.format(Locale.US, "%.5f,%.5f,%.5f,%.5f,%.5f,%.5f",
                dstTop.left / viewW, dstTop.top / viewH, dstTop.width() / viewW,
                dstBottom.left / viewW, dstBottom.top / viewH, dstBottom.width() / viewW);
        prefs.edit().putString(customKey(), v).putInt(presetKey(), LAYOUT_CUSTOM).apply();
    }

    /** Keeps a screen inside the view and within the allowed size range. */
    private void clampRect(RectF r) {
        float maxW = Math.min(viewW, viewH / ASPECT);
        float minW = Math.min(maxW, Math.min(viewW, viewH) * 0.2f);
        float rw = Math.max(minW, Math.min(maxW, r.width()));
        float rh = rw * ASPECT;
        float cx = r.centerX(), cy = r.centerY();
        float left = Math.max(0f, Math.min(viewW - rw, cx - rw / 2f));
        float top = Math.max(0f, Math.min(viewH - rh, cy - rh / 2f));
        r.set(left, top, left + rw, top + rh);
    }

    private void pushRects() {
        renderer.setRects(new float[]{
                dstTop.left, dstTop.top, dstTop.width(), dstTop.height(),
                dstBottom.left, dstBottom.top, dstBottom.width(), dstBottom.height()});
        glView.requestRender();
        overlay.invalidate();
    }

    /** Picks one of the built-in arrangements for the current orientation. */
    public void setLayoutPreset(int preset) {
        prefs.edit().putInt(presetKey(), preset).apply();
        relayout();
    }

    public int getLayoutPreset() { return prefs.getInt(presetKey(), 0); }

    /** Enters the editor: drag a screen to move it, pinch to resize it. */
    public void startLayoutEdit() {
        editMode = true;
        editSel = -1;
        pinching = false;
        touchKeys = 0;
        stylusDown = false;
        stylusPointerId = -1;
        overlay.invalidate();
    }

    public boolean isEditingLayout() { return editMode; }

    public void finishLayoutEdit() {
        if (!editMode) return;
        editMode = false;
        saveCustom();
        overlay.invalidate();
    }

    private void layoutControls(int w, int h) {
        final float sw = Native.SCREEN_W, sh = Native.SCREEN_H;
        if (h >= w) {
            // Portrait: screens stacked at the top, controls underneath.
            float scale = Math.min(w / sw, (h * 0.62f) / (sh * 2));
            float left = (w - sw * scale) / 2f;
            defTop.set(left, 0, left + sw * scale, sh * scale);
            defBottom.set(left, sh * scale, left + sw * scale, sh * scale * 2);
            float top = defBottom.bottom;
            float areaH = h - top;
            unit = Math.min(w / 8f, areaH / 5.2f);
            float cy = top + unit * 0.9f + (areaH - unit * 0.9f) * 0.45f;
            placeControls(0, w, top, cy, unit, w / 2f, h - unit * 0.75f);
        } else {
            // Landscape: screens stacked in the centre, controls on both sides.
            float scale = Math.min(h / (sh * 2), (w * 0.5f) / sw);
            float left = (w - sw * scale) / 2f;
            float topY = (h - sh * scale * 2) / 2f;
            defTop.set(left, topY, left + sw * scale, topY + sh * scale);
            defBottom.set(left, defTop.bottom, left + sw * scale, defTop.bottom + sh * scale);
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
        // Layout editor buttons, bottom centre.
        float ew = unit * 3.4f, eh = unit * 0.8f, eg = unit * 0.2f, ey = h - eh * 1.4f;
        editReset.set(w / 2f - ew - eg, ey, w / 2f - eg, ey + eh);
        editDone.set(w / 2f + eg, ey, w / 2f + eg + ew, ey + eh);
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
                    applyPreset(0);
                    pushRects();
                    return true;
                }
                editSel = dstBottom.contains(x, y) ? 1 : (dstTop.contains(x, y) ? 0 : -1);
                pinching = false;
                lastX = x;
                lastY = y;
                break;
            }
            case MotionEvent.ACTION_POINTER_DOWN: {
                if (e.getPointerCount() >= 2) {
                    if (editSel < 0) {
                        float x = e.getX(1), y = e.getY(1);
                        editSel = dstBottom.contains(x, y) ? 1 : (dstTop.contains(x, y) ? 0 : -1);
                    }
                    if (editSel >= 0) {
                        pinchStartDist = Math.max(1f, (float) Math.hypot(e.getX(0) - e.getX(1), e.getY(0) - e.getY(1)));
                        pinchStartW = (editSel == 0 ? dstTop : dstBottom).width();
                        pinching = true;
                    }
                }
                break;
            }
            case MotionEvent.ACTION_MOVE: {
                if (editSel < 0) break;
                RectF r = (editSel == 0) ? dstTop : dstBottom;
                if (pinching && e.getPointerCount() >= 2) {
                    float d = (float) Math.hypot(e.getX(0) - e.getX(1), e.getY(0) - e.getY(1));
                    float nw = pinchStartW * d / pinchStartDist;
                    float cx = r.centerX(), cy = r.centerY();
                    r.set(cx - nw / 2f, cy - nw * ASPECT / 2f, cx + nw / 2f, cy + nw * ASPECT / 2f);
                } else if (!pinching) {
                    float x = e.getX(0), y = e.getY(0);
                    r.offset(x - lastX, y - lastY);
                    lastX = x;
                    lastY = y;
                }
                clampRect(r);
                pushRects();
                break;
            }
            case MotionEvent.ACTION_POINTER_UP: {
                pinching = false;
                // Continue dragging with whichever finger stays down, without a jump.
                int keep = (e.getActionIndex() == 0) ? 1 : 0;
                if (keep < e.getPointerCount()) {
                    lastX = e.getX(keep);
                    lastY = e.getY(keep);
                }
                if (e.getPointerCount() > 2) editSel = -1;
                break;
            }
            default:
                if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                    editSel = -1;
                    pinching = false;
                    overlay.invalidate();
                }
                break;
        }
        overlay.invalidate();
        return true;
    }

    private boolean handleTouch(MotionEvent e) {
        if (editMode) return editTouch(e);
        int action = e.getActionMasked();
        int idx = e.getActionIndex();

        if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_POINTER_DOWN) {
            float x = e.getX(idx), y = e.getY(idx);
            if (menuBtn.contains(x, y)) {
                listener.onMenuRequested();
                return true;
            }
            // A button drawn over the touch screen wins over the stylus.
            if (stylusPointerId < 0 && dstBottom.contains(x, y) && !onControl(x, y)) {
                stylusPointerId = e.getPointerId(idx);
            }
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
        if (keys != touchKeys) {
            touchKeys = keys;
            overlay.invalidate();
        }
        return true;
    }

    private boolean onControl(float x, float y) {
        if (!showControls) return false;
        if (dpad.contains(x, y)) return true;
        for (Btn b : buttons) {
            if (b.r.contains(x, y)) return true;
        }
        return false;
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

    // ------------------------------------------------------------------ overlay drawing
    /** Transparent view on top of the GL surface: controls, FPS text and the layout editor. */
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
            if (editMode) {
                drawEditor(c);
                return;
            }
            if (noRom) {
                float ts = textPaint.getTextSize();
                c.drawText("DeSmuME", dstTop.centerX(), dstTop.centerY(), textPaint);
                c.drawText("MENU \u2192 Abrir ROM", dstBottom.centerX(), dstBottom.centerY() + ts, textPaint);
            }
            drawControls(c);
            if (showFps && !noRom) {
                textPaint.setTextAlign(Paint.Align.LEFT);
                c.drawText(fpsText, dstTop.left + 8, dstTop.top + textPaint.getTextSize() + 4, textPaint);
                textPaint.setTextAlign(Paint.Align.CENTER);
            }
        }
    }

    private void drawEditor(Canvas c) {
        float txtOff = textPaint.getTextSize() * 0.35f;
        for (int i = 0; i < 2; i++) {
            RectF r = (i == 0) ? dstTop : dstBottom;
            ctrlFill.setColor(Color.argb(i == editSel ? 70 : 25, 255, 255, 255));
            c.drawRect(r.left, r.top, r.right, r.bottom, ctrlFill);
            float sw = ctrlStroke.getStrokeWidth() / 2f;
            c.drawRoundRect(r.left + sw, r.top + sw, r.right - sw, r.bottom - sw, 0f, 0f, ctrlStroke);
            String label = (i == 0 ? "Tela de cima" : "Tela de baixo (toque)")
                    + "  " + Math.round(r.width() / Native.SCREEN_W * 100f) + "%";
            c.drawText(label, r.centerX(), r.centerY() + txtOff, textPaint);
        }
        c.drawText("Arraste para mover \u2022 Pin\u00e7a com dois dedos para redimensionar",
                viewW / 2f, editDone.top - textPaint.getTextSize() * 0.8f, textPaint);
        ctrlFill.setColor(Color.argb(190, 30, 30, 30));
        float rr = editDone.height() / 2f;
        c.drawRoundRect(editReset, rr, rr, ctrlFill);
        c.drawRoundRect(editReset, rr, rr, ctrlStroke);
        c.drawText("Restaurar padr\u00e3o", editReset.centerX(), editReset.centerY() + txtOff, textPaint);
        ctrlFill.setColor(Color.argb(210, 20, 110, 60));
        c.drawRoundRect(editDone, rr, rr, ctrlFill);
        c.drawRoundRect(editDone, rr, rr, ctrlStroke);
        c.drawText("Concluir", editDone.centerX(), editDone.centerY() + txtOff, textPaint);
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
        int idleTicks = 0;
        long fpsT0 = System.nanoTime();
        int fpsFrames = 0, fpsDrawn = 0;

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

            Native.runFrame(touchKeys | padKeys, stylusDown, stylusX, stylusY, skipNext);
            totalFrames++;
            fpsFrames++;
            if ((totalFrames & 63) == 0) listener.onFramesRun(totalFrames);

            if (!skipNext) {
                if (renderer.fetchFrame(resScale)) glView.requestRender();
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
                fpsText = String.format(Locale.US, "%.0f fps (%.0f desenhados)",
                        fpsFrames / secs, fpsDrawn / secs);
                if (showFps) overlay.postInvalidate();
                fpsT0 = now;
                fpsFrames = 0;
                fpsDrawn = 0;
            }
        }
        closeAudio();
    }
}
