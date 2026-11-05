#version 330 core
// Anamorphic streak (Kawase): repeated passes with growing tap spacing smear bright light into a long horizontal line.
in vec2 vUV;
uniform sampler2D uSrc;
uniform vec2 uTexel;            // 1 / source size
uniform float uStep;            // 1, 4, 16, 64
uniform float uAtten;           // per-texel attenuation (0.9..0.98)
uniform vec3 uTint;
uniform float uGain;            // last pass: brightens point lights, then soft-limits big ones
uniform float uThreshold;       // extra threshold on the first pass: only the hottest light streaks
out vec4 fragColor;
void main() {
    vec3 s = vec3(0.0);
    float wsum = 0.0;
    for (int i = -3; i <= 3; i++) {
        float w = pow(uAtten, abs(float(i)) * uStep);
        vec3 c = texture(uSrc, vUV + vec2(float(i) * uStep * uTexel.x, 0.0)).rgb;
        s += max(c - uThreshold, 0.0) * w;
        wsum += w;
    }
    vec3 c = s / wsum;
    if (uGain > 0.0) { c *= uGain; c = c / (1.0 + c * 0.5); }
    fragColor = vec4(c * uTint, 1.0);
}
