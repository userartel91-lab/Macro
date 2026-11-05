package derpcraft.world;

import derpcraft.block.Block;
import derpcraft.block.BlockGetter;
import derpcraft.block.Blocks;
import derpcraft.entity.Entity;
import derpcraft.item.ItemStack;
import derpcraft.world.be.BlockEntity;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** The loaded world: chunk map, block/light access by world coordinates, edits, block entities, entities. */
public final class World implements BlockGetter {
    public interface SectionListener { void sectionDirty(int cx, int sy, int cz, boolean urgent); }

    public final WorldInfo info;
    public final ConcurrentHashMap<Long, Chunk> chunks = new ConcurrentHashMap<>();
    public final LightEngine light;
    public volatile SectionListener listener = (a, b, c, d) -> {};

    /** Sees every block change (used to record what an actor does). */
    public interface BlockRecorder {
        void changed(int x, int y, int z, int before, int after);
        /** Just before setBlock replaces a block (its block entity, e.g. a chest's items, is still there). */
        default void before(int x, int y, int z, int old, int state) {}
    }
    public BlockRecorder blockRecorder;

    /** Dropped items, falling blocks... (main thread only). */
    public final List<Entity> entities = new ArrayList<>();
    public final Random random = new Random();
    /** Game time in ticks (not the day time), used for scheduled ticks. */
    public long gameTime;
    private final PriorityQueue<long[]> scheduled = new PriorityQueue<>(Comparator.comparingLong(a -> a[0]));

    public World(WorldInfo info) {
        this.info = info;
        this.light = new LightEngine(this);
    }

    /**
     * Last looked-up chunk column of the thread that made the world (the game thread: ticks, collision, rays, AI,
     * rendering lists). Block reads come in runs inside one column, and a map lookup costs a boxed Long plus a hash
     * probe per block. Other threads (light, meshing, saving) keep their own caches / snapshots and use the map.
     * {@link #chunksChanged()} (after every put / remove of {@link #chunks}, done on the game thread) clears it.
     */
    private final Thread owner = Thread.currentThread();
    private int lastCx = Integer.MIN_VALUE, lastCz = Integer.MIN_VALUE;
    private Chunk lastChunk;

    /** Must be called after adding or removing entries of {@link #chunks} (ChunkManager does). */
    public void chunksChanged() {
        if (Thread.currentThread() == owner) lastCx = Integer.MIN_VALUE;
        else invalidateFromOtherThread = true;
    }
    private volatile boolean invalidateFromOtherThread;

    public Chunk getChunk(int cx, int cz) {
        if (Thread.currentThread() != owner) return chunks.get(Chunk.key(cx, cz));
        if (invalidateFromOtherThread) { invalidateFromOtherThread = false; lastCx = Integer.MIN_VALUE; }
        if (cx == lastCx && cz == lastCz) return lastChunk;
        Chunk c = chunks.get(Chunk.key(cx, cz));
        lastCx = cx; lastCz = cz; lastChunk = c;
        return c;
    }

    public Chunk chunkAt(int x, int z) { return getChunk(x >> 4, z >> 4); }

    public int getState(int x, int y, int z) {
        if (y < 0 || y >= Chunk.HEIGHT) return 0;
        Chunk c = chunkAt(x, z);
        return c == null ? 0 : c.get(x & 15, y, z & 15);
    }

    public Block getBlock(int x, int y, int z) { return Blocks.byId(getState(x, y, z) >> 4); }

    public int getMeta(int x, int y, int z) { return getState(x, y, z) & 15; }

    public int getSky(int x, int y, int z) {
        Chunk c = chunkAt(x, z);
        return c == null ? 15 : c.getSky(x & 15, y, z & 15);
    }

    public int getBlockLight(int x, int y, int z) {
        Chunk c = chunkAt(x, z);
        return c == null ? 0 : c.getLight(x & 15, y, z & 15);
    }

    /** Coloured block light channel (0 = red, 1 = green, 2 = blue). */
    public int getBlockLight(int ch, int x, int y, int z) {
        Chunk c = chunkAt(x, z);
        return c == null ? 0 : c.getLight(ch, x & 15, y, z & 15);
    }

    public boolean isLoaded(int x, int z) { return chunkAt(x, z) != null; }

