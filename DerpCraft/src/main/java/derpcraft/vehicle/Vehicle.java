package derpcraft.vehicle;

import derpcraft.block.Block;
import derpcraft.block.Blocks;
import derpcraft.util.Mat4;
import derpcraft.world.World;
import derpcraft.world.be.BlockEntity;

/**
 * A car (or any model with wheel groups) standing or driving in the world. Position = the point between the axles on
 * the ground; yaw like the player's (0 = the front looks to +z / south, 90 = west). Physics: a bicycle model (the
 * rear axle follows, the front wheels steer), terrain following with pitch and roll, steps up to one block, gravity,
 * and collisions with blocks and other vehicles.
 */
public final class Vehicle {
    public static final int NONE = -1, PLAYER = -2;
    /** Highest step the wheels climb (blocks): one block, so cars can drive over ordinary terrain. */
    public static final float STEP = 1.05f;
    /** speedLimit when nobody asks for less. */
    public static final float NO_LIMIT = 100;

    /** One block place (block1..block4): a block state and its block entity (chest items, furnace...). */
    public static final class Slot {
        public int state;
        public BlockEntity be;
        public float mining;
    }

    public final int id;
    public String model;
    /**
     * Position: x, z and the height y as shown and used for everything outside the physics (drawing, riders, camera, collision
     * with people). The physics itself runs on the height of the ground 'gy'; y follows it smoothly, so steps of terrain
     * do not shake the car.
     */
    public double x, y, z, px, py, pz;
    /** Height the wheels rest at (exact), and the speed of the smoothing of y (blocks per tick). */
    double gy;
    private float yVel;
    public float yaw, pitch, roll, prevYaw, prevPitch, prevRoll;
    /** Speed along the front (blocks per tick, negative = reversing), vertical speed. */
    public float speed, vy;
    /** Steering -1 (right) .. 1 (left) and the wheel turn angle (radians). */
    public float steer, prevSteer, spin, prevSpin;
    public boolean onGround = true;
    /** Doors, hood, trunk: open (target) and how far (0..1) per openable part of the model. */
    public boolean[] openTarget = new boolean[0];
    public float[] open = new float[0], prevOpen = new float[0];
    /** Doors an NPC opened to get in: closed again when the timer (ticks) runs out. */
    int[] closeTimer = new int[0];
    boolean[] autoClose = new boolean[0];
    /** Who sits in each seat: NONE, PLAYER or an NPC id. */
    public int[] riders = new int[0];
    public final Slot[] slots = new Slot[VehicleModel.MAX_SLOTS];
    // controls of this tick (set by the driver)
    public float throttle, steerIn;
    public boolean handbrake;
    /** Highest speed the autopilot asks for (NPC drivers). */
    public float speedLimit = NO_LIMIT;
    /** Sound events of this tick (read and cleared by the sounds): skidding, the speed of a crash. */
    public boolean skidding;
    public float crashed;
    /** The driver's gas of the last tick (engine sound load). */
    public float lastThrottle;
    /** The driver is braking this tick (counter-throttle or handbrake without a drift, while moving): for the brake sound. */
    public boolean braking;
    /** Sideways speed (blocks per tick, + = to the right of the car): non-zero while the tyres slide. */
    public float lat;
    /** How much the tyres have let go (0..1, Space with gas and steering at speed), and the police turn (> 0 = ticks since it began). */
    public float drift;
    public int police, policeCool;
    private float policeDir, policeYaw;
    /** How much the tyres slide this tick (0..1, for the tyre marks, smoke and sound). */
    public float slip;
    /** Rolling over: the extra roll angle (degrees, up to +-360), the ticks left, the way it rolls, and the build-up of sideways force. */
    public float flip, prevFlip;
    public int flipTicks;
    private float flipDir, rollRisk;
    /** Set when the car starts to roll (the speed) / lands again: the riders are hurt (read and cleared by the vehicles). */
    public float flipImpact, flipLanded;
    /** The speed (blocks per tick) a person pushing it from behind asks for (0 = nobody); set before the tick like the controls. */
    public float push;
    public static final int FLIP_TICKS = 30;
    // ---- a wreck (Ctrl + End): burnt out, the parts bent, smoking for minutes
    /** Blown up; ticks since; how black it is (0..1); per part {x, y, z} turn in degrees and how far open it hangs (doors, hood, trunk). */
    public boolean wrecked;
    public int wreckAge;
    public float charred;
    public long wreckSeed;
    public float[] dmgTurn = new float[0], dmgOpen = new float[0];

    // ------------------------------------------------------------------ bullet holes (kept until /vehicle clear)
    /** Bullet holes: {part index, x, y, z, nx, ny, nz} in the part's own (model) coordinates: they move with doors. */
    public final java.util.List<float[]> holes = new java.util.ArrayList<>();
    public static final int MAX_HOLES = 400;
    /** Ticks until a blast next to it sets it off (0 = none); ticks it has stood in fire and when that blows it up. */
    public int blastIn, fireTicks, fireFuse;

    /** A bullet hole on a part (model coordinates of the part, the face's normal). The oldest go when there are too many. */
    public void addHole(int part, float x, float y, float z, float nx, float ny, float nz) {
        if (holes.size() >= MAX_HOLES) holes.remove(0);
        holes.add(new float[]{part, x, y, z, nx, ny, nz});
    }

    // ------------------------------------------------------------------ parts blown off in the explosion
    /** A part torn off by the explosion: it flies, tumbles, lands and stays there (until the car is repaired). */
    public static final class Debris {
        /** The part's matrix when it came off, relative to the anchor point (the car's position then). */
        public Mat4 m0;
        public double ax, ay, az;
        /** Its middle (relative to the anchor), the offset it flew, its speed; turn axis, angle, spin (degrees per tick). */
        public float cx, cy, cz;
        public double ox, oy, oz, pox, poy, poz, vx, vy, vz;
        public float axX, axY = 1, axZ, angle, prevAngle, spin;
        public boolean rest;
    }
    /** Per part (index in the model's parts): the flying / landed piece, or null while attached. */
    public Debris[] debris = new Debris[0];
    /** Loaded as a wreck: the pieces are put where they would have landed at the first tick. */
    public boolean settleDebris;

    /** Whether a part was blown off (not on the car any more: no clicks, no bullets, drawn where it lies). */
    public boolean detached(int part) { return part >= 0 && part < debris.length && debris[part] != null; }

    /** The matrix a part is drawn with (relative to the camera): on the car, or where its blown off piece is. */
    public Mat4 partMatrix(VehicleModel m, int i, float partial, double camX, double camY, double camZ) {
        VehicleModel.Part p = m.parts.get(i);
        Debris d = i < debris.length ? debris[i] : null;
        if (d != null) {
            float ang = d.prevAngle + (d.angle - d.prevAngle) * partial;
            double ox = d.pox + (d.ox - d.pox) * partial, oy = d.poy + (d.oy - d.poy) * partial, oz = d.poz + (d.oz - d.poz) * partial;
            Mat4 r = new Mat4().translate((float) (d.ax + ox - camX), (float) (d.ay + oy - camY), (float) (d.az + oz - camZ));
            r.translate(d.cx, d.cy, d.cz).rotate((float) Math.toRadians(ang), d.axX, d.axY, d.axZ).translate(-d.cx, -d.cy, -d.cz);
            return r.mul(d.m0);
        }
        Mat4 base = baseMatrix(partial, camX, camY, camZ);
        if (p.role == VehicleModel.BODY) return base.mul(bodyAnim(m, partial));
        if (p.role == VehicleModel.WHEEL) return base.mul(partAnim(m, p, partial));
        return base.mul(bodyAnim(m, partial)).mul(partAnim(m, p, partial));
    }

