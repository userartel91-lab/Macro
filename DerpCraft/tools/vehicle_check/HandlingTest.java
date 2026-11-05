package derpcraft.vehicle;

import derpcraft.world.World;

import java.nio.file.*;
import java.util.*;

/**
 * Drift, police turn, rolling over and pushing with the game's own physics on a flat stub world (no game, no OpenGL).
 * Builds a plain box car (4 wheels, body) in a temp folder, so no model is needed.
 * Usage: sh tools/vehicle_check/handling.sh
 */
public class HandlingTest {
    static void cube(StringBuilder sb, int[] vi, String name, double x0, double y0, double z0, double x1, double y1, double z1) {
        sb.append("o ").append(name).append('\n');
        double[][] v = {{x0, y0, z0}, {x1, y0, z0}, {x1, y1, z0}, {x0, y1, z0}, {x0, y0, z1}, {x1, y0, z1}, {x1, y1, z1}, {x0, y1, z1}};
        for (double[] p : v) sb.append(String.format(Locale.ROOT, "v %.3f %.3f %.3f%n", p[0], p[1], p[2]));
        int b = vi[0];
        int[][] f = {{0, 3, 2, 1}, {4, 5, 6, 7}, {0, 1, 5, 4}, {3, 7, 6, 2}, {0, 4, 7, 3}, {1, 2, 6, 5}};
        for (int[] q : f) sb.append(String.format("f %d %d %d %d%n", b + q[0], b + q[1], b + q[2], b + q[3]));
        vi[0] += 8;
    }

    static Path makeCar(String name, double halfTrack, double height) throws Exception {
        Path dir = Files.createTempDirectory("dc_car").resolve(name);
        Files.createDirectories(dir);
        StringBuilder sb = new StringBuilder();
        int[] vi = {1};
        cube(sb, vi, "body", -halfTrack - 0.1, 0.35, -2.1, halfTrack + 0.1, height, 2.1);
        double r = 0.35;
        cube(sb, vi, "wheel1", -halfTrack - 0.15, 0, -1.3 - r, -halfTrack + 0.1, 2 * r, -1.3 + r);
        cube(sb, vi, "wheel2", halfTrack - 0.1, 0, -1.3 - r, halfTrack + 0.15, 2 * r, -1.3 + r);
        cube(sb, vi, "wheel3", -halfTrack - 0.15, 0, 1.3 - r, -halfTrack + 0.1, 2 * r, 1.3 + r);
        cube(sb, vi, "wheel4", halfTrack - 0.1, 0, 1.3 - r, halfTrack + 0.15, 2 * r, 1.3 + r);
        Files.writeString(dir.resolve(name + ".obj"), sb.toString());
        return dir;
    }

    static Vehicle car(VehicleType ty, Vehicles vs, World w, double speed) {
        Vehicle v = new Vehicle(1, ty.name); vs.l.clear(); vs.l.add(v); v.vm();
        v.setPose(0.5, 64, 0.5, 0);
        v.riders[v.vm().driverSeat] = Vehicle.PLAYER;
        v.speed = (float) speed;
        return v;
    }

    static World flat() { World w = new World(); w.groundY = 63; return w; }

    static String line(int t, Vehicle v) {
        return String.format(Locale.ROOT, "  t=%2d yaw %7.1f speed %5.1f km/h lat %5.1f km/h drift %.2f slip %.2f flip %6.1f%s", t, v.yaw, v.speed * 72, v.lat * 72, v.drift, v.slip, v.flip, v.police > 0 ? " POLICE" : "");
    }

    /** Runs 'ticks' ticks with the given controls; prints every 'every'-th. */
    static void run(Vehicle v, World w, int ticks, float thr, float steer, boolean hb, int every) {
        for (int t = 0; t < ticks; t++) {
            v.throttle = thr; v.steerIn = steer; v.handbrake = hb;
            v.tick(w, false);
            if (every > 0 && t % every == 0) System.out.println(line(t, v));
        }
    }

