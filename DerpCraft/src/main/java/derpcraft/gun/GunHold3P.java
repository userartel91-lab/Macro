package derpcraft.gun;

import derpcraft.util.Mat4;

/**
 * Third person hold (no OpenGL): the gun in both hands in front of the right shoulder, pointing where the head looks
 * (turned with the head's yaw and pitch), the right hand on the grip ("rag"), the left on the handguard ("lag").
 *
 * The upper body is turned by {@link #TWIST} (the renderer does it), the head turned back.
 *
 * <p>Space: the upper body of the Minecraft player model in blocks (pixels / 16), y down, -z forward, right = -x; arm
 * pivots in pixels (right -5, 2, 0; left 5, 2, 0). Arm angles are ModelBiped's (x, y, z; applied z, y, x) with the
 * arm hanging along +y; the stretch makes the arm reach its point (a little at most).
 */
public final class GunHold3P {
    /** The gun's model space to the upper body space. */
    public final Mat4 gun = new Mat4();
    public final float[] rArm = new float[3], lArm = new float[3];
    public float rStretch = 1, lStretch = 1;
    /** The magazine (model space, moved on reloads) and the bolt pushed back (model units). */
    public final Mat4 mag = new Mat4();
    public float slide;
    /** Whether {@link #mag} is moved (draw it with it). */
    public boolean magMoved;
    /** The magazine: 0 = on the gun ({@link #mag}), 1 = none (let go), 2 = in the hand ({@link #magBody}: model to upper body space). */
    public int magMode;
    public final Mat4 magBody = new Mat4();
    /** Pockets on the hips (upper body space) the new magazine comes from: the left and the right one. */
    static final float[] POCKET_LEFT = {4.5f / 16, 11f / 16, -1.5f / 16}, POCKET_RIGHT = {-4.5f / 16, 11f / 16, -1.5f / 16};

    /** Where the grip is from the chest (between the shoulders) before the head's turn (blocks, body space). */
    public static float[] GRIP = {-1.5f / 16, 3f / 16, -4.5f / 16};
    /**
     * The upper body turns this much (degrees, left shoulder forward: a bladed stance) so the left hand reaches the
     * handguard of a gun held out in front; the head and the gun turn back by it, so they still look straight.
     */
    public static float TWIST = 20;
    /** The point the hold turns around with the head: the middle between the shoulders. */
    static final float[] CHEST = {0, 2f / 16, 0};
    /** Pixels from the shoulder pivot to the palm along the arm (the arm box runs from -2 to 10). */
    static final float PALM = 8.5f;
    /** The arm reaches its point by stretching at most this much (no long arms). */
    public static float MAX_STRETCH = 1.25f;
    /**
     * The first person melee moves are made for the view (the arms come from out of the picture); the body's arms are
     * fixed at the shoulders, so the third person takes this share of them (the turns in full).
     */
    public static float MELEE_MOVE = 0.45f;

    public void compute(GunType t, float pitchDeg, float headYawDeg, float[] rPivot, float[] lPivot) {
        compute(t, pitchDeg, headYawDeg, rPivot, lPivot, 0, -1, false);
    }

    /**
     * With motion: 'kick' (0..1, a shot just now: the gun jumps back and up) and a reload at progress 'reload' (0..1;
     * below 0 = none; 'empty' = with the bolt): the gun tilts, the right hand takes the magazine out and puts a new one
     * in, on an empty reload the left hand pulls the bolt. The same timeline as the first person reload.
     */
    public void compute(GunType t, float pitchDeg, float headYawDeg, float[] rPivot, float[] lPivot, float kick, float reload, boolean empty) {
        compute(t, pitchDeg, headYawDeg, rPivot, lPivot, kick, reload, empty, -1, 0);
    }

