package derpcraft.npc;

import derpcraft.util.Mat4;

import java.io.PrintWriter;
import java.util.Locale;

/**
 * Offline check of {@link Ragdoll} (no game): falls on flat ground, against a wall, off a step, a hard push; prints
 * how far the sticks stretch, whether points end up inside blocks, when it falls asleep, and writes frames of the
 * fall for tools/gun_check/preview.py (boxes in view space).
 * Usage: java derpcraft.npc.RagdollTest out.txt
 */
public final class RagdollTest {
    public static void main(String[] a) throws Exception {
        try (PrintWriter out = new PrintWriter(a[0])) {
            boolean ok = true;
            // ground: y < 0 solid; a wall at x < -1.0 (scene 2); a step 1 high at z > 1 (scene 3)
            Ragdoll.Solid flat = (x, y, z) -> y < 0;
            Ragdoll.Solid wall = (x, y, z) -> y < 0 || x < -1.0;
            Ragdoll.Solid step = (x, y, z) -> y < 0 || (z < 0.6 && y < 1);
            Ragdoll.Solid kerb = (x, y, z) -> y < 0 || (z < 0.6 && z > -0.4 && y < 0.5);
            Object[][] cases = {
                    {"shot_back", flat, Ragdoll.standing(0, 0, 0, 0, 1, 0, 0, -0.25, -0.1)},
                    {"fall_forward", flat, Ragdoll.standing(0, 0, 0, 0, 1, 0, 0, 0.05, 0.15)},
                    {"wall", wall, Ragdoll.standing(0, 0, 0, 90, 1, -0.3, 0.1, 0, 0)},
                    {"off_step", step, Ragdoll.standing(0, 1, 0.2, 0, 1, 0, 0, 0.12, 0.2)},
                    {"car", flat, Ragdoll.standing(0, 0, 0, 90, 1, 0.9, 0.35, 0, 0)},
                    {"big", flat, Ragdoll.standing(0, 0, 0, 0, 2, 0, 0, -0.2, -0.1)},
                    {"kerb", kerb, Ragdoll.standing(0, 0, 1.0, 0, 1, 0, 0, -0.12, -0.15)},
                    {"headshot", flat, headshot()},
            };
            for (Object[] c : cases) {
                String name = (String) c[0];
                Ragdoll.Solid w = (Ragdoll.Solid) c[1];
                Ragdoll r = (Ragdoll) c[2];
                double[] len0 = lengths(r);
                double worst = 0, inside = 0;
                int sleptAt = -1;
                for (int t = 0; t < 200; t++) {
                    r.tick(w);
                    double[] l = lengths(r);
                    for (int k = 0; k < l.length; k++) worst = Math.max(worst, Math.abs(l[k] - len0[k]) / len0[k]);
                    for (int i = 0; i < Ragdoll.N; i++) if (w.at(r.p[i * 3], r.p[i * 3 + 1], r.p[i * 3 + 2])) inside++;
                    if (r.asleep && sleptAt < 0) sleptAt = t;
                    if (t == 0 || t == 4 || t == 8 || t == 14 || t == 30 || t == 199) frame(out, name + "_" + t, r, w == wall, w == step, w == kerb);
                }
                double top = -1e9;
                for (int i = 0; i < Ragdoll.N; i++) top = Math.max(top, r.p[i * 3 + 1]);
                double[] ce = r.centre();
                boolean good = worst < 0.08 && inside == 0 && sleptAt >= 0 && top < (w == step ? 1.2 : 0.75) * r.scale;
                ok &= good;
                System.out.printf(Locale.ROOT, "%-13s stretch max %4.1f%%  inside blocks %3.0f  asleep at %3d  highest point %.2f  torso at %.2f %.2f %.2f  %s%n",
                        name, worst * 100, inside, sleptAt, top, ce[0], ce[1], ce[2], good ? "OK" : "PROBLEM");
            }
            System.out.println(ok ? "RAGDOLL OK" : "RAGDOLL PROBLEM");
        }
    }

