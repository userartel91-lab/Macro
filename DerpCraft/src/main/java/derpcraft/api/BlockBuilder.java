package derpcraft.api;

import derpcraft.block.Block;
import derpcraft.block.Blocks;
import derpcraft.block.Box;
import derpcraft.block.Dir;
import derpcraft.block.ModBlock;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Fluent builder for new blocks.
 * <pre>
 * ctx.registerBlock("marble").texture("mymod:marble").register();
 * ctx.registerBlock("lamp").texture("mymod:lamp").light(15).register();
 * ctx.registerBlock("marble_slab").texture("mymod:marble").shape(Block.Shape.SLAB).register();
 * ctx.registerBlock("hedge").texture("leaves_oak").cutout().tint(Block.Tint.FOLIAGE).register();
 * </pre>
 * Texture names without a namespace refer to Minecraft 1.12.2 textures (e.g. "stone", "planks_oak").
 *
 * <p>Features need no subclassing:
 * <pre>
 * ctx.registerBlock("crate").texture("mymod:crate").storage(12).register();             // 12-slot storage, GUI 2x6
 * ctx.registerBlock("suitcase").texture("mymod:case").storage(9).persistent().register(); // keeps contents when broken
 * ctx.registerBlock("grinder").texture("mymod:grinder").facesPlayer()
 *    .machine("mymod:grinding").cookTime(100).litFace("mymod:grinder_on").register();      // furnace-like machine
 * ctx.registerBlock("street_sign").texture("mymod:sign").sign(4).textColor(0xFFFFFF).register();  // modern sign
 * ctx.registerBlock("screen").texture("mymod:screen").facesPlayer().textDisplay(3).textGlow().register();
 * ctx.registerBlock("lamp").texture("mymod:lamp").onUse((block, game) -> { ... return true; }).register();
 * </pre>
 */
public final class BlockBuilder {
    private final ModBlock b;
    private final List<Object[]> model = new ArrayList<>();
    private String litFront;
    private int litLight = 13;

    BlockBuilder(String fullName) { b = new ModBlock(fullName); }

    /** Same texture on all six faces. */
    public BlockBuilder texture(String tex) { Arrays.fill(b.textures, tex); return this; }

    /** Different textures for top, sides and bottom (like a log or a grass block). */
    public BlockBuilder textures(String top, String side, String bottom) {
        Arrays.fill(b.textures, side);
        b.textures[Dir.UP] = top;
        b.textures[Dir.DOWN] = bottom;
        return this;
    }

    /** Texture of a single face: Dir.DOWN, Dir.UP, Dir.NORTH, Dir.SOUTH, Dir.WEST, Dir.EAST. */
    public BlockBuilder face(int dir, String tex) { b.textures[dir] = tex; return this; }

    public BlockBuilder displayName(String name) { b.displayName = name; return this; }

    /** CUBE (default), SLAB, STAIRS, CROSS (plant), TORCH, PANE, FENCE, WALL, CARPET... */
    public BlockBuilder shape(Block.Shape shape) {
        b.shape = shape;
        switch (shape) {
            case SLAB -> b.placement = Block.Placement.SLAB;
            case STAIRS -> b.placement = Block.Placement.STAIRS;
            case TORCH -> { b.placement = Block.Placement.TORCH; b.layer = Block.Layer.CUTOUT; b.solid = false; b.lightOpacity = 0; }
            case CROSS -> { b.layer = Block.Layer.CUTOUT; b.solid = false; b.lightOpacity = 0; }
            case PANE, FENCE, WALL -> { b.lightOpacity = 0; b.connectGroup = shape.name().toLowerCase(); }
            case CARPET, SNOW_LAYER -> b.lightOpacity = 0;
            default -> {}
        }
        return this;
    }

    /** Light emitted, 0..15 (torch = 14, glowstone = 15). */
    public BlockBuilder light(int level) { b.lightEmission = level; return this; }

    /** Damage bullets must do to break it (-1 = automatic: from the hardness, cameras at once; 0 = never). */
    public BlockBuilder bulletHealth(float damage) { b.bulletHealth = damage; return this; }

    /** Texture has fully transparent pixels (glass, leaves). */
    public BlockBuilder cutout() { b.layer = Block.Layer.CUTOUT; b.lightOpacity = 0; return this; }

