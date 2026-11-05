package derpcraft.item;

import derpcraft.block.Blocks;

import java.util.*;

/**
 * Crafting (shaped/shapeless) and smelting recipes of Minecraft 1.12.2.
 * Recipes are written in a tiny text format so mods can add their own with {@link #parse}:
 * <pre>
 *   S result count | row row row | k=item k=#tag      (shaped, rows separated by spaces, '.' = empty)
 *   L result count | item item #tag                   (shapeless)
 *   F input result xp                                 (smelting)
 * </pre>
 */
public final class Recipes {
    public record Shaped(int w, int h, List<Item>[] grid, ItemStack result) {}
    public record Shapeless(List<List<Item>> ingredients, ItemStack result) {}

    private static final List<Shaped> SHAPED = new ArrayList<>();
    private static final List<Shapeless> SHAPELESS = new ArrayList<>();
    private static final Map<Item, ItemStack> SMELT = new HashMap<>();
    private static final Map<Item, Float> SMELT_XP = new HashMap<>();
    private static final Map<String, List<Item>> TAGS = new HashMap<>();

    private Recipes() {}


    public static ItemStack smelt(Item in) { return SMELT.get(in); }

    public static float smeltXp(Item in) { return SMELT_XP.getOrDefault(in, 0f); }

    /** Adds items to a tag ("#name" in recipes). */
    public static void addTag(String name, String... items) {
        List<Item> l = new ArrayList<>(TAGS.getOrDefault(name, List.of()));
        for (String n : items) { Item i = Items.byName(n); if (i != null && !l.contains(i)) l.add(i); }
        TAGS.put(name, l);
    }

    static List<Item> ingredient(String s) {
        if (s.startsWith("#")) {
            List<Item> t = TAGS.get(s.substring(1));
            if (t == null) throw new IllegalArgumentException("Unknown tag " + s);
            return t;
        }
        Item i = Items.byName(s);
        if (i == null) throw new IllegalArgumentException("Unknown item " + s);
        return List.of(i);
    }

    /** Parses and registers one recipe line. Lines whose items don't exist are skipped with a warning. */
    @SuppressWarnings("unchecked")
    public static void parse(String line) {
        line = line.trim();
        if (line.isEmpty() || line.startsWith("//")) return;
        try {
            String[] parts = line.split("\\|");
            String[] head = parts[0].trim().split("\\s+");
            switch (head[0]) {
                case "S" -> {
                    ItemStack res = new ItemStack(Items.get(head[1]), head.length > 2 ? Integer.parseInt(head[2]) : 1);
                    String[] rows = parts[1].trim().split("\\s+");
                    Map<Character, List<Item>> keys = new HashMap<>();
                    for (String k : parts[2].trim().split("\\s+")) keys.put(k.charAt(0), ingredient(k.substring(2)));
                    int h = rows.length, w = 0;
                    for (String r : rows) w = Math.max(w, r.length());
                    List<Item>[] grid = new List[w * h];
                    for (int y = 0; y < h; y++)
                        for (int x = 0; x < rows[y].length(); x++) {
                            char c = rows[y].charAt(x);
                            if (c != '.') grid[y * w + x] = Objects.requireNonNull(keys.get(c), "key " + c);
                        }
                    SHAPED.add(new Shaped(w, h, grid, res));
                }
                case "L" -> {
                    ItemStack res = new ItemStack(Items.get(head[1]), head.length > 2 ? Integer.parseInt(head[2]) : 1);
                    List<List<Item>> ing = new ArrayList<>();
                    for (String s : parts[1].trim().split("\\s+")) ing.add(ingredient(s));
                    SHAPELESS.add(new Shapeless(ing, res));
                }
                case "F" -> {
                    Item in = Items.get(head[1]);
                    SMELT.put(in, new ItemStack(Items.get(head[2]), 1));
                    if (head.length > 3) SMELT_XP.put(in, Float.parseFloat(head[3]));
                }
                default -> throw new IllegalArgumentException("Bad recipe type");
            }
        } catch (RuntimeException e) {
            System.err.println("Skipping recipe '" + line + "': " + e.getMessage());
        }
    }

