package com.enhance.comic.render

/**
 * GLSL ES 3.10 sources.
 *
 * Pipeline: bitmap -> srcTex (RGBA8) -> copy pass -> ping0 (RGBA8)
 *           -> [denoise] -> [sharpen] -> [contrast] (ping-pong) -> display pass.
 *
 * All intermediate targets are RGBA8: the input is 8 bit anyway, and RGBA8 is
 * guaranteed color-renderable on every GLES 3.1 device (RGBA16F is not).
 */
object Shaders {

    /** Unit quad, triangle strip: (0,0) (1,0) (0,1) (1,1). */
    val QUAD = floatArrayOf(
        0f, 0f,
        1f, 0f,
        0f, 1f,
        1f, 1f,
    )

    /** Shared vertex shader: maps the unit quad to an NDC rect and to a UV rect. */
    const val VERT = """#version 310 es
layout(location = 0) in vec2 aPos;
uniform vec4 uRect;   // x, y, w, h in NDC
uniform vec4 uUv;     // u0, v0, u1, v1 (top-left origin)
out vec2 vUv;
void main() {
    vec2 p = uRect.xy + aPos * uRect.zw;
    gl_Position = vec4(p, 0.0, 1.0);
    float u = mix(uUv.x, uUv.z, aPos.x);
    float v = mix(uUv.y, uUv.w, aPos.y);
    vUv = vec2(u, 1.0 - v);   // GL textures are bottom-up
}
"""

    /** Straight copy into the first RGBA8 target. */
    const val FRAG_COPY = """#version 310 es
precision highp float;
in vec2 vUv;
uniform sampler2D uTex;
out vec4 fragColor;
void main() {
    fragColor = texture(uTex, vUv);
}
"""

    const val FRAG_DISPLAY = """#version 310 es
precision highp float;
in vec2 vUv;
uniform sampler2D uTex;
uniform vec2 uTexSize;
uniform float uTexelsPerPixel;
uniform int uFilter;       // 0 nearest, 1 bilinear, 2 bicubic, 3 lanczos3
uniform vec3 uColor;       // brightness, contrast, gamma
out vec4 fragColor;

vec4 texel(ivec2 p) {
    return texelFetch(uTex, clamp(p, ivec2(0), ivec2(uTexSize) - 1), 0);
}

vec4 bilinear(vec2 uv) {
    vec2 px = uv * uTexSize - 0.5;
    vec2 f = floor(px);
    vec2 t = px - f;
    ivec2 i = ivec2(f);
    vec4 c00 = texel(i);
    vec4 c10 = texel(i + ivec2(1, 0));
    vec4 c01 = texel(i + ivec2(0, 1));
    vec4 c11 = texel(i + ivec2(1, 1));
    return mix(mix(c00, c10, t.x), mix(c01, c11, t.x), t.y);
}

vec4 cubicWeights(float t) {
    float t2 = t * t;
    float t3 = t2 * t;
    return vec4(
        -0.5 * t3 + t2 - 0.5 * t,
         1.5 * t3 - 2.5 * t2 + 1.0,
        -1.5 * t3 + 2.0 * t2 + 0.5 * t,
         0.5 * t3 - 0.5 * t2);
}

vec4 bicubic(vec2 uv) {
    vec2 px = uv * uTexSize - 0.5;
    vec2 f = floor(px);
    vec2 t = px - f;
    ivec2 base = ivec2(f);
    vec4 wx = cubicWeights(t.x);
    vec4 wy = cubicWeights(t.y);
    vec4 sum = vec4(0.0);
    for (int y = 0; y < 4; y++) {
        vec4 row = vec4(0.0);
        for (int x = 0; x < 4; x++) {
            row += wx[x] * texel(base + ivec2(x - 1, y - 1));
        }
        sum += wy[y] * row;
    }
    return sum;
}

float lanczos(float x) {
    float ax = abs(x);
    if (ax < 1e-5) return 1.0;
    if (ax >= 3.0) return 0.0;
    float px = 3.141592653589793 * x;
    return 3.0 * sin(px) * sin(px / 3.0) / (px * px);
}

vec4 lanczos3(vec2 uv) {
    vec2 px = uv * uTexSize - 0.5;
    vec2 f = floor(px);
    vec2 t = px - f;
    ivec2 base = ivec2(f);
    vec4 sum = vec4(0.0);
    float wsum = 0.0;
    for (int y = 0; y < 6; y++) {
        for (int x = 0; x < 6; x++) {
            float w = lanczos(float(x - 2) - t.x) * lanczos(float(y - 2) - t.y);
            sum += w * texel(base + ivec2(x - 2, y - 2));
            wsum += w;
        }
    }
    if (wsum < 0.0001) return texel(base);
    return sum / wsum;
}

void main() {
    vec4 c;
    if (uTexelsPerPixel > 1.02) {
        // Minifying: manual filtering offers no win without mipmaps.
        c = bilinear(vUv);
    } else if (uFilter == 0) {
        c = texel(ivec2(floor(vUv * uTexSize)));
    } else if (uFilter == 2) {
        c = bicubic(vUv);
    } else if (uFilter == 3) {
        c = lanczos3(vUv);
    } else {
        c = bilinear(vUv);
    }

    vec3 rgb = c.rgb * uColor.x;
    rgb = (rgb - 0.5) * uColor.y + 0.5;
    rgb = clamp(rgb, 0.0, 1.0);
    rgb = pow(rgb, vec3(1.0 / max(uColor.z, 0.05)));
    fragColor = vec4(rgb, c.a);
}
"""