    /** Texture is semi-transparent (stained glass, ice, water). */
    public BlockBuilder translucent() { b.layer = Block.Layer.TRANSLUCENT; b.lightOpacity = 0; return this; }

    /** Biome tint: GRASS, FOLIAGE, or FIXED with {@link #tintColor(int)}. */
    public BlockBuilder tint(Block.Tint tint) { b.tint = tint; return this; }

    public BlockBuilder tintColor(int rgb) { b.tint = Block.Tint.FIXED; b.fixedTint = rgb; return this; }

    /** Players and entities walk through it. */
    public BlockBuilder noCollision() { b.solid = false; return this; }

    /** Rotates like a log depending on the clicked face. */
    public BlockBuilder pillar() { b.placement = Block.Placement.AXIS; return this; }

    /** NORTH texture is the "front", which faces the player when placed (like a furnace). */
    public BlockBuilder facesPlayer() { b.placement = Block.Placement.FACING; return this; }

    /** Inventory tab. */
    public BlockBuilder category(String category) { b.category = category; return this; }

    /** Colour of the emitted light with Colored Light on (e.g. 0xFF4020 for red). */
    public BlockBuilder lightColor(int rgb) { b.lightColor = rgb & 0xFFFFFF; return this; }

    /** Tints light passing through this block (like stained glass), e.g. 0x3050FF. */
    public BlockBuilder lightFilter(int rgb) { b.lightFilter = rgb & 0xFFFFFF; return this; }

    /** Mining time (stone 1.5, dirt 0.5, obsidian 50; -1 = unbreakable in survival). */
    public BlockBuilder hardness(float h) { b.hardness = h; return this; }

    /** Tool that mines it fast; 'required' = no drops without it; level 0 wood, 1 stone, 2 iron, 3 diamond. */
    public BlockBuilder tool(derpcraft.item.Item.ToolType tool, int level, boolean required) {
        b.effectiveTool = tool; b.harvestLevel = level; b.requiresTool = required; return this;
    }

    /** Item dropped when mined ("" = nothing, null = the block itself). */
    public BlockBuilder drops(String item, int min, int max) { b.dropItem = item; b.dropMin = min; b.dropMax = max; return this; }

    /** Creative tab id: building, decoration, redstone, transport, misc, food, tools, combat, brewing. */
    public BlockBuilder tab(String tab) { b.tab = tab; return this; }

    public BlockBuilder hiddenFromInventory() { b.inInventory = false; return this; }

    /** Replaced when placing into it (like tall grass). */
    public BlockBuilder replaceable() { b.replaceable = true; return this; }

    /** Players can climb it like a ladder. */
    public BlockBuilder climbable() { b.climbable = true; return this; }

    /** Breaks when the block below is removed (plants, lamps standing on the ground). */
    public BlockBuilder needsSupport() { b.needsSupport = true; return this; }

    public BlockBuilder unbreakable() { b.hardness = -1; return this; }

    /** How much light it blocks, 0..15. */
    public BlockBuilder opacity(int o) { b.lightOpacity = Math.max(0, Math.min(15, o)); return this; }

    // ================================================================ model
    /**
     * Adds a model element (pixels 0..16, like a JSON block model). Faces use the block textures (NORTH = front);
     * with {@link #facesPlayer()} the model turns with the block. The block becomes non-cube automatically.
     */
    public BlockBuilder box(int x0, int y0, int z0, int x1, int y1, int z1) { model.add(new Object[]{new int[]{x0, y0, z0, x1, y1, z1}, null}); return this; }

    /** Model element with its own texture on all faces. */
    public BlockBuilder box(int x0, int y0, int z0, int x1, int y1, int z1, String texture) { model.add(new Object[]{new int[]{x0, y0, z0, x1, y1, z1}, texture}); return this; }

    // ================================================================ storage
    /** A container with 1..54 slots; the screen lays them out automatically (5 = one row, 12 = 2x6, 30 = 4x8). */
    public BlockBuilder storage(int slots) { b.storageSlots = Math.max(1, Math.min(54, slots)); return this; }

    public BlockBuilder storage(int slots, String title) { storage(slots); b.storageTitle = title; return this; }

