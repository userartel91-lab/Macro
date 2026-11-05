package derpcraft.api;

import derpcraft.block.Block;

/** Event types passed to mod handlers. */
public final class Events {
    private Events() {}

    /** Base for events a handler may cancel. */
    public static class Cancellable {
        private boolean cancelled;
        public void cancel() { cancelled = true; }
        public boolean isCancelled() { return cancelled; }
    }

    /** Fired before the player places a block. Cancel to prevent it, or change 'block'/'meta'. */
    public static final class BlockPlace extends Cancellable {
        public final int x, y, z;
        public Block block;
        public int meta;
        public BlockPlace(int x, int y, int z, Block block, int meta) { this.x = x; this.y = y; this.z = z; this.block = block; this.meta = meta; }
    }

    /** Fired before the player breaks a block. Cancel to prevent it. */
    public static final class BlockBreak extends Cancellable {
        public final int x, y, z;
        public final Block block;
        public BlockBreak(int x, int y, int z, Block block) { this.x = x; this.y = y; this.z = z; this.block = block; }
    }

    /** Fired when the player right-clicks a block, before the block reacts. Cancel to block the interaction. */
    public static final class BlockInteract extends Cancellable {
        public final int x, y, z;
        public final Block block;
        public final derpcraft.item.ItemStack held;
        public BlockInteract(int x, int y, int z, Block block, derpcraft.item.ItemStack held) { this.x = x; this.y = y; this.z = z; this.block = block; this.held = held; }
    }

    /** Fired when the player uses (right-clicks with) an item. Cancel to stop the use. */
    public static final class ItemUse extends Cancellable {
        public final derpcraft.item.ItemStack stack;
        /** Targeted block or null. */
        public final BlockContext target;
        public ItemUse(derpcraft.item.ItemStack stack, BlockContext target) { this.stack = stack; this.target = target; }
    }

    /** Fired when the player sends a chat line that is not a command. Cancel to hide it. */
    public static final class Chat extends Cancellable {
        public String message;
        public Chat(String message) { this.message = message; }
    }

    public interface TickHandler { void onTick(GameApi game); }
    public interface PlaceHandler { void onPlace(GameApi game, BlockPlace event); }
    public interface BreakHandler { void onBreak(GameApi game, BlockBreak event); }
    public interface ChatHandler { void onChat(GameApi game, Chat event); }
    public interface InteractHandler { void onInteract(GameApi game, BlockInteract event); }
    public interface ItemUseHandler { void onItemUse(GameApi game, ItemUse event); }
    public interface WorldHandler { void onWorld(GameApi game); }
    public interface KeyHandler { void onKey(GameApi game); }
    /** Called every rendered frame before the camera moves ('seconds' = frame time). Use it for smooth camera moves. */
    public interface FrameHandler { void onFrame(GameApi game, float seconds); }
    /** Called when a camera path finishes playing (not when stopped by hand or looping). */
    public interface CameraPathHandler { void onPathEnd(GameApi game); }
    /** Called when an NPC scene finishes playing (not when stopped or looping). */
    public interface SceneHandler { void onSceneEnd(GameApi game); }
    /** Draw extra HUD elements; coordinates are GUI pixels (origin top-left). */
    public interface HudHandler { void onHud(GameApi game, Hud hud); }

    /** Simple HUD drawing helpers for mods. */
    public interface Hud {
        int width();
        int height();
        void text(String s, int x, int y, int argb);
        void rect(int x, int y, int w, int h, int argb);
        void blockIcon(Block b, int x, int y);
    }

    // ================================================================== 1.7: typed events (ModContext.on)
    /** Handler for {@link ModContext#on}. */
    public interface Handler<E> { void handle(GameApi game, E event); }

    /** The player is about to take damage. Change 'amount' or cancel. Causes: fall, lava, fire, drown, starve, cactus, void, suffocate, npc, mob, explosion, projectile, or a mod's own. */
    public static final class PlayerDamage extends Cancellable {
        public float amount;
        public final String cause;
        public PlayerDamage(float amount, String cause) { this.amount = amount; this.cause = cause; }
    }

    /** The player died. 'message' is shown on the death screen. */
    public static final class PlayerDeath {
        public final String cause;
        public String message;
        public PlayerDeath(String cause, String message) { this.cause = cause; this.message = message; }
    }

