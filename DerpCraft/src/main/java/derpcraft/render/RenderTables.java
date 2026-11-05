package derpcraft.render;

import derpcraft.block.Block;
import derpcraft.block.Blocks;
import derpcraft.block.Box;
import derpcraft.block.Dir;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Per-block-state data resolved against the current texture array, read by mesher threads. */
public final class RenderTables {
    public static final int T_NONE = 0, T_GRASS = 1, T_FOLIAGE = 2, T_FIXED = 3;

    public final int[] faceLayer = new int[65536 * 6];
    public final boolean[] faceRot = new boolean[65536 * 6];
    public final int[] overlay = new int[Blocks.MAX_BLOCKS];
    public final int[] tint = new int[Blocks.MAX_BLOCKS];       // type | idx << 2
    public final byte[] shape = new byte[Blocks.MAX_BLOCKS];
    /** Blocks meshed normally that also get drawn by the entity renderer (mod text blocks). */
    public final boolean[] textOverlay = new boolean[Blocks.MAX_BLOCKS];
    public final byte[] layer = new byte[Blocks.MAX_BLOCKS];
    public final boolean[] opaque = new boolean[Blocks.MAX_BLOCKS];
    public final boolean[] hideSame = new boolean[Blocks.MAX_BLOCKS];
    public final boolean[] connectSolid = new boolean[Blocks.MAX_BLOCKS];
    public final String[] connectGroup = new String[Blocks.MAX_BLOCKS];
    public final boolean[] neighborDependent = new boolean[Blocks.MAX_BLOCKS];
    /** Crossed plants drawn with a per-position height (tall grass). */
    public final boolean[] randomHeight = new boolean[Blocks.MAX_BLOCKS];
    /** Meshed AND listed for the entity renderer (fire), and blocks whose model 'conn' is a position hash (animation phase). */
    public final boolean[] entityToo = new boolean[Blocks.MAX_BLOCKS], phased = new boolean[Blocks.MAX_BLOCKS];
    /** Texture layers of the 6 soot overlays. */
    public final int[] sootLayers = new int[SOOT_VARIANTS];
    public static final int SOOT_VARIANTS = 6;

    private static boolean overrides(Block b, String method) {
        try {
            return b.getClass().getMethod(method, derpcraft.block.BlockGetter.class, int.class, int.class, int.class, int.class).getDeclaringClass() != Block.class;
        } catch (NoSuchMethodException e) { return false; }
    }
    public final float[] fixedTints = new float[16 * 3];
    private final BlockTextures tex;
    private final Map<Integer, Quad[]> boxCache = new ConcurrentHashMap<>();

    /** A resolved model face of a non-cube block (pixel units relative to the block). */
    public static final class Quad {
        public final int[] pos = new int[12];   // 4 corners xyz
        public final int[] uv = new int[8];
        public int layer, dir, tint;
        public boolean cull, boundary;
    }

    public RenderTables(BlockTextures tex) {
        this.tex = tex;
        Map<Integer, Integer> fixedIdx = new HashMap<>();
        for (Block b : Blocks.all()) {
            int id = b.id;
            for (int meta = 0; meta < 16; meta++)
                for (int f = 0; f < 6; f++) {
                    int si = ((id << 4) | meta) * 6 + f;
                    faceLayer[si] = tex.layer(b.textureName(f, meta));
                    faceRot[si] = b.uvRotated(f, meta);
                }
            overlay[id] = b.sideOverlay != null ? tex.layer(b.sideOverlay) : -1;
            int t = switch (b.tint) {
                case GRASS -> T_GRASS;
                case FOLIAGE -> T_FOLIAGE;
                case FIXED -> {
                    Integer idx = fixedIdx.get(b.fixedTint);
                    if (idx == null && fixedIdx.size() < 15) {
                        idx = fixedIdx.size() + 1;
                        fixedIdx.put(b.fixedTint, idx);
                        fixedTints[idx * 3] = ((b.fixedTint >> 16) & 255) / 255f;
                        fixedTints[idx * 3 + 1] = ((b.fixedTint >> 8) & 255) / 255f;
                        fixedTints[idx * 3 + 2] = (b.fixedTint & 255) / 255f;
                    }
                    yield idx == null ? T_NONE : (T_FIXED | idx << 2);
                }
                default -> T_NONE;
            };
            tint[id] = t;
            shape[id] = (byte) b.shape.ordinal();
            textOverlay[id] = b instanceof derpcraft.block.ModBlock mb && mb.textLines > 0;
            layer[id] = (byte) b.layer.ordinal();
            opaque[id] = b.isOpaqueCube();
            hideSame[id] = b.shape == Block.Shape.CUBE && (b.layer != Block.Layer.CUTOUT || b.name.contains("glass"))
                    || b.shape == Block.Shape.LIQUID;
            connectGroup[id] = b.connectGroup;
            connectSolid[id] = b.isOpaqueCube();
            neighborDependent[id] = b.connectGroup != null || overrides(b, "neighborInfo");
            randomHeight[id] = b.randomHeight;
            entityToo[id] = b.wantsEntityPass();
            phased[id] = b.positionPhased();
        }
        for (int i = 0; i < SOOT_VARIANTS; i++) sootLayers[i] = tex.layer("soot_" + i);
    }

