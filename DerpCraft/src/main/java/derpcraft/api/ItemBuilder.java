package derpcraft.api;

import derpcraft.item.Item;
import derpcraft.item.ItemStack;
import derpcraft.item.Items;
import derpcraft.item.ModItem;

import java.util.ArrayList;
import java.util.List;

/**
 * Builds an item ("modid:name"). Call from {@link DerpMod#initItems}.
 *
 * <pre>
 * ctx.item("magic_wand").texture("magic_wand").maxStack(1).tooltip("Right click a block")
 *    .onUse((game, stack, block) -> { if (block == null) return false; block.setBlock(game.block("gold_block"), 0); return true; })
 *    .register();
 * </pre>
 */
public final class ItemBuilder {
    /** Right click with the item. 'block' is the targeted block or null. Return true when handled. */
    public interface Use { boolean onUse(GameApi game, ItemStack stack, BlockContext block); }

    /** What an NPC holding the item does at its target: from its eyes (x, y, z) at the target (tx, ty, tz). */
    public interface NpcUse { void use(GameApi game, String npc, double x, double y, double z, double tx, double ty, double tz); }

    private final ModItem it;
    private final String modId;

    ItemBuilder(String modId, String name) {
        this.modId = modId;
        it = new ModItem(name.contains(":") ? name : modId + ":" + name);
        it.displayName = derpcraft.block.Block.prettify(name);
        it.texture = modId + ":items/" + (name.contains(":") ? name.substring(name.indexOf(':') + 1) : name);
    }

    /** Texture from assets/&lt;modid&gt;/textures/items/&lt;name&gt;.png ("mymod:ruby"), or a vanilla item texture ("stick"). */
    public ItemBuilder texture(String tex) {
        it.texture = tex.contains(":") ? tex.substring(0, tex.indexOf(':') + 1) + "items/" + tex.substring(tex.indexOf(':') + 1) : modId + ":items/" + tex;
        return this;
    }

    /** Uses a vanilla Minecraft item texture (textures/items/&lt;name&gt;.png of the resource pack). */
    public ItemBuilder vanillaTexture(String tex) { it.texture = "items/" + tex; return this; }

    public ItemBuilder displayName(String n) { it.displayName = n; return this; }
    public ItemBuilder maxStack(int n) { it.maxStack = Math.max(1, Math.min(64, n)); return this; }
    public ItemBuilder tab(String tab) { it.tab = tab; return this; }
    public ItemBuilder food(int hunger, float saturation) { it.food = hunger; it.saturation = saturation; return this; }
    public ItemBuilder alwaysEdible() { it.alwaysEdible = true; return this; }
    public ItemBuilder fuel(int burnTicks) { it.fuel = burnTicks; return this; }

    /** A tool: type, level (0 wood/gold, 1 stone, 2 iron, 3 diamond), mining speed, durability. */
    public ItemBuilder tool(Item.ToolType type, int level, float speed, int durability) {
        it.tool = type; it.toolLevel = level; it.toolSpeed = speed; it.maxDamage = durability; it.maxStack = 1;
        return this;
    }

    /**
     * The item also works as these tool types (multitools, paxels): mining speed, harvesting and right-click actions
     * (hoe tilling, shears) use the best matching type. Call after {@link #tool}. Example:
     * {@code ctx.item("paxel").tool(PICKAXE, 2, 6, 750).alsoTool(AXE, SHOVEL).register();}
     */
    public ItemBuilder alsoTool(Item.ToolType... types) {
        if (it.extraTools == null) it.extraTools = java.util.EnumSet.noneOf(Item.ToolType.class);
        for (Item.ToolType t : types) if (t != null && t != it.tool) it.extraTools.add(t);
        if (it.tool == null && !it.extraTools.isEmpty()) it.tool = it.extraTools.iterator().next();
        it.maxStack = 1;
        return this;
    }

    public ItemBuilder durability(int uses) { it.maxDamage = uses; it.maxStack = 1; return this; }
    public ItemBuilder attackDamage(float d) { it.attackDamage = d; return this; }

    /** Armour: slot 0 head, 1 chest, 2 legs, 3 feet; points like vanilla (iron chestplate = 6). */
    public ItemBuilder armor(int slot, int points, int durability) { it.armorSlot = slot; it.armorPoints = points; it.maxDamage = durability; it.maxStack = 1; return this; }

    /** Item left behind after use (like the bowl of a stew). */
    public ItemBuilder leaves(String item) { it.container = new ItemStack(Items.get(item), 1); return this; }

    public ItemBuilder tooltip(String... lines) {
        if (it.tooltip == null) it.tooltip = new ArrayList<>();
        it.tooltip.addAll(List.of(lines));
        return this;
    }

    public ItemBuilder hidden() { it.inCreative = false; return this; }

    public ItemBuilder onUse(Use handler) { it.onUse = handler; return this; }

    /** One item is used up when the handler returns true (outside creative). */
    public ItemBuilder consumeOnUse() { it.consumeOnUse = true; return this; }

    /** Right click throws the projectile (one item is used up in survival), like snowballs. */
    public ItemBuilder throwsProjectile(String projectile, float speed) {
        it.onUse = (game, stack, block) -> { game.shootFromPlayer(projectile, speed); return true; };
        it.npcProjectile = projectile; it.npcSpeed = speed; it.npcCooldown = 40; it.npcRange = 14;
        it.consumeOnUse = true;
        return this;
    }

    /** Right click shoots the projectile, using one 'ammo' item from the inventory (null = free) and waiting 'cooldown' ticks between shots. */
    public ItemBuilder shootsProjectile(String projectile, float speed, String ammo, int cooldown) {
        final long[] last = {0};
        it.onUse = (game, stack, block) -> {
            long now = System.currentTimeMillis();
            if (now - last[0] < cooldown * 50L) return true;
            if (ammo != null && !game.gameMode().equals("creative") && game.playerInventory().remove(ammo, 1) < 1) { game.chat("\u00a7cNo " + ammo); return true; }
            last[0] = now;
            game.shootFromPlayer(projectile, speed);
            return true;
        };
        it.maxStack = 1;
        it.npcProjectile = projectile; it.npcSpeed = speed; it.npcCooldown = Math.max(4, cooldown); it.npcRange = 24;
        return this;
    }

    /**
     * How NPCs use the item when they fight (aggressive NPCs, guards): throws / shoots are set automatically by
     * {@link #throwsProjectile} and {@link #shootsProjectile}; this sets range and ticks between uses.
     */
    public ItemBuilder npcUse(float range, int cooldown) { it.npcRange = range; it.npcCooldown = cooldown; return this; }

    /** Your own NPC use (a gun with a muzzle flash, a spell...). */
    public ItemBuilder npcUse(float range, int cooldown, NpcUse handler) { it.npcRange = range; it.npcCooldown = cooldown; it.npcUse = handler; return this; }

    public Item register() { return Items.register(it); }
}
