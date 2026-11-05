package derpcraft.world;

import derpcraft.util.Workers;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/** Loads/generates chunks around the player on worker threads, unloads and saves far ones. */
public final class ChunkManager {
    private static final int MAX_RADIUS = 70;
    private static final int[] SPIRAL;

    static {
        List<int[]> l = new ArrayList<>();
        for (int x = -MAX_RADIUS; x <= MAX_RADIUS; x++)
            for (int z = -MAX_RADIUS; z <= MAX_RADIUS; z++)
                if (x * x + z * z <= MAX_RADIUS * MAX_RADIUS) l.add(new int[]{x, z, x * x + z * z});
        l.sort(Comparator.comparingInt(a -> a[2]));
        SPIRAL = new int[l.size() * 2];
        for (int i = 0; i < l.size(); i++) { SPIRAL[i * 2] = l.get(i)[0]; SPIRAL[i * 2 + 1] = l.get(i)[1]; }
    }

    public final World world;
    public final ChunkGenerator generator;
    public final RegionStorage storage;
    private final Workers workers;
    private final Set<Long> pending = ConcurrentHashMap.newKeySet();
    private final ConcurrentLinkedQueue<Chunk> ready = new ConcurrentLinkedQueue<>();
    private volatile int centerX, centerZ, radius = 8;
    public Consumer<Chunk> onLoad = c -> {}, onUnload = c -> {};
    private int lastScanX = Integer.MIN_VALUE, lastScanZ, lastScanR, scanCooldown;
    private boolean scanComplete;
    private int frame;
    private final java.util.concurrent.atomic.AtomicInteger savesPending = new java.util.concurrent.atomic.AtomicInteger();
    /**
     * Unloaded chunks whose save is still queued/running. If the player comes back before the save finished,
     * the chunk is taken from here instead of reading the stale copy from disk (edits were lost before).
     */
    private final ConcurrentHashMap<Long, Chunk> saving = new ConcurrentHashMap<>();
    /** Chunks written by far-away edits (WorldEdit): a copy loaded/generated before that write is stale. */
    private final ConcurrentHashMap<Long, Long> detachedWrites = new ConcurrentHashMap<>();
    private static final java.util.concurrent.atomic.AtomicLong STAMP = new java.util.concurrent.atomic.AtomicLong();

    public ChunkManager(World world, Workers workers) {
        this.world = world;
        this.workers = workers;
        this.generator = ChunkGenerator.create(world.info);
        this.storage = new RegionStorage(world.info.dir);
    }

    public int pendingCount() { return pending.size(); }

    private boolean inRange(int cx, int cz, int r) {
        int dx = cx - centerX, dz = cz - centerZ;
        return dx * dx + dz * dz <= r * r;
    }

    /** Called every frame from the main thread. */
    public void update(double px, double pz, int renderDistance) {
        centerX = (int) Math.floor(px) >> 4;
        centerZ = (int) Math.floor(pz) >> 4;
        radius = Math.min(MAX_RADIUS - 3, renderDistance + 1);

        // 1) insert finished chunks (bounded per frame to avoid hitches)
        long until = System.nanoTime() + 3_000_000L;
        Chunk c;
        while ((c = ready.poll()) != null) {
            pending.remove(c.key());
            if (!inRange(c.cx, c.cz, radius + 2) || world.chunks.containsKey(c.key())) continue;
            Long written = detachedWrites.get(c.key());
            if (written != null) {
                if (written > c.loadStamp) { scanComplete = false; continue; }   // edited on disk meanwhile: request it again
                detachedWrites.remove(c.key(), written);
            }
            world.chunks.put(c.key(), c);
            world.chunksChanged();
            onLoad.accept(c);
            world.light.lightChunk(c);
            if (System.nanoTime() > until) break;
        }

        // 2) request missing chunks
        boolean moved = centerX != lastScanX || centerZ != lastScanZ || radius != lastScanR;
        if (moved) scanComplete = false;
        int maxPending = Math.max(64, Workers.defaultThreads() * 12);
        if (!scanComplete && pending.size() < maxPending && --scanCooldown <= 0) {
            scanCooldown = 2;
            lastScanX = centerX; lastScanZ = centerZ; lastScanR = radius;
            boolean all = true;
            int r2 = radius * radius;
            for (int i = 0; i < SPIRAL.length; i += 2) {
                int dx = SPIRAL[i], dz = SPIRAL[i + 1];
                int d2 = dx * dx + dz * dz;
                if (d2 > r2) break;
                int cx = centerX + dx, cz = centerZ + dz;
                long k = Chunk.key(cx, cz);
                if (world.chunks.containsKey(k) || pending.contains(k)) continue;
                all = false;
                if (pending.size() >= maxPending) break;
                pending.add(k);
                final int fcx = cx, fcz = cz;
                workers.execute(d2, () -> produce(fcx, fcz));
            }
            scanComplete = all;
        }

        // 3) unload far chunks
        if ((++frame & 15) == 0 || moved) {
            int ur = radius + 3;
            for (Iterator<Chunk> it = world.chunks.values().iterator(); it.hasNext(); ) {
                Chunk ch = it.next();
                if (!inRange(ch.cx, ch.cz, ur)) {
                    it.remove();
                    world.chunksChanged();
                    onUnload.accept(ch);
                    if (ch.modified) saveAsync(ch);
                    else if (ch.saveRefs.get() > 0) {                     // autosave still writing it
                        saving.put(ch.key(), ch);
                        if (ch.saveRefs.get() == 0) saving.remove(ch.key(), ch);   // finished meanwhile
                    }
                }
            }
        }
    }

