package derpcraft.game;

import derpcraft.block.Block;
import derpcraft.block.Blocks;
import derpcraft.world.World;


/** The player with Minecraft 1.12 movement physics (walking, sprinting, sneaking, creative flight, swimming, noclip). */
public final class Player {
    public static final float WIDTH = 0.6f, HEIGHT = Stance.STAND.height, EYE = Stance.STAND.eye;
    /** How fast the camera, the hit box and the model follow a change of posture (share of the way per tick). */
    private static final float EASE = 0.22f;
    /** Collision box size (creatures use other sizes). */
    public float width = WIDTH, height = HEIGHT;

    public double x, y, z, px, py, pz;
    public double mx, my, mz;
    public float yaw, pitch;
    public boolean onGround, flying, sneaking, sprinting, inWater, noclip;
    public float flySpeedMultiplier = 1f;
    public float eyeHeight = EYE, prevEyeHeight = EYE;
    /** The posture asked for with Alt + 1..4 (STAND, SIT, LIE, CRAWL). */
    public Stance stance = Stance.STAND;
    /** Walking / running speed multiplier (NPC setting; the player keeps 1). */
    public float speedMul = 1;
    /** The posture the player is in now: the asked one plus Shift / Alt + Shift, lowered when there is no room above. */
    public Stance pose = Stance.STAND;
    /** Smoothed model parameters of the posture (see {@link Stance}); the previous tick's copy is for interpolation. */
    public final float[] vis = new float[Stance.N], prevVis = new float[Stance.N];
    private boolean getUpHeld;
    public boolean collidedHorizontally;
    private int jumpTicks, spaceTapTicks;
    private boolean prevJumpKey;
    public float walkDist, prevWalkDist;
    public float fallDistance;
    /** Fall distance of the last landing (Session turns it into damage). */
    public float landed;
    public boolean onLadder;

    /** Input for one tick. */
    public static final class Input {
        public float forward, strafe;
        public boolean jump, sneak, sprint;
        /** Alt + Shift held (together with {@code sneak}): the deep crouch. */
        public boolean deepSneak;
    }

    public void setPos(double x, double y, double z) {
        this.x = px = x; this.y = py = y; this.z = pz = z;
        mx = my = mz = 0;
    }

    public void tick(World w, Input in, boolean creative) {
        px = x; py = y; pz = z;
        prevEyeHeight = eyeHeight;
        System.arraycopy(vis, 0, prevVis, 0, vis.length);
        prevWalkDist = walkDist;

        // jump gets the player up from sitting, lying and crawling (the press is used up, no jump follows)
        if (getUpHeld) { if (!in.jump) getUpHeld = false; else in.jump = false; }
        if (in.jump && !prevJumpKey && stance != Stance.STAND) { stance = Stance.STAND; getUpHeld = true; in.jump = false; }

        // double-tap jump toggles flight in creative
        if (creative && !noclip) {
            if (in.jump && !prevJumpKey) {
                if (spaceTapTicks > 0) { flying = !flying; spaceTapTicks = 0; }
                else spaceTapTicks = 7;
            }
        }
        if (spaceTapTicks > 0) spaceTapTicks--;
        prevJumpKey = in.jump;
        if (noclip) flying = true;

        sneaking = in.sneak && !flying;
        inWater = isInWater(w);
        updatePose(w, in);

        float fwd = in.forward, str = in.strafe;
        float walk = pose.speed;
        if (sneaking) walk = Math.min(walk, 0.3f);
        if (walk != 1f) { fwd *= walk; str *= walk; }
        sprinting = in.sprint && fwd > 0 && !sneaking && pose == Stance.STAND && (!collidedHorizontally || flying);
        if (fwd <= 0) sprinting = false;

        if (flying) {
            float speed = 0.05f * flySpeedMultiplier * (sprinting ? 2f : 1f);
            accelerate(str, fwd, speed);
            if (in.jump) my += 0.15f * flySpeedMultiplier;
            if (in.sneak) my -= 0.15f * flySpeedMultiplier;
            move(w, mx, my, mz);
            mx *= 0.91; mz *= 0.91;
            my *= 0.6;
            if (onGround && !noclip) flying = false;
        } else if (inWater) {
            accelerate(str, fwd, 0.02f);
            if (in.jump) my += 0.04;
            move(w, mx, my, mz);
            mx *= 0.8; my *= 0.8; mz *= 0.8;
            my -= 0.02;
            if (collidedHorizontally && in.jump) my = 0.3;
        } else {
            float friction = onGround ? 0.546f : 0.91f;
            float accel = onGround ? 0.1f * (0.16277136f / (friction * friction * friction)) : 0.02f;
            if (sprinting) accel *= 1.3f;
            if (in.jump && onGround && jumpTicks == 0) {
                my = 0.42;
                if (sprinting) {
                    float r = (float) Math.toRadians(yaw);
                    mx -= Math.sin(r) * 0.2f;
                    mz += Math.cos(r) * 0.2f;
                }
                jumpTicks = 10;
            }
            accelerate(str, fwd, accel);
            onLadder = isOnLadder(w);
            if (onLadder) {
                mx = Math.max(-0.15, Math.min(0.15, mx));
                mz = Math.max(-0.15, Math.min(0.15, mz));
                my = Math.max(my, -0.15);
                fallDistance = 0;
                if (in.sneak && my < 0) my = 0;
            }
            move(w, mx, my, mz);
            if (onLadder && collidedHorizontally) my = 0.2;
            my = (my - 0.08) * 0.98;
            mx *= friction; mz *= friction;
        }
        // fall distance -> landing damage (consumed by the session)
        if (flying || inWater || noclip) fallDistance = 0;
        else if (onGround) { if (fallDistance > 0) landed = fallDistance; fallDistance = 0; }
        else if (y < py) fallDistance += (float) (py - y);
        if (jumpTicks > 0) jumpTicks--;
        if (!in.jump) jumpTicks = 0;
        double dx = x - px, dz = z - pz;
        if (onGround) walkDist += (float) Math.sqrt(dx * dx + dz * dz) * 0.6f;
    }

