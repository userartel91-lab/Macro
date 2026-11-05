package derpcraft.util;

/** View frustum extracted from a combined projection*view matrix (camera-relative space). */
public final class Frustum {
    private final float[][] p = new float[6][4];

    public void set(Mat4 pv) {
        float[] m = pv.m;
        for (int i = 0; i < 3; i++) {
            for (int s = 0; s < 2; s++) {
                float[] pl = p[i * 2 + s];
                float sign = s == 0 ? 1 : -1;
                pl[0] = m[3] + sign * m[i];
                pl[1] = m[7] + sign * m[4 + i];
                pl[2] = m[11] + sign * m[8 + i];
                pl[3] = m[15] + sign * m[12 + i];
                float len = (float) Math.sqrt(pl[0] * pl[0] + pl[1] * pl[1] + pl[2] * pl[2]);
                for (int k = 0; k < 4; k++) pl[k] /= len;
            }
        }
    }

    /** Axis aligned box test, true if (partially) inside. */
    public boolean testAabb(float x0, float y0, float z0, float x1, float y1, float z1) {
        for (float[] pl : p) {
            float x = pl[0] > 0 ? x1 : x0;
            float y = pl[1] > 0 ? y1 : y0;
            float z = pl[2] > 0 ? z1 : z0;
            if (pl[0] * x + pl[1] * y + pl[2] * z + pl[3] < 0) return false;
        }
        return true;
    }
}
