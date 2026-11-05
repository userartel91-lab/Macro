#version 330 core
// Depth of field, step 3: blends the blurred half-res image over the sharp scene where the blur is visible.
in vec2 vUV;
uniform sampler2D uSrc;
out vec4 fragColor;
void main() {
    vec4 d = texture(uSrc, vUV);
    fragColor = vec4(pow(max(d.rgb, 0.0), vec3(1.0 / 2.2)), smoothstep(0.35, 1.25, d.a));
}
