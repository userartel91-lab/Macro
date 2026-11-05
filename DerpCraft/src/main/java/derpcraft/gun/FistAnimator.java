package derpcraft.gun;

import derpcraft.util.Mat4;

import java.util.Random;

/**
 * First person fist fighting (Alt + Q): the player's own Minecraft arms (the same rigid arms the guns use) in a boxer's
 * guard, punching with the left hand (left mouse) and the right hand (right mouse). No OpenGL, no game state: inputs in,
 * a {@link Pose} out, like {@link GunAnimator}.
 *
 * <p>The guard lives on the same layers as the guns: look sway that lags behind the mouse and settles with a little
 * overshoot, strafe lean, a figure eight bob tied to the steps, jump float and landing dip, breathing, a sprint pose
 * (fists low, pumping with the steps), and a rise from below when the fists come up. A punch is keyframed: a short wind
 * up, a snap out to the crosshair with the fist turning over (corkscrew), a moment at full reach, the way back; the body
 * turns into it (the shoulder goes forward, the other fist tucks in to the chin). Pressing the same hand again soon after
 * throws a hook (round from the side, the elbow up), then a straight again. A punch that lands shakes the fist back on a
 * spring and stops the motion for a moment (hit stop), so it feels like it hit something.
 *
 * <p>View space: camera at the origin looking to -Z, +X right, +Y up, 1 unit = 1 block.
 */
public final class FistAnimator {
    /** Output for the renderer. */
    public static final class Pose {
        /** Arm box space (pixels / 16, like {@link GunAnimator.Pose#rightArm}) to view space. */
        public final Mat4 rightArm = new Mat4(), leftArm = new Mat4();
        /** Where the fists are (view space). */
        public final float[] rightHand = new float[3], leftHand = new float[3];
        /** How far each fist is out (0 = in the guard, 1 = full reach): the third person model's punches. */
        public float rightPunch, leftPunch;
        /** How far the fists are up (0 = put away, 1 = in the guard). */
        public float up;
    }

    // ---------------------------------------------------------------- inputs (set by the controller every frame)
    /** The fists are wanted (Alt + Q on): they come up; off: they go down and {@link #shown()} turns false. */
    public boolean active;
    public boolean sprinting, onGround = true;
    /** Horizontal speed (blocks / second), sideways (+ = right) and forward speed, vertical speed (blocks / tick), view angles. */
    public float speed, strafe, forward, vertical, yaw, pitch;

    // ---------------------------------------------------------------- the punch timeline (seconds)
    /** Wind up ends, the fist is at full reach (the hit), it starts back, it is back in the guard. */
    public static final float WIND = 0.06f, IMPACT = 0.135f, HOLD = 0.18f, BACK = 0.42f;
    /** After a punch ends, pressing the same hand within this long throws the next punch of the combo (hook after straight). */
    static final float COMBO = 0.35f;
    /** Punch types. */
    public static final int STRAIGHT = 0, HOOK = 1;

    /** One hand: its punch (time since it started, below 0 = none), type, where it started from. */
    private static final class Hand {
        final int side;                         // +1 right, -1 left
        float t = -1, sinceEnd = 10;
        int type, combo;
        final float[] from = new float[3], fromTo = new float[3];
        float fromTwist;
        boolean impactDone;
        Hand(int side) { this.side = side; }
        boolean busy() { return t >= 0 && t < HOLD; }
    }

    private final Hand right = new Hand(1), left = new Hand(-1);
    private float up, move, bobPhase, time, hitStop;
    private float lastYaw = Float.NaN, lastPitch, lastVertical;
    private final GunAnimator.Spring sway = new GunAnimator.Spring(5), lean = new GunAnimator.Spring(4), air = new GunAnimator.Spring(2);
    /** Per hand recoil of a punch that landed: back, up, sideways (0..2 right, 3..5 left), and a shake of the whole guard. */
    private final GunAnimator.Spring recoil = new GunAnimator.Spring(6), shake = new GunAnimator.Spring(3);
    private int impacts;
    private final Random rnd = new Random();
    public final Pose pose = new Pose();
    /** The fists were drawn this frame (false once they are put away). */
    private boolean shown;

