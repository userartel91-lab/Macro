package derpcraft.block;

import derpcraft.game.Session;
import derpcraft.item.Item;
import derpcraft.item.ItemStack;
import derpcraft.world.World;
import derpcraft.world.be.BlockEntity;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * A block type. Create blocks with {@link derpcraft.api.BlockBuilder} (from mods) or subclass this for custom behaviour.
 * Block states are stored as (id << 4 | meta), meta is 0..15 like in Minecraft 1.12.
 */
public class Block {
    /** CUSTOM = geometry from an overridden {@link #boxes}; ENTITY = drawn by the block entity renderer (chests, signs, beds). */
    public enum Shape { CUBE, CROSS, SLAB, STAIRS, TORCH, LIQUID, PANE, FENCE, WALL, CARPET, SNOW_LAYER, CACTUS, CUSTOM, ENTITY, AIR }
    public enum Layer { SOLID, CUTOUT, TRANSLUCENT }
    public enum Tint { NONE, GRASS, FOLIAGE, FIXED }
    /** Placement rule for meta. */
    public enum Placement { NONE, AXIS, SLAB, STAIRS, TORCH, FACING }

    public int id = -1;
    public final String name;
    public String displayName;
    public Shape shape = Shape.CUBE;
    public Layer layer = Layer.SOLID;
    public Tint tint = Tint.NONE;
    public int fixedTint = 0xFFFFFF;
    public Placement placement = Placement.NONE;
    /** Texture names per face (down, up, north, south, west, east). */
    public final String[] textures = new String[6];
    /** Optional tinted overlay drawn on the 4 side faces (grass block). */
    public String sideOverlay;
    public int lightEmission;
    /** Colour of emitted light (RGB), -1 = automatic (white unless known coloured source). */
    public int lightColor = -1;
    /** Colour filter applied to light passing through (stained glass), -1 = none. */
    public int lightFilter = -1;
    public int lightOpacity = 15;
    public boolean solid = true;
    public boolean replaceable;
    public boolean inInventory = true;
    /** Blocks of the same "connectGroup" connect as fences/panes/walls. */
    public String connectGroup;
    public String category = "Building";

    // ---------- survival properties (Minecraft 1.12 values) ----------
    /** Seconds-ish break resistance; -1 = unbreakable. */
    public float hardness = 1.5f;
    public Item.ToolType effectiveTool;
    /** Drops nothing unless mined with the right tool (stone needs a pickaxe). */
    public boolean requiresTool;
    public int harvestLevel;
    public boolean climbable;
    /** Item dropped instead of this block's own item (e.g. stone -> cobblestone). "" = nothing. */
    public String dropItem;
    public int dropMin = 1, dropMax = 1;
    public float dropChance = 1;
    /** Drops nothing without silk touch (glass, ice). */
    public boolean fragile;
    /**
     * Bullets (gun/Ballistics): damage this block takes before shots break it. -1 = automatic: cameras (any block whose
     * block entity is a camera, or named "...camera...") break at the first hit; other blocks without a block entity by
     * hardness ({@link derpcraft.gun.Ballistics#blockHealth}); 0 = bullets never break it (only chips and cracks).
     */
    public float bulletHealth = -1;
    /** Breaks when the block it stands on is removed (plants, torches, doors...). */
    public boolean needsSupport;
    /** Crossed plants (tall grass) are drawn with a different height in every block, see {@link #plantHeightPx}. */
    public boolean randomHeight;
    /** The item form of this block (set by Items). */
    public Item item;
    /** Creative tab id. */
    public String tab = "building";

    public Block(String name) {
        this.name = name;
        this.displayName = prettify(name);
    }

    public static String prettify(String n) {
        if (n.indexOf(':') >= 0) n = n.substring(n.indexOf(':') + 1);
        StringBuilder sb = new StringBuilder();
        for (String p : n.split("_")) {
            if (p.isEmpty()) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(Character.toUpperCase(p.charAt(0))).append(p.substring(1));
        }
        return sb.toString();
    }

