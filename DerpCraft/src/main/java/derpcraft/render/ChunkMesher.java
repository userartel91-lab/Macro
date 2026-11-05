package derpcraft.render;

import derpcraft.block.Dir;
import derpcraft.util.IntList;
import derpcraft.world.Chunk;
import derpcraft.world.Section;
import derpcraft.world.World;

import static derpcraft.render.RenderTables.*;

/**
 * Builds the mesh of one 16^3 section.
 * - full cubes use greedy meshing (faces with identical texture, tint and per-corner light/AO are merged)
 * - vanilla-style smooth lighting and ambient occlusion
 * - output is split into 3 render layers x 7 direction groups (6 faces + unaligned) for backface-group culling
 * Vertex = 3 ints: [x9 y9 z9 normal3 decal1] [u9 v9 layer14] [sky6 red6 green6 blue6 ao2 tint6]
 */
public final class ChunkMesher {
    public static final int GROUPS = 21;
    /** Bit 30 of a vertex's first int: decal (soot) drawn over a face, pushed towards the viewer in terrain.vsh. */
    public static final int DECAL_BIT = 1 << 30;
    private static final int P = 18;
    private static final int[] CU = {-1, 1, 1, -1}, CV = {-1, -1, 1, 1};

    private final char[] st = new char[P * P * P];
    private final byte[] sky = new byte[P * P * P], blkR = new byte[P * P * P], blkG = new byte[P * P * P], blkB = new byte[P * P * P];
    private final IntList[] out = new IntList[GROUPS];
    private final int[] maskA = new int[256];
    private final long[] maskB = new long[256], maskC = new long[256];
    private final int[] maskO = new int[256];
    private final int[] p = new int[3];
    private final int[] cornerLight = new int[4];

    public ChunkMesher() { for (int i = 0; i < GROUPS; i++) out[i] = new IntList(1024); }

    private static int idx(int x, int y, int z) { return ((y + 1) * P + (z + 1)) * P + (x + 1); }

    /** Result of meshing. verts holds all groups back to back; groupStart has GROUPS+1 vertex offsets. */
    public static final class Mesh {
        public int[] verts;
        public final int[] groupStart = new int[GROUPS + 1];
        /** Local indices of blocks drawn by the entity renderer; applied to the chunk on the render thread. */
        public int[] entityBlocks;
        /**
         * Box around all vertices, in 1/16 blocks from the section origin: x0, y0, z0, x1, y1, z1. The renderer culls
         * with it instead of the whole 16^3 section (a surface section is often only a few layers of ground and grass).
         */
        public final int[] bounds = new int[6];
        public int vertexCount() { return groupStart[GROUPS]; }

        /** Mesh of an empty section (shared: never modified). */
        public static final Mesh EMPTY = new Mesh();
        static { EMPTY.verts = new int[0]; }
    }

