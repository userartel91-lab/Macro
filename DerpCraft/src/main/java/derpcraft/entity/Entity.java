package derpcraft.entity;

import derpcraft.world.World;

import java.util.ArrayList;
import java.util.List;

/** Something that moves in the world (dropped items, falling blocks). Ticked 20x per second on the main thread. */
public abstract class Entity {
    public final World world;
    public double x, y, z, px, py, pz, mx, my, mz;
    public float width = 0.25f, height = 0.25f;
    public boolean onGround, dead;
    public int age;

    protected Entity(World w, double x, double y, double z) {
        this.world = w;
        this.x = px = x; this.y = py = y; this.z = pz = z;
    }

    public void tick() {
        px = x; py = y; pz = z;
        age++;
    }

    /** Collision boxes around the entity, reused every tick (main thread only, like all entity ticks). */
    private static final derpcraft.world.Collision.Boxes BOXES = new derpcraft.world.Collision.Boxes();

    /** Moves with block collision (axis by axis, like Minecraft). */
    protected void move(double dx, double dy, double dz) {
        double h = width / 2;
        derpcraft.world.Collision.Boxes boxes = BOXES;
        boxes.clear();
        derpcraft.world.Collision.collect(world, x - h + Math.min(0, dx), y + Math.min(0, dy), z - h + Math.min(0, dz),
                x + h + Math.max(0, dx), y + height + Math.max(0, dy), z + h + Math.max(0, dz), boxes, null, false);
        double ody = dy, odx = dx, odz = dz;
        double x0 = x - h, y0 = y, z0 = z - h, x1 = x + h, y1 = y + height, z1 = z + h;
        dy = boxes.clip(1, dy, x0, y0, z0, x1, y1, z1);
        y0 += dy; y1 += dy;
        dx = boxes.clip(0, dx, x0, y0, z0, x1, y1, z1);
        x0 += dx; x1 += dx;
        dz = boxes.clip(2, dz, x0, y0, z0, x1, y1, z1);
        x += dx; y += dy; z += dz;
        onGround = ody < 0 && dy != ody;
        if (dy != ody) my = 0;
        if (dx != odx) mx = 0;
        if (dz != odz) mz = 0;
    }

    /** Collision boxes overlapping a region, as separate arrays (for mods and old code; physics uses {@link derpcraft.world.Collision}). */
    public static List<double[]> collect(World w, double x0, double y0, double z0, double x1, double y1, double z1) {
        derpcraft.world.Collision.Boxes b = new derpcraft.world.Collision.Boxes();
        derpcraft.world.Collision.collect(w, x0, y0, z0, x1, y1, z1, b, null, false);
        List<double[]> out = new ArrayList<>(b.n);
        for (int k = 0; k < b.n; k++) out.add(b.get(k));
        return out;
    }

    public static double clip(double[] b, int axis, double d, double x0, double y0, double z0, double x1, double y1, double z1) {
        return derpcraft.world.Collision.clip(b, axis, d, x0, y0, z0, x1, y1, z1);
    }

    public double distSq(double ox, double oy, double oz) {
        double dx = x - ox, dy = y - oy, dz = z - oz;
        return dx * dx + dy * dy + dz * dz;
    }
}
