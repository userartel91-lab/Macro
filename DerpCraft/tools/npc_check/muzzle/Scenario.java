import derpcraft.game.Keys;
import derpcraft.game.Session;
import derpcraft.gun.GunAnimator;
import derpcraft.item.ItemStack;
import derpcraft.item.Items;
import derpcraft.util.Mat4;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Locale;

/**
 * Where the shot's world effects start (smoke, light, tracer: GunController.muzzleWorld) against where the first person
 * muzzle flash is drawn: both projected to the screen, hip / aim (right mouse) / second aim (Alt + right mouse), standing
 * and walking (view bob). Also the old way of computing it, for comparison, and a third person shot.
 * Each check prints "[smoke] CHECK <what> OK|FAIL ...".
 */
public class Scenario {
    static Session S() { return Session.current; }
    static int fails;

    static void check(String what, boolean ok, String info) {
        if (!ok) fails++;
        System.out.println("[smoke] CHECK " + what + " " + (ok ? "OK" : "FAIL") + " " + info);
    }

    static Object get(Object o, Class<?> c, String f) throws Exception { Field fl = c.getDeclaredField(f); fl.setAccessible(true); return fl.get(o); }

    /** Screen pixels between the drawn flash and the world point (new way, old way). */
    static double[] measure() throws Exception {
        Session s = S();
        GunAnimator.Pose ps = s.guns.anim.pose;
        int w = s.game.fbWidth, h = s.game.fbHeight;
        float aspect = w / (float) h;
        // the flash on the screen: the gun's own projection
        Mat4 gp = new Mat4().perspective((float) Math.toRadians(s.game.entities.gunFov - 14 * ps.aim), aspect, 0.02f, 10f);
        float[] c = new float[4];
        gp.transform(ps.muzzle[0], ps.muzzle[1], ps.muzzle[2], c);
        double fx = c[0] / c[3], fy = c[1] / c[3];
        // the world point with the world's camera of the frame
        Mat4 pv = (Mat4) get(s.npcs, derpcraft.npc.NpcManager.class, "lastProjView");
        double camX = (double) get(s.npcs, derpcraft.npc.NpcManager.class, "camX"), camY = (double) get(s.npcs, derpcraft.npc.NpcManager.class, "camY"), camZ = (double) get(s.npcs, derpcraft.npc.NpcManager.class, "camZ");
        Method mw = derpcraft.gun.GunController.class.getDeclaredMethod("muzzleWorld", double.class, double.class, double.class, float.class, float.class, float.class);
        mw.setAccessible(true);
        var p = s.player;
        double[] nw = (double[]) mw.invoke(s.guns, p.x, p.y + p.eyeHeight, p.z, p.yaw, p.pitch, s.guns.anim.aimAmount());
        // the old way: the tick's eye, yaw and pitch only
        double yaw = Math.toRadians(p.yaw), pitch = Math.toRadians(p.pitch);
        double ffx = -Math.sin(yaw) * Math.cos(pitch), ffy = -Math.sin(pitch), ffz = Math.cos(yaw) * Math.cos(pitch);
        double rx = -Math.cos(yaw), ry = 0, rz = -Math.sin(yaw);
        double ux = ry * ffz - rz * ffy, uy = rz * ffx - rx * ffz, uz = rx * ffy - ry * ffx;
        double k = Math.tan(Math.toRadians(s.guns.worldFov) / 2) / Math.tan(Math.toRadians(s.game.entities.gunFov - 14 * ps.aim) / 2);
        double x = ps.muzzle[0] * k, y = ps.muzzle[1] * k, z = -ps.muzzle[2];
        double[] old = {p.x + rx * x + ux * y + ffx * z, p.y + p.eyeHeight + ry * x + uy * y + ffy * z, p.z + rz * x + uz * y + ffz * z};
        double[] out = new double[2];
        double[][] pts = {nw, old};
        for (int i = 0; i < 2; i++) {
            pv.transform((float) (pts[i][0] - camX), (float) (pts[i][1] - camY), (float) (pts[i][2] - camZ), c);
            double sx = c[0] / c[3], sy = c[1] / c[3];
            out[i] = Math.hypot((sx - fx) * w / 2, (sy - fy) * h / 2);
        }
        return out;
    }

    static void at(String name, boolean walking) {
        try {
            double[] d = measure();
            System.out.printf(Locale.ROOT, "[smoke] %s: new %.2f px, old %.2f px (aim %.2f)%n", name, d[0], d[1], S().guns.anim.aimAmount());
            check(name + ": world effects start at the drawn flash", d[0] < 2, String.format(Locale.ROOT, "%.2f px off", d[0]));
        } catch (Exception e) { e.printStackTrace(); check(name, false, e.toString()); }
    }

    static void build() {
        SmokeMain.at(60, () -> {
            Session s = S();
            s.game.settings.chatMessages = false;
            s.gameMode = "creative";
            s.game.settings.viewBobbing = true;
            var gun = derpcraft.gun.Guns.get("fp9");
            if (gun == null) for (var g : derpcraft.gun.Guns.all()) { gun = g; break; }
            s.inv.set(s.inv.selected, new ItemStack(Items.byName(derpcraft.gun.Guns.PREFIX + gun.name), 1));
            s.player.yaw = 30; s.player.pitch = 5; s.view = 0;
        });
        String[] modes = {"hip", "aim", "aim2 (Alt)"};
        for (int m = 0; m < 3; m++) {
            final int mode = m;
            SmokeMain.wait(5, () -> { SmokeMain.hold(Keys.USE, mode >= 1); SmokeMain.hold(Keys.AIM2, mode == 2); });
            SmokeMain.wait(30, () -> at(modes[mode] + ", standing", false));
            SmokeMain.wait(1, () -> SmokeMain.hold(Keys.FORWARD, true));
            SmokeMain.wait(13, () -> at(modes[mode] + ", walking", true));
            SmokeMain.wait(1, () -> SmokeMain.hold(Keys.ATTACK, true));
            SmokeMain.wait(1, () -> { SmokeMain.hold(Keys.ATTACK, false); SmokeMain.shot("m_" + mode + "_shot"); });
            SmokeMain.wait(8, () -> SmokeMain.hold(Keys.FORWARD, false));
        }
        // third person: the light / smoke start at the gun drawn in the player's hands
        SmokeMain.wait(5, () -> { SmokeMain.hold(Keys.USE, false); SmokeMain.hold(Keys.AIM2, false); S().view = 1; });
        SmokeMain.wait(10, () -> {
            double[] m = S().game.entities.playerMuzzle;
            var p = S().player;
            double d = m == null ? -1 : Math.hypot(m[0] - p.x, m[2] - p.z);
            check("third person: muzzle at the gun in the hands", m != null && d > 0.3 && d < 2 && m[1] > p.y + 0.8 && m[1] < p.y + 1.8,
                    m == null ? "none" : String.format(Locale.ROOT, "%.2f from the body, %.2f up", d, m[1] - p.y));
            SmokeMain.hold(Keys.ATTACK, true);
        });
        SmokeMain.wait(1, () -> { SmokeMain.hold(Keys.ATTACK, false); SmokeMain.shot("m_3p_shot"); });
        SmokeMain.wait(5, () -> { System.out.println("[smoke] fails: " + fails); System.out.println("[smoke] DONE"); System.exit(0); });
    }
}
