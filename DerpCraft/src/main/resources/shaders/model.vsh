#version 330 core
layout(location = 0) in vec3 aPos;
layout(location = 1) in vec2 aUV;
layout(location = 2) in vec4 aColor;
layout(location = 3) in float aLayer;
layout(location = 4) in float aSky;
uniform mat4 uMVP;
// static meshes (vehicles): positions are in the mesh's own space, uModel moves them camera-relative; the light colour and
// its sky share come as uniforms instead of per vertex. 0 (default) = the batched path, vertices already camera-relative.
uniform int uUseModel;
uniform mat4 uModel;
uniform vec4 uTint;
uniform float uSkyU;
out vec2 vUV;
out vec4 vColor;
out float vLayer;
out vec3 vPos;
out float vSky;
void main() {
    vec3 p = aPos;
    vColor = aColor;
    vSky = aSky;
    if (uUseModel == 1) {
        p = (uModel * vec4(aPos, 1.0)).xyz;
        vColor = aColor * uTint;
        vSky = uSkyU;
    }
    gl_Position = uMVP * vec4(p, 1.0);
    vUV = aUV;
    vLayer = aLayer;
    vPos = p;
}
