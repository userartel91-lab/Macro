#version 330 core
// Final grade: exposure, bloom, white balance, tone mapping, lift/gamma/gain, contrast, saturation, vibrance,
// split toning, curves, 3D LUT, vignette and grain. With neutral settings the output equals the input exactly.
in vec2 vUV;
uniform sampler2D uScene;
uniform sampler2D uBloom;
uniform sampler2D uCurves;        // 256x1: rgb = per-channel curves, a = master curve
uniform sampler3D uLut;
uniform sampler2D uFlare;         // anamorphic streaks and ghosts (linear light)
uniform float uFlareOn;
uniform float uDistortion, uAberration, uLetterbox;   // letterbox = target aspect (0 = off)
uniform float uExposure;          // linear multiplier
uniform float uBloomStrength;
uniform vec3 uWB;
uniform int uTonemap;
uniform int uTonemap2;            // look transitions: mixed with uTonemap by uTonemapMix
uniform float uTonemapMix;
uniform float uContrast, uSaturation, uVibrance, uLift, uGamma, uGain;
uniform vec3 uShadowTint, uHighlightTint;
uniform int uUseCurves, uUseLut;
uniform float uLutSize, uLutStrength;
uniform float uVignette, uGrain, uTime;
uniform vec2 uRes;
out vec4 fragColor;

const vec3 LUMA = vec3(0.2126, 0.7152, 0.0722);

vec3 aces(vec3 x) {
    const float a = 2.51, b = 0.03, c = 2.43, d = 0.59, e = 0.14;
    return clamp((x * (a * x + b)) / (x * (c * x + d) + e), 0.0, 1.0);
}

vec3 uncharted(vec3 x) {
    const float A = 0.15, B = 0.50, C = 0.10, D = 0.20, E = 0.02, F = 0.30;
    return ((x * (A * x + C * B) + D * E) / (x * (A * x + B) + D * F)) - E / F;
}

// Gran Turismo tone curve (Uchimura): linear mid-tones, gentle toe, smooth shoulder.
vec3 gt(vec3 x) {
    const float P = 1.0, a = 1.0, m = 0.22, l = 0.4, c = 1.33;
    float l0 = ((P - m) * l) / a, S0 = m + l0, S1 = m + a * l0, C2 = (a * P) / (P - S1), CP = -C2 / P;
    vec3 w0 = 1.0 - smoothstep(0.0, m, x);
    vec3 w2 = step(m + l0, x);
    vec3 w1 = 1.0 - w0 - w2;
    vec3 T = m * pow(x / m, vec3(c));
    vec3 S = P - (P - S1) * exp(CP * (x - S0));
    vec3 L = m + a * (x - m);
    return T * w0 + L * w1 + S * w2;
}

vec3 tonemap(vec3 c, int mode) {
    if (mode == 1) c = gt(c);
    else if (mode == 2) c = uncharted(c * 4.0) / uncharted(vec3(11.2));
    else if (mode == 3) c = c * 1.5 * (1.0 + c * 1.5 / 4.0) / (1.0 + c * 1.5);
    else if (mode == 4) c = aces(c * 1.2);
    return clamp(c, 0.0, 1.0);
}

float hash(vec2 p) { return fract(sin(dot(p, vec2(12.9898, 78.233))) * 43758.5453); }

vec3 curve(vec3 c) {
    const float s = 255.0 / 256.0, o = 0.5 / 256.0;
    vec3 m = vec3(texture(uCurves, vec2(c.r * s + o, 0.5)).a, texture(uCurves, vec2(c.g * s + o, 0.5)).a, texture(uCurves, vec2(c.b * s + o, 0.5)).a);
    return vec3(texture(uCurves, vec2(m.r * s + o, 0.5)).r, texture(uCurves, vec2(m.g * s + o, 0.5)).g, texture(uCurves, vec2(m.b * s + o, 0.5)).b);
}

void main() {
    vec2 uv = vUV;
    vec2 d = uv - 0.5;
    float aspect = uRes.x / uRes.y;
    float r2 = dot(d * vec2(aspect, 1.0), d * vec2(aspect, 1.0)) / (aspect * aspect * 0.25 + 0.25);
    if (uDistortion != 0.0) {
        // barrel (positive) / pincushion (negative); corners stay in place so the frame stays filled
        uv = 0.5 + d * (1.0 - uDistortion * 0.25 * (1.0 - r2));
        d = uv - 0.5;
    }
    vec3 c;
    if (uAberration > 0.0) {
        vec2 ca = d * uAberration * 0.012 * r2;
        c = vec3(texture(uScene, uv + ca).r, texture(uScene, uv).g, texture(uScene, uv - ca).b);
    } else c = texture(uScene, uv).rgb;
    // to linear light
    c = pow(max(c, 0.0), vec3(2.2)) * uExposure;
    c += texture(uBloom, uv).rgb * uBloomStrength;
    if (uFlareOn > 0.0) c += texture(uFlare, uv).rgb;
    c *= uWB;
    if (uTonemapMix <= 0.0) c = tonemap(c, uTonemap);
    else c = mix(tonemap(c, uTonemap), tonemap(c, uTonemap2), uTonemapMix);
    // back to display space
    c = pow(c, vec3(1.0 / 2.2));
    c = uGain * (c + uLift * (1.0 - c));
    c = pow(max(c, 0.0), vec3(1.0 / uGamma));
    c = (c - 0.5) * uContrast + 0.5;
    float l = dot(c, LUMA);
    c = mix(vec3(l), c, uSaturation);
    float sat = max(c.r, max(c.g, c.b)) - min(c.r, min(c.g, c.b));
    c = mix(vec3(l), c, 1.0 + uVibrance * (1.0 - sat));
    float ls = clamp(l, 0.0, 1.0);
    c += uShadowTint * (1.0 - ls) * (1.0 - ls) + uHighlightTint * ls * ls;
    c = clamp(c, 0.0, 1.0);
    if (uUseCurves == 1) c = curve(c);
    if (uUseLut == 1) {
        vec3 uvw = c * ((uLutSize - 1.0) / uLutSize) + 0.5 / uLutSize;
        c = mix(c, texture(uLut, uvw).rgb, uLutStrength);
    }
    if (uVignette > 0.0) {
        vec2 d = (vUV - 0.5) * vec2(uRes.x / uRes.y, 1.0);
        c *= 1.0 - uVignette * smoothstep(0.25, 1.1, length(d) * 1.25);
    }
    if (uGrain > 0.0) {
        float n = hash(vUV * uRes + fract(uTime) * 917.0) - 0.5;
        c += n * uGrain * 0.2 * (1.0 - dot(c, LUMA) * 0.6);
    }
    c += (hash(vUV * uRes + 3.7) - 0.5) / 255.0;   // dither against banding
    if (uLetterbox > 0.0) {
        if (aspect < uLetterbox) { float hh = aspect / uLetterbox * 0.5; if (abs(vUV.y - 0.5) > hh) c = vec3(0.0); }
        else { float hw = uLetterbox / aspect * 0.5; if (abs(vUV.x - 0.5) > hw) c = vec3(0.0); }
    }
    fragColor = vec4(c, 1.0);
}
