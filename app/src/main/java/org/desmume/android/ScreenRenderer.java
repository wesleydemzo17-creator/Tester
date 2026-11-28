package org.desmume.android;

import android.graphics.Bitmap;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.util.Log;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

/**
 * Draws the two DS screens with OpenGL ES 2.0, each as a textured quad placed anywhere on the
 * surface, through a selectable post-processing fragment shader.
 */
final class ScreenRenderer implements GLSurfaceView.Renderer {

    private static final String TAG = "desmume";

    private static final class Program {
        int id, aPos, uRect, uViewport, uTex, uTexSize, uOutSize;
    }

    // Frame shared with the emulation thread.
    private final Object frameLock = new Object();
    private ByteBuffer pixels;
    private int frameScale;
    private boolean frameDirty;
    private boolean hasFrame;

    // Screen rectangles: left, top, width, height for the top screen, then the touch screen.
    private volatile float[] rects = new float[8];
    private volatile int shader;

    private final Program[] programs = new Program[Shaders.FRAG_MAIN.length];
    private final int[] textures = new int[2];
    private final FloatBuffer quad;
    private int texW, texH;
    private int appliedFilterShader = -1;
    private int viewW = 1, viewH = 1;

    ScreenRenderer() {
        quad = ByteBuffer.allocateDirect(8 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        quad.put(new float[]{0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f}).position(0);
    }

    // ------------------------------------------------------------------ called from other threads
    /** Emulation thread: fetches the latest frame from the core at the given resolution scale. */
    boolean fetchFrame(int scale) {
        int bytes = Native.SCREEN_W * scale * Native.SCREEN_H * scale * 2 * 2;
        synchronized (frameLock) {
            if (pixels == null || pixels.capacity() != bytes) {
                pixels = ByteBuffer.allocateDirect(bytes).order(ByteOrder.nativeOrder());
            }
            if (!Native.copyFrame(pixels, scale)) return false;
            frameScale = scale;
            frameDirty = true;
            hasFrame = true;
            return true;
        }
    }

    /** A copy of the top screen of the last frame (for the moving preview), or null. */
    Bitmap snapshotTop() {
        synchronized (frameLock) {
            if (!hasFrame || pixels == null || frameScale < 1) return null;
            int w = Native.SCREEN_W * frameScale, h = Native.SCREEN_H * frameScale;
            java.nio.ByteBuffer top = pixels.duplicate();
            top.position(0);
            top.limit(w * h * 2);
            Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.RGB_565);
            b.copyPixelsFromBuffer(top);
            return b;
        }
    }

    void clearFrame() {
        synchronized (frameLock) {
            hasFrame = false;
            frameDirty = false;
        }
    }

    void setRects(float[] r) { rects = r.clone(); }

    void setShader(int index) {
        shader = Math.max(0, Math.min(Shaders.FRAG_MAIN.length - 1, index));
    }

