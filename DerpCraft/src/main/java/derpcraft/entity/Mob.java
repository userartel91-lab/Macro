package derpcraft.entity;

import derpcraft.api.Creature;
import derpcraft.api.DataTag;
import derpcraft.api.EventBus;
import derpcraft.api.Events;
import derpcraft.block.Block;
import derpcraft.block.Blocks;
import derpcraft.block.FunctionalBlocks;
import derpcraft.game.Player;
import derpcraft.game.Raycast;
import derpcraft.game.Session;
import derpcraft.item.ItemStack;
import derpcraft.item.Items;
import derpcraft.npc.PathFinder;
import derpcraft.world.World;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/** A creature of a mod-defined {@link MobType}: physics like the player, simple AI, path finding, combat. */
public final class Mob extends Entity implements Creature {
    private static int nextId = 1;
    private static final Random RND = new Random();

    public final int id = nextId++;
    public final MobType type;
    public final Player body = new Player();
    public float health;
    public int hurtTime, deathTime, invulnerable, attackCooldown, fireTicks, angerTicks, fleeTicks, swingTicks = -2;
    public boolean persistent;
    public String customName;
    public final DataTag data = new DataTag();

    // targets
    boolean targetPlayer;
    Mob targetMob;
    derpcraft.npc.Actor targetNpc;
    double fleeX, fleeZ;
    boolean walking;
    double walkX, walkY, walkZ;

    // animation
    public float bodyYaw, prevBodyYaw, headYaw, prevHeadYaw, pitch, prevPitch, limbSwing, prevLimbSwing, limbAmount, prevLimbAmount;

    // navigation
    private List<int[]> path;
    private int pathIdx, pathAge = 100, stuck;
    private double progX, progZ, pathTX, pathTY, pathTZ;
    private double wanderX, wanderZ;
    private int wanderWait = 40;
    private final List<int[]> openedDoors = new ArrayList<>();

    public Mob(World w, MobType type, double x, double y, double z, float yaw) {
        super(w, x, y, z);
        this.type = type;
        body.width = type.width;
        body.height = type.height;
        width = type.width;
        height = type.height;
        body.setPos(x, y, z);
        body.yaw = yaw;
        bodyYaw = prevBodyYaw = headYaw = prevHeadYaw = yaw;
        health = type.maxHealth;
        wanderX = x; wanderZ = z;
    }

    // ================================================================================ tick
    @Override public void tick() {
        super.tick();
        prevBodyYaw = bodyYaw; prevHeadYaw = headYaw; prevPitch = pitch; prevLimbSwing = limbSwing; prevLimbAmount = limbAmount;
        Session s = Session.current;
        if (deathTime > 0) {
            deathTime++;
            body.tick(world, new Player.Input(), false);
            sync();
            animate();
            if (deathTime >= 20) {
                dead = true;
                if (s != null) s.game.entities.poof(body.x, body.y + type.height / 2, body.z, type.width, type.height);
            }
            return;
        }
        if (hurtTime > 0) hurtTime--;
        if (invulnerable > 0) invulnerable--;
        if (attackCooldown > 0) attackCooldown--;
        if (swingTicks >= -1 && ++swingTicks >= 6) swingTicks = -2;
        if (s == null) return;

        environment(s);
        if (dead || deathTime > 0) return;
        if (world.random.nextInt(450) == 0) derpcraft.audio.Sfx.mob(s, type.id, "say", body.x, body.y + type.height / 2, body.z, 0.9f);
        if (type.onTick != null) Session.safe(() -> type.onTick.onTick(s, this));
        if (dead) return;

        Player.Input in = new Player.Input();
        ai(s, in);
        if (body.inWater && type.swims) in.jump = true;
        closeDoorsBehind();
        body.tick(world, in, false);
        // footsteps (mob/<name>/step), one per stride on the ground
        double sdx = body.x - stepX, sdz = body.z - stepZ;
        stepX = body.x; stepZ = body.z;
        if (body.onGround && Math.abs(sdx) + Math.abs(sdz) < 2) {
            stepAcc += Math.sqrt(sdx * sdx + sdz * sdz);
            if (stepAcc > 1.4) { stepAcc = 0; derpcraft.audio.Sfx.mob(s, type.id, "step", body.x, body.y, body.z, 0.5f); }
        }
        if (body.landed > 3 && !body.inWater) hurt(body.landed - 3, "fall", false, null, null, body.x, body.z);
        body.landed = 0;
        sync();
        animate();
    }