    /** One tick of the blown off pieces: they fly, fall, bounce on the ground and come to rest. */
    void tickDebris(World w) {
        for (Debris d : debris) {
            if (d == null) continue;
            d.pox = d.ox; d.poy = d.oy; d.poz = d.oz; d.prevAngle = d.angle;
            if (d.rest) continue;
            d.vy -= 0.04;
            d.ox += d.vx; d.oy += d.vy; d.oz += d.vz;
            d.angle += d.spin;
            double wx = d.ax + d.ox + d.cx, wy = d.ay + d.oy + d.cy, wz = d.az + d.oz + d.cz;
            double g = w == null ? d.ay : groundAt(w, wx, wz, wy + 0.4, 1.5f);
            if (g == NO_GROUND) g = d.ay - 64;
            if (wy - 0.12 <= g && d.vy < 0) {
                d.oy = g + 0.12 - d.ay - d.cy;
                d.vy = -d.vy * 0.25;
                d.vx *= 0.55; d.vz *= 0.55; d.spin *= 0.45f;
                if (Math.abs(d.vy) < 0.06 && d.vx * d.vx + d.vz * d.vz < 0.0009) { d.rest = true; d.vy = 0; }
            }
            if (d.ay + d.oy < -64) d.rest = true;
        }
    }
    /** Taken from a scene take (not from the player's own explosion): a take that does not have it repairs it. */
    public boolean wreckedByTake;
    /** The actor who drove it in a scene died: the takes of its passengers no longer move it, it rolls out and stops (until a scene starts again). */
    public boolean driverKilled;
    private float wreckTarget = Float.NaN, wreckFrom;
    private int wreckT;
    /** The slip a scene take recorded (tyre marks and smoke while it plays). */
    public float replaySlip;
    /** Ticks an empty car keeps rolling after another one hit it (no handbrake), and the vehicle that blocked the last move test. */
    int coast;
    private Vehicle blockedBy;
    /** What it drives on ({@link Surfaces}), and the lights switched on (headlights, tail lights). */
    public int surface;
    public boolean lightsOn;
    /** Where each wheel's tyre mark ended last tick {x, y, z}, or null (read and written by the vehicles). */
    double[][] skidPrev = new double[0][];
    /** Game tick when a scene take last moved the vehicle (the physics rests while a take drives it). */
    long replayTick = Long.MIN_VALUE;
    int replaySeat = -1;
    int stuck, reverse;
    /**
     * The body on its springs (only how it looks and where riders sit; the wheels stay on the ground): pitch and roll in
     * degrees and heave in blocks, each {value, velocity, previous value}.
     */
    public final float[] susPitch = new float[3], susRoll = new float[3], susHeave = new float[3];
    private float susAccel, susLastSpeed, susVy;
    private double susLastY = Double.NaN;
    /** Collision boxes in the world for the current pose (rebuilt when the vehicle moves). */
    private double[][] boxes;
    private double boxX = Double.NaN, boxY, boxZ;
    private float boxYaw;
    private VehicleModel boxModel;
    private float autoTarget;
    private VehicleModel cachedVm;
    private VehicleType cachedType;
    private static final VehicleType DEFAULTS = new VehicleType("", java.nio.file.Path.of("."));

    /** The vehicle kind's settings (defaults when the kind is missing). */
    public VehicleType type() { vm(); return cachedType != null ? cachedType : DEFAULTS; }
    private int cachedVersion = -1;

    public Vehicle(int id, String model) {
        this.id = id;
        this.model = model;
        for (int i = 0; i < slots.length; i++) slots[i] = new Slot();
    }

    /** The analysed model (reloaded when the 3D models change), or null if the file is missing. */
    public VehicleModel vm() {
        if (cachedVm != null && cachedVersion == Transports.version()) return cachedVm;
        cachedType = Transports.get(model);
        cachedVm = cachedType == null ? null : cachedType.vm();
        cachedVersion = Transports.version();
        if (cachedVm != null) fit(cachedVm);
        return cachedVm;
    }

    /** Arrays sized for the model's doors and seats. */
    private void fit(VehicleModel m) {
        int no = m.openables.size();
        if (openTarget.length != no) {
            openTarget = java.util.Arrays.copyOf(openTarget, no);
            open = java.util.Arrays.copyOf(open, no);
            prevOpen = java.util.Arrays.copyOf(prevOpen, no);
        }
        if (closeTimer.length != no) { closeTimer = new int[no]; autoClose = new boolean[no]; }
        int ns = m.seats.size();
        if (riders.length != ns) {
            int old = riders.length;
            riders = java.util.Arrays.copyOf(riders, ns);
            for (int i = old; i < ns; i++) riders[i] = NONE;
        }
    }

    public void setPose(double x, double y, double z, float yaw) {
        this.x = px = x; this.y = py = gy = y; this.z = pz = z;
        yVel = 0;
        this.yaw = prevYaw = yaw;
        pitch = prevPitch = roll = prevRoll = 0;
        speed = 0; vy = 0;
        steer = prevSteer = 0;
        stuck = reverse = 0;
        lat = drift = slip = rollRisk = push = 0;
        police = policeCool = flipTicks = 0;
        flip = prevFlip = 0;
    }

    /** True when the seat can be got into: its door is open (mostly), or it has no door. */
    public boolean doorOpenFor(VehicleModel m, int seat) {
        int d = seat < m.seatDoor.length ? m.seatDoor[seat] : -1;
        return d < 0 || d >= open.length || openTarget[d] && open[d] > 0.5f;
    }

    public int seatOf(int rider) { for (int i = 0; i < riders.length; i++) if (riders[i] == rider) return i; return -1; }

    public boolean hasDriver() { VehicleModel m = vm(); return m != null && m.driverSeat < riders.length && riders[m.driverSeat] != NONE; }

    public float yawAt(float partial) { return prevYaw + wrap(yaw - prevYaw) * partial; }

    /** The roll-over angle between the last two ticks (degrees). */
    public float flipAt(float partial) { return prevFlip + (flip - prevFlip) * partial; }

    public double distSq(double ox, double oz) { return (x - ox) * (x - ox) + (z - oz) * (z - oz); }

    /** Speed in km/h (1 block = 1 m). */
    public float kmh() { return Math.abs(speed) * 20 * 3.6f; }

    // ------------------------------------------------------------------ transforms (local: x right... see VehicleModel)
    /** Local point -> world (current pose), into out[0..2]. */
    public void toWorld(double lx, double ly, double lz, double[] out) { toWorld(lx, ly, lz, x, y + lift(flip), z, yaw, pitch, roll + flip, out); }

    /** How far the car is lifted while it rolls over (so its roof and its side do not sink into the ground). */
    double lift(float flipDeg) {
        if (flipDeg == 0 || cachedVm == null) return 0;
        double r = Math.toRadians(flipDeg);
        return Math.max(0, -cachedVm.maxY * Math.cos(r)) + (cachedVm.maxX - cachedVm.minX) / 2 * Math.abs(Math.sin(r));
    }

    public boolean flipping() { return flipTicks > 0; }

    /**
     * Blows the car up (state only): black, burning, every moving part turned a little differently about its own axes (wheels
     * toe in and lean, doors and the hood hang open by different amounts). Blown up while rolling over, it stays where the roll was
     * stopped: on its side, on its roof, or back on its wheels (picked by 'seed').
     */
    public void wreck(long seed) {
        VehicleModel m = vm();
        wrecked = true; wreckAge = 0; wreckSeed = seed; lightsOn = false;
        java.util.Random r = new java.util.Random(seed);
        int n = m == null ? 0 : m.parts.size();
        dmgTurn = new float[n * 3]; dmgOpen = new float[n];
        for (int i = 0; i < n; i++) {
            VehicleModel.Part p = m.parts.get(i);
            if (p.role == VehicleModel.WHEEL) {
                dmgTurn[i * 3 + 1] = (r.nextFloat() - 0.5f) * 50;             // toe
                dmgTurn[i * 3 + 2] = (r.nextFloat() - 0.5f) * 34;             // camber
                if (r.nextInt(5) == 0) dmgTurn[i * 3] = (r.nextFloat() - 0.5f) * 20;
            } else if (p.role == VehicleModel.OPENABLE) {
                dmgOpen[i] = 0.75f + r.nextFloat() * 0.35f;                     // thrown wide open (past its stop a little)
                dmgTurn[i * 3] = (r.nextFloat() - 0.5f) * 36; dmgTurn[i * 3 + 2] = (r.nextFloat() - 0.5f) * 30; dmgTurn[i * 3 + 1] = (r.nextFloat() - 0.5f) * 24;
            } else if (p.role == VehicleModel.STEERING) {
                dmgTurn[i * 3 + 2] = (r.nextFloat() - 0.5f) * 40;
            }
        }
        steer = (r.nextFloat() - 0.5f) * 1.2f;
        coast = 40;
        blowOff(m, r);
        if (flipTicks > 0) {
            // the roll stops somewhere else every time: on the side, on the roof, or it lands on its wheels
            float a = Math.abs(flip);
            float[] stops = {90, 180, 270, 360};
            java.util.List<Float> ok = new java.util.ArrayList<>();
            for (float st : stops) if (st > a + 8) ok.add(st);
            float to = ok.isEmpty() ? 360 : ok.get(r.nextInt(ok.size()));
            wreckFrom = flip; wreckTarget = Math.signum(flip == 0 ? flipDir : flip) * to; wreckT = 0;
        }
    }

