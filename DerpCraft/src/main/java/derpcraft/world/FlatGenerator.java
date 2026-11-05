package derpcraft.world;

import derpcraft.block.Block;
import derpcraft.block.Blocks;

import java.util.ArrayList;
import java.util.List;

/** Superflat world. Layer string like "bedrock,2*dirt,grass" (bottom to top). Ideal for building cities. */
public final class FlatGenerator implements ChunkGenerator {
    private final int[] column;

    public FlatGenerator(String layers) {
        List<Integer> list = new ArrayList<>();
        for (String part : layers.split(",")) {
            part = part.trim();
            if (part.isEmpty()) continue;
            int count = 1;
            int star = part.indexOf('*');
            if (star > 0) { count = Integer.parseInt(part.substring(0, star).trim()); part = part.substring(star + 1).trim(); }
            Block b = Blocks.byName(part);
            if (b == null) b = Blocks.STONE;
            for (int i = 0; i < count && list.size() < 255; i++) list.add(b.id << 4);
        }
        if (list.isEmpty()) list.add(Blocks.GRASS.id << 4);
        column = list.stream().mapToInt(Integer::intValue).toArray();
    }

    @Override public void generate(Chunk c) {
        for (int z = 0; z < 16; z++)
            for (int x = 0; x < 16; x++) {
                for (int y = 0; y < column.length; y++) c.setRaw(x, y, z, column[y]);
                c.temperature[z * 16 + x] = (byte) 204; // plains-like climate (0.8)
                c.humidity[z * 16 + x] = (byte) 102;    // 0.4
            }
        for (Section s : c.sections) if (s != null) s.recount();
    }

    @Override public int spawnY(int x, int z) { return column.length; }
}
