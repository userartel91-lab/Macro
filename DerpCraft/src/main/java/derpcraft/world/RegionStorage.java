package derpcraft.world;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

/**
 * Region files: 32x32 chunks per file, a header of 1024 (offset,length) entries followed by deflated chunk blobs.
 * Rewritten chunks reuse their slot when they fit, otherwise they are appended.
 */
public final class RegionStorage {
    private static final int VERSION = 3;
    private final Path dir;
    private final BlockIdMap ids;
    private final LinkedHashMap<Long, RandomAccessFile> open = new LinkedHashMap<>(16, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<Long, RandomAccessFile> e) {
            if (size() > 48) { try { e.getValue().close(); } catch (IOException ignored) {} return true; }
            return false;
        }
    };

    public RegionStorage(Path worldDir) {
        this.dir = worldDir.resolve("region");
        try { Files.createDirectories(dir); ids = new BlockIdMap(worldDir); } catch (IOException e) { throw new UncheckedIOException(e); }
    }

    private RandomAccessFile file(int rx, int rz, boolean create) throws IOException {
        long k = Chunk.key(rx, rz);
        RandomAccessFile f = open.get(k);
        if (f != null) return f;
        Path p = dir.resolve("r." + rx + "." + rz + ".dcr");
        if (!create && !Files.exists(p)) return null;
        f = new RandomAccessFile(p.toFile(), "rw");
        if (f.length() < 8192) { f.setLength(8192); }
        open.put(k, f);
        return f;
    }

    /**
     * Only the file access is serialized; inflating and decoding run in parallel on the worker threads
     * (before, every chunk load and save of all workers waited for one lock including the (de)compression).
     */
    public Chunk load(int cx, int cz) {
        try {
            byte[] data = readRaw(cx, cz);
            return data == null ? null : decode(cx, cz, data);
        } catch (Exception e) {
            System.err.println("Failed to load chunk " + cx + "," + cz + ": " + e);
            return null;
        }
    }

    private synchronized byte[] readRaw(int cx, int cz) throws IOException {
        RandomAccessFile f = file(cx >> 5, cz >> 5, false);
        if (f == null) return null;
        int idx = ((cz & 31) << 5) | (cx & 31);
        f.seek(idx * 8L);
        int off = f.readInt(), len = f.readInt();
        if (off == 0 || len <= 0 || (long) off + len > f.length()) return null;
        byte[] data = new byte[len];
        f.seek(off);
        f.readFully(data);
        return data;
    }

    private static final java.util.concurrent.atomic.AtomicLong SAVE_SEQ = new java.util.concurrent.atomic.AtomicLong();

    public void save(Chunk c) {
        long seq = SAVE_SEQ.incrementAndGet();   // taken before encoding: a later number holds newer contents
        byte[] data;
        try { data = encode(c); }
        catch (RuntimeException e) { System.err.println("Failed to encode chunk " + c.cx + "," + c.cz + ": " + e); return; }
        write(c, data, seq);
    }

    private synchronized void write(Chunk c, byte[] data, long seq) {
        if (seq < c.writtenSeq) return;          // a newer save of this chunk already finished: don't roll it back
        c.writtenSeq = seq;
        try {
            RandomAccessFile f = file(c.cx >> 5, c.cz >> 5, true);
            int idx = ((c.cz & 31) << 5) | (c.cx & 31);
            f.seek(idx * 8L);
            int off = f.readInt(), len = f.readInt();
            int cap = len > 0 ? ((len + 4095) & ~4095) : 0;
            if (off == 0 || data.length > cap) off = (int) Math.max(8192, f.length());
            f.seek(off);
            f.write(data);
            int padded = (data.length + 4095) & ~4095;
            if (f.length() < off + padded) f.setLength(off + padded);
            f.seek(idx * 8L);
            f.writeInt(off);
            f.writeInt(data.length);
        } catch (IOException e) {
            System.err.println("Failed to save chunk " + c.cx + "," + c.cz + ": " + e);
        }
    }

    public synchronized void close() {
        for (RandomAccessFile f : open.values()) try { f.close(); } catch (IOException ignored) {}
        open.clear();
    }

    private byte[] encode(Chunk c) {
        try {
            ByteArrayOutputStream raw = new ByteArrayOutputStream(64 * 1024);
            DataOutputStream out = new DataOutputStream(raw);
            out.writeInt(VERSION);
            out.write(c.temperature);
            out.write(c.humidity);
            int mask = 0;
            for (int i = 0; i < 16; i++) if (c.sections[i] != null && !c.sections[i].isEmpty()) mask |= 1 << i;
            out.writeShort(mask);
            byte[] sec = new byte[8192];
            for (int i = 0; i < 16; i++) {
                if ((mask & (1 << i)) == 0) continue;
                char[] b = c.sections[i].blocks;
                for (int j = 0; j < 4096; j++) {
                    char v = b == null ? 0 : ids.toWorld(b[j]);
                    sec[j * 2] = (byte) (v >> 8);
                    sec[j * 2 + 1] = (byte) v;
                }
                out.write(sec);
            }
            // block entities (v2)
            List<derpcraft.world.be.BlockEntity> bes = new ArrayList<>(c.blockEntities.values());
            out.writeInt(bes.size());
            for (var be : bes) {
                out.writeInt(BlockEntityKey.key(be.x, be.y, be.z));
                out.writeUTF(be.type());
                Map<String, String> kv = new LinkedHashMap<>();
                be.save(kv);
                out.writeShort(kv.size());
                for (var e : kv.entrySet()) { out.writeUTF(e.getKey()); writeLong(out, e.getValue()); }
            }
            // soot (v3): sections that have some, then their 4096 face masks
            int sootMask = 0;
            for (int i = 0; i < 16; i++) {
                byte[] a = c.sections[i] == null ? null : c.sections[i].soot;
                if (a == null) continue;
                for (byte v : a) if (v != 0) { sootMask |= 1 << i; break; }
            }
            out.writeShort(sootMask);
            for (int i = 0; i < 16; i++) if ((sootMask & (1 << i)) != 0) out.write(c.sections[i].soot);
            out.flush();
            byte[] data = raw.toByteArray();
            Deflater d = new Deflater(1);
            d.setInput(data);
            d.finish();
            ByteArrayOutputStream z = new ByteArrayOutputStream(data.length / 4 + 64);
            byte[] tmp = new byte[16384];
            while (!d.finished()) z.write(tmp, 0, d.deflate(tmp));
            d.end();
            return z.toByteArray();
        } catch (IOException e) { throw new UncheckedIOException(e); }
    }

    /** Strings can be long (chest contents), so length is written as an int. */
    private static void writeLong(DataOutputStream out, String s) throws IOException {
        byte[] b = s.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        out.writeInt(b.length);
        out.write(b);
    }

    private static String readLong(DataInputStream in) throws IOException {
        byte[] b = new byte[in.readInt()];
        in.readFully(b);
        return new String(b, java.nio.charset.StandardCharsets.UTF_8);
    }

    private Chunk decode(int cx, int cz, byte[] data) throws DataFormatException, IOException {
        DataInputStream in = new DataInputStream(new InflaterInputStream(new ByteArrayInputStream(data), new Inflater(), 32768));
        int ver = in.readInt();
        if (ver < 1 || ver > VERSION) throw new DataFormatException("Unknown chunk version " + ver);
        Chunk c = new Chunk(cx, cz);
        in.readFully(c.temperature);
        in.readFully(c.humidity);
        int mask = in.readShort() & 0xffff;
        byte[] sec = new byte[8192];
        for (int i = 0; i < 16; i++) {
            if ((mask & (1 << i)) == 0) continue;
            Section s = c.ensureSection(i);
            in.readFully(sec);
            char[] b = new char[4096];
            for (int j = 0; j < 4096; j++) b[j] = ids.toReg(((sec[j * 2] & 255) << 8) | (sec[j * 2 + 1] & 255));
            s.blocks = b;
            s.recount();
        }
        if (ver >= 2) {
            int n = in.readInt();
            for (int k = 0; k < n; k++) {
                int key = in.readInt();
                String type = in.readUTF();
                int pairs = in.readShort();
                Map<String, String> kv = new HashMap<>();
                for (int p = 0; p < pairs; p++) kv.put(in.readUTF(), readLong(in));
                var be = derpcraft.world.be.BlockEntity.create(type);
                if (be == null) continue;
                be.x = (cx << 4) | (key & 15); be.z = (cz << 4) | ((key >> 4) & 15); be.y = key >> 8;
                be.load(kv);
                c.blockEntities.put(key, be);
            }
        }
        if (ver >= 3) {
            int sootMask = in.readShort() & 0xffff;
            for (int i = 0; i < 16; i++) {
                if ((sootMask & (1 << i)) == 0) continue;
                byte[] a = new byte[4096];
                in.readFully(a);
                c.ensureSection(i).soot = a;
            }
        }
        c.state = Chunk.STATE_GENERATED;
        return c;
    }
}
