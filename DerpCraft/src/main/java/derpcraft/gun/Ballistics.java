package derpcraft.gun;

import derpcraft.audio.Audio;
import derpcraft.game.Blood;
import derpcraft.game.Raycast;
import derpcraft.game.Session;
import derpcraft.npc.Actor;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * One bullet, for everyone who shoots: the player, NPCs in a fight and actors replaying a take. A hit-scan ray to the
 * gun's range: the first block, creature, NPC (not the shooter) or the player (when an NPC shoots) along it is hit;
 * chips and sparks on blocks, a red puff on bodies, a tracer now and then, smoke at the muzzle, the shot sound (quieter
 * far away, panned), a flash of light at an NPC's muzzle.
 */
public final class Ballistics {
    private Ballistics() {}

    private static final Random RND = new Random();

    /** What a bullet did: how far it flew, whether it hit a body (creature, NPC or the player) and whether in the head. */
    public record Result(double dist, boolean body, boolean headshot) {}

    /** How hard a bullet pushes what it hits (blocks per tick per point of damage, at most 0.32): a body falls away from the shot. */
    public static float PUSH = 0.03f;

    /**
     * Fires a bullet of gun 't' from the eye (ex, ey, ez) along the unit direction (dx, dy, dz); the muzzle (mx, my, mz) is
     * where smoke, flash and tracer start. shooter = the NPC that shoots (null: the player). 'hurt' = deal damage (off
     * for replayed takes: the bullets still hit and show it); 'glass' = glass blocks shatter and wooden doors give
     * (a wooden door cracks with every hit and breaks at DOOR_HEALTH damage; torches are knocked off; the player's shots).
     */
    public static Result fire(Session s, GunType t, Actor shooter, double ex, double ey, double ez, double dx, double dy, double dz,
                              double mx, double my, double mz, boolean hurt, boolean glass) {
        return fire(s, t, shooter, ex, ey, ez, dx, dy, dz, mx, my, mz, hurt, glass, glass ? DOORS_BREAK : DOORS_NONE);
    }

    /** Wooden doors: untouched / cracked and broken by the shots / only cracked (a replayed take: the recorded block changes break it). */
    public static final int DOORS_NONE = 0, DOORS_BREAK = 1, DOORS_VISUAL = 2;

