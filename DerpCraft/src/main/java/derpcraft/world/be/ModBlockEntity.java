package derpcraft.world.be;

import derpcraft.api.DataTag;
import derpcraft.block.ModBlock;
import derpcraft.item.Inventory;
import derpcraft.item.ItemStack;
import derpcraft.item.MachineRecipes;

import java.util.Arrays;
import java.util.Map;

/**
 * Block entity of mod blocks: inventory (storage or machine slots), machine progress, text lines and free-form
 * data, all driven by the {@link ModBlock}'s settings. Only ticks when the block is a machine or has a tick handler.
 */
public final class ModBlockEntity extends BlockEntity {
    public final ModBlock spec;
    public final Inventory items;
    public final String[] lines;
    public final DataTag data = new DataTag();
    public int burnTime, burnTotal, cookTime, cookTotal;
    public float xp;
    /** Players looking into the GUI (lid animations, "is open" checks for mods). */
    public int viewers;

    public ModBlockEntity(ModBlock spec) {
        this.spec = spec;
        int n = spec.inventorySize();
        items = n > 0 ? new Inventory(n) : null;
        if (items != null) items.onChange = this::markDirty;
        lines = spec.textLines > 0 ? new String[spec.textLines] : null;
        if (lines != null) Arrays.fill(lines, "");
        data.onChange = this::markDirty;
    }

    @Override public String type() { return spec.entityType(); }

    @Override public Inventory inventory() { return items; }

    @Override public boolean keepsContents() { return spec.persistent; }

    @Override public boolean ticks() { return spec.machineType != null || spec.tick != null; }

    // ---------------------------------------------------------------- machine slots
    public int fuelSlot() { return spec.machineFuel ? spec.machineInputs : -1; }

    public int outputSlot() { return spec.machineInputs + (spec.machineFuel ? 1 : 0); }

    private boolean active;

    /** True while the machine burns fuel (or processes, for machines without fuel). */
    public boolean working() { return active; }

    private MachineRecipes.Recipe recipe() {
        ItemStack a = items.get(0), b = spec.machineInputs > 1 ? items.get(1) : null;
        MachineRecipes.Recipe r = MachineRecipes.find(spec.machineType, a, b);
        if (r == null) return null;
        ItemStack out = items.get(outputSlot());
        if (out != null && (!out.stacksWith(r.out()) || out.count + r.out().count > out.maxStack())) return null;
        return r;
    }

    @Override public void tick() {
        if (spec.machineType != null) machineTick();
        if (spec.tick != null && world != null) spec.entityTick(world, x, y, z);
    }

    private void machineTick() {
        boolean wasWorking = spec.litVariant != null && world != null ? world.getBlock(x, y, z) == spec.litVariant : active;
        boolean changed = false;
        if (burnTime > 0) burnTime--;
        MachineRecipes.Recipe r = recipe();
        if (spec.machineFuel && burnTime == 0 && r != null) {
            ItemStack fuel = items.get(fuelSlot());
            if (fuel != null && fuel.item.fuel > 0) {
                burnTime = burnTotal = Math.max(1, Math.round(fuel.item.fuel * spec.fuelEfficiency));
                ItemStack container = fuel.item.container;
                fuel.count--;
                if (fuel.count <= 0) items.set(fuelSlot(), container != null ? container.copy() : null);
                else items.changed();
                changed = true;
            }
        }
        boolean powered = !spec.machineFuel || burnTime > 0;
        if (powered && r != null) {
            cookTotal = r.ticks() > 0 ? r.ticks() : spec.machineTicks;
            if (++cookTime >= cookTotal) {
                cookTime = 0;
                ItemStack out = items.get(outputSlot());
                if (out == null) items.set(outputSlot(), r.out().copy());
                else { out.count += r.out().count; items.changed(); }
                take(0, r.count1());
                if (r.in2() != null) take(1, r.count2());
                xp += r.xp();
                changed = true;
            }
        } else if (cookTime > 0) {
            cookTime = spec.machineFuel ? Math.max(0, cookTime - 2) : 0;
        }
        boolean now = spec.machineFuel ? burnTime > 0 : r != null;
        active = now;
        if (now != wasWorking && world != null && spec.litVariant != null) {
            int st = world.getState(x, y, z);
            ModBlock target = now ? spec.litVariant : spec;
            world.setBlockKeepEntity(x, y, z, (target.id << 4) | (st & 15));
            changed = true;
        }
        if (changed) markDirty();
    }

    private void take(int slot, int n) {
        ItemStack s = items.get(slot);
        if (s == null) return;
        s.count -= n;
        if (s.count <= 0) items.set(slot, s.item.container != null ? s.item.container.copy() : null);
        else items.changed();
    }

    // ---------------------------------------------------------------- portable storage
    /** Stores the contents (items, data, text) in an item stack's tag. */
    public void writeTo(ItemStack st) {
        if (items != null && !items.isEmpty()) st.tag().put("items", items.serialize());
        if (!data.isEmpty()) st.tag().putTag("data", data);
        if (lines != null) {
            StringBuilder sb = new StringBuilder();
            for (String l : lines) sb.append(l.replace("\n", " ")).append('\n');
            if (sb.toString().trim().length() > 0) st.tag().put("text", sb.toString());
        }
    }

    public void readFrom(ItemStack st) {
        DataTag t = st.tag();
        if (items != null && t.has("items")) items.deserialize(t.getString("items"));
        if (t.has("data")) data.copyFrom(t.getTag("data"));
        if (lines != null && t.has("text")) {
            String[] l = t.getString("text").split("\n", -1);
            for (int i = 0; i < lines.length && i < l.length; i++) lines[i] = l[i];
        }
        markDirty();
    }

    // ---------------------------------------------------------------- saving
    @Override public void save(Map<String, String> out) {
        if (items != null) out.put("items", items.serialize());
        if (spec.machineType != null) out.put("machine", burnTime + "," + burnTotal + "," + cookTime + "," + cookTotal + "," + xp);
        if (lines != null) for (int i = 0; i < lines.length; i++) out.put("line" + i, lines[i]);
        if (!data.isEmpty()) out.put("data", data.encode());
    }

    @Override public void load(Map<String, String> in) {
        if (items != null) items.deserialize(in.get("items"));
        String m = in.get("machine");
        if (m != null) {
            String[] p = m.split(",");
            try {
                burnTime = Integer.parseInt(p[0]); burnTotal = Integer.parseInt(p[1]); cookTime = Integer.parseInt(p[2]);
                cookTotal = Integer.parseInt(p[3]); xp = Float.parseFloat(p[4]);
            } catch (Exception ignored) {}
        }
        if (lines != null) for (int i = 0; i < lines.length; i++) lines[i] = in.getOrDefault("line" + i, "");
        DataTag d = DataTag.decode(in.get("data"));
        data.onChange = null;
        data.copyFrom(d);
        data.onChange = this::markDirty;
    }
}