    /** Sets a block with lighting and re-meshing. Returns false if the chunk is not loaded. */
    public boolean setBlock(int x, int y, int z, int state) {
        if (y < 0 || y >= Chunk.HEIGHT) return false;
        Chunk c = chunkAt(x, z);
        if (c == null) return false;
        int old = c.get(x & 15, y, z & 15);
        if (old == state) return true;
        BlockRecorder rec = blockRecorder;
        if (rec != null) rec.before(x, y, z, old, state);
        if ((old >> 4) != (state >> 4)) {
            int idx = BlockEntityKey.key(x, y, z);
            BlockEntity be = c.blockEntities.remove(idx);
            if (be != null) dropContents(be);
        }
        return setBlockKeepEntity(x, y, z, state);
    }

    /**
     * Sets a block without spilling the old block's container (scene replays and "restore world": the contents are
     * put back from a snapshot, so spilling them would duplicate items). The old block entity is simply removed.
     */
    public boolean setBlockNoDrop(int x, int y, int z, int state) {
        if (y < 0 || y >= Chunk.HEIGHT) return false;
        Chunk c = chunkAt(x, z);
        if (c == null) return false;
        int old = c.get(x & 15, y, z & 15);
        if (old == state) return true;
        if ((old >> 4) != (state >> 4)) c.blockEntities.remove(BlockEntityKey.key(x, y, z));
        return setBlockKeepEntity(x, y, z, state);
    }

    /** The block entity stored at a position, or null (never creates one). */
    public BlockEntity existingBlockEntity(int x, int y, int z) {
        Chunk c = chunkAt(x, z);
        if (c == null || y < 0 || y > 255) return null;
        return c.blockEntities.get(BlockEntityKey.key(x, y, z));
    }

    /** Changes the block but keeps its block entity (furnace lighting up, door opening). */
    public boolean setBlockKeepEntity(int x, int y, int z, int state) {
        if (y < 0 || y >= Chunk.HEIGHT) return false;
        Chunk c = chunkAt(x, z);
        if (c == null) return false;
        int old = c.get(x & 15, y, z & 15);
        if (old == state) return true;
        c.setEdited(x & 15, y, z & 15, state);                 // a different block does not wear the old soot
        c.modified = true;
        BlockRecorder rec = blockRecorder;
        if (rec != null) rec.changed(x, y, z, old, state);
        markAround(x, y, z, true);
        light.blockChanged(x, y, z, old, state); // the light thread skips chunks whose initial lighting is still pending
        return true;
    }

    public boolean setBlock(int x, int y, int z, Block b, int meta) { return setBlock(x, y, z, (b.id << 4) | (meta & 15)); }

    // ------------------------------------------------------------------------------------------ block entities
    /** The block entity at a position, created on demand if the block wants one. */
    public BlockEntity getBlockEntity(int x, int y, int z) {
        Chunk c = chunkAt(x, z);
        if (c == null || y < 0 || y > 255) return null;
        int idx = BlockEntityKey.key(x, y, z);
        BlockEntity be = c.blockEntities.get(idx);
        Block b = getBlock(x, y, z);
        String type = b.blockEntityType();
        if (be != null && type != null && be.type().equals(type)) { be.world = this; return be; }
        if (type == null) { if (be != null) c.blockEntities.remove(idx); return null; }
        BlockEntity proto = b.createBlockEntity();
        proto.x = x; proto.y = y; proto.z = z; proto.world = this;
        c.blockEntities.put(idx, proto);
        c.modified = true;
        return proto;
    }

    /** All loaded block entities that need ticking (furnaces, chest lids). */
    public void tickBlockEntities() {
        for (Chunk c : chunks.values()) {
            if (c.blockEntities.isEmpty()) continue;
            for (Iterator<BlockEntity> it = c.blockEntities.values().iterator(); it.hasNext(); ) {
                BlockEntity be = it.next();
                be.world = this;
                if (!be.ticks()) continue;
                String type = getBlock(be.x, be.y, be.z).blockEntityType();
                if (type == null || !type.equals(be.type())) { it.remove(); continue; }
                be.tick();
            }
        }
    }

    /** Spills a container's items into the world (block broken). */
    private void dropContents(BlockEntity be) {
        if (be.keepsContents()) return;
        var inv = be.inventory();
        if (inv == null) return;
        for (int i = 0; i < inv.size(); i++) {
            ItemStack st = inv.get(i);
            if (st != null) dropItem(be.x + 0.5, be.y + 0.5, be.z + 0.5, st);
        }
    }