    /** A creature or an NPC is about to take damage. Exactly one of creature / npc is set. */
    public static final class EntityDamage extends Cancellable {
        public final Creature creature;
        public final String npc;
        public float amount;
        public final String cause;
        /** Who hit: the player, a creature, an NPC name, or nobody. */
        public final boolean byPlayer;
        public final Creature attacker;
        public final String attackerNpc;
        public EntityDamage(Creature c, String npc, float amount, String cause, boolean byPlayer, Creature attacker, String attackerNpc) {
            this.creature = c; this.npc = npc; this.amount = amount; this.cause = cause; this.byPlayer = byPlayer; this.attacker = attacker; this.attackerNpc = attackerNpc;
        }
    }

    /** A creature or an NPC died. For creatures 'drops' and 'xp' can be changed. */
    public static final class EntityDeath {
        public final Creature creature;
        public final String npc;
        public final boolean byPlayer;
        public final java.util.List<derpcraft.item.ItemStack> drops;
        public int xp;
        public EntityDeath(Creature c, String npc, boolean byPlayer, java.util.List<derpcraft.item.ItemStack> drops, int xp) {
            this.creature = c; this.npc = npc; this.byPlayer = byPlayer; this.drops = drops; this.xp = xp;
        }
    }

    /** A creature is about to appear ('natural' = spawned by the world, not an egg / command / mod). Cancel to stop it. */
    public static final class CreatureSpawn extends Cancellable {
        public final Creature creature;
        public final boolean natural;
        public CreatureSpawn(Creature c, boolean natural) { this.creature = c; this.natural = natural; }
    }

    /** An explosion. Cancel it, change 'power', or edit the list of blocks it destroys ({x, y, z} each). */
    public static final class Explosion extends Cancellable {
        public final double x, y, z;
        public float power;
        public final java.util.List<int[]> blocks;
        public boolean breakBlocks;
        public Explosion(double x, double y, double z, float power, java.util.List<int[]> blocks, boolean breakBlocks) {
            this.x = x; this.y = y; this.z = z; this.power = power; this.blocks = blocks; this.breakBlocks = breakBlocks;
        }
    }

    /** A projectile hit a block (blockX/Y/Z set) or a creature / the player / an NPC. Cancel to let it fly on (or for no damage). */
    public static final class ProjectileHit extends Cancellable {
        public final String projectile;
        public final double x, y, z;
        public final int blockX, blockY, blockZ;
        public final derpcraft.block.Block block;
        public final Creature creature;
        public final String npc;
        public final boolean hitPlayer;
        public ProjectileHit(String projectile, double x, double y, double z, int bx, int by, int bz, derpcraft.block.Block block, Creature creature, String npc, boolean hitPlayer) {
            this.projectile = projectile; this.x = x; this.y = y; this.z = z; this.blockX = bx; this.blockY = by; this.blockZ = bz; this.block = block;
            this.creature = creature; this.npc = npc; this.hitPlayer = hitPlayer;
        }
    }

    /** A key was pressed while playing (no screen open). Cancel to stop the game from reacting. Key codes are GLFW_KEY_*. */
    public static final class KeyPress extends Cancellable {
        public final int key, mods;
        public KeyPress(int key, int mods) { this.key = key; this.mods = mods; }
    }

    /** A mouse button was pressed while playing (0 left, 1 right, 2 middle). Cancel to stop the game from reacting. */
    public static final class MouseClick extends Cancellable {
        public final int button;
        public MouseClick(int button) { this.button = button; }
    }

    /** A screen is about to open ('name' is its class name, e.g. "Inventory", "NpcHub"; null screen = closing). Cancel to keep the current one. */
    public static final class ScreenOpen extends Cancellable {
        public final String name;
        public final Object screen;
        public ScreenOpen(String name, Object screen) { this.name = name; this.screen = screen; }
    }

    /** The player is picking up a dropped item. Cancel to leave it on the ground. */
    public static final class ItemPickup extends Cancellable {
        public final derpcraft.item.ItemStack stack;
        public ItemPickup(derpcraft.item.ItemStack stack) { this.stack = stack; }
    }

    /** A command is about to run ("/name args"). Cancel to stop it (yours or the game's). */
    public static final class CommandRun extends Cancellable {
        public final String name;
        public final String[] args;
        public CommandRun(String name, String[] args) { this.name = name; this.args = args; }
    }

    /** The player respawned after dying. */
    public static final class PlayerRespawn {}

    /** The world is being saved and closed (save your own data here). */
    public static final class WorldClose {}
}
