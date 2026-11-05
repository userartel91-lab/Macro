package derpcraft.npc;

import derpcraft.block.Block;
import derpcraft.block.FunctionalBlocks;
import derpcraft.world.World;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.PriorityQueue;

/**
 * Small A* over the block grid for NPCs: walks, steps up one block, drops up to three, goes through wooden doors and
 * fence gates (when the NPC may open them), avoids lava, fire and cactus. Returns block cells from start to goal,
 * or a path to the reachable cell closest to the goal when the goal itself can't be reached.
 */
public final class PathFinder {
    private static final int CLEAR = 1, DOOR = 2, PARTIAL = 4, FLOOR = 8, DANGER = 16, LOADED = 32, ALONG_X = 64;
    private static final int[] DX = {1, -1, 0, 0, 1, 1, -1, -1}, DZ = {0, 0, 1, -1, 1, -1, 1, -1};

    private final World w;
    private final boolean doors;
    private final HashMap<Long, Integer> cells = new HashMap<>();

    private PathFinder(World w, boolean doors) { this.w = w; this.doors = doors; }

    private static final class Node implements Comparable<Node> {
        final int x, y, z; final Node parent; final float g, f;
        Node(int x, int y, int z, Node parent, float g, float h) { this.x = x; this.y = y; this.z = z; this.parent = parent; this.g = g; this.f = g + h; }
        public int compareTo(Node o) { return Float.compare(f, o.f); }
    }

    /** Index of the next path point still to walk to, from 'idx' on: skips the points a walker at (x, y, z) has reached. */
    public static int reached(List<int[]> path, int idx, double x, double y, double z) {
        while (idx < path.size()) {
            int[] c = path.get(idx);
            double dx = c[0] + 0.5 - x, dz = c[2] + 0.5 - z;
            if (dx * dx + dz * dz < 0.4 * 0.4 && Math.abs(c[1] - y) < 1.1) idx++;
            else break;
        }
        return idx;
    }

    public static List<int[]> find(World w, int sx, int sy, int sz, int tx, int ty, int tz, boolean doors, int maxNodes) {
        return new PathFinder(w, doors).search(sx, sy, sz, tx, ty, tz, maxNodes);
    }

    private static long key(int x, int y, int z) { return ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | (y & 0xFFF); }

    private int cell(int x, int y, int z) {
        long k = key(x, y, z);
        Integer c = cells.get(k);
        if (c != null) return c;
        int v = classify(x, y, z);
        cells.put(k, v);
        return v;
    }

    private int classify(int x, int y, int z) {
        if (y < 0 || y > 255) return y < 0 ? 0 : CLEAR | LOADED;
        var ch = w.chunkAt(x, z);
        if (ch == null) return 0;
        Block b = w.getBlock(x, y, z);
        int v = LOADED;
        String n = b.name;
        if (n.contains("lava") || n.contains("fire") || n.contains("cactus") || n.contains("magma")) return v | DANGER;
        if (b instanceof FunctionalBlocks.DoorBlock) {
            boolean wooden = !n.contains("iron");
            int meta = w.getMeta(x, y, z);
            int lowerMeta = (meta & 8) != 0 ? w.getMeta(x, y - 1, z) : meta;
            int axis = (lowerMeta & 1) != 0 ? ALONG_X : 0;   // facing west / east: walk through along x
            if ((lowerMeta & 4) != 0 || (wooden && doors)) return v | CLEAR | DOOR | axis;
            return v;
        }
        if (b instanceof FunctionalBlocks.FenceGateBlock) {
            int meta = w.getMeta(x, y, z), axis = (meta & 1) != 0 ? ALONG_X : 0;
            if ((meta & 4) != 0 || doors) return v | CLEAR | DOOR | axis;
            return v;
        }
        float h = 0;
        for (float[] box : b.collisionShape(w.getMeta(x, y, z), 0)) h = Math.max(h, box[4]);
        if (h <= 0) return v | CLEAR;
        if (h <= 0.6f) return v | PARTIAL | FLOOR;
        if (h <= 1.01f) return v | FLOOR;
        return v;   // fences, walls: neither walkable nor something to stand on
    }

    private boolean clear(int x, int y, int z) { int c = cell(x, y, z); return (c & CLEAR) != 0 && (c & DANGER) == 0; }

