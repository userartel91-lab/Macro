package derpcraft.vehicle;

import java.util.Random;

/**
 * The cinematic camera of a vehicle (Right Alt + F8 while riding): third person cameras that follow the car like in GTA.
 * The camera hangs behind, above, beside or in front of the car and trails it on springs (it swings round after the
 * car's heading, stays a little behind when it speeds up), cuts to another shot every few seconds (auto) or stays on the
 * shot picked with Shift + the key. It never goes into blocks (the caller gives the distance a ray can go).
 *
 * <p>No GL, no game classes: the world only enters through {@link Clear}.
 */
public final class VehicleCamera {
    public static final String[] SHOTS = {"Chase", "Far chase", "Low front", "Side", "Helicopter", "Wheel"};
    /** Off, automatic cuts, or a fixed shot (2 + the shot's index). */
    public int mode;
    private int shot;
    private float shotTime, shotLength = 6;
    private boolean snap = true;
    private double cx, cy, cz, lx, ly, lz;
    private float heading;
    private float lastYaw = Float.NaN, bank, fov = 1;
    private final Random random = new Random();

    /** How far a camera can move from a point along a direction before it meets a block (at most 'max'). */
    public interface Clear { double dist(double x, double y, double z, double dx, double dy, double dz, double max); }

    public boolean active() { return mode != 0; }

    /** The key: on / off (automatic cuts); with Shift: the next fixed shot, after the last back to the automatic one. */
    public void press(boolean shift) {
        if (!shift) mode = mode == 0 ? 1 : 0;
        else if (mode != 0) mode = mode == 1 ? 2 : mode + 1 > 1 + SHOTS.length ? 1 : mode + 1;
        snap = true;
        if (mode >= 2) shot = mode - 2;
    }

    public void off() { mode = 0; snap = true; }

    public String name() { return mode == 0 ? "off" : mode == 1 ? "auto (" + SHOTS[shot] + ")" : SHOTS[mode - 2]; }

    /** Zoom factor of the view (a little wider when fast). */
    public float fovFactor() { return fov; }

