package derpcraft.item;

import derpcraft.block.Block;
import derpcraft.game.Raycast;
import derpcraft.game.Session;

/**
 * An item type. Every inventory-visible block has an item with the same name; other items (tools, food, materials)
 * use a texture from assets/minecraft/textures/items/. Subclass and override {@link #use} for custom behaviour.
 */
public class Item {
    public enum ToolType { PICKAXE, AXE, SHOVEL, HOE, SWORD, SHEARS }

    public int id = -1;
    public final String name;
    public String displayName;
    /** Item texture name ("items/stick"), or null to render the block. */
    public String texture;
    /** Block placed by this item (block items, doors, signs, seeds...). */
    public Block block;
    public int maxStack = 64;
    public int maxDamage;
    public ToolType tool;
    public int toolLevel;          // 0 wood/gold, 1 stone, 2 iron, 3 diamond
    public float toolSpeed = 1;
    public float attackDamage = 1;
    public int food;
    public float saturation;
    public boolean alwaysEdible;
    public ItemStack container;    // left behind after use (bowl, bucket)
    public int fuel;               // furnace burn time in ticks
    public String tab = "misc";    // creative tab id
    public int armorSlot = -1;     // 0 head .. 3 feet
    public int armorPoints;
    public boolean inCreative = true;
    /** NPCs holding it use it at their target: projectile, speed, ticks between uses, range (set by ItemBuilder). */
    public String npcProjectile;
    public float npcSpeed = 1, npcRange = 16;
    public int npcCooldown = 30;
    public derpcraft.api.ItemBuilder.NpcUse npcUse;
    /** Colour multiplied over a texture item (spawn eggs). */
    public int tint = 0xFFFFFF;
    /** Extra tooltip lines (mods). */
    public java.util.List<String> tooltip;

    public Item(String name) {
        this.name = name;
        this.displayName = Block.prettify(name);
    }

    public boolean isBlockItem() { return block != null && texture == null; }

    public boolean isFood() { return food > 0; }

    /** Further tool types this item counts as (multitools/paxels from mods), or null. See {@link #isTool}. */
    public java.util.EnumSet<ToolType> extraTools;

    /** Whether the item works as the given tool type (its main type or one of {@link #extraTools}). */
    public boolean isTool(ToolType t) { return t != null && (tool == t || extraTools != null && extraTools.contains(t)); }

    /** True for any kind of tool or weapon (they wear out when used). */
    public boolean isAnyTool() { return tool != null || extraTools != null && !extraTools.isEmpty(); }

    /** Plants, vines, leaves and gourds: swords cut them faster (Minecraft 1.12 materials). */
    private static boolean swordMaterial(Block b) {
        String n = b.name;
        return b.shape == Block.Shape.CROSS || n.endsWith("leaves") || n.endsWith("leaves2") || n.equals("vine")
                || n.equals("pumpkin") || n.equals("melon_block") || n.equals("jack_o_lantern") || n.equals("cocoa") || n.endsWith("_leaves");
    }

    private static boolean isLeaves(Block b) { return b.name.endsWith("leaves") || b.name.endsWith("leaves2"); }

    /** Mining speed against a block (the best of all tool types the item counts as). */
    public float speedAgainst(Block b) {
        if (!isAnyTool()) return 1;
        float best = 1;
        if (isTool(ToolType.SWORD)) best = Math.max(best, b.name.equals("web") ? 15 : swordMaterial(b) ? 1.5f : 1);
        if (isTool(ToolType.SHEARS))
            best = Math.max(best, b.name.equals("web") || isLeaves(b) ? 15 : b.name.endsWith("_wool") ? 5 : b.name.equals("vine") ? 2 : 1);
        ToolType eff = b.effectiveTool;
        if (eff != null && eff != ToolType.SWORD && eff != ToolType.SHEARS && isTool(eff)) best = Math.max(best, toolSpeed);
        return best;
    }

    /** Whether this item can harvest (get drops from) a block that requires a tool. */
    public boolean canHarvest(Block b) {
        if (!b.requiresTool) return true;
        if (b.name.equals("web")) return isTool(ToolType.SWORD) || isTool(ToolType.SHEARS);
        if (b.effectiveTool == null) return isAnyTool();   // "needs a tool" without saying which: any tool works
        return isTool(b.effectiveTool) && toolLevel >= b.harvestLevel;
    }

    /** Durability used up when this item breaks a block (vanilla: tools 1, swords 2, everything else 0). */
    public int wearOnBreak(Block b) {
        if (maxDamage <= 0 || !isAnyTool()) return 0;
        if (tool == ToolType.SHEARS && extraTools == null)   // shears only wear on what they are meant for
            return isLeaves(b) || b.name.equals("web") || b.name.endsWith("_wool") || b.name.equals("vine") || b.shape == Block.Shape.CROSS ? 1 : 0;
        if (b.hardness == 0) return 0;
        return tool == ToolType.SWORD ? 2 : 1;
    }

    /** Durability used up when this item hits a creature (vanilla: swords 1, other tools 2, everything else 0). */
    public int wearOnHit() {
        if (maxDamage <= 0 || !isAnyTool()) return 0;
        return tool == ToolType.SWORD ? 1 : 2;
    }

    /**
     * Right click (on a block or in the air). Default: place the item's block.
     * @return true if the click was consumed
     */
    public boolean use(Session s, ItemStack stack, Raycast.Hit hit) {
        if (block != null && hit != null) return s.placeBlock(block, stack, hit);
        return false;
    }

    @Override public String toString() { return name; }
}