    /** Feet can be in this cell. */
    private boolean standable(int x, int y, int z) {
        int c = cell(x, y, z);
        if ((c & DANGER) != 0 || (c & LOADED) == 0) return false;
        if ((c & PARTIAL) != 0) return clear(x, y + 1, z) && clear(x, y + 2, z);
        if ((c & CLEAR) == 0 || !clear(x, y + 1, z)) return false;
        int below = cell(x, y - 1, z);
        return (below & FLOOR) != 0 && (below & DANGER) == 0 || (c & DOOR) != 0 && (below & FLOOR) != 0;
    }

    private boolean isDoor(int x, int y, int z) { return (cell(x, y, z) & DOOR) != 0; }

    /** Doors and gates can only be walked through straight, along their axis. */
    private boolean doorAllows(int x, int y, int z, int d) {
        int c = cell(x, y, z);
        if ((c & DOOR) == 0) return true;
        return ((c & ALONG_X) != 0) == (DX[d] != 0);
    }

    private List<int[]> search(int sx, int sy, int sz, int tx, int ty, int tz, int maxNodes) {
        PriorityQueue<Node> open = new PriorityQueue<>();
        HashMap<Long, Float> best = new HashMap<>();
        Node start = new Node(sx, sy, sz, null, 0, h(sx, sy, sz, tx, ty, tz));
        open.add(start);
        best.put(key(sx, sy, sz), 0f);
        Node closest = start;
        float closestH = start.f;
        int expanded = 0;
        while (!open.isEmpty() && expanded < maxNodes) {
            Node n = open.poll();
            Float bg = best.get(key(n.x, n.y, n.z));
            if (bg != null && bg < n.g) continue;
            expanded++;
            float hn = h(n.x, n.y, n.z, tx, ty, tz);
            if (hn < closestH) { closestH = hn; closest = n; }
            if (Math.abs(n.x - tx) + Math.abs(n.z - tz) <= 1 && Math.abs(n.y - ty) <= 1) { closest = n; break; }
            for (int d = 0; d < 8; d++) {
                int nx = n.x + DX[d], nz = n.z + DZ[d];
                if (d >= 4) {   // diagonal: both sides free at this level, no doors involved
                    if (!standable(n.x + DX[d], n.y, n.z) || !standable(n.x, n.y, n.z + DZ[d]) || isDoor(n.x, n.y, n.z)
                            || isDoor(n.x + DX[d], n.y, n.z) || isDoor(n.x, n.y, n.z + DZ[d])) continue;
                }
                int ny = Integer.MIN_VALUE;
                if (standable(nx, n.y, nz)) ny = n.y;
                else if (d < 4 && standable(nx, n.y + 1, nz) && clear(n.x, n.y + 2, n.z) && !isDoor(n.x, n.y, n.z)) ny = n.y + 1;
                else if (d < 4 && clear(nx, n.y, nz) && clear(nx, n.y + 1, nz) && !isDoor(nx, n.y, nz)) {
                    for (int drop = 1; drop <= 3; drop++) {
                        if (standable(nx, n.y - drop, nz)) { ny = n.y - drop; break; }
                        if (!clear(nx, n.y - drop, nz)) break;
                    }
                }
                if (ny == Integer.MIN_VALUE) continue;
                if (d < 4 && (!doorAllows(n.x, n.y, n.z, d) || !doorAllows(nx, ny, nz, d))) continue;
                float cost = (d >= 4 ? 1.414f : 1f) + (ny > n.y ? 0.6f : 0) + (isDoor(nx, ny, nz) ? 0.5f : 0);
                float g = n.g + cost;
                long k = key(nx, ny, nz);
                Float old = best.get(k);
                if (old != null && old <= g) continue;
                best.put(k, g);
                open.add(new Node(nx, ny, nz, n, g, h(nx, ny, nz, tx, ty, tz)));
            }
        }
        if (closest == start) return null;
        List<int[]> path = new ArrayList<>();
        for (Node n = closest; n != null; n = n.parent) path.add(0, new int[]{n.x, n.y, n.z});
        return path;
    }

    private static float h(int x, int y, int z, int tx, int ty, int tz) {
        int dx = Math.abs(x - tx), dz = Math.abs(z - tz);
        return Math.max(dx, dz) + 0.414f * Math.min(dx, dz) + Math.abs(y - ty) * 0.5f;
    }
}
