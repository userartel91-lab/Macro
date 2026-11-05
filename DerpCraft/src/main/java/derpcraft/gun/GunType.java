package derpcraft.gun;

import derpcraft.model.Model3D;
import derpcraft.model.Models;
import derpcraft.util.Json;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * One gun kind: a folder in game/guns (its name is the gun's id), read without compiling anything:
 * <ul>
 *   <li>&lt;name&gt;.obj — the model (1 unit = 1 block, barrel to -Z). Objects: the gun itself (any name), "mag" (magazine),
 *   "slide" (bolt / charging handle). Helper objects, never drawn: "rag" (right hand: grip), "lag" (left hand: handguard),
 *   "las" (left hand on the bolt when reloading), "ram" (right hand on the magazine; "lam" is read too), "larm" / "rarm"
 *   (old arm boxes: ignored, the arms are the player's own).</li>
 *   <li>&lt;name&gt;.png — texture, &lt;name&gt;_item.png — inventory icon, &lt;name&gt;_mag.png — magazine icon.</li>
 *   <li>shoot1..3 (.ogg or .wav) and optional reload sounds: mag_out, mag_in, bolt, dry, equip; melee (the swing of a
 *   butt strike) and melee_hit (it lands).</li>
 *   <li>&lt;name&gt;.json — the numbers: every {@link Param} (see {@link #PARAMS}) and "points" (hand points, sight,
 *   muzzle in model units when the automatic ones are off). Unknown keys are kept when the gun editor saves.</li>
 * </ul>
 */
public final class GunType {
    public final String name;
    public final Path dir;
    public String displayName;
    /** The settings file (may not exist yet: the editor creates it). */
    public final Path jsonFile;

    // ---------------------------------------------------------------- numbers (<name>.json)
    /** Rounds per minute. */
    public float rpm = 600;
    public boolean auto = true;
    public int magazine = 30;
    public float damage = 6;
    public float range = 160;
    /** Reload time with rounds left / with an empty gun (bolt pulled), seconds. */
    public float reloadTime = 2.2f, reloadEmptyTime = 2.8f;
    /** Walking speed with the gun in hand, while aiming, and the extra loss when running (multipliers). */
    public float moveSpeed = 0.95f, aimMoveSpeed = 0.6f, sprintSpeed = 0.9f;
    /** Spread in degrees: from the hip, aiming; the extra per shot (bloom) and while walking. */
    public float spreadHip = 3.5f, spreadAim = 0.25f, spreadShot = 0.35f, spreadMove = 1.5f;
    /** Camera kick per shot (degrees up, random sideways). */
    public float recoilUp = 0.9f, recoilSide = 0.45f;
    /** Aiming zoom (field of view divided by it) and how long it takes to raise the sights (seconds). */
    public float aimZoom = 1.35f, aimTime = 0.22f;
    /** Size of the model: in the first person view and in the hands of the third person model. */
    public float firstPersonScale = 0.4f, thirdPersonScale = 0.42f;
    /**
     * First person poses: hip, aim (down the sights), sprint, reload (magazine change) and bolt (pulled on an empty
     * reload). For each: the move and turn of the whole rig (gun and hands; hip: where the grip is, the others: added
     * on top while that pose is on), the gun alone (the hands stay) and the arms alone (the gun stays). View space:
     * x right, y up, z forward negative; turns: yaw, pitch, roll in degrees.
     */
    public static final String[] POSES = {"hip", "aim", "sprint", "reload", "bolt", "aim2",
            "jabWind", "jabHit", "swingWind", "swingHit", "smashWind", "smashHit"};
    /** Index of the second aim (Alt while aiming: a canted sight, a magnifier...). */
    public static final int AIM2 = 5;
    /**
     * Melee with the gun (butt strikes, a pistol whip): three kinds, each two poses added on top of the hold, like the
     * reload pose: the wind-up (the gun drawn back) and the hit (where the strike ends). A strike goes hold -> wind-up ->
     * hit (speeding up) -> holds there a moment -> back; its times and damage are per kind (see {@link #meleeTime}).
     */
    public static final String[] MELEE = {"jab", "swing", "smash"};
    public static final String[] MELEE_NAMES = {"Jab", "Swing", "Smash"};
    /** Index of the first melee pose (jab wind-up); then jab hit, swing wind-up, swing hit, smash wind-up, smash hit. */
    public static final int MELEE_POSE = 6;
    public static int meleePose(int kind, boolean hit) { return MELEE_POSE + kind * 2 + (hit ? 1 : 0); }
    public static boolean meleePose(int pose) { return pose >= MELEE_POSE; }
    // rifle defaults (the pistol ones: PISTOL_MELEE, used when the gun's json has none)
    public final float[][] poseMove = {{0.2f, -0.24f, -0.4f}, {0, 0, 0}, {-0.02f, 0.05f, 0.02f}, {-0.07f, 0.07f, 0.03f}, {-0.02f, -0.07f, 0.02f}, {0, 0, 0},
            {0.1f, -0.04f, 0.12f}, {-0.32f, 0.1f, -0.22f},          // jab: drawn back to the right, then turned across, the butt thrust forward
            {0.14f, 0.02f, 0.06f}, {-0.15f, 0.06f, -0.2f},          // swing: out to the right, then the butt sweeps across to the left
            {0.04f, 0.16f, 0.04f}, {-0.22f, -0.02f, -0.24f}};       // smash: the butt up high, then chopped down and forward
    public final float[][] poseTurn = {{2, 0, 0}, {0, 0, 0}, {32, -8, 22}, {22, 24, -24}, {16, -14, 35}, {0, 0, 35},
            {-20, -5, -15}, {100, -12, 15},
            {25, 0, -25}, {-120, -10, 10},
            {50, -35, -30}, {90, 28, 20}};
    public final float[][] gunMove = new float[POSES.length][3], gunTurn = new float[POSES.length][3], armsMove = new float[POSES.length][3];
    /** Per pose, each arm on its own: a move (view space) and a turn around its hand (yaw, pitch, roll), on top of the Arms page's. */
    public final float[][] rArmMove = new float[POSES.length][3], rArmTurn = new float[POSES.length][3],
            lArmMove = new float[POSES.length][3], lArmTurn = new float[POSES.length][3];
    /**
     * Melee times per kind (seconds): wind-up, strike (wind-up -> hit; the moment it ends is the impact), hold at the hit,
     * back to the hold. Damage per kind; reach (blocks); knockback; how soon after a strike the next key press goes on to
     * the next kind (combo jab -> swing -> smash; later: a jab again).
     */
    public final float[][] meleeTime = {{0.1f, 0.09f, 0.06f, 0.28f}, {0.14f, 0.1f, 0.07f, 0.32f}, {0.18f, 0.11f, 0.08f, 0.36f}};
    public final float[] meleeDamage = {4, 5, 7};
    public float meleeReach = 2.6f, meleeKnockback = 0.45f, meleeCombo = 0.45f;
    /** Where the grip is at the hip (the hip pose's move) and its turn. */
    public final float[] hipPosition = poseMove[0], hipRotation = poseTurn[0];
    /** How far in front of the eye the sight is when aiming (blocks); the same for the second aim, and its zoom. */
    public float aimDistance = 0.3f, aim2Distance = 0.3f, aim2Zoom = 1.35f;
    /** Third person: a move of the gun in the hands (blocks: x right, y up, z forward negative) and a turn (yaw, pitch, roll). */
    public float[] thirdPersonOffset = {0, 0, 0}, thirdPersonRotation = {0, 0, 0};
    /** Third person, the gun carried on the back (Left Alt + F; {@link GunHold3P#back}): a move (blocks, like the offset) and a turn around its middle. */
    public float[] thirdPersonBackOffset = {0, 0, 0}, thirdPersonBackRotation = {0, 0, 0};
    /**
     * The magazine of this gun standing in a belt pouch (Left Alt + E; tuned with the magazine in the hand + Left Alt + Home):
     * a move (blocks: out from the middle of the belt, up, forward; mirrored for the right pouches), a turn around the
     * middle of the magazine (yaw, pitch, roll; mirrored) and a size multiplier on top of the 3rd person one.
     */
    public float[] pouchOffset = {0, 0, 0}, pouchRotation = {0, 0, 0};
    public float pouchScale = 1;
    /** Damage of a hit in the head (the top quarter of a body) = damage x this. */
    public float headshotMultiplier = 2.5f;
    /** Size of the gun on the back (like thirdPersonScale); 0 = the same as thirdPersonScale (in the hands). */
    public float thirdPersonBackScale = 0;

    /** The size the gun is drawn with on the back. */
    public float backScale() { return thirdPersonBackScale > 0 ? thirdPersonBackScale : thirdPersonScale; }
    /** Animation strengths (1 = normal). */
    public float swayAmount = 1, bobAmount = 1, kickBack = 1, kickUp = 1, kickRoll = 1, sprintTilt = 1, reloadTilt = 1;
    /** Smoothing of the poses (sprint, reload, bolt): the more extreme a pose, the softer the way into it; 0 = off. */
    public float poseSmooth = 1;
    /** How fast every motion plays (springs, blends, bob, equip; 1 = the old slow pace). Reload length: reloadTime. */
    public float animSpeed = 1.6f;
    /**
     * Arms (normal Minecraft arms, not stretched): size (1 = a skin pixel the size of a model pixel), extra length and
     * thickness; where each arm points to from its hand (view space: the shoulder side, out of the picture), a move of the
     * whole arm and a turn around the hand.
     */
    public float armSize = 1.4f, armLength = 1, armThickness = 1;
    public float[] shoulderRight = {0.4f, -1.0f, 0.35f}, shoulderLeft = {-0.15f, -1.3f, 0.35f};
    public float[] armRightOffset = {0, 0, 0}, armLeftOffset = {0, 0, 0};
    public float[] armRightRotation = {0, 0, 0}, armLeftRotation = {0, 0, 0};
    /** The turn of the hand (yaw, pitch, roll around the palm) while it holds the magazine / pulls the bolt (on top of its arm's turn). */
    public float[] magHandRotation = {0, 0, 0}, boltHandRotation = {0, 0, 0};
    /** Shooting volume. */
    public float volume = 1;
    /** Reload hands: the magazine is changed by the left hand (else the right), the bolt pulled by the right (else the left). */
    public boolean magLeft = true, boltRight = true;
    /**
     * Reload style: false = rifle (the hand rocks the magazine out around its front corner, AK), true = pistol (the
     * magazine slides straight out of the grip along its own axis and drops free, the hand brings a new one and pushes it
     * up into the grip; on an empty reload the slide is racked).
     */
    public boolean pistol = false;
    /** How far the bolt / slide goes back on each shot (share of its travel: 1 = all the way, pistols). */
    public float boltShot = 0.9f;
    /** The slide stays back after the last round (pistols) until the empty reload racks it. */
    public boolean slideLock = false;
    /** Tracers: the share of bullets that glow (0..1), their speed (blocks / second), length and width (blocks), colour and brightness. */
    public float tracerChance = 0.3f, tracerSpeed = 320, tracerLength = 5, tracerWidth = 0.05f, tracerGlow = 4;
    public float[] tracerColor = {1, 0.82f, 0.25f};
    /** Muzzle flash: a move of the flash from the muzzle point (model units; also where smoke and tracers start) and its size. */
    public float[] flashOffset = {0, 0, 0};
    public float flashSize = 1;

    // ---------------------------------------------------------------- model and files
    public Model3D model;
    public String error;
    public final List<String> warnings = new ArrayList<>();
    /** Bones of the model drawn as the gun body, the magazine and the bolt (null when the model has none). */
    public final List<Model3D.Bone> body = new ArrayList<>();
    public Model3D.Bone mag, slide;
    /** Hand points (model units), sight, muzzle, the point the magazine rocks around and the ejection port. */
    public float[] rag, lag, las, ram, sightPoint, sight2Point, muzzlePoint, magPivot, ejectPoint;
    /**
     * The bolt's limits: a point on the bolt at rest ("boltFront") and where that point is when the bolt is all the way
     * back ("boltBack"). The bolt moves only along that line and never past it (also the hand that pulls it).
     */
    public float[] boltFront, boltBack;
    /** From the limits: the direction the bolt moves back (unit, model space) and how far it can go (model units). */
    public final float[] boltDir = {0, 0, 1};
    public float boltTravel = 0.16f;
    /** The magazine's long axis (unit, model space, pointing out of the gun: the way a pistol magazine slides out) and its length. */
    public final float[] magAxis = {0, -1, 0};
    public float magLength = 0.3f;
    /** The points as found in the model (before "points" overrides from the json). */
    public final Map<String, float[]> autoPoints = new LinkedHashMap<>();
    /** Overrides from the json / the editor: rag, lag, las, ram, sight, muzzle, magPivot, eject, boltFront, boltBack. */
    public final Map<String, float[]> pointOverrides = new LinkedHashMap<>();
    public static final String[] POINTS = {"rag", "lag", "las", "ram", "sight", "sight2", "muzzle", "magPivot", "eject", "boltFront", "boltBack"};
    public final List<Path> shootSounds = new ArrayList<>();
    public final Map<String, Path> sounds = new HashMap<>();
    /** Numbered variants in the gun's folder, a random one per play: unattach1-5, attach1-5, slide1-2 (.ogg / .wav). */
    public final Map<String, List<Path>> variants = new HashMap<>();
    /** Variant groups: name and how many numbers are looked for (slide1 = bolt starts, slide2 = bolt ends). */
    public static final Object[][] VARIANTS = {{"unattach", 5}, {"attach", 5}, {"slide", 2}};
    public Path itemIcon, magIcon;
    /** Keys of the json this game version does not know: written back unchanged. */
    private final Map<String, Object> extra = new LinkedHashMap<>();

    static final Set<String> HELPERS = Set.of("rag", "lag", "las", "ram", "lam", "larm", "rarm");

    // ---------------------------------------------------------------- the parameter table (json keys, editor sliders)
    /** One number of the gun: json key (with an index for vectors), editor page, range, step. */
    public record Param(String key, int index, String label, String page, float min, float max, float step,
                        java.util.function.ToDoubleFunction<GunType> get, java.util.function.ObjDoubleConsumer<GunType> set) {
        public float value(GunType t) { return (float) get.applyAsDouble(t); }
        public void apply(GunType t, double v) { set.accept(t, Math.max(min, Math.min(max, v))); }
        public boolean vector() { return index >= 0; }
        /** A melee number or a melee pose's (their defaults depend on the gun: see {@link #defaultsFor}). */
        public boolean melee() {
            if (page.startsWith("Melee")) return true;
            for (int i = MELEE_POSE; i < POSES.length; i++) if (page.equals(posePage(i))) return true;
            return false;
        }
    }

    public static final List<Param> PARAMS = new ArrayList<>();
    public static final String[] PAGES = {"Stats", "Handling", "View", "Anim", "Arms", "FX", "Points"};
    /** View page sections: "View" (sizes, 3rd person) and "View:hip" ... "View:bolt" (one per pose). */
    public static String posePage(int i) { return "View:" + POSES[i]; }
    /** The melee numbers: "Melee" (reach, knockback, combo) and "Melee:jab" ... (times and damage of one kind). */
    public static String meleePage(int kind) { return "Melee:" + MELEE[kind]; }

    private static void p(String key, String label, String page, float min, float max, float step,
                          java.util.function.ToDoubleFunction<GunType> get, java.util.function.ObjDoubleConsumer<GunType> set) {
        PARAMS.add(new Param(key, -1, label, page, min, max, step, get, set));
    }

    private static void v(String key, String label, String page, float min, float max, float step, java.util.function.Function<GunType, float[]> arr) {
        String[] ax = {"X", "Y", "Z"};
        for (int i = 0; i < 3; i++) {
            final int k = i;
            PARAMS.add(new Param(key, i, label + " " + ax[i], page, min, max, step, t -> arr.apply(t)[k], (t, x) -> arr.apply(t)[k] = (float) x));
        }
    }

    private static void r(String key, String label, String page, float min, float max, float step, java.util.function.Function<GunType, float[]> arr) {
        String[] ax = {"yaw", "pitch", "roll"};
        for (int i = 0; i < 3; i++) {
            final int k = i;
            PARAMS.add(new Param(key, i, label + " " + ax[i], page, min, max, step, t -> arr.apply(t)[k], (t, x) -> arr.apply(t)[k] = (float) x));
        }
    }

    private static void c(String key, String label, String page, java.util.function.Function<GunType, float[]> arr) {
        String[] ax = {"R", "G", "B"};
        for (int i = 0; i < 3; i++) {
            final int k = i;
            PARAMS.add(new Param(key, i, label + " " + ax[i], page, 0, 1, 0.01f, t -> arr.apply(t)[k], (t, x) -> arr.apply(t)[k] = (float) x));
        }
    }

    static {
        p("fireRate", "Fire rate (rpm)", "Stats", 30, 1500, 10, t -> t.rpm, (t, x) -> t.rpm = (float) x);
        p("magazine", "Magazine", "Stats", 1, 200, 1, t -> t.magazine, (t, x) -> t.magazine = (int) Math.round(x));
        p("damage", "Damage", "Stats", 0, 60, 0.5f, t -> t.damage, (t, x) -> t.damage = (float) x);
        p("range", "Range", "Stats", 8, 500, 1, t -> t.range, (t, x) -> t.range = (float) x);
        p("headshotMultiplier", "Headshot x damage", "Stats", 1, 10, 0.1f, t -> t.headshotMultiplier, (t, x) -> t.headshotMultiplier = (float) x);
        p("reloadTime", "Reload (s)", "Stats", 0.3f, 8, 0.05f, t -> t.reloadTime, (t, x) -> t.reloadTime = (float) x);
        p("reloadEmptyTime", "Empty reload (s)", "Stats", 0.3f, 8, 0.05f, t -> t.reloadEmptyTime, (t, x) -> t.reloadEmptyTime = (float) x);
        p("volume", "Volume", "Stats", 0, 2, 0.05f, t -> t.volume, (t, x) -> t.volume = (float) x);

        p("moveSpeed", "Walk speed", "Handling", 0.1f, 1.5f, 0.01f, t -> t.moveSpeed, (t, x) -> t.moveSpeed = (float) x);
        p("aimMoveSpeed", "Aim walk speed", "Handling", 0.1f, 1.5f, 0.01f, t -> t.aimMoveSpeed, (t, x) -> t.aimMoveSpeed = (float) x);
        p("sprintSpeed", "Run speed", "Handling", 0.1f, 1.5f, 0.01f, t -> t.sprintSpeed, (t, x) -> t.sprintSpeed = (float) x);
        p("spreadHip", "Spread hip", "Handling", 0, 15, 0.05f, t -> t.spreadHip, (t, x) -> t.spreadHip = (float) x);
        p("spreadAim", "Spread aim", "Handling", 0, 5, 0.05f, t -> t.spreadAim, (t, x) -> t.spreadAim = (float) x);
        p("spreadPerShot", "Spread per shot", "Handling", 0, 3, 0.05f, t -> t.spreadShot, (t, x) -> t.spreadShot = (float) x);
        p("spreadMove", "Spread moving", "Handling", 0, 10, 0.1f, t -> t.spreadMove, (t, x) -> t.spreadMove = (float) x);
        p("recoil", "Recoil up", "Handling", 0, 5, 0.05f, t -> t.recoilUp, (t, x) -> t.recoilUp = (float) x);
        p("recoilSide", "Recoil side", "Handling", 0, 3, 0.05f, t -> t.recoilSide, (t, x) -> t.recoilSide = (float) x);
        p("aimZoom", "Aim zoom", "Handling", 1, 4, 0.05f, t -> t.aimZoom, (t, x) -> t.aimZoom = (float) x);
        p("aimTime", "Aim time (s)", "Handling", 0.05f, 1, 0.01f, t -> t.aimTime, (t, x) -> t.aimTime = (float) x);

        p("firstPersonScale", "1st person size", "View", 0.05f, 2, 0.005f, t -> t.firstPersonScale, (t, x) -> t.firstPersonScale = (float) x);
        for (int i = 0; i < POSES.length; i++) {
            final int k = i;
            String n = POSES[i], pg = posePage(i);
            boolean hip = i == 0;
            float turnMax = meleePose(i) ? 180 : 90;          // a strike turns the gun further
            if (i == 1) p("aimDistance", "Sight distance", pg, 0.08f, 1, 0.005f, t -> t.aimDistance, (t, x) -> t.aimDistance = (float) x);
            if (i == AIM2) {
                p("aim2Distance", "Sight distance", pg, 0.08f, 1, 0.005f, t -> t.aim2Distance, (t, x) -> t.aim2Distance = (float) x);
                p("aim2Zoom", "Zoom", pg, 1, 8, 0.05f, t -> t.aim2Zoom, (t, x) -> t.aim2Zoom = (float) x);
            }
            v(n + "Position", "All move", pg, hip ? -1.2f : -0.6f, hip ? 1.2f : 0.6f, 0.0025f, t -> t.poseMove[k]);
            r(n + "Rotation", "All turn", pg, -turnMax, turnMax, 0.5f, t -> t.poseTurn[k]);
            v(n + "GunPosition", "Gun move", pg, -0.6f, 0.6f, 0.0025f, t -> t.gunMove[k]);
            r(n + "GunRotation", "Gun turn", pg, -turnMax, turnMax, 0.5f, t -> t.gunTurn[k]);
            v(n + "ArmsPosition", "Arms move", pg, -0.6f, 0.6f, 0.0025f, t -> t.armsMove[k]);
            v(n + "RightArmPosition", "R arm move", pg, -0.6f, 0.6f, 0.0025f, t -> t.rArmMove[k]);
            r(n + "RightArmRotation", "R arm turn", pg, -180, 180, 1, t -> t.rArmTurn[k]);
            v(n + "LeftArmPosition", "L arm move", pg, -0.6f, 0.6f, 0.0025f, t -> t.lArmMove[k]);
            r(n + "LeftArmRotation", "L arm turn", pg, -180, 180, 1, t -> t.lArmTurn[k]);
        }
        p("meleeReach", "Reach (blocks)", "Melee", 1, 5, 0.05f, t -> t.meleeReach, (t, x) -> t.meleeReach = (float) x);
        p("meleeKnockback", "Knockback", "Melee", 0, 2, 0.05f, t -> t.meleeKnockback, (t, x) -> t.meleeKnockback = (float) x);
        p("meleeCombo", "Combo window (s)", "Melee", 0, 2, 0.05f, t -> t.meleeCombo, (t, x) -> t.meleeCombo = (float) x);
        String[] phase = {"Wind", "Strike", "Hold", "Back"}, phaseLabel = {"Wind-up time (s)", "Strike time (s)", "Hold time (s)", "Back time (s)"};
        for (int i = 0; i < MELEE.length; i++) {
            final int k = i;
            String pg = meleePage(i);
            p(MELEE[i] + "Damage", "Damage", pg, 0, 40, 0.5f, t -> t.meleeDamage[k], (t, x) -> t.meleeDamage[k] = (float) x);
            for (int j = 0; j < 4; j++) {
                final int q = j;
                p(MELEE[i] + phase[j] + "Time", phaseLabel[j], pg, j == 1 ? 0.02f : 0, 1.5f, 0.01f, t -> t.meleeTime[k][q], (t, x) -> t.meleeTime[k][q] = (float) x);
            }
        }
        p("thirdPersonScale", "3rd person size", "View", 0.05f, 2, 0.005f, t -> t.thirdPersonScale, (t, x) -> t.thirdPersonScale = (float) x);
        v("thirdPersonOffset", "3rd person move", "View", -0.6f, 0.6f, 0.0025f, t -> t.thirdPersonOffset);
        r("thirdPersonRotation", "3rd person turn", "View", -90, 90, 0.5f, t -> t.thirdPersonRotation);
        p("thirdPersonBackScale", "On the back: size (0 = as 3rd person)", "View", 0, 2, 0.005f, t -> t.thirdPersonBackScale, (t, x) -> t.thirdPersonBackScale = (float) x);
        v("thirdPersonBackOffset", "On the back: move", "View", -0.6f, 0.6f, 0.0025f, t -> t.thirdPersonBackOffset);
        r("thirdPersonBackRotation", "On the back: turn", "View", -180, 180, 0.5f, t -> t.thirdPersonBackRotation);

        // the magazine in the belt pouches (gun editor with a magazine in the hand)
        p("pouchScale", "Size (x of the 3rd person size)", "Pouch", 0.2f, 3, 0.01f, t -> t.pouchScale, (t, x) -> t.pouchScale = (float) x);
        String[] pm = {"Move out (from the belt middle)", "Move up", "Move forward"}, pr = {"Turn: yaw", "Turn: pitch", "Turn: roll"};
        for (int i = 0; i < 3; i++) {
            final int k = i;
            PARAMS.add(new Param("pouchOffset", i, pm[i], "Pouch", -0.3f, 0.3f, 0.0025f, t -> t.pouchOffset[k], (t, x) -> t.pouchOffset[k] = (float) x));
        }
        for (int i = 0; i < 3; i++) {
            final int k = i;
            PARAMS.add(new Param("pouchRotation", i, pr[i], "Pouch", -180, 180, 0.5f, t -> t.pouchRotation[k], (t, x) -> t.pouchRotation[k] = (float) x));
        }

        p("swayAmount", "Look sway", "Anim", 0, 3, 0.05f, t -> t.swayAmount, (t, x) -> t.swayAmount = (float) x);
        p("bobAmount", "Walk bob", "Anim", 0, 3, 0.05f, t -> t.bobAmount, (t, x) -> t.bobAmount = (float) x);
        p("kickBack", "Kick back", "Anim", 0, 3, 0.05f, t -> t.kickBack, (t, x) -> t.kickBack = (float) x);
        p("kickUp", "Kick up", "Anim", 0, 3, 0.05f, t -> t.kickUp, (t, x) -> t.kickUp = (float) x);
        p("kickRoll", "Kick roll", "Anim", 0, 3, 0.05f, t -> t.kickRoll, (t, x) -> t.kickRoll = (float) x);
        p("sprintTilt", "Sprint pose", "Anim", 0, 2, 0.05f, t -> t.sprintTilt, (t, x) -> t.sprintTilt = (float) x);
        p("reloadTilt", "Reload tilt", "Anim", 0, 2, 0.05f, t -> t.reloadTilt, (t, x) -> t.reloadTilt = (float) x);
        p("animSpeed", "Anim speed", "Anim", 0.25f, 4, 0.05f, t -> t.animSpeed, (t, x) -> t.animSpeed = (float) x);
        p("poseSmooth", "Pose smoothing", "Anim", 0, 4, 0.05f, t -> t.poseSmooth, (t, x) -> t.poseSmooth = (float) x);
        p("boltShot", "Bolt per shot", "Anim", 0, 1, 0.05f, t -> t.boltShot, (t, x) -> t.boltShot = (float) x);

        p("armSize", "Arm size", "Arms", 0.3f, 6, 0.05f, t -> t.armSize, (t, x) -> t.armSize = (float) x);
        p("armLength", "Arm length", "Arms", 0.3f, 3, 0.05f, t -> t.armLength, (t, x) -> t.armLength = (float) x);
        p("armThickness", "Arm thickness", "Arms", 0.3f, 3, 0.05f, t -> t.armThickness, (t, x) -> t.armThickness = (float) x);
        v("armRightOffset", "R arm move", "Arms", -0.5f, 0.5f, 0.0025f, t -> t.armRightOffset);
        r("armRightRotation", "R arm turn", "Arms", -180, 180, 1, t -> t.armRightRotation);
        v("shoulderRight", "R arm points to", "Arms", -2, 2, 0.01f, t -> t.shoulderRight);
        v("armLeftOffset", "L arm move", "Arms", -0.5f, 0.5f, 0.0025f, t -> t.armLeftOffset);
        r("armLeftRotation", "L arm turn", "Arms", -180, 180, 1, t -> t.armLeftRotation);
        v("shoulderLeft", "L arm points to", "Arms", -2, 2, 0.01f, t -> t.shoulderLeft);
        // the hand on the magazine / on the bolt: its own turn (gun editor: Arms -> Hands -> Mag / Bolt)
        r("magHandRotation", "Mag hand turn", "Hands", -180, 180, 1, t -> t.magHandRotation);
        r("boltHandRotation", "Bolt hand turn", "Hands", -180, 180, 1, t -> t.boltHandRotation);

        p("tracerChance", "Tracer chance", "FX", 0, 1, 0.01f, t -> t.tracerChance, (t, x) -> t.tracerChance = (float) x);
        p("tracerSpeed", "Tracer speed", "FX", 20, 1500, 5, t -> t.tracerSpeed, (t, x) -> t.tracerSpeed = (float) x);
        p("tracerLength", "Tracer length", "FX", 0.2f, 30, 0.1f, t -> t.tracerLength, (t, x) -> t.tracerLength = (float) x);
        p("tracerWidth", "Tracer width", "FX", 0.005f, 0.4f, 0.005f, t -> t.tracerWidth, (t, x) -> t.tracerWidth = (float) x);
        p("tracerGlow", "Tracer glow", "FX", 0.5f, 20, 0.1f, t -> t.tracerGlow, (t, x) -> t.tracerGlow = (float) x);
        c("tracerColor", "Tracer", "FX", t -> t.tracerColor);
        v("flashOffset", "Flash move", "FX", -0.5f, 0.5f, 0.0025f, t -> t.flashOffset);
        p("flashSize", "Flash size", "FX", 0, 4, 0.05f, t -> t.flashSize, (t, x) -> t.flashSize = (float) x);
    }

    /** A fresh gun with the default numbers (the editor's "default" on right click). */
    private static GunType defaults;

    public static synchronized GunType defaults() {
        if (defaults == null) defaults = new GunType("_defaults", null);
        return defaults;
    }

    private static GunType pistolDefaults;

    /** The defaults for a gun's style: a pistol's melee is a pistol whip, not a rifle's butt strokes. */
    public static synchronized GunType defaultsFor(GunType t) {
        if (t == null || !t.pistol) return defaults();
        if (pistolDefaults == null) {
            pistolDefaults = new GunType("_pistol_defaults", null);
            pistolDefaults.applyKeys(Json.obj(Json.parse(PISTOL_PRESET)));
            pistolDefaults.applyKeys(Json.obj(Json.parse(PISTOL_MELEE)));
        }
        return pistolDefaults;
    }

    GunType(String name, Path dir) {
        this.name = name;
        this.dir = dir;
        this.displayName = name.toUpperCase(Locale.ROOT);
        this.jsonFile = dir == null ? null : dir.resolve(name + ".json");
        if (dir != null) readFiles();
    }

    private Path find(String... names) {
        for (String n : names) {
            Path p = dir.resolve(n);
            if (Files.isRegularFile(p)) return p;
        }
        return null;
    }

    private void readFiles() {
        itemIcon = find(name + "_item.png", "item.png", "icon.png");
        magIcon = find(name + "_mag.png", "mag.png");
        if (magIcon == null) {
            try (var st = Files.list(dir)) {
                magIcon = st.filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith("mag.png")).findFirst().orElse(null);
            } catch (Exception ignored) {}
        }
        for (int i = 1; i <= 3; i++) {
            Path p = find("shoot" + i + ".ogg", "shoot" + i + ".wav", "sounds/shoot" + i + ".ogg", "sounds/shoot" + i + ".wav");
            if (p != null) shootSounds.add(p);
        }
        if (shootSounds.isEmpty()) { Path p = find("shoot.ogg", "shoot.wav"); if (p != null) shootSounds.add(p); }
        for (String ev : new String[]{"reload", "mag_out", "mag_in", "bolt", "dry", "equip", "aim", "melee", "melee_hit", "headshot"}) {
            Path p = find(ev + ".ogg", ev + ".wav", "sounds/" + ev + ".ogg", "sounds/" + ev + ".wav");
            if (p != null) sounds.put(ev, p);
        }
        variants.clear();
        for (Object[] v : VARIANTS) {
            List<Path> l = new ArrayList<>();
            for (int i = 1; i <= (Integer) v[1]; i++) {
                String n = v[0] + String.valueOf(i);
                Path p = find(n + ".ogg", n + ".wav", "sounds/" + n + ".ogg", "sounds/" + n + ".wav");
                if (p != null) l.add(p);
            }
            if (!l.isEmpty()) variants.put((String) v[0], l);
        }
        Path json = find(name + ".json", "gun.json");
        if (json != null) {
            try { settings(Json.obj(Json.parse(Files.readString(json, StandardCharsets.UTF_8)))); }
            catch (Exception e) { warnings.add(json.getFileName() + ": " + e.getMessage()); }
        }
    }

    private static final Set<String> SPECIAL = Set.of("name", "mode", "points", "sight", "muzzle", "magHand", "boltHand", "reloadStyle", "slideLock");

    private void settings(Map<String, Object> c) {
        displayName = String.valueOf(c.getOrDefault("name", displayName));
        auto = !"semi".equalsIgnoreCase(String.valueOf(c.getOrDefault("mode", "auto")));
        magLeft = !"right".equalsIgnoreCase(String.valueOf(c.getOrDefault("magHand", "left")));
        boltRight = !"left".equalsIgnoreCase(String.valueOf(c.getOrDefault("boltHand", "right")));
        pistol = "pistol".equalsIgnoreCase(String.valueOf(c.getOrDefault("reloadStyle", "rifle")));
        Object sl = c.get("slideLock");
        slideLock = sl instanceof Boolean b ? b : "true".equalsIgnoreCase(String.valueOf(sl));
        Set<String> known = new HashSet<>(SPECIAL);
        for (Param p : PARAMS) {
            known.add(p.key);
            Object o = c.get(p.key);
            if (p.vector()) {
                List<Object> l = Json.arr(o);
                if (l.size() > p.index) { double v = Json.num(l.get(p.index), Double.NaN); if (!Double.isNaN(v)) p.apply(this, v); }
            } else {
                double v = Json.num(o, Double.NaN);
                if (!Double.isNaN(v)) p.apply(this, v);
            }
        }
        if (!c.containsKey("reloadEmptyTime")) reloadEmptyTime = Math.max(reloadEmptyTime, reloadTime);
        // a pistol without melee numbers in its json (made before melee was added) gets the pistol's strikes
        if (pistol) {
            Map<String, Object> pm = new LinkedHashMap<>(Json.obj(Json.parse(PISTOL_MELEE)));
            pm.keySet().removeAll(c.keySet());
            applyKeys(pm);
        }
        // points: {"rag": [x, y, z], ...}; the old top level "sight" / "muzzle" keys still work
        Map<String, Object> pts = Json.obj(c.get("points"));
        for (String n : POINTS) { float[] v = vec(pts.get(n)); if (v != null) pointOverrides.put(n, v); }
        float[] s = vec(c.get("sight")), m = vec(c.get("muzzle"));
        if (s != null && !pointOverrides.containsKey("sight")) pointOverrides.put("sight", s);
        if (m != null && !pointOverrides.containsKey("muzzle")) pointOverrides.put("muzzle", m);
        for (var e : c.entrySet()) if (!known.contains(e.getKey())) extra.put(e.getKey(), e.getValue());
    }

    private static float[] vec(Object o) {
        List<Object> l = Json.arr(o);
        if (l.size() < 3) return null;
        return new float[]{(float) Json.num(l.get(0), 0), (float) Json.num(l.get(1), 0), (float) Json.num(l.get(2), 0)};
    }

    // ---------------------------------------------------------------- saving (gun editor)
    /** Writes &lt;name&gt;.json with every number, the point overrides and the unknown keys. */
    public void save() throws java.io.IOException {
        if (jsonFile == null) return;
        String json = toJson();
        Files.createDirectories(jsonFile.getParent());
        Path tmp = jsonFile.resolveSibling(jsonFile.getFileName() + ".tmp");
        Files.writeString(tmp, json, StandardCharsets.UTF_8);
        Files.move(tmp, jsonFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }

    /**
     * Puts back every setting from a {@link #toJson()} text (the gun editor's undo / redo): the numbers, the hands, the
     * point overrides and the unknown keys. The model stays loaded.
     */
    public void fromJson(String json) {
        Map<String, Object> c = Json.obj(Json.parse(json));
        pointOverrides.clear();
        extra.clear();
        settings(c);
        applyPoints();
    }

    /**
     * Gun editor presets: the poses (every pose's moves and turns, the arms' directions), the reload style, the hands of
     * the reload and the bolt of a rifle (the defaults, AK) or a pistol (two hands on the grip, the magazine out of the
     * grip, the slide racked over the top and locked back when empty). Sizes and points stay.
     */
    public static void posePreset(GunType t, boolean pistol) {
        GunType d = defaults();
        for (Param p : PARAMS) {
            String k = p.key;
            boolean pose = false;
            for (String n : POSES) if (k.startsWith(n) && (k.endsWith("Position") || k.endsWith("Rotation"))) pose = true;
            if (pose || k.startsWith("shoulder") || k.startsWith("armRight") || k.startsWith("armLeft") || k.equals("boltShot") || k.equals("reloadTilt") || k.equals("aimDistance"))
                p.apply(t, p.value(d));
        }
        t.pistol = false; t.slideLock = false; t.magLeft = true; t.boltRight = true;
        // the melee numbers too: the rifle's butt strokes or the pistol whip
        for (Param p : PARAMS) if (p.melee()) p.apply(t, p.value(d));
        if (pistol) { t.applyKeys(Json.obj(Json.parse(PISTOL_PRESET))); t.applyKeys(Json.obj(Json.parse(PISTOL_MELEE))); }
        t.applyPoints();
    }

    /** Applies only the keys present (numbers and the reload keys), leaving everything else as it is. */
    void applyKeys(Map<String, Object> c) {
        if (c.containsKey("magHand")) magLeft = !"right".equalsIgnoreCase(String.valueOf(c.get("magHand")));
        if (c.containsKey("boltHand")) boltRight = !"left".equalsIgnoreCase(String.valueOf(c.get("boltHand")));
        if (c.containsKey("reloadStyle")) pistol = "pistol".equalsIgnoreCase(String.valueOf(c.get("reloadStyle")));
        if (c.containsKey("slideLock")) slideLock = Boolean.TRUE.equals(c.get("slideLock")) || "true".equals(String.valueOf(c.get("slideLock")));
        for (Param p : PARAMS) {
            if (!c.containsKey(p.key)) continue;
            Object o = c.get(p.key);
            double v = p.vector() ? (Json.arr(o).size() > p.index ? Json.num(Json.arr(o).get(p.index), Double.NaN) : Double.NaN) : Json.num(o, Double.NaN);
            if (!Double.isNaN(v)) p.apply(this, v);
        }
    }

    /** The pistol preset (the FP9's poses, see game/guns/fp9). */
    static final String PISTOL_PRESET = """
            {"reloadStyle": "pistol", "slideLock": true, "magHand": "left", "boltHand": "left", "boltShot": 1, "aimDistance": 0.4,
             "shoulderRight": [0.35, -1, 0.3],
             "shoulderLeft": [-0.4, -1, 0.25],
             "hipPosition": [0.16, -0.2, -0.42],
             "hipRotation": [3, 0, 0],
             "sprintPosition": [-0.05, -0.04, 0.04],
             "sprintRotation": [25, -35, 15],
             "reloadPosition": [-0.1, 0.1, 0],
             "reloadRotation": [5, 20, -70],
             "reloadRightArmRotation": [25, 0, 0],
             "boltPosition": [-0.03, 0, -0.12],
             "boltRotation": [25, 4, 30],
             "boltLeftArmRotation": [-50, 0, 0]}
            """;

    /**
     * The pistol's melee (pistol presets, and a pistol whose json has no melee numbers), the left hand lets go each time:
     * a jab = a pistol whip across to the left, a swing = a backhand to the right, a smash = the grip hammered down.
     */
    static final String PISTOL_MELEE = """
            {"jabWindPosition": [0.08, 0.03, 0.06], "jabWindRotation": [-25, 8, 25],
             "jabHitPosition": [-0.22, 0.02, -0.12], "jabHitRotation": [55, -5, -70],
             "jabWindLeftArmPosition": [-0.06, -0.08, 0.04], "jabHitLeftArmPosition": [-0.12, -0.28, 0.12],
             "swingWindPosition": [-0.16, 0.04, 0.04], "swingWindRotation": [45, 5, -35],
             "swingHitPosition": [0.2, 0.02, -0.14], "swingHitRotation": [-55, -5, 70],
             "swingWindLeftArmPosition": [-0.04, -0.1, 0.04], "swingHitLeftArmPosition": [-0.14, -0.3, 0.12],
             "smashWindPosition": [0.02, 0.14, -0.02], "smashWindRotation": [0, 35, -10],
             "smashHitPosition": [-0.03, -0.1, -0.2], "smashHitRotation": [0, 5, 0],
             "smashWindLeftArmPosition": [-0.06, -0.12, 0.05], "smashHitLeftArmPosition": [-0.12, -0.3, 0.12],
             "jabWindTime": 0.08, "jabStrikeTime": 0.08, "jabHoldTime": 0.05, "jabBackTime": 0.24, "jabDamage": 3,
             "swingWindTime": 0.1, "swingStrikeTime": 0.08, "swingHoldTime": 0.05, "swingBackTime": 0.26, "swingDamage": 3.5,
             "smashWindTime": 0.14, "smashStrikeTime": 0.09, "smashHoldTime": 0.06, "smashBackTime": 0.3, "smashDamage": 5,
             "meleeReach": 2.2}
            """;

    /** Every setting as the json text {@link #save()} writes. */
    public String toJson() {
        StringBuilder sb = new StringBuilder("{\n");
        sb.append("  \"name\": ").append(quote(displayName)).append(",\n");
        sb.append("  \"mode\": \"").append(auto ? "auto" : "semi").append("\"");
        sb.append(",\n  \"magHand\": \"").append(magLeft ? "left" : "right").append("\"");
        sb.append(",\n  \"boltHand\": \"").append(boltRight ? "right" : "left").append("\"");
        sb.append(",\n  \"reloadStyle\": \"").append(pistol ? "pistol" : "rifle").append("\"");
        sb.append(",\n  \"slideLock\": ").append(slideLock);
        Set<String> done = new HashSet<>();
        for (Param p : PARAMS) {
            if (!done.add(p.key)) continue;
            sb.append(",\n  ").append(quote(p.key)).append(": ");
            if (p.vector()) {
                sb.append('[');
                for (Param q : PARAMS) if (q.key.equals(p.key)) sb.append(q.index > 0 ? ", " : "").append(num(q.value(this)));
                sb.append(']');
            } else sb.append(p.key.equals("magazine") ? String.valueOf(magazine) : num(p.value(this)));
        }
        if (!pointOverrides.isEmpty()) {
            sb.append(",\n  \"points\": {");
            boolean first = true;
            for (var e : pointOverrides.entrySet()) {
                float[] v = e.getValue();
                sb.append(first ? "\n" : ",\n").append("    ").append(quote(e.getKey())).append(": [").append(num(v[0])).append(", ").append(num(v[1])).append(", ").append(num(v[2])).append(']');
                first = false;
            }
            sb.append("\n  }");
        }
        for (var e : extra.entrySet()) sb.append(",\n  ").append(quote(e.getKey())).append(": ").append(jsonValue(e.getValue()));
        sb.append("\n}\n");
        return sb.toString();
    }

    static String num(float v) {
        if (v == Math.rint(v) && Math.abs(v) < 1e6) return String.valueOf((long) v);
        String s = String.format(Locale.ROOT, "%.4f", v);
        while (s.endsWith("0")) s = s.substring(0, s.length() - 1);
        return s.endsWith(".") ? s.substring(0, s.length() - 1) : s;
    }

    private static String quote(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\"";
    }

    private static String jsonValue(Object o) {
        if (o == null) return "null";
        if (o instanceof String s) return quote(s);
        if (o instanceof Number n) return num(n.floatValue());
        if (o instanceof Boolean b) return b.toString();
        if (o instanceof List<?> l) { StringJoiner j = new StringJoiner(", ", "[", "]"); for (Object x : l) j.add(jsonValue(x)); return j.toString(); }
        if (o instanceof Map<?, ?> m) { StringJoiner j = new StringJoiner(", ", "{", "}"); for (var e : m.entrySet()) j.add(quote(String.valueOf(e.getKey())) + ": " + jsonValue(e.getValue())); return j.toString(); }
        return quote(String.valueOf(o));
    }

    // ---------------------------------------------------------------- model
    /** Loads the model on first use (main thread: textures are made when it is drawn). Null when it does not load. */
    public Model3D model() {
        if (model != null || error != null || dir == null) return model;
        Path obj = find(name + ".obj");
        if (obj == null) {
            try (var st = Files.list(dir)) { obj = st.filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".obj")).findFirst().orElse(null); }
            catch (Exception ignored) {}
        }
        if (obj == null) { error = "no .obj model in " + dir.getFileName(); return null; }
        Model3D m = Models.loadFile(name + "/" + obj.getFileName(), obj);
        if (m == null || m.empty()) { error = m == null || m.error == null ? "empty model" : m.error; return null; }
        analyse(m);
        model = m;
        return m;
    }

    private float[] bodyMin, bodyMax;

    /** Finds the parts and the helper points of the model. */
    private void analyse(Model3D m) {
        bodyMin = new float[]{Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE};
        bodyMax = new float[]{-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE};
        Map<String, float[]> boxes = new HashMap<>();
        for (Model3D.Bone b : m.bones) {
            if (b.triangles() == 0) continue;
            String n = b.name.toLowerCase(Locale.ROOT).trim();
            float[] box = bounds(b);
            if (HELPERS.contains(n)) { boxes.put(n, box); continue; }
            if (n.equals("mag") || n.equals("magazine")) { mag = b; continue; }
            if (n.equals("slide") || n.equals("bolt")) { slide = b; continue; }
            body.add(b);
            for (int k = 0; k < 3; k++) { bodyMin[k] = Math.min(bodyMin[k], box[k]); bodyMax[k] = Math.max(bodyMax[k], box[k + 3]); }
        }
        if (body.isEmpty()) {           // no objects at all: the whole model is the gun
            Model3D.Bone all = new Model3D.Bone("gun", -1);
            for (Model3D.Group g : m.groups) all.groups.add(g);
            body.add(all);
            bodyMin = new float[]{m.minX, m.minY, m.minZ}; bodyMax = new float[]{m.maxX, m.maxY, m.maxZ};
        }
        float cx = (bodyMin[0] + bodyMax[0]) / 2;
        float[] rg = centre(boxes.get("rag"));
        if (rg == null) { rg = new float[]{cx, bodyMin[1] + (bodyMax[1] - bodyMin[1]) * 0.3f, bodyMin[2] + (bodyMax[2] - bodyMin[2]) * 0.7f}; warnings.add("no 'rag' point: the right hand guesses the grip"); }
        autoPoints.put("rag", rg);
        float[] lg = centre(boxes.get("lag"));
        if (lg == null) { lg = new float[]{cx, rg[1] + 0.1f, bodyMin[2] + (bodyMax[2] - bodyMin[2]) * 0.3f}; warnings.add("no 'lag' point: the left hand guesses the handguard"); }
        autoPoints.put("lag", lg);
        float[] ls = centre(boxes.get("las"));
        if (ls == null) ls = slide != null ? centre(bounds(slide)) : lg.clone();
        autoPoints.put("las", ls);
        float[] rm = centre(boxes.get("ram"));
        if (rm == null) rm = centre(boxes.get("lam"));
        if (rm == null) rm = mag != null ? centre(bounds(mag)) : rg.clone();
        autoPoints.put("ram", rm);
        // the magazine rocks around its front top corner (AK style: front first, then back until it clicks)
        if (mag != null) { float[] mb = bounds(mag); autoPoints.put("magPivot", new float[]{(mb[0] + mb[3]) / 2, mb[4], mb[2]}); }
        else autoPoints.put("magPivot", rm.clone());
        // ejection port: the bolt (casings fly out on its side)
        if (slide != null) { float[] sb = bounds(slide); float side = (sb[0] + sb[3]) / 2 >= cx ? sb[3] : sb[0]; autoPoints.put("eject", new float[]{side, (sb[1] + sb[4]) / 2, (sb[2] + sb[5]) / 2}); }
        else autoPoints.put("eject", new float[]{bodyMax[0], rg[1] + 0.2f, rg[2] - 0.25f});
        // the bolt's limits: the middle of its back end at rest, and that point moved back by the travel. A short bolt
        // (an AK's handle) goes back most of its own length; a long one (a pistol slide: more than 40 % of the gun) a fifth
        // of it, like a real slide
        float[] sb = slide != null ? bounds(slide) : new float[]{cx, rg[1] + 0.15f, rg[2] - 0.3f, cx, rg[1] + 0.2f, rg[2] - 0.2f};
        float len = sb[5] - sb[2], gunLen = bodyMax[2] - bodyMin[2];
        float travel = len > 0.4f * gunLen ? Math.max(0.03f, len * 0.2f) : clampF(len * 0.9f, 0.05f, 0.3f);
        float[] bf = {(sb[0] + sb[3]) / 2, (sb[1] + sb[4]) / 2, sb[5]};
        autoPoints.put("boltFront", bf);
        autoPoints.put("boltBack", new float[]{bf[0], bf[1], bf[2] + travel});
        magAxis(m);
        applyPoints();
    }

    private static float clampF(float v, float lo, float hi) { return Math.max(lo, Math.min(hi, v)); }

    /**
     * The magazine's long axis (principal axis of its vertices: works for a box turned in Blockbench, like a pistol
     * magazine following the angle of the grip), pointing out of the gun (down), and its length along it.
     */
    private void magAxis(Model3D m) {
        if (mag == null) return;
        double[] c = new double[3];
        int n = 0;
        for (Model3D.Group g : mag.groups) for (int i = 0; i < g.tris * 3; i++) { for (int k = 0; k < 3; k++) c[k] += g.v[i * 5 + k]; n++; }
        if (n == 0) return;
        for (int k = 0; k < 3; k++) c[k] /= n;
        double[][] cov = new double[3][3];
        for (Model3D.Group g : mag.groups) for (int i = 0; i < g.tris * 3; i++)
            for (int a = 0; a < 3; a++) for (int b = 0; b < 3; b++) cov[a][b] += (g.v[i * 5 + a] - c[a]) * (g.v[i * 5 + b] - c[b]);
        double[] v = {0, -1, 0};
        for (int it = 0; it < 60; it++) {         // power iteration: the direction the vertices spread most along
            double[] w = new double[3];
            for (int a = 0; a < 3; a++) for (int b = 0; b < 3; b++) w[a] += cov[a][b] * v[b];
            double l = Math.sqrt(w[0] * w[0] + w[1] * w[1] + w[2] * w[2]);
            if (l < 1e-12) return;
            for (int a = 0; a < 3; a++) v[a] = w[a] / l;
        }
        if (v[1] > 0) for (int a = 0; a < 3; a++) v[a] = -v[a];      // out of the gun = down
        double lo = Double.MAX_VALUE, hi = -Double.MAX_VALUE;
        for (Model3D.Group g : mag.groups) for (int i = 0; i < g.tris * 3; i++) {
            double d = 0;
            for (int a = 0; a < 3; a++) d += (g.v[i * 5 + a] - c[a]) * v[a];
            lo = Math.min(lo, d); hi = Math.max(hi, d);
        }
        for (int a = 0; a < 3; a++) magAxis[a] = (float) v[a];
        magLength = (float) Math.max(0.02, hi - lo);
    }

    /** A point of the bolt (model units) moved back by 'slide' along the bolt's line (into 'out'). */
    public float[] boltPoint(float[] p, float slide, float[] out) {
        for (int k = 0; k < 3; k++) out[k] = p[k] + boltDir[k] * slide;
        return out;
    }

    /** Recomputes the working points from the automatic ones and the overrides (after the editor changed one). */
    public void applyPoints() {
        if (bodyMin == null) return;
        rag = pt("rag"); lag = pt("lag"); las = pt("las"); ram = pt("ram"); magPivot = pt("magPivot"); ejectPoint = pt("eject");
        // the hand on the magazine holds it on its own side: the model's point is mirrored across the magazine for the
        // other hand (unless the point was set by hand)
        if (!pointOverrides.containsKey("ram") && mag != null) {
            float[] mb = bounds(mag);
            float cx = (mb[0] + mb[3]) / 2;
            if (magLeft ? ram[0] > cx : ram[0] < cx) ram[0] = 2 * cx - ram[0];
        }
        float cx = (bodyMin[0] + bodyMax[0]) / 2;
        // sight: the top of the gun above the grip area (where iron sights / a rail are)
        float top = -Float.MAX_VALUE;
        for (Model3D.Bone b : body) for (Model3D.Group g : b.groups)
            for (int i = 0; i < g.tris * 3; i++) {
                float x = g.v[i * 5], y = g.v[i * 5 + 1], z = g.v[i * 5 + 2];
                if (z > rag[2] - 0.7f && z < rag[2] + 0.15f && Math.abs(x - cx) < 0.2f) top = Math.max(top, y);
            }
        if (top == -Float.MAX_VALUE) top = bodyMax[1];
        autoPoints.put("sight", new float[]{cx, top + 0.01f, rag[2]});
        // muzzle: the front end of the gun, at the height of the vertices there
        float sy = 0; int n = 0;
        for (Model3D.Bone b : body) for (Model3D.Group g : b.groups)
            for (int i = 0; i < g.tris * 3; i++) if (g.v[i * 5 + 2] < bodyMin[2] + 0.05f) { sy += g.v[i * 5 + 1]; n++; }
        autoPoints.put("muzzle", new float[]{cx, n == 0 ? top - 0.05f : sy / n, bodyMin[2]});
        sightPoint = pt("sight");
        // the second aim's sight: the first one unless it is set (a canted sight on the side, a magnifier behind...)
        autoPoints.put("sight2", sightPoint.clone());
        sight2Point = pt("sight2");
        muzzlePoint = pt("muzzle");
        // the bolt's line: front (at rest) -> back (all the way back); only "boltBack" set: the front stays automatic
        boltFront = pt("boltFront"); boltBack = pt("boltBack");
        float dx = boltBack[0] - boltFront[0], dy = boltBack[1] - boltFront[1], dz = boltBack[2] - boltFront[2];
        float l = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (l < 1e-4f) { boltDir[0] = 0; boltDir[1] = 0; boltDir[2] = 1; boltTravel = 0; }
        else { boltDir[0] = dx / l; boltDir[1] = dy / l; boltDir[2] = dz / l; boltTravel = l; }
    }

    private float[] pt(String n) {
        float[] o = pointOverrides.get(n);
        return (o != null ? o : autoPoints.get(n)).clone();
    }

    /** Size of the gun (model units): the editor's slider range for points. */
    public float[] bodyBounds() { return bodyMin == null ? null : new float[]{bodyMin[0], bodyMin[1], bodyMin[2], bodyMax[0], bodyMax[1], bodyMax[2]}; }

    private static float[] centre(float[] box) {
        return box == null ? null : new float[]{(box[0] + box[3]) / 2, (box[1] + box[4]) / 2, (box[2] + box[5]) / 2};
    }

    public static float[] bounds(Model3D.Bone b) {
        float[] r = {Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE};
        for (Model3D.Group g : b.groups)
            for (int i = 0; i < g.tris * 3; i++)
                for (int k = 0; k < 3; k++) { float v = g.v[i * 5 + k]; r[k] = Math.min(r[k], v); r[k + 3] = Math.max(r[k + 3], v); }
        return r;
    }
}
