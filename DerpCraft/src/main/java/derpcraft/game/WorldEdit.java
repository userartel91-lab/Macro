package derpcraft.game;

import derpcraft.block.Block;
import derpcraft.block.Blocks;
import derpcraft.world.Chunk;
import derpcraft.world.World;

import java.util.*;
import java.util.function.IntUnaryOperator;

/**
 * WorldEdit-style building tools: selection, set, replace, walls, copy/paste, undo.
 * Edits walk chunk by chunk; chunks that are not loaded are loaded (or generated) on the fly, edited and saved
 * straight back to disk, so edits of any size work without keeping the area in memory.
 */
public final class WorldEdit {
    public static final long MAX_VOLUME = 256L * 1024 * 1024;
    /** Edits larger than this are applied without an undo record (memory). */
    public static final int MAX_UNDO = 16 * 1024 * 1024;
    private final Session s;
    public int[] pos1, pos2;
    private Clipboard clipboard;
    private final Deque<Undo> undo = new ArrayDeque<>();

    private record Undo(long[] pos, char[] old, int count, int[] box) {}
    private record Clipboard(int sx, int sy, int sz, char[] data, int ox, int oy, int oz) {}

    /** Returns the new state for a position, or -1 to keep it. */
    interface PosFn { int at(int x, int y, int z, int cur); }

    WorldEdit(Session s) { this.s = s; }

    /** The selection frame is drawn for 10 seconds after the last use (a position set, an edit); F3 + D hides it at once. */
    private static final long FRAME_NANOS = 10_000_000_000L;
    private long frameUntil;
    public void showFrame() { frameUntil = System.nanoTime() + FRAME_NANOS; }
    public void hideFrame() { frameUntil = 0; }
    public boolean frameVisible() { return System.nanoTime() < frameUntil; }

    /** Normalised selection {x0,y0,z0,x1,y1,z1} or null. */
    public int[] selection() {
        if (pos1 == null || pos2 == null) return null;
        return new int[]{Math.min(pos1[0], pos2[0]), Math.min(pos1[1], pos2[1]), Math.min(pos1[2], pos2[2]),
                Math.max(pos1[0], pos2[0]), Math.max(pos1[1], pos2[1]), Math.max(pos1[2], pos2[2])};
    }

    public static long volume(int[] b) { return (long) (b[3] - b[0] + 1) * (b[4] - b[1] + 1) * (b[5] - b[2] + 1); }

    // ------------------------------------------------------------------------------------------ core
    private final class Recorder {
        long[] pos = new long[1024];
        char[] old = new char[1024];
        int n;
        void add(int x, int y, int z, int cur) {
            if (n < MAX_UNDO) {
                if (n == pos.length) { pos = Arrays.copyOf(pos, n * 2); old = Arrays.copyOf(old, n * 2); }
                pos[n] = pack(x, y, z);
                old[n] = (char) cur;
            }
            n++;
        }
    }

    /** Visits every chunk column overlapping the box; unloaded chunks are loaded/generated and saved afterwards. */
    private int edit(int[] box, PosFn f) {
        World w = s.world;
        int x0 = box[0], y0 = Math.max(0, box[1]), z0 = box[2], x1 = box[3], y1 = Math.min(255, box[4]), z1 = box[5];
        if (y0 > y1) return 0;
        long vol = (long) (x1 - x0 + 1) * (y1 - y0 + 1) * (z1 - z0 + 1);
        if (vol > MAX_VOLUME) throw new IllegalArgumentException("Selection too large (" + vol + " blocks, max " + MAX_VOLUME + ")");
        showFrame();
        Recorder rec = new Recorder();
        int loadedEdits = 0;
        for (int cx = x0 >> 4; cx <= x1 >> 4; cx++)
            for (int cz = z0 >> 4; cz <= z1 >> 4; cz++) {
                Chunk c = w.getChunk(cx, cz);
                boolean detached = c == null;
                if (detached) c = s.chunks.loadDetached(cx, cz);
                int ax = Math.max(x0, cx << 4), bx = Math.min(x1, (cx << 4) + 15);
                int az = Math.max(z0, cz << 4), bz = Math.min(z1, (cz << 4) + 15);
                int before = rec.n;
                for (int x = ax; x <= bx; x++)
                    for (int z = az; z <= bz; z++)
                        for (int y = y0; y <= y1; y++) {
                            int cur = c.get(x & 15, y, z & 15);
                            int nv = f.at(x, y, z, cur);
                            if (nv < 0 || nv == cur) continue;
                            rec.add(x, y, z, cur);
                            c.setEdited(x & 15, y, z & 15, nv);
                        }
                if (rec.n != before) {
                    if (detached) s.chunks.saveDetached(c);
                    else { c.modified = true; loadedEdits++; }
                }
            }
        if (rec.n > 0) {
            if (rec.n <= MAX_UNDO) {
                undo.push(new Undo(rec.pos, rec.old, rec.n, new int[]{x0, y0, z0, x1, y1, z1}));
                while (undo.size() > 10) undo.removeLast();
            } else s.chat("§eEdit too large to undo (" + rec.n + " blocks)");
            if (loadedEdits > 0) w.finishBulk(x0, z0, x1, z1);
        }
        return rec.n;
    }

