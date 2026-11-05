package derpcraft.world;

/** Fills new chunks with blocks. Implementations must be thread safe (chunks are generated in parallel). */
public interface ChunkGenerator {
    void generate(Chunk c);

    /** A safe y to spawn the player at (top of terrain + 1). */
    int spawnY(int x, int z);

    /** A good x,z to spawn new players at (on land). */
    default int[] findSpawn() { return new int[]{0, 0}; }

    static ChunkGenerator create(WorldInfo info) {
        ChunkGenerator g = "flat".equals(info.generator) ? new FlatGenerator(info.flatLayers) : new TerrainGenerator(info.seed);
        return derpcraft.api.WorldGen.wrap(g, info.seed, "flat".equals(info.generator) ? "flat" : "default");
    }
}
