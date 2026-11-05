import derpcraft.block.Blocks;
import derpcraft.game.Session;
import derpcraft.npc.Actor;
import derpcraft.npc.NpcManager;
import derpcraft.vehicle.Vehicle;
import derpcraft.vehicle.Vehicles;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * Cars in the real game: bullet holes that stay (on the part: they move with a door; saved), takes that start with the
 * cars as clean as when recorded, /vehicle clear on the car looked at, Alt + End from 40 blocks away, parts blown off
 * by the explosion, a chain of explosions, a car blowing up after standing in fire.
 * Each check prints "[smoke] CHECK <what> OK|FAIL ...".
 */
public class Scenario {
    static Session S() { return Session.current; }
    static NpcManager N() { return S().npcs; }
    static Vehicle car, c2, c3, c4;
    static Actor drv;
    static int fails, gy, holesAfterTake;
    static long t0;
    static double px, pz;

    static void check(String what, boolean ok, String info) {
        if (!ok) fails++;
        System.out.println("[smoke] CHECK " + what + " " + (ok ? "OK" : "FAIL") + " " + info);
    }

    static void makeCar(Path d) throws Exception {
        Files.createDirectories(d);
        StringBuilder sb = new StringBuilder("mtllib car2.mtl\n");
        int[] vi = {1};
        double ht = 0.8, r = 0.35;
        SmokeMain.cube(sb, vi, "body", "red", -ht - 0.1, 0.35, -2.1, ht + 0.1, 0.6, 2.1);
        SmokeMain.cube(sb, vi, "front", "red", -ht - 0.1, 0.6, -2.1, ht + 0.1, 0.95, -0.9);
        SmokeMain.cube(sb, vi, "back", "red", -ht - 0.1, 0.6, 0.9, ht + 0.1, 0.95, 2.1);
        SmokeMain.cube(sb, vi, "roof", "blue", -ht + 0.05, 1.55, -0.7, ht - 0.05, 1.6, 1.0);
        SmokeMain.cube(sb, vi, "door1", "red", -ht - 0.1, 0.6, -0.9, -ht + 0.0, 1.5, 0.9);
        SmokeMain.cube(sb, vi, "door2", "red", ht - 0.0, 0.6, -0.9, ht + 0.1, 1.5, 0.9);
        SmokeMain.cube(sb, vi, "hood", "red", -ht - 0.05, 0.95, -2.1, ht + 0.05, 1.0, -0.9);
        SmokeMain.cube(sb, vi, "seat1", "blue", -0.6, 0.6, -0.2, -0.1, 0.7, 0.3);
        SmokeMain.cube(sb, vi, "seat2", "blue", 0.1, 0.6, -0.2, 0.6, 0.7, 0.3);
        SmokeMain.cube(sb, vi, "wheel1", "black", -ht - 0.15, 0, -1.3 - r, -ht + 0.1, 2 * r, -1.3 + r);
        SmokeMain.cube(sb, vi, "wheel2", "black", ht - 0.1, 0, -1.3 - r, ht + 0.15, 2 * r, -1.3 + r);
        SmokeMain.cube(sb, vi, "wheel3", "black", -ht - 0.15, 0, 1.3 - r, -ht + 0.1, 2 * r, 1.3 + r);
        SmokeMain.cube(sb, vi, "wheel4", "black", ht - 0.1, 0, 1.3 - r, ht + 0.15, 2 * r, 1.3 + r);
        SmokeMain.cube(sb, vi, "steering_wheel", "black", -0.5, 0.95, -0.55, -0.2, 1.0, -0.45);
        Files.writeString(d.resolve("car2.obj"), sb.toString());
        Files.writeString(d.resolve("car2.mtl"), "newmtl red\nKd 0.8 0.1 0.1\nnewmtl blue\nKd 0.2 0.3 0.8\nnewmtl black\nKd 0.05 0.05 0.05\n");
    }

    static derpcraft.gun.GunType gun() {
        var g = derpcraft.gun.Guns.get("fp9");
        if (g == null) for (var x : derpcraft.gun.Guns.all()) { g = x; break; }
        return g;
    }