    /**
     * The explosion tears parts off: each door / hood / trunk with a chance of 45 %, sometimes a wheel. They fly away from
     * the car and up, tumbling (the same pieces for the same seed: a replayed take blows off the same ones).
     */
    private void blowOff(VehicleModel m, java.util.Random r) {
        debris = new Debris[m == null ? 0 : m.parts.size()];
        if (m == null) return;
        int wheelOff = r.nextFloat() < 0.3f ? r.nextInt(Math.max(1, m.wheels.size())) : -1, wheelNo = 0;
        Mat4 base = baseMatrix(1, x, y, z), body = new Mat4().set(base).mul(bodyAnim(m, 1));
        for (int i = 0; i < m.parts.size(); i++) {
            VehicleModel.Part p = m.parts.get(i);
            boolean off = p.role == VehicleModel.OPENABLE ? r.nextFloat() < 0.45f : p.role == VehicleModel.WHEEL && wheelNo++ == wheelOff;
            float rv = r.nextFloat(), ru = r.nextFloat(), rs = r.nextFloat(), ra = r.nextFloat(), rb = r.nextFloat(), rc = r.nextFloat();
            if (!off) continue;
            Debris d = new Debris();
            d.ax = x; d.ay = y; d.az = z;
            d.m0 = new Mat4().set(p.role == VehicleModel.WHEEL ? base : body).mul(partAnim0(m, p, 1));
            float[] c = new float[4];
            d.m0.transform((p.x0 + p.x1) / 2, (p.y0 + p.y1) / 2, (p.z0 + p.z1) / 2, c);
            d.cx = c[0]; d.cy = c[1]; d.cz = c[2];
            // away from the middle of the car, and up
            float[] mid = new float[4];
            body.transform((m.minX + m.maxX) / 2, m.maxY * 0.4f, (m.minZ + m.maxZ) / 2, mid);
            double hx = c[0] - mid[0], hz = c[2] - mid[2], hl = Math.sqrt(hx * hx + hz * hz) + 1e-6;
            double sp = 0.22 + rv * 0.28;
            d.vx = hx / hl * sp + (x - px); d.vz = hz / hl * sp + (z - pz);
            d.vy = 0.38 + ru * 0.3;
            d.spin = (8 + rs * 18) * (ra < 0.5f ? -1 : 1);
            double ax = rb - 0.5, ay = 0.3 + rc, az = ra - 0.5, al = Math.sqrt(ax * ax + ay * ay + az * az);
            d.axX = (float) (ax / al); d.axY = (float) (ay / al); d.axZ = (float) (az / al);
            debris[i] = d;
        }
    }

    /** Back to a working car. */
    public void repair() {
        debris = new Debris[0]; settleDebris = false;
        wrecked = false; wreckAge = 0; charred = 0; wreckedByTake = false;
        wreckTarget = Float.NaN;
        dmgTurn = new float[0]; dmgOpen = new float[0];
        flip = prevFlip = 0; flipTicks = 0;
    }

    static void toWorld(double lx, double ly, double lz, double x, double y, double z, float yaw, float pitch, float roll, double[] out) {
        // roll around z, pitch around x, then the yaw turn (local -z = the front)
        double cr = Math.cos(Math.toRadians(roll)), sr = Math.sin(Math.toRadians(roll));
        double x1 = lx * cr - ly * sr, y1 = lx * sr + ly * cr, z1 = lz;
        double cp = Math.cos(Math.toRadians(pitch)), sp = Math.sin(Math.toRadians(pitch));
        double y2 = y1 * cp - z1 * sp, z2 = y1 * sp + z1 * cp, x2 = x1;
        double a = Math.PI - Math.toRadians(yaw), ca = Math.cos(a), sa = Math.sin(a);
        out[0] = x + x2 * ca + z2 * sa;
        out[1] = y + y2;
        out[2] = z - x2 * sa + z2 * ca;
    }

    /** World point -> local (current pose). */
    public void toLocal(double wx, double wy, double wz, double[] out) {
        double dx = wx - x, dy = wy - y - lift(flip), dz = wz - z;
        localDir(dx, dy, dz, out);
    }

    /** World direction -> local direction (current pose). */
    public void localDir(double dx, double dy, double dz, double[] out) {
        double a = Math.PI - Math.toRadians(yaw), ca = Math.cos(a), sa = Math.sin(a);
        double x2 = dx * ca - dz * sa, z2 = dx * sa + dz * ca, y2 = dy;
        double cp = Math.cos(Math.toRadians(pitch)), sp = Math.sin(Math.toRadians(pitch));
        double y1 = y2 * cp + z2 * sp, z1 = -y2 * sp + z2 * cp, x1 = x2;
        double cr = Math.cos(Math.toRadians(roll + flip)), sr = Math.sin(Math.toRadians(roll + flip));
        out[0] = x1 * cr + y1 * sr;
        out[1] = -x1 * sr + y1 * cr;
        out[2] = z1;
    }

    /** The vehicle in the world relative to the camera (between the last two ticks). */
    public Mat4 baseMatrix(float partial, double camX, double camY, double camZ) {
        double ix = px + (x - px) * partial, iy = py + (y - py) * partial, iz = pz + (z - pz) * partial;
        float iyaw = yawAt(partial), ip = prevPitch + (pitch - prevPitch) * partial, ir = prevRoll + (roll - prevRoll) * partial;
        float iflip = flipAt(partial);
        ir += iflip;
        iy += lift(iflip);
        Mat4 m = new Mat4().translate((float) (ix - camX), (float) (iy - camY), (float) (iz - camZ));
        m.rotate((float) (Math.PI - Math.toRadians(iyaw)), 0, 1, 0);
        if (ip != 0) m.rotate((float) Math.toRadians(ip), 1, 0, 0);
        if (ir != 0) m.rotate((float) Math.toRadians(ir), 0, 0, 1);
        return m;
    }

    /** The animation of a part around its pivot (local space). */
    public Mat4 partAnim(VehicleModel m, VehicleModel.Part p, float partial) {
        Mat4 a = partAnim0(m, p, partial);
        if (wrecked && dmgTurn.length > 0) {
            int i = m.parts.indexOf(p);
            if (i >= 0 && i * 3 + 2 < dmgTurn.length && (dmgTurn[i * 3] != 0 || dmgTurn[i * 3 + 1] != 0 || dmgTurn[i * 3 + 2] != 0)) {
                float k = Math.min(1, wreckAge / 6f);                                  // bends in over the first moments
                Mat4 d = new Mat4();
                d.translate(p.px, p.py, p.pz);
                d.rotate((float) Math.toRadians(dmgTurn[i * 3] * k), 1, 0, 0);
                d.rotate((float) Math.toRadians(dmgTurn[i * 3 + 1] * k), 0, 1, 0);
                d.rotate((float) Math.toRadians(dmgTurn[i * 3 + 2] * k), 0, 0, 1);
                d.translate(-p.px, -p.py, -p.pz);
                d.mul(a);
                return d;
            }
        }
        return a;
    }

