package derpcraft.gun;

import derpcraft.model.Model3D;
import derpcraft.util.Mat4;

import java.io.PrintWriter;
import java.nio.file.Path;
import java.util.Locale;

/**
 * Third person pictures of a gun in the player's hands (the Minecraft model as plain boxes, the hold of GunHold3P):
 * from behind, from the front and from the side, looking straight, up, down and turned. For preview.py.
 * Usage: java derpcraft.gun.GunPose3PDump &lt;gun folder&gt; &lt;out.txt&gt;
 */
public final class GunPose3PDump {
    public static void main(String[] args) throws Exception {
        Path dir = Path.of(args[0]);
        String id = dir.getFileName().toString().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_]", "_");
        GunType t = new GunType(id, dir);
        if (t.model() == null) { System.out.println("model: " + t.error); return; }
        float[][] cams = {{0.6f, 1.9f, -1.5f}, {1.1f, 1.55f, 1.6f}, {-1.9f, 1.4f, 0.3f}};
        String[] camNames = {"back", "front", "side"};
        float[][] looks = {{0, 0}, {-40, 0}, {40, 0}, {0, 40}};
        String[] lookNames = {"straight", "up", "down", "turned"};
        try (PrintWriter out = new PrintWriter(args[1])) {
            for (int c = 0; c < cams.length; c++)
                for (int l = 0; l < looks.length; l++) {
                    if (c == 2 && l == 3) continue;
                    out.println("FRAME 3p_" + camNames[c] + "_" + lookNames[l] + " 0");
                    Mat4 view = lookAt(cams[c], new float[]{0, 1.3f, 0.3f});
                    model(out, view, t, looks[l][0], looks[l][1], 0, -1, false);
                }
            // close-ups of the right hand on the grip: from the right, from above, from the front
            float[][] close = {{-0.9f, 1.45f, 0.5f}, {-0.35f, 2.2f, 0.45f}, {-0.3f, 1.35f, 1.2f}};
            String[] closeNames = {"right", "above", "front"};
            for (int c = 0; c < close.length; c++) {
                out.println("FRAME 3p_hand_" + closeNames[c] + " 0");
                model(out, lookAt(close[c], new float[]{-0.15f, 1.28f, 0.35f}), t, 0, 0, 0, -1, false);
            }
            // motion from the side: a shot, the reload stages, the bolt
            Object[][] moves = {{"kick", 1f, -1f, false}, {"reload_25", 0f, 0.25f, false}, {"reload_38", 0f, 0.38f, false}, {"reload_47", 0f, 0.47f, false},
                    {"reload_56", 0f, 0.56f, false}, {"reload_66", 0f, 0.66f, false}, {"empty_80", 0f, 0.8f, true}, {"empty_86", 0f, 0.86f, true}};
            for (Object[] mv : moves) {
                out.println("FRAME 3p_" + mv[0] + " 0");
                model(out, lookAt(new float[]{-1.6f, 1.6f, 0.9f}, new float[]{0, 1.3f, 0.3f}), t, 0, 0, (Float) mv[1], (Float) mv[2], (Boolean) mv[3]);
            }
            // melee from the side and from the front: each kind's wind-up and hit
            for (int k = 0; k < GunType.MELEE.length; k++)
                for (int ph = 0; ph < 2; ph++) {
                    float tm = ph == 0 ? t.meleeTime[k][0] : GunAnimator.meleeImpact(t, k);
                    String nm = GunType.MELEE[k] + (ph == 0 ? "_wind" : "_hit");
                    out.println("FRAME 3p_" + nm + " 0");
                    model(out, lookAt(new float[]{-1.6f, 1.6f, 0.9f}, new float[]{0, 1.3f, 0.3f}), t, 0, 0, 0, -1, false, k, tm);
                    out.println("FRAME 3p_" + nm + "_front 0");
                    model(out, lookAt(new float[]{1.1f, 1.55f, 1.6f}, new float[]{0, 1.3f, 0.3f}), t, 0, 0, 0, -1, false, k, tm);
                }
            // the gun carried on the back (Left Alt + F): from behind, the side, the front, above; the arm reaching for it
            float[][] backCams = {{0.7f, 1.7f, -1.7f}, {-1.9f, 1.4f, -0.2f}, {1.9f, 1.5f, 0.6f}, {0.3f, 3.1f, -0.9f}, {1.1f, 1.55f, 1.6f}};
            String[] backNames = {"back", "side", "side_left", "above", "front"};
            for (int c = 0; c < backCams.length; c++) {
                out.println("FRAME 3p_onback_" + backNames[c] + " 0");
                backModel(out, lookAt(backCams[c], new float[]{0, 1.1f, 0}), t, 0);
            }
            out.println("FRAME 3p_onback_close 0");
            backModel(out, lookAt(new float[]{0.35f, 1.25f, -1.0f}, new float[]{0, 1.0f, 0}), t, 0);
            // magazines in the pouches (Left Alt + E): from the front and the side
            out.println("FRAME 3p_pouches_front 0");
            backModel(out, lookAt(new float[]{0.5f, 1.3f, 1.3f}, new float[]{0, 0.95f, 0}), t, 0, true);
            out.println("FRAME 3p_pouches_side 0");
            backModel(out, lookAt(new float[]{-1.4f, 1.2f, 0.6f}, new float[]{0, 0.95f, 0}), t, 0, true);
            out.println("FRAME 3p_onback_reach 0");
            backModel(out, lookAt(new float[]{0.9f, 1.7f, -1.7f}, new float[]{0, 1.2f, 0}), t, 1);
            out.println("FRAME 3p_onback_reach_side 0");
            backModel(out, lookAt(new float[]{-1.9f, 1.5f, -0.4f}, new float[]{0, 1.2f, 0}), t, 1);
            // the NPC muzzle point (worldPoint) must be where the drawn gun's muzzle is
            Mat4 base = new Mat4().rotate((float) Math.PI, 0, 1, 0).scale(-1, -1, 1).translate(0, -1.501f, 0);
            Mat4 upper = new Mat4().set(base).translate(0, 12 / 16f, 0).rotate((float) Math.toRadians(GunHold3P.TWIST), 0, 1, 0).translate(0, -12 / 16f, 0);
            GunHold3P h = new GunHold3P();
            h.compute(t, 20, 15, new float[]{-5, 2, 0}, new float[]{5, 2, 0});
            float[] drawn = new float[4];
            new Mat4().set(upper).mul(h.gun).transform(t.muzzlePoint[0], t.muzzlePoint[1], t.muzzlePoint[2], drawn);
            double[] wp = GunHold3P.worldPoint(t, t.muzzlePoint, 0, 0, 0, 0, 15, 20, 1, 0);
            System.out.printf(Locale.ROOT, "NPC muzzle point vs drawn: %.4f blocks apart%n",
                    Math.sqrt((wp[0] - drawn[0]) * (wp[0] - drawn[0]) + (wp[1] - drawn[1]) * (wp[1] - drawn[1]) + (wp[2] - drawn[2]) * (wp[2] - drawn[2])));
        }
    }

    static Mat4 lookAt(float[] e, float[] c) {
        float fx = c[0] - e[0], fy = c[1] - e[1], fz = c[2] - e[2];
        float fl = (float) Math.sqrt(fx * fx + fy * fy + fz * fz); fx /= fl; fy /= fl; fz /= fl;
        float rx = fy * 0 - fz * 1, ry = fz * 0 - fx * 0, rz = fx * 1 - fy * 0;    // f x up
        float rl = (float) Math.sqrt(rx * rx + ry * ry + rz * rz); rx /= rl; ry /= rl; rz /= rl;
        float ux = ry * fz - rz * fy, uy = rz * fx - rx * fz, uz = rx * fy - ry * fx;
        Mat4 v = new Mat4();
        float[] m = v.m;
        m[0] = rx; m[4] = ry; m[8] = rz;
        m[1] = ux; m[5] = uy; m[9] = uz;
        m[2] = -fx; m[6] = -fy; m[10] = -fz;
        m[12] = -(rx * e[0] + ry * e[1] + rz * e[2]);
        m[13] = -(ux * e[0] + uy * e[1] + uz * e[2]);
        m[14] = fx * e[0] + fy * e[1] + fz * e[2];
        return v;
    }

    /** The player model (feet at 0, facing +z, like EntityRenderer with body yaw 0) with the gun. */
    static void model(PrintWriter out, Mat4 view, GunType t, float pitch, float headYaw, float kick, float reload, boolean empty) {
        model(out, view, t, pitch, headYaw, kick, reload, empty, -1, 0);
    }

    static void model(PrintWriter out, Mat4 view, GunType t, float pitch, float headYaw, float kick, float reload, boolean empty, int melee, float meleeTime) {
        Mat4 base = new Mat4().set(view).rotate((float) Math.PI, 0, 1, 0).scale(-1, -1, 1).translate(0, -1.501f, 0);
        // the upper body twisted at the waist like EntityRenderer does with a gun
        Mat4 upper = new Mat4().set(base).translate(0, 12 / 16f, 0).rotate((float) Math.toRadians(GunHold3P.TWIST), 0, 1, 0).translate(0, -12 / 16f, 0);
        GunHold3P h = new GunHold3P();
        float[] rP = {-5, 2, 0}, lP = {5, 2, 0};
        h.compute(t, pitch, headYaw, rP, lP, kick, reload, empty, melee, meleeTime);
        box(out, part(upper, new float[]{0, 0, 0}, new float[]{(float) Math.toRadians(pitch), (float) Math.toRadians(headYaw - GunHold3P.TWIST), 0}, 1), -4, -8, -4, 8, 8, 8, "196 150 110");
        // a nose so the head's direction shows
        box(out, part(upper, new float[]{0, 0, 0}, new float[]{(float) Math.toRadians(pitch), (float) Math.toRadians(headYaw - GunHold3P.TWIST), 0}, 1), -1, -4, -6, 2, 2, 2, "120 80 60");
        box(out, part(upper, new float[]{0, 0, 0}, new float[3], 1), -4, 0, -2, 8, 12, 4, "0 160 160");
        box(out, part(upper, rP, h.rArm, h.rStretch), -3, -2, -2, 4, 12, 4, "196 140 106");
        box(out, part(upper, lP, h.lArm, h.lStretch), -1, -2, -2, 4, 12, 4, "170 112 84");
        box(out, part(base, new float[]{-1.9f, 12, 0}, new float[3], 1), -2, 0, -2, 4, 12, 4, "60 60 160");
        box(out, part(base, new float[]{1.9f, 12, 0}, new float[3], 1), -2, 0, -2, 4, 12, 4, "50 50 140");
        Mat4 g = new Mat4().set(upper).mul(h.gun);
        for (Model3D.Bone b : t.body) GunPoseDump.tris(out, g, b, "TRI");
        if (t.mag != null && h.magMode == 0) GunPoseDump.tris(out, h.magMoved ? new Mat4().set(g).mul(h.mag) : g, t.mag, "TRIM");
        if (t.mag != null && h.magMode == 2) GunPoseDump.tris(out, new Mat4().set(upper).mul(h.magBody), t.mag, "TRIM");
        if (t.slide != null) GunPoseDump.tris(out, new Mat4().set(g).translate(t.boltDir[0] * h.slide, t.boltDir[1] * h.slide, t.boltDir[2] * h.slide), t.slide, "TRIS");
    }

    /** The player model with empty hands and the gun on the back (GunHold3P.back on the body box), the right arm reaching by 'reach'. */
    static void backModel(PrintWriter out, Mat4 view, GunType t, float reach) { backModel(out, view, t, reach, false); }

    static void backModel(PrintWriter out, Mat4 view, GunType t, float reach, boolean pouches) {
        Mat4 base = new Mat4().set(view).rotate((float) Math.PI, 0, 1, 0).scale(-1, -1, 1).translate(0, -1.501f, 0);
        float[] rP = {-5, 2, 0}, lP = {5, 2, 0}, rArm = {0, 0, 0.1f}, lArm = {0, 0, -0.1f};
        GunHold3P.backReach(t, reach, rArm);
        box(out, part(base, new float[]{0, 0, 0}, new float[3], 1), -4, -8, -4, 8, 8, 8, "196 150 110");
        box(out, part(base, new float[]{0, 0, 0}, new float[3], 1), -1, -4, -6, 2, 2, 2, "120 80 60");
        Mat4 body = part(base, new float[]{0, 0, 0}, new float[3], 1);
        box(out, body, -4, 0, -2, 8, 12, 4, "0 160 160");
        box(out, part(base, rP, rArm, 1), -3, -2, -2, 4, 12, 4, "196 140 106");
        box(out, part(base, lP, lArm, 1), -1, -2, -2, 4, 12, 4, "170 112 84");
        box(out, part(base, new float[]{-1.9f, 12, 0}, new float[3], 1), -2, 0, -2, 4, 12, 4, "60 60 160");
        box(out, part(base, new float[]{1.9f, 12, 0}, new float[3], 1), -2, 0, -2, 4, 12, 4, "50 50 140");
        Mat4 g = new Mat4().set(body).mul(GunHold3P.back(t, new Mat4()));
        for (Model3D.Bone b : t.body) GunPoseDump.tris(out, g, b, "TRI");
        if (t.mag != null) GunPoseDump.tris(out, g, t.mag, "TRIM");
        if (t.slide != null) GunPoseDump.tris(out, g, t.slide, "TRIS");
        if (pouches && t.mag != null) for (int k = 0; k < 6; k++) GunPoseDump.tris(out, new Mat4().set(body).mul(GunHold3P.pouch(t, k, new Mat4())), t.mag, "TRIM");
    }

    static Mat4 part(Mat4 base, float[] pivot, float[] ang, float stretch) {
        Mat4 m = new Mat4().set(base).translate(pivot[0] / 16f, pivot[1] / 16f, pivot[2] / 16f);
        if (ang[2] != 0) m.rotate(ang[2], 0, 0, 1);
        if (ang[1] != 0) m.rotate(ang[1], 0, 1, 0);
        if (ang[0] != 0) m.rotate(ang[0], 1, 0, 0);
        if (stretch != 1) m.scale(1, stretch, 1);
        return m;
    }

    static void box(PrintWriter out, Mat4 m, float x, float y, float z, float w, float h, float d, String rgb) {
        StringBuilder sb = new StringBuilder("BOX " + rgb);
        float[] o = new float[4];
        for (int i = 0; i < 8; i++) {
            m.transform((x + ((i & 1) == 0 ? 0 : w)) / 16f, (y + ((i & 4) == 0 ? 0 : h)) / 16f, (z + ((i & 2) == 0 ? 0 : d)) / 16f, o);
            sb.append(String.format(Locale.ROOT, " %f %f %f", o[0], o[1], o[2]));
        }
        out.println(sb);
    }
}