    private void sync() { x = body.x; y = body.y; z = body.z; mx = body.mx; my = body.my; mz = body.mz; onGround = body.onGround; }

    private void environment(Session s) {
        int bx = (int) Math.floor(body.x), by = (int) Math.floor(body.y + 0.1), bz = (int) Math.floor(body.z);
        Block feet = world.getBlock(bx, by, bz);
        if (feet == Blocks.LAVA && !type.fireproof) { fireTicks = Math.max(fireTicks, 300); if (age % 10 == 0) hurt(4, "lava", false, null, null, body.x, body.z); }
        if (feet.name.contains("fire") && !type.fireproof) fireTicks = Math.max(fireTicks, 160);
        if (type.burnsInSun && age % 20 == 0 && isDay(s) && !body.inWater) {
            int hy = (int) Math.floor(body.y + type.height * 0.85);
            if (world.getSky(bx, hy, bz) >= 15 && world.topSolidY(bx, bz) <= hy) fireTicks = Math.max(fireTicks, 160);
        }
        if (body.inWater) fireTicks = 0;
        if (fireTicks > 0) {
            fireTicks--;
            if (fireTicks % 20 == 0 && !type.fireproof) hurt(1, "fire", false, null, null, body.x, body.z);
        }
        if (body.y < -64) die(false);
    }

    public static boolean isDay(Session s) {
        long t = Math.floorMod(s.world.info.time, 24000L);
        return t < 12300 || t > 23850;
    }

    // ================================================================================ AI
    private boolean playerTargetable(Session s) { return s.survival() && !s.stats.dead && !s.spectator(); }

