package derpcraft.world;

import derpcraft.block.Block;
import derpcraft.block.Blocks;
import derpcraft.util.Noise;

import java.util.Random;

/** Minecraft-like terrain: oceans, beaches, plains, forests, deserts, mountains and snowy taiga. */
public final class TerrainGenerator implements ChunkGenerator {
    public enum Biome { OCEAN, BEACH, PLAINS, FOREST, BIRCH_FOREST, DESERT, MOUNTAINS, TAIGA }

    public static final int SEA = 62;
    private final long seed;
    private final Noise cont, hill, mountMask, ridge, detail, temp, humid, misc;

    public TerrainGenerator(long seed) {
        this.seed = seed;
        cont = new Noise(seed); hill = new Noise(seed + 1); mountMask = new Noise(seed + 2); ridge = new Noise(seed + 3);
        detail = new Noise(seed + 4); temp = new Noise(seed + 5); humid = new Noise(seed + 6); misc = new Noise(seed + 7);
    }

    private static double clamp(double v, double a, double b) { return v < a ? a : (v > b ? b : v); }
    private static double smooth(double v, double a, double b) { double t = clamp((v - a) / (b - a), 0, 1); return t * t * (3 - 2 * t); }

    /** Mountain factor 0..1 at a column. */
    private double mountain(int x, int z) {
        double c = cont.fbm(x / 900.0, z / 900.0, 4);
        return smooth(mountMask.fbm(x / 650.0, z / 650.0, 3), 0.08, 0.4) * clamp((c + 0.05) * 5, 0, 1);
    }

    public int height(int x, int z) {
        double c = cont.fbm(x / 900.0, z / 900.0, 4);
        double base = 65 + c * 42;
        double hills = hill.fbm(x / 220.0, z / 220.0, 4) * 12;
        double m = smooth(mountMask.fbm(x / 650.0, z / 650.0, 3), 0.08, 0.4) * clamp((c + 0.05) * 5, 0, 1);
        double r = ridge.ridged(x / 280.0, z / 280.0, 5);
        double mount = m * r * r * 95;
        double det = detail.fbm(x / 36.0, z / 36.0, 3) * 2.5;
        return (int) Math.round(clamp(base + hills + mount + det, 4, 250));
    }

    public double temperature(int x, int z, int h) {
        return clamp(0.55 + temp.fbm(x / 1100.0, z / 1100.0, 3) * 1.4 - Math.max(0, h - 95) * 0.006, 0, 1);
    }

    public double humidity(int x, int z) { return clamp(0.5 + humid.fbm(x / 900.0, z / 900.0, 3) * 1.4, 0, 1); }

    public Biome biome(int x, int z, int h) {
        if (h < SEA) return Biome.OCEAN;
        if (h <= SEA + 2 && cont.fbm(x / 900.0, z / 900.0, 4) < 0.12) return Biome.BEACH;
        if (mountain(x, z) > 0.55) return Biome.MOUNTAINS;
        double t = temperature(x, z, h), hu = humidity(x, z);
        if (t > 0.72 && hu < 0.45) return Biome.DESERT;
        if (t < 0.28) return Biome.TAIGA;
        if (hu > 0.6) return t > 0.5 ? Biome.FOREST : Biome.BIRCH_FOREST;
        return Biome.PLAINS;
    }

    @Override public int spawnY(int x, int z) { return Math.max(height(x, z), SEA) + 1; }

    @Override public int[] findSpawn() {
        for (int r = 0; r < 4000; r += 24)
            for (int a = 0; a < Math.max(1, r / 4); a++) {
                double ang = a * Math.PI * 2 / Math.max(1, r / 4);
                int x = (int) (Math.cos(ang) * r), z = (int) (Math.sin(ang) * r);
                int h = height(x, z);
                Biome b = biome(x, z, h);
                if (h > SEA + 2 && h < 100 && (b == Biome.PLAINS || b == Biome.FOREST || b == Biome.BIRCH_FOREST)) return new int[]{x, z};
            }
        return new int[]{0, 0};
    }

