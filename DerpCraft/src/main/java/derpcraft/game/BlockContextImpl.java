package derpcraft.game;

import derpcraft.api.BlockContext;
import derpcraft.api.DataTag;
import derpcraft.api.GameApi;
import derpcraft.api.InventoryApi;
import derpcraft.block.Block;
import derpcraft.block.Blocks;
import derpcraft.item.ItemStack;
import derpcraft.world.World;
import derpcraft.world.be.ModBlockEntity;

/** A block position handed to mod handlers. */
public final class BlockContextImpl implements BlockContext {
    private final Session s;
    private final World w;
    private final int x, y, z;

    public BlockContextImpl(Session s, World w, int x, int y, int z) { this.s = s; this.w = w; this.x = x; this.y = y; this.z = z; }

    private ModBlockEntity be() { return w.getBlockEntity(x, y, z) instanceof ModBlockEntity m ? m : null; }

    @Override public GameApi game() { return s; }
    @Override public int x() { return x; }
    @Override public int y() { return y; }
    @Override public int z() { return z; }
    @Override public Block block() { return w.getBlock(x, y, z); }
    @Override public int meta() { return w.getMeta(x, y, z); }
    @Override public void setMeta(int meta) { w.setBlockKeepEntity(x, y, z, (block().id << 4) | (meta & 15)); }

    private static final DataTag EMPTY = new DataTag();

    @Override public DataTag data() { ModBlockEntity b = be(); return b == null ? EMPTY.copy() : b.data; }

    @Override public InventoryApi inventory() {
        var be = w.getBlockEntity(x, y, z);
        var inv = be == null ? null : be.inventory();
        return inv == null ? null : new InventoryView(inv);
    }

    @Override public String[] text() { ModBlockEntity b = be(); return b == null ? null : b.lines; }

    @Override public void setText(int line, String text) {
        ModBlockEntity b = be();
        if (b == null || b.lines == null || line < 0 || line >= b.lines.length) return;
        b.lines[line] = text == null ? "" : text;
        b.markDirty();
    }

    @Override public boolean working() { ModBlockEntity b = be(); return b != null && b.working(); }
    @Override public void scheduleTick(int ticks) { w.scheduleTick(x, y, z, Math.max(1, ticks)); }
    @Override public void drop(ItemStack stack) { w.dropItem(x + 0.5, y + 1.1, z + 0.5, stack); }

    @Override public void setBlock(Block block, int meta) {
        if (block == null) block = Blocks.byId(0);
        w.setBlock(x, y, z, block, meta);
    }

    @Override public void breakBlock(boolean drops) { if (s != null) s.harvest(x, y, z, drops); else w.setBlock(x, y, z, 0); }

    @Override public int light() { return Math.max(w.getSky(x, y, z), w.getBlockLight(x, y, z)); }

    @Override public void openGui() { if (s != null) s.openModBlock(x, y, z); }
}
