package derpcraft.gun;
/**
 * Fist fighting (Alt + Q) without the game: the arms keep their size in every frame (rigid Minecraft arms), each punch
 * reports one hit at full reach, the same hand again soon after throws a hook, a busy hand cannot punch, the fists come
 * up and go away, the fist reaches near the crosshair.
 */
public class FistTest {
    static float len(derpcraft.util.Mat4 m) { float[] a = new float[4], b = new float[4]; m.transform(0, 0, 0, a); m.transform(0, 12 / 16f, 0, b); return GunCheck.dist(a, b); }
    public static void main(String[] args) {
        FistAnimator a = new FistAnimator();
        float dt = 1 / 60f, minL = 9, maxL = 0;
        a.active = true;
        for (int i = 0; i < 60; i++) a.update(dt);
        System.out.println("fists up: " + a.shown() + ", ready L " + a.ready(true) + " R " + a.ready(false));
        int hits = 0, punches = 0;
        String types = "";
        float best = 9;
        for (int k = 0; k < 6; k++) {
            boolean left = k % 3 == 0;
            if (a.punch(left)) { punches++; types += (left ? "L" : "R") + (a.type(left) == FistAnimator.HOOK ? "hook " : "straight "); }
            if (!left) System.out.print("");
            boolean again = a.punch(left);                 // the same hand at once: busy
            if (again) System.out.println("PROBLEM a busy hand punched again");
            for (int i = 0; i < 16; i++) {
                FistAnimator.Pose p = a.update(dt);
                int im = a.takeImpacts();
                if (im != 0) { hits += Integer.bitCount(im); float[] h = left ? p.leftHand : p.rightHand; best = Math.min(best, (float) Math.hypot(h[0] / -h[2], h[1] / -h[2])); }
                for (var m : new derpcraft.util.Mat4[]{p.rightArm, p.leftArm}) { float l = len(m); minL = Math.min(minL, l); maxL = Math.max(maxL, l); }
            }
        }
        System.out.println("punches " + punches + ", hits " + hits + (hits == punches ? " (one each)" : "  PROBLEM"));
        System.out.println("combo: " + types.trim());
        System.out.printf("arm length %.3f .. %.3f%s%n", minL, maxL, maxL - minL > 1e-3 ? "  PROBLEM (stretched)" : " (rigid)");
        System.out.printf("closest fist to the crosshair at a hit: %.2f (screen units, 0 = centre)%n", best);
        a.active = false;
        for (int i = 0; i < 30; i++) a.update(dt);
        System.out.println("fists down: shown " + a.shown());
    }
}
