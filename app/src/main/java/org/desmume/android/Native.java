package org.desmume.android;

import java.nio.ByteBuffer;

/** JNI entry points into the DeSmuME core. All calls are serialised on this class. */
public final class Native {
    public static final int KEY_A = 1, KEY_B = 1 << 1, KEY_SELECT = 1 << 2, KEY_START = 1 << 3;
    public static final int KEY_RIGHT = 1 << 4, KEY_LEFT = 1 << 5, KEY_UP = 1 << 6, KEY_DOWN = 1 << 7;
    public static final int KEY_R = 1 << 8, KEY_L = 1 << 9, KEY_X = 1 << 10, KEY_Y = 1 << 11;
    public static final int KEY_LID = 1 << 12;

    public static final int SCREEN_W = 256, SCREEN_H = 192;
    public static final int SAMPLE_RATE = 44100;

    static {
        System.loadLibrary("desmume-jni");
    }

    private Native() {}

    public static synchronized native boolean init(String baseDir, int rasterThreads);
    public static synchronized native boolean jitAvailable();
    public static synchronized native boolean gpuRendererAvailable();
    public static synchronized native boolean setGpuRenderer(boolean enabled);
    public static synchronized native void setJit(boolean enabled, int blockSize);
    public static synchronized native void setAdvancedTiming(boolean enabled);
    public static synchronized native void setSoundEnabled(boolean enabled);
    public static synchronized native int loadRom(String path);
    public static synchronized native void closeRom();
    public static synchronized native void reset();
    public static synchronized native boolean romLoaded();
    public static synchronized native void runFrame(int keys, boolean touching, int tx, int ty, boolean skipVideo);
    public static synchronized native void setResolutionScale(int scale);
    public static synchronized native boolean copyFrame(ByteBuffer rgb565, int scale);
    public static synchronized native int readAudio(short[] out);
    public static synchronized native boolean saveState(String path);
    public static synchronized native boolean loadState(String path);
    public static synchronized native void flushSaves();
    public static synchronized native void setLocalWireless(boolean enabled, String mac);
    public static synchronized native boolean localWirelessActive();
    public static synchronized native void cheatsClear();
    public static synchronized native boolean cheatsAddActionReplay(String code, String description);
    public static synchronized native int cheatsActiveCount();
    public static synchronized native String romTitle();
    public static synchronized native void shutdown();
}
