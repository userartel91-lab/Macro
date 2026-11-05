package derpcraft.camera;

import derpcraft.block.Block;
import derpcraft.block.Blocks;
import derpcraft.block.Box;
import derpcraft.game.Session;
import derpcraft.model.Model3D;
import derpcraft.model.Models;
import derpcraft.world.World;
import derpcraft.world.be.BlockEntity;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;

/**
 * Security camera / camcorder: a block with the camera model (samples3d/security_camera.bbmodel). Placing it asks for
 * the code screens use to connect; right click (after entering the code) changes its view, name and quality.
 */
public final class CameraBlock extends Block {
    public static CameraBlock INSTANCE;
    public static final String MODEL_RESOURCE = "security_camera.bbmodel";
    private static Model3D model;
    private static boolean modelTried;

    private CameraBlock() {
        super("security_camera");
        displayName = "Security Camera";
        shape = Shape.ENTITY;
        solid = false;
        lightOpacity = 0;
        tab = "redstone";
        java.util.Arrays.fill(textures, "iron_block");
    }

    public static void register() {
        INSTANCE = new CameraBlock();
        INSTANCE.hardness = 0.5f;
        Blocks.register(INSTANCE);
        BlockEntity.register(CameraEntity.TYPE, CameraEntity::new);
        CanvasBlock.register();
    }

    /** The camera model, read once from the game's resources (independent of game/3d). Null if it cannot be read. */
    public static Model3D model() {
        if (modelTried) return model;
        modelTried = true;
        try (InputStream in = CameraBlock.class.getResourceAsStream("/samples3d/" + MODEL_RESOURCE)) {
            if (in == null) return null;
            Path tmp = Files.createTempFile("derpcraft-camera", ".bbmodel");
            try {
                Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
                model = Models.loadFile(MODEL_RESOURCE, tmp);
                if (model != null && (model.empty() || model.error != null)) { System.err.println("Camera model: " + model.error); model = null; }
            } finally { Files.deleteIfExists(tmp); }
        } catch (Exception e) { System.err.println("Cannot load the camera model: " + e); }
        return model;
    }

    /** Inventory icon (the world shows the 3D model): a small grey box with a dark lens. */
    @Override public List<Box> boxes(int meta, int conn) {
        return List.of(new Box(4, 5, 2, 12, 11, 14).all("iron_block"),
                new Box(6, 6, 0, 10, 10, 2).all("coal_block"));
    }

    @Override public List<float[]> selectionBoxes(int meta, int conn) { return List.of(new float[]{0.2f, 0.25f, 0.2f, 0.8f, 0.8f, 0.8f}); }

    @Override public List<float[]> collisionBoxes(int meta, int conn) { return List.of(); }

    @Override public BlockEntity createBlockEntity() { return new CameraEntity(); }

    @Override public boolean onUse(Session s, int x, int y, int z, int meta) {
        if (s.player.sneaking && s.heldStack() != null) return false;          // sneak + block in hand: place against it
        if (s.world.getBlockEntity(x, y, z) instanceof CameraEntity ce)
            s.game.setScreen(new derpcraft.gui.CameraScreen(s.game, s, ce, ce.code.isEmpty()));
        return true;
    }

    @Override public void onPlaced(World w, int x, int y, int z, int meta) {
        Session s = Session.current;
        if (s == null || !(w.getBlockEntity(x, y, z) instanceof CameraEntity ce)) return;
        // looks back at the player (snapped to 15 degrees), a little down; on a ceiling steeply down
        ce.yaw = Math.round((s.player.yaw + 180) / 15f) * 15 % 360;
        boolean ceiling = !w.getBlock(x, y + 1, z).isAir() && w.getBlock(x, y - 1, z).isAir();
        ce.pitch = ceiling ? 35 : 10;
        ce.changed();
        s.game.setScreen(new derpcraft.gui.CameraScreen(s.game, s, ce, true));
    }
}
