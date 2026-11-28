package org.emu.hub;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.LruCache;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * A menu in the style of the PSP's XMB: a row of categories across the screen and, under the
 * selected one, a vertical list of games. The selected game's cover fills the background and its
 * recorded preview plays in a panel. Everything is drawn here; the activity supplies the content.
 */
public class XmbView extends View {

    // ------------------------------------------------------------------ content
    public static final int ICON_SETTINGS = 0, ICON_RECENT = 1, ICON_DS = 2, ICON_GBA = 3, ICON_GB = 4;

    public static final class Item {
        public final String title;
        public String subtitle;
        public String value;          // shown on the right, for settings
        public final Library.Game game;
        public final Runnable action; // for items that are not games

        public Item(Library.Game game) {
            this.game = game;
            this.title = game.title;
            this.subtitle = game.systemLabel() + (game.subtitle.isEmpty() ? "" : " · " + game.subtitle)
                    + (game.ownSettings ? " · configurações próprias" : "");
            this.action = null;
        }

        public Item(String title, String value, Runnable action) {
            this.game = null;
            this.title = title;
            this.value = value;
            this.subtitle = null;
            this.action = action;
        }
    }

    public static final class Category {
        public final String name;
        public final int icon;
        public final List<Item> items = new ArrayList<>();
        float pos;      // animated scroll position
        int selected;

        public Category(String name, int icon) {
            this.name = name;
            this.icon = icon;
        }
    }

    public interface Listener {
        void onActivate(Item item);
        void onOptions(Item item);
    }

    private final List<Category> categories = new ArrayList<>();
    private Listener listener;
    private XmbTheme theme;

    private int catSel;
    private float catPos;

    // ------------------------------------------------------------------ drawing state
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pixel = new Paint();       // nearest-neighbour, for pixel-art icons
    private final Paint smooth = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF r = new RectF();
    private final Rect src = new Rect();
    private final RectF previewRect = new RectF();
    private final float density;
    private final SimpleDateFormat clockFmt = new SimpleDateFormat("HH:mm", Locale.getDefault());
    private final SimpleDateFormat dateFmt = new SimpleDateFormat("EEE, d MMM", new Locale("pt", "BR"));
    private long lastFrame = SystemClock.uptimeMillis();
    private final long start = SystemClock.uptimeMillis();
    private boolean attached;

    // Background crossfade between covers.
    private File coverNow, coverPrev;
    private long coverChanged;
    private Bitmap userBackground;
    private File userBackgroundFile;

    // Preview playback.
    private Library.Game previewGame;
    private List<Bitmap> previewFrames = new ArrayList<>();
    private long previewStart;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable loadPreview = this::startPreviewLoad;

    // ------------------------------------------------------------------ images
    private final LruCache<String, Bitmap> cache;
    private final java.util.Set<String> loading = new java.util.HashSet<>();
    private final ExecutorService loader = Executors.newFixedThreadPool(2);

    public XmbView(Context context) {
        super(context);
        density = context.getResources().getDisplayMetrics().density;
        int maxKb = (int) Math.min(48 * 1024, Runtime.getRuntime().maxMemory() / 1024 / 6);
        cache = new LruCache<String, Bitmap>(maxKb) {
            @Override protected int sizeOf(String key, Bitmap b) { return b.getByteCount() / 1024; }
        };
        pixel.setFilterBitmap(false);
        stroke.setStyle(Paint.Style.STROKE);
        text.setColor(Color.WHITE);
        setFocusable(true);
        setFocusableInTouchMode(true);
    }

    public void setListener(Listener l) { listener = l; }

    public void setTheme(XmbTheme t) {
        theme = t;
        invalidate();
    }

    /** Replaces the content, keeping the selection where possible. */
    public void setCategories(List<Category> list, int preferredCategory) {
        String oldName = categories.isEmpty() ? null : categories.get(catSel).name;
        for (Category fresh : list) {
            for (Category old : categories) {
                if (old.name.equals(fresh.name)) {
                    fresh.selected = Math.min(old.selected, Math.max(0, fresh.items.size() - 1));
                    fresh.pos = fresh.selected;
                }
            }
        }
        categories.clear();
        categories.addAll(list);
        int target = preferredCategory;
        if (oldName != null) {
            for (int i = 0; i < categories.size(); i++) if (categories.get(i).name.equals(oldName)) target = i;
        }
        catSel = Math.max(0, Math.min(categories.size() - 1, target));
        if (oldName == null) catPos = catSel;
        selectionChanged();
        invalidate();
    }