    /** Contents stay inside the item when the block is broken (suitcases, safes, shulker boxes). */
    public BlockBuilder persistent() { b.persistent = true; return this; }

    /** Which items each slot accepts (storage). */
    public BlockBuilder slotFilter(BlockHooks.SlotFilter filter) { b.slotFilter = filter; return this; }

    // ================================================================ machines
    /** A vanilla-style furnace: smelts furnace recipes with fuel. */
    public BlockBuilder furnace() { return machine("smelting"); }

    /**
     * A furnace-like machine using recipes of 'recipeType' (add them with {@link ModContext#addMachineRecipe}).
     * Default: 1 input, fuel, 200 ticks per item.
     */
    public BlockBuilder machine(String recipeType) { b.machineType = recipeType; return this; }

    /** Default processing time in ticks (20 = 1 second). */
    public BlockBuilder cookTime(int ticks) { b.machineTicks = Math.max(1, ticks); return this; }

    /** Two input slots (alloy smelter style recipes). */
    public BlockBuilder twoInputs() { b.machineInputs = 2; return this; }

    /** Works without fuel (an "electric" machine). */
    public BlockBuilder noFuel() { b.machineFuel = false; return this; }

    /** Fuel lasts this many times longer (2 = twice as efficient as a furnace). */
    public BlockBuilder fuelEfficiency(float f) { b.fuelEfficiency = Math.max(0.05f, f); return this; }

    /** Front texture while working; the block also lights up (like lit furnaces). */
    public BlockBuilder litFace(String texture) { litFront = texture; return this; }

    /** Light level while working (default 13). */
    public BlockBuilder litLight(int level) { litLight = level; if (litFront == null) litFront = ""; return this; }

    // ================================================================ text
    /**
     * A sign with 1..8 lines: a thin board, hung on the wall when placed against a side or on a post when placed on
     * the ground. Textures: NORTH = board front, others = edges. The editor opens when placed.
     */
    public BlockBuilder sign(int lines) {
        b.textLines = Math.max(1, Math.min(8, lines));
        b.textStyle = ModBlock.TextStyle.PANEL;
        b.shape = Block.Shape.CUSTOM;
        b.solid = false; b.lightOpacity = 0; b.hardness = 1;
        b.textMaxWidth = 80;
        return this;
    }

    /** Text on the front face of the block (screens, shop signs, labels); combine with {@link #facesPlayer()}. */
    public BlockBuilder textDisplay(int lines) {
        b.textLines = Math.max(1, Math.min(8, lines));
        b.textStyle = ModBlock.TextStyle.FACE;
        if (b.placement == Block.Placement.NONE) b.placement = Block.Placement.FACING;
        return this;
    }

    public BlockBuilder textColor(int rgb) { b.textColor = rgb & 0xFFFFFF; return this; }

    /** Text shines in the dark (screens, neon). */
    public BlockBuilder textGlow() { b.textGlow = true; return this; }

    public BlockBuilder textScale(float scale) { b.textScale = Math.max(0.25f, Math.min(4, scale)); return this; }

    /** Maximum line width in font pixels (the editor stops there). */
    public BlockBuilder maxTextWidth(int px) { b.textMaxWidth = Math.max(10, px); return this; }

    /** Text area on a custom model (pixels of the block facing south): centre x, centre y, front plane z, width. */
    public BlockBuilder textArea(float centerX, float centerY, float frontZ, float width) {
        b.textX = centerX; b.textY = centerY; b.textZ = frontZ; b.textWidth = width;
        return this;
    }

    /** Don't open the text editor when placed (set the text with right click or from code). */
    public BlockBuilder noEditOnPlace() { b.editOnPlace = false; return this; }

    // ================================================================ data and handlers
    /** Gives the block a saved key/value store ({@link BlockContext#data()}) even without other features. */
    public BlockBuilder data() { b.wantsData = true; return this; }

    /** Right click. Return true when handled; otherwise storage / machine / text GUIs open as usual. */
    public BlockBuilder onUse(BlockHooks.Use h) { b.use = h; return this; }

    public BlockBuilder onPlaced(BlockHooks.Simple h) { b.placed = h; return this; }

