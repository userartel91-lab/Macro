package derpcraft.world.be;

import derpcraft.item.Inventory;
import derpcraft.item.ItemStack;

import java.util.Map;

/** What an item frame shows: one item and its turn (0..7, 45 degrees each). */
public final class ItemFrameEntity extends BlockEntity {
    public final Inventory items = new Inventory(1);
    public int rotation;

    public ItemFrameEntity() { items.onChange = this::markDirty; }

    @Override public String type() { return "item_frame"; }

    /** The framed item or null. */
    public ItemStack item() { return items.get(0); }

    @Override public Inventory inventory() { return items; }

    @Override public void save(Map<String, String> out) {
        out.put("items", items.serialize());
        out.put("rotation", Integer.toString(rotation));
    }

    @Override public void load(Map<String, String> in) {
        items.deserialize(in.get("items"));
        try { rotation = Integer.parseInt(in.getOrDefault("rotation", "0")) & 7; } catch (NumberFormatException e) { rotation = 0; }
    }
}
