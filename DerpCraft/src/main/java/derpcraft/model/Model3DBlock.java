package derpcraft.model;

import derpcraft.block.Block;
import derpcraft.block.Blocks;
import derpcraft.block.Box;
import derpcraft.game.Session;
import derpcraft.world.Chunk;
import derpcraft.world.World;
import derpcraft.world.be.BlockEntity;

import java.util.List;

/**
 * The 3D block: shows a model from game/3d in the world. Right click opens its settings (model, turn, size,
 * offset, collision). With collision on, players, NPCs and mobs bump into the model's box (it may be bigger than
 * the block).
 */
public final class Model3DBlock extends Block {
    public static Model3DBlock INSTANCE;
    /** How far a model may reach out of its block (blocks) for collision checks. */
    public static final int REACH = 12;

    private Model3DBlock() {
        super("model_block");
        displayName = "3D Block";
        shape = Shape.ENTITY;
        solid = false;
        lightOpacity = 0;
        hardness = 0.5f;
        tab = "decoration";
        java.util.Arrays.fill(textures, "structure_block");
    }

    public static void register() {
        INSTANCE = new Model3DBlock();
        Blocks.register(INSTANCE);
        BlockEntity.register("model_block", ModelEntity::new);
    }

    @Override public List<Box> boxes(int meta, int conn) { return List.of(new Box(2, 0, 2, 14, 12, 14).all("structure_block")); }

    @Override public List<float[]> selectionBoxes(int meta, int conn) { return List.of(new float[]{0, 0, 0, 1, 1, 1}); }

    @Override public List<float[]> collisionBoxes(int meta, int conn) { return List.of(); }

    @Override public BlockEntity createBlockEntity() { return new ModelEntity(); }

    @Override public boolean onUse(Session s, int x, int y, int z, int meta) {
        if (s.player.sneaking && s.heldStack() != null) return false;          // sneak + block in hand: place against it
        if (s.world.getBlockEntity(x, y, z) instanceof ModelEntity me) s.game.setScreen(new derpcraft.gui.ModelBlockScreen(s.game, s, me));
        return true;
    }

    @Override public void onPlaced(World w, int x, int y, int z, int meta) {
        Session s = Session.current;
        if (s == null || !(w.getBlockEntity(x, y, z) instanceof ModelEntity me)) return;
        ModelEntity.any = true;
        me.yaw = Math.round((s.player.yaw + 180) / 90f) * 90 % 360;      // faces the player
        if (me.model.isEmpty() && !Models.names().isEmpty()) me.model = ModelEntity.lastModel != null && Models.exists(ModelEntity.lastModel) ? ModelEntity.lastModel : Models.names().get(0);
        me.changed();
        s.game.setScreen(new derpcraft.gui.ModelBlockScreen(s.game, s, me));
    }

    /** Collision boxes of 3D blocks overlapping a region (added to 'out' as x0,y0,z0,x1,y1,z1). */
    public static void colliders(World w, double x0, double y0, double z0, double x1, double y1, double z1, derpcraft.world.Collision.Boxes out) {
        if (!ModelEntity.any) return;
        int cx0 = ((int) Math.floor(x0) - REACH) >> 4, cx1 = ((int) Math.floor(x1) + REACH) >> 4;
        int cz0 = ((int) Math.floor(z0) - REACH) >> 4, cz1 = ((int) Math.floor(z1) + REACH) >> 4;
        for (int cx = cx0; cx <= cx1; cx++)
            for (int cz = cz0; cz <= cz1; cz++) {
                Chunk c = w.getChunk(cx, cz);
                if (c == null || c.blockEntities.isEmpty()) continue;
                for (BlockEntity be : c.blockEntities.values()) {
                    if (!(be instanceof ModelEntity me)) continue;
                    if (w.getBlock(me.x, me.y, me.z) != INSTANCE) continue;
                    double[] b = me.collisionBox();
                    if (b == null) continue;
                    if (b[3] <= x0 || b[0] >= x1 || b[4] <= y0 || b[1] >= y1 || b[5] <= z0 || b[2] >= z1) continue;
                    out.add(b);
                }
            }
    }
}