    private Mat4 partAnim0(VehicleModel m, VehicleModel.Part p, float partial) {
        Mat4 a = new Mat4();
        switch (p.role) {
            case VehicleModel.WHEEL -> {
                float st = prevSteer + (steer - prevSteer) * partial, sp = prevSpin + (spin - prevSpin) * partial;
                a.translate(p.px, p.py, p.pz);
                if (p.front) a.rotate((float) Math.toRadians(st * steerAngle()), 0, 1, 0);
                a.rotate(sp, 1, 0, 0);
                a.translate(-p.px, -p.py, -p.pz);
            }
            case VehicleModel.OPENABLE -> {
                int i = m.openables.indexOf(p);
                float o = i < 0 || i >= open.length ? 0 : prevOpen[i] + (open[i] - prevOpen[i]) * partial;
                if (wrecked) { int pi = m.parts.indexOf(p); if (pi >= 0 && pi < dmgOpen.length) o = Math.max(o, dmgOpen[pi] * Math.min(1, wreckAge / 8f)); }
                if (o <= 1e-4f) return a;
                o = o * o * (3 - 2 * o);       // eases in and out
                a.translate(p.px, p.py, p.pz);
                a.rotate((float) Math.toRadians(p.openAngle * o), p.axis == 0 ? 1 : 0, p.axis == 1 ? 1 : 0, 0);
                a.translate(-p.px, -p.py, -p.pz);
            }
            case VehicleModel.STEERING -> {
                float st = prevSteer + (steer - prevSteer) * partial;
                if (Math.abs(st) < 1e-4f) return a;
                a.translate(p.px, p.py, p.pz);
                a.rotate((float) Math.toRadians(st * 140), p.ax, p.ay, p.az);
                a.translate(-p.px, -p.py, -p.pz);
            }
            default -> {}
        }
        return a;
    }

    /** How far the front wheels turn at full steering (less when fast). */
    public float steerAngle() { return type().steerAngle / (1 + Math.max(0, Math.abs(speed) - 0.15f) * 5); }

    // ------------------------------------------------------------------ physics
    /** One game tick of driving. Controls (throttle, steerIn, handbrake) were set by the driver before. */
    void tick(World w, boolean replayed) {
        px = x; py = y; pz = z;
        prevYaw = yaw; prevPitch = pitch; prevRoll = roll;
        prevSteer = steer; prevSpin = spin;
        prevFlip = flip;
        slip = replayed ? replaySlip : 0;
        VehicleModel m = vm();
        if (wrecked) { wreckAge++; charred = Math.min(1, charred + 0.04f); lightsOn = false; }
        for (int i = 0; i < open.length; i++) {
            if (i < closeTimer.length && closeTimer[i] > 0 && --closeTimer[i] == 0) openTarget[i] = false;
            prevOpen[i] = open[i];
            float t = openTarget[i] ? 1 : 0;
            open[i] += Math.max(-0.12f, Math.min(0.12f, t - open[i]));
        }
        if (m == null) return;
        if (replayed) { if (w != null) surface = Surfaces.under(w, x, gy, z); suspension(m); return; }

        boolean driver = hasDriver();
        lastThrottle = driver ? throttle : 0;
        VehicleType ty = type();
        surface = Surfaces.under(w, x, gy, z);
        float sGrip = ty.surfGrip[surface], sSpeed = ty.surfSpeed[surface], traction = (float) Math.sqrt(sGrip);       // ice: little grip, long braking
        float maxF = ty.maxSpeed * sSpeed, maxR = ty.reverseSpeed * sSpeed;
        boolean tumbling = flipTicks > 0;
        if (wrecked) driver = false;
        float thr = driver && !tumbling ? throttle : 0, st = driver && !tumbling ? steerIn : 0;
        boolean pushed = push != 0 && !driver && !tumbling;
        boolean human = driver && riders[m.driverSeat] == PLAYER;
        boolean hb = handbrake && driver && !tumbling;
        float weightGrip = (float) Math.sqrt(VehicleType.REF_WEIGHT / ty.weight);        // a heavy car slides on with more momentum
        // ---- drift (Space with gas and steering at speed) and the police turn (Space + a sharp turn at high speed)
        float total = (float) Math.hypot(speed, lat);
        if (policeCool > 0) policeCool--;
        if (human && hb && onGround && police == 0 && policeCool == 0 && speed >= ty.policeSpeed && Math.abs(st) >= 0.7f) {
            police = 1; policeDir = -Math.signum(st); policeYaw = 0;
        } else if (police > 0) {
            police++;
            if (!human || !hb && police > 8 || total < 0.12f || policeYaw >= 172 || !onGround || tumbling) { police = 0; policeCool = 25; }
        }
        boolean wantDrift = police > 0 || human && hb && onGround && total >= ty.driftMinSpeed && (Math.abs(st) > 0.25f || drift > 0.3f);
        if (police > 0) drift = 1;
        else drift = wantDrift ? Math.min(1, drift + 0.35f) : Math.max(0, drift - 0.08f);
        // slippery ground (ice, snow): steering at speed lets the tyres go by itself
        float loose = Math.max(0, Math.min(1, (0.8f - sGrip) * 1.4f));
        if (loose > 0 && !tumbling) drift = Math.max(drift, loose * Math.min(1, Math.abs(steer) * 2) * Math.min(1, total / 0.35f) * 0.85f);
        // steering eases towards the wheel input and back to the middle
        steer += Math.max(-0.12f, Math.min(0.12f, st - steer));
        if (coast > 0) coast--;
        // nobody drives: the parking brake; a car whose driver was killed in a scene rolls out on its speed first
        boolean brake = !driver && !pushed && coast == 0 && (!driverKilled || Math.abs(speed) < 0.01f) || hb && drift < 0.3f && police == 0;
        braking = onGround && driver && !tumbling && Math.abs(speed) > 0.05f
                && (thr > 0 && speed < -0.01f || thr < 0 && speed > 0.01f || hb && drift < 0.3f && police == 0);
        if (onGround) {
            if (pushed) {
                speed += Math.max(-0.01f, Math.min(0.01f, push - speed));           // somebody pushes it from behind
            } else if (thr > 0) {
                if (speed < -0.01f) speed = Math.min(0, speed + ty.braking * traction);
                else speed += ty.accel * traction * thr * Math.max(0, 1 - Math.max(0, speed) / Math.max(0.05f, Math.min(maxF, speedLimit)));
            } else if (thr < 0) {
                if (speed > 0.01f) speed = Math.max(0, speed - ty.braking * traction);
                else speed = Math.max(-maxR, speed + ty.accel * traction * 0.75f * thr);
            } else if (!pushed) {
                speed *= !driver && coast > 0 ? 0.965f : 0.985f;
                speed = speed > 0 ? Math.max(0, speed - 0.002f) : Math.min(0, speed + 0.002f);
            }
            if (brake) speed = speed > 0 ? Math.max(0, speed - ty.handbrake * traction) : Math.min(0, speed + ty.handbrake * traction);
            if (hb && drift < 0.3f && Math.abs(speed) > 0.3f) skidding = true;
            if (thr != 0 && Math.signum(thr) != Math.signum(speed) && Math.abs(speed) > 0.45f) { skidding = true; slip = Math.max(slip, 0.5f); }
            if (speed > speedLimit && driver) speed = Math.max(speedLimit, speed - 0.025f);
            if (speed > maxF && !pushed) speed = Math.max(maxF, speed - 0.012f);          // onto sand or snow: slows down to what it can do there
            if (tumbling) speed *= 0.975f;
            if (inWater(w)) { speed *= ty.waterDrag; lat *= ty.waterDrag; }
        }
        if (Math.abs(speed) < 1e-4f) speed = 0;

        // move: the rear axle rolls on, the front wheels pull the nose round; in a drift the car's speed keeps its direction
        // while the nose swings (the sideways part 'lat' appears and dies out as the tyres bite again)
        if (speed != 0 || Math.abs(lat) > 1e-4f) {
            float f = speed;
            float ang = steer * (drift > 0 ? steerAngle() + (ty.steerAngle / (1 + Math.max(0, Math.abs(f) - 0.15f) * 2) - steerAngle()) * drift : steerAngle());
            float ys = f + (f == 0 && drift <= 0 ? 0 : (float) Math.copySign(Math.hypot(f, lat), f == 0 ? 1 : f) - f) * drift;
            float dyaw = -(float) Math.toDegrees(ys * Math.tan(Math.toRadians(ang)) / m.wheelBase) * (1 + drift * (ty.driftYaw - 1));
            if (police > 0) { dyaw = policeDir * ty.policeRate * Math.min(1, police / 5f); policeYaw += Math.abs(dyaw); }
            float rd = (float) Math.toRadians(dyaw) * drift, cr = (float) Math.cos(rd), sr = (float) Math.sin(rd);
            float nf = f * cr + lat * sr, nl = -f * sr + lat * cr;
            float gripNow = (ty.grip + (Math.min(1, ty.driftGrip * weightGrip) - ty.grip) * drift) * sGrip;
            if (police == 0 && drift > 0) {
                // a slide that gets too sideways catches: the wider the angle between the nose and the way it goes, the more the tyres bite
                float angle = (float) Math.toDegrees(Math.atan2(Math.abs(nl), nf));
                float catchUp = Math.max(0, Math.min(1, (angle - 30) / 35f));
                gripNow += (Math.max(gripNow, ty.grip * 0.7f * sGrip) - gripNow) * catchUp;
            }
            float before = Math.abs(nl);
            nl *= 1 - gripNow;
            float lost = before - Math.abs(nl);
            if (drift > 0) { float scrub = 1 - (police > 0 ? 0.02f : 0.012f) * drift; nf *= scrub; nl *= scrub; }
            if (Math.abs(nl) < 1e-4f) nl = 0;
            float nyaw0 = yaw + dyaw;
            float nyaw = nyaw0, mid = yaw + dyaw / 2;
            double rx = x - fx(yaw) * m.rearZ, rz = z - fz(yaw) * m.rearZ;
            rx += fx(mid) * nf + rgx(mid) * nl; rz += fz(mid) * nf + rgz(mid) * nl;
            double nx = rx + fx(nyaw) * m.rearZ, nz = rz + fz(nyaw) * m.rearZ;
            // already stuck in something (spawned under leaves, two cars pushed together): it may drive out
            if (!blocked(w, m, nx, gy, nz, nyaw) || stuck > 2 && blocked(w, m, x, gy, z, yaw)) { x = nx; z = nz; yaw = nyaw; stuck = 0; speed = nf; lat = nl; }
            else if (!blocked(w, m, x + fx(yaw) * nf + rgx(yaw) * nl, gy, z + fz(yaw) * nf + rgz(yaw) * nl, yaw)) { x += fx(yaw) * nf + rgx(yaw) * nl; z += fz(yaw) * nf + rgz(yaw) * nl; stuck = 0; speed = nf; lat = nl; }
            else {
                float hit = Math.max(Math.abs(nf), Math.abs(nl));
                if (hit > 0.25f) crashed = Math.max(crashed, hit);
                if (blockedBy != null && collide(blockedBy, nf, nl)) { speed = lastSpeed; lat = lastLat; hit = 0; }
                else {
                    speed = -nf * 0.25f;         // bump
                    lat = -nl * 0.25f;
                }
                if (Math.abs(speed) < 0.02f) speed = 0;
                if (Math.abs(lat) < 0.02f) lat = 0;
                if (police > 0) { police = 0; policeCool = 25; }
                stuck++;
            }
            spin -= (float) (Math.sqrt((x - px) * (x - px) + (z - pz) * (z - pz)) * Math.signum(speed == 0 ? 1 : speed)) / m.wheelRadius;
            // the tyres slide: marks, smoke, the squeal
            if (drift > 0.05f) { slip = Math.max(slip, drift * Math.min(1, (float) Math.hypot(speed, lat) / 0.45f)); skidding = true; }
            if (onGround && !wrecked) rollOver(m, ty, dyaw, nf, nl, lost, weightGrip);
        } else {
            stuck = thr != 0 ? stuck + 1 : 0;
            rollRisk = Math.max(0, rollRisk - 0.06f);
        }
        tickFlip();

        // the ground under the four wheels: height, nose up / down, lean
        double hFL = ground(w, -m.halfTrack, m.frontZ), hFR = ground(w, m.halfTrack, m.frontZ), hRL = ground(w, -m.halfTrack, m.rearZ), hRR = ground(w, m.halfTrack, m.rearZ);
        // it rests on the highest wheels: a wheel over a drop only tips it a little instead of pulling it into the ground
        double[] hs = {hFL, hFR, hRL, hRR};
        java.util.Arrays.sort(hs);
        double target = Math.max((hs[3] + hs[2]) / 2, hs[3] - 0.5);
        hFL = Math.max(hFL, target - 1); hFR = Math.max(hFR, target - 1); hRL = Math.max(hRL, target - 1); hRR = Math.max(hRR, target - 1);
        float pitchT = (float) Math.toDegrees(Math.atan2((hFL + hFR) / 2 - (hRL + hRR) / 2, m.wheelBase));
        float rollT = (float) Math.toDegrees(Math.atan2((hRR + hFR) / 2 - (hRL + hFL) / 2, m.halfTrack * 2));
        pitchT = Math.max(-35, Math.min(35, pitchT));
        rollT = Math.max(-30, Math.min(30, rollT));
        double drop = gy - target;
        if (drop > 0.01) {
            // the ground falls away: gentle slopes and one block drops (they are ramps now) are followed, a real drop is a fall
            double hug = 0.35 + Math.abs(speed) * 0.7;
            if (onGround && drop <= hug) { gy = target; vy = 0; }
            else {
                vy -= 0.06f;
                gy += Math.max(vy, -1.5f);
                if (gy <= target) { gy = target; vy = 0; onGround = true; }
                else onGround = false;
            }
        } else {
            gy = target;                          // the ground rises: the car goes with it
            vy = 0;
            onGround = true;
        }
        if (onGround) {
            pitch += (pitchT - pitch) * ANGLE_FOLLOW;
            roll += (rollT - roll) * ANGLE_FOLLOW;
        }
        if (gy < -64) { gy = -64; vy = 0; }
        smoothHeight();
        suspension(m);
    }

