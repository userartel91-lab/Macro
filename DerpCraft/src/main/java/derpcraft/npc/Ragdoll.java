package derpcraft.npc;

/**
 * A falling body (no OpenGL): the Minecraft player model as 9 points joined by sticks (Verlet physics), dropping under
 * gravity, bouncing and sliding on blocks. Used when an NPC / actor dies: shot, hit, run over, fallen.
 *
 * <p>Points (world blocks): 0..3 the corners of the torso (top left, top right, bottom left, bottom right; "left" is
 * the body's own left), 4 the top of the head, 5 / 6 the left / right hand, 7 / 8 the left / right foot. The torso is
 * rigid (all 6 sticks), the head hangs on the shoulders, each limb is a rigid stick from its joint (shoulder, hip:
 * worked out from the torso every step) to its end. Soft limits keep the limbs from folding into the body.
 *
 * <p>Model space of the parts follows EntityRenderer's player model: pixels, x = left, y = down, z = back.
 */
public final class Ragdoll {
    /** Whether a point of the world is inside something solid (blocks' collision boxes). */
    public interface Solid { boolean at(double x, double y, double z); }

    public static final int TL = 0, TR = 1, BL = 2, BR = 3, HEAD = 4, LHAND = 5, RHAND = 6, LFOOT = 7, RFOOT = 8, N = 9;
    /** Gravity (blocks / tick^2, like falling entities), air drag per tick, bounce off blocks, share of the sliding speed kept on the ground per step. */
    public static double GRAVITY = 0.08, DRAG = 0.985, BOUNCE = 0.2, FRICTION = 0.12;
    /** How far (pixels) a point keeps from block surfaces: the parts are 4 pixels thick. */
    public static double RADIUS = 1.9;

    /** Positions now and one step before (x, y, z per point), and where each was at the start of the last tick (drawing). */
    public final double[] p = new double[N * 3], q = new double[N * 3], last = new double[N * 3];
    /** Size of the model (1 = normal) and pixels -> blocks. */
    public final double scale, px;
    private final double[][] sticks;            // {a, b, length}
    private int quiet;
    /** Asleep: it lies still and is not simulated until it is pushed. */
    public boolean asleep;
    /** Ticks it has been falling (for the drawing and the sounds of a landing). */
    public int age;

    private Ragdoll(double scale) {
        this.scale = scale;
        this.px = scale / 16;
        sticks = new double[][]{{TL, TR, 0}, {BL, BR, 0}, {TL, BL, 0}, {TR, BR, 0}, {TL, BR, 0}, {TR, BL, 0}, {HEAD, TL, 0}, {HEAD, TR, 0}};
    }

    /**
     * A body standing at (x, y, z) (feet), turned 'yaw' (Minecraft degrees), size 'scale', all of it moving by (vx, vy, vz)
     * blocks per tick; 'lean' tips it forward (radians) so it does not balance on its feet.
     */
    public static Ragdoll standing(double x, double y, double z, float yaw, double scale, double vx, double vy, double vz, double lean) {
        Ragdoll r = new Ragdoll(scale);
        double yr = Math.toRadians(yaw);
        double fx = -Math.sin(yr), fz = Math.cos(yr), lx = Math.cos(yr), lz = Math.sin(yr);
        // local (left, up, forward) in pixels
        double[][] loc = {{4, 24, 0}, {-4, 24, 0}, {4, 12, 0}, {-4, 12, 0}, {0, 32, 0}, {5, 12, 0}, {-5, 12, 0}, {1.9, 0, 0}, {-1.9, 0, 0}};
        double cl = Math.cos(lean), sl = Math.sin(lean);
        for (int i = 0; i < N; i++) {
            double u = loc[i][1], f = loc[i][2];
            // lean around the feet: up tips towards forward
            double u2 = u * cl - f * sl, f2 = u * sl + f * cl;
            double wx = x + (lx * loc[i][0] + fx * f2) * r.px, wy = y + u2 * r.px, wz = z + (lz * loc[i][0] + fz * f2) * r.px;
            r.p[i * 3] = wx; r.p[i * 3 + 1] = wy; r.p[i * 3 + 2] = wz;
            r.q[i * 3] = wx - vx; r.q[i * 3 + 1] = wy - vy; r.q[i * 3 + 2] = wz - vz;
        }
        for (double[] s : r.sticks) s[2] = r.dist((int) s[0], (int) s[1]);
        System.arraycopy(r.p, 0, r.last, 0, r.p.length);
        return r;
    }

