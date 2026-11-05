#version 330 core
// Bloom upsample (dual Kawase tent), added onto the next larger level with additive blending.
in vec2 vUV;
uniform sampler2D uSrc;
uniform vec2 uTexel;        // 1 / source size
out vec4 fragColor;
void main() {
    vec2 h = uTexel * 0.5;
    vec3 s = texture(uSrc, vUV + vec2(-h.x * 2.0, 0.0)).rgb;
    s += texture(uSrc, vUV + vec2(-h.x, h.y)).rgb * 2.0;
    s += texture(uSrc, vUV + vec2(0.0, h.y * 2.0)).rgb;
    s += texture(uSrc, vUV + vec2(h.x, h.y)).rgb * 2.0;
    s += texture(uSrc, vUV + vec2(h.x * 2.0, 0.0)).rgb;
    s += texture(uSrc, vUV + vec2(h.x, -h.y)).rgb * 2.0;
    s += texture(uSrc, vUV + vec2(0.0, -h.y * 2.0)).rgb;
    s += texture(uSrc, vUV + vec2(-h.x, -h.y)).rgb * 2.0;
    fragColor = vec4(s / 12.0, 1.0);
}
