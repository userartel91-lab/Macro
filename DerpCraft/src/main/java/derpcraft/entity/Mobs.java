package derpcraft.entity;

import derpcraft.api.DataTag;
import derpcraft.api.EventBus;
import derpcraft.api.Events;
import derpcraft.block.Block;
import derpcraft.block.Blocks;
import derpcraft.game.Session;
import derpcraft.world.World;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** The creatures of the open world: spawning, despawning, saving (mobs.txt in the world folder), picking. */
public final class Mobs {
    private final Session s;
    private final Path file;
    private final Random rnd = new Random();
    /** Saved creatures whose mod is missing: kept as they are and written back, so removing a mod doesn't lose them. */
    private final List<String> unknown = new ArrayList<>();
    public boolean spawning = true;

    public Mobs(Session s, Path worldDir) {
        this.s = s;
        this.file = worldDir.resolve("mobs.txt");
    }

    private World world() { return s.world; }

    public List<Mob> list() {
        List<Mob> out = new ArrayList<>();
        for (Entity e : world().entities) if (e instanceof Mob m && !m.dead) out.add(m);
        return out;
    }

    public Mob spawn(MobType t, double x, double y, double z, float yaw, boolean natural) {
        if (t == null) return null;
        Mob m = new Mob(world(), t, x, y, z, yaw);
        m.persistent = !natural;
        if (EventBus.has(Events.CreatureSpawn.class) && EventBus.post(s, new Events.CreatureSpawn(m, natural)).isCancelled()) return null;
        world().entities.add(m);
        return m;
    }

    public Projectile shoot(String type, double x, double y, double z, double dx, double dy, double dz, float speed, boolean byPlayer, Mob owner) {
        ProjectileType t = ProjectileType.get(type);
        if (t == null) { System.err.println("Unknown projectile " + type); return null; }
        double l = Math.sqrt(dx * dx + dy * dy + dz * dz) + 1e-9;
        Projectile p = new Projectile(world(), t, x, y, z, dx / l * speed, dy / l * speed, dz / l * speed, byPlayer, owner);
        world().entities.add(p);
        return p;
    }

    /** Creature under the crosshair. */
    public Mob pick(double ox, double oy, double oz, double dx, double dy, double dz, double max) {
        Mob best = null;
        double bt = max;
        for (Entity e : world().entities) {
            if (!(e instanceof Mob m) || m.dead) continue;
            double t = m.rayHit(ox, oy, oz, dx, dy, dz, bt);
            if (t >= 0 && t < bt) { bt = t; best = m; }
        }
        return best;
    }

    // ================================================================================ per tick
    public void tick() {
        List<Mob> mobs = list();
        var p = s.player;
        // keep them from walking into each other and into the player
        for (int i = 0; i < mobs.size(); i++) {
            Mob a = mobs.get(i);
            if (a.deathTime > 0) continue;
            double pdx = a.body.x - p.x, pdz = a.body.z - p.z, pr = a.type.width / 2 + 0.3;
            if (Math.abs(pdx) < pr && Math.abs(pdz) < pr && Math.abs(a.body.y - p.y) < 1.8 && !s.spectator()) {
                double l = Math.sqrt(pdx * pdx + pdz * pdz) + 1e-3;
                a.body.mx += pdx / l * 0.05; a.body.mz += pdz / l * 0.05;
            }
            for (int j = i + 1; j < mobs.size(); j++) {
                Mob b = mobs.get(j);
                double dx = a.body.x - b.body.x, dz = a.body.z - b.body.z, r = (a.type.width + b.type.width) / 2;
                if (Math.abs(dx) >= r || Math.abs(dz) >= r || Math.abs(a.body.y - b.body.y) > 1.5) continue;
                double l = Math.sqrt(dx * dx + dz * dz) + 1e-3;
                a.body.mx += dx / l * 0.04; a.body.mz += dz / l * 0.04;
                b.body.mx -= dx / l * 0.04; b.body.mz -= dz / l * 0.04;
            }
        }
        if (s.ticks % 20 != 0) return;
        // despawn natural creatures far away
        for (Mob m : mobs) {
            if (m.persistent || m.customName != null) continue;
            double dx = m.body.x - p.x, dz = m.body.z - p.z;
            if (dx * dx + dz * dz > 112 * 112) m.dead = true;
        }
        if (spawning) naturalSpawns(mobs);
    }

