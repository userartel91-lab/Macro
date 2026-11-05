package derpcraft.game;

import derpcraft.block.Blocks;
import derpcraft.entity.Entity;
import derpcraft.entity.ItemEntity;
import derpcraft.entity.Projectile;
import derpcraft.item.Item;
import derpcraft.item.ItemStack;
import derpcraft.render.EntityRenderer;
import derpcraft.render.WorldRenderer;

import java.util.List;
import java.util.Map;

/**
 * Light from things that move: a light source in the hand of the player or an NPC, lying on the ground or flying
 * (a thrown torch). The nearest few go to the shaders as extra block light; nothing is written into the world.
 */
public final class DynamicLights {
    private DynamicLights() {}

    /** Items that are not blocks but still shine. */
    private static final Map<String, Integer> ITEM_LEVELS = Map.of("lava_bucket", 15, "blaze_rod", 10, "glowstone_dust", 8, "nether_star", 12, "fire_arrow", 10);

    /** Light level (0..15) an item gives. */
    public static int level(Item it) {
        if (it == null) return 0;
        if (it.block != null && it.isBlockItem()) return Math.max(0, Math.min(15, it.block.lightEmission));
        return ITEM_LEVELS.getOrDefault(it.name, 0);
    }

    private static int level(ItemStack s) { return s == null || s.count <= 0 ? 0 : level(s.item); }

    /** RGB (0..1, brightest channel 1) of the light of an item. */
    private static void color(Item it, float[] out) {
        int c = 0xFFFFFF;
        if (it.block != null && it.isBlockItem()) c = Blocks.lightColor(it.block);
        else if (it.name.equals("lava_bucket")) c = Blocks.lightColor(Blocks.LAVA);
        float r = (c >> 16 & 255) / 255f, g = (c >> 8 & 255) / 255f, b = (c & 255) / 255f;
        float mx = Math.max(0.01f, Math.max(r, Math.max(g, b)));
        out[0] = r / mx; out[1] = g / mx; out[2] = b / mx;
    }

    private static final class Cand {
        double x, y, z, d2;
        int level;
        float r, g, b;
    }

    /** Fills the dynamic light list of the frame (empty when the option is off). */
    public static void collect(Session s, WorldRenderer.FrameParams fp, EntityRenderer.PlayerPose self, List<EntityRenderer.PlayerPose> actors,
                               double cx, double cy, double cz) {
        fp.dynCount = 0;
        if (!s.game.settings.dynamicLights) return;
        java.util.ArrayList<Cand> cands = new java.util.ArrayList<>();
        float[] col = new float[3];
        if (self != null) {
            // the player's pose is at the feet: the light is at chest height in front of the body
            addHeld(cands, self, cx, cy, cz, col);
        }
        for (EntityRenderer.PlayerPose a : actors) addHeld(cands, a, cx, cy, cz, col);
        for (Entity e : s.world.entities) {
            Item it = null;
            double ey = e.y;
            if (e instanceof ItemEntity ie && ie.stack != null) { it = ie.stack.item; ey += 0.2; }
            else if (e instanceof Projectile p && !p.dead) { it = p.type.spriteItem(); ey += 0.1; }
            if (it == null) continue;
            int lv = level(it);
            if (lv <= 0) continue;
            add(cands, e.x, ey, e.z, lv, it, cx, cy, cz, col);
        }
        addVehicleLights(s, cands, cx, cy, cz);
        // burning wrecks: a flickering orange light
        if (s.vehicles != null) for (derpcraft.vehicle.Vehicle v : s.vehicles.burning()) {
            derpcraft.vehicle.VehicleModel m = v.vm();
            if (m == null) continue;
            double[] w = new double[3];
            v.bodyPointAt(m, 0, m.maxY * 0.6f, (m.minZ + m.maxZ) / 2 - 0.3f, 1, w);
            Cand c = new Cand();
            c.x = w[0]; c.y = w[1]; c.z = w[2];
            float fire = 1 - v.wreckAge / (float) derpcraft.vehicle.Vehicles.FIRE_TICKS;
            c.level = Math.max(3, Math.round((8 + 3 * (float) Math.sin(s.ticks * 0.9 + v.id)) * (0.4f + 0.6f * fire)));
            c.r = 1f; c.g = 0.55f; c.b = 0.2f;
            double ddx = c.x - cx, ddy = c.y - cy, ddz = c.z - cz;
            c.d2 = ddx * ddx + ddy * ddy + ddz * ddz;
            if (c.d2 < 80 * 80) cands.add(c);
        }
        cands.sort((p, q) -> Double.compare(p.d2, q.d2));
        int n = Math.min(cands.size(), WorldRenderer.FrameParams.MAX_DYN);
        for (int i = 0; i < n; i++) {
            Cand c = cands.get(i);
            fp.dynPos[i * 4] = (float) c.x; fp.dynPos[i * 4 + 1] = (float) c.y; fp.dynPos[i * 4 + 2] = (float) c.z; fp.dynPos[i * 4 + 3] = c.level;
            fp.dynCol[i * 3] = c.r; fp.dynCol[i * 3 + 1] = c.g; fp.dynCol[i * 3 + 2] = c.b;
        }
        fp.dynCount = n;
    }

