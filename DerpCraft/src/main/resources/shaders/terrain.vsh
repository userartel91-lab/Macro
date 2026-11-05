#version 330 core
layout(location = 0) in uvec3 aData;
layout(location = 1) in vec3 aOrigin;   // section origin relative to floor(camera)

uniform mat4 uProjView;                 // projection * rotation (camera at origin)
uniform vec3 uCamFrac;                  // camera - floor(camera)
uniform float uAO;                      // ambient occlusion strength (1 = Minecraft, 0 = none, 2 = twice as dark)

#include "wave.glsl"

out vec3 vUV;
out vec4 vLight;   // sky, block red, green, blue (0..15)
out float vShade;
flat out int vTint;
out vec3 vRel;
out vec2 vTintPos;
flat out int vNormal;

invariant gl_Position;

const float SHADE[8] = float[](0.5, 1.0, 0.8, 0.8, 0.6, 0.6, 1.0, 1.0);   // [6] = crossed plants: lit like the top of the ground
const float AO[4] = float[](1.0, 0.8, 0.6, 0.4);
const vec3 NORMALS[8] = vec3[](vec3(0,-1,0), vec3(0,1,0), vec3(0,0,-1), vec3(0,0,1), vec3(-1,0,0), vec3(1,0,0), vec3(0), vec3(0));

void main() {
    uint a = aData.x, b = aData.y, c = aData.z;
    vec3 local = vec3(float(a & 511u), float((a >> 9) & 511u), float((a >> 18) & 511u)) / 16.0;
    uint n = (a >> 27) & 7u;
    vec3 sway = waveOffset(aOrigin, local, float(b >> 18), float((b >> 9) & 511u) / 16.0);
    vec3 rel = aOrigin + local + sway - uCamFrac;
    vec3 pos = rel;
    if ((a & 0x40000000u) != 0u && n < 6u) {
        // decal (soot): lifted off the face along its normal so it never z-fights with the block face under it.
        // The lift grows with distance (24-bit depth gets coarser); it stays well under a pixel.
        pos += NORMALS[n] * (0.002 + 4e-6 * dot(rel, rel));
    }
    gl_Position = uProjView * vec4(pos, 1.0);
    vUV = vec3(float(b & 511u) / 16.0, float((b >> 9) & 511u) / 16.0, float(b >> 18));
    vLight = vec4(float(c & 63u), float((c >> 6) & 63u), float((c >> 12) & 63u), float((c >> 18) & 63u)) * 0.25;
    vShade = SHADE[n] * max(0.05, 1.0 - (1.0 - AO[(c >> 24) & 3u]) * uAO);
    vTint = int((c >> 26) & 63u);
    vRel = rel;
    vNormal = int(n);
    vec3 inside = local - NORMALS[n] * 0.5;
    if (n >= 6u) inside = local;
    vTintPos = aOrigin.xz + inside.xz;
}
