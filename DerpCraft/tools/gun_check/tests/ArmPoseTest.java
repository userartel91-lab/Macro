package derpcraft.gun;
import java.nio.file.*;
public class ArmPoseTest {
    static float diff(derpcraft.util.Mat4 a, derpcraft.util.Mat4 b) { float d = 0; for (int i = 0; i < 16; i++) d = Math.max(d, Math.abs(a.m[i] - b.m[i])); return d; }
    public static void main(String[] a) throws Exception {
        GunType base = new GunType(Path.of(a[0]).getFileName().toString().toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9_]", "_"), Path.of(a[0])); base.model();
        GunType ch = new GunType(Path.of(a[0]).getFileName().toString().toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9_]", "_"), Path.of(a[0])); ch.model();
        ch.rArmTurn[4][0] = 40;   // bolt pose: right arm yawed 40 degrees
        for (String what : new String[]{"hip", "bolt"}) {
            GunAnimator x = new GunAnimator(), y = new GunAnimator(); x.equip(); y.equip();
            if (what.equals("bolt")) { for (GunAnimator g : new GunAnimator[]{x, y}) { g.reloading = true; g.reloadEmpty = true; g.reload = 0.85f; } }
            for (int i = 0; i < 200; i++) { x.update(base, 1 / 60f); y.update(ch, 1 / 60f); }
            System.out.printf("%s: right arm changed %.3f, left arm changed %.3f, gun changed %.3f%n", what,
                diff(x.pose.rightArm, y.pose.rightArm), diff(x.pose.leftArm, y.pose.leftArm), diff(x.pose.gun, y.pose.gun));
        }
    }
}
