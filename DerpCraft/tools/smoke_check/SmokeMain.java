import derpcraft.DerpCraft;
import derpcraft.api.ModContext;
import derpcraft.game.Keys;
import derpcraft.game.Session;
import derpcraft.vehicle.Vehicle;
import derpcraft.world.WorldInfo;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.*;
import java.util.*;

/**
 * Starts the real game under software OpenGL (Xvfb + Mesa llvmpipe), drives the vehicle features by script (Scenario.java:
 * drift, police turn, rolling over, the cinematic camera, the editor, pushing) and takes screenshots. Run it with
 * sh tools/smoke_check/run.sh (see its README.md).
 */
public class SmokeMain {
    static DerpCraft game;
    static final List<String> shots = new ArrayList<>();
    static final TreeMap<Long, List<Runnable>> script = new TreeMap<>();
    static Field shotFlag;
    static Vehicle car;
    static long base = -1;

    static void at(long t, Runnable r) { script.computeIfAbsent(t, k -> new ArrayList<>()).add(r); }

    /** Sequential steps: a step runs when its condition holds (or after its timeout in ticks); the next one starts at once after. */
    static final ArrayDeque<Object[]> steps = new ArrayDeque<>();
    static long stepStart = -1;

    static void step(java.util.function.BooleanSupplier ready, int timeout, Runnable r) { steps.add(new Object[]{ready, timeout, r}); }

    static void wait(int ticks, Runnable r) { long[] t0 = {-1}; steps.add(new Object[]{(java.util.function.BooleanSupplier) () -> { if (t0[0] < 0) t0[0] = Session.current.ticks; return Session.current.ticks - t0[0] >= ticks; }, ticks + 5, r}); }

    static void runSteps(Session s) {
        Object[] st = steps.peek();
        if (st == null) return;
        if (stepStart < 0) stepStart = s.ticks;
        boolean ok = ((java.util.function.BooleanSupplier) st[0]).getAsBoolean() || s.ticks - stepStart > (int) st[1];
        if (ok) { steps.poll(); stepStart = -1; ((Runnable) st[2]).run(); runSteps(s); }
    }

    static void key(int code, int mods, int holdMod) throws Exception {
        if (holdMod != 0) Keys.VIRTUAL_MODS.add(holdMod);
        Method m = DerpCraft.class.getDeclaredMethod("onKey", int.class, int.class, boolean.class); m.setAccessible(true);
        m.invoke(game, code, mods, false);
        if (holdMod != 0) Keys.VIRTUAL_MODS.remove(holdMod);
    }

    static void shot(String name) {
        try { shotFlag.set(game, true); } catch (Exception e) { throw new RuntimeException(e); }
        shots.add(name);
        System.out.println("[smoke] shot " + name);
    }

    static void hold(Keys.Binding b, boolean on) { if (on) Keys.HELD.put(b, Long.MAX_VALUE); else Keys.HELD.remove(b); }

    static void log(Session s, String what) {
        Vehicle v = car;
        System.out.printf(Locale.ROOT, "[smoke] t=%d %s | car %s%n", s.ticks - base, what, v == null ? "-" : String.format(Locale.ROOT, "pos %.1f %.1f %.1f yaw %.0f speed %.0f km/h lat %.0f drift %.2f slip %.2f flip %.0f flipTicks %d police %d | hp %.1f", v.x, v.y, v.z, v.yaw, v.kmh(), v.lat * 72, v.drift, v.slip, v.flip, v.flipTicks, v.police, s.stats.health));
    }

    static void screen(String pageName, int page) throws Exception {
        Class<?> c = Class.forName("derpcraft.gui.VehicleEditor");
        Field f = c.getDeclaredField("page"); f.setAccessible(true); f.setInt(null, page);
        Field sc = c.getDeclaredField("scroll"); sc.setAccessible(true); sc.setInt(null, 0);
        game.relayout();
    }