    public Item selectedItem() {
        if (categories.isEmpty()) return null;
        Category c = categories.get(catSel);
        return c.items.isEmpty() ? null : c.items.get(c.selected);
    }

    /** Forgets cached pictures of one game (after its cover or preview changed). */
    public void refreshImages(Library.Game g) {
        if (g == null) {
            cache.evictAll();
        } else {
            for (String k : cache.snapshot().keySet()) if (k.startsWith(g.dir.getPath())) cache.remove(k);
        }
        if (previewGame != null && (g == null || previewGame.key.equals(g.key))) {
            previewGame = null;
            selectionChanged();
        }
        postInvalidate();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        attached = true;
        lastFrame = SystemClock.uptimeMillis();
        invalidate();
    }

    @Override
    protected void onDetachedFromWindow() {
        attached = false;
        handler.removeCallbacksAndMessages(null);
        super.onDetachedFromWindow();
    }

    // ------------------------------------------------------------------ geometry
    private boolean portrait() { return getHeight() >= getWidth(); }
    private float unit() { return Math.min(getWidth(), getHeight()) / 10f; }
    private float rowY() { return getHeight() * (portrait() ? 0.15f : 0.22f); }
    private float catSpacing() { return unit() * 2.5f; }
    private float catX() { return getWidth() * (portrait() ? 0.2f : 0.17f); }
    private float iconScale() { return (theme != null ? theme.iconScale : 100) / 100f; }
    private float itemPitch() { return unit() * 1.75f * iconScale(); }
    private float firstItemY() { return rowY() + unit() * 2.3f; }

    private float aspectOf(Library.Game g) {
        if (g == null) return 0.75f;
        switch (g.system) {
            case RomInfo.NDS: return 0.75f;
            case RomInfo.GBA: return 2f / 3f;
            default: return 0.9f;
        }
    }

    private void placePreview(Library.Game g) {
        float w = getWidth(), h = getHeight(), a = aspectOf(g);
        if (portrait()) {
            float pw = w * 0.86f, ph = pw * a;
            float top = h - ph - unit() * 0.6f;
            previewRect.set((w - pw) / 2f, top, (w + pw) / 2f, top + ph);
        } else {
            float pw = w * 0.36f, ph = pw * a;
            float left = w * 0.6f, top = h * 0.33f;
            if (top + ph > h * 0.95f) {
                ph = h * 0.62f;
                pw = ph / a;
            }
            previewRect.set(left, top, left + pw, top + ph);
        }
    }

    // ------------------------------------------------------------------ drawing
    @Override
    protected void onDraw(Canvas c) {
        if (theme == null) return;
        long now = SystemClock.uptimeMillis();
        float dt = Math.min(0.05f, (now - lastFrame) / 1000f);
        lastFrame = now;
        boolean animating = animate(dt);

        drawBackground(c, now);
        Item sel = selectedItem();
        Library.Game game = sel != null ? sel.game : null;
        if (game != null && theme.moving) drawPreview(c, game, now);
        drawCategories(c);
        drawItems(c);
        if (theme.clock) drawClock(c);

        boolean previewMoving = game != null && theme.moving && previewFrames.size() > 1;
        boolean coverFading = now - coverChanged < 600;
        if (attached && (animating || coverFading || previewMoving || theme.waveSpeed() > 0f || theme.colorIndex >= 9)) {
            postInvalidateOnAnimation();
        }
    }