    // ------------------------------------------------------------------------------------------ matching
    /** Result for a crafting grid (w x h, row-major) or null. */
    public static ItemStack match(ItemStack[] grid, int gw, int gh) {
        int minX = gw, minY = gh, maxX = -1, maxY = -1, count = 0;
        for (int y = 0; y < gh; y++)
            for (int x = 0; x < gw; x++)
                if (grid[y * gw + x] != null) { minX = Math.min(minX, x); maxX = Math.max(maxX, x); minY = Math.min(minY, y); maxY = Math.max(maxY, y); count++; }
        if (count == 0) return null;
        int w = maxX - minX + 1, h = maxY - minY + 1;
        for (Shaped r : SHAPED) {
            if (r.w != w || r.h != h) continue;
            if (matches(r, grid, gw, minX, minY, false) || matches(r, grid, gw, minX, minY, true)) return r.result.copy();
        }
        outer:
        for (Shapeless r : SHAPELESS) {
            if (r.ingredients.size() != count) continue;
            List<List<Item>> left = new ArrayList<>(r.ingredients);
            for (ItemStack s : grid) {
                if (s == null) continue;
                boolean found = false;
                for (Iterator<List<Item>> it = left.iterator(); it.hasNext(); ) {
                    if (it.next().contains(s.item)) { it.remove(); found = true; break; }
                }
                if (!found) continue outer;
            }
            if (left.isEmpty()) return r.result.copy();
        }
        return null;
    }

    private static boolean matches(Shaped r, ItemStack[] grid, int gw, int ox, int oy, boolean mirror) {
        for (int y = 0; y < r.h; y++)
            for (int x = 0; x < r.w; x++) {
                List<Item> need = r.grid[y * r.w + (mirror ? r.w - 1 - x : x)];
                ItemStack have = grid[(oy + y) * gw + ox + x];
                if (need == null) { if (have != null) return false; }
                else if (have == null || !need.contains(have.item)) return false;
            }
        return true;
    }

    // ------------------------------------------------------------------------------------------ content
    private static void tag(String name, String... items) {
        List<Item> l = new ArrayList<>();
        for (String s : items) { Item i = Items.byName(s); if (i != null) l.add(i); }
        TAGS.put(name, l);
    }

    private static final String[] WOODS = {"oak", "spruce", "birch", "jungle", "acacia", "dark_oak"};