    static double rgx(float yaw) { return -Math.cos(Math.toRadians(yaw)); }
    static double rgz(float yaw) { return -Math.sin(Math.toRadians(yaw)); }

    /**
     * Sharp turns at speed can roll the car over: the sideways force (speed x how fast the nose turns while the tyres hold, plus
     * the sideways speed they take away when they bite again after a slide) is compared with what the car can take
     * (the 'rollover' setting, higher for a wide, low and heavy car). Over the limit the strain builds up, a bit more every tick,
     * and when it is full the car goes over.
     */
    private void rollOver(VehicleModel m, VehicleType ty, float dyaw, float nf, float nl, float lost, float weightGrip) {
        if (!ty.rolloverOn || ty.rollover <= 0 || flipTicks > 0 || Math.hypot(speed, lat) < Math.max(0.2f, ty.rolloverSpeed)) { rollRisk = Math.max(0, rollRisk - 0.06f); return; }
        float limit = ty.rollover * stability(m, ty);
        float force = (float) (Math.hypot(nf, nl) * Math.abs(Math.toRadians(dyaw)) * (1 - drift) + lost * 0.45f);
        if (force > limit) rollRisk += (force - limit) / limit * 0.12f + 0.02f;
        else rollRisk = Math.max(0, rollRisk - 0.06f);
        if (rollRisk >= 1) {
            // outwards of the turn: the way the nose turns (or the way the car was sliding)
            float dir = Math.abs(nl) > 0.08f ? -Math.signum(nl) : Math.signum(dyaw);
            startFlip(dir == 0 ? 1 : dir);
        }
    }

    /** How hard the car is to tip: a wide, low, heavy one more than a narrow tall light one (1 = a normal car). */
    public static float stability(VehicleModel m, VehicleType ty) {
        float shape = Math.max(0.6f, Math.min(1.6f, 2 * m.halfTrack / Math.max(0.5f, m.maxY) / 1.15f));
        float mass = Math.max(0.8f, Math.min(1.3f, (float) Math.sqrt(ty.weight / VehicleType.REF_WEIGHT)));
        return shape * mass;
    }