    private void ai(Session s, Player.Input in) {
        Player p = s.player;
        double pdx = p.x - body.x, pdz = p.z - body.z, pd2 = pdx * pdx + pdz * pdz;
        if (angerTicks > 0) angerTicks--;
        else if (type.attitude != MobType.Attitude.HOSTILE) { targetPlayer = false; targetMob = null; targetNpc = null; }
        if (targetPlayer && !playerTargetable(s)) targetPlayer = false;
        if (targetMob != null && (targetMob.dead || targetMob.deathTime > 0)) targetMob = null;
        if (targetNpc != null && (targetNpc.dead() || !s.npcs.actors.contains(targetNpc))) targetNpc = null;
        float ar = type.aggroRange;
        if (type.attitude == MobType.Attitude.HOSTILE && !targetPlayer && targetMob == null && targetNpc == null && (age + id) % 10 == 0
                && playerTargetable(s) && pd2 < ar * ar && Math.abs(p.y - body.y) < 8 && canSee(p.x, p.y + 1.5, p.z)) targetPlayer = true;
        if (targetPlayer && pd2 > ar * ar * 4) targetPlayer = false;
        // hostile creatures also go for NPCs nearby (like zombies and villagers)
        if (type.attitude == MobType.Attitude.HOSTILE && !targetPlayer && targetMob == null && targetNpc == null && (age + id) % 20 == 5) {
            double best = Math.min(ar, 12) * Math.min(ar, 12);
            for (var a : s.npcs.actors) {
                if (a.dead() || a.mode() != derpcraft.npc.Actor.Mode.AI) continue;
                double dx = a.x() - body.x, dz = a.z() - body.z, d2 = dx * dx + dz * dz;
                if (d2 < best && Math.abs(a.y() - body.y) < 4 && canSee(a.x(), a.y() + 1.5, a.z())) { best = d2; targetNpc = a; }
            }
        }
        if (targetNpc != null && (targetNpc.mode() != derpcraft.npc.Actor.Mode.AI)) targetNpc = null;

        float lookYaw = body.yaw, lookPitch = 0;
        boolean looking = false;
        if (fleeTicks > 0) {
            fleeTicks--;
            double ax = body.x - fleeX, az = body.z - fleeZ, al = Math.sqrt(ax * ax + az * az) + 1e-6;
            walkTowards(in, body.x + ax / al * 5, body.y, body.z + az / al * 5, Math.max(1, type.speed * 1.3f));
            lookYaw = body.yaw;
        } else if (walking) {
            double dx = walkX - body.x, dz = walkZ - body.z;
            if (dx * dx + dz * dz < 0.6 * 0.6) walking = false;
            else moveTo(in, walkX, walkY, walkZ, type.speed);
            lookYaw = body.yaw;
        } else if (targetPlayer || targetMob != null || targetNpc != null) {
            double tx, ty, tz;
            if (targetPlayer) { tx = p.x; ty = p.y; tz = p.z; }
            else if (targetMob != null) { tx = targetMob.body.x; ty = targetMob.body.y; tz = targetMob.body.z; }
            else { tx = targetNpc.x(); ty = targetNpc.y(); tz = targetNpc.z(); }
            double dx = tx - body.x, dz = tz - body.z, d2 = dx * dx + dz * dz;
            lookYaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
            double eye = body.y + type.height * 0.85, dy = ty + 1.4 - eye;
            lookPitch = (float) -Math.toDegrees(Math.atan2(dy, Math.sqrt(d2)));
            looking = true;
            if (type.projectile != null && d2 < type.shootRange * type.shootRange) {
                if (d2 > type.shootRange * type.shootRange * 0.36) moveTo(in, tx, ty, tz, type.speed);
                else body.yaw = turn(body.yaw, lookYaw, 30);
                if (attackCooldown == 0 && canSee(tx, ty + 1.4, tz)) {
                    attackCooldown = type.shootInterval;
                    swingTicks = -1;
                    double dist = Math.sqrt(d2 + dy * dy) + 1e-6;
                    ProjectileType pt = ProjectileType.get(type.projectile);
                    float g = pt == null ? 0.03f : pt.gravity;
                    // aim a little above to make up for gravity
                    double ticks = dist / Math.max(0.1, type.projectileSpeed);
                    double aimY = dy + 0.5 * g * ticks * ticks * 0.9;
                    double l = Math.sqrt(d2 + aimY * aimY) + 1e-6;
                    s.mobs.shoot(type.projectile, body.x - Math.sin(Math.toRadians(lookYaw)) * 0.4, eye - 0.1, body.z + Math.cos(Math.toRadians(lookYaw)) * 0.4,
                            dx / l, aimY / l, dz / l, type.projectileSpeed, false, this);
                }
            } else {
                double reach = type.reach;
                if (d2 > (reach * 0.6) * (reach * 0.6)) moveTo(in, tx, ty, tz, Math.max(type.speed, type.speed * 1.15f));
                else body.yaw = turn(body.yaw, lookYaw, 30);
                if (d2 < reach * reach && Math.abs(ty - body.y) < 2 && attackCooldown == 0) {
                    attackCooldown = type.attackCooldown;
                    swingTicks = -1;
                    if (targetPlayer) s.hurtPlayer(type.damage, "mob", body.x, body.z);
                    else if (targetMob != null) targetMob.hurt(type.damage, "mob", false, this, null, body.x, body.z);
                    else targetNpc.hurt(s.npcs, type.damage, null, false, this);
                    if (fireTicks > 0 && targetPlayer) s.stats.fireTicks = Math.max(s.stats.fireTicks, 80);
                }
            }
        } else {
            ItemStack held = s.inv.held();
            boolean tempted = type.temptItem != null && held != null && pd2 < 10 * 10 && playerTargetableOrCreative(s)
                    && held.item == derpcraft.item.Items.byName(type.temptItem);
            if (tempted) {
                if (pd2 > 2.2 * 2.2) moveTo(in, p.x, p.y, p.z, type.speed * 0.8f);
                lookYaw = (float) Math.toDegrees(Math.atan2(-pdx, pdz));
                looking = true;
            } else if (type.wander) {
                if (wanderWait > 0) wanderWait--;
                else {
                    double tx = wanderX - body.x, tz = wanderZ - body.z;
                    if (tx * tx + tz * tz < 0.5 || wanderWait < -160) {
                        double a = RND.nextDouble() * Math.PI * 2, rad = 3 + RND.nextDouble() * 7;
                        wanderX = body.x + Math.cos(a) * rad; wanderZ = body.z + Math.sin(a) * rad;
                        wanderWait = 60 + RND.nextInt(140);
                    } else {
                        moveTo(in, wanderX, body.y, wanderZ, type.speed * 0.55f);
                        wanderWait--;
                    }
                }
                lookYaw = body.yaw;
            }
            if (!tempted && type.lookAtPlayer && pd2 < 8 * 8 && in.forward == 0 && !s.spectator()) {
                lookYaw = (float) Math.toDegrees(Math.atan2(-pdx, pdz));
                lookPitch = (float) -Math.toDegrees(Math.atan2(p.y + 1.62 - (body.y + type.height * 0.85), Math.sqrt(pd2)));
                looking = true;
            }
        }
        headYaw = turn(headYaw, lookYaw, looking ? 12 : 20);
        if (Math.abs(wrap(headYaw - body.yaw)) > 60 && in.forward == 0) body.yaw = turn(body.yaw, headYaw, 8);
        pitch += (lookPitch - pitch) * 0.25f;
    }

