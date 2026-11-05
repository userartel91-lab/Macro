package derpcraft.game;

import derpcraft.block.Block;
import derpcraft.block.Blocks;
import derpcraft.block.Dir;
import derpcraft.world.World;

/** Block picking along the view ray (voxel DDA + per-box tests for slabs, stairs, plants...). */
public final class Raycast {
    public static final class Hit {
        public int x, y, z, face;
        public double hx, hy, hz;
        public Block block;
        public int meta;
    }

    public static Hit cast(World w, double ox, double oy, double oz, double dx, double dy, double dz, double maxDist, boolean liquids) {
        int x = (int) Math.floor(ox), y = (int) Math.floor(oy), z = (int) Math.floor(oz);
        int sx = dx > 0 ? 1 : -1, sy = dy > 0 ? 1 : -1, sz = dz > 0 ? 1 : -1;
        double tdx = dx == 0 ? Double.MAX_VALUE : Math.abs(1 / dx), tdy = dy == 0 ? Double.MAX_VALUE : Math.abs(1 / dy), tdz = dz == 0 ? Double.MAX_VALUE : Math.abs(1 / dz);
        double tmx = dx == 0 ? Double.MAX_VALUE : ((dx > 0 ? (x + 1 - ox) : (ox - x)) * tdx);
        double tmy = dy == 0 ? Double.MAX_VALUE : ((dy > 0 ? (y + 1 - oy) : (oy - y)) * tdy);
        double tmz = dz == 0 ? Double.MAX_VALUE : ((dz > 0 ? (z + 1 - oz) : (oz - z)) * tdz);
        for (int i = 0; i < 512; i++) {
            if (y >= 0 && y < 256) {
                int st = w.getState(x, y, z);
                if (st != 0) {
                    Block b = Blocks.byId(st >> 4);
                    if (b.shape != Block.Shape.LIQUID || liquids) {
                        Hit h = testBlock(w, b, st & 15, x, y, z, ox, oy, oz, dx, dy, dz, maxDist);
                        if (h != null) return h;
                    }
                }
            }
            double t;
            if (tmx < tmy && tmx < tmz) { t = tmx; x += sx; tmx += tdx; }
            else if (tmy < tmz) { t = tmy; y += sy; tmy += tdy; }
            else { t = tmz; z += sz; tmz += tdz; }
            if (t > maxDist) break;
        }
        return null;
    }

    /**
     * Whether nothing opaque (a full solid cube) lies on the segment from (x0, y0, z0) to (x1, y1, z1): line of sight.
     * Plants, glass, fences and slabs do not block it, but they do not hide a wall behind them either.
     */
    public static boolean clearLine(World w, double x0, double y0, double z0, double x1, double y1, double z1) {
        double dx = x1 - x0, dy = y1 - y0, dz = z1 - z0, len = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (len < 1e-3) return true;
        dx /= len; dy /= len; dz /= len;
        int x = (int) Math.floor(x0), y = (int) Math.floor(y0), z = (int) Math.floor(z0);
        int sx = dx > 0 ? 1 : -1, sy = dy > 0 ? 1 : -1, sz = dz > 0 ? 1 : -1;
        double tdx = dx == 0 ? Double.MAX_VALUE : Math.abs(1 / dx), tdy = dy == 0 ? Double.MAX_VALUE : Math.abs(1 / dy), tdz = dz == 0 ? Double.MAX_VALUE : Math.abs(1 / dz);
        double tmx = dx == 0 ? Double.MAX_VALUE : ((dx > 0 ? (x + 1 - x0) : (x0 - x)) * tdx);
        double tmy = dy == 0 ? Double.MAX_VALUE : ((dy > 0 ? (y + 1 - y0) : (y0 - y)) * tdy);
        double tmz = dz == 0 ? Double.MAX_VALUE : ((dz > 0 ? (z + 1 - z0) : (z0 - z)) * tdz);
        double t = 0;
        for (int i = 0; i < 1024 && t <= len; i++) {
            if (y >= 0 && y < 256) {
                int st = w.getState(x, y, z);
                if (st != 0 && Blocks.byId(st >> 4).isOpaqueCube()) return false;
            }
            if (tmx < tmy && tmx < tmz) { t = tmx; x += sx; tmx += tdx; }
            else if (tmy < tmz) { t = tmy; y += sy; tmy += tdy; }
            else { t = tmz; z += sz; tmz += tdz; }
        }
        return true;
    }

    private static Hit testBlock(World w, Block b, int meta, int bx, int by, int bz, double ox, double oy, double oz, double dx, double dy, double dz, double maxDist) {
        Hit best = null;
        double bestT = maxDist;
        java.util.List<float[]> shape = b.selectionBoxes(meta, b.neighborInfo(w, bx, by, bz, meta), bx, by, bz);
        for (int k = 0, n = shape.size(); k < n; k++) {
            float[] box = shape.get(k);
            // slab test per axis (x, y, z); the face is the one the ray enters through
            double tmin = -1e30, tmax = 1e30;
            int face = -1;
            boolean miss = false;
            for (int a = 0; a < 3 && !miss; a++) {
                double o = a == 0 ? ox : a == 1 ? oy : oz, d = a == 0 ? dx : a == 1 ? dy : dz;
                double base = a == 0 ? bx : a == 1 ? by : bz, mn = base + box[a], mx = base + box[a + 3];
                if (Math.abs(d) < 1e-12) {
                    if (o < mn || o > mx) miss = true;
                    continue;
                }
                double t1 = (mn - o) / d, t2 = (mx - o) / d;
                int f1 = a == 0 ? Dir.WEST : a == 1 ? Dir.DOWN : Dir.NORTH;
                if (t1 > t2) { double t = t1; t1 = t2; t2 = t; f1 = Dir.OPPOSITE[f1]; }
                if (t1 > tmin) { tmin = t1; face = f1; }
                if (t2 < tmax) tmax = t2;
                if (tmin > tmax) miss = true;
            }
            if (miss || tmax < 0 || tmin > bestT || face < 0) continue;
            if (tmin < 0) tmin = 0;
            bestT = tmin;
            if (best == null) best = new Hit();
            best.x = bx; best.y = by; best.z = bz; best.face = face;
            best.hx = ox + dx * tmin; best.hy = oy + dy * tmin; best.hz = oz + dz * tmin;
            best.block = b; best.meta = meta;
        }
        return best;
    }
}
