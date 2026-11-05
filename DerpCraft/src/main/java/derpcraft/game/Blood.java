package derpcraft.game;

/**
 * Blood (Options -> Gameplay -> Blood): drops fly out of a body and leave splats where they land, more the harder the
 * hit. Bullets spray from the wound along the shot ({@link derpcraft.gun.Ballistics}); any other damage (punches,
 * creatures, falls, cars) bleeds from the body ({@link #body}). Drawn by EntityRenderer.blood.
 */
public final class Blood {
    private Blood() {}

    /** While above 0, {@link #body} does nothing (a bullet already sprays its own blood from the wound). */
    public static int muted;

    /** Blood from a point, thrown along (dx, dy, dz) (any length; 0 = all around), as much as 'damage' asks for. */
    public static void spill(double x, double y, double z, double dx, double dy, double dz, float damage) {
        Session s = Session.current;
        if (s == null || damage <= 0 || !s.game.settings.blood) return;
        s.game.entities.blood(s.world, x, y, z, dx, dy, dz, damage);
    }

    /** Damage of kind 'cause' to a body standing at (x, y, z), 'height' tall: it bleeds (falls from the feet, the rest from the chest). */
    public static void body(double x, double y, double z, double height, float damage, String cause) {
        if (muted > 0 || !bleeds(cause)) return;
        boolean fall = "fall".equals(cause);
        spill(x, y + (fall ? 0.1 : height * 0.6), z, 0, fall ? 0.2 : 0.1, 0, damage);
    }

    /** Causes that do not draw blood (burning, drowning, hunger, the void, commands). */
    public static boolean bleeds(String cause) {
        if (cause == null) return true;
        return switch (cause) {
            case "fire", "lava", "drown", "starve", "void", "kill", "suffocate", "magic", "wither", "poison" -> false;
            default -> true;
        };
    }
}