    private boolean playerTargetableOrCreative(Session s) { return !s.stats.dead && !s.spectator(); }

    private boolean canSee(double tx, double ty, double tz) {
        double ex = body.x, ey = body.y + type.height * 0.85, ez = body.z;
        // every cell on the line counts (the first block hit used to decide: grass in front hid the wall behind it)
        return Raycast.clearLine(world, ex, ey, ez, tx, ty, tz);
    }

    // ================================================================================ movement
    private void moveTo(Player.Input in, double tx, double ty, double tz, float speed) {
        double dx = tx - body.x, dz = tz - body.z, d2 = dx * dx + dz * dz;
        pathAge++;
        if (d2 < 1.5 * 1.5 && Math.abs(ty - body.y) < 1.2 || type.height > 2.2) { path = null; walkTowards(in, tx, ty, tz, speed); return; }
        if (path != null && pathIdx < path.size()) {
            int[] c = path.get(pathIdx);
            double ox = c[0] + 0.5 - body.x, oz = c[2] + 0.5 - body.z;
            if (ox * ox + oz * oz > 9 || Math.abs(c[1] - body.y) > 3) { path = null; pathAge = 100; }
        }
        boolean moved = (tx - pathTX) * (tx - pathTX) + (tz - pathTZ) * (tz - pathTZ) > 2.5 * 2.5 || Math.abs(ty - pathTY) > 2;
        boolean want = path == null ? pathAge > 15 : moved && pathAge > 10 || pathAge > 60 || stuck > 12;
        if (want && d2 < 48 * 48 && (age + id) % 3 == 0) {
            pathAge = 0;
            pathTX = tx; pathTY = ty; pathTZ = tz;
            path = PathFinder.find(world, (int) Math.floor(body.x), (int) Math.floor(body.y + 0.2), (int) Math.floor(body.z),
                    (int) Math.floor(tx), (int) Math.floor(ty + 0.2), (int) Math.floor(tz), type.openDoors, 1200);
            pathIdx = 1;
            stuck = 0;
        }
        if (path != null) {
            pathIdx = PathFinder.reached(path, pathIdx, body.x, body.y, body.z);
            if (pathIdx < path.size()) {
                int[] c = path.get(pathIdx);
                walkTowards(in, c[0] + 0.5, c[1], c[2] + 0.5, speed);
                return;
            }
            path = null;
        }
        walkTowards(in, tx, ty, tz, speed);
    }

    private void walkTowards(Player.Input in, double tx, double ty, double tz, float speed) {
        double dx = tx - body.x, dz = tz - body.z;
        float want = (float) Math.toDegrees(Math.atan2(-dx, dz));
        body.yaw = turn(body.yaw, want, 20);
        float diff = Math.abs(wrap(want - body.yaw));
        in.forward = (diff > 60 ? 0.3f : 1f) * Math.min(1, speed);
        in.sprint = speed > 1.05f;
        if (body.collidedHorizontally) in.jump = true;
        if (type.openDoors) {
            double yr = Math.toRadians(body.yaw);
            int fx = (int) Math.floor(body.x - Math.sin(yr) * 0.7), fz = (int) Math.floor(body.z + Math.cos(yr) * 0.7), fy = (int) Math.floor(body.y + 0.01);
            for (int k = 0; k < 2; k++) {
                int ly = fy + k;
                if (world.getBlock(fx, ly, fz) instanceof FunctionalBlocks.DoorBlock door && !door.name.contains("iron")) {
                    int meta = world.getMeta(fx, ly, fz);
                    if ((meta & 8) != 0) { ly--; meta = world.getMeta(fx, ly, fz); }
                    if ((meta & 4) == 0) { door.setOpen(world, fx, ly, fz, true); openedDoors.add(new int[]{fx, ly, fz, 0}); }
                    break;
                }
            }
        }
        double moved = Math.abs(body.x - progX) + Math.abs(body.z - progZ);
        if (moved > 0.4) { progX = body.x; progZ = body.z; stuck = 0; } else stuck++;
    }

