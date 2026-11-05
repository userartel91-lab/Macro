package derpcraft.vehicle;

import derpcraft.world.World;

import java.nio.file.*;
import java.util.*;

/**
 * Drives a car over stepped, Minecraft-like terrain (hills of 1 block steps) and measures how rough it is: the biggest
 * jump of the body per tick, the biggest change of the nose angle per tick and the "jerk" (second difference of the height).
 * Usage: java derpcraft.vehicle.BumpTest <vehicle folder>
 */
public class BumpTest {
    /** Size of the random 1 block steps on top of the hills (0 = smooth rolling hills, 0.6 = rough, like a field of steps). */
    static double NOISE = 0.6;

    static int hash(int x, int z) { int h = x * 73856093 ^ z * 19349663; h ^= h >>> 13; h *= 0x5bd1e995; h ^= h >>> 15; return h & 0x7fffffff; }

    /** Rolling hills: smooth noise rounded to whole blocks (so steps of 1 block every few blocks), amplitude 'amp'. */
    static int height(int x, int z, double amp) {
        double v = amp * (Math.sin(x * 0.11 + 1.3) + Math.sin(z * 0.07 + 0.4) + 0.6 * Math.sin((x + z) * 0.23)) / 2.6 + (hash(x / 3, z / 3) % 100) / 100.0 * NOISE;
        return 64 + (int) Math.round(v);
    }

    /** A long even slope (rise per block 'k'): the shown height must stay close to the ground, no lag, no sinking. */
    static void slope(VehicleType ty, Vehicles vs, double k) {
        World w = new World();
        w.groundY = -100;
        for (int x = -10; x < 10; x++) for (int z = -10; z < 300; z++) for (int y = 40; y <= 64 + (int) Math.floor(Math.max(0, z) * k); y++) w.set(x, y, z, 1);
        Vehicle v = new Vehicle(1, ty.name); vs.l.clear(); vs.l.add(v); v.vm();
        v.setPose(0.5, 65, 0.5, 0);
        v.riders[v.vm().driverSeat] = 7;
        double lagMax = 0, lagMin = 0;
        for (int t = 0; t < 200; t++) {
            v.throttle = 1; v.tick(w, false);
            if (t > 40) { lagMax = Math.max(lagMax, v.y - v.gy); lagMin = Math.min(lagMin, v.y - v.gy); }
        }
        System.out.printf("slope %.0f%%: z %.0f, %.0f km/h, pitch %.0f deg, height rel. to the ground: %.2f .. %.2f, stuck %d%n", k * 100, v.z, v.kmh(), v.pitch, lagMin, lagMax, v.stuck);
    }

    /** Drives off the edge of a platform 'h' blocks high at about 'kmh' and prints the shown height, ground height and angles per tick. */
    static void dropOff(VehicleType ty, Vehicles vs, int h, boolean trace) { dropOff(ty, vs, h, trace, false); }

    /** 'up': the platform is after the start instead (driving up onto a step 'h' blocks high). */
    static void dropOff(VehicleType ty, Vehicles vs, int h, boolean trace, boolean up) {
        World w = new World();
        w.groundY = -100;
        for (int x = -10; x < 10; x++) for (int z = -10; z < 300; z++) for (int y = 40; y <= (z < 60 ? 64 + (up ? 0 : h) : 64 + (up ? h : 0)); y++) w.set(x, y, z, 1);
        Vehicle v = new Vehicle(1, ty.name); vs.l.clear(); vs.l.add(v); v.vm();
        v.setPose(0.5, 64 + (up ? 0 : h) + 1, 5.5, 0);
        v.riders[v.vm().driverSeat] = 7;
        double maxJump = 0, prevY = v.y, prevDy = 0, maxJerk = 0; float maxDp = 0, prevP = 0;
        if (trace) System.out.println("   t   z     y     gy   dy    pitch roll air");
        int t0 = -1;
        for (int t = 0; t < 140; t++) {
            v.throttle = 1; v.tick(w, false);
            double dy = v.y - prevY;
            if (t0 < 0 && v.z > 52) t0 = t;
            if (t0 >= 0 && t < t0 + 30) {
                maxJump = Math.max(maxJump, Math.abs(dy)); maxJerk = Math.max(maxJerk, Math.abs(dy - prevDy)); maxDp = Math.max(maxDp, Math.abs(v.pitch - prevP));
                if (trace) System.out.printf("%4d %5.1f %6.2f %6.2f %5.2f %6.1f %5.1f %s%n", t - t0, v.z, v.y, v.gy, dy, v.pitch, v.roll, v.onGround ? "" : "air");
            }
            prevDy = dy; prevY = v.y; prevP = v.pitch;
        }
        System.out.printf((up ? "step up %d blocks at" : "drop %d blocks at") + " ~%.0f km/h: shown height max change/tick %.2f, jerk %.2f, nose max change/tick %.1f deg, ends at y %.2f (ground %.2f)%n", h, v.kmh(), maxJump, maxJerk, maxDp, v.y, v.gy);
    }