    private double dist(int a, int b) {
        double dx = p[a * 3] - p[b * 3], dy = p[a * 3 + 1] - p[b * 3 + 1], dz = p[a * 3 + 2] - p[b * 3 + 2];
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    /** A push at a point of the world (a bullet, a car): the points near it get most of 'impulse' (blocks / tick), the rest a share. */
    public void push(double hx, double hy, double hz, double ix, double iy, double iz) {
        asleep = false; quiet = 0;
        for (int i = 0; i < N; i++) {
            double dx = p[i * 3] - hx, dy = p[i * 3 + 1] - hy, dz = p[i * 3 + 2] - hz;
            double d = Math.sqrt(dx * dx + dy * dy + dz * dz) / scale;
            double w = 0.35 + 0.65 * Math.max(0, 1 - d / 0.9);
            q[i * 3] -= ix * w; q[i * 3 + 1] -= iy * w; q[i * 3 + 2] -= iz * w;
        }
    }

    /** Gives one point a speed of at least (ix, iy, iz) along that way (a car pushing it): it moves with the car, not faster. */
    public void pushPoint(int i, double ix, double iy, double iz) {
        asleep = false; quiet = 0;
        int o = i * 3;
        double[] want = {ix, iy, iz};
        for (int k = 0; k < 3; k++) {
            double v = p[o + k] - q[o + k];
            if (want[k] > 0 ? v < want[k] : v > want[k]) q[o + k] = p[o + k] - want[k];
        }
    }

    /** The middle of the torso (x, y, z). */
    public double[] centre() {
        double[] c = new double[3];
        for (int i = 0; i < 4; i++) for (int k = 0; k < 3; k++) c[k] += p[i * 3 + k] / 4;
        return c;
    }

    /** The lowest point (y), minus the parts' thickness: where the body's box starts. */
    public double bottom() {
        double m = Double.MAX_VALUE;
        for (int i = 0; i < N; i++) m = Math.min(m, p[i * 3 + 1]);
        return m - RADIUS * px;
    }

    /** Whether a point of the world (blocks) is near the body (a car runs into it, a bullet may hit it). */
    public boolean near(double x, double y, double z, double r) {
        for (int i = 0; i < N; i++) {
            double dx = p[i * 3] - x, dy = p[i * 3 + 1] - y, dz = p[i * 3 + 2] - z;
            if (dx * dx + dy * dy + dz * dz < r * r) return true;
        }
        return false;
    }

    // ------------------------------------------------------------------ the joints (from the torso)
    /** Torso axes: left, up, forward (unit) and the middle of its top and bottom edges, into 'out' (15 numbers). */
    private void frame(double[] out) {
        for (int k = 0; k < 3; k++) {
            out[9 + k] = (p[TL * 3 + k] + p[TR * 3 + k]) / 2;
            out[12 + k] = (p[BL * 3 + k] + p[BR * 3 + k]) / 2;
        }
        double ux = out[9] - out[12], uy = out[10] - out[13], uz = out[11] - out[14];
        double ul = Math.sqrt(ux * ux + uy * uy + uz * uz) + 1e-9; ux /= ul; uy /= ul; uz /= ul;
        double lx = (p[TL * 3] + p[BL * 3] - p[TR * 3] - p[BR * 3]) / 2, ly = (p[TL * 3 + 1] + p[BL * 3 + 1] - p[TR * 3 + 1] - p[BR * 3 + 1]) / 2,
                lz = (p[TL * 3 + 2] + p[BL * 3 + 2] - p[TR * 3 + 2] - p[BR * 3 + 2]) / 2;
        double dot = lx * ux + ly * uy + lz * uz;
        lx -= ux * dot; ly -= uy * dot; lz -= uz * dot;
        double ll = Math.sqrt(lx * lx + ly * ly + lz * lz) + 1e-9; lx /= ll; ly /= ll; lz /= ll;
        // forward = left x up
        double fx = ly * uz - lz * uy, fy = lz * ux - lx * uz, fz = lx * uy - ly * ux;
        out[0] = lx; out[1] = ly; out[2] = lz; out[3] = ux; out[4] = uy; out[5] = uz; out[6] = fx; out[7] = fy; out[8] = fz;
    }

    /** Joint of limb point 'i' (hand: shoulder 5 px out, 2 px down; foot: hip 1.9 px out) into j[0..2]. */
    private void joint(int i, double[] f, double[] j) {
        boolean left = i == LHAND || i == LFOOT, arm = i == LHAND || i == RHAND;
        double side = (left ? 1 : -1) * (arm ? 5 : 1.9) * px;
        int o = arm ? 9 : 12;
        double down = arm ? 2 * px : 0;
        for (int k = 0; k < 3; k++) j[k] = f[o + k] + f[k] * side - f[3 + k] * down;
    }

    /** Length of a limb (pixels from its joint to its end): arms 10, legs 12. */
    private static double limb(int i) { return i == LHAND || i == RHAND ? 10 : 12; }

    // ------------------------------------------------------------------ simulation
    /** One game tick (in a few steps when it moves fast). */
    public void tick(Solid w) {
        System.arraycopy(p, 0, last, 0, p.length);
        if (asleep) return;
        age++;
        double vmax = 0;
        for (int i = 0; i < N * 3; i++) vmax = Math.max(vmax, Math.abs(p[i] - q[i]));
        int steps = (int) Math.max(1, Math.min(4, Math.ceil(vmax / 0.35)));
        // p - q is the speed per step: a tick cut into 'steps' steps moves 1 / steps as far in each
        if (steps > 1) for (int i = 0; i < N * 3; i++) q[i] = p[i] - (p[i] - q[i]) / steps;
        double moved = 0;
        for (int s = 0; s < steps; s++) moved += step(w, 1.0 / steps);
        if (steps > 1) for (int i = 0; i < N * 3; i++) q[i] = p[i] - (p[i] - q[i]) * steps;
        if (moved < 0.004 * scale) { if (++quiet > 25) asleep = true; } else quiet = 0;
    }

    private final double[] f = new double[15], j = new double[3], before = new double[N * 3];
    /** The blocks of the step being simulated (the sticks never pull a point into them). */
    private Solid world;
    /** Points touching the ground this step (they also grip: their sliding is damped). */
    private final boolean[] grounded = new boolean[N];

    /** Moves point i by (dx, dy, dz) unless that puts it into a block; returns whether it moved. */
    private boolean shift(int i, double dx, double dy, double dz) {
        int o = i * 3;
        double nx = p[o] + dx, ny = p[o + 1] + dy, nz = p[o + 2] + dz;
        if (world != null && world.at(nx, ny, nz)) return false;
        p[o] = nx; p[o + 1] = ny; p[o + 2] = nz;
        return true;
    }

    private double step(Solid w, double h) {
        world = w;
        // integrate: v = p - q, damped, plus gravity (a fraction h of a tick: the velocity is per sub-step)
        double moved = 0;
        System.arraycopy(p, 0, before, 0, p.length);
        double drag = Math.pow(DRAG, h);
        for (int i = 0; i < N; i++) {
            int o = i * 3;
            double vx = (p[o] - q[o]) * drag, vy = (p[o + 1] - q[o + 1]) * drag - GRAVITY * h * h, vz = (p[o + 2] - q[o + 2]) * drag;   // speed per step
            q[o] = p[o]; q[o + 1] = p[o + 1]; q[o + 2] = p[o + 2];
            grounded[i] = moveAxis(w, i, vx, vy, vz);
        }
        for (int it = 0; it < 8; it++) {
            for (double[] s : sticks) stick((int) s[0], (int) s[1], s[2], 0.5);
            frame(f);
            for (int i = LHAND; i <= RFOOT; i++) {
                joint(i, f, j);
                limbStick(i, j, limb(i) * px);
            }
            // soft limits: hands not through the chest, legs not folded up onto it, feet apart
            frame(f);
            keepAway(LHAND, f[9] - f[0] * 5 * px, f[10] - f[1] * 5 * px, f[11] - f[2] * 5 * px, 7 * px);
            keepAway(RHAND, f[9] + f[0] * 5 * px, f[10] + f[1] * 5 * px, f[11] + f[2] * 5 * px, 7 * px);
            keepAway(LFOOT, f[9], f[10], f[11], 15 * px);
            keepAway(RFOOT, f[9], f[10], f[11], 15 * px);
            minStick(LFOOT, RFOOT, 2.5 * px);
            minStick(LHAND, HEAD, 5 * px);
            minStick(RHAND, HEAD, 5 * px);
            // the middle of the limbs and the torso's edges do not pass through blocks either (a leg over a kerb)
            if (it % 2 == 1) {
                frame(f);
                for (int i = LHAND; i <= RFOOT; i++) {
                    joint(i, f, j);
                    boolean arm = i == LHAND || i == RHAND, left = i == LHAND || i == LFOOT;
                    int c = arm ? (left ? TL : TR) : (left ? BL : BR);
                    segment(c, j[0], j[1], j[2], i);
                }
                segment(TL, p[TL * 3], p[TL * 3 + 1], p[TL * 3 + 2], BL);
                segment(TR, p[TR * 3], p[TR * 3 + 1], p[TR * 3 + 2], BR);
                segment(TL, p[TL * 3], p[TL * 3 + 1], p[TL * 3 + 2], BR);
                segment(TR, p[TR * 3], p[TR * 3 + 1], p[TR * 3 + 2], BL);
            }
        }
        for (int i = 0; i < N; i++) {
            int o = i * 3;
            // a point lying on the ground grips it: what the sticks made it slide this step is mostly taken back
            if (grounded[i]) {
                q[o] += (p[o] - q[o]) * (1 - FRICTION);
                q[o + 2] += (p[o + 2] - q[o + 2]) * (1 - FRICTION);
            }
            double dx = p[o] - before[o], dy = p[o + 1] - before[o + 1], dz = p[o + 2] - before[o + 2];
            moved += Math.abs(dx) + Math.abs(dy) + Math.abs(dz);
        }
        return moved;
    }

    /** Moves point i by (vx, vy, vz), one axis at a time; an axis that runs into a block stops (bounces a little). Returns whether it is on the ground. */
    private boolean moveAxis(Solid w, int i, double vx, double vy, double vz) {
        int o = i * 3;
        double r = RADIUS * px;
        double[] v = {vx, vy, vz};
        boolean ground = false;
        for (int a : new int[]{1, 0, 2}) {
            if (v[a] == 0) continue;
            double nx = p[o] + (a == 0 ? v[0] : 0), ny = p[o + 1] + (a == 1 ? v[1] : 0), nz = p[o + 2] + (a == 2 ? v[2] : 0);
            double ex = a == 0 ? Math.signum(v[0]) * r : 0, ey = a == 1 ? Math.signum(v[1]) * r : 0, ez = a == 2 ? Math.signum(v[2]) * r : 0;
            if (w.at(nx + ex, ny + ey, nz + ez) || w.at(nx, ny, nz)) {
                if (a == 1 && v[1] < 0) ground = true;
                // stays on this axis; the stored previous position makes it come back off the surface a little
                q[o + a] = p[o + a] + v[a] * BOUNCE;
                v[a] = 0;
            } else {
                p[o] = nx; p[o + 1] = ny; p[o + 2] = nz;
            }
        }
        if (!ground && w.at(p[o], p[o + 1] - r - 0.01, p[o + 2])) ground = true;        // resting on it
        return ground;
    }

    private void stick(int a, int b, double len, double wa) {
        int oa = a * 3, ob = b * 3;
        double dx = p[ob] - p[oa], dy = p[ob + 1] - p[oa + 1], dz = p[ob + 2] - p[oa + 2];
        double d = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (d < 1e-9) return;
        double cx = dx * (d - len) / d, cy = dy * (d - len) / d, cz = dz * (d - len) / d;
        // a point that cannot move (a block in the way) leaves its share of the correction to the other one
        boolean ma = shift(a, cx * wa, cy * wa, cz * wa), mb = shift(b, -cx * (1 - wa), -cy * (1 - wa), -cz * (1 - wa));
        if (!ma && mb) shift(b, -cx * wa, -cy * wa, -cz * wa);
        else if (ma && !mb) shift(a, cx * (1 - wa), cy * (1 - wa), cz * (1 - wa));
    }

    /** A limb end held at its length from its joint: the end moves most, the torso corners next to the joint take the rest. */
    private void limbStick(int i, double[] jt, double len) {
        int o = i * 3;
        double dx = p[o] - jt[0], dy = p[o + 1] - jt[1], dz = p[o + 2] - jt[2];
        double d = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (d < 1e-9) return;
        double k = (d - len) / d;
        double ex = dx * k, ey = dy * k, ez = dz * k;
        boolean arm = i == LHAND || i == RHAND, left = i == LHAND || i == LFOOT;
        int c = arm ? (left ? TL : TR) : (left ? BL : BR);
        double w = shift(i, -ex * 0.7, -ey * 0.7, -ez * 0.7) ? 0.3 : 1;
        if (!shift(c, ex * w, ey * w, ez * w) && w < 1) shift(i, -ex * 0.3, -ey * 0.3, -ez * 0.3);
    }

    /** Point i kept at least 'min' from a point of the torso (only the point moves). */
    private void keepAway(int i, double x, double y, double z, double min) {
        int o = i * 3;
        double dx = p[o] - x, dy = p[o + 1] - y, dz = p[o + 2] - z;
        double d = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (d >= min || d < 1e-9) return;
        double k = (min - d) / d;
        shift(i, dx * k, dy * k, dz * k);
    }

    private final double[] exit = new double[3];

    /**
     * The line from (ax, ay, az) (where point 'a' holds it: a joint, or 'a' itself) to point 'b': its third and two thirds
     * are tested against blocks; one inside is pushed out of its block through the nearest open face, moving both ends.
     */
    private void segment(int a, double ax, double ay, double az, int b) {
        for (double t : new double[]{0.33, 0.66}) {
            double sx = ax + (p[b * 3] - ax) * t, sy = ay + (p[b * 3 + 1] - ay) * t, sz = az + (p[b * 3 + 2] - az) * t;
            if (!world.at(sx, sy, sz) || !exitOf(sx, sy, sz)) continue;
            // the end nearer the stuck point moves more: the line turns out of the block
            shift(a, exit[0] * (1 - t) * 2, exit[1] * (1 - t) * 2, exit[2] * (1 - t) * 2);
            shift(b, exit[0] * t * 2, exit[1] * t * 2, exit[2] * t * 2);
        }
    }

    /** Directions tried to get out of a block: up first (lying on things), then the sides, down last. */
    private static final int[][] DIRS = {{0, 1, 0}, {1, 0, 0}, {-1, 0, 0}, {0, 0, 1}, {0, 0, -1}, {0, -1, 0}};

    /** The shortest way out of what a point is in (probed: works for slabs, stairs, kerbs), plus the parts' thickness, into 'exit'. */
    private boolean exitOf(double x, double y, double z) {
        double r = RADIUS * px;
        for (double d = 0.04; d <= 0.6; d += 0.04)
            for (int[] dir : DIRS)
                if (!world.at(x + dir[0] * d, y + dir[1] * d, z + dir[2] * d)) {
                    double m = d + r;
                    exit[0] = dir[0] * m; exit[1] = dir[1] * m; exit[2] = dir[2] * m;
                    return true;
                }
        return false;
    }

    private void minStick(int a, int b, double min) {
        if (dist(a, b) < min) stick(a, b, min, 0.5);
    }

    // ------------------------------------------------------------------ drawing
    /**
     * Where the parts are, between the last tick and now ('partial'), relative to (ox, oy, oz): for each part (0 head,
     * 1 body, 2 right arm, 3 left arm, 4 right leg, 5 left leg) a column-major 4x4 matrix (16 floats) from the part's
     * model space (pixels as the player model's boxes use them / 16: x left, y down along the limb, z back, origin at its
     * pivot) to the world, scaled by the size. 96 floats.
     */
    public float[] parts(float partial, double ox, double oy, double oz) {
        double[] cur = new double[N * 3];
        for (int i = 0; i < N * 3; i++) cur[i] = last[i] + (p[i] - last[i]) * partial;
        double[] keep = p.clone();
        System.arraycopy(cur, 0, p, 0, p.length);
        double[] fr = new double[15], jt = new double[3];
        frame(fr);
        float[] out = new float[96];
        double lx = fr[0], ly = fr[1], lz = fr[2];
        // head: from its top down to the neck
        double[] neck = {fr[9], fr[10], fr[11]};
        put(out, 0, neck, sub(neck, HEAD), lx, ly, lz, ox, oy, oz);
        // body: from the neck down to the hips
        put(out, 1, neck, new double[]{fr[12] - fr[9], fr[13] - fr[10], fr[14] - fr[11]}, lx, ly, lz, ox, oy, oz);
        int[][] limbs = {{2, RHAND}, {3, LHAND}, {4, RFOOT}, {5, LFOOT}};
        for (int[] lb : limbs) {
            joint(lb[1], fr, jt);
            double[] a = jt.clone();
            put(out, lb[0], a, new double[]{p[lb[1] * 3] - a[0], p[lb[1] * 3 + 1] - a[1], p[lb[1] * 3 + 2] - a[2]}, lx, ly, lz, ox, oy, oz);
        }
        System.arraycopy(keep, 0, p, 0, p.length);
        return out;
    }

    /** Direction from point i to 'to'. */
    private double[] sub(double[] to, int i) { return new double[]{to[0] - p[i * 3], to[1] - p[i * 3 + 1], to[2] - p[i * 3 + 2]}; }

    /** A part's matrix: origin 'o', +y along 'down' (unit after this), +x = the torso's left made square to it, +z = x cross y. */
    private void put(float[] out, int part, double[] o, double[] down, double lx, double ly, double lz, double ox, double oy, double oz) {
        double yl = Math.sqrt(down[0] * down[0] + down[1] * down[1] + down[2] * down[2]);
        double yx, yy, yz;
        if (yl < 1e-9) { yx = 0; yy = -1; yz = 0; } else { yx = down[0] / yl; yy = down[1] / yl; yz = down[2] / yl; }
        double d = lx * yx + ly * yy + lz * yz;
        double xx = lx - yx * d, xy = ly - yy * d, xz = lz - yz * d;
        double xl = Math.sqrt(xx * xx + xy * xy + xz * xz);
        if (xl < 1e-4) {                       // the limb runs along the shoulders: any square axis
            xx = yy; xy = -yx; xz = 0; xl = Math.sqrt(xx * xx + xy * xy) + 1e-9;
            if (xl < 1e-4) { xx = 0; xy = yz; xz = -yy; xl = Math.sqrt(xy * xy + xz * xz) + 1e-9; }
        }
        xx /= xl; xy /= xl; xz /= xl;
        double zx = xy * yz - xz * yy, zy = xz * yx - xx * yz, zz = xx * yy - xy * yx;
        int b = part * 16;
        float s = (float) scale;
        out[b] = (float) xx * s; out[b + 1] = (float) xy * s; out[b + 2] = (float) xz * s; out[b + 3] = 0;
        out[b + 4] = (float) yx * s; out[b + 5] = (float) yy * s; out[b + 6] = (float) yz * s; out[b + 7] = 0;
        out[b + 8] = (float) zx * s; out[b + 9] = (float) zy * s; out[b + 10] = (float) zz * s; out[b + 11] = 0;
        out[b + 12] = (float) (o[0] - ox); out[b + 13] = (float) (o[1] - oy); out[b + 14] = (float) (o[2] - oz); out[b + 15] = 1;
    }
}
