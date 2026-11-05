package derpcraft.resource;

import java.util.HashMap;
import java.util.Map;

/**
 * Built-in 2x2 "programmer art". Every texture is just 4 pixels; drop any Minecraft 1.12.2 resource pack into
 * the resourcepacks folder for real textures.
 * Line format: name color [kind] [color2]
 *   kind: (none) = shaded noise, o = ore speck (color2), p = plant, l = leaves, a = translucent (AARRGGBB), t = top row color2
 */
public final class DefaultTextures {
    private static final String TABLE = """
            stone 7d7d7d
            command_block_front b48a6a o 3a3a7a
            command_block_side b48a6a
            command_block_back b48a6a o 303030
            stone_granite 9a6b56
            stone_granite_smooth 9e6c58
            stone_diorite bcbcbc
            stone_diorite_smooth c4c4c6
            stone_andesite 888888
            stone_andesite_smooth 848686
            grass_top 959595
            grass_side 866043
            grass_side_overlay 959595 t2
            dirt 866043
            coarse_dirt 77553b
            dirt_podzol_side 866043 t 5b3f1b
            dirt_podzol_top 5b3f1b
            cobblestone 7a7a7a
            cobblestone_mossy 6e7f62
            bedrock 555555
            sand dbd3a0
            red_sand a95821
            gravel 837f7e
            gold_ore 8f8c7d o fcee4b
            iron_ore 887f78 o d8af93
            coal_ore 737373 o 2a2a2a
            diamond_ore 7d8e8d o 5decf5
            lapis_ore 667086 o 1d47a6
            emerald_ore 75897a o 17dd62
            log_oak 6b5534
            log_oak_top 9a7c4b
            log_spruce 3b2812
            log_spruce_top 6e5232
            log_birch d7d3c8 o 3a3a3a
            log_birch_top c1ad7e
            log_jungle 554419
            log_jungle_top 9c7348
            log_acacia 6a645c
            log_acacia_top a65b37
            log_big_oak 3c2e1a
            log_big_oak_top 4d3a22
            planks_oak 9d804f
            itemframe_background 7a5a35
            planks_spruce 735532
            planks_birch c4b27a
            planks_jungle a07351
            planks_acacia a85a32
            planks_big_oak 422b14
            leaves_oak 8a8a8a l
            leaves_spruce 8a8a8a l
            leaves_birch 8a8a8a l
            leaves_jungle 8a8a8a l
            leaves_acacia 8a8a8a l
            leaves_big_oak 8a8a8a l
            glass dbf0f5 g
            sandstone_normal d8cb9b
            sandstone_top dfd5a4
            sandstone_bottom d4c793
            sandstone_carved d5c898
            sandstone_smooth dad0a0
            red_sandstone_normal ba6421
            red_sandstone_top b5611e
            red_sandstone_bottom b25e1d
            red_sandstone_smooth b8621f
            hardened_clay 985e43
            gold_block f9d849
            iron_block dbdbdb
            diamond_block 61dbd5
            emerald_block 2acb57
            lapis_block 1f43a0
            coal_block 131313
            redstone_block a91807
            brick 966253 o b8b2a8
            bookshelf 6c5a3b o 7f2f2f
            obsidian 14121d
            quartz_block_side ece6df
            quartz_block_top ede7e0
            quartz_block_bottom ebe5de
            quartz_block_chiseled e7e2da
            quartz_block_chiseled_top e7e2da
            quartz_block_lines e8e3d9
            quartz_block_lines_top e9e4da
            stonebrick 7a7a7a
            stonebrick_mossy 6f7562
            stonebrick_cracked 777574
            stonebrick_carved 777777
            snow f9fefe
            ice 9f7da7f7 a
            ice_packed 8db4fa
            clay a0a6b3
            netherrack 6f3634
            soul_sand 513e32
            glowstone ab8653 o ffda74
            nether_brick 2c1519
            red_nether_brick 450709
            end_stone dde0a5
            end_bricks dae09e
            purpur_block a97ea9
            purpur_pillar ab81ab
            purpur_pillar_top ab8aab
            prismarine_rough 63a291
            prismarine_bricks 63ab9e
            prismarine_dark 335c4b
            sea_lantern acc8be o e8f5ee
            redstone_lamp_on c08c58 o f0c070
            hay_block_side a68b0c
            hay_block_top a68e1b
            tnt_side c8402c
            tnt_top b07a50
            tnt_bottom b07a50
            bone_block_side e1ddc9
            bone_block_top d1cdb4
            nether_wart_block 720202
            magma 8e3f1f o e0861e
            crafting_table_side 7f6545
            crafting_table_top 6f5a3a
            crafting_table_front 7a603e
            furnace_front_off 5e5e5e o 222222
            furnace_side 6e6e6e
            furnace_top 6e6e6e
            pumpkin_face_off c3721a o 3a1d05
            pumpkin_face_on d38920 o ffd25a
            pumpkin_side c47a1b
            pumpkin_top c07717
            melon_side 6f911f
            melon_top 727e1c
            sponge c3c04a
            slime c06fc05b a
            cactus_side 0d6418
            cactus_top 5c8c2d
            cactus_bottom 9fc06c
            water_still a82f5bd6 a
            lava_still d45a12
            tallgrass 8f8f8f p 8f8f8f
            fern 7d7d7d p 7d7d7d
            deadbush 946428 p 946428
            flower_dandelion f5e43c p
            flower_rose c91f1a p
            flower_blue_orchid 2aa2d8 p
            flower_allium b664e8 p
            flower_houstonia e3e9e9 p
            flower_tulip_red d22a1e p
            flower_tulip_orange e46d23 p
            flower_tulip_white e9e9e9 p
            flower_tulip_pink eba7c5 p
            flower_oxeye_daisy eaeaea p
            sapling_oak 3f7a20 p 3f7a20
            sapling_spruce 2d4a2d p 2d4a2d
            sapling_birch 6f9a46 p 6f9a46
            sapling_jungle 3b6b16 p 3b6b16
            sapling_acacia 6b8a1c p 6b8a1c
            sapling_roofed_oak 2a5214 p 2a5214
            mushroom_red c73128 p
            mushroom_brown 916d55 p
            reeds c8d8b0 p c8d8b0
            stone_slab_top a8a8a8
            stone_slab_side a0a0a0
            glass_pane_top dbf0f5
            iron_bars 6d6d6d g
            anvil_base 444444
            anvil_top_damaged_0 3c3c3c
            beacon 8fe8e0
            beetroots_stage_0 3a8a20 p 3a8a20
            beetroots_stage_1 3a8a20 p 3a8a20
            beetroots_stage_2 3a8a20 p 6a2a20
            beetroots_stage_3 3a8a20 p a02030
            brewing_stand 6a6a6a p e8c040
            brewing_stand_base 6e6e6e
            cake_bottom 9a5a2a
            cake_inner d8b080
            cake_side f0e8e0 t e04040
            cake_top f8f4f0
            carrots_stage_0 3a9a20 p 3a9a20
            carrots_stage_1 3a9a20 p 3a9a20
            carrots_stage_2 3a9a20 p 3a9a20
            carrots_stage_3 3a9a20 p f08a1a
            cauldron_bottom 3a3a3a
            cauldron_inner 2a2a2a
            cauldron_side 4a4a4a
            cauldron_top 4a4a4a
            dispenser_front_horizontal 7a7a7a o 2a2a2a
            door_acacia_lower a85a32
            door_acacia_upper a85a32 t c07040
            door_birch_lower d8cc9a
            door_birch_upper d8cc9a t e8e0b8
            door_dark_oak_lower 4a3018
            door_dark_oak_upper 4a3018 t 5a3a20
            door_iron_lower c8c8c8
            door_iron_upper c8c8c8 t d8d8d8
            door_jungle_lower a07351
            door_jungle_upper a07351 t b88a60
            door_spruce_lower 6a4a28
            door_spruce_upper 6a4a28 t 7a5a34
            door_wood_lower 9d804f
            door_wood_upper 9d804f t b09060
            double_plant_fern_bottom 8a8a8a p 8a8a8a
            double_plant_fern_top 8a8a8a p 8a8a8a
            double_plant_grass_bottom 8a8a8a p 8a8a8a
            double_plant_grass_top 8a8a8a p 8a8a8a
            double_plant_paeonia_bottom 3a8a20 p 3a8a20
            double_plant_paeonia_top e8a0d0 p 3a8a20
            double_plant_rose_bottom 3a8a20 p 3a8a20
            double_plant_rose_top d02020 p 3a8a20
            double_plant_sunflower_bottom 3a8a20 p 3a8a20
            double_plant_sunflower_top f8d020 p 3a8a20
            double_plant_syringa_bottom 3a8a20 p 3a8a20
            double_plant_syringa_top c890d8 p 3a8a20
            dropper_front_horizontal 7a7a7a o 1a1a1a
            enchanting_table_bottom 1a1a2a
            enchanting_table_side 2a1a1a t a02020
            enchanting_table_top a02020
            end_rod f0e8e0
            farmland_dry 7a5a3a
            farmland_wet 4a3020
            fire_layer_0 f8a020 p f86010
            flower_pot 7a3a20
            furnace_front_on 7a7a7a o f8b020
            hopper_inside 3a3a3a
            hopper_outside 4a4a4a
            hopper_top 4a4a4a
            iron_trapdoor c8c8c8
            jukebox_side 6a4a30
            jukebox_top 6a4a30 o 2a2a2a
            ladder 9d804f
            lever 6b5534 p 7a7a7a
            noteblock 6a4a30 o 3a2a1a
            potatoes_stage_0 3a9a20 p 3a9a20
            potatoes_stage_1 3a9a20 p 3a9a20
            potatoes_stage_2 3a9a20 p 3a9a20
            potatoes_stage_3 3a9a20 p d8b060
            pumpkin_stem_disconnected 8a8a8a p 8a8a8a
            rail_activator 7a7a7a p c02020
            rail_detector 7a7a7a p 6a6a6a
            rail_golden_powered 9a8a6a p f8d020
            rail_normal 7a7a7a p 6b5534
            rail_normal_turned 7a7a7a p 6b5534
            redstone_lamp_off 6a4a30
            redstone_ore 8a8a8a o c81a0b
            redstone_torch_on 6b5534 p f02010
            sponge_wet a8a830
            trapdoor 9d804f
            vine 8a8a8a l
            waterlily 8a8a8a l
            web e8e8e8 p e8e8e8
            wheat_stage_0 3a9a20 p 3a9a20
            wheat_stage_1 3a9a20 p 3a9a20
            wheat_stage_2 5aa020 p 5aa020
            wheat_stage_3 6aa020 p 6aa020
            wheat_stage_4 8aa020 p 8aa020
            wheat_stage_5 a0a030 p a0a030
            wheat_stage_6 c0a838 p c0a838
            wheat_stage_7 d0b040 p d0b040
            """;