    /** A shot from the player's eye at a point of the car (world). */
    static void shootAt(double tx, double ty, double tz, boolean record) {
        var p = S().player;
        double ex = p.x, ey = p.y + p.eyeHeight, ez = p.z, dx = tx - ex, dy = ty - ey, dz = tz - ez, l = Math.sqrt(dx * dx + dy * dy + dz * dz);
        derpcraft.gun.Ballistics.fire(S(), gun(), null, ex, ey, ez, dx / l, dy / l, dz / l, ex, ey, ez, true, true);
        if (record) N().recordItem(3, gun().name, 0, dx / l, dy / l, dz / l);
    }

    static void look(Vehicle v, double fromDist) {
        var p = S().player;
        p.setPos(v.x + fromDist, v.y, v.z + 0.5);
        double dx = v.x - p.x, dy = v.y + 0.8 - (p.y + p.eyeHeight), dz = v.z - p.z;
        p.yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        p.pitch = (float) -Math.toDegrees(Math.atan2(dy, Math.hypot(dx, dz)));
    }

    static int door(Vehicle v, String name) {
        var m = v.vm();
        for (int i = 0; i < m.parts.size(); i++) if (m.parts.get(i).name.equalsIgnoreCase(name)) return i;
        return -1;
    }

    static void build() {
        SmokeMain.at(60, () -> {
            Session s = S();
            s.game.settings.chatMessages = false;
            s.gameMode = "creative";
            try { makeCar(s.game.gameDir.resolve("transport/car2")); } catch (Exception e) { e.printStackTrace(); }
            derpcraft.vehicle.Transports.rescan();
            px = Math.floor(s.player.x) + 0.5; pz = Math.floor(s.player.z) + 0.5;
            gy = s.world.topSolidY((int) Math.floor(px), (int) Math.floor(pz));
            car = s.vehicles.spawn("car2", px, gy + 1, pz + 6, 0);
            s.view = 0;
        });
        // ---- bullet holes: two shots into the right door (from the right side), they stay and move with the door
        SmokeMain.wait(10, () -> {
            look(car, 4);
            shootAt(car.x + 0.85, car.y + 1.0, car.z - 0.3, false);
            shootAt(car.x + 0.85, car.y + 1.2, car.z + 0.4, false);
            int d2 = door(car, "door2");
            long onDoor = car.holes.stream().filter(h -> (int) h[0] == d2).count();
            check("bullet holes on the door", onDoor == 2, onDoor + " on door2, " + car.holes.size() + " in all");
        });
        SmokeMain.wait(4, () -> { look(car, 2.6); SmokeMain.shot("f01_holes"); });
        SmokeMain.wait(2, () -> { int i = car.vm().openables.indexOf(car.vm().parts.get(door(car, "door2"))); car.openTarget[i] = true; });
        SmokeMain.wait(20, () -> SmokeMain.shot("f02_holes_on_open_door"));
        SmokeMain.wait(2, () -> {
            for (int i = 0; i < car.openTarget.length; i++) car.openTarget[i] = false;
            var back = Vehicles.holesFrom(Vehicles.holesText(car.holes));
            check("holes saved and read back", back.size() == car.holes.size(), back.size() + " / " + car.holes.size());
        });
        // ---- a take: the car clean when it began, shot in it; the replay starts clean and the replayed shot makes the hole again
        SmokeMain.wait(20, () -> {
            car.holes.clear();
            drv = N().spawn("Shooter", px, gy + 1, pz, 0);
            N().countdownSeconds = 0; N().walkToMarks = false;
            look(car, 4);
            N().startRecording(drv);
        });
        SmokeMain.wait(10, () -> shootAt(car.x + 0.85, car.y + 1.0, car.z, true));
        SmokeMain.wait(10, () -> {
            S().stopRecordingKey();
            holesAfterTake = car.holes.size();
            check("holes from the take stay", holesAfterTake > 0, holesAfterTake + " holes");
            check("take remembers the car clean", drv.take(drv.group) != null && drv.take(drv.group).vehicleHoles.getOrDefault(car.id, -1) == 0, "" + drv.take(drv.group).vehicleHoles);
            S().player.setPos(S().player.x, S().player.y, S().player.z - 3);         // not on the shooter's mark (it would take the bullet)
            N().playScene(drv.group);
        });
        SmokeMain.wait(3, () -> check("replay starts with the car clean", car.holes.isEmpty(), car.holes.size() + " holes"));
        SmokeMain.step(() -> !N().scenePlaying(drv.group), 200, () -> check("the replayed shot makes the hole again", !car.holes.isEmpty(), car.holes.size() + " holes"));
        SmokeMain.wait(2, () -> { N().resetScene(drv.group); check("To start: the car as before the take (clean)", car.holes.isEmpty(), car.holes.size() + " holes"); });
        // ---- /vehicle clear on the car looked at
        SmokeMain.wait(5, () -> { look(car, 12); S().runCommand("vehicle clear"); check("/vehicle clear (looking at it)", car.holes.isEmpty(), car.holes.size() + " holes"); });
        // ---- Alt + End from 40 blocks: the explosion, parts blown off
        SmokeMain.wait(5, () -> {
            look(car, 40);
            check("Alt + End from 40 blocks", S().vehicles.explodeKey() && car.wrecked, "");
            int off = 0;
            for (int i = 0; i < car.vm().parts.size(); i++) if (car.detached(i)) off++;
            System.out.println("[smoke] parts blown off: " + off);
            S().player.setPos(car.x + 9, car.y, car.z - 3); S().player.yaw = 70; S().player.pitch = 12;
        });
        SmokeMain.wait(4, () -> SmokeMain.shot("f03_explosion"));
        SmokeMain.wait(70, () -> {
            int off = 0, rest = 0;
            double far = 0;
            for (var d : car.debris) if (d != null) { off++; if (d.rest) rest++; far = Math.max(far, Math.hypot(d.ox, d.oz)); }
            check("blown off parts landed away from the car", off == 0 || rest == off && far > 1, off + " parts, " + rest + " at rest, up to " + String.format(Locale.ROOT, "%.1f", far) + " blocks");
            SmokeMain.shot("f04_after_explosion");
        });
        // ---- a chain: two cars 2 blocks apart, the first blows up, the second follows
        SmokeMain.wait(5, () -> {
            c2 = S().vehicles.spawn("car2", px + 12, gy + 1, pz + 6, 0);
            c3 = S().vehicles.spawn("car2", px + 14.5, gy + 1, pz + 6, 0);
            S().player.setPos(px + 13, gy + 1, pz - 8); S().player.yaw = 0; S().player.pitch = 10;
        });
        SmokeMain.wait(10, () -> S().vehicles.explode(c2, false));
        SmokeMain.wait(25, () -> { check("the car next to it blows up too", c3.wrecked, ""); SmokeMain.shot("f05_chain"); });
        // ---- fire under a car: it blows up 20-30 s later
        SmokeMain.wait(5, () -> {
            c4 = S().vehicles.spawn("car2", px - 12, gy + 1, pz + 6, 0);
            t0 = S().ticks;
        });
        SmokeMain.wait(5, () -> S().world.setBlock((int) Math.floor(c4.x), gy + 1, (int) Math.floor(c4.z), Blocks.byName("fire"), 0));
        SmokeMain.wait(200, () -> { check("not yet after 10 s", !c4.wrecked, ""); S().player.setPos(c4.x + 7, gy + 1, c4.z - 4); S().player.yaw = 60; S().player.pitch = 15; SmokeMain.shot("f06_fire_under"); });
        SmokeMain.step(() -> c4.wrecked, 700, () -> {
            long t = S().ticks - t0;
            check("standing in fire: blows up after 20-30 s", c4.wrecked && t >= 380 && t <= 640, (t / 20.0) + " s");
        });
        // ---- /vehicle clear on a wreck: like new
        SmokeMain.wait(5, () -> { look(car, 10); S().player.setPos(car.x + 10, car.y, car.z - 1.5); S().runCommand("vehicle clear"); check("/vehicle clear repairs the wreck", !car.wrecked && !car.detached(door(car, "door2")) && car.debris.length == 0, ""); });
        SmokeMain.wait(5, () -> { System.out.println("[smoke] fails: " + fails); System.out.println("[smoke] DONE"); System.exit(0); });
    }
}
