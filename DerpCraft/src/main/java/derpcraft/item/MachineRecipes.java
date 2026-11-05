package derpcraft.item;

import java.util.*;

/**
 * Recipes of mod machines (furnace-like blocks), grouped by type ("mymod:grinding"). The built-in type
 * "smelting" is the vanilla furnace list. A recipe has one or two inputs (item names or #tags), an output, its
 * own processing time (0 = the machine's default) and experience.
 */
public final class MachineRecipes {
    public record Recipe(List<Item> in1, int count1, List<Item> in2, int count2, ItemStack out, int ticks, float xp) {
        public boolean matches(ItemStack a, ItemStack b) {
            if (!ok(in1, count1, a)) return false;
            return in2 == null ? true : ok(in2, count2, b);
        }

        private static boolean ok(List<Item> in, int n, ItemStack s) { return s != null && in.contains(s.item) && s.count >= n; }
    }

    private static final Map<String, List<Recipe>> TYPES = new HashMap<>();

    private MachineRecipes() {}

    /** Adds a recipe. Inputs: "item" or "item*count" or "#tag"; input2 may be null. */
    public static void add(String type, String input1, String input2, String output, int outCount, int ticks, float xp) {
        Item outItem = Items.get(output);
        String[] a = split(input1);
        String[] b = input2 == null ? null : split(input2);
        Recipe r = new Recipe(Recipes.ingredient(a[0]), Integer.parseInt(a[1]), b == null ? null : Recipes.ingredient(b[0]), b == null ? 0 : Integer.parseInt(b[1]),
                new ItemStack(outItem, Math.max(1, outCount)), Math.max(0, ticks), xp);
        TYPES.computeIfAbsent(type, k -> new ArrayList<>()).add(r);
    }

    private static String[] split(String in) {
        int i = in.indexOf('*');
        return i < 0 ? new String[]{in.trim(), "1"} : new String[]{in.substring(0, i).trim(), in.substring(i + 1).trim()};
    }

    /** The recipe for these inputs, or null. "smelting" falls back to the vanilla furnace recipes. */
    public static Recipe find(String type, ItemStack a, ItemStack b) {
        List<Recipe> list = TYPES.get(type);
        if (list != null) for (Recipe r : list) if (r.matches(a, b)) return r;
        if ("smelting".equals(type) && a != null) {
            ItemStack res = Recipes.smelt(a.item);
            if (res != null) return new Recipe(List.of(a.item), 1, null, 0, res, 0, Recipes.smeltXp(a.item));
        }
        return null;
    }

    /** Whether an item is an input of any recipe of the type (shift-click routing, slot filters). */
    public static boolean isInput(String type, Item it, int slot) {
        List<Recipe> list = TYPES.get(type);
        if (list != null) for (Recipe r : list) if ((slot == 0 ? r.in1 : r.in2) != null && (slot == 0 ? r.in1 : r.in2).contains(it)) return true;
        return "smelting".equals(type) && slot == 0 && Recipes.smelt(it) != null;
    }
}
