package derpcraft.util;

/** Growable int array. */
public final class IntList {
    public int[] a;
    public int size;

    public IntList(int cap) { a = new int[cap]; }

    public void add(int v) {
        if (size == a.length) a = java.util.Arrays.copyOf(a, a.length * 2);
        a[size++] = v;
    }

    public void add3(int x, int y, int z) {
        if (size + 3 > a.length) a = java.util.Arrays.copyOf(a, Math.max(a.length * 2, size + 3));
        a[size] = x; a[size + 1] = y; a[size + 2] = z;
        size += 3;
    }

    public void clear() { size = 0; }
}
