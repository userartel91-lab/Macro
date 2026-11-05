package derpcraft.api;

import derpcraft.block.Block;

/** What mods can do with the running game. All methods must be called from the game thread (event handlers are). */
public interface GameApi {
    /** True while a world is open. */
    boolean inWorld();

    Block getBlock(int x, int y, int z);

    int getMeta(int x, int y, int z);

    /** Places a block (with lighting/re-render). Returns false if the chunk is not loaded. */
    boolean setBlock(int x, int y, int z, Block block);

    boolean setBlock(int x, int y, int z, Block block, int meta);

    /** Fills a box (inclusive corners) quickly; returns the number of blocks changed. Undoable with //undo. */
    int fill(int x1, int y1, int z1, int x2, int y2, int z2, Block block);

    /** Finds a block by name ("stone", "minecraft:stone", "mymod:ruby_block"), or null. */
    Block block(String name);

    double playerX();
    double playerY();
    double playerZ();
    float playerYaw();
    float playerPitch();

    void teleport(double x, double y, double z);

    void setRotation(float yaw, float pitch);

    /** World time in ticks (0 = sunrise, 6000 = noon, 18000 = midnight). */
    long time();

    void setTime(long ticks);

    /** Prints a message in chat (supports §-colour codes). */
    void chat(String message);

    /** Runs a chat command as if the player typed it (without the leading slash). */
    void runCommand(String command);

    // ------------------------------------------------------------------ since 1.3
    /** The film camera: free camera, orbit, paths, lens, focus and lens effects. */
    CameraApi camera();

    /** Shadows, ray tracing, coloured light and colour grading. */
    GraphicsApi graphics();

    /** 0 = first person, 1 = third person back, 2 = third person front (F5). */
    int view();
    void setView(int view);

    /** F1: hide the HUD and hand. */
    boolean hudHidden();
    void setHudHidden(boolean hidden);

    /** "survival", "creative" or "spectator". */
    String gameMode();
    void setGameMode(String mode);

    /** Drops an item entity ("diamond", "minecraft:stone", "mymod:ruby"); false if the item doesn't exist. */
    boolean dropItem(double x, double y, double z, String item, int count);

    /** Item physics (items lie flat) and click-to-pick-up from the Gameplay options. */
    void setItemPhysics(boolean on);
    void setClickPickup(boolean on);

    /** Swings the player's arm (third person / first person animation). */
    void swingArm();

    /** Saves a screenshot like F2 (with HUD unless hidden). */
    void screenshot();

    /** NPCs, recorded actors and scenes (F10 NPC Hub). */
    NpcApi npcs();

    // ------------------------------------------------------------------ since 1.5: blocks, items, menus, data
    /** Runs a task on the game thread after 'delayTicks' game ticks (20 per second). */
    void schedule(int delayTicks, Runnable task);

    /** Saved data of this world for a mod (use your mod id as key); written when the world closes. */
    DataTag worldData(String key);

    /** A block as a {@link BlockContext} (data, inventory, text, meta...). */
    BlockContext blockAt(int x, int y, int z);

    /** Saved data of a mod block, or null when the block has none. */
    DataTag blockData(int x, int y, int z);

    /** Slots of any container block (chests, furnaces, hoppers, mod storage and machines), or null. */
    InventoryApi inventoryAt(int x, int y, int z);

    /** A new free-standing inventory (for menus); save it with {@code inv.save()} in a {@link DataTag}. */
    InventoryApi createInventory(int size);

    /** The player's 36 main slots (0-8 = hotbar) plus armour. */
    InventoryApi playerInventory();

    /** Opens a custom container screen. */
    void openMenu(Menu menu);

    derpcraft.item.ItemStack heldItem();
    void setHeldItem(derpcraft.item.ItemStack stack);

    /** Puts items into the player's inventory, dropping what doesn't fit. Returns true if all fit. */
    boolean giveItem(derpcraft.item.ItemStack stack);

    /** A new stack of an item by name ("diamond", "mymod:ruby"), or null if unknown. */
    derpcraft.item.ItemStack createItem(String item, int count);

    boolean playerSneaking();

    /** Adds to the player's velocity (blocks per tick): jump pads, trampolines, knockback. */
    void pushPlayer(double dx, double dy, double dz);

    // ------------------------------------------------------------------ since 1.7: creatures, projectiles, explosions
    /** All creatures in the loaded world. */
    java.util.List<Creature> creatures();

    /** Creatures within 'radius' blocks. */
    java.util.List<Creature> creaturesNear(double x, double y, double z, double radius);

    /** Spawns a creature ("modid:name"); null if the type is unknown or a mod cancelled it. */
    Creature spawnCreature(String type, double x, double y, double z);

    /** The creature under the crosshair, or null. */
    Creature targetedCreature();

    /** Turns natural creature spawning in this world on / off. */
    void setCreatureSpawning(boolean on);

    /** Launches a projectile ("modid:name") from a point in a direction. */
    void shoot(String projectile, double x, double y, double z, double dx, double dy, double dz, float speed);

    /** Launches a projectile from the player's eyes where they look. */
    void shootFromPlayer(String projectile, float speed);

    /** An explosion (TNT is power 4). */
    void explode(double x, double y, double z, float power, boolean breakBlocks);

    /** Hurts the player (survival) with a cause name (shown in events). */
    void hurtPlayer(float amount, String cause);

    float playerHealth();
    void setPlayerHealth(float health);

    /** Daytime (sun up). */
    boolean isDay();

    /** Light level 0..15 at a block (the brighter of sky and block light, sky dimmed at night). */
    int lightAt(int x, int y, int z);
}
