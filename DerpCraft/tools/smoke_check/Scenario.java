import derpcraft.game.Keys;
import derpcraft.game.Session;
import derpcraft.vehicle.Vehicle;

import java.util.Locale;

/** The script: what is done at which tick after the world opened. */
public class Scenario {
    static Session S() { return Session.current; }
    static Vehicle car() { return SmokeMain.car; }

    static void slider(int i, double v) {
        try {
            var f = Class.forName("derpcraft.gui.VehicleEditor").getDeclaredField("sliders"); f.setAccessible(true);
            var l = (java.util.List<?>) f.get(S().game.screen);
            ((derpcraft.gui.Widget.Slider) l.get(i)).setValue(v);
        } catch (Exception e) { e.printStackTrace(); }
    }

    static void drive(boolean on) { SmokeMain.hold(Keys.FORWARD, on); }

    static void build() {
        // ---- the car: spawn it ahead, third person shot, get in
        SmokeMain.at(60, () -> {
            Session s = S();
            s.game.settings.chatMessages = false;
            s.game.settings.fov = 80;
            double yr = Math.toRadians(s.player.yaw);
            double x = s.player.x - Math.sin(yr) * 6, z = s.player.z + Math.cos(yr) * 6;
            double y = s.world.topSolidY((int) Math.floor(x), (int) Math.floor(z)) + 1;
            SmokeMain.car = s.vehicles.spawn("testcar", x, y, z, s.player.yaw);
            s.view = 1;
            SmokeMain.log(s, "spawned");
        });
        SmokeMain.wait(10, () -> SmokeMain.shot("01_parked_third_person"));
        SmokeMain.wait(2, () -> { Session s = S(); s.vehicles.enterPlayer(car(), null); s.view = 0; SmokeMain.log(s, "entered"); });
        SmokeMain.wait(5, () -> SmokeMain.shot("02_cabin_first_person"));
        // ---- drift
        SmokeMain.wait(1, () -> { drive(true); S().view = 1; });
        SmokeMain.step(() -> car().kmh() > 44, 200, () -> { SmokeMain.log(S(), "gas"); SmokeMain.hold(Keys.LEFT, true); SmokeMain.hold(Keys.JUMP, true); });
        SmokeMain.wait(8, () -> { SmokeMain.log(S(), "drift"); SmokeMain.shot("03_drift_third_person"); });
        SmokeMain.wait(8, () -> { SmokeMain.log(S(), "drift"); SmokeMain.shot("04_drift_marks"); });
        SmokeMain.wait(6, () -> { SmokeMain.hold(Keys.LEFT, false); SmokeMain.hold(Keys.JUMP, false); SmokeMain.log(S(), "drift released"); });
        SmokeMain.wait(30, () -> { SmokeMain.log(S(), "after drift"); S().view = 1; SmokeMain.shot("05_after_drift_marks"); });
        // ---- the cinematic camera: Right Alt + F8
        SmokeMain.wait(2, () -> { try { SmokeMain.key(297, 0, 346); } catch (Exception e) { e.printStackTrace(); } });
        SmokeMain.wait(12, () -> { SmokeMain.log(S(), "cam " + S().vehicles.camera.name()); SmokeMain.shot("06_cinematic_auto_1"); });
        SmokeMain.wait(50, () -> SmokeMain.shot("07_cinematic_auto_2"));
        for (int i = 0; i < 6; i++) {
            final int k = i;
            SmokeMain.wait(2, () -> { try { SmokeMain.key(297, 1, 346); } catch (Exception e) { e.printStackTrace(); } });   // Shift + Right Alt + F8
            SmokeMain.wait(12, () -> SmokeMain.shot("08_cinematic_fixed_" + (k + 1) + "_" + S().vehicles.camera.name().replace(' ', '_')));
        }
        // ---- police turn: at speed, Space + steering right (the cinematic camera stays on: Wheel shot)
        SmokeMain.step(() -> car().kmh() > 72, 300, () -> { SmokeMain.log(S(), "before police turn"); SmokeMain.hold(Keys.RIGHT, true); SmokeMain.hold(Keys.JUMP, true); });
        SmokeMain.wait(6, () -> { SmokeMain.log(S(), "police"); SmokeMain.shot("09_police_turn_a"); });
        SmokeMain.wait(8, () -> { SmokeMain.log(S(), "police"); SmokeMain.shot("10_police_turn_b"); });
        SmokeMain.step(() -> car().police == 0, 60, () -> { SmokeMain.hold(Keys.RIGHT, false); SmokeMain.hold(Keys.JUMP, false); SmokeMain.log(S(), "police turn done"); });
        SmokeMain.wait(2, () -> { try { SmokeMain.key(297, 0, 346); } catch (Exception e) { e.printStackTrace(); } SmokeMain.log(S(), "cam " + S().vehicles.camera.name()); });
        // ---- rolling over from the cabin: top speed, steer
        SmokeMain.step(() -> car().kmh() > 76, 400, () -> { S().view = 0; SmokeMain.hold(Keys.LEFT, true); SmokeMain.log(S(), "roll over test: steer"); });
        SmokeMain.step(() -> car().flipTicks > 18, 100, () -> { SmokeMain.log(S(), "rolling"); SmokeMain.shot("11_flip_first_person_a"); });
        SmokeMain.step(() -> car().flipTicks > 0 && car().flipTicks < 20, 100, () -> {});
        SmokeMain.wait(3, () -> SmokeMain.shot("12_flip_first_person_b"));
        SmokeMain.wait(4, () -> SmokeMain.shot("13_flip_first_person_c"));
        SmokeMain.step(() -> car().flipTicks == 0, 60, () -> { SmokeMain.hold(Keys.LEFT, false); SmokeMain.log(S(), "after the roll"); });
        // ---- another roll, the cinematic camera watches
        SmokeMain.wait(2, () -> { S().stats.health = 20; try { SmokeMain.key(297, 0, 346); } catch (Exception e) { e.printStackTrace(); } });
        SmokeMain.step(() -> car().kmh() > 76, 400, () -> SmokeMain.hold(Keys.RIGHT, true));
        SmokeMain.step(() -> car().flipTicks > 14, 100, () -> SmokeMain.shot("14_flip_cinematic_a"));
        SmokeMain.wait(5, () -> SmokeMain.shot("15_flip_cinematic_b"));
        SmokeMain.step(() -> car().flipTicks == 0, 60, () -> { SmokeMain.hold(Keys.RIGHT, false); drive(false); S().vehicles.camera.off(); S().view = 1; SmokeMain.log(S(), "after the second roll"); });
        // ---- the editor: Left Alt + End
        SmokeMain.wait(40, () -> { try { SmokeMain.key(269, 0, 342); } catch (Exception e) { e.printStackTrace(); } SmokeMain.log(S(), "editor: " + S().game.screen); });
        String[] pages = {"Speed", "Seats", "Blocks", "Size", "Suspension", "Terrain", "Weight", "Handling"};
        for (int i = 0; i < pages.length; i++) {
            final int p = i;
            SmokeMain.wait(3, () -> { try { SmokeMain.screen(pages[p], p); } catch (Exception e) { e.printStackTrace(); } });
            SmokeMain.wait(4, () -> SmokeMain.shot("20_editor_" + pages[p]));
        }
        SmokeMain.wait(3, () -> {
            try {
                var c = Class.forName("derpcraft.gui.VehicleEditor");
                var ed = S().game.screen;
                var m = c.getDeclaredMethod("addBlock"); m.setAccessible(true); m.invoke(ed);
                var m2 = c.getDeclaredMethod("addSeat"); m2.setAccessible(true); m2.invoke(ed);
                SmokeMain.screen("Blocks", 2);
            } catch (Exception e) { e.printStackTrace(); }
        });
        SmokeMain.wait(6, () -> SmokeMain.shot("21_editor_blocks_added"));
        SmokeMain.wait(2, () -> { try { SmokeMain.screen("Seats", 1); } catch (Exception e) { e.printStackTrace(); } });
        SmokeMain.wait(6, () -> SmokeMain.shot("22_editor_seat_added"));
        // sliders through their own code: top speed, size, undo
        SmokeMain.wait(2, () -> { try { SmokeMain.screen("Speed", 0); } catch (Exception e) { e.printStackTrace(); } });
        SmokeMain.wait(2, () -> { slider(0, 120); System.out.println("[smoke] CHECK top speed now " + car().type().maxSpeed * 72); });
        SmokeMain.wait(30, () -> {
            try { String j = java.nio.file.Files.readString(car().type().settingsFile()); System.out.println("[smoke] CHECK file has 120: " + j.contains("\"maxSpeed\": 120")); } catch (Exception e) { e.printStackTrace(); }
        });
        SmokeMain.wait(2, () -> { try { SmokeMain.screen("Size", 3); } catch (Exception e) { e.printStackTrace(); } });
        SmokeMain.wait(2, () -> { slider(0, 1.6); slider(1, 0.8); });
        SmokeMain.wait(40, () -> {
            var m = car().vm();
            System.out.printf(Locale.ROOT, "[smoke] CHECK size after the sliders: %.2f x %.2f x %.2f (want %.2f x %.2f x %.2f), seats at z %.2f%n", m.maxX - m.minX, m.maxY, m.maxZ - m.minZ, 1.9 * 1.6 * 0.8, 1.4 * 1.6, 4.2 * 1.6, m.seats.get(0)[2]);
            SmokeMain.shot("23_editor_size_1_6");
        });
        SmokeMain.wait(2, () -> { try { SmokeMain.key(90, 2, 0); } catch (Exception e) { e.printStackTrace(); } });   // Ctrl+Z
        SmokeMain.wait(40, () -> {
            var m = car().vm();
            System.out.printf(Locale.ROOT, "[smoke] CHECK after undo: scale %.2f width %.2f, size %.2f x %.2f x %.2f%n", car().type().userScale(), car().type().sizeX, m.maxX - m.minX, m.maxY, m.maxZ - m.minZ);
            SmokeMain.shot("24_editor_after_undo");
        });
        SmokeMain.wait(2, () -> { try { SmokeMain.key(269, 0, 342); } catch (Exception e) { e.printStackTrace(); } SmokeMain.log(S(), "editor closed: " + S().game.screen + ", marks " + S().vehicles.marks.size()); });
        // ---- a camera point of its own for the driver's seat (editor, Seats page), seen from the cabin
        SmokeMain.wait(5, () -> {
            try {
                Session s = S();
                s.vehicles.enterPlayer(car(), null); s.view = 0;
                s.game.setScreen(new derpcraft.gui.VehicleEditor(s.game, s));
                SmokeMain.screen("Seats", 1);
                var c = Class.forName("derpcraft.gui.VehicleEditor");
                var m = c.getDeclaredMethod("toggleEye"); m.setAccessible(true); m.invoke(s.game.screen);
                var sx = c.getDeclaredMethod("setEye", int.class, float.class); sx.setAccessible(true);
                sx.invoke(s.game.screen, 1, 3.5f); sx.invoke(s.game.screen, 2, 3.0f);
            } catch (Exception e) { e.printStackTrace(); }
        });
        SmokeMain.wait(8, () -> { S().game.setScreen(null); });
        SmokeMain.wait(6, () -> SmokeMain.shot("25_custom_camera_point"));
        SmokeMain.wait(2, () -> { S().vehicles.exitPlayer(); });
        // ---- night, lights and ice: a patch of ice and one of sand ahead, headlights on
        SmokeMain.wait(5, () -> {
            Session s = S();
            s.world.info.time = 18000;
            Vehicle v = car();
            v.setPose(300.5, 4, 0.5, 0);
            s.player.setPos(300.5, 4, 0.5);
            s.view = 1;
        });
        SmokeMain.wait(60, () -> {
            Session s = S();
            for (int xx = 296; xx <= 305; xx++) for (int zz = 60; zz < 140; zz++) s.world.setBlock(xx, 3, zz, derpcraft.block.Blocks.byName(zz < 100 ? "sand" : "ice"), 0);
            s.vehicles.enterPlayer(car(), null);
            s.vehicles.toggleLights();
            SmokeMain.hold(Keys.FORWARD, true);
        });
        SmokeMain.step(() -> car().z > 20, 200, () -> SmokeMain.shot("26_headlights_night_a"));
        SmokeMain.wait(10, () -> { S().view = 0; SmokeMain.shot("27_headlights_night_first_person"); S().view = 1; });
        SmokeMain.step(() -> car().z > 70, 300, () -> SmokeMain.log(S(), "on sand: surface " + car().surface));
        SmokeMain.step(() -> car().z > 108, 300, () -> { SmokeMain.log(S(), "on ice: surface " + car().surface); SmokeMain.hold(Keys.LEFT, true); });
        SmokeMain.wait(12, () -> { SmokeMain.log(S(), "steering on ice"); SmokeMain.shot("28_ice_slide"); });
        SmokeMain.wait(8, () -> { SmokeMain.hold(Keys.LEFT, false); SmokeMain.hold(Keys.FORWARD, false); SmokeMain.log(S(), "ice slide done"); S().vehicles.exitPlayer(); });
        // editor pages: Lights, Surfaces, Doors (none in the test car)
        SmokeMain.wait(10, () -> { S().game.setScreen(new derpcraft.gui.VehicleEditor(S().game, S())); });
        SmokeMain.wait(3, () -> { try { SmokeMain.screen("Lights", 9); } catch (Exception e) { e.printStackTrace(); } });
        SmokeMain.wait(6, () -> SmokeMain.shot("29_editor_Lights"));
        SmokeMain.wait(2, () -> { try { SmokeMain.screen("Surfaces", 10); } catch (Exception e) { e.printStackTrace(); } });
        SmokeMain.wait(6, () -> SmokeMain.shot("29_editor_Surfaces"));
        SmokeMain.wait(2, () -> { try { SmokeMain.screen("Doors", 8); } catch (Exception e) { e.printStackTrace(); } });
        SmokeMain.wait(6, () -> SmokeMain.shot("29_editor_Doors"));
        SmokeMain.wait(2, () -> { S().game.setScreen(null); S().world.info.time = 6000; });
        // ---- pushing: out of the car, behind it, Alt
        SmokeMain.wait(30, () -> {
            Session s = S();
            if (s.vehicles.riding()) s.vehicles.exitPlayer();
            Vehicle v = car();
            var m = v.vm();
            v.speed = 0; v.lat = 0;
            double a = Math.PI - Math.toRadians(v.yaw), c = Math.cos(a), sn = Math.sin(a);
            double lz = m.maxZ + 0.6;
            s.player.setPos(v.x + lz * sn, v.y, v.z + lz * c);
            s.player.yaw = v.yaw;
            s.player.pitch = 25;
            s.view = 1;
            SmokeMain.log(s, "player behind the car, hint: " + (s.vehicles.pushable() != null));
        });
        SmokeMain.wait(10, () -> SmokeMain.shot("30_push_hint"));
        SmokeMain.wait(2, () -> SmokeMain.hold(Keys.PUSH, true));
        SmokeMain.wait(30, () -> { SmokeMain.log(S(), "pushing (third person)"); SmokeMain.shot("31_push_third_person"); });
        SmokeMain.wait(2, () -> S().view = 0);
        SmokeMain.wait(12, () -> { SmokeMain.log(S(), "pushing (first person)"); SmokeMain.shot("32_push_first_person"); });
        SmokeMain.wait(2, () -> { S().player.pitch = 40; });
        SmokeMain.wait(6, () -> SmokeMain.shot("33_push_first_person_down"));
        SmokeMain.wait(10, () -> { SmokeMain.hold(Keys.PUSH, false); SmokeMain.log(S(), "released"); });
        SmokeMain.wait(40, () -> { SmokeMain.log(S(), "stopped"); System.out.println("[smoke] DONE"); System.exit(0); });
    }
}
