#version 330 core
// Lens ghosts and halo: scaled, mirrored copies of the brightest lights along the line through the image centre,
// with slight chromatic fringes, as reflections between lens elements produce. Added onto the streak texture.
in vec2 vUV;
uniform sampler2D uSrc;         // thresholded bright image (low resolution)
uniform float uAspect;
uniform float uSqueeze;
uniform float uStrength;
uniform float uThreshold;
out vec4 fragColor;

vec3 bright(vec2 uv) {
    if (uv.x < 0.0 || uv.y < 0.0 || uv.x > 1.0 || uv.y > 1.0) return vec3(0.0);
    return max(texture(uSrc, uv).rgb - uThreshold, 0.0);
}

// A ghost: the bright image scaled by 1/k around the centre (negative k = mirrored), each channel slightly
// differently so the edges fringe.
vec3 ghost(vec2 uv, float k, vec3 tint) {
    vec2 d = uv - 0.5;
    vec3 c = vec3(bright(0.5 + d * k * 1.02).r, bright(0.5 + d * k).g, bright(0.5 + d * k * 0.98).b);
    // fade ghosts towards the frame edges
    float edge = 1.0 - smoothstep(0.2, 0.7, length(d * vec2(uAspect, 1.0)) / uAspect);
    return c * tint * edge;
}

void main() {
    vec3 r = vec3(0.0);
    r += ghost(vUV, -1.35, vec3(0.75, 0.85, 1.0)) * 0.9;
    r += ghost(vUV, -2.4, vec3(1.0, 0.85, 0.6)) * 0.7;
    r += ghost(vUV, -4.2, vec3(0.6, 1.0, 0.75)) * 0.6;
    r += ghost(vUV, 2.8, vec3(0.85, 0.7, 1.0)) * 0.45;
    r += ghost(vUV, -0.7, vec3(0.6, 0.75, 1.0)) * 0.35;
    // faint halo ring (stretched sideways by an anamorphic lens)
    vec2 d = (vUV - 0.5) * vec2(uAspect / uSqueeze, 1.0);
    float rl = length(d);
    vec2 hv = d / max(rl, 1e-4) * 0.38 * vec2(uSqueeze / uAspect, 1.0);
    float ring = smoothstep(0.30, 0.38, rl) * (1.0 - smoothstep(0.38, 0.46, rl));
    r += bright(vUV - hv) * ring * vec3(0.7, 0.8, 1.0) * 0.1;
    fragColor = vec4(r * uStrength, 1.0);
}
