#version 330 core
layout(location = 0) in vec3 aPos;
layout(location = 1) in vec2 aUV;
layout(location = 2) in vec4 aColor;
uniform mat4 uMVP;
uniform mat4 uModel;
out vec2 vUV;
out vec4 vColor;
out vec3 vPos;
void main() {
    vec4 wp = uModel * vec4(aPos, 1.0);
    gl_Position = uMVP * wp;
    vUV = aUV;
    vColor = aColor;
    vPos = wp.xyz;
}
