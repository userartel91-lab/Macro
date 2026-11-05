#version 330 core
// Screen space reflections on water and glossy blocks. The terrain writes its material into the second colour
// attachment of the scene buffer (terrain.fsh, fragMaterial): octahedral normal, kind + sky visibility, distance.
// Output: reflected colour and its weight (Fresnel), blended over the scene by the caller.
in vec2 vUV;
uniform sampler2D uScene;
uniform sampler2D uDepth;
uniform sampler2D uMaterial;
uniform mat4 uProjView;          // camera-relative projection * view
uniform mat4 uInvProjView;
uniform float uNear, uFar;
uniform vec3 uSkyColor, uFogColor, uSunColor, uLightDir;
uniform float uLightStrength;
uniform float uBlocks;           // strength of the block reflections
uniform float uWater;            // strength of the water reflections (1; less while a look transition fades them)
uniform vec2 uFog;               // fog start, end (blocks): the reflection fades into the fog like the surface
out vec4 fragColor;

vec3 octDecode(vec2 e) {
    vec3 v = vec3(e.x, 1.0 - abs(e.x) - abs(e.y), e.y);
    if (v.y < 0.0) v.xz = (1.0 - abs(v.zx)) * vec2(v.x >= 0.0 ? 1.0 : -1.0, v.z >= 0.0 ? 1.0 : -1.0);
    return normalize(v);
}

float linearDepth(float d) { return 2.0 * uNear * uFar / (uFar + uNear - (d * 2.0 - 1.0) * (uFar - uNear)); }

vec3 position(vec2 uv, float d) {
    vec4 w = uInvProjView * vec4(uv * 2.0 - 1.0, d * 2.0 - 1.0, 1.0);
    return w.xyz / w.w;
}

// sky and ground seen in a reflected direction (when the ray finds nothing on screen)
vec3 skyColor(vec3 rd) {
    float up = clamp(rd.y * 0.5 + 0.5, 0.0, 1.0);
    vec3 sky = mix(uSkyColor, vec3(dot(uSkyColor, vec3(0.3, 0.5, 0.2))), 0.35);
    vec3 c = mix(uFogColor * 0.35, mix(uFogColor, sky, smoothstep(0.5, 0.75, up)), smoothstep(0.42, 0.52, up));
    return c + uSunColor * uLightStrength * pow(max(dot(rd, uLightDir), 0.0), 256.0) * 4.0;
}

float hash12(vec2 p) { vec3 q = fract(vec3(p.xyx) * 0.1031); q += dot(q, q.yzx + 33.33); return fract((q.x + q.y) * q.z); }

