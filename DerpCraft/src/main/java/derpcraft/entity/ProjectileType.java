package derpcraft.entity;

import derpcraft.api.Events;
import derpcraft.api.GameApi;
import derpcraft.api.Model;

import java.util.LinkedHashMap;
import java.util.Map;

/** A kind of projectile (registered by mods with {@link derpcraft.api.ProjectileBuilder}). */
public final class ProjectileType {
    public interface FlyHook { void onTick(GameApi game, double x, double y, double z, int age); }

    public static final Map<String, ProjectileType> REGISTRY = new LinkedHashMap<>();

    public final String id, modId;
    public String item;
    public Model model;
    public String texture;
    public float size = 0.25f, gravity = 0.03f, drag = 0.99f, damage = 0, knockback = 0.4f;
    public int fireTicks;
    public float explode;
    public boolean explodeBreaks = true;
    public float bounce = -1;
    public int fuse = 60;
    public String dropItem;
    public boolean spin;
    /** > 0: after landing it stays on the ground this many ticks (burning), then burns out. */
    public int lingerTicks;
    /** Sets the place where it lands on fire (needs lingerTicks > 0: the fire goes out with it). */
    public boolean igniteBlocks;
    /** Sticks in the block it hits (arrows), at any side, and is drawn pointing along its flight; with lingerTicks it vanishes after that time. */
    public boolean sticks;
    /** Signal flare: pours out a tall curtain of red smoke while it flies and lies there. */
    public boolean flare;
    /** Lit fuse: sparks and smoke while it flies and lies there (dynamite, primed TNT). */
    public boolean fused;
    /** Drawn as an upright block of its item (primed TNT) instead of a flat sprite; flashes white. */
    public boolean blockModel;
    /** Passes through creatures and the player (primed TNT only falls and waits for the fuse). */
    public boolean ignoreEntities;
    /** The model and the blast are this far above the projectile's position (a block resting on its centre point). */
    public float lift;
    public Events.Handler<Events.ProjectileHit> onHit;
    public FlyHook onTick;

    public ProjectileType(String modId, String name) {
        this.modId = modId;
        this.id = name.contains(":") ? name : modId + ":" + name;
    }

    /** The torch the player throws with Alt + left click: hurts and sets creatures on fire, lights the block it lands on, burns out after a while. */
    public static final ProjectileType THROWN_TORCH = thrownTorch();

    private static ProjectileType thrownTorch() {
        ProjectileType t = new ProjectileType("derpcraft", "thrown_torch");
        t.item = "torch";
        t.size = 0.25f; t.gravity = 0.04f; t.drag = 0.99f;
        t.damage = 2; t.fireTicks = 100; t.knockback = 0.2f;
        t.spin = true;
        t.lingerTicks = 140; t.igniteBlocks = true;
        return t;
    }

    /** Redstone torch thrown with Alt + left click: a signal flare, burns nothing, shows a big red smoke curtain for 20 seconds. */
    public static final ProjectileType THROWN_FLARE = flare();

    private static ProjectileType flare() {
        ProjectileType t = new ProjectileType("derpcraft", "signal_flare");
        t.item = "redstone_torch";
        t.size = 0.25f; t.gravity = 0.04f; t.drag = 0.99f;
        t.spin = true; t.flare = true; t.lingerTicks = 400; t.knockback = 0.1f;
        return t;
    }

    /** Stick of dynamite (Alt + left click): bounces about with a lit fuse and explodes when the fuse is out or when it hits a creature. */
    public static final ProjectileType DYNAMITE = dynamite();

    private static ProjectileType dynamite() {
        ProjectileType t = new ProjectileType("derpcraft", "dynamite");
        t.item = "dynamite";
        t.size = 0.25f; t.gravity = 0.04f; t.drag = 0.99f;
        t.spin = true; t.bounce = 0.35f; t.fuse = 50; t.fused = true;
        t.explode = 2.6f; t.knockback = 0.2f;
        return t;
    }

    /** A lit TNT block: hops up, falls, and explodes after 4 seconds (power 4 like Minecraft). */
    public static final ProjectileType TNT_PRIMED = primedTnt();

    private static ProjectileType primedTnt() {
        ProjectileType t = new ProjectileType("derpcraft", "primed_tnt");
        t.item = "tnt";
        t.size = 0.49f; t.gravity = 0.04f; t.drag = 0.98f;
        t.bounce = 0f; t.fuse = 80; t.fused = true; t.blockModel = true; t.ignoreEntities = true; t.lift = 0.44f;
        t.explode = 4f;
        return t;
    }

    /** Arrow shot from a bow: sticks in what it hits for 30 seconds. */
    public static final ProjectileType ARROW = arrow("arrow", "arrow", false);
    /** Fire arrow: sets creatures on fire, lights the place where it sticks and burns out after a few seconds. */
    public static final ProjectileType FIRE_ARROW = arrow("fire_arrow", "fire_arrow", true);

    private static ProjectileType arrow(String name, String item, boolean fire) {
        ProjectileType t = new ProjectileType("derpcraft", name);
        t.item = item;
        t.size = 0.2f; t.gravity = 0.05f; t.drag = 0.99f;
        t.damage = 6; t.knockback = 0.5f;
        t.sticks = true;
        t.lingerTicks = fire ? 120 : 600;
        if (fire) { t.fireTicks = 100; t.igniteBlocks = true; }
        return t;
    }

    public static ProjectileType get(String id) {
        if (id == null) return null;
        if (THROWN_TORCH.id.equals(id)) return THROWN_TORCH;
        if (THROWN_FLARE.id.equals(id)) return THROWN_FLARE;
        if (DYNAMITE.id.equals(id)) return DYNAMITE;
        if (TNT_PRIMED.id.equals(id)) return TNT_PRIMED;
        if (ARROW.id.equals(id)) return ARROW;
        if (FIRE_ARROW.id.equals(id)) return FIRE_ARROW;
        ProjectileType t = REGISTRY.get(id);
        if (t == null && !id.contains(":")) for (ProjectileType p : REGISTRY.values()) if (p.id.endsWith(":" + id)) return p;
        return t;
    }

    /** The item drawn for it. */
    public derpcraft.item.Item spriteItem() {
        derpcraft.item.Item it = item != null ? derpcraft.item.Items.byName(item) : derpcraft.item.Items.byName(id);
        if (it == null) it = derpcraft.item.Items.byName("snowball");
        if (it == null) it = derpcraft.item.Items.byName("cobblestone");
        return it;
    }
}
