package derpcraft.entity;

import derpcraft.api.EventBus;
import derpcraft.api.Events;
import derpcraft.game.Raycast;
import derpcraft.game.Session;
import derpcraft.item.ItemStack;
import derpcraft.item.Items;
import derpcraft.world.World;

/** Something thrown or shot: flies with gravity and drag, hits blocks, creatures, NPCs and the player. */
public final class Projectile extends Entity {
    public final ProjectileType type;
    public final boolean byPlayer;
    public final Mob owner;
    /** NPC that threw / shot it (or null). */
    public derpcraft.npc.Actor ownerNpc;
    public float spin, prevSpin;
    /** Lingering projectiles (torches): lying on the ground, burning out. */
    public boolean landed;
    private int lingerLeft;
    /** Already hit a creature (a torch then only falls to the ground). */
    private boolean spent;
    /** Damage multiplier (bow draw strength). */
    public float dmgScale = 1;
    /** Signal flare: horizontal direction the smoke drifts (set once, to the left of the view when it first smokes). */
    public double windX, windZ;
    public boolean windSet;
    /** Ticks until a fused projectile (primed TNT) explodes; -1 = the type's fuse. */
    public int fuse = -1;
    /** Came to rest on the ground (dynamite): lies flat, no more spinning. */
    public boolean resting;
    /** Direction of flight (kept when stuck, for drawing). */
    public double dirX, dirY = -1, dirZ;
    private int stickX, stickY, stickZ;
    private int fireX, fireY, fireZ;
    private boolean fireSet;

    public Projectile(World w, ProjectileType type, double x, double y, double z, double vx, double vy, double vz, boolean byPlayer, Mob owner) {
        super(w, x, y, z);
        this.type = type;
        this.byPlayer = byPlayer;
        this.owner = owner;
        mx = vx; my = vy; mz = vz;
        width = height = type.size;
        spent = type.ignoreEntities;
    }

    @Override public void tick() {
        super.tick();
        Session s = Session.current;
        if (s == null || age > 1200 + type.lingerTicks || y < -64) { if (landed && s != null) burnOut(s); dead = true; return; }
        if (landed) { lie(s); return; }
        prevSpin = spin;
        if (resting && !world.getBlock((int) Math.floor(x), (int) Math.floor(y - 0.1), (int) Math.floor(z)).solid) resting = false;   // the ground was removed
        if (type.spin && !resting) spin += 25;
        if (type.onTick != null) Session.safe(() -> type.onTick.onTick(s, x, y, z, age));
        if (type.bounce >= 0 && age >= fuseTicks()) { land(s, x, y, z); return; }
        double len = Math.sqrt(mx * mx + my * my + mz * mz);
        if (len > 1e-6) { dirX = mx / len; dirY = my / len; dirZ = mz / len; }
        if (len > 1e-6) {
            double dx = mx / len, dy = my / len, dz = mz / len;
            Raycast.Hit h = Raycast.cast(world, x, y, z, dx, dy, dz, len, false);
            double tBlock = h == null ? len : Math.sqrt((h.hx - x) * (h.hx - x) + (h.hy - y) * (h.hy - y) + (h.hz - z) * (h.hz - z));
            // creatures, NPCs, the player
            double best = tBlock;
            Mob hitMob = null;
            derpcraft.npc.Actor hitNpc = null;
            boolean hitPlayer = false;
            for (Entity e : world.entities) {
                if (spent) break;
                if (!(e instanceof Mob m) || m.dead || m.deathTime > 0 || (m == owner && age < 20)) continue;
                double t = m.rayHit(x, y, z, dx, dy, dz, best);
                if (t >= 0 && t < best) { best = t; hitMob = m; }
            }
            var a = spent ? null : s.npcs.pick(x, y, z, dx, dy, dz, best);
            if (a != null && a == ownerNpc && age < 20) a = null;
            if (a != null) {
                double t = a.rayHit(x, y, z, dx, dy, dz, best);
                if (t >= 0 && t < best) { best = t; hitNpc = a; hitMob = null; }
            }
            if (!spent && (!byPlayer || age > 20) && !s.spectator()) {
                double[] bb = s.player.aabb();
                double t = boxHit(bb, x, y, z, dx, dy, dz, best);
                if (t >= 0 && t < best) { best = t; hitPlayer = true; hitMob = null; hitNpc = null; }
            }
            boolean moved = false;
            if (hitMob != null || hitNpc != null || hitPlayer) {
                double hx = x + dx * best, hy = y + dy * best, hz = z + dz * best;
                if (hitEntity(s, hitMob, hitNpc, hitPlayer, hx, hy, hz)) return;
            } else if (h != null) {
                moved = hitBlock(s, h, dx, dy, dz);
                if (dead) return;
            }
            if (!moved) { x += mx; y += my; z += mz; }
        }
        boolean water = world.getBlock((int) Math.floor(x), (int) Math.floor(y), (int) Math.floor(z)) == derpcraft.block.Blocks.WATER;
        if (water && type.lingerTicks > 0) { burnOut(s); dead = true; return; }
        float drag = water ? 0.8f : type.drag;
        mx *= drag; my *= drag; mz *= drag;
        if (resting) { mx *= 0.8; mz *= 0.8; my = 0; }      // lies still on the ground (no gravity: it would bob)
        else my -= type.gravity;
    }