    // ---------- properties used by the engine ----------

    /** Full, opaque cube: hides neighbour faces and casts ambient occlusion. */
    public boolean isOpaqueCube() { return shape == Shape.CUBE && layer == Layer.SOLID; }

    public boolean isAir() { return shape == Shape.AIR; }

    /** Texture used for a face, given the meta value. Override for custom behaviour. */
    public String textureName(int face, int meta) {
        if (placement == Placement.AXIS) {
            int axis = meta & 3; // 0=Y, 1=X, 2=Z
            if (axis == 1) {
                if (face == Dir.WEST || face == Dir.EAST) return textures[Dir.UP];
                return textures[Dir.NORTH];
            }
            if (axis == 2) {
                if (face == Dir.NORTH || face == Dir.SOUTH) return textures[Dir.UP];
                return textures[Dir.NORTH];
            }
        }
        if (placement == Placement.FACING && face >= 2) {
            // texture of NORTH is the "front"; rotate so that front points to meta direction
            int front = Dir.HORIZONTAL[meta & 3];
            if (face == front) return textures[Dir.NORTH];
            return textures[Dir.EAST];
        }
        return textures[face];
    }

    /** True when the texture of this face should be rotated 90 degrees (sideways logs). */
    public boolean uvRotated(int face, int meta) {
        if (placement != Placement.AXIS) return false;
        int axis = meta & 3;
        if (axis == 1) return face == Dir.UP || face == Dir.DOWN || face == Dir.NORTH || face == Dir.SOUTH;
        if (axis == 2) return face == Dir.WEST || face == Dir.EAST;
        return false;
    }

