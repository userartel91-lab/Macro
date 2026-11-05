#version 330 core
// Bloom downsample (dual Kawase). The first pass also converts to linear light and applies the threshold.
in vec2 vUV;
uniform sampler2D uSrc;
uniform vec2 uTexel;        // 1 / source size
uniform int uPrefilter;
uniform float uThreshold;
uniform float uExposure;
out vec4 fragColor;

vec3 prefilter(vec3 c) {
    c = pow(max(c, 0.0), vec3(2.2)) * uExposure;
    float br = max(c.r, max(c.g, c.b));
    float knee = uThreshold * 0.25;
    float soft = clamp(br - uThreshold + knee, 0.0, 2.0 * knee);
    soft = soft * soft / (4.0 * knee + 1e-4);
    float w = max(soft, br - uThreshold) / max(br, 1e-4);
    return c * w;
}

vec3 tap(vec2 uv) {
    vec3 c = texture(uSrc, uv).rgb;
    return uPrefilter == 1 ? prefilter(c) : c;
}

void main() {
    vec2 h = uTexel * 0.5;
    vec3 s = tap(vUV) * 4.0;
    s += tap(vUV - h);
    s += tap(vUV + h);
    s += tap(vUV + vec2(h.x, -h.y));
    s += tap(vUV - vec2(h.x, -h.y));
    fragColor = vec4(s / 8.0, 1.0);
}
