#version 330 core
// God rays (half resolution, blurred and added to the scene by the caller).
// Mode 1 (shadows on): real volumetric light. Along the view ray the shadow map tells which parts of the air the sun
// reaches; that light is scattered towards the camera (strongest when looking towards the sun). Shafts appear through
// leaves and windows even when the sun itself is off screen.
// Mode 0 (shadows off): the classic screen space version, the sky around the sun smeared towards the sun.
in vec2 vUV;
uniform sampler2D uDepth;
uniform mat4 uInvProjView;
uniform int uMode;
uniform float uMaxDist;        // mode 1: how far the air is marched (blocks)
uniform vec2 uSunUV;           // mode 0: sun (or moon) on screen
uniform float uSunFade;        // mode 0: 0 when the sun is behind the camera / far off screen
uniform float uAspect;
#include "lighting.glsl"
out vec4 fragColor;

// sun visibility of a point (camera relative) from the first cascade that holds it: one tap is enough, the result is blurred
float shadowAt(vec3 p) {
    for (int c = 0; c < uCascades; c++) {
        vec4 s = uShadowMats[c] * vec4(p, 1.0);
        vec3 uv = s.xyz * 0.5 + 0.5;
        if (uv.x < 0.0 || uv.y < 0.0 || uv.x > 1.0 || uv.y > 1.0 || uv.z > 1.0) continue;
        return mix(1.0, texture(uShadowMap, vec4(uv.xy, float(c), uv.z)), uShadowFade);
    }
    return 1.0;
}

// Henyey-Greenstein phase function times 4*pi (1 = isotropic)
float phase(float c, float g) { float g2 = g * g; return (1.0 - g2) / pow(1.0 + g2 - 2.0 * g * c, 1.5); }

void main() {
    float d = texture(uDepth, vUV).r;
    if (uMode == 1) {
        vec4 w = uInvProjView * vec4(vUV * 2.0 - 1.0, d * 2.0 - 1.0, 1.0);
        vec3 rel = w.xyz / w.w;
        float len = length(rel);
        vec3 dir = rel / max(len, 1e-4);
        float dist = d >= 1.0 ? uMaxDist : min(len, uMaxDist);
        const int N = 24;
        // steps grow with distance (fine near the camera, where shafts are big); a fixed per-pixel offset (no
        // flicker between frames) hides the steps, the blur afterwards evens it out
        float j = fract(52.9829189 * fract(0.06711056 * gl_FragCoord.x + 0.00583715 * gl_FragCoord.y));
        float acc = 0.0, prev = 0.0;
        for (int i = 0; i < N; i++) {
            float a = (float(i) + j) / float(N);
            float t = dist * a * a;
            float b = (float(i) + 1.0) / float(N);
            float dt = dist * b * b - prev;
            prev = dist * b * b;
            acc += shadowAt(dir * t) * dt * exp(-t * 0.004);
        }
        float ph = 0.1 + 0.9 * phase(dot(dir, uLightDir), 0.7);
        fragColor = vec4(uSunColor * uLightStrength * acc * 0.0004 * ph, 1.0);
    } else {
        if (uSunFade <= 0.0) { fragColor = vec4(0.0, 0.0, 0.0, 1.0); return; }
        const int N = 40;
        vec2 delta = (vUV - uSunUV) * 0.95 / float(N);
        vec2 uv = vUV;
        float decay = 1.0, sum = 0.0;
        for (int i = 0; i < N; i++) {
            uv -= delta;
            vec2 q = clamp(uv, 0.0, 1.0);
            float sky = texture(uDepth, q).r >= 1.0 ? 1.0 : 0.0;
            vec2 o = (q - uSunUV) * vec2(uAspect, 1.0);
            sum += sky * exp(-dot(o, o) * 22.0) * decay;
            decay *= 0.96;
        }
        fragColor = vec4(uSunColor * uLightStrength * sum / float(N) * 1.5 * uSunFade, 1.0);
    }
}