    public static void registerDefaults() {
        List<String> planks = new ArrayList<>(), logs = new ArrayList<>(), slabs = new ArrayList<>(), wool = new ArrayList<>();
        for (String w : WOODS) { planks.add(w + "_planks"); logs.add(w + "_log"); slabs.add(w + "_slab"); }
        for (String c : Blocks.COLOR_NAMES) wool.add(c + "_wool");
        tag("planks", planks.toArray(new String[0]));
        tag("logs", logs.toArray(new String[0]));
        tag("wooden_slab", slabs.toArray(new String[0]));
        tag("wool", wool.toArray(new String[0]));
        tag("coal", "coal", "charcoal");
        tag("stone_tool", "cobblestone");
        tag("sand", "sand", "red_sand");

        StringBuilder r = new StringBuilder(BASE);
        for (int i = 0; i < WOODS.length; i++) {
            String w = WOODS[i];
            r.append("L ").append(w).append("_planks 4 | ").append(w).append("_log\n");
            r.append("S ").append(w).append("_slab 6 | ### | #=").append(w).append("_planks\n");
            r.append("S ").append(w).append("_stairs 4 | #.. ##. ### | #=").append(w).append("_planks\n");
            r.append("S ").append(w).append("_fence 3 | #s# #s# | #=").append(w).append("_planks s=stick\n");
            r.append("S ").append(w).append("_fence_gate 1 | s#s s#s | #=").append(w).append("_planks s=stick\n");
            r.append("S ").append(w).append("_door 3 | ## ## ## | #=").append(w).append("_planks\n");
            r.append("F ").append(w).append("_log charcoal 0.15\n");
        }
        String[][] shapes = {{"stone", "stone"}, {"cobblestone", "cobblestone"}, {"stone_brick", "stone_bricks"}, {"brick", "bricks"},
                {"sandstone", "sandstone"}, {"red_sandstone", "red_sandstone"}, {"quartz", "quartz_block"}, {"nether_brick", "nether_bricks"},
                {"purpur", "purpur_block"}, {"polished_andesite", "polished_andesite"}, {"polished_diorite", "polished_diorite"},
                {"polished_granite", "polished_granite"}, {"mossy_cobblestone", "mossy_cobblestone"}, {"prismarine", "prismarine"}};
        for (String[] s : shapes) {
            r.append("S ").append(s[0]).append("_slab 6 | ### | #=").append(s[1]).append("\n");
            r.append("S ").append(s[0]).append("_stairs 4 | #.. ##. ### | #=").append(s[1]).append("\n");
        }
        for (int c = 0; c < 16; c++) {
            String col = Blocks.COLOR_NAMES[c];
            String dye = Items.DYE_NAMES[15 - c];
            r.append("L ").append(col).append("_wool 1 | ").append(dye).append(" #wool\n");
            r.append("S ").append(col).append("_carpet 3 | ## | #=").append(col).append("_wool\n");
            r.append("S ").append(col).append("_stained_glass 8 | ### #d# ### | #=glass d=").append(dye).append("\n");
            r.append("S ").append(col).append("_stained_glass_pane 16 | ### ### | #=").append(col).append("_stained_glass\n");
            r.append("S ").append(col).append("_terracotta 8 | ### #d# ### | #=terracotta d=").append(dye).append("\n");
            r.append("L ").append(col).append("_concrete_powder 8 | ").append(dye).append(" sand sand sand sand gravel gravel gravel gravel\n");
            r.append("S ").append(col).append("_bed 1 | www ppp | w=").append(col).append("_wool p=#planks\n");
            r.append("S ").append(col).append("_concrete_slab 6 | ### | #=").append(col).append("_concrete\n");
        }
        for (String line : r.toString().split("\n")) parse(line);
    }

