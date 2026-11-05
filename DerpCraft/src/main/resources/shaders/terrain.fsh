#version 330 core
in vec3 vUV;
in vec4 vLight;
in float vShade;
flat in int vTint;
in vec3 vRel;
in vec2 vTintPos;
flat in int vNormal;

uniform sampler2DArray uBlocks;
uniform sampler2D uGrassMap;
uniform sampler2D uFoliageMap;
uniform sampler2D uBiome;          // RG = temperature, humidity; 4096x4096 wrapped world map
uniform ivec2 uCamBlockXZ;
uniform vec3 uFixedTints[16];
uniform vec3 uFogColor;
uniform vec2 uFog;                 // start, end
uniform float uAlphaCut;
uniform float uSun;                // world sun brightness 0.2..1
uniform float uGamma;              // brightness option 0..1
uniform float uFlicker;            // torch flicker ~1.5
uniform float uColoredLight;       // 0 = classic white block light, 1 = RGB coloured light
uniform sampler2D uEmissive;       // per texture layer: r = emission, g = flags (1 = water)
uniform float uEmissiveBoost;

uniform int uFrame;
uniform int uRTRays;

// water shader and screen space reflections
uniform float uWaveTime;           // seconds of game time (wave.glsl)
uniform float uWater;              // wave height, 0 = vanilla flat water
uniform float uSSRWater;           // 1 = the post chain reflects the world in the water (no sky reflection here);
                                   // between 0 and 1 while a look transition fades the reflections in or out
uniform float uWaterFade;          // 1 = water shader fully on (a look transition fades it in / out)
uniform float uSSRBlocks;          // > 0 = glossy blocks (material table alpha) reflect in the post chain

// dynamic lights (held, dropped and thrown light sources): xyz relative to the camera + level 0..15, colour (max component 1)
uniform int uDynCount;
uniform vec4 uDynPos[16];
uniform vec3 uDynCol[16];

#include "lighting.glsl"

layout(location = 0) out vec4 fragColor;
// Material for the screen space reflections (second colour attachment of the scene buffer, only bound when they
// are on): xy = octahedral normal, z = kind (1..63 glossy block, 64 water) + sky visibility * 0.9, w = distance.
layout(location = 1) out vec4 fragMaterial;

const vec3 NORMALS[8] = vec3[](vec3(0,-1,0), vec3(0,1,0), vec3(0,0,-1), vec3(0,0,1), vec3(-1,0,0), vec3(1,0,0), vec3(0), vec3(0));


// Minecraft 1.12 lightmap; with coloured light every channel uses its own block light level
// (white light gives exactly the vanilla result). 'skyMul' scales the sky term (shadows).
vec3 lightmap(float s, vec3 rgb, vec3 skyMul) {
    vec3 b = mix(vec3(max(rgb.r, max(rgb.g, rgb.b))), rgb, uColoredLight);
    float sun = uSun;
    float sky = bright(s) * (sun * 0.95 + 0.05);
    vec3 bl = vec3(bright(b.r), bright(b.g), bright(b.b)) * uFlicker;
    float skyRG = sky * (sun * 0.65 + 0.35);
    vec3 skyC = vec3(skyRG, skyRG, sky) * skyMul;
    vec3 c = vec3(skyC.r + bl.r, skyC.g + bl.g * ((bl.g * 0.6 + 0.4) * 0.6 + 0.4), skyC.b + bl.b * (bl.b * bl.b * 0.6 + 0.4));
    c = clamp(c * 0.96 + 0.03, 0.0, 1.0);
    vec3 inv = 1.0 - c;
    vec3 g = 1.0 - inv * inv * inv * inv;
    c = mix(c, g, uGamma);
    return clamp(c * 0.96 + 0.03, 0.0, 1.0);
}

// block light part only (for ray traced lighting)
vec3 blockLight(vec3 rgb) {
    vec3 b = mix(vec3(max(rgb.r, max(rgb.g, rgb.b))), rgb, uColoredLight);
    vec3 bl = vec3(bright(b.r), bright(b.g), bright(b.b)) * uFlicker;
    return vec3(bl.r, bl.g * ((bl.g * 0.6 + 0.4) * 0.6 + 0.4), bl.b * (bl.b * bl.b * 0.6 + 0.4));
}

