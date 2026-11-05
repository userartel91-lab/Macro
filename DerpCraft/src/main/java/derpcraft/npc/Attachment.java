package derpcraft.npc;

import derpcraft.model.Model3D;
import derpcraft.model.Models;
import derpcraft.util.Mat4;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * A 3D model (game/3d) worn on a body part of an NPC: a watch on the wrist, a cap on the head, a backpack on the
 * body. Offset in pixels from the part's pivot (x = to the NPC's right, y = up, z = forward), turn in degrees, size.
 */
public final class Attachment {
    public String model;
    /** Poses.HEAD .. Poses.LEFT_LEG */
    public int part;
    public float ox, oy, oz, rx, ry, rz, scale = 1;

    public Attachment(String model, int part) {
        this.model = model;
        this.part = part;
        float[] d = defaultOffset(part);
        ox = d[0]; oy = d[1]; oz = d[2];
    }

    public Attachment copy() {
        Attachment a = new Attachment(model, part);
        a.ox = ox; a.oy = oy; a.oz = oz; a.rx = rx; a.ry = ry; a.rz = rz; a.scale = scale;
        return a;
    }

    /** A good start for each part: top of the head, middle of the chest, the wrists, the feet. */
    public static float[] defaultOffset(int part) {
        return switch (part) {
            case Poses.HEAD -> new float[]{0, 8, 0};
            case Poses.BODY -> new float[]{0, -6, 0};
            case Poses.RIGHT_ARM -> new float[]{1, -8, 0};
            case Poses.LEFT_ARM -> new float[]{-1, -8, 0};
            default -> new float[]{0, -12, 0};
        };
    }

    /**
     * World / GUI matrix of the model from the matrix of its body part (block units, Minecraft model space: y down,
     * the NPC's right is -x, its face looks to -z). The model's own placement (3D Hub) is applied too.
     */
    public Mat4 matrix(Mat4 partMatrix, Model3D m) {
        Mat4 r = new Mat4().set(partMatrix).translate(-ox / 16f, -oy / 16f, -oz / 16f);
        r.scale(-1, -1, 1);                       // y up, x to the right, the NPC looks to -z
        if (rz != 0) r.rotate((float) Math.toRadians(rz), 0, 0, 1);
        if (ry != 0) r.rotate((float) Math.toRadians(ry), 0, 1, 0);
        if (rx != 0) r.rotate((float) Math.toRadians(rx), 1, 0, 0);
        if (scale != 1) r.scale(scale, scale, scale);
        return Models.settings(model).apply(r, m);
    }

    // ------------------------------------------------------------------ actors.txt: model|part|ox,oy,oz|rx,ry,rz|scale ; ...
    public static String encode(List<Attachment> l) {
        StringBuilder sb = new StringBuilder();
        for (Attachment a : l) {
            if (sb.length() > 0) sb.append(';');
            sb.append(a.model.replace(";", "").replace("|", "")).append('|').append(a.part)
              .append('|').append(String.format(Locale.ROOT, "%s,%s,%s|%s,%s,%s|%s", a.ox, a.oy, a.oz, a.rx, a.ry, a.rz, a.scale));
        }
        return sb.toString();
    }

    public static List<Attachment> decode(String s) {
        List<Attachment> out = new ArrayList<>();
        if (s == null || s.isBlank()) return out;
        for (String e : s.split(";")) {
            String[] p = e.split("\\|");
            if (p.length < 2 || p[0].isBlank()) continue;
            Attachment a;
            try { a = new Attachment(p[0], Math.max(0, Math.min(5, Integer.parseInt(p[1].trim())))); } catch (NumberFormatException ex) { continue; }
            float[] o = nums(p.length > 2 ? p[2] : "", 3), r = nums(p.length > 3 ? p[3] : "", 3), sc = nums(p.length > 4 ? p[4] : "1", 1);
            if (p.length > 2) { a.ox = o[0]; a.oy = o[1]; a.oz = o[2]; }
            a.rx = r[0]; a.ry = r[1]; a.rz = r[2];
            a.scale = sc[0] <= 0 ? 1 : sc[0];
            out.add(a);
        }
        return out;
    }

    private static float[] nums(String s, int n) {
        float[] v = new float[n];
        String[] p = s.split(",");
        for (int i = 0; i < n && i < p.length; i++) try { v[i] = Float.parseFloat(p[i].trim()); } catch (NumberFormatException ignored) {}
        return v;
    }
}
