package derpcraft.api;

import derpcraft.block.Block;
import derpcraft.block.Blocks;
import derpcraft.world.Chunk;
import derpcraft.world.ChunkGenerator;

import java.util.List;
import java.util.Random;
import java.util.concurrent.CopyOnWriteArrayList;

/** Mod world generation: decorators run on each new chunk after the game generated it. */
public final class WorldGen {
    private WorldGen() {}

    /** Called for every newly generated chunk. */
    public interface Decorator { void decorate(ChunkData chunk); }

    private static final List<Decorator> decorators = new CopyOnWriteArrayList<>();

    static void add(Decorator d) { decorators.add(d); }

    public static boolean any() { return !decorators.isEmpty(); }

    static void addOre(String ore, String in, int size, int veins, int minY, int maxY) {
        decorators.add(c -> {
            Block o = c.block(ore), host = c.block(in);
            if (o == null || host == null) return;
            Random r = c.random();
            for (int v = 0; v < veins; v++) {
                int x = r.nextInt(16), y = minY + r.nextInt(Math.max(1, maxY - minY + 1)), z = r.nextInt(16);
                int n = 1 + r.nextInt(Math.max(1, size));
                for (int i = 0; i < n; i++) {
                    if (x >= 0 && x < 16 && z >= 0 && z < 16 && y > 0 && y < 256 && c.getBlock(x, y, z) == host) c.setBlock(x, y, z, o, 0);
                    switch (r.nextInt(6)) { case 0 -> x++; case 1 -> x--; case 2 -> y++; case 3 -> y--; case 4 -> z++; default -> z--; }
                }
            }
        });
    }

    /** Wraps a generator so decorators run after it. */
    public static ChunkGenerator wrap(ChunkGenerator base, long seed, String kind) {
        if (decorators.isEmpty()) return base;
        return new ChunkGenerator() {
            @Override public void generate(Chunk c) {
                base.generate(c);
                Data d = new Data(c, seed, kind);
                for (Decorator dec : decorators) {
                    try { dec.decorate(d); }
                    catch (Throwable t) { System.err.println("[worldgen] decorator failed in chunk " + c.cx + "," + c.cz + ": " + t); }
                }
            }
            @Override public int spawnY(int x, int z) { return base.spawnY(x, z); }
            @Override public int[] findSpawn() { return base.findSpawn(); }
        };
    }

    private static final class Data implements ChunkData {
        final Chunk c; final long seed; final String kind; Random random;
        Data(Chunk c, long seed, String kind) { this.c = c; this.seed = seed; this.kind = kind; }
        public int chunkX() { return c.cx; }
        public int chunkZ() { return c.cz; }
        public long seed() { return seed; }
        public Random random() {
            if (random == null) random = new Random(seed ^ (c.cx * 341873128712L + c.cz * 132897987541L) ^ 0x5DEECE66DL);
            return random;
        }
        public String generator() { return kind; }
        public Block getBlock(int x, int y, int z) { return y < 0 || y > 255 ? Blocks.AIR : Blocks.byId(c.get(x & 15, y, z & 15) >> 4); }
        public int getMeta(int x, int y, int z) { return y < 0 || y > 255 ? 0 : c.get(x & 15, y, z & 15) & 15; }
        public void setBlock(int x, int y, int z, Block b, int meta) {
            if (y < 0 || y > 255 || x < 0 || x > 15 || z < 0 || z > 15) return;
            c.setRaw(x, y, z, b == null ? 0 : (b.id << 4) | (meta & 15));
        }
        public int topY(int x, int z) { for (int y = 255; y >= 0; y--) if (c.get(x & 15, y, z & 15) != 0) return y; return -1; }
        public Block block(String name) { return Blocks.byName(name); }
    }
}
