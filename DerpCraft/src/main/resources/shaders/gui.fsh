#version 330 core
in vec2 vUV;
in vec4 vColor;
in float vLayer;
uniform sampler2D uTex;
uniform sampler2DArray uArray;
out vec4 fragColor;
void main() {
    vec4 c = vColor;
    if (vLayer >= 0.0) c *= textureLod(uArray, vec3(vUV, floor(vLayer + 0.5)), 0.0);
    else if (vLayer > -1.5) c *= texture(uTex, vUV);
    if (c.a < 0.004) discard;
    fragColor = c;
}
