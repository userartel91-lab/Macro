#version 330 core
// 9-tap Gaussian blur along uStep (one direction per pass), output multiplied by uGain (god rays: the second pass
// is added to the scene with additive blending).
in vec2 vUV;
uniform sampler2D uSrc;
uniform vec2 uStep;            // texel offset of one tap
uniform float uGain;
out vec4 fragColor;
void main() {
    const float W[5] = float[](0.227027, 0.1945946, 0.1216216, 0.054054, 0.016216);
    vec3 c = texture(uSrc, vUV).rgb * W[0];
    for (int i = 1; i < 5; i++) {
        c += texture(uSrc, vUV + uStep * float(i)).rgb * W[i];
        c += texture(uSrc, vUV - uStep * float(i)).rgb * W[i];
    }
    fragColor = vec4(c * uGain, 1.0);
}
