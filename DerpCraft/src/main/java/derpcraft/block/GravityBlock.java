package derpcraft.block;

import derpcraft.entity.FallingBlock;
import derpcraft.world.World;

/** Sand and gravel: when there is nothing under the block it turns into a falling block. */
public class GravityBlock extends Block {
    public GravityBlock(String name) { super(name); }

    public static boolean canFallThrough(World w, int x, int y, int z) {
        if (y < 0) return false;
        Block b = w.getBlock(x, y, z);
        return b.isAir() || b.replaceable || b.shape == Shape.LIQUID;
    }

    @Override public void onPlaced(World w, int x, int y, int z, int meta) { w.scheduleTick(x, y, z, 2); }

    @Override public void scheduledTick(World w, int x, int y, int z, int meta) {
        if (y <= 0 || !canFallThrough(w, x, y - 1, z)) return;
        w.setBlock(x, y, z, 0);
        w.entities.add(new FallingBlock(w, x + 0.5, y, z + 0.5, (id << 4) | meta));
        if (w.getBlock(x, y + 1, z) instanceof GravityBlock) w.scheduleTick(x, y + 1, z, 2);   // the column above follows
        derpcraft.game.Session s = derpcraft.game.Session.current;
        if (s != null) s.breakDependents(x, y, z);                                             // torches, plants on top
    }
}
