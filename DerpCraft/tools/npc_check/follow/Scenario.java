import derpcraft.game.Session;
import derpcraft.npc.Actor;
import derpcraft.npc.NpcManager;
import derpcraft.vehicle.Vehicle;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * A following NPC and cars, in the real game: it never sits on the driver's / the player's seat (also with seats made up
 * from a steering wheel in the middle, which lie on top of each other), it walks round the car to the free door instead
 * of pushing into the side, and after dying it waits where it stood up until the player comes back to it.
 * Each check prints "[smoke] CHECK <what> OK|FAIL ...".
 */
public class Scenario {
    static Session S() { return Session.current; }
    static NpcManager N() { return S().npcs; }
    static Vehicle car, mid;
    static Actor ann;
    static int fails, gy, maxStuck;
    static double px, pz;
    static double[] home = new double[3];

    static void check(String what, boolean ok, String info) {
        if (!ok) fails++;
        System.out.println("[smoke] CHECK " + what + " " + (ok ? "OK" : "FAIL") + " " + info);
    }

    /** A car with two doors and two seats (left = the driver), the right door separate. */
    static void makeCar(Path d) throws Exception {
        Files.createDirectories(d);
        StringBuilder sb = new StringBuilder("mtllib c.mtl\n");
        int[] vi = {1};
        double ht = 0.8, r = 0.35;
        SmokeMain.cube(sb, vi, "body", "red", -ht - 0.1, 0.35, -2.1, ht + 0.1, 0.6, 2.1);
        SmokeMain.cube(sb, vi, "front", "red", -ht - 0.1, 0.6, -2.1, ht + 0.1, 1.2, -0.9);
        SmokeMain.cube(sb, vi, "back", "red", -ht - 0.1, 0.6, 0.9, ht + 0.1, 1.2, 2.1);
        SmokeMain.cube(sb, vi, "roof", "blue", -ht + 0.05, 1.55, -0.7, ht - 0.05, 1.6, 1.0);
        SmokeMain.cube(sb, vi, "door1", "red", -ht - 0.1, 0.6, -0.9, -ht + 0.0, 1.5, 0.9);
        SmokeMain.cube(sb, vi, "door2", "red", ht - 0.0, 0.6, -0.9, ht + 0.1, 1.5, 0.9);
        SmokeMain.cube(sb, vi, "seat1", "blue", -0.6, 0.6, -0.2, -0.1, 0.7, 0.3);
        SmokeMain.cube(sb, vi, "seat2", "blue", 0.1, 0.6, -0.2, 0.6, 0.7, 0.3);
        for (int i = 0; i < 4; i++) SmokeMain.cube(sb, vi, "wheel" + (i + 1), "black", (i % 2 == 0 ? -ht - 0.15 : ht - 0.1), 0, (i < 2 ? -1.3 : 1.3) - r, (i % 2 == 0 ? -ht + 0.1 : ht + 0.15), 2 * r, (i < 2 ? -1.3 : 1.3) + r);
        SmokeMain.cube(sb, vi, "steering_wheel", "black", -0.5, 0.95, -0.55, -0.2, 1.0, -0.45);
        Files.writeString(d.resolve("c.obj"), sb.toString());
        Files.writeString(d.resolve("c.mtl"), "newmtl red\nKd 0.8 0.1 0.1\nnewmtl blue\nKd 0.2 0.3 0.8\nnewmtl black\nKd 0.05 0.05 0.05\n");
    }

    /** No seats and no doors in the model, the steering wheel in the middle: the made up seats lie on top of each other. */
    static void makeMidCar(Path d) throws Exception {
        Files.createDirectories(d);
        StringBuilder sb = new StringBuilder("mtllib c.mtl\n");
        int[] vi = {1};
        double ht = 0.8, r = 0.35;
        SmokeMain.cube(sb, vi, "body", "red", -ht - 0.1, 0.35, -2.1, ht + 0.1, 0.6, 2.1);
        for (int i = 0; i < 4; i++) SmokeMain.cube(sb, vi, "wheel" + (i + 1), "black", (i % 2 == 0 ? -ht - 0.15 : ht - 0.1), 0, (i < 2 ? -1.3 : 1.3) - r, (i % 2 == 0 ? -ht + 0.1 : ht + 0.15), 2 * r, (i < 2 ? -1.3 : 1.3) + r);
        SmokeMain.cube(sb, vi, "steering_wheel", "black", -0.1, 0.95, -0.55, 0.1, 1.0, -0.45);
        Files.writeString(d.resolve("c.obj"), sb.toString());
        Files.writeString(d.resolve("c.mtl"), "newmtl red\nKd 0.8 0.1 0.1\nnewmtl black\nKd 0.05 0.05 0.05\n");
    }

