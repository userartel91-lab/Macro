package derpcraft.game;

import derpcraft.api.InventoryApi;
import derpcraft.item.Inventory;
import derpcraft.item.Item;
import derpcraft.item.ItemStack;
import derpcraft.item.Items;

/** {@link InventoryApi} over an engine inventory. */
public final class InventoryView implements InventoryApi {
    private final Inventory inv;

    public InventoryView(Inventory inv) { this.inv = inv; }

    /** The engine inventory behind this view. */
    public Inventory engine() { return inv; }

    @Override public String save() { return inv.serialize(); }
    @Override public void load(String saved) { inv.deserialize(saved); inv.changed(); }

    @Override public int size() { return inv.size(); }
    @Override public ItemStack get(int slot) { return slot < 0 || slot >= inv.size() ? null : inv.get(slot); }
    @Override public void set(int slot, ItemStack stack) { if (slot >= 0 && slot < inv.size()) inv.set(slot, stack); }
    @Override public ItemStack add(ItemStack stack) { return inv.add(stack); }
    @Override public boolean isEmpty() { return inv.isEmpty(); }
    @Override public void clear() { inv.clear(); }

    @Override public int count(String item) {
        Item it = Items.byName(item);
        int n = 0;
        for (int i = 0; i < inv.size(); i++) { ItemStack s = inv.get(i); if (s != null && s.item == it) n += s.count; }
        return n;
    }

    @Override public int remove(String item, int count) {
        Item it = Items.byName(item);
        int left = count;
        for (int i = 0; i < inv.size() && left > 0; i++) {
            ItemStack s = inv.get(i);
            if (s == null || s.item != it) continue;
            int n = Math.min(left, s.count);
            s.count -= n;
            left -= n;
            inv.set(i, s.count <= 0 ? null : s);
        }
        return count - left;
    }
}
