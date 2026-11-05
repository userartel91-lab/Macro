package derpcraft.model;

import derpcraft.util.Mat4;
import derpcraft.world.be.BlockEntity;

import java.util.Map;

/** Data of a 3D block: which model, how it is turned, sized and moved, and whether it has collision. */
public final class ModelEntity extends BlockEntity {
    /** Set once any 3D block exists (collision queries skip the search before that). */
    static volatile boolean any;
    /** The last model put on a 3D block: new blocks start with it. */
    public static String lastModel;

    public String model = "";
    /** Turn (degrees), size, offset from the block's bottom centre (blocks). */
    public float yaw, pitch, roll, scale = 1, ox, oy, oz;
    public boolean collision = true;
    /** Cached collision box (world coordinates) and the settings version it was made with. */
    double[] box;
    int boxVersion = -1;


    @Override public String type() { return "model_block"; }

    /** The model's matrix in the world, relative to (relX, relY, relZ) = position minus camera. */
    public Mat4 matrix(double relX, double relY, double relZ, Model3D m) {
        Mat4 r = new Mat4().translate((float) (relX + 0.5 + ox), (float) (relY + oy), (float) (relZ + 0.5 + oz));
        if (yaw != 0) r.rotate((float) Math.toRadians(-yaw), 0, 1, 0);
        if (pitch != 0) r.rotate((float) Math.toRadians(pitch), 1, 0, 0);
        if (roll != 0) r.rotate((float) Math.toRadians(roll), 0, 0, 1);
        if (scale != 1) r.scale(scale, scale, scale);
        return Models.settings(model).apply(r, m);
    }

    /** Collision box in world coordinates (the model's bounds), or null. */
    public double[] collisionBox() {
        if (!collision || model.isEmpty()) return null;
        if (box != null && boxVersion == Models.version()) return box;
        Model3D m = Models.get(model);
        if (m == null || m.empty()) { box = null; boxVersion = Models.version(); return null; }
        Mat4 base = new Mat4().translate(0.5f + ox, oy, 0.5f + oz);
        if (yaw != 0) base.rotate((float) Math.toRadians(-yaw), 0, 1, 0);
        if (pitch != 0) base.rotate((float) Math.toRadians(pitch), 1, 0, 0);
        if (roll != 0) base.rotate((float) Math.toRadians(roll), 0, 0, 1);
        if (scale != 1) base.scale(scale, scale, scale);
        float[] b = Models.settings(model).bounds(m, base);
        box = new double[]{x + b[0], y + b[1], z + b[2], x + b[3], y + b[4], z + b[5]};
        // very thin models (a flat poster) still get a little thickness
        for (int k = 0; k < 3; k++) if (box[k + 3] - box[k] < 0.02) { box[k] -= 0.01; box[k + 3] += 0.01; }
        boxVersion = Models.version();
        return box;
    }

    /** Settings changed: rebuild the collision box and save. */
    public void changed() { box = null; boxVersion = -1; markDirty(); }

    @Override public void save(Map<String, String> out) {
        out.put("model", model);
        out.put("turn", yaw + "," + pitch + "," + roll);
        out.put("scale", Float.toString(scale));
        out.put("offset", ox + "," + oy + "," + oz);
        out.put("collision", Boolean.toString(collision));
    }

    @Override public void load(Map<String, String> in) {
        any = true;
        model = in.getOrDefault("model", "");
        float[] t = nums(in.get("turn")), o = nums(in.get("offset"));
        yaw = t[0]; pitch = t[1]; roll = t[2];
        ox = o[0]; oy = o[1]; oz = o[2];
        try { scale = Float.parseFloat(in.getOrDefault("scale", "1")); } catch (NumberFormatException e) { scale = 1; }
        if (scale <= 0) scale = 1;
        collision = Boolean.parseBoolean(in.getOrDefault("collision", "true"));
        box = null;
    }

    private static float[] nums(String s) {
        float[] v = new float[3];
        if (s == null) return v;
        String[] p = s.split(",");
        for (int i = 0; i < 3 && i < p.length; i++) try { v[i] = Float.parseFloat(p[i].trim()); } catch (NumberFormatException ignored) {}
        return v;
    }
}