    /**
     * Geometry for non-cube shapes. 'conn' bit i (0..3 = N,S,W,E) is set when the block connects to that side
     * (fences, panes, walls).
     */
    public List<Box> boxes(int meta, int conn) {
        List<Box> list = new ArrayList<>(4);
        switch (shape) {
            case CUBE -> list.add(new Box(0, 0, 0, 16, 16, 16).tex(this, meta));
            case SLAB -> {
                Box b = new Box(0, 0, 0, 16, 8, 16).tex(this, meta);
                list.add((meta & 1) == 1 ? b.flipY() : b);
            }
            case STAIRS -> {
                int q = meta & 3;
                boolean upside = (meta & 4) != 0;
                Box base = new Box(0, 0, 0, 16, 8, 16).tex(this, 0);
                Box step = new Box(0, 8, 8, 16, 16, 16).tex(this, 0).rotateY(q);
                if (upside) { base = base.flipY(); step = step.flipY(); }
                list.add(base); list.add(step);
            }
            case TORCH -> {
                String t = textures[Dir.NORTH];
                if (meta >= 1 && meta <= 8) {
                    // wall torch (1-4 leaning 22.5 degrees, 5-8 sticking straight out): this is only its hit box, the tilted model is
                    // drawn by the entity renderer (chunk vertices are whole 1/16 steps)
                    int f = Dir.HORIZONTAL[(meta - 1) & 3];
                    boolean straight = meta >= 5;
                    int depth = straight ? 10 : 5, y0 = straight ? 5 : 3, y1 = straight ? 11 : 13;
                    int a0 = Dir.DX[f] + Dir.DZ[f] > 0 ? 16 - depth : 0;
                    list.add((Dir.DX[f] != 0 ? new Box(a0, y0, 6, a0 + depth, y1, 10) : new Box(6, y0, a0, 10, y1, a0 + depth)).all(t));
                } else {
                    Box b = new Box(7, 0, 7, 9, 10, 9).all(t);
                    b.face(Dir.UP, t, 7, 6, 9, 8).face(Dir.DOWN, t, 7, 13, 9, 15);
                    for (int f = 2; f < 6; f++) b.face(f, t, 7, 6, 9, 16);
                    java.util.Arrays.fill(b.cull, false);
                    list.add(b);
                }
            }
            case CARPET -> list.add(new Box(0, 0, 0, 16, 1, 16).tex(this, meta));
            case CACTUS -> {
                // like Minecraft's cactus.json: full-size top/bottom, sides inset by one pixel
                Box tb = new Box(0, 0, 0, 16, 16, 16);
                tb.face(Dir.UP, textures[Dir.UP]).face(Dir.DOWN, textures[Dir.DOWN]);
                Box ns = new Box(0, 0, 1, 16, 16, 15);
                ns.face(Dir.NORTH, textures[Dir.NORTH]).face(Dir.SOUTH, textures[Dir.SOUTH]);
                Box we = new Box(1, 0, 0, 15, 16, 16);
                we.face(Dir.WEST, textures[Dir.WEST]).face(Dir.EAST, textures[Dir.EAST]);
                list.add(tb); list.add(ns); list.add(we);
            }
            case SNOW_LAYER -> list.add(new Box(0, 0, 0, 16, 2 * ((meta & 7) + 1), 16).tex(this, meta));
            case FENCE -> {
                list.add(new Box(6, 0, 6, 10, 16, 10).all(textures[0]));
                addArms(list, conn, 7, 9, new int[][]{{12, 15}, {6, 9}});
            }
            case WALL -> {
                boolean straightNS = conn == 0b0011, straightWE = conn == 0b1100;
                if (straightNS) list.add(new Box(5, 0, 0, 11, 13, 16).all(textures[0]));
                else if (straightWE) list.add(new Box(0, 0, 5, 16, 13, 11).all(textures[0]));
                else {
                    list.add(new Box(4, 0, 4, 12, 16, 12).all(textures[0]));
                    addArms(list, conn, 5, 11, new int[][]{{0, 13}});
                }
            }
            case PANE -> {
                String edge = textures[Dir.UP] != null ? textures[Dir.UP] : textures[0];
                boolean n = (conn & 1) != 0, s = (conn & 2) != 0, w = (conn & 4) != 0, e = (conn & 8) != 0;
                if (conn == 0) { n = s = w = e = true; }
                if (n || s) list.add(paneBox(7, n ? 0 : 7, 9, s ? 16 : 9, true, edge));
                if (w || e) list.add(paneBox(w ? 0 : 7, 7, e ? 16 : 9, 9, false, edge));
                if (!(n || s) && !(w || e)) list.add(paneBox(7, 7, 9, 9, true, edge));
            }
            default -> {}
        }
        return list;
    }

    private Box paneBox(int x0, int z0, int x1, int z1, boolean ns, String edge) {
        Box b = new Box(x0, 0, z0, x1, 16, z1).all(textures[0]);
        b.face(Dir.UP, edge).face(Dir.DOWN, edge);
        if (ns) { b.face(Dir.NORTH, edge); b.face(Dir.SOUTH, edge); } else { b.face(Dir.WEST, edge); b.face(Dir.EAST, edge); }
        return b;
    }

    private void addArms(List<Box> list, int conn, int lo, int hi, int[][] ys) {
        String t = textures[0];
        for (int[] y : ys) {
            if ((conn & 1) != 0) list.add(new Box(lo, y[0], 0, hi, y[1], 6).all(t));
            if ((conn & 2) != 0) list.add(new Box(lo, y[0], 10, hi, y[1], 16).all(t));
            if ((conn & 4) != 0) list.add(new Box(0, y[0], lo, 6, y[1], hi).all(t));
            if ((conn & 8) != 0) list.add(new Box(10, y[0], lo, 16, y[1], hi).all(t));
        }
    }

    // ---------- behaviour hooks ----------

    /** Right click on the block. Return true if handled (opening a GUI, toggling a door...). */
    public boolean onUse(Session s, int x, int y, int z, int meta) { return false; }

    /** Called after the block was placed by a player (e.g. place the upper half of a door). */
    public void onPlaced(World w, int x, int y, int z, int meta) {}