    private void naturalSpawns(List<Mob> mobs) {
        var p = s.player;
        World w = world();
        for (MobType t : MobType.REGISTRY.values()) {
            if (!t.spawns || rnd.nextFloat() > t.spawnChance) continue;
            int count = 0;
            for (Mob m : mobs) if (m.type == t && (m.body.x - p.x) * (m.body.x - p.x) + (m.body.z - p.z) * (m.body.z - p.z) < 96 * 96) count++;
            if (count >= t.spawnCap) continue;
            for (int attempt = 0; attempt < 4; attempt++) {
                double a = rnd.nextDouble() * Math.PI * 2, d = 24 + rnd.nextDouble() * 24;
                int x = (int) Math.floor(p.x + Math.cos(a) * d), z = (int) Math.floor(p.z + Math.sin(a) * d);
                if (!w.isLoaded(x, z)) continue;
                int y;
                if (t.spawnUnderground) {
                    int top = w.topSolidY(x, z);
                    if (top < 12) continue;
                    y = 5 + rnd.nextInt(top - 10);
                    while (y > 2 && !w.getBlock(x, y - 1, z).solid) y--;
                } else y = w.topSolidY(x, z) + 1;
                if (!canSpawnAt(t, x, y, z)) continue;
                int n = t.spawnMin + rnd.nextInt(t.spawnMax - t.spawnMin + 1);
                for (int k = 0; k < n && count < t.spawnCap; k++) {
                    int gx = x + (k == 0 ? 0 : rnd.nextInt(5) - 2), gz = z + (k == 0 ? 0 : rnd.nextInt(5) - 2);
                    int gy = t.spawnUnderground ? y : w.topSolidY(gx, gz) + 1;
                    if (k > 0 && !canSpawnAt(t, gx, gy, gz)) continue;
                    if (spawn(t, gx + 0.5, gy, gz + 0.5, rnd.nextFloat() * 360, true) != null) count++;
                }
                break;
            }
        }
    }

    private boolean canSpawnAt(MobType t, int x, int y, int z) {
        World w = world();
        if (y < 1 || y > 250) return false;
        Block below = w.getBlock(x, y - 1, z);
        if (!below.solid || below == Blocks.WATER || below == Blocks.LAVA || below == Blocks.BEDROCK) return false;
        // spawnOn holds "minecraft:grass" style names, built-in blocks are registered as plain "grass"
        if (!t.spawnOn.isEmpty() && !t.spawnOn.contains(below.name) && !t.spawnOn.contains("minecraft:" + below.name)) return false;
        for (int h = 0; h < Math.max(1, (int) Math.ceil(t.height)); h++) {
            Block b = w.getBlock(x, y + h, z);
            if (b.solid || b == Blocks.WATER || b == Blocks.LAVA) return false;
        }
        int sky = w.getSky(x, y, z) - (Mob.isDay(s) ? 0 : 11);
        int light = Math.max(Math.max(0, sky), w.getBlockLight(x, y, z));
        return switch (t.spawnLight) {
            case "dark" -> light <= 7;
            case "light" -> light >= 9;
            default -> true;
        };
    }

    // ================================================================================ saving
    public void save() {
        StringBuilder sb = new StringBuilder("spawning=" + spawning + "\n");
        for (Mob m : list()) {
            if (m.deathTime > 0) continue;
            DataTag t = new DataTag();
            t.put("type", m.type.id);
            t.put("x", m.body.x); t.put("y", m.body.y); t.put("z", m.body.z);
            t.put("yaw", m.body.yaw);
            t.put("hp", m.health);
            if (m.persistent) t.put("persistent", true);
            if (m.customName != null) t.put("name", m.customName);
            if (m.fireTicks > 0) t.put("fire", m.fireTicks);
            if (!m.data.isEmpty()) t.putTag("data", m.data);
            sb.append(t.encode()).append('\n');
        }
        for (String u : unknown) sb.append(u).append('\n');
        try { derpcraft.util.SafeFiles.writeString(file, sb); }
        catch (Exception e) { System.err.println("Cannot save mobs: " + e); }
    }

    public void load() {
        unknown.clear();
        if (!Files.exists(file)) return;
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                if (line.isBlank()) continue;
                if (line.startsWith("spawning=")) { spawning = Boolean.parseBoolean(line.substring(9).trim()); continue; }
                DataTag t = DataTag.decode(line);
                MobType type = MobType.get(t.getString("type"));
                if (type == null) { unknown.add(line); continue; }
                Mob m = new Mob(world(), type, t.getDouble("x", 0), t.getDouble("y", 64), t.getDouble("z", 0), t.getFloat("yaw", 0));
                m.health = t.getFloat("hp", type.maxHealth);
                m.persistent = t.getBool("persistent");
                m.customName = t.has("name") ? t.getString("name") : null;
                m.fireTicks = t.getInt("fire", 0);
                if (t.has("data")) m.data.copyFrom(t.getTag("data"));
                world().entities.add(m);
            }
            if (!unknown.isEmpty()) System.out.println(unknown.size() + " creature(s) of missing mods kept in mobs.txt");
        } catch (Exception e) { System.err.println("Cannot load mobs: " + e); }
    }
}