    /** Spawns a dropped item entity with a small random motion. */
    public void dropItem(double x, double y, double z, ItemStack stack) {
        if (ItemStack.isEmpty(stack)) return;
        var e = new derpcraft.entity.ItemEntity(this, x, y, z, stack.copy());
        e.mx = (random.nextDouble() - 0.5) * 0.2;
        e.my = 0.2;
        e.mz = (random.nextDouble() - 0.5) * 0.2;
        entities.add(e);
    }

    // ------------------------------------------------------------------------------------------ soot
    /** Blackens the faces in 'mask' of the block at x,y,z. Returns true if something changed (the chunk is saved and re-meshed). */
    public boolean addSoot(int x, int y, int z, int mask) {
        if (y < 0 || y >= Chunk.HEIGHT || mask == 0) return false;
        Chunk c = chunkAt(x, z);
        if (c == null) return false;
        Section s = c.sections[y >> 4];
        if (s == null) return false;
        int i = Section.index(x & 15, y & 15, z & 15);
        byte[] a = s.soot;
        int old = a == null ? 0 : a[i] & 63;
        if ((old | mask) == old) return false;
        if (a == null) a = s.soot = new byte[4096];
        a[i] = (byte) (old | mask & 63);
        c.modified = true;
        markAround(x, y, z, false);
        return true;
    }

    /** Fire: positions that have a tick scheduled -> game time it is due (fires loaded from disk start ticking again via random ticks). */
    public final java.util.Map<Long, Long> fireDue = new java.util.HashMap<>();

    // ------------------------------------------------------------------------------------------ scheduled ticks
    public void scheduleTick(int x, int y, int z, int delay) {
        scheduled.add(new long[]{gameTime + delay, x, y, z});
    }

    public void runScheduledTicks() {
        while (!scheduled.isEmpty() && scheduled.peek()[0] <= gameTime) {
            long[] t = scheduled.poll();
            int x = (int) t[1], y = (int) t[2], z = (int) t[3];
            if (chunkAt(x, z) == null) continue;
            int st = getState(x, y, z);
            Blocks.byId(st >> 4).scheduledTick(this, x, y, z, st & 15);
        }
    }

    /** Relights and re-meshes all chunks touched by a bulk edit within the given box. */
    public void finishBulk(int x0, int z0, int x1, int z1) {
        java.util.List<Chunk> list = new java.util.ArrayList<>();
        for (int cx = (x0 >> 4); cx <= (x1 >> 4); cx++)
            for (int cz = (z0 >> 4); cz <= (z1 >> 4); cz++) {
                Chunk c = getChunk(cx, cz);
                if (c != null) {
                    for (Section s : c.sections) if (s != null) s.recount();
                    list.add(c);
                    for (int sy = 0; sy < 16; sy++) listener.sectionDirty(cx, sy, cz, false);
                }
            }
        light.relight(list);
    }

    /** Marks the section containing (x,y,z) dirty plus neighbours when on a border. */
    public void markAround(int x, int y, int z, boolean urgent) {
        int cx = x >> 4, cz = z >> 4, sy = y >> 4;
        SectionListener l = listener;
        l.sectionDirty(cx, sy, cz, urgent);
        int lx = x & 15, ly = y & 15, lz = z & 15;
        if (lx == 0) l.sectionDirty(cx - 1, sy, cz, urgent);
        if (lx == 15) l.sectionDirty(cx + 1, sy, cz, urgent);
        if (lz == 0) l.sectionDirty(cx, sy, cz - 1, urgent);
        if (lz == 15) l.sectionDirty(cx, sy, cz + 1, urgent);
        if (ly == 0 && sy > 0) l.sectionDirty(cx, sy - 1, cz, urgent);
        if (ly == 15 && sy < 15) l.sectionDirty(cx, sy + 1, cz, urgent);
    }

    /** Highest solid (non-air, non-plant) block y at x,z or -1. */
    public int topSolidY(int x, int z) {
        Chunk c = chunkAt(x, z);
        if (c == null) return -1;
        for (int y = c.topSection() * 16 + 15; y >= 0; y--) {
            Block b = Blocks.byId(c.get(x & 15, y, z & 15) >> 4);
            if (b.solid || b.shape == Block.Shape.LIQUID) return y;
        }
        return -1;
    }
}
