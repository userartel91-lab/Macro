package derpcraft.world;

import derpcraft.block.Blocks;
import derpcraft.block.Dir;
import derpcraft.util.LongQueue;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Sky + block light flood fill (Minecraft style, levels 0..15) running on its own thread.
 * All light writes happen on that thread; readers (meshers) may see transient values and get re-meshed.
 */
public final class LightEngine {
    private final World world;
    private final ExecutorService exec;
    public final AtomicInteger pending = new AtomicInteger();
    private final LongQueue inc = new LongQueue(), dec = new LongQueue();
    private final HashSet<Long> dirty = new HashSet<>();
    private Chunk initializing;
    private Chunk cache;

    public LightEngine(World world) {
        this.world = world;
        exec = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "DerpCraft-Light");
            t.setDaemon(true);
            t.setPriority(Thread.NORM_PRIORITY - 1);
            return t;
        });
    }

    private void submit(Runnable r) {
        pending.incrementAndGet();
        exec.execute(() -> {
            try { r.run(); flushDirty(); }
            catch (Throwable t) { t.printStackTrace(); }
            finally { pending.decrementAndGet(); }
        });
    }

    public void shutdown() { exec.shutdownNow(); }

    /** Waits (up to timeout) until all queued light work is done. */
    public void await(long ms) {
        long end = System.currentTimeMillis() + ms;
        while (pending.get() > 0 && System.currentTimeMillis() < end) {
            try { Thread.sleep(2); } catch (InterruptedException e) { return; }
        }
    }

    public void lightChunk(Chunk c) { submit(() -> initChunk(c)); }

    public void blockChanged(int x, int y, int z, int oldState, int newState) { submit(() -> update(x, y, z)); }

    public void relight(List<Chunk> chunks) {
        submit(() -> {
            for (Chunk c : chunks) c.state = Chunk.STATE_GENERATED;
            for (Chunk c : chunks) if (world.getChunk(c.cx, c.cz) == c) initChunk(c);
        });
    }

    // ------------------------------------------------------------------ packing
    private static long pack(int x, int y, int z, int level) {
        return ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | ((long) (level & 15) << 8) | (y & 255);
    }
    private static int ux(long p) { return (int) (p >> 38); }
    private static int uz(long p) { int z = (int) ((p >> 12) & 0x3FFFFFF); return (z << 6) >> 6; }
    private static int uy(long p) { return (int) (p & 255); }
    private static int ul(long p) { return (int) ((p >> 8) & 15); }

    // ------------------------------------------------------------------ access
    private Chunk chunk(int x, int z) {
        int cx = x >> 4, cz = z >> 4;
        Chunk c = cache;
        if (c != null && c.cx == cx && c.cz == cz) return c;
        c = world.getChunk(cx, cz);
        if (c == null) return null;
        if (c != initializing && c.state < Chunk.STATE_LIT) return null;
        cache = c;
        return c;
    }

    /** Channel 0 = sky, 1..3 = block light red, green, blue. */
    private static int get(Chunk c, int x, int y, int z, int ch) {
        return ch == 0 ? c.getSky(x & 15, y, z & 15) : c.getLight(ch - 1, x & 15, y, z & 15);
    }

    private void set(Chunk c, int x, int y, int z, int ch, int v) {
        Section s = c.sections[y >> 4];
        if (s == null) return; // above everything: implicit full sky light
        int i = Section.index(x & 15, y & 15, z & 15);
        if (ch == 0) s.setSky(i, v); else s.setLight(ch - 1, i, v);
        if (c != initializing) markDirty(x, y, z);
    }

    private void markDirty(int x, int y, int z) {
        int cx = x >> 4, cz = z >> 4, sy = y >> 4;
        dirty.add(skey(cx, sy, cz));
        int lx = x & 15, lz = z & 15, ly = y & 15;
        if (lx == 0) dirty.add(skey(cx - 1, sy, cz));
        if (lx == 15) dirty.add(skey(cx + 1, sy, cz));
        if (lz == 0) dirty.add(skey(cx, sy, cz - 1));
        if (lz == 15) dirty.add(skey(cx, sy, cz + 1));
        if (ly == 0 && sy > 0) dirty.add(skey(cx, sy - 1, cz));
        if (ly == 15 && sy < 15) dirty.add(skey(cx, sy + 1, cz));
    }

    private static long skey(int cx, int sy, int cz) { return ((long) (cx & 0xFFFFFFF) << 36) | ((long) (cz & 0xFFFFFFF) << 8) | sy; }

    private void flushDirty() {
        if (dirty.isEmpty()) return;
        World.SectionListener l = world.listener;
        for (long k : dirty) {
            int cx = (int) (k >> 36); cx = (cx << 4) >> 4;
            int cz = (int) ((k >> 8) & 0xFFFFFFF); cz = (cz << 4) >> 4;
            l.sectionDirty(cx, (int) (k & 255), cz, false);
        }
        dirty.clear();
    }

    // ------------------------------------------------------------------ propagation
    private void increase(int ch) {
        boolean sky = ch == 0;
        byte[] opacity = Blocks.OPACITY;
        byte[] filter = sky ? null : Blocks.FILTER_RGB[ch - 1];
        while (!inc.isEmpty()) {
            long p = inc.poll();
            int x = ux(p), y = uy(p), z = uz(p);
            Chunk c = chunk(x, z);
            if (c == null) continue;
            int level = get(c, x, y, z, ch);
            if (level <= 1) continue;
            for (int d = 0; d < 6; d++) {
                int nx = x + Dir.DX[d], ny = y + Dir.DY[d], nz = z + Dir.DZ[d];
                if (ny < 0 || ny > 255) continue;
                Chunk nc = (nx >> 4) == c.cx && (nz >> 4) == c.cz ? c : chunk(nx, nz);
                if (nc == null) continue;
                int nid = nc.get(nx & 15, ny, nz & 15) >> 4;
                int op = opacity[nid];
                if (op >= 15) continue;
                int nl = (sky && d == Dir.DOWN && level == 15 && op == 0) ? 15 : level - Math.max(1, op) - (sky ? 0 : filter[nid]);
                if (nl > get(nc, nx, ny, nz, ch)) {
                    set(nc, nx, ny, nz, ch, nl);
                    if (nl > 1) inc.add(pack(nx, ny, nz, 0));
                }
            }
        }
    }

    private void decrease(int ch) {
        boolean sky = ch == 0;
        byte[] emission = sky ? null : Blocks.EMISSION_RGB[ch - 1];
        while (!dec.isEmpty()) {
            long p = dec.poll();
            int x = ux(p), y = uy(p), z = uz(p), old = ul(p);
            for (int d = 0; d < 6; d++) {
                int nx = x + Dir.DX[d], ny = y + Dir.DY[d], nz = z + Dir.DZ[d];
                if (ny < 0 || ny > 255) continue;
                Chunk nc = chunk(nx, nz);
                if (nc == null) continue;
                int nl = get(nc, nx, ny, nz, ch);
                if (nl == 0) continue;
                boolean dependent = nl < old || (sky && d == Dir.DOWN && old == 15 && nl == 15);
                if (dependent && nc.sections[ny >> 4] != null) {
                    set(nc, nx, ny, nz, ch, 0);
                    dec.add(pack(nx, ny, nz, nl));
                    if (!sky) {
                        int e = emission[nc.get(nx & 15, ny, nz & 15) >> 4];
                        if (e > 0) { set(nc, nx, ny, nz, ch, e); inc.add(pack(nx, ny, nz, 0)); }
                    }
                } else {
                    inc.add(pack(nx, ny, nz, 0));
                }
            }
        }
    }

    private void update(int x, int y, int z) {
        cache = null;
        Chunk c = chunk(x, z);
        if (c == null) return;
        int state = c.get(x & 15, y, z & 15);
        if (c.sections[y >> 4] == null) c.ensureSection(y >> 4);
        for (int ch = 1; ch <= 4; ch++) {
            int channel = ch & 3; // block R, G, B, then sky
            boolean sky = channel == 0;
            int cur = get(c, x, y, z, channel);
            if (cur > 0) {
                set(c, x, y, z, channel, 0);
                dec.add(pack(x, y, z, cur));
                decrease(channel);
            }
            if (!sky) {
                int e = Blocks.EMISSION_RGB[channel - 1][state >> 4];
                if (e > 0) { set(c, x, y, z, channel, e); inc.add(pack(x, y, z, 0)); }
            }
            for (int d = 0; d < 6; d++) {
                int ny = y + Dir.DY[d];
                if (ny >= 0 && ny < 256) inc.add(pack(x + Dir.DX[d], ny, z + Dir.DZ[d], 0));
            }
            increase(channel);
        }
        markDirty(x, y, z);
    }

    /** Full light computation for a freshly generated/loaded chunk (or a relight). */
    private void initChunk(Chunk c) {
        cache = null;
        initializing = c;
        try {
            int top = c.topSection();
            if (top >= 0) c.ensureSection(top);
            for (int sy = 0; sy <= top; sy++) c.sections[sy].resetLight(15);
            for (int sy = top + 1; sy < 16; sy++) if (c.sections[sy] != null) c.sections[sy].resetLight(15);
            int topY = top * 16 + 15;
            byte[] opacity = Blocks.OPACITY;
            int[] h = new int[256]; // lowest y that still has full sky light (column floor)
            int bx = c.cx << 4, bz = c.cz << 4;
            for (int z = 0; z < 16; z++)
                for (int x = 0; x < 16; x++) {
                    int level = 15, floor = topY + 1;
                    for (int y = topY; y >= 0; y--) {
                        int op = opacity[c.get(x, y, z) >> 4];
                        if (level > 0 && op > 0) level = Math.max(0, level - op);
                        if (level == 15) floor = y;
                        Section s = c.sections[y >> 4];
                        s.setSky(Section.index(x, y & 15, z), level);
                    }
                    h[z * 16 + x] = Math.max(0, floor);
                }
            // block light sources, one pass per colour channel
            for (int ch = 1; ch <= 3; ch++) {
                byte[] emission = Blocks.EMISSION_RGB[ch - 1];
                for (int sy = 0; sy <= top; sy++) {
                    Section s = c.sections[sy];
                    if (s.isEmpty()) continue;
                    char[] b = s.blocks;
                    if (b == null) continue;
                    for (int i = 0; i < 4096; i++) {
                        int e = emission[b[i] >> 4];
                        if (e > 0) {
                            s.setLight(ch - 1, i, e);
                            inc.add(pack(bx + (i & 15), sy * 16 + (i >> 8), bz + ((i >> 4) & 15), 0));
                        }
                    }
                }
                // inflow of block light from lit neighbours
                seedBorders(c, ch, null);
                increase(ch);
            }

            // sky: seed cells next to deeper columns, partially lit cells, and neighbour borders
            for (int z = 0; z < 16; z++)
                for (int x = 0; x < 16; x++) {
                    int hs = h[z * 16 + x];
                    int maxN = hs;
                    for (int d = 2; d < 6; d++) {
                        int nx = x + Dir.DX[d], nz = z + Dir.DZ[d];
                        if (nx >= 0 && nx < 16 && nz >= 0 && nz < 16) maxN = Math.max(maxN, h[nz * 16 + nx]);
                    }
                    for (int y = hs; y < maxN; y++) inc.add(pack(bx + x, y, bz + z, 0));
                    for (int y = hs - 1; y >= 0; y--) {
                        int l = c.getSky(x, y, z);
                        if (l <= 1) break;
                        inc.add(pack(bx + x, y, bz + z, 0));
                    }
                }
            seedBorders(c, 0, h);
            increase(0);
            c.state = Chunk.STATE_LIT;
        } finally {
            initializing = null;
            cache = null;
        }
        // this chunk and its neighbours need meshes
        World.SectionListener l = world.listener;
        for (int dx = -1; dx <= 1; dx++)
            for (int dz = -1; dz <= 1; dz++)
                for (int sy = 0; sy < 16; sy++) l.sectionDirty(c.cx + dx, sy, c.cz + dz, false);
    }

    /** Seeds border cells of this chunk and of its lit neighbours so light flows both ways. */
    private void seedBorders(Chunk c, int ch, int[] h) {
        int bx = c.cx << 4, bz = c.cz << 4;
        int maxY = Math.max(c.topSection(), 0) * 16 + 16;
        for (int d = 2; d < 6; d++) {
            Chunk n = world.getChunk(c.cx + Dir.DX[d], c.cz + Dir.DZ[d]);
            if (n == null || n.state < Chunk.STATE_LIT) continue;
            int nTop = Math.max(n.topSection(), 0) * 16 + 16;
            int yMax = Math.min(255, Math.max(maxY, nTop));
            for (int i = 0; i < 16; i++) {
                // cell in c at the border, and the matching cell in n
                int lx, lz;
                switch (d) {
                    case Dir.NORTH -> { lx = i; lz = 0; }
                    case Dir.SOUTH -> { lx = i; lz = 15; }
                    case Dir.WEST -> { lx = 0; lz = i; }
                    default -> { lx = 15; lz = i; }
                }
                int wx = bx + lx, wz = bz + lz, nx = wx + Dir.DX[d], nz = wz + Dir.DZ[d];
                for (int y = 0; y <= yMax; y++) {
                    int ln = ch == 0 ? n.getSky(nx & 15, y, nz & 15) : n.getLight(ch - 1, nx & 15, y, nz & 15);
                    int lc = ch == 0 ? c.getSky(lx, y, lz) : c.getLight(ch - 1, lx, y, lz);
                    if (ln > lc + 1) inc.add(pack(nx, y, nz, 0));
                    else if (lc > ln + 1) inc.add(pack(wx, y, wz, 0));
                }
            }
        }
    }
}
