#version 330 core
layout(location = 0) in uvec3 aData;
layout(location = 1) in vec3 aOrigin;
uniform mat4 uProjView;     // cascade matrix for camera-relative positions
uniform vec3 uCamFrac;
#include "wave.glsl"
out vec3 vUV;
void main() {
    uint a = aData.x, b = aData.y;
    vec3 local = vec3(float(a & 511u), float((a >> 9) & 511u), float((a >> 18) & 511u)) / 16.0;
    // swaying leaves cast swaying shadows (same offset as terrain.vsh)
    local += waveOffset(aOrigin, local, float(b >> 18), float((b >> 9) & 511u) / 16.0);
    gl_Position = uProjView * vec4(aOrigin + local - uCamFrac, 1.0);
    vUV = vec3(float(b & 511u) / 16.0, float((b >> 9) & 511u) / 16.0, float(b >> 18));
}
