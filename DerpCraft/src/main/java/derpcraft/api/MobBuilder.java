package derpcraft.api;

import derpcraft.entity.MobType;

/**
 * Builds a creature type. Call from {@link DerpMod#init}.
 *
 * <pre>
 * ctx.registerMob("ghoul").model(Model.humanoid().armsForward()).texture("ghoul")
 *    .hostile().health(20).damage(3).speed(0.9f).burnsInSun()
 *    .drops("rotten_flesh", 0, 2).xp(5)
 *    .spawnsNaturally("dark", 1, 3, "grass", "dirt", "sand", "stone")
 *    .spawnEgg(0x3a5a3a)
 *    .register();
 * </pre>
 * The texture is assets/&lt;modid&gt;/textures/entity/&lt;name&gt;.png in the mod jar (any Minecraft entity texture
 * fits the matching ready-made model: a player skin for humanoid(), pig.png for quadruped(6), cow.png for quadruped(12)).
 */
public final class MobBuilder {
    private final MobType t;

    MobBuilder(String modId, String name) { t = new MobType(modId, name); }

    public MobBuilder displayName(String name) { t.displayName = name; return this; }
    /** The model (see {@link Model}); default {@link Model#humanoid()}. */
    public MobBuilder model(Model m) { t.model = m; return this; }
    /** Texture "name" = assets/&lt;modid&gt;/textures/entity/name.png, "othermod:name" or a full path "assets/minecraft/textures/entity/pig/pig.png". */
    public MobBuilder texture(String tex) {
        if (tex.startsWith("assets/")) t.texture = tex;
        else if (tex.contains(":")) t.texture = "assets/" + tex.substring(0, tex.indexOf(':')) + "/textures/entity/" + tex.substring(tex.indexOf(':') + 1) + ".png";
        else t.texture = "assets/" + t.modId + "/textures/entity/" + tex + ".png";
        return this;
    }
    /** Collision box in blocks (player 0.6 x 1.8, pig 0.9 x 0.9). */
    public MobBuilder size(float width, float height) { t.width = width; t.height = height; return this; }
    /** Draw the model bigger / smaller (the collision box is not changed). */
    public MobBuilder scale(float s) { t.scale = s; return this; }

    public MobBuilder health(float hp) { t.maxHealth = hp; return this; }
    /** Damage per hit (hearts x 2). */
    public MobBuilder damage(float d) { t.damage = d; return this; }
    /** Walking speed: 1 = player walking, above 1 runs. */
    public MobBuilder speed(float s) { t.speed = s; return this; }
    /** Ticks between attacks. */
    public MobBuilder attackCooldown(int ticks) { t.attackCooldown = ticks; return this; }
    public MobBuilder reach(float blocks) { t.reach = blocks; return this; }
    public MobBuilder knockbackResistance(float r) { t.knockbackResistance = r; return this; }

    /** Attacks the player (survival) within 'aggroRange'. */
    public MobBuilder hostile() { t.attitude = MobType.Attitude.HOSTILE; return this; }
    /** Fights back when hit. */
    public MobBuilder neutral() { t.attitude = MobType.Attitude.NEUTRAL; return this; }
    /** Runs away when hit (default). */
    public MobBuilder passive() { t.attitude = MobType.Attitude.PASSIVE; return this; }
    public MobBuilder aggroRange(float blocks) { t.aggroRange = blocks; return this; }
    /** Runs away when hurt even if hostile / neutral. */
    public MobBuilder fleesWhenHurt() { t.fleeWhenHurt = true; return this; }
    public MobBuilder noWander() { t.wander = false; return this; }
    public MobBuilder noLookAtPlayer() { t.lookAtPlayer = false; return this; }
    /** Follows a player holding this item (like animals and wheat). */
    public MobBuilder temptedBy(String item) { t.temptItem = item; return this; }
    /** Catches fire in daylight. */
    public MobBuilder burnsInSun() { t.burnsInSun = true; return this; }
    public MobBuilder fireproof() { t.fireproof = true; return this; }
    public MobBuilder opensDoors() { t.openDoors = true; return this; }
    /** Sinks instead of swimming. */
    public MobBuilder sinks() { t.swims = false; return this; }

    /** Shoots a projectile (see {@link ModContext#registerProjectile}) at its target instead of hitting: every 'interval' ticks, within 'range'. */
    public MobBuilder shoots(String projectile, float speed, int interval, float range) {
        t.projectile = projectile; t.projectileSpeed = speed; t.shootInterval = interval; t.shootRange = range;
        return this;
    }

    /** Drops min..max of an item when killed. */
    public MobBuilder drops(String item, int min, int max) { t.drops.add(new MobType.Drop(item, min, max, 1)); return this; }
    /** Drops with a chance (0..1). */
    public MobBuilder drops(String item, int min, int max, float chance) { t.drops.add(new MobType.Drop(item, min, max, chance)); return this; }
    /** Experience for the player that kills it. */
    public MobBuilder xp(int xp) { t.xp = xp; return this; }

    /**
     * Spawns by itself near the player: light "dark" (night / caves), "light" (day) or "any", group size, on which
     * blocks ("grass", "sand", ... none = any solid block). Spawns on the surface; add {@link #spawnsUnderground()} for caves.
     */
    public MobBuilder spawnsNaturally(String light, int minGroup, int maxGroup, String... onBlocks) {
        t.spawns = true; t.spawnLight = light; t.spawnMin = Math.max(1, minGroup); t.spawnMax = Math.max(t.spawnMin, maxGroup);
        for (String b : onBlocks) t.spawnOn.add(b.contains(":") ? b : "minecraft:" + b);
        return this;
    }
    /** How many of this type may be around the player at once (default 8) and how often a spawn is tried (0..1 per second, default 0.1). */
    public MobBuilder spawnLimit(int cap, float chancePerSecond) { t.spawnCap = cap; t.spawnChance = chancePerSecond; return this; }
    public MobBuilder spawnsUnderground() { t.spawnUnderground = true; return this; }

    /** Adds a spawn egg item "&lt;id&gt;_spawn_egg" with this colour. */
    public MobBuilder spawnEgg(int rgb) { t.eggColor = rgb & 0xFFFFFF; return this; }

    /** Every tick while alive. */
    public MobBuilder onTick(MobType.TickHook h) { t.onTick = h; return this; }
    /** After taking damage. */
    public MobBuilder onHurt(MobType.HurtHook h) { t.onHurt = h; return this; }
    /** When it dies. */
    public MobBuilder onDeath(MobType.TickHook h) { t.onDeath = h; return this; }
    /** Right click by the player; return true when handled. */
    public MobBuilder onInteract(MobType.InteractHook h) { t.onInteract = h; return this; }

    /** Registers the type and returns its id. */
    public String register() {
        MobType.REGISTRY.put(t.id, t);
        return t.id;
    }
}