    /** Called after the block was removed by a player (e.g. remove the other half of a bed). */
    public void onRemoved(World w, int x, int y, int z, int meta) {}

    /** Whether the block can be placed here (e.g. crops need farmland). */
    public boolean canPlaceAt(BlockGetter w, int x, int y, int z, int meta) { return true; }

    public boolean ticksRandomly() { return false; }

    /** Tick requested with {@link World#scheduleTick} (buttons popping back out...). */
    public void scheduledTick(World w, int x, int y, int z, int meta) {}

    /** Random tick (crops grow, saplings turn into trees, grass spreads). */
    public void randomTick(World w, int x, int y, int z, int meta, Random r) {}

    /** Block entity (chest contents, furnace state, sign text) or null. */
    public BlockEntity createBlockEntity() { return null; }

    private String beTypeCache;
    private boolean beTypeKnown;

    /** Type id of this block's block entity, or null. Cached: the world compares it every tick without allocating. */
    public String blockEntityType() {
        if (!beTypeKnown) {
            BlockEntity p = createBlockEntity();
            beTypeCache = p == null ? null : p.type();
            beTypeKnown = true;
        }
        return beTypeCache;
    }

    /** Drops when broken, with access to the block entity (portable storage keeps its contents). */
    public List<ItemStack> dropsWithEntity(World w, int x, int y, int z, int meta, Random r, BlockEntity be) { return drops(meta, r); }

    /** Called after a player placed this block from an item stack (restores data carried by the item). */
    public void placedFromItem(World w, int x, int y, int z, ItemStack stack) {}

    /** Items dropped when mined in survival with a suitable tool. */
    public List<ItemStack> drops(int meta, Random r) {
        List<ItemStack> out = new ArrayList<>(1);
        if (fragile) return out;
        if (r.nextFloat() >= dropChance) return out;
        Item it = dropItem != null ? (dropItem.isEmpty() ? null : derpcraft.item.Items.byName(dropItem)) : item;
        if (it == null) return out;
        int n = dropMin + (dropMax > dropMin ? r.nextInt(dropMax - dropMin + 1) : 0);
        if (n > 0) out.add(new ItemStack(it, n));
        return out;
    }

    /**
     * Extra render/collision info from neighbours (4 bits): fence/pane/wall connections N,S,W,E by default.
     * Doors use it for the other half's meta.
     */
    /** Further texture names this block needs in the texture array (e.g. phase copies of an animated texture). */
    public java.util.List<String> extraTextures() { return java.util.List.of(); }

    /** True for blocks that are both part of the chunk mesh and listed for the entity renderer (fire: particles). */
    public boolean wantsEntityPass() { return shape == Shape.TORCH; }

    /** True for blocks whose models depend on the position (phase of an animated texture): see {@link #boxes} 'conn' = position hash. */
    public boolean positionPhased() { return false; }

    private static final int[] CONNECT_DIRS = {Dir.NORTH, Dir.SOUTH, Dir.WEST, Dir.EAST};

    public int neighborInfo(BlockGetter g, int x, int y, int z, int meta) {
        if (connectGroup == null) return 0;
        int c = 0;
        for (int k = 0; k < 4; k++) {
            Block n = g.getBlock(x + Dir.DX[CONNECT_DIRS[k]], y, z + Dir.DZ[CONNECT_DIRS[k]]);
            if (n.isOpaqueCube() || connectGroup.equals(n.connectGroup) || (connectGroup.equals("fence") && n.connectGroup != null && n.connectGroup.equals("gate"))) c |= 1 << k;
        }
        return c;
    }