    /** The car goes over: a roll of 360 degrees on the way, the people inside are hurt (see the vehicles). */
    public void startFlip(float dir) {
        flipTicks = FLIP_TICKS;
        flipDir = dir < 0 ? -1 : 1;
        flip = prevFlip = 0;
        rollRisk = 0; drift = 0; police = 0; policeCool = 40;
        flipImpact = Math.max(0.2f, (float) Math.hypot(speed, lat));
        crashed = Math.max(crashed, flipImpact);
    }

    private void tickFlip() {
        if (flipTicks > 0 && wrecked && !Float.isNaN(wreckTarget)) {
            // blown up in the middle of a roll: it goes on a few more ticks to where it stays
            wreckT++;
            float t = Math.min(1, wreckT / 12f);
            flip = wreckFrom + (wreckTarget - wreckFrom) * (t * t * (3 - 2 * t));
            speed *= 0.9f; lat *= 0.9f;
            if (wreckT >= 12) { flipTicks = 0; flip = wreckTarget; wreckTarget = Float.NaN; }
            return;
        }
        if (flipTicks > 0) {
            flipTicks--;
            float t = 1 - flipTicks / (float) FLIP_TICKS;
            flip = flipDir * 360 * (t * t * (3 - 2 * t));
            skidding = true;
            slip = Math.max(slip, 0.3f);
            if (flipTicks == 0) { flip = flipDir * 360; flipLanded = Math.max(0.2f, (float) Math.hypot(speed, lat)); speed *= 0.5f; lat *= 0.5f; crashed = Math.max(crashed, flipLanded); }
        } else if (flip != 0 && !wrecked) flip = prevFlip = 0;
    }

    /**
     * Springs between the wheels and the body: it squats when speeding up, dives when braking, leans out of turns and
     * lags behind steps and bumps, then settles with a small bounce.
     */
    private void suspension(VehicleModel m) {
        if (Double.isNaN(susLastY)) susLastY = y;
        susAccel += (speed - susLastSpeed - susAccel) * 0.35f;
        susLastSpeed = speed;
        float dyaw = (float) Math.toRadians(wrap(yaw - prevYaw));
        VehicleType ty = type();
        float pitchT = Math.max(-ty.maxPitch, Math.min(ty.maxPitch, susAccel * 110));              // speeding up: the nose rises
        float rollT = Math.max(-ty.maxRoll, Math.min(ty.maxRoll, (float) Math.toDegrees(speed * dyaw) * 2.6f));   // a left turn leans it to the right
        susSpring(susPitch, pitchT, ty.springStiffness, ty.springDamping);
        susSpring(susRoll, rollT, ty.springStiffness, ty.springDamping);
        // the body lags behind changes of the climbing speed (bumps, the start and the end of a slope, landings), not behind a steady slope
        float dyv = (float) (y - susLastY), acc = dyv - susVy;
        susVy = dyv;
        susLastY = y;
        susHeave[0] -= Math.max(-0.5f, Math.min(0.5f, acc)) * 0.9f;
        susSpring(susHeave, 0, 0.22f, 0.7f);
        susHeave[0] = Math.max(-ty.heaveLimit, Math.min(ty.heaveLimit, susHeave[0]));
    }

    /**
     * The shown height follows the ground height with an alpha-beta filter: it predicts with the climbing speed it already has,
     * so a long slope is followed without lag, while a one block step (or a bump at 80 km/h) is spread over a few ticks instead
     * of being a jump. It never sinks more than 0.6 into the ground, and a fall is followed exactly.
     */
    /** Smoothing of the shown height: how fast it follows (alpha), how much it keeps its own climbing speed (beta), how far it may sink into the ground / float above it. */
    static float SM_ALPHA = 0.30f, SM_BETA = 0.05f, SM_SINK = 0.7f, SM_FLOAT = 0.6f;
    /** How fast the angles follow the ground's (share per tick). */
    static float ANGLE_FOLLOW = 0.12f;

    private void smoothHeight() {
        // in the air the filter is tighter (the car should not hang above its own fall), on the ground softer
        double alpha = onGround ? SM_ALPHA : 0.5, beta = onGround ? SM_BETA : 0.12;
        if (onGround && wasAir) { yVel = Math.max(yVel, 0); wasAir = false; }     // landed: the falling speed is not carried into the ground
        if (!onGround) wasAir = true;
        double pred = y + yVel;
        double err = gy - pred;
        double ny = pred + alpha * err;
        yVel = (float) Math.max(-1.5, Math.min(1.5, yVel + beta * err));
        double lo = gy - SM_SINK, hi = gy + SM_FLOAT;
        if (ny < lo) { ny = lo; if (yVel < 0) yVel = 0; } else if (ny > hi) ny = hi;
        y = ny;
    }

    private boolean wasAir;

    private static void susSpring(float[] s, float target, float k, float damp) {
        s[2] = s[0];
        s[1] = (s[1] + (target - s[0]) * k) * damp;
        s[0] += s[1];
    }

    /** The body's sway on its springs (local space, around the axle height), between the last two ticks. */
    public Mat4 bodyAnim(VehicleModel m, float partial) {
        float p = susPitch[2] + (susPitch[0] - susPitch[2]) * partial, r = susRoll[2] + (susRoll[0] - susRoll[2]) * partial, h = susHeave[2] + (susHeave[0] - susHeave[2]) * partial;
        Mat4 a = new Mat4();
        float rh = type().rideHeight;
        if (Math.abs(p) < 1e-3f && Math.abs(r) < 1e-3f && Math.abs(h) < 1e-4f && rh == 0) return a;
        float ay = m.wheelRadius;
        a.translate(0, ay + h + rh, 0);
        if (p != 0) a.rotate((float) Math.toRadians(p), 1, 0, 0);
        if (r != 0) a.rotate((float) Math.toRadians(r), 0, 0, 1);
        a.translate(0, -ay, 0);
        return a;
    }

    /**
     * World position of a local point of the body (with the springs) between the last two ticks, in doubles so it stays
     * exact far from the world origin: the camera and drawn riders use exactly the pose the car is drawn with.
     */
    public void bodyPointAt(VehicleModel m, double lx, double ly, double lz, float partial, double[] out) {
        float[] t = new float[4];
        bodyAnim(m, partial).transform((float) lx, (float) ly, (float) lz, t);
        double ix = px + (x - px) * partial, iy = py + (y - py) * partial, iz = pz + (z - pz) * partial;
        float iflip = flipAt(partial);
        toWorld(t[0], t[1], t[2], ix, iy + lift(iflip), iz, yawAt(partial), prevPitch + (pitch - prevPitch) * partial, prevRoll + (roll - prevRoll) * partial + iflip, out);
    }

    /** Collision boxes {x0, y0, z0, x1, y1, z1} in the world, one per piece along the length (cached for the pose). */
    double[][] worldBoxes(VehicleModel m) {
        if (boxes != null && boxModel == m && boxX == x && boxY == y && boxZ == z && boxYaw == yaw) return boxes;
        double a = Math.PI - Math.toRadians(yaw), ca = Math.cos(a), sa = Math.sin(a);
        double[][] out = new double[m.segments.size()][];
        for (int i = 0; i < out.length; i++) {
            float[] sg = m.segments.get(i);
            double bx0 = Double.MAX_VALUE, bz0 = Double.MAX_VALUE, bx1 = -Double.MAX_VALUE, bz1 = -Double.MAX_VALUE;
            for (int k = 0; k < 4; k++) {
                double lx = (k & 1) == 0 ? sg[2] : sg[3], lz = (k & 2) == 0 ? sg[0] : sg[1];
                double gx = x + lx * ca + lz * sa, gz = z - lx * sa + lz * ca;
                bx0 = Math.min(bx0, gx); bx1 = Math.max(bx1, gx); bz0 = Math.min(bz0, gz); bz1 = Math.max(bz1, gz);
            }
            out[i] = new double[]{bx0, y + 0.05, bz0, bx1, y + sg[4], bz1};
        }
        boxes = out; boxModel = m; boxX = x; boxY = y; boxZ = z; boxYaw = yaw;
        return out;
    }

    static double fx(float yaw) { return -Math.sin(Math.toRadians(yaw)); }
    static double fz(float yaw) { return Math.cos(Math.toRadians(yaw)); }

