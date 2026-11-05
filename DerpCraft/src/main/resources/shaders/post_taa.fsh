#version 330 core
// Temporal accumulation for the ray traced lighting: reprojects last frame with the depth buffer and clips it
// to the colour range of the current neighbourhood (no ghosting on movement, noise averages out when still).
in vec2 vUV;
uniform sampler2D uCurrent;
uniform sampler2D uHistory;
uniform sampler2D uDepth;
uniform mat4 uInvProjView;
uniform mat4 uPrevProjView;
uniform vec3 uCamDelta;
uniform vec2 uTexel;
uniform float uBlend;
uniform int uReset;
out vec4 fragColor;

vec3 toYCoCg(vec3 c) { return vec3(dot(c, vec3(0.25, 0.5, 0.25)), dot(c, vec3(0.5, 0.0, -0.5)), dot(c, vec3(-0.25, 0.5, -0.25))); }
vec3 fromYCoCg(vec3 c) { return vec3(c.x + c.y - c.z, c.x + c.z, c.x - c.y - c.z); }

void main() {
    vec3 cur = texture(uCurrent, vUV).rgb;
    if (uReset == 1) { fragColor = vec4(cur, 1.0); return; }
    float d = texture(uDepth, vUV).r;
    vec4 w = uInvProjView * vec4(vUV * 2.0 - 1.0, d * 2.0 - 1.0, 1.0);
    vec3 rel = w.xyz / w.w;
    vec4 pc = uPrevProjView * vec4(rel + uCamDelta, 1.0);
    vec2 puv = pc.xy / pc.w * 0.5 + 0.5;
    if (pc.w <= 0.0 || puv.x < 0.0 || puv.y < 0.0 || puv.x > 1.0 || puv.y > 1.0) { fragColor = vec4(cur, 1.0); return; }
    vec3 m1 = vec3(0.0), m2 = vec3(0.0);
    for (int y = -1; y <= 1; y++)
        for (int x = -1; x <= 1; x++) {
            vec3 c = toYCoCg(texture(uCurrent, vUV + vec2(x, y) * uTexel).rgb);
            m1 += c; m2 += c * c;
        }
    m1 /= 9.0; m2 /= 9.0;
    vec3 sigma = sqrt(max(m2 - m1 * m1, 0.0)) * 1.25;
    vec3 hist = toYCoCg(texture(uHistory, puv).rgb);
    hist = clamp(hist, m1 - sigma, m1 + sigma);
    vec3 outc = mix(fromYCoCg(hist), cur, uBlend);
    fragColor = vec4(max(outc, 0.0), 1.0);
}
