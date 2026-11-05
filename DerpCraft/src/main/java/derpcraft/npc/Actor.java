package derpcraft.npc;

import derpcraft.block.Block;
import derpcraft.block.FunctionalBlocks;
import derpcraft.game.Player;
import derpcraft.item.ItemStack;
import derpcraft.item.Items;
import derpcraft.render.EntityRenderer;
import derpcraft.world.Chunk;
import derpcraft.world.World;

import java.util.*;

/**
 * An NPC / actor: a player-shaped figure with a skin, an AI (behaviour, faction, combat, following, doors and
 * block breaking) and recorded takes for named scenes. When a scene starts the actor walks to its mark, plays the
 * take, and afterwards goes back to its normal AI from wherever the scene left it. Uses the player physics.
 */
public final class Actor {
    public enum Behavior {
        STAND("Stand still"), LOOK("Look at player"), WANDER("Wander around"), FOLLOW("Follow player"),
        PATROL("Patrol points"), SEAT("Sit on a seat"), WATCH("Watch an actor"), RIDE("Ride"), DRIVE("Drive a vehicle");
        public final String label;
        Behavior(String label) { this.label = label; }
    }

    public enum Pose {
        NORMAL("Standing"), SIT("Sitting"), SNEAK("Sneaking"), WAVE("Waving");
        public final String label;
        Pose(String label) { this.label = label; }
    }

    public enum Faction {
        AGGRESSIVE("Aggressive"), NEUTRAL("Neutral"), FRIENDLY("Friendly");
        public final String label;
        Faction(String label) { this.label = label; }
    }

    /** What a neutral NPC does after being hit. */
    public enum OnHit {
        FIGHT("Fights back"), FLEE("Runs away");
        public final String label;
        OnHit(String label) { this.label = label; }
    }

    /** What it does when someone attacks the player. */
    public enum Guard {
        IGNORE("Ignores attacks"), DEFEND("Defends player"), FLEE("Flees from fights");
        public final String label;
        Guard(String label) { this.label = label; }
    }

    /** Scene state: normal AI, walking to the scene's start mark, waiting on the mark, playing the take. */
    public enum Mode { AI, GOTO_MARK, ON_MARK, PLAYING }

    public final int id;
    public String name;
    public String skin = "Steve";
    public Behavior behavior = Behavior.LOOK;
    public Pose pose = Pose.NORMAL;
    public ItemStack held;
    public boolean showName = true;
    public double homeX, homeY, homeZ;
    public float homeYaw;

    // ---------------------------------------------------------------- AI settings
    public Faction faction = Faction.NEUTRAL;
    public OnHit onHit = OnHit.FIGHT;
    public Guard guard = Guard.IGNORE;
    /** Aggressive NPCs attack what comes this close (blocks). */
    public float aggroRange = 10;
    /** FOLLOW: start following when the player is farther than 'followStart', stop at 'followStop'. */
    public float followStart = 6, followStop = 2.5f;
    public boolean openDoors = true, breakBlocks;
    public float maxHealth = 20, health = 20;
    /** Damage per hit; 0 = from the held item (fist = 1). */
    public float damage;
    public boolean respawn = true;
    /** Scene group the NPC belongs to (its scene is the group's scene). */
    public String group = "main";
    /** Off = taken out of the world (kept, can be switched back on). */
    public boolean enabled = true;
    /** Off = keeps its AI but does not play in scenes. */
    public boolean sceneEnabled = true;

    // ---------------------------------------------------------------- body / stats (Stats page of the NPC Hub)
    /** Walking and running speed (1 = like the player). */
    public float speed = 1;
    /** LOOK / FOLLOW: how close the player must be for the head to turn (blocks). */
    public float lookRange = 10;
    /** WANDER: how far from its spot it walks (blocks). */
    public float wanderRadius = 8;
    /** Ticks between two melee hits (20 = one a second). */
    public int attackDelay = 12;
    /** Health points healed per second when not hurt for 5 seconds (0 = none). */
    public float regen;
    /** Cannot be hurt at all (by the player, NPCs, creatures, cars, falls). */
    public boolean invulnerable;
    /** Hits push it back. */
    public boolean knockback = true;
    /** The player and other NPCs push it aside when they walk into it. */
    public boolean pushable = true;
    /** Falls of more than 3 blocks hurt. */
    public boolean takesFallDamage = true;
    /** Seconds after death before it stands up at home again (with 'respawn' on). */
    public float respawnSeconds = 5;
    /** Helps its faction: attacks whoever attacks an NPC of its faction within its attack range. */
    public boolean helpAllies;
    /** Following the player: attacks whatever the player attacks (NPCs of any faction, creatures). */
    public boolean assistPlayer;
    /** Name tag colour: 0 = auto (red when aggressive), then {@link #NAME_COLORS}. */
    public int nameColor;
    public static final String[] NAME_COLOR_NAMES = {"Auto", "White", "Yellow", "Green", "Aqua", "Gold", "Pink", "Gray"};
    public static final int[] NAME_COLORS = {0, 0xFFFFFF, 0xFFFF55, 0x55FF55, 0x55FFFF, 0xFFAA00, 0xFF77DD, 0xAAAAAA};

    /** Copies every setting (look, AI, stats; not the takes, the place or the group) from another NPC. */
    public void copySettingsFrom(Actor a) {
        skin = a.skin; behavior = a.behavior; pose = a.pose; held = a.held == null ? null : a.held.copy(1); showName = a.showName;
        faction = a.faction; onHit = a.onHit; guard = a.guard; aggroRange = a.aggroRange; followStart = a.followStart; followStop = a.followStop;
        openDoors = a.openDoors; breakBlocks = a.breakBlocks; maxHealth = a.maxHealth; health = a.maxHealth; damage = a.damage; respawn = a.respawn;
        enabled = a.enabled; sceneEnabled = a.sceneEnabled;
        customPose = a.customPose; emotion = a.emotion; blink = a.blink; offhand = a.offhand == null ? null : a.offhand.copy(1); scale = a.scale;
        attachments.clear();
        for (Attachment at : a.attachments) attachments.add(at.copy());
        for (int i = 0; i < 4; i++) armor[i] = a.armor[i] == null ? null : a.armor[i].copy(1);
        waypoints.clear();
        for (double[] w : a.waypoints) waypoints.add(w.clone());
        patrolLoop = a.patrolLoop; patrolWait = a.patrolWait; seat = a.seat == null ? null : a.seat.clone();
        speed = a.speed; lookRange = a.lookRange; wanderRadius = a.wanderRadius; attackDelay = a.attackDelay; regen = a.regen;
        invulnerable = a.invulnerable; knockback = a.knockback; pushable = a.pushable; takesFallDamage = a.takesFallDamage; nameColor = a.nameColor;
        respawnSeconds = a.respawnSeconds; helpAllies = a.helpAllies; assistPlayer = a.assistPlayer;
    }

    /** The stats as one line for actors.txt. */
    String statsLine() {
        return speed + "," + lookRange + "," + wanderRadius + "," + attackDelay + "," + regen + "," + nameColor + ","
                + invulnerable + "," + knockback + "," + pushable + "," + takesFallDamage + "," + waiting + "," + respawnSeconds + "," + helpAllies + "," + assistPlayer;
    }

    void statsFrom(String line) {
        if (line == null || line.isBlank()) return;
        String[] v = line.split(",");
        try {
            speed = Math.max(0.1f, Math.min(4, Float.parseFloat(v[0])));
            lookRange = Float.parseFloat(v[1]);
            wanderRadius = Math.max(1, Float.parseFloat(v[2]));
            attackDelay = Math.max(2, Integer.parseInt(v[3]));
            regen = Math.max(0, Float.parseFloat(v[4]));
            nameColor = Math.floorMod(Integer.parseInt(v[5]), NAME_COLORS.length);
            invulnerable = Boolean.parseBoolean(v[6]);
            knockback = Boolean.parseBoolean(v[7]);
            pushable = Boolean.parseBoolean(v[8]);
            takesFallDamage = Boolean.parseBoolean(v[9]);
            waiting = v.length > 10 && Boolean.parseBoolean(v[10]);
            if (v.length > 13) {
                respawnSeconds = Math.max(0.5f, Float.parseFloat(v[11]));
                helpAllies = Boolean.parseBoolean(v[12]);
                assistPlayer = Boolean.parseBoolean(v[13]);
            }
        } catch (RuntimeException ignored) {}       // older / damaged line: the rest keeps its defaults
    }

    /** Pose from the pose library ("" = none; overrides 'pose'). */
    public String customPose = "";
    /** Face (see {@link Emotions}) and automatic blinking. */
    public int emotion;
    public boolean blink = true;
    public ItemStack offhand;
    /** Helmet (any item or block = hat), chestplate, leggings, boots. */
    public final ItemStack[] armor = new ItemStack[4];
    /** Size (1 = normal). */
    public float scale = 1;
    /** 3D models worn on the body (pose editor, Models page). */
    public final java.util.List<Attachment> attachments = new java.util.ArrayList<>();
    /** PATROL: points {x, y, z}; loop or back and forth; ticks to wait at each. */
    public final List<double[]> waypoints = new ArrayList<>();
    public boolean patrolLoop = true;
    public int patrolWait = 20;
    /** SEAT: the block it sits on. */
    public int[] seat;
    /** WATCH: actor id to look at; RIDE: actor id to ride (or creature id when rideCreature). */
    public int watchId = -1, rideId = -1;
    public boolean rideCreature;
    /** DRIVE: the vehicle (id) and the wanted seat (-1 = the driver's); the route is the patrol points. */
    public int vehicleId = -1, vehicleSeat = -1;
    /** DRIVE: the patrol point driven to and the direction along a back-and-forth route. */
    public int driveWp, driveDir = 1;
    /** FOLLOW: the vehicle it rides along in with the player (-1 = none). */
    public int rideAlong = -1;
    /** FOLLOW: told to wait (Tab while looking at it): it stays where it is until Tab again. */
    public boolean waiting;
    /** Patrolling a small area because of Tab + 1; Tab gives back the behaviour and patrol points it had before. */
    public boolean tabPatrol;
    private Behavior savedBehavior;
    private final List<double[]> savedWaypoints = new ArrayList<>();
    private boolean savedLoop;
    private int savedWait;

