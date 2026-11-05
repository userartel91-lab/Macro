package derpcraft.game;

import derpcraft.item.Inventory;
import derpcraft.item.Item;
import derpcraft.item.ItemStack;

/**
 * Slots 0-8 hotbar, 9-35 main inventory, 36-39 armor (helmet, chestplate, leggings, boots), 40 = the gun carried on the
 * back (Left Alt + F, {@link Session#toggleBackGun}; not in any menu; its stack keeps the slot it came from in the tag
 * {@link #BACK_SLOT_TAG}).
 */
public final class PlayerInventory extends Inventory {
    public static final int HOTBAR = 9, MAIN = 36, ARMOR = 36, BACK = 40;
    /**
     * Pouches (equipment, Left Alt + E): POUCH .. POUCH + 2 on the left of the belt, POUCH + 3 .. POUCH + 5 on the right;
     * gun magazines only, at most POUCH_MAX in each. Reloads take magazines from here first.
     */
    public static final int POUCH = 41, POUCHES = 6, POUCH_MAX = 3;
    /** Tag of the gun on the back: the hotbar / inventory slot it was taken from (it goes back there). */
    public static final String BACK_SLOT_TAG = "backSlot";
    public int selected;
    /** Stack held by the mouse cursor in container screens. */
    public ItemStack cursor;

    public PlayerInventory() { super(POUCH + POUCHES); }

    public ItemStack held() { return get(selected); }

    /** The gun on the back, or null. */
    public ItemStack back() { return get(BACK); }

    /** Items added without a range (mods) never go onto the back. */
    @Override public ItemStack add(ItemStack s) { return add(s, 0, BACK); }

    /** Adds to hotbar first, then main inventory (like picking up items). Returns leftovers. */
    public ItemStack addItem(ItemStack s) { return add(s, 0, MAIN); }

    public int armorPoints() {
        int p = 0;
        for (int i = ARMOR; i < ARMOR + 4; i++) if (slots[i] != null) p += slots[i].item.armorPoints;
        return p;
    }

    /** Creative pick block: select an existing hotbar slot with the item or put it in the current slot. */
    public void pick(Item item) {
        for (int i = 0; i < HOTBAR; i++) if (slots[i] != null && slots[i].item == item) { selected = i; return; }
        int slot = selected;
        if (slots[slot] != null) for (int i = 0; i < HOTBAR; i++) if (slots[i] == null) { slot = i; break; }
        selected = slot;
        set(slot, new ItemStack(item, item.maxStack));
    }

    public int count(Item item) {
        int n = 0;
        for (int i = 0; i < MAIN; i++) if (slots[i] != null && slots[i].item == item) n += slots[i].count;
        return n;
    }
}
