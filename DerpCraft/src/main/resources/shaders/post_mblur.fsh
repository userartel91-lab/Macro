#version 330 core
// Camera motion blur: each pixel is smeared along its screen-space motion since the last frame (from depth).
in vec2 vUV;
uniform sampler2D uScene;
uniform sampler2D uDepth;
uniform mat4 uInvProjView;
uniform mat4 uPrevProjView;
uniform vec3 uCamDelta;
uniform float uShutter;         // 0..1 (1 = 360 degree shutter)
uniform float uMaxLen;          // max blur length in uv
out vec4 fragColor;

void main() {
    float d = texture(uDepth, vUV).r;
    vec4 w = uInvProjView * vec4(vUV * 2.0 - 1.0, d * 2.0 - 1.0, 1.0);
    vec3 rel = w.xyz / w.w;
    if (d >= 1.0) rel = normalize(rel) * 1000.0;   // sky: rotation only
    vec4 pc = uPrevProjView * vec4(rel + (d >= 1.0 ? vec3(0.0) : uCamDelta), 1.0);
    vec3 c = texture(uScene, vUV).rgb;
    if (pc.w <= 0.0) { fragColor = vec4(c, 1.0); return; }
    vec2 puv = pc.xy / pc.w * 0.5 + 0.5;
    vec2 v = (vUV - puv) * uShutter;
    float l = length(v);
    if (l > uMaxLen) v *= uMaxLen / l;
    if (l < 0.0005) { fragColor = vec4(c, 1.0); return; }
    vec3 sum = c;
    const int N = 12;
    for (int i = 1; i < N; i++) {
        float t = float(i) / float(N - 1) - 0.5;
        sum += texture(uScene, vUV + v * t).rgb;
    }
    fragColor = vec4(sum / float(N), 1.0);
}
