package derpcraft.item;

import derpcraft.block.Block;
import derpcraft.block.Blocks;
import derpcraft.item.Item.ToolType;

import java.util.*;

/** Item registry and the Minecraft 1.12.2 item set. */
public final class Items {
    private static final Map<String, Item> BY_NAME = new LinkedHashMap<>();
    private static final List<Item> BY_ID = new ArrayList<>();

    private Items() {}

    public static synchronized Item register(Item it) {
        if (BY_NAME.containsKey(it.name)) throw new IllegalArgumentException("Duplicate item " + it.name);
        it.id = BY_ID.size();
        BY_ID.add(it);
        BY_NAME.put(it.name, it);
        return it;
    }

    public static Item byName(String name) {
        if (name == null) return null;
        Item i = BY_NAME.get(name);
        if (i == null && name.startsWith("minecraft:")) i = BY_NAME.get(name.substring(10));
        return i;
    }

    public static Item get(String name) {
        Item i = byName(name);
        if (i == null) throw new IllegalArgumentException("Unknown item " + name);
        return i;
    }

    public static Collection<Item> all() { return Collections.unmodifiableCollection(BY_NAME.values()); }


    // ------------------------------------------------------------------------------------------ helpers
    private static Item simple(String name, String tex, String tab) {
        Item i = new Item(name);
        i.texture = "items/" + tex;
        i.tab = tab;
        return register(i);
    }

    private static Item food(String name, String tex, int food, float sat) {
        Item i = simple(name, tex, "food");
        i.food = food;
        i.saturation = sat;
        return i;
    }

    /** Item that places a block (doors, signs, seeds...). */
    private static Item placer(String name, String tex, String block, String tab) {
        Item i = simple(name, tex, tab);
        i.block = Blocks.byName(block);
        return i;
    }

    private static void tool(String material, String texPrefix, int level, float speed, int durability, float dmg) {
        String[][] kinds = {{"pickaxe", "PICKAXE"}, {"axe", "AXE"}, {"shovel", "SHOVEL"}, {"hoe", "HOE"}, {"sword", "SWORD"}};
        for (String[] k : kinds) {
            Item i = simple(material + "_" + k[0], texPrefix + "_" + k[0], k[0].equals("sword") ? "combat" : "tools");
            i.tool = ToolType.valueOf(k[1]);
            i.toolLevel = level;
            i.toolSpeed = k[0].equals("sword") || k[0].equals("hoe") ? 1 : speed;
            i.maxDamage = durability;
            i.maxStack = 1;
            i.attackDamage = dmg + switch (k[0]) { case "sword" -> 4; case "axe" -> 3; case "pickaxe" -> 1; case "shovel" -> 1.5f; default -> 0; };
            if (material.equals("wooden")) i.fuel = 200;
        }
    }

    private static void armor(String material, String texPrefix, int durMul, int[] points) {
        String[] parts = {"helmet", "chestplate", "leggings", "boots"};
        int[] base = {11, 16, 15, 13};
        for (int s = 0; s < 4; s++) {
            Item i = simple(material + "_" + parts[s], texPrefix + "_" + parts[s], "combat");
            i.armorSlot = s;
            i.armorPoints = points[s];
            i.maxDamage = base[s] * durMul;
            i.maxStack = 1;
        }
    }

    public static final String[] DYE_TEX = {"black", "red", "green", "brown", "blue", "purple", "cyan", "silver", "gray", "pink",
            "lime", "yellow", "light_blue", "magenta", "orange", "white"};
    public static final String[] DYE_NAMES = {"ink_sac", "rose_red", "cactus_green", "cocoa_beans", "lapis_lazuli", "purple_dye",
            "cyan_dye", "light_gray_dye", "gray_dye", "pink_dye", "lime_dye", "dandelion_yellow", "light_blue_dye", "magenta_dye",
            "orange_dye", "bone_meal"};

