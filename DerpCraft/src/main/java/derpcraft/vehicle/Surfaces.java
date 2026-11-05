package derpcraft.vehicle;

import derpcraft.block.Block;
import derpcraft.block.Blocks;
import derpcraft.world.World;

/**
 * What the car drives on: the block under it decides the grip and the speed (ice slides, sand is slow). The multipliers of each
 * kind are settings of the vehicle ({@link VehicleType#surfGrip}, {@link VehicleType#surfSpeed}).
 */
public final class Surfaces {
    private Surfaces() {}

    public static final int ROAD = 0, DIRT = 1, SAND = 2, SNOW = 3, ICE = 4;
    public static final String[] NAMES = {"road", "dirt", "sand", "snow", "ice"};
    public static final String[] LABELS = {"Road", "Dirt, grass", "Sand", "Snow", "Ice"};
    /** Tyre smoke colour on each surface. */
    private static final float[][] DUST = {{0.8f, 0.8f, 0.8f}, {0.55f, 0.45f, 0.34f}, {0.85f, 0.76f, 0.5f}, {0.95f, 0.97f, 1f}, {0.9f, 0.95f, 1f}};

    public static float[] dust(int surface) { return DUST[Math.max(0, Math.min(DUST.length - 1, surface))]; }

    /** The surface under a point (the block whose top the wheels rest on). */
    public static int under(World w, double x, double groundY, double z) {
        int bx = (int) Math.floor(x), bz = (int) Math.floor(z), by = (int) Math.floor(groundY - 0.05);
        for (int k = 0; k < 2; k++, by--) {
            Block b = Blocks.byId(w.getState(bx, by, bz) >> 4);
            if (b == null || b.isAir() || b.shape == Block.Shape.LIQUID) continue;
            return of(b.name);
        }
        return ROAD;
    }

    /** The kind of surface of a block by its name. */
    public static int of(String n) {
        if (n.contains("ice")) return ICE;
        if (n.contains("snow")) return SNOW;
        if (n.contains("sand") && !n.contains("stone")) return SAND;
        if (n.contains("grass") || n.contains("dirt") || n.contains("gravel") || n.contains("podzol") || n.contains("mycelium") || n.contains("farmland")
                || n.contains("clay") || n.contains("leaves") || n.contains("path") || n.contains("hay")) return DIRT;
        return ROAD;
    }
}