    /** The vehicle it sits in by its own AI (driving or riding along with the player), -1 = none. */
    public int seatedVehicleId() { return rideAlong >= 0 ? rideAlong : vehicleId; }

    /** No damage for this many ticks (getting out of a vehicle). */
    public void exitGrace(int ticks) { noHurtUntil = Math.max(noHurtUntil, age + ticks); }
    private long noHurtUntil = -1;

    /** Behaviour and patrol points to save (a Tab + 1 patrol is only for now: the ones it had before are kept). */
    public Behavior fileBehavior() { return tabPatrol && savedBehavior != null ? savedBehavior : behavior; }
    public List<double[]> fileWaypoints() { return tabPatrol ? savedWaypoints : waypoints; }
    public boolean filePatrolLoop() { return tabPatrol ? savedLoop : patrolLoop; }
    public int filePatrolWait() { return tabPatrol ? savedWait : patrolWait; }

    /**
     * Tab + 1: patrols a small area around where it stands (4 points 3-6 blocks away, on the ground, in a loop). The
     * behaviour it had is kept: {@link #endTabPatrol} gives it back.
     */
    public void startTabPatrol(World w, Random r) {
        if (!tabPatrol) {
            savedBehavior = behavior; savedLoop = patrolLoop; savedWait = patrolWait;
            savedWaypoints.clear();
            for (double[] p : waypoints) savedWaypoints.add(p.clone());
        }
        tabPatrol = true;
        waiting = false;
        waypoints.clear();
        int by = (int) Math.floor(body.y + 0.01);
        for (int k = 0; k < 4; k++) {
            for (int tryNo = 0; tryNo < 8; tryNo++) {
                double a = (k + r.nextDouble() * 0.7) * Math.PI / 2, rad = 3 + r.nextDouble() * 3;
                double x = body.x + Math.cos(a) * rad, z = body.z + Math.sin(a) * rad;
                int bx = (int) Math.floor(x), bz = (int) Math.floor(z);
                int found = Integer.MIN_VALUE;
                for (int dy = 0; dy <= 4 && found == Integer.MIN_VALUE; dy++)
                    for (int sg = -1; sg <= 1; sg += 2) {
                        int y = by + dy * sg;
                        if (w.getBlock(bx, y - 1, bz).solid && !w.getBlock(bx, y, bz).solid && !w.getBlock(bx, y + 1, bz).solid) { found = y; break; }
                    }
                if (found != Integer.MIN_VALUE) { waypoints.add(new double[]{bx + 0.5, found, bz + 0.5}); break; }
            }
        }
        if (waypoints.isEmpty()) waypoints.add(new double[]{body.x, body.y, body.z});
        behavior = Behavior.PATROL;
        patrolLoop = true;
        patrolWait = 20 + r.nextInt(40);
        wpIndex = 0;
        setHome();
    }

    /** Ends a Tab + 1 patrol: the behaviour and patrol points from before (following the player again, if it was). */
    public void endTabPatrol() {
        if (!tabPatrol) return;
        tabPatrol = false;
        behavior = savedBehavior == null ? Behavior.FOLLOW : savedBehavior;
        waypoints.clear();
        waypoints.addAll(savedWaypoints);
        patrolLoop = savedLoop; patrolWait = savedWait;
        wpIndex = 0;
    }

    // ---------------------------------------------------------------- takes: scene name -> track (several takes)
    final Map<String, Track> tracks = new LinkedHashMap<>();

    /** The active take's recording for a scene, or null. */
    public Recording take(String scene) { Track t = track(scene); return t == null ? null : t.rec(); }

    /** The actor's part in a scene (group names are not case sensitive). */
    public Track track(String scene) {
        if (scene == null) return null;
        Track t = tracks.get(scene);
        if (t != null) return t;
        for (var e : tracks.entrySet()) if (e.getKey().equalsIgnoreCase(scene)) return e.getValue();
        return null;
    }

    Track trackOrNew(String scene) {
        Track t = track(scene);
        if (t == null) { t = new Track(); tracks.put(scene, t); }
        return t;
    }

    Track removeTrack(String scene) {
        if (scene == null) return null;
        for (var it = tracks.entrySet().iterator(); it.hasNext(); ) {
            var e = it.next();
            if (e.getKey().equalsIgnoreCase(scene)) { it.remove(); return e.getValue(); }
        }
        return null;
    }
    public Set<String> scenes() { Set<String> s = new LinkedHashSet<>(); for (var e : tracks.entrySet()) if (e.getValue().rec() != null) s.add(e.getKey()); return s; }
    public boolean hasTakes() { return !scenes().isEmpty(); }

    // ---------------------------------------------------------------- runtime
    final Player body = new Player();
    float headYaw, prevHeadYaw, prevPitch;
    private float limbSwing, prevLimbSwing, limbAmount, prevLimbAmount, bodyYaw, prevBodyYaw;
    private int swingTicks = -2;
    private boolean recSneak, recSit, recWave;
    private ItemStack recHeld;
    /** A take: the gun on the back (Left Alt + F when recorded) and the ticks the arm has been reaching for it (-1 = not). */
    private ItemStack recBack;
    /** The magazines in the belt pouches of the take being played (parsed from the frame's text only when it changes). */
    private final ItemStack[] recPouches = new ItemStack[6];
    private String recPouchText = "";
    private int recReach = -1;
    /** A take: the posture (smoothed like the player's, see Stance) and the fist fighting guard / punches. */
    private final float[] recVis = derpcraft.game.Stance.STAND.params.clone(), prevRecVis = derpcraft.game.Stance.STAND.params.clone();
    private boolean recVisUsed;
    private float recFists, recPunchL, recPunchR, prevRecFists, prevRecPunchL, prevRecPunchR;
    private int lastHurtAge = -1000;
    String recPose = "";
    int recEmotion, lastSrc = -1;
    /** Timeline pose keys at the current tick: blend from 'keyFrom' to 'keyTo' by 'keyT'. */
    String keyFrom, keyTo;
    float keyT;
    int wpIndex, wpDir = 1, wpWait;
    Mode mode = Mode.AI;
    /** The track / take being played (or walked to). */
    Track sceneTrack;
    Track.Take sceneTake;
    double markX, markY, markZ;
    float markYaw;
    private double wanderX, wanderZ;
    private int wanderWait;
    long age;
    // combat
    Actor targetActor;
    /** A creature (mod mob) this NPC fights. */
    public derpcraft.entity.Mob targetMob;
    boolean targetPlayer;
    long guardReact = -1;
    int angerTicks, fleeTicks, attackCooldown, hurtTime, deathTime, respawnTimer;
    double fleeFromX, fleeFromZ;
    // guns: rounds in the magazine (-1 = full, not used yet), the reload in progress, the burst, the shot just fired
    int gunAmmo = -1, gunReloadLeft, gunReloadTotal, gunReloadStage, gunBurst, gunPause, gunSeen;
    boolean gunReloadEmpty, gunMagDropped;
    double gunNext;
    float gunKick, prevGunKick;
    private derpcraft.gun.GunType gunReloading;
    /** A melee strike with the gun (a take replays the player's): its kind (-1 = none) and ticks since it started. */
    int gunMelee = -1, gunMeleeTicks;
    // navigation
    private boolean following;
    private int stuckTicks;
    private double progressX, progressZ;
    private int breakX, breakY = -1, breakZ;
    private float breakProgress;
    private final List<int[]> openedDoors = new ArrayList<>();

    Actor(int id, String name) { this.id = id; this.name = name; }

    /** The physics body (position, size): vehicles push and carry NPCs. */
    public Player body() { return body; }

    public double x() { return body.x; }
    public double y() { return body.y; }
    public double z() { return body.z; }
    public float yaw() { return body.yaw; }
    public boolean dead() { return deathTime > 0; }

    // ---------------------------------------------------------------- body physics: ragdoll, impacts, headshots
    /** The falling body after death (null while alive, and until the first tick of death). */
    Ragdoll ragdoll;
    /** A push that the next damage brings (a bullet, a car): {hit x, y, z, push x, y, z (blocks / tick)} and the tick it was given. */
    private double[] pendingPush;
    private long pendingAge;

    /** Whether height 'y' (world) is in the head (the top quarter of its box). */
    public boolean headAt(double y) { return !dead() && y >= body.y + hitH * 0.76; }

    /**
     * A push at a point (a bullet, a car): a body lying dead is moved by it at once; a living one remembers it for a
     * moment, so when the damage that comes with it kills, the body falls away from the hit.
     */
    public void impact(double hx, double hy, double hz, double ix, double iy, double iz) {
        if (ragdoll != null) { ragdoll.push(hx, hy, hz, ix, iy, iz); return; }
        pendingPush = new double[]{hx, hy, hz, ix, iy, iz};
        pendingAge = age;
    }

    /** Whether a point of the world is inside a solid block's collision boxes (the ragdoll's ground). */
    static boolean solidAt(World w, double x, double y, double z) { return derpcraft.world.Collision.solidAt(w, x, y, z); }

    /** Starts the ragdoll from where the body stands, moving as it moves, plus the push that killed it (or a fall away from 'fromX/Z'). */
    private void startRagdoll(double fromX, double fromZ) {
        double vx = body.x - body.px + body.mx * 0.5, vy = Math.max(0, body.y - body.py), vz = body.z - body.pz + body.mz * 0.5;
        ragdoll = Ragdoll.standing(body.x, body.y, body.z, bodyYaw, scale, vx, vy, vz, 0);
        double[] c = ragdoll.centre();
        if (pendingPush != null && age - pendingAge <= 2) ragdoll.push(pendingPush[0], pendingPush[1], pendingPush[2], pendingPush[3], pendingPush[4], pendingPush[5]);
        else {
            double kx = body.x - fromX, kz = body.z - fromZ, kl = Math.sqrt(kx * kx + kz * kz);
            if (kl < 1e-4) { double yr = Math.toRadians(bodyYaw + (id % 2 == 0 ? 0 : 180)); kx = -Math.sin(yr); kz = Math.cos(yr); kl = 1; }
            ragdoll.push(c[0], c[1] + 0.3 * scale, c[2], kx / kl * 0.12, 0.02, kz / kl * 0.12);
        }
        pendingPush = null;
    }