    private static final int[] DYE = {0xe9ecec, 0xf07613, 0xbd44b3, 0x3aafd9, 0xf8c527, 0x70b919, 0xed8dac, 0x3e4447,
            0x8e8e86, 0x158991, 0x792aac, 0x35399d, 0x724728, 0x546d1b, 0xa12722, 0x141519};
    private static final int[] CONCRETE = {0xcfd5d6, 0xe06100, 0xa9309f, 0x2389c6, 0xf0af15, 0x5ea818, 0xd5658e, 0x36393d,
            0x7d7d73, 0x157788, 0x64209c, 0x2c2e8f, 0x603b1f, 0x495b24, 0x8e2020, 0x080a0f};
    private static final int[] TERRACOTTA = {0xd1b1a1, 0xa15325, 0x95586c, 0x716c89, 0xba8523, 0x677534, 0xa14e4e, 0x392a23,
            0x876a61, 0x565b5b, 0x764656, 0x4a3b5b, 0x4d3323, 0x4b522a, 0x8f3d2e, 0x251610};
    private static final String[] COLORS = {"white", "orange", "magenta", "light_blue", "yellow", "lime", "pink", "gray",
            "silver", "cyan", "purple", "blue", "brown", "green", "red", "black"};

    private static final Map<String, int[]> MAP = new HashMap<>();