    private void closeDoorsBehind() {
        for (var it = openedDoors.iterator(); it.hasNext(); ) {
            int[] d = it.next();
            d[3]++;
            double dx = d[0] + 0.5 - body.x, dz = d[2] + 0.5 - body.z;
            if (d[3] > 20 && dx * dx + dz * dz > 4) {
                if (world.getBlock(d[0], d[1], d[2]) instanceof FunctionalBlocks.DoorBlock door) door.setOpen(world, d[0], d[1], d[2], false);
                it.remove();
            } else if (d[3] > 400) it.remove();
        }
    }

    private void animate() {
        double dx = body.x - body.px, dz = body.z - body.pz;
        float f = (float) Math.min(1, Math.sqrt(dx * dx + dz * dz) * 4);
        if (deathTime > 0) f = 0;
        limbAmount += (f - limbAmount) * 0.4f;
        limbSwing += limbAmount;
        float target = bodyYaw;
        if (dx * dx + dz * dz > 0.0025 * 0.0025) target = (float) Math.toDegrees(Math.atan2(dz, dx)) - 90;
        bodyYaw += wrap(target - bodyYaw) * 0.3f;
        float off = wrap(headYaw - bodyYaw);
        if (off < -60) bodyYaw = headYaw + 60;
        if (off > 60) bodyYaw = headYaw - 60;
    }

    static float turn(float from, float to, float max) { float d = wrap(to - from); return from + Math.max(-max, Math.min(max, d)); }

    static float wrap(float a) { a %= 360; if (a >= 180) a -= 360; if (a < -180) a += 360; return a; }

    // ================================================================================ damage
    /** Damage from anything. Returns true if it was hurt. */
    public boolean hurt(float amount, String cause, boolean byPlayer, Mob attacker, derpcraft.npc.Actor npcAttacker, double fromX, double fromZ) {
        if (dead || deathTime > 0 || amount <= 0) return false;
        if (invulnerable > 0 && !cause.equals("kill")) return false;
        Session s = Session.current;
        if (s != null && EventBus.has(Events.EntityDamage.class)) {
            var ev = EventBus.post(s, new Events.EntityDamage(this, null, amount, cause, byPlayer, attacker, npcAttacker == null ? null : npcAttacker.name));
            if (ev.isCancelled() || ev.amount <= 0) return false;
            amount = ev.amount;
        }
        health -= amount;
        hurtTime = 10;
        invulnerable = 10;
        derpcraft.game.Blood.body(body.x, body.y, body.z, type.height, amount, cause);
        if (!cause.equals("fire") && !cause.equals("lava") && !cause.equals("fall")) {
            double kx = body.x - fromX, kz = body.z - fromZ, l = Math.sqrt(kx * kx + kz * kz);
            float k = 0.4f * (1 - type.knockbackResistance);
            if (l > 1e-4 && k > 0) { body.mx += kx / l * k; body.mz += kz / l * k; if (body.onGround) body.my = 0.3 * (1 - type.knockbackResistance); }
        }
        final float amt = amount;
        if (type.onHurt != null && s != null) Session.safe(() -> type.onHurt.onHurt(s, this, amt, byPlayer));
        if (byPlayer && s != null) derpcraft.audio.Sfx.play(!s.player.onGround && s.player.my < 0 ? "entity/player/attack/crit" : "entity/player/attack/strong", 0.8f, 0.95f + world.random.nextFloat() * 0.1f);
        if (health > 0 && s != null) derpcraft.audio.Sfx.mob(s, type.id, "hurt", body.x, body.y + type.height / 2, body.z, 1f);
        if (health <= 0) { die(byPlayer); return true; }
        if (cause.equals("fire") || cause.equals("lava") || cause.equals("fall")) return true;
        if (type.attitude == MobType.Attitude.PASSIVE || type.fleeWhenHurt) { fleeTicks = 60; fleeX = fromX; fleeZ = fromZ; }
        else {
            angerTicks = 400;
            if (byPlayer) { targetPlayer = true; targetMob = null; targetNpc = null; }
            else if (attacker != null && attacker != this) { targetMob = attacker; targetPlayer = false; targetNpc = null; }
            else if (npcAttacker != null) { targetNpc = npcAttacker; targetPlayer = false; targetMob = null; }
        }
        return true;
    }