    /** Collision boxes in block units (x0,y0,z0,x1,y1,z1 per box). Empty for non-solid blocks. */
    public List<float[]> collisionBoxes(int meta, int conn) {
        List<float[]> out = new ArrayList<>();
        if (!solid) return out;
        if (shape == Shape.CUBE) { out.add(new float[]{0, 0, 0, 1, 1, 1}); return out; }
        for (Box b : boxes(meta, conn)) {
            float y1 = b.y1 / 16f;
            if (shape == Shape.FENCE || shape == Shape.WALL) y1 = 1.5f;
            out.add(new float[]{b.x0 / 16f, b.y0 / 16f, b.z0 / 16f, b.x1 / 16f, y1, b.z1 / 16f});
        }
        return out;
    }

    // ---------- cached shapes (collision every tick for every body, rays every frame) ----------
    /**
     * {@link #collisionBoxes} / {@link #selectionBoxes(int, int)} depend only on (meta, conn), but building them makes
     * {@link Box} objects with six texture names each; physics asked for them for every block around every body, several
     * times per tick. The results are kept per (meta, conn) here; the lists and arrays are shared, never modify them.
     * Index meta * 16 + conn; conn outside 0..15 is not cached.
     */
    @SuppressWarnings("unchecked")
    private final List<float[]>[] collisionCache = new List[256], selectionCache = new List[256];

    /** Cached {@link #collisionBoxes(int, int)} (shared, read only). */
    public final List<float[]> collisionShape(int meta, int conn) {
        if (shape == Shape.CUBE && solid && collisionIsCube()) return FULL_CUBE;
        if ((conn & ~15) != 0) return collisionBoxes(meta, conn);
        int i = (meta & 15) << 4 | conn;
        List<float[]> l = collisionCache[i];
        if (l == null) collisionCache[i] = l = frozen(collisionBoxes(meta & 15, conn));
        return l;
    }

    /** Cached {@link #selectionBoxes(int, int)} (shared, read only). */
    public final List<float[]> selectionShape(int meta, int conn) {
        if ((conn & ~15) != 0) return selectionBoxes(meta, conn);
        int i = (meta & 15) << 4 | conn;
        List<float[]> l = selectionCache[i];
        if (l == null) selectionCache[i] = l = frozen(selectionBoxes(meta & 15, conn));
        return l;
    }

    /** Forgets the cached shapes (a mod changed a block's model at run time). */
    public void shapesChanged() {
        java.util.Arrays.fill(collisionCache, null);
        java.util.Arrays.fill(selectionCache, null);
        cubeKnown = false;
        reachVersion = -1;
    }

    private static final List<float[]> FULL_CUBE = List.of(new float[]{0, 0, 0, 1, 1, 1});
    private boolean cubeKnown, cubeCollision;

    /** True when this class keeps the plain full-cube collision of {@link Block} (most blocks: no list at all). */
    private boolean collisionIsCube() {
        if (!cubeKnown) {
            boolean own;
            try { own = getClass().getMethod("collisionBoxes", int.class, int.class).getDeclaringClass() == Block.class; }
            catch (NoSuchMethodException e) { own = false; }
            cubeCollision = own;
            cubeKnown = true;
        }
        return cubeCollision;
    }

    private static List<float[]> frozen(List<float[]> l) {
        if (l == null || l.isEmpty()) return List.of();
        return l.size() == 1 ? List.of(l.get(0)) : List.copyOf(l);
    }

    /**
     * How far collision boxes of any registered block reach out of their own cell: {sideways (x / z), above the top,
     * below the bottom}, in blocks. Fences and walls are 1.5 high (0.5 above); mods may make more. Collision scans
     * look only this far around a box instead of one whole block in every direction.
     */
    public static double[] collisionReach() {
        double[] r = reach;
        if (r != null && reachVersion == Blocks.count()) return r;
        double side = 0, up = 0, down = 0;
        for (Block b : Blocks.all()) {
            if (!b.solid || (b.shape == Shape.CUBE && b.collisionIsCube())) continue;
            for (int meta = 0; meta < 16; meta++)
                for (int conn = 0; conn < 16; conn++) {
                    List<float[]> l;
                    try { l = b.collisionShape(meta, conn); } catch (RuntimeException e) { continue; }
                    for (float[] c : l) {
                        side = Math.max(side, Math.max(Math.max(-c[0], c[3] - 1), Math.max(-c[2], c[5] - 1)));
                        up = Math.max(up, c[4] - 1);
                        down = Math.max(down, -c[1]);
                    }
                }
        }
        reach = r = new double[]{side, up, down};
        reachVersion = Blocks.count();
        return r;
    }
    private static volatile double[] reach;
    private static volatile int reachVersion = -1;

