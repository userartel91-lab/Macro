package derpcraft.block;

/** Read access to block states (implemented by the world and by the mesher's local snapshot). */
public interface BlockGetter {
    int getState(int x, int y, int z);

    default Block getBlock(int x, int y, int z) { return Blocks.byId(getState(x, y, z) >> 4); }

    default int getMeta(int x, int y, int z) { return getState(x, y, z) & 15; }
}
