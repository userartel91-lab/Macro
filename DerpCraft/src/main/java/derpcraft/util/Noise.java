package derpcraft.util;

import java.util.Random;

/** Classic improved Perlin noise (2D/3D) with fractal helpers. Thread safe after construction. */
public final class Noise {
    private final int[] perm = new int[512];
    private final double ox, oy, oz;

    public Noise(long seed) {
        Random r = new Random(seed);
        ox = r.nextDouble() * 256; oy = r.nextDouble() * 256; oz = r.nextDouble() * 256;
        int[] p = new int[256];
        for (int i = 0; i < 256; i++) p[i] = i;
        for (int i = 255; i > 0; i--) { int j = r.nextInt(i + 1); int t = p[i]; p[i] = p[j]; p[j] = t; }
        for (int i = 0; i < 512; i++) perm[i] = p[i & 255];
    }

    private static double fade(double t) { return t * t * t * (t * (t * 6 - 15) + 10); }
    private static double lerp(double t, double a, double b) { return a + t * (b - a); }

    private static double grad(int h, double x, double y, double z) {
        h &= 15;
        double u = h < 8 ? x : y, v = h < 4 ? y : (h == 12 || h == 14 ? x : z);
        return ((h & 1) == 0 ? u : -u) + ((h & 2) == 0 ? v : -v);
    }

    /** 3D noise in roughly [-1,1]. */
    public double noise(double x, double y, double z) {
        x += ox; y += oy; z += oz;
        int X = (int) Math.floor(x) & 255, Y = (int) Math.floor(y) & 255, Z = (int) Math.floor(z) & 255;
        x -= Math.floor(x); y -= Math.floor(y); z -= Math.floor(z);
        double u = fade(x), v = fade(y), w = fade(z);
        int A = perm[X] + Y, AA = perm[A] + Z, AB = perm[A + 1] + Z, B = perm[X + 1] + Y, BA = perm[B] + Z, BB = perm[B + 1] + Z;
        return lerp(w, lerp(v, lerp(u, grad(perm[AA], x, y, z), grad(perm[BA], x - 1, y, z)),
                        lerp(u, grad(perm[AB], x, y - 1, z), grad(perm[BB], x - 1, y - 1, z))),
                lerp(v, lerp(u, grad(perm[AA + 1], x, y, z - 1), grad(perm[BA + 1], x - 1, y, z - 1)),
                        lerp(u, grad(perm[AB + 1], x, y - 1, z - 1), grad(perm[BB + 1], x - 1, y - 1, z - 1))));
    }

    public double noise(double x, double z) { return noise(x, 0.5, z); }

    /** Fractal brownian motion, result roughly in [-1,1]. */
    public double fbm(double x, double z, int octaves) {
        double sum = 0, amp = 1, norm = 0, f = 1;
        for (int i = 0; i < octaves; i++) {
            sum += noise(x * f, z * f) * amp;
            norm += amp; amp *= 0.5; f *= 2.03;
        }
        return sum / norm;
    }

    /** Ridged multifractal in [0,1]. */
    public double ridged(double x, double z, int octaves) {
        double sum = 0, amp = 1, norm = 0, f = 1;
        for (int i = 0; i < octaves; i++) {
            double n = 1 - Math.abs(noise(x * f, z * f));
            sum += n * n * amp;
            norm += amp; amp *= 0.5; f *= 2.1;
        }
        return sum / norm;
    }
}