    private boolean inWater(World w) {
        return w.getBlock((int) Math.floor(x), (int) Math.floor(y + 0.3), (int) Math.floor(z)) == Blocks.WATER;
    }

    /** Ground height under a local point (x, z) of the current pose: the highest block top not more than a step above the car. */
    private double ground(World w, double lx, double lz) {
        double a = Math.PI - Math.toRadians(yaw), ca = Math.cos(a), sa = Math.sin(a);
        double gx = x + lx * ca + lz * sa, gz = z - lx * sa + lz * ca;
        return groundAt(w, gx, gz, gy, type().stepHeight);
    }

    /** How steeply the wheels' ground falls away from a block that is higher than its surroundings (1 = a 45 degree ramp, one block long per block of height). */
    static final double RAMP = 1.0;
    private static final double NO_GROUND = Double.NEGATIVE_INFINITY;

    /**
     * Ground height under a point. Minecraft terrain is made of steps, and a wheel that meets a "cliff" of one block makes the
     * whole car jump. So the ground is a smooth surface: on a block it is the block's top, and next to a higher block (one the
     * car can climb) it rises towards it as a 45 degree ramp over the lower block. Heights then change evenly with the position,
     * and so do the height and the angles of the car. Nothing is ever below a block's top.
     */
    static double groundAt(World w, double gx, double gz, double ref, float step) {
        int cx = (int) Math.floor(gx), cz = (int) Math.floor(gz);
        if (!w.isLoaded(cx, cz)) return ref;
        double best = NO_GROUND;
        for (int dx = -1; dx <= 1; dx++)
            for (int dz = -1; dz <= 1; dz++) {
                int bx = cx + dx, bz = cz + dz;
                double top = columnTop(w, bx, bz, ref, step);
                if (top == NO_GROUND) continue;
                double ex = Math.max(0, Math.max(bx - gx, gx - (bx + 1))), ez = Math.max(0, Math.max(bz - gz, gz - (bz + 1)));
                double h = top - Math.sqrt(ex * ex + ez * ez) * RAMP;
                if (h > best) best = h;
            }
        return best == NO_GROUND ? ref - 4 : best;
    }

    static double groundAt(World w, double gx, double gz, double ref) { return groundAt(w, gx, gz, ref, STEP); }

    /**
     * Highest top in a column that the car can stand on (not more than 'step' above 'ref'); NO_GROUND for no ground within 4 blocks
     * below, and for a wall: a block across the climbing height whose top is higher than that. Water counts as ground just
     * under its surface (the car wades along the bottom).
     */
    private static double columnTop(World w, int bx, int bz, double ref, float step) {
        double limit = ref + step + 1e-4;
        int top = (int) Math.floor(limit);
        for (int by = top; by >= (int) Math.floor(ref) - 4; by--) {
            int s = w.getState(bx, by, bz);
            if (s == 0) continue;
            Block b = Blocks.byId(s >> 4);
            if (b.shape == Block.Shape.LIQUID) return by + 0.1;
            if (!b.solid) continue;
            double best = NO_GROUND;
            for (float[] cb : b.collisionShape(s & 15, b.neighborInfo(w, bx, by, bz, s & 15))) {
                double t = by + cb[4];
                if (t <= limit) best = Math.max(best, t);
                else if (by == top) return NO_GROUND;          // reaches above the climbing height: a wall, not a step
            }
            if (best != NO_GROUND) return best;
        }
        return NO_GROUND;
    }

    /** Blocks a fast car goes through (breaking them): glass and panes, planks, leaves, fences and gates, trapdoors. */
    public static boolean smashable(Block b) {
        String n = b.name;
        return n.contains("glass") || n.contains("plank") || n.contains("wood_slab") || n.contains("wooden_slab") || n.contains("leaves") || n.contains("fence") || n.contains("trapdoor");
    }

    /** The smashable blocks the body touches a little ahead of its pose (the ones to break now): {x, y, z} each. */
    java.util.List<int[]> smashTargets(World w, VehicleModel m, float ahead) {
        java.util.List<int[]> out = new java.util.ArrayList<>();
        double a = Math.PI - Math.toRadians(yaw), ca = Math.cos(a), sa = Math.sin(a);
        double ox = fx(yaw) * ahead, oz = fz(yaw) * ahead;
        float x0 = m.minX, x1 = m.maxX, z0 = m.minZ, z1 = m.maxZ;
        int nx = Math.max(2, (int) Math.ceil((x1 - x0) / 0.4f) + 1), nz = Math.max(2, (int) Math.ceil((z1 - z0) / 0.4f) + 1);
        double lo = y + 0.1, hi = y + Math.max(0.6, m.maxY);
        for (int i = 0; i < nx; i++)
            for (int k = 0; k < nz; k++) {
                double lx = x0 + (x1 - x0) * i / (nx - 1), lz = z0 + (z1 - z0) * k / (nz - 1);
                double gx = x + ox + lx * ca + lz * sa, gz = z + oz - lx * sa + lz * ca;
                int bx = (int) Math.floor(gx), bz = (int) Math.floor(gz);
                if (!w.isLoaded(bx, bz)) continue;
                for (int by = (int) Math.floor(lo); by <= (int) Math.floor(hi); by++) {
                    int s = w.getState(bx, by, bz);
                    if (s == 0) continue;
                    Block b = Blocks.byId(s >> 4);
                    if (!b.solid || !smashable(b)) continue;
                    boolean dup = false;
                    for (int[] o : out) if (o[0] == bx && o[1] == by && o[2] == bz) dup = true;
                    if (dup) continue;
                    double fxp = gx - bx, fzp = gz - bz;
                    for (float[] cb : b.collisionShape(s & 15, b.neighborInfo(w, bx, by, bz, s & 15)))
                        if (fxp >= cb[0] - 0.05 && fxp <= cb[3] + 0.05 && fzp >= cb[2] - 0.05 && fzp <= cb[5] + 0.05 && by + cb[4] > lo && by + cb[1] < hi) { out.add(new int[]{bx, by, bz}); break; }
                }
            }
        return out;
    }

    private float lastSpeed, lastLat;

    /**
     * Runs into another vehicle at (nf, nl) (forward / sideways speed): the other one is pushed away along the line between them
     * (more the heavier this one is), this one loses speed (lastSpeed / lastLat are what it keeps). False when they are not closing.
     */
    private boolean collide(Vehicle o, float nf, float nl) {
        double vx = fx(yaw) * nf + rgx(yaw) * nl, vz = fz(yaw) * nf + rgz(yaw) * nl;
        double ovx = fx(o.yaw) * o.speed + rgx(o.yaw) * o.lat, ovz = fz(o.yaw) * o.speed + rgz(o.yaw) * o.lat;
        double dx = o.x - x, dz = o.z - z, dl = Math.sqrt(dx * dx + dz * dz);
        if (dl < 1e-3) return false;
        dx /= dl; dz /= dl;
        double closing = (vx - ovx) * dx + (vz - ovz) * dz;
        if (closing <= 0.02) return false;
        float mw = type().weight, ow = o.type().weight;
        double share = mw / (mw + ow);
        double give = closing * 1.4 * share, keep = closing * 1.2 * share;
        o.speed += (float) ((dx * fx(o.yaw) + dz * fz(o.yaw)) * give);
        o.lat += (float) ((dx * rgx(o.yaw) + dz * rgz(o.yaw)) * give);
        o.coast = 30;
        o.stuck = 0;
        o.crashed = Math.max(o.crashed, (float) closing);
        crashed = Math.max(crashed, (float) closing);
        // this one: what it had, minus what went into the other
        double nvx = vx - dx * keep, nvz = vz - dz * keep;
        lastSpeed = (float) (nvx * fx(yaw) + nvz * fz(yaw));
        lastLat = (float) (nvx * rgx(yaw) + nvz * rgz(yaw));
        return true;
    }