    // ------------------------------------------------------------------ GL thread
    @Override
    public void onSurfaceCreated(GL10 unused, EGLConfig config) {
        // The context is new: everything has to be created again.
        for (int i = 0; i < programs.length; i++) programs[i] = build(i);
        GLES20.glGenTextures(2, textures, 0);
        for (int t : textures) {
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, t);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        }
        texW = texH = 0;
        appliedFilterShader = -1;
        GLES20.glDisable(GLES20.GL_DEPTH_TEST);
        GLES20.glDisable(GLES20.GL_BLEND);
        GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 1);
        synchronized (frameLock) {
            frameDirty = hasFrame; // re-upload the last frame into the new textures
        }
    }

    @Override
    public void onSurfaceChanged(GL10 unused, int width, int height) {
        viewW = Math.max(1, width);
        viewH = Math.max(1, height);
        GLES20.glViewport(0, 0, viewW, viewH);
    }

    @Override
    public void onDrawFrame(GL10 unused) {
        boolean draw;
        synchronized (frameLock) {
            if (frameDirty && pixels != null) {
                upload();
                frameDirty = false;
            }
            draw = hasFrame && texW > 0;
        }

        GLES20.glClearColor(0f, 0f, 0f, 1f);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
        if (!draw) return;

        int sh = shader;
        Program p = programs[sh] != null ? programs[sh] : programs[0];
        if (p == null) return;

        if (appliedFilterShader != sh) {
            int filter = Shaders.LINEAR[sh] ? GLES20.GL_LINEAR : GLES20.GL_NEAREST;
            for (int t : textures) {
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, t);
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, filter);
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, filter);
            }
            appliedFilterShader = sh;
        }

        float[] r = rects;
        GLES20.glUseProgram(p.id);
        GLES20.glUniform2f(p.uViewport, viewW, viewH);
        GLES20.glUniform2f(p.uTexSize, texW, texH);
        GLES20.glUniform1i(p.uTex, 0);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glEnableVertexAttribArray(p.aPos);
        GLES20.glVertexAttribPointer(p.aPos, 2, GLES20.GL_FLOAT, false, 0, quad);
        for (int s = 0; s < 2; s++) {
            float w = r[s * 4 + 2], h = r[s * 4 + 3];
            if (w < 1f || h < 1f) continue;
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textures[s]);
            GLES20.glUniform4f(p.uRect, r[s * 4], r[s * 4 + 1], w, h);
            GLES20.glUniform2f(p.uOutSize, w, h);
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
        }
        GLES20.glDisableVertexAttribArray(p.aPos);
    }

    /** Uploads both screens. Caller holds frameLock. */
    private void upload() {
        int w = Native.SCREEN_W * frameScale, h = Native.SCREEN_H * frameScale;
        boolean realloc = (w != texW || h != texH);
        for (int s = 0; s < 2; s++) {
            pixels.position(s * w * h * 2);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textures[s]);
            if (realloc) {
                GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGB, w, h, 0,
                        GLES20.GL_RGB, GLES20.GL_UNSIGNED_SHORT_5_6_5, pixels);
            } else {
                GLES20.glTexSubImage2D(GLES20.GL_TEXTURE_2D, 0, 0, 0, w, h,
                        GLES20.GL_RGB, GLES20.GL_UNSIGNED_SHORT_5_6_5, pixels);
            }
        }
        pixels.position(0);
        if (realloc) {
            texW = w;
            texH = h;
            appliedFilterShader = -1; // new texture storage: set the filters again
        }
    }

    private static Program build(int index) {
        int vs = compile(GLES20.GL_VERTEX_SHADER, Shaders.VERTEX);
        int fs = compile(GLES20.GL_FRAGMENT_SHADER, Shaders.FRAG_HEADER + Shaders.FRAG_MAIN[index]);
        if (vs == 0 || fs == 0) return null;
        int id = GLES20.glCreateProgram();
        GLES20.glAttachShader(id, vs);
        GLES20.glAttachShader(id, fs);
        GLES20.glLinkProgram(id);
        int[] ok = new int[1];
        GLES20.glGetProgramiv(id, GLES20.GL_LINK_STATUS, ok, 0);
        if (ok[0] == 0) {
            Log.e(TAG, "Shader " + index + " link failed: " + GLES20.glGetProgramInfoLog(id));
            GLES20.glDeleteProgram(id);
            return null;
        }
        Program p = new Program();
        p.id = id;
        p.aPos = GLES20.glGetAttribLocation(id, "aPos");
        p.uRect = GLES20.glGetUniformLocation(id, "uRect");
        p.uViewport = GLES20.glGetUniformLocation(id, "uViewport");
        p.uTex = GLES20.glGetUniformLocation(id, "uTex");
        p.uTexSize = GLES20.glGetUniformLocation(id, "uTexSize");
        p.uOutSize = GLES20.glGetUniformLocation(id, "uOutSize");
        return p;
    }

    private static int compile(int type, String source) {
        int s = GLES20.glCreateShader(type);
        GLES20.glShaderSource(s, source);
        GLES20.glCompileShader(s);
        int[] ok = new int[1];
        GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0);
        if (ok[0] == 0) {
            Log.e(TAG, "Shader compile failed: " + GLES20.glGetShaderInfoLog(s));
            GLES20.glDeleteShader(s);
            return 0;
        }
        return s;
    }
}
