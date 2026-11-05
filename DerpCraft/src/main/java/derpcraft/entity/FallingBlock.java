package derpcraft.entity;

import derpcraft.block.Block;
import derpcraft.block.Blocks;
import derpcraft.block.GravityBlock;
import derpcraft.item.ItemStack;
import derpcraft.world.World;

/** A sand / gravel block that lost its support: falls, then becomes a block again (or drops as an item when the place is taken). */
public final class FallingBlock extends Entity {
    public final int state;

    public FallingBlock(World w, double x, double y, double z, int state) {
        super(w, x, y, z);
        this.state = state;
        width = 0.98f;
        height = 0.98f;
    }

    @Override public void tick() {
        super.tick();
        my -= 0.04;
        move(mx, my, mz);
        mx *= 0.98; my *= 0.98; mz *= 0.98;
        if (onGround) land();
        else if (age > 600 || y < -64) dead = true;
    }

    private void land() {
        dead = true;
        int bx = (int) Math.floor(x), bz = (int) Math.floor(z), by = (int) Math.floor(y + 0.01);
        if (!GravityBlock.canFallThrough(world, bx, by, bz)) by++;           // landed on a slab / fence: the cell above
        Block b = Blocks.byId(state >> 4);
        if (GravityBlock.canFallThrough(world, bx, by, bz) && world.setBlock(bx, by, bz, state)) return;
        for (ItemStack d : b.drops(state & 15, world.random)) world.dropItem(bx + 0.5, by + 0.5, bz + 0.5, d);
    }
}