    static {
        for (String line : TABLE.split("\n")) {
            String[] t = line.trim().split("\\s+");
            if (t.length < 2) continue;
            long c = Long.parseLong(t[1], 16);
            String kind = t.length > 2 ? t[2] : "";
            int c2 = t.length > 3 ? Integer.parseInt(t[3], 16) : 0x4a8a2a;
            MAP.put(t[0], make((int) c, kind, c2));
        }
        for (int i = 0; i < 16; i++) {
            MAP.put("wool_colored_" + COLORS[i], make(DYE[i], "", 0));
            MAP.put("concrete_" + COLORS[i], make(CONCRETE[i], "flat", 0));
            MAP.put("concrete_powder_" + COLORS[i], make(mix(CONCRETE[i], 0xffffff, 0.2f), "", 0));
            MAP.put("hardened_clay_stained_" + COLORS[i], make(TERRACOTTA[i], "", 0));
            int glass = 0x80000000 | DYE[i];
            MAP.put("glass_" + COLORS[i], make(glass, "a", 0));
            MAP.put("glass_pane_top_" + COLORS[i], make(0xff000000 | DYE[i], "", 0));
        }
        // torch: top row flame, bottom row stick (the torch model only samples the centre columns)
        MAP.put("torch_on", new int[]{0xffffd84a, 0xffffa020, 0xff6b5032, 0xff5a4128});
    }

