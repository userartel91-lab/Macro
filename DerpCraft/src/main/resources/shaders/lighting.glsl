// Shared sun / moon lighting: cascaded shadow maps, the entity shadow map and voxel ray traced visibility.
// Included by terrain.fsh and model.fsh.
// sun / moon
uniform vec3 uLightDir;            // towards the sun or moon
uniform float uLightStrength;      // 0..1 (fades at the horizon, weaker for the moon)
uniform vec3 uSunColor;
uniform vec3 uSkyColor;
// shadow maps
uniform int uCascades;             // 0 = shadows off
uniform mat4 uShadowMats[4];
uniform sampler2DArrayShadow uShadowMap;
uniform float uShadowTexel;
uniform int uShadowTaps;
uniform float uShadowFade;         // 1 = shadows as they are, 0 = none (shadows fading in / out in a look transition)
// voxel ray tracing
uniform int uRT;
uniform usampler3D uVoxels;
uniform sampler2D uBlockTable;     // row 0: albedo + flags, row 1: emission rgb + level
uniform ivec3 uCamBlock;
uniform vec3 uCamFrac;
uniform ivec2 uVoxelMin;

uniform int uEntityCascades;          // cascades of the entity shadow map (0 = none)
uniform sampler2DArrayShadow uEntityShadowMap;
uniform float uEntityShadowTexel;

float bright(float l) { float f = 1.0 - l / 15.0; return (1.0 - f) / (f * 3.0 + 1.0); }

// ------------------------------------------------------------------ shadow maps
const vec2 POISSON[12] = vec2[](vec2(-0.326, -0.406), vec2(-0.840, -0.074), vec2(-0.696, 0.457), vec2(-0.203, 0.621),
    vec2(0.962, -0.195), vec2(0.473, -0.480), vec2(0.519, 0.767), vec2(0.185, -0.893), vec2(0.507, 0.064),
    vec2(0.896, 0.412), vec2(-0.322, -0.933), vec2(-0.792, -0.598));

// Sun visibility from cascade c (0..1), or -1.0 when the point lies outside it. 'edge' = distance to the cascade border (0..0.5).
float cascadeVis(int c, vec3 p, vec3 n, out float edge) {
    vec3 q = p + n * (0.04 + 0.03 * float(c * c + c));
    vec4 s = uShadowMats[c] * vec4(q, 1.0);
    vec3 uv = s.xyz * 0.5 + 0.5;
    edge = min(min(uv.x, 1.0 - uv.x), min(uv.y, 1.0 - uv.y));
    if (edge < 0.01 || uv.z > 1.0) return -1.0;
    float r = uShadowTexel * (c == 0 ? 1.5 : 1.0);
    if (uShadowTaps <= 1) return texture(uShadowMap, vec4(uv.xy, float(c), uv.z));
    float sum = 0.0;
    float ang = fract(sin(dot(gl_FragCoord.xy, vec2(12.9898, 78.233))) * 43758.5453) * 6.2831;
    mat2 rot = mat2(cos(ang), sin(ang), -sin(ang), cos(ang));
    for (int i = 0; i < uShadowTaps; i++) sum += texture(uShadowMap, vec4(uv.xy + rot * POISSON[i] * r, float(c), uv.z));
    return sum / float(uShadowTaps);
}

float shadowMap(vec3 p, vec3 n) {
    for (int c = 0; c < uCascades; c++) {
        float edge;
        float v = cascadeVis(c, p, n, edge);
        if (v < 0.0) continue;
        // near the border fade into the next (coarser) cascade: the seam between cascades turns with the sun and used to flicker
        if (c + 1 < uCascades && edge < 0.08) {
            float edge2;
            float v2 = cascadeVis(c + 1, p, n, edge2);
            if (v2 >= 0.0) v = mix(v2, v, smoothstep(0.01, 0.08, edge));
        }
        return mix(1.0, v, uShadowFade);
    }
    return 1.0;
}

// ------------------------------------------------------------------ voxel ray tracing
uvec2 voxel(ivec3 w) {
    if (w.y < 0 || w.y > 255 || w.x < uVoxelMin.x || w.z < uVoxelMin.y || w.x >= uVoxelMin.x + 256 || w.z >= uVoxelMin.y + 256) return uvec2(0u, 15u);
    return texelFetch(uVoxels, ivec3(w.x & 255, w.y, w.z & 255), 0).rg;
}