    private void die(boolean byPlayer) {
        if (deathTime > 0 || dead) return;
        health = 0;
        deathTime = 1;
        targetPlayer = false; targetMob = null; targetNpc = null;
        Session s = Session.current;
        if (s == null) return;
        derpcraft.audio.Sfx.mob(s, type.id, "death", body.x, body.y + type.height / 2, body.z, 1f);
        List<ItemStack> drops = new ArrayList<>();
        for (MobType.Drop d : type.drops) {
            if (RND.nextFloat() >= d.chance()) continue;
            var it = Items.byName(d.item());
            if (it == null) it = Items.byName("minecraft:" + d.item());
            if (it == null) continue;
            int n = d.min() + (d.max() > d.min() ? RND.nextInt(d.max() - d.min() + 1) : 0);
            if (n > 0) drops.add(new ItemStack(it, n));
        }
        int xp = byPlayer ? type.xp : 0;
        if (EventBus.has(Events.EntityDeath.class)) xp = EventBus.post(s, new Events.EntityDeath(this, null, byPlayer, drops, xp)).xp;
        if (fireTicks > 0) for (int i = 0; i < drops.size(); i++) {      // burning animals drop cooked meat
            ItemStack st = drops.get(i), cooked = derpcraft.item.Recipes.smelt(st.item);
            if (cooked != null && st.item.isFood()) drops.set(i, new ItemStack(cooked.item, st.count));
        }
        for (ItemStack st : drops) if (st.count > 0) world.dropItem(body.x, body.y + 0.5, body.z, st);
        if (xp > 0 && s.survival()) s.stats.addXp(xp);
        if (type.onDeath != null) Session.safe(() -> type.onDeath.onTick(s, this));
    }

    // ================================================================================ picking
    /** Distance along the ray to this mob's box, or -1. */
    public double rayHit(double ox, double oy, double oz, double dx, double dy, double dz, double max) {
        if (deathTime > 0) return -1;
        double r = type.width / 2 + 0.1;
        return derpcraft.util.RayBox.t(ox, oy, oz, dx, dy, dz, max, body.x - r, body.y, body.z - r, body.x + r, body.y + type.height, body.z + r);
    }

    // ================================================================================ Creature API
    @Override public int id() { return id; }
    @Override public String type() { return type.id; }
    @Override public double x() { return body.x; }
    @Override public double y() { return body.y; }
    @Override public double z() { return body.z; }
    @Override public float yaw() { return body.yaw; }
    @Override public void teleport(double x, double y, double z) { body.setPos(x, y, z); px = this.x = x; py = this.y = y; pz = this.z = z; path = null; }
    @Override public void push(double dx, double dy, double dz) { body.mx += dx; body.my += dy; body.mz += dz; }
    @Override public float health() { return health; }
    @Override public float maxHealth() { return type.maxHealth; }
    @Override public void setHealth(float h) { health = Math.min(type.maxHealth, h); if (health <= 0) die(false); }
    @Override public void hurt(float amount) { hurt(amount, "generic", false, null, null, body.x, body.z); }
    @Override public void kill() { die(false); }
    @Override public void remove() { dead = true; }
    @Override public boolean alive() { return !dead && deathTime == 0; }
    @Override public String customName() { return customName; }
    @Override public void setCustomName(String n) { customName = n == null || n.isBlank() ? null : n; }
    @Override public void attackPlayer() { targetPlayer = true; targetMob = null; targetNpc = null; angerTicks = 600; }
    @Override public void attack(Creature t) { if (t instanceof Mob m && m != this) { targetMob = m; targetPlayer = false; targetNpc = null; angerTicks = 600; } }
    @Override public void calm() { targetPlayer = false; targetMob = null; targetNpc = null; angerTicks = 0; fleeTicks = 0; }
    @Override public void walkTo(double x, double y, double z) { walking = true; walkX = x; walkY = y; walkZ = z; path = null; pathAge = 100; }
    @Override public DataTag data() { return data; }
    @Override public boolean persistent() { return persistent; }
    @Override public void setPersistent(boolean p) { persistent = p; }

    private double stepX, stepZ, stepAcc;

    public boolean hostile() { return type.attitude == MobType.Attitude.HOSTILE; }

    /** True while it is going for the player. */
    public boolean targetsPlayer() { return targetPlayer; }
}