    public BlockBuilder onRemoved(BlockHooks.Simple h) { b.removed = h; return this; }

    /** Every game tick (20 per second) while the chunk is loaded. Keep it cheap. */
    public BlockBuilder onTick(BlockHooks.Simple h) { b.tick = h; return this; }

    /** Random ticks (about every 68 seconds per block on average, like crop growth). */
    public BlockBuilder onRandomTick(BlockHooks.RandomTick h) { b.random = h; return this; }

    /** After {@link BlockContext#scheduleTick}. */
    public BlockBuilder onScheduledTick(BlockHooks.Simple h) { b.scheduled = h; return this; }

    /** Every tick while the player stands on (or in) the block. */
    public BlockBuilder onStep(BlockHooks.Simple h) { b.stepped = h; return this; }

    public BlockBuilder canPlace(BlockHooks.CanPlace h) { b.canPlace = h; return this; }

    /** Direct access for advanced tweaks. */
    public Block raw() { return b; }

    public Block register() {
        for (int i = 0; i < 6; i++) if (b.textures[i] == null) b.textures[i] = "missingno";
        if (!model.isEmpty()) {
            List<Box> boxes = new ArrayList<>();
            for (Object[] m : model) {
                int[] c = (int[]) m[0];
                Box box = new Box(c[0], c[1], c[2], c[3], c[4], c[5]);
                if (m[1] != null) box.all((String) m[1]);
                else box.face(Dir.SOUTH, b.textures[Dir.NORTH]).face(Dir.NORTH, b.textures[Dir.SOUTH]).face(Dir.WEST, b.textures[Dir.WEST])
                        .face(Dir.EAST, b.textures[Dir.EAST]).face(Dir.UP, b.textures[Dir.UP]).face(Dir.DOWN, b.textures[Dir.DOWN]);
                boxes.add(box);
            }
            b.model = boxes;
            b.shapesChanged();
            if (b.shape == Block.Shape.CUBE) { b.shape = Block.Shape.CUSTOM; if (b.lightOpacity == 15) b.lightOpacity = 0; }
        }
        Block reg = Blocks.register(b);
        b.registerEntityType();
        if (b.machineType != null && litFront != null) {
            // the lit twin: same block, glowing front, swapped in while working (like furnace / lit_furnace)
            ModBlock lit = copyAsLit();
            Blocks.register(lit);
            b.litVariant = lit;
            lit.unlitVariant = b;
            lit.litVariant = lit;
        }
        return reg;
    }

    private ModBlock copyAsLit() {
        int c = b.name.indexOf(':');
        ModBlock l = new ModBlock(c < 0 ? "lit_" + b.name : b.name.substring(0, c + 1) + "lit_" + b.name.substring(c + 1));
        System.arraycopy(b.textures, 0, l.textures, 0, 6);
        if (!litFront.isEmpty()) l.textures[Dir.NORTH] = litFront;
        l.displayName = b.displayName;
        l.shape = b.shape; l.layer = b.layer; l.tint = b.tint; l.fixedTint = b.fixedTint; l.placement = b.placement;
        l.lightEmission = Math.max(b.lightEmission, litLight); l.lightColor = b.lightColor; l.lightOpacity = b.lightOpacity;
        l.solid = b.solid; l.hardness = b.hardness; l.effectiveTool = b.effectiveTool; l.requiresTool = b.requiresTool; l.harvestLevel = b.harvestLevel;
        l.dropItem = b.dropItem != null ? b.dropItem : b.name; l.dropMin = b.dropMin; l.dropMax = b.dropMax;
        l.inInventory = false; l.tab = b.tab; l.category = b.category;
        l.use = b.use; l.placed = b.placed; l.removed = b.removed; l.tick = b.tick; l.stepped = b.stepped; l.scheduled = b.scheduled;
        l.random = b.random; l.canPlace = b.canPlace;
        l.storageSlots = b.storageSlots; l.machineType = b.machineType; l.machineTicks = b.machineTicks; l.machineInputs = b.machineInputs;
        l.machineFuel = b.machineFuel; l.fuelEfficiency = b.fuelEfficiency; l.persistent = b.persistent; l.wantsData = b.wantsData;
        l.model = b.model;
        return l;
    }
}
