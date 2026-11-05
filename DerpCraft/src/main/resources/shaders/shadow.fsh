#version 330 core
in vec3 vUV;
uniform sampler2DArray uBlocks;
void main() {
#ifdef ALPHA_TEST
    vec2 t = vUV.xy;
    if (textureLod(uBlocks, vec3(fract(t), vUV.z), 0.0).a < 0.5) discard;
#endif
}
