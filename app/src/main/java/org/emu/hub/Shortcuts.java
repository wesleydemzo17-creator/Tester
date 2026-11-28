package org.emu.hub;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ShortcutInfo;
import android.content.pm.ShortcutManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Icon;

/** Home-screen icons that open one game straight away, with that game's own settings. */
public final class Shortcuts {

    /** Intent extra naming the game by its library key (never by path, so other apps cannot point it elsewhere). */
    public static final String EXTRA_KEY = "org.emu.hub.GAME_KEY";

    private Shortcuts() {}

    /** Asks the launcher to pin an icon for this game. Returns false if the launcher cannot. */
    public static boolean pin(Context c, Library.Game g) {
        ShortcutManager sm = c.getSystemService(ShortcutManager.class);
        if (sm == null || !sm.isRequestPinShortcutSupported()) return false;
        Intent open = new Intent(c, ShortcutActivity.class);
        open.setAction(Intent.ACTION_VIEW);
        open.putExtra(EXTRA_KEY, g.key);
        String label = g.title.length() > 24 ? g.title.substring(0, 23) + "…" : g.title;
        ShortcutInfo info = new ShortcutInfo.Builder(c, "game_" + g.key)
                .setShortLabel(label)
                .setLongLabel(g.title)
                .setIcon(Icon.createWithAdaptiveBitmap(iconBitmap(g)))
                .setIntent(open)
                .build();
        return sm.requestPinShortcut(info, null);
    }

    /**
     * Adaptive icon picture (432x432; the launcher shows the middle two thirds): the game's cover
     * if there is one, otherwise its DS icon, otherwise a coloured tile with the system name.
     */
    static Bitmap iconBitmap(Library.Game g) {
        final int size = 432;
        Bitmap out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);
        c.drawColor(0xFF22252A);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        Bitmap cover = Library.decode(g.bestCover(), 512);
        Bitmap icon = Library.decode(g.icon(), 64);
        if (cover != null) {
            float s = Math.max((float) size / cover.getWidth(), (float) size / cover.getHeight());
            float sw = size / s, sh = size / s;
            int sx = (int) ((cover.getWidth() - sw) / 2), sy = (int) ((cover.getHeight() - sh) / 2);
            c.drawBitmap(cover, new Rect(sx, sy, sx + (int) sw, sy + (int) sh), new RectF(0, 0, size, size), p);
        } else if (icon != null) {
            Paint sharp = new Paint();
            sharp.setFilterBitmap(false);
            float m = size * 0.24f;
            c.drawBitmap(icon, null, new RectF(m, m, size - m, size - m), sharp);
        } else {
            p.setColor(0xFF4B3F9E);
            c.drawRect(0, 0, size, size, p);
            p.setColor(Color.WHITE);
            p.setTextAlign(Paint.Align.CENTER);
            p.setTextSize(size * 0.2f);
            c.drawText(g.title.isEmpty() ? "?" : g.title.substring(0, 1).toUpperCase(java.util.Locale.ROOT),
                    size / 2f, size * 0.52f, p);
            p.setTextSize(size * 0.08f);
            c.drawText(g.systemLabel(), size / 2f, size * 0.66f, p);
        }
        return out;
    }
}