    public static Result fire(Session s, GunType t, Actor shooter, double ex, double ey, double ez, double dx, double dy, double dz,
                              double mx, double my, double mz, boolean hurt, boolean glass, int doors) {
        Raycast.Hit h = Raycast.cast(s.world, ex, ey, ez, dx, dy, dz, t.range, false);
        double best = h == null ? t.range : Math.sqrt((h.hx - ex) * (h.hx - ex) + (h.hy - ey) * (h.hy - ey) + (h.hz - ez) * (h.hz - ez));
        derpcraft.entity.Mob mob = s.mobs.pick(ex, ey, ez, dx, dy, dz, best);
        if (mob != null) { double tm = mob.rayHit(ex, ey, ez, dx, dy, dz, best); if (tm >= 0) best = tm; else mob = null; }
        Actor npc = s.npcs.pick(ex, ey, ez, dx, dy, dz, best, shooter);
        if (npc != null) { double tn = npc.rayHit(ex, ey, ez, dx, dy, dz, best); if (tn >= 0 && tn <= best) { best = tn; mob = null; } else npc = null; }
        boolean player = false;
        if (shooter != null && !s.spectator() && !s.stats.dead) {
            var p = s.player;
            // the player's own box: lying, crawling, sitting and crouching are lower (a fixed 1.85 tall box was hit by shots over a lying player)
            double pr = p.width / 2 + 0.05;
            double tp = rayBox(ex, ey, ez, dx, dy, dz, best, p.x - pr, p.y, p.z - pr, p.x + pr, p.y + p.height + 0.05, p.z + pr);
            if (tp >= 0) { best = tp; player = true; mob = null; npc = null; }
        }
        var fx = s.game.entities;
        double hx = ex + dx * best, hy = ey + dy * best, hz = ez + dz * best;
        boolean body = player || mob != null || npc != null;
        // a headshot: the top of the body (the head is the top quarter of the model), the gun's headshotMultiplier
        boolean head = player ? hy >= s.player.y + s.player.eyeHeight - 0.26
                : mob != null ? hy >= mob.y() + mob.type.height * 0.76
                : npc != null && npc.headAt(hy);
        float dmg = t.damage * (head ? t.headshotMultiplier : 1);
        Blood.muted++;                                    // the shot sprays from the wound itself (below), not from the chest
        try {
            if (player) {
                if (hurt) s.npcs.hitPlayer(shooter, dmg);
            } else if (mob != null) {
                if (hurt) mob.hurt(dmg, "gun", shooter == null, null, shooter, ex, ez);
            } else if (npc != null) {
                // the push of the bullet: a body that dies (or already lies there) falls away from the shot
                double k = Math.min(0.32, PUSH * dmg);
                npc.impact(hx, hy, hz, dx * k, dy * k + 0.02, dz * k);
                if (hurt) npc.hurt(s.npcs, dmg, shooter, shooter == null);
            }
        } finally { Blood.muted--; }
        if (body) {
            fx.gunBlood(hx, hy, hz, dx, dy, dz);
            Blood.spill(hx, hy, hz, dx, dy * 0.5 + 0.1, dz, dmg * (head ? 1.6f : 1));
        } else if (h != null) {
            fx.gunImpact(s.world, h);
            String n = h.block.name;
            if (glass && (n.equals("glass") || n.endsWith("_glass") || n.contains("glass_pane")) && !n.contains("bottle")) {
                fx.spawnBreakParticles(s.world, h.x, h.y, h.z, h.block, h.meta);
                s.world.setBlock(h.x, h.y, h.z, 0);
            } else if (glass && h.block.shape == derpcraft.block.Block.Shape.TORCH) {
                shootTorch(s, h);
            } else if (doors != DOORS_NONE && h.block instanceof derpcraft.block.FunctionalBlocks.DoorBlock door && !door.iron) {
                shootDoor(s, t, door, h, doors == DOORS_VISUAL);
            } else if (isCamera(h.block) && (glass || doors == DOORS_VISUAL)) {
                if (glass) shootCamera(s, h); else fx.gunImpact(s.world, h);
            } else {
                // a chip out of the block: a crater, chunks of the block flying off, cracks that grow; enough hits break it
                shootBlock(s, t, h, doors == DOORS_VISUAL || !glass || !s.game.settings.bulletBlocks);
            }
        }
        fx.gunMuzzle(mx, my, mz, dx, dy, dz);
        if (RND.nextFloat() < t.tracerChance) {
            float[] c = t.tracerColor;
            fx.tracer(mx, my, mz, hx, hy, hz, t.tracerSpeed, t.tracerLength, t.tracerWidth, c[0], c[1], c[2], t.tracerGlow);
        }
        if (shooter != null) {
            fx.muzzleLight(mx, my, mz);
            List<Path> sh = t.shootSounds;
            if (!sh.isEmpty()) sound(s, sh.get(RND.nextInt(sh.size())), mx, my, mz, 0.9f * t.volume, 0.96f + RND.nextFloat() * 0.08f);
        }
        return new Result(best, body, body && head);
    }

    // ------------------------------------------------------------------ blocks
    /** Blocks: block id -> whether it is a camera / whether it keeps a block entity (asked once per block kind). */
    private static final java.util.Map<Integer, Boolean> CAMERA = new java.util.HashMap<>(), HAS_ENTITY = new java.util.HashMap<>();

    /**
     * Whether a block is a camera: bullets break it at once. Any camera counts, also ones added later or by mods: a block
     * whose block entity is a {@link derpcraft.camera.CameraEntity} (or a kind of it), a {@link derpcraft.camera.CameraBlock},
     * or a block named "...camera..." (its id or its display name).
     */
    public static boolean isCamera(derpcraft.block.Block b) {
        if (b == null) return false;
        return CAMERA.computeIfAbsent(b.id, k -> {
            if (b instanceof derpcraft.camera.CameraBlock) return true;
            String n = (b.name + " " + b.displayName).toLowerCase(java.util.Locale.ROOT);
            if (n.contains("camera") && !n.contains("canvas")) return true;
            try { return b.createBlockEntity() instanceof derpcraft.camera.CameraEntity; } catch (RuntimeException e) { return false; }
        });
    }

    private static boolean hasEntity(derpcraft.block.Block b) {
        return HAS_ENTITY.computeIfAbsent(b.id, k -> { try { return b.createBlockEntity() != null; } catch (RuntimeException e) { return true; } });
    }

    /**
     * Damage bullets must do to break a block, or 0 if they never do: its own {@link derpcraft.block.Block#bulletHealth}
     * if set; cameras 1 (the first hit); blocks with contents or settings (a block entity: chests, signs...),
     * unbreakable ones (bedrock) and liquids never; others by hardness: 6 + 30 per point (dirt 21 = 3 rifle rounds,
     * stone 51 = 8, planks 66 = 10, leaves 12 = 2, obsidian about 1500).
     */
    public static float blockHealth(derpcraft.block.Block b) {
        if (b == null || b.isAir()) return 0;
        if (b.bulletHealth >= 0) return b.bulletHealth;
        if (isCamera(b)) return 1;
        if (b.hardness < 0 || b.shape == derpcraft.block.Block.Shape.LIQUID || hasEntity(b)) return 0;
        return 6 + b.hardness * 30;
    }

