package derpcraft.render;

import derpcraft.block.Block;
import derpcraft.block.Blocks;
import derpcraft.block.Dir;
import derpcraft.world.Chunk;
import derpcraft.world.Section;
import derpcraft.world.World;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

import static org.lwjgl.opengl.GL45C.*;

/**
 * GPU copy of the world around the camera for voxel ray tracing: a 256 x 256 x 256 RG16UI texture with
 * (block id, packed light) per block, addressed toroidally (x & 255, y, z & 255) so moving only uploads new columns.
 * A small table texture holds per-block average colour, ray flags and emission.
 */
public final class VoxelVolume {
    public static final int SIZE = 256, CHUNKS = SIZE / 16;
    /** Flags in the block table alpha: 1 opaque, 2 leaves (partial), 4 coloured filter (stained glass), 8 water, 16 emissive. */
    public static final int F_OPAQUE = 1, F_LEAVES = 2, F_FILTER = 4, F_WATER = 8, F_EMISSIVE = 16;
    private int tex, tableTex;
    private final long[] slot = new long[CHUNKS * CHUNKS];
    private final boolean[] dirty = new boolean[CHUNKS * CHUNKS];
    private final ByteBuffer column = ByteBuffer.allocateDirect(16 * 256 * 16 * 4).order(ByteOrder.nativeOrder());
    private int tableVersion = -1;
    /** World block coordinates of the minimum corner of the valid window (x, z). */
    public int minX, minZ;
    public int uploadsLastFrame;

    public VoxelVolume() { Arrays.fill(slot, Long.MIN_VALUE); }

    public int texture() { return tex; }

    public int table() { return tableTex; }

    private void create() {
        tex = glGenTextures();
        glBindTexture(GL_TEXTURE_3D, tex);
        glTexParameteri(GL_TEXTURE_3D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_3D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        glTexImage3D(GL_TEXTURE_3D, 0, GL_RG16UI, SIZE, SIZE, SIZE, 0, GL_RG_INTEGER, GL_UNSIGNED_SHORT, (ByteBuffer) null);
        tableTex = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, tableTex);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
    }

