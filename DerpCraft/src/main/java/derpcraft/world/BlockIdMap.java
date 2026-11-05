package derpcraft.world;

import derpcraft.block.Block;
import derpcraft.block.Blocks;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/**
 * Persistent per-world mapping between block names and the numeric ids stored in region files,
 * so adding/removing mods never scrambles saved worlds.
 */
public final class BlockIdMap {
    private final Path file;
    private final Map<String, Integer> nameToWorld = new LinkedHashMap<>();
    public final int[] regToWorld = new int[Blocks.MAX_BLOCKS];
    public final int[] worldToReg = new int[Blocks.MAX_BLOCKS];

    public BlockIdMap(Path worldDir) throws IOException {
        file = worldDir.resolve("blockids.txt");
        if (Files.exists(file)) {
            for (String line : Files.readAllLines(file)) {
                int eq = line.indexOf('=');
                if (eq > 0) nameToWorld.put(line.substring(0, eq).trim(), Integer.parseInt(line.substring(eq + 1).trim()));
            }
        }
        boolean[] used = new boolean[Blocks.MAX_BLOCKS];
        for (int v : nameToWorld.values()) used[v] = true;
        int next = 0;
        boolean changed = false;
        for (Block b : Blocks.all()) {
            Integer w = nameToWorld.get(b.name);
            if (w == null) {
                if (b.id == 0 && !used[0]) w = 0;
                else { while (used[next]) next++; w = next; }
                used[w] = true;
                nameToWorld.put(b.name, w);
                changed = true;
            }
        }
        Arrays.fill(worldToReg, 0);
        for (Map.Entry<String, Integer> e : nameToWorld.entrySet()) {
            Block b = Blocks.byName(e.getKey());
            if (b != null) { worldToReg[e.getValue()] = b.id; regToWorld[b.id] = e.getValue(); }
        }
        if (changed) save();
    }

    private void save() throws IOException {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Integer> e : nameToWorld.entrySet()) sb.append(e.getKey()).append('=').append(e.getValue()).append('\n');
        derpcraft.util.SafeFiles.writeString(file, sb);
    }

    public char toWorld(int state) { return (char) ((regToWorld[state >> 4] << 4) | (state & 15)); }
    public char toReg(int state) {
        int id = worldToReg[(state >> 4) & 4095];
        // blocks of a removed mod become plain air (not "air with metadata", which counted as a solid cell)
        return id == 0 ? 0 : (char) ((id << 4) | (state & 15));
    }
}