    /**
     * Run into by a car moving (vx, vz) blocks per tick ('speed' its length): an actor in a take leaves it (the hit is
     * not in the recording), the body is thrown along the car's way and up, and takes 'damage' ("vehicle"). False while
     * the last hit is a moment ago.
     */
    public boolean hitByVehicle(NpcManager m, float damage, double vx, double vz, double speed) {
        if (dead() || age - vehicleHitAge < 10 || age < safeUntil) return false;
        vehicleHitAge = age;
        if (mode != Mode.AI) toAI();
        double[] c = {body.x, body.y + hitH * 0.45, body.z};
        double up = 0.12 + speed * 0.35;
        impact(c[0], c[1], c[2], vx * 1.15, up, vz * 1.15);
        body.mx += vx * 0.9; body.mz += vz * 0.9; body.my = Math.max(body.my, up);
        hurt(m, damage, null, false, null, "vehicle");
        return true;
    }

    private long vehicleHitAge = -100;
    /** Until this age (ticks) the actor cannot be hurt by cars and falls (getting into or out of a vehicle: no deaths from the seat teleport). */
    private long safeUntil = -1;

    public void grace(int ticks) { safeUntil = Math.max(safeUntil, age + ticks); body.landed = 0; body.fallDistance = 0; }

    public boolean inGrace() { return age < safeUntil; }

    /** Whether it lies dead as a falling body (cars push it along). */
    public boolean lyingBody() { return ragdoll != null; }

    /** Pushes the points of the fallen body for which 'touch' (world x, y, z) is true by (ix, iy, iz) blocks per tick. */
    public void pushBodyBy(java.util.function.Predicate<double[]> touch, double ix, double iy, double iz) {
        if (ragdoll == null) return;
        double[] pt = new double[3];
        boolean any = false;
        for (int i = 0; i < Ragdoll.N; i++) {
            pt[0] = ragdoll.p[i * 3]; pt[1] = ragdoll.p[i * 3 + 1]; pt[2] = ragdoll.p[i * 3 + 2];
            if (!touch.test(pt)) continue;
            ragdoll.pushPoint(i, ix, iy, iz);
            any = true;
        }
        if (any) ragdoll.asleep = false;
    }

    /** Fall damage like the player's (3 blocks free, then a point per block). */
    private void fallDamage(NpcManager m) {
        float d = body.landed;
        body.landed = 0;
        if (age < safeUntil) { body.fallDistance = 0; return; }
        if (d > 3 && mode == Mode.AI && !body.inWater && takesFallDamage) hurt(m, (float) Math.ceil(d - 3), null, false, null, "fall");
    }
    public Mode mode() { return mode; }

    public void place(double x, double y, double z, float yaw) {
        body.setPos(x, y, z);
        body.yaw = yaw; body.pitch = 0;
        headYaw = prevHeadYaw = bodyYaw = prevBodyYaw = yaw;
        prevPitch = 0;
        body.flying = false;
        path = null; pathAge = 100;
    }

    public void setHome() { homeX = body.x; homeY = body.y; homeZ = body.z; homeYaw = body.yaw; }

    /** Back to normal AI where it stands. */
    void toAI() {
        mode = Mode.AI;
        sceneTake = null;
        sceneTrack = null;
        recSneak = recSit = recWave = false;
        recHeld = null;
        recBack = null; recReach = -1;
        java.util.Arrays.fill(recPouches, null); recPouchText = "";
        recPose = ""; recEmotion = 0; lastSrc = -1;
        recVisUsed = false; recFists = recPunchL = recPunchR = prevRecFists = prevRecPunchL = prevRecPunchR = 0;
        keyFrom = keyTo = null;
        setHome();
    }

    /** Forgets targets, anger and fear. */
    public void calm() { targetActor = null; targetMob = null; targetPlayer = false; angerTicks = 0; fleeTicks = 0; }

    public void revive() {
        ragdoll = null; pendingPush = null;
        deathTime = 0; respawnTimer = 0; health = maxHealth; hurtTime = 0;
        targetActor = null; targetPlayer = false; angerTicks = fleeTicks = 0;
    }

    // ================================================================ scene playback
    /** Plays one frame of a take (already timed, moved and turned). */
    void playFrame(Recording.Frame f, boolean swing) {
        if (f == null) return;
        age++;
        if (hurtTime > 0) hurtTime--;
        body.px = body.x; body.py = body.y; body.pz = body.z;
        prevHeadYaw = headYaw; prevPitch = body.pitch;
        body.x = f.x; body.y = f.y; body.z = f.z;
        body.yaw = f.yaw; body.pitch = f.pitch;
        body.mx = body.my = body.mz = 0;
        headYaw = f.yaw;
        applyFlags(f);
        if (swing) swingTicks = -1;
        animate();
    }

    void applyFlags(Recording.Frame f) {
        recSneak = (f.flags & Recording.FLAG_SNEAK) != 0;
        recSit = (f.flags & Recording.FLAG_SIT) != 0;
        recWave = (f.flags & Recording.FLAG_WAVE) != 0;
        body.flying = (f.flags & Recording.FLAG_FLYING) != 0;
        recPose = f.pose == null ? "" : f.pose;
        recEmotion = f.emotion;
        if (recHeld == null || !recHeld.item.name.equals(f.held)) {
            var it = f.held.isEmpty() ? null : Items.byName(f.held);
            recHeld = it == null ? null : new ItemStack(it, 1);
        }
        String back = f.back == null ? "" : f.back;
        if (recBack == null ? !back.isEmpty() : !recBack.item.name.equals(back)) {
            var it = back.isEmpty() ? null : Items.byName(back);
            recBack = it == null ? null : new ItemStack(it, 1);
        }
        recReach = (f.flags & Recording.FLAG_BACK_REACH) == 0 ? -1 : recReach + 1;
        if ((f.flags & Recording.FLAG_HURT) != 0) hurtTime = Math.max(hurtTime, 8);
        // posture (sit / crawl / lie / deep crouch on the ground) eases like the player's
        var stances = derpcraft.game.Stance.values();
        float[] want = stances[Math.max(0, Math.min(stances.length - 1, f.stance))].params;
        System.arraycopy(recVis, 0, prevRecVis, 0, recVis.length);
        if (!recVisUsed) { System.arraycopy(want, 0, recVis, 0, recVis.length); System.arraycopy(want, 0, prevRecVis, 0, recVis.length); recVisUsed = true; }
        else for (int i = 0; i < recVis.length; i++) recVis[i] += (want[i] - recVis[i]) * 0.35f;
        prevRecFists = recFists; prevRecPunchL = recPunchL; prevRecPunchR = recPunchR;
        recFists = f.fists; recPunchL = f.punchL; recPunchR = f.punchR;
        String pouches = f.pouches == null ? "" : f.pouches;
        if (!pouches.equals(recPouchText)) {
            recPouchText = pouches;
            java.util.Arrays.fill(recPouches, null);
            String[] parts = pouches.split(";", -1);
            for (int k = 0; k < recPouches.length && k < parts.length; k++) {
                int c = parts[k].lastIndexOf(':');
                if (c <= 0) continue;
                var it = Items.byName(parts[k].substring(0, c));
                int n = 1;
                try { n = Integer.parseInt(parts[k].substring(c + 1)); } catch (NumberFormatException ignored) {}
                if (it != null && n > 0) recPouches[k] = new ItemStack(it, n);
            }
        }
    }

    /** Puts the actor exactly on its mark (first frame of the take). */
    void snapToMark() {
        place(markX, markY, markZ, markYaw);
        Recording.Frame f = sceneTake == null ? null : sceneTake.frame(sceneTake.offset);
        if (f != null) { body.pitch = f.pitch; applyFlags(f); }
    }

    /** Whether the arm swing started in this tick (a recording of its AI marks the frame). */
    boolean swingStartedNow() { return swingTicks == 0; }

    /** Where the head looks (yaw). */
    float headYaw() { return headYaw; }

    /** Swings the arm (throwing, shooting). */
    public void swing() { swingTicks = -1; }

    /** Stands still where it is (waiting on the mark, frozen during a countdown). */
    void hold() {
        age++;
        body.px = body.x; body.py = body.y; body.pz = body.z;
        prevHeadYaw = headYaw; prevPitch = body.pitch;
        if (hurtTime > 0) hurtTime--;
        animate();
    }

