package derpcraft.gun;

import java.nio.file.Path;
import java.util.Locale;

/**
 * Melee strikes: for each kind, the strike played at 240 frames a second: the fastest the muzzle and the butt move
 * (blocks / second) and when (should be at the impact: the strike speeds up into the hit), the largest jump between two
 * frames (a jump = a pose snapping), where the gun ends up after the strike (back at the hold), the palms on the gun
 * (unless an arm's own move takes it off) and the third person arms at the hit (stretch).
 */
public final class MeleeTest {
    public static void main(String[] args) {
        GunType t = new GunType(Path.of(args[0]).getFileName().toString().toLowerCase(Locale.ROOT), Path.of(args[0]));
        if (t.model() == null) { System.out.println("model: " + t.error); return; }
        float dt = 1 / 240f;
        float[] bb = t.bodyBounds();
        float[] butt = {(bb[0] + bb[3]) / 2, (bb[1] + bb[4]) / 2, bb[5]};
        boolean ok = true;
        for (int k = 0; k < GunType.MELEE.length; k++) {
            GunAnimator a = new GunAnimator();
            for (int i = 0; i < 240; i++) a.update(t, dt);
            float[] rest = point(a.update(t, dt), t.muzzlePoint);
            float len = GunAnimator.meleeLength(t, k), imp = GunAnimator.meleeImpact(t, k);
            float[] prevM = rest, prevB = point(a.pose, butt);
            float topM = 0, topB = 0, topAt = 0, jump = 0, palmOff = 0;
            a.melee = k;
            for (float time = 0; time <= len + 1e-4f; time += dt) {
                a.meleeTime = time;
                GunAnimator.Pose p = a.update(t, dt);
                float[] m = point(p, t.muzzlePoint), b = point(p, butt);
                float vm = dist(m, prevM) / dt, vb = dist(b, prevB) / dt;
                if (Math.max(vm, vb) > Math.max(topM, topB)) topAt = time;
                topM = Math.max(topM, vm); topB = Math.max(topB, vb);
                jump = Math.max(jump, Math.max(dist(m, prevM), dist(b, prevB)));
                prevM = m; prevB = b;
                // palms: the right on the grip unless its own move is set (same for the left on the guard)
                float[] g = point(p, t.rag), l = point(p, t.lag);
                if (zero(t.rArmMove, k)) palmOff = Math.max(palmOff, dist(g, p.rightHand));
                if (zero(t.lArmMove, k)) palmOff = Math.max(palmOff, dist(l, p.leftHand));
            }
            a.melee = -1;
            for (int i = 0; i < 240; i++) a.update(t, dt);
            float back = dist(point(a.update(t, dt), t.muzzlePoint), rest);
            GunHold3P h = new GunHold3P();
            h.compute(t, 0, 0, new float[]{-5, 2, 0}, new float[]{5, 2, 0}, 0, -1, false, k, imp);
            boolean bad = Math.abs(topAt - imp) > 0.03f || back > 0.01f || palmOff > 0.02f || h.rStretch >= GunHold3P.MAX_STRETCH || h.lStretch >= GunHold3P.MAX_STRETCH;
            ok &= !bad;
            System.out.printf(Locale.ROOT, "%-6s %.2fs (hit at %.2fs): fastest muzzle %.1f, butt %.1f blocks/s at %.3fs; largest frame jump %.4f; back at the hold %.4f off; palms off %.4f; 3rd person stretch R %.2f L %.2f%s%n",
                    GunType.MELEE[k], len, imp, topM, topB, topAt, jump, back, palmOff, h.rStretch, h.lStretch, bad ? "  PROBLEM" : "");
        }
        // the weights: wind-up 0 -> 1, hit 0 -> 1 at the impact, both 0 at the end
        float[] w0 = GunAnimator.meleeWeights(t, 0, 0), wi = GunAnimator.meleeWeights(t, 0, GunAnimator.meleeImpact(t, 0) - 1e-4f);
        System.out.printf(Locale.ROOT, "weights at the start %s, just before the hit %.3f / %.3f, after the end %s%n",
                java.util.Arrays.toString(w0), wi[0], wi[1], GunAnimator.meleeWeights(t, 0, GunAnimator.meleeLength(t, 0) + 0.01f));
        System.out.println(ok ? "melee OK" : "melee PROBLEM");
    }

    static boolean zero(float[][] v, int k) {
        for (int ph = 0; ph < 2; ph++) for (float x : v[GunType.meleePose(k, ph == 1)]) if (x != 0) return false;
        return true;
    }

    static float[] point(GunAnimator.Pose p, float[] pt) { float[] o = new float[4]; p.gun.transform(pt[0], pt[1], pt[2], o); return o; }

    static float dist(float[] a, float[] b) { float x = a[0] - b[0], y = a[1] - b[1], z = a[2] - b[2]; return (float) Math.sqrt(x * x + y * y + z * z); }
}