    // ---------------------------------------------------------------- the guard (orthodox stance: the left fist leads)
    /** Fists in the guard (view space). */
    static final float[] GUARD_R = {0.19f, -0.14f, -0.37f}, GUARD_L = {-0.165f, -0.115f, -0.44f};
    /** Where each arm comes from (the shoulder side, out of the picture). */
    static final float[] FROM_R = {0.50f, -0.80f, 0.12f}, FROM_L = {-0.50f, -0.78f, 0.10f};
    /** A straight punch lands here (a little to its own side of the crosshair, low: the arm's end stays out of the picture). */
    static final float[] STRAIGHT_R = {0.035f, -0.075f, -0.60f}, STRAIGHT_L = {-0.035f, -0.065f, -0.62f};
    /** Arm size: one skin pixel (the same as the AK's arms: firstPersonScale 0.4 x armSize 1.4). */
    public static final float PIX = 0.4f * 1.4f / 16f;

    // ---------------------------------------------------------------- control
    /** Whether a punch with this hand can start now (its last one is on the way back). */
    public boolean ready(boolean leftHand) { Hand h = leftHand ? left : right; return up > 0.6f && !h.busy(); }

    /** Throws a punch with the left or the right hand; false when that hand is still out. The type follows the combo. */
    public boolean punch(boolean leftHand) {
        Hand h = leftHand ? left : right;
        if (!ready(leftHand)) return false;
        // the combo: the same hand again soon after its last punch -> a hook, then a straight again
        boolean chain = h.t >= 0 || h.sinceEnd < COMBO;
        h.combo = chain ? h.combo + 1 : 0;
        h.type = h.combo % 2 == 1 ? HOOK : STRAIGHT;
        float[] now = handNow(h), to = towardNow(h);
        System.arraycopy(now, 0, h.from, 0, 3);
        System.arraycopy(to, 0, h.fromTo, 0, 3);
        h.fromTwist = twistNow(h);
        h.t = 0;
        h.impactDone = false;
        return true;
    }

    /** The type of the punch a hand threw last (STRAIGHT / HOOK). */
    public int type(boolean leftHand) { return (leftHand ? left : right).type; }

    /** Punches that reached full reach since the last call: bit 0 = left, bit 1 = right (the controller tests what they hit). */
    public int takeImpacts() { int i = impacts; impacts = 0; return i; }

    /**
     * Feedback of a punch: 'landed' = it hit a creature or an NPC (the fist bounces back on a spring, the guard shakes,
     * a short hit stop), else a block (a smaller bounce) or nothing (no feedback).
     */
    public void hit(boolean leftHand, boolean landed) {
        int o = leftHand ? 3 : 0, s = leftHand ? -1 : 1;
        float k = landed ? 1 : 0.6f;
        recoil.v[o] += 2.2f * k;                     // back
        recoil.v[o + 1] += 0.6f * k;                 // up
        recoil.v[o + 2] += s * 0.5f * k;             // out to its side
        shake.v[0] += (rnd.nextFloat() - 0.5f) * 140 * k;
        shake.v[1] += 90 * k;
        shake.v[2] += (rnd.nextFloat() - 0.5f) * 0.3f * k;
        if (landed) hitStop = 0.05f;
    }

    /** Whether anything is drawn (the fists are up or still going down). */
    public boolean shown() { return shown; }