    /** A plain car (red body, blue cabin, black wheels, a steering wheel) so that no model file is needed. */
    static void makeCar(Path d) throws Exception {
        Files.createDirectories(d);
        if (Files.exists(d.resolve("testcar.obj"))) return;
        StringBuilder sb = new StringBuilder("mtllib testcar.mtl\n");
        int[] vi = {1};
        double ht = 0.8, r = 0.35;
        cube(sb, vi, "body", "red", -ht - 0.1, 0.35, -2.1, ht + 0.1, 0.95, 2.1);
        cube(sb, vi, "cabin", "blue", -ht + 0.05, 0.95, -0.7, ht - 0.05, 1.4, 1.5);
        cube(sb, vi, "wheel1", "black", -ht - 0.15, 0, -1.3 - r, -ht + 0.1, 2 * r, -1.3 + r);
        cube(sb, vi, "wheel2", "black", ht - 0.1, 0, -1.3 - r, ht + 0.15, 2 * r, -1.3 + r);
        cube(sb, vi, "wheel3", "black", -ht - 0.15, 0, 1.3 - r, -ht + 0.1, 2 * r, 1.3 + r);
        cube(sb, vi, "wheel4", "black", ht - 0.1, 0, 1.3 - r, ht + 0.15, 2 * r, 1.3 + r);
        cube(sb, vi, "steering_wheel", "black", -0.55, 0.85, -0.35, -0.25, 0.9, -0.05);
        Files.writeString(d.resolve("testcar.obj"), sb.toString());
        Files.writeString(d.resolve("testcar.mtl"), "newmtl red\nKd 0.8 0.1 0.1\nnewmtl blue\nKd 0.2 0.3 0.8\nnewmtl black\nKd 0.05 0.05 0.05\n");
    }

    static void cube(StringBuilder sb, int[] vi, String name, String mat, double x0, double y0, double z0, double x1, double y1, double z1) {
        sb.append("o ").append(name).append("\nusemtl ").append(mat).append('\n');
        double[][] v = {{x0, y0, z0}, {x1, y0, z0}, {x1, y1, z0}, {x0, y1, z0}, {x0, y0, z1}, {x1, y0, z1}, {x1, y1, z1}, {x0, y1, z1}};
        for (double[] p : v) sb.append(String.format(Locale.ROOT, "v %.3f %.3f %.3f%n", p[0], p[1], p[2]));
        int[][] f = {{0, 3, 2, 1}, {4, 5, 6, 7}, {0, 1, 5, 4}, {3, 7, 6, 2}, {0, 4, 7, 3}, {1, 2, 6, 5}};
        for (int[] q : f) sb.append(String.format("f %d %d %d %d%n", vi[0] + q[0], vi[0] + q[1], vi[0] + q[2], vi[0] + q[3]));
        vi[0] += 8;
    }

    public static void main(String[] args) throws Exception {
        Path dir = Path.of(args[0]).toAbsolutePath();
        makeCar(dir.resolve("transport/testcar"));
        System.setProperty("derpcraft.dir", dir.toString());
        game = new DerpCraft(dir);
        shotFlag = DerpCraft.class.getDeclaredField("screenshotRequested"); shotFlag.setAccessible(true);
        Method init = DerpCraft.class.getDeclaredMethod("init"); init.setAccessible(true);
        init.invoke(game);
        WorldInfo info = new WorldInfo(dir.resolve("saves/smoke"));
        info.name = "smoke"; info.seed = 1; info.generator = "flat"; info.time = 6000; info.gameMode = "survival"; info.daylightCycle = false;
        info.save();
        game.openWorld(info);

        ModContext.TICK.add(s -> {
            try {
                Session ss = (Session) s;
                if (base < 0) base = ss.ticks;
                long t = ss.ticks - base;
                List<Runnable> l = script.remove(t);
                if (l != null) for (Runnable r : l) r.run();
                if (t >= 60) runSteps(ss);
                if (t > 0 && t % 20 == 0 && car != null && t < 2000) { /* quiet */ }
            } catch (Throwable e) { e.printStackTrace(); }
        });
        Scenario.build();
        Method loop = DerpCraft.class.getDeclaredMethod("loop"); loop.setAccessible(true);
        loop.invoke(game);
    }
}