    /**
     * Picks the posture (asked stance + Shift, or one that fits under the ceiling) and eases the camera, the hit box and
     * the model parameters towards it: the camera follows where the head is, instead of jumping.
     */
    private void updatePose(World w, Input in) {
        Stance want;
        if (flying || inWater || noclip) { want = Stance.STAND; stance = Stance.STAND; }   // flying and swimming end sitting / lying
        else if (stance != Stance.STAND) want = stance;
        else want = in.sneak ? (in.deepSneak ? Stance.DEEP : Stance.SNEAK) : Stance.STAND;
        if (want != pose && want.height > pose.height) {
            // getting taller: only as tall as the space above allows (standing up in a tunnel keeps the player crouched)
            int i = 0;
            while (Stance.BY_HEIGHT[i] != want) i++;
            while (i < Stance.BY_HEIGHT.length - 1 && !fits(w, Stance.BY_HEIGHT[i].height)) i++;
            want = Stance.BY_HEIGHT[i];
        }
        pose = want;
        // the hit box grows at once (the room was checked) and shrinks together with the camera
        if (pose.height >= height) height = pose.height;
        else { height += (pose.height - height) * EASE; if (height - pose.height < 0.01f) height = pose.height; }
        eyeHeight += (pose.eye - eyeHeight) * EASE;
        for (int i = 0; i < vis.length; i++) vis[i] += (pose.params[i] - vis[i]) * EASE;
    }

    /** True when a hit box of this height fits at the current position (no block inside it). */
    private boolean fits(World w, float h) {
        double r = width / 2;
        collectBoxes(w, x - r, y, z - r, x + r, y + h, z + r);
        return !boxes.overlaps(x - r, y, z - r, x + r, y + h, z + r, 1e-4);
    }

    /** The model parameters between the last two ticks (see {@link Stance}), for drawing. */
    public float[] visual(float partial) {
        float[] out = new float[vis.length];
        for (int i = 0; i < out.length; i++) out[i] = prevVis[i] + (vis[i] - prevVis[i]) * partial;
        return out;
    }

    private void accelerate(float strafe, float forward, float speed) {
        float d = strafe * strafe + forward * forward;
        if (d < 1.0E-4F) return;
        d = (float) Math.sqrt(d);
        if (d < 1.0F) d = 1.0F;
        d = speed * speedMul / d;
        strafe *= d * 0.98f;
        forward *= d * 0.98f;
        float r = (float) Math.toRadians(yaw);
        float s = (float) Math.sin(r), c = (float) Math.cos(r);
        mx += strafe * c - forward * s;
        mz += forward * c + strafe * s;
    }

    private boolean isOnLadder(World w) {
        return w.getBlock((int) Math.floor(x), (int) Math.floor(y), (int) Math.floor(z)).climbable;
    }

    private boolean isInWater(World w) {
        int bx = (int) Math.floor(x), bz = (int) Math.floor(z);
        for (int yy = (int) Math.floor(y + 0.1); yy <= (int) Math.floor(y + 0.9); yy++)
            if (w.getBlock(bx, yy, bz) == Blocks.WATER) return true;
        return false;
    }

    public boolean eyeInWater(World w, float partial) {
        double ey = py + (y - py) * partial + eyeHeight;
        int bx = (int) Math.floor(px + (x - px) * partial), bz = (int) Math.floor(pz + (z - pz) * partial);
        Block b = w.getBlock(bx, (int) Math.floor(ey), bz);
        if (b != Blocks.WATER) return false;
        boolean full = w.getBlock(bx, (int) Math.floor(ey) + 1, bz) == Blocks.WATER;
        return full || ey - Math.floor(ey) < 0.875;
    }