    /** Copies the section plus a 1 block border into local arrays. Returns false if the section is empty. */
    private boolean snapshot(World w, int cx, int sy, int cz) {
        Chunk center = w.getChunk(cx, cz);
        if (center == null) return false;
        Section cs = center.sections[sy];
        if (cs == null || cs.isEmpty()) return false;
        Chunk[] cols = new Chunk[9];
        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) cols[(dz + 1) * 3 + dx + 1] = w.getChunk(cx + dx, cz + dz);
        byte[] sootSrc = cs.soot;
        hasSoot = false;
        if (sootSrc != null) {
            for (int i = 0; i < 4096; i++) { byte v = sootSrc[i]; soot[i] = v; if (v != 0) hasSoot = true; }
        }
        char stoneState = (char) (derpcraft.block.Blocks.STONE.id << 4);
        // Copy per (column, vertical slot) block: every section array is read once (volatile fields) instead of
        // per cell. Slots: y = -1 (section below), 0..15 (this section), 16 (section above).
        for (int slot = 0; slot < 3; slot++) {
            int y0 = slot == 0 ? -1 : slot == 1 ? 0 : 16, y1 = slot == 0 ? -1 : slot == 1 ? 15 : 16;
            int wsy = sy + slot - 1;
            for (int cz3 = 0; cz3 < 3; cz3++) {
                int z0 = cz3 == 0 ? -1 : cz3 == 1 ? 0 : 16, z1 = cz3 == 0 ? -1 : cz3 == 1 ? 15 : 16;
                for (int cx3 = 0; cx3 < 3; cx3++) {
                    int x0 = cx3 == 0 ? -1 : cx3 == 1 ? 0 : 16, x1 = cx3 == 0 ? -1 : cx3 == 1 ? 15 : 16;
                    Chunk c = cols[cz3 * 3 + cx3];
                    if (wsy < 0) { fill(x0, x1, y0, y1, z0, z1, stoneState, 0); continue; }
                    Section s = c == null || wsy > 15 ? null : c.sections[wsy];
                    if (s == null) { fill(x0, x1, y0, y1, z0, z1, (char) 0, 15); continue; }
                    char[] b = s.blocks;
                    byte[] sk = s.sky, r = s.light[0], g = s.light[1], bl = s.light[2];
                    int skyDef = s.skyDefault;
                    for (int y = y0; y <= y1; y++)
                        for (int z = z0; z <= z1; z++) {
                            int i = idx(x0, y, z);
                            int si = Section.index(x0 & 15, y & 15, z & 15);
                            for (int x = x0; x <= x1; x++, i++, si++) {
                                st[i] = b == null ? 0 : b[si];
                                int sh = (si & 1) << 2, h = si >> 1;
                                sky[i] = (byte) (sk == null ? skyDef : (sk[h] >> sh) & 15);
                                blkR[i] = (byte) (r == null ? 0 : (r[h] >> sh) & 15);
                                blkG[i] = (byte) (g == null ? 0 : (g[h] >> sh) & 15);
                                blkB[i] = (byte) (bl == null ? 0 : (bl[h] >> sh) & 15);
                            }
                        }
                }
            }
        }
        return true;
    }

    private void fill(int x0, int x1, int y0, int y1, int z0, int z1, char state, int skyLevel) {
        for (int y = y0; y <= y1; y++)
            for (int z = z0; z <= z1; z++) {
                int i = idx(x0, y, z);
                for (int x = x0; x <= x1; x++, i++) { st[i] = state; sky[i] = (byte) skyLevel; blkR[i] = blkG[i] = blkB[i] = 0; }
            }
    }

    private final IntList entityList = new IntList(16);
    /** Origin of the section being meshed (block coordinates) and its soot masks. */
    private int originX, originY, originZ;
    private final byte[] soot = new byte[4096];
    private boolean hasSoot;
    /** Block lookups over the padded snapshot (local coordinates -1..16) for neighbour-dependent models. */
    private final derpcraft.block.BlockGetter local = (x, y, z) ->
            x < -1 || y < -1 || z < -1 || x > 16 || y > 16 || z > 16 ? 0 : st[idx(x, y, z)];

    public Mesh build(World w, RenderTables t, int cx, int sy, int cz) {
        Mesh m = new Mesh();
        originX = cx * 16; originY = sy * 16; originZ = cz * 16;
        for (IntList l : out) l.clear();
        entityList.clear();
        if (snapshot(w, cx, sy, cz) && !buried(t)) {
            greedy(t);
            special(t);
            if (hasSoot) sootPass(t);
        }
        // not written to the chunk here: an older in-flight result could overwrite a newer one (chest placed
        // while the section was meshing in the background would vanish); WorldRenderer.apply sets it
        m.entityBlocks = entityList.size == 0 ? null : java.util.Arrays.copyOf(entityList.a, entityList.size);
        int total = 0;
        for (int g = 0; g < GROUPS; g++) { m.groupStart[g] = total; total += out[g].size / 3; }
        m.groupStart[GROUPS] = total;
        m.verts = new int[total * 3];
        for (int g = 0; g < GROUPS; g++) System.arraycopy(out[g].a, 0, m.verts, m.groupStart[g] * 3, out[g].size);
        int[] v = m.verts;
        int x0 = 511, y0 = 511, z0 = 511, x1 = 0, y1 = 0, z1 = 0;
        for (int i = 0; i < v.length; i += 3) {
            int a = v[i], x = a & 511, y = a >> 9 & 511, z = a >> 18 & 511;
            if (x < x0) x0 = x; if (x > x1) x1 = x;
            if (y < y0) y0 = y; if (y > y1) y1 = y;
            if (z < z0) z0 = z; if (z > z1) z1 = z;
        }
        int[] b = m.bounds;
        b[0] = x0; b[1] = y0; b[2] = z0; b[3] = x1; b[4] = y1; b[5] = z1;
        return m;
    }

    /**
     * True when the section and the layer of cells touching its six sides are all full opaque cubes (rock deep
     * underground): no face can be seen, so the mesh is empty. Most sections below the surface are like this; the full
     * passes look at every cell six times, this stops at the first cell that is not rock.
     */
    private boolean buried(RenderTables t) {
        boolean[] op = t.opaque, text = t.textOverlay;
        for (int y = -1; y <= 16; y++)
            for (int z = -1; z <= 16; z++) {
                boolean yIn = y >= 0 && y <= 15, zIn = z >= 0 && z <= 15;
                if (!yIn && !zIn) continue;                     // an edge of the border: no face touches it
                int x0 = yIn && zIn ? -1 : 0, x1 = yIn && zIn ? 16 : 15;
                int i = idx(x0, y, z);
                for (int x = x0; x <= x1; x++, i++) {
                    int id = st[i] >> 4;
                    if (!op[id] || text[id]) return false;
                }
            }
        return true;
    }

    // ------------------------------------------------------------------------------------------ lighting
    private boolean opq(RenderTables t, int i) { return t.opaque[st[i] >> 4]; }

    /** Light of a cell packed as 4 x 4 bits: sky | r << 4 | g << 8 | b << 12. */
    private int cell(int i) { return sky[i] | blkR[i] << 4 | blkG[i] << 8 | blkB[i] << 12; }

    /**
     * Smooth light + AO for the 4 corners of a face of block (x,y,z) in direction d -> cornerLight[].
     * Each corner: sum of 4 cells per channel (0..60): sky | r << 6 | g << 12 | b << 18 | ao << 24.
     */
    private void faceCorners(RenderTables t, int x, int y, int z, int d) {
        int fx = x + Dir.DX[d], fy = y + Dir.DY[d], fz = z + Dir.DZ[d];
        int fi = idx(fx, fy, fz);
        int f = cell(fi);
        boolean frontOpaque = opq(t, fi);
        int ua = AX_U[d], va = AX_V[d];
        for (int k = 0; k < 4; k++) {
            int ux = ua == 0 ? CU[k] : 0, uy = ua == 1 ? CU[k] : 0, uz = ua == 2 ? CU[k] : 0;
            int vx = va == 0 ? CV[k] : 0, vy = va == 1 ? CV[k] : 0, vz = va == 2 ? CV[k] : 0;
            int i1 = idx(fx + ux, fy + uy, fz + uz), i2 = idx(fx + vx, fy + vy, fz + vz), ic = idx(fx + ux + vx, fy + uy + vy, fz + uz + vz);
            boolean o1 = opq(t, i1), o2 = opq(t, i2), oc = (o1 && o2) || opq(t, ic);
            int c1 = cell(i1), c2 = cell(i2), c3 = cell(ic);
            if (o1 || c1 == 0) c1 = f;
            if (o2 || c2 == 0) c2 = f;
            if (oc || c3 == 0) c3 = f;
            int packed = 0;
            for (int ch = 0; ch < 4; ch++) {
                int sh = ch * 4;
                int sum = ((f >> sh) & 15) + ((c1 >> sh) & 15) + ((c2 >> sh) & 15) + ((c3 >> sh) & 15);
                packed |= sum << (ch * 6);
            }
            int ao = (o1 ? 1 : 0) + (o2 ? 1 : 0) + (oc ? 1 : 0);
            if (frontOpaque) ao = 0;
            cornerLight[k] = packed | ao << 24;
        }
    }

    // ------------------------------------------------------------------------------------------ greedy cubes
    private static final int[] STRIDE = {1, P * P, P};   // index step along x, y, z

    private void greedy(RenderTables t) {
        byte[] shapes = t.shape;
        for (int d = 0; d < 6; d++) {
            int na = AX_N[d], ua = AX_U[d], va = AX_V[d];
            int sN = STRIDE[na], sU = STRIDE[ua], sV = STRIDE[va];
            int nOff = Dir.DX[d] * STRIDE[0] + Dir.DY[d] * STRIDE[1] + Dir.DZ[d] * STRIDE[2];
            int origin = idx(0, 0, 0);
            for (int s = 0; s < 16; s++) {
                boolean anyO = false, any = false;
                java.util.Arrays.fill(maskA, 0);
                java.util.Arrays.fill(maskO, 0);
                for (int v = 0; v < 16; v++) {
                    int rowBase = origin + s * sN + v * sV;
                    for (int u = 0; u < 16; u++) {
                        int ci = rowBase + u * sU;
                        int state = st[ci];
                        if (state == 0) continue;
                        int id = state >> 4;
                        int sh = shapes[id];
                        boolean liquid = sh == SH_LIQUID;
                        if (sh != SH_CUBE && !(liquid && d == Dir.UP)) continue;
                        int nb = st[ci + nOff] >> 4;
                        if (t.hidden(id, nb)) continue;
                        int mi = v * 16 + u;
                        p[na] = s; p[ua] = u; p[va] = v;
                        int x = p[0], y = p[1], z = p[2];
                        int si = state * 6 + d;
                        int ov = t.overlay[id];
                        int tint = t.tint[id];
                        if (ov >= 0 && d != Dir.UP) tint = 0;
                        faceCorners(t, x, y, z, d);
                        long kb = (long) cornerLight[0] | (long) cornerLight[1] << 26, kc = (long) cornerLight[2] | (long) cornerLight[3] << 26;
                        maskA[mi] = t.faceLayer[si] | t.layer[id] << 14 | tint << 16 | (t.faceRot[si] ? 1 << 22 : 0)
                                | (liquid ? 1 << 23 : 0) | 1 << 31;
                        maskB[mi] = kb;
                        maskC[mi] = kc;
                        any = true;
                        if (ov >= 0 && d >= 2) {
                            maskO[mi] = ov | 1 << 14 | t.tint[id] << 16 | 1 << 31;
                            anyO = true;
                        }
                    }
                }
                if (any) merge(d, s, maskA, false);
                if (anyO) merge(d, s, maskO, true);
            }
        }
    }

    private void merge(int d, int s, int[] mask, boolean overlay) {
        for (int v = 0; v < 16; v++)
            for (int u = 0; u < 16; ) {
                int mi = v * 16 + u;
                int ka = mask[mi];
                if (ka == 0) { u++; continue; }
                long kb = maskB[mi], kc = maskC[mi];
                int w = 1;
                while (u + w < 16 && mask[mi + w] == ka && maskB[mi + w] == kb && maskC[mi + w] == kc) w++;
                int h = 1;
                outer:
                while (v + h < 16) {
                    int row = (v + h) * 16 + u;
                    for (int k = 0; k < w; k++) if (mask[row + k] != ka || maskB[row + k] != kb || maskC[row + k] != kc) break outer;
                    h++;
                }
                for (int hh = 0; hh < h; hh++) for (int k = 0; k < w; k++) mask[(v + hh) * 16 + u + k] = 0;
                emitGreedy(d, s, u, v, w, h, ka, kb, kc);
                u += w;
            }
    }

    private final int[] qx = new int[4], qy = new int[4], qz = new int[4], qu = new int[4], qv = new int[4], ql = new int[4];

    private void emitGreedy(int d, int s, int u, int v, int w, int h, int ka, long kb, long kc) {
        int layer = ka & 0x3FFF, rl = (ka >> 14) & 3, tint = (ka >> 16) & 63;
        boolean rot = (ka & (1 << 22)) != 0, liquidTop = (ka & (1 << 23)) != 0;
        int na = AX_N[d], ua = AX_U[d], va = AX_V[d];
        int plane = s * 16 + (POS[d] ? 16 : 0) - (liquidTop ? 2 : 0);
        for (int k = 0; k < 4; k++) {
            p[na] = plane;
            p[ua] = (CU[k] < 0 ? u : u + w) * 16;
            p[va] = (CV[k] < 0 ? v : v + h) * 16;
            qx[k] = p[0]; qy[k] = p[1]; qz[k] = p[2];
            qu[k] = texU(d, p[0], p[1], p[2]);
            qv[k] = texV(d, p[0], p[1], p[2]);
            ql[k] = (int) (((k < 2 ? kb : kc) >> (26 * (k & 1))) & 0x3FFFFFF);
        }
        normalizeUV(rot);
        put(rl * 7 + d, d, layer, tint);
    }

    private void normalizeUV(boolean rot) {
        int mu = Integer.MAX_VALUE, mv = Integer.MAX_VALUE;
        for (int k = 0; k < 4; k++) { mu = Math.min(mu, qu[k]); mv = Math.min(mv, qv[k]); }
        int su = Math.floorDiv(mu, 16) * 16, sv = Math.floorDiv(mv, 16) * 16;
        for (int k = 0; k < 4; k++) {
            qu[k] -= su; qv[k] -= sv;
            if (rot) { int tmp = qu[k]; qu[k] = qv[k]; qv[k] = tmp; }
        }
    }

    /** Writes the quad in qx..ql into a group, flipping the diagonal for nicer AO interpolation. */
    private void put(int group, int normal, int layer, int tint) {
        IntList o = out[group];
        int b0 = brightness(ql[0]), b1 = brightness(ql[1]), b2 = brightness(ql[2]), b3 = brightness(ql[3]);
        int start = (b0 + b2 < b1 + b3) ? 1 : 0;
        for (int i = 0; i < 4; i++) {
            int k = (i + start) & 3;
            o.add3(qx[k] | qy[k] << 9 | qz[k] << 18 | normal << 27,
                    (qu[k] & 511) | (qv[k] & 511) << 9 | layer << 18,
                    ql[k] | tint << 26);
        }
    }

    private static int brightness(int l) {
        int blk = Math.max((l >> 6) & 63, Math.max((l >> 12) & 63, (l >> 18) & 63));
        return (l & 63) + blk - ((l >> 24) & 3) * 8;
    }

    // ------------------------------------------------------------------------------------------ other shapes
    private void special(RenderTables t) {
        for (int y = 0; y < 16; y++)
            for (int z = 0; z < 16; z++)
                for (int x = 0; x < 16; x++) {
                    int i = idx(x, y, z);
                    int state = st[i];
                    if (state == 0) continue;
                    int id = state >> 4;
                    int sh = t.shape[id];
                    if (t.textOverlay[id]) entityList.add(Section.index(x, y, z));
                    if (sh == SH_CUBE || sh == SH_AIR) continue;
                    if (sh == SH_ENTITY) { entityList.add(Section.index(x, y, z)); continue; }
                    if (sh == SH_TORCH && (state & 15) >= 1 && (state & 15) <= 8) { entityList.add(Section.index(x, y, z)); continue; }   // tilted wall torch
                    if (t.entityToo[id]) entityList.add(Section.index(x, y, z));   // fire: particles are spawned by the entity renderer
                    if (sh == SH_CROSS) cross(t, x, y, z, state);
                    else if (sh == SH_LIQUID) liquid(t, x, y, z, state);
                    else boxes(t, x, y, z, state);
                }
    }

    private int flatLight(int i) { return sky[i] * 4 | (blkR[i] * 4) << 6 | (blkG[i] * 4) << 12 | (blkB[i] * 4) << 18; }

    private int maxLight(int a, int b) {
        int r = 0;
        for (int ch = 0; ch < 4; ch++) r |= Math.max((a >> (ch * 6)) & 63, (b >> (ch * 6)) & 63) << (ch * 6);
        return r;
    }

    private static final int[][] CROSS_PLANES = {{1, 1, 15, 15}, {1, 15, 15, 1}};

    private void cross(RenderTables t, int x, int y, int z, int state) {
        int id = state >> 4;
        int layer = t.faceLayer[state * 6 + Dir.NORTH];
        int light = flatLight(idx(x, y, z));
        int rl = t.layer[id];
        int bx = x * 16, by = y * 16, bz = z * 16;
        // tall grass: every block gets its own height (the texture is squeezed, so the blades keep their shape)
        int hpx = t.randomHeight[id] ? derpcraft.block.Block.plantHeightPx(originX + x, originY + y, originZ + z) : 16;
        // contact shading: the foot of a plant standing on a solid block is a little darker, so it sits on the ground
        int footAo = opq(t, idx(x, y - 1, z)) ? 1 : 0;
        for (int[] pl : CROSS_PLANES) {
            for (int side = 0; side < 2; side++) {
                int ax = pl[0], az = pl[1], cx2 = pl[2], cz2 = pl[3];
                if (side == 1) { int tx = ax; ax = cx2; cx2 = tx; int tz = az; az = cz2; cz2 = tz; }
                qx[0] = bx + ax; qy[0] = by; qz[0] = bz + az; qu[0] = 0; qv[0] = 16;
                qx[1] = bx + cx2; qy[1] = by; qz[1] = bz + cz2; qu[1] = 16; qv[1] = 16;
                qx[2] = bx + cx2; qy[2] = by + hpx; qz[2] = bz + cz2; qu[2] = 16; qv[2] = 0;
                qx[3] = bx + ax; qy[3] = by + hpx; qz[3] = bz + az; qu[3] = 0; qv[3] = 0;
                ql[0] = ql[1] = light | footAo << 24;
                ql[2] = ql[3] = light;
                put(rl * 7 + 6, 6, layer, t.tint[id]);
            }
        }
    }

    private void liquid(RenderTables t, int x, int y, int z, int state) {
        int id = state >> 4;
        int rl = t.layer[id];
        boolean lowered = t.shape[st[idx(x, y + 1, z)] >> 4] != SH_LIQUID;
        int top = lowered ? 14 : 16;
        for (int d = 0; d < 6; d++) {
            if (d == Dir.UP) continue; // handled by greedy pass
            int ni = idx(x + Dir.DX[d], y + Dir.DY[d], z + Dir.DZ[d]);
            if (t.hidden(id, st[ni] >> 4)) continue;
            int layer = t.faceLayer[state * 6 + d];
            faceCorners(t, x, y, z, d);
            int na = AX_N[d], ua = AX_U[d], va = AX_V[d];
            for (int k = 0; k < 4; k++) {
                p[na] = POS[d] ? 16 : 0;
                p[ua] = CU[k] < 0 ? 0 : 16;
                p[va] = CV[k] < 0 ? 0 : 16;
                if (p[1] == 16) p[1] = top;
                qx[k] = x * 16 + p[0]; qy[k] = y * 16 + p[1]; qz[k] = z * 16 + p[2];
                qu[k] = texU(d, p[0], p[1], p[2]); qv[k] = texV(d, p[0], p[1], p[2]);
                ql[k] = cornerLight[k];
            }
            put(rl * 7 + d, d, layer, 0);
        }
    }

    private void boxes(RenderTables t, int x, int y, int z, int state) {
        int id = state >> 4;
        int conn = t.neighborDependent[id] ? derpcraft.block.Blocks.byId(id).neighborInfo(local, x, y, z, state & 15) & 15 : 0;
        if (t.phased[id]) conn = posHash(originX + x, originY + y, originZ + z) & 7;
        RenderTables.Quad[] quads = t.boxQuads(state, conn);
        int rl = t.layer[id];
        int own = flatLight(idx(x, y, z));
        int up = flatLight(idx(x, y + 1, z));
        for (RenderTables.Quad q : quads) {
            int d = q.dir;
            int ni = idx(x + Dir.DX[d], y + Dir.DY[d], z + Dir.DZ[d]);
            if (q.cull && t.hidden(id, st[ni] >> 4)) continue;
            if (q.boundary) {
                faceCorners(t, x, y, z, d);
                int ua = AX_U[d], va = AX_V[d];
                for (int k = 0; k < 4; k++) {
                    float fu = q.pos[k * 3 + ua] / 16f, fv = q.pos[k * 3 + va] / 16f;
                    ql[k] = bilerp(fu, fv);
                }
            } else {
                int l = maxLight(maxLight(own, up), flatLight(ni));
                for (int k = 0; k < 4; k++) ql[k] = l;
            }
            for (int k = 0; k < 4; k++) {
                qx[k] = x * 16 + q.pos[k * 3]; qy[k] = y * 16 + q.pos[k * 3 + 1]; qz[k] = z * 16 + q.pos[k * 3 + 2];
                qu[k] = q.uv[k * 2]; qv[k] = q.uv[k * 2 + 1];
            }
            put(rl * 7 + d, d, q.layer, q.tint);
        }
    }

    private static int posHash(int x, int y, int z) {
        int h = x * 73856093 ^ y * 19349663 ^ z * 83492791;
        h ^= h >>> 13; h *= 0x5bd1e995; h ^= h >>> 15;
        return h & 0x7fffffff;
    }

    /** Soot: a dark translucent overlay on the faces marked in the soot masks of full opaque cubes (one of 6 textures, turned at random). */
    private void sootPass(RenderTables t) {
        for (int y = 0; y < 16; y++)
            for (int z = 0; z < 16; z++)
                for (int x = 0; x < 16; x++) {
                    int mask = soot[Section.index(x, y, z)];
                    if (mask == 0) continue;
                    int ci = idx(x, y, z);
                    int id = st[ci] >> 4;
                    if (st[ci] == 0 || t.shape[id] != SH_CUBE || t.layer[id] != 0) continue;   // only solid cubes (not leaves, glass...)
                    for (int d = 0; d < 6; d++) {
                        if ((mask & (1 << d)) == 0) continue;
                        int ni = idx(x + Dir.DX[d], y + Dir.DY[d], z + Dir.DZ[d]);
                        if (t.opaque[st[ni] >> 4]) continue;                                  // covered by a neighbour
                        int h = posHash(originX + x, originY + y, originZ + z) + d * 40503;
                        h ^= h >>> 11;
                        int variant = Math.floorMod(h, RenderTables.SOOT_VARIANTS);
                        faceCorners(t, x, y, z, d);
                        int na = AX_N[d], ua = AX_U[d], va = AX_V[d];
                        p[0] = x; p[1] = y; p[2] = z;
                        int s = p[na], u = p[ua], v = p[va];
                        for (int k = 0; k < 4; k++) {
                            p[na] = s * 16 + (POS[d] ? 16 : 0);
                            p[ua] = (CU[k] < 0 ? u : u + 1) * 16;
                            p[va] = (CV[k] < 0 ? v : v + 1) * 16;
                            qx[k] = p[0]; qy[k] = p[1]; qz[k] = p[2];
                            qu[k] = texU(d, p[0], p[1], p[2]);
                            qv[k] = texV(d, p[0], p[1], p[2]);
                            ql[k] = cornerLight[k];
                        }
                        normalizeUV((h >> 8 & 1) != 0);
                        put(2 * 7 + d, d, t.sootLayers[variant], 0);
                        // decal flag (bit 30): terrain.vsh lifts the quad off the face, else it z-fights with the
                        // (greedy-merged, differently triangulated) block face under it and flickers at some angles
                        IntList o = out[2 * 7 + d];
                        for (int k = 0; k < 4; k++) o.a[o.size - 12 + k * 3] |= DECAL_BIT;
                    }
                }
    }

    /** Bilinear interpolation of the 4 face corner light values (corner order (-,-),(+,-),(+,+),(-,+)). */
    private int bilerp(float fu, float fv) {
        int r = 0;
        for (int ch = 0; ch < 4; ch++) {
            int sh = ch * 6;
            float c0 = (cornerLight[0] >> sh) & 63, c1 = (cornerLight[1] >> sh) & 63, c2 = (cornerLight[2] >> sh) & 63, c3 = (cornerLight[3] >> sh) & 63;
            float a = c0 + (c1 - c0) * fu, b = c3 + (c2 - c3) * fu;
            r |= Math.round(a + (b - a) * fv) << sh;
        }
        float a0 = (cornerLight[0] >> 24) & 3, a1 = (cornerLight[1] >> 24) & 3, a2 = (cornerLight[2] >> 24) & 3, a3 = (cornerLight[3] >> 24) & 3;
        float a = a0 + (a1 - a0) * fu, b = a3 + (a2 - a3) * fu;
        r |= Math.round(a + (b - a) * fv) << 24;
        return r;
    }
}
