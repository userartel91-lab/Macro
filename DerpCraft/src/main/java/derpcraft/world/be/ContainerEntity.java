package derpcraft.world.be;

import derpcraft.item.Inventory;

import java.util.Map;

/** Plain storage: chests, dispensers, droppers, hoppers. */
public class ContainerEntity extends BlockEntity {
    private final String type;
    public final Inventory items;
    /** Lid animation for chests (0 closed .. 1 open), driven by the renderer. */
    public float lid, prevLid;
    public int viewers;

    public ContainerEntity(String type, int size) {
        this.type = type;
        this.items = new Inventory(size);
        this.items.onChange = this::markDirty;
    }

    @Override public String type() { return type; }

    @Override public Inventory inventory() { return items; }

    @Override public boolean ticks() { return type.equals("chest") || type.equals("ender_chest"); }

    @Override public void tick() {
        prevLid = lid;
        if (viewers > 0) lid = Math.min(1, lid + 0.1f);
        else lid = Math.max(0, lid - 0.1f);
    }

    @Override public void save(Map<String, String> out) { out.put("items", items.serialize()); }

    @Override public void load(Map<String, String> in) { items.deserialize(in.get("items")); }
}
