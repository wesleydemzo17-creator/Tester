package org.desmume.android;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
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
            "Esticadas: as duas preenchem a tela",
            "Tela de cima em tela cheia (esticada)",
    };
    public static final String[] CONTROL_STYLES = {
            "Clássico (translúcido)", "Contorno", "Colorido", "Escuro", "Só letras"};
    public static final String[] DPAD_STYLES = {"Cruz", "Disco", "Quatro botões"};
    public static final String[] HUD_CORNERS = {
            "superior esquerdo", "superior direito", "inferior esquerdo", "inferior direito"};
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
    private int editLast;
    private boolean pinching, resizing;
    private float lastX, lastY, pinchStartDist, pinchStartW, pinchStartH;
    private final RectF editDone = new RectF();
    private final RectF editReset = new RectF();
    private final RectF editAspect = new RectF();
    private final RectF editFill = new RectF();

    // Performance monitor (HUD).
    private final PerfMonitor perf;
    private final float density;
    private final Paint hudText = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint hudBack = new Paint();
    private volatile String[] hudLines = new String[0];
    private volatile boolean hudDetails = true;
    private volatile String rendererLabel = "CPU";
    private float hudScale = 1f;
    private int hudAlpha = 204;
    private int hudCorner;
    private final RectF dpad = new RectF();
    private final RectF menuBtn = new RectF();
    private final ArrayList<Btn> buttons = new ArrayList<>();

    // On-screen control customisation. Control 0 is the D-pad, 1..8 the buttons, 9 MENU.
    private static final int CTRL_COUNT = 10;
    private final float[] ctrlCx = new float[CTRL_COUNT];   // centre as a fraction of the view; NaN = default
    private final float[] ctrlCy = new float[CTRL_COUNT];
    private final float[] ctrlS = new float[CTRL_COUNT];    // size multiplier of this control
    private boolean ctrlEdit, ctrlPinching;
    private int ctrlSel = -1;
    private float ctrlPinchDist, ctrlPinchS;
    private float ctrlScale = 1f, ctrlOpacity = 0.5f;
    private int ctrlStyle, dpadStyle;
    private final RectF ceDone = new RectF(), ceReset = new RectF(), ceMinus = new RectF(), cePlus = new RectF();
    private final Path path = new Path();

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
    public void setRendererLabel(String label) { rendererLabel = label; }

    /**
     * Configures the performance monitor.
     * @param details show CPU, GPU, RAM and clock lines besides the FPS
     * @param scalePercent text size, 100 = normal
     * @param opacityPercent 100 = solid, lower = more see-through
     * @param corner index into HUD_CORNERS
     */
    public void setHud(boolean details, int scalePercent, int opacityPercent, int corner) {
        hudDetails = details;
        hudScale = Math.max(50, Math.min(300, scalePercent)) / 100f;
        hudAlpha = Math.max(10, Math.min(100, opacityPercent)) * 255 / 100;
        hudCorner = Math.max(0, Math.min(3, corner));
        if (!details && hudLines.length > 1) hudLines = new String[]{hudLines[0]};
        overlay.invalidate();
    }
    public void setShowControls(boolean v) { showControls = v; overlay.invalidate(); }
    public void setPaused(boolean v) { paused = v; }
    public void setPadKeys(int keys) { padKeys = keys; overlay.invalidate(); }

    /** Internal rendering resolution: 1 (native) to 4. */
    public void setResolutionScale(int scale) {
        scale = Math.max(1, Math.min(4, scale));
        Native.setResolutionScale(scale);
        resScale = scale;
    }

    /**
     * Look of the on-screen controls.
     * @param style index into CONTROL_STYLES
     * @param dpad index into DPAD_STYLES
     * @param opacityPercent 10..100
     * @param scalePercent overall size, 100 = normal
     */
    public void setControlLook(int style, int dpad, int opacityPercent, int scalePercent) {
        ctrlStyle = Math.max(0, Math.min(CONTROL_STYLES.length - 1, style));
        dpadStyle = Math.max(0, Math.min(DPAD_STYLES.length - 1, dpad));
        ctrlOpacity = Math.max(10, Math.min(100, opacityPercent)) / 100f;
        ctrlScale = Math.max(50, Math.min(200, scalePercent)) / 100f;
        if (viewW > 0) layoutControls(viewW, viewH);
        overlay.invalidate();
    }

    /** Enters the control editor: drag a control to move it, pinch or use − / + to resize it. */
    public void startControlEdit() {
        ctrlEdit = true;
        ctrlSel = -1;
        ctrlPinching = false;
        touchKeys = 0;
        stylusDown = false;
        stylusPointerId = -1;
        overlay.invalidate();
    }

    public boolean isEditingControls() { return ctrlEdit; }

    public void finishControlEdit() {
        if (!ctrlEdit) return;
        ctrlEdit = false;
        saveControls();
        overlay.invalidate();
    }

    /** Puts every control back in its default place and size, for the current orientation. */
    public void resetControls() {
        prefs.edit().remove(controlsKey()).apply();
        if (viewW > 0) layoutControls(viewW, viewH);
        overlay.invalidate();
    }

    private String controlsKey() { return portrait() ? "ctrl_custom_p" : "ctrl_custom_l"; }

    private RectF ctrlRect(int i) {
        return i == 0 ? dpad : (i == CTRL_COUNT - 1 ? menuBtn : buttons.get(i - 1).r);
    }

    private void loadControls() {
        for (int i = 0; i < CTRL_COUNT; i++) {
            ctrlCx[i] = ctrlCy[i] = Float.NaN;
            ctrlS[i] = 1f;
        }
        String v = prefs.getString(controlsKey(), null);
        if (v == null) return;
        String[] items = v.split(";");
        for (int i = 0; i < items.length && i < CTRL_COUNT; i++) {
            String[] p = items[i].split(",");
            if (p.length != 3) continue;
            try {
                float cx = Float.parseFloat(p[0]), cy = Float.parseFloat(p[1]), sc = Float.parseFloat(p[2]);
                if (cx >= 0f) { ctrlCx[i] = cx; ctrlCy[i] = cy; }
                ctrlS[i] = Math.max(0.4f, Math.min(3f, sc));
            } catch (NumberFormatException ignored) {
                // Keep the default for this control.
            }
        }
    }

    private void saveControls() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < CTRL_COUNT; i++) {
            if (i > 0) sb.append(';');
            boolean moved = !Float.isNaN(ctrlCx[i]);
            sb.append(String.format(Locale.US, "%.5f,%.5f,%.3f", moved ? ctrlCx[i] : -1f, moved ? ctrlCy[i] : -1f, ctrlS[i]));
        }
        prefs.edit().putString(controlsKey(), sb.toString()).apply();
    }

    /** Applies the saved position and size of each control on top of the default layout. */
    private void applyControlCustom() {
        for (int i = 0; i < CTRL_COUNT; i++) {
            RectF r = ctrlRect(i);
            float w = r.width() * ctrlS[i] * ctrlScale, h = r.height() * ctrlS[i] * ctrlScale;
            float cx = Float.isNaN(ctrlCx[i]) ? r.centerX() : ctrlCx[i] * viewW;
            float cy = Float.isNaN(ctrlCy[i]) ? r.centerY() : ctrlCy[i] * viewH;
            cx = Math.max(w / 2f, Math.min(viewW - w / 2f, cx));
            cy = Math.max(h / 2f, Math.min(viewH - h / 2f, cy));
            r.set(cx - w / 2f, cy - h / 2f, cx + w / 2f, cy + h / 2f);
        }
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
        if (preset < 1 || preset > 5) {
            dstTop.set(defTop);
            dstBottom.set(defBottom);
            return;
        }
        if (preset == 4) {
            // Both screens stretched to cover the whole view.
            if (portrait()) {
                dstTop.set(0, 0, w, h / 2f);
                dstBottom.set(0, h / 2f, w, h);
            } else {
                dstTop.set(0, 0, w / 2f, h);
                dstBottom.set(w / 2f, 0, w, h);
            }
            return;
        }
        if (preset == 5) {
            // Top screen stretched over everything, touch screen as a small window in the corner.
            dstTop.set(0, 0, w, h);
            float bw = portrait() ? w * 0.4f : w * 0.26f;
            dstBottom.set(w - bw, h - bw * ASPECT, w, h);
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
        if (p.length != 6 && p.length != 8) return false;
        try {
            float[] f = new float[p.length];
            for (int i = 0; i < p.length; i++) f[i] = Float.parseFloat(p[i]);
            if (p.length == 6) {
                // Older format: left, top, width (height followed the 4:3 shape).
                place(dstTop, f[0] * viewW, f[1] * viewH, f[2] * viewW);
                place(dstBottom, f[3] * viewW, f[4] * viewH, f[5] * viewW);
            } else {
                // left, top, width, height, each as a fraction of the view.
                dstTop.set(f[0] * viewW, f[1] * viewH, (f[0] + f[2]) * viewW, (f[1] + f[3]) * viewH);
                dstBottom.set(f[4] * viewW, f[5] * viewH, (f[4] + f[6]) * viewW, (f[5] + f[7]) * viewH);
            }
            clampRect(dstTop);
            clampRect(dstBottom);
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private void saveCustom() {
        String v = String.format(Locale.US, "%.5f,%.5f,%.5f,%.5f,%.5f,%.5f,%.5f,%.5f",
                dstTop.left / viewW, dstTop.top / viewH, dstTop.width() / viewW, dstTop.height() / viewH,
                dstBottom.left / viewW, dstBottom.top / viewH, dstBottom.width() / viewW, dstBottom.height() / viewH);
        prefs.edit().putString(customKey(), v).putInt(presetKey(), LAYOUT_CUSTOM).apply();
    }

    private float minScreenW() { return Math.min(viewW, viewH) * 0.2f; }
    private float minScreenH() { return minScreenW() * ASPECT; }

    /** Keeps a screen inside the view and within the allowed size range. Width and height are independent. */
    private void clampRect(RectF r) {
        float rw = Math.max(Math.min(minScreenW(), viewW), Math.min(viewW, r.width()));
        float rh = Math.max(Math.min(minScreenH(), viewH), Math.min(viewH, r.height()));
        float cx = r.centerX(), cy = r.centerY();
        float left = Math.max(0f, Math.min(viewW - rw, cx - rw / 2f));
        float top = Math.max(0f, Math.min(viewH - rh, cy - rh / 2f));
        r.set(left, top, left + rw, top + rh);
    }

    /** The square in a screen's bottom-right corner that stretches it freely in the editor. */
    private boolean onHandle(RectF r, float x, float y) {
        float hs = Math.min(unit * 1.1f, Math.min(r.width(), r.height()) * 0.45f);
        return x >= r.right - hs && x <= r.right + hs * 0.3f && y >= r.bottom - hs && y <= r.bottom + hs * 0.3f;
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
        editLast = 0;
        pinching = false;
        resizing = false;
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
        if (!ctrlEdit) loadControls(); // while editing, the arrays hold the unsaved changes
        applyControlCustom();

        // Control editor buttons, across the middle.
        float cg = unit * 0.15f;
        float cw = Math.min(unit * 2.4f, (w - cg * 5f) / 4f), ch = unit * 0.85f, cyy = h * 0.5f - ch / 2f;
        float cx0 = (w - cw * 4f - cg * 3f) / 2f;
        ceReset.set(cx0, cyy, cx0 + cw, cyy + ch);
        ceMinus.set(cx0 + (cw + cg), cyy, cx0 + (cw + cg) + cw, cyy + ch);
        cePlus.set(cx0 + (cw + cg) * 2f, cyy, cx0 + (cw + cg) * 2f + cw, cyy + ch);
        ceDone.set(cx0 + (cw + cg) * 3f, cyy, cx0 + (cw + cg) * 3f + cw, cyy + ch);

        // Layout editor buttons, bottom centre.
        float eg = unit * 0.15f;
        float ew = Math.min(unit * 2.6f, (w - eg * 5f) / 4f), eh = unit * 0.8f, ey = h - eh * 1.4f;
        float ex = (w - ew * 4f - eg * 3f) / 2f;
        editReset.set(ex, ey, ex + ew, ey + eh);
        editAspect.set(ex + (ew + eg), ey, ex + (ew + eg) + ew, ey + eh);
        editFill.set(ex + (ew + eg) * 2f, ey, ex + (ew + eg) * 2f + ew, ey + eh);
        editDone.set(ex + (ew + eg) * 3f, ey, ex + (ew + eg) * 3f + ew, ey + eh);
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
                if (editAspect.contains(x, y) || editFill.contains(x, y)) {
                    RectF r = (editLast == 0) ? dstTop : dstBottom;
                    if (editFill.contains(x, y)) {
                        r.set(0, 0, viewW, viewH);
                    } else {
                        // Back to the DS shape, keeping the width (or the height if that would not fit).
                        float nw = Math.min(r.width(), viewH / ASPECT);
                        float cx = r.centerX(), cy = r.centerY();
                        r.set(cx - nw / 2f, cy - nw * ASPECT / 2f, cx + nw / 2f, cy + nw * ASPECT / 2f);
                        clampRect(r);
                    }
                    pushRects();
                    return true;
                }
                resizing = false;
                if (onHandle(dstBottom, x, y)) { editSel = 1; resizing = true; }
                else if (onHandle(dstTop, x, y)) { editSel = 0; resizing = true; }
                else editSel = dstBottom.contains(x, y) ? 1 : (dstTop.contains(x, y) ? 0 : -1);
                if (editSel >= 0) editLast = editSel;
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
                        editLast = editSel;
                        pinchStartDist = Math.max(1f, (float) Math.hypot(e.getX(0) - e.getX(1), e.getY(0) - e.getY(1)));
                        pinchStartW = (editSel == 0 ? dstTop : dstBottom).width();
                        pinchStartH = (editSel == 0 ? dstTop : dstBottom).height();
                        pinching = true;
                        resizing = false;
                    }
                }
                break;
            }
            case MotionEvent.ACTION_MOVE: {
                if (editSel < 0) break;
                RectF r = (editSel == 0) ? dstTop : dstBottom;
                if (pinching && e.getPointerCount() >= 2) {
                    // Pinch scales the screen while keeping whatever shape it has now.
                    float d = (float) Math.hypot(e.getX(0) - e.getX(1), e.getY(0) - e.getY(1));
                    float k = d / pinchStartDist;
                    k = Math.min(k, Math.min(viewW / pinchStartW, viewH / pinchStartH));
                    float nw = pinchStartW * k, nh = pinchStartH * k;
                    float cx = r.centerX(), cy = r.centerY();
                    r.set(cx - nw / 2f, cy - nh / 2f, cx + nw / 2f, cy + nh / 2f);
                } else if (resizing) {
                    // Corner handle: stretch width and height independently.
                    float x = e.getX(0), y = e.getY(0);
                    float right = Math.max(r.left + minScreenW(), Math.min(viewW, r.right + (x - lastX)));
                    float bottom = Math.max(r.top + minScreenH(), Math.min(viewH, r.bottom + (y - lastY)));
                    r.set(r.left, r.top, right, bottom);
                    lastX = x;
                    lastY = y;
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
                    resizing = false;
                    overlay.invalidate();
                }
                break;
        }
        overlay.invalidate();
        return true;
    }

    private boolean controlEditTouch(MotionEvent e) {
        int action = e.getActionMasked();
        switch (action) {
            case MotionEvent.ACTION_DOWN: {
                float x = e.getX(0), y = e.getY(0);
                ctrlPinching = false;
                if (ceDone.contains(x, y)) {
                    finishControlEdit();
                    return true;
                }
                if (ceReset.contains(x, y)) {
                    for (int i = 0; i < CTRL_COUNT; i++) {
                        ctrlCx[i] = ctrlCy[i] = Float.NaN;
                        ctrlS[i] = 1f;
                    }
                    ctrlSel = -1;
                } else if (ceMinus.contains(x, y) || cePlus.contains(x, y)) {
                    if (ctrlSel >= 0) {
                        float k = cePlus.contains(x, y) ? 1.1f : 1f / 1.1f;
                        ctrlS[ctrlSel] = Math.max(0.4f, Math.min(3f, ctrlS[ctrlSel] * k));
                    }
                } else {
                    // Topmost control under the finger; small ones get a little extra reach.
                    int hit = -1;
                    for (int i = CTRL_COUNT - 1; i >= 0 && hit < 0; i--) {
                        RectF r = ctrlRect(i);
                        float slop = Math.max(0f, unit * 0.4f - Math.min(r.width(), r.height()) / 2f);
                        if (x >= r.left - slop && x <= r.right + slop && y >= r.top - slop && y <= r.bottom + slop) hit = i;
                    }
                    ctrlSel = hit;
                    lastX = x;
                    lastY = y;
                }
                break;
            }
            case MotionEvent.ACTION_POINTER_DOWN:
                if (ctrlSel >= 0 && e.getPointerCount() >= 2) {
                    ctrlPinchDist = Math.max(1f, (float) Math.hypot(e.getX(0) - e.getX(1), e.getY(0) - e.getY(1)));
                    ctrlPinchS = ctrlS[ctrlSel];
                    ctrlPinching = true;
                }
                break;
            case MotionEvent.ACTION_MOVE: {
                if (ctrlSel < 0) return true;
                if (ctrlPinching && e.getPointerCount() >= 2) {
                    float d = (float) Math.hypot(e.getX(0) - e.getX(1), e.getY(0) - e.getY(1));
                    ctrlS[ctrlSel] = Math.max(0.4f, Math.min(3f, ctrlPinchS * d / ctrlPinchDist));
                } else if (!ctrlPinching) {
                    RectF r = ctrlRect(ctrlSel);
                    float x = e.getX(0), y = e.getY(0);
                    ctrlCx[ctrlSel] = Math.max(0f, Math.min(1f, (r.centerX() + x - lastX) / viewW));
                    ctrlCy[ctrlSel] = Math.max(0f, Math.min(1f, (r.centerY() + y - lastY) / viewH));
                    lastX = x;
                    lastY = y;
                }
                break;
            }
            case MotionEvent.ACTION_POINTER_UP: {
                ctrlPinching = false;
                int keep = (e.getActionIndex() == 0) ? 1 : 0;
                if (keep < e.getPointerCount()) {
                    lastX = e.getX(keep);
                    lastY = e.getY(keep);
                }
                break;
            }
            default:
                ctrlPinching = false;
                return true;
        }
        layoutControls(viewW, viewH);
        overlay.invalidate();
        return true;
    }

    private boolean handleTouch(MotionEvent e) {
        if (ctrlEdit) return controlEditTouch(e);
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
            ctrlEdit = false; // unsaved control edits belong to the previous orientation
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
            if (ctrlEdit) {
                drawControlEditor(c);
                return;
            }
            if (noRom) {
                float ts = textPaint.getTextSize();
                c.drawText("DeSmuME", dstTop.centerX(), dstTop.centerY(), textPaint);
                c.drawText("MENU \u2192 Abrir ROM", dstBottom.centerX(), dstBottom.centerY() + ts, textPaint);
            }
            drawControls(c);
            if (showFps && !noRom) drawHud(c);
        }
    }

    /** Performance monitor: a small text box in one corner of the view. */
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

    private void drawPill(Canvas c, RectF r, String label, int color) {
        float rr = r.height() / 2f;
        ctrlFill.setColor(color);
        c.drawRoundRect(r, rr, rr, ctrlFill);
        c.drawRoundRect(r, rr, rr, ctrlStroke);
        c.drawText(label, r.centerX(), r.centerY() + textPaint.getTextSize() * 0.35f, textPaint);
    }

    private void drawEditor(Canvas c) {
        float txtOff = textPaint.getTextSize() * 0.35f;
        for (int i = 0; i < 2; i++) {
            RectF r = (i == 0) ? dstTop : dstBottom;
            ctrlFill.setColor(Color.argb(i == editLast ? 60 : 25, 255, 255, 255));
            c.drawRect(r.left, r.top, r.right, r.bottom, ctrlFill);
            float sw = ctrlStroke.getStrokeWidth() / 2f;
            c.drawRoundRect(r.left + sw, r.top + sw, r.right - sw, r.bottom - sw, 0f, 0f, ctrlStroke);
            String label = (i == 0 ? "Cima" : "Baixo (toque)") + "  "
                    + Math.round(r.width() / Native.SCREEN_W * 100f) + "% \u00d7 "
                    + Math.round(r.height() / Native.SCREEN_H * 100f) + "%";
            c.drawText(label, r.centerX(), r.centerY() + txtOff, textPaint);
            // Stretch handle in the bottom-right corner.
            float hs = Math.min(unit * 0.55f, Math.min(r.width(), r.height()) * 0.25f);
            ctrlFill.setColor(Color.argb(230, 255, 255, 255));
            c.drawRect(r.right - hs, r.bottom - hs, r.right, r.bottom, ctrlFill);
        }
        float ts = textPaint.getTextSize();
        c.drawText("Arraste: mover \u2022 Pin\u00e7a: tamanho \u2022 Quadrado do canto: esticar",
                viewW / 2f, editDone.top - ts * 0.8f, textPaint);
        int dark = Color.argb(200, 30, 30, 30);
        drawPill(c, editReset, "Padr\u00e3o", dark);
        drawPill(c, editAspect, "4:3", dark);
        drawPill(c, editFill, "Tela cheia", dark);
        drawPill(c, editDone, "Concluir", Color.argb(220, 20, 110, 60));
    }

    // Tints for the "Colorido" style.
    private static final int TINT_A = 0xE53935, TINT_B = 0xFDD835, TINT_X = 0x1E88E5, TINT_Y = 0x43A047,
            TINT_SHOULDER = 0x9E9E9E, TINT_NONE = 0xFFFFFF;

    private static int tintOf(int key) {
        if (key == Native.KEY_A) return TINT_A;
        if (key == Native.KEY_B) return TINT_B;
        if (key == Native.KEY_X) return TINT_X;
        if (key == Native.KEY_Y) return TINT_Y;
        if (key == Native.KEY_L || key == Native.KEY_R) return TINT_SHOULDER;
        return TINT_NONE;
    }

    private int alpha(int base) { return Math.max(0, Math.min(255, Math.round(base * ctrlOpacity))); }

    /** Sets ctrlFill for a control in the current style. Returns false if nothing should be filled. */
    private boolean fillFor(boolean down, int tint) {
        int a, rgb;
        switch (ctrlStyle) {
            case 1: a = down ? alpha(260) : 0; rgb = 0xFFFFFF; break;
            case 2: a = alpha(down ? 440 : 230); rgb = tint; break;
            case 3: a = alpha(340); rgb = down ? 0x707070 : 0x181818; break;
            case 4: a = down ? alpha(200) : 0; rgb = 0xFFFFFF; break;
            default: a = alpha(down ? 300 : 120); rgb = 0xFFFFFF; break;
        }
        ctrlFill.setColor(Color.argb(a, (rgb >> 16) & 0xFF, (rgb >> 8) & 0xFF, rgb & 0xFF));
        return a > 0;
    }

    private boolean strokes() { return ctrlStyle != 4; }

    /** Paints for the controls follow the chosen opacity; other overlay drawing uses fixed ones. */
    private void controlPaints(boolean on) {
        ctrlStroke.setColor(Color.argb(on ? alpha(ctrlStyle == 1 ? 420 : 300) : 150, 255, 255, 255));
        textPaint.setColor(Color.argb(on ? alpha(440) : 210, 255, 255, 255));
        if (!on) textPaint.setTextSize(unit * 0.42f);
    }

    private void drawButton(Canvas c, RectF r, String label, boolean round, boolean down, int tint) {
        float rr = round ? 0f : r.height() / 2f;
        if (fillFor(down, tint)) {
            if (round) c.drawOval(r, ctrlFill);
            else c.drawRoundRect(r, rr, rr, ctrlFill);
        }
        if (strokes()) {
            if (round) c.drawOval(r, ctrlStroke);
            else c.drawRoundRect(r, rr, rr, ctrlStroke);
        }
        float size = Math.min(r.height() * 0.5f, r.width() * 1.5f / Math.max(2, label.length()));
        textPaint.setTextSize(size);
        c.drawText(label, r.centerX(), r.centerY() + size * 0.35f, textPaint);
    }

    /** A small arrow head pointing in direction (dx, dy), centred on (cx, cy). */
    private void drawArrow(Canvas c, float cx, float cy, float dx, float dy, float size, boolean down) {
        path.reset();
        path.moveTo(cx + dx * size, cy + dy * size);
        path.lineTo(cx - dx * size * 0.5f - dy * size * 0.8f, cy - dy * size * 0.5f + dx * size * 0.8f);
        path.lineTo(cx - dx * size * 0.5f + dy * size * 0.8f, cy - dy * size * 0.5f - dx * size * 0.8f);
        path.close();
        ctrlFill.setColor(Color.argb(alpha(down ? 510 : 300), 255, 255, 255));
        c.drawPath(path, ctrlFill);
    }

    private void drawDpad(Canvas c, int keys) {
        float cx = dpad.centerX(), cy = dpad.centerY(), arm = dpad.width() / 2f, th = dpad.width() / 6f;
        boolean left = (keys & Native.KEY_LEFT) != 0, right = (keys & Native.KEY_RIGHT) != 0;
        boolean up = (keys & Native.KEY_UP) != 0, dn = (keys & Native.KEY_DOWN) != 0;

        if (dpadStyle == 1) {
            // Disc with four arrows.
            if (fillFor(false, TINT_NONE)) c.drawOval(dpad, ctrlFill);
            if (strokes()) c.drawOval(dpad, ctrlStroke);
            float d = arm * 0.62f, sz = arm * 0.2f;
            drawArrow(c, cx - d, cy, -1, 0, sz, left);
            drawArrow(c, cx + d, cy, 1, 0, sz, right);
            drawArrow(c, cx, cy - d, 0, -1, sz, up);
            drawArrow(c, cx, cy + d, 0, 1, sz, dn);
            return;
        }
        if (dpadStyle == 2) {
            // Four separate buttons.
            float bs = arm * 0.62f, d = arm - bs / 2f, rr = bs * 0.25f, sz = bs * 0.26f;
            float[][] at = {{-d, 0, -1, 0}, {d, 0, 1, 0}, {0, -d, 0, -1}, {0, d, 0, 1}};
            boolean[] down = {left, right, up, dn};
            for (int i = 0; i < 4; i++) {
                float bx = cx + at[i][0], by = cy + at[i][1];
                if (fillFor(down[i], TINT_NONE)) c.drawRoundRect(bx - bs / 2f, by - bs / 2f, bx + bs / 2f, by + bs / 2f, rr, rr, ctrlFill);
                if (strokes()) c.drawRoundRect(bx - bs / 2f, by - bs / 2f, bx + bs / 2f, by + bs / 2f, rr, rr, ctrlStroke);
                drawArrow(c, bx, by, at[i][2], at[i][3], sz, down[i]);
            }
            return;
        }
        // Cross.
        if (fillFor(false, TINT_NONE)) {
            c.drawRoundRect(cx - arm, cy - th, cx + arm, cy + th, th * 0.4f, th * 0.4f, ctrlFill);
            c.drawRect(cx - th, cy - arm + th * 0.2f, cx + th, cy - th, ctrlFill);
            c.drawRect(cx - th, cy + th, cx + th, cy + arm - th * 0.2f, ctrlFill);
        }
        if (strokes()) {
            c.drawRoundRect(cx - arm, cy - th, cx + arm, cy + th, th * 0.4f, th * 0.4f, ctrlStroke);
            c.drawRoundRect(cx - th, cy - arm, cx + th, cy + arm, th * 0.4f, th * 0.4f, ctrlStroke);
        }
        float d = arm - th, sz = th * 0.45f;
        drawArrow(c, cx - d, cy, -1, 0, sz, left);
        drawArrow(c, cx + d, cy, 1, 0, sz, right);
        drawArrow(c, cx, cy - d, 0, -1, sz, up);
        drawArrow(c, cx, cy + d, 0, 1, sz, dn);
    }

    private void drawControls(Canvas c) {
        int keys = touchKeys | padKeys;
        controlPaints(true);

        // MENU is always drawn so the app can never get stuck without it.
        drawButton(c, menuBtn, "MENU", false, false, TINT_NONE);
        if (showControls || ctrlEdit) {
            drawDpad(c, keys);
            for (Btn b : buttons) {
                drawButton(c, b.r, b.label, b.round, (keys & b.key) != 0, tintOf(b.key));
            }
        }
        controlPaints(false);
    }

    private void drawControlEditor(Canvas c) {
        drawControls(c);
        if (ctrlSel >= 0) {
            RectF r = ctrlRect(ctrlSel);
            ctrlFill.setColor(Color.argb(60, 80, 200, 120));
            c.drawRect(r.left, r.top, r.right, r.bottom, ctrlFill);
            c.drawRoundRect(r.left, r.top, r.right, r.bottom, 0f, 0f, ctrlStroke);
        }
        float ts = textPaint.getTextSize();
        c.drawText("Toque e arraste um controle \u2022 Pin\u00e7a ou \u2212 / + mudam o tamanho",
                viewW / 2f, ceDone.top - ts * 0.8f, textPaint);
        int dark = Color.argb(210, 30, 30, 30);
        drawPill(c, ceReset, "Padr\u00e3o", dark);
        drawPill(c, ceMinus, "\u2212", dark);
        drawPill(c, cePlus, "+", dark);
        drawPill(c, ceDone, "Concluir", Color.argb(220, 20, 110, 60));
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
                if (showFps) {
                    hudLines = hudDetails
                            ? perf.sample(fpsText, "3D: " + rendererLabel + " \u00b7 " + resScale + "x")
                            : new String[]{fpsText};
                    overlay.postInvalidate();
                }
                fpsT0 = now;
                fpsFrames = 0;
                fpsDrawn = 0;
            }
        }
        closeAudio();
    }
}