    public static final int SH_CUBE = Block.Shape.CUBE.ordinal(), SH_CROSS = Block.Shape.CROSS.ordinal(),
            SH_LIQUID = Block.Shape.LIQUID.ordinal(), SH_AIR = Block.Shape.AIR.ordinal(),
            SH_ENTITY = Block.Shape.ENTITY.ordinal(), SH_TORCH = Block.Shape.TORCH.ordinal();

    /** True when the face of 'self' is hidden by neighbour state 'nb'. */
    public boolean hidden(int selfId, int nbId) {
        if (opaque[nbId]) return true;
        if (nbId == selfId && hideSame[selfId]) return true;
        return shape[selfId] == SH_LIQUID && shape[nbId] == SH_LIQUID;
    }

    // ---------------------------------------------------------------- box models
    static final int[] AX_N = {1, 1, 2, 2, 0, 0}, AX_U = {0, 2, 1, 0, 2, 1}, AX_V = {2, 0, 0, 1, 1, 2};
    static final boolean[] POS = {false, true, false, true, false, true};

    /** Texture u,v (pixels) for a point on a face, following Minecraft's automatic UV mapping. */
    static int texU(int face, int x, int y, int z) {
        return switch (face) {
            case Dir.DOWN, Dir.UP, Dir.SOUTH -> x;
            case Dir.NORTH -> 16 - x;
            case Dir.WEST -> z;
            default -> 16 - z;
        };
    }

    static int texV(int face, int x, int y, int z) {
        return switch (face) {
            case Dir.UP -> z;
            case Dir.DOWN -> 16 - z;
            default -> 16 - y;
        };
    }

    public Quad[] boxQuads(int state, int conn) {
        int key = (state << 4) | conn;
        Quad[] q = boxCache.get(key);
        if (q != null) return q;
        Block b = Blocks.byId(state >> 4);
        List<Quad> out = new ArrayList<>();
        int meta = state & 15;
        for (Box box : b.boxes(meta, conn)) {
            int[] mn = {box.x0, box.y0, box.z0}, mx = {box.x1, box.y1, box.z1};
            for (int f = 0; f < 6; f++) {
                if (box.tex[f] == null) continue;
                int na = AX_N[f], ua = AX_U[f], va = AX_V[f];
                if (mn[ua] == mx[ua] || mn[va] == mx[va]) continue;
                Quad qd = new Quad();
                qd.dir = f;
                qd.layer = tex.layer(box.tex[f]);
                qd.cull = box.cull[f];
                qd.boundary = POS[f] ? mx[na] == 16 : mn[na] == 0;
                qd.tint = box.tinted || b.tint != Block.Tint.NONE ? tint[b.id] : 0;
                int plane = POS[f] ? mx[na] : mn[na];
                int[][] cu = {{0, 0}, {1, 0}, {1, 1}, {0, 1}};
                int umin = Integer.MAX_VALUE, umax = Integer.MIN_VALUE, vmin = Integer.MAX_VALUE, vmax = Integer.MIN_VALUE;
                int[] tu = new int[4], tv = new int[4];
                for (int k = 0; k < 4; k++) {
                    int[] p = new int[3];
                    p[na] = plane;
                    p[ua] = cu[k][0] == 0 ? mn[ua] : mx[ua];
                    p[va] = cu[k][1] == 0 ? mn[va] : mx[va];
                    qd.pos[k * 3] = p[0]; qd.pos[k * 3 + 1] = p[1]; qd.pos[k * 3 + 2] = p[2];
                    tu[k] = texU(f, p[0], p[1], p[2]);
                    tv[k] = texV(f, p[0], p[1], p[2]);
                    umin = Math.min(umin, tu[k]); umax = Math.max(umax, tu[k]);
                    vmin = Math.min(vmin, tv[k]); vmax = Math.max(vmax, tv[k]);
                }
                int[] r = box.uv[f];
                for (int k = 0; k < 4; k++) {
                    int u = tu[k], v = tv[k];
                    if (r != null) {
                        u = umax == umin ? r[0] : r[0] + (u - umin) * (r[2] - r[0]) / (umax - umin);
                        v = vmax == vmin ? r[1] : r[1] + (v - vmin) * (r[3] - r[1]) / (vmax - vmin);
                    }
                    if (box.mirror[f]) u = 16 - u;
                    int ru = u, rv = v;
                    switch (box.rot[f]) {
                        case 90 -> { ru = v; rv = 16 - u; }
                        case 180 -> { ru = 16 - u; rv = 16 - v; }
                        case 270 -> { ru = 16 - v; rv = u; }
                        default -> {}
                    }
                    qd.uv[k * 2] = Math.max(0, ru);
                    qd.uv[k * 2 + 1] = Math.max(0, rv);
                }
                out.add(qd);
            }
        }
        q = out.toArray(new Quad[0]);
        boxCache.put(key, q);
        return q;
    }
}