    /** Damaged blocks: position -> {damage in tenths, game time of the last hit}. Read by the renderer for the cracks. */
    public static final java.util.Map<Long, long[]> BLOCK_HITS = new java.util.HashMap<>();
    /** Cracks of a block left alone this long (ticks: a minute) go away. */
    public static final long BLOCK_HEAL = 1200;

    /** Share of a block's health shot away (0..1), from a BLOCK_HITS value; 'health' from blockHealth. */
    public static float blockDamage(long[] v, float health) { return health <= 0 ? Math.min(0.9f, v[0] / 600f) : v[0] / (health * DOOR_UNITS); }

    /**
     * A bullet hits a block: a crater (bigger as the block wears), chunks of the block fly off the face, the cracks
     * grow. 'visualOnly' (NPC fights, replayed takes, Bullets Break Blocks off, or a block bullets do not break): it
     * never breaks; otherwise when the damage reaches its health it breaks into pieces (nothing drops).
     */
    static void shootBlock(Session s, GunType t, Raycast.Hit h, boolean visualOnly) {
        var w = s.world;
        var fx = s.game.entities;
        float health = blockHealth(h.block);
        BLOCK_HITS.values().removeIf(v -> w.gameTime - v[1] > BLOCK_HEAL);
        long key = doorKey(h.x, h.y, h.z);
        long[] v = BLOCK_HITS.computeIfAbsent(key, k -> new long[2]);
        long add = Math.max(1, Math.round(Math.max(1, t.damage) * DOOR_UNITS));
        long full = health <= 0 ? Long.MAX_VALUE : (long) (health * DOOR_UNITS);
        v[0] = visualOnly || health <= 0 ? Math.min(v[0] + add, Math.max(1, full - 1)) : v[0] + add;
        v[1] = w.gameTime;
        float worn = Math.min(1, blockDamage(v, health <= 0 ? 0 : health));
        fx.bulletCrater(w, h, worn);
        if (visualOnly || health <= 0 || v[0] < full) return;
        BLOCK_HITS.remove(key);
        fx.spawnBreakParticles(w, h.x, h.y, h.z, h.block, h.meta);
        w.setBlock(h.x, h.y, h.z, 0);
        h.block.onRemoved(w, h.x, h.y, h.z, h.meta);
    }

    /** A camera hit by a bullet: it bursts in sparks and glass, the block is gone (the camera network forgets it), nothing drops. */
    static void shootCamera(Session s, Raycast.Hit h) {
        var w = s.world;
        var fx = s.game.entities;
        fx.spawnBreakParticles(w, h.x, h.y, h.z, h.block, h.meta);
        for (int i = 0; i < 4; i++) fx.gunImpact(w, h);
        w.setBlock(h.x, h.y, h.z, 0);
        h.block.onRemoved(w, h.x, h.y, h.z, h.meta);
        derpcraft.camera.CameraNetwork.markDirty();
    }

    /**
     * A torch (also a redstone torch, a mod's torch-shaped block) that a bullet hits is knocked off: it breaks with its
     * particles and the light goes out; in survival it drops like a broken torch.
     */
    public static void shootTorch(Session s, Raycast.Hit h) {
        var w = s.world;
        s.game.entities.spawnBreakParticles(w, h.x, h.y, h.z, h.block, h.meta);
        if (s.survival()) for (var d : h.block.drops(h.meta, w.random)) w.dropItem(h.x + 0.5, h.y + 0.3, h.z + 0.5, d);
        w.setBlock(h.x, h.y, h.z, 0);
    }

    /**
     * Damage a wooden door takes before it breaks (each bullet adds its gun's damage: AK-12 (7) - 5 shots, FP9 (5) -
     * 6 shots); a door that is left alone for 10 seconds heals.
     */
    public static final float DOOR_HEALTH = 30;
    /** DOOR_HITS keeps the damage in tenths. */
    private static final long DOOR_UNITS = 10;
    private static final long DOOR_HEAL = 200;
    /** Damaged doors: lower half position -> {damage in tenths, game time of the last shot}. Read by the renderer for the crack overlay. */
    public static final java.util.Map<Long, long[]> DOOR_HITS = new java.util.HashMap<>();

    public static long doorKey(int x, int y, int z) { return ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | (y & 0xFFF); }

    /**
     * A bullet hits a wooden door: it splinters and cracks (the block-breaking animation on both halves); when the
     * damage reaches DOOR_HEALTH it breaks (both halves splinter, nothing drops). Changes are block changes: a take
     * being recorded keeps them.
     */
    static void shootDoor(Session s, GunType t, derpcraft.block.FunctionalBlocks.DoorBlock door, Raycast.Hit h, boolean visualOnly) {
        shootDoor(s, t, door, h, visualOnly, Math.max(1, t.damage));
    }