    /**
     * The camera for this frame: {x, y, z, yaw, pitch, roll} (in doubles: far from the origin; angles in degrees like the game's camera: yaw 0 looks to +z).
     * 'dt' is the frame time in seconds (0 = paused: the camera stays), the pose of the car is the one between the ticks.
     */
    public double[] frame(Vehicle v, VehicleModel m, float partial, float dt, Clear clear) {
        double vx = v.px + (v.x - v.px) * partial, vy = v.py + (v.y - v.py) * partial, vz = v.pz + (v.z - v.pz) * partial;
        float yaw = v.yawAt(partial);
        float len = m.maxZ - m.minZ, wid = m.maxX - m.minX, hgt = m.maxY;
        double speed = Math.hypot(v.x - v.px, v.z - v.pz);
        if (snap || Float.isNaN(lastYaw)) { heading = yaw; lastYaw = yaw; bank = 0; }
        // automatic cuts: another shot every few seconds; a roll over cuts to a wide shot at once
        if (mode == 1) {
            shotTime += dt;
            boolean cut = shotTime > shotLength;
            if (v.flipping() && shot != 1 && shot != 3) { shot = random.nextBoolean() ? 1 : 3; shotTime = 0; snap = true; }
            else if (cut) {
                int next;
                do { next = random.nextInt(SHOTS.length); } while (next == shot);
                shot = next; shotTime = 0; shotLength = 4 + random.nextFloat() * 4; snap = true;
            }
        } else if (mode >= 2) shot = mode - 2;
        // the heading the camera follows trails the car's (and a drift does not whip it round)
        if (dt > 0) heading += wrap(yaw - heading) * (float) (1 - Math.exp(-dt * (shot == 4 ? 2.5 : 4.5)));
        double hr = Math.toRadians(heading);
        double fx = -Math.sin(hr), fz = Math.cos(hr), rx = -Math.cos(hr), rz = -Math.sin(hr);
        double mid = hgt * 0.6;
        double tx = vx + fx * Math.min(4, speed * 14), ty = vy + mid, tz = vz + fz * Math.min(4, speed * 14);     // look a little ahead of the car
        double dx, dy, dz;
        switch (shot) {
            case 1 -> { double b = len * 0.5 + 8.5; dx = vx - fx * b; dy = vy + hgt + 3.4; dz = vz - fz * b; }
            case 2 -> { double a = len * 0.5 + 5.5; dx = vx + fx * a + rx * 2.4; dy = vy + 0.9; dz = vz + fz * a + rz * 2.4; tx = vx; ty = vy + hgt * 0.5; tz = vz; }
            case 3 -> { double a = wid * 0.5 + 5.2; dx = vx + rx * a + fx * 1.2; dy = vy + hgt * 0.8 + 0.5; dz = vz + rz * a + fz * 1.2; tx = vx; ty = vy + hgt * 0.5; tz = vz; }
            case 4 -> { dx = vx - fx * 4.5; dy = vy + hgt + 11; dz = vz - fz * 4.5; tx = vx + fx * 1.5; ty = vy; tz = vz + fz * 1.5; }
            case 5 -> { double b = len * 0.5 + 2.8; dx = vx - fx * b + rx * 1.0; dy = vy + 0.55; dz = vz - fz * b + rz * 1.0; tx = vx + fx * 7; ty = vy + 0.9; tz = vz + fz * 7; }
            default -> { double b = len * 0.5 + 4.0; dx = vx - fx * b; dy = vy + hgt + 1.2; dz = vz - fz * b; }
        }
        // not into blocks: pulled in along the line from the car to where the camera wants to be
        double ax = vx, ay = vy + hgt * 0.6 + 0.3, az = vz;
        double ex = dx - ax, ey = dy - ay, ez = dz - az, el = Math.sqrt(ex * ex + ey * ey + ez * ez);
        if (el > 1e-3 && clear != null) {
            double ok = clear.dist(ax, ay, az, ex / el, ey / el, ez / el, el);
            if (ok < el) { double k = Math.max(0.5, ok) / el; dx = ax + ex * k; dy = ay + ey * k; dz = az + ez * k; }
        }
        if (snap) { cx = dx; cy = dy; cz = dz; lx = tx; ly = ty; lz = tz; snap = false; }
        else if (dt > 0) {
            // the camera trails the car on a spring (a little more loosely when it speeds up); the point it looks at follows tighter
            double kp = 1 - Math.exp(-dt * (shot == 4 ? 3.0 : shot == 5 ? 9 : shot == 2 || shot == 3 ? 13 : 7)), kl = 1 - Math.exp(-dt * 11);
            cx += (dx - cx) * kp; cy += (dy - cy) * kp; cz += (dz - cz) * kp;
            lx += (tx - lx) * kl; ly += (ty - ly) * kl; lz += (tz - lz) * kl;
        }
        // the camera itself (it trails on its spring) is kept out of blocks too
        double fx2 = cx - ax, fy2 = cy - ay, fz2 = cz - az, fl = Math.sqrt(fx2 * fx2 + fy2 * fy2 + fz2 * fz2);
        if (fl > 1e-3 && clear != null) {
            double ok = clear.dist(ax, ay, az, fx2 / fl, fy2 / fl, fz2 / fl, fl);
            if (ok < fl) { double k = Math.max(0.5, ok) / fl; cx = ax + fx2 * k; cy = ay + fy2 * k; cz = az + fz2 * k; }
        }
        double ddx = lx - cx, ddy = ly - cy, ddz = lz - cz;
        float camYaw = (float) Math.toDegrees(Math.atan2(-ddx, ddz));
        float camPitch = (float) -Math.toDegrees(Math.atan2(ddy, Math.sqrt(ddx * ddx + ddz * ddz)));
        // a little lean into turns, and a wider view when fast
        float yawRate = dt > 0 ? wrap(yaw - lastYaw) / Math.max(dt, 1f / 120f) / 20f : 0;      // degrees per tick-ish
        lastYaw = yaw;
        if (dt > 0) bank += (Math.max(-5, Math.min(5, -yawRate * (float) Math.min(1, speed * 3) * 0.7f)) - bank) * (float) (1 - Math.exp(-dt * 4));
        if (dt > 0) fov += (1 + (float) Math.min(0.18, speed * 0.16) - fov) * (float) (1 - Math.exp(-dt * 3));
        return new double[]{cx, cy, cz, camYaw, camPitch, bank};
    }

    private static float wrap(float a) { a %= 360; if (a >= 180) a -= 360; if (a < -180) a += 360; return a; }
}
