package derpcraft.gun;

import derpcraft.util.Mat4;

import java.io.PrintWriter;
import java.util.Locale;

/**
 * Dumps first person fist fighting frames (Alt + Q) for tools/gun_check/preview.py: the guard, coming up, walking,
 * sprinting, and every stage of a left straight, a right straight and a right hook (a red mark on the knuckles shows
 * how the fist turns). Usage: java derpcraft.gun.FistPoseDump &lt;out.txt&gt;  (see fist_preview.sh)
 */
public final class FistPoseDump {
    public static void main(String[] args) throws Exception {
        try (PrintWriter out = new PrintWriter(args[0])) {
            FistAnimator a = new FistAnimator();
            float dt = 1 / 60f;
            a.active = true;
            run(a, 3);
            dump(out, "up_05", a.update(dt));
            run(a, 60);
            dump(out, "guard", a.update(dt));
            a.speed = 4.3f; a.forward = 4.3f; run(a, 20);
            dump(out, "walk", a.update(dt));
            a.sprinting = true; a.speed = 5.6f; a.forward = 5.6f; run(a, 40);
            dump(out, "sprint", a.update(dt));
            a.sprinting = false; a.speed = 0; a.forward = 0; run(a, 60);
            // left straight, right straight, then right again soon after = a hook
            String[] names = {"jabL", "crossR", "hookR"};
            boolean[] hands = {true, false, false};
            for (int k = 0; k < 3; k++) {
                a.punch(hands[k]);
                float t = 0;
                for (float at : new float[]{0.05f, 0.10f, 0.135f, 0.17f, 0.26f, 0.36f}) {
                    while (t < at) { a.update(dt); t += dt; }
                    if (at == 0.135f) a.hit(hands[k], true);
                    dump(out, names[k] + "_" + Math.round(at * 1000), a.update(0));
                }
                run(a, k == 1 ? 2 : 40);                  // the hook comes right after the cross
            }
            a.active = false; run(a, 6);
            dump(out, "down", a.update(dt));
        }
    }

    static void run(FistAnimator a, int frames) { for (int i = 0; i < frames; i++) a.update(1 / 60f); }

    static void dump(PrintWriter out, String name, FistAnimator.Pose p) {
        out.println("FRAME " + name + " 0");
        GunPoseDump.arm(out, p.rightArm);
        GunPoseDump.arm(out, p.leftArm);
        knuckles(out, p.rightArm);
        knuckles(out, p.leftArm);
    }

    /** A red mark on one side of the fist (the knuckles: arm box face x = +2, pixels 9..12). */
    static void knuckles(PrintWriter out, Mat4 m) {
        StringBuilder sb = new StringBuilder("BOX 220 40 40");
        float[] o = new float[4];
        for (int i = 0; i < 8; i++) {
            m.transform(((i & 1) == 0 ? 2.0f : 2.4f) / 16f, ((i & 4) == 0 ? 9 : 12) / 16f, ((i & 2) == 0 ? -1.2f : 1.2f) / 16f, o);
            sb.append(String.format(Locale.ROOT, " %f %f %f", o[0], o[1], o[2]));
        }
        out.println(sb);
    }
}