    private DefaultTextures() {}

    private static int shade(int rgb, float f) {
        int r = Math.min(255, (int) (((rgb >> 16) & 255) * f));
        int g = Math.min(255, (int) (((rgb >> 8) & 255) * f));
        int b = Math.min(255, (int) ((rgb & 255) * f));
        return 0xff000000 | r << 16 | g << 8 | b;
    }

    private static int mix(int a, int b, float t) {
        int r = (int) (((a >> 16) & 255) * (1 - t) + ((b >> 16) & 255) * t);
        int g = (int) (((a >> 8) & 255) * (1 - t) + ((b >> 8) & 255) * t);
        int bl = (int) ((a & 255) * (1 - t) + (b & 255) * t);
        return r << 16 | g << 8 | bl;
    }

    private static int[] make(int c, String kind, int c2) {
        return switch (kind) {
            case "o" -> new int[]{shade(c, 1.0f), shade(c2, 1.0f), shade(c, 0.9f), shade(c, 1.06f)};
            case "p" -> new int[]{0, shade(c, 1f), shade(c2, 0.9f), 0};
            case "l" -> new int[]{shade(c, 1.05f), 0, shade(c, 0.85f), shade(c, 0.95f)};
            case "g" -> new int[]{shade(c, 1f), 0, 0, 0};
            case "a" -> new int[]{c, c, c, c};
            case "t" -> new int[]{shade(c2, 1f), shade(c2, 0.95f), shade(c, 1f), shade(c, 0.9f)};
            case "t2" -> new int[]{shade(c, 1f), shade(c, 0.95f), 0, 0};
            case "flat" -> new int[]{shade(c, 1f), shade(c, 0.98f), shade(c, 0.98f), shade(c, 1f)};
            default -> new int[]{shade(c, 1.04f), shade(c, 0.9f), shade(c, 0.95f), shade(c, 1.0f)};
        };
    }

