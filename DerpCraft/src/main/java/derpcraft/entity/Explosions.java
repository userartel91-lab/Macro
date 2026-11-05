package derpcraft.entity;

import derpcraft.api.EventBus;
import derpcraft.api.Events;
import derpcraft.block.Block;
import derpcraft.block.Blocks;
import derpcraft.game.Session;
import derpcraft.item.ItemStack;
import derpcraft.world.World;

import java.util.*;

/** Minecraft-like explosions: rays break blocks by their hardness, nearby things are hurt and thrown. */
public final class Explosions {
    private Explosions() {}
    private static final Random RND = new Random();

    public static void explode(Session s, double x, double y, double z, float power, boolean breakBlocks) {
        World w = s.world;
        derpcraft.audio.Sfx.at(s, "random/explode", x, y, z, 1.6f, 0.85f + w.random.nextFloat() * 0.3f);
        List<int[]> blocks = new ArrayList<>();
        if (breakBlocks && power > 0) {
            Set<Long> seen = new HashSet<>();
            for (int i = 0; i < 16; i++) for (int j = 0; j < 16; j++) for (int k = 0; k < 16; k++) {
                if (i != 0 && i != 15 && j != 0 && j != 15 && k != 0 && k != 15) continue;
                double dx = i / 15.0 * 2 - 1, dy = j / 15.0 * 2 - 1, dz = k / 15.0 * 2 - 1, l = Math.sqrt(dx * dx + dy * dy + dz * dz);
                dx /= l; dy /= l; dz /= l;
                float f = power * (0.7f + RND.nextFloat() * 0.6f);
                double px = x, py = y, pz = z;
                for (; f > 0; f -= 0.225f) {
                    int bx = (int) Math.floor(px), by = (int) Math.floor(py), bz = (int) Math.floor(pz);
                    if (by < 0 || by > 255) break;
                    Block b = w.getBlock(bx, by, bz);
                    if (!b.isAir()) {
                        float res = b.hardness < 0 ? 1e6f : b == Blocks.WATER || b == Blocks.LAVA ? 100 : b.hardness * 4;
                        f -= (res + 0.3f) * 0.3f;
                        if (f > 0 && b.hardness >= 0 && b != Blocks.WATER && b != Blocks.LAVA && seen.add(((long) bx << 38) ^ ((long) bz << 12) ^ by))
                            blocks.add(new int[]{bx, by, bz});
                    }
                    px += dx * 0.3; py += dy * 0.3; pz += dz * 0.3;
                }
            }
        }
        Events.Explosion ev = new Events.Explosion(x, y, z, power, blocks, breakBlocks);
        EventBus.post(s, ev);
        if (ev.isCancelled()) return;
        power = ev.power;
        // blocks
        if (ev.breakBlocks) {
            int n = 0;
            for (int[] p : ev.blocks) {
                int st = w.getState(p[0], p[1], p[2]);
                Block b = Blocks.byId(st >> 4);
                if (b.isAir()) continue;
                if (b == Blocks.TNT) {          // TNT in the blast goes off too, after a short fuse, pushed away from the centre
                    w.setBlock(p[0], p[1], p[2], 0);
                    double ax = p[0] + 0.5 - x, az = p[2] + 0.5 - z, al = Math.sqrt(ax * ax + az * az) + 1e-6;
                    s.spawnPrimedTnt(p[0] + 0.5, p[1] + 0.06, p[2] + 0.5, 10 + RND.nextInt(20), ax / al * 0.8 + (RND.nextDouble() - 0.5) * 0.4, az / al * 0.8 + (RND.nextDouble() - 0.5) * 0.4);
                    continue;
                }
                if (n++ % 6 == 0) s.game.entities.spawnBreakParticles(w, p[0], p[1], p[2], b, st & 15);
                if (RND.nextFloat() < 1f / Math.max(1, power))
                    for (ItemStack d : b.dropsWithEntity(w, p[0], p[1], p[2], st & 15, w.random, w.getBlockEntity(p[0], p[1], p[2]))) w.dropItem(p[0] + 0.5, p[1] + 0.3, p[2] + 0.5, d);
                w.setBlock(p[0], p[1], p[2], 0);
                b.onRemoved(w, p[0], p[1], p[2], st & 15);
            }
            // what stood on or hung at the destroyed blocks (torches, flowers, signs...) falls off and drops
            for (int[] p : ev.blocks) s.breakDependents(p[0], p[1], p[2]);
        }
        // creatures, NPCs, items, the player
        double r = power * 2;
        for (var e : new ArrayList<>(w.entities)) {
            double dx = e.x - x, dy = e.y + e.height / 2 - y, dz = e.z - z, d = Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (d >= r || d < 1e-4) continue;
            double impact = 1 - d / r;
            if (e instanceof Mob m) {
                m.hurt((float) ((impact * impact + impact) / 2 * 7 * r + 1), "explosion", false, null, null, x, z);
                m.push(dx / d * impact, dy / d * impact * 0.6 + 0.1, dz / d * impact);
            } else if (e instanceof ItemEntity ie) { ie.mx += dx / d * impact; ie.my += dy / d * impact * 0.5 + 0.1; ie.mz += dz / d * impact; }
        }
        for (var a : s.npcs.actors) {
            double dx = a.x() - x, dy = a.y() + 0.9 - y, dz = a.z() - z, d = Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (d >= r || d < 1e-4 || a.dead()) continue;
            double impact = 1 - d / r;
            a.hurt(s.npcs, (float) ((impact * impact + impact) / 2 * 7 * r + 1), null, false);
        }
        var p = s.player;
        double dx = p.x - x, dy = p.y + 0.9 - y, dz = p.z - z, d = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (d < r && d > 1e-4 && !s.spectator()) {
            double impact = 1 - d / r;
            if (s.survival()) s.stats.damage((float) ((impact * impact + impact) / 2 * 7 * r + 1), "explosion", s.inv.armorPoints(), s.ticks);
            if (!p.flying) s.pushPlayer(dx / d * impact, dy / d * impact * 0.6 + 0.15, dz / d * impact);
        }
        s.game.entities.explosion(x, y, z, power);
        if (s.vehicles != null) s.vehicles.blast(x, y, z, power);          // cars next to it go up too
    }
}
