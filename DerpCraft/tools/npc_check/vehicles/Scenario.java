import derpcraft.game.Keys;
import derpcraft.game.Session;
import derpcraft.npc.Actor;
import derpcraft.npc.NpcManager;
import derpcraft.vehicle.Vehicle;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * NPCs and vehicles in the real game: a following NPC gets into the player's car and out only after the player walked
 * its follow distance away; Tab = wait / follow, Tab + 1 = patrol here; a second without damage after getting out;
 * bullets through car parts (the Bullets page setting); a driver killed in a scene leaves the car to roll out and stop.
 * Each check prints "[smoke] CHECK <what> OK|FAIL ...".
 */
public class Scenario {
    static Session S() { return Session.current; }
    static NpcManager N() { return S().npcs; }
    static Vehicle car;
    static Actor ann, drv;
    static int fails;
    static double[] mark = new double[3];

    static void check(String what, boolean ok, String info) {
        if (!ok) fails++;
        System.out.println("[smoke] CHECK " + what + " " + (ok ? "OK" : "FAIL") + " " + info);
    }

    static void log(String what) {
        System.out.printf(Locale.ROOT, "[smoke] %s | ann %.1f %.1f %.1f seat %d hp %.1f wait %b | car %.1f %.1f speed %.3f | player %.1f %.1f hp %.1f%n", what,
                ann.x(), ann.y(), ann.z(), car.seatOf(ann.id), ann.health, ann.waiting, car.x, car.z, car.speed, S().player.x, S().player.z, S().stats.health);
    }

    /** A test car with two doors and two seats (left = the driver), the right door is a separate part. */
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

    /** Opens / closes a door at once (by its part name). */
    static void door(String name, boolean open) {
        var m = car.vm();
        for (int i = 0; i < m.openables.size(); i++) if (m.openables.get(i).name.equalsIgnoreCase(name)) {
            car.openTarget[i] = open; car.open[i] = car.prevOpen[i] = open ? 1 : 0;
        }
    }

    /** The player gets into the driver's seat (its door opened first, as with a click on it). */
    static void playerIn() { door("door1", true); S().vehicles.enterPlayer(car, null); door("door1", false); }

    /** A bullet from 3 blocks right of Ann (outside the car's right door) at her chest. */
    static void shootAnn() {
        var gun = derpcraft.gun.Guns.get("fp9");
        if (gun == null) for (var g : derpcraft.gun.Guns.all()) { gun = g; break; }
        double ax = ann.x(), ay = ann.y() + 0.5, az = ann.z();
        double sx = ax - car.x, sz = az - car.z, l = Math.hypot(sx, sz);
        double ex = ax + sx / l * 3, ez = az + sz / l * 3;
        double dx = ax - ex, dy = 0, dz = az - ez, dl = Math.sqrt(dx * dx + dy * dy + dz * dz);
        var r = derpcraft.gun.Ballistics.fire(S(), gun, null, ex, ay, ez, dx / dl, dy / dl, dz / dl, ex, ay, ez, true, false);
        System.out.printf(Locale.ROOT, "[smoke] bullet: dist %.2f body %b%n", r.dist(), r.body());
    }

