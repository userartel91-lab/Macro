import derpcraft.block.*;
import derpcraft.world.*;
import derpcraft.game.*;
import java.nio.file.*;
import java.util.*;

/** Deterministic mixed-block world: prints a digest of body trajectories, ray hits and selection data. */
public class Equiv {
    public static void main(String[] a) throws Exception {
        Blocks.registerDefaults();
        try { derpcraft.item.Items.registerDefaults(); } catch (Throwable t) {}
        World w = new World(new WorldInfo(Files.createTempDirectory("dce")));
        int R = 3;
        List<Block> solids = new ArrayList<>();
        for (Block b : Blocks.all()) if (b.solid && !b.isAir() && b.createBlockEntity() == null) solids.add(b);
        solids.sort(Comparator.comparing(b -> b.name));
        Random r = new Random(7);
        for (int cx = -R; cx <= R; cx++) for (int cz = -R; cz <= R; cz++) {
            Chunk c = new Chunk(cx, cz);
            for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) {
                for (int y = 0; y < 60; y++) c.setRaw(x, y, z, Blocks.STONE.id << 4);
                for (int y = 60; y < 66; y++) if (r.nextInt(4) == 0) {
                    Block b = solids.get(r.nextInt(solids.size()));
                    c.setRaw(x, y, z, b.id << 4 | r.nextInt(16));
                }
            }
            c.state = Chunk.STATE_GENERATED; w.chunks.put(c.key(), c); w.chunksChanged();
        }
        long h = 1125899906842597L;
        int N = 200; Player[] ps = new Player[N];
        for (int i = 0; i < N; i++) { Player p = new Player(); p.setPos(r.nextInt(90) - 45 + 0.5, 67, r.nextInt(90) - 45 + 0.5); p.yaw = r.nextFloat() * 360; ps[i] = p;
            if (i % 7 == 0) { p.width = 0.9f; p.height = 1.4f; } if (i % 11 == 0) { p.width = 0.3f; p.height = 0.5f; } }
        Player.Input in = new Player.Input();
        for (int tick = 0; tick < 1500; tick++)
            for (int i = 0; i < N; i++) {
                Player p = ps[i]; in.forward = (tick / 40 + i) % 5 == 0 ? 0 : 1; in.strafe = (tick / 30 + i) % 3 - 1; in.jump = (tick + i) % 23 == 0; in.sneak = (tick / 60 + i) % 4 == 0; in.sprint = i % 3 == 0;
                in.deepSneak = i % 9 == 0;
                if ((tick + i) % 50 == 0) p.yaw += 70 + i;
                if (i % 13 == 0 && tick % 300 == 0) p.stance = Stance.values()[(tick / 300 + i) % Stance.values().length];
                p.tick(w, in, false);
                h = h * 31 + Double.doubleToLongBits(p.x); h = h * 31 + Double.doubleToLongBits(p.y); h = h * 31 + Double.doubleToLongBits(p.z);
                h = h * 31 + (p.onGround ? 1 : 0) + (p.collidedHorizontally ? 2 : 0) + Float.floatToIntBits(p.height);
            }
        System.out.println("bodies " + Long.toHexString(h));
        long rh = 7;
        for (int i = 0; i < 200000; i++) {
            double yaw = r.nextDouble() * 6.283, pit = -r.nextDouble() * 1.2;
            double dx = Math.cos(pit) * Math.sin(yaw), dy = Math.sin(pit), dz = Math.cos(pit) * Math.cos(yaw);
            Raycast.Hit hit = Raycast.cast(w, r.nextDouble() * 90 - 45, 66.5 + r.nextDouble() * 3, r.nextDouble() * 90 - 45, dx, dy, dz, 30, r.nextBoolean());
            if (hit != null) { rh = rh * 31 + hit.x * 7 + hit.y * 13 + hit.z * 17 + hit.face; rh = rh * 31 + Double.doubleToLongBits(hit.hx + hit.hy + hit.hz); }
        }
        System.out.println("rays " + Long.toHexString(rh));
        // entity-style collect
        long eh = 3;
        for (int i = 0; i < 20000; i++) {
            double x = r.nextDouble() * 80 - 40, y = 59 + r.nextDouble() * 8, z = r.nextDouble() * 80 - 40;
            for (double[] b : derpcraft.entity.Entity.collect(w, x, y, z, x + 0.25, y + 0.25, z + 0.25)) {
                if (b[3] <= x || b[0] >= x + 0.25 || b[4] <= y || b[1] >= y + 0.25 || b[5] <= z || b[2] >= z + 0.25) continue;
                for (double v : b) eh = eh * 31 + Double.doubleToLongBits(v);
            }
        }
        System.out.println("overlapping boxes " + Long.toHexString(eh));
        System.exit(0);
    }
}