    @Override
    public void generate(Chunk c) {
        int bx = c.cx << 4, bz = c.cz << 4;
        int[] hm = new int[18 * 18];
        for (int z = -1; z <= 16; z++)
            for (int x = -1; x <= 16; x++) hm[(z + 1) * 18 + x + 1] = height(bx + x, bz + z);
        Random rnd = new Random(seed ^ (c.cx * 341873128712L + c.cz * 132897987541L));
        final int stone = Blocks.STONE.id << 4, dirt = Blocks.DIRT.id << 4, grass = Blocks.GRASS.id << 4,
                sand = Blocks.SAND.id << 4, sandstone = Blocks.SANDSTONE.id << 4, water = Blocks.WATER.id << 4,
                gravel = Blocks.GRAVEL.id << 4, bedrock = Blocks.BEDROCK.id << 4, clay = Blocks.CLAY.id << 4,
                snowLayer = Blocks.SNOW_LAYER.id << 4, ice = Blocks.ICE.id << 4, snow = Blocks.SNOW.id << 4;
        Biome[] biomes = new Biome[256];
        for (int z = 0; z < 16; z++)
            for (int x = 0; x < 16; x++) {
                int wx = bx + x, wz = bz + z;
                int h = hm[(z + 1) * 18 + x + 1];
                Biome b = biome(wx, wz, h);
                biomes[z * 16 + x] = b;
                double t = temperature(wx, wz, h), hu = humidity(wx, wz);
                c.temperature[z * 16 + x] = (byte) (t * 255);
                c.humidity[z * 16 + x] = (byte) (hu * 255);
                int slope = Math.max(Math.max(Math.abs(hm[(z + 1) * 18 + x] - hm[(z + 1) * 18 + x + 2]),
                        Math.abs(hm[z * 18 + x + 1] - hm[(z + 2) * 18 + x + 1])), 0);
                int depth = 3 + (int) ((misc.noise(wx / 8.0, wz / 8.0) + 1) * 1.5);
                int top = grass, filler = dirt;
                switch (b) {
                    case OCEAN -> {
                        double n = misc.noise(wx / 24.0, 3.3, wz / 24.0);
                        top = n > 0.25 ? gravel : (n < -0.35 ? clay : sand);
                        filler = top == clay ? clay : sand;
                        if (h < SEA - 12) { top = gravel; filler = gravel; }
                    }
                    case BEACH, DESERT -> { top = sand; filler = sand; }
                    case MOUNTAINS -> {
                        if (h > 100 || slope > 3) { top = stone; filler = stone; }
                    }
                    default -> {}
                }
                c.setRaw(x, 0, z, bedrock);
                for (int y = 1; y < 5; y++) if (rnd.nextInt(5) >= y) c.setRaw(x, y, z, bedrock);
                for (int y = 1; y <= h; y++) {
                    if (c.get(x, y, z) != 0) continue;
                    int st;
                    if (y == h) st = top;
                    else if (y >= h - depth) st = filler;
                    else if (b == Biome.DESERT && y >= h - depth - 3) st = sandstone;
                    else st = stone;
                    c.setRaw(x, y, z, st);
                }
                if (h < SEA) {
                    for (int y = h + 1; y <= SEA; y++) c.setRaw(x, y, z, water);
                    if (t < 0.15) c.setRaw(x, SEA, z, ice);
                } else {
                    boolean cold = b == Biome.TAIGA || (b == Biome.MOUNTAINS && h > 118);
                    if (b == Biome.MOUNTAINS && h > 128) c.setRaw(x, h, z, snow);
                    if (cold && h < 255) c.setRaw(x, h + 1, z, snowLayer);
                }
            }
        ores(c, rnd, hm);
        plants(c, rnd, biomes, hm);
        trees(c);
        for (Section s : c.sections) if (s != null) s.recount();
    }

    private void ores(Chunk c, Random rnd, int[] hm) {
        int[][] specs = {{Blocks.COAL_ORE.id, 18, 128, 8}, {Blocks.IRON_ORE.id, 14, 64, 6}, {Blocks.GOLD_ORE.id, 2, 32, 6},
                {Blocks.DIAMOND_ORE.id, 1, 16, 5}};
        int stone = Blocks.STONE.id << 4;
        for (int[] s : specs) {
            for (int i = 0; i < s[1]; i++) {
                int x = rnd.nextInt(16), z = rnd.nextInt(16), y = 2 + rnd.nextInt(s[2]);
                int n = 2 + rnd.nextInt(s[3]);
                for (int k = 0; k < n; k++) {
                    if (x >= 0 && x < 16 && z >= 0 && z < 16 && y > 0 && y < 255 && c.get(x, y, z) == stone)
                        c.setRaw(x, y, z, s[0] << 4);
                    switch (rnd.nextInt(6)) { case 0 -> x++; case 1 -> x--; case 2 -> y++; case 3 -> y--; case 4 -> z++; default -> z--; }
                }
            }
        }
    }