    public static final int PLANT_MIN_PX = 6;

    /**
     * Height in 1/16 blocks (PLANT_MIN_PX..16) of a {@link #randomHeight} plant standing at x,y,z. Depends only on the
     * position, so grass has the same height after generation, placing, bone meal, /fill, mods and reloading a world.
     */
    public static int plantHeightPx(int x, int y, int z) {
        int h = x * 73856093 ^ y * 19349663 ^ z * 83492791 ^ 0x2F6B1C35;
        h ^= h >>> 15; h *= 0x2c1b3c6d; h ^= h >>> 12; h *= 0x297a2d39; h ^= h >>> 15;
        return PLANT_MIN_PX + Math.floorMod(h, 16 - PLANT_MIN_PX + 1);
    }

    /** Selection boxes of the block at a position: like {@link #selectionBoxes(int, int)}, but random height plants get their own height. */
    public List<float[]> selectionBoxes(int meta, int conn, int x, int y, int z) {
        List<float[]> out = selectionShape(meta, conn);
        if (!randomHeight) return out;
        float k = plantHeightPx(x, y, z) / 16f;
        List<float[]> scaled = new ArrayList<>(out.size());
        for (float[] b : out) scaled.add(new float[]{b[0], b[1], b[2], b[3], b[4] * k, b[5]});
        return scaled;
    }

    /** Selection (outline / raycast) boxes in block units. */
    public List<float[]> selectionBoxes(int meta, int conn) {
        List<float[]> out = new ArrayList<>();
        if (shape == Shape.CUBE || shape == Shape.LIQUID) { out.add(new float[]{0, 0, 0, 1, 1, 1}); return out; }
        if (shape == Shape.CROSS) { out.add(new float[]{0.1f, 0, 0.1f, 0.9f, 0.8f, 0.9f}); return out; }
        for (Box b : boxes(meta, conn)) out.add(new float[]{b.x0 / 16f, b.y0 / 16f, b.z0 / 16f, b.x1 / 16f, b.y1 / 16f, b.z1 / 16f});
        return out;
    }

    /**
     * Meta value when a player places this block.
     * @param face face of the block that was clicked
     * @param hitY fractional y of the hit point inside the clicked block (0..1)
     * @param yaw player yaw in degrees
     */
    public int placementMeta(int face, float hitY, float yaw) {
        int quarter = Math.floorMod(Math.round(yaw / 90f), 4); // 0 = looking south, 1 = west, 2 = north, 3 = east
        return switch (placement) {
            case AXIS -> face < 2 ? 0 : (face >= 4 ? 1 : 2);
            case SLAB -> face == Dir.DOWN || (face >= 2 && hitY > 0.5f) ? 1 : 0;
            case STAIRS -> {
                // the high part of the stair is on the far side (direction the player looks)
                int lookDir = Dir.HORIZONTAL[quarter];
                int q = switch (lookDir) { case Dir.SOUTH -> 0; case Dir.WEST -> 1; case Dir.NORTH -> 2; default -> 3; };
                boolean upside = face == Dir.DOWN || (face >= 2 && hitY > 0.5f);
                yield q | (upside ? 4 : 0);
            }
            case TORCH -> {
                if (face >= 2) for (int i = 0; i < 4; i++) if (Dir.HORIZONTAL[i] == Dir.OPPOSITE[face]) yield i + 1;
                yield 0;
            }
            case FACING -> (quarter + 2) & 3; // front faces the player
            default -> 0;
        };
    }

    @Override public String toString() { return name; }
}