    public static void main(String[] args) throws Exception {
        Path dir = makeCar("testcar", 0.8, 1.4);
        VehicleType ty = Transports.add(dir);
        Vehicles vs = new Vehicles(); Vehicles.current = vs;
        World w = flat();
        int fails = 0;
        System.out.printf("car: %.2f x %.2f x %.2f, wheelbase %.2f, track %.2f, stability %.2f, rollover limit %.4f%n",
                ty.vm().maxX - ty.vm().minX, ty.vm().maxY, ty.vm().maxZ - ty.vm().minZ, ty.vm().wheelBase, ty.vm().halfTrack * 2, Vehicle.stability(ty.vm(), ty), ty.rollover * Vehicle.stability(ty.vm(), ty));

        System.out.println("\n1. normal driving: 3 s of full steering at 50 km/h, then at top speed with a moderate turn (no drift, no flip, no sideways speed)");
        Vehicle v = car(ty, vs, w, 0.7);
        run(v, w, 60, 0.3f, 1, false, 20);
        boolean ok = v.flipTicks == 0 && v.flip == 0 && Math.abs(v.lat) < 0.01f && v.drift == 0;
        System.out.println(ok ? "  OK" : "  FAIL");
        fails += ok ? 0 : 1;

        System.out.println("\n2. drift: 45 km/h, Space + steering left for 1.5 s, then Space released");
        v = car(ty, vs, w, 0.62);
        float yaw0 = v.yaw, maxLat = 0, maxSlip = 0;
        for (int t = 0; t < 30; t++) { v.throttle = 1; v.steerIn = 1; v.handbrake = true; v.tick(w, false); maxLat = Math.max(maxLat, Math.abs(v.lat)); maxSlip = Math.max(maxSlip, v.slip); if (t % 6 == 0) System.out.println(line(t, v)); }
        float turned = Math.abs(v.yaw - yaw0);
        System.out.printf("  turned %.0f deg, max sideways %.0f km/h, max slip %.2f, flipping %b%n", turned, maxLat * 72, maxSlip, v.flipTicks > 0);
        for (int t = 0; t < 40; t++) { v.throttle = 1; v.steerIn = 0; v.handbrake = false; v.tick(w, false); if (t % 8 == 0) System.out.println(line(30 + t, v)); }
        ok = maxLat > 0.15f && maxSlip > 0.5f && turned > 40 && v.flipTicks == 0 && Math.abs(v.lat) < 0.05f && v.flip == 0;
        System.out.println(ok ? "  OK (slides sideways, recovers, no flip)" : "  FAIL"); fails += ok ? 0 : 1;

        System.out.println("\n3. police turn: 75 km/h, Space + full steering (the gas is let go), then the gas");
        v = car(ty, vs, w, 1.04);
        yaw0 = v.yaw;
        for (int t = 0; t < 40; t++) { boolean hb = t < 2 || v.police > 0; v.throttle = t < 4 ? 1 : 0; v.steerIn = hb ? 1 : 0; v.handbrake = hb; v.tick(w, false); if (t % 4 == 0) System.out.println(line(t, v)); }
        float dy = Math.abs(Vehicle.wrap(v.yaw - yaw0));
        System.out.printf("  turned %.0f deg, speed now %.0f km/h, police %d, flip %b%n", dy, v.speed * 72, v.police, v.flipTicks > 0);
        ok = dy > 150 && dy < 200 && v.flipTicks == 0 && v.flip == 0;
        System.out.println(ok ? "  OK (about a half turn)" : "  FAIL"); fails += ok ? 0 : 1;
        for (int t = 0; t < 30; t++) { v.throttle = 1; v.steerIn = 0; v.handbrake = false; v.tick(w, false); }
        System.out.printf("  30 ticks of gas later: %.0f km/h forward (speed %+.2f)%n", v.kmh(), v.speed);

        System.out.println("\n4. rolling over: top speed, full steering held");
        v = car(ty, vs, w, 1.2);
        int flipAt = -1; float maxFlip = 0;
        for (int t = 0; t < 80; t++) {
            v.throttle = 1; v.steerIn = 1; v.handbrake = false; v.tick(w, false);
            if (flipAt < 0 && v.flipTicks > 0) flipAt = t;
            maxFlip = Math.max(maxFlip, Math.abs(v.flip));
            if (flipAt >= 0 && t % 5 == 0 && t < flipAt + 40) System.out.println(line(t, v) + String.format(" lift %.2f", v.lift(v.flip)));
            if (v.flipImpact > 0) { System.out.printf("  start of the roll at tick %d: impact %.0f km/h%n", t, v.flipImpact * 72); v.flipImpact = 0; }
            if (v.flipLanded > 0) { System.out.printf("  landed at tick %d: %.0f km/h%n", t, v.flipLanded * 72); v.flipLanded = 0; }
        }
        ok = flipAt >= 0 && maxFlip >= 359 && v.flip == 0 && v.flipTicks == 0;
        System.out.println(ok ? "  OK (rolled once over, back on the wheels)" : "  FAIL (flipAt " + flipAt + ", max " + maxFlip + ")"); fails += ok ? 0 : 1;

        System.out.println("\n5. rolling over takes longer for a wide low car, and never with rollover 0");
        for (double[] sh : new double[][]{{0.8, 1.4}, {1.1, 1.1}, {0.6, 1.9}}) {
            VehicleType t2 = Transports.add(makeCar("shape" + (int) (sh[0] * 10), sh[0], sh[1]));
            Vehicle v2 = car(t2, vs, w, 1.2);
            int at = -1;
            for (int t = 0; t < 120 && at < 0; t++) { v2.throttle = 1; v2.steerIn = 1; v2.tick(w, false); if (v2.flipTicks > 0) at = t; }
            System.out.printf("  track %.1f height %.1f: stability %.2f, flips after %s ticks%n", sh[0] * 2, sh[1], Vehicle.stability(t2.vm(), t2), at < 0 ? "never" : String.valueOf(at));
        }
        VehicleType safe = Transports.add(makeCar("safe", 0.8, 1.4)); safe.rollover = 0;
        Vehicle v3 = car(safe, vs, w, 1.2);
        for (int t = 0; t < 120; t++) { v3.throttle = 1; v3.steerIn = 1; v3.tick(w, false); }
        ok = v3.flip == 0 && v3.flipTicks == 0;
        System.out.println(ok ? "  OK (rollover 0 = never)" : "  FAIL"); fails += ok ? 0 : 1;

        System.out.println("\n6. weight: the same drift with 600 / 1200 / 3000 kg");
        for (float kg : new float[]{600, 1200, 3000}) {
            VehicleType t4 = Transports.add(makeCar("kg" + (int) kg, 0.8, 1.4)); t4.weight = kg;
            Vehicle v4 = car(t4, vs, w, 0.83);
            float ml = 0; for (int t = 0; t < 20; t++) { v4.throttle = 1; v4.steerIn = 1; v4.handbrake = true; v4.tick(w, false); ml = Math.max(ml, Math.abs(v4.lat)); }
            System.out.printf("  %5.0f kg: stability %.2f, max sideways speed %.0f km/h, flipped %b%n", kg, Vehicle.stability(t4.vm(), t4), ml * 72, v4.flipTicks > 0 || v4.flip != 0);
        }

        System.out.println("\n7. pushing: nobody inside, push speed 0.045 for 40 ticks");
        v = car(ty, vs, w, 0);
        v.riders[v.vm().driverSeat] = Vehicle.NONE;
        for (int t = 0; t < 40; t++) { v.push = 0.045f; v.tick(w, false); }
        System.out.printf("  moved %.1f blocks, speed %.1f km/h%n", v.z - 0.5, v.speed * 72);
        for (int t = 0; t < 30; t++) { v.push = 0; v.tick(w, false); }
        ok = v.z > 0.5 + 1.2 && v.speed == 0;
        System.out.println(ok ? "  OK (pushed ahead slowly, stops when released)" : "  FAIL");
        fails += ok ? 0 : 1;

        System.out.println("\n8. surfaces: the same steering at 50 km/h on road, sand and ice");
        double[] top = new double[3]; float[] latMax = new float[3];
        int[] ids = {1, 4, 3};
        for (int k = 0; k < 3; k++) {
            World sw = new World(); sw.groundY = 63;
            for (int xx = -30; xx < 30; xx++) for (int zz = -30; zz < 400; zz++) sw.set(xx, 63, zz, ids[k]);
            Vehicle sv = car(ty, vs, sw, 0.7f);
            for (int t = 0; t < 60; t++) { sv.throttle = 1; sv.steerIn = t > 20 && t < 40 ? 1 : 0; sv.tick(sw, false); latMax[k] = Math.max(latMax[k], Math.abs(sv.lat)); top[k] = Math.max(top[k], sv.kmh()); }
            System.out.printf("  %s: surface %d, top %.0f km/h, max sideways %.1f km/h, drift %.2f%n", new String[]{"road", "sand", "ice"}[k], sv.surface, top[k], latMax[k] * 72, sv.drift);
        }
        ok = latMax[2] > 0.1f && latMax[0] < 0.02f && top[1] < top[0] - 3;
        System.out.println(ok ? "  OK (ice slides sideways, road does not, sand is slower)" : "  FAIL"); fails += ok ? 0 : 1;

        System.out.println("\n9. a car runs into a parked one: the parked one is pushed, the first slows down");
        for (float kg : new float[]{1200, 600, 3000}) {
            World cw = flat();
            Vehicle hitter = car(ty, vs, cw, 0.8f);
            VehicleType ty2 = Transports.add(makeCar("parked" + (int) kg, 0.8, 1.4)); ty2.weight = kg;
            Vehicle parked = new Vehicle(2, ty2.name); vs.l.add(parked); parked.vm(); parked.setPose(0.5, 64, 12.5, 0);
            for (int t = 0; t < 60; t++) { hitter.throttle = 1; hitter.tick(cw, false); parked.tick(cw, false); }
            System.out.printf("  parked car %4.0f kg: pushed %.1f blocks, now %.0f km/h; the hitting car (1200 kg) at z %.1f, %.0f km/h%n", kg, parked.z - 12.5, parked.kmh(), hitter.z, hitter.kmh());
            ok = parked.z > 13 && hitter.z < parked.z - 3;
            System.out.println(ok ? "  OK" : "  FAIL"); fails += ok ? 0 : 1;
            vs.l.remove(parked);
        }

        System.out.println("\n10. blowing a car up while it rolls over: where it stays differs from time to time");
        java.util.Map<Integer, Integer> stops = new java.util.TreeMap<>();
        boolean upright = true;
        for (long seed = 1; seed <= 40; seed++) {
            World ww = flat();
            Vehicle rv = car(ty, vs, ww, 1.2f);
            for (int t = 0; t < 80 && rv.flipTicks == 0; t++) { rv.throttle = 1; rv.steerIn = 1; rv.tick(ww, false); }
            for (int t = 0; t < 8 + seed % 12; t++) { rv.tick(ww, false); }
            if (rv.flipTicks == 0) continue;
            rv.wreck(seed * 7919);
            for (int t = 0; t < 60; t++) rv.tick(ww, false);
            stops.merge(Math.round(Math.abs(rv.flip)), 1, Integer::sum);
            if (rv.flipTicks != 0 || !rv.wrecked) upright = false;
        }
        System.out.println("  final roll angle (degrees) -> how many of 40: " + stops);
        ok = upright && stops.size() >= 2;
        System.out.println(ok ? "  OK (stops on its side, roof or wheels, never the same)" : "  FAIL"); fails += ok ? 0 : 1;
        Vehicle wv = car(ty, vs, flat(), 0.5f);
        wv.wreck(5);
        World fw = flat();
        for (int t = 0; t < 120; t++) { wv.throttle = 1; wv.tick(fw, false); }
        ok = wv.wrecked && wv.charred > 0.9f && wv.dmgTurn.length > 0 && wv.partAnim(wv.vm(), wv.vm().wheels.get(0), 1) != null && wv.speed < 0.05f;
        System.out.println(ok ? "  OK (burnt black, parts bent, the gas does nothing: it rolls to a stop, speed " + wv.speed + ")" : "  FAIL speed " + wv.speed); fails += ok ? 0 : 1;
        wv.repair();
        ok = !wv.wrecked && wv.charred == 0 && wv.flip == 0;
        System.out.println(ok ? "  OK (repaired)" : "  FAIL"); fails += ok ? 0 : 1;

        System.out.println(fails == 0 ? "\nALL OK" : "\nFAILS: " + fails);
        System.exit(fails == 0 ? 0 : 1);
    }
}
