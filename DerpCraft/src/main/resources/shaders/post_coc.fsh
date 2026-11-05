#version 330 core
// Depth of field, step 1 (half resolution): linear-light colour plus the signed circle of confusion in half-res
// pixels (negative = in front of the focus plane), from a thin lens model on a 24 mm high sensor.
in vec2 vUV;
uniform sampler2D uScene;
uniform sampler2D uDepth;
uniform vec2 uTexel;            // 1 / full resolution
uniform float uNear, uFar;
uniform float uFocal;           // mm
uniform float uAperture;        // f-number
uniform float uFocus;           // metres (= blocks)
uniform float uPxPerMm;         // full-res pixels per mm on the sensor (incl. bokeh scale)
uniform float uMaxCoc;          // half-res pixels
out vec4 fragColor;

float linearZ(float d) {
    float z = d * 2.0 - 1.0;
    return 2.0 * uNear * uFar / (uFar + uNear - z * (uFar - uNear));
}

vec3 lin(vec2 uv) { return pow(max(texture(uScene, uv).rgb, 0.0), vec3(2.2)); }

void main() {
    vec2 h = uTexel * 0.5;
    vec3 c = (lin(vUV + vec2(-h.x, -h.y)) + lin(vUV + vec2(h.x, -h.y)) + lin(vUV + vec2(-h.x, h.y)) + lin(vUV + vec2(h.x, h.y))) * 0.25;
    // nearest depth of the 2x2 footprint keeps foreground edges crisp
    float d = min(min(texture(uDepth, vUV + vec2(-h.x, -h.y)).r, texture(uDepth, vUV + vec2(h.x, -h.y)).r),
                  min(texture(uDepth, vUV + vec2(-h.x, h.y)).r, texture(uDepth, vUV + vec2(h.x, h.y)).r));
    float z = d >= 1.0 ? 1e5 : linearZ(d) * 1000.0;
    float f = uFocal, S = max(uFocus * 1000.0, f * 1.05);
    float coc = f * f / (uAperture * (S - f)) * (z - S) / z;   // mm on the sensor
    float px = clamp(coc * uPxPerMm * 0.5, -uMaxCoc, uMaxCoc);
    fragColor = vec4(c, px);
}
