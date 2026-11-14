package org.desmume.android;

/**
 * GLSL ES 1.00 sources for the screen shaders. Kept free of Android classes so the build can
 * dump and validate them (see tools/DumpShaders.java).
 */
final class Shaders {
    private Shaders() {}

    static final String[] NAMES = {
            "Nenhum (pixels nítidos)",
            "Suave (bilinear)",
            "Nítido sem serrilhado (sharp bilinear)",
            "Grade LCD",
            "Scanlines",
            "Cores do DS (tela original)",
            "Grade LCD + cores do DS",
    };

    /** true = sample with GL_LINEAR, false = GL_NEAREST. */
    static final boolean[] LINEAR = {false, true, true, true, true, false, true};

    static final String VERTEX =
            "attribute vec2 aPos;\n"
            + "uniform vec4 uRect;\n"       // x, y, w, h in surface pixels, origin top-left
            + "uniform vec2 uViewport;\n"
            + "varying vec2 vUv;\n"
            + "void main() {\n"
            + "  vec2 p = (uRect.xy + aPos * uRect.zw) / uViewport;\n"
            + "  gl_Position = vec4(p.x * 2.0 - 1.0, 1.0 - p.y * 2.0, 0.0, 1.0);\n"
            + "  vUv = aPos;\n"
            + "}\n";

    static final String FRAG_HEADER =
            "#ifdef GL_FRAGMENT_PRECISION_HIGH\n"
            + "precision highp float;\n"
            + "#else\n"
            + "precision mediump float;\n"
            + "#endif\n"
            + "uniform sampler2D uTex;\n"
            + "uniform vec2 uTexSize;\n"    // texture size in texels
            + "uniform vec2 uOutSize;\n"    // size of the quad on screen, in pixels
            + "varying vec2 vUv;\n"
            + "const vec2 NATIVE = vec2(256.0, 192.0);\n"
            // Keeps texels sharp but blends the one-pixel border between them (needs GL_LINEAR).
            + "vec2 sharpUv(vec2 uv) {\n"
            + "  vec2 texel = uv * uTexSize;\n"
            + "  vec2 scale = max(floor(uOutSize / uTexSize), vec2(1.0));\n"
            + "  vec2 f = fract(texel) - 0.5;\n"
            + "  vec2 region = 0.5 - 0.5 / scale;\n"
            + "  vec2 adj = (f - clamp(f, -region, region)) * scale + 0.5;\n"
            + "  return (floor(texel) + adj) / uTexSize;\n"
            + "}\n"
            // Approximates the washed-out, slightly cool look of the original DS panel.
            + "vec3 dsColor(vec3 c) {\n"
            + "  c = pow(c, vec3(2.0));\n"
            + "  c = vec3(dot(c, vec3(0.82, 0.14, 0.04)),\n"
            + "           dot(c, vec3(0.10, 0.76, 0.14)),\n"
            + "           dot(c, vec3(0.04, 0.12, 0.84)));\n"
            + "  return pow(clamp(c, 0.0, 1.0), vec3(1.0 / 2.1));\n"
            + "}\n"
            // Dark lines between DS pixels; fades out when the pixels are too small to show a grid.
            + "float lcdGrid(vec2 uv) {\n"
            + "  vec2 px = uOutSize / NATIVE;\n"
            + "  float strength = clamp((min(px.x, px.y) - 2.0) / 2.0, 0.0, 1.0);\n"
            + "  vec2 f = fract(uv * NATIVE);\n"
            + "  vec2 edge = 1.0 / px;\n"
            + "  float gx = smoothstep(0.0, edge.x, f.x) * smoothstep(0.0, edge.x, 1.0 - f.x);\n"
            + "  float gy = smoothstep(0.0, edge.y, f.y) * smoothstep(0.0, edge.y, 1.0 - f.y);\n"
            + "  return mix(1.0, 0.55 + 0.45 * gx * gy, strength);\n"
            + "}\n";

    static final String[] FRAG_MAIN = {
            // 0: plain
            "void main() { gl_FragColor = vec4(texture2D(uTex, vUv).rgb, 1.0); }\n",
            // 1: bilinear (same code, linear sampling)
            "void main() { gl_FragColor = vec4(texture2D(uTex, vUv).rgb, 1.0); }\n",
            // 2: sharp bilinear
            "void main() { gl_FragColor = vec4(texture2D(uTex, sharpUv(vUv)).rgb, 1.0); }\n",
            // 3: LCD grid
            "void main() {\n"
            + "  vec3 c = texture2D(uTex, sharpUv(vUv)).rgb;\n"
            + "  gl_FragColor = vec4(c * lcdGrid(vUv) * 1.12, 1.0);\n"
            + "}\n",
            // 4: scanlines
            "void main() {\n"
            + "  vec3 c = texture2D(uTex, sharpUv(vUv)).rgb;\n"
            + "  float lines = uOutSize.y / NATIVE.y;\n"
            + "  float strength = clamp((lines - 2.0) / 2.0, 0.0, 1.0);\n"
            + "  float wave = 0.5 - 0.5 * cos(vUv.y * NATIVE.y * 6.2831853);\n"
            + "  gl_FragColor = vec4(c * mix(1.0, 0.62 + 0.48 * wave, strength), 1.0);\n"
            + "}\n",
            // 5: DS colours
            "void main() { gl_FragColor = vec4(dsColor(texture2D(uTex, vUv).rgb), 1.0); }\n",
            // 6: LCD grid + DS colours
            "void main() {\n"
            + "  vec3 c = dsColor(texture2D(uTex, sharpUv(vUv)).rgb);\n"
            + "  gl_FragColor = vec4(c * lcdGrid(vUv) * 1.12, 1.0);\n"
            + "}\n",
    };
}