    /** Moves the animated positions toward their targets. Returns true while still moving. */
    private boolean animate(float dt) {
        float k = Math.min(1f, dt * 14f);
        boolean moving = false;
        if (!dragging || dragAxis != AXIS_H) {
            float d = catSel - catPos;
            catPos += d * k;
            if (Math.abs(d) > 0.002f) moving = true; else catPos = catSel;
        }
        for (int i = 0; i < categories.size(); i++) {
            Category cat = categories.get(i);
            if (dragging && dragAxis == AXIS_V && i == catSel) continue;
            float d = cat.selected - cat.pos;
            cat.pos += d * k;
            if (Math.abs(d) > 0.002f) moving = true; else cat.pos = cat.selected;
        }
        return moving;
    }

    private static int mix(int a, int b, float t) {
        int r = (int) (((a >> 16) & 0xFF) * (1 - t) + ((b >> 16) & 0xFF) * t);
        int g = (int) (((a >> 8) & 0xFF) * (1 - t) + ((b >> 8) & 0xFF) * t);
        int bl = (int) ((a & 0xFF) * (1 - t) + (b & 0xFF) * t);
        return 0xFF000000 | (r << 16) | (g << 8) | bl;
    }

    private void drawBackground(Canvas c, long now) {
        float w = getWidth(), h = getHeight();
        int base = theme.baseColor();
        int top = mix(base, 0x000000, 0.55f), bottom = mix(base, 0xFFFFFF, 0.12f);
        fill.setShader(new LinearGradient(0, 0, 0, h, top, bottom, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h, fill);
        fill.setShader(null);

        if (theme.background == XmbTheme.BG_IMAGE) {
            Bitmap bg = userBackground();
            if (bg != null) drawCropped(c, bg, 0, 0, w, h, 255);
        }

        // Cover of the selected game, fading in over the previous one.
        if (theme.coverBackground) {
            float t = Math.min(1f, (now - coverChanged) / 450f);
            if (coverPrev != null && t < 1f) drawCover(c, coverPrev, (int) (255 * (1 - t)));
            if (coverNow != null) drawCover(c, coverNow, (int) (255 * t));
            if (coverNow != null || coverPrev != null) {
                fill.setColor(Color.argb(theme.dim * 255 / 100, 0, 0, 0));
                c.drawRect(0, 0, w, h, fill);
            }
        }

        if (theme.background == XmbTheme.BG_WAVE) drawWaves(c, now);
    }

    private void drawCover(Canvas c, File f, int alpha) {
        Bitmap b = image(f, 720);
        if (b != null) drawCropped(c, b, 0, 0, getWidth(), getHeight(), alpha);
    }

    /** Draws b scaled to cover the rectangle, cutting off what does not fit. */
    private void drawCropped(Canvas c, Bitmap b, float l, float t, float rgt, float btm, int alpha) {
        float w = rgt - l, h = btm - t;
        float s = Math.max(w / b.getWidth(), h / b.getHeight());
        float sw = w / s, sh = h / s;
        int sx = (int) ((b.getWidth() - sw) / 2f), sy = (int) ((b.getHeight() - sh) / 2f);
        src.set(sx, sy, sx + (int) sw, sy + (int) sh);
        r.set(l, t, rgt, btm);
        smooth.setAlpha(alpha);
        c.drawBitmap(b, src, r, smooth);
        smooth.setAlpha(255);
    }

    private void drawWaves(Canvas c, long now) {
        float w = getWidth(), h = getHeight();
        float phase = (now - start) / 1000f * theme.waveSpeed();
        for (int k = 0; k < 3; k++) {
            float amp = h * (0.05f + 0.025f * k), yMid = h * (0.58f + 0.07f * k);
            float freq = (float) (Math.PI * 2 / (w * (1.1f + 0.35f * k)));
            path.reset();
            path.moveTo(0, h);
            for (int x = 0; x <= (int) w + 8; x += 8) {
                float y = yMid + (float) Math.sin(x * freq + phase * (1f + 0.3f * k) + k * 1.7f) * amp
                        + (float) Math.sin(x * freq * 2.3f - phase * 0.7f) * amp * 0.3f;
                path.lineTo(x, y);
            }
            path.lineTo(w, h);
            path.close();
            fill.setColor(Color.argb(22 + 8 * k, 255, 255, 255));
            c.drawPath(path, fill);
        }
    }

    private void drawPreview(Canvas c, Library.Game game, long now) {
        placePreview(game);
        if (previewFrames.isEmpty()) {
            Bitmap cover = image(game.bestCover(), 512);
            if (cover == null || theme.coverBackground) return;
            drawFrame(c, cover, 255, 1f);
            return;
        }
        long t = now - previewStart;
        int n = previewFrames.size();
        final long hold = 1100, fade = 350;
        int idx = (int) ((t / hold) % n);
        float into = (t % hold) / (float) hold;
        float zoom = 1f + 0.05f * into;
        drawFrame(c, previewFrames.get(idx), 255, zoom);
        long rest = t % hold;
        if (n > 1 && rest > hold - fade) {
            float a = (rest - (hold - fade)) / (float) fade;
            drawFrame(c, previewFrames.get((idx + 1) % n), (int) (255 * a), 1f);
        }
    }

    private void drawFrame(Canvas c, Bitmap b, int alpha, float zoom) {
        RectF pr = previewRect;
        float rr = unit() * 0.18f;
        if (alpha == 255) {
            fill.setColor(Color.argb(150, 0, 0, 0));
            c.drawRoundRect(pr.left - 6, pr.top - 6, pr.right + 6, pr.bottom + 6, rr, rr, fill);
        }
        c.save();
        path.reset();
        path.addRoundRect(pr, rr, rr, Path.Direction.CW);
        c.clipPath(path);
        float cx = pr.centerX(), cy = pr.centerY(), hw = pr.width() * zoom / 2f, hh = pr.height() * zoom / 2f;
        drawCropped(c, b, cx - hw, cy - hh, cx + hw, cy + hh, alpha);
        c.restore();
        if (alpha == 255) {
            stroke.setStrokeWidth(Math.max(2f, density * 1.5f));
            stroke.setColor(Color.argb(150, 255, 255, 255));
            c.drawRoundRect(pr, rr, rr, stroke);
        }
    }

    private void drawCategories(Canvas c) {
        float u = unit(), y = rowY();
        for (int i = 0; i < categories.size(); i++) {
            float x = catX() + (i - catPos) * catSpacing();
            float dist = Math.abs(i - catPos);
            if (x < -u * 2 || x > getWidth() + u * 2) continue;
            float size = u * (1.05f + 0.35f * Math.max(0f, 1f - dist));
            int alpha = (int) (255 * Math.max(0.45f, 1f - dist * 0.35f));
            drawCategoryIcon(c, categories.get(i).icon, x, y, size, alpha);
            if (dist < 0.5f) {
                text.setTextAlign(Paint.Align.CENTER);
                text.setTextSize(u * 0.36f);
                shadowText(c, categories.get(i).name, x, y + size * 0.75f + u * 0.25f, 255);
            }
        }
    }

    private void shadowText(Canvas c, String s, float x, float y, int alpha) {
        text.setShadowLayer(density * 3f, 0, density, Color.argb(alpha * 2 / 3, 0, 0, 0));
        text.setColor(Color.argb(alpha, 255, 255, 255));
        c.drawText(s, x, y, text);
        text.clearShadowLayer();
    }

    /** Simple line drawings for the category row. */
    private void drawCategoryIcon(Canvas c, int icon, float cx, float cy, float size, int alpha) {
        float s = size / 2f;
        stroke.setColor(Color.argb(alpha, 255, 255, 255));
        stroke.setStrokeWidth(Math.max(2f, size * 0.07f));
        fill.setColor(Color.argb(alpha, 255, 255, 255));
        switch (icon) {
            case ICON_SETTINGS: {
                c.drawCircle(cx, cy, s * 0.42f, stroke);
                for (int k = 0; k < 8; k++) {
                    double a = Math.PI / 4 * k;
                    float x0 = cx + (float) Math.cos(a) * s * 0.55f, y0 = cy + (float) Math.sin(a) * s * 0.55f;
                    float x1 = cx + (float) Math.cos(a) * s * 0.8f, y1 = cy + (float) Math.sin(a) * s * 0.8f;
                    c.drawLine(x0, y0, x1, y1, stroke);
                }
                break;
            }
            case ICON_RECENT: {
                c.drawCircle(cx, cy, s * 0.78f, stroke);
                c.drawLine(cx, cy, cx, cy - s * 0.5f, stroke);
                c.drawLine(cx, cy, cx + s * 0.35f, cy + s * 0.2f, stroke);
                break;
            }
            case ICON_DS: {
                float w = s * 1.3f, h = s * 0.62f, g = s * 0.12f;
                c.drawRoundRect(cx - w / 2, cy - h - g / 2, cx + w / 2, cy - g / 2, s * 0.12f, s * 0.12f, stroke);
                c.drawRoundRect(cx - w / 2, cy + g / 2, cx + w / 2, cy + h + g / 2, s * 0.12f, s * 0.12f, stroke);
                r.set(cx - w * 0.28f, cy + g / 2 + h * 0.22f, cx + w * 0.28f, cy + g / 2 + h * 0.78f);
                c.drawRect(r, fill);
                break;
            }
            case ICON_GBA: {
                float w = s * 1.7f, h = s * 0.9f;
                c.drawRoundRect(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2, h * 0.35f, h * 0.35f, stroke);
                r.set(cx - w * 0.2f, cy - h * 0.28f, cx + w * 0.2f, cy + h * 0.28f);
                c.drawRect(r, fill);
                break;
            }
            default: {
                float w = s * 1.0f, h = s * 1.5f;
                c.drawRoundRect(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2, w * 0.12f, w * 0.12f, stroke);
                r.set(cx - w * 0.32f, cy - h * 0.36f, cx + w * 0.32f, cy - h * 0.02f);
                c.drawRect(r, fill);
                c.drawCircle(cx + w * 0.22f, cy + h * 0.25f, w * 0.08f, fill);
                break;
            }
        }
    }

    private float itemY(Category cat, int i) {
        float d = i - cat.pos;
        if (d >= -0.5f) return firstItemY() + d * itemPitch();
        // Items before the selected one go above the category row, like on the PSP.
        return rowY() - unit() * 1.25f + (d + 1f) * itemPitch() * 0.9f;
    }

    private void drawItems(Canvas c) {
        if (categories.isEmpty()) return;
        Category cat = categories.get(catSel);
        float u = unit(), x = catX(), bottomLimit = getHeight();
        Item sel = selectedItem();
        if (portrait() && sel != null && sel.game != null && theme.moving) {
            placePreview(sel.game);
            bottomLimit = previewRect.top - u * 0.2f;
        }
        float colAlpha = Math.max(0f, 1f - Math.abs(catSel - catPos) * 2f);
        if (cat.items.isEmpty()) return;
        for (int i = 0; i < cat.items.size(); i++) {
            float y = itemY(cat, i);
            float d = Math.abs(i - cat.pos);
            boolean isSel = i == cat.selected;
            float size = itemPitch() * (isSel ? 0.95f : 0.72f) * (1f - Math.min(0.2f, d * 0.04f));
            if (y + size < 0 || y - size > bottomLimit) continue;
            if (y < rowY() + u * 0.9f && y > rowY() - u * 1.2f) continue; // keep the row clear
            int alpha = (int) (255 * colAlpha * (isSel ? 1f : Math.max(0.35f, 0.85f - d * 0.12f)));
            if (alpha < 8) continue;
            Item it = cat.items.get(i);
            drawItemIcon(c, it, x, y, size, alpha);

            float tx = x + size * 0.62f + u * 0.25f;
            float maxW = (portrait() || sel == null || sel.game == null || !theme.moving)
                    ? getWidth() - tx - u * 0.3f : getWidth() * 0.58f - tx;
            text.setTextAlign(Paint.Align.LEFT);
            text.setTextSize(u * (isSel ? 0.46f : 0.38f));
            String title = ellipsize(it.title, maxW);
            boolean hasSub = isSel && it.subtitle != null && !it.subtitle.isEmpty();
            shadowText(c, title, tx, y + (hasSub ? -u * 0.02f : u * 0.15f), alpha);
            if (hasSub) {
                text.setTextSize(u * 0.28f);
                shadowText(c, ellipsize(it.subtitle, maxW), tx, y + u * 0.38f, alpha * 3 / 4);
            }
            if (it.value != null) {
                text.setTextAlign(Paint.Align.RIGHT);
                text.setTextSize(u * 0.34f);
                shadowText(c, it.value, getWidth() - u * 0.4f, y + u * 0.12f, alpha);
            }
        }
    }

    private String ellipsize(String s, float maxW) {
        if (maxW <= 0 || text.measureText(s) <= maxW) return s;
        int n = s.length();
        while (n > 1 && text.measureText(s, 0, n) + text.measureText("…") > maxW) n--;
        return s.substring(0, n) + "…";
    }

    private static int systemColor(String system) {
        switch (system) {
            case RomInfo.NDS: return 0xFF5C6B7A;
            case RomInfo.GBA: return 0xFF4B3F9E;
            case RomInfo.GBC: return 0xFF2E8B57;
            default: return 0xFF6E7B3A;
        }
    }

    private void drawItemIcon(Canvas c, Item it, float cx, float cy, float size, int alpha) {
        float half = size / 2f, rr = size * 0.14f;
        r.set(cx - half, cy - half, cx + half, cy + half);
        if (it.game == null) {
            fill.setColor(Color.argb(alpha / 3, 255, 255, 255));
            c.drawRoundRect(r, rr, rr, fill);
            fill.setColor(Color.argb(alpha, 255, 255, 255));
            c.drawCircle(cx, cy, size * 0.12f, fill);
            return;
        }
        Library.Game g = it.game;
        Bitmap icon = image(g.icon(), 64);
        Bitmap cover = icon == null ? image(g.bestCover(), 256) : null;
        fill.setColor(Color.argb(alpha * 200 / 255, 20, 22, 26));
        c.drawRoundRect(r, rr, rr, fill);
        if (icon != null) {
            // DS banner icons are 32x32 pixel art: draw them sharp.
            float pad = size * 0.08f;
            pixel.setAlpha(alpha);
            c.drawBitmap(icon, null, new RectF(r.left + pad, r.top + pad, r.right - pad, r.bottom - pad), pixel);
        } else if (cover != null) {
            c.save();
            path.reset();
            path.addRoundRect(r, rr, rr, Path.Direction.CW);
            c.clipPath(path);
            drawCropped(c, cover, r.left, r.top, r.right, r.bottom, alpha);
            c.restore();
        } else {
            int col = systemColor(g.system);
            fill.setColor((col & 0x00FFFFFF) | (alpha << 24));
            c.drawRoundRect(r, rr, rr, fill);
            text.setTextAlign(Paint.Align.CENTER);
            text.setTextSize(size * 0.42f);
            String letter = g.title.isEmpty() ? "?" : g.title.substring(0, 1).toUpperCase(Locale.ROOT);
            text.setColor(Color.argb(alpha, 255, 255, 255));
            c.drawText(letter, cx, cy + size * 0.12f, text);
            text.setTextSize(size * 0.17f);
            c.drawText(g.system.toUpperCase(Locale.ROOT), cx, cy + size * 0.38f, text);
        }
        stroke.setStrokeWidth(Math.max(1.5f, density));
        stroke.setColor(Color.argb(alpha / 2, 255, 255, 255));
        c.drawRoundRect(r, rr, rr, stroke);
    }

    private void drawClock(Canvas c) {
        float u = unit();
        Date now = new Date();
        text.setTextAlign(Paint.Align.RIGHT);
        text.setTextSize(u * 0.42f);
        shadowText(c, clockFmt.format(now), getWidth() - u * 0.45f, u * 0.75f, 230);
        text.setTextSize(u * 0.26f);
        shadowText(c, dateFmt.format(now), getWidth() - u * 0.45f, u * 1.12f, 200);
    }

    // ------------------------------------------------------------------ images (loaded off the UI thread)
    private Bitmap image(File f, int maxSide) {
        if (f == null || !f.exists()) return null;
        final String key = f.getPath() + "@" + maxSide + "#" + f.lastModified();
        Bitmap b = cache.get(key);
        if (b != null) return b;
        synchronized (loading) {
            if (!loading.add(key)) return null;
        }
        final File file = f;
        loader.execute(() -> {
            Bitmap loaded = null;
            try {
                loaded = Library.decode(file, maxSide);
            } catch (OutOfMemoryError ignored) { }
            final Bitmap result = loaded;
            post(() -> {
                synchronized (loading) {
                    loading.remove(key);
                }
                if (result != null) cache.put(key, result);
                invalidate();
            });
        });
        return null;
    }

    private Bitmap userBackground() {
        File f = new File(getContext().getFilesDir(), "xmb_background.png");
        if (!f.exists()) return null;
        if (userBackground == null || userBackgroundFile == null || userBackgroundFile.lastModified() != f.lastModified()) {
            userBackground = Library.decode(f, Math.max(getWidth(), getHeight()));
            userBackgroundFile = f;
        }
        return userBackground;
    }

    public void backgroundImageChanged() {
        userBackground = null;
        invalidate();
    }

    private void selectionChanged() {
        Item sel = selectedItem();
        Library.Game g = sel != null ? sel.game : null;
        File cover = g != null ? g.bestCover() : null;
        if (cover == null ? coverNow != null : !cover.equals(coverNow)) {
            coverPrev = coverNow;
            coverNow = cover;
            coverChanged = SystemClock.uptimeMillis();
        }
        if (g == null || previewGame == null || !previewGame.key.equals(g.key)) {
            previewGame = g;
            previewFrames = new ArrayList<>();
            handler.removeCallbacks(loadPreview);
            // Wait a moment: scrolling quickly through the list should not load every preview.
            if (g != null) handler.postDelayed(loadPreview, 450);
        }
        invalidate();
    }

    private void startPreviewLoad() {
        final Library.Game g = previewGame;
        if (g == null || theme == null || !theme.moving) return;
        loader.execute(() -> {
            List<Bitmap> frames = new ArrayList<>();
            try {
                for (File f : g.previewFrames()) {
                    Bitmap b = Library.decode(f, 400);
                    if (b != null) frames.add(b);
                }
            } catch (OutOfMemoryError ignored) { }
            post(() -> {
                if (previewGame == g) {
                    previewFrames = frames;
                    previewStart = SystemClock.uptimeMillis();
                    invalidate();
                }
            });
        });
    }

    // ------------------------------------------------------------------ input
    private static final int AXIS_NONE = 0, AXIS_H = 1, AXIS_V = 2;
    private boolean dragging, longPressed;
    private int dragAxis;
    private float downX, downY, startPos, lastY, velocity;
    private long lastMoveTime;
    private final int slop = ViewConfiguration.get(getContext()).getScaledTouchSlop();
    private final Runnable longPress = () -> {
        longPressed = true;
        Item it = hitItem(downX, downY);
        if (it != null && listener != null) {
            select(it);
            performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);
            listener.onOptions(it);
        }
    };