    /**
     * With a melee strike too: kind 'melee' (-1 = none) at 'meleeTime' seconds. The first person wind-up and hit poses
     * (the whole rig's and the gun's move and turn) are used here, turned into the body's space (x and y flipped) and
     * sized like the third person gun; the hands stay on the gun (an arm's own move in the poses moves its hand off).
     */
    public void compute(GunType t, float pitchDeg, float headYawDeg, float[] rPivot, float[] lPivot, float kick, float reload, boolean empty,
                        int melee, float meleeTime) {
        float pitch = (float) Math.toRadians(Math.max(-75, Math.min(75, pitchDeg)));
        // the head's yaw relative to the twisted upper body
        float yaw = (float) Math.toRadians(Math.max(-60, Math.min(60, headYawDeg)) - TWIST);
        // the grip: in front of the right shoulder, the whole hold turned with the head
        Mat4 look = new Mat4().rotate(yaw, 0, 1, 0).rotate(pitch, 1, 0, 0);
        float[] o = t.thirdPersonOffset, v = new float[4];
        look.transform(GRIP[0] - o[0], GRIP[1] - o[1], GRIP[2] + o[2], v);     // offset: x right, y up, z forward = minus
        float gx = CHEST[0] + v[0], gy = CHEST[1] + v[1], gz = CHEST[2] + v[2];
        float s = t.thirdPersonScale;
        float[] r = t.thirdPersonRotation;
        gun.identity().translate(gx, gy, gz);
        gun.rotate(yaw, 0, 1, 0).rotate(pitch, 1, 0, 0);
        float[] mw = reload >= 0 ? null : GunAnimator.meleeWeights(t, melee, meleeTime);
        float k3 = t.thirdPersonScale / Math.max(0.05f, t.firstPersonScale) * MELEE_MOVE;
        if (mw != null) {
            // view space (x right, y up, z back) -> body space (x left, y down, z back): x and y flip, so do yaw and pitch
            float mx = GunAnimator.meleeMix(t.poseMove, melee, mw, 0) + GunAnimator.meleeMix(t.gunMove, melee, mw, 0);
            float my = GunAnimator.meleeMix(t.poseMove, melee, mw, 1) + GunAnimator.meleeMix(t.gunMove, melee, mw, 1);
            float mz = GunAnimator.meleeMix(t.poseMove, melee, mw, 2) + GunAnimator.meleeMix(t.gunMove, melee, mw, 2);
            gun.translate(-mx * k3, -my * k3, mz * k3);
            gun.rotate((float) Math.toRadians(-(GunAnimator.meleeMix(t.poseTurn, melee, mw, 0) + GunAnimator.meleeMix(t.gunTurn, melee, mw, 0))), 0, 1, 0);
            gun.rotate((float) Math.toRadians(-(GunAnimator.meleeMix(t.poseTurn, melee, mw, 1) + GunAnimator.meleeMix(t.gunTurn, melee, mw, 1))), 1, 0, 0);
            gun.rotate((float) Math.toRadians(GunAnimator.meleeMix(t.poseTurn, melee, mw, 2) + GunAnimator.meleeMix(t.gunTurn, melee, mw, 2)), 0, 0, 1);
        }
        // the gun's own model: y up and x right, here y is down and right is -x (half a turn around z)
        gun.scale(-s, -s, s);
        gun.rotate((float) Math.toRadians(r[0]), 0, 1, 0);
        gun.rotate((float) Math.toRadians(r[1]), 1, 0, 0);
        gun.rotate((float) Math.toRadians(r[2]), 0, 0, 1);
        // a shot: back along the gun and the muzzle up (around the grip)
        if (kick > 0) { gun.translate(0, 0, 0.14f * kick); gun.rotate((float) Math.toRadians(8 * kick), 1, 0, 0); }
        GunAnimator.Reload rs = reload >= 0 ? GunAnimator.reloadState(t, reload, empty) : null;
        if (rs != null) {
            // tilted to bring the magazine well up (muzzle up, rolled), on the bolt part rolled towards the bolt
            float side = t.las[0] >= t.rag[0] ? 1 : -1;
            gun.rotate((float) Math.toRadians(18 * rs.tilt()), 1, 0, 0);
            gun.rotate((float) Math.toRadians(-28 * rs.tilt() + side * 30 * rs.show()), 0, 0, 1);
        }
        gun.translate(-t.rag[0], -t.rag[1], -t.rag[2]);
        magMoved = rs != null;
        magMode = rs == null ? 0 : rs.magMode();
        slide = 0;
        if (rs != null) { GunAnimator.magMatrix(mag, t, rs); slide = rs.slide(); } else mag.identity();
        float[] grip = new float[4], guard = new float[4], onMag = new float[4], bolt = new float[4], tmp = new float[4];
        gun.transform(t.rag[0], t.rag[1], t.rag[2], grip);
        gun.transform(t.lag[0], t.lag[1], t.lag[2], guard);
        if (rs != null) {
            float[] pocket = t.magLeft ? POCKET_LEFT : POCKET_RIGHT;
            if (magMode == 0) {
                mag.transform(t.ram[0], t.ram[1], t.ram[2], tmp);
                gun.transform(tmp[0], tmp[1], tmp[2], onMag);
                // pistol: the old magazine drops free, the hand is already on its way to the pocket
                for (int k = 0; k < 3; k++) onMag[k] += (pocket[k] - onMag[k]) * rs.toPocket();
            } else if (magMode == 1) {
                // the old magazine was let go: the hand goes from there to the pocket on the hip
                Mat4 rel = new Mat4();
                GunAnimator.releaseMatrix(rel, t);
                rel.transform(t.ram[0], t.ram[1], t.ram[2], tmp);
                gun.transform(tmp[0], tmp[1], tmp[2], onMag);
                for (int k = 0; k < 3; k++) onMag[k] += (pocket[k] - onMag[k]) * rs.pocket();
            } else {
                // the new one in the hand, from the pocket up to the gun
                GunAnimator.magInHand(magBody, gun, t, pocket, rs.fromPocket());
                magBody.transform(t.ram[0], t.ram[1], t.ram[2], onMag);
            }
            float[] bp = t.boltPoint(t.las, slide, new float[3]);
            gun.transform(bp[0], bp[1], bp[2], bolt);
            float a = GunAnimator.smooth(rs.rOnMag()), b = GunAnimator.smooth(rs.lOnBolt());
            float[] magHand = t.magLeft ? guard : grip, boltHand = t.boltRight ? grip : guard;
            for (int k = 0; k < 3; k++) magHand[k] += (onMag[k] - magHand[k]) * a;
            for (int k = 0; k < 3; k++) boltHand[k] += (bolt[k] - boltHand[k]) * b;
        }
        if (mw != null) {
            // an arm's own move in the melee poses (a pistol whip: the left hand lets go), turned with the look
            float[] ro = new float[4], lo = new float[4];
            look.transform(-GunAnimator.meleeMix(t.rArmMove, melee, mw, 0) * k3, -GunAnimator.meleeMix(t.rArmMove, melee, mw, 1) * k3, GunAnimator.meleeMix(t.rArmMove, melee, mw, 2) * k3, ro);
            look.transform(-GunAnimator.meleeMix(t.lArmMove, melee, mw, 0) * k3, -GunAnimator.meleeMix(t.lArmMove, melee, mw, 1) * k3, GunAnimator.meleeMix(t.lArmMove, melee, mw, 2) * k3, lo);
            for (int k = 0; k < 3; k++) { grip[k] += ro[k]; guard[k] += lo[k]; }
        }
        rStretch = aim(rPivot, grip, rArm, -1);
        lStretch = aim(lPivot, guard, lArm, 1);
    }