    private static void addHeld(List<Cand> out, EntityRenderer.PlayerPose p, double cx, double cy, double cz, float[] col) {
        int l1 = level(p.held), l2 = level(p.offhand);
        if (l1 <= 0 && l2 <= 0) return;
        ItemStack st = l1 >= l2 ? p.held : p.offhand;
        // slightly in front of the body (the hand), so a wall right in front of the face does not swallow the light
        double yaw = Math.toRadians(p.bodyYaw);
        double hx = p.x - Math.sin(yaw) * 0.35, hz = p.z + Math.cos(yaw) * 0.35;
        add(out, hx, p.y + 1.1 * (p.scale <= 0 ? 1 : p.scale), hz, Math.max(l1, l2), st.item, cx, cy, cz, col);
    }

    private static void add(List<Cand> out, double x, double y, double z, int level, Item it, double cx, double cy, double cz, float[] col) {
        double dx = x - cx, dy = y - cy, dz = z - cz, d2 = dx * dx + dy * dy + dz * dz;
        if (d2 > 48 * 48) return;
        Cand c = new Cand();
        c.x = x; c.y = y; c.z = z; c.d2 = d2; c.level = level;
        color(it, col);
        c.r = col[0]; c.g = col[1]; c.b = col[2];
        out.add(c);
    }

    /**
     * Vehicle lights (key N): along each light from its lamp (start) to the end of the beam a few points of light, dim at the lamp
     * and brighter the further they are, so the ground ahead is lit more than the bumper. A beam stops at a block that stops light.
     */
    private static void addVehicleLights(Session s, java.util.ArrayList<Cand> cands, double cx, double cy, double cz) {
        if (s.vehicles == null) return;
        double[] a = new double[3], b = new double[3];
        for (derpcraft.vehicle.Vehicle v : s.vehicles.list()) {
            derpcraft.vehicle.VehicleModel m = v.vm();
            if (!v.lightsOn || m == null) continue;
            double dx0 = v.x - cx, dz0 = v.z - cz;
            if (dx0 * dx0 + dz0 * dz0 > 100 * 100) continue;
            for (derpcraft.vehicle.VehicleType.Light l : v.type().effectiveLights()) {
                if (l.intensity <= 0) continue;
                v.bodyPointAt(m, l.a[0], l.a[1], l.a[2], 1, a);
                v.bodyPointAt(m, l.b[0], l.b[1], l.b[2], 1, b);
                double dx = b[0] - a[0], dy = b[1] - a[1], dz = b[2] - a[2], len = Math.sqrt(dx * dx + dy * dy + dz * dz);
                if (len < 0.2) continue;
                dx /= len; dy /= len; dz /= len;
                // how far the light gets before a block stops it
                double reach = len;
                int lx = Integer.MIN_VALUE, ly = 0, lz = 0;
                for (double t = 0.15; t <= len; t += 0.25) {
                    int bx = (int) Math.floor(a[0] + dx * t), by = (int) Math.floor(a[1] + dy * t), bz = (int) Math.floor(a[2] + dz * t);
                    if (bx == lx && by == ly && bz == lz) continue;
                    lx = bx; ly = by; lz = bz;
                    if (s.world.getBlock(bx, by, bz).isOpaqueCube()) { reach = Math.max(0.3, t - 0.3); break; }
                }
                int pts = (int) Math.max(1, Math.min(3, Math.round(len / 4)));
                float mx = Math.max(0.01f, Math.max(l.color[0], Math.max(l.color[1], l.color[2])));
                for (int i = 1; i <= pts; i++) {
                    double f = i / (double) pts;                          // the way along the beam (to its end)
                    int level = (int) Math.round(l.intensity * (3 + 12 * f * f));          // dim at the lamp, brighter further on
                    if (level <= 0) continue;
                    Cand c = new Cand();
                    c.x = a[0] + dx * reach * f; c.y = a[1] + dy * reach * f; c.z = a[2] + dz * reach * f;
                    c.level = Math.min(15, level);
                    c.r = l.color[0] / mx; c.g = l.color[1] / mx; c.b = l.color[2] / mx;
                    double ddx = c.x - cx, ddy = c.y - cy, ddz = c.z - cz;
                    c.d2 = ddx * ddx + ddy * ddy + ddz * ddz;
                    cands.add(c);
                }
            }
        }
    }
}
