package derpcraft.render;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.util.Map;
import java.util.TreeMap;

import static org.lwjgl.opengl.GL45C.*;

/**
 * One big vertex buffer shared by all chunk sections (allocation unit = 1 vertex = 12 bytes).
 * Best-fit free list with coalescing; grows by copying into a larger buffer on the GPU.
 * Free blocks are indexed by start (for coalescing) and by size (O(log n) allocation; the old first-fit scan walked
 * every free block, which got slow once the arena was fragmented at large render distances).
 */
public final class GpuArena {
    public static final int VERTEX_BYTES = 12;
    public int buffer;
    private long capacity;              // in vertices
    private final TreeMap<Long, Long> free = new TreeMap<>(); // start -> length
    private final java.util.TreeSet<Long> bySize = new java.util.TreeSet<>(); // length << 32 | start
    private long used;
    private ByteBuffer staging = ByteBuffer.allocateDirect(1 << 20).order(ByteOrder.nativeOrder());
    public Runnable onResize = () -> {};

    public GpuArena(long initialVertices) {
        capacity = initialVertices;
        buffer = glGenBuffers();
        glBindBuffer(GL_ARRAY_BUFFER, buffer);
        glBufferData(GL_ARRAY_BUFFER, capacity * VERTEX_BYTES, GL_DYNAMIC_DRAW);
        addFree(0L, capacity);
    }

    private static long sizeKey(long start, long len) { return len << 32 | start; }

    private void addFree(long start, long len) { free.put(start, len); bySize.add(sizeKey(start, len)); }

    private void removeFree(long start, long len) { free.remove(start); bySize.remove(sizeKey(start, len)); }

    public long usedBytes() { return used * VERTEX_BYTES; }
    public long capacityBytes() { return capacity * VERTEX_BYTES; }

    /** Allocates n vertices, returns the start vertex. */
    public long alloc(int n) {
        Long k = bySize.ceiling(sizeKey(0, n));
        if (k == null) {
            grow(Math.max(capacity * 2, capacity + n * 2L));
            return alloc(n);
        }
        long start = k & 0xFFFFFFFFL, len = k >>> 32;
        removeFree(start, len);
        if (len > n) addFree(start + n, len - n);
        used += n;
        return start;
    }

    public void free(long start, int n) {
        if (n <= 0) return;
        used -= n;
        long s = start, l = n;
        Map.Entry<Long, Long> prev = free.floorEntry(s);
        if (prev != null && prev.getKey() + prev.getValue() == s) { s = prev.getKey(); l += prev.getValue(); removeFree(prev.getKey(), prev.getValue()); }
        Long nextLen = free.get(start + n);
        if (nextLen != null) { removeFree(start + n, nextLen); l += nextLen; }
        addFree(s, l);
    }

    private void grow(long newCap) {
        int nb = glGenBuffers();
        glBindBuffer(GL_COPY_WRITE_BUFFER, nb);
        glBufferData(GL_COPY_WRITE_BUFFER, newCap * VERTEX_BYTES, GL_DYNAMIC_DRAW);
        glBindBuffer(GL_COPY_READ_BUFFER, buffer);
        glCopyBufferSubData(GL_COPY_READ_BUFFER, GL_COPY_WRITE_BUFFER, 0, 0, capacity * VERTEX_BYTES);
        glDeleteBuffers(buffer);
        buffer = nb;
        // new space, merged with a trailing free block
        Map.Entry<Long, Long> prev = free.lowerEntry(capacity);
        if (prev != null && prev.getKey() + prev.getValue() == capacity) {
            removeFree(prev.getKey(), prev.getValue());
            addFree(prev.getKey(), prev.getValue() + newCap - capacity);
        } else addFree(capacity, newCap - capacity);
        capacity = newCap;
        onResize.run();
    }

    public void upload(long startVertex, int[] data, int offsetInts, int countInts) {
        int bytes = countInts * 4;
        if (staging.capacity() < bytes) staging = ByteBuffer.allocateDirect(Math.max(bytes, staging.capacity() * 2)).order(ByteOrder.nativeOrder());
        staging.clear();
        IntBuffer ib = staging.asIntBuffer();
        ib.put(data, offsetInts, countInts);
        staging.limit(bytes);
        glBindBuffer(GL_ARRAY_BUFFER, buffer);
        glBufferSubData(GL_ARRAY_BUFFER, startVertex * VERTEX_BYTES, staging);
    }
}