    public int fuseTicks() { return fuse >= 0 ? fuse : type.fuse; }

    private static double boxHit(double[] bb, double ox, double oy, double oz, double dx, double dy, double dz, double max) {
        return derpcraft.util.RayBox.t(ox, oy, oz, dx, dy, dz, max, bb[0] - 0.1, bb[1] - 0.1, bb[2] - 0.1, bb[3] + 0.1, bb[4] + 0.1, bb[5] + 0.1);
    }

    /** Returns true when the projectile is used up. */
    private boolean hitEntity(Session s, Mob m, derpcraft.npc.Actor npc, boolean player, double hx, double hy, double hz) {
        Events.ProjectileHit ev = new Events.ProjectileHit(type.id, hx, hy, hz, 0, 0, 0, null, m, npc == null ? null : npc.name, player);
        if (type.onHit != null) Session.safe(() -> type.onHit.handle(s, ev));
        EventBus.post(s, ev);
        if (ev.isCancelled()) return false;
        double fx = hx - mx, fz = hz - mz;
        float damage = type.damage * dmgScale;
        if (damage > 0) {
            if (m != null) { m.hurt(damage, "projectile", byPlayer, owner, ownerNpc, fx, fz); if (type.fireTicks > 0) m.fireTicks = Math.max(m.fireTicks, type.fireTicks); }
            else if (npc != null) npc.hurt(s.npcs, damage, ownerNpc, byPlayer, owner);
            else if (player) { s.hurtPlayer(damage, "projectile", fx, fz); if (type.fireTicks > 0) s.stats.fireTicks = Math.max(s.stats.fireTicks, type.fireTicks); }
        } else if (m != null && type.knockback > 0) m.push(mx * 0.1, 0.1, mz * 0.1);
        x = hx; y = hy; z = hz;
        if (type.lingerTicks > 0 && !type.sticks) {      // a torch: bounces off and falls to the ground
            spent = true;
            mx *= -0.1; mz *= -0.1; my = 0.05;
            return false;
        }
        if (type.explode > 0) Explosions.explode(s, hx, hy, hz, type.explode, type.explodeBreaks);
        dead = true;
        return true;
    }

    private boolean hitBlock(Session s, Raycast.Hit h, double dx, double dy, double dz) {
        if (!h.block.solid) return false;      // flies through grass, flowers, torches
        Events.ProjectileHit ev = new Events.ProjectileHit(type.id, h.hx, h.hy, h.hz, h.x, h.y, h.z, h.block, null, null, false);
        if (type.onHit != null) Session.safe(() -> type.onHit.handle(s, ev));
        EventBus.post(s, ev);
        if (ev.isCancelled()) return false;
        if (type.lingerTicks > 0) return torchHitBlock(s, h);
        if (type.bounce >= 0) {
            // reflect on the face that was hit
            int f = h.face;
            double ex = derpcraft.block.Dir.DX[f] * 0.05, ey = derpcraft.block.Dir.DY[f] * 0.05, ez = derpcraft.block.Dir.DZ[f] * 0.05;
            x = h.hx + ex; y = h.hy + ey; z = h.hz + ez;
            if (derpcraft.block.Dir.DX[f] != 0) mx = -mx * type.bounce;
            if (derpcraft.block.Dir.DY[f] != 0) my = -my * type.bounce;
            if (derpcraft.block.Dir.DZ[f] != 0) mz = -mz * type.bounce;
            if (derpcraft.block.Dir.DY[f] > 0) {
                mx *= 0.7; mz *= 0.7;
                // a small bounce is no bounce: it comes to rest on the ground (and slides to a stop)
                if (Math.abs(my) < 0.08) { my = 0; mx *= 0.5; mz *= 0.5; resting = true; }
            }
            return true;
        }
        int f = h.face;
        land(s, h.hx + derpcraft.block.Dir.DX[f] * 0.1, h.hy + derpcraft.block.Dir.DY[f] * 0.1, h.hz + derpcraft.block.Dir.DZ[f] * 0.1);
        return true;
    }

