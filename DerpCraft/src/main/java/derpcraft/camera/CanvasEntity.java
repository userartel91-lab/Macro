package derpcraft.camera;

import derpcraft.world.be.BlockEntity;

import java.util.Locale;
import java.util.Map;

/**
 * A screen canvas: a flat picture hung on a block face (meta = direction to that block, like an item frame). It is
 * sized, moved and turned to fit a decoration (a 3D monitor model) and shows the camera whose code was entered.
 * Sizes and offsets are in blocks: right / up along the canvas, out = away from the block it hangs on.
 */
public final class CanvasEntity extends BlockEntity {
    public static final String TYPE = "screen_canvas";

    /** Code of the connected camera ("" = none). */
    public String code = "";
    public float width = 1, height = 0.5625f;
    public float right, up, out;
    /** Turn in the canvas plane (degrees). */
    public float roll;
    /** Cut off from each side of the picture (0..0.45 of its width / height). */
    public float cropL, cropR, cropT, cropB;
    public float brightness = 1;
    /** Floor and ceiling canvases: which horizontal direction is the picture's top (0..3 = Dir.HORIZONTAL order S, W, N, E). */
    public int facing;

    @Override public String type() { return TYPE; }

    /** Crops the picture so it fills the canvas without stretching (16:9 camera picture). */
    public void cropToFill() {
        float pic = 16f / 9f, can = width / Math.max(0.01f, height);
        cropL = cropR = cropT = cropB = 0;
        if (can > pic) { float keep = pic / can; cropT = cropB = (1 - keep) / 2; }       // wider canvas: cut top and bottom
        else if (can < pic) { float keep = can / pic; cropL = cropR = (1 - keep) / 2; }  // narrower: cut the sides
    }

    @Override public void save(Map<String, String> o) {
        o.put("code", code);
        o.put("size", f(width) + "," + f(height));
        o.put("offset", f(right) + "," + f(up) + "," + f(out));
        o.put("roll", f(roll));
        o.put("crop", f(cropL) + "," + f(cropR) + "," + f(cropT) + "," + f(cropB));
        o.put("bright", f(brightness));
        o.put("facing", Integer.toString(facing));
    }

    @Override public void load(Map<String, String> in) {
        code = in.getOrDefault("code", "");
        float[] s = CameraEntity.nums(in.get("size"), 2), off = CameraEntity.nums(in.get("offset"), 3), c = CameraEntity.nums(in.get("crop"), 4);
        width = in.containsKey("size") ? CameraEntity.clamp(s[0], 0.05f, 16) : 1;
        height = in.containsKey("size") ? CameraEntity.clamp(s[1], 0.05f, 16) : 0.5625f;
        right = CameraEntity.clamp(off[0], -4, 4); up = CameraEntity.clamp(off[1], -4, 4); out = CameraEntity.clamp(off[2], -4, 4);
        roll = CameraEntity.parse(in.get("roll"), 0);
        cropL = CameraEntity.clamp(c[0], 0, 0.45f); cropR = CameraEntity.clamp(c[1], 0, 0.45f);
        cropT = CameraEntity.clamp(c[2], 0, 0.45f); cropB = CameraEntity.clamp(c[3], 0, 0.45f);
        brightness = CameraEntity.clamp(CameraEntity.parse(in.get("bright"), 1), 0.1f, 2);
        facing = (int) CameraEntity.parse(in.get("facing"), 0) & 3;
    }

    private static String f(float v) { return String.format(Locale.ROOT, "%.4f", v); }
}
