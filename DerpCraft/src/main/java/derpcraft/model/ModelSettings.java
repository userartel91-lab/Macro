package derpcraft.model;

import derpcraft.util.Mat4;

import java.util.Locale;

/**
 * How a model is placed (3D Hub): the anchor point (the point that sits on the block / on the NPC's body part, in
 * the model's own units), the size (uniform and per axis) and the turn. Used everywhere the model is shown.
 */
public final class ModelSettings {
    /** Anchor in model units (blocks). NaN = bottom centre of the model (default). */
    public float ax = Float.NaN, ay = Float.NaN, az = Float.NaN;
    public float scale = 1, sx = 1, sy = 1, sz = 1;
    /** Turn in degrees (applied z, y, x like Blockbench). */
    public float rx, ry, rz;

    public ModelSettings copy() {
        ModelSettings s = new ModelSettings();
        s.ax = ax; s.ay = ay; s.az = az; s.scale = scale; s.sx = sx; s.sy = sy; s.sz = sz; s.rx = rx; s.ry = ry; s.rz = rz;
        return s;
    }

    /** The anchor point, filling in the default from the model's bounds. */
    public float[] anchor(Model3D m) {
        float dx = m == null || m.empty() ? 0 : (m.minX + m.maxX) / 2, dy = m == null || m.empty() ? 0 : m.minY, dz = m == null || m.empty() ? 0 : (m.minZ + m.maxZ) / 2;
        return new float[]{Float.isNaN(ax) ? dx : ax, Float.isNaN(ay) ? dy : ay, Float.isNaN(az) ? dz : az};
    }

    /** Applies size, turn and anchor to 'm' (model units -> blocks, anchor at the origin). */
    public Mat4 apply(Mat4 m, Model3D model) {
        if (rz != 0) m.rotate((float) Math.toRadians(rz), 0, 0, 1);
        if (ry != 0) m.rotate((float) Math.toRadians(ry), 0, 1, 0);
        if (rx != 0) m.rotate((float) Math.toRadians(rx), 1, 0, 0);
        m.scale(scale * sx, scale * sy, scale * sz);
        float[] a = anchor(model);
        m.translate(-a[0], -a[1], -a[2]);
        return m;
    }

    public String encode() {
        return String.format(Locale.ROOT, "%s,%s,%s,%s,%s,%s,%s,%s,%s,%s", f(ax), f(ay), f(az), f(scale), f(sx), f(sy), f(sz), f(rx), f(ry), f(rz));
    }

    private static String f(float v) { return Float.isNaN(v) ? "auto" : Float.toString(v); }

    public static ModelSettings decode(String s) {
        ModelSettings m = new ModelSettings();
        if (s == null || s.isBlank()) return m;
        String[] p = s.split(",");
        float[] v = new float[10];
        float[] def = {Float.NaN, Float.NaN, Float.NaN, 1, 1, 1, 1, 0, 0, 0};
        for (int i = 0; i < 10; i++) {
            v[i] = def[i];
            if (i < p.length) try { v[i] = p[i].trim().equals("auto") ? Float.NaN : Float.parseFloat(p[i].trim()); } catch (NumberFormatException ignored) {}
        }
        m.ax = v[0]; m.ay = v[1]; m.az = v[2]; m.scale = v[3] <= 0 ? 1 : v[3]; m.sx = v[4]; m.sy = v[5]; m.sz = v[6]; m.rx = v[7]; m.ry = v[8]; m.rz = v[9];
        return m;
    }

    /**
     * The model's bounding box after these settings and an extra matrix (8 corners transformed): {x0,y0,z0,x1,y1,z1}.
     */
    public float[] bounds(Model3D model, Mat4 extra) {
        Mat4 m = new Mat4();
        if (extra != null) m.set(extra);
        apply(m, model);
        float[] out = {Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE};
        if (model == null || model.empty()) return new float[]{0, 0, 0, 0, 0, 0};
        float[] t = new float[4];
        for (int i = 0; i < 8; i++) {
            float x = (i & 1) == 0 ? model.minX : model.maxX, y = (i & 2) == 0 ? model.minY : model.maxY, z = (i & 4) == 0 ? model.minZ : model.maxZ;
            m.transform(x, y, z, t);
            for (int k = 0; k < 3; k++) { out[k] = Math.min(out[k], t[k]); out[k + 3] = Math.max(out[k + 3], t[k]); }
        }
        return out;
    }
}
