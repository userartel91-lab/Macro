package derpcraft.block;

import derpcraft.block.Block.*;
import derpcraft.item.Item;

import java.util.*;

/** Global block registry plus the built-in content. Mods register through {@link derpcraft.api.BlockRegistry}. */
public final class Blocks {
    public static final int MAX_BLOCKS = 4096;
    private static final Block[] BY_ID = new Block[MAX_BLOCKS];
    private static final Map<String, Block> BY_NAME = new LinkedHashMap<>();
    private static int nextId = 0;

    // Fast lookup tables indexed by block id (rebuilt by freeze())
    public static boolean[] OPAQUE = new boolean[MAX_BLOCKS];
    public static byte[] OPACITY = new byte[MAX_BLOCKS];
    public static byte[] EMISSION = new byte[MAX_BLOCKS];
    /** Emission per colour channel [ch][id] and extra attenuation through coloured filters [ch][id]. */
    public static byte[][] EMISSION_RGB = new byte[3][MAX_BLOCKS], FILTER_RGB = new byte[3][MAX_BLOCKS];
    private static final Map<String, Integer> LIGHT_COLORS = Map.ofEntries(
            Map.entry("lava", 0xFFA050), Map.entry("fire", 0xFFB060), Map.entry("magma_block", 0xFF7030),
            Map.entry("redstone_torch", 0xFF3020), Map.entry("sea_lantern", 0xB8E8FF), Map.entry("beacon", 0xB0FFFF),
            Map.entry("end_rod", 0xF0E8FF), Map.entry("glowstone", 0xFFE8B8), Map.entry("jack_o_lantern", 0xFFC060),
            Map.entry("lit_furnace", 0xFFB060), Map.entry("ender_chest", 0x60FFC8), Map.entry("brewing_stand", 0xFFE070),
            Map.entry("lit_redstone_lamp", 0xFFE8C8));
    private static final int[] DYE_RGB = {0xF9FFFE, 0xF9801D, 0xC74EBD, 0x3AB3DA, 0xFED83D, 0x80C71F, 0xF38BAA, 0x474F52,
            0x9D9D97, 0x169C9C, 0x8932B8, 0x3C44AA, 0x835432, 0x5E7C16, 0xB02E26, 0x1D1D21};

    /** TNT: set when FunctionalBlocks registers it. */
    public static Block TNT;
    public static Block AIR, STONE, GRASS, DIRT, COBBLESTONE, BEDROCK, SAND, GRAVEL, WATER, LAVA, OAK_LOG, OAK_LEAVES,
            BIRCH_LOG, BIRCH_LEAVES, SPRUCE_LOG, SPRUCE_LEAVES, SANDSTONE, TALL_GRASS, DANDELION, POPPY, CACTUS, SNOW,
            SNOW_LAYER, ICE, COAL_ORE, IRON_ORE, GOLD_ORE, DIAMOND_ORE, DEAD_BUSH, CLAY, OAK_PLANKS, GLASS, TORCH, STONE_BRICKS,
            BRICKS, FERN, REEDS;

    private Blocks() {}

    public static synchronized Block register(Block b) {
        if (BY_NAME.containsKey(b.name)) throw new IllegalArgumentException("Duplicate block name: " + b.name);
        if (nextId >= MAX_BLOCKS) throw new IllegalStateException("Too many blocks");
        b.id = nextId++;
        BY_ID[b.id] = b;
        BY_NAME.put(b.name, b);
        return b;
    }

    public static Block byId(int id) { Block b = BY_ID[id & (MAX_BLOCKS - 1)]; return b == null ? AIR : b; }

    public static Block byName(String name) {
        Block b = BY_NAME.get(name);
        if (b == null && name.indexOf(':') < 0) b = BY_NAME.get("minecraft:" + name);
        if (b == null && name.startsWith("minecraft:")) b = BY_NAME.get(name.substring(10));
        return b;
    }

    public static Collection<Block> all() { return Collections.unmodifiableCollection(BY_NAME.values()); }

