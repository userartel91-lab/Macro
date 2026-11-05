package derpcraft.entity;

import derpcraft.api.Creature;
import derpcraft.api.GameApi;
import derpcraft.api.Model;

import java.util.*;

/** A kind of creature (registered by mods with {@link derpcraft.api.MobBuilder}). */
public final class MobType {
    public enum Attitude { HOSTILE, NEUTRAL, PASSIVE }

    public record Drop(String item, int min, int max, float chance) {}

    public interface TickHook { void onTick(GameApi game, Creature c); }
    public interface HurtHook { void onHurt(GameApi game, Creature c, float amount, boolean byPlayer); }
    public interface InteractHook { boolean onInteract(GameApi game, Creature c); }

    public static final Map<String, MobType> REGISTRY = new LinkedHashMap<>();

    public final String id, modId;
    public String displayName;
    public Model model = Model.humanoid();
    /** Texture path inside resource packs / mod jars. */
    public String texture;
    public float width = 0.6f, height = 1.8f, scale = 1;
    public float maxHealth = 20, damage = 3, speed = 0.8f, reach = 2, aggroRange = 16, knockbackResistance;
    public int attackCooldown = 20;
    public Attitude attitude = Attitude.PASSIVE;
    public boolean wander = true, lookAtPlayer = true, fleeWhenHurt, burnsInSun, swims = true, openDoors, fireproof;
    public final List<Drop> drops = new ArrayList<>();
    public int xp = 1;
    public String temptItem;
    public int eggColor = -1;
    // ranged attack
    public String projectile;
    public float projectileSpeed = 1.2f;
    public int shootInterval = 40;
    public float shootRange = 14;
    // natural spawning
    public boolean spawns;
    public String spawnLight = "any";       // "dark", "light", "any"
    public Set<String> spawnOn = new HashSet<>();
    public int spawnMin = 1, spawnMax = 1, spawnCap = 8;
    public float spawnChance = 0.1f;
    public boolean spawnUnderground;
    // hooks
    public TickHook onTick;
    public HurtHook onHurt;
    public TickHook onDeath;
    public InteractHook onInteract;

    public MobType(String modId, String name) {
        this.modId = modId;
        this.id = name.contains(":") ? name : modId + ":" + name;
        String n = this.id.substring(this.id.indexOf(':') + 1);
        displayName = Character.toUpperCase(n.charAt(0)) + n.substring(1).replace('_', ' ');
        texture = "assets/" + this.id.substring(0, this.id.indexOf(':')) + "/textures/entity/" + n + ".png";
    }

    public static MobType get(String id) {
        if (id == null) return null;
        MobType t = REGISTRY.get(id);
        if (t == null && !id.contains(":")) for (MobType m : REGISTRY.values()) if (m.id.endsWith(":" + id)) return m;
        return t;
    }

    /** Spawn egg items for every type that wants one (after mods registered their items). */
    public static void createEggs() {
        for (MobType t : REGISTRY.values()) {
            if (t.eggColor < 0) continue;
            String name = t.id + "_spawn_egg";
            if (derpcraft.item.Items.byName(name) != null) continue;
            derpcraft.item.ModItem it = new derpcraft.item.ModItem(name);
            it.displayName = "Spawn " + t.displayName;
            it.texture = "items/spawn_egg";
            it.tint = t.eggColor & 0xFFFFFF;
            it.tab = "misc";
            it.consumeOnUse = true;
            final MobType type = t;
            it.onUse = (game, stack, block) -> {
                if (block == null) return false;
                derpcraft.game.Session s = (derpcraft.game.Session) game;
                int f = s.target == null ? 1 : s.target.face;
                double x = block.x() + 0.5 + derpcraft.block.Dir.DX[f], y = block.y() + derpcraft.block.Dir.DY[f], z = block.z() + 0.5 + derpcraft.block.Dir.DZ[f];
                Mob m = s.mobs.spawn(type, x, y, z, s.player.yaw + 180, false);
                if (m != null) m.persistent = true;
                return m != null;
            };
            derpcraft.item.Items.register(it);
        }
    }
}
