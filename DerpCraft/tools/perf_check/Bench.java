import derpcraft.block.*;
import derpcraft.world.*;
import derpcraft.game.*;
import java.nio.file.*;
import java.util.*;

public class Bench {
    public static void main(String[] a) throws Exception {
        Blocks.registerDefaults();
        try { derpcraft.item.Items.registerDefaults(); } catch (Throwable t) { System.out.println("items: " + t); }
        World w = new World(new WorldInfo(Files.createTempDirectory("dcw")));
        TerrainGenerator gen = new TerrainGenerator(Long.getLong("seed", 28));
        int R = Integer.getInteger("R", 16);
        long t0 = System.nanoTime();
        for (int cx = -R; cx <= R; cx++) for (int cz = -R; cz <= R; cz++) {
            Chunk c = new Chunk(cx, cz); gen.generate(c); c.state = Chunk.STATE_GENERATED; w.chunks.put(c.key(), c); w.chunksChanged();
        }
        long t1 = System.nanoTime();
        for (Chunk c : w.chunks.values()) w.light.lightChunk(c);
        w.light.await(600000);
        long t2 = System.nanoTime();
        System.out.printf("gen %d chunks: %.0f ms, light: %.0f ms%n", w.chunks.size(), (t1 - t0) / 1e6, (t2 - t1) / 1e6);
        String what = a.length > 0 ? a[0] : "all";
        Random r = new Random(1);
        // getState
        if (what.equals("all") || what.equals("state")) for (int rep = 0; rep < 3; rep++) {
            long s = System.nanoTime(); long sum = 0;
            for (int i = 0; i < 20_000_000; i++) { int x = (i * 7) % 200 - 100, z = (i / 200 * 3) % 200 - 100, y = 40 + (i & 63); sum += w.getState(x, y, z); }
            System.out.printf("getState 20M: %.0f ms (%d)%n", (System.nanoTime() - s) / 1e6, sum & 1);
        }
        // physics: walking bodies
        if (what.equals("all") || what.equals("phys")) {
            int N = 300;
            Player[] ps = new Player[N];
            for (int i = 0; i < N; i++) {
                Player p = new Player(); int x = r.nextInt(200) - 100, z = r.nextInt(200) - 100;
                p.setPos(x + 0.5, w.topSolidY(x, z) + 1, z + 0.5); p.yaw = r.nextFloat() * 360; ps[i] = p;
            }
            Player.Input in = new Player.Input();
            for (int rep = 0; rep < 5; rep++) {
                long s = System.nanoTime();
                for (int tick = 0; tick < 400; tick++)
                    for (int i = 0; i < N; i++) {
                        Player p = ps[i]; in.forward = 1; in.jump = (tick + i) % 37 == 0; in.sneak = (i % 5 == 0); in.sprint = i % 3 == 0;
                        if ((tick + i) % 50 == 0) p.yaw += 70;
                        p.tick(w, in, false);
                    }
                System.out.printf("phys %d bodies x 400 ticks: %.0f ms (%.3f ms/tick)%n", N, (System.nanoTime() - s) / 1e6, (System.nanoTime() - s) / 1e6 / 400);
            }
            double sy = 0; for (Player p : ps) sy += p.y; System.out.println("checksum y " + sy);
        }
        if (what.equals("all") || what.equals("ray")) for (int rep = 0; rep < 3; rep++) {
            long s = System.nanoTime(); int hits = 0;
            for (int i = 0; i < 300_000; i++) {
                double yaw = r.nextDouble() * 6.283, pit = -r.nextDouble() * 0.8;
                double dx = Math.cos(pit) * Math.sin(yaw), dy = Math.sin(pit), dz = Math.cos(pit) * Math.cos(yaw);
                int x = r.nextInt(160) - 80, z = r.nextInt(160) - 80;
                if (Raycast.cast(w, x + 0.5, w.topSolidY(x, z) + 2.6, z + 0.5, dx, dy, dz, 64, false) != null) hits++;
            }
            System.out.printf("raycast 300k: %.0f ms (%d hits)%n", (System.nanoTime() - s) / 1e6, hits);
        }
        if (what.equals("all") || what.equals("path")) for (int rep = 0; rep < 3; rep++) {
            long s = System.nanoTime(); int found = 0, len = 0;
            Random pr = new Random(5);
            for (int i = 0; i < 300; i++) {
                int x = pr.nextInt(200) - 100, z = pr.nextInt(200) - 100, tx = x + pr.nextInt(60) - 30, tz = z + pr.nextInt(60) - 30;
                if (w.getBlock(x, w.topSolidY(x, z), z) == Blocks.WATER || w.getBlock(tx, w.topSolidY(tx, tz), tz) == Blocks.WATER) { i--; continue; }
                java.util.List<int[]> p = derpcraft.npc.PathFinder.find(w, x, w.topSolidY(x, z) + 1, z, tx, w.topSolidY(tx, tz) + 1, tz, true, 3000);
                if (p != null) { found++; len += p.size(); }
            }
            System.out.printf("path 300: %.0f ms (%d found, %d steps)%n", (System.nanoTime() - s) / 1e6, found, len);
        }
        System.exit(0);
    }
}
