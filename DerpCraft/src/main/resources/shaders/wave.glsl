// Wind: leaves, grass, flowers, crops, vines and lily pads sway (terrain.vsh and shadow.vsh).
// The wave class of a texture layer is the blue channel of the material table (Assets.WAVE_*).
// Every term repeats exactly every 1024 blocks (spatial frequencies are whole multiples of 2*pi/1024), so positions are
// wrapped to 0..1023 without a seam (no float precision loss far from spawn), and blocks that share a corner move it the
// same way: leaf blocks stay closed while they move.
uniform float uWaveTime;    // seconds of game time: stops with the game and follows the recording clock
uniform float uWave;        // wind strength, 0 = off
uniform ivec3 uWaveCam;     // floor(camera) & 1023
uniform sampler2D uEmissive;

const float WK = 6.28318531 / 1024.0;

float wv(vec3 p, vec3 n, float speed) { return sin(uWaveTime * speed + dot(p, n) * WK); }

// origin: section origin relative to floor(camera); local: vertex inside the section (blocks);
// layer: texture layer; v: texture v of the vertex in tiles (0 = top of the texture, 1 = bottom)
vec3 waveOffset(vec3 origin, vec3 local, float layer, float v) {
    if (uWave <= 0.0) return vec3(0.0);
    int cls = int(texelFetch(uEmissive, ivec2(int(layer), 0), 0).b * 255.0 + 0.5);
    if (cls == 0) return vec3(0.0);
    vec3 p = vec3((ivec3(floor(origin + 0.5)) + uWaveCam) & 1023) + local;
    // gusts: a slow swell rolling over the landscape
    float gust = 0.65 + 0.35 * wv(p, vec3(7.0, 0.0, 4.0), 0.45) * wv(p, vec3(-3.0, 0.0, 5.0), 0.23);
    float s = uWave * gust;
    if (cls == 1 || cls == 3) {
        // leaves and vines: every corner wobbles a little
        vec3 o = vec3(wv(p, vec3(61.0, 23.0, 37.0), 1.9) * 0.6 + wv(p, vec3(97.0, 41.0, -53.0), 3.1) * 0.4,
                      wv(p, vec3(67.0, 29.0, 47.0), 2.3) * 0.35,
                      wv(p, vec3(43.0, -31.0, 71.0), 1.6) * 0.6 + wv(p, vec3(-59.0, 17.0, 89.0), 2.7) * 0.4);
        return o * (cls == 1 ? 0.065 : 0.04) * s;
    }
    if (cls == 5) {
        // lily pads drift and bob on the water
        return vec3(wv(p, vec3(31.0, 0.0, 17.0), 0.9) * 0.03, wv(p, vec3(13.0, 0.0, 29.0), 1.3) * 0.008,
                    wv(p, vec3(-19.0, 0.0, 37.0), 0.8) * 0.03) * s;
    }
    // plants and crops: bend from the foot, lean with the wind and flutter. The upper half of a tall plant (class 4)
    // starts where the lower half ends, so the stalk stays in one piece.
    float h = clamp(1.0 - v, 0.0, 1.0);
    if (cls == 4) h += 1.0;
    float push = 0.55 + wv(p, vec3(35.0, 0.0, 29.0), 1.5) * 0.3 + wv(p, vec3(77.0, 13.0, -61.0), 2.6) * 0.15;
    float side = wv(p, vec3(51.0, 0.0, 83.0), 1.8) * 0.25 + wv(p, vec3(-91.0, 0.0, 47.0), 3.4) * 0.1;
    vec2 xz = (vec2(0.8, 0.6) * push + vec2(-0.6, 0.8) * side) * 0.12 * h * s;
    return vec3(xz.x, -dot(xz, xz) * 0.5, xz.y);
}