    /** Lingering projectile hits a block: on top it lands (and lights the place); on a side or ceiling it slides down. */
    private boolean torchHitBlock(Session s, Raycast.Hit h) {
        int f = h.face;
        if (type.sticks) {
            // arrows stay in the block they hit, at any side
            x = h.hx - dirX * 0.1; y = h.hy - dirY * 0.1; z = h.hz - dirZ * 0.1;
            mx = my = mz = 0;
            landed = true;
            lingerLeft = type.lingerTicks;
            stickX = h.x; stickY = h.y; stickZ = h.z;
            if (type.igniteBlocks) {
                int fx = h.x + derpcraft.block.Dir.DX[f], fy = h.y + derpcraft.block.Dir.DY[f], fz = h.z + derpcraft.block.Dir.DZ[f];
                if (derpcraft.block.FunctionalBlocks.FireBlock.ignite(world, fx, fy, fz, false)) { fireX = fx; fireY = fy; fireZ = fz; fireSet = true; }
            }
            return true;
        }
        if (f != derpcraft.block.Dir.UP) {
            x = h.hx + derpcraft.block.Dir.DX[f] * 0.12; y = h.hy + derpcraft.block.Dir.DY[f] * 0.12; z = h.hz + derpcraft.block.Dir.DZ[f] * 0.12;
            if (derpcraft.block.Dir.DX[f] != 0) mx = derpcraft.block.Dir.DX[f] * 0.02;
            if (derpcraft.block.Dir.DZ[f] != 0) mz = derpcraft.block.Dir.DZ[f] * 0.02;
            if (derpcraft.block.Dir.DY[f] != 0) my = Math.min(my, 0) * 0.1;
            return true;
        }
        x = h.hx; y = h.hy + 0.04; z = h.hz;
        mx = my = mz = 0;
        landed = true;
        lingerLeft = type.lingerTicks;
        if (type.igniteBlocks) {
            int fx = h.x, fy = h.y + 1, fz = h.z;
            var at = world.getBlock(fx, fy, fz);
            boolean already = at == derpcraft.block.FunctionalBlocks.FireBlock.INSTANCE;
            if (!already && derpcraft.block.FunctionalBlocks.FireBlock.ignite(world, fx, fy, fz, false)) {
                fireX = fx; fireY = fy; fireZ = fz; fireSet = true;
            }
        }
        return true;
    }

    /** Lying on the ground: burns for a while, falls if the ground goes away, goes out in water. */
    private void lie(Session s) {
        if (--lingerLeft <= 0) { burnOut(s); dead = true; s.game.entities.poof(x, y, z, 0.3f, 0.3f); return; }
        int bx = (int) Math.floor(x), by = (int) Math.floor(y), bz = (int) Math.floor(z);
        if (world.getBlock(bx, by, bz) == derpcraft.block.Blocks.WATER) { burnOut(s); dead = true; return; }
        if (type.sticks) { if (!world.getBlock(stickX, stickY, stickZ).solid) { landed = false; mx = mz = 0; my = 0; } }   // the block it stuck in was removed
        else if (!world.getBlock(bx, (int) Math.floor(y - 0.1), bz).solid) { landed = false; mx = mz = 0; my = 0; }   // the ground was removed
    }

    /** The fire this projectile lit goes out with it. */
    private void burnOut(Session s) {
        if (fireSet && world.getBlock(fireX, fireY, fireZ).name.equals("fire") && (world.getMeta(fireX, fireY, fireZ) & derpcraft.block.FunctionalBlocks.FireBlock.ETERNAL) == 0)
            world.setBlock(fireX, fireY, fireZ, 0);
        fireSet = false;
    }

    private void land(Session s, double lx, double ly, double lz) {
        x = lx; y = ly; z = lz;
        dead = true;
        if (type.explode > 0) { Explosions.explode(s, lx, ly + type.lift, lz, type.explode, type.explodeBreaks); return; }
        if (type.dropItem != null) {
            var it = Items.byName(type.dropItem);
            if (it != null) world.dropItem(lx, ly, lz, new ItemStack(it, 1));
        }
        s.game.entities.poof(lx, ly, lz, 0.2f, 0.2f);
    }
}
