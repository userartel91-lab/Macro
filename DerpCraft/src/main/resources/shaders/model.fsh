#version 330 core
in vec2 vUV;
in vec4 vColor;
in float vLayer;
in vec3 vPos;
in float vSky;
uniform sampler2D uTex;
uniform sampler2DArray uArray;
uniform vec3 uFogColor;
uniform vec2 uFog;        // start, end (end <= 0: no fog)
uniform float uAlphaCut;
uniform int uLit;         // 1 = world models: receive sun / moon shadows
out vec4 fragColor;

#include "lighting.glsl"

void main() {
    vec4 c = vColor;
    if (vLayer >= 0.0) c *= texture(uArray, vec3(vUV, floor(vLayer + 0.5)));
    else if (vLayer > -1.5) c *= texture(uTex, vUV);
    if (c.a < uAlphaCut) discard;
    if (uLit == 1 && uLightStrength > 0.0 && vSky > 0.0 && (uCascades > 0 || uEntityCascades > 0 || uRT == 1)) {
        vec3 n = normalize(cross(dFdx(vPos), dFdy(vPos)));
        if (dot(n, vPos) > 0.0) n = -n;
        float ndl = dot(n, uLightDir);
        float vis = ndl <= 0.0 ? 0.0 : sunVisibility(vPos, n) * smoothstep(0.0, 0.2, ndl);
        float amount = uLightStrength * 0.42 * vSky;
        c.rgb *= mix(vec3(1.0 - amount * 0.95, 1.0 - amount * 0.85, 1.0 - amount * 0.6), vec3(1.0), vis);
    }
    if (uFog.y > 0.0) {
        float f = clamp((length(vPos) - uFog.x) / max(uFog.y - uFog.x, 0.001), 0.0, 1.0);
        c.rgb = mix(c.rgb, uFogColor, f);
    }
    fragColor = c;
}
