package derpcraft.util;

/** Minimal column-major 4x4 matrix (OpenGL convention). */
public final class Mat4 {
    public final float[] m = new float[16];

    public Mat4() { identity(); }

    public Mat4 identity() {
        java.util.Arrays.fill(m, 0);
        m[0] = m[5] = m[10] = m[15] = 1;
        return this;
    }

    public Mat4 set(Mat4 o) { System.arraycopy(o.m, 0, m, 0, 16); return this; }

    public Mat4 perspective(float fovYRad, float aspect, float near, float far) {
        float f = (float) (1.0 / Math.tan(fovYRad / 2));
        java.util.Arrays.fill(m, 0);
        m[0] = f / aspect;
        m[5] = f;
        m[10] = (far + near) / (near - far);
        m[11] = -1;
        m[14] = 2 * far * near / (near - far);
        return this;
    }

    public Mat4 ortho(float l, float r, float b, float t, float n, float f) {
        java.util.Arrays.fill(m, 0);
        m[0] = 2 / (r - l);
        m[5] = 2 / (t - b);
        m[10] = -2 / (f - n);
        m[12] = -(r + l) / (r - l);
        m[13] = -(t + b) / (t - b);
        m[14] = -(f + n) / (f - n);
        m[15] = 1;
        return this;
    }

    /** this = this * other */
    public Mat4 mul(Mat4 o) {
        float[] a = m, b = o.m, r = new float[16];
        for (int c = 0; c < 4; c++)
            for (int row = 0; row < 4; row++)
                r[c * 4 + row] = a[row] * b[c * 4] + a[4 + row] * b[c * 4 + 1] + a[8 + row] * b[c * 4 + 2] + a[12 + row] * b[c * 4 + 3];
        System.arraycopy(r, 0, m, 0, 16);
        return this;
    }

    public Mat4 translate(float x, float y, float z) {
        m[12] += m[0] * x + m[4] * y + m[8] * z;
        m[13] += m[1] * x + m[5] * y + m[9] * z;
        m[14] += m[2] * x + m[6] * y + m[10] * z;
        m[15] += m[3] * x + m[7] * y + m[11] * z;
        return this;
    }

    public Mat4 scale(float x, float y, float z) {
        for (int i = 0; i < 4; i++) { m[i] *= x; m[4 + i] *= y; m[8 + i] *= z; }
        return this;
    }

    public Mat4 rotate(float angleRad, float x, float y, float z) {
        // the axis must have length 1, otherwise the matrix stretches and shears the model (tumbling items looked like rhombuses)
        float len = (float) Math.sqrt(x * x + y * y + z * z);
        if (len < 1e-6f) return this;
        if (Math.abs(len - 1) > 1e-6f) { x /= len; y /= len; z /= len; }
        float c = (float) Math.cos(angleRad), s = (float) Math.sin(angleRad), t = 1 - c;
        Mat4 r = new Mat4();
        float[] q = r.m;
        q[0] = t * x * x + c;     q[4] = t * x * y - s * z; q[8] = t * x * z + s * y;
        q[1] = t * x * y + s * z; q[5] = t * y * y + c;     q[9] = t * y * z - s * x;
        q[2] = t * x * z - s * y; q[6] = t * y * z + s * x; q[10] = t * z * z + c;
        return mul(r);
    }

    /** Transforms (x,y,z,1); writes result into out[0..3]. */
    public void transform(float x, float y, float z, float[] out) {
        out[0] = m[0] * x + m[4] * y + m[8] * z + m[12];
        out[1] = m[1] * x + m[5] * y + m[9] * z + m[13];
        out[2] = m[2] * x + m[6] * y + m[10] * z + m[14];
        out[3] = m[3] * x + m[7] * y + m[11] * z + m[15];
    }