    /** Applies f(oldState) -> newState (or -1 to keep) to every block in the box. Returns changed count. */
    public int apply(int[] box, IntUnaryOperator f) { return edit(box, (x, y, z, cur) -> f.applyAsInt(cur)); }

    public int fill(int x1, int y1, int z1, int x2, int y2, int z2, Block b, int meta) {
        int st = (b.id << 4) | meta;
        return apply(new int[]{Math.min(x1, x2), Math.min(y1, y2), Math.min(z1, z2), Math.max(x1, x2), Math.max(y1, y2), Math.max(z1, z2)}, cur -> st);
    }

    /** The four vertical faces of the box, as one undo step. */
    public int walls(int[] b, int state) {
        return edit(b, (x, y, z, cur) -> (x == b[0] || x == b[3] || z == b[2] || z == b[5]) ? state : -1);
    }

    public boolean undo() {
        Undo u = undo.poll();
        if (u == null) return false;
        World w = s.world;
        Map<Long, Chunk> detached = new HashMap<>();
        boolean anyLoaded = false;
        for (int i = u.count - 1; i >= 0; i--) {
            long p = u.pos[i];
            int x = ux(p), y = uy(p), z = uz(p);
            Chunk c = w.chunkAt(x, z);
            if (c == null) {
                long k = Chunk.key(x >> 4, z >> 4);
                c = detached.get(k);
                if (c == null) { c = s.chunks.loadDetached(x >> 4, z >> 4); detached.put(k, c); }
            } else { c.modified = true; anyLoaded = true; }
            c.setEdited(x & 15, y, z & 15, u.old[i]);
        }
        for (Chunk c : detached.values()) s.chunks.saveDetached(c);
        if (anyLoaded) w.finishBulk(u.box[0], u.box[2], u.box[3], u.box[5]);
        return true;
    }

    /** Copies the selection relative to the player's block position. */
    public int copy(int px, int py, int pz) {
        int[] b = selection();
        if (b == null) throw new IllegalArgumentException("Make a selection first (//pos1, //pos2)");
        if (volume(b) > 64L * 1024 * 1024) throw new IllegalArgumentException("Selection too large to copy");
        int sx = b[3] - b[0] + 1, sy = b[4] - b[1] + 1, sz = b[5] - b[2] + 1;
        char[] data = new char[sx * sy * sz];
        edit(b, (x, y, z, cur) -> {
            if (y >= 0 && y < 256) data[((y - b[1]) * sz + (z - b[2])) * sx + (x - b[0])] = (char) cur;
            return -1;
        });
        clipboard = new Clipboard(sx, sy, sz, data, b[0] - px, b[1] - py, b[2] - pz);
        return data.length;
    }

    public int paste(int px, int py, int pz, boolean skipAir) {
        Clipboard c = clipboard;
        if (c == null) throw new IllegalArgumentException("Clipboard is empty (use //copy)");
        int x0 = px + c.ox, y0 = py + c.oy, z0 = pz + c.oz;
        int[] box = {x0, y0, z0, x0 + c.sx - 1, y0 + c.sy - 1, z0 + c.sz - 1};
        return edit(box, (x, y, z, cur) -> {
            int v = c.data[((y - y0) * c.sz + (z - z0)) * c.sx + (x - x0)];
            return skipAir && v == 0 ? -1 : v;
        });
    }

    /** Rotates the clipboard 90 degrees clockwise around Y (block meta is kept). */
    public void rotateClipboard() {
        Clipboard c = clipboard;
        if (c == null) throw new IllegalArgumentException("Clipboard is empty");
        int nsx = c.sz, nsz = c.sx;
        char[] d = new char[c.data.length];
        for (int x = 0; x < c.sx; x++) for (int y = 0; y < c.sy; y++) for (int z = 0; z < c.sz; z++) {
            int nx = c.sz - 1 - z, nz = x;
            d[(y * nsz + nz) * nsx + nx] = c.data[(y * c.sz + z) * c.sx + x];
        }
        clipboard = new Clipboard(nsx, c.sy, nsz, d, -c.oz - c.sz + 1, c.oy, c.ox);
    }

    /** Counts blocks of a type in the selection (loads chunks as needed). */
    public long count(int[] box, int blockId) {
        long[] n = {0};
        edit(box, (x, y, z, cur) -> { if ((cur >> 4) == blockId) n[0]++; return -1; });
        return n[0];
    }

    private static long pack(int x, int y, int z) { return ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | (y & 0xFFF); }
    private static int ux(long p) { return (int) (p >> 38); }
    private static int uz(long p) { int z = (int) ((p >> 12) & 0x3FFFFFF); return (z << 6) >> 6; }
    private static int uy(long p) { return (int) (p & 0xFFF); }

    /** Parses "stone", "oak_stairs:3" (meta) into a state. */
    public static int parseState(String s) {
        int meta = 0;
        int c = s.lastIndexOf(':');
        String name = s;
        if (c > 0 && !s.substring(c + 1).isEmpty() && s.substring(c + 1).chars().allMatch(Character::isDigit)) {
            meta = Integer.parseInt(s.substring(c + 1)) & 15;
            name = s.substring(0, c);
        }
        if (name.equals("air")) return 0;
        Block b = Blocks.byName(name);
        if (b == null) throw new IllegalArgumentException("Unknown block: " + name);
        return (b.id << 4) | meta;
    }
}