    // ================================================================ AI
    /** One AI tick: combat, fleeing, following, behaviour, walking to a scene mark. */
    void tickAI(NpcManager m, Random r) {
        World w = m.world();
        age++;
        prevHeadYaw = headYaw; prevPitch = body.pitch;
        if (hurtTime > 0) hurtTime--;
        if (attackCooldown > 0) attackCooldown--;
        if (dead()) { tickDead(m); return; }
        body.speedMul = speed;
        if (regen > 0 && health < maxHealth && age - lastHurtAge > 100) health = Math.min(maxHealth, health + regen / 20f);
        Chunk c = w.chunkAt((int) Math.floor(body.x), (int) Math.floor(body.z));
        if (c == null || c.state < Chunk.STATE_LIT) { body.px = body.x; body.py = body.y; body.pz = body.z; animate(); return; }
        closeDoorsBehind(w);
        applyHitbox();
        if (mode == Mode.AI && behavior == Behavior.RIDE && ride(m)) return;
        tickWorld = w;
        if (mode == Mode.AI && behavior == Behavior.DRIVE && m.session().vehicles != null && m.session().vehicles.npcDrive(this)) { seatedFight(m, r); return; }
        // following the player: gets into the car the player sits in, and out when the player walked away
        if (mode == Mode.AI && behavior == Behavior.FOLLOW && m.session().vehicles != null
                && (rideAlong >= 0 || targetActor == null && targetMob == null && !targetPlayer && fleeTicks == 0)
                && m.session().vehicles.npcFollow(this)) { seatedFight(m, r); return; }
        if (behavior != Behavior.FOLLOW) waiting = false;

        var p = m.player();
        double pdx = p.x - body.x, pdz = p.z - body.z, pd2 = pdx * pdx + pdz * pdz;
        Player.Input in = new Player.Input();
        float lookYaw = body.yaw;
        float lookPitch = 0;
        boolean lookAtPlayer = false;

        if (mode == Mode.GOTO_MARK) {
            double dx = markX - body.x, dz = markZ - body.z, d2 = dx * dx + dz * dz;
            if (d2 < 0.35 * 0.35 && Math.abs(markY - body.y) < 1.2) { mode = Mode.ON_MARK; snapToMark(); hold(); return; }
            moveTo(w, in, markX, markY, markZ, d2 > 8 * 8);
            lookYaw = body.yaw;
        } else if (fleeTicks > 0) {
            fleeTicks--;
            double ax = body.x - fleeFromX, az = body.z - fleeFromZ, al = Math.sqrt(ax * ax + az * az) + 1e-6;
            walkTowards(w, in, body.x + ax / al * 6, body.y, body.z + az / al * 6, true);
            lookYaw = body.yaw;
        } else if (hasTarget(m)) {
            double tx, ty, tz;
            if (targetPlayer) { tx = p.x; ty = p.y; tz = p.z; }
            else if (targetActor != null) { tx = targetActor.body.x; ty = targetActor.body.y; tz = targetActor.body.z; }
            else { tx = targetMob.x(); ty = targetMob.y(); tz = targetMob.z(); }
            double dx = tx - body.x, dz = tz - body.z, d2 = dx * dx + dz * dz;
            lookYaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
            derpcraft.gun.GunType gunType = derpcraft.gun.Guns.of(held);
            if (gunType != null && gunType.model() != null) {
                // a gun: keep a distance, aim at the chest, fire in bursts when it can see the target, reload
                double aimY = targetPlayer ? ty + p.height * 0.72 : targetActor != null ? ty + 1.2 * targetActor.scale : ty + 0.8;   // the chest (lower when the player crouches / lies)
                lookPitch = gunFight(m, w, in, r, gunType, tx, ty, tz, aimY, d2, lookYaw);
            } else {
            derpcraft.item.Item hi = held == null ? null : held.item;
            boolean ranged = hi != null && (hi.npcProjectile != null || hi.npcUse != null) && d2 > 3 * 3 && d2 < hi.npcRange * hi.npcRange;
            if (ranged) {
                // keep a distance and use the held thing (throw, shoot) when the target can be seen
                if (d2 > hi.npcRange * hi.npcRange * 0.5) moveTo(w, in, tx, ty, tz, false);
                else body.yaw = turn(body.yaw, lookYaw, 30);
                double ex = body.x, ey = body.y + 1.5, ez = body.z, aimY = ty + 1.2;
                if (attackCooldown == 0 && m.canSee(ex, ey, ez, tx, aimY, tz)) {
                    attackCooldown = Math.max(2, hi.npcCooldown);
                    swingTicks = -1;
                    m.useItem(this, hi, ex, ey, ez, tx, aimY, tz);
                }
                double dy2 = aimY - ey;
                lookPitch = (float) -Math.toDegrees(Math.atan2(dy2, Math.sqrt(d2)));
            } else if (d2 > 1.6 * 1.6) moveTo(w, in, tx, ty, tz, true);
            else body.yaw = turn(body.yaw, lookYaw, 30);
            if (!ranged && d2 < 2.4 * 2.4 && Math.abs(ty - body.y) < 2 && attackCooldown == 0) {
                attackCooldown = Math.max(2, attackDelay);
                swingTicks = -1;
                float dmg = damage > 0 ? damage : (held != null ? Math.max(1, held.item.attackDamage) : 1);
                if (targetPlayer) m.hitPlayer(this, dmg);
                else if (targetActor != null) { m.aiAction(this, 6, "npc:" + targetActor.id, Math.round(dmg * 10), 0, 0, 0); targetActor.hurt(m, dmg, this, false); }
                else { m.aiAction(this, 6, "mob:" + targetMob.type.id, Math.round(dmg * 10), 0, 0, 0); targetMob.hurt(dmg, "npc", false, null, this, body.x, body.z); }
            }
            if (!ranged) {
                double dy = ty + 1.5 - (body.y + 1.62);
                lookPitch = (float) -Math.toDegrees(Math.atan2(dy, Math.sqrt(d2)));
            }
            }
        } else {
            // ordinary behaviour
            switch (behavior) {
                case STAND, LOOK -> {
                    double hx = homeX - body.x, hz = homeZ - body.z;
                    // walk back to the spot if pushed / after a fight
                    if (hx * hx + hz * hz > 1.5 * 1.5) moveTo(w, in, homeX, homeY, homeZ, false);
                    else if (behavior == Behavior.STAND) body.yaw = turn(body.yaw, homeYaw, 8);
                }
                case WANDER -> {
                    if (wanderWait > 0) wanderWait--;
                    else {
                        double tx = wanderX - body.x, tz = wanderZ - body.z;
                        if (tx * tx + tz * tz < 0.5 || wanderWait < -200) {
                            double a = r.nextDouble() * Math.PI * 2, rad = Math.min(2, wanderRadius * 0.25) + r.nextDouble() * Math.max(0, wanderRadius - Math.min(2, wanderRadius * 0.25));
                            wanderX = homeX + Math.cos(a) * rad; wanderZ = homeZ + Math.sin(a) * rad;
                            wanderWait = 40 + r.nextInt(80);
                        } else {
                            moveTo(w, in, wanderX, body.y, wanderZ, false);
                            in.forward = 0.5f;
                            wanderWait--;
                        }
                    }
                }
                case PATROL -> {
                    if (waypoints.isEmpty()) break;
                    wpIndex = Math.floorMod(wpIndex, waypoints.size());
                    double[] wp = waypoints.get(wpIndex);
                    double dx = wp[0] - body.x, dz = wp[2] - body.z;
                    if (dx * dx + dz * dz < 0.6 * 0.6) {
                        if (wpWait++ >= patrolWait) {
                            wpWait = 0;
                            if (waypoints.size() > 1) {
                                if (patrolLoop) wpIndex = (wpIndex + 1) % waypoints.size();
                                else {
                                    if (wpIndex + wpDir < 0 || wpIndex + wpDir >= waypoints.size()) wpDir = -wpDir;
                                    wpIndex += wpDir;
                                }
                            }
                        }
                    } else { moveTo(w, in, wp[0], wp[1], wp[2], false); in.forward = Math.min(in.forward, 0.6f); }
                }
                case SEAT -> {
                    if (seat == null) break;
                    double sx = seat[0] + 0.5, sy = seat[1] + seatHeight(w), sz = seat[2] + 0.5;
                    double dx = sx - body.x, dz = sz - body.z;
                    if (dx * dx + dz * dz > 1.2 * 1.2 || Math.abs(sy - body.y) > 1.5) moveTo(w, in, sx, seat[1] + 1, sz, false);
                    else {
                        // sit down: snap onto the seat and hold still
                        body.x = sx; body.z = sz; body.y = sy; body.mx = body.mz = body.my = 0;
                        body.px = body.x; body.py = body.y; body.pz = body.z;
                        body.yaw = turn(body.yaw, homeYaw, 10);
                        seated = true;
                        headYaw = turn(headYaw, pd2 < 8 * 8 ? (float) Math.toDegrees(Math.atan2(-pdx, pdz)) : body.yaw, 6);
                        animate();
                        return;
                    }
                }
                case WATCH -> {
                    Actor t = m.byId(watchId);
                    double hx = homeX - body.x, hz = homeZ - body.z;
                    if (hx * hx + hz * hz > 1.5 * 1.5) moveTo(w, in, homeX, homeY, homeZ, false);
                    if (t != null && m.inWorld(t)) {
                        double dx = t.x() - body.x, dz = t.z() - body.z;
                        float ty = (float) Math.toDegrees(Math.atan2(-dx, dz));
                        if (in.forward == 0 && Math.abs(wrap(ty - body.yaw)) > 60) body.yaw = turn(body.yaw, ty, 6);
                        headYaw = turn(headYaw, ty, 10);
                        body.pitch += ((float) -Math.toDegrees(Math.atan2(t.y() - body.y, Math.sqrt(dx * dx + dz * dz))) - body.pitch) * 0.2f;
                        body.tick(w, in, false);
                        animate();
                        return;
                    }
                }
                case RIDE, DRIVE -> {}
                case FOLLOW -> {
                    if (waiting) {          // Tab: stays here until the player comes back and presses Tab again
                        double hx = homeX - body.x, hz = homeZ - body.z;
                        following = false;
                        if (hx * hx + hz * hz > 1.5 * 1.5) moveTo(w, in, homeX, homeY, homeZ, false);
                        break;
                    }
                    if (pd2 > 48 * 48) place(p.x - 1, p.y, p.z - 1, body.yaw);
                    if (!following && pd2 > followStart * followStart) following = true;
                    if (following && pd2 < followStop * followStop) following = false;
                    if (following) moveTo(w, in, p.x, p.y, p.z, pd2 > 10 * 10);
                }
            }
            lookYaw = body.yaw;
            if ((behavior == Behavior.LOOK || behavior == Behavior.FOLLOW) && pd2 < lookRange * lookRange) {
                lookAtPlayer = true;
                lookYaw = (float) Math.toDegrees(Math.atan2(-pdx, pdz));
                double dy = p.y + 1.62 - (body.y + 1.62);
                lookPitch = (float) -Math.toDegrees(Math.atan2(dy, Math.sqrt(pd2)));
                if (behavior == Behavior.LOOK && in.forward == 0 && Math.abs(wrap(lookYaw - body.yaw)) > 75) body.yaw = turn(body.yaw, lookYaw, 6);
            }
        }
        in.sneak = pose == Pose.SNEAK && mode == Mode.AI;
        if (pose == Pose.SIT && mode == Mode.AI && in.forward == 0) in.jump = false;
        body.tick(w, in, false);
        fallDamage(m);
        if (dead()) { animate(); return; }
        headYaw = turn(headYaw, lookYaw, lookAtPlayer ? 10 : 20);
        body.pitch += (lookPitch - body.pitch) * 0.2f;
        if (body.y < -64) place(homeX, homeY, homeZ, homeYaw);
        animate();
    }