    /** ARGB pixels (row-major 2x2) or a magenta/black "missing" texture. */
    public static int[] get(String name) {
        int[] p = MAP.get(name);
        if (p != null) return p;
        if (name.startsWith("items/")) return item(name.substring(6));
        if (name.startsWith("destroy_stage_")) {
            int n = Integer.parseInt(name.substring(14)), k = n < 2 ? 1 : n < 5 ? 2 : n < 8 ? 3 : 4;
            int[] px = new int[4];
            int[] order = {1, 2, 0, 3};
            for (int i = 0; i < k; i++) px[order[i]] = 0xff404040;
            return px;
        }
        return new int[]{0xfff800f8, 0xff000000, 0xff000000, 0xfff800f8};
    }

    private static final String[][] ITEM_COLORS = {
            {"diamond", "5decf5"}, {"iron", "d8d8d8"}, {"gold", "f5d33b"}, {"wood", "9d804f"}, {"stick", "6b5534"}, {"stone", "7d7d7d"},
            {"coal", "2a2a2a"}, {"redstone", "c81a0b"}, {"leather", "a0522d"}, {"apple", "d42a2a"}, {"bread", "c89646"}, {"emerald", "17dd62"},
            {"string", "f0f0f0"}, {"feather", "f4f4f4"}, {"flint", "3c3c3c"}, {"bone", "e8e4d0"}, {"slime", "70c060"}, {"paper", "f0f0e0"},
            {"book", "7a4b2a"}, {"bucket", "c0c0c0"}, {"bowl", "8a6a40"}, {"wheat", "d0b040"}, {"seeds", "60a030"}, {"carrot", "f08a1a"},
            {"potato", "d8b060"}, {"beef", "c04040"}, {"porkchop", "f09090"}, {"chicken", "f0c0a0"}, {"fish", "6090b0"}, {"egg", "f0e0c0"},
            {"sugar", "fafafa"}, {"melon", "e04030"}, {"pumpkin", "e08a1a"}, {"cookie", "c08040"}, {"cake", "f0f0f0"}, {"clay", "a0a8b8"},
            {"brick", "a04c35"}, {"glowstone", "f8d870"}, {"blaze", "f8b020"}, {"ender", "2a8a70"}, {"quartz", "ece6dc"}, {"lapis", "1d47a6"},
            {"dye_black", "1d1d21"}, {"dye", "b040b0"}, {"sign", "9d804f"}, {"door", "9d804f"}, {"bed", "b02e26"}, {"glass", "c0e0f0"},
            {"item_frame", "b0793c"}, {"arrow", "8a8a8a"}, {"bow", "7a5a30"}, {"chain", "6a6a6a"}, {"saddle", "8a4a20"}, {"snowball", "fafafa"}, {"gunpowder", "606060"}};
    private static final String[] DIAGONAL = {"sword", "pickaxe", "axe", "shovel", "hoe", "stick", "rod", "bow", "arrow", "shears", "flint_and_steel", "blaze_rod"};

    /** 2x2 item icons: a colour picked from the name, tools drawn as a diagonal line. */
    private static int[] item(String n) {
        if (n.startsWith("bed_")) {   // built-in bed icons: one dye colour per bed
            int idx = java.util.Arrays.asList(derpcraft.block.Blocks.COLORS).indexOf(n.substring(4));
            if (idx >= 0) { int bc = DefaultImages.dyeColor(idx); return new int[]{shade(bc, 1.1f), shade(bc, 1f), shade(bc, 0.9f), shade(bc, 0.8f)}; }
        }
        int c = 0x808080 | (n.hashCode() & 0x7F7F7F);
        for (String[] e : ITEM_COLORS) if (n.contains(e[0])) { c = Integer.parseInt(e[1], 16); break; }
        if (n.startsWith("wooden_") || n.startsWith("wood_")) c = 0x9d804f;
        for (String d : DIAGONAL)
            if (n.contains(d)) {
                int handle = n.equals("stick") || n.contains("rod") ? c : 0x6b5534;
                return new int[]{0, shade(c, 1.1f), shade(handle, 1f), 0};
            }
        return new int[]{shade(c, 1.1f), shade(c, 1f), shade(c, 0.9f), shade(c, 0.8f)};
    }

    public static boolean has(String name) { return MAP.containsKey(name); }
}