    private void select(Item it) {
        Category cat = categories.get(catSel);
        int i = cat.items.indexOf(it);
        if (i >= 0 && i != cat.selected) {
            cat.selected = i;
            selectionChanged();
        }
    }

    /** The item under a point in the current column, or null. */
    private Item hitItem(float x, float y) {
        if (categories.isEmpty()) return null;
        Category cat = categories.get(catSel);
        if (previewRect.contains(x, y) && selectedItem() != null && selectedItem().game != null) return selectedItem();
        Item best = null;
        float bestD = itemPitch() * 0.55f;
        for (int i = 0; i < cat.items.size(); i++) {
            float d = Math.abs(itemY(cat, i) - y);
            if (d < bestD) {
                bestD = d;
                best = cat.items.get(i);
            }
        }
        return best;
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (categories.isEmpty()) return true;
        Category cat = categories.get(catSel);
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = e.getX();
                downY = e.getY();
                lastY = downY;
                lastMoveTime = e.getEventTime();
                velocity = 0f;
                dragging = false;
                dragAxis = AXIS_NONE;
                longPressed = false;
                handler.postDelayed(longPress, ViewConfiguration.getLongPressTimeout());
                return true;
            case MotionEvent.ACTION_MOVE: {
                float dx = e.getX() - downX, dy = e.getY() - downY;
                if (dragAxis == AXIS_NONE && Math.hypot(dx, dy) > slop) {
                    handler.removeCallbacks(longPress);
                    dragging = true;
                    dragAxis = Math.abs(dx) > Math.abs(dy) ? AXIS_H : AXIS_V;
                    startPos = dragAxis == AXIS_H ? catPos : cat.pos;
                }
                if (dragAxis == AXIS_H) {
                    catPos = Math.max(-0.3f, Math.min(categories.size() - 0.7f, startPos - dx / catSpacing()));
                    invalidate();
                } else if (dragAxis == AXIS_V && !cat.items.isEmpty()) {
                    long dtm = Math.max(1, e.getEventTime() - lastMoveTime);
                    velocity = 0.7f * velocity + 0.3f * ((e.getY() - lastY) / dtm);
                    lastY = e.getY();
                    lastMoveTime = e.getEventTime();
                    cat.pos = Math.max(-0.3f, Math.min(cat.items.size() - 0.7f, startPos - dy / itemPitch()));
                    invalidate();
                }
                return true;
            }
            case MotionEvent.ACTION_UP:
                handler.removeCallbacks(longPress);
                if (longPressed) return true;
                if (dragAxis == AXIS_H) {
                    dragging = false;
                    setCategory(Math.round(catPos));
                } else if (dragAxis == AXIS_V) {
                    dragging = false;
                    // A flick carries on for a few items.
                    float extra = -velocity * 260f / itemPitch();
                    setItem(Math.round(cat.pos + extra));
                } else {
                    tap(e.getX(), e.getY());
                }
                dragAxis = AXIS_NONE;
                invalidate();
                return true;
            case MotionEvent.ACTION_CANCEL:
                handler.removeCallbacks(longPress);
                dragging = false;
                dragAxis = AXIS_NONE;
                invalidate();
                return true;
            default:
                return true;
        }
    }

    private void tap(float x, float y) {
        // On the category row: switch to that category.
        if (Math.abs(y - rowY()) < unit() * 1.1f) {
            int i = Math.round(catPos + (x - catX()) / catSpacing());
            if (i >= 0 && i < categories.size()) setCategory(i);
            return;
        }
        Item it = hitItem(x, y);
        if (it == null) return;
        if (it == selectedItem()) {
            if (listener != null) listener.onActivate(it);
        } else {
            select(it);
        }
    }

    private void setCategory(int i) {
        i = Math.max(0, Math.min(categories.size() - 1, i));
        if (i != catSel) {
            catSel = i;
            selectionChanged();
        }
        invalidate();
    }

    private void setItem(int i) {
        Category cat = categories.get(catSel);
        if (cat.items.isEmpty()) return;
        i = Math.max(0, Math.min(cat.items.size() - 1, i));
        if (i != cat.selected) {
            cat.selected = i;
            selectionChanged();
        }
        invalidate();
    }

    /** Game pad and keyboard: arrows move, A/Enter opens, Y/X/Menu shows the options. */
    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (categories.isEmpty()) return super.onKeyDown(keyCode, event);
        Category cat = categories.get(catSel);
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_LEFT: setCategory(catSel - 1); return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT: setCategory(catSel + 1); return true;
            case KeyEvent.KEYCODE_DPAD_UP: setItem(cat.selected - 1); return true;
            case KeyEvent.KEYCODE_DPAD_DOWN: setItem(cat.selected + 1); return true;
            case KeyEvent.KEYCODE_BUTTON_L1: setItem(cat.selected - 5); return true;
            case KeyEvent.KEYCODE_BUTTON_R1: setItem(cat.selected + 5); return true;
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_BUTTON_A:
            case KeyEvent.KEYCODE_BUTTON_START: {
                Item it = selectedItem();
                if (it != null && listener != null) listener.onActivate(it);
                return true;
            }
            case KeyEvent.KEYCODE_BUTTON_X:
            case KeyEvent.KEYCODE_BUTTON_Y:
            case KeyEvent.KEYCODE_MENU: {
                Item it = selectedItem();
                if (it != null && listener != null) listener.onOptions(it);
                return true;
            }
            default:
                return super.onKeyDown(keyCode, event);
        }
    }
}
