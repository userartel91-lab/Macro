package derpcraft.vehicle;

import derpcraft.world.World;

import java.nio.file.*;
import java.util.*;

/**
 * What the vehicle editor (Alt + End) relies on, without the game: settings written to the json and read back, the model analysed
 * again after a change of size, seats and block places from the settings, and the cinematic camera.
 * Usage: sh tools/vehicle_check/editor.sh
 */
public class EditorTest {
    static int fails;

    static void check(String what, boolean ok) { System.out.println((ok ? "  OK    " : "  FAIL  ") + what); if (!ok) fails++; }

    static boolean near(double a, double b) { return Math.abs(a - b) < 1e-3; }

    public static void main(String[] args) throws Exception {
        Path dir = HandlingTest.makeCar("edcar", 0.8, 1.4);
        VehicleType ty = Transports.add(dir);
        Vehicles vs = new Vehicles(); Vehicles.current = vs;
        VehicleModel m0 = ty.vm();
        float w0 = m0.maxX - m0.minX, l0 = m0.maxZ - m0.minZ, h0 = m0.maxY;
        System.out.printf("car %.2f x %.2f x %.2f, %d seats (auto), %d block places, settings file would be %s%n", w0, h0, l0, m0.seats.size(), m0.slots.size(), ty.settingsFile().getFileName());

        System.out.println("\n1. settings file: written by the editor, read back by the game");
        ty.maxSpeed = 140 / 72f; ty.accel = 40 / 72f / 20; ty.weight = 2500; ty.stepHeight = 1.5f; ty.rideHeight = 0.1f; ty.rollover = 0.02f; ty.grip = 0.4f; ty.waterDrag = 0.9f;
        ty.save();
        System.out.println(Files.readString(ty.settingsFile()));
        Path bd = Files.createDirectories(Files.createTempDirectory("dc_copy").resolve("edcopy"));
        Files.copy(dir.resolve("edcar.obj"), bd.resolve("edcopy.obj"));
        Files.copy(ty.settingsFile(), bd.resolve("edcopy.json"));
        VehicleType back = Transports.add(bd);
        back.vm();
        check("top speed 140 km/h", near(back.maxSpeed * 72, 140));
        check("acceleration 40 km/h/s", near(back.accel * 72 * 20, 40));
        check("weight 2500", near(back.weight, 2500));
        check("step height 1.5", near(back.stepHeight, 1.5));
        check("ride height 0.1", near(back.rideHeight, 0.1));
        check("rollover 0.02, grip 0.4, water 0.9", near(back.rollover, 0.02) && near(back.grip, 0.4) && near(back.waterDrag, 0.9));

        System.out.println("\n2. size: scale 1.5, width x 0.8, rebuilt");
        float f = 1.5f / ty.userScale(); ty.setUserScale(1.5f); ty.resize(f, f, f);
        f = 0.8f / ty.sizeX; ty.sizeX = 0.8f; ty.resize(f, 1, 1);
        ty.rebuild();
        VehicleModel m1 = ty.vm();
        System.out.printf("  now %.2f x %.2f x %.2f%n", m1.maxX - m1.minX, m1.maxY, m1.maxZ - m1.minZ);
        check("length x1.5", near(m1.maxZ - m1.minZ, l0 * 1.5));
        check("height x1.5", near(m1.maxY, h0 * 1.5));
        check("width x1.5 x0.8", near(m1.maxX - m1.minX, w0 * 1.5 * 0.8));
        Vehicle v = new Vehicle(1, ty.name); vs.l.clear(); vs.l.add(v); v.setPose(0.5, 64, 0.5, 0);
        check("the vehicle picks up the new analysis", near(v.vm().maxZ - v.vm().minZ, l0 * 1.5));

        System.out.println("\n3. seats from the settings (the editor's seat list keeps its order)");
        ty.seats.clear();
        for (float[] sv : ty.vm().seats) ty.seats.add(sv.clone());
        int n = ty.seats.size();
        ty.seats.add(new float[]{-0.5f, 0.6f, 2.0f});          // a seat added last stays the last one
        ty.seats.get(0)[1] = 0.9f;
        ty.rebuild();
        VehicleModel m2 = ty.vm();
        check("one more seat", m2.seats.size() == n + 1);
        check("the added seat is the last", near(m2.seats.get(n)[2], 2.0) && near(m2.seats.get(n)[0], -0.5));
        check("first seat height 0.9", near(m2.seats.get(0)[1], 0.9));
        v.vm();
        check("the vehicle's rider list follows", v.riders.length == n + 1);

        System.out.println("\n3b. camera point per seat");
        ty.eyes.clear(); ty.eyes.add(new float[]{-0.4f, 1.3f, 0.5f}); ty.eyes.add(null);
        ty.rebuild();
        check("seat 1 has its camera point, seat 2 the usual", ty.vm().eyes.size() == ty.vm().seats.size() && near(ty.vm().eyes.get(0)[1], 1.3) && ty.vm().eyes.get(1) == null);
        ty.save();
        Path ed = Files.createDirectories(Files.createTempDirectory("dc_copy3").resolve("edeye"));
        Files.copy(dir.resolve("edcar.obj"), ed.resolve("edeye.obj"));
        Files.copy(ty.settingsFile(), ed.resolve("edeye.json"));
        VehicleType eye = Transports.add(ed);
        check("camera points survive the file", eye.vm().eyes.get(0) != null && near(eye.vm().eyes.get(0)[2], 0.5) && eye.vm().eyes.get(1) == null);
        ty.eyes.clear(); ty.rebuild();

        System.out.println("\n3c. seat turn (yaw)");
        ty.seatYaws.clear(); ty.seatYaws.add(0f); ty.seatYaws.add(180f);
        ty.rebuild();
        check("seat 2 faces backwards", near(ty.vm().seatYaw.get(1), 180) && near(ty.vm().seatYaw.get(0), 0) && ty.vm().seatYaw.size() == ty.vm().seats.size());
        ty.save();
        Path yd = Files.createDirectories(Files.createTempDirectory("dc_copy4").resolve("edyaw"));
        Files.copy(dir.resolve("edcar.obj"), yd.resolve("edyaw.obj"));
        Files.copy(ty.settingsFile(), yd.resolve("edyaw.json"));
        check("seat turn survives the file", near(Transports.add(yd).vm().seatYaw.get(1), 180));
        ty.seatYaws.clear(); ty.rebuild();

        System.out.println("\n3d. surfaces, lights, doors");
        ty.surfGrip[4] = 0.3f; ty.surfSpeed[2] = 0.5f;
        ty.lightsSet = true; ty.lights.clear();
        VehicleType.Light lg = new VehicleType.Light(); lg.a = new float[]{0.5f, 0.6f, -2}; lg.b = new float[]{0.4f, 0.1f, -12}; lg.width = 2.5f; lg.intensity = 1.4f; lg.color = new float[]{1, 0.5f, 0.2f};
        ty.lights.add(lg);
        ty.save();
        Path ld = Files.createDirectories(Files.createTempDirectory("dc_copy5").resolve("edlight"));
        Files.copy(dir.resolve("edcar.obj"), ld.resolve("edlight.obj"));
        Files.copy(ty.settingsFile(), ld.resolve("edlight.json"));
        VehicleType lt = Transports.add(ld); lt.vm();
        check("surface multipliers survive", near(lt.surfGrip[4], 0.3) && near(lt.surfSpeed[2], 0.5) && near(lt.surfGrip[0], 1));
        check("one light with its start, end, width, colour", lt.lightsSet && lt.lights.size() == 1 && near(lt.lights.get(0).b[2], -12) && near(lt.lights.get(0).width, 2.5) && near(lt.lights.get(0).color[1], 0.5));
        ty.lightsSet = false; ty.lights.clear();
        check("no lights in the settings: two headlights and two tail lights are made up", ty.effectiveLights().size() == 4 && ty.effectiveLights().get(0).b[2] < ty.effectiveLights().get(0).a[2] - 10);
        ty.surfGrip[4] = 0.12f; ty.surfSpeed[2] = 0.8f;
        // a door: the angle set as the editor does it (relative to the geometry) comes out the same after the model is analysed again
        StringBuilder ob = new StringBuilder("");
        int[] vi = {1};
        HandlingTest.cube(ob, vi, "body", -0.9, 0.35, -2.1, 0.9, 0.95, 2.1);
        HandlingTest.cube(ob, vi, "wheel1", -1.0, 0, -1.65, -0.7, 0.7, -0.95); HandlingTest.cube(ob, vi, "wheel2", 0.7, 0, -1.65, 1.0, 0.7, -0.95);
        HandlingTest.cube(ob, vi, "wheel3", -1.0, 0, 0.95, -0.7, 0.7, 1.65); HandlingTest.cube(ob, vi, "wheel4", 0.7, 0, 0.95, 1.0, 0.7, 1.65);
        HandlingTest.cube(ob, vi, "door1", -0.95, 0.4, -0.3, -0.88, 1.0, 0.7);
        Path dd = Files.createDirectories(Files.createTempDirectory("dc_door").resolve("eddoor"));
        Files.writeString(dd.resolve("eddoor.obj"), ob.toString());
        VehicleType dt = Transports.add(dd);
        VehicleModel.Part door = dt.vm().openables.get(0);
        float auto = door.openAngle;
        check("a door was found, angle " + auto, dt.vm().openables.size() == 1 && Math.abs(auto) > 10);
        float want = -50;                                           // the editor: absolute angle -> the settings' relative one
        float flipV = (want < 0 ? -1 : 1) * door.geoSign;
        dt.angles.put(door.name.toLowerCase(), flipV * Math.abs(want));
        dt.pivots.put(door.name.toLowerCase(), new float[]{-0.9f, 0.7f, -0.3f});
        dt.rebuild();
        VehicleModel.Part d2 = dt.vm().openables.get(0);
        check("angle -50 and the hinge from the settings", near(d2.openAngle, -50) && near(d2.px, -0.9) && near(d2.pz, -0.3));
        dt.save();
        check("door settings in the file", Files.readString(dt.settingsFile()).contains("\"parts\"") && Files.readString(dt.settingsFile()).contains("pivot"));

        System.out.println("\n3e. a model with seat groups: seats moved in the editor stay moved");
        StringBuilder sg = new StringBuilder("");
        int[] gi = {1};
        HandlingTest.cube(sg, gi, "body", -0.9, 0.35, -2.1, 0.9, 0.95, 2.1);
        HandlingTest.cube(sg, gi, "wheel1", -1.0, 0, -1.65, -0.7, 0.7, -0.95); HandlingTest.cube(sg, gi, "wheel2", 0.7, 0, -1.65, 1.0, 0.7, -0.95);
        HandlingTest.cube(sg, gi, "wheel3", -1.0, 0, 0.95, -0.7, 0.7, 1.65); HandlingTest.cube(sg, gi, "wheel4", 0.7, 0, 0.95, 1.0, 0.7, 1.65);
        HandlingTest.cube(sg, gi, "seat1", -0.7, 0.4, -0.2, -0.1, 0.6, 0.4); HandlingTest.cube(sg, gi, "seat2", 0.1, 0.4, -0.2, 0.7, 0.6, 0.4);
        Path gd = Files.createDirectories(Files.createTempDirectory("dc_seatg").resolve("edseats"));
        Files.writeString(gd.resolve("edseats.obj"), sg.toString());
        VehicleType st = Transports.add(gd);
        VehicleModel sm = st.vm();
        System.out.println("  seats from the groups: " + sm.seats.size() + " first " + java.util.Arrays.toString(sm.seats.get(0)));
        for (float[] a : sm.seats) st.seats.add(a.clone());          // the editor's materialize
        st.seats.get(0)[0] = 0.33f; st.seats.get(0)[2] = 0.9f; sm.seats.get(0)[0] = 0.33f;
        st.save(); st.rebuild();
        check("seat 1 stays where the editor put it after the rebuild", near(st.vm().seats.get(0)[0], 0.33) && near(st.vm().seats.get(0)[2], 0.9));
        Path gd2 = Files.createDirectories(Files.createTempDirectory("dc_seatg2").resolve("edseats2"));
        Files.copy(gd.resolve("edseats.obj"), gd2.resolve("edseats2.obj")); Files.copy(st.settingsFile(), gd2.resolve("edseats2.json"));
        VehicleType st2 = Transports.add(gd2);
        check("and after reading the file again", near(st2.vm().seats.get(0)[0], 0.33) && near(st2.vm().seats.get(0)[2], 0.9) && st2.vm().seats.size() == sm.seats.size());

        System.out.println("\n4. block places from the settings");
        ty.blocksSet = true; ty.blocks.clear();
        ty.blocks.add(new float[]{0, 1.4f, 0}); ty.blocks.add(new float[]{0.5f, 1.4f, 1.5f});
        ty.rebuild();
        VehicleModel m3 = ty.vm();
        check("2 block places", m3.slots.size() == 2 && near(m3.slots.get(1)[2], 1.5));
        ty.save();
        String json = Files.readString(ty.settingsFile());
        check("blocks and seats in the file", json.contains("\"blocks\"") && json.contains("\"seats\""));
        Path ad = Files.createDirectories(Files.createTempDirectory("dc_copy2").resolve("edagain"));
        Files.copy(dir.resolve("edcar.obj"), ad.resolve("edagain.obj"));
        Files.copy(ty.settingsFile(), ad.resolve("edagain.json"));
        VehicleType again = Transports.add(ad);
        VehicleModel m4 = again.vm();
        check("the file read back gives the same car (size, seats, blocks)", near(m4.maxZ - m4.minZ, l0 * 1.5) && m4.seats.size() == n + 1 && m4.slots.size() == 2 && near(again.sizeX, 0.8) && near(again.userScale(), 1.5));
        ty.blocksSet = false; ty.blocks.clear(); ty.rebuild();
        check("blocks reset: the model's again", ty.vm().slots.size() == m0.slots.size());

        System.out.println("\n5. the cinematic camera follows, cuts and keeps out of walls");
        World w = HandlingTest.flat();
        Vehicle car = new Vehicle(2, ty.name); vs.l.clear(); vs.l.add(car); car.vm(); car.setPose(0.5, 64, 0.5, 0); car.riders[car.vm().driverSeat] = Vehicle.PLAYER;
        VehicleCamera cam = new VehicleCamera();
        cam.press(false);
        check("on after the key", cam.active());
        float maxBehind = 0; double minDist = 1e9, maxDist = 0;
        for (int t = 0; t < 400; t++) {
            car.throttle = 1; car.steerIn = t > 150 && t < 190 ? 1 : 0; car.tick(w, false);
            double[] c = cam.frame(car, car.vm(), 1f, 0.05f, (x, y, z, dx, dy, dz, max) -> max);
            double d = Math.hypot(c[0] - car.x, c[2] - car.z);
            minDist = Math.min(minDist, d); maxDist = Math.max(maxDist, d);
            if (t == 100 || t == 399) System.out.printf("  t=%d camera %.1f %.1f %.1f yaw %.0f pitch %.0f, car at %.1f %.1f, shot %s%n", t, c[0], c[1], c[2], c[3], c[4], car.x, car.z, cam.name());
        }
        System.out.printf("  distance to the car %.1f .. %.1f blocks%n", minDist, maxDist);
        check("the camera stays within 1.5 .. 20 blocks of the car", minDist > 1.5 && maxDist < 20);
        double[] walled = cam.frame(car, car.vm(), 1f, 0.05f, (x, y, z, dx, dy, dz, max) -> 1.0);
        check("a wall pulls it in: within 1 block of the car's top", Math.hypot(walled[0] - car.x, walled[2] - car.z) < 1.5);
        cam.press(true);
        check("Shift: a fixed shot (" + cam.name() + ")", cam.mode >= 2);
        for (int i = 0; i < VehicleCamera.SHOTS.length; i++) cam.press(true);
        check("after the last shot: auto again", cam.mode == 1);
        cam.press(false);
        check("off again", !cam.active());

        System.out.println(fails == 0 ? "\nALL OK" : "\nFAILS: " + fails);
        System.exit(fails == 0 ? 0 : 1);
    }
}