    /** Creates block items and all other items. Call after blocks are registered (mods may add more afterwards). */
    public static void registerDefaults() {
        // block items
        for (Block b : Blocks.all()) {
            if (!b.inInventory) continue;
            Item i = new Item(b.name);
            i.block = b;
            i.displayName = b.displayName;
            i.tab = b.tab;
            register(i);
            b.item = i;
        }
        // materials
        String M = "materials";
        simple("stick", "stick", M).fuel = 100;
        simple("coal", "coal", M).fuel = 1600;
        simple("charcoal", "charcoal", M).fuel = 1600;
        simple("diamond", "diamond", M);
        simple("emerald", "emerald", M);
        simple("iron_ingot", "iron_ingot", M);
        simple("gold_ingot", "gold_ingot", M);
        simple("iron_nugget", "iron_nugget", M);
        simple("gold_nugget", "gold_nugget", M);
        simple("redstone", "redstone_dust", "redstone");
        simple("quartz", "quartz", M);
        simple("flint", "flint", M);
        simple("string", "string", M);
        simple("feather", "feather", M);
        simple("leather", "leather", M);
        simple("paper", "paper", "misc");
        simple("book", "book_normal", "misc");
        simple("bone", "bone", "misc");
        simple("sugar", "sugar", M);
        simple("wheat", "wheat", M);
        simple("clay_ball", "clay_ball", M);
        simple("brick", "brick", M);
        simple("nether_brick_item", "netherbrick", M).displayName = "Nether Brick";
        simple("glowstone_dust", "glowstone_dust", M);
        simple("gunpowder", "gunpowder", M);
        simple("dynamite", "dynamite", "combat");
        simple("slime_ball", "slimeball", "misc");
        simple("snowball", "snowball", "misc").maxStack = 16;
        simple("egg", "egg", M).maxStack = 16;
        simple("bowl", "bowl", M).fuel = 100;
        simple("glass_bottle", "potion_bottle_empty", "brewing");
        simple("blaze_rod", "blaze_rod", M).fuel = 2400;
        simple("blaze_powder", "blaze_powder", "brewing");
        simple("ender_pearl", "ender_pearl", "misc").maxStack = 16;
        simple("ender_eye", "ender_eye", "misc");
        simple("prismarine_shard", "prismarine_shard", M);
        simple("prismarine_crystals", "prismarine_crystals", M);
        simple("nether_star", "nether_star", M);
        simple("ghast_tear", "ghast_tear", "brewing");
        simple("magma_cream", "magma_cream", "brewing");
        simple("spider_eye", "spider_eye", "brewing");
        simple("nether_wart", "nether_wart", "brewing");
        simple("potion", "potion_bottle_drinkable", "brewing");
        simple("popped_chorus_fruit", "chorus_fruit_popped", "misc");
        simple("tripwire_hook", "tripwire_hook", "redstone");
        food("chorus_fruit", "chorus_fruit", 4, 0.3f).alwaysEdible = true;
        simple("name_tag", "name_tag", "tools");
        simple("lead", "lead", "tools");
        simple("saddle", "saddle", "transport").maxStack = 1;
        simple("arrow", "arrow", "combat");
        Item fireArrow = simple("fire_arrow", "arrow", "combat"); fireArrow.tint = 0xFF9040; fireArrow.displayName = "Fire Arrow";
        Item bow = simple("bow", "bow_standby", "combat"); bow.maxStack = 1; bow.maxDamage = 384;
        simple("compass", "compass_00", "tools").maxStack = 1;
        simple("clock", "clock_00", "tools").maxStack = 1;
        simple("map", "map_empty", "misc");
        Item fs = simple("flint_and_steel", "flint_and_steel", "tools"); fs.maxStack = 1; fs.maxDamage = 64;
        Item adminFs = simple("admin_flint_and_steel", "flint_and_steel", "tools");
        adminFs.maxStack = 1; adminFs.tint = 0xC878FF; adminFs.displayName = "Admin Flint and Steel";
        adminFs.tooltip = java.util.List.of("Eternal fire: never goes out, never spreads,", "never destroys anything. For fire scenes.");
        Item shears = simple("shears", "shears", "tools"); shears.maxStack = 1; shears.maxDamage = 238; shears.tool = ToolType.SHEARS;
        Item fishingRod = simple("fishing_rod", "fishing_rod_uncast", "tools"); fishingRod.maxStack = 1; fishingRod.maxDamage = 64;
        for (int d = 0; d < 16; d++) {
            Item dye = simple(DYE_NAMES[d], d == 4 ? "dye_powder_blue" : "dye_powder_" + DYE_TEX[d], M);
            if (d == 15) dye.tab = "misc";
        }
        // buckets
        Item bucket = simple("bucket", "bucket_empty", "misc"); bucket.maxStack = 16;
        Item water = placer("water_bucket", "bucket_water", "water", "misc"); water.maxStack = 1; water.container = new ItemStack(bucket);
        Item lava = placer("lava_bucket", "bucket_lava", "lava", "misc"); lava.maxStack = 1; lava.container = new ItemStack(bucket); lava.fuel = 20000;
        Item milk = simple("milk_bucket", "bucket_milk", "misc"); milk.maxStack = 1; milk.container = new ItemStack(bucket);

        // tools & armor
        tool("wooden", "wood", 0, 2, 59, 0);
        tool("stone", "stone", 1, 4, 131, 1);
        tool("iron", "iron", 2, 6, 250, 2);
        tool("golden", "gold", 0, 12, 32, 0);
        tool("diamond", "diamond", 3, 8, 1561, 3);
        armor("leather", "leather", 5, new int[]{1, 3, 2, 1});
        armor("chainmail", "chainmail", 15, new int[]{2, 5, 4, 1});
        armor("iron", "iron", 15, new int[]{2, 6, 5, 2});
        armor("golden", "gold", 7, new int[]{2, 5, 3, 1});
        armor("diamond", "diamond", 33, new int[]{3, 8, 6, 3});

        // food
        food("apple", "apple", 4, 2.4f);
        Item ga = food("golden_apple", "apple_golden", 4, 9.6f); ga.alwaysEdible = true;
        food("bread", "bread", 5, 6);
        food("porkchop", "porkchop_raw", 3, 1.8f);
        food("cooked_porkchop", "porkchop_cooked", 8, 12.8f);
        food("beef", "beef_raw", 3, 1.8f);
        food("cooked_beef", "beef_cooked", 8, 12.8f);
        food("chicken", "chicken_raw", 2, 1.2f);
        food("cooked_chicken", "chicken_cooked", 6, 7.2f);
        food("mutton", "mutton_raw", 2, 1.2f);
        food("cooked_mutton", "mutton_cooked", 6, 9.6f);
        food("rabbit", "rabbit_raw", 3, 1.8f);
        food("cooked_rabbit", "rabbit_cooked", 5, 6);
        food("cod", "fish_cod_raw", 2, 0.4f);
        food("cooked_cod", "fish_cod_cooked", 5, 6);
        food("salmon", "fish_salmon_raw", 2, 0.4f);
        food("cooked_salmon", "fish_salmon_cooked", 6, 9.6f);
        food("potato", "potato", 1, 0.6f).block = Blocks.byName("potatoes");
        food("baked_potato", "potato_baked", 5, 6);
        food("carrot", "carrot", 3, 3.6f).block = Blocks.byName("carrots");
        food("golden_carrot", "carrot_golden", 6, 14.4f);
        food("beetroot", "beetroot", 1, 1.2f);
        food("melon", "melon", 2, 1.2f);
        food("cookie", "cookie", 2, 0.4f);
        food("pumpkin_pie", "pumpkin_pie", 8, 4.8f);
        Item stew = food("mushroom_stew", "mushroom_stew", 6, 7.2f); stew.maxStack = 1; stew.container = new ItemStack(get("bowl"));
        Item soup = food("beetroot_soup", "beetroot_soup", 6, 7.2f); soup.maxStack = 1; soup.container = new ItemStack(get("bowl"));
        food("rotten_flesh", "rotten_flesh", 4, 0.8f);
        // seeds
        placer("wheat_seeds", "seeds_wheat", "wheat", "materials");
        placer("beetroot_seeds", "beetroot_seeds", "beetroots", "materials");
        placer("pumpkin_seeds", "seeds_pumpkin", "pumpkin_stem", "materials");
        placer("melon_seeds", "seeds_melon", "melon_stem", "materials");
        // block placers
        placer("sugar_cane", "reeds", "sugar_cane", "materials");
        String[] doors = {"oak", "spruce", "birch", "jungle", "acacia", "dark_oak", "iron"};
        String[] doorTex = {"wood", "spruce", "birch", "jungle", "acacia", "dark_oak", "iron"};
        for (int d = 0; d < doors.length; d++) {
            Item door = placer(doors[d] + "_door", "door_" + doorTex[d], doors[d] + "_door", "redstone");
            if (d < 6) door.fuel = 200;
        }
        Item sign = placer("sign", "sign", "standing_sign", "decoration"); sign.maxStack = 16; sign.fuel = 200;
        for (int c = 0; c < 16; c++) {
            Item bed = placer(Blocks.COLOR_NAMES[c] + "_bed", "bed_" + Blocks.COLORS[c], Blocks.COLOR_NAMES[c] + "_bed", "decoration");   // 1.12.2: items/bed_red.png ...
            bed.maxStack = 1;
        }
        placer("item_frame", "item_frame", "item_frame", "decoration");
        placer("flower_pot", "flower_pot", "flower_pot", "decoration");
        placer("cauldron", "cauldron", "cauldron", "brewing");
        placer("brewing_stand", "brewing_stand", "brewing_stand", "brewing");
        Item boat = simple("oak_boat", "boat_oak", "transport"); boat.maxStack = 1;
        Item cart = simple("minecart", "minecart_normal", "transport"); cart.maxStack = 1;

        // fuel values for wooden blocks
        for (Item i : all()) {
            if (i.block == null || i.fuel > 0) continue;
            String n = i.name;
            if (n.endsWith("_planks") || n.endsWith("_log") || n.endsWith("_fence") || n.endsWith("_fence_gate") || n.equals("crafting_table")
                    || n.equals("chest") || n.equals("trapped_chest") || n.equals("bookshelf") || n.equals("jukebox") || n.equals("note_block")
                    || n.endsWith("_trapdoor") && !n.startsWith("iron") || n.equals("ladder") || n.endsWith("_stairs") && isWood(n)) i.fuel = 300;
            else if (n.endsWith("_slab") && isWood(n)) i.fuel = 150;
            else if (n.endsWith("_sapling")) i.fuel = 100;
            else if (n.endsWith("_wool")) i.fuel = 100;
            else if (n.endsWith("_carpet")) i.fuel = 67;
            else if (n.equals("coal_block")) i.fuel = 16000;
            else if (n.endsWith("_button") && n.startsWith("wooden")) i.fuel = 100;
            else if (n.equals("wooden_pressure_plate")) i.fuel = 300;
        }
    }

    private static boolean isWood(String n) {
        return n.startsWith("oak") || n.startsWith("spruce") || n.startsWith("birch") || n.startsWith("jungle") || n.startsWith("acacia") || n.startsWith("dark_oak");
    }
}
