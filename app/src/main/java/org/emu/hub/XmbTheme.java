package org.emu.hub;

import android.content.Context;
import android.content.SharedPreferences;

/** Everything the user can change about the look of the game list, read from one place. */
public final class XmbTheme {

    public static final String[] COLOR_NAMES = {
            "Azul", "Vermelho", "Verde", "Roxo", "Laranja", "Rosa", "Ciano", "Grafite", "Dourado",
            "Muda com a hora do dia"};
    /** Base colours (0xRRGGBB), one per name above except the last. */
    private static final int[] COLORS = {
            0x1F4FA8, 0x9E1F2A, 0x2E7D32, 0x5E35B1, 0xD8641B, 0xC2185B, 0x00838F, 0x37474F, 0xA6801E};

    public static final String[] BACKGROUND_NAMES = {"Onda animada", "Cor sólida", "Imagem da galeria"};
    public static final int BG_WAVE = 0, BG_SOLID = 1, BG_IMAGE = 2;

    public static final String[] SPEED_NAMES = {"Parada", "Lenta", "Normal", "Rápida"};
    public static final int[] ICON_SCALES = {80, 90, 100, 115, 130, 150};
    public static final int[] DIM_LEVELS = {25, 40, 55, 70};
    public static final String[] SORT_NAMES = {"Nome", "Jogados por último primeiro"};

    public int colorIndex, background, speed, iconScale, dim, sort;
    public boolean coverBackground, moving, clock, autoCovers;

    private XmbTheme() {}

    public static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("xmb", Context.MODE_PRIVATE);
    }

    public static XmbTheme load(Context c) {
        SharedPreferences p = prefs(c);
        XmbTheme t = new XmbTheme();
        t.colorIndex = clamp(p.getInt("color", 0), 0, COLOR_NAMES.length - 1);
        t.background = clamp(p.getInt("background", BG_WAVE), 0, BACKGROUND_NAMES.length - 1);
        t.speed = clamp(p.getInt("speed", 2), 0, SPEED_NAMES.length - 1);
        t.iconScale = p.getInt("icon_scale", 100);
        t.dim = p.getInt("dim", 55);
        t.sort = clamp(p.getInt("sort", 0), 0, SORT_NAMES.length - 1);
        t.coverBackground = p.getBoolean("cover_bg", true);
        t.moving = p.getBoolean("moving_preview", true);
        t.clock = p.getBoolean("clock", true);
        t.autoCovers = p.getBoolean("auto_covers", true);
        return t;
    }

    private static int clamp(int v, int lo, int hi) { return Math.max(lo, Math.min(hi, v)); }

    /** The base colour right now (the "time of day" choice changes through the day). */
    public int baseColor() {
        if (colorIndex < COLORS.length) return COLORS[colorIndex];
        int hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY);
        int[] byHour = {0x37474F, 0x5E35B1, 0x00838F, 0x1F4FA8, 0x2E7D32, 0xA6801E, 0xD8641B, 0x9E1F2A};
        return byHour[(hour / 3) % byHour.length];
    }

    /** Wave speed in radians per second. */
    public float waveSpeed() {
        return new float[]{0f, 0.25f, 0.55f, 1.1f}[speed];
    }
}
