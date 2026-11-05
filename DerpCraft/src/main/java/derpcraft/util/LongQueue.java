package derpcraft.util;

/** Growable ring buffer of longs (not thread safe). */
public final class LongQueue {
    private long[] a = new long[1 << 12];
    private int head, tail, size;

    public void add(long v) {
        if (size == a.length) grow();
        a[tail] = v;
        tail = (tail + 1) & (a.length - 1);
        size++;
    }

    public long poll() {
        long v = a[head];
        head = (head + 1) & (a.length - 1);
        size--;
        return v;
    }

    public boolean isEmpty() { return size == 0; }
    public int size() { return size; }
    public void clear() { head = tail = size = 0; }

    private void grow() {
        long[] n = new long[a.length * 2];
        for (int i = 0; i < size; i++) n[i] = a[(head + i) & (a.length - 1)];
        a = n; head = 0; tail = size;
    }
}
