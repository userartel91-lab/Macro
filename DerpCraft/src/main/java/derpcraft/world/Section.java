package derpcraft.world;

/**
 * 16x16x16 block storage. Index = (y << 8) | (z << 4) | x.
 * Arrays are allocated lazily: blocks == null means all air, light arrays == null means uniform default value.
 */
public final class Section {
    public volatile char[] blocks;
    public volatile byte[] sky;      // nibbles, 2048 bytes
    /** Block light nibbles per colour channel (R, G, B). The classic block light level is max(R, G, B). */
    public final byte[][] light = new byte[3][];
    public byte skyDefault = 15;
    public int nonAir;
    /** Soot per block: bit d set = face d (see Dir) is blackened. null = none. Saved with the chunk, drawn by the mesher. */
    public volatile byte[] soot;

    public static int index(int x, int y, int z) { return (y << 8) | (z << 4) | x; }

    public int get(int i) { char[] b = blocks; return b == null ? 0 : b[i]; }

    public void set(int i, int state) {
        char[] b = blocks;
        if (b == null) {
            if (state == 0) return;
            b = blocks = new char[4096];
        }
        int old = b[i];
        if (old == 0 && state != 0) nonAir++;
        else if (old != 0 && state == 0) nonAir--;
        b[i] = (char) state;
    }

    public void recount() {
        int n = 0;
        char[] b = blocks;
        if (b != null) for (char c : b) if (c != 0) n++;
        nonAir = n;
        if (n == 0) blocks = null;
    }

    public boolean isEmpty() { return blocks == null || nonAir == 0; }

    public int getSky(int i) {
        byte[] s = sky;
        return s == null ? skyDefault : (s[i >> 1] >> ((i & 1) << 2)) & 15;
    }

    public void setSky(int i, int v) {
        byte[] s = sky;
        if (s == null) {
            if (v == skyDefault) return;
            s = new byte[2048];
            byte d = (byte) (skyDefault | skyDefault << 4);
            java.util.Arrays.fill(s, d);
            sky = s;
        }
        int sh = (i & 1) << 2;
        s[i >> 1] = (byte) ((s[i >> 1] & ~(15 << sh)) | (v << sh));
    }

    /** Classic block light (0..15) = brightest channel. */
    public int getLight(int i) {
        return Math.max(getLight(0, i), Math.max(getLight(1, i), getLight(2, i)));
    }

    public int getLight(int ch, int i) {
        byte[] s = light[ch];
        return s == null ? 0 : (s[i >> 1] >> ((i & 1) << 2)) & 15;
    }

    public void setLight(int ch, int i, int v) {
        byte[] s = light[ch];
        if (s == null) {
            if (v == 0) return;
            s = light[ch] = new byte[2048];
        }
        int sh = (i & 1) << 2;
        s[i >> 1] = (byte) ((s[i >> 1] & ~(15 << sh)) | (v << sh));
    }

    public void resetLight(int skyValue) {
        sky = null; light[0] = light[1] = light[2] = null; skyDefault = (byte) skyValue;
    }
}