    /** True if the body at this pose would run into blocks higher than a step, or into another vehicle. */
    boolean blocked(World w, VehicleModel m, double cx, double cy, double cz, float cyaw) {
        blockedBy = null;
        double a = Math.PI - Math.toRadians(cyaw), ca = Math.cos(a), sa = Math.sin(a);
        float x0 = m.minX + 0.08f, x1 = m.maxX - 0.08f, z0 = m.minZ + 0.08f, z1 = m.maxZ - 0.08f;
        int nx = Math.max(2, (int) Math.ceil((x1 - x0) / 0.45f) + 1), nz = Math.max(2, (int) Math.ceil((z1 - z0) / 0.45f) + 1);
        float step = type().stepHeight;
        VehicleType ty = type();
        double lo = cy + step, hi = cy + Math.max(step + 0.1, m.maxY - 0.05);
        for (int i = 0; i < nx; i++)
            for (int k = 0; k < nz; k++) {
                if (i != 0 && i != nx - 1 && k != 0 && k != nz - 1 && (i + k) % 2 == 1) continue;   // the inside more sparsely
                double lx = x0 + (x1 - x0) * i / (nx - 1), lz = z0 + (z1 - z0) * k / (nz - 1);
                double gx = cx + lx * ca + lz * sa, gz = cz - lx * sa + lz * ca;
                int bx = (int) Math.floor(gx), bz = (int) Math.floor(gz);
                if (!w.isLoaded(bx, bz)) return true;
                double fx = gx - bx, fz = gz - bz;
                for (int by = (int) Math.floor(lo) - 1; by <= (int) Math.floor(hi); by++) {
                    int s = w.getState(bx, by, bz);
                    if (s == 0) continue;
                    Block b = Blocks.byId(s >> 4);
                    if (!b.solid) continue;
                    if (Math.abs(speed) >= ty.smashSpeed && smashable(b)) continue;          // fast enough: it goes through (the vehicles break it)
                    for (float[] cb : b.collisionShape(s & 15, b.neighborInfo(w, bx, by, bz, s & 15))) {
                        if (fx < cb[0] || fx > cb[3] || fz < cb[2] || fz > cb[5]) continue;
                        if (by + cb[4] > lo && by + cb[1] < hi) return true;
                    }
                }
            }
        Vehicles vs = Vehicles.current;
        if (vs != null) for (Vehicle o : vs.list()) if (o != this && overlaps(m, cx, cy, cz, cyaw, o)) { blockedBy = o; return true; }
        return false;
    }

    /** Footprints (turned rectangles) of this vehicle at a pose and another vehicle overlap (and their heights do). */
    private static boolean overlaps(VehicleModel m, double cx, double cy, double cz, float cyaw, Vehicle o) {
        VehicleModel om = o.vm();
        if (om == null) return false;
        double r1 = Math.hypot(Math.max(-m.minX, m.maxX), Math.max(-m.minZ, m.maxZ)), r2 = Math.hypot(Math.max(-om.minX, om.maxX), Math.max(-om.minZ, om.maxZ));
        double dx = o.x - cx, dz = o.z - cz;
        if (dx * dx + dz * dz > (r1 + r2) * (r1 + r2)) return false;
        if (o.y > cy + m.maxY || o.y + om.maxY < cy) return false;
        double[][] a = corners(m, cx, cz, cyaw), b = corners(om, o.x, o.z, o.yaw);
        return !separated(a, b) && !separated(b, a);
    }

    static double[][] corners(VehicleModel m, double cx, double cz, float cyaw) {
        double an = Math.PI - Math.toRadians(cyaw), ca = Math.cos(an), sa = Math.sin(an);
        double[][] lc = {{m.minX, m.minZ}, {m.maxX, m.minZ}, {m.maxX, m.maxZ}, {m.minX, m.maxZ}};
        double[][] c = new double[4][2];
        for (int i = 0; i < 4; i++) { c[i][0] = cx + lc[i][0] * ca + lc[i][1] * sa; c[i][1] = cz - lc[i][0] * sa + lc[i][1] * ca; }
        return c;
    }

    /** Separating axis test with the edge normals of polygon a. */
    private static boolean separated(double[][] a, double[][] b) {
        for (int i = 0; i < a.length; i++) {
            double[] p = a[i], q = a[(i + 1) % a.length];
            double nx = q[1] - p[1], nz = p[0] - q[0];
            double amin = Double.MAX_VALUE, amax = -Double.MAX_VALUE, bmin = Double.MAX_VALUE, bmax = -Double.MAX_VALUE;
            for (double[] c : a) { double d = c[0] * nx + c[1] * nz; amin = Math.min(amin, d); amax = Math.max(amax, d); }
            for (double[] c : b) { double d = c[0] * nx + c[1] * nz; bmin = Math.min(bmin, d); bmax = Math.max(bmax, d); }
            if (amax < bmin || bmax < amin) return true;
        }
        return false;
    }

    /**
     * NPC driving towards a point: steers with a look at how fast the nose already turns (no zig-zag), keeps a cruising
     * speed with a smooth pedal, slows down for sharp turns and, at the end of the way ('stop' > 0, or not 'through'),
     * rolls to a halt instead of braking hard. After getting stuck it backs out with the wheels turned the other way.
     */
    void autopilot(double tx, double tz, double stop, float cruise, boolean through) {
        double dx = tx - x, dz = tz - z, dist = Math.sqrt(dx * dx + dz * dz);
        float want = (float) Math.toDegrees(Math.atan2(-dx, dz));
        float diff = wrap(want - yaw);
        if (reverse > 0) {
            reverse--;
            throttle = -0.7f;
            steerIn = Math.signum(diff);                   // reversing turns the nose the other way
            return;
        }
        // steering: aim where the heading will be in a few ticks (damps the swing), at most a smooth change per tick
        float rate = wrap(yaw - prevYaw);
        float want0 = Math.max(-1, Math.min(1, -(diff - rate * 7) / 35f));
        steerIn = want0;
        // speed: cruise, slower in sharp turns, roll to a stop near the end
        float target = cruise * (1 - Math.min(0.7f, Math.abs(diff) / 110f));
        if (!through) {
            double room = dist - stop;
            if (room <= 0.3 || Math.abs(speed) < 0.03f && room < 3) target = 0;     // stopped: waits until there is room to go
            else target = (float) Math.min(target, Math.sqrt(2 * 0.025 * room));   // the speed it can lose over the room left
        }
        if (Math.abs(diff) > 110 && dist < 6) target = Math.min(target, 0.15f);   // the point is behind: a slow tight turn
        if (target < autoTarget) target = Math.max(target, autoTarget - 0.02f);   // asks for less speed gradually
        autoTarget = target;
        speedLimit = Math.max(0.0f, target);
        float err = target - speed;
        if (target <= 0.001f && Math.abs(speed) < 0.03f) { handbrake = true; throttle = 0; return; }
        throttle = err > 0 ? Math.min(1, err * 14) : err < -0.4f ? -1 : 0;     // a surplus is lost gently (speed limit), hard braking only in an emergency
        if (stuck > 12) { reverse = 22; stuck = 0; }
    }

    /** Puts the vehicle so that a seat is at the given point (a scene take of a rider drives it). */
    void placeBySeat(int seat, double sx, double sy, double sz, float vyaw, float vpitch, float vroll) {
        VehicleModel m = vm();
        if (m == null || seat < 0 || seat >= m.seats.size()) return;
        float[] s = m.seats.get(seat);
        double[] o = new double[3];
        toWorld(s[0], s[1] + Vehicles.SIT_DROP, s[2], 0, 0, 0, vyaw, vpitch, vroll, o);
        double nx = sx - o[0], ny = sy - o[1], nz = sz - o[2];
        // speed from the movement along the front (for the wheels and for coasting on after the take)
        double mv = (nx - x) * fx(vyaw) + (nz - z) * fz(vyaw);
        // the speed from where the car was at the start of the tick: a passenger's take may have placed it already this tick
        double sp = (nx - px) * fx(vyaw) + (nz - pz) * fz(vyaw);
        if (Math.abs(sp) < 3) speed = (float) sp;
        spin -= (float) mv / m.wheelRadius;
        x = nx; y = gy = ny; z = nz; yVel = 0;
        yaw = vyaw; pitch = vpitch; roll = vroll;
        onGround = true; vy = 0;
    }

    static float wrap(float a) { a %= 360; if (a >= 180) a -= 360; if (a < -180) a += 360; return a; }
}