// Radiance leaving a hit voxel: its colour lit by the light stored in the cell in front of it, plus emission.
vec3 hitRadiance(ivec3 cell, ivec3 prev, vec3 rd) {
    uvec2 v = voxel(cell);
    vec3 albedo = texelFetch(uBlockTable, ivec2(int(v.x), 0), 0).rgb;
    vec4 em = texelFetch(uBlockTable, ivec2(int(v.x), 1), 0);
    uint l = voxel(prev).y;
    float sky = float(l & 15u);
    vec3 rgb = vec3(float((l >> 4) & 15u), float((l >> 8) & 15u), float((l >> 12) & 15u));
    float skyLit = bright(sky);
    vec3 light = uSkyColor * skyLit * 0.9 + uSunColor * uLightStrength * skyLit * 0.55 + blockLight(rgb);
    return albedo * light + em.rgb * em.a * 0.6;
}

vec3 skyRadiance(vec3 rd) {
    float up = clamp(rd.y * 0.5 + 0.5, 0.0, 1.0);
    vec3 sky = mix(uSkyColor, vec3(dot(uSkyColor, vec3(0.3, 0.5, 0.2))), 0.35);
    return mix(uFogColor * 0.4, sky, up) + uSunColor * uLightStrength * pow(max(dot(rd, uLightDir), 0.0), 64.0) * 2.0;
}

// cosine weighted direction around n
vec3 cosineDir(vec3 n, vec2 u) {
    float r = sqrt(u.x), phi = 6.2831853 * u.y;
    vec3 t = abs(n.y) < 0.99 ? normalize(cross(n, vec3(0, 1, 0))) : vec3(1, 0, 0);
    vec3 b = cross(n, t);
    return normalize(t * r * cos(phi) + b * r * sin(phi) + n * sqrt(max(0.0, 1.0 - u.x)));
}

// ------------------------------------------------------------------ water waves
// Sum of directional waves; every wave vector is a whole multiple of 2*pi/1024, so the pattern repeats every 1024
// blocks and the wrapped position (no precision loss far from spawn) has no seam. Returns the surface normal.
const float WATER_K = 6.28318531 / 1024.0;

// 'footprint' = blocks covered by one pixel: waves shorter than about 3 pixels fade out (no sparkling noise
// in the distance, far water turns into a calm mirror like real water seen at a grazing angle)
vec3 waterNormal(vec2 p, float dist, float footprint) {
    float t = uWaveTime;
    vec2 g = vec2(0.0);
    // wave vector (whole multiples of WATER_K), height, speed (rad/s); wavelengths 5 .. 1.1 blocks
    const vec4 W[6] = vec4[](vec4(173.0, 101.0, 0.060, 1.6), vec4(-131.0, 211.0, 0.045, 2.0), vec4(311.0, -149.0, 0.025, 2.9),
                             vec4(-389.0, -277.0, 0.014, 3.7), vec4(521.0, 433.0, 0.009, 4.6), vec4(-701.0, 619.0, 0.005, 5.8));
    for (int i = 0; i < 6; i++) {
        vec2 k = W[i].xy * WATER_K;
        float wl = 6.28318531 / length(k);
        g += k * W[i].z * cos(dot(p, k) + t * W[i].w) * smoothstep(2.0, 4.0, wl / max(footprint, 1e-4));
    }
    g *= uWater / (1.0 + dist * 0.01);
    return normalize(vec3(-g.x, 1.0, -g.y));
}

vec2 octEncode(vec3 n) {
    n /= abs(n.x) + abs(n.y) + abs(n.z);
    vec2 p = n.xz;
    if (n.y < 0.0) p = (1.0 - abs(p.yx)) * vec2(p.x >= 0.0 ? 1.0 : -1.0, p.y >= 0.0 ? 1.0 : -1.0);
    return p;
}

float ign(vec2 p, int frame) {
    p += 5.588238 * float(frame & 63);
    return fract(52.9829189 * fract(0.06711056 * p.x + 0.00583715 * p.y));
}

