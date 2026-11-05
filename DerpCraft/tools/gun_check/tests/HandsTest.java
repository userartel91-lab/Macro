package derpcraft.gun;
import java.nio.file.*;
/**
 * The palms stay on their points in every pose whatever the pose's All / Gun / Arms / arm turn values are: for each pose
 * (held like the gun editor holds it) and each kind of change, how far each palm is from the drawn gun point it holds
 * (grip / guard, the bolt in the bolt pose, the magazine in the reload pose). All should be 0.000 (only the per arm
 * "move", which moves a palm off its point on purpose, is left out).
 */
public class HandsTest {
    static float d(float[] a, float[] b) { float x = a[0] - b[0], y = a[1] - b[1], z = a[2] - b[2]; return (float) Math.sqrt(x * x + y * y + z * z); }
    static GunType load(String a) { Path p = Path.of(a); GunType t = new GunType(p.getFileName().toString().toLowerCase(java.util.Locale.ROOT), p); t.model(); return t; }
    static float run(GunType t, String pose) {
        GunAnimator g = new GunAnimator(); g.equip();
        for (int i = 0; i < 240; i++) {
            switch (pose) {
                case "bolt" -> { g.reloading = true; g.reloadEmpty = true; g.reload = GunAnimator.EMPTY_MAG_END + (1 - GunAnimator.EMPTY_MAG_END) * 0.3f; }
                case "reload" -> { g.reloading = true; g.reloadEmpty = false; g.reload = 0.2f; }
                case "aim" -> g.aiming = true;
                case "aim2" -> { g.aiming = true; g.aim2 = true; }
                case "sprint" -> g.sprinting = true;
            }
            g.update(t, 1 / 60f);
        }
        GunAnimator.Pose p = g.pose;
        float[] grip = new float[4], guard = new float[4], bolt = new float[4], mag = new float[4], tmp = new float[4];
        p.gun.transform(t.rag[0], t.rag[1], t.rag[2], grip);
        p.gun.transform(t.lag[0], t.lag[1], t.lag[2], guard);
        float[] bp = t.boltPoint(t.las, p.slide, new float[3]);
        p.gun.transform(bp[0], bp[1], bp[2], bolt);
        p.mag.transform(t.ram[0], t.ram[1], t.ram[2], tmp); p.gun.transform(tmp[0], tmp[1], tmp[2], mag);
        float[] rT = grip, lT = guard;
        if (pose.equals("bolt")) { if (t.boltRight) rT = bolt; else lT = bolt; }
        if (pose.equals("reload") && !t.pistol) { if (t.magLeft) lT = mag; else rT = mag; }
        if (pose.equals("reload") && t.pistol) { if (t.magLeft) lT = null; else rT = null; }     // a pistol's hand is at the pocket
        return Math.max(rT == null ? 0 : d(p.rightHand, rT), lT == null ? 0 : d(p.leftHand, lT));
    }
    public static void main(String[] a) {
        String[] changes = {"none", "all turn", "gun move+turn", "arms move", "arm turns"};
        float worst = 0;
        for (int pi = 0; pi < GunType.POSES.length; pi++) {
            String pose = GunType.POSES[pi];
            StringBuilder sb = new StringBuilder(String.format("%-7s", pose));
            for (int c = 0; c < changes.length; c++) {
                GunType t = load(a[0]);
                switch (c) {
                    case 1 -> { t.poseTurn[pi][0] += 20; t.poseTurn[pi][2] += 15; }
                    case 2 -> { t.gunMove[pi][1] += 0.05f; t.gunTurn[pi][0] += 15; t.gunTurn[pi][2] += 30; }
                    case 3 -> t.armsMove[pi][0] += 0.08f;
                    case 4 -> { t.rArmTurn[pi][0] += 30; t.lArmTurn[pi][1] += 30; }
                }
                float off = run(t, pose);
                worst = Math.max(worst, off);
                sb.append(String.format(" | %s %.3f", changes[c], off));
            }
            System.out.println(sb);
        }
        System.out.printf("palms off their points at most %.3f blocks%s%n", worst, worst > 0.04f ? "  PROBLEM" : "  OK");
    }
}