    /**
     * A point of the gun (model units) in the world for a model standing at (x, y, z) with body yaw, head yaw relative to
     * the body, pitch and size, like EntityRenderer draws it (the muzzle of an NPC's gun: smoke, tracers start there).
     */
    public static double[] worldPoint(GunType t, float[] pt, double x, double y, double z, float bodyYaw, float headYawRel, float pitch, float scale, float kick) {
        Mat4 m = new Mat4().rotate((float) Math.toRadians(180 - bodyYaw), 0, 1, 0);
        if (scale != 1) m.scale(scale, scale, scale);
        m.scale(-1, -1, 1).translate(0, -1.501f, 0);
        m.translate(0, 12 / 16f, 0).rotate((float) Math.toRadians(TWIST), 0, 1, 0).translate(0, -12 / 16f, 0);
        GunHold3P h = new GunHold3P();
        h.compute(t, pitch, headYawRel, new float[]{-5, 2, 0}, new float[]{5, 2, 0}, kick, -1, false);
        m.mul(h.gun);
        float[] o = new float[4];
        m.transform(pt[0], pt[1], pt[2], o);
        return new double[]{x + o[0], y + o[1], z + o[2]};
    }

    /**
     * Where the magazine is let go in a reload, in the world (its middle), for a model like in {@link #worldPoint}: the
     * old magazine starts falling to the ground there. Also its yaw (degrees, the way the gun points).
     */
    public static double[] worldMagRelease(GunType t, double x, double y, double z, float bodyYaw, float headYawRel, float pitch, float scale, boolean empty) {
        if (t.mag == null) return null;
        Mat4 m = new Mat4().rotate((float) Math.toRadians(180 - bodyYaw), 0, 1, 0);
        if (scale != 1) m.scale(scale, scale, scale);
        m.scale(-1, -1, 1).translate(0, -1.501f, 0);
        m.translate(0, 12 / 16f, 0).rotate((float) Math.toRadians(TWIST), 0, 1, 0).translate(0, -12 / 16f, 0);
        GunHold3P h = new GunHold3P();
        float q = GunAnimator.MAG_DROP - 0.005f;
        h.compute(t, pitch, headYawRel, new float[]{-5, 2, 0}, new float[]{5, 2, 0}, 0, empty ? q * GunAnimator.EMPTY_MAG_END : q, empty);
        m.mul(h.gun).mul(h.mag);
        float[] c = GunType.bounds(t.mag), o = new float[4];
        m.transform((c[0] + c[3]) / 2, (c[1] + c[4]) / 2, (c[2] + c[5]) / 2, o);
        return new double[]{x + o[0], y + o[1], z + o[2], bodyYaw + headYawRel};
    }

