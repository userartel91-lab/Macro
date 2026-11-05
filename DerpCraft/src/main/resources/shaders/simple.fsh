#version 330 core
in vec2 vUV;
in vec4 vColor;
in vec3 vPos;
uniform sampler2D uTex;
uniform int uUseTex;
uniform vec4 uColor;
uniform vec3 uFogColor;
uniform vec2 uFog;      // start, end; end <= 0 disables fog
out vec4 fragColor;
void main() {
    vec4 c = vColor * uColor;
    if (uUseTex != 0) c *= texture(uTex, vUV);
    if (c.a < 0.004) discard;
    if (uFog.y > 0.0) {
        float f = clamp((length(vPos) - uFog.x) / max(uFog.y - uFog.x, 0.001), 0.0, 1.0);
        c.rgb = mix(c.rgb, uFogColor, f);
    }
    fragColor = c;
}
