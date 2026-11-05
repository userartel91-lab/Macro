#version 330 core
// Depth of field, step 2 (half resolution): scatter-as-gather bokeh on a golden-angle spiral. Samples behind the
// pixel can't blur over it more than it is blurred itself, so sharp subjects keep clean edges. An anamorphic
// squeeze narrows the kernel horizontally for oval bokeh.
in vec2 vUV;
uniform sampler2D uSrc;
uniform vec2 uTexel;            // 1 / half resolution
uniform float uMaxCoc;          // half-res pixels
uniform float uSqueeze;
uniform float uRadScale;
out vec4 fragColor;
const float GOLDEN = 2.39996323;

void main() {
    vec4 c0 = texture(uSrc, vUV);
    float centerSize = abs(c0.a);
    vec3 color = c0.rgb;
    float tot = 1.0, fg = 0.0;
    float radius = uRadScale, ang = 0.0;
    for (int i = 0; i < 160 && radius < uMaxCoc; i++) {
        vec2 off = vec2(cos(ang) / uSqueeze, sin(ang)) * radius;
        vec4 s = texture(uSrc, vUV + off * uTexel);
        float size = abs(s.a);
        if (s.a > c0.a) size = min(size, centerSize * 2.0);
        float m = smoothstep(radius - 0.5, radius + 0.5, size);
        color += mix(color / tot, s.rgb, m);
        tot += 1.0;
        if (s.a < c0.a) fg = max(fg, m * min(size, radius + 1.0));
        ang += GOLDEN;
        radius += uRadScale / radius;
    }
    fragColor = vec4(color / tot, max(centerSize, fg));
}
