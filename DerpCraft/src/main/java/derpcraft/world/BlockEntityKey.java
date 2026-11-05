package derpcraft.world;

/** Packs a position inside a chunk into the block entity map key. */
public final class BlockEntityKey {
    private BlockEntityKey() {}
    public static int key(int x, int y, int z) { return (y << 8) | ((z & 15) << 4) | (x & 15); }
}