    public static int count() { return nextId; }

    /** RGB colour of the light a block gives (white unless it has its own colour). */
    public static int lightColor(Block b) { return b.lightColor >= 0 ? b.lightColor : LIGHT_COLORS.getOrDefault(b.name, 0xFFFFFF); }

    /** Rebuilds the fast lookup tables; call after all registrations. */
    public static void freeze() {
        for (int i = 0; i < nextId; i++) {
            Block b = BY_ID[i];
            OPAQUE[i] = b.isOpaqueCube();
            OPACITY[i] = (byte) Math.max(0, Math.min(15, b.lightOpacity));
            EMISSION[i] = (byte) Math.max(0, Math.min(15, b.lightEmission));
            int col = b.lightColor >= 0 ? b.lightColor : LIGHT_COLORS.getOrDefault(b.name, 0xFFFFFF);
            int mx = Math.max(1, Math.max((col >> 16) & 255, Math.max((col >> 8) & 255, col & 255)));
            int filter = b.lightFilter;
            if (filter < 0 && b.name.contains("stained_glass")) {
                for (int k = 0; k < 16; k++) if (b.name.startsWith(COLOR_NAMES[k] + "_")) filter = DYE_RGB[k];
                if (b.name.startsWith("light_gray_") || b.name.startsWith("silver_")) filter = DYE_RGB[8];
            }
            for (int ch = 0; ch < 3; ch++) {
                int comp = (col >> (16 - ch * 8)) & 255;
                EMISSION_RGB[ch][i] = (byte) Math.round(EMISSION[i] * comp / (float) mx);
                int fc = filter < 0 ? 255 : (filter >> (16 - ch * 8)) & 255;
                int fmx = filter < 0 ? 255 : Math.max(1, Math.max((filter >> 16) & 255, Math.max((filter >> 8) & 255, filter & 255)));
                FILTER_RGB[ch][i] = (byte) Math.round((1 - fc / (float) fmx) * 8);
            }
        }
        // collision shapes of every block (and how far they reach out of their cells): built here once at start,
        // not as a hitch on the first tick of the world
        Block.collisionReach();
    }

    // ------------------------------------------------------------------------------------------
    // helpers
    private static Block cube(String name, String tex) { Block b = new Block(name); Arrays.fill(b.textures, tex); return register(b); }

    private static Block gravity(String name, String tex) { Block b = new GravityBlock(name); Arrays.fill(b.textures, tex); return register(b); }

    private static Block column(String name, String side, String top, String bottom) {
        Block b = new Block(name);
        Arrays.fill(b.textures, side);
        b.textures[Dir.UP] = top; b.textures[Dir.DOWN] = bottom;
        return register(b);
    }

    private static Block pillar(String name, String side, String end) {
        Block b = new Block(name);
        Arrays.fill(b.textures, side);
        b.textures[Dir.UP] = end; b.textures[Dir.DOWN] = end;
        b.placement = Placement.AXIS;
        return register(b);
    }

    private static Block facing(String name, String front, String side, String top, String bottom) {
        Block b = new Block(name);
        b.textures[Dir.NORTH] = front; b.textures[Dir.EAST] = side;
        b.textures[Dir.SOUTH] = side; b.textures[Dir.WEST] = side;
        b.textures[Dir.UP] = top; b.textures[Dir.DOWN] = bottom;
        b.placement = Placement.FACING;
        return register(b);
    }

    private static Block cutout(Block b) { b.layer = Layer.CUTOUT; b.lightOpacity = 0; return b; }

    private static Block plant(String name, String tex, Tint tint) {
        Block b = new Block(name);
        Arrays.fill(b.textures, tex);
        b.shape = Shape.CROSS; b.layer = Layer.CUTOUT; b.tint = tint;
        b.solid = false; b.lightOpacity = 0; b.replaceable = tint != Tint.NONE; b.category = "Plants";
        return register(b);
    }