    // ------------------------------------------------------------------ carried on the back (Left Alt + F)
    /** A rifle on the back: the muzzle up over the right shoulder, this many degrees from straight up (the stock at the left hip). */
    public static float BACK_TILT = 38;
    /** A pistol in the waistband at the small of the back: the muzzle towards the left, this many degrees down, the grip up on the right. */
    public static float PISTOL_TILT = 12;
    /** Gap between the back and the gun (pixels: room for a chestplate). */
    public static float BACK_GAP = 1.1f;

    /**
     * The gun carried on the back: its model space to the space of the body part (the box -4..4, 0..12, -2..2 pixels;
     * y down, right = -x, the back at +z), like {@link #gun} but on the body instead of the upper body. A rifle hangs
     * across the back on its side, a pistol ({@link GunType#pistol}) is tucked in the waistband; the gun's
     * thirdPersonBackOffset / thirdPersonBackRotation move and turn it from there.
     */
    public static Mat4 back(GunType t, Mat4 out) {
        out.identity();
        float[] b = t.bodyBounds();
        if (b == null) return out;
        float s = t.backScale();                          // its own size on the back (thirdPersonBackScale), else the hands' one
        float[] c = {(b[0] + b[3]) / 2, (b[1] + b[4]) / 2, (b[2] + b[5]) / 2};
        float half = (b[3] - b[0]) / 2 * s;               // the gun lies on its side: its width is the distance off the back
        // columns: where the model's x (its right side), y (its top) and z (towards the butt) go in the body's space
        float[] X, Y, Z, at;
        if (t.pistol) {
            float a = (float) Math.toRadians(PISTOL_TILT), ca = (float) Math.cos(a), sa = (float) Math.sin(a);
            Z = new float[]{-ca, -sa, 0};                 // the muzzle to the left (+x) and a little down
            Y = new float[]{-sa, ca, 0};                  // upside down: the slide below, the grip up
            X = new float[]{0, 0, 1};
            at = new float[]{-0.5f / 16, 9.5f / 16, 2f / 16};
        } else {
            float a = (float) Math.toRadians(BACK_TILT), ca = (float) Math.cos(a), sa = (float) Math.sin(a);
            Z = new float[]{sa, ca, 0};                   // the muzzle up (-y) and over the right shoulder (-x)
            Y = new float[]{ca, -sa, 0};                  // the top towards the left shoulder, the magazine down and right
            X = new float[]{0, 0, 1};
            at = new float[]{0, 6f / 16, 2f / 16};
        }
        float[] o = t.thirdPersonBackOffset, r = t.thirdPersonBackRotation;
        // offset like thirdPersonOffset: x right, y up, z forward negative (the body's x and y point the other way)
        out.translate(at[0] - o[0], at[1] - o[1], at[2] + BACK_GAP / 16 + half + o[2]);
        Mat4 rot = new Mat4();
        float[] q = rot.m;
        q[0] = X[0]; q[1] = X[1]; q[2] = X[2];
        q[4] = Y[0]; q[5] = Y[1]; q[6] = Y[2];
        q[8] = Z[0]; q[9] = Z[1]; q[10] = Z[2];
        out.mul(rot).scale(s, s, s);
        out.rotate((float) Math.toRadians(r[0]), 0, 1, 0);
        out.rotate((float) Math.toRadians(r[1]), 1, 0, 0);
        out.rotate((float) Math.toRadians(r[2]), 0, 0, 1);
        out.translate(-c[0], -c[1], -c[2]);
        return out;
    }