    /**
     * In a vehicle seat (driving or riding along) with a gun: it shoots out of the window at its target (the same
     * targets as on foot: the player when aggressive, attackers of the player as a guard, other factions...). It turns its
     * head to the target (as far as a neck can from the seat) and fires bursts like on foot, reloads when empty.
     */
    private void seatedFight(NpcManager m, Random r) {
        var vs = m.session().vehicles;
        var v = vs.seatedIn(this);
        int seat = v == null ? -1 : v.seatOf(id);
        if (seat < 0) return;
        tickGun();
        derpcraft.gun.GunType gun = derpcraft.gun.Guns.of(held);
        if (gun == null || gun.model() == null || dead() || !hasTarget(m)) { gunBurst = 0; gunSeen = 0; return; }
        var p = m.player();
        double tx, ty, tz, aimY;
        if (targetPlayer) { tx = p.x; ty = p.y; tz = p.z; aimY = ty + p.height * 0.72; }
        else if (targetActor != null) { tx = targetActor.body.x; ty = targetActor.body.y; tz = targetActor.body.z; aimY = ty + 1.2 * targetActor.scale; }
        else { tx = targetMob.x(); ty = targetMob.y(); tz = targetMob.z(); aimY = ty + 0.8; }
        double ex = body.x, ey = body.y + 1.5 * scale, ez = body.z;
        double dx = tx - ex, dz = tz - ez, d = Math.sqrt(dx * dx + dz * dz);
        float lookYaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        float seatYaw = vs.seatYaw(v, seat);
        // the head turns to the target as far as it can from the seat; the gun points there
        float rel = Math.max(-100, Math.min(100, wrap(lookYaw - seatYaw)));
        headYaw = turn(headYaw, seatYaw + rel, 20);
        float pitch = (float) -Math.toDegrees(Math.atan2(aimY - ey, d));
        body.pitch += (pitch - body.pitch) * 0.35f;
        if (d > Math.min(gun.range, 48)) return;
        boolean see = m.canSee(ex, ey, ez, tx, aimY, tz);
        gunSeen = see ? gunSeen + 1 : 0;
        if (gunAmmo < 0) gunAmmo = gun.magazine;
        if (gunReloadLeft > 0) return;
        if (gunAmmo == 0) { gunReload(gun, true); return; }
        boolean facing = Math.abs(wrap(headYaw - lookYaw)) < 15;           // a target behind the car: out of reach from this seat
        if (!see || !facing) { gunBurst = 0; return; }
        if (gunSeen < 12 + (id % 7)) return;
        if (gunPause > 0) { gunPause--; return; }
        if (gunBurst <= 0) { gunBurst = gun.auto ? 2 + r.nextInt(4) : 1; gunNext = age; }
        if (gunNext < age - 1) gunNext = age;
        if (gunNext <= age && gunAmmo > 0) {
            double dy = aimY - ey, l = Math.sqrt(dx * dx + dy * dy + dz * dz) + 1e-9;
            boolean moving = Math.abs(v.speed) > 0.02f;                       // from a moving car: much less accurate
            float spread = gun.spreadHip * 0.5f + 1.5f + (moving ? gun.spreadMove * 1.5f + 2 : 0) + (float) Math.min(2, d / 30);
            double[] dir = derpcraft.gun.Ballistics.spread(dx / l, dy / l, dz / l, spread, r);
            gunShot(gun, dir[0], dir[1], dir[2], true);
            gunAmmo--;
            gunBurst--;
            gunNext += Math.max(1, 1200.0 / gun.rpm);
        }
        if (gunBurst <= 0) gunPause = gun.auto ? 14 + r.nextInt(20) : 8 + r.nextInt(10);
    }

    /** True while it sits on its seat (SEAT behaviour). */
    boolean seated;
    private World tickWorld;

    /** In a vehicle seat (DRIVE behaviour): sits there, looking ahead. Called by the vehicles after the vehicle moved. */
    public void sitIn(double x, double y, double z, float yaw) {
        grace(60);
        body.px = body.x; body.py = body.y; body.pz = body.z;
        body.x = x; body.y = y; body.z = z;
        body.mx = body.my = body.mz = 0;
        body.onGround = true; body.flying = false;
        prevBodyYaw = bodyYaw;
        bodyYaw = yaw;
        body.yaw = yaw;
        headYaw = turn(headYaw, yaw, 10);
        seated = true;
        prevLimbSwing = limbSwing; prevLimbAmount = limbAmount = 0;
    }

    /** A scene take drives a vehicle with this actor in it: the body sits on the seat and faces the vehicle's front (the head keeps the take's look). */
    public void sitInTake(double x, double y, double z, float yaw) {
        body.x = x; body.y = y; body.z = z;
        bodyYaw = yaw;
        float off = wrap(headYaw - bodyYaw);
        if (off > 85) headYaw = bodyYaw + 85;
        if (off < -85) headYaw = bodyYaw - 85;
        prevLimbSwing = limbSwing; prevLimbAmount = limbAmount = 0;
    }

    /**
     * A scripted move for one tick (getting into / out of a car in a scene): the body goes to this point facing 'yaw', the
     * previous point is given by the caller so the drawing between ticks is smooth, the limbs follow the movement.
     */
    public void glide(double x, double y, double z, float yaw, double fromX, double fromY, double fromZ) {
        body.px = fromX; body.py = fromY; body.pz = fromZ;
        prevHeadYaw = headYaw;
        body.x = x; body.y = y; body.z = z;
        body.yaw = yaw; headYaw = yaw;
        body.mx = body.my = body.mz = 0;
        seated = false;
        animate();
    }

    /** Stands still facing a point (waiting at the car door). */
    public void lookAt(double tx, double tz) {
        body.px = body.x; body.py = body.y; body.pz = body.z;
        float want = (float) Math.toDegrees(Math.atan2(-(tx - body.x), tz - body.z));
        body.yaw = turn(body.yaw, want, 20);
        headYaw = turn(headYaw, want, 20);
        animate();
    }

    /** Walks one tick towards a point (DRIVE: to the vehicle before getting in). Always true. */
    public boolean walkTo(double tx, double ty, double tz, boolean sprint) {
        World w = tickWorld;
        if (w == null) return false;
        seated = false;
        Player.Input in = new Player.Input();
        double dx = tx - body.x, dz = tz - body.z;
        if (dx * dx + dz * dz > 0.25) moveTo(w, in, tx, ty, tz, sprint);
        body.tick(w, in, false);
        headYaw = turn(headYaw, body.yaw, 20);
        body.pitch += (0 - body.pitch) * 0.2f;
        animate();
        return true;
    }

    /** Height of a seat's top above the block's y (slabs and stairs are half a block). */
    private double seatHeight(World w) {
        var b = w.getBlock(seat[0], seat[1], seat[2]);
        float h = 2;     // the lowest top: the seat of stairs, a slab, a full block
        for (float[] box : b.collisionShape(w.getMeta(seat[0], seat[1], seat[2]), 0)) h = Math.min(h, box[4]);
        return h >= 2 || h <= 0 ? 0 : Math.min(1, h) - 0.1;
    }

    /** Sits on another actor or a creature (RIDE behaviour). Returns false when the mount is gone. */
    private boolean ride(NpcManager m) {
        double mx, my, mz;
        float myaw;
        if (rideCreature) {
            derpcraft.entity.Mob mob = null;
            if (m.session().mobs != null) for (var c : m.session().mobs.list()) if (c.id == rideId) mob = c;
            if (mob == null || !mob.alive()) return false;
            mx = mob.x(); my = mob.y() + mob.type.height * 0.75 * mob.type.scale; mz = mob.z(); myaw = mob.bodyYaw;
        } else {
            Actor a = m.byId(rideId);
            if (a == null || a == this || !m.inWorld(a)) return false;
            double yr = Math.toRadians(a.bodyYaw);
            mx = a.body.x + Math.sin(yr) * 0.35; my = a.body.y + 1.05 * a.scale; mz = a.body.z - Math.cos(yr) * 0.35; myaw = a.bodyYaw;
        }
        body.px = body.x; body.py = body.y; body.pz = body.z;
        body.x = mx; body.y = my - 0.0; body.z = mz;
        body.mx = body.my = body.mz = 0;
        bodyYaw = prevBodyYaw = myaw;
        headYaw = turn(headYaw, myaw, 10);
        body.yaw = myaw;
        seated = true;
        prevLimbSwing = limbSwing; prevLimbAmount = limbAmount = 0;
        return true;
    }

    /** The pose that applies now (timeline key, recorded gesture, or the NPC's own). */
    public Poses.Def currentPose() {
        if (keyTo != null && !keyTo.isEmpty()) return Poses.get(keyT < 0.5f && keyFrom != null ? keyFrom : keyTo);
        if (mode == Mode.PLAYING || mode == Mode.ON_MARK) return Poses.get(recPose);
        if (seated && (behavior == Behavior.SEAT || behavior == Behavior.RIDE || behavior == Behavior.DRIVE || rideAlong >= 0)) return Poses.get("Sit");
        return Poses.get(customPose);
    }

    /** Collision / hit box from the pose and the size. */
    void applyHitbox() {
        Poses.Def d = currentPose();
        float w = d == null ? 0.6f : d.width, h = d == null ? (pose == Pose.SIT ? 1.3f : pose == Pose.SNEAK ? 1.65f : 1.8f) : d.height;
        body.width = Math.max(0.2f, Math.min(1.0f, w * scale));     // walking box (wide boxes would get stuck)
        body.height = Math.max(0.3f, h * scale);
        hitW = w * scale; hitH = h * scale;
        if (behavior != Behavior.SEAT && behavior != Behavior.RIDE && behavior != Behavior.DRIVE && rideAlong < 0) seated = false;
    }

