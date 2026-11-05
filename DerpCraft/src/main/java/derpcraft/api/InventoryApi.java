package derpcraft.api;

import derpcraft.item.ItemStack;

/** Slots of a container (chest, furnace, mod storage, machine). Slots hold {@link ItemStack}s or null. */
public interface InventoryApi {
    int size();

    ItemStack get(int slot);

    void set(int slot, ItemStack stack);

    /** Puts items in (merging with stacks first). Returns what didn't fit, or null. */
    ItemStack add(ItemStack stack);

    /** Removes up to 'count' items of a kind ("coal"); returns how many were removed. */
    int remove(String item, int count);

    /** Total number of an item in all slots. */
    int count(String item);

    boolean isEmpty();

    void clear();

    /** The contents as text (store it in a {@link DataTag}) and back. */
    String save();
    void load(String saved);
}
