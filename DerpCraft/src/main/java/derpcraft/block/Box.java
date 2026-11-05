package derpcraft.block;

/**
 * An axis-aligned model element in pixel units (0..16), like an "element" in a Minecraft 1.12 block model.
 * Each face has a texture name and optional explicit UV rectangle (null = derived from position, like vanilla).
 */
public final class Box {
    public final int x0, y0, z0, x1, y1, z1;
    public final String[] tex = new String[6];
    public final int[][] uv = new int[6][];
    /** Per face: whether it should be hidden when the neighbour in that direction is a full opaque block. */
    public final boolean[] cull = new boolean[6];
    public boolean tinted;
    /** Per face texture rotation in degrees (0, 90, 180, 270) and horizontal mirroring. */
    public final int[] rot = new int[6];
    public final boolean[] mirror = new boolean[6];

    public Box(int x0, int y0, int z0, int x1, int y1, int z1) {
        this.x0 = x0; this.y0 = y0; this.z0 = z0; this.x1 = x1; this.y1 = y1; this.z1 = z1;
        cull[Dir.DOWN] = y0 == 0; cull[Dir.UP] = y1 == 16;
        cull[Dir.NORTH] = z0 == 0; cull[Dir.SOUTH] = z1 == 16;
        cull[Dir.WEST] = x0 == 0; cull[Dir.EAST] = x1 == 16;
    }

    public Box all(String t) { java.util.Arrays.fill(tex, t); return this; }
    public Box face(int f, String t) { tex[f] = t; return this; }
    public Box face(int f, String t, int u0, int v0, int u1, int v1) { tex[f] = t; uv[f] = new int[]{u0, v0, u1, v1}; return this; }
    public Box tint() { tinted = true; return this; }
    public Box rotate(int f, int degrees) { rot[f] = ((degrees % 360) + 360) % 360; return this; }
    public Box mirror(int f) { mirror[f] = !mirror[f]; return this; }
    /** Only the listed faces keep a texture. */
    public Box only(int... faces) {
        String[] keep = new String[6];
        int[][] uvs = new int[6][];
        for (int f : faces) { keep[f] = tex[f]; uvs[f] = uv[f]; }
        for (int f = 0; f < 6; f++) { tex[f] = keep[f]; uv[f] = uvs[f]; }
        return this;
    }
    /** Disables face culling against neighbours (thin parts inside the block). */
    public Box noCull() { java.util.Arrays.fill(cull, false); culling = false; return this; }
    private boolean culling = true;

    /** Sets textures from a block's face textures. */
    public Box tex(Block b, int meta) { for (int f = 0; f < 6; f++) tex[f] = b.textureName(f, meta); return this; }

    /** Returns a copy rotated clockwise (seen from above) by quarter turns. */
    public Box rotateY(int q) {
        q &= 3;
        if (q == 0) return this;
        int ax0 = x0, az0 = z0, ax1 = x1, az1 = z1;
        for (int i = 0; i < q; i++) {
            int nx0 = 16 - az1, nx1 = 16 - az0, nz0 = ax0, nz1 = ax1;
            ax0 = nx0; ax1 = nx1; az0 = nz0; az1 = nz1;
        }
        Box b = new Box(ax0, y0, az0, ax1, y1, az1);
        for (int f = 0; f < 6; f++) {
            int nf = Dir.rotateY(f, q);
            b.tex[nf] = tex[f];
            b.uv[nf] = uv[f] != null && f >= 2 ? uv[f] : null;
            b.mirror[nf] = mirror[f];
            b.rot[nf] = rot[f];
        }
        b.tinted = tinted;
        if (rotUV) { b.rot[Dir.UP] = (rot[Dir.UP] + 90 * q) % 360; b.rot[Dir.DOWN] = (rot[Dir.DOWN] + 270 * q) % 360; }
        b.rotUV = rotUV;
        if (!culling) b.noCull();
        return b;
    }

    /** When true, rotateY also turns the top/bottom textures (for oriented models like beds and doors). */
    public boolean rotUV;

    /** Mirrors vertically (for upside-down stairs and top slabs). */
    public Box flipY() {
        Box b = new Box(x0, 16 - y1, z0, x1, 16 - y0, z1);
        for (int f = 0; f < 6; f++) { b.tex[f] = tex[f]; b.uv[f] = uv[f]; }
        b.tex[Dir.UP] = tex[Dir.DOWN];
        b.tex[Dir.DOWN] = tex[Dir.UP];
        b.tinted = tinted;
        b.rotUV = rotUV;
        if (!culling) b.noCull();
        return b;
    }
}