    private static Block shaped(String name, Shape shape, Block source, Placement p) {
        Block b = new Block(name);
        System.arraycopy(source.textures, 0, b.textures, 0, 6);
        b.shape = shape; b.placement = p; b.lightOpacity = shape == Shape.SLAB || shape == Shape.STAIRS ? 15 : 0;
        b.layer = source.layer == Layer.SOLID ? Layer.SOLID : source.layer;
        b.category = "Shapes";
        return register(b);
    }

    public static final String[] COLORS = {"white", "orange", "magenta", "light_blue", "yellow", "lime", "pink", "gray",
            "silver", "cyan", "purple", "blue", "brown", "green", "red", "black"};
    public static final String[] COLOR_NAMES = {"white", "orange", "magenta", "light_blue", "yellow", "lime", "pink", "gray",
            "light_gray", "cyan", "purple", "blue", "brown", "green", "red", "black"};

    /** Registers vanilla-like content. Texture names follow Minecraft 1.12.2 (assets/minecraft/textures/blocks). */
    public static void registerDefaults() {
        AIR = new Block("air"); AIR.shape = Shape.AIR; AIR.solid = false; AIR.lightOpacity = 0; AIR.replaceable = true; AIR.inInventory = false;
        register(AIR);

        STONE = cube("stone", "stone");
        cube("granite", "stone_granite"); cube("polished_granite", "stone_granite_smooth");
        cube("diorite", "stone_diorite"); cube("polished_diorite", "stone_diorite_smooth");
        cube("andesite", "stone_andesite"); cube("polished_andesite", "stone_andesite_smooth");
        GRASS = new FunctionalBlocks.GrassBlock();
        Arrays.fill(GRASS.textures, "grass_side"); GRASS.textures[Dir.UP] = "grass_top"; GRASS.textures[Dir.DOWN] = "dirt";
        register(GRASS);
        GRASS.sideOverlay = "grass_side_overlay"; GRASS.tint = Tint.GRASS; GRASS.category = "Natural";
        DIRT = cube("dirt", "dirt"); cube("coarse_dirt", "coarse_dirt");
        column("podzol", "dirt_podzol_side", "dirt_podzol_top", "dirt");
        COBBLESTONE = cube("cobblestone", "cobblestone");
        cube("mossy_cobblestone", "cobblestone_mossy");
        BEDROCK = cube("bedrock", "bedrock");
        SAND = gravity("sand", "sand"); gravity("red_sand", "red_sand");
        GRAVEL = gravity("gravel", "gravel");
        GOLD_ORE = cube("gold_ore", "gold_ore"); IRON_ORE = cube("iron_ore", "iron_ore");
        COAL_ORE = cube("coal_ore", "coal_ore"); DIAMOND_ORE = cube("diamond_ore", "diamond_ore");
        cube("lapis_ore", "lapis_ore"); cube("emerald_ore", "emerald_ore");

        String[][] woods = {{"oak", "oak", "oak"}, {"spruce", "spruce", "spruce"}, {"birch", "birch", "birch"},
                {"jungle", "jungle", "jungle"}, {"acacia", "acacia", "acacia"}, {"dark_oak", "big_oak", "big_oak"}};
        Block[] planks = new Block[6];
        for (int i = 0; i < woods.length; i++) {
            Block log = pillar(woods[i][0] + "_log", "log_" + woods[i][1], "log_" + woods[i][1] + "_top");
            log.category = "Natural";
            planks[i] = cube(woods[i][0] + "_planks", "planks_" + woods[i][2]);
            if (i == 0) { OAK_LOG = log; OAK_PLANKS = planks[0]; }
            if (i == 1) SPRUCE_LOG = log;
            if (i == 2) BIRCH_LOG = log;
        }
        String[] leafNames = {"oak", "spruce", "birch", "jungle", "acacia", "big_oak"};
        int[] leafTint = {-1, 0x619961, 0x80A755, -1, -1, -1};
        for (int i = 0; i < leafNames.length; i++) {
            Block l = cutout(cube(woods[i][0] + "_leaves", "leaves_" + leafNames[i]));
            l.lightOpacity = 1; l.category = "Natural";
            if (leafTint[i] < 0) l.tint = Tint.FOLIAGE; else { l.tint = Tint.FIXED; l.fixedTint = leafTint[i]; }
            if (i == 0) OAK_LEAVES = l;
            if (i == 1) SPRUCE_LEAVES = l;
            if (i == 2) BIRCH_LEAVES = l;
        }

        GLASS = cutout(cube("glass", "glass"));
        SANDSTONE = column("sandstone", "sandstone_normal", "sandstone_top", "sandstone_bottom");
        column("chiseled_sandstone", "sandstone_carved", "sandstone_top", "sandstone_top");
        column("smooth_sandstone", "sandstone_smooth", "sandstone_top", "sandstone_top");
        Block redSandstone = column("red_sandstone", "red_sandstone_normal", "red_sandstone_top", "red_sandstone_bottom");
        column("smooth_red_sandstone", "red_sandstone_smooth", "red_sandstone_top", "red_sandstone_top");

        for (int i = 0; i < 16; i++) cube(COLOR_NAMES[i] + "_wool", "wool_colored_" + COLORS[i]).category = "Colored";
        for (int i = 0; i < 16; i++) cube(COLOR_NAMES[i] + "_concrete", "concrete_" + COLORS[i]).category = "Colored";
        for (int i = 0; i < 16; i++) cube(COLOR_NAMES[i] + "_concrete_powder", "concrete_powder_" + COLORS[i]).category = "Colored";
        for (int i = 0; i < 16; i++) cube(COLOR_NAMES[i] + "_terracotta", "hardened_clay_stained_" + COLORS[i]).category = "Colored";
        cube("terracotta", "hardened_clay");
        for (int i = 0; i < 16; i++) {
            Block g = cube(COLOR_NAMES[i] + "_stained_glass", "glass_" + COLORS[i]);
            g.layer = Layer.TRANSLUCENT; g.lightOpacity = 0; g.category = "Glass";
        }

        cube("gold_block", "gold_block"); cube("iron_block", "iron_block"); cube("diamond_block", "diamond_block");
        cube("emerald_block", "emerald_block"); cube("lapis_block", "lapis_block"); cube("coal_block", "coal_block");
        cube("redstone_block", "redstone_block");
        BRICKS = cube("bricks", "brick");
        column("bookshelf", "bookshelf", "planks_oak", "planks_oak");
        cube("obsidian", "obsidian");
        Block quartz = column("quartz_block", "quartz_block_side", "quartz_block_top", "quartz_block_bottom");
        column("chiseled_quartz_block", "quartz_block_chiseled", "quartz_block_chiseled_top", "quartz_block_chiseled_top");
        pillar("quartz_pillar", "quartz_block_lines", "quartz_block_lines_top");
        STONE_BRICKS = cube("stone_bricks", "stonebrick");
        cube("mossy_stone_bricks", "stonebrick_mossy"); cube("cracked_stone_bricks", "stonebrick_cracked");
        cube("chiseled_stone_bricks", "stonebrick_carved");
        SNOW = cube("snow", "snow");
        ICE = cube("ice", "ice"); ICE.layer = Layer.TRANSLUCENT; ICE.lightOpacity = 2;
        cube("packed_ice", "ice_packed");
        CLAY = cube("clay", "clay");
        cube("netherrack", "netherrack"); cube("soul_sand", "soul_sand");
        Block glowstone = cube("glowstone", "glowstone"); glowstone.lightEmission = 15;
        Block netherBrick = cube("nether_bricks", "nether_brick");
        cube("red_nether_bricks", "red_nether_brick");
        cube("end_stone", "end_stone"); cube("end_stone_bricks", "end_bricks");
        Block purpur = cube("purpur_block", "purpur_block");
        pillar("purpur_pillar", "purpur_pillar", "purpur_pillar_top");
        cube("prismarine", "prismarine_rough"); cube("prismarine_bricks", "prismarine_bricks");
        cube("dark_prismarine", "prismarine_dark");
        cube("sea_lantern", "sea_lantern").lightEmission = 15;
        cube("redstone_lamp", "redstone_lamp_off");
        pillar("hay_block", "hay_block_side", "hay_block_top");
        pillar("bone_block", "bone_block_side", "bone_block_top");
        cube("nether_wart_block", "nether_wart_block");
        cube("magma_block", "magma").lightEmission = 3;
        facing("pumpkin", "pumpkin_face_off", "pumpkin_side", "pumpkin_top", "pumpkin_top");
        facing("jack_o_lantern", "pumpkin_face_on", "pumpkin_side", "pumpkin_top", "pumpkin_top").lightEmission = 15;
        column("melon_block", "melon_side", "melon_top", "melon_top");
        cube("sponge", "sponge");
        Block slime = cube("slime_block", "slime"); slime.layer = Layer.TRANSLUCENT; slime.lightOpacity = 0;
        CACTUS = column("cactus", "cactus_side", "cactus_top", "cactus_bottom");
        cutout(CACTUS).category = "Plants";
        CACTUS.shape = Shape.CACTUS;

        // Liquids
        WATER = cube("water", "water_still");
        WATER.shape = Shape.LIQUID; WATER.layer = Layer.TRANSLUCENT; WATER.solid = false; WATER.lightOpacity = 2;
        WATER.replaceable = true; WATER.category = "Natural"; WATER.inInventory = false;
        LAVA = cube("lava", "lava_still");
        LAVA.shape = Shape.LIQUID; LAVA.solid = false; LAVA.lightEmission = 15; LAVA.replaceable = true; LAVA.category = "Natural"; LAVA.inInventory = false;

        // Plants
        TALL_GRASS = plant("tall_grass", "tallgrass", Tint.GRASS);
        TALL_GRASS.randomHeight = true;
        FERN = plant("fern", "fern", Tint.GRASS);
        DEAD_BUSH = plant("dead_bush", "deadbush", Tint.NONE);
        DANDELION = plant("dandelion", "flower_dandelion", Tint.NONE);
        POPPY = plant("poppy", "flower_rose", Tint.NONE);
        for (String f : new String[]{"blue_orchid", "allium", "houstonia", "tulip_red", "tulip_orange", "tulip_white", "tulip_pink", "oxeye_daisy"})
            plant(f, "flower_" + f, Tint.NONE);
        String[] saplings = {"oak", "spruce", "birch", "jungle", "acacia", "roofed_oak"};
        for (int i = 0; i < saplings.length; i++) register(new FunctionalBlocks.SaplingBlock(saplings[i] + "_sapling", "sapling_" + saplings[i], i));
        plant("red_mushroom", "mushroom_red", Tint.NONE);
        plant("brown_mushroom", "mushroom_brown", Tint.NONE);
        REEDS = plant("sugar_cane", "reeds", Tint.GRASS);
        REEDS.replaceable = false; REEDS.inInventory = false; REEDS.dropItem = "sugar_cane";

        // Torch
        TORCH = new Block("torch");
        Arrays.fill(TORCH.textures, "torch_on");
        TORCH.shape = Shape.TORCH; TORCH.layer = Layer.CUTOUT; TORCH.solid = false; TORCH.lightOpacity = 0;
        TORCH.lightEmission = 14; TORCH.placement = Placement.TORCH; TORCH.category = "Decoration";
        register(TORCH);

        // Snow layer
        SNOW_LAYER = new Block("snow_layer");
        Arrays.fill(SNOW_LAYER.textures, "snow");
        SNOW_LAYER.shape = Shape.SNOW_LAYER; SNOW_LAYER.lightOpacity = 0; SNOW_LAYER.replaceable = true; SNOW_LAYER.category = "Natural";
        register(SNOW_LAYER);

        // Slabs & stairs
        Object[][] shapedSources = {
                {"stone", byName("stone")}, {"cobblestone", COBBLESTONE}, {"stone_brick", STONE_BRICKS}, {"brick", BRICKS},
                {"sandstone", SANDSTONE}, {"red_sandstone", redSandstone}, {"quartz", quartz}, {"nether_brick", netherBrick},
                {"purpur", purpur}, {"oak", planks[0]}, {"spruce", planks[1]}, {"birch", planks[2]}, {"jungle", planks[3]},
                {"acacia", planks[4]}, {"dark_oak", planks[5]}, {"polished_andesite", byName("polished_andesite")},
                {"polished_diorite", byName("polished_diorite")}, {"polished_granite", byName("polished_granite")},
                {"mossy_cobblestone", byName("mossy_cobblestone")}, {"prismarine", byName("prismarine")}};
        for (Object[] s : shapedSources) {
            Block src = (Block) s[1];
            Block slab = shaped(s[0] + "_slab", Shape.SLAB, src, Placement.SLAB);
            if (src == STONE) { slab.textures[Dir.UP] = slab.textures[Dir.DOWN] = "stone_slab_top"; Arrays.fill(slab.textures, 2, 6, "stone_slab_side"); }
            shaped(s[0] + "_stairs", Shape.STAIRS, src, Placement.STAIRS);
        }
        for (int i = 0; i < 16; i++) shaped(COLOR_NAMES[i] + "_concrete_slab", Shape.SLAB, byName(COLOR_NAMES[i] + "_concrete"), Placement.SLAB);

        // Fences, walls, panes, carpets
        String[] fenceNames = {"oak", "spruce", "birch", "jungle", "acacia", "dark_oak"};
        for (int i = 0; i < 6; i++) {
            Block f = shaped(fenceNames[i] + "_fence", Shape.FENCE, planks[i], Placement.NONE);
            f.connectGroup = "fence"; f.lightOpacity = 0;
        }
        Block nf = shaped("nether_brick_fence", Shape.FENCE, netherBrick, Placement.NONE); nf.connectGroup = "fence"; nf.lightOpacity = 0;
        Block wall = shaped("cobblestone_wall", Shape.WALL, COBBLESTONE, Placement.NONE); wall.connectGroup = "wall"; wall.lightOpacity = 0;
        Block mwall = shaped("mossy_cobblestone_wall", Shape.WALL, byName("mossy_cobblestone"), Placement.NONE); mwall.connectGroup = "wall"; mwall.lightOpacity = 0;

        Block pane = new Block("glass_pane");
        Arrays.fill(pane.textures, "glass"); pane.textures[Dir.UP] = "glass_pane_top";
        pane.shape = Shape.PANE; pane.layer = Layer.CUTOUT; pane.lightOpacity = 0; pane.connectGroup = "pane"; pane.category = "Glass";
        register(pane);
        Block bars = new Block("iron_bars");
        Arrays.fill(bars.textures, "iron_bars");
        bars.shape = Shape.PANE; bars.layer = Layer.CUTOUT; bars.lightOpacity = 0; bars.connectGroup = "pane"; bars.category = "Decoration";
        register(bars);
        for (int i = 0; i < 16; i++) {
            Block p = new Block(COLOR_NAMES[i] + "_stained_glass_pane");
            Arrays.fill(p.textures, "glass_" + COLORS[i]); p.textures[Dir.UP] = "glass_pane_top_" + COLORS[i];
            p.shape = Shape.PANE; p.layer = Layer.TRANSLUCENT; p.lightOpacity = 0; p.connectGroup = "pane"; p.category = "Glass";
            register(p);
        }
        for (int i = 0; i < 16; i++) {
            Block c = new Block(COLOR_NAMES[i] + "_carpet");
            Arrays.fill(c.textures, "wool_colored_" + COLORS[i]);
            c.shape = Shape.CARPET; c.lightOpacity = 0; c.category = "Decoration";
            register(c);
        }

        int base = nextId;
        applySurvivalProps(base);
        FunctionalBlocks.register();
    }