    /** Rebuilds the block table (colours change with resource packs). */
    private void buildTable(Assets a) {
        int n = Blocks.MAX_BLOCKS;
        ByteBuffer b = ByteBuffer.allocateDirect(n * 2 * 4).order(ByteOrder.nativeOrder());
        byte[] row0 = new byte[n * 4], row1 = new byte[n * 4];
        RenderTables t = a.tables;
        for (Block bl : Blocks.all()) {
            int id = bl.id;
            if (id < 0 || id >= n || bl.isAir()) continue;
            int layer = t.faceLayer[(id << 4) * 6 + Dir.UP];
            int avg = layer < a.blocks.average.length ? a.blocks.average[layer] : 0xFF808080;
            int side = t.faceLayer[(id << 4) * 6 + Dir.NORTH];
            int avgS = side < a.blocks.average.length ? a.blocks.average[side] : avg;
            int r = (((avg >> 16) & 255) + ((avgS >> 16) & 255)) / 2, g = (((avg >> 8) & 255) + ((avgS >> 8) & 255)) / 2, bb = ((avg & 255) + (avgS & 255)) / 2;
            int tint = switch (bl.tint) { case GRASS -> a.plainsGrass; case FOLIAGE -> a.plainsFoliage; case FIXED -> bl.fixedTint; default -> 0xFFFFFF; };
            if (bl.tint != Block.Tint.NONE) {
                if (bl.sideOverlay != null) { // grass block: tinted top, dirt sides -> mostly the top matters
                    r = ((avg >> 16) & 255) * ((tint >> 16) & 255) / 255; g = ((avg >> 8) & 255) * ((tint >> 8) & 255) / 255; bb = (avg & 255) * (tint & 255) / 255;
                } else { r = r * ((tint >> 16) & 255) / 255; g = g * ((tint >> 8) & 255) / 255; bb = bb * (tint & 255) / 255; }
            }
            int flags = 0;
            boolean cube = bl.shape == Block.Shape.CUBE;
            if (bl == Blocks.WATER) flags |= F_WATER;
            else if (cube && bl.layer == Block.Layer.SOLID) flags |= F_OPAQUE;
            else if (cube && bl.name.contains("leaves")) flags |= F_LEAVES;
            else if (cube && bl.name.contains("stained_glass")) flags |= F_FILTER;
            else if (bl.shape == Block.Shape.SLAB || bl.shape == Block.Shape.STAIRS || bl.shape == Block.Shape.CACTUS
                    || bl.shape == Block.Shape.ENTITY && bl.name.contains("chest") || bl == Blocks.LAVA) flags |= F_OPAQUE;
            int e = Blocks.EMISSION[id];
            if (e > 0) flags |= F_EMISSIVE;
            row0[id * 4] = (byte) r; row0[id * 4 + 1] = (byte) g; row0[id * 4 + 2] = (byte) bb; row0[id * 4 + 3] = (byte) flags;
            for (int ch = 0; ch < 3; ch++) row1[id * 4 + ch] = (byte) Math.min(255, Blocks.EMISSION_RGB[ch][id] * 17);
            row1[id * 4 + 3] = (byte) (e * 17);
        }
        b.put(row0).put(row1).flip();
        glBindTexture(GL_TEXTURE_2D, tableTex);
        glPixelStorei(GL_UNPACK_ALIGNMENT, 4);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, n, 2, 0, GL_RGBA, GL_UNSIGNED_BYTE, b);
    }

    /** Marks a section's column for re-upload (called when the section got a new mesh). */
    public void sectionChanged(int cx, int cz) {
        int s = (cx & (CHUNKS - 1)) + (cz & (CHUNKS - 1)) * CHUNKS;
        if (slot[s] == Chunk.key(cx, cz)) dirty[s] = true;
    }

    /** Uploads missing/changed columns around the camera, nearest first, within a per-frame budget. */
    public void update(World w, Assets a, double camX, double camZ, int budget) {
        if (tex == 0) create();
        if (tableVersion != a.version) { buildTable(a); tableVersion = a.version; }
        int ccx = (int) Math.floor(camX) >> 4, ccz = (int) Math.floor(camZ) >> 4;
        minX = (ccx - CHUNKS / 2) * 16;
        minZ = (ccz - CHUNKS / 2) * 16;
        uploadsLastFrame = 0;
        int half = CHUNKS / 2;
        for (int ring = 0; ring <= half && budget > 0; ring++)
            for (int dx = -ring; dx <= ring && budget > 0; dx++)
                for (int dz = -ring; dz <= ring && budget > 0; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != ring) continue;
                    int cx = ccx + dx, cz = ccz + dz;
                    if (cx < ccx - half || cx >= ccx + half || cz < ccz - half || cz >= ccz + half) continue;
                    int s = (cx & (CHUNKS - 1)) + (cz & (CHUNKS - 1)) * CHUNKS;
                    long key = Chunk.key(cx, cz);
                    if (slot[s] == key && !dirty[s]) continue;
                    Chunk c = w.getChunk(cx, cz);
                    if (c == null || c.state < Chunk.STATE_LIT) {
                        if (slot[s] != key && slot[s] != Long.MIN_VALUE + 1) { upload(null, cx, cz); slot[s] = Long.MIN_VALUE + 1; budget--; }
                        continue;
                    }
                    upload(c, cx, cz);
                    slot[s] = key;
                    dirty[s] = false;
                    budget--;
                    uploadsLastFrame++;
                }
    }

    private void upload(Chunk c, int cx, int cz) {
        column.clear();
        for (int z = 0; z < 16; z++)
            for (int y = 0; y < 256; y++) {
                Section s = c == null ? null : c.sections[y >> 4];
                for (int x = 0; x < 16; x++) {
                    if (s == null) {
                        column.putShort((short) 0).putShort((short) (c == null ? 0 : 15));
                        continue;
                    }
                    int i = Section.index(x, y & 15, z);
                    int st = s.get(i);
                    int light = s.getSky(i) | s.getLight(0, i) << 4 | s.getLight(1, i) << 8 | s.getLight(2, i) << 12;
                    column.putShort((short) (st >> 4)).putShort((short) light);
                }
            }
        column.flip();
        glBindTexture(GL_TEXTURE_3D, tex);
        glPixelStorei(GL_UNPACK_ALIGNMENT, 4);
        glTexSubImage3D(GL_TEXTURE_3D, 0, (cx * 16) & (SIZE - 1), 0, (cz * 16) & (SIZE - 1), 16, 256, 16, GL_RG_INTEGER, GL_UNSIGNED_SHORT, column);
    }

    public void delete() {
        if (tex != 0) { glDeleteTextures(tex); glDeleteTextures(tableTex); tex = 0; }
    }
}
