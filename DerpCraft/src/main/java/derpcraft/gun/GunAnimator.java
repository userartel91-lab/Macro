package derpcraft.gun;

import derpcraft.util.Mat4;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * First person gun animation in the style of modern shooters (no OpenGL, no game state: inputs in, a {@link Pose} out).
 *
 * <p>The gun: base poses (hip / aim down the sights / tactical sprint / equip) blended with eased curves; on top of them
 * damped springs give the motion its weight: look sway that lags behind the mouse and settles with a little overshoot,
 * strafe lean, walk / run bob on a figure eight tied to the steps, jump float and landing dip, a snappy recoil kick
 * (back, up, random twist and roll) per shot, breathing. Reloads are keyframed (pose to pose with easing) and then run
 * through a spring too, so the gun overshoots and settles like it has mass: the magazine rocks out around its front
 * corner, drops out of view, the new one comes up angled, hooks in at the front and rocks back with a jolt; on an empty
 * reload the gun is rolled to show the bolt, the left hand pulls it back and lets it slam home.
 *
 * <p>The arms: the player's own Minecraft arms (normal proportions, rigid), the palm of each on a gun point (right: grip
 * "rag", or the magazine "ram" riding with it; left: handguard "lag", or the bolt "las" moving with it), pointing back
 * towards the shoulder side; each can be moved and turned (gun editor, Arms page). The palms are placed on the gun as
 * it is drawn, so no pose (its "gun" move and turn included) takes a hand off its point.
 *
 * <p>Two reload styles ({@link GunType#pistol}): rifle (the magazine rocks out around its front, AK) and pistol (it slides
 * out of the grip along its own axis and drops, the slide is racked over the top and can lock back when empty). The
 * bolt / slide moves only between its limit points (boltFront .. boltBack).
 *
 * <p>View space: camera at the origin looking to -Z, +X right, +Y up, 1 unit = 1 block.
 */
public final class GunAnimator {
    /** Output for the renderer. */
    public static final class Pose {
        public GunType type;
        /** Model to view space of the gun body. */
        public final Mat4 gun = new Mat4();
        /** The magazine in model space (offset and rock around its pivot), applied before {@link #gun}. */
        public final Mat4 mag = new Mat4();
        /** Where the magazine is: 0 = on the gun ({@link #mag}), 1 = none (let go, the hand at the pocket), 2 = in the hand ({@link #magView}, view space). */
        public int magMode;
        public final Mat4 magView = new Mat4();
        /** Bolt pushed back (model units, +Z). */
        public float slide;
        /**
         * Arms: Minecraft arm box space (pixels / 16, as ModelBatch.box takes them: x -2..2, y 0 = shoulder end .. 12 = hand end, z -2..2) to view space. The
         * palm (y 10.5) is on the gun point; the arm is rigid and keeps its proportions.
         */
        public final Mat4 rightArm = new Mat4(), leftArm = new Mat4();
        /** Where the palms are (view space). */
        public final float[] rightHand = new float[3], leftHand = new float[3];
        /**
         * The old magazine after it was let go, falling out of the picture (view space, model units to view; drawn while
         * {@link #dropMagShown}): it keeps the speed it had and falls, turning over, instead of vanishing.
         */
        public final Mat4 dropMag = new Mat4();
        public boolean dropMagShown;
        /** Muzzle flash this frame (0 = none) and its turn; muzzle position (view space). */
        public float flash, flashTurn;
        public final float[] muzzle = new float[4];
        /** How far the sights are up (0..1): the HUD hides the crosshair, the view narrows. */
        public float aim;
        /** Gun editor: a point of the model to mark (model units), or null; and a second one (the other bolt limit), or null. */
        public float[] marker, marker2;
        /** Spent casings in the air (view space). */
        public final List<Casing> casings = new ArrayList<>();
    }

    /** A spent casing flying out of the ejection port (view space). */
    public static final class Casing {
        public float x, y, z, vx, vy, vz, spin, spinVel, age;
    }

    // ---------------------------------------------------------------- inputs (set by the controller every frame)
    public boolean aiming, sprinting, reloading, reloadEmpty, onGround = true;
    /** The magazine is empty and the gun has a slide lock: the slide stays back after the last shot. */
    public boolean boltLocked;
    /** Gun editor: hold the bolt this far back (0..1 of its travel; below 0 = off), to see its limit. */
    public float boltHold = -1;
    /** The second aim key is held (it shows only while aiming). */
    public boolean aim2;
    /** Reload progress 0..1. */
    public float reload;
    /** Putting the gun away (0..1: 1 = gone below the picture), set by the controller while the old gun goes down. */
    public float lower;
    /** A melee strike: its kind (GunType.MELEE index, -1 = none) and how long ago it started (seconds, the controller's clock). */
    public int melee = -1;
    public float meleeTime;
    /** Horizontal speed (blocks / second), sideways speed (+ = right) and forward speed in view terms, vertical speed (blocks / tick), view angles (degrees). */
    public float speed, strafe, forward, vertical, yaw, pitch;

    /** Reload timeline (fractions of the magazine part of the reload): when the magazine comes out and when the new one clicks in. */
    public static final float MAG_OUT = 0.30f, MAG_IN = 0.76f;
    /** Empty reload: the magazine part ends here, then the bolt; the bolt is let go at this fraction of the bolt part. */
    public static final float EMPTY_MAG_END = 0.70f, BOLT_RELEASE = 0.50f;

    // ---------------------------------------------------------------- state
    private float aim, aimTwo, sprint, equip = 1, move, bobPhase, time;
    private float lastYaw = Float.NaN, lastPitch;
    private float lastVertical;
    private final Spring sway = new Spring(5), lean = new Spring(4), kick = new Spring(6), air = new Spring(2), rel = new Spring(6);
    private float sinceShot = 10, seat;
    private int flashFrames;
    private float flashTurn;
    private boolean pendingCasing;
    private final Random rnd = new Random();
    public final Pose pose = new Pose();

    /** Damped springs (several channels): x'' = k (target - x) - c x'. */
    static final class Spring {
        final float[] x, v;
        Spring(int n) { x = new float[n]; v = new float[n]; }
        void step(float[] target, float k, float zeta, float dt) {
            float c = 2 * zeta * (float) Math.sqrt(k);
            int steps = Math.max(1, (int) Math.ceil(dt / 0.004f));      // small steps: stiff springs stay stable at low frame rates
            float h = dt / steps;
            for (int s = 0; s < steps; s++)
                for (int i = 0; i < x.length; i++) {
                    float t = target == null ? 0 : target[i];
                    v[i] += (k * (t - x[i]) - c * v[i]) * h;
                    x[i] += v[i] * h;
                }
        }
        void reset() { java.util.Arrays.fill(x, 0); java.util.Arrays.fill(v, 0); }
    }

    /** The gun was just taken out: it rises from below. */
    public void equip() { lower = 0; pose.dropMagShown = false; dropAge = -1; equip = 0; aim = 0; sprint = 0; kick.reset(); rel.reset(); java.util.Arrays.fill(layer, 0); java.util.Arrays.fill(layerVel, 0); sinceShot = 10; pose.casings.clear(); }

    /** A shot: kick, bolt cycle, flash, a casing. 'aimed' = through the sights (a smaller kick). */
    public void shot(boolean aimed, GunType t) {
        float k = aimed ? 0.5f : 1f;
        // channels: 0 back (z), 1 up (y), 2 pitch, 3 yaw, 4 roll, 5 sideways
        kick.v[0] += 1.2f * k * t.kickBack;                                        // ~0.03 back
        kick.v[1] += 0.3f * k * t.kickUp;                                          // ~0.008 up
        kick.v[2] += (aimed ? 70 : 160) * t.kickUp * (0.85f + rnd.nextFloat() * 0.3f);   // ~4 degrees muzzle climb
        kick.v[3] += (rnd.nextFloat() - 0.5f) * 110 * k * t.kickRoll;
        kick.v[4] += (rnd.nextFloat() - 0.35f) * 220 * k * t.kickRoll;
        kick.v[5] += (rnd.nextFloat() - 0.5f) * 0.45f * k;
        sinceShot = 0;
        flashFrames = 1;
        flashTurn = rnd.nextFloat() * 360;
        pendingCasing = true;
    }

    /** The smoothed pose layer: rig move (0..2), rig turn (3..5), gun move (6..8), gun turn (9..11), arms move (12..14). */
    private final float[] layer = new float[LAYER], layerVel = new float[LAYER];
    /** Pose layer channels: rig move, rig turn, gun move, gun turn, arms move, R arm move, R arm turn, L arm move, L arm turn. */
    static final int LAYER = 27;

    /**
     * Moves the pose layer towards its target with a critically damped follow (SmoothDamp) whose time grows with how
     * extreme the pose is: a small pose is reached quickly, a big turn (the gun on its side for the bolt) eases in and
     * out over longer. All channels share one time. 'amount' scales the smoothing (0 = none).
     */
    private void smoothPose(float[] target, float dt, float amount) {
        // one time for every channel (the rig, the gun alone, the arms alone): they all arrive together, so the hands
        // never run ahead of the gun; it grows with the most extreme part of the pose
        float ext = 0;
        for (int g = 0; g < LAYER / 3; g++) {
            boolean turn = g == 1 || g == 3 || g == 6 || g == 8;
            for (int k = 0; k < 3; k++) {
                int i = g * 3 + k;
                ext = Math.max(ext, Math.max(Math.abs(target[i]), Math.abs(layer[i])) / (turn ? 20f : 0.06f));
            }
        }
        // base time 0.0525 s (animation time), + its own length for every 20 degrees / 0.06 blocks
        float time = Math.max(0.001f, amount * 0.0525f * (1 + ext));
        float omega = 2 / time, x = omega * dt, e = 1 / (1 + x + 0.48f * x * x + 0.235f * x * x * x);
        for (int i = 0; i < LAYER; i++) {
            if (amount <= 0) { layer[i] = target[i]; layerVel[i] = 0; continue; }
            float change = layer[i] - target[i], temp = (layerVel[i] + omega * change) * dt;
            layerVel[i] = (layerVel[i] - omega * temp) * e;
            layer[i] = target[i] + (change + temp) * e;
        }
    }

    /** Kept for old callers: a shot without gun numbers. */
    public void shot(boolean aimed) { GunType d = GunType.defaults(); shot(aimed, d); }

    /**
     * A melee strike reached its hit: 'landed' = it struck something (the gun bounces back off it), else it swings through
     * a little further.
     */
    public void meleeHit(boolean landed) {
        if (landed) { rel.v[2] += 1.1f; rel.v[1] += 0.25f; rel.v[4] -= 70; rel.v[3] += (rnd.nextFloat() - 0.5f) * 60; }
        else { rel.v[2] -= 0.35f; rel.v[3] += 25; }
    }

    /**
     * The weights {wind-up, hit} of melee kind 'k' at 'time' seconds after the key, or null when the strike is over (or
     * none): the wind-up eases in; then the gun goes from the wind-up to the hit pose speeding up (it hits at full speed);
     * it stays a moment and eases back to the hold. Times: GunType.meleeTime.
     */
    public static float[] meleeWeights(GunType t, int k, float time) {
        if (t == null || k < 0 || k >= GunType.MELEE.length || time < 0) return null;
        float[] m = t.meleeTime[k];
        float w = m[0], st = Math.max(0.02f, m[1]), h = m[2], b = m[3];
        if (time < w) return new float[]{smooth(time / w), 0};
        time -= w;
        if (time < st) { float u = time / st, e = u * u; return new float[]{w > 0 ? 1 - e : 0, e}; }
        time -= st;
        if (time < h) return new float[]{0, 1};
        time -= h;
        if (time < b) return new float[]{0, 1 - smooth(time / b)};
        return null;
    }

    /** When melee kind 'k' hits (seconds after the key: the end of the strike). */
    public static float meleeImpact(GunType t, int k) { return t.meleeTime[k][0] + Math.max(0.02f, t.meleeTime[k][1]); }

    /** How long melee kind 'k' takes in all (seconds). */
    public static float meleeLength(GunType t, int k) { float[] m = t.meleeTime[k]; return m[0] + Math.max(0.02f, m[1]) + m[2] + m[3]; }

    /** A per pose value of the melee strike now: the wind-up and hit poses' values weighted ('w' = {wind-up, hit}, or null = 0). */
    static float meleeMix(float[][] v, int kind, float[] w, int k) {
        if (w == null) return 0;
        return v[GunType.meleePose(kind, false)][k] * w[0] + v[GunType.meleePose(kind, true)][k] * w[1];
    }

    /** The new magazine clicked in: a short jolt. */
    public void seated() { seat = 1; rel.v[1] -= 0.35f; rel.v[3] += 60; }

    /** Advances the animation by 'dt' seconds and fills {@link #pose} for gun type 't'. */
    public Pose update(GunType t, float dt) {
        dt = Math.max(0, Math.min(0.1f, dt));
        float realDt = dt;
        dt *= t.animSpeed;                 // every motion faster or slower (the aim time and the reload length are in seconds)
        time += dt;
        Pose p = pose;
        p.type = t;
        float s = t.firstPersonScale;
        // ---- layer weights (eased)
        aim = approach(aim, aiming && !reloading && !sprinting ? 1 : 0, realDt / Math.max(0.05f, t.aimTime));
        float a = smooth(aim);
        aimTwo = approach(aimTwo, aim2 ? 1 : 0, realDt / Math.max(0.05f, t.aimTime));
        float b2 = smooth(aimTwo);
        sprint = approach(sprint, sprinting && !reloading ? 1 : 0, dt * 4.5f);
        float sp = smooth(sprint) * (1 - a);
        equip = approach(equip, 1, dt / 0.45f);
        float eq = Math.max(1 - backOut(equip), smooth(lower));      // rising when drawn, going down when put away
        move += (Math.min(1.3f, speed / 4.3f) - move) * (1 - (float) Math.exp(-dt * 8));
        bobPhase += dt * speed * 1.45f;

        // ---- springs: look sway (lags behind the view, settles with a little overshoot)
        if (Float.isNaN(lastYaw)) { lastYaw = yaw; lastPitch = pitch; }
        float dYaw = wrap(yaw - lastYaw), dPitch = pitch - lastPitch;
        lastYaw = yaw; lastPitch = pitch;
        float rate = realDt > 1e-4f ? 1 / realDt : 0, sw = t.swayAmount * (1 - 0.65f * a);
        float yawRate = clamp(dYaw * rate, -400, 400), pitchRate = clamp(dPitch * rate, -400, 400);
        sway.step(new float[]{-yawRate * 0.022f * sw, -pitchRate * 0.02f * sw, -yawRate * 0.00025f * sw, pitchRate * 0.00022f * sw, yawRate * 0.012f * sw}, 140, 0.5f, dt);
        // strafe lean and forward push
        lean.step(new float[]{-strafe * 1.6f * (1 - 0.6f * a), strafe * -0.006f * (1 - 0.7f * a), forward * 0.004f * (1 - 0.7f * a), -forward * 0.6f * (1 - 0.6f * a)}, 60, 0.8f, dt);
        // air: float up while rising, dip on landing
        float vy = vertical * 20;
        if (lastVertical < -0.12f && Math.abs(vertical) < 0.02f && onGround) { air.v[0] -= Math.min(1.6f, -lastVertical * 6); air.v[1] += Math.min(30, -lastVertical * 120); }
        lastVertical = vertical;
        air.step(new float[]{onGround ? 0 : clamp(-vy * 0.006f, -0.03f, 0.03f), onGround ? 0 : clamp(vy * 0.6f, -6, 6)}, 90, 0.55f, dt);
        // recoil: snappy, slight bounce
        kick.step(null, 520, 0.55f, dt);
        seat *= (float) Math.exp(-dt * 12);
        sinceShot += dt;

        // ---- base: hip, aim (sight on the screen centre), tactical sprint, equip
        float[] rag = t.rag, sight = t.sightPoint;
        float[] hip = t.poseMove[0], hr = t.poseTurn[0];
        // the two aims: each puts its own sight point on the screen centre, turned around that point
        float[] aim1 = aimPose(t, t.sightPoint, t.aimDistance, t.poseMove[1], t.poseTurn[1]);
        float[] aimB = aimPose(t, t.sight2Point, t.aim2Distance, t.poseMove[GunType.AIM2], t.poseTurn[GunType.AIM2]);
        float[] am = new float[6];
        for (int k = 0; k < 6; k++) am[k] = lerp(aim1[k], aimB[k], b2);
        float px = lerp(hip[0], am[0], a), py = lerp(hip[1], am[1], a), pz = lerp(hip[2], am[2], a);
        float yawD = lerp(hr[0], am[3], a), pitchD = lerp(hr[1], am[4], a), rollD = lerp(hr[2], am[5], a);
        float st = t.sprintTilt * sp;              // the sprint pose goes through the pose smoothing below
        py -= eq * 0.5f; pz += eq * 0.08f; pitchD -= eq * 60; rollD += eq * 25; yawD += eq * 10;

        // walk / run bob: a figure eight, much less through the sights, bigger when running
        float bob = move * t.bobAmount * (1 - 0.88f * a) * (1 + sp * 1.2f);
        float sx = (float) Math.sin(bobPhase), s2 = (float) Math.sin(bobPhase * 2);
        px += sx * 0.016f * bob;
        py += s2 * 0.008f * bob - 0.004f * bob;
        rollD += sx * 2.5f * bob;
        yawD += (float) Math.cos(bobPhase) * 1.4f * bob;
        pitchD += s2 * 1.2f * bob;
        // breathing
        float idle = (1 - 0.75f * a) * (1 - move * 0.5f);
        py += (float) Math.sin(time * 1.6f) * 0.004f * idle;
        pitchD += (float) Math.sin(time * 1.6f + 0.7f) * 0.6f * idle;
        yawD += (float) Math.sin(time * 0.9f) * 0.3f * idle;
        // springs
        yawD += sway.x[0]; pitchD += sway.x[1]; px += sway.x[2]; py += sway.x[3]; rollD += sway.x[4];
        rollD += lean.x[0]; px += lean.x[1]; pz += lean.x[2]; pitchD += lean.x[3];
        py += air.x[0]; pitchD += air.x[1];
        pz += kick.x[0]; py += kick.x[1]; pitchD += kick.x[2]; yawD += kick.x[3]; rollD += kick.x[4]; px += kick.x[5];

        // ---- reload: keyframes, then a spring so the gun moves with weight
        float magY = 0, magZ = 0, magRot = 0, slide = 0, rOnMag = 0, lOnBolt = 0, boltPull = 0;
        Reload reloadNow = null;
        float[] relT = new float[6];
        float wReload = 0, wBolt = 0;                           // how much of the reload / bolt pose is on (for the gun / arms only moves)
        float side = t.las[0] >= t.rag[0] ? 1 : -1;           // which side the bolt is on (+1 = right)
        float travel = boltTravel(t);
        if (reloading) {
            float r = clamp(reload, 0, 1);
            Reload rs = reloadState(t, r, reloadEmpty);
            reloadNow = rs;
            wReload = rs.tilt;
            float tilt = wReload * t.reloadTilt;
            float[] rm = t.poseMove[3], rr = t.poseTurn[3];
            relT[0] = tilt * rm[0]; relT[1] = tilt * rm[1]; relT[2] = tilt * rm[2];
            relT[3] = tilt * rr[0]; relT[4] = tilt * rr[1]; relT[5] = tilt * rr[2];
            magY = rs.magY; magZ = rs.magZ; magRot = rs.magRot; rOnMag = rs.rOnMag;
            if (reloadEmpty) {
                float b = clamp((r - EMPTY_MAG_END) / (1 - EMPTY_MAG_END), 0, 1);
                float show = rs.show;
                wBolt = show;
                float[] bm = t.poseMove[4], br = t.poseTurn[4];
                float bt = show * t.reloadTilt;
                relT[0] += bt * bm[0]; relT[1] += bt * bm[1]; relT[2] += bt * bm[2];
                relT[3] += bt * br[0]; relT[4] += bt * br[1]; relT[5] += bt * side * br[2];   // roll: towards the bolt's side
                lOnBolt = rs.lOnBolt;
                boltPull = rs.boltPull;
                slide = rs.slide;
                if (b >= BOLT_RELEASE && !boltSlammed) { boltSlammed = true; rel.v[4] += 120; rel.v[1] -= 0.25f; rel.v[5] -= side * 60; }
            }
        } else boltSlammed = false;
        // ---- pose layer (sprint, reload, bolt: the whole rig, the gun alone, the arms alone), smoothed: the further a pose
        // goes from the normal hold, the longer and softer the way into it and back
        float[] sm = t.poseMove[2], sr = t.poseTurn[2];
        float[] L = new float[LAYER];
        for (int k = 0; k < 3; k++) { L[k] = relT[k] + sm[k] * st; L[3 + k] = relT[3 + k] + sr[k] * st; }
        float[] lw = {0, 0, sp, wReload, wBolt};
        for (int i = 2; i < 5; i++)
            for (int k = 0; k < 3; k++) {
                float turn = t.gunTurn[i][k];
                if (i == 4 && k == 2) turn *= side;                  // the bolt's roll: towards the bolt's side
                L[6 + k] += t.gunMove[i][k] * lw[i]; L[9 + k] += turn * lw[i]; L[12 + k] += t.armsMove[i][k] * lw[i];
                L[15 + k] += t.rArmMove[i][k] * lw[i]; L[18 + k] += t.rArmTurn[i][k] * lw[i];
                L[21 + k] += t.lArmMove[i][k] * lw[i]; L[24 + k] += t.lArmTurn[i][k] * lw[i];
            }
        smoothPose(L, dt, t.poseSmooth);
        px += layer[0]; py += layer[1]; pz += layer[2]; yawD += layer[3]; pitchD += layer[4]; rollD += layer[5];
        // ---- melee: the strike's wind-up and hit poses (keyed, not smoothed: a strike is quick)
        float[] mw = reloading ? null : meleeWeights(t, melee, meleeTime);
        int mk = melee;
        px += meleeMix(t.poseMove, mk, mw, 0); py += meleeMix(t.poseMove, mk, mw, 1); pz += meleeMix(t.poseMove, mk, mw, 2);
        yawD += meleeMix(t.poseTurn, mk, mw, 0); pitchD += meleeMix(t.poseTurn, mk, mw, 1); rollD += meleeMix(t.poseTurn, mk, mw, 2);
        // impulses (the magazine click, the bolt slamming home) on their own spring
        rel.step(null, 220, 0.62f, dt);
        px += rel.x[0]; py += rel.x[1]; pz += rel.x[2]; yawD += rel.x[3]; pitchD += rel.x[4]; rollD += rel.x[5];
        pitchD += seat * 3; py += seat * 0.01f;
        // the bolt cycles on every shot (back 25 ms, forward 45 ms), as far back as boltShot says
        float cycle = sinceShot < 0.025f ? sinceShot / 0.025f : 1 - (sinceShot - 0.025f) / 0.045f;
        if (sinceShot < 0.07f) slide = Math.max(slide, travel * t.boltShot * cycle);
        // slide lock: after the last shot the slide stops back (at the catch) until a reload racks it
        if (boltLocked && !reloading && sinceShot >= 0.025f) slide = Math.max(slide, travel * LOCK);
        if (boltHold >= 0) slide = travel * clamp(boltHold, 0, 1);
        slide = clamp(slide, 0, travel);                       // never past its limits (boltFront .. boltBack)

        // ---- matrices: m = the rig (gun and hands); p.gun = the gun drawn (the rig plus the poses' "gun" move and turn
        // around the grip). The hands hold the drawn gun, so they always stay on their points.
        Mat4 m = new Mat4().translate(px, py, pz);
        m.rotate((float) Math.toRadians(yawD), 0, 1, 0);
        m.rotate((float) Math.toRadians(pitchD), 1, 0, 0);
        m.rotate((float) Math.toRadians(rollD), 0, 0, 1);
        m.scale(s, s, s);
        m.translate(-rag[0], -rag[1], -rag[2]);
        if (reloadNow != null) magMatrix(p.mag, t, reloadNow); else p.mag.identity();
        p.slide = slide;
        // the per pose "gun" and "arms" moves, blended like the poses themselves
        float[] gm = new float[3], gt = new float[3], arm = new float[3];
        for (int k = 0; k < 3; k++) {
            // hip <-> aim crossfade (timed by aimTime), the other poses from the smoothed layer
            gm[k] = lerp(t.gunMove[0][k], lerp(t.gunMove[1][k], t.gunMove[GunType.AIM2][k], b2), a) + layer[6 + k];
            gt[k] = lerp(t.gunTurn[0][k], lerp(t.gunTurn[1][k], t.gunTurn[GunType.AIM2][k], b2), a) + layer[9 + k];
            arm[k] = lerp(t.armsMove[0][k], lerp(t.armsMove[1][k], t.armsMove[GunType.AIM2][k], b2), a) + layer[12 + k];
            gm[k] += meleeMix(t.gunMove, mk, mw, k); gt[k] += meleeMix(t.gunTurn, mk, mw, k); arm[k] += meleeMix(t.armsMove, mk, mw, k);
        }
        float[] pivot = new float[4];
        m.transform(rag[0], rag[1], rag[2], pivot);
        gunOnly(p.gun, m, pivot, gm, gt);
        Mat4 G = p.gun;

        // ---- hand targets (view space), all on the drawn gun
        float[] tmp = new float[4], onMag = new float[4], grip = new float[4], guard = new float[4], bolt = new float[4];
        G.transform(rag[0], rag[1], rag[2], grip);
        G.transform(t.lag[0], t.lag[1], t.lag[2], guard);
        p.magMode = reloadNow == null ? 0 : reloadNow.magMode();
        float[] pocket = t.magLeft ? POCKET_LEFT : POCKET_RIGHT;
        if (p.magMode == 0) {
            p.mag.transform(t.ram[0], t.ram[1], t.ram[2], tmp);
            G.transform(tmp[0], tmp[1], tmp[2], onMag);
            // pistol: the hand is not on the old magazine (it drops free): it is on its way to the pocket
            if (reloadNow != null && reloadNow.toPocket() > 0) for (int k = 0; k < 3; k++) onMag[k] += (pocket[k] - onMag[k]) * reloadNow.toPocket();
        } else {
            // the magazine hand: from where it let the old magazine go to the pocket, then up with the new one
            if (p.magMode == 1) {
                Mat4 rel = new Mat4();
                releaseMatrix(rel, t);
                rel.transform(t.ram[0], t.ram[1], t.ram[2], tmp);
                G.transform(tmp[0], tmp[1], tmp[2], onMag);
                float e = reloadNow.pocket();
                for (int k = 0; k < 3; k++) onMag[k] += (pocket[k] - onMag[k]) * e;
            } else {
                magInHand(p.magView, G, t, pocket, reloadNow.fromPocket());
                p.magView.transform(t.ram[0], t.ram[1], t.ram[2], onMag);
            }
        }
        // the bolt hand holds the handle a little outside it (fingers around it) and rides back with it (never past the
        // bolt's back limit, a little further only while it lets go)
        float[] bp = t.boltPoint(new float[]{t.las[0] + side * 0.02f, t.las[1] + 0.01f, t.las[2]},
                slide + (boltPull > 0.9f || (lOnBolt > 0.5f && slide < 0.02f && boltSlammed) ? Math.min(0.04f, 0.25f * travel + 0.01f) : 0), new float[3]);
        G.transform(bp[0], bp[1], bp[2], bolt);
        // which hand does what (like a real AK: the left hand changes the magazine, the right one pulls the bolt on its side)
        float magW = smooth(rOnMag), boltW = smooth(lOnBolt);
        float[] rh = grip.clone(), lh = guard.clone();
        if (t.magLeft) lh = mix3(lh, onMag, magW); else rh = mix3(rh, onMag, magW);
        if (t.boltRight) rh = mix3(rh, bolt, boltW); else lh = mix3(lh, bolt, boltW);
        // the hand off the gun travels on an arc (out a little), not through it
        float magArc = (float) Math.sin(Math.PI * clamp(rOnMag, 0, 1)) * (rOnMag > 0 && rOnMag < 1 ? 1 : 0);
        float[] mh = t.magLeft ? lh : rh;
        mh[1] -= magArc * 0.03f; mh[0] += magArc * (t.magLeft ? -0.02f : 0.02f);
        float boltArc = (float) Math.sin(Math.PI * clamp(lOnBolt, 0, 1)) * (lOnBolt > 0 && lOnBolt < 1 ? 1 : 0);
        float[] bh = t.boltRight ? rh : lh;
        bh[1] += boltArc * 0.05f; bh[0] += boltArc * side * 0.03f;

        // ---- arms: rigid Minecraft arms, the palm on the target, pointing back to the shoulder side
        float pix = s / 16f * t.armSize;
        // each arm points from its hand towards its "points to", which moves along with the hand by half (ARM_FOLLOW): a hand
        // that moves (to the magazine, the bolt, through the sights) carries its arm partly along instead of swinging it at
        // the camera, and the arm still comes in at an angle from its side
        float[] hipGrip = hipPoint(t, rag), hipGuard = hipPoint(t, t.lag);
        float[] rTo = new float[3], lTo = new float[3];
        for (int k = 0; k < 3; k++) {         // the "points to" moves half as far as the hand does
            rTo[k] = t.shoulderRight[k] + (rh[k] - hipGrip[k]) * ARM_FOLLOW;
            lTo[k] = t.shoulderLeft[k] + (lh[k] - hipGuard[k]) * ARM_FOLLOW;
        }
        // the poses' "arms" move: both arms swing around their hands (where they come from moves), the hands stay on the gun
        for (int k = 0; k < 3; k++) { rTo[k] += arm[k] * ARMS_SWING; lTo[k] += arm[k] * ARMS_SWING; }
        // each arm's own move and turn: the Arms page's plus the poses' (hip <-> aims crossfaded, the others from the layer)
        float[] rOff = new float[3], rRot = new float[3], lOff = new float[3], lRot = new float[3];
        for (int k = 0; k < 3; k++) {
            rOff[k] = t.armRightOffset[k] + aimMix(t.rArmMove, k, a, b2) + layer[15 + k];
            rRot[k] = t.armRightRotation[k] + aimMix(t.rArmTurn, k, a, b2) + layer[18 + k];
            lOff[k] = t.armLeftOffset[k] + aimMix(t.lArmMove, k, a, b2) + layer[21 + k];
            lRot[k] = t.armLeftRotation[k] + aimMix(t.lArmTurn, k, a, b2) + layer[24 + k];
            rOff[k] += meleeMix(t.rArmMove, mk, mw, k); rRot[k] += meleeMix(t.rArmTurn, mk, mw, k);
            lOff[k] += meleeMix(t.lArmMove, mk, mw, k); lRot[k] += meleeMix(t.lArmTurn, mk, mw, k);
        }
        // the hand on the magazine / the bolt turns as set for that (blended in as the hand gets there)
        float[] mRot = t.magLeft ? lRot : rRot, bRot = t.boltRight ? rRot : lRot;
        for (int k = 0; k < 3; k++) { mRot[k] += t.magHandRotation[k] * magW; bRot[k] += t.boltHandRotation[k] * boltW; }
        armMatrix(p.rightArm, rh, rTo, rOff, rRot, pix, t.armLength, t.armThickness, p.rightHand);
        armMatrix(p.leftArm, lh, lTo, lOff, lRot, pix, t.armLength, t.armThickness, p.leftHand);

        // ---- the old magazine falling out of the picture after it is let go
        dropMagazine(p, t, G, reloadNow, realDt, s);

        // ---- flash, muzzle, casings
        float[] fo = t.flashOffset;
        p.gun.transform(t.muzzlePoint[0] + fo[0], t.muzzlePoint[1] + fo[1], t.muzzlePoint[2] + fo[2], p.muzzle);
        if (pendingCasing) {
            pendingCasing = false;
            Casing c = new Casing();
            float[] e = new float[4];
            p.gun.transform(t.ejectPoint[0], t.ejectPoint[1], t.ejectPoint[2], e);
            c.x = e[0]; c.y = e[1]; c.z = e[2];
            float sc = s / 0.4f;
            c.vx = side * (1.3f + rnd.nextFloat() * 0.5f) * sc; c.vy = (1.1f + rnd.nextFloat() * 0.5f) * sc; c.vz = (0.2f + rnd.nextFloat() * 0.3f) * sc;
            c.spinVel = (rnd.nextFloat() * 2 - 1) * 1400;
            p.casings.add(c);
            if (p.casings.size() > 12) p.casings.remove(0);
        }
        for (int i = p.casings.size() - 1; i >= 0; i--) {
            Casing c = p.casings.get(i);
            c.age += realDt;
            c.vy -= 6f * realDt * s / 0.4f;
            c.x += c.vx * realDt; c.y += c.vy * realDt; c.z += c.vz * realDt;
            c.spin += c.spinVel * realDt;
            if (c.age > 0.7f) p.casings.remove(i);
        }
        p.flash = flashFrames > 0 ? 1 : 0;
        if (flashFrames > 0) flashFrames--;
        p.flashTurn = flashTurn;
        p.aim = a;
        return p;
    }

    private boolean boltSlammed;

    // the falling magazine: its matrix when let go, its middle then (view space), speed, how long it has fallen
    private final Mat4 dropStart = new Mat4();
    private final float[] dropC = new float[3], dropV = new float[3], lastMagC = new float[3];
    private float dropAge = -1, dropSpin, lastMagDt;
    private boolean lastMagOnGun;

    /**
     * The moment a reload lets the old magazine go (magazine mode 0 -> 1) it is not just hidden: it keeps falling in the
     * view with the speed it had (the pistol's slides down out of the grip, the rifle's is pulled down), plus gravity
     * (down in the world: it depends on the look up / down), turning over a little, until it is well out of the picture.
     */
    private void dropMagazine(Pose p, GunType t, Mat4 G, Reload rs, float dt, float s) {
        int mode = rs == null ? 0 : rs.magMode();
        float[] c = new float[4];
        if (t.mag != null && rs != null && mode == 0) {
            float[] mb = GunType.bounds(t.mag), tmp = new float[4];
            p.mag.transform((mb[0] + mb[3]) / 2, (mb[1] + mb[4]) / 2, (mb[2] + mb[5]) / 2, tmp);
            G.transform(tmp[0], tmp[1], tmp[2], c);
        }
        if (rs != null && mode == 1 && lastMagOnGun && t.mag != null) {
            // let go just now: start from where it was let go, with its speed
            Mat4 rel = new Mat4();
            releaseMatrix(rel, t);
            dropStart.set(G).mul(rel);
            float[] mb = GunType.bounds(t.mag), c0 = new float[4];
            dropStart.transform((mb[0] + mb[3]) / 2, (mb[1] + mb[4]) / 2, (mb[2] + mb[5]) / 2, c0);
            dropC[0] = c0[0]; dropC[1] = c0[1]; dropC[2] = c0[2];
            float k = lastMagDt > 1e-4f ? 1 / lastMagDt : 0;
            for (int i = 0; i < 3; i++) dropV[i] = clamp((c0[i] - lastMagC[i]) * k, -3, 3);
            dropAge = 0;
            dropSpin = (t.pistol ? 70 : 140) * (rnd.nextFloat() < 0.5f ? 1 : -1);
        }
        lastMagOnGun = rs != null && mode == 0 && t.mag != null;
        if (lastMagOnGun) { lastMagC[0] = c[0]; lastMagC[1] = c[1]; lastMagC[2] = c[2]; lastMagDt = dt; }
        if (dropAge < 0) { p.dropMagShown = false; return; }
        dropAge += dt;
        float a = dropAge, g = 12 * s / 0.4f;          // blocks / s^2 (about a real fall), in the gun's view scale
        double pr = Math.toRadians(pitch);
        float gy = (float) -Math.cos(pr) * g, gz = (float) -Math.sin(pr) * g;   // world down in view space
        float x = dropC[0] + dropV[0] * a, y = dropC[1] + dropV[1] * a + 0.5f * gy * a * a, z = dropC[2] + dropV[2] * a + 0.5f * gz * a * a;
        p.dropMag.identity().translate(x, y, z).rotate((float) Math.toRadians(dropSpin * a), 1, 0, 0.3f)
                .translate(-dropC[0], -dropC[1], -dropC[2]).mul(dropStart);
        // gone once far below the picture (or after a second)
        p.dropMagShown = a < 1.5f && y > -1.5f && z < 0.5f;
        if (!p.dropMagShown) dropAge = -1;
    }

    /** How far the bolt can go back (model units): from its limit points boltFront to boltBack. */
    public static float boltTravel(GunType t) { return t.boltTravel; }

    /** Where a reload is at (also for the third person hold): the tilt weight, the magazine's offset and rock, the hands. */
    /**
     * rOnMag: how much the magazine hand is busy with the magazine; lOnBolt: how much the bolt hand is on the bolt (see
     * GunType.magLeft / boltRight). magMode: 0 = the magazine is on the gun (magY / magZ / magRot), 1 = none (the old one
     * was let go and falls to the ground, the hand goes to the pocket: 'pocket' 0..1), 2 = the new one in the hand on its
     * way from the pocket to the gun ('fromPocket' 0..1).
     */
    public record Reload(float tilt, float magY, float magZ, float magRot, float rOnMag, float show, float lOnBolt, float boltPull, float slide,
                         int magMode, float pocket, float fromPocket, float magOut, float toPocket, boolean pistol) {
        Reload(float tilt, float magY, float magZ, float magRot, float rOnMag, float show, float lOnBolt, float boltPull, float slide,
               int magMode, float pocket, float fromPocket) {
            this(tilt, magY, magZ, magRot, rOnMag, show, lOnBolt, boltPull, slide, magMode, pocket, fromPocket, 0, 0, false);
        }
    }

    /** The pocket the new magazine comes from (view space, below the picture): for a left and a right magazine hand. */
    static final float[] POCKET_LEFT = {-0.30f, -0.72f, -0.12f}, POCKET_RIGHT = {0.34f, -0.72f, -0.08f};

    /** Reload timeline (magazine part): the old magazine is let go, the hand is at the pocket, the new one reaches the gun. */
    public static final float MAG_DROP = 0.40f, POCKET = 0.50f, MAG_INSERT = 0.62f;
    /** The magazine where it is let go and where the new one starts going in (model units / degrees: y, z, rock). */
    static final float[] RELEASE = {-0.35f, -0.05f, 28}, INSERT = {-0.45f, -0.06f, 30};

    /**
     * The reload timeline at progress r (0..1): the gun tilts (weight); the hand takes the magazine, rocks it out and pulls
     * it down, lets it go (it falls to the ground), goes to the pocket, brings the new one up angled, hooks it in and rocks
     * it back (click); on an empty reload then the bolt: the gun shows it, the other hand pulls it back ('travel' model
     * units) and lets go.
     */
    public static Reload reloadState(float r, boolean empty, float travel) { return reloadState(r, empty, travel, false, false); }

    /** The reload of gun 't' at progress r: its style (rifle / pistol), its bolt travel and slide lock. */
    public static Reload reloadState(GunType t, float r, boolean empty) {
        return reloadState(r, empty, t.boltTravel, t.pistol, t.slideLock);
    }

    /** Pistol magazine (in magazine lengths along its axis): where it is let go and where the new one starts going in. */
    static final float PISTOL_RELEASE = 0.55f, PISTOL_INSERT = 0.6f;

    public static Reload reloadState(float r, boolean empty, float travel, boolean pistol, boolean lock) {
        if (pistol) return pistolState(r, empty, travel, lock);
        r = clamp(r, 0, 1);
        float q = clamp(r / (empty ? EMPTY_MAG_END : 1f), 0, 1);
        float tilt = key(q, new float[]{0, 0.14f, 0.45f, 0.70f, 0.80f, 1}, new float[]{0, 1, 0.92f, 1, 1, empty ? 1 : 0});
        float magY = 0, magZ = 0, magRot = 0, pocket = 0, fromPocket = 0;
        int mode;
        if (q < MAG_DROP) {
            mode = 0;
            float[] tq = {0, 0.20f, MAG_OUT, MAG_DROP};
            magY = key(q, tq, new float[]{0, 0, -0.02f, RELEASE[0]});
            magZ = key(q, tq, new float[]{0, 0, 0, RELEASE[1]});
            magRot = key(q, tq, new float[]{0, 0, 22, RELEASE[2]});
        } else if (q < POCKET) {
            mode = 1;
            pocket = easeInOut(ramp(q, MAG_DROP, POCKET - 0.02f));
        } else if (q < MAG_INSERT) {
            mode = 2;
            pocket = 1;
            fromPocket = easeInOut(ramp(q, POCKET, MAG_INSERT));
        } else {
            mode = 0;
            float[] tq = {MAG_INSERT, 0.70f, MAG_IN, 1};
            magY = key(q, tq, new float[]{INSERT[0], -0.04f, 0, 0});
            magZ = key(q, tq, new float[]{INSERT[1], -0.01f, 0, 0});
            magRot = key(q, tq, new float[]{INSERT[2], 20, 0, 0});
        }
        float rOnMag = key(q, new float[]{0, 0.08f, 0.20f, 0.80f, 0.94f, 1}, new float[]{0, 0, 1, 1, 0, 0});
        float show = 0, lOnBolt = 0, pull = 0;
        if (empty) {
            float b = clamp((r - EMPTY_MAG_END) / (1 - EMPTY_MAG_END), 0, 1);
            show = key(b, new float[]{0, 0.2f, 0.75f, 1}, new float[]{0, 1, 1, 0});
            lOnBolt = key(b, new float[]{0, 0.05f, 0.25f, 0.62f, 0.88f, 1}, new float[]{0, 0, 1, 1, 0, 0});
            pull = b < 0.28f ? 0 : b < BOLT_RELEASE ? easeInOut(ramp(b, 0.28f, BOLT_RELEASE)) : b < BOLT_RELEASE + 0.03f ? 1 - ramp(b, BOLT_RELEASE, BOLT_RELEASE + 0.03f) : 0;
        }
        return new Reload(tilt, magY, magZ, magRot, rOnMag, show, lOnBolt, pull, boltSlide(empty, r, pull, travel, lock), mode, pocket, fromPocket, 0, 0, false);
    }

    /**
     * Where the bolt is in a reload: pulled ('pull' of its travel); with a slide lock an empty gun's slide stays at the
     * catch until the hand racks it (pulls it from there to the back limit and lets go).
     */
    static float boltSlide(boolean empty, float r, float pull, float travel, boolean lock) {
        float sl = travel * pull;
        if (lock && empty) {
            float b = (r - EMPTY_MAG_END) / (1 - EMPTY_MAG_END);
            if (b < BOLT_RELEASE) sl = Math.max(sl, travel * LOCK);
        }
        return clamp(sl, 0, travel);
    }

    /**
     * Pistol reload: the gun turns its grip to the free hand, which leaves the gun for the pocket at once; the thumb drops
     * the magazine, which slides straight out of the grip along its axis and falls (let go at MAG_DROP); the hand brings
     * the new one from the pocket, lines it up under the grip and pushes it up until it clicks, then takes the gun again.
     * Empty: then the free hand racks the slide (over the top, from its back end) and lets it go. Same event times as the
     * rifle reload (sounds, the dropped magazine, the third person and NPCs).
     */
    static Reload pistolState(float r, boolean empty, float travel, boolean lock) {
        r = clamp(r, 0, 1);
        float q = clamp(r / (empty ? EMPTY_MAG_END : 1f), 0, 1);
        float tilt = key(q, new float[]{0, 0.12f, 0.45f, 0.70f, 0.84f, 1}, new float[]{0, 1, 0.95f, 1, 1, empty ? 1 : 0});
        float out = 0, rot = 0, pocket = 0, fromPocket = 0, toPocket = 0;
        int mode;
        if (q < MAG_DROP) {
            mode = 0;
            // the button: the magazine starts sliding out of the grip and slides faster and faster (one smooth curve: its
            // speed when it is let go carries on into the fall, see dropMagazine)
            float u = ramp(q, 0.08f, MAG_DROP);
            out = PISTOL_RELEASE * (float) Math.pow(u, 1.7);
            toPocket = 1;
        } else if (q < POCKET) {
            mode = 1;
            pocket = 1;
        } else if (q < MAG_INSERT) {
            mode = 2;
            pocket = 1;
            fromPocket = easeInOut(ramp(q, POCKET, MAG_INSERT));
        } else {
            mode = 0;
            // lined up under the grip, then pushed up (quick at the end) until it clicks in at MAG_IN
            float u = ramp(q, MAG_INSERT, MAG_IN);
            out = PISTOL_INSERT * (1 - (float) Math.pow(u, 1.6));
            rot = 6 * (1 - smooth(ramp(q, MAG_INSERT, 0.70f)));
        }
        // the free hand: leaves the gun at once and is at the pocket when the old magazine falls; back on the gun after the click
        float rOnMag = key(q, new float[]{0, 0.04f, 0.38f, 0.84f, 0.96f, 1}, new float[]{0, 0, 1, 1, 0, 0});
        float show = 0, lOnBolt = 0, pull = 0;
        if (empty) {
            float b = clamp((r - EMPTY_MAG_END) / (1 - EMPTY_MAG_END), 0, 1);
            show = key(b, new float[]{0, 0.2f, 0.75f, 1}, new float[]{0, 1, 1, 0});
            lOnBolt = key(b, new float[]{0, 0.05f, 0.25f, 0.62f, 0.88f, 1}, new float[]{0, 0, 1, 1, 0, 0});
            pull = b < 0.28f ? 0 : b < BOLT_RELEASE ? easeInOut(ramp(b, 0.28f, BOLT_RELEASE)) : b < BOLT_RELEASE + 0.03f ? 1 - ramp(b, BOLT_RELEASE, BOLT_RELEASE + 0.03f) : 0;
        }
        return new Reload(tilt, 0, 0, rot, rOnMag, show, lOnBolt, pull, boltSlide(empty, r, pull, travel, lock), mode, pocket, fromPocket, out, toPocket, true);
    }

    /** The magazine's model space matrix at an offset (model units) and rock (degrees) around the gun's magazine pivot. */
    public static void magMatrix(Mat4 out, GunType t, float y, float z, float rot) {
        float[] pv = t.magPivot;
        out.identity().translate(0, y, z).translate(pv[0], pv[1], pv[2]).rotate((float) Math.toRadians(rot), 1, 0, 0).translate(-pv[0], -pv[1], -pv[2]);
    }

    /** Pistol: the magazine slid 'out' magazine lengths out of the grip along its axis, tipped 'rot' degrees (model space). */
    public static void pistolMag(Mat4 out, GunType t, float outLen, float rot) {
        float d = outLen * t.magLength;
        float[] ax = t.magAxis, pv = t.magPivot;
        out.identity().translate(ax[0] * d, ax[1] * d, ax[2] * d);
        if (rot != 0) out.translate(pv[0], pv[1], pv[2]).rotate((float) Math.toRadians(rot), 1, 0, 0).translate(-pv[0], -pv[1], -pv[2]);
    }

    /** Where the old magazine is let go (model space matrix of the magazine), for the gun's reload style. */
    public static void releaseMatrix(Mat4 out, GunType t) {
        if (t.pistol) pistolMag(out, t, PISTOL_RELEASE, 0);
        else magMatrix(out, t, RELEASE[0], RELEASE[1], RELEASE[2]);
    }

    /** Where the new magazine starts going in (model space matrix of the magazine), for the gun's reload style. */
    public static void insertMatrix(Mat4 out, GunType t) {
        if (t.pistol) pistolMag(out, t, PISTOL_INSERT, 6);
        else magMatrix(out, t, INSERT[0], INSERT[1], INSERT[2]);
    }

    /**
     * The new magazine in the hand between the pocket and the gun: 'gunM' = the gun's model matrix (view or body space),
     * 'pocket' = where the hand takes it from (same space). The magazine keeps the angle it goes in with; it travels from
     * the pocket (its hand point there) to where it starts going in. Writes the matrix (model units to that space).
     */
    public static void magInHand(Mat4 out, Mat4 gunM, GunType t, float[] pocket, float u) {
        Mat4 ins = new Mat4();
        insertMatrix(ins, t);
        Mat4 start = new Mat4().set(gunM).mul(ins);
        float[] h = new float[4];
        start.transform(t.ram[0], t.ram[1], t.ram[2], h);
        float e = smooth(u);
        out.set(start);
        out.m[12] += (pocket[0] - h[0]) * (1 - e);
        out.m[13] += (pocket[1] - h[1]) * (1 - e);
        out.m[14] += (pocket[2] - h[2]) * (1 - e);
    }

    /** The magazine's model space matrix for a reload state (offset, rocked around the gun's magazine pivot). */
    public static void magMatrix(Mat4 out, GunType t, Reload rs) {
        if (rs.pistol()) pistolMag(out, t, rs.magOut(), rs.magRot());
        else magMatrix(out, t, rs.magY, rs.magZ, rs.magRot);
    }

    /** A per pose value at the hip, the aim and the second aim, crossfaded like those poses ('a' aim, 'b2' second aim). */
    static float aimMix(float[][] v, int k, float a, float b2) { return lerp(v[0][k], lerp(v[1][k], v[GunType.AIM2][k], b2), a); }

    /** How much the point an arm runs to follows its hand away from the hip pose (0 = fixed, 1 = the arm only moves). */
    static final float ARM_FOLLOW = 0.5f;

    /** A gun point in view space at the plain hip pose (no motion; with the hip pose's "gun" move and turn). */
    static float[] hipPoint(GunType t, float[] pt) {
        float s = t.firstPersonScale;
        Mat4 m = new Mat4().translate(t.hipPosition[0], t.hipPosition[1], t.hipPosition[2]);
        m.rotate((float) Math.toRadians(t.hipRotation[0]), 0, 1, 0);
        m.rotate((float) Math.toRadians(t.hipRotation[1]), 1, 0, 0);
        m.rotate((float) Math.toRadians(t.hipRotation[2]), 0, 0, 1);
        m.scale(s, s, s);
        m.translate(-t.rag[0], -t.rag[1], -t.rag[2]);
        float[] pivot = new float[4], o = new float[4];
        m.transform(t.rag[0], t.rag[1], t.rag[2], pivot);
        Mat4 g = new Mat4();
        gunOnly(g, m, pivot, t.gunMove[0], t.gunTurn[0]);
        g.transform(pt[0], pt[1], pt[2], o);
        return new float[]{o[0], o[1], o[2]};
    }

    /** The gun drawn: the rig 'm' moved by 'move' and turned by 'turn' (yaw, pitch, roll) around 'pivot' (view space). */
    static void gunOnly(Mat4 out, Mat4 m, float[] pivot, float[] move, float[] turn) {
        out.identity().translate(pivot[0] + move[0], pivot[1] + move[1], pivot[2] + move[2]);
        out.rotate((float) Math.toRadians(turn[0]), 0, 1, 0);
        out.rotate((float) Math.toRadians(turn[1]), 1, 0, 0);
        out.rotate((float) Math.toRadians(turn[2]), 0, 0, 1);
        out.translate(-pivot[0], -pivot[1], -pivot[2]);
        out.mul(m);
    }

    /** How far the poses' "arms" move swings the arms (the point they come from moves this many blocks per block). */
    static final float ARMS_SWING = 1.5f;

    /** The slide lock holds the slide this far back (share of its travel: the catch, a little before the back limit). */
    public static final float LOCK = 0.85f;

    /** Pixel position of the palm along the arm box (12 = the end of the hand). */
    public static final float PALM = 10.5f;

    /**
     * The arm box matrix: palm at hand + offset, the arm running from there towards 'toward' (the shoulder side), turned
     * by rot (yaw, pitch, roll degrees) around the palm. pix = one skin pixel (view units). Writes the palm position.
     */
    static void armMatrix(Mat4 out, float[] hand, float[] toward, float[] offset, float[] rot, float pix, float length, float thick, float[] palm) {
        float hx = hand[0] + offset[0], hy = hand[1] + offset[1], hz = hand[2] + offset[2];
        palm[0] = hx; palm[1] = hy; palm[2] = hz;
        // +y of the box runs from the shoulder end to the hand: from 'toward' to the palm
        float yx = hx - toward[0], yy = hy - toward[1], yz = hz - toward[2];
        float yl = (float) Math.sqrt(yx * yx + yy * yy + yz * yz);
        if (yl < 1e-5f) { yx = 0; yy = 1; yz = 0; yl = 1; }
        yx /= yl; yy /= yl; yz /= yl;
        // x: across the arm, level (up x y), z completes the frame. Like Minecraft's own arm (ModelBiped, raised forward):
        // the box's -x face (the outer side of the right arm, the inner side of the left one in the skin) faces right, its
        // front (-z) faces up, so the outside of the shoulder and the armpit are where they belong (it was turned half
        // round the arm: the armpit outside, the front of the arm underneath)
        float xx = yz, xy = 0, xz = -yx;
        float xl = (float) Math.sqrt(xx * xx + xy * xy + xz * xz);
        if (xl < 1e-5f) { xx = 1; xy = 0; xz = 0; xl = 1; }
        xx /= xl; xy /= xl; xz /= xl;
        float zx = xy * yz - xz * yy, zy = xz * yx - xx * yz, zz = xx * yy - xy * yx;
        Mat4 frame = new Mat4();
        float[] m = frame.m;
        // box() takes pixels / 16: one unit here = 16 skin pixels
        float w = pix * thick, l = pix * length;
        m[0] = xx * w * 16; m[1] = xy * w * 16; m[2] = xz * w * 16;
        m[4] = yx * l * 16; m[5] = yy * l * 16; m[6] = yz * l * 16;
        m[8] = zx * w * 16; m[9] = zy * w * 16; m[10] = zz * w * 16;
        m[12] = -yx * l * PALM; m[13] = -yy * l * PALM; m[14] = -yz * l * PALM;     // the palm at the origin of the turn
        out.identity().translate(hx, hy, hz);
        out.rotate((float) Math.toRadians(rot[0]), 0, 1, 0);
        out.rotate((float) Math.toRadians(rot[1]), 1, 0, 0);
        out.rotate((float) Math.toRadians(rot[2]), 0, 0, 1);
        out.mul(frame);
    }

    /** Through the sights far enough to hide the crosshair and narrow the spread. */
    public float aimAmount() { return smooth(aim); }

    /** How far the second aim is in (0..1; it counts while aiming). */
    public float aim2Amount() { return smooth(aimTwo); }

    /**
     * An aim pose: the grip position and turn (yaw, pitch, roll) that put 'sight' at the screen centre, 'dist' in front
     * of the eye, plus 'move', with the gun turned by 'turn' around the sight (so a canted sight stays centred).
     */
    static float[] aimPose(GunType t, float[] sight, float dist, float[] move, float[] turn) {
        float s = t.firstPersonScale;
        Mat4 r = new Mat4();
        r.rotate((float) Math.toRadians(turn[0]), 0, 1, 0);
        r.rotate((float) Math.toRadians(turn[1]), 1, 0, 0);
        r.rotate((float) Math.toRadians(turn[2]), 0, 0, 1);
        float[] v = new float[4];
        r.transform(s * (sight[0] - t.rag[0]), s * (sight[1] - t.rag[1]), s * (sight[2] - t.rag[2]), v);
        return new float[]{move[0] - v[0], move[1] - v[1], -dist + move[2] - v[2], turn[0], turn[1], turn[2]};
    }

    /** Keyframes: value at x for key times ts (ascending) and values vs, eased in and out between keys. */
    static float key(float x, float[] ts, float[] vs) {
        if (x <= ts[0]) return vs[0];
        for (int i = 1; i < ts.length; i++)
            if (x <= ts[i]) return lerp(vs[i - 1], vs[i], easeInOut(ramp(x, ts[i - 1], ts[i])));
        return vs[vs.length - 1];
    }

    static float approach(float v, float target, float step) { return v < target ? Math.min(target, v + step) : Math.max(target, v - step); }
    static float smooth(float x) { x = clamp(x, 0, 1); return x * x * (3 - 2 * x); }
    static float easeInOut(float x) { x = clamp(x, 0, 1); return x < 0.5f ? 4 * x * x * x : 1 - (float) Math.pow(-2 * x + 2, 3) / 2; }
    /** Ease out with a small overshoot (equip). */
    static float backOut(float x) { x = clamp(x, 0, 1); float c1 = 1.4f, c3 = c1 + 1; return 1 + c3 * (float) Math.pow(x - 1, 3) + c1 * (float) Math.pow(x - 1, 2); }
    static float ramp(float x, float a, float b) { return clamp((x - a) / (b - a), 0, 1); }
    static float lerp(float a, float b, float t) { return a + (b - a) * t; }
    static float clamp(float v, float lo, float hi) { return Math.max(lo, Math.min(hi, v)); }
    static float wrap(float d) { d %= 360; if (d > 180) d -= 360; if (d < -180) d += 360; return d; }
    static float[] mix3(float[] a, float[] b, float t) { return new float[]{lerp(a[0], b[0], t), lerp(a[1], b[1], t), lerp(a[2], b[2], t)}; }
    static void set3(float[] o, float x, float y, float z) { o[0] = x; o[1] = y; o[2] = z; }
    static float dist3(float[] a, float[] b) { float x = a[0] - b[0], y = a[1] - b[1], z = a[2] - b[2]; return (float) Math.sqrt(x * x + y * y + z * z); }
}