    private void plants(Chunk c, Random rnd, Biome[] biomes, int[] hm) {
        int grassId = Blocks.GRASS.id;
        Block[] flowers = {Blocks.DANDELION, Blocks.POPPY, Blocks.byName("houstonia"), Blocks.byName("oxeye_daisy"),
                Blocks.byName("tulip_red"), Blocks.byName("allium"), Blocks.byName("blue_orchid")};
        for (int z = 0; z < 16; z++)
            for (int x = 0; x < 16; x++) {
                Biome b = biomes[z * 16 + x];
                int h = hm[(z + 1) * 18 + x + 1];
                if (h < SEA || h > 250) continue;
                int below = c.get(x, h, z) >> 4;
                int r = rnd.nextInt(1000);
                if (below == grassId && c.get(x, h + 1, z) == 0) {
                    switch (b) {
                        case PLAINS -> {
                            if (r < 220) c.setRaw(x, h + 1, z, Blocks.TALL_GRASS.id << 4);
                            else if (r < 236) c.setRaw(x, h + 1, z, flowers[rnd.nextInt(flowers.length)].id << 4);
                        }
                        case FOREST, BIRCH_FOREST -> {
                            if (r < 80) c.setRaw(x, h + 1, z, Blocks.TALL_GRASS.id << 4);
                            else if (r < 88) c.setRaw(x, h + 1, z, flowers[rnd.nextInt(2)].id << 4);
                            else if (r < 92) c.setRaw(x, h + 1, z, Blocks.FERN.id << 4);
                        }
                        case MOUNTAINS -> { if (r < 40) c.setRaw(x, h + 1, z, Blocks.TALL_GRASS.id << 4); }
                        default -> {}
                    }
                } else if (b == Biome.DESERT && r < 4 && c.get(x, h + 1, z) == 0) {
                    c.setRaw(x, h + 1, z, Blocks.DEAD_BUSH.id << 4);
                } else if (b == Biome.DESERT && r >= 996 && x > 0 && x < 15 && z > 0 && z < 15) {
                    int hh = 1 + rnd.nextInt(3);
                    for (int i = 1; i <= hh; i++) c.setRaw(x, h + i, z, Blocks.CACTUS.id << 4);
                } else if (b == Biome.BEACH && r < 6 && h == SEA + 1 && c.get(x, h + 1, z) == 0) {
                    int hh = 1 + rnd.nextInt(3);
                    for (int i = 1; i <= hh; i++) c.setRaw(x, h + i, z, Blocks.REEDS.id << 4);
                }
            }
    }

    // ------------------------------------------------------------------------- trees
    /** Receives tree blocks; replaceSolid = logs may overwrite leaves. */
    public interface Sink { void set(int x, int y, int z, int state, boolean replaceSolid); }

