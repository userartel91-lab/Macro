#version 330 core
// Applies the half resolution ambient occlusion (post_ssao.fsh) to the full resolution scene: a depth aware 4x4
// blur (removes the sample noise without bleeding over edges) and upsampling in one step. Output is multiplied
// into the scene by blending (GL_ZERO, GL_SRC_COLOR).
in vec2 vUV;
uniform sampler2D uAO;          // half resolution: r = visibility, g = linear depth
uniform sampler2D uDepth;       // full resolution depth
uniform float uNear, uFar;
uniform float uStrength;        // 0 = off, 1 = default, 2 = twice as dark
out vec4 fragColor;

float linearDepth(float d) { return 2.0 * uNear * uFar / (uFar + uNear - (d * 2.0 - 1.0) * (uFar - uNear)); }

void main() {
    float d = textureLod(uDepth, vUV, 0.0).r;
    if (d >= 1.0) { fragColor = vec4(1.0); return; }
    float lin = linearDepth(d);
    ivec2 size = textureSize(uAO, 0);
    vec2 c = vUV * vec2(size) - 0.5;
    ivec2 base = ivec2(floor(c)) - 1;
    vec2 f = fract(c);
    float sum = 0.0, wsum = 0.0;
    float tol = 0.04 * lin + 0.05;
    for (int y = 0; y < 4; y++)
        for (int x = 0; x < 4; x++) {
            ivec2 q = clamp(base + ivec2(x, y), ivec2(0), size - 1);
            vec2 v = texelFetch(uAO, q, 0).rg;
            vec2 o = vec2(float(x) - 1.0, float(y) - 1.0) - f;
            float w = exp(-dot(o, o) * 0.35) * exp(-min(abs(v.g - lin) / tol, 60.0));
            sum += v.r * w;
            wsum += w;
        }
    float ao = wsum > 1e-4 ? sum / wsum : 1.0;
    ao = clamp(1.0 - (1.0 - ao) * uStrength * 1.5, 0.0, 1.0);   // 1.5: clearly visible at the default strength
    fragColor = vec4(vec3(ao), 1.0);
}