    public static void main(String[] args) throws Exception {
        VehicleType ty = Transports.add(Path.of(args[0]));
        Vehicles vs = new Vehicles(); Vehicles.current = vs;
        if (args.length >= 6) { Vehicle.SM_ALPHA = Float.parseFloat(args[1]); Vehicle.SM_BETA = Float.parseFloat(args[2]); Vehicle.SM_SINK = Float.parseFloat(args[3]); Vehicle.SM_FLOAT = Float.parseFloat(args[4]); Vehicle.ANGLE_FOLLOW = Float.parseFloat(args[5]); }
        if (args.length >= 7) NOISE = Double.parseDouble(args[6]);
        System.out.printf("smoothing alpha %.2f beta %.2f sink %.2f float %.2f angles %.2f, terrain noise %.1f%n", Vehicle.SM_ALPHA, Vehicle.SM_BETA, Vehicle.SM_SINK, Vehicle.SM_FLOAT, Vehicle.ANGLE_FOLLOW, NOISE);
        if (args.length >= 8 && args[7].equals("trace")) { dropOff(ty, vs, Math.abs(Integer.parseInt(args[8])), true, Integer.parseInt(args[8]) < 0); return; }
        for (int h : new int[]{1, 2, 4}) dropOff(ty, vs, h, false);
        dropOff(ty, vs, 1, false, true);
        slope(ty, vs, 0.25);
        slope(ty, vs, 0.5);
        for (double amp : new double[]{0, 2}) {
            World w = new World();
            w.groundY = -100;
            for (int x = -10; x < 10; x++) for (int z = -10; z < 400; z++) for (int y = 40; y <= height(x, z, amp); y++) w.set(x, y, z, 1);
            Vehicle v = new Vehicle(1, ty.name); vs.l.clear(); vs.l.add(v); v.vm();
            v.setPose(0.5, height(0, 5, amp) + 1, 5.5, 0);
            v.riders[v.vm().driverSeat] = 7;
            double prevY = v.y, prevDy = 0, maxDy = 0, maxJerk = 0, sumJerk = 0, maxBody = 0, prevBody = 0, prevBodyD = 0, maxBodyJerk = 0;
            float prevPitch = v.pitch, maxDPitch = 0, maxPitch = 0, maxRoll = 0;
            int n = 0;
            for (int t = 0; t < 260; t++) {
                v.throttle = 1; v.tick(w, false);
                double dy = v.y - prevY, jerk = dy - prevDy;
                double body = v.y + v.susHeave[0], bd = body - prevBody;
                if (t > 20) {
                    maxDy = Math.max(maxDy, Math.abs(dy)); maxJerk = Math.max(maxJerk, Math.abs(jerk)); sumJerk += jerk * jerk; n++;
                    maxDPitch = Math.max(maxDPitch, Math.abs(v.pitch - prevPitch));
                    maxBodyJerk = Math.max(maxBodyJerk, Math.abs(bd - prevBodyD));
                    maxPitch = Math.max(maxPitch, Math.abs(v.pitch)); maxRoll = Math.max(maxRoll, Math.abs(v.roll));
                }
                prevDy = dy; prevY = v.y; prevPitch = v.pitch; prevBodyD = bd; prevBody = body;
            }
            System.out.printf("hills amp %.0f: reached z %.0f at %.0f km/h | body jump/tick max %.2f, jerk max %.2f rms %.3f | drawn body jerk max %.2f | pitch change/tick max %.1f deg (|pitch| max %.0f, roll %.0f) | stuck %d%n",
                    amp, v.z, v.kmh(), maxDy, maxJerk, Math.sqrt(sumJerk / Math.max(1, n)), maxBodyJerk, maxDPitch, maxPitch, maxRoll, v.stuck);
        }
    }
}
