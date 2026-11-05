package derpcraft.world;

import derpcraft.block.Block;
import derpcraft.block.Blocks;

import java.util.List;

/**
 * Block collision shared by the player, creatures, NPC bodies and dropped items / falling blocks: gathers the
 * collision boxes around a region into a flat reusable buffer (no array per box) and clips movement against them
 * axis by axis, like Minecraft.
 * <p>
 * Only the cells whose boxes can reach the region are read ({@link Block#collisionReach()}: a fence 0.5 above its
 * cell, nothing sideways for the built-in blocks), instead of one extra block on every side. Block shapes come from
 * the per-(meta, conn) cache {@link Block#collisionShape}.
 */
public final class Collision {
    private Collision() {}

    /** Touching is not overlapping: boxes this close to the region border are skipped by every test anyway. */
    private static final double EPS = 1e-3;

    /** A growable list of boxes {x0, y0, z0, x1, y1, z1} stored back to back. */
    public static final class Boxes {
        public double[] a = new double[6 * 32];
        /** Number of boxes. */
        public int n;

        public void clear() { n = 0; }

        public void add(double x0, double y0, double z0, double x1, double y1, double z1) {
            int i = n * 6;
            if (i + 6 > a.length) a = java.util.Arrays.copyOf(a, a.length * 2);
            a[i] = x0; a[i + 1] = y0; a[i + 2] = z0; a[i + 3] = x1; a[i + 4] = y1; a[i + 5] = z1;
            n++;
        }

        public void add(double[] b) { add(b[0], b[1], b[2], b[3], b[4], b[5]); }

        /** Box k as a new array (for code that keeps the old double[] form). */
        public double[] get(int k) { return java.util.Arrays.copyOfRange(a, k * 6, k * 6 + 6); }

        /** True when any box overlaps the region by more than 'e' on every axis. */
        public boolean overlaps(double x0, double y0, double z0, double x1, double y1, double z1, double e) {
            double[] b = a;
            for (int i = 0, end = n * 6; i < end; i += 6)
                if (b[i + 3] > x0 + e && b[i] < x1 - e && b[i + 4] > y0 + e && b[i + 1] < y1 - e && b[i + 5] > z0 + e && b[i + 2] < z1 - e) return true;
            return false;
        }

        /**
         * Clips movement 'd' along 'axis' (0 x, 1 y, 2 z) of the box (x0..z1) against all boxes: the largest move of the
         * same sign that does not enter one of them (same rule as {@link #clip(double[], int, double, double, double, double, double, double, double)}).
         */
        public double clip(int axis, double d, double x0, double y0, double z0, double x1, double y1, double z1) {
            if (d == 0) return 0;
            double[] b = a;
            for (int i = 0, end = n * 6; i < end; i += 6) d = clipOne(b, i, axis, d, x0, y0, z0, x1, y1, z1);
            return d;
        }
    }