    float hitW = 0.6f, hitH = 1.8f;

    private boolean hasTarget(NpcManager m) {
        if (targetPlayer && !m.playerTargetable()) targetPlayer = false;
        if (targetActor != null && (targetActor.dead() || !m.actors.contains(targetActor) || targetActor.mode == Mode.PLAYING)) targetActor = null;
        if (targetMob != null && !targetMob.alive()) targetMob = null;
        if (angerTicks > 0) angerTicks--;
        else if (faction != Faction.AGGRESSIVE) { targetActor = null; targetPlayer = false; targetMob = null; }
        // give up when the target got far away
        if (targetPlayer) {
            var p = m.player();
            double dx = p.x - body.x, dz = p.z - body.z;
            if (dx * dx + dz * dz > Math.max(aggroRange * 2.5, 24) * Math.max(aggroRange * 2.5, 24)) targetPlayer = false;
        }
        if (targetActor != null) {
            double dx = targetActor.body.x - body.x, dz = targetActor.body.z - body.z;
            if (dx * dx + dz * dz > 40 * 40) targetActor = null;
        }
        if (targetMob != null) {
            double dx = targetMob.x() - body.x, dz = targetMob.z() - body.z;
            if (dx * dx + dz * dz > 40 * 40) targetMob = null;
        }
        return targetPlayer || targetActor != null || targetMob != null;
    }

    private void tickDead(NpcManager m) {
        if (deathTime < 20) deathTime++;
        body.px = body.x; body.py = body.y; body.pz = body.z;
        // the body falls, rolls and comes to rest on the blocks; its box follows the torso (bullets, the NPC Hub find it there)
        if (ragdoll == null) startRagdoll(body.x, body.z);
        World w = m.world();
        ragdoll.tick((x, y, z) -> solidAt(w, x, y, z));
        double[] c = ragdoll.centre();
        body.x = c[0]; body.z = c[2]; body.y = ragdoll.bottom();
        body.mx = body.my = body.mz = 0;
        if (respawn && ++respawnTimer > Math.max(10, respawnSeconds * 20)) {
            revive();
            place(homeX, homeY, homeZ, homeYaw);
        }
        animate();
    }

    /** Takes damage from 'attacker' (an actor) or from the player (attacker null, byPlayer true). */
    public void hurt(NpcManager m, float amount, Actor attacker, boolean byPlayer) { hurt(m, amount, attacker, byPlayer, null); }

    /** Damage from the player, another NPC or a creature (mob). */
    public void hurt(NpcManager m, float amount, Actor attacker, boolean byPlayer, derpcraft.entity.Mob mob) { hurt(m, amount, attacker, byPlayer, mob, "hit"); }

    /** The same with its cause ("hit", "fall", "vehicle"...: blood, the fall of the body). */
    public void hurt(NpcManager m, float amount, Actor attacker, boolean byPlayer, derpcraft.entity.Mob mob, String cause) {
        if (dead() || mode == Mode.ON_MARK || invulnerable || age < noHurtUntil) return;
        boolean playing = mode == Mode.PLAYING;           // an actor in a take: hurt and killed for real, but the take goes on while it lives
        if (byPlayer && faction == Faction.FRIENDLY && !m.playerHurtsFriendly) { m.playerAttacked(this); return; }   // no harm, but your helpers still go for it     // friendly NPCs ignore the player's hits (unless allowed on Teams)
        var ses = derpcraft.game.Session.current;
        if (ses != null && derpcraft.api.EventBus.has(derpcraft.api.Events.EntityDamage.class)) {
            var ev = derpcraft.api.EventBus.post(ses, new derpcraft.api.Events.EntityDamage(null, name, amount, byPlayer ? "player" : mob != null ? "mob" : attacker != null ? "npc" : "other",
                    byPlayer, mob, attacker == null ? null : attacker.name));
            if (ev.isCancelled() || ev.amount <= 0) return;
            amount = ev.amount;
        }
        health -= amount;
        hurtTime = 10;
        lastHurtAge = (int) Math.min(Integer.MAX_VALUE, age);
        derpcraft.game.Blood.body(body.x, body.y, body.z, hitH, amount, cause);
        double ax = byPlayer ? m.player().x : attacker != null ? attacker.body.x : mob != null ? mob.x() : body.x;
        double az = byPlayer ? m.player().z : attacker != null ? attacker.body.z : mob != null ? mob.z() : body.z;
        double kx = body.x - ax, kz = body.z - az, kl = Math.sqrt(kx * kx + kz * kz) + 1e-6;
        if (knockback && !playing) {
            body.mx += kx / kl * 0.4; body.mz += kz / kl * 0.4;
            if (body.onGround) body.my = 0.36;
        }
        if (health <= 0) {
            if (ses != null && ses.vehicles != null) ses.vehicles.actorDied(this);     // a driver killed in a scene: the car rolls out driverless
            if (playing) toAI();                                                      // out of the take: the body falls where it was
            health = 0; deathTime = 1; respawnTimer = 0; targetActor = null; targetMob = null; targetPlayer = false;
            startRagdoll(ax, az);
            if (ses != null && derpcraft.api.EventBus.has(derpcraft.api.Events.EntityDeath.class))
                derpcraft.api.EventBus.post(ses, new derpcraft.api.Events.EntityDeath(null, name, byPlayer, new java.util.ArrayList<>(), 0));
            return;
        }
        m.actorHurt(this, attacker, byPlayer, mob);          // allies of its faction / the player's helpers join in
        if (playing) return;                  // a take goes on: no reaction until it is over
        // reaction
        boolean flee = faction == Faction.FRIENDLY && guard != Guard.DEFEND || faction == Faction.NEUTRAL && onHit == OnHit.FLEE;
        if (flee) { fleeTicks = 100; fleeFromX = ax; fleeFromZ = az; }
        else {
            angerTicks = 600;
            if (byPlayer) { targetPlayer = true; targetActor = null; targetMob = null; }
            else if (attacker != null) { targetActor = attacker; targetPlayer = false; targetMob = null; }
            else if (mob != null) { targetMob = mob; targetActor = null; targetPlayer = false; }
        }
    }

    /** Flee from a point for a while (guards that run away from fights). */
    void fleeFrom(double x, double z, int ticks) { fleeTicks = Math.max(fleeTicks, ticks); fleeFromX = x; fleeFromZ = z; }

    // ================================================================ walking
    private List<int[]> path;
    private int pathIdx, pathAge = 100;
    private double pathTX, pathTY, pathTZ;

    /** Walks to a point, finding a way round walls and through doors (A*); straight line when close or no path. */
    private void moveTo(World w, Player.Input in, double tx, double ty, double tz, boolean sprint) {
        double dx = tx - body.x, dz = tz - body.z, d2 = dx * dx + dz * dz;
        pathAge++;
        if (d2 < 1.5 * 1.5 && Math.abs(ty - body.y) < 1.2) { path = null; walkTowards(w, in, tx, ty, tz, sprint); return; }
        boolean moved = (tx - pathTX) * (tx - pathTX) + (tz - pathTZ) * (tz - pathTZ) > 2.5 * 2.5 || Math.abs(ty - pathTY) > 2;
        if (path != null && pathIdx < path.size()) {   // pushed / teleported off the path
            int[] c = path.get(pathIdx);
            double ox = c[0] + 0.5 - body.x, oz = c[2] + 0.5 - body.z;
            if (ox * ox + oz * oz > 3 * 3 || Math.abs(c[1] - body.y) > 3) { path = null; pathAge = 100; }
        }
        boolean want = path == null ? pathAge > 15 : moved && pathAge > 10 || pathAge > 60 || stuckTicks > 12;
        if (want && d2 < 64 * 64) {
            pathAge = 0;
            pathTX = tx; pathTY = ty; pathTZ = tz;
            int sx = (int) Math.floor(body.x), sy = (int) Math.floor(body.y + 0.2), sz = (int) Math.floor(body.z);
            path = PathFinder.find(w, sx, sy, sz, (int) Math.floor(tx), (int) Math.floor(ty + 0.2), (int) Math.floor(tz), openDoors, d2 > 24 * 24 ? 3000 : 1500);
            pathIdx = 1;
            if (Boolean.getBoolean("npc.debug")) System.out.println("[path] " + name + " " + sx + "," + sy + "," + sz + " -> " + (int) Math.floor(tx) + "," + (int) Math.floor(ty + 0.2) + "," + (int) Math.floor(tz) + " : " + (path == null ? "none" : path.size() + " end " + java.util.Arrays.toString(path.get(path.size() - 1))));
            if (stuckTicks > 12 && !breakBlocks) stuckTicks = 0;
        }
        if (path != null) {
            pathIdx = PathFinder.reached(path, pathIdx, body.x, body.y, body.z);
            if (pathIdx < path.size()) {
                int[] c = path.get(pathIdx);
                walkTowards(w, in, c[0] + 0.5, c[1], c[2] + 0.5, sprint);
                // corners: slow down so we don't overshoot doorways
                if (pathIdx + 1 < path.size()) {
                    int[] n = path.get(pathIdx + 1);
                    if (n[0] - c[0] != c[0] - path.get(pathIdx - 1)[0] || n[2] - c[2] != c[2] - path.get(pathIdx - 1)[2]) in.sprint = false;
                }
                return;
            }
            path = null;
        }
        walkTowards(w, in, tx, ty, tz, sprint);
    }