    // ------------------------------------------------------------------ magazines in the pouches (Left Alt + E)
    /** Magazines in the pouches are this share of the third person gun size. */
    public static float POUCH_SCALE = 0.8f;
    /** Pouch k (0..2 left, 3..5 right) from the middle of the belt (pixels across), the top of the magazines (pixels down) and the gap in front of the belly. */
    public static float POUCH_X0 = 1.2f, POUCH_DX = 1.35f, POUCH_TOP = 8.6f, POUCH_GAP = 1.1f;

    /**
     * A magazine standing in pouch 'k' on the front of the belt (0..2 on the left from the middle out, 3..5 on the
     * right): the magazine's model space to the body part's space (like {@link #back}). Upright as on the gun.
     */
    public static Mat4 pouch(GunType t, int k, Mat4 out) {
        out.identity();
        if (t.mag == null) return out;
        float[] b = GunType.bounds(t.mag);
        float s = t.thirdPersonScale * POUCH_SCALE * t.pouchScale;
        float side = k < 3 ? 1 : -1;                     // left = +x in the body's space
        float[] o = t.pouchOffset, r = t.pouchRotation;
        // the default spot is the top of the magazine; the turn is around its middle, so move there first
        float x = side * (POUCH_X0 + (k % 3) * POUCH_DX) / 16 + side * o[0];
        float y = POUCH_TOP / 16 - o[1] + s * (b[4] - b[1]) / 2;
        float z = -(2 + POUCH_GAP) / 16 - o[2] - s * (b[5] - b[2]) / 2;
        // the model: y up, x right -> the body: y down, right = -x (half a turn around z), its back (+z) against the belly
        out.translate(x, y, z);
        out.rotate((float) Math.toRadians(r[0] * side), 0, 1, 0);
        out.rotate((float) Math.toRadians(r[1]), 1, 0, 0);
        out.rotate((float) Math.toRadians(r[2] * side), 0, 0, 1);
        out.scale(-s, -s, s);
        out.translate(-(b[0] + b[3]) / 2, -(b[1] + b[4]) / 2, -(b[2] + b[5]) / 2);
        return out;
    }

    /**
     * The right arm reaching for the gun on the back by 'reach' (0..1): up behind the right shoulder for a rifle, behind
     * the hip for a pistol. ModelBiped angles (x, y, z), blended into 'arm'.
     */
    public static void backReach(GunType t, float reach, float[] arm) {
        if (reach <= 0) return;
        float[] to = t != null && t.pistol ? new float[]{0.5f, 0, -0.45f} : new float[]{-3.2f, 0, -0.1f};
        for (int k = 0; k < 3; k++) arm[k] += (to[k] - arm[k]) * reach;
    }

    /** Turns an arm so its hand points at 'target' (upper body space); returns the stretch that makes the palm reach it. */
    static float aim(float[] pivot, float[] target, float[] ang) { return aim(pivot, target, ang, 0); }

    /**
     * 'sideX': where the middle of the arm box is across from its pivot (pixels: the right arm's box runs -3..1, so -1;
     * the left one's -1..3, so +1). The hand is that far off the line from the pivot, turned with the arm: aimed twice,
     * the second time at the target moved back by that offset, so the middle of the hand lands on the point.
     */
    static float aim(float[] pivot, float[] target, float[] ang, float sideX) {
        float st = aimOnce(pivot, target, ang);
        if (sideX == 0) return st;
        // the box middle turned like the arm: Ry(b) * Rx(a) * (sideX, 0, 0) = (sideX cos b, 0, -sideX sin b)
        float ox = sideX / 16f * (float) Math.cos(ang[1]), oz = -sideX / 16f * (float) Math.sin(ang[1]);
        return aimOnce(pivot, new float[]{target[0] - ox, target[1], target[2] - oz}, ang);
    }

    private static float aimOnce(float[] pivot, float[] target, float[] ang) {
        float dx = target[0] - pivot[0] / 16f, dy = target[1] - pivot[1] / 16f, dz = target[2] - pivot[2] / 16f;
        float len = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (len < 1e-4f) return 1;
        dx /= len; dy /= len; dz /= len;
        // Ry(b) * Rx(a) * (0, 1, 0) = (sin a sin b, cos a, sin a cos b)
        ang[0] = -(float) Math.acos(Math.max(-1, Math.min(1, dy)));
        ang[1] = (float) Math.atan2(-dx, -dz);
        ang[2] = 0;
        return Math.max(0.8f, Math.min(MAX_STRETCH, len / (PALM / 16f)));
    }
}
