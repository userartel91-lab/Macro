#version 330 core
// Screen recorder: packs the scaled frame into I420 (YUV 4:2:0 planar, BT.709, TV range) on the GPU.
// Every texel of this target holds 4 consecutive bytes of the I420 stream (the Y plane, then U, then V), so the frame
// is read back with glReadPixels at 1.5 bytes a pixel instead of 4 and ffmpeg needs no colour conversion at all
// (before, its single-threaded RGB -> YUV scaler was the bottleneck at 120 fps).
uniform sampler2D uSrc;   // the video frame, w x h, row 0 = top of the picture
uniform ivec2 uSize;      // w, h (both even)
uniform int uOutWidth;    // texels per row of this target
out vec4 fragColor;

const vec3 K = vec3(0.2126, 0.7152, 0.0722);

float byteAt(int k) {
    int w = uSize.x, h = uSize.y;
    int ys = w * h;
    if (k < ys) {
        vec3 c = texelFetch(uSrc, ivec2(k % w, k / w), 0).rgb;
        return clamp(floor(16.0 + 219.0 * dot(c, K) + 0.5), 16.0, 235.0);
    }
    k -= ys;
    int cw = w / 2;
    int cs = cw * (h / 2);
    bool isV = k >= cs;
    if (isV) k -= cs;
    if (k >= cs) return 0.0;                      // padding after the V plane
    ivec2 q = ivec2(k % cw, k / cw) * 2;
    vec3 c = (texelFetch(uSrc, q, 0).rgb + texelFetch(uSrc, q + ivec2(1, 0), 0).rgb
            + texelFetch(uSrc, q + ivec2(0, 1), 0).rgb + texelFetch(uSrc, q + ivec2(1, 1), 0).rgb) * 0.25;
    float y = dot(c, K);
    float d = isV ? (c.r - y) / 1.5748 : (c.b - y) / 1.8556;
    return clamp(floor(128.0 + 224.0 * d + 0.5), 16.0, 240.0);
}

void main() {
    ivec2 p = ivec2(gl_FragCoord.xy);
    int base = (p.y * uOutWidth + p.x) * 4;
    fragColor = vec4(byteAt(base), byteAt(base + 1), byteAt(base + 2), byteAt(base + 3)) / 255.0;
}