    /** Steers towards a point: turns, walks / sprints, jumps up steps, opens doors and (if allowed) breaks blocks. */
    private void walkTowards(World w, Player.Input in, double tx, double ty, double tz, boolean sprint) {
        double dx = tx - body.x, dz = tz - body.z;
        float want = (float) Math.toDegrees(Math.atan2(-dx, dz));
        body.yaw = turn(body.yaw, want, 25);
        in.forward = 1;
        in.sprint = sprint;
        // the block in front, at feet and head height
        double yr = Math.toRadians(body.yaw);
        int fx = (int) Math.floor(body.x - Math.sin(yr) * 0.7), fz = (int) Math.floor(body.z + Math.cos(yr) * 0.7), fy = (int) Math.floor(body.y + 0.01);
        Block feet = w.getBlock(fx, fy, fz), head = w.getBlock(fx, fy + 1, fz);
        if (openDoors) {
            for (int k = 0; k < 2; k++) {
                Block b = k == 0 ? feet : head;
                if (b instanceof FunctionalBlocks.DoorBlock door && !b.name.contains("iron")) {
                    int ly = k == 0 ? fy : fy + 1;
                    int meta = w.getMeta(fx, ly, fz);
                    if ((meta & 8) != 0) { ly--; meta = w.getMeta(fx, ly, fz); }
                    if ((meta & 4) == 0) { door.setOpen(w, fx, ly, fz, true); openedDoors.add(new int[]{fx, ly, fz, 0}); }
                    break;
                }
                if (b instanceof FunctionalBlocks.FenceGateBlock gate && (w.getMeta(fx, fy, fz) & 4) == 0 && k == 0) {
                    gate.setOpen(w, fx, fy, fz, true);
                    openedDoors.add(new int[]{fx, fy, fz, 0});
                    break;
                }
            }
        }
        if (body.collidedHorizontally) in.jump = true;
        // stuck detection
        double moved = Math.abs(body.x - progressX) + Math.abs(body.z - progressZ);
        if (moved > 0.4) { progressX = body.x; progressZ = body.z; stuckTicks = 0; breakY = -1; breakProgress = 0; }
        else stuckTicks++;
        if (breakBlocks && stuckTicks > 25) {
            int by = head.solid && !head.isAir() ? fy + 1 : feet.solid && !feet.isAir() ? fy : -1;
            Block target = by == fy + 1 ? head : feet;
            if (by >= 0 && target.hardness >= 0 && target.hardness < 30) {
                if (breakX != fx || breakY != by || breakZ != fz) { breakX = fx; breakY = by; breakZ = fz; breakProgress = 0; }
                // a held tool helps like it does for the player (pickaxe through stone, axe through planks)
                float toolSpeed = held != null ? Math.max(1, held.item.speedAgainst(target)) : 1;
                breakProgress += toolSpeed / (target.hardness * 25 + 6);
                if (age % 6 == 0) swingTicks = -1;
                if (breakProgress >= 1) { NpcManager.breakBlock(w, fx, by, fz); breakY = -1; breakProgress = 0; stuckTicks = 0; }
            }
        }
    }

    /** Closes doors this NPC opened once it is 2+ blocks away. */
    private void closeDoorsBehind(World w) {
        for (Iterator<int[]> it = openedDoors.iterator(); it.hasNext(); ) {
            int[] d = it.next();
            d[3]++;
            double dx = d[0] + 0.5 - body.x, dz = d[2] + 0.5 - body.z;
            if (d[3] > 20 && dx * dx + dz * dz > 4) {
                Block b = w.getBlock(d[0], d[1], d[2]);
                if (b instanceof FunctionalBlocks.DoorBlock door) door.setOpen(w, d[0], d[1], d[2], false);
                else if (b instanceof FunctionalBlocks.FenceGateBlock gate) gate.setOpen(w, d[0], d[1], d[2], false);
                it.remove();
            } else if (d[3] > 400) it.remove();
        }
    }

    // ================================================================ animation
    private void animate() {
        tickGun();
        prevLimbSwing = limbSwing; prevLimbAmount = limbAmount; prevBodyYaw = bodyYaw;
        double dx = body.x - body.px, dz = body.z - body.pz;
        float f = (float) Math.min(1, Math.sqrt(dx * dx + dz * dz) * 4);
        if (body.flying || dead()) f *= dead() ? 0 : 0.3f;
        limbAmount += (f - limbAmount) * 0.4f;
        limbSwing += limbAmount;
        float target = bodyYaw;
        if (dx * dx + dz * dz > 0.0025 * 0.0025) target = (float) Math.toDegrees(Math.atan2(dz, dx)) - 90;
        boolean gunHeld = derpcraft.gun.Guns.of(mode == Mode.PLAYING || mode == Mode.ON_MARK ? recHeld : held) != null;
        if (swingTicks >= 0 || gunHeld) target = headYaw;          // with a gun the body faces where it looks
        bodyYaw += wrap(target - bodyYaw) * (gunHeld ? 0.5f : 0.3f);
        float off = wrap(headYaw - bodyYaw);
        if (off < -50) off = -50;
        if (off > 50) off = 50;
        bodyYaw = headYaw - off;
        if (swingTicks >= -1) { if (++swingTicks >= 6) swingTicks = -2; }
    }

    private static float turn(float from, float to, float max) {
        float d = wrap(to - from);
        return from + Math.max(-max, Math.min(max, d));
    }

    static float wrap(float a) { a %= 360; if (a >= 180) a -= 360; if (a < -180) a += 360; return a; }

    // ================================================================ guns
    /**
     * Gun fight (AI): closes in when the target is far or out of sight, otherwise stands (backs off when it is very
     * close) and turns to it; fires bursts (single shots with a semi gun) once it has seen the target for a moment and
     * faces it; reloads when the magazine is empty. Returns the pitch to look at.
     */
    private float gunFight(NpcManager m, World w, Player.Input in, Random r, derpcraft.gun.GunType gun,
                           double tx, double ty, double tz, double aimY, double d2, float lookYaw) {
        double d = Math.sqrt(d2), ex = body.x, ey = body.y + 1.5 * scale, ez = body.z;
        float engage = Math.min(gun.range, 40);
        boolean see = m.canSee(ex, ey, ez, tx, aimY, tz);
        if (!see || d > engage * 0.75) moveTo(w, in, tx, ty, tz, d > engage);
        else if (d < 3) {      // too close: step back while facing it
            body.yaw = turn(body.yaw, lookYaw, 30);
            in.forward = -0.6f;
        } else body.yaw = turn(body.yaw, lookYaw, 25);
        gunSeen = see ? gunSeen + 1 : 0;
        float pitch = (float) -Math.toDegrees(Math.atan2(aimY - ey, d));
        if (gunAmmo < 0) gunAmmo = gun.magazine;
        if (gunReloadLeft > 0) return pitch;
        if (gunAmmo == 0) { gunReload(gun, true); return pitch; }
        boolean facing = Math.abs(wrap(body.yaw - lookYaw)) < 20;
        // a moment to aim after it sees the target or turned to it (10..16 ticks)
        if (!see || !facing) { gunBurst = 0; gunSeen = Math.min(gunSeen, 0); return pitch; }
        if (gunSeen < 10 + (id % 7)) return pitch;
        if (gunPause > 0) { gunPause--; return pitch; }
        if (gunBurst <= 0) { gunBurst = gun.auto ? 2 + r.nextInt(4) : 1; gunNext = age; }
        // never catch up on time that went by without shooting (a fight paused, the target lost): one shot per interval
        if (gunNext < age - 1) gunNext = age;
        double interval = Math.max(1, 1200.0 / gun.rpm);
        if (gunNext <= age && gunBurst > 0 && gunAmmo > 0) {
            // aim at the chest, off by the gun's spread (more while walking, a bit more far away)
            double dx = tx - ex, dy = aimY - ey, dz = tz - ez, l = Math.sqrt(dx * dx + dy * dy + dz * dz) + 1e-9;
            boolean moving = Math.abs(body.x - body.px) + Math.abs(body.z - body.pz) > 0.02;
            float spread = gun.spreadHip * 0.5f + 1.2f + (moving ? gun.spreadMove : 0) + (float) Math.min(2, d / 30);
            double[] dir = derpcraft.gun.Ballistics.spread(dx / l, dy / l, dz / l, spread, r);
            gunShot(gun, dir[0], dir[1], dir[2], true);
            gunAmmo--;
            gunBurst--;
            gunNext += interval;
        }
        // between bursts: a pause to steady the aim (auto 0.6..1.5 s, semi 0.35..0.8 s)
        if (gunBurst <= 0) gunPause = gun.auto ? 12 + r.nextInt(18) : 7 + r.nextInt(9);
        return pitch;
    }

    /** One shot of the gun in hand along (dx, dy, dz) (unit) from the eye; the gun kicks. 'hurt' = deal damage. */
    public void gunShot(derpcraft.gun.GunType gun, double dx, double dy, double dz, boolean hurt) { gunShot(gun, dx, dy, dz, hurt, false); }

    /** 'replay' = a recorded shot: wooden doors only crack (the take's block changes break or open them). */
    public void gunShot(derpcraft.gun.GunType gun, double dx, double dy, double dz, boolean hurt, boolean replay) {
        var s = derpcraft.game.Session.current;
        if (s == null || gun.model() == null) return;
        double ex = body.x, ey = body.y + 1.5 * scale, ez = body.z;
        float[] fo = gun.flashOffset, mp = gun.muzzlePoint;
        double[] mz = derpcraft.gun.GunHold3P.worldPoint(gun, new float[]{mp[0] + fo[0], mp[1] + fo[1], mp[2] + fo[2]},
                body.x, body.y, body.z, bodyYaw, wrap(headYaw - bodyYaw), body.pitch, scale, 0);
        derpcraft.gun.Ballistics.fire(s, gun, this, ex, ey, ez, dx, dy, dz, mz[0], mz[1], mz[2], hurt, false,
                replay ? derpcraft.gun.Ballistics.DOORS_VISUAL : derpcraft.gun.Ballistics.DOORS_NONE);
        gunKick = 1;
        if (!replay && s.npcs != null) s.npcs.aiAction(this, 3, gun.name, 0, dx, dy, dz);     // its own take (recorded with the scene)
    }

    /** A melee strike with the gun in hand (a take replays the player's): the third person animation and its swing sound. */
    public void gunMelee(derpcraft.gun.GunType gun, int kind) {
        if (gun == null || kind < 0 || kind >= derpcraft.gun.GunType.MELEE.length) return;
        gunMelee = kind;
        gunMeleeTicks = 0;
        var s = derpcraft.game.Session.current;
        if (s != null) derpcraft.gun.Ballistics.sound(s, gun.sounds.get("melee"), body.x, body.y + 1.2, body.z, 0.8f * gun.volume, 1);
    }