    /** Seat groups in the model: seat1 (the driver) and seat2 in the same place, seat3 behind. */
    static void makeDupCar(Path d) throws Exception {
        Files.createDirectories(d);
        StringBuilder sb = new StringBuilder("mtllib c.mtl\n");
        int[] vi = {1};
        double ht = 0.8, r = 0.35;
        SmokeMain.cube(sb, vi, "body", "red", -ht - 0.1, 0.35, -2.1, ht + 0.1, 0.6, 2.1);
        SmokeMain.cube(sb, vi, "seat1", "blue", -0.6, 0.6, -0.2, -0.1, 0.7, 0.3);
        SmokeMain.cube(sb, vi, "seat2", "blue", -0.6, 0.6, -0.2, -0.1, 0.7, 0.3);
        SmokeMain.cube(sb, vi, "seat3", "blue", -0.6, 0.6, 0.8, -0.1, 0.7, 1.3);
        for (int i = 0; i < 4; i++) SmokeMain.cube(sb, vi, "wheel" + (i + 1), "black", (i % 2 == 0 ? -ht - 0.15 : ht - 0.1), 0, (i < 2 ? -1.3 : 1.3) - r, (i % 2 == 0 ? -ht + 0.1 : ht + 0.15), 2 * r, (i < 2 ? -1.3 : 1.3) + r);
        SmokeMain.cube(sb, vi, "steering_wheel", "black", -0.5, 0.95, -0.55, -0.2, 1.0, -0.45);
        Files.writeString(d.resolve("c.obj"), sb.toString());
        Files.writeString(d.resolve("c.mtl"), "newmtl red\nKd 0.8 0.1 0.1\nnewmtl blue\nKd 0.2 0.3 0.8\nnewmtl black\nKd 0.05 0.05 0.05\n");
    }

    static void door(Vehicle v, String name, boolean open) {
        var m = v.vm();
        for (int i = 0; i < m.openables.size(); i++) if (m.openables.get(i).name.equalsIgnoreCase(name)) { v.openTarget[i] = open; v.open[i] = v.prevOpen[i] = open ? 1 : 0; }
    }

    static double seatGap(Vehicle v, int a, int b) {
        float[] p = v.vm().seats.get(a), q = v.vm().seats.get(b);
        return Math.hypot(p[0] - q[0], p[2] - q[2]);
    }