    /** Every chunk replays the trees of its 3x3 neighbourhood and keeps only the blocks inside itself. */
    private void trees(Chunk c) {
        int bx = c.cx << 4, bz = c.cz << 4;
        int air = 0;
        Sink sink = (x, y, z, state, replaceSolid) -> {
            if (x < bx || x >= bx + 16 || z < bz || z >= bz + 16 || y < 1 || y > 255) return;
            int cur = c.get(x - bx, y, z - bz);
            Block cb = Blocks.byId(cur >> 4);
            if (cur == air || cb.replaceable || cb.shape == Block.Shape.CROSS || (replaceSolid && cb == Blocks.OAK_LEAVES))
                c.setRaw(x - bx, y, z - bz, state);
            else if (replaceSolid && (cb.layer == Block.Layer.CUTOUT)) c.setRaw(x - bx, y, z - bz, state);
        };
        for (int dcx = -1; dcx <= 1; dcx++)
            for (int dcz = -1; dcz <= 1; dcz++) {
                int ncx = c.cx + dcx, ncz = c.cz + dcz;
                Random r = new Random(seed * 31 ^ (ncx * 73428767L + ncz * 912931L));
                int cxm = (ncx << 4) + 8, czm = (ncz << 4) + 8;
                int hc = height(cxm, czm);
                Biome b = biome(cxm, czm, hc);
                int count = switch (b) {
                    case FOREST -> 7 + r.nextInt(4);
                    case BIRCH_FOREST -> 6 + r.nextInt(3);
                    case TAIGA -> 5 + r.nextInt(4);
                    case PLAINS -> r.nextInt(10) == 0 ? 1 : 0;
                    case MOUNTAINS -> r.nextInt(3);
                    default -> 0;
                };
                for (int i = 0; i < count; i++) {
                    int tx = (ncx << 4) + r.nextInt(16), tz = (ncz << 4) + r.nextInt(16);
                    long ts = r.nextLong();
                    int th = height(tx, tz);
                    Biome tb = biome(tx, tz, th);
                    if (th < SEA + 1 || tb == Biome.DESERT || tb == Biome.BEACH || tb == Biome.OCEAN || th > 115) continue;
                    if (tb == Biome.MOUNTAINS && th > 100) continue;
                    // only grow if the chunk that owns the root has grass there (cheap re-check via the height function)
                    Random tr = new Random(ts);
                    switch (tb) {
                        case TAIGA -> spruce(sink, tx, th + 1, tz, tr);
                        case BIRCH_FOREST -> { if (tr.nextInt(5) == 0) oak(sink, tx, th + 1, tz, tr, false); else oak(sink, tx, th + 1, tz, tr, true); }
                        case FOREST -> { if (tr.nextInt(5) == 0) oak(sink, tx, th + 1, tz, tr, true); else oak(sink, tx, th + 1, tz, tr, false); }
                        case MOUNTAINS -> { if (tr.nextBoolean()) spruce(sink, tx, th + 1, tz, tr); else oak(sink, tx, th + 1, tz, tr, false); }
                        default -> oak(sink, tx, th + 1, tz, tr, false);
                    }
                }
            }
    }

    public static void oak(Sink s, int x, int y, int z, Random r, boolean birch) {
        int log = (birch ? Blocks.BIRCH_LOG : Blocks.OAK_LOG).id << 4;
        int leaves = (birch ? Blocks.BIRCH_LEAVES : Blocks.OAK_LEAVES).id << 4;
        int h = (birch ? 5 : 4) + r.nextInt(3);
        int top = y + h;
        for (int ly = top - 3; ly <= top; ly++) {
            int off = ly - top;
            int rad = 1 - off / 2;
            for (int dx = -rad; dx <= rad; dx++)
                for (int dz = -rad; dz <= rad; dz++) {
                    if (Math.abs(dx) == rad && Math.abs(dz) == rad && (r.nextInt(2) == 0 || off == 0)) continue;
                    s.set(x + dx, ly, z + dz, leaves, false);
                }
        }
        for (int i = 0; i < h; i++) s.set(x, y + i, z, log, true);
        s.set(x, y - 1, z, Blocks.DIRT.id << 4, true);
    }

    public static void spruce(Sink s, int x, int y, int z, Random r) {
        int log = Blocks.SPRUCE_LOG.id << 4, leaves = Blocks.SPRUCE_LEAVES.id << 4;
        int h = 7 + r.nextInt(4);
        int top = y + h;
        int bottomLeaves = y + 1 + r.nextInt(2);
        int rad = 0, maxRad = 2 + r.nextInt(2);
        for (int ly = top; ly >= bottomLeaves; ly--) {
            for (int dx = -rad; dx <= rad; dx++)
                for (int dz = -rad; dz <= rad; dz++) {
                    if (rad > 0 && Math.abs(dx) == rad && Math.abs(dz) == rad) continue;
                    s.set(x + dx, ly, z + dz, leaves, false);
                }
            if (rad >= 1 && r.nextInt(3) != 0) rad = Math.max(0, rad - 1);
            else rad = Math.min(maxRad, rad + 1);
        }
        s.set(x, top + 1, z, leaves, false);
        for (int i = 0; i < h; i++) s.set(x, y + i, z, log, true);
        s.set(x, y - 1, z, Blocks.DIRT.id << 4, true);
    }
}
