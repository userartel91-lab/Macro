#version 330 core
in vec2 vUV;
uniform sampler2D uSrc;
out vec4 fragColor;
void main() { fragColor = texture(uSrc, vUV); }