bool inWindow(ivec3 w) { return w.x >= uVoxelMin.x && w.z >= uVoxelMin.y && w.x < uVoxelMin.x + 256 && w.z < uVoxelMin.y + 256; }

float hash13(vec3 p) { p = fract(p * 0.1031); p += dot(p, p.zyx + 31.32); return fract((p.x + p.y) * p.z); }

// Amanatides-Woo DDA through the voxel grid. ro is relative to the camera block (uCamBlock).
// Returns true on a hit; 'cell' = hit cell (world), 'prev' = last empty cell, 'face' = entered face normal.
bool trace(vec3 ro, vec3 rd, float maxDist, out ivec3 cell, out ivec3 prev, out vec3 transmit, out float dist) {
    ivec3 c = ivec3(floor(ro));
    vec3 inv = 1.0 / max(abs(rd), vec3(1e-5));
    ivec3 st = ivec3(sign(rd));
    vec3 side = (vec3(st) * (vec3(c) - ro) + vec3(st) * 0.5 + 0.5) * inv;
    transmit = vec3(1.0);
    prev = c + uCamBlock;
    dist = 0.0;
    for (int i = 0; i < 160; i++) {
        if (side.x < side.y && side.x < side.z) { dist = side.x; side.x += inv.x; c.x += st.x; }
        else if (side.y < side.z) { dist = side.y; side.y += inv.y; c.y += st.y; }
        else { dist = side.z; side.z += inv.z; c.z += st.z; }
        if (dist > maxDist) return false;
        ivec3 w = c + uCamBlock;
        if (w.y < 0 || w.y > 255 || !inWindow(w)) return false;
        uvec2 v = voxel(w);
        if (v.x != 0u) {
            vec4 t = texelFetch(uBlockTable, ivec2(int(v.x), 0), 0);
            int f = int(t.a * 255.0 + 0.5);
            if ((f & 1) != 0) { cell = w; return true; }
            if ((f & 2) != 0 && hash13(vec3(w)) < 0.55) { cell = w; return true; }
            if ((f & 4) != 0) transmit *= mix(vec3(1.0), t.rgb, 0.85);
            if ((f & 8) != 0) transmit *= vec3(0.85, 0.92, 1.0);
        }
        prev = w;
    }
    return false;
}


// Shadows of chests, signs, beds, dropped items and the player (same cascades as the terrain map).
float entityShadow(vec3 p, vec3 n) {
    for (int c = 0; c < uEntityCascades; c++) {
        vec4 s = uShadowMats[c] * vec4(p + n * (0.02 + 0.02 * float(c)), 1.0);
        vec3 uv = s.xyz * 0.5 + 0.5;
        if (uv.x < 0.01 || uv.y < 0.01 || uv.x > 0.99 || uv.y > 0.99 || uv.z > 1.0) continue;
        float r = uEntityShadowTexel;
        float sum = 0.0;
        for (int i = 0; i < 4; i++) sum += texture(uEntityShadowMap, vec4(uv.xy + POISSON[i * 3] * r, float(c), uv.z));
        return mix(1.0, sum * 0.25, uShadowFade);
    }
    return 1.0;
}

// Visibility of the sun (or moon) from a camera-relative point with normal n (0 normal = no offset).
float sunVisibility(vec3 rel, vec3 n) {
    float vis = 1.0;
    if (uRT == 1) {
        vec3 dir = uLightDir;
        vec3 ro = rel + uCamFrac + (dot(n, n) > 0.0 ? n : dir) * 0.03;
        ivec3 hc, hp; vec3 tr; float hd;
        bool blocked = trace(ro, dir, 140.0, hc, hp, tr, hd);
        vis = blocked ? 0.0 : (tr.r + tr.g + tr.b) / 3.0;
        ivec3 endCell = ivec3(floor(ro + dir * 140.0)) + uCamBlock;
        if (!blocked && uCascades > 0 && !inWindow(endCell)) vis = shadowMap(rel, n);
    } else if (uCascades > 0) vis = shadowMap(rel, n);
    if (uEntityCascades > 0) vis *= entityShadow(rel, n);
    return vis;
}
