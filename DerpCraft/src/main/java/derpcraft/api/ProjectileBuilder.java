package derpcraft.api;

import derpcraft.entity.ProjectileType;

/**
 * Builds a projectile type: something thrown or shot that flies, falls and hits.
 *
 * <pre>
 * ctx.registerProjectile("bomb").item("creatures:bomb").gravity(0.04f).explodes(3, true).register();
 * ctx.registerProjectile("pebble").item("cobblestone").size(0.15f).damage(4)
 *    .onHit((game, hit) -> { if (hit.creature != null) hit.creature.push(0, 0.3, 0); })
 *    .register();
 * // throw it with an item: ctx.item("bomb").throwsProjectile("creatures:bomb", 1.0f)...
 * </pre>
 */
public final class ProjectileBuilder {
    private final ProjectileType t;

    ProjectileBuilder(String modId, String name) { t = new ProjectileType(modId, name); }

    /** Looks like this item (flat sprite, like snowballs and eggs). Default: the item with the same id, or a snowball. */
    public ProjectileBuilder item(String item) { t.item = item; return this; }
    /** Looks like a 3D model instead (texture as for creatures). */
    public ProjectileBuilder model(Model m, String texture) {
        t.model = m;
        t.texture = texture.startsWith("assets/") ? texture : texture.contains(":")
                ? "assets/" + texture.substring(0, texture.indexOf(':')) + "/textures/entity/" + texture.substring(texture.indexOf(':') + 1) + ".png"
                : "assets/" + t.modId + "/textures/entity/" + texture + ".png";
        return this;
    }
    /** Size of its hit box and of the sprite (blocks, default 0.25). */
    public ProjectileBuilder size(float s) { t.size = s; return this; }
    /** Pull down per tick (default 0.03 like snowballs; arrows 0.05; 0 = flies straight). */
    public ProjectileBuilder gravity(float g) { t.gravity = g; return this; }
    /** Speed kept per tick (default 0.99). */
    public ProjectileBuilder drag(float d) { t.drag = d; return this; }
    public ProjectileBuilder damage(float d) { t.damage = d; return this; }
    public ProjectileBuilder knockback(float k) { t.knockback = k; return this; }
    /** Sets what it hits on fire for some ticks. */
    public ProjectileBuilder ignites(int ticks) { t.fireTicks = ticks; return this; }
    /** Explodes on impact. */
    public ProjectileBuilder explodes(float power, boolean breakBlocks) { t.explode = power; t.explodeBreaks = breakBlocks; return this; }
    /** Bounces off blocks (factor 0..1) instead of stopping; explodes / ends after 'fuse' ticks. */
    public ProjectileBuilder bounces(float factor, int fuse) { t.bounce = factor; t.fuse = fuse; return this; }
    /** Drops this item where it lands (arrows you can pick up). */
    public ProjectileBuilder dropsOnLand(String item) { t.dropItem = item; return this; }
    /** Spins while flying. */
    public ProjectileBuilder spins() { t.spin = true; return this; }
    /** Called on every hit (block, creature, NPC or player). Cancel the event to fly on. */
    public ProjectileBuilder onHit(Events.Handler<Events.ProjectileHit> h) { t.onHit = h; return this; }
    /** Every tick while flying. */
    public ProjectileBuilder onTick(ProjectileType.FlyHook h) { t.onTick = h; return this; }

    public String register() {
        ProjectileType.REGISTRY.put(t.id, t);
        return t.id;
    }
}
