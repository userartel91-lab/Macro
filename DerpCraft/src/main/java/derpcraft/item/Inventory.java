package derpcraft.item;

/** A fixed list of item slots (chest contents, furnace slots, player inventory...). */
public class Inventory {
    protected final ItemStack[] slots;
    /** Called whenever a slot changes (used to mark chunks dirty / update crafting results). */
    public Runnable onChange = () -> {};

    public Inventory(int size) { slots = new ItemStack[size]; }

    public int size() { return slots.length; }

    public ItemStack get(int i) { return slots[i]; }

    public void set(int i, ItemStack s) {
        slots[i] = ItemStack.isEmpty(s) ? null : s;
        onChange.run();
    }

    public void changed() { onChange.run(); }

    public void clear() { java.util.Arrays.fill(slots, null); onChange.run(); }

    /**
     * Adds items to existing stacks first, then empty slots in the given range. Returns what didn't fit (or null).
     */
    public ItemStack add(ItemStack s, int from, int to) {
        if (ItemStack.isEmpty(s)) return null;
        s = s.copy();
        for (int i = from; i < to && s.count > 0; i++) {
            ItemStack t = slots[i];
            if (t != null && t.stacksWith(s) && t.count < t.maxStack()) {
                int n = Math.min(s.count, t.maxStack() - t.count);
                t.count += n;
                s.count -= n;
            }
        }
        for (int i = from; i < to && s.count > 0; i++) {
            if (slots[i] == null) {
                int n = Math.min(s.count, s.maxStack());
                slots[i] = s.copy(n);
                s.count -= n;
            }
        }
        onChange.run();
        return s.count > 0 ? s : null;
    }

    public ItemStack add(ItemStack s) { return add(s, 0, slots.length); }

    public boolean isEmpty() {
        for (ItemStack s : slots) if (s != null) return false;
        return true;
    }

    public String serialize() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < slots.length; i++) {
            if (slots[i] == null) continue;
            if (sb.length() > 0) sb.append(';');
            sb.append(i).append(':').append(slots[i].serialize());
        }
        return sb.toString();
    }

    public void deserialize(String s) {
        java.util.Arrays.fill(slots, null);
        if (s == null || s.isBlank()) return;
        for (String part : s.split(";")) {
            int c = part.indexOf(':');
            if (c < 0) continue;
            try {
                int i = Integer.parseInt(part.substring(0, c));
                if (i >= 0 && i < slots.length) slots[i] = ItemStack.parse(part.substring(c + 1));
            } catch (NumberFormatException ignored) {}
        }
    }
}
