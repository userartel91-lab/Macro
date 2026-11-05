package derpcraft.world.be;

import derpcraft.item.Inventory;
import derpcraft.world.World;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Extra data attached to a block (chest items, furnace progress, sign text). Saved with the chunk as
 * key=value pairs, so it is easy to add new types from mods with {@link #register}.
 */
public abstract class BlockEntity {
    private static final Map<String, Supplier<BlockEntity>> TYPES = new HashMap<>();

    public int x, y, z;
    public World world;

    public static void register(String type, Supplier<BlockEntity> factory) { TYPES.put(type, factory); }

    public static BlockEntity create(String type) {
        Supplier<BlockEntity> f = TYPES.get(type);
        return f == null ? null : f.get();
    }

    static {
        register("chest", () -> new ContainerEntity("chest", 27));
        register("ender_chest", () -> new ContainerEntity("ender_chest", 0));
        register("dispenser", () -> new ContainerEntity("dispenser", 9));
        register("dropper", () -> new ContainerEntity("dropper", 9));
        register("hopper", () -> new ContainerEntity("hopper", 5));
        register("furnace", FurnaceEntity::new);
        register("sign", SignEntity::new);
        register("item_frame", ItemFrameEntity::new);
    }

    public abstract String type();

    public boolean ticks() { return false; }

    public void tick() {}

    /** True when the contents go into the dropped item instead of spilling (portable storage). */
    public boolean keepsContents() { return false; }

    /** Inventory for container GUIs, or null. */
    public Inventory inventory() { return null; }

    public abstract void save(Map<String, String> out);

    public abstract void load(Map<String, String> in);

    /** Marks the owning chunk as modified so it gets saved. */
    public void markDirty() {
        if (world != null) {
            var c = world.chunkAt(x, z);
            if (c != null) c.modified = true;
        }
    }
}
