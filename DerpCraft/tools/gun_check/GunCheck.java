package derpcraft.gun;

import derpcraft.model.Model3D;

import java.nio.file.Path;
import java.util.Locale;

/**
 * Offline check of gun folders (game/guns/&lt;name&gt;) with the game's own code: model parts, hand points, sounds,
 * settings, and a run of the first person animation (shots, both reloads) checking that the hands stay on the
 * grip / handguard / magazine / bolt. No LWJGL needed. Run with tools/gun_check/check.sh.
 */
public final class GunCheck {
    static int problems;

    public static void main(String[] args) {
        for (String a : args) check(Path.of(a));
        System.out.println(problems == 0 ? "\nALL OK" : "\nPROBLEMS: " + problems);
    }

    static void bad(String s) { problems++; System.out.println("  PROBLEM " + s); }

    static String v(float[] p) { return p == null ? "-" : String.format(Locale.ROOT, "[%.3f, %.3f, %.3f]", p[0], p[1], p[2]); }

    static void check(Path dir) {
        String id = dir.getFileName().toString().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_]", "_");
        System.out.println("== " + dir.getFileName() + " (id " + id + ")");
        GunType t = new GunType(id, dir);
        Model3D m = t.model();
        if (m == null) { bad("model: " + t.error); return; }
        System.out.printf(Locale.ROOT, "  %s: %.0f rpm %s, %d rounds, damage %.1f, range %.0f, reload %.2f s / empty %.2f s%n",
                t.displayName, t.rpm, t.auto ? "auto" : "semi", t.magazine, t.damage, t.range, t.reloadTime, t.reloadEmptyTime);
        System.out.printf(Locale.ROOT, "  speed with gun x%.2f, aiming x%.2f, running x%.2f; scale 1st %.2f / 3rd %.2f%n",
                t.moveSpeed, t.aimMoveSpeed, t.sprintSpeed, t.firstPersonScale, t.thirdPersonScale);
        System.out.print("  objects:");
        for (Model3D.Bone b : m.bones) System.out.print(" " + b.name + "(" + b.triangles() + ")");
        System.out.println();
        System.out.print("  drawn: body =");
        for (Model3D.Bone b : t.body) {
            System.out.print(" " + b.name);
            if (GunType.HELPERS.contains(b.name.toLowerCase(Locale.ROOT))) bad("helper object '" + b.name + "' would be drawn");
        }
        System.out.println(", mag = " + (t.mag == null ? "-" : t.mag.name) + ", slide = " + (t.slide == null ? "-" : t.slide.name));
        if (t.mag == null) System.out.println("  WARNING no 'mag' object: the magazine does not come out on reload");
        if (t.slide == null) System.out.println("  WARNING no 'slide' object: the bolt does not move");
        System.out.println("  points: rag " + v(t.rag) + " lag " + v(t.lag) + " las " + v(t.las) + " ram " + v(t.ram));
        System.out.println("          sight " + v(t.sightPoint) + " muzzle " + v(t.muzzlePoint) + " magPivot " + v(t.magPivot) + " eject " + v(t.ejectPoint));
        for (String w : t.warnings) System.out.println("  WARNING " + w);
        System.out.println("  shot sounds: " + t.shootSounds.size() + (t.shootSounds.isEmpty() ? " (silent until shoot1..3.ogg are added)" : " " + t.shootSounds));
        System.out.println("  other sounds: " + (t.sounds.isEmpty() ? "none" : t.sounds.keySet()));
        System.out.println("  sound variants: " + (t.variants.isEmpty() ? "none (unattach1-5, attach1-5, slide1-2)" : t.variants.toString()));
        if (t.mag != null) {
            float[] mb = GunType.bounds(t.mag);
            if (!inside(t.ram, mb, 0.15f)) System.out.println("  WARNING 'ram' is not on the magazine " + v(t.ram));
        }
        if (t.slide != null) {
            float[] sb = GunType.bounds(t.slide);
            if (!inside(t.las, sb, 0.15f)) System.out.println("  WARNING 'las' is not at the bolt " + v(t.las));
            float bodyCx = (t.rag[0] + t.lag[0]) / 2;
            System.out.println("  bolt side: " + (((sb[0] + sb[3]) / 2) < bodyCx ? "left" : "right") + " of the gun (pulled by the " + (t.boltRight ? "right" : "left") + " hand; magazine: " + (t.magLeft ? "left" : "right") + " hand)");
            float[] bb = t.bodyBounds();
            System.out.printf(Locale.ROOT, "  bolt limits: boltFront %s -> boltBack %s, travel %.3f; all the way back its back end is at z %.3f (the gun's body ends at %.3f)%n",
                    v(t.boltFront), v(t.boltBack), t.boltTravel, sb[5] + t.boltDir[2] * t.boltTravel, bb[5]);
            if (t.boltTravel < 0.01f) System.out.println("  WARNING the bolt cannot move (boltFront = boltBack)");
        }
        System.out.println("  reload style: " + (t.pistol ? String.format(Locale.ROOT, "pistol (magazine axis %s, length %.3f)", v(t.magAxis), t.magLength) : "rifle")
                + ", bolt per shot " + t.boltShot + (t.slideLock ? ", slide locks back when empty" : ""));
        animate(t);
        hold3p(t);
    }

    /** Third person: the middle of each hand on its point (grip, handguard) when looking straight, up and down. */
    static void hold3p(GunType t) {
        GunHold3P h = new GunHold3P();
        float[] rP = {-5, 2, 0}, lP = {5, 2, 0};
        float worst = 0;
        for (float pitch : new float[]{-60, 0, 60})
            for (float yaw : new float[]{-15, 0, 15}) {
                h.compute(t, pitch, yaw, rP, lP);
                float[] g = new float[4], l = new float[4];
                h.gun.transform(t.rag[0], t.rag[1], t.rag[2], g);
                h.gun.transform(t.lag[0], t.lag[1], t.lag[2], l);
                worst = Math.max(worst, Math.max(palm(rP, h.rArm, h.rStretch, -1, g), palm(lP, h.lArm, h.lStretch, 1, l)));
            }
        System.out.printf(Locale.ROOT, "  third person: hands off their points by at most %.3f blocks (%.1f px)%n", worst, worst * 16);
        if (worst > 0.05f) bad("third person hands do not hold the gun");
    }

    static float palm(float[] pivot, float[] ang, float stretch, float side, float[] target) {
        derpcraft.util.Mat4 m = new derpcraft.util.Mat4().translate(pivot[0] / 16f, pivot[1] / 16f, pivot[2] / 16f);
        m.rotate(ang[1], 0, 1, 0); m.rotate(ang[0], 1, 0, 0); m.scale(1, stretch, 1);
        float[] o = new float[4];
        m.transform(side / 16f, GunHold3P.PALM / 16f, 0, o);
        return dist(o, target);
    }

    static boolean inside(float[] p, float[] b, float pad) {
        for (int k = 0; k < 3; k++) if (p[k] < b[k] - pad || p[k] > b[k + 3] + pad) return false;
        return true;
    }

    /** Runs the animation: equip, a burst, walk, sprint, aim, both reloads; checks the IK arms and the hands on the parts. */
    static void animate(GunType t) {
        GunAnimator a = new GunAnimator();
        a.equip();
        float dt = 1 / 60f;
        for (int i = 0; i < 60; i++) check(a.update(t, dt), "equip");
        GunAnimator.Pose p = a.update(t, dt);
        System.out.printf(Locale.ROOT, "  hip: arms %s / %s%n", arm(p.rightArm), arm(p.leftArm));
        float[] g = new float[4];
        p.gun.transform(t.rag[0], t.rag[1], t.rag[2], g);
        if (dist(p.rightHand, g) > 0.01f) bad("right hand not on the grip at the hip (" + dist(p.rightHand, g) + ")");
        p.gun.transform(t.lag[0], t.lag[1], t.lag[2], g);
        if (dist(p.leftHand, g) > 0.01f) bad("left hand not on the handguard at the hip (" + dist(p.leftHand, g) + ")");
        for (int i = 0; i < 10; i++) { a.shot(false, t); for (int f = 0; f < 5; f++) check(a.update(t, dt), "burst"); }
        System.out.println("  casings in the air after a burst: " + a.pose.casings.size());
        a.speed = 4.3f; a.forward = 4.3f;
        for (int i = 0; i < 60; i++) check(a.update(t, dt), "walk");
        a.strafe = 4.3f; a.forward = 0;
        for (int i = 0; i < 60; i++) check(a.update(t, dt), "strafe");
        a.sprinting = true; a.speed = 5.6f; a.forward = 5.6f; a.strafe = 0;
        for (int i = 0; i < 60; i++) check(a.update(t, dt), "sprint");
        a.sprinting = false; a.speed = 0; a.forward = 0;
        a.aiming = true;
        for (int i = 0; i < 90; i++) check(a.update(t, dt), "aim");
        p = a.update(t, dt);
        float[] sight = new float[4];
        p.gun.transform(t.sightPoint[0], t.sightPoint[1], t.sightPoint[2], sight);
        System.out.printf(Locale.ROOT, "  aiming: sight at screen %.3f, %.3f (0, 0 = centre), %.2f in front%n",
                sight[0] / -sight[2], sight[1] / -sight[2], -sight[2]);
        if (Math.abs(sight[0] / -sight[2]) > 0.02f || Math.abs(sight[1] / -sight[2]) > 0.02f) bad("sight is off the screen centre when aiming");
        // the second aim (Alt): its own sight on the centre, turned around it
        a.aim2 = true;
        for (int i = 0; i < 90; i++) check(a.update(t, dt), "aim2");
        p = a.update(t, dt);
        p.gun.transform(t.sight2Point[0], t.sight2Point[1], t.sight2Point[2], sight);
        float roll = (float) Math.toDegrees(Math.atan2(p.gun.m[1], p.gun.m[0]));
        System.out.printf(Locale.ROOT, "  second aim: sight2 at screen %.3f, %.3f, gun rolled %.0f deg%n", sight[0] / -sight[2], sight[1] / -sight[2], roll);
        if (Math.abs(sight[0] / -sight[2]) > 0.02f || Math.abs(sight[1] / -sight[2]) > 0.02f) bad("sight2 is off the screen centre in the second aim");
        a.aim2 = false;
        for (int i = 0; i < 60; i++) a.update(t, dt);
        a.aiming = false;
        for (int i = 0; i < 60; i++) a.update(t, dt);
        for (boolean empty : new boolean[]{false, true}) {
            a.reloading = true; a.reloadEmpty = empty;
            float time = empty ? t.reloadEmptyTime : t.reloadTime;
            float maxR = 0, maxL = 0, magOut = 0, slideMax = 0, maxRot = 0;
            boolean sawDrop = false, sawPocket = false, slidePast = false;
            for (float s = 0; s <= time; s += dt) {
                a.reload = s / time;
                p = a.update(t, dt);
                check(p, "reload");
                float[] mp = new float[4], mag = new float[4], bolt = new float[4];
                p.mag.transform(t.ram[0], t.ram[1], t.ram[2], mp);
                if (p.magMode == 0) magOut = t.pistol ? Math.min(magOut, -dist(mp, t.ram)) : Math.min(magOut, mp[1] - t.ram[1]);
                float[] pv = new float[4];
                p.mag.transform(t.magPivot[0], t.magPivot[1] - 1, t.magPivot[2], pv);
                maxRot = Math.max(maxRot, (float) Math.toDegrees(Math.atan2(-(pv[2] - t.magPivot[2] - p.mag.m[14]), -(pv[1] - t.magPivot[1] - p.mag.m[13]))));
                slideMax = Math.max(slideMax, p.slide);
                if (p.slide > t.boltTravel + 1e-4f) slidePast = true;
                if (p.magMode == 2) p.magView.transform(t.ram[0], t.ram[1], t.ram[2], mag);
                else p.gun.transform(mp[0], mp[1], mp[2], mag);
                float[] bq = t.boltPoint(t.las, p.slide, new float[3]); p.gun.transform(bq[0], bq[1], bq[2], bolt);
                float q = a.reload / (empty ? GunAnimator.EMPTY_MAG_END : 1f);
                // the hand rides with the magazine while it is in view (it goes off screen below that)
                float[] magHand = t.magLeft ? p.leftHand : p.rightHand, boltHand = t.boltRight ? p.rightHand : p.leftHand;
                // (a pistol's hand does not hold the old magazine: it drops free; the new one is in the hand from the pocket)
                if (q > (t.pistol ? GunAnimator.POCKET : 0.22f) && q < 0.78f && p.magMode != 1) maxR = Math.max(maxR, dist(magHand, mag));
                if (p.magMode == 1) sawDrop = true;
                if (p.magMode == 2) sawPocket = true;
                float b = (a.reload - GunAnimator.EMPTY_MAG_END) / (1 - GunAnimator.EMPTY_MAG_END);
                if (empty && b > 0.28f && b < 0.6f) maxL = Math.max(maxL, dist(boltHand, bolt));
            }
            String mh = t.magLeft ? "left" : "right", bh = t.boltRight ? "right" : "left";
            System.out.printf(Locale.ROOT, "  %s reload: magazine drops %.2f and rocks %.0f deg, %s hand off the magazine by %.3f%s%n",
                    empty ? "empty" : "tactical", -magOut, maxRot, mh, maxR, empty ? String.format(Locale.ROOT, ", bolt pulled %.2f, %s hand off the bolt by %.3f", slideMax, bh, maxL) : "");
            if (magOut > (t.pistol ? -0.4f * t.magLength : -0.3f)) bad("the magazine does not come out");
            if (slidePast) bad("the bolt goes past its back limit (boltBack)");
            if (empty && t.slide != null && slideMax < 0.6f * t.boltTravel) bad("the bolt is not pulled back far enough");
            if (!sawDrop || !sawPocket) bad("the old magazine is not let go / the new one does not come from the pocket");
            if (maxR > 0.07f) bad("the magazine hand leaves the magazine during the reload");
            if (empty && maxL > 0.07f) bad("the bolt hand leaves the bolt");
            if (empty && slideMax < Math.min(0.05f, 0.6f * t.boltTravel)) bad("the bolt is not pulled");
            a.reloading = false;
            for (int i = 0; i < 30; i++) check(a.update(t, dt), "after reload");
        }
    }

    /** Length and width of an arm box in view units (they must not change: no stretching). */
    static String arm(derpcraft.util.Mat4 m) {
        float[] a = new float[4], b = new float[4], c = new float[4];
        m.transform(0, 0, 0, a); m.transform(0, 12 / 16f, 0, b); m.transform(4 / 16f, 0, 0, c);
        return String.format(Locale.ROOT, "length %.3f width %.3f", dist(a, b), dist(a, c));
    }

    static float dist(float[] a, float[] b) { return (float) Math.sqrt((a[0] - b[0]) * (a[0] - b[0]) + (a[1] - b[1]) * (a[1] - b[1]) + (a[2] - b[2]) * (a[2] - b[2])); }

    static boolean nanSeen;

    static void check(GunAnimator.Pose p, String what) {
        if (nanSeen) return;
        for (float f : p.gun.m) if (!Float.isFinite(f)) { nanSeen = true; bad("NaN in the gun matrix (" + what + ")"); return; }
        for (float f : p.rightHand) if (!Float.isFinite(f)) { nanSeen = true; bad("NaN in the hands (" + what + ")"); return; }
        // the gun must stay in front of the camera
        float[] g = new float[4];
        p.gun.transform(p.type.rag[0], p.type.rag[1], p.type.rag[2], g);
        if (g[2] > -0.05f) { nanSeen = true; bad("the grip goes behind the camera (" + what + ")"); return; }
        // the arms keep their size: the same length in every pose (rigid, not stretched)
        float[] a0 = new float[4], a1 = new float[4];
        p.rightArm.transform(0, 0, 0, a0); p.rightArm.transform(0, 12 / 16f, 0, a1);
        float len = dist(a0, a1), want = p.type.firstPersonScale / 16f * p.type.armSize * p.type.armLength * 12;
        if (Math.abs(len - want) > 1e-3f) { nanSeen = true; bad("the right arm stretches (" + what + ")"); return; }
        p.rightArm.transform(0, GunAnimator.PALM / 16f, 0, a0);
        if (dist(a0, p.rightHand) > 1e-3f) { nanSeen = true; bad("the right palm is not on its point (" + what + ")"); }
    }
}
