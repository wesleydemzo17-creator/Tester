package org.mgba.android;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/** Build-time helper: writes every screen shader to a file so glslangValidator can check it. */
public final class DumpShaders {
    public static void main(String[] args) throws Exception {
        File dir = new File(args[0]);
        dir.mkdirs();
        write(new File(dir, "screen.vert"), Shaders.VERTEX);
        for (int i = 0; i < Shaders.FRAG_MAIN.length; i++) {
            write(new File(dir, "shader" + i + ".frag"), Shaders.FRAG_HEADER + Shaders.FRAG_MAIN[i]);
        }
        if (Shaders.NAMES.length != Shaders.FRAG_MAIN.length || Shaders.LINEAR.length != Shaders.FRAG_MAIN.length) {
            throw new IllegalStateException("Shader tables have different lengths");
        }
        System.out.println("Wrote " + (Shaders.FRAG_MAIN.length + 1) + " shaders to " + dir);
    }

    private static void write(File f, String src) throws Exception {
        Files.write(f.toPath(), ("#version 100\n" + src).getBytes(StandardCharsets.UTF_8));
    }
}
