package org.mgba.android;

import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.util.Log;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

/** Draws the game picture with OpenGL ES 2.0 as one textured quad, through a selectable shader. */
final class ScreenRenderer implements GLSurfaceView.Renderer {

    private static final String TAG = "mgba";

    private static final class Program {
        int id, aPos, uRect, uViewport, uTex, uTexSize, uOutSize, uNative;
    }

    // Frame shared with the emulation thread.
    private final Object frameLock = new Object();
    private final ByteBuffer pixels =
            ByteBuffer.allocateDirect(Native.MAX_W * Native.MAX_H * 4).order(ByteOrder.nativeOrder());
    private int frameW, frameH;
    private boolean frameDirty;
    private boolean hasFrame;

    /** left, top, width, height of the picture in surface pixels. */
    private volatile float[] rect = new float[4];
    private volatile int shader;
    private volatile int nativeW = 240, nativeH = 160;

    private final Program[] programs = new Program[Shaders.FRAG_MAIN.length];
    private final int[] texture = new int[1];
    private final FloatBuffer quad;
    private int texW, texH;
    private int appliedFilterShader = -1;
    private int viewW = 1, viewH = 1;

    ScreenRenderer() {
        quad = ByteBuffer.allocateDirect(8 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        quad.put(new float[]{0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f}).position(0);
    }

    // ------------------------------------------------------------------ called from other threads
    /** Emulation thread: fetches the latest picture. Returns (width << 16) | height, or 0. */
    int fetchFrame() {
        synchronized (frameLock) {
            pixels.position(0);
            int size = Native.copyFrame(pixels);
            if (size == 0) return 0;
            frameW = size >>> 16;
            frameH = size & 0xFFFF;
            frameDirty = true;
            hasFrame = true;
            return size;
        }
    }

    void clearFrame() {
        synchronized (frameLock) {
            hasFrame = false;
            frameDirty = false;
        }
    }

    void setRect(float left, float top, float width, float height) {
        rect = new float[]{left, top, width, height};
    }

    void setShader(int index) {
        shader = Math.max(0, Math.min(Shaders.FRAG_MAIN.length - 1, index));
    }

    /** Resolution of the console being emulated (not of a high-resolution picture). */
    void setNativeSize(int w, int h) {
        nativeW = w;
        nativeH = h;
    }

    // ------------------------------------------------------------------ GL thread
    @Override
    public void onSurfaceCreated(GL10 unused, EGLConfig config) {
        // The context is new: everything has to be created again.
        for (int i = 0; i < programs.length; i++) programs[i] = build(i);
        GLES20.glGenTextures(1, texture, 0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture[0]);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        texW = texH = 0;
        appliedFilterShader = -1;
        GLES20.glDisable(GLES20.GL_DEPTH_TEST);
        GLES20.glDisable(GLES20.GL_BLEND);
        GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 1);
        synchronized (frameLock) {
            frameDirty = hasFrame; // re-upload the last picture into the new texture
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
            if (frameDirty) {
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
        float[] r = rect;
        if (r[2] < 1f || r[3] < 1f) return;

        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture[0]);
        if (appliedFilterShader != sh) {
            int filter = Shaders.LINEAR[sh] ? GLES20.GL_LINEAR : GLES20.GL_NEAREST;
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, filter);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, filter);
            appliedFilterShader = sh;
        }

        GLES20.glUseProgram(p.id);
        GLES20.glUniform2f(p.uViewport, viewW, viewH);
        GLES20.glUniform2f(p.uTexSize, texW, texH);
        GLES20.glUniform2f(p.uNative, nativeW, nativeH);
        GLES20.glUniform1i(p.uTex, 0);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glUniform4f(p.uRect, r[0], r[1], r[2], r[3]);
        GLES20.glUniform2f(p.uOutSize, r[2], r[3]);
        GLES20.glEnableVertexAttribArray(p.aPos);
        GLES20.glVertexAttribPointer(p.aPos, 2, GLES20.GL_FLOAT, false, 0, quad);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
        GLES20.glDisableVertexAttribArray(p.aPos);
    }

    /** Uploads the picture. Caller holds frameLock. */
    private void upload() {
        boolean realloc = (frameW != texW || frameH != texH);
        pixels.position(0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture[0]);
        if (realloc) {
            GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, frameW, frameH, 0,
                    GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, pixels);
            texW = frameW;
            texH = frameH;
            appliedFilterShader = -1; // new texture storage: set the filters again
        } else {
            GLES20.glTexSubImage2D(GLES20.GL_TEXTURE_2D, 0, 0, 0, frameW, frameH,
                    GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, pixels);
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
        p.uNative = GLES20.glGetUniformLocation(id, "uNative");
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
