package derpcraft.item;

/** A stack of items. 'damage' is used up durability for tools. Empty stacks are represented by null. */
public final class ItemStack {
    public final Item item;
    public int count;
    public int damage;
    /** Extra data (custom names, stored contents of portable storage, mod data). Null = none. */
    private derpcraft.api.DataTag tag;

    public ItemStack(Item item, int count) { this(item, count, 0); }

    public ItemStack(Item item, int count, int damage) {
        this.item = item;
        this.count = count;
        this.damage = damage;
    }

    public ItemStack(Item item) { this(item, 1); }

    public ItemStack copy() { return copy(count); }

    public ItemStack copy(int n) {
        ItemStack c = new ItemStack(item, n, damage);
        if (tag != null && !tag.isEmpty()) c.tag = tag.copy();
        return c;
    }

    /** The stack's data tag, created on first use. */
    public derpcraft.api.DataTag tag() {
        if (tag == null) tag = new derpcraft.api.DataTag();
        return tag;
    }

    public boolean hasTag() { return tag != null && !tag.isEmpty(); }

    /** Name shown in the inventory: the "name" tag if set, else the item's name. */
    public String displayName() {
        if (tag != null && tag.has("name")) return tag.getString("name");
        return item.displayName;
    }

    private static boolean sameTag(derpcraft.api.DataTag a, derpcraft.api.DataTag b) {
        boolean ea = a == null || a.isEmpty(), eb = b == null || b.isEmpty();
        if (ea || eb) return ea == eb;
        return a.equals(b);
    }

    public int maxStack() { return item.maxStack; }

    /** Same item and data (can merge). */
    public boolean stacksWith(ItemStack o) {
        return o != null && o.item == item && o.damage == damage && item.maxDamage == 0 && sameTag(tag, o.tag);
    }

    public static boolean isEmpty(ItemStack s) { return s == null || s.count <= 0; }

    /** Removes n items, returning them as a new stack. */
    public ItemStack split(int n) {
        n = Math.min(n, count);
        count -= n;
        return copy(n);
    }

    /** Uses up durability; returns true when the item broke. */
    public boolean damageItem(int amount) {
        if (item.maxDamage <= 0) return false;
        damage += amount;
        if (damage >= item.maxDamage) { count--; damage = 0; return true; }
        return false;
    }

    /** "name,count,damage" for saving. */
    public String serialize() { return item.name + "," + count + "," + damage + (hasTag() ? ",#" + tag.encode() : ""); }

    public static ItemStack parse(String s) {
        if (s == null || s.isBlank()) return null;
        String[] p = s.split(",");
        Item it = Items.byName(p[0]);
        if (it == null) return null;
        int c = p.length > 1 ? Integer.parseInt(p[1].trim()) : 1;
        int d = p.length > 2 ? Integer.parseInt(p[2].trim()) : 0;
        if (c <= 0) return null;
        ItemStack st = new ItemStack(it, c, d);
        if (p.length > 3 && p[3].startsWith("#")) st.tag = derpcraft.api.DataTag.decode(p[3].substring(1));
        return st;
    }

    @Override public String toString() { return count + "x" + item.name; }
}