    // ---------------------------------------------------------------- animation
    /** Advances the animation by 'dt' seconds (real time) and fills {@link #pose}. */
    public Pose update(float dt) {
        dt = Math.max(0, Math.min(0.1f, dt));
        float adt = dt * 1.6f;                  // the springs' pace, like a gun's animSpeed 1.6
        time += adt;
        up = GunAnimator.approach(up, active ? 1 : 0, dt / (active ? 0.32f : 0.22f));
        shown = up > 0 || active;
        Pose p = pose;
        float eq = active ? 1 - GunAnimator.backOut(up) : 1 - GunAnimator.smooth(up);
        p.up = up;
        move += (Math.min(1.3f, speed / 4.3f) - move) * (1 - (float) Math.exp(-adt * 8));
        bobPhase += adt * speed * 1.45f;
        float sp = sprinting ? 1 : 0;
        sprint += (sp - sprint) * (1 - (float) Math.exp(-adt * 7));

        // look sway, strafe lean, air, like the guns
        if (Float.isNaN(lastYaw)) { lastYaw = yaw; lastPitch = pitch; }
        float dYaw = GunAnimator.wrap(yaw - lastYaw), dPitch = pitch - lastPitch;
        lastYaw = yaw; lastPitch = pitch;
        float rate = dt > 1e-4f ? 1 / dt : 0;
        float yawRate = GunAnimator.clamp(dYaw * rate, -400, 400), pitchRate = GunAnimator.clamp(dPitch * rate, -400, 400);
        sway.step(new float[]{-yawRate * 0.02f, -pitchRate * 0.018f, -yawRate * 0.00028f, pitchRate * 0.00024f, yawRate * 0.012f}, 140, 0.5f, adt);
        lean.step(new float[]{-strafe * 1.8f, strafe * -0.007f, forward * 0.005f, -forward * 0.7f}, 60, 0.8f, adt);
        float vy = vertical * 20;
        if (lastVertical < -0.12f && Math.abs(vertical) < 0.02f && onGround) { air.v[0] -= Math.min(1.6f, -lastVertical * 6); air.v[1] += Math.min(30, -lastVertical * 120); }
        lastVertical = vertical;
        air.step(new float[]{onGround ? 0 : GunAnimator.clamp(-vy * 0.006f, -0.03f, 0.03f), onGround ? 0 : GunAnimator.clamp(vy * 0.6f, -6, 6)}, 90, 0.55f, adt);
        recoil.step(null, 260, 0.45f, adt);
        shake.step(null, 420, 0.4f, adt);

        // the punches: time stands still for a moment when one lands
        float pdt = hitStop > 0 ? 0 : dt;
        hitStop = Math.max(0, hitStop - dt);
        for (Hand h : new Hand[]{left, right}) {
            if (h.t >= 0) {
                float before = h.t;
                h.t += pdt;
                if (!h.impactDone && before < IMPACT && h.t >= IMPACT) { h.impactDone = true; impacts |= h.side < 0 ? 1 : 2; }
                if (h.t >= BACK) { h.t = -1; h.sinceEnd = 0; }
            } else h.sinceEnd += dt;
        }

        // ---- the whole guard: position and turn (around a point in front of the chest)
        float px = 0, py = 0, pz = 0, yawD = 0, pitchD = 0, rollD = 0;
        float bob = move * (1 + sprint * 1.3f);
        float sx = (float) Math.sin(bobPhase), s2 = (float) Math.sin(bobPhase * 2);
        px += sx * 0.018f * bob; py += s2 * 0.01f * bob - 0.004f * bob;
        rollD += sx * 3f * bob; yawD += (float) Math.cos(bobPhase) * 1.6f * bob; pitchD += s2 * 1.4f * bob;
        float idle = 1 - move * 0.5f;                     // breathing, and a boxer's slow bounce in the guard
        py += (float) Math.sin(time * 1.6f) * 0.006f * idle;
        pitchD += (float) Math.sin(time * 1.6f + 0.7f) * 0.8f * idle;
        yawD += (float) Math.sin(time * 0.9f) * 0.5f * idle;
        yawD += sway.x[0]; pitchD += sway.x[1]; px += sway.x[2]; py += sway.x[3]; rollD += sway.x[4];
        rollD += lean.x[0]; px += lean.x[1]; pz += lean.x[2]; pitchD += lean.x[3];
        py += air.x[0]; pitchD += air.x[1];
        yawD += shake.x[0] * 0.02f; pitchD += shake.x[1] * 0.02f; px += shake.x[2] * 0.05f;
        // the body turns into a punch: the right one turns the guard left (the right shoulder comes forward) and back
        float eR = extension(right), eL = extension(left);
        yawD += (eR - eL) * 7; rollD += (eR - eL) * -4; pz -= Math.max(eR, eL) * 0.03f;
        // sprint: the fists drop and pump with the steps
        py -= sprint * 0.04f; pitchD -= sprint * 8;
        // coming up / going down
        py -= eq * 0.55f; pz += eq * 0.06f; pitchD -= eq * 50;
        Mat4 rig = new Mat4().translate(px, py, pz).translate(0, -0.22f, -0.35f);
        rig.rotate((float) Math.toRadians(yawD), 0, 1, 0);
        rig.rotate((float) Math.toRadians(pitchD), 1, 0, 0);
        rig.rotate((float) Math.toRadians(rollD), 0, 0, 1);
        rig.translate(0, 0.22f, 0.35f);

        for (Hand h : new Hand[]{right, left}) {
            boolean r = h.side > 0;
            float[] hand = handNow(h), to = towardNow(h);
            float twist = twistNow(h);
            // sprint pumping: the fists swing forward and back opposite to each other, a little lower and closer in
            float pump = (float) Math.sin(bobPhase + (r ? 0 : Math.PI)) * sprint * Math.min(1, move);
            hand[2] -= pump * 0.06f; hand[1] += pump * 0.02f; hand[0] -= h.side * sprint * 0.03f;
            // the recoil of a punch that landed
            int o = r ? 0 : 3;
            hand[2] += recoil.x[o] * 0.03f; hand[1] += recoil.x[o + 1] * 0.03f; hand[0] += recoil.x[o + 2] * 0.03f;
            float[] hv = new float[4], tv = new float[4];
            rig.transform(hand[0], hand[1], hand[2], hv);
            rig.transform(to[0], to[1], to[2], tv);
            Mat4 arm = r ? p.rightArm : p.leftArm;
            GunAnimator.armMatrix(arm, new float[]{hv[0], hv[1], hv[2]}, new float[]{tv[0], tv[1], tv[2]}, new float[3], new float[]{0, 0, rollD * 0.5f}, PIX, 1, 1,
                    r ? p.rightHand : p.leftHand);
            arm.rotate((float) Math.toRadians(twist), 0, 1, 0);     // the fist turns around its own arm (the box's long axis runs through the palm)
        }
        p.rightPunch = eR;
        p.leftPunch = eL;
        return p;
    }

