#version 330 core
in vec2 vNdc;
uniform mat4 uInvProjView;
uniform vec3 uSkyColor;
uniform vec3 uFogColor;
uniform vec3 uVoidColor;
uniform vec4 uSunrise;     // rgb + strength
uniform float uSunSide;    // +1 sun in the east, -1 west
uniform float uFar;        // fog distance for the sky planes (blocks)
out vec4 fragColor;

void main() {
    vec4 w = uInvProjView * vec4(vNdc, 1.0, 1.0);
    vec3 dir = normalize(w.xyz / w.w);
    vec3 col;
    if (dir.y >= 0.0) {
        // sky plane 16 blocks above the camera, fogged like Minecraft
        float dist = 16.0 / max(dir.y, 1e-4);
        float t = clamp(dist / uFar, 0.0, 1.0);
        col = mix(uSkyColor, uFogColor, t);
    } else {
        float dist = 16.0 / max(-dir.y, 1e-4);
        float t = clamp(dist / uFar, 0.0, 1.0);
        col = mix(uVoidColor, uFogColor, t);
    }
    if (uSunrise.a > 0.0) {
        float side = max(0.0, dir.x * uSunSide);
        float horiz = exp(-abs(dir.y + 0.02) * 5.0);
        float g = side * side * horiz * uSunrise.a;
        col = mix(col, uSunrise.rgb, clamp(g, 0.0, 1.0));
    }
    fragColor = vec4(col, 1.0);
}
