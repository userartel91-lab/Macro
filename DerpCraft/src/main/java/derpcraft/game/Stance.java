package derpcraft.game;

/**
 * Body postures of the player: hit box height, eye height, walking speed and the pose of the third person model.
 * STAND / SIT / LIE / CRAWL are chosen with Alt + 1..4; SNEAK (Shift) and DEEP (Alt + Shift) are held while standing.
 * Heights are in blocks above the feet; the width is always {@link Player#WIDTH}.
 */
public enum Stance {
    //     height eye    speed  | tilt   lift    sneak deep keep | parts held by the pose: {part, x, y, z} in degrees
    STAND(1.80f, 1.62f, 1.00f, 0, 0, 0, 0, 0),
    /** Shift: a bent back and bent knees, the camera 0.3 lower than standing. */
    SNEAK(1.65f, 1.32f, 0.30f, 0, 0, 1, 0, 0),
    /** Alt + Shift: a deep crouch; fits under a space 1.5 blocks high. */
    DEEP(1.30f, 1.02f, 0.20f, 0, 0, 1, 1, 0),
    /** Alt + 2: sitting on the ground, legs out in front. */
    SIT(1.10f, 0.90f, 0.12f, 0, -0.72f, 0, 0, 0,
            new float[]{2, -25, 0, 8}, new float[]{3, -25, 0, -8}, new float[]{4, -90, 8, 0}, new float[]{5, -90, -8, 0}),
    /** Alt + 4: crawling on the belly, head forward (a 1 block high tunnel). */
    CRAWL(0.60f, 0.38f, 0.25f, -90, 0.24f, 0, 0, 0.35f,
            new float[]{2, -160, 0, 6}, new float[]{3, -160, 0, -6}, new float[]{4, 0, 0, 3}, new float[]{5, 0, 0, -3}),
    /** Alt + 3: lying on the back, feet in front. */
    LIE(0.50f, 0.28f, 0.06f, 90, 0.14f, 0, 0, 0,
            new float[]{2, 0, 0, 8}, new float[]{3, 0, 0, -8}, new float[]{4, 0, 0, 2}, new float[]{5, 0, 0, -2});

    // layout of the model parameter vector (blended smoothly by the player, read by the renderer)
    public static final int P_TILT = 0, P_LIFT = 1, P_SNEAK = 2, P_DEEP = 3, P_KEEP = 4, P_ROT = 5, P_HOLD = 23, N = 29;

    public final float height, eye, speed;
    /** Model parameters: P_TILT (degrees, whole body around the neck), P_LIFT (blocks), P_SNEAK / P_DEEP (0..1), P_KEEP (how much of the walk animation stays on held limbs), rotations and hold weights per part. */
    public final float[] params = new float[N];

    Stance(float height, float eye, float speed, float tilt, float lift, float sneak, float deep, float keep, float[]... parts) {
        this.height = height; this.eye = eye; this.speed = speed;
        params[P_TILT] = tilt; params[P_LIFT] = lift; params[P_SNEAK] = sneak; params[P_DEEP] = deep; params[P_KEEP] = keep;
        for (float[] p : parts) {
            int part = (int) p[0];
            params[P_ROT + part * 3] = p[1]; params[P_ROT + part * 3 + 1] = p[2]; params[P_ROT + part * 3 + 2] = p[3];
            params[P_HOLD + part] = 1;
        }
    }

    /** Postures from the tallest to the lowest: when there is no room for one, the next lower one that fits is used. */
    public static final Stance[] BY_HEIGHT = {STAND, SNEAK, DEEP, SIT, CRAWL, LIE};
}
