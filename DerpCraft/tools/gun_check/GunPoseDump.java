package derpcraft.gun;

import derpcraft.model.Model3D;
import derpcraft.util.Mat4;

import java.io.PrintWriter;
import java.nio.file.Path;
import java.util.Locale;

/**
 * Dumps first person poses of a gun (view space triangles of the gun parts and the IK arm joints) for
 * tools/gun_check/preview.py, which draws them to PNG frames: a look at the animation without the game.
 * Usage: java derpcraft.gun.GunPoseDump &lt;gun folder&gt; &lt;out.txt&gt;
 */
public final class GunPoseDump {
    public static void main(String[] args) throws Exception {
        Path dir = Path.of(args[0]);
        String id = dir.getFileName().toString().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_]", "_");
        GunType t = new GunType(id, dir);
        if (t.model() == null) { System.out.println("model: " + t.error); return; }
        try (PrintWriter out = new PrintWriter(args[1])) {
            GunAnimator a = new GunAnimator();
            float dt = 1 / 60f;
            a.equip();
            run(a, t, 90);
            dump(out, "hip", a.update(t, dt), t);
            a.equip();
            run(a, t, 9);
            dump(out, "equip", a.update(t, dt), t);
            run(a, t, 60);
            a.shot(false, t); run(a, t, 3);
            dump(out, "shot", a.update(t, dt), t);
            run(a, t, 60);
            a.aiming = true; run(a, t, 60);
            dump(out, "aim", a.update(t, dt), t);
            a.shot(true, t); run(a, t, 3);
            dump(out, "aim_shot", a.update(t, dt), t);
            a.aim2 = true; run(a, t, 60);
            dump(out, "aim2", a.update(t, dt), t);
            a.aim2 = false; run(a, t, 60);
            a.aiming = false; a.sprinting = true; a.speed = 5.6f; a.forward = 5.6f; run(a, t, 60);
            dump(out, "sprint", a.update(t, dt), t);
            a.sprinting = false; a.speed = 4.3f; a.forward = 0; a.strafe = 4.3f; run(a, t, 40);
            dump(out, "strafe", a.update(t, dt), t);
            a.speed = 0; a.strafe = 0; run(a, t, 60);
            // the bolt at its back limit (boltBack), and the slide locked back after the last shot (slide lock)
            a.boltHold = 1; run(a, t, 2);
            dump(out, "bolt_back", a.update(t, dt), t);
            a.boltHold = -1;
            a.boltLocked = true; a.shot(false, t); run(a, t, 30);
            dump(out, "locked", a.update(t, dt), t);
            a.boltLocked = false; run(a, t, 30);
            for (boolean empty : new boolean[]{false, true}) {
                a.reloading = true; a.reloadEmpty = empty;
                float[] at = empty ? new float[]{0.1f, 0.21f, 0.3f, 0.43f, 0.5f, 0.6f, 0.75f, 0.8f, 0.86f, 0.9f, 0.95f} : new float[]{0.15f, 0.3f, 0.38f, 0.42f, 0.45f, 0.48f, 0.52f, 0.57f, 0.62f, 0.72f, 0.85f};
                float time = empty ? t.reloadEmptyTime : t.reloadTime, s = 0;
                for (float r : at) {
                    while (s / time < r) { a.reload = s / time; a.update(t, dt); s += dt; }
                    dump(out, (empty ? "empty_" : "reload_") + Math.round(r * 100), a.update(t, dt), t);
                }
                a.reloading = false; run(a, t, 60);
            }
            // melee: each kind at the end of its wind-up, half way through the strike, at the hit, on the way back
            for (int k = 0; k < GunType.MELEE.length; k++) {
                float[] m = t.meleeTime[k];
                float imp = GunAnimator.meleeImpact(t, k);
                float[] at = {m[0], m[0] + Math.max(0.02f, m[1]) * 0.6f, imp, imp + m[2] + m[3] * 0.45f};
                String[] nm = {"wind", "strike", "hit", "back"};
                a.melee = k;
                float s = 0;
                for (int i = 0; i < at.length; i++) {
                    while (s < at[i] - 1e-4f) { a.meleeTime = s; a.update(t, dt); s += dt; }
                    a.meleeTime = at[i];
                    dump(out, GunType.MELEE[k] + "_" + nm[i], a.update(t, dt), t);
                }
                a.melee = -1; run(a, t, 60);
            }
        }
    }

    static void run(GunAnimator a, GunType t, int frames) { for (int i = 0; i < frames; i++) a.update(t, 1 / 60f); }

    static void dump(PrintWriter out, String name, GunAnimator.Pose p, GunType t) {
        out.println("FRAME " + name + " " + p.aim);
        Mat4 magM = new Mat4().set(p.gun).mul(p.mag);
        Mat4 slideM = new Mat4().set(p.gun).translate(t.boltDir[0] * p.slide, t.boltDir[1] * p.slide, t.boltDir[2] * p.slide);
        for (Model3D.Bone b : t.body) tris(out, p.gun, b, "TRI");
        if (t.mag != null && p.magMode == 0) tris(out, magM, t.mag, MARK_PARTS ? "TRIM" : "TRI");
        if (t.mag != null && p.magMode == 2) tris(out, p.magView, t.mag, MARK_PARTS ? "TRIM" : "TRI");
        if (t.mag != null && p.dropMagShown) tris(out, p.dropMag, t.mag, MARK_PARTS ? "TRIM" : "TRI");
        if (t.slide != null) tris(out, slideM, t.slide, MARK_PARTS ? "TRIS" : "TRI");
        arm(out, p.rightArm);
        arm(out, p.leftArm);
    }

    /** The 8 corners of the arm box (x -2..2, y 0..12, z -2..2 pixels; the matrix takes pixels / 16). */
    static void arm(PrintWriter out, Mat4 m) {
        StringBuilder sb = new StringBuilder("ARMBOX");
        float[] o = new float[4];
        for (int i = 0; i < 8; i++) {
            m.transform(((i & 1) == 0 ? -2 : 2) / 16f, ((i & 4) == 0 ? 0 : 12) / 16f, ((i & 2) == 0 ? -2 : 2) / 16f, o);
            sb.append(String.format(Locale.ROOT, " %f %f %f", o[0], o[1], o[2]));
        }
        out.println(sb);
    }

    static String v(float[] a) { return String.format(Locale.ROOT, "%f %f %f", a[0], a[1], a[2]); }

    /** Magazine red, bolt yellow in the pictures (env GUN_PREVIEW_PARTS=1). */
    static final boolean MARK_PARTS = System.getenv("GUN_PREVIEW_PARTS") != null;

    static void tris(PrintWriter out, Mat4 m, Model3D.Bone b, String tag) {
        float[] o = new float[4];
        for (Model3D.Group g : b.groups)
            for (int i = 0; i < g.tris; i++) {
                StringBuilder sb = new StringBuilder(tag);
                for (int k = 0; k < 3; k++) {
                    int q = i * 15 + k * 5;
                    m.transform(g.v[q], g.v[q + 1], g.v[q + 2], o);
                    sb.append(String.format(Locale.ROOT, " %f %f %f %f %f", o[0], o[1], o[2], g.v[q + 3], g.v[q + 4]));
                }
                out.println(sb);
            }
    }
}
