package derpcraft.camera;

import derpcraft.block.Block;
import derpcraft.block.BlockGetter;
import derpcraft.block.Blocks;
import derpcraft.block.Box;
import derpcraft.block.Dir;
import derpcraft.game.Session;
import derpcraft.world.World;
import derpcraft.world.be.BlockEntity;

import java.util.List;

/**
 * Screen canvas: hangs on a face of any block, plain or 3D (meta = direction from the canvas to that block). The
 * picture is drawn by the entity renderer from the connected camera; size, position, turn and crop are set in its menu.
 */
public final class CanvasBlock extends Block {
    public static CanvasBlock INSTANCE;

    private CanvasBlock() {
        super("screen_canvas");
        displayName = "Screen Canvas";
        shape = Shape.ENTITY;
        solid = false;
        lightOpacity = 0;
        tab = "redstone";
        java.util.Arrays.fill(textures, "wool_colored_black");
    }

    static void register() {
        INSTANCE = new CanvasBlock();
        INSTANCE.hardness = 0.3f;
        Blocks.register(INSTANCE);
        BlockEntity.register(CanvasEntity.TYPE, CanvasEntity::new);
    }

    /** Direction (Dir) from the canvas to the block it hangs on. */
    public static int back(int meta) { return Math.min(5, meta & 7); }

    /** Inventory icon: a dark screen with a blue face. In the world the entity renderer draws the picture. */
    @Override public List<Box> boxes(int meta, int conn) {
        return List.of(new Box(1, 3, 7, 15, 13, 9).all("wool_colored_black").face(Dir.SOUTH, "glass_light_blue").face(Dir.NORTH, "glass_light_blue"));
    }

    /** What is clicked: a thin plate against the block it hangs on (the picture itself may be moved elsewhere). */
    @Override public List<float[]> selectionBoxes(int meta, int conn) {
        float t = 2 / 16f;
        return List.of(switch (back(meta)) {
            case Dir.DOWN -> new float[]{0, 0, 0, 1, t, 1};
            case Dir.UP -> new float[]{0, 1 - t, 0, 1, 1, 1};
            case Dir.NORTH -> new float[]{0, 0, 0, 1, 1, t};
            case Dir.SOUTH -> new float[]{0, 0, 1 - t, 1, 1, 1};
            case Dir.WEST -> new float[]{0, 0, 0, t, 1, 1};
            default -> new float[]{1 - t, 0, 0, 1, 1, 1};
        });
    }

    @Override public List<float[]> collisionBoxes(int meta, int conn) { return List.of(); }

    @Override public int placementMeta(int face, float hitY, float yaw) { return Dir.OPPOSITE[face]; }

    /** Any block that is not air carries it (3D blocks and other non-solid decorations too). */
    @Override public boolean canPlaceAt(BlockGetter w, int x, int y, int z, int meta) {
        int d = back(meta);
        return !w.getBlock(x + Dir.DX[d], y + Dir.DY[d], z + Dir.DZ[d]).isAir();
    }

    @Override public BlockEntity createBlockEntity() { return new CanvasEntity(); }

    @Override public boolean onUse(Session s, int x, int y, int z, int meta) {
        if (s.player.sneaking && s.heldStack() != null) return false;
        if (s.world.getBlockEntity(x, y, z) instanceof CanvasEntity ce) s.game.setScreen(new derpcraft.gui.CanvasScreen(s.game, s, ce));
        return true;
    }

    @Override public void onPlaced(World w, int x, int y, int z, int meta) {
        Session s = Session.current;
        if (s == null || !(w.getBlockEntity(x, y, z) instanceof CanvasEntity ce)) return;
        // on a floor or ceiling the top of the picture is the side the player looks to
        ce.facing = Math.floorMod(Math.round(s.player.yaw / 90f), 4);
        ce.markDirty();
        s.game.setScreen(new derpcraft.gui.CanvasScreen(s.game, s, ce));
    }

    /**
     * Corners of the canvas picture in world coordinates: out[0..11] = bottom left, bottom right, top right, top left
     * (x, y, z each), out[12..14] = the normal (towards the viewer).
     */
    public static void corners(CanvasEntity ce, int meta, float[] out) {
        int b = back(meta);
        float nx = -Dir.DX[b], ny = -Dir.DY[b], nz = -Dir.DZ[b];          // normal: away from the block it hangs on
        float ux, uy, uz;                                                  // picture "up"
        if (b >= 2) { ux = 0; uy = 1; uz = 0; }
        else { int d = Dir.HORIZONTAL[ce.facing & 3]; ux = Dir.DX[d]; uy = 0; uz = Dir.DZ[d]; }
        // right = up x normal (seen from the front)
        float rx = uy * nz - uz * ny, ry = uz * nx - ux * nz, rz = ux * ny - uy * nx;
        if (ce.roll != 0) {
            double a = Math.toRadians(ce.roll);
            float c = (float) Math.cos(a), s = (float) Math.sin(a);
            float nrx = rx * c + ux * s, nry = ry * c + uy * s, nrz = rz * c + uz * s;
            float nux = ux * c - rx * s, nuy = uy * c - ry * s, nuz = uz * c - rz * s;
            rx = nrx; ry = nry; rz = nrz; ux = nux; uy = nuy; uz = nuz;
        }
        // centre: on the face of the block it hangs on (a hair in front of it), then the offsets
        float cx = ce.x + 0.5f + Dir.DX[b] * (0.5f - 0.004f) + rx * ce.right + ux * ce.up + nx * ce.out;
        float cy = ce.y + 0.5f + Dir.DY[b] * (0.5f - 0.004f) + ry * ce.right + uy * ce.up + ny * ce.out;
        float cz = ce.z + 0.5f + Dir.DZ[b] * (0.5f - 0.004f) + rz * ce.right + uz * ce.up + nz * ce.out;
        float hw = ce.width / 2, hh = ce.height / 2;
        float[] sx = {-1, 1, 1, -1}, sy = {-1, -1, 1, 1};
        for (int k = 0; k < 4; k++) {
            out[k * 3] = cx + rx * hw * sx[k] + ux * hh * sy[k];
            out[k * 3 + 1] = cy + ry * hw * sx[k] + uy * hh * sy[k];
            out[k * 3 + 2] = cz + rz * hw * sx[k] + uz * hh * sy[k];
        }
        out[12] = nx; out[13] = ny; out[14] = nz;
    }
}