void main() {
    fragColor = vec4(0.0);
    ivec2 px = ivec2(gl_FragCoord.xy);
    vec4 m = texelFetch(uMaterial, px, 0);
    if (m.z < 1.0) return;
    float d = texelFetch(uDepth, px, 0).r;
    if (d >= 1.0) return;
    vec3 rel = position(vUV, d);
    float dist = length(rel);
    // the material belongs to the terrain: something drawn later in front of it (player, NPC, item, cloud) hides it
    if (abs(dist - m.w) > 0.02 * dist + 0.08) return;
    float kind = floor(m.z + 0.001);
    float skyVis = clamp((m.z - kind) / 0.9, 0.0, 1.0);
    bool water = kind > 63.5;
    float r = water ? 1.0 : kind / 63.0;
    vec3 N = octDecode(m.xy);
    vec3 V = rel / dist;
    float cosv = dot(-V, N);
    if (cosv <= 0.0) return;
    vec3 R = reflect(V, N);
    if (water && R.y < 0.02) R = normalize(vec3(R.x, 0.02, R.z));
    float p5 = pow(1.0 - cosv, 5.0);
    float F;
    if (water) F = (0.02 + 0.98 * p5) * uWater;
    else {
        float f0 = 0.04 + 0.5 * r * r;
        F = (f0 + (1.0 - f0) * p5) * min(1.0, r * 2.5) * uBlocks;
        // matte walls (concrete, quartz) only shine on floors; glass and metal everywhere
        if (N.y < 0.5 && r < 0.4) F *= 0.4;
        F = min(F, 0.95);
    }
    if (F < 0.003) return;

    // ---------------------------------------------------------------- ray march in camera-relative space
    vec3 start = rel + N * (0.03 + dist * 0.002);
    float stepLen = max(0.1, dist * 0.012);
    float t = stepLen * (0.4 + 0.6 * hash12(gl_FragCoord.xy));
    float prevT = 0.0;
    bool hit = false;
    vec2 hitUV = vec2(0.0);
    for (int i = 0; i < 48; i++) {
        vec3 p = start + R * t;
        vec4 c = uProjView * vec4(p, 1.0);
        if (c.w <= uNear) break;
        vec2 uv = c.xy / c.w * 0.5 + 0.5;
        if (uv.x < 0.0 || uv.y < 0.0 || uv.x > 1.0 || uv.y > 1.0) break;
        float sd = textureLod(uDepth, uv, 0.0).r;
        if (sd < 1.0 && c.w > linearDepth(sd)) {
            // crossed a surface: binary search for the exact point
            float lo = prevT, hi = t;
            vec2 huv = uv;
            float diff = c.w - linearDepth(sd);
            for (int j = 0; j < 6; j++) {
                float mid = (lo + hi) * 0.5;
                vec4 mc = uProjView * vec4(start + R * mid, 1.0);
                vec2 muv = mc.xy / mc.w * 0.5 + 0.5;
                float ms = textureLod(uDepth, muv, 0.0).r;
                float md = mc.w - linearDepth(ms);
                if (ms < 1.0 && md > 0.0) { hi = mid; huv = muv; diff = md; } else lo = mid;
            }
            // passed behind a thin object (a pole, a leaf): not the reflection, keep going. A "hit" on the reflecting
            // surface itself (far away the depth buffer gets coarse, waves tilt the ray) is not one either.
            vec3 hp = position(huv, textureLod(uDepth, huv, 0.0).r);
            if (diff < max(0.75, stepLen * 3.0) && dot(hp - rel, N) > 0.05 + dist * 0.004) { hit = true; hitUV = huv; break; }
        }
        prevT = t;
        t += stepLen;
        stepLen *= 1.09;
    }

    // what the ray sees when it finds nothing: the real sky on screen (clouds, sun, stars) or the analytic one,
    // dark under a roof (the sky light of the surface says how much sky it sees)
    vec3 sky = skyColor(R);
    vec4 sc = uProjView * vec4(R * 1000.0, 1.0);
    if (sc.w > 0.0) {
        vec2 suv = sc.xy / sc.w * 0.5 + 0.5;
        if (suv.x > 0.0 && suv.y > 0.0 && suv.x < 1.0 && suv.y < 1.0 && textureLod(uDepth, suv, 0.0).r >= 1.0) {
            float e = smoothstep(0.0, 0.08, min(min(suv.x, suv.y), min(1.0 - suv.x, 1.0 - suv.y)));
            sky = mix(sky, textureLod(uScene, suv, 0.0).rgb, e);
        }
    }
    vec3 refl = sky * skyVis + uFogColor * (1.0 - skyVis) * 0.08;
    if (hit) {
        float e = smoothstep(0.0, 0.1, min(min(hitUV.x, hitUV.y), min(1.0 - hitUV.x, 1.0 - hitUV.y)));
        refl = mix(refl, textureLod(uScene, hitUV, 0.0).rgb, e);
    }
    // metals (iron, gold...) tint their reflection with their own colour
    if (!water && r >= 0.6) {
        vec3 own = texelFetch(uScene, px, 0).rgb;
        float mx = max(max(own.r, own.g), max(own.b, 1e-3));
        refl *= mix(vec3(1.0), own / mx, 0.6);
    }
    refl = mix(refl, uFogColor, clamp((dist - uFog.x) / max(uFog.y - uFog.x, 0.001), 0.0, 1.0));
    fragColor = vec4(min(refl, vec3(32.0)), F);
}
