package derpcraft.vehicle;

import derpcraft.model.Model3D;
import derpcraft.model.Models;
import derpcraft.world.World;

import java.nio.file.*;
import java.util.*;

/**
 * Offline check of vehicle models (no game, no OpenGL): runs the game's real loader, VehicleModel analysis and
 * Vehicle physics on a stub flat world. Usage: sh tools/vehicle_check/check.sh path/to/car.bbmodel [...]
 */
public class VehicleCheck {
    public static void main(String[] args) throws Exception {
        Vehicles vs = new Vehicles(); Vehicles.current = vs;
        int bad = 0;
        for (String a : args) bad += check(Transports.add(Path.of(a)), vs);
        System.out.println(bad == 0 ? "\nALL OK" : "\nPROBLEMS: " + bad);
    }

    static int check(VehicleType ty, Vehicles vs) {
        String name = ty.name;
        System.out.println("=== " + name + " (" + ty.dir + ")");
        VehicleModel v0 = ty.vm();
        Model3D m = ty.model();
        List<String> warn = new ArrayList<>(ty.warnings);
        if (v0 == null || m == null) { System.out.println("  LOAD ERROR: " + ty.error); return 1; }
        System.out.printf("  model %s, settings %s%n", ty.modelFile.getFileName(), ty.configFile == null ? "none (defaults)" : ty.configFile.getFileName());
        System.out.printf("  %s: %.0f km/h, reverse %.0f, acceleration %.0f km/h/s, braking %.0f, steering %.0f deg, step %.2f, scale %.3f, turn %.0f%n",
                ty.displayName, VehicleType.toKmh(ty.maxSpeed), VehicleType.toKmh(ty.reverseSpeed), ty.accel * 72 * 20, ty.braking * 72 * 20, ty.steerAngle, ty.stepHeight, ty.scale, ty.turn);
        System.out.println("  sounds: " + (ty.sounds.isEmpty() ? "none" : String.join(", ", ty.sounds.keySet().stream().sorted().toList())));
        int texGroups = 0, plain = 0;
        for (Model3D.Group g : m.groups) { if (g.image != null) texGroups++; else plain++; }
        System.out.printf("  textures: %d textured groups, %d colour-only groups%n", texGroups, plain);
        System.out.printf("  %d triangles, %d groups: %s%n", m.triangles(), m.bones.size() - 1, String.join(", ", m.bones.stream().skip(1).map(b -> b.name).toList()));
        VehicleModel v = v0;
        if (!VehicleModel.isVehicle(m)) { System.out.println("  NOT A VEHICLE: needs at least 2 groups named wheel*"); return 1; }
        System.out.printf("  size %.2f x %.2f x %.2f blocks (w x h x l), wheelbase %.2f, track %.2f, wheel radius %.2f%n",
                v.maxX - v.minX, v.maxY - v.minY, v.maxZ - v.minZ, v.wheelBase, v.halfTrack * 2, v.wheelRadius);
        for (VehicleModel.Part p : v.parts)
            System.out.printf("  part %-16s %5d tris  x %5.2f..%5.2f  y %5.2f..%5.2f  z %5.2f..%5.2f%n", p.name, p.triangles(), p.x0, p.x1, p.y0, p.y1, p.z0, p.z1);
        int front = 0;
        for (VehicleModel.Part p : v.wheels) if (p.front) front++;
        System.out.printf("  wheels %d (front, steering: %d)%n", v.wheels.size(), front);
        if (front == 0) warn.add("no front wheels found (front = -Z)");
        for (VehicleModel.Part p : v.openables)
            System.out.printf("  opens: %-14s %s, %+.0f deg around %s at pivot %.2f %.2f %.2f%n", p.name, p.axis == 1 ? "door (swings out)" : "lid (lifts)", p.openAngle, p.axis == 1 ? "Y" : "X", p.px, p.py, p.pz);
        if (v.steering == null) warn.add("no steering_wheel group (driver seat = seat with the lowest z)");
        else System.out.printf("  steering wheel: %d triangles turn, column axis %.2f %.2f %.2f%n", v.steering.triangles(), v.steering.ax, v.steering.ay, v.steering.az);
        for (int i = 0; i < v.seats.size(); i++) {
            float[] s = v.seats.get(i);
            String door = v.seatDoor[i] < 0 ? "none (gets in freely)" : v.openables.get(v.seatDoor[i]).name;
            System.out.printf("  seat %d at %.2f %.2f %.2f%s, door %s%n", i + 1, s[0], s[1], s[2], i == v.driverSeat ? " DRIVER" : "", door);
            if (s[1] > v.maxY * 0.6f) warn.add("seat " + (i + 1) + " surface is high (" + s[1] + "): the cushion top face is found by the largest upward face");
        }
        if (v.seats.isEmpty()) warn.add("no seat groups");
        for (int i = 0; i < v.slots.size(); i++) {
            float[] s = v.slots.get(i);
            System.out.printf("  block place %s: block from y %.2f to %.2f at %.2f %.2f%n", v.slotNames.get(i), s[1], s[1] + 1, s[0], s[2]);
            for (VehicleModel.Part p : v.openables)
                if (p.axis == 0 && s[0] + 0.5 > p.x0 && s[0] - 0.5 < p.x1 && s[2] + 0.5 > p.z0 && s[2] - 0.5 < p.z1 && s[1] < p.y1 && s[1] + 1 > p.y1)
                    warn.add("block place " + v.slotNames.get(i) + " sticks out of the closed " + p.name + " by " + String.format(Locale.ROOT, "%.2f", s[1] + 1 - p.y1) + " blocks");
        }
        // ray test boxes: every triangle inside its chunk's box
        int outside = 0, total = 0;
        for (VehicleModel.Part p : v.parts)
            for (int gi = 0; gi < p.groups.size(); gi++) {
                Model3D.Group gr = p.groups.get(gi);
                float[] b = p.chunks[gi];
                for (int t = 0; t < gr.tris; t++, total++) {
                    int c = t / VehicleModel.CHUNK;
                    for (int k = 0; k < 3; k++) {
                        int o = t * 15 + k * 5;
                        if (gr.v[o] < b[c * 6] || gr.v[o + 1] < b[c * 6 + 1] || gr.v[o + 2] < b[c * 6 + 2] || gr.v[o] > b[c * 6 + 3] || gr.v[o + 1] > b[c * 6 + 4] || gr.v[o + 2] > b[c * 6 + 5]) { outside++; break; }
                    }
                }
            }
        System.out.printf("  ray test boxes: %d triangles in %d-triangle boxes, %d outside%n", total, VehicleModel.CHUNK, outside);
        if (outside > 0) warn.add("does not keep triangles inside their ray test boxes");
        // drive on flat ground: 2 s gas (yaw 0 -> +z), then 1 s left
        World w = new World();
        Vehicle c = new Vehicle(1, name);
        vs.l.clear(); vs.l.add(c);
        c.vm();
        c.setPose(0.5, 64, 0.5, 0);
        c.riders[v.driverSeat] = 7;
        for (int t = 0; t < 40; t++) { c.throttle = 1; c.tick(w, false); c.throttle = 0; }
        double z1 = c.z;
        float kmh = c.kmh();
        for (int t = 0; t < 20; t++) { c.throttle = 1; c.steerIn = 1; c.tick(w, false); c.throttle = 0; c.steerIn = 0; }
        float maxP = 0, maxR = 0;
        for (int t = 0; t < 30; t++) { c.throttle = -1; c.steerIn = 1; c.tick(w, false); c.throttle = 0; c.steerIn = 0; maxP = Math.max(maxP, Math.abs(c.susPitch[0])); maxR = Math.max(maxR, Math.abs(c.susRoll[0])); }
        for (int t = 0; t < 60; t++) { c.handbrake = true; c.tick(w, false); c.handbrake = false; }
        System.out.printf("  suspension: braking/turning up to pitch %.1f, roll %.1f deg; at rest after 3 s: %.2f %.2f %.3f%n", maxP, maxR, c.susPitch[0], c.susRoll[0], c.susHeave[0]);
        if (Math.abs(c.susPitch[0]) > 0.2 || Math.abs(c.susRoll[0]) > 0.2) warn.add("does not settle on its springs");
        System.out.printf("  drive test: 2 s gas -> %.1f blocks forward (%.0f km/h), then left -> yaw %.0f, y %.2f, pitch %.1f%n", z1 - 0.5, kmh, c.yaw, c.y, c.pitch);
        if (z1 < 5) warn.add("does not drive forward (stuck? body inside the ground?)");
        if (c.yaw > -5) warn.add("does not turn left");
        if (Math.abs(c.y - 64) > 0.05 || Math.abs(c.pitch) > 1) warn.add("does not sit flat on flat ground (wheel bottoms not at the lowest point?)");
        for (String s : warn) System.out.println("  WARNING: " + s);
        return (int) warn.stream().filter(s -> s.startsWith("does not") || s.startsWith("no front")).count();
    }
}