    private float sprint;

    /** How far a hand's punch is out (0..1): out to the hit, a moment there, then back. */
    static float extension(Hand h) {
        if (h.t < 0) return 0;
        float t = h.t;
        if (t < WIND) return -0.15f * GunAnimator.smooth(t / WIND);
        if (t < IMPACT) return -0.15f + 1.15f * snap((t - WIND) / (IMPACT - WIND));
        if (t < HOLD) return 1;
        return 1 - GunAnimator.easeInOut((t - HOLD) / (BACK - HOLD));
    }

    /** Fast out, decelerating into the target (a punch snaps out). */
    static float snap(float u) { u = GunAnimator.clamp(u, 0, 1); return 1 - (1 - u) * (1 - u) * (1 - u); }

    private static float[] guard(Hand h) { return (h.side > 0 ? GUARD_R : GUARD_L).clone(); }
    private static float[] from(Hand h) { return (h.side > 0 ? FROM_R : FROM_L).clone(); }

    /** Where the fist is now (before the rig, the pump and the recoil). */
    private float[] handNow(Hand h) {
        float[] g = guard(h);
        Hand other = h == right ? left : right;
        // the other hand's punch: this fist tucks in to the chin
        float tuck = Math.max(0, extension(other));
        g[0] -= h.side * 0.03f * tuck; g[1] += 0.03f * tuck; g[2] += 0.05f * tuck;
        if (h.t < 0) return g;
        float t = h.t, s = h.side;
        float[] wind = {g[0] + s * (h.type == HOOK ? 0.10f : 0.02f), g[1] - 0.015f, g[2] + 0.06f};
        float[] tgt = target(h);
        if (t < WIND) return GunAnimator.mix3(h.from, wind, GunAnimator.smooth(t / WIND));
        if (t < IMPACT) {
            float u = snap((t - WIND) / (IMPACT - WIND));
            if (h.type == HOOK) {      // round from the side: a curve through a point out wide
                float[] c = {s * 0.34f, tgt[1] + 0.03f, -0.46f};
                return bezier(wind, c, tgt, u);
            }
            return GunAnimator.mix3(wind, tgt, u);
        }
        if (t < HOLD) return tgt;
        float u = GunAnimator.easeInOut((t - HOLD) / (BACK - HOLD));
        return GunAnimator.mix3(tgt, g, u);
    }

    /** Where the punch lands (view space). */
    private static float[] target(Hand h) {
        if (h.type == HOOK) return new float[]{-h.side * 0.03f, -0.07f, -0.45f};
        return (h.side > 0 ? STRAIGHT_R : STRAIGHT_L).clone();
    }

    /** Where the arm comes from now: the shoulder drives forward and in with a straight, out wide with a hook (elbow up). */
    private float[] towardNow(Hand h) {
        float[] f = from(h);
        float e = Math.max(0, extension(h));
        float s = h.side;
        if (h.t >= 0 && h.type == HOOK) { f[0] += s * 0.55f * e; f[1] += 0.35f * e; f[2] += 0.40f * e; }
        else { f[0] -= s * 0.12f * e; f[1] += 0.12f * e; f[2] -= 0.30f * e; }
        return f;
    }

    /** The fist's turn around its own arm (degrees): a straight turns over (corkscrew), a hook goes palm down. */
    private float twistNow(Hand h) {
        float e = Math.max(0, extension(h));
        float base = h.side * 12;                       // the guard: knuckles turned a little in
        return base + (h.t >= 0 && h.type == HOOK ? -h.side * 85 : -h.side * 70) * e;
    }

    private static float[] bezier(float[] a, float[] c, float[] b, float u) {
        float v = 1 - u;
        return new float[]{v * v * a[0] + 2 * v * u * c[0] + u * u * b[0], v * v * a[1] + 2 * v * u * c[1] + u * u * b[1], v * v * a[2] + 2 * v * u * c[2] + u * u * b[2]};
    }
}