    /**
     * Writes a chunk on a worker thread. Until the write is done the chunk stays in 'saving', so a reload of it
     * (player came back, or unloaded right after an autosave) takes this copy instead of the older one on disk.
     */
    private void saveAsync(Chunk ch) {
        ch.modified = false;
        long key = ch.key();
        ch.saveRefs.incrementAndGet();
        if (!world.chunks.containsKey(key)) saving.put(key, ch);
        savesPending.incrementAndGet();
        workers.execute(-1, () -> {
            try { storage.save(ch); }
            finally {
                if (ch.saveRefs.decrementAndGet() == 0) saving.remove(key, ch);
                savesPending.decrementAndGet();
            }
        });
    }

    private void produce(int cx, int cz) {
        if (!inRange(cx, cz, radius + 2)) { pending.remove(Chunk.key(cx, cz)); return; }
        long stamp = STAMP.incrementAndGet();
        Chunk c = fromSaving(cx, cz);
        if (c == null) c = storage.load(cx, cz);
        if (c == null) {
            c = new Chunk(cx, cz);
            generator.generate(c);
            c.state = Chunk.STATE_GENERATED;
        }
        c.loadStamp = stamp;
        ready.add(c);
    }

    /** An unloaded chunk still waiting to be written, reset for re-insertion (light and meshes are rebuilt). */
    private Chunk fromSaving(int cx, int cz) {
        Chunk c = saving.get(Chunk.key(cx, cz));
        if (c == null) return null;
        c.state = Chunk.STATE_GENERATED;
        return c;
    }

    /** Synchronously loads/generates the chunks around a point (used when entering a world). */
    public void loadNow(int cx0, int cz0, int r) {
        for (int dx = -r; dx <= r; dx++)
            for (int dz = -r; dz <= r; dz++) {
                long k = Chunk.key(cx0 + dx, cz0 + dz);
                if (world.chunks.containsKey(k)) continue;
                Chunk c = fromSaving(cx0 + dx, cz0 + dz);
                if (c == null) c = storage.load(cx0 + dx, cz0 + dz);
                if (c == null) { c = new Chunk(cx0 + dx, cz0 + dz); generator.generate(c); c.state = Chunk.STATE_GENERATED; }
                world.chunks.put(k, c);
                world.chunksChanged();
                onLoad.accept(c);
                world.light.lightChunk(c);
            }
    }

    /** Loads or generates a chunk without adding it to the world (for edits far away). */
    public Chunk loadDetached(int cx, int cz) {
        Chunk c = saving.get(Chunk.key(cx, cz));
        if (c == null) c = storage.load(cx, cz);
        if (c == null) { c = new Chunk(cx, cz); generator.generate(c); }
        c.state = Chunk.STATE_GENERATED;
        return c;
    }

    /** Saves a chunk that is not part of the loaded world. */
    public void saveDetached(Chunk c) {
        for (Section s : c.sections) if (s != null) s.recount();
        storage.save(c);
        detachedWrites.put(c.key(), STAMP.incrementAndGet());
    }

    /** Saves modified chunks on worker threads (no frame hitch); used by autosave. */
    public void saveAllAsync() {
        for (Chunk c : world.chunks.values()) if (c.modified) saveAsync(c);
        try { world.info.save(); } catch (Exception e) { System.err.println("Failed to save level: " + e); }
    }

    public void saveAll() {
        for (Chunk c : world.chunks.values()) {
            if (c.modified) { c.modified = false; storage.save(c); }
        }
        try { world.info.save(); } catch (Exception e) { System.err.println("Failed to save level: " + e); }
    }

    public void close() {
        world.light.await(5000);
        long end = System.currentTimeMillis() + 10000;
        while (savesPending.get() > 0 && System.currentTimeMillis() < end) {
            try { Thread.sleep(5); } catch (InterruptedException e) { break; }
        }
        saveAll();
        world.light.shutdown();
        storage.close();
    }
}
