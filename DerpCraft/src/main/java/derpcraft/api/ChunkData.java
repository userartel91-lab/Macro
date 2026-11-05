package derpcraft.api;

import derpcraft.block.Block;

import java.util.Random;

/**
 * A freshly generated chunk (16 x 256 x 16) handed to world generation hooks ({@link ModContext#onChunkGenerate}),
 * before it appears in the world. Coordinates are local (0..15). Runs on worker threads: only touch this chunk.
 */
public interface ChunkData {
    int chunkX();
    int chunkZ();
    /** World x / z of local 0. */
    default int worldX() { return chunkX() << 4; }
    default int worldZ() { return chunkZ() << 4; }
    long seed();
    /** Random seeded from the world seed and the chunk position (the same every time). */
    Random random();
    /** "default" or "flat". */
    String generator();

    Block getBlock(int x, int y, int z);
    int getMeta(int x, int y, int z);
    void setBlock(int x, int y, int z, Block block, int meta);
    default void setBlock(int x, int y, int z, Block block) { setBlock(x, y, z, block, 0); }
    /** Highest non-air block in the column (-1 if none). */
    int topY(int x, int z);
    Block block(String name);
}