    static void build() {
        SmokeMain.at(60, () -> {
            Session s = S();
            s.game.settings.chatMessages = false;
            try { makeCar(s.game.gameDir.resolve("transport/car2")); } catch (Exception e) { e.printStackTrace(); }
            derpcraft.vehicle.Transports.rescan();
            s.player.yaw = 0; s.player.pitch = 15;
            double px = s.player.x, pz = s.player.z;
            double y = s.world.topSolidY((int) Math.floor(px), (int) Math.floor(pz + 6)) + 1;
            car = s.vehicles.spawn("car2", px, y, pz + 6, 0);
            ann = N().spawn("Ann", px - 3, y, pz, 0);
            ann.behavior = Actor.Behavior.FOLLOW;
            ann.skin = "Doctor";
            ann.followStart = 6; ann.followStop = 2.5f;
            s.view = 1;
            s.stats.health = 20;
            System.out.println("[smoke] car seats " + car.vm().seats.size() + ", driver " + car.vm().driverSeat + ", doors " + car.vm().openables.size()
                    + ", parts " + car.vm().parts.size());
        });
        // ---- the player gets in: Ann comes, opens the door and gets in
        SmokeMain.wait(5, () -> { playerIn(); check("player in the car", S().vehicles.playerVehicle == car, ""); log("player in"); });
        SmokeMain.wait(12, () -> { log("ann walking"); SmokeMain.shot("v01_ann_walks_to_car"); });
        SmokeMain.step(() -> car.seatOf(ann.id) >= 0, 300, () -> {
            check("follower got in", car.seatOf(ann.id) >= 0, "seat " + car.seatOf(ann.id));
            log("ann in");
        });
        SmokeMain.wait(10, () -> SmokeMain.shot("v02_ann_in_car"));
        // ---- the player gets out: a second without damage; Ann stays in while the player is near
        SmokeMain.wait(5, () -> {
            S().vehicles.exitPlayer();
            float hp = S().stats.health;
            S().hurtPlayer(5, "hit", S().player.x + 1, S().player.z);
            check("player: no damage right after getting out", S().stats.health == hp, "hp " + S().stats.health);
        });
        SmokeMain.wait(15, () -> { check("follower stays in while the player is near", car.seatOf(ann.id) >= 0, ""); log("player near"); });
        SmokeMain.wait(25, () -> {
            float hp = S().stats.health;
            S().stats.invulnerable = 0;
            S().hurtPlayer(5, "hit", S().player.x + 1, S().player.z);
            check("player: damage again after a second", S().stats.health < hp, "hp " + S().stats.health);
            S().stats.health = 20;
            S().player.setPos(car.x - 12, S().player.y, car.z);       // walks away beyond the follow distance
        });
        SmokeMain.step(() -> car.seatOf(ann.id) < 0, 120, () -> {
            check("follower got out after the player walked away", car.seatOf(ann.id) < 0, "");
            float hp = ann.health;
            ann.hurt(N(), 5, null, false);
            check("NPC: no damage right after getting out", ann.health == hp, "hp " + ann.health);
            log("ann out");
        });
        SmokeMain.wait(8, () -> SmokeMain.shot("v03_ann_got_out"));
        SmokeMain.step(() -> Math.hypot(ann.x() - S().player.x, ann.z() - S().player.z) < 4, 300, () -> {
            check("follower comes after the player", Math.hypot(ann.x() - S().player.x, ann.z() - S().player.z) < 4, "");
            ann.health = ann.maxHealth;
        });
        // ---- Tab: wait here; Tab again: follow
        SmokeMain.wait(5, () -> { S().targetActor = ann; S().npcTabPressed(); });
        SmokeMain.wait(2, () -> {
            check("Tab: waits", ann.waiting, "");
            mark[0] = ann.x(); mark[2] = ann.z();
            S().player.setPos(S().player.x + 15, S().player.y, S().player.z);
        });
        SmokeMain.wait(80, () -> {
            double moved = Math.hypot(ann.x() - mark[0], ann.z() - mark[2]);
            check("waiting NPC stays", moved < 1.6, String.format(Locale.ROOT, "moved %.2f", moved));
            S().targetActor = ann; S().npcTabPressed();
        });
        SmokeMain.wait(2, () -> check("Tab again: follows", !ann.waiting, ""));
        SmokeMain.step(() -> Math.hypot(ann.x() - S().player.x, ann.z() - S().player.z) < 4, 300, () -> check("follows again", true, ""));
        // ---- Tab + 1: patrols a small area; Tab: follows again
        SmokeMain.wait(2, () -> { ann.place(car.x - 14, ann.y(), car.z, 0); S().player.setPos(car.x - 16, S().player.y, car.z); });
        SmokeMain.wait(5, () -> {
            S().targetActor = ann; S().npcTabPressed();
            SmokeMain.hold(Keys.GESTURE, true);
            S().npcTabNumber(1);
            mark[0] = ann.x(); mark[2] = ann.z();
            check("Tab + 1: patrol", ann.behavior == Actor.Behavior.PATROL && ann.tabPatrol && ann.waypoints.size() >= 3, ann.waypoints.size() + " points");
        });
        SmokeMain.wait(3, () -> { SmokeMain.hold(Keys.GESTURE, false); check("Tab + 1 does not also toggle waiting", !ann.waiting && ann.tabPatrol, ""); S().player.setPos(S().player.x + 10, S().player.y, S().player.z); });
        SmokeMain.wait(30, () -> log("patrol 30"));
        SmokeMain.wait(30, () -> log("patrol 60 wp " + java.util.Arrays.deepToString(ann.waypoints.toArray())));
        SmokeMain.wait(40, () -> {
            log("patrol 100");
            double far = 0;
            for (double[] w : ann.waypoints) far = Math.max(far, Math.hypot(w[0] - mark[0], w[2] - mark[2]));
            check("patrols near its place", Math.hypot(ann.x() - mark[0], ann.z() - mark[2]) > 0.5 && far < 7, String.format(Locale.ROOT, "points within %.1f", far));
            SmokeMain.shot("v04_tab1_patrol");
            S().targetActor = ann; S().npcTabPressed();
        });
        SmokeMain.wait(2, () -> check("Tab: back to following", ann.behavior == Actor.Behavior.FOLLOW && !ann.tabPatrol, ann.behavior.name()));
        // ---- bullets through the car: Ann in the right seat, shot through the right door
        SmokeMain.wait(5, () -> { S().player.setPos(car.x - 2, car.y, car.z); playerIn(); });
        SmokeMain.step(() -> car.seatOf(ann.id) >= 0, 300, () -> {
            check("follower got in again", car.seatOf(ann.id) >= 0, "");
            S().vehicles.exitPlayer();            // the shooter's own car does not count: shoot from outside
            door("door2", false);
            float hp = ann.health;
            shootAnn();
            check("bullet goes through a door (passes)", ann.health < hp, "hp " + hp + " -> " + ann.health);
            ann.calm(); ann.health = ann.maxHealth;
            car.type().bullets.put("door2", true);
        });
        SmokeMain.wait(3, () -> SmokeMain.shot("v05_shot_through_door"));
        SmokeMain.wait(25, () -> {
            float hp = ann.health;
            shootAnn();
            check("bullet stops in a door set to 'stops'", car.seatOf(ann.id) >= 0 && ann.health == hp, "hp " + ann.health);
            car.type().bullets.clear();
            ann.calm(); ann.health = ann.maxHealth;
        });
        // ---- a driver killed in a scene: the car rolls out and stops
        SmokeMain.wait(5, () -> {
            ann.behavior = Actor.Behavior.STAND;               // out of the car, not following
            S().vehicles.exitPlayer();
            drv = N().spawn("Drv", car.x - 3, car.y, car.z, 0);
            N().countdownSeconds = 0;
            N().walkToMarks = false;
        });
        SmokeMain.wait(30, () -> {
            check("follower left the car (now STAND)", car.seatOf(ann.id) < 0, "");
            S().player.setPos(car.x - 2, car.y, car.z);
            playerIn();
            N().startRecording(drv);
            SmokeMain.hold(Keys.FORWARD, true);
        });
        SmokeMain.wait(70, () -> {
            SmokeMain.hold(Keys.FORWARD, false);
            S().stopRecordingKey();
            var t = drv.take(drv.group);
            check("driving take", t != null && t.frame(10).vehicle == car.id, t == null ? "none" : t.length() + " frames");
            S().vehicles.exitPlayer();
            S().player.setPos(S().player.x - 6, S().player.y, S().player.z);
        });
        SmokeMain.wait(20, () -> check("scene plays", N().playScene(drv.group), ""));
        SmokeMain.step(() -> Math.abs(car.speed) > 0.25f, 200, () -> {
            System.out.printf(Locale.ROOT, "[smoke] before the kill: car speed %.3f%n", car.speed);
            drv.hurt(N(), 1000, null, true);
            check("driver dies in the take", drv.dead(), "");
            check("car without a driver", car.driverKilled, "");
            mark[0] = car.x; mark[2] = car.z;
        });
        SmokeMain.wait(4, () -> SmokeMain.shot("v06_driver_killed"));
        SmokeMain.wait(80, () -> {
            double rolled = Math.hypot(car.x - mark[0], car.z - mark[2]);
            check("car rolled on and stopped", Math.abs(car.speed) < 0.01 && rolled > 0.5, String.format(Locale.ROOT, "rolled %.1f, speed %.3f", rolled, car.speed));
            SmokeMain.shot("v07_car_stopped");
            N().stopScene();
        });
        // ---- the vehicle editor's Bullets page (door2 set to stop, picked)
        SmokeMain.wait(5, () -> {
            car.type().bullets.put("door2", true);
            S().player.setPos(car.x - 4, car.y, car.z + 1); S().player.yaw = -100; S().player.pitch = 20; S().view = 0;
            S().game.setScreen(new derpcraft.gui.VehicleEditor(S().game, S()));
            try { SmokeMain.screen("Bullets", 11); } catch (Exception e) { e.printStackTrace(); }
        });
        SmokeMain.wait(6, () -> SmokeMain.shot("v08_editor_bullets"));
        SmokeMain.wait(5, () -> { System.out.println("[smoke] fails: " + fails); System.out.println("[smoke] DONE"); System.exit(0); });
    }
}