    private static final String BASE = """
            S stick 4 | # # | #=#planks
            S crafting_table 1 | ## ## | #=#planks
            S chest 1 | ### #.# ### | #=#planks
            S furnace 1 | ### #.# ### | #=cobblestone
            S tnt 1 | gsg sgs gsg | g=gunpowder s=sand
            L dynamite 9 | tnt
            L tnt 1 | dynamite dynamite dynamite dynamite dynamite dynamite dynamite dynamite dynamite
            S torch 4 | c s | c=#coal s=stick
            S redstone_torch 1 | r s | r=redstone s=stick
            S ladder 3 | s.s sss s.s | s=stick
            S sign 3 | ### ### .s. | #=#planks s=stick
            S item_frame 1 | sss sls sss | s=stick l=leather
            S security_camera 1 | iii igr iii | i=iron_ingot g=glass r=redstone
            S screen_canvas 2 | ppp prp ppp | p=glass_pane r=redstone
            S bowl 4 | #.# .#. | #=#planks
            S oak_trapdoor 2 | ### ### | #=#planks
            S bookshelf 1 | ### bbb ### | #=#planks b=book
            S wooden_pressure_plate 1 | ## | #=#planks
            L wooden_button 1 | #planks
            S jukebox 1 | ### #d# ### | #=#planks d=diamond
            S note_block 1 | ### #r# ### | #=#planks r=redstone
            S wooden_pickaxe 1 | ### .s. .s. | #=#planks s=stick
            S wooden_axe 1 | ## #s .s | #=#planks s=stick
            S wooden_shovel 1 | # s s | #=#planks s=stick
            S wooden_hoe 1 | ## .s .s | #=#planks s=stick
            S wooden_sword 1 | # # s | #=#planks s=stick
            S stone_pickaxe 1 | ### .s. .s. | #=#stone_tool s=stick
            S stone_axe 1 | ## #s .s | #=#stone_tool s=stick
            S stone_shovel 1 | # s s | #=#stone_tool s=stick
            S stone_hoe 1 | ## .s .s | #=#stone_tool s=stick
            S stone_sword 1 | # # s | #=#stone_tool s=stick
            S iron_pickaxe 1 | ### .s. .s. | #=iron_ingot s=stick
            S iron_axe 1 | ## #s .s | #=iron_ingot s=stick
            S iron_shovel 1 | # s s | #=iron_ingot s=stick
            S iron_hoe 1 | ## .s .s | #=iron_ingot s=stick
            S iron_sword 1 | # # s | #=iron_ingot s=stick
            S golden_pickaxe 1 | ### .s. .s. | #=gold_ingot s=stick
            S golden_axe 1 | ## #s .s | #=gold_ingot s=stick
            S golden_shovel 1 | # s s | #=gold_ingot s=stick
            S golden_hoe 1 | ## .s .s | #=gold_ingot s=stick
            S golden_sword 1 | # # s | #=gold_ingot s=stick
            S diamond_pickaxe 1 | ### .s. .s. | #=diamond s=stick
            S diamond_axe 1 | ## #s .s | #=diamond s=stick
            S diamond_shovel 1 | # s s | #=diamond s=stick
            S diamond_hoe 1 | ## .s .s | #=diamond s=stick
            S diamond_sword 1 | # # s | #=diamond s=stick
            S leather_helmet 1 | ### #.# | #=leather
            S leather_chestplate 1 | #.# ### ### | #=leather
            S leather_leggings 1 | ### #.# #.# | #=leather
            S leather_boots 1 | #.# #.# | #=leather
            S iron_helmet 1 | ### #.# | #=iron_ingot
            S iron_chestplate 1 | #.# ### ### | #=iron_ingot
            S iron_leggings 1 | ### #.# #.# | #=iron_ingot
            S iron_boots 1 | #.# #.# | #=iron_ingot
            S golden_helmet 1 | ### #.# | #=gold_ingot
            S golden_chestplate 1 | #.# ### ### | #=gold_ingot
            S golden_leggings 1 | ### #.# #.# | #=gold_ingot
            S golden_boots 1 | #.# #.# | #=gold_ingot
            S diamond_helmet 1 | ### #.# | #=diamond
            S diamond_chestplate 1 | #.# ### ### | #=diamond
            S diamond_leggings 1 | ### #.# #.# | #=diamond
            S diamond_boots 1 | #.# #.# | #=diamond
            S bow 1 | .sx s.x .sx | s=stick x=string
            S arrow 4 | f s e | f=flint s=stick e=feather
            L fire_arrow 1 | arrow coal
            S shears 1 | .# #. | #=iron_ingot
            S flint_and_steel 1 | i. .f | i=iron_ingot f=flint
            S bucket 1 | #.# .#. | #=iron_ingot
            S compass 1 | .#. #r# .#. | #=iron_ingot r=redstone
            S clock 1 | .#. #r# .#. | #=gold_ingot r=redstone
            S fishing_rod 1 | ..s .sx s.x | s=stick x=string
            S iron_bars 16 | ### ### | #=iron_ingot
            S iron_door 3 | ## ## ## | #=iron_ingot
            S iron_trapdoor 1 | ## ## | #=iron_ingot
            S rail 16 | i.i isi i.i | i=iron_ingot s=stick
            S golden_rail 6 | g.g gsg grg | g=gold_ingot s=stick r=redstone
            S cauldron 1 | #.# #.# ### | #=iron_ingot
            S hopper 1 | #c# #c# .#. | #=iron_ingot c=chest
            S anvil 1 | bbb .i. iii | b=iron_block i=iron_ingot
            S enchanting_table 1 | .b. d#d ### | b=book d=diamond #=obsidian
            S brewing_stand 1 | .b. ### | b=blaze_rod #=cobblestone
            S dispenser 1 | ### #b# #r# | #=cobblestone b=bow r=redstone
            S dropper 1 | ### #.# #r# | #=cobblestone r=redstone
            S lever 1 | s c | s=stick c=cobblestone
            L stone_button 1 | stone
            S stone_pressure_plate 1 | ## | #=stone
            S light_weighted_pressure_plate 1 | ## | #=gold_ingot
            S heavy_weighted_pressure_plate 1 | ## | #=iron_ingot
            S redstone_lamp 1 | .r. rgr .r. | r=redstone g=glowstone
            S flower_pot 1 | #.# .#. | #=brick
            S cobblestone_wall 6 | ### ### | #=cobblestone
            S mossy_cobblestone_wall 6 | ### ### | #=mossy_cobblestone
            S nether_brick_fence 6 | ### ### | #=nether_bricks
            L mossy_cobblestone 1 | cobblestone vine
            L mossy_stone_bricks 1 | stone_bricks vine
            S stone_bricks 4 | ## ## | #=stone
            S chiseled_stone_bricks 1 | # # | #=stone_brick_slab
            S bricks 1 | ## ## | #=brick
            S nether_bricks 1 | ## ## | #=nether_brick_item
            S sandstone 1 | ## ## | #=sand
            S red_sandstone 1 | ## ## | #=red_sand
            S chiseled_sandstone 1 | # # | #=sandstone_slab
            S smooth_sandstone 4 | ## ## | #=sandstone
            S smooth_red_sandstone 4 | ## ## | #=red_sandstone
            S polished_granite 4 | ## ## | #=granite
            S polished_diorite 4 | ## ## | #=diorite
            S polished_andesite 4 | ## ## | #=andesite
            L granite 1 | diorite quartz
            S diorite 2 | cq qc | c=cobblestone q=quartz
            L andesite 2 | diorite cobblestone
            S quartz_block 1 | ## ## | #=quartz
            S chiseled_quartz_block 1 | # # | #=quartz_slab
            S quartz_pillar 2 | # # | #=quartz_block
            S purpur_block 4 | ## ## | #=popped_chorus_fruit
            S purpur_pillar 1 | # # | #=purpur_slab
            S prismarine 1 | ## ## | #=prismarine_shard
            S prismarine_bricks 1 | ### ### ### | #=prismarine_shard
            S dark_prismarine 1 | ### #d# ### | #=prismarine_shard d=ink_sac
            S sea_lantern 1 | #c# ccc #c# | #=prismarine_shard c=prismarine_crystals
            S glass_pane 16 | ### ### | #=glass
            S white_wool 1 | ## ## | #=string
            S snow 1 | ## ## | #=snowball
            S snow_layer 6 | ### | #=snow
            S clay 1 | ## ## | #=clay_ball
            S glowstone 1 | ## ## | #=glowstone_dust
            S terracotta 1 | # | #=clay
            S iron_block 1 | ### ### ### | #=iron_ingot
            L iron_ingot 9 | iron_block
            S gold_block 1 | ### ### ### | #=gold_ingot
            L gold_ingot 9 | gold_block
            S diamond_block 1 | ### ### ### | #=diamond
            L diamond 9 | diamond_block
            S emerald_block 1 | ### ### ### | #=emerald
            L emerald 9 | emerald_block
            S lapis_block 1 | ### ### ### | #=lapis_lazuli
            L lapis_lazuli 9 | lapis_block
            S coal_block 1 | ### ### ### | #=coal
            L coal 9 | coal_block
            S redstone_block 1 | ### ### ### | #=redstone
            L redstone 9 | redstone_block
            S gold_ingot 1 | ### ### ### | #=gold_nugget
            L gold_nugget 9 | gold_ingot
            S iron_ingot 1 | ### ### ### | #=iron_nugget
            L iron_nugget 9 | iron_ingot
            S hay_block 1 | ### ### ### | #=wheat
            L wheat 9 | hay_block
            S bone_block 1 | ### ### ### | #=bone_meal
            L bone_meal 9 | bone_block
            L bone_meal 3 | bone
            S slime_block 1 | ### ### ### | #=slime_ball
            L slime_ball 9 | slime_block
            S melon_block 1 | ### ### ### | #=melon
            L melon_seeds 1 | melon
            L pumpkin_seeds 4 | pumpkin
            S bread 1 | ### | #=wheat
            S paper 3 | ### | #=sugar_cane
            L sugar 1 | sugar_cane
            L book 1 | paper paper paper leather
            S golden_apple 1 | ### #a# ### | #=gold_ingot a=apple
            S golden_carrot 1 | ### #c# ### | #=gold_nugget c=carrot
            L mushroom_stew 1 | bowl red_mushroom brown_mushroom
            S beetroot_soup 1 | bbb bbb .w. | b=beetroot w=bowl
            L pumpkin_pie 1 | pumpkin sugar egg
            S cookie 8 | wcw | w=wheat c=cocoa_beans
            S glass_bottle 3 | #.# .#. | #=glass
            L jack_o_lantern 1 | pumpkin torch
            L flint_and_steel 1 | iron_ingot flint
            L rose_red 1 | poppy
            L rose_red 1 | tulip_red
            L dandelion_yellow 1 | dandelion
            L orange_dye 1 | tulip_orange
            L orange_dye 2 | rose_red dandelion_yellow
            L magenta_dye 1 | allium
            L magenta_dye 2 | purple_dye pink_dye
            L light_blue_dye 1 | blue_orchid
            L light_blue_dye 2 | lapis_lazuli bone_meal
            L pink_dye 1 | tulip_pink
            L pink_dye 2 | rose_red bone_meal
            L lime_dye 2 | cactus_green bone_meal
            L gray_dye 2 | ink_sac bone_meal
            L light_gray_dye 1 | houstonia
            L light_gray_dye 1 | oxeye_daisy
            L light_gray_dye 1 | tulip_white
            L light_gray_dye 2 | gray_dye bone_meal
            L cyan_dye 2 | lapis_lazuli cactus_green
            L purple_dye 2 | rose_red lapis_lazuli
            L ender_eye 1 | ender_pearl blaze_powder
            L blaze_powder 2 | blaze_rod
            L magma_cream 1 | slime_ball blaze_powder
            S beacon 1 | ggg gsg ooo | g=glass s=nether_star o=obsidian
            S ender_chest 1 | ooo oeo ooo | o=obsidian e=ender_eye
            S end_rod 4 | b p | b=blaze_rod p=popped_chorus_fruit
            S end_stone_bricks 4 | ## ## | #=end_stone
            S red_nether_bricks 1 | nw wn | n=nether_brick_item w=nether_wart
            S magma_block 1 | ## ## | #=magma_cream
            S trapped_chest 1 | ct | c=chest t=tripwire_hook
            S lead 2 | ss. sb. ..s | s=string b=slime_ball
            S minecart 1 | #.# ### | #=iron_ingot
            S oak_boat 1 | #.# ### | #=oak_planks
            S coarse_dirt 4 | dg gd | d=dirt g=gravel
            F iron_ore iron_ingot 0.7
            F gold_ore gold_ingot 1.0
            F diamond_ore diamond 1.0
            F emerald_ore emerald 1.0
            F lapis_ore lapis_lazuli 0.2
            F coal_ore coal 0.1
            F sand glass 0.1
            F red_sand glass 0.1
            F cobblestone stone 0.1
            F stone_bricks cracked_stone_bricks 0.1
            F clay_ball brick 0.3
            F clay terracotta 0.35
            F netherrack nether_brick_item 0.1
            F cactus cactus_green 1.0
            F porkchop cooked_porkchop 0.35
            F beef cooked_beef 0.35
            F chicken cooked_chicken 0.35
            F mutton cooked_mutton 0.35
            F rabbit cooked_rabbit 0.35
            F cod cooked_cod 0.35
            F salmon cooked_salmon 0.35
            F potato baked_potato 0.35
            F wet_sponge sponge 0.15
            """;
}