    /** Minecraft 1.12 hardness, tools, drops and creative tabs for the basic blocks (ids below 'count'). */
    private static void applySurvivalProps(int count) {
        Item.ToolType PICK = Item.ToolType.PICKAXE, AXE = Item.ToolType.AXE, SHOVEL = Item.ToolType.SHOVEL;
        for (int i = 0; i < count; i++) {
            Block b = BY_ID[i];
            String n = b.name;
            // creative tab
            if (b.shape == Shape.CROSS || n.endsWith("_leaves") || b.shape == Shape.PANE || b.shape == Shape.FENCE || b.shape == Shape.WALL
                    || b.shape == Shape.CARPET || b.shape == Shape.SNOW_LAYER || b.shape == Shape.TORCH || b.shape == Shape.CACTUS) b.tab = "decoration";
            else b.tab = "building";
            if (n.equals("redstone_block") || n.equals("redstone_lamp")) b.tab = "redstone";
            // defaults
            b.hardness = 1.5f; b.effectiveTool = null; b.requiresTool = false; b.harvestLevel = 0;
            boolean wood = n.contains("oak") || n.contains("spruce") || n.contains("birch") || n.contains("jungle") || n.contains("acacia");
            if (b.shape == Shape.CROSS) { b.hardness = 0; b.needsSupport = true; }
            else if (b.shape == Shape.TORCH) { b.hardness = 0; b.needsSupport = true; }
            else if (b.shape == Shape.LIQUID) { b.hardness = 100; b.dropItem = ""; }
            else if (n.endsWith("_leaves")) { b.hardness = 0.2f; b.dropItem = n.startsWith("dark_oak") ? "roofed_oak_sapling" : n.replace("_leaves", "_sapling"); b.dropChance = 0.05f; }
            else if (n.endsWith("_log") || n.endsWith("_planks") || (wood && (b.shape == Shape.SLAB || b.shape == Shape.STAIRS || b.shape == Shape.FENCE))) { b.hardness = 2; b.effectiveTool = AXE; }
            else if (n.endsWith("_wool")) { b.hardness = 0.8f; b.effectiveTool = Item.ToolType.SHEARS; }
            else if (n.endsWith("_carpet")) b.hardness = 0.1f;
            else if (n.contains("glass")) { b.hardness = 0.3f; b.fragile = true; }
            else if (n.equals("grass")) { b.hardness = 0.6f; b.effectiveTool = SHOVEL; b.dropItem = "dirt"; }
            else if (n.equals("dirt") || n.equals("coarse_dirt") || n.equals("sand") || n.equals("red_sand") || n.endsWith("concrete_powder") || n.equals("soul_sand")) { b.hardness = 0.5f; b.effectiveTool = SHOVEL; }
            else if (n.equals("podzol")) { b.hardness = 0.5f; b.effectiveTool = SHOVEL; b.dropItem = "dirt"; }
            else if (n.equals("gravel")) { b.hardness = 0.6f; b.effectiveTool = SHOVEL; }
            else if (n.equals("clay")) { b.hardness = 0.6f; b.effectiveTool = SHOVEL; b.dropItem = "clay_ball"; b.dropMin = b.dropMax = 4; }
            else if (n.equals("snow")) { b.hardness = 0.2f; b.effectiveTool = SHOVEL; b.requiresTool = true; b.dropItem = "snowball"; b.dropMin = b.dropMax = 4; }
            else if (n.equals("snow_layer")) { b.hardness = 0.1f; b.effectiveTool = SHOVEL; b.requiresTool = true; b.dropItem = "snowball"; }
            else if (n.equals("ice") || n.equals("packed_ice")) { b.hardness = 0.5f; b.effectiveTool = PICK; b.fragile = true; }
            else if (n.equals("bedrock")) b.hardness = -1;
            else if (n.equals("obsidian")) { b.hardness = 50; b.effectiveTool = PICK; b.requiresTool = true; b.harvestLevel = 3; }
            else if (n.endsWith("_ore")) {
                b.hardness = 3; b.effectiveTool = PICK; b.requiresTool = true;
                switch (n) {
                    case "coal_ore" -> b.dropItem = "coal";
                    case "iron_ore" -> b.harvestLevel = 1;
                    case "lapis_ore" -> { b.harvestLevel = 1; b.dropItem = "lapis_lazuli"; b.dropMin = 4; b.dropMax = 8; }
                    case "diamond_ore" -> { b.harvestLevel = 2; b.dropItem = "diamond"; }
                    case "emerald_ore" -> { b.harvestLevel = 2; b.dropItem = "emerald"; }
                    default -> b.harvestLevel = 2;
                }
            }
            else if (n.equals("gold_block") || n.equals("diamond_block") || n.equals("emerald_block")) { b.hardness = n.equals("gold_block") ? 3 : 5; b.effectiveTool = PICK; b.requiresTool = true; b.harvestLevel = 2; }
            else if (n.equals("iron_block") || n.equals("lapis_block")) { b.hardness = n.equals("iron_block") ? 5 : 3; b.effectiveTool = PICK; b.requiresTool = true; b.harvestLevel = 1; }
            else if (n.equals("coal_block") || n.equals("redstone_block")) { b.hardness = 5; b.effectiveTool = PICK; b.requiresTool = true; }
            else if (n.equals("iron_bars")) { b.hardness = 5; b.effectiveTool = PICK; b.requiresTool = true; }
            else if (n.equals("bookshelf")) { b.hardness = 1.5f; b.effectiveTool = AXE; b.dropItem = "book"; b.dropMin = b.dropMax = 3; }
            else if (n.equals("glowstone")) { b.hardness = 0.3f; b.dropItem = "glowstone_dust"; b.dropMin = 2; b.dropMax = 4; }
            else if (n.equals("sea_lantern")) { b.hardness = 0.3f; b.dropItem = "prismarine_crystals"; b.dropMin = 2; b.dropMax = 3; }
            else if (n.equals("melon_block")) { b.hardness = 1; b.effectiveTool = AXE; b.dropItem = "melon"; b.dropMin = 3; b.dropMax = 7; }
            else if (n.equals("pumpkin") || n.equals("jack_o_lantern")) { b.hardness = 1; b.effectiveTool = AXE; }
            else if (n.equals("hay_block")) b.hardness = 0.5f;
            else if (n.equals("sponge") || n.equals("slime_block")) b.hardness = n.equals("sponge") ? 0.6f : 0;
            else if (n.equals("cactus")) { b.hardness = 0.4f; b.needsSupport = true; }
            else if (n.equals("redstone_lamp") || n.equals("netherrack") || n.equals("magma_block")) { b.hardness = n.equals("redstone_lamp") ? 0.3f : 0.5f; if (!n.equals("redstone_lamp")) { b.effectiveTool = PICK; b.requiresTool = true; } }
            else { // stone-like: needs a pickaxe
                b.effectiveTool = PICK; b.requiresTool = true;
                if (n.equals("stone")) { b.dropItem = "cobblestone"; }
                else if (n.contains("cobblestone") || n.contains("brick") || n.equals("bone_block")) b.hardness = 2;
                else if (n.contains("sandstone") || n.contains("quartz") || n.equals("end_stone_bricks")) b.hardness = 0.8f;
                else if (n.contains("terracotta")) b.hardness = 1.25f;
                else if (n.contains("concrete")) b.hardness = 1.8f;
                else if (n.equals("end_stone")) b.hardness = 3;
                else if (n.equals("nether_wart_block")) { b.hardness = 1; b.requiresTool = false; b.effectiveTool = null; }
            }
            if (n.equals("tall_grass") || n.equals("fern")) { b.dropItem = "wheat_seeds"; b.dropChance = 0.125f; }
            if (n.equals("dead_bush")) { b.dropItem = "stick"; b.dropMin = 0; b.dropMax = 2; }
        }
    }
}
