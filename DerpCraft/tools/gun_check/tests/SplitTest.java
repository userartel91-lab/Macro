package derpcraft.gun;
import java.nio.file.*;
/** The per pose groups: "gun" moves the gun and the hands ride on it, "arms" swings the arms (the hands stay), "all" moves everything. */
public class SplitTest {
    static float[] pt(derpcraft.util.Mat4 m, float[] p) { float[] o = new float[4]; m.transform(p[0], p[1], p[2], o); return o; }
    public static void main(String[] a) throws Exception {
        String[] names = {"gun (hands ride on it)", "arms (swing, hands stay)", "all"};
        for (int mode = 0; mode < 3; mode++) {
            GunType base = new GunType(Path.of(a[0]).getFileName().toString().toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9_]", "_"), Path.of(a[0])); base.model();
            GunType ch = new GunType(Path.of(a[0]).getFileName().toString().toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9_]", "_"), Path.of(a[0])); ch.model();
            if (mode == 0) { ch.gunMove[0][1] = 0.05f; ch.gunTurn[0][0] = 10; }
            if (mode == 1) ch.armsMove[0][0] = 0.05f;
            if (mode == 2) ch.poseMove[0][1] -= 0.05f;
            GunAnimator x = new GunAnimator(), y = new GunAnimator(); x.equip(); y.equip();
            for (int i = 0; i < 200; i++) { x.update(base, 1 / 60f); y.update(ch, 1 / 60f); }
            float[] g = pt(y.pose.gun, ch.rag), l = pt(y.pose.gun, ch.lag);
            System.out.printf("%s: muzzle moved %.3f, right hand moved %.3f, left hand moved %.3f, right arm turned %.3f; palms off grip %.3f / guard %.3f%n", names[mode],
                GunCheck.dist(pt(x.pose.gun, base.muzzlePoint), pt(y.pose.gun, ch.muzzlePoint)),
                GunCheck.dist(x.pose.rightHand, y.pose.rightHand), GunCheck.dist(x.pose.leftHand, y.pose.leftHand),
                GunCheck.dist(pt(x.pose.rightArm, new float[]{0, 0, 0}), pt(y.pose.rightArm, new float[]{0, 0, 0})),
                GunCheck.dist(y.pose.rightHand, g), GunCheck.dist(y.pose.leftHand, l));
        }
    }
}
