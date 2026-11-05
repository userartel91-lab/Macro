package derpcraft.world.be;

import derpcraft.block.Block;
import derpcraft.block.Blocks;
import derpcraft.item.Inventory;
import derpcraft.item.ItemStack;
import derpcraft.item.Recipes;

import java.util.Map;

/** Vanilla furnace: slot 0 input, 1 fuel, 2 output; 200 ticks per item. */
public final class FurnaceEntity extends BlockEntity {
    public final Inventory items = new Inventory(3);
    public int burnTime, burnTotal, cookTime;
    public static final int COOK_TIME = 200;
    public float xp;

    public FurnaceEntity() { items.onChange = this::markDirty; }

    @Override public String type() { return "furnace"; }

    @Override public Inventory inventory() { return items; }

    @Override public boolean ticks() { return true; }

    private boolean canSmelt() {
        ItemStack in = items.get(0);
        if (in == null) return false;
        ItemStack res = Recipes.smelt(in.item);
        if (res == null) return false;
        ItemStack out = items.get(2);
        if (out == null) return true;
        return out.stacksWith(res) && out.count + res.count <= out.maxStack();
    }

    @Override public void tick() {
        boolean wasBurning = burnTime > 0;
        boolean changed = false;
        if (burnTime > 0) burnTime--;
        if (burnTime == 0 && canSmelt()) {
            ItemStack fuel = items.get(1);
            if (fuel != null && fuel.item.fuel > 0) {
                burnTime = burnTotal = fuel.item.fuel;
                ItemStack container = fuel.item.container;
                fuel.count--;
                if (fuel.count <= 0) items.set(1, container != null ? container.copy() : null);
                else items.changed();
                changed = true;
            }
        }
        if (burnTime > 0 && world != null && world.random.nextInt(160) == 0 && derpcraft.game.Session.current != null)
            derpcraft.audio.Sfx.at(derpcraft.game.Session.current, "block/furnace/fire_crackle", x + 0.5, y + 0.5, z + 0.5, 0.8f);
        if (burnTime > 0 && canSmelt()) {
            if (++cookTime >= COOK_TIME) {
                cookTime = 0;
                ItemStack in = items.get(0);
                ItemStack res = Recipes.smelt(in.item);
                ItemStack out = items.get(2);
                if (out == null) items.set(2, res.copy());
                else { out.count += res.count; items.changed(); }
                in.count--;
                if (in.count <= 0) items.set(0, null); else items.changed();
                xp += Recipes.smeltXp(in.item);
                changed = true;
            }
        } else if (cookTime > 0) {
            cookTime = Math.max(0, cookTime - 2);
        }
        boolean burning = burnTime > 0;
        if (burning != wasBurning && world != null) {
            // swap furnace <-> lit_furnace keeping orientation (lit furnaces emit light)
            int st = world.getState(x, y, z);
            Block target = Blocks.byName(burning ? "lit_furnace" : "furnace");
            if (target != null) world.setBlockKeepEntity(x, y, z, (target.id << 4) | (st & 15));
            changed = true;
        }
        if (changed) markDirty();
    }

    @Override public void save(Map<String, String> out) {
        out.put("items", items.serialize());
        out.put("burn", burnTime + "," + burnTotal + "," + cookTime);
        if (xp > 0) out.put("xp", Float.toString(xp));   // stored experience survived only until the chunk unloaded
    }

    @Override public void load(Map<String, String> in) {
        items.deserialize(in.get("items"));
        String[] b = in.getOrDefault("burn", "0,0,0").split(",");
        try {
            burnTime = Integer.parseInt(b[0].trim()); burnTotal = Integer.parseInt(b[1].trim()); cookTime = Integer.parseInt(b[2].trim());
            xp = Float.parseFloat(in.getOrDefault("xp", "0"));
        } catch (RuntimeException e) { burnTime = burnTotal = cookTime = 0; }   // damaged entry: keep the items
    }
}