    /** General inverse (returns a new matrix; identity if singular). */
    public Mat4 inverted() {
        float[] a = m, inv = new float[16];
        inv[0] = a[5] * a[10] * a[15] - a[5] * a[11] * a[14] - a[9] * a[6] * a[15] + a[9] * a[7] * a[14] + a[13] * a[6] * a[11] - a[13] * a[7] * a[10];
        inv[4] = -a[4] * a[10] * a[15] + a[4] * a[11] * a[14] + a[8] * a[6] * a[15] - a[8] * a[7] * a[14] - a[12] * a[6] * a[11] + a[12] * a[7] * a[10];
        inv[8] = a[4] * a[9] * a[15] - a[4] * a[11] * a[13] - a[8] * a[5] * a[15] + a[8] * a[7] * a[13] + a[12] * a[5] * a[11] - a[12] * a[7] * a[9];
        inv[12] = -a[4] * a[9] * a[14] + a[4] * a[10] * a[13] + a[8] * a[5] * a[14] - a[8] * a[6] * a[13] - a[12] * a[5] * a[10] + a[12] * a[6] * a[9];
        inv[1] = -a[1] * a[10] * a[15] + a[1] * a[11] * a[14] + a[9] * a[2] * a[15] - a[9] * a[3] * a[14] - a[13] * a[2] * a[11] + a[13] * a[3] * a[10];
        inv[5] = a[0] * a[10] * a[15] - a[0] * a[11] * a[14] - a[8] * a[2] * a[15] + a[8] * a[3] * a[14] + a[12] * a[2] * a[11] - a[12] * a[3] * a[10];
        inv[9] = -a[0] * a[9] * a[15] + a[0] * a[11] * a[13] + a[8] * a[1] * a[15] - a[8] * a[3] * a[13] - a[12] * a[1] * a[11] + a[12] * a[3] * a[9];
        inv[13] = a[0] * a[9] * a[14] - a[0] * a[10] * a[13] - a[8] * a[1] * a[14] + a[8] * a[2] * a[13] + a[12] * a[1] * a[10] - a[12] * a[2] * a[9];
        inv[2] = a[1] * a[6] * a[15] - a[1] * a[7] * a[14] - a[5] * a[2] * a[15] + a[5] * a[3] * a[14] + a[13] * a[2] * a[7] - a[13] * a[3] * a[6];
        inv[6] = -a[0] * a[6] * a[15] + a[0] * a[7] * a[14] + a[4] * a[2] * a[15] - a[4] * a[3] * a[14] - a[12] * a[2] * a[7] + a[12] * a[3] * a[6];
        inv[10] = a[0] * a[5] * a[15] - a[0] * a[7] * a[13] - a[4] * a[1] * a[15] + a[4] * a[3] * a[13] + a[12] * a[1] * a[7] - a[12] * a[3] * a[5];
        inv[14] = -a[0] * a[5] * a[14] + a[0] * a[6] * a[13] + a[4] * a[1] * a[14] - a[4] * a[2] * a[13] - a[12] * a[1] * a[6] + a[12] * a[2] * a[5];
        inv[3] = -a[1] * a[6] * a[11] + a[1] * a[7] * a[10] + a[5] * a[2] * a[11] - a[5] * a[3] * a[10] - a[9] * a[2] * a[7] + a[9] * a[3] * a[6];
        inv[7] = a[0] * a[6] * a[11] - a[0] * a[7] * a[10] - a[4] * a[2] * a[11] + a[4] * a[3] * a[10] + a[8] * a[2] * a[7] - a[8] * a[3] * a[6];
        inv[11] = -a[0] * a[5] * a[11] + a[0] * a[7] * a[9] + a[4] * a[1] * a[11] - a[4] * a[3] * a[9] - a[8] * a[1] * a[7] + a[8] * a[3] * a[5];
        inv[15] = a[0] * a[5] * a[10] - a[0] * a[6] * a[9] - a[4] * a[1] * a[10] + a[4] * a[2] * a[9] + a[8] * a[1] * a[6] - a[8] * a[2] * a[5];
        float det = a[0] * inv[0] + a[1] * inv[4] + a[2] * inv[8] + a[3] * inv[12];
        Mat4 r = new Mat4();
        if (Math.abs(det) < 1e-20f) return r;
        for (int i = 0; i < 16; i++) r.m[i] = inv[i] / det;
        return r;
    }
}
