package org.mgba.android;

import java.nio.ByteBuffer;

/** JNI entry points into the mGBA core. Every call is serialised on this class. */
public final class Native {
    public static final int KEY_A = 1, KEY_B = 1 << 1, KEY_SELECT = 1 << 2, KEY_START = 1 << 3;
    public static final int KEY_RIGHT = 1 << 4, KEY_LEFT = 1 << 5, KEY_UP = 1 << 6, KEY_DOWN = 1 << 7;
    public static final int KEY_R = 1 << 8, KEY_L = 1 << 9;

    public static final int PLATFORM_GBA = 0, PLATFORM_GB = 1;
    public static final int SAMPLE_RATE = 48000;
    /** Largest picture the core can produce: GBA at 6x. */
    public static final int MAX_W = 240 * 6, MAX_H = 160 * 6;

    static {
        System.loadLibrary("mgba-jni");
    }

    private Native() {}

    public static synchronized native void init(String baseDir);
    public static synchronized native boolean hiresAvailable();
    public static synchronized native void setOption(String key, String value);
    public static synchronized native int loadRom(String path);
    public static synchronized native void closeRom();
    public static synchronized native boolean romLoaded();
    public static synchronized native void reset();
    public static synchronized native String gameInfo();
    public static synchronized native boolean runFrame(int keys, boolean rewinding);
    public static synchronized native int copyFrame(ByteBuffer rgba);
    public static synchronized native int readAudio(short[] out);
    public static synchronized native void clearAudio();
    public static synchronized native boolean saveState(int slot);
    public static synchronized native boolean loadState(int slot);
    public static synchronized native boolean stateExists(int slot);
    public static synchronized native boolean screenshot(String path);
    public static synchronized native void setRewindSeconds(int seconds);
    public static synchronized native void setHiresScale(int scale);
    public static synchronized native boolean hiresActive();
    public static synchronized native void cheatsClear();
    public static synchronized native int cheatsAdd(String name, String code);
    public static synchronized native void setMotion(float x, float y, float gyroZ);
    public static synchronized native void setLuminance(int level);
    public static synchronized native float rumble();
    public static synchronized native String[] videoLayers();
    public static synchronized native String[] audioChannels();
    public static synchronized native void enableVideoLayer(int id, boolean on);
    public static synchronized native void enableAudioChannel(int id, boolean on);
}