    /** Standing still, hit in the head from the front by the strongest bullet push (Ballistics: 0.32 blocks / tick). */
    static Ragdoll headshot() {
        Ragdoll r = Ragdoll.standing(0, 0, 0, 0, 1, 0, 0, 0, 0);
        r.push(0, 1.85, 0.1, 0, 0.02, -0.32);
        return r;
    }

    /** Lengths that must stay: torso sticks, head sticks, limbs from their joints (via the part matrices). */
    static double[] lengths(Ragdoll r) {
        int[][] pairs = {{0, 1}, {2, 3}, {0, 2}, {1, 3}, {0, 3}, {1, 2}, {4, 0}, {4, 1}};
        double[] l = new double[pairs.length + 4];
        for (int k = 0; k < pairs.length; k++) l[k] = d(r, pairs[k][0], pairs[k][1]);
        float[] m = r.parts(1, 0, 0, 0);
        int[][] limbs = {{2, Ragdoll.RHAND}, {3, Ragdoll.LHAND}, {4, Ragdoll.RFOOT}, {5, Ragdoll.LFOOT}};
        for (int k = 0; k < 4; k++) {
            int b = limbs[k][0] * 16, i = limbs[k][1];
            double dx = r.p[i * 3] - m[b + 12], dy = r.p[i * 3 + 1] - m[b + 13], dz = r.p[i * 3 + 2] - m[b + 14];
            l[pairs.length + k] = Math.sqrt(dx * dx + dy * dy + dz * dz);
        }
        return l;
    }

    static double d(Ragdoll r, int a, int b) {
        double dx = r.p[a * 3] - r.p[b * 3], dy = r.p[a * 3 + 1] - r.p[b * 3 + 1], dz = r.p[a * 3 + 2] - r.p[b * 3 + 2];
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    static final float[][][] BOXES = {
            {{-4, -8, -4, 8, 8, 8}}, {{-4, 0, -2, 8, 12, 4}}, {{-3, -2, -2, 4, 12, 4}}, {{-1, -2, -2, 4, 12, 4}}, {{-2, 0, -2, 4, 12, 4}}, {{-2, 0, -2, 4, 12, 4}}};
    static final String[] COL = {"196 150 110", "0 160 160", "196 140 106", "170 112 84", "60 60 160", "50 50 140"};

    static void frame(PrintWriter out, String name, Ragdoll r, boolean wall, boolean step, boolean kerb) {
        out.println("FRAME rag_" + name + " 0");
        double[] c = r.centre();
        Mat4 view = lookAt(new float[]{(float) c[0] + 2.2f, 1.6f, (float) c[2] + 2.4f}, new float[]{(float) c[0], 0.3f, (float) c[2]});
        // the ground (a slab under the scene), the wall / the step
        box(out, view, -64, -16, -64, 128, 16, 128, "100 140 70");
        if (wall) box(out, view, -48, 0, -48, 32, 32, 96, "130 130 130");
        if (step) box(out, view, -48, 0, -48, 96, 16, 57.6f, "130 130 130");
        if (kerb) box(out, view, -48, 0, -6.4f, 96, 8, 16, "130 130 130");
        float[] m = r.parts(1, 0, 0, 0);
        for (int pt = 0; pt < 6; pt++) {
            Mat4 pm = new Mat4();
            System.arraycopy(m, pt * 16, pm.m, 0, 16);
            Mat4 v = new Mat4().set(view).mul(pm);
            for (float[] b : BOXES[pt]) box(out, v, b[0], b[1], b[2], b[3], b[4], b[5], COL[pt]);
        }
    }

    static Mat4 lookAt(float[] e, float[] c) {
        float fx = c[0] - e[0], fy = c[1] - e[1], fz = c[2] - e[2];
        float fl = (float) Math.sqrt(fx * fx + fy * fy + fz * fz); fx /= fl; fy /= fl; fz /= fl;
        float rx = -fz, ry = 0, rz = fx;
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

    /** A box in pixels (as the player model's), written as its 8 corners in view space (preview.py "BOX"). */
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