    /** Share of a door's health already shot away (0..1), from a DOOR_HITS value. */
    public static float doorDamage(long[] v) { return v[0] / (DOOR_HEALTH * DOOR_UNITS); }

    /** The same with 'damage' at once (a butt strike: GunController.MELEE_DOOR_DAMAGE). */
    static void shootDoor(Session s, GunType t, derpcraft.block.FunctionalBlocks.DoorBlock door, Raycast.Hit h, boolean visualOnly, float damage) {
        var w = s.world;
        var fx = s.game.entities;
        int ly = (h.meta & 8) != 0 ? h.y - 1 : h.y;           // the lower half holds the door's state
        DOOR_HITS.values().removeIf(v -> w.gameTime - v[1] > DOOR_HEAL);
        long key = doorKey(h.x, ly, h.z);
        long[] v = DOOR_HITS.computeIfAbsent(key, k -> new long[2]);
        long full = (long) (DOOR_HEALTH * DOOR_UNITS), add = Math.max(1, Math.round(damage * DOOR_UNITS));
        v[0] = visualOnly ? Math.min(v[0] + add, full - 1) : v[0] + add;
        v[1] = w.gameTime;
        fx.spawnBreakParticles(w, h.x, h.y, h.z, h.block, h.meta);
        if (visualOnly || v[0] < full) return;
        DOOR_HITS.remove(key);
        for (int y = ly; y <= ly + 1; y++) {
            if (w.getBlock(h.x, y, h.z) != door) continue;
            fx.spawnBreakParticles(w, h.x, y, h.z, door, w.getMeta(h.x, y, h.z));
        }
        int lm = w.getMeta(h.x, ly, h.z);
        w.setBlock(h.x, ly + 1, h.z, 0);
        w.setBlock(h.x, ly, h.z, 0);
        door.onRemoved(w, h.x, ly, h.z, lm);
        brokeSound(s, h.x + 0.5, ly + 1, h.z + 0.5);
    }

    /** A door shot to pieces: a random game/sfx/door/broke1-5 (.ogg / .wav), at the door. */
    static void brokeSound(Session s, double x, double y, double z) {
        Path dir = s.game.gameDir.resolve("sfx").resolve("door");
        List<Path> l = new ArrayList<>();
        for (int i = 1; i <= 5; i++)
            for (String ext : new String[]{".ogg", ".wav"}) {
                Path f = dir.resolve("broke" + i + ext);
                if (java.nio.file.Files.isRegularFile(f)) { l.add(f); break; }
            }
        if (!l.isEmpty()) sound(s, l.get(RND.nextInt(l.size())), x, y, z, 1f, 0.95f + RND.nextFloat() * 0.1f);
    }

    /** A random direction within 'spreadDeg' degrees of (dx, dy, dz) (unit). */
    public static double[] spread(double dx, double dy, double dz, float spreadDeg, Random r) {
        double yaw = Math.atan2(-dx, dz), pitch = -Math.asin(Math.max(-1, Math.min(1, dy)));
        double a = Math.toRadians(spreadDeg) * Math.sqrt(r.nextDouble()), ang = r.nextDouble() * Math.PI * 2;
        yaw += a * Math.cos(ang); pitch += a * Math.sin(ang);
        return new double[]{-Math.sin(yaw) * Math.cos(pitch), -Math.sin(pitch), Math.cos(yaw) * Math.cos(pitch)};
    }

    /** A sound at a place in the world: quieter with distance (silent past 96 blocks), panned left / right from the player's view. */
    public static void sound(Session s, Path file, double x, double y, double z, float volume, float pitch) {
        if (file == null) return;
        var p = s.player;
        double lx = p.x, ly = p.y + p.eyeHeight, lz = p.z;
        double dx = x - lx, dy = y - ly, dz = z - lz, d = Math.sqrt(dx * dx + dy * dy + dz * dz);
        float fall = (float) Math.max(0, 1 - d / 96);
        if (fall <= 0) return;
        double yaw = Math.toRadians(p.yaw);
        float pan = d < 1e-3 ? 0 : (float) ((-Math.cos(yaw) * dx - Math.sin(yaw) * dz) / d);
        Audio.play(Audio.load(file), false, volume * fall * fall, pitch, Math.max(-1, Math.min(1, pan * 0.8f)));
    }

    /** Ray against an axis aligned box: the distance along the ray, or -1. */
    static double rayBox(double ox, double oy, double oz, double dx, double dy, double dz, double max,
                         double x0, double y0, double z0, double x1, double y1, double z1) {
        return derpcraft.util.RayBox.t(ox, oy, oz, dx, dy, dz, max, x0, y0, z0, x1, y1, z1);
    }
}