    // ------------------------------------------------------------------ collision
    /** Boxes around the body, gathered once per move (see {@link #move}) and reused: no arrays per tick. */
    private final derpcraft.world.Collision.Boxes boxes = new derpcraft.world.Collision.Boxes();

    private void collectBoxes(World w, double x0, double y0, double z0, double x1, double y1, double z1) {
        boxes.clear();
        derpcraft.world.Collision.collect(w, x0, y0, z0, x1, y1, z1, boxes, this, true);
    }

    public static int connections(World w, Block b, int x, int y, int z) {
        return b.neighborInfo(w, x, y, z, w.getMeta(x, y, z));
    }

    public double[] aabb() {
        double h = width / 2;
        return new double[]{x - h, y, z - h, x + h, y + height, z + h};
    }

    private void move(World w, double dx, double dy, double dz) {
        if (noclip) { x += dx; y += dy; z += dz; onGround = false; collidedHorizontally = false; return; }
        double h = width / 2;
        double odx = dx, ody = dy, odz = dz;
        // sneaking: don't walk off edges (the boxes under the reachable area are gathered once for all the tries)
        if (sneaking && onGround) {
            double ax = Math.abs(dx), az = Math.abs(dz);
            collectBoxes(w, x - h - ax, y - 1.0, z - h - az, x + h + ax, y, z + h + az);
            double step = 0.05;
            while (dx != 0 && !hasGround(x + dx, z)) { if (Math.abs(dx) < step) dx = 0; else dx -= Math.signum(dx) * step; }
            while (dz != 0 && !hasGround(x, z + dz)) { if (Math.abs(dz) < step) dz = 0; else dz -= Math.signum(dz) * step; }
            while (dx != 0 && dz != 0 && !hasGround(x + dx, z + dz)) {
                if (Math.abs(dx) < step) dx = 0; else dx -= Math.signum(dx) * step;
                if (Math.abs(dz) < step) dz = 0; else dz -= Math.signum(dz) * step;
            }
            odx = dx; odz = dz;
        }
        // one gathering covers the move and the step up / step down tries (extra boxes never change a clip)
        boolean canStep = !flying;
        collectBoxes(w, x - h + Math.min(0, dx), y + Math.min(0, dy), z - h + Math.min(0, dz),
                x + h + Math.max(0, dx), y + height + Math.max(0, dy) + (canStep ? STEP : 0), z + h + Math.max(0, dz));
        double rx, ry, rz;
        sweep(x, y, z, dx, dy, dz);
        rx = swept[0]; ry = swept[1]; rz = swept[2];
        // step up (slabs, stairs) like vanilla stepHeight = 0.6
        boolean blockedH = rx != dx || rz != dz;
        if (blockedH && (onGround || (ody < 0 && ry != ody)) && canStep) {
            sweep(x, y, z, 0, STEP, 0);
            double up = swept[1];
            sweep(x, y + up, z, dx, 0, dz);
            double sx = swept[0], sz = swept[2];
            sweep(x + sx, y + up, z + sz, 0, -up + (dy < 0 ? dy : 0), 0);
            double down = swept[1];
            double h1 = rx * rx + rz * rz, h2 = sx * sx + sz * sz;
            if (h2 > h1 + 1e-7) { rx = sx; ry = up + down; rz = sz; }
        }
        x += rx; y += ry; z += rz;
        collidedHorizontally = Math.abs(rx - odx) > 1e-7 || Math.abs(rz - odz) > 1e-7;
        boolean vert = Math.abs(ry - ody) > 1e-7;
        onGround = vert && ody < 0;
        if (vert) my = 0;
        if (Math.abs(rx - odx) > 1e-7) mx = 0;
        if (Math.abs(rz - odz) > 1e-7) mz = 0;
    }

    private static final double STEP = 0.6;

    /** Whether there is ground under the body moved to (nx, nz), among the boxes gathered by {@link #move}. */
    private boolean hasGround(double nx, double nz) {
        double h = width / 2;
        return boxes.overlaps(nx - h, y - 1.0, nz - h, nx + h, y, nz + h, 0);
    }

    /** Result of {@link #sweep}: the allowed movement x, y, z. */
    private final double[] swept = new double[3];

    /** Moves an AABB at (x,y,z) by (dx,dy,dz) against the gathered collision boxes; the allowed movement goes to {@link #swept}. */
    private void sweep(double x, double y, double z, double dx, double dy, double dz) {
        double h = width / 2;
        double x0 = x - h, y0 = y, z0 = z - h, x1 = x + h, y1 = y + height, z1 = z + h;
        dy = boxes.clip(1, dy, x0, y0, z0, x1, y1, z1);
        y0 += dy; y1 += dy;
        dx = boxes.clip(0, dx, x0, y0, z0, x1, y1, z1);
        x0 += dx; x1 += dx;
        dz = boxes.clip(2, dz, x0, y0, z0, x1, y1, z1);
        swept[0] = dx; swept[1] = dy; swept[2] = dz;
    }

}