    /**
     * Adds the collision boxes of blocks, model blocks and vehicles that overlap the region to 'out'.
     * @param who the body asking (vehicles skip the player's own car), or null
     * @param unloadedSolid columns of chunks that are not loaded count as solid from y -1000 to 1000 (the player does not
     *                      fall out of the world while chunks load)
     */
    public static void collect(World w, double x0, double y0, double z0, double x1, double y1, double z1, Boxes out,
                               Object who, boolean unloadedSolid) {
        double[] reach = Block.collisionReach();
        double side = reach[0] + EPS;
        int bx0 = (int) Math.floor(x0 - side), bx1 = (int) Math.floor(x1 + side);
        int bz0 = (int) Math.floor(z0 - side), bz1 = (int) Math.floor(z1 + side);
        int by0 = Math.max(0, (int) Math.floor(y0 - reach[1] - EPS)), by1 = Math.min(Chunk.HEIGHT - 1, (int) Math.floor(y1 + reach[2] + EPS));
        for (int bx = bx0; bx <= bx1; bx++)
            for (int bz = bz0; bz <= bz1; bz++) {
                Chunk c = w.chunkAt(bx, bz);
                if (c == null) {
                    if (unloadedSolid) out.add(bx, -1000, bz, bx + 1, 1000, bz + 1);
                    continue;
                }
                int lx = bx & 15, lz = bz & 15;
                for (int by = by0; by <= by1; by++) {
                    int st = c.get(lx, by, lz);
                    if (st == 0) continue;
                    Block b = Blocks.byId(st >> 4);
                    if (!b.solid) continue;
                    int meta = st & 15;
                    List<float[]> shape = b.collisionShape(meta, b.neighborInfo(w, bx, by, bz, meta));
                    for (int k = 0, n = shape.size(); k < n; k++) {
                        float[] cb = shape.get(k);
                        double ax0 = bx + cb[0], ay0 = by + cb[1], az0 = bz + cb[2], ax1 = bx + cb[3], ay1 = by + cb[4], az1 = bz + cb[5];
                        if (ax1 < x0 - EPS || ax0 > x1 + EPS || ay1 < y0 - EPS || ay0 > y1 + EPS || az1 < z0 - EPS || az0 > z1 + EPS) continue;
                        out.add(ax0, ay0, az0, ax1, ay1, az1);
                    }
                }
            }
        derpcraft.model.Model3DBlock.colliders(w, x0 - EPS, y0 - EPS, z0 - EPS, x1 + EPS, y1 + EPS, z1 + EPS, out);
        derpcraft.vehicle.Vehicles.colliders(w, x0 - EPS, y0 - EPS, z0 - EPS, x1 + EPS, y1 + EPS, z1 + EPS, out, who);
    }

    /** Whether a point is inside the collision boxes of the block there (bodies of the dead lie on this). */
    public static boolean solidAt(World w, double x, double y, double z) {
        int bx = (int) Math.floor(x), by = (int) Math.floor(y), bz = (int) Math.floor(z);
        if (by < 0) return true;
        if (by >= Chunk.HEIGHT) return false;
        Chunk c = w.chunkAt(bx, bz);
        if (c == null) return true;
        int st = c.get(bx & 15, by, bz & 15);
        if (st == 0) return false;
        Block b = Blocks.byId(st >> 4);
        if (!b.solid) return false;
        int meta = st & 15;
        double lx = x - bx, ly = y - by, lz = z - bz;
        List<float[]> shape = b.collisionShape(meta, b.neighborInfo(w, bx, by, bz, meta));
        for (int k = 0, n = shape.size(); k < n; k++) {
            float[] cb = shape.get(k);
            if (lx >= cb[0] && lx <= cb[3] && ly >= cb[1] && ly <= cb[4] && lz >= cb[2] && lz <= cb[5]) return true;
        }
        return false;
    }

    private static double clipOne(double[] b, int i, int axis, double d, double x0, double y0, double z0, double x1, double y1, double z1) {
        final double e = 1e-7;
        // overlap on the two other axes (touching does not count)
        if (axis != 0 && (b[i + 3] <= x0 + e || b[i] >= x1 - e)) return d;
        if (axis != 1 && (b[i + 4] <= y0 + e || b[i + 1] >= y1 - e)) return d;
        if (axis != 2 && (b[i + 5] <= z0 + e || b[i + 2] >= z1 - e)) return d;
        double mn = axis == 0 ? x0 : axis == 1 ? y0 : z0, mx = axis == 0 ? x1 : axis == 1 ? y1 : z1;
        if (d > 0 && b[i + axis] >= mx - e) d = Math.min(d, b[i + axis] - mx);
        else if (d < 0 && b[i + axis + 3] <= mn + e) d = Math.max(d, b[i + axis + 3] - mn);
        return d;
    }

    /** Clip against one box in the old double[6] form. */
    public static double clip(double[] b, int axis, double d, double x0, double y0, double z0, double x1, double y1, double z1) {
        return clipOne(b, 0, axis, d, x0, y0, z0, x1, y1, z1);
    }
}