    static void build() {
        SmokeMain.at(60, () -> {
            Session s = S();
            s.game.settings.chatMessages = false;
            s.gameMode = "creative";
            try { makeCar(s.game.gameDir.resolve("transport/fcar")); makeMidCar(s.game.gameDir.resolve("transport/midcar")); makeDupCar(s.game.gameDir.resolve("transport/dupcar")); } catch (Exception e) { e.printStackTrace(); }
            derpcraft.vehicle.Transports.rescan();
            px = Math.floor(s.player.x) + 0.5; pz = Math.floor(s.player.z) + 0.5;
            gy = s.world.topSolidY((int) Math.floor(px), (int) Math.floor(pz));
            mid = s.vehicles.spawn("dupcar", px + 10, gy + 1, pz + 6, 0);
            s.view = 1;
            System.out.println("[smoke] midcar seats " + mid.vm().seats.size() + ", driver " + mid.vm().driverSeat + ", seat 0-1 gap " + String.format(Locale.ROOT, "%.2f", seatGap(mid, 0, 1)));
        });
        // ---- seats on top of each other: the follower does not take the one where the player (driver) sits
        SmokeMain.wait(5, () -> {
            S().player.setPos(mid.x - 2, mid.y, mid.z);
            S().vehicles.enterPlayer(mid, null);
            ann = N().spawn("Ann", mid.x - 4, mid.y, mid.z, 0);
            ann.behavior = Actor.Behavior.FOLLOW;
        });
        SmokeMain.step(() -> mid.seatOf(ann.id) >= 0, 300, () -> {
            int st = mid.seatOf(ann.id), drv = mid.vm().driverSeat;
            check("follower got into the car", st >= 0, "seat " + st);
            check("not on the driver's / player's seat", st >= 0 && st != drv && seatGap(mid, st, drv) > 0.5, st < 0 ? "-" : String.format(Locale.ROOT, "seat %d, %.2f from the driver's", st, seatGap(mid, st, drv)));
        });
        SmokeMain.wait(5, () -> SmokeMain.shot("w01_midcar_seats"));
        // ---- the free door is on the other side: round the car, not into it
        SmokeMain.wait(5, () -> {
            S().vehicles.exitPlayer();
            var vs = S().vehicles;
            vs.ejectSeat(mid, mid.seatOf(ann.id));
            ann.behavior = Actor.Behavior.STAND;
            car = vs.spawn("fcar", px - 6, gy + 1, pz + 6, 0);
        });
        SmokeMain.wait(10, () -> {
            door(car, "door1", true);
            S().player.setPos(car.x - 2, car.y, car.z);
            S().vehicles.enterPlayer(car, null);
            door(car, "door1", false);
            check("player in the driver's seat (left)", S().vehicles.playerVehicle == car, "");
            // Ann on the left (the player's side), close to the car: the free seat's door is on the right
            double a = Math.PI - Math.toRadians(car.yaw), c = Math.cos(a), sn = Math.sin(a), lx = car.vm().minX - 1.2, lz = 0.6;
            ann.place(car.x + lx * c + lz * sn, car.y, car.z - lx * sn + lz * c, 0);
            ann.behavior = Actor.Behavior.FOLLOW;
            maxStuck = 0;
        });
        SmokeMain.wait(15, () -> SmokeMain.shot("w02_walks_round"));
        SmokeMain.step(() -> { maxStuck = Math.max(maxStuck, ann.boardStuck); return car.seatOf(ann.id) >= 0; }, 400, () -> {
            int st = car.seatOf(ann.id);
            check("walked round to the free door and got in", st >= 0 && st != car.vm().driverSeat, "seat " + st + ", longest stand-still " + maxStuck + " ticks");
            check("did not push into the car's side", maxStuck < 40, maxStuck + " ticks");
        });
        SmokeMain.wait(5, () -> SmokeMain.shot("w03_in_from_the_far_side"));
        // ---- dies, stands up again: waits there until the player comes back to it
        SmokeMain.wait(5, () -> {
            S().vehicles.exitPlayer();
            S().vehicles.ejectSeat(car, car.seatOf(ann.id));
            ann.place(px, gy + 1, pz - 10, 0);
            ann.setHome();
            ann.respawnSeconds = 1;
            S().player.setPos(px + 20, gy + 1, pz - 10);
        });
        SmokeMain.wait(30, () -> { ann.hurt(N(), 1000, null, false, null, "hit"); check("follower dies", ann.dead(), ""); home[0] = ann.homeX; home[2] = ann.homeZ; });
        SmokeMain.wait(90, () -> {
            double d = Math.hypot(ann.x() - home[0], ann.z() - home[2]);
            check("stood up and waits where it stood up", !ann.dead() && ann.waitForReturn && d < 1.6, String.format(Locale.ROOT, "%.1f from its spot, waits %b", d, ann.waitForReturn));
            S().player.setPos(ann.x() + 2, ann.y(), ann.z());        // the player comes back to it
        });
        SmokeMain.wait(10, () -> {
            check("follows again when the player came back", !ann.waitForReturn, "");
            S().player.setPos(ann.x() + 14, ann.y(), ann.z());
        });
        SmokeMain.wait(120, () -> {
            double d = Math.hypot(ann.x() - S().player.x, ann.z() - S().player.z);
            check("comes after the player again", d < 5, String.format(Locale.ROOT, "%.1f from the player", d));
        });
        SmokeMain.wait(5, () -> { System.out.println("[smoke] fails: " + fails); System.out.println("[smoke] DONE"); System.exit(0); });
    }
}
