package derpcraft.camera;

import derpcraft.world.be.BlockEntity;

import java.util.Locale;
import java.util.Map;

/**
 * A placed camera: its access code (screens connect with it), a name, where it looks and how wide.
 * Angles use the player's convention: yaw 0 = looking south (+z), pitch &gt; 0 = looking down.
 */
public final class CameraEntity extends BlockEntity {
    public static final String TYPE = "security_camera";
    /** Picture sizes of the three quality steps (16:9). */
    public static final int[][] SIZES = {{192, 108}, {320, 180}, {640, 360}};
    public static final String[] QUALITY = {"Low", "Medium", "High"};

    /** Code a screen must enter to show this camera ("" = not set up yet). */
    public String code = "";
    public String name = "";
    public float yaw, pitch = 15, fov = 70;
    public boolean on = true;
    /** 0..2, see {@link #SIZES}. */
    public int quality = 1;
    /** How far the camera sees (blocks); also limited by the render distance. */
    public int range = 96;
    /** Night vision: the picture is brightened like a security camera with infrared. */
    public boolean nightVision;

    @Override public String type() { return TYPE; }

    /** Camera position: in front of the lens, so the camera's own body is never in the picture. */
    public double eyeX() { return x + 0.5 - Math.sin(Math.toRadians(yaw)) * Math.cos(Math.toRadians(pitch)) * 0.42; }
    public double eyeY() { return y + 0.5 - Math.sin(Math.toRadians(pitch)) * 0.42; }
    public double eyeZ() { return z + 0.5 + Math.cos(Math.toRadians(yaw)) * Math.cos(Math.toRadians(pitch)) * 0.42; }

    public String label() { return name.isBlank() ? "Camera " + x + " " + y + " " + z : name; }

    /** Settings changed: save the chunk and let the network find the new code. */
    public void changed() { markDirty(); CameraNetwork.dirty = true; }

    @Override public void save(Map<String, String> out) {
        out.put("code", code);
        out.put("name", name);
        out.put("view", String.format(Locale.ROOT, "%.2f,%.2f,%.1f", yaw, pitch, fov));
        out.put("on", Boolean.toString(on));
        out.put("quality", Integer.toString(quality));
        out.put("range", Integer.toString(range));
        out.put("night", Boolean.toString(nightVision));
    }

    @Override public void load(Map<String, String> in) {
        code = in.getOrDefault("code", "");
        name = in.getOrDefault("name", "");
        float[] v = nums(in.get("view"), 3);
        yaw = v[0]; pitch = clamp(v[1], -90, 90); fov = in.containsKey("view") ? clamp(v[2], 10, 120) : 70;
        on = !"false".equals(in.get("on"));
        quality = (int) clamp(parse(in.get("quality"), 1), 0, SIZES.length - 1);
        range = (int) clamp(parse(in.get("range"), 96), 16, 256);
        nightVision = "true".equals(in.get("night"));
        CameraNetwork.dirty = true;
    }

    static float clamp(float v, float lo, float hi) { return Float.isNaN(v) ? lo : Math.max(lo, Math.min(hi, v)); }

    static float parse(String s, float def) {
        if (s == null) return def;
        try { return Float.parseFloat(s.trim()); } catch (NumberFormatException e) { return def; }
    }

    static float[] nums(String s, int n) {
        float[] v = new float[n];
        if (s == null) return v;
        String[] p = s.split(",");
        for (int i = 0; i < n && i < p.length; i++) v[i] = parse(p[i], 0);
        return v;
    }
}