    /** Edge-gated unsharp mask. */
    const val COMPUTE_SHARPEN = """#version 310 es
precision highp float;
layout(local_size_x = 16, local_size_y = 16) in;
layout(rgba8, binding = 0) uniform readonly highp image2D uSrc;
layout(rgba8, binding = 1) uniform writeonly highp image2D uDst;
uniform float uAmount;
uniform float uGate;

vec3 sample3(ivec2 p, ivec2 size) {
    return imageLoad(uSrc, clamp(p, ivec2(0), size - 1)).rgb;
}

void main() {
    ivec2 p = ivec2(gl_GlobalInvocationID.xy);
    ivec2 size = imageSize(uSrc);
    if (p.x >= size.x || p.y >= size.y) return;

    vec4 c = imageLoad(uSrc, p);
    vec3 blur =
        (sample3(p + ivec2(-1, -1), size) + 2.0 * sample3(p + ivec2(0, -1), size) + sample3(p + ivec2(1, -1), size) +
         2.0 * sample3(p + ivec2(-1, 0), size) + 4.0 * c.rgb + 2.0 * sample3(p + ivec2(1, 0), size) +
         sample3(p + ivec2(-1, 1), size) + 2.0 * sample3(p + ivec2(0, 1), size) + sample3(p + ivec2(1, 1), size)) / 16.0;

    vec3 detail = c.rgb - blur;
    float mag = length(detail) * 8.0;
    // Flat areas keep a quarter of the effect so paper grain is not amplified.
    float gate = mix(0.25, 1.0, smoothstep(uGate, uGate + 0.25, mag));
    vec3 outRgb = clamp(c.rgb + detail * uAmount * gate, 0.0, 1.0);
    imageStore(uDst, p, vec4(outRgb, c.a));
}
"""

    /**
     * Kuwahara style denoise: pick the lowest-variance quadrant average.
     * The radius is baked into the source (constant trip count) before compiling.
     */
    fun computeDenoise(radius: Int): String {
        val r = radius.coerceIn(1, 3)
        return """#version 310 es
precision highp float;
layout(local_size_x = 16, local_size_y = 16) in;
layout(rgba8, binding = 0) uniform readonly highp image2D uSrc;
layout(rgba8, binding = 1) uniform writeonly highp image2D uDst;
uniform float uBlend;
const int R = $r;

vec3 sample3(ivec2 p, ivec2 size) {
    return imageLoad(uSrc, clamp(p, ivec2(0), size - 1)).rgb;
}

void main() {
    ivec2 p = ivec2(gl_GlobalInvocationID.xy);
    ivec2 size = imageSize(uSrc);
    if (p.x >= size.x || p.y >= size.y) return;

    vec4 c = imageLoad(uSrc, p);
    vec3 best = c.rgb;
    float bestVar = 1e9;

    for (int qy = 0; qy < 2; qy++) {
        for (int qx = 0; qx < 2; qx++) {
            vec3 sum = vec3(0.0);
            vec3 sum2 = vec3(0.0);
            float n = 0.0;
            for (int y = 0; y <= R; y++) {
                for (int x = 0; x <= R; x++) {
                    int dy = qy == 0 ? -y : y;
                    int dx = qx == 0 ? -x : x;
                    vec3 s = sample3(p + ivec2(dx, dy), size);
                    sum += s;
                    sum2 += s * s;
                    n += 1.0;
                }
            }
            vec3 mean = sum / n;
            vec3 var = max(sum2 / n - mean * mean, vec3(0.0));
            float v = var.r + var.g + var.b;
            if (v < bestVar) {
                bestVar = v;
                best = mean;
            }
        }
    }

    vec3 outRgb = mix(c.rgb, best, uBlend);
    imageStore(uDst, p, vec4(outRgb, c.a));
}
"""
    }

    /** Gamma lift plus a soft S-curve, blended against the original. */
    const val COMPUTE_CONTRAST = """#version 310 es
precision highp float;
layout(local_size_x = 16, local_size_y = 16) in;
layout(rgba8, binding = 0) uniform readonly highp image2D uSrc;
layout(rgba8, binding = 1) uniform writeonly highp image2D uDst;
uniform float uBlend;

void main() {
    ivec2 p = ivec2(gl_GlobalInvocationID.xy);
    ivec2 size = imageSize(uSrc);
    if (p.x >= size.x || p.y >= size.y) return;

    vec4 c = imageLoad(uSrc, p);
    vec3 x = clamp(c.rgb, 0.0, 1.0);
    x = pow(x, vec3(1.0 / 0.92));
    x = mix(x, smoothstep(0.0, 1.0, x), 0.35);
    imageStore(uDst, p, vec4(mix(c.rgb, x, uBlend), c.a));
}
"""
}