    /** Starts a reload of the gun in hand (animation, sounds; 'empty' = with the bolt). The magazine is full at the end. */
    public void gunReload(derpcraft.gun.GunType gun, boolean empty) {
        if (gunReloadLeft > 0) return;
        gunReloading = gun;
        gunReloadEmpty = empty;
        gunReloadTotal = gunReloadLeft = Math.max(4, Math.round((empty ? gun.reloadEmptyTime : gun.reloadTime) * 20));
        gunReloadStage = 0;
        gunMagDropped = false;
        gunBurst = 0;
        reloadSound("reload");
        var ses = derpcraft.game.Session.current;
        if (ses != null && ses.npcs != null && mode == Mode.AI) ses.npcs.aiAction(this, 4, gun.name, empty ? 1 : 0, 0, 0, 0);
    }

    private void reloadSound(String ev) {
        var s = derpcraft.game.Session.current;
        if (s == null || gunReloading == null) return;
        derpcraft.gun.Ballistics.sound(s, gunReloading.sounds.get(ev), body.x, body.y + 1.2, body.z, 0.8f * gunReloading.volume, 1);
    }

    /** Every tick (AI or a take): the kick fades, a reload moves on (its sounds), a gun put away stops reloading. */
    private void tickGun() {
        prevGunKick = gunKick;
        gunKick = Math.max(0, gunKick - 0.34f);
        if (gunMelee >= 0) {
            var mg = derpcraft.gun.Guns.of(mode == Mode.PLAYING || mode == Mode.ON_MARK ? recHeld : held);
            gunMeleeTicks++;
            if (mg == null || gunMeleeTicks / 20f > derpcraft.gun.GunAnimator.meleeLength(mg, gunMelee)) gunMelee = -1;
        }
        if (gunReloadLeft <= 0) {
            // between fights: top the magazine up
            if (mode == Mode.AI && targetActor == null && targetMob == null && !targetPlayer && gunAmmo >= 0) {
                var g = derpcraft.gun.Guns.of(held);
                if (g != null && gunAmmo < g.magazine) gunReload(g, gunAmmo == 0);
            }
            return;
        }
        var g = gunReloading;
        boolean rec = mode == Mode.PLAYING || mode == Mode.ON_MARK;
        if (g == null || derpcraft.gun.Guns.of(rec ? recHeld : held) != g) { gunReloadLeft = 0; return; }
        gunReloadLeft--;
        float r = 1 - gunReloadLeft / (float) gunReloadTotal;
        float q = gunReloadEmpty ? r / derpcraft.gun.GunAnimator.EMPTY_MAG_END : r;
        if (gunReloadStage == 0 && q >= derpcraft.gun.GunAnimator.MAG_OUT) { gunReloadStage = 1; reloadSound("mag_out"); }
        if (!gunMagDropped && q >= derpcraft.gun.GunAnimator.MAG_DROP) {
            // the old magazine is let go: it falls to the ground at the NPC's hands
            gunMagDropped = true;
            var s = derpcraft.game.Session.current;
            double[] at = derpcraft.gun.GunHold3P.worldMagRelease(g, body.x, body.y, body.z, bodyYaw, wrap(headYaw - bodyYaw), body.pitch, scale, gunReloadEmpty);
            if (s != null && at != null) s.game.entities.dropMagazine(g, at[0], at[1], at[2], (float) at[3], body.x - body.px, body.y - body.py, body.z - body.pz);
        }
        if (gunReloadStage == 1 && q >= derpcraft.gun.GunAnimator.MAG_IN) { gunReloadStage = 2; reloadSound("mag_in"); }
        float boltAt = derpcraft.gun.GunAnimator.EMPTY_MAG_END + (1 - derpcraft.gun.GunAnimator.EMPTY_MAG_END) * derpcraft.gun.GunAnimator.BOLT_RELEASE;
        if (gunReloadStage == 2 && gunReloadEmpty && r >= boltAt) { gunReloadStage = 3; reloadSound("bolt"); }
        if (gunReloadLeft == 0) gunAmmo = g.magazine;
    }

    // ================================================================ rendering
    public EntityRenderer.PlayerPose pose(float partial, Skins skins) {
        EntityRenderer.PlayerPose p = new EntityRenderer.PlayerPose();
        p.x = body.px + (body.x - body.px) * partial;
        p.y = body.py + (body.y - body.py) * partial;
        p.z = body.pz + (body.z - body.pz) * partial;
        float by = prevBodyYaw + wrap(bodyYaw - prevBodyYaw) * partial;
        float hy = prevHeadYaw + wrap(headYaw - prevHeadYaw) * partial;
        p.bodyYaw = by;
        p.headYaw = wrap(hy - by);
        p.pitch = prevPitch + (body.pitch - prevPitch) * partial;
        p.limbSwing = prevLimbSwing + (limbSwing - prevLimbSwing) * partial;
        p.limbAmount = prevLimbAmount + (limbAmount - prevLimbAmount) * partial;
        p.swing = swingTicks >= 0 ? Math.min(1, (swingTicks + partial) / 6f) : 0;
        p.age = age + partial;
        boolean rec = mode == Mode.PLAYING || mode == Mode.ON_MARK;
        p.sneaking = rec ? recSneak : pose == Pose.SNEAK && mode == Mode.AI;
        p.sitting = rec ? recSit : pose == Pose.SIT && mode == Mode.AI && Math.abs(body.x - body.px) + Math.abs(body.z - body.pz) < 0.01;
        p.waving = rec ? recWave : pose == Pose.WAVE && mode == Mode.AI && !targetPlayer && targetActor == null && targetMob == null;
        p.held = rec ? recHeld : held;
        p.backGun = rec ? recBack : null;
        p.pouches = rec ? recPouches : null;
        p.backReach = rec && recReach >= 0 ? (float) Math.sin(Math.PI * Math.min(1, (recReach + partial) / derpcraft.game.Session.BACK_REACH)) : 0;
        p.gunKick = prevGunKick + (gunKick - prevGunKick) * partial;
        p.gunReload = gunReloadLeft > 0 && gunReloadTotal > 0 ? Math.min(1, 1 - (gunReloadLeft - partial) / gunReloadTotal) : -1;
        p.gunReloadEmpty = gunReloadEmpty;
        if (rec && recVisUsed) {
            float[] sv = new float[recVis.length];
            for (int i = 0; i < sv.length; i++) sv[i] = prevRecVis[i] + (recVis[i] - prevRecVis[i]) * partial;
            p.stanceVis = sv;
        }
        if (rec) {
            p.fists = prevRecFists + (recFists - prevRecFists) * partial;
            p.punchLeft = prevRecPunchL + (recPunchL - prevRecPunchL) * partial;
            p.punchRight = prevRecPunchR + (recPunchR - prevRecPunchR) * partial;
            if (p.fists > 0.01f) p.held = null;
        }
        p.gunMelee = gunMelee;
        p.gunMeleeTime = gunMelee >= 0 ? (gunMeleeTicks + partial) / 20f : 0;
        Skins.Skin s = skins.get(skin);
        int face = rec ? recEmotion : emotion;
        // blinking: 3 ticks every few seconds (unless the face already has closed eyes)
        if (blink && face != Emotions.CLOSED && !dead() && Math.floorMod(age + id * 37L, 97) < 3) face = Emotions.CLOSED;
        p.skin = skins.face(skin, face);
        p.slim = s.slim();
        p.offhand = offhand;
        p.armor = armor;
        p.scale = scale;
        p.attachments = attachments;
        // pose: timeline keys blend between two poses; otherwise the recorded gesture or the NPC's own pose
        if (keyTo != null && !keyTo.isEmpty() || keyFrom != null && !keyFrom.isEmpty() && keyT < 1) {
            p.poseFrom = Poses.get(keyFrom); p.poseTo = Poses.get(keyTo); p.poseT = keyT;
        } else {
            p.poseTo = currentPose(); p.poseT = 1;
        }
        if (p.poseTo != null && (p.poseTo.set[Poses.RIGHT_LEG] || p.poseTo.tiltX != 0) && p.poseT >= 1) p.limbAmount *= 0.3f;
        if (seated && p.poseTo != null) p.limbAmount = 0;
        if (p.sitting) p.limbAmount = 0;
        p.hurt = hurtTime > 0 || dead() ? 1 : 0;
        p.death = dead() ? Math.min(1, (deathTime + partial) / 20f) : 0;
        if (ragdoll != null) {
            // the falling body: its parts where the physics put them (red only while the hit is fresh)
            p.ragdoll = ragdoll.parts(partial, p.x, p.y, p.z);
            p.hurt = hurtTime > 0 ? 1 : 0;
            p.death = 0;
        }
        return p;
    }

    public double rayHit(double ox, double oy, double oz, double dx, double dy, double dz, double max) {
        double hw = hitW / 2 + 0.05, hh = dead() ? 0.6 : hitH + 0.05;
        double x0 = body.x - hw, x1 = body.x + hw, y0 = body.y, y1 = body.y + hh, z0 = body.z - hw, z1 = body.z + hw;
        if (ragdoll != null) {
            // a body on the ground: the box around its parts
            double r = 2.2 / 16 * scale;
            x0 = y0 = z0 = Double.MAX_VALUE; x1 = y1 = z1 = -Double.MAX_VALUE;
            for (int i = 0; i < Ragdoll.N; i++) {
                double px = ragdoll.p[i * 3], py = ragdoll.p[i * 3 + 1], pz = ragdoll.p[i * 3 + 2];
                x0 = Math.min(x0, px - r); y0 = Math.min(y0, py - r); z0 = Math.min(z0, pz - r);
                x1 = Math.max(x1, px + r); y1 = Math.max(y1, py + r); z1 = Math.max(z1, pz + r);
            }
        }
        return derpcraft.util.RayBox.t(ox, oy, oz, dx, dy, dz, max, x0, y0, z0, x1, y1, z1);
    }
}
