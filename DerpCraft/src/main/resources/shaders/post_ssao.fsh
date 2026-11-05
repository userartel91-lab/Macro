#version 330 core
// Screen space ambient occlusion (half resolution) for everything that writes depth: blocks, chests, signs, beds,
// dropped items, 3D models, players, NPCs, vehicles, mobs. Positions come from the depth buffer (camera-relative),
// normals from the neighbouring depths. Output: r = ambient visibility (1 = open), g = linear depth (for the
// depth aware blur in post_ssao_apply.fsh).
in vec2 vUV;
uniform sampler2D uDepth;
uniform mat4 uProjView;
uniform mat4 uInvProjView;
uniform float uNear, uFar;
uniform vec2 uTexel;           // one full resolution pixel in uv
uniform float uRadius;         // blocks (world) / view units (the hand and the gun in the first person)
uniform float uHand;           // 1 = the first person pass: full radius right at the lens, biases for small parts
out vec4 fragColor;

float linearDepth(float d) { return 2.0 * uNear * uFar / (uFar + uNear - (d * 2.0 - 1.0) * (uFar - uNear)); }

vec3 position(vec2 uv, float d) {
    vec4 w = uInvProjView * vec4(uv * 2.0 - 1.0, d * 2.0 - 1.0, 1.0);
    return w.xyz / w.w;
}

// position of a full resolution pixel (exact pixel centres: half resolution uv lie between pixels)
vec3 positionAt(ivec2 q) {
    q = clamp(q, ivec2(0), textureSize(uDepth, 0) - 1);
    return position((vec2(q) + 0.5) * uTexel, texelFetch(uDepth, q, 0).r);
}

void main() {
    ivec2 px = ivec2(gl_FragCoord.xy) * 2;
    float d = texelFetch(uDepth, px, 0).r;
    if (d >= 1.0) { fragColor = vec4(1.0, 60000.0, 0.0, 1.0); return; }   // sky: far away (16-bit float limit 65504)
    float lin = linearDepth(d);
    vec3 p = position((vec2(px) + 0.5) * uTexel, d);
    // normal: on each axis the neighbour closer in depth (no smearing across silhouettes)
    vec3 r = positionAt(px + ivec2(1, 0)), l = positionAt(px - ivec2(1, 0));
    vec3 u = positionAt(px + ivec2(0, 1)), b = positionAt(px - ivec2(0, 1));
    vec3 dx = dot(r - p, r - p) < dot(p - l, p - l) ? r - p : p - l;
    vec3 dy = dot(u - p, u - p) < dot(p - b, p - b) ? u - p : p - b;
    vec3 n = normalize(cross(dx, dy));
    if (dot(n, p) > 0.0) n = -n;
    float dist = length(p);
    float graze = 1.0 / max(abs(dot(n, p)) / dist, 0.15);   // surfaces seen edge-on need more bias
    // smaller radius right in front of the lens (the hand area), fades out in the distance
    float radius = uHand > 0.5 ? uRadius : uRadius * clamp(dist * 0.5, 0.25, 1.0);
    vec3 t = normalize(cross(n, abs(n.y) < 0.99 ? vec3(0.0, 1.0, 0.0) : vec3(1.0, 0.0, 0.0)));
    vec3 bt = cross(n, t);
    // per pixel rotation of the sample spiral (blurred away afterwards)
    float a0 = 6.2831853 * fract(52.9829189 * fract(0.06711056 * gl_FragCoord.x + 0.00583715 * gl_FragCoord.y));
    const int N = 12;
    float occ = 0.0;
    vec3 origin = p + n * (0.03 * uRadius + dist * 0.004);
    for (int i = 0; i < N; i++) {
        float k = (float(i) + 0.5) / float(N);
        float phi = a0 + float(i) * 2.3999632;
        float cosT = sqrt(1.0 - k), sinT = sqrt(k);
        vec3 dir = (t * cos(phi) + bt * sin(phi)) * sinT + n * cosT;
        float len = radius * mix(0.1, 1.0, k * k);
        vec4 c = uProjView * vec4(origin + dir * len, 1.0);
        if (c.w <= uNear) continue;
        vec2 suv = c.xy / c.w * 0.5 + 0.5;
        if (suv.x < 0.0 || suv.y < 0.0 || suv.x > 1.0 || suv.y > 1.0) continue;
        float sd = textureLod(uDepth, suv, 0.0).r;
        if (sd >= 1.0) continue;
        float diff = c.w - linearDepth(sd);   // > 0: the sample point is inside / behind something
        // bias grows with distance: the 24-bit depth gets coarse, flat ground seen at a low angle must not shade itself
        if (diff > 0.02 * uRadius + c.w * 0.003 * graze) occ += 1.0 - smoothstep(radius * 0.6, radius * 2.0, diff);
    }
    float ao = 1.0 - occ / float(N);
    ao = mix(ao, 1.0, smoothstep(64.0, 128.0, dist));
    fragColor = vec4(ao, lin, 0.0, 1.0);
}
