package derpcraft.util;

/**
 * Ray against an axis-aligned box (slab test), shared by every hit box: mobs, NPCs, the player, cars, bullets.
 * No arrays: it runs for every creature on every shot and every frame of the crosshair.
 */
public final class RayBox {
    private RayBox() {}

    /**
     * Distance along the ray (origin o, direction d) to the box, 0 when the origin is inside, or -1 when the ray misses
     * it or reaches it only beyond 'max'.
     */
    public static double t(double ox, double oy, double oz, double dx, double dy, double dz, double max,
                           double x0, double y0, double z0, double x1, double y1, double z1) {
        double tmin = 0, tmax = max;
        // x
        if (Math.abs(dx) < 1e-9) { if (ox < x0 || ox > x1) return -1; }
        else {
            double t1 = (x0 - ox) / dx, t2 = (x1 - ox) / dx;
            if (t1 > t2) { double t = t1; t1 = t2; t2 = t; }
            if (t1 > tmin) tmin = t1;
            if (t2 < tmax) tmax = t2;
            if (tmin > tmax) return -1;
        }
        // y
        if (Math.abs(dy) < 1e-9) { if (oy < y0 || oy > y1) return -1; }
        else {
            double t1 = (y0 - oy) / dy, t2 = (y1 - oy) / dy;
            if (t1 > t2) { double t = t1; t1 = t2; t2 = t; }
            if (t1 > tmin) tmin = t1;
            if (t2 < tmax) tmax = t2;
            if (tmin > tmax) return -1;
        }
        // z
        if (Math.abs(dz) < 1e-9) { if (oz < z0 || oz > z1) return -1; }
        else {
            double t1 = (z0 - oz) / dz, t2 = (z1 - oz) / dz;
            if (t1 > t2) { double t = t1; t1 = t2; t2 = t; }
            if (t1 > tmin) tmin = t1;
            if (t2 < tmax) tmax = t2;
            if (tmin > tmax) return -1;
        }
        return tmin;
    }
}