void main() {
    vec2 t = vUV.xy;
    vec4 col = textureGrad(uBlocks, vec3(fract(t), vUV.z), dFdx(t), dFdy(t));
#ifdef ALPHA_TEST
    if (col.a < uAlphaCut) discard;
#endif
    int tt = vTint & 3;
    if (tt != 0) {
        vec3 tint;
        if (tt == 3) {
            tint = uFixedTints[vTint >> 2];
        } else {
            ivec2 w = (uCamBlockXZ + ivec2(floor(vTintPos))) & 4095;
            vec2 th = texelFetch(uBiome, w, 0).rg;
            vec2 cuv = vec2(1.0 - th.r, 1.0 - th.g * th.r);
            tint = tt == 1 ? texture(uGrassMap, cuv).rgb : texture(uFoliageMap, cuv).rgb;
        }
        col.rgb *= tint;
    }
    vec3 albedo = col.rgb;
    vec4 layerInfo = texelFetch(uEmissive, ivec2(int(vUV.z + 0.5), 0), 0);
    vec3 n = NORMALS[vNormal];
    bool flat2 = vNormal >= 6;
    // Crossed plants (grass, flowers) are lit exactly like the ground they stand on: the sun angle of an up-facing
    // surface, and the shadow is read at the foot of the plant (the point straight below on the block's floor).
    // Before, every fragment looked up its own shadow without any normal, so the blades shadowed themselves and
    // flickered, and in the evening they stayed bright on dark ground.
    vec3 sn = flat2 ? vec3(0.0, 1.0, 0.0) : n;
    vec3 sp = vRel;
    if (flat2) sp.y = floor(vRel.y + uCamFrac.y + 0.0001) - uCamFrac.y;
    float ndl = dot(sn, uLightDir);

    // water surface seen from above: waves (normal), Fresnel, sun glints
    vec3 viewDir = normalize(vRel);
    bool waterTop = layerInfo.g > 0.5 && vNormal == 1 && viewDir.y < 0.0;
    vec3 wn = n;
    vec2 fw = fwidth(vRel.xz);     // outside the branch: derivatives need all pixels of the quad
    if (waterTop && uWater > 0.0) {
        vec2 wp = vec2(uCamBlockXZ & 1023) + vRel.xz + uCamFrac.xz;
        wn = normalize(mix(n, waterNormal(wp, length(vRel), max(fw.x, fw.y)), uWaterFade));
    }
    float sunVis = 1.0;

    // block light of this fragment: the stored light and the dynamic lights (level falls by one per block).
    // Like Minecraft light, a face gets the light of the air in front of it: the distance is measured from half a
    // block in front of the face, and the angle of the light does not matter. Before, the light faded with the angle,
    // so the top of a one block wall next to the player (the hand is only a little higher than its top) and the
    // wall right in front of the player (the hand is almost in it) stayed black.
    // Only a light more than about a block behind the face's plane is cut off: no light through a wall onto its
    // back side.
    vec3 blkRGB = vLight.yzw;
    for (int i = 0; i < uDynCount; i++) {
        vec3 lp = uDynPos[i].xyz;
        float lvl;
        if (flat2) lvl = uDynPos[i].w - length(lp - vRel);
        else {
            float h = dot(n, lp - vRel);            // height of the light above the plane of the face
            if (h < -1.0) continue;
            lvl = (uDynPos[i].w - length(lp - (vRel + n * 0.5))) * smoothstep(-1.0, -0.3, h);
        }
        if (lvl <= 0.0) continue;
        blkRGB = max(blkRGB, uDynCol[i] * min(lvl, 15.0));
    }

    if (uRT == 1) {
        // ---------------------------------------------------------- ray traced lighting
        vec3 p = vRel + uCamFrac;
        vec3 nn = flat2 ? normalize(uLightDir + vec3(0.0, 0.3, 0.0)) : n;
        vec3 ro = p + nn * 0.02;
        ivec3 hc, hp; vec3 tr; float hd;
        // direct sun / moon
        vec3 direct = vec3(0.0);
        if (ndl > 0.0 && uLightStrength > 0.0) {
            bool blocked = trace(ro, uLightDir, 140.0, hc, hp, tr, hd);
            float vis = blocked ? 0.0 : 1.0;
            ivec3 endCell = ivec3(floor(ro + uLightDir * 140.0)) + uCamBlock;
            if (!blocked && uCascades > 0 && !inWindow(endCell)) vis = shadowMap(sp, sn);
            if (uEntityCascades > 0) vis *= entityShadow(sp, sn);
            direct = uSunColor * uLightStrength * vis * ndl * tr;
            sunVis = vis * (tr.r + tr.g + tr.b) / 3.0;
        }
        // indirect: cosine rays, one bounce from the voxel scene
        vec3 indirect = vec3(0.0);
        int rays = max(uRTRays, 1);
        float noise = ign(gl_FragCoord.xy, uFrame);
        for (int i = 0; i < rays; i++) {
            vec2 u = fract(vec2(noise + float(i) * 0.618034, hash13(vec3(gl_FragCoord.xy, float(uFrame * 7 + i)))));
            vec3 rd = cosineDir(nn, u);
            if (trace(ro, rd, 24.0, hc, hp, tr, hd)) indirect += hitRadiance(hc, hp, rd) * tr;
            else {
                // escaped: sky if the vanilla sky light says the sky is reachable, else dark
                indirect += skyRadiance(rd) * tr * bright(vLight.x);
            }
        }
        indirect /= float(rays);
        vec3 lighting = direct * 0.78 + indirect * 0.45 + vec3(0.03) + blockLight(blkRGB) * 0.9;
        lighting *= mix(1.0, vShade, 0.35);
        col.rgb = albedo * lighting;
        // reflections on water
        if (layerInfo.g > 0.5 && vNormal == 1) {
            vec3 v = viewDir;
            vec3 rn = waterTop ? wn : n;
            vec3 rd = reflect(v, rn);
            if (rd.y < 0.02) rd = normalize(vec3(rd.x, 0.02, rd.z));   // waves never reflect below the horizon
            vec3 refl = trace(ro, rd, 96.0, hc, hp, tr, hd) ? hitRadiance(hc, hp, rd) * tr : skyRadiance(rd) * bright(vLight.x) + uFogColor * (1.0 - bright(vLight.x)) * 0.2;
            float f = 0.02 + 0.98 * pow(1.0 - max(dot(-v, rn), 0.0), 5.0);
            col.rgb = mix(col.rgb, refl, clamp(f * 1.2 + 0.15, 0.0, 0.9));
            col.a = mix(col.a, 1.0, f);
        }
    } else {
        // ---------------------------------------------------------- vanilla lightmap (+ shadow maps)
        vec3 skyMul = vec3(1.0);
        if ((uCascades > 0 || uEntityCascades > 0) && uLightStrength > 0.0) {
            float vis = ndl <= 0.0 ? 0.0 : (uCascades > 0 ? shadowMap(sp, sn) : 1.0) * smoothstep(0.0, 0.2, ndl);
            if (uEntityCascades > 0 && vis > 0.0) vis *= entityShadow(sp, sn);
            sunVis = vis;
            float amount = uLightStrength * 0.42;
            skyMul = mix(vec3(1.0 - amount * 0.95, 1.0 - amount * 0.85, 1.0 - amount * 0.6), vec3(1.0), vis);
        }
        col.rgb *= lightmap(vLight.x, blkRGB, skyMul) * vShade;
        // water shader without ray tracing: reflection of the sky (the post chain reflects the world instead when
        // screen space reflections are on)
        if (waterTop && uWater > 0.0) {
            vec3 rd = reflect(viewDir, wn);
            if (rd.y < 0.02) rd = normalize(vec3(rd.x, 0.02, rd.z));
            float f = 0.02 + 0.98 * pow(1.0 - max(dot(-viewDir, wn), 0.0), 5.0);
            if (uSSRWater < 1.0) {
                float skyLit = bright(vLight.x);
                vec3 refl = skyRadiance(rd) * skyLit + uFogColor * (1.0 - skyLit) * 0.15;
                col.rgb = mix(col.rgb, refl, clamp(f * 1.1 + 0.08, 0.0, 0.9) * (1.0 - uSSRWater) * uWaterFade);
            }
            col.a = mix(col.a, 1.0, f * uWaterFade);
        }
    }
    // sun / moon glints on the waves (only where the sun reaches the water)
    if (waterTop && uWater > 0.0 && uLightStrength > 0.0) {
        vec3 rd = reflect(viewDir, wn);
        float rl = max(dot(rd, uLightDir), 0.0);
        float spec = pow(rl, 400.0) * 4.0 + pow(rl, 60.0) * 0.12;
        vec3 glint = uSunColor * uLightStrength * spec * sunVis * bright(vLight.x) * min(uWater, 1.0) * uWaterFade;
        col.rgb += glint;
        col.a = max(col.a, clamp(dot(glint, vec3(0.33)), 0.0, 1.0));
    }
    // fire (flag 100 in the emissive table): full brightness and a little brighter than its texture, no shading
    if (layerInfo.g > 0.3 && layerInfo.g < 0.45) col.rgb = albedo * 1.2;
    if (uEmissiveBoost > 0.0) col.rgb += albedo * layerInfo.r * uEmissiveBoost;
    float d = length(vRel);
    float f = clamp((d - uFog.x) / max(uFog.y - uFog.x, 0.001), 0.0, 1.0);
    col.rgb = mix(col.rgb, uFogColor, f);
    fragColor = col;

    float kind = 0.0;
    vec3 mn = n;
    if (layerInfo.g > 0.5 && vNormal == 1) {
        if (waterTop && uSSRWater > 0.0) { kind = 64.0; mn = wn; }
    } else if (uSSRBlocks > 0.0 && layerInfo.a > 0.0 && !flat2) kind = clamp(floor(layerInfo.a * 63.0 + 0.5), 1.0, 63.0);
    // faded out in thick fog: nothing to reflect there
    if (f > 0.98) kind = 0.0;
    fragMaterial = vec4(octEncode(mn), kind > 0.0 ? kind + clamp(bright(vLight.x), 0.0, 1.0) * 0.9 : 0.0, length(vRel));
}
