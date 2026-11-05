package derpcraft.npc;

import derpcraft.api.Events;
import derpcraft.api.ModContext;
import derpcraft.block.Block;
import derpcraft.block.Blocks;
import derpcraft.game.Player;
import derpcraft.game.Session;
import derpcraft.item.ItemStack;
import derpcraft.util.Mat4;
import derpcraft.world.World;

import java.io.*;
import java.nio.file.*;
import java.util.*;

/**
 * NPCs of the open world and the "director". Every actor has an AI (behaviour, faction, combat) and recorded
 * takes per named scene. Playing a scene sends its cast to their start marks, then everyone plays together; when an
 * actor's take is over it continues with its AI from there — scenes and AI combine freely. Scenes start from the
 * NPC Hub, commands, mods or Scene Blocks. Saved in the world folder (npc/actors.txt, npc/recordings/*.rec).
 */
public final class NpcManager {
    private final Session s;
    private final Path dir;
    public final Skins skins;
    /** NPCs in the world. */
    public final List<Actor> actors = new ArrayList<>();
    /** NPCs switched off (themselves or their group): kept, not in the world. */
    public final List<Actor> hidden = new ArrayList<>();
    /** Scene groups in order, with on / off. */
    private final LinkedHashMap<String, Boolean> groups = new LinkedHashMap<>();
    private int nextId = 1;
    private final Random random = new Random();

    // ---------------------------------------------------------------- settings (saved)
    public boolean loopScene, linkCamera = true, othersPlayWhileRecording = true, recordBlocks = true, recordChat;
    /** Actors walk to their start marks (false = teleport there). */
    public boolean walkToMarks = true;
    /** Aggressive NPCs also attack a player in creative mode (for filming fights). */
    public boolean attackCreative;
    public int countdownSeconds = 3;
    /** Scene recorded and played from the hub. */
    public String currentScene = "main";
    /** attacks[a][b]: NPCs of faction a attack NPCs of faction b (Actor.Faction order). */
    public final boolean[][] attacks = new boolean[3][3];

    // ---------------------------------------------------------------- runtime
    /** A scene in progress: first its cast walks to the marks, then all takes play together. */
    public static final class SceneRun {
        public final String name;
        public int tick, gatherTicks, length, start;
        public boolean gathering = true;
        final List<Actor> cast = new ArrayList<>();
        SceneRun(String name) { this.name = name; }
    }

    private final List<SceneRun> runs = new ArrayList<>();
    private Actor recordingActor;
    private Recording take;
    private Track.Take recTake;
    private String recScene;
    private int countdown, recTick;
    private boolean takeStarted;
    private final List<Actor> recCast = new ArrayList<>();
    /** Gesture and face the player acts with while recording (Tab / Alt + number). */
    public String recordPose = "";
    public int recordEmotion;
    /** Actors standing at a timeline preview position. */
    private final Map<Actor, double[]> previewing = new HashMap<>();

    // ---------------------------------------------------------------- camera on the timeline
    /** A saved camera position. */
    public static final class Shot {
        public String name;
        public double x, y, z;
        public float yaw, pitch, focal;
        public Shot(String name, double x, double y, double z, float yaw, float pitch, float focal) { this.name = name; this.x = x; this.y = y; this.z = z; this.yaw = yaw; this.pitch = pitch; this.focal = focal; }
    }

    /** A camera cut on a group's timeline. */
    public static final class CamKey {
        public static final String[] TYPES = {"Player view", "Shot", "Follow NPC", "NPC's eyes", "Camera path"};
        public int tick, type, ref;
        public CamKey(int tick, int type, int ref) { this.tick = tick; this.type = type; this.ref = ref; }
    }

    public final List<Shot> shots = new ArrayList<>();
    private final Map<String, List<CamKey>> camKeys = new HashMap<>();

    public List<CamKey> camKeys(String group) { return camKeys.computeIfAbsent(group.toLowerCase(Locale.ROOT), k -> new ArrayList<>()); }
    /** True while the player's own clicks are handled (their block changes go into the take). */
    public boolean playerActing;
    private Actor lastPlayerAttacker;
    private long lastPlayerAttackTick = -1000, tickCount;

    private Mat4 lastProjView;
    private double camX, camY, camZ;

    public NpcManager(Session s, Path worldDir) {
        this.s = s;
        this.dir = worldDir.resolve("npc");
        this.skins = new Skins(s.game.assets, s.game.gameDir.resolve("skins"));
        attacks[Actor.Faction.AGGRESSIVE.ordinal()][Actor.Faction.NEUTRAL.ordinal()] = true;
        attacks[Actor.Faction.AGGRESSIVE.ordinal()][Actor.Faction.FRIENDLY.ordinal()] = true;
        load();
        s.world.blockRecorder = this::blockChanged;
    }

    World world() { return s.world; }
    Session session() { return s; }
    Player player() { return s.player; }

    /** Whether hostile NPCs may go for the player right now. */
    boolean playerTargetable() { return !s.spectator() && !s.stats.dead && (s.survival() || attackCreative); }

    // ================================================================ actors
    public Actor spawn(String name, double x, double y, double z, float yaw) {
        ensureGroup(currentScene);
        Actor a = new Actor(nextId++, uniqueName(name, currentScene));
        a.group = currentScene;
        a.place(x, y, z, yaw);
        a.setHome();
        if (groupEnabled(a.group)) actors.add(a); else hidden.add(a);
        save();
        return a;
    }

    /** Names are unique inside a group (the same actor name can be in several groups). */
    private String uniqueName(String base, String group) {
        if (base == null || base.isBlank()) base = "Actor";
        String n = base;
        for (int i = 2; byName(n, group) != null; i++) n = base + " " + i;
        return n;
    }

    public void rename(Actor a, String name) {
        if (name == null || name.isBlank() || name.equals(a.name)) return;
        Actor other = byName(name, a.group);
        a.name = other == null || other == a ? name : uniqueName(name, a.group);
    }

    /** First NPC with this name (in the current group first, then anywhere; switched-off ones too). */
    public Actor byName(String name) {
        Actor a = byName(name, currentScene);
        if (a != null) return a;
        for (Actor b : all()) if (b.name.equalsIgnoreCase(name)) return b;
        return null;
    }

    public Actor byName(String name, String group) {
        for (Actor a : all()) if (a.name.equalsIgnoreCase(name) && a.group.equalsIgnoreCase(group)) return a;
        return null;
    }

    public Actor byId(int id) {
        for (Actor a : all()) if (a.id == id) return a;
        return null;
    }

    /** Every NPC, in the world or switched off. */
    public List<Actor> all() {
        List<Actor> l = new ArrayList<>(actors.size() + hidden.size());
        l.addAll(actors);
        l.addAll(hidden);
        l.sort(Comparator.comparingInt(a -> a.id));
        return l;
    }

    // ================================================================ groups
    /** All scene groups (each is a scene with its own NPCs). */
    public List<String> groups() {
        for (Actor a : all()) ensureGroup(a.group);
        ensureGroup(currentScene);
        return new ArrayList<>(groups.keySet());
    }

    private String groupKey(String g) {
        for (String k : groups.keySet()) if (k.equalsIgnoreCase(g)) return k;
        return null;
    }

    private void ensureGroup(String g) { if (g != null && !g.isBlank() && groupKey(g) == null) groups.put(g, true); }

    public boolean groupEnabled(String g) { String k = groupKey(g); return k == null || groups.get(k); }

    public List<Actor> members(String g) {
        List<Actor> l = new ArrayList<>();
        for (Actor a : all()) if (a.group.equalsIgnoreCase(g)) l.add(a);
        return l;
    }

    /** True when the NPC is in the world (itself and its group switched on). */
    public boolean inWorld(Actor a) { return actors.contains(a); }

    /** Whether the timeline preview holds this actor (no walking in and out of cars then). */
    public boolean previewing(Actor a) { return previewing.containsKey(a); }

    /** Puts every NPC in the world or out of it according to its own switch and its group's. */
    public void refreshPresence() {
        for (Actor a : all()) {
            boolean want = a.enabled && groupEnabled(a.group);
            boolean is = actors.contains(a);
            if (want == is) continue;
            if (want) { hidden.remove(a); actors.add(a); }
            else {
                for (SceneRun r : runs) r.cast.remove(a);
                if (a.mode != Actor.Mode.AI) a.toAI();
                a.calm();
                if (a == recordingActor) cancelRecording();
                actors.remove(a); hidden.add(a);
                for (Actor o : actors) if (o.targetActor == a) o.targetActor = null;
            }
        }
        actors.sort(Comparator.comparingInt(a -> a.id));
    }

    public void setEnabled(Actor a, boolean on) { a.enabled = on; refreshPresence(); save(); }

    public void setGroupEnabled(String g, boolean on) {
        ensureGroup(g);
        groups.put(groupKey(g), on);
        refreshPresence();
        save();
    }

    /** A new empty group ("Scene 2", ...); returns its name. */
    public String newGroup(String base) {
        if (base == null || base.isBlank()) base = "Scene";
        String n = base;
        for (int i = 2; groupKey(n) != null; i++) n = base + " " + i;
        groups.put(n, true);
        save();
        return n;
    }

    public void renameGroup(String old, String name) {
        name = name == null ? "" : name.trim();
        String k = groupKey(old);
        if (k == null || name.isEmpty() || name.equals(k)) return;
        if (groupKey(name) != null && !name.equalsIgnoreCase(k)) return;     // taken
        stopScene(k);
        LinkedHashMap<String, Boolean> copy = new LinkedHashMap<>();
        for (var e : groups.entrySet()) copy.put(e.getKey().equals(k) ? name : e.getKey(), e.getValue());
        groups.clear();
        groups.putAll(copy);
        for (Actor a : all()) {
            if (a.group.equalsIgnoreCase(k)) a.group = name;
            Track t = a.removeTrack(k);
            if (t != null) a.tracks.put(name, t);
        }
        List<CamKey> ck = camKeys.remove(k.toLowerCase(Locale.ROOT));
        if (ck != null) camKeys.put(name.toLowerCase(Locale.ROOT), ck);
        if (currentScene.equalsIgnoreCase(k)) currentScene = name;
        save();
    }

    /** Deletes a group with its NPCs and their takes. */
    public void deleteGroup(String g) {
        String k = groupKey(g);
        if (k == null) return;
        stopScene(k);
        for (Actor a : members(k)) remove(a);
        groups.remove(k);
        if (currentScene.equalsIgnoreCase(k)) currentScene = groups.isEmpty() ? newGroup("main") : groups.keySet().iterator().next();
        save();
    }

    /** Copies a group: the same NPCs (new ones, same places and settings), with their takes or without. Returns the new group's name. */
    public String copyGroup(String g, boolean withTakes) {
        String k = groupKey(g);
        if (k == null) return null;
        String name = newGroup(k + " copy");
        if (withTakes) for (CamKey ck : camKeys(k)) camKeys(name).add(new CamKey(ck.tick, ck.type, ck.ref));
        for (Actor a : members(k)) {
            Actor c = new Actor(nextId++, a.name);
            c.group = name;
            c.skin = a.skin; c.behavior = a.behavior; c.pose = a.pose; c.held = a.held == null ? null : a.held.copy(1); c.showName = a.showName;
            c.faction = a.faction; c.onHit = a.onHit; c.guard = a.guard; c.aggroRange = a.aggroRange; c.followStart = a.followStart; c.followStop = a.followStop;
            c.openDoors = a.openDoors; c.breakBlocks = a.breakBlocks; c.maxHealth = a.maxHealth; c.health = a.maxHealth; c.damage = a.damage; c.respawn = a.respawn;
            c.enabled = a.enabled; c.sceneEnabled = a.sceneEnabled;
            c.customPose = a.customPose; c.emotion = a.emotion; c.blink = a.blink; c.offhand = a.offhand == null ? null : a.offhand.copy(1); c.scale = a.scale;
            for (Attachment at : a.attachments) c.attachments.add(at.copy());
            for (int i = 0; i < 4; i++) c.armor[i] = a.armor[i] == null ? null : a.armor[i].copy(1);
            for (double[] w : a.waypoints) c.waypoints.add(w.clone());
            c.patrolLoop = a.patrolLoop; c.patrolWait = a.patrolWait; c.seat = a.seat == null ? null : a.seat.clone();
            c.homeX = a.homeX; c.homeY = a.homeY; c.homeZ = a.homeZ; c.homeYaw = a.homeYaw;
            c.place(a.x(), a.y(), a.z(), a.yaw());
            Track t = a.track(k);
            if (withTakes && t != null && !t.takes.isEmpty()) {
                Track ct = c.trackOrNew(name);
                ct.active = t.active;
                for (Track.PoseKey pk : t.keys) ct.keys.add(new Track.PoseKey(pk.tick, pk.pose, pk.curve, pk.blend));
                for (Track.Take tk : t.takes) {
                    String file = takeFile(c, name);
                    try {
                        tk.rec.save(dir.resolve("recordings").resolve(file));
                        ct.takes.add(new Track.Take(tk.rec, file).copySettingsFrom(tk));
                    } catch (IOException e) { System.err.println("Cannot copy take: " + e); }
                }
            }
            hidden.add(c);
        }
        refreshPresence();
        save();
        return name;
    }

    /** Moves an NPC to another group; its take for the old group becomes its take for the new one. */
    public void moveToGroup(Actor a, String g) {
        ensureGroup(g);
        g = groupKey(g);
        if (a.group.equalsIgnoreCase(g)) return;
        Track t = a.removeTrack(a.group);
        if (t != null && a.track(g) == null) a.tracks.put(g, t);
        a.group = g;
        if (byName(a.name, g) != a) a.name = uniqueName(a.name, g);
        refreshPresence();
        save();
    }

    public void remove(Actor a) {
        for (SceneRun r : runs) r.cast.remove(a);
        actors.remove(a);
        hidden.remove(a);
        for (Actor o : actors) if (o.targetActor == a) o.targetActor = null;
        for (Track t : a.tracks.values()) for (Track.Take tk : t.takes) deleteFile(tk.file);
        if (a == recordingActor) cancelRecording();
        save();
    }

    /** The actor along a ray within 'max' blocks, or null. */
    public Actor pick(double ox, double oy, double oz, double dx, double dy, double dz, double max) { return pick(ox, oy, oz, dx, dy, dz, max, null); }

    /** The actor along a ray within 'max' blocks, not 'exclude' (the one who shoots), or null. */
    public Actor pick(double ox, double oy, double oz, double dx, double dy, double dz, double max, Actor exclude) {
        Actor best = null;
        double bd = max;
        for (Actor a : actors) {
            if (a == recordingActor || a == exclude) continue;
            double t = a.rayHit(ox, oy, oz, dx, dy, dz, bd);
            if (t >= 0 && t < bd) { bd = t; best = a; }
        }
        return best;
    }

    // ================================================================ combat
    /** The player hits an NPC. */
    public void playerAttack(Actor a) {
        ItemStack h = s.heldStack();
        float dmg = h != null ? Math.max(1, h.item.attackDamage) : 1;
        a.hurt(this, dmg, null, true);
    }

    /** An NPC hits the player (melee or a bullet). */
    public void hitPlayer(Actor a, float dmg) {
        lastPlayerAttacker = a;
        lastPlayerAttackTick = tickCount;
        s.npcHitPlayer(a.x(), a.z(), dmg);
    }

    /** Breaks a block like a player would (drops and particles). */
    static void breakBlock(World w, int x, int y, int z) {
        int st = w.getState(x, y, z);
        Block b = Blocks.byId(st >> 4);
        if (b.isAir()) return;
        Session ses = Session.current;
        if (ses != null) ses.game.entities.spawnBreakParticles(w, x, y, z, b, st & 15);
        for (ItemStack d : b.dropsWithEntity(w, x, y, z, st & 15, w.random, w.getBlockEntity(x, y, z))) w.dropItem(x + 0.5, y + 0.3, z + 0.5, d);
        w.setBlock(x, y, z, 0);
        b.onRemoved(w, x, y, z, st & 15);
    }

    /** Orders an NPC to attack another NPC (or the player when target is null). */
    public void order(Actor a, Actor target) {
        a.targetActor = target;
        a.targetPlayer = target == null;
        a.angerTicks = 600;
        a.fleeTicks = 0;
    }

    /** Aggressive / hostile-faction NPCs pick targets; guards react to attacks on the player. */
    private void chooseTargets() {
        var p = s.player;
        boolean guardEvent = lastPlayerAttacker != null && tickCount - lastPlayerAttackTick < 60 && !lastPlayerAttacker.dead();
        for (Actor a : actors) {
            if (a.mode != Actor.Mode.AI || a.dead()) continue;
            if (guardEvent && a != lastPlayerAttacker) {
                double dx = a.x() - p.x, dz = a.z() - p.z;
                if (dx * dx + dz * dz < 24 * 24) {
                    if (a.guard == Actor.Guard.DEFEND) { a.targetActor = lastPlayerAttacker; a.targetPlayer = false; a.angerTicks = 300; a.fleeTicks = 0; continue; }
                    if (a.guard == Actor.Guard.FLEE) { if (a.guardReact != lastPlayerAttackTick) { a.guardReact = lastPlayerAttackTick; a.fleeFrom(lastPlayerAttacker.x(), lastPlayerAttacker.z(), 50); } continue; }
                }
            }
            if ((a.id + tickCount) % 10 != 0) continue;              // spread the searches over ticks
            if (a.targetPlayer || a.targetActor != null || a.targetMob != null) continue;
            // guards fight hostile creatures (mod mobs) that go for the player or come near
            if (a.guard == Actor.Guard.DEFEND && s.mobs != null) {
                derpcraft.entity.Mob best = null;
                double bd = a.aggroRange * a.aggroRange;
                for (derpcraft.entity.Mob mob : s.mobs.list()) {
                    if (!mob.alive() || !mob.hostile()) continue;
                    double dx = mob.x() - a.x(), dz = mob.z() - a.z(), d2 = dx * dx + dz * dz;
                    double px = mob.x() - p.x, pz = mob.z() - p.z;
                    if ((d2 < bd || mob.targetsPlayer() && px * px + pz * pz < 16 * 16) && Math.abs(mob.y() - a.y()) < 6) { if (d2 < bd) bd = d2; best = mob; }
                }
                if (best != null) { a.targetMob = best; a.angerTicks = 300; continue; }
            }
            float range = a.aggroRange, r2 = range * range;
            if (a.faction == Actor.Faction.AGGRESSIVE && playerTargetable()) {
                double dx = p.x - a.x(), dy = p.y - a.y(), dz = p.z - a.z();
                if (dx * dx + dz * dz < r2 && Math.abs(dy) < 6) { a.targetPlayer = true; continue; }
            }
            boolean[] hates = attacks[a.faction.ordinal()];
            if (!hates[0] && !hates[1] && !hates[2]) continue;
            Actor best = null;
            double bd = r2;
            for (Actor b : actors) {
                if (b == a || b.dead() || b.mode != Actor.Mode.AI || !hates[b.faction.ordinal()]) continue;
                double dx = b.x() - a.x(), dz = b.z() - a.z(), d2 = dx * dx + dz * dz;
                if (d2 < bd && Math.abs(b.y() - a.y()) < 6) { bd = d2; best = b; }
            }
            if (best != null) { a.targetActor = best; a.angerTicks = Math.max(a.angerTicks, 200); }
        }
        if (!guardEvent) lastPlayerAttacker = null;
    }

    // ================================================================ scenes
    /** All scene names: the groups, plus scenes NPCs have takes for. */
    public List<String> sceneNames() {
        List<String> l = groups();
        for (Actor a : all()) for (String sc : a.scenes()) if (groupKey(sc) == null && !l.contains(sc)) l.add(sc);
        return l;
    }

    /** NPCs in the world that play in a scene. */
    public List<Actor> cast(String scene) {
        List<Actor> l = new ArrayList<>();
        for (Actor a : actors) if (a.take(scene) != null && a.sceneEnabled) l.add(a);
        return l;
    }

    public int sceneLength(String scene) {
        int n = 0;
        for (Actor a : actors) { Track t = a.track(scene); if (t != null && t.take() != null && a.sceneEnabled) n = Math.max(n, t.length()); }
        return n;
    }

    public int sceneLength() { return sceneLength(currentScene); }

    public boolean scenePlaying() { return !runs.isEmpty(); }
    public boolean scenePlaying(String name) { for (SceneRun r : runs) if (r.name.equalsIgnoreCase(name)) return true; return false; }
    public List<SceneRun> runs() { return Collections.unmodifiableList(runs); }
    public SceneRun run(String name) { for (SceneRun r : runs) if (r.name.equalsIgnoreCase(name)) return r; return null; }

    public boolean recording() { return recordingActor != null; }
    public Actor recordingActor() { return recordingActor; }
    public int countdownTicks() { return countdown; }
    public int recordTick() { return recTick; }

    /** Plays the hub's current scene. */
    public boolean playScene() { return playScene(currentScene); }

    /**
     * Starts a scene: its cast walks to the start marks (or teleports, see {@link #walkToMarks}) and plays when
     * everyone is there. Returns false when nothing is recorded for it or it is already playing.
     */
    public boolean playScene(String name) { return playScene(name, null, 0); }

    public boolean playScene(String name, Collection<Integer> only) { return playScene(name, only, 0); }

    /** Plays a scene from a tick of its timeline, with only some of its NPCs (ids; null = everyone). */
    public boolean playScene(String name, Collection<Integer> only, int from) {
        if (recording() || name == null || only == null && scenePlaying(name)) return false;
        releasePreview();
        SceneRun run = new SceneRun(name);
        run.start = Math.max(0, from);
        for (Actor a : actors) {
            Track t = a.track(name);
            Track.Take tk = t == null ? null : t.take();
            if (tk == null || tk.rec.length() == 0 || !a.sceneEnabled || only != null && !only.contains(a.id)) continue;
            if (from > 0 && from >= tk.end()) continue;
            boolean busy = false;
            for (SceneRun o : runs) if (o.cast.contains(a)) busy = true;
            if (busy) continue;
            if (a.dead()) a.revive();
            repairVehicles(tk);
            if (s.vehicles != null) s.vehicles.cancelBoard(a);
            a.calm();
            a.sceneTrack = t;
            a.sceneTake = tk;
            a.lastSrc = -1;
            Recording.Frame f = tk.frame(Math.max(from, tk.offset));
            a.markX = f.x; a.markY = f.y; a.markZ = f.z; a.markYaw = f.yaw;
            if (walkToMarks) a.mode = Actor.Mode.GOTO_MARK;
            else { a.mode = Actor.Mode.ON_MARK; a.snapToMark(); }
            run.cast.add(a);
            run.length = Math.max(run.length, tk.end());
        }
        if (run.cast.isEmpty()) return false;
        runs.add(run);
        return true;
    }

    /** Stops every scene; actors continue with their AI where they are. */
    public void stopScene() {
        for (SceneRun r : runs) for (Actor a : r.cast) if (a.mode != Actor.Mode.AI) a.toAI();
        runs.clear();
        if (linkCamera && s.cam.playing()) s.cam.stop();
    }

    public void stopScene(String name) {
        for (Iterator<SceneRun> it = runs.iterator(); it.hasNext(); ) {
            SceneRun r = it.next();
            if (!r.name.equalsIgnoreCase(name)) continue;
            for (Actor a : r.cast) if (a.mode != Actor.Mode.AI) a.toAI();
            it.remove();
        }
    }

    /** Cars a take drives that were blown up (by an earlier replay or by hand) are whole again when the scene starts. */
    private void repairVehicles(Track.Take tk) {
        if (s.vehicles == null) return;
        java.util.Set<Integer> ids = new java.util.HashSet<>();
        for (int i = 0; i < tk.rec.length(); i++) {
            Recording.Frame f = tk.rec.frame(i);
            if (f != null && f.vehicle >= 0) ids.add(f.vehicle);
        }
        for (int id : ids) s.vehicles.repairForScene(id);
    }

    /** Undoes the scene's block changes and puts its cast on their marks. */
    public void resetScene() { resetScene(currentScene); }

    public void resetScene(String name) {
        stopScene(name);
        revertBlocks(name);
        for (Actor a : cast(name)) {
            Track.Take tk = a.track(name).take();
            Recording.Frame f = tk.frame(tk.offset);
            repairVehicles(tk);
            if (s.vehicles != null) s.vehicles.cancelBoard(a);
            if (a.dead()) a.revive();
            a.place(f.x, f.y, f.z, f.yaw);
            a.toAI();
        }
    }

    /** Timeline preview: puts the scene's actors where they are at a tick and keeps them there. */
    public void preview(String name, int tick) {
        if (recording()) return;
        for (Actor a : cast(name)) {
            boolean busy = false;
            for (SceneRun o : runs) if (o.cast.contains(a)) busy = true;
            if (busy) continue;
            Track t = a.track(name);
            Track.Take tk = t.take();
            Recording.Frame f = tk.frame(Math.max(tick, tk.offset));
            if (!previewing.containsKey(a)) previewing.put(a, new double[]{a.x(), a.y(), a.z(), a.yaw(), a.homeX, a.homeY, a.homeZ, a.homeYaw});
            a.sceneTrack = t; a.sceneTake = tk;
            a.mode = Actor.Mode.ON_MARK;
            a.markX = f.x; a.markY = f.y; a.markZ = f.z; a.markYaw = f.yaw;
            a.place(f.x, f.y, f.z, f.yaw);
            a.body.pitch = a.prevPitch = f.pitch;      // also right while the world is paused (no tick to catch up)
            a.applyFlags(f);
            if (f.vehicle >= 0 && s.vehicles != null) s.vehicles.replay(a, f.vehicle, f.seat, f.x, f.y, f.z, f.vyaw, f.vpitch, f.vroll, f.steer, f.doors, f.vflip, f.vslip, f.vflags, true);
            Object[] pk = t.poseAt(tick);
            a.keyFrom = pk == null ? null : (String) pk[0]; a.keyTo = pk == null ? null : (String) pk[1]; a.keyT = pk == null ? 0 : (float) pk[2];
        }
    }

    /** Lets previewed actors go back to their AI. */
    public void releasePreview() {
        for (var e : previewing.entrySet()) {
            Actor a = e.getKey();
            double[] o = e.getValue();
            boolean busy = a == recordingActor;
            for (SceneRun r : runs) if (r.cast.contains(a)) busy = true;
            if (busy || a.mode != Actor.Mode.ON_MARK) continue;
            a.toAI();
            a.place(o[0], o[1], o[2], (float) o[3]);     // back where it was before the preview
            a.homeX = o[4]; a.homeY = o[5]; a.homeZ = o[6]; a.homeYaw = (float) o[7];
        }
        previewing.clear();
    }

    /** Blocks changed by the scene's takes, oldest first, with the scene tick they happen at. */
    private List<Object[]> sceneBlocks(String scene) {
        List<Object[]> all = new ArrayList<>();
        for (Actor a : all()) {
            Track t = a.track(scene);
            Track.Take tk = t == null ? null : t.take();
            if (tk == null) continue;
            for (Recording.BlockAction b : tk.rec.blocks) {
                if (!tk.inside(b.tick())) continue;
                int[] p = tk.transformBlock(b.x(), b.y(), b.z());
                all.add(new Object[]{(int) Math.floor(tk.sceneTick(b.tick())), p, b});
            }
        }
        all.sort(Comparator.comparingInt(o -> (int) o[0]));
        return all;
    }

    private void revertBlocks(String scene) {
        derpcraft.gun.Ballistics.DOOR_HITS.clear();          // the cracks of shot doors go with the blocks
        derpcraft.gun.Ballistics.BLOCK_HITS.clear();
        List<Object[]> all = sceneBlocks(scene);
        for (int i = all.size() - 1; i >= 0; i--) {
            int[] p = (int[]) all.get(i)[1];
            s.world.setBlock(p[0], p[1], p[2], ((Recording.BlockAction) all.get(i)[2]).before());
        }
    }

    /** Blocks as they are at a tick of the scene (for starting in the middle). */
    private void applyBlocksBefore(String scene, int tick) {
        for (Object[] o : sceneBlocks(scene)) {
            if ((int) o[0] >= tick) break;
            int[] p = (int[]) o[1];
            s.world.setBlock(p[0], p[1], p[2], ((Recording.BlockAction) o[2]).after());
        }
    }

    private void tickRun(SceneRun run) {
        if (run.gathering) {
            run.gatherTicks++;
            boolean all = true;
            for (Actor a : run.cast) if (a.mode == Actor.Mode.GOTO_MARK) all = false;
            if (!all && run.gatherTicks < 600) return;
            for (Actor a : run.cast) { a.snapToMark(); a.mode = Actor.Mode.ON_MARK; }   // stragglers teleport after 30 s
            revertBlocks(run.name);
            if (run.start > 0) applyBlocksBefore(run.name, run.start);
            run.gathering = false;
            run.tick = run.start;
            for (Actor a : run.cast) a.mode = Actor.Mode.PLAYING;
            if (linkCamera && run.name.equalsIgnoreCase(currentScene)) {
                if (!camKeys(run.name).isEmpty()) applyCamera(run.name, run.tick, true);
                else if (s.cam.keys.size() >= 2) s.cam.play();
            }
        }
        if (linkCamera && run.name.equalsIgnoreCase(currentScene) && run.tick > run.start) applyCamera(run.name, run.tick, false);
        for (Actor a : run.cast) {
            if (a.mode != Actor.Mode.PLAYING || a.sceneTake == null) continue;
            Track.Take tk = a.sceneTake;
            if (run.tick >= tk.end()) { a.toAI(); continue; }       // this take is over: back to the AI
            playActor(a, a.sceneTrack, tk, run.tick);
        }
        run.tick++;
    }

    /** One scene tick of a take: the frame, block changes, item actions, lines and pose keys. */
    private void playActor(Actor a, Track t, Track.Take tk, int tick) {
        Object[] pk = t == null ? null : t.poseAt(tick);
        a.keyFrom = pk == null ? null : (String) pk[0]; a.keyTo = pk == null ? null : (String) pk[1]; a.keyT = pk == null ? 0 : (float) pk[2];
        if (tick < tk.offset) { a.hold(); a.body.yaw = a.markYaw; return; }      // waits for its start on the timeline
        Recording.Frame f = tk.frame(tick);
        int src = (int) Math.floor(tk.sourceTick(tick));
        Recording.Frame raw = tk.rec.frame(src);
        boolean swing = src != a.lastSrc && raw != null && (raw.flags & Recording.FLAG_SWING) != 0;
        a.lastSrc = src;
        // the take is about to put the actor into a car: it walks to the door, opens it and gets in
        if (f.vehicle < 0 && s.vehicles != null && !s.vehicles.boarding(a)) {
            int lead = 0, max = derpcraft.vehicle.Vehicles.BOARD_TICKS;
            if (tick == tk.offset) { for (int j = 1; j <= max; j++) if (tk.frame(tick + j).vehicle >= 0) { lead = j; break; } }
            else if (tk.frame(tick + max).vehicle >= 0 && tk.frame(tick + max - 1).vehicle < 0) lead = max;
            if (lead >= 4) {
                Recording.Frame vf = tk.frame(tick + lead);
                s.vehicles.beginEnter(a, vf.vehicle, vf.seat, vf.x, vf.y, vf.z, vf.vyaw, vf.vpitch, vf.vroll, lead);
            }
        }
        a.playFrame(f, swing);
        if (f.vehicle >= 0 && s.vehicles != null) s.vehicles.replay(a, f.vehicle, f.seat, f.x, f.y, f.z, f.vyaw, f.vpitch, f.vroll, f.steer, f.doors, f.vflip, f.vslip, f.vflags, false);
        for (var b : tk.rec.blocks) {
            if (!tk.inside(b.tick()) || (int) Math.floor(tk.sceneTick(b.tick())) != tick) continue;
            int[] p = tk.transformBlock(b.x(), b.y(), b.z());
            s.world.setBlock(p[0], p[1], p[2], b.after());
        }
        for (var c : tk.rec.chat) if (tk.inside(c.tick()) && (int) Math.floor(tk.sceneTick(c.tick())) == tick && recordChat) s.chat("<" + a.name + "> " + c.text());
        for (var it : tk.rec.items) if (tk.inside(it.tick()) && (int) Math.floor(tk.sceneTick(it.tick())) == tick) itemAction(a, tk, it);
    }

    /** Replays a recorded pickup / drop / shot. */
    private void itemAction(Actor a, Track.Take tk, Recording.ItemAction it) {
        double ex = a.x(), ey = a.y() + 1.5 * a.scale, ez = a.z();
        switch (it.kind()) {
            case 0 -> {   // picked up: the nearest such item next to the actor disappears
                derpcraft.entity.ItemEntity best = null;
                double bd = 3 * 3;
                for (var e : s.world.entities)
                    if (e instanceof derpcraft.entity.ItemEntity ie && !ie.dead && ie.stack.item.name.equals(it.item())) {
                        double d = ie.distSq(a.x(), a.y() + 0.5, a.z());
                        if (d < bd) { bd = d; best = ie; }
                    }
                if (best != null) best.dead = true;
            }
            case 1 -> {   // dropped
                var item = derpcraft.item.Items.byName(it.item());
                if (item == null) return;
                double[] d = tk.transformDir(it.dx(), it.dz());
                var ie = new derpcraft.entity.ItemEntity(s.world, ex, ey - 0.3, ez, new ItemStack(item, Math.max(1, it.count())));
                ie.mx = d[0] * 0.3; ie.my = it.dy() * 0.3 + 0.1; ie.mz = d[1] * 0.3;
                ie.pickupDelay = 40;
                s.world.entities.add(ie);
            }
            case 3 -> {   // a gun shot: the same direction (moved / turned with the take), bullets hit and show it, no damage
                var gun = derpcraft.gun.Guns.get(it.item());
                if (gun == null || gun.model() == null) return;
                double[] d = tk.transformDir(it.dx(), it.dz());
                double l = Math.sqrt(d[0] * d[0] + it.dy() * it.dy() + d[1] * d[1]) + 1e-9;
                a.gunShot(gun, d[0] / l, it.dy() / l, d[1] / l, replayGunDamage, true);
            }
            case 4 -> {   // a reload (count 1 = empty: with the bolt)
                var gun = derpcraft.gun.Guns.get(it.item());
                if (gun != null) a.gunReload(gun, it.count() == 1);
            }
            case 5 -> {   // a melee strike with the gun (count = its kind); what it broke is in the take's block changes
                var gun = derpcraft.gun.Guns.get(it.item());
                if (gun != null) a.gunMelee(gun, it.count());
            }
            default -> {  // threw / shot a projectile
                double[] d = tk.transformDir(it.dx(), it.dz());
                var p = s.mobs.shoot(it.item(), ex + d[0] * 0.3, ey - 0.1, ez + d[1] * 0.3, d[0], it.dy(), d[1], it.count() / 100f, false, null);
                if (p != null) p.ownerNpc = a;
                a.swing();
            }
        }
    }

    /** An NPC uses the item in its hand at a target (throws / shoots it, or the item's own NPC use). */
    void useItem(Actor a, derpcraft.item.Item it, double ex, double ey, double ez, double tx, double ty, double tz) {
        if (it.npcUse != null) {
            Session.safe(() -> it.npcUse.use(s, a.name, ex, ey, ez, tx, ty, tz));
            return;
        }
        double dx = tx - ex, dy = ty - ey, dz = tz - ez, dist = Math.sqrt(dx * dx + dy * dy + dz * dz) + 1e-6;
        var pt = derpcraft.entity.ProjectileType.get(it.npcProjectile);
        float g = pt == null ? 0.03f : pt.gravity, speed = Math.max(0.2f, it.npcSpeed);
        double ticks = dist / speed;
        double aimY = dy + 0.5 * g * ticks * ticks * 0.9;      // aim higher for gravity
        double l = Math.sqrt(dx * dx + aimY * aimY + dz * dz) + 1e-6;
        var p = s.mobs.shoot(it.npcProjectile, ex + dx / dist * 0.4, ey - 0.1, ez + dz / dist * 0.4, dx / l, aimY / l, dz / l, speed, false, null);
        if (p != null) p.ownerNpc = a;
    }

    /** Line of sight between two points (no opaque block in between). */
    boolean canSee(double x0, double y0, double z0, double x1, double y1, double z1) {
        // every cell on the line (the first block hit used to decide: tall grass or glass in front made a wall behind it invisible)
        return derpcraft.game.Raycast.clearLine(s.world, x0, y0, z0, x1, y1, z1);
    }

    // ---------------------------------------------------------------- camera cuts
    /** Applies the camera cut active at a tick ('start' = also the cut before the first tick). */
    private void applyCamera(String scene, int tick, boolean start) {
        CamKey k = null;
        for (CamKey c : camKeys(scene)) {
            if (start ? c.tick <= tick : c.tick == tick) { if (k == null || c.tick >= k.tick) k = c; }
        }
        if (k == null) return;
        var cam = s.cam;
        switch (k.type) {
            case 1 -> {
                if (k.ref < 0 || k.ref >= shots.size()) return;
                Shot sh = shots.get(k.ref);
                cam.startFree(sh.x, sh.y, sh.z, sh.yaw, sh.pitch);
                if (sh.focal > 0) cam.focal = cam.focalTarget = sh.focal;
            }
            case 2 -> { cam.follow(k.ref, false); placeFollowCamera(k.ref); }
            case 3 -> cam.follow(k.ref, true);
            case 4 -> { if (cam.keys.size() >= 2) cam.play(); }
            default -> cam.toPlayer();
        }
    }

    /** A follow camera starts a few blocks behind and above the actor if it was not free yet. */
    private void placeFollowCamera(int id) {
        Actor a = byId(id);
        if (a == null) return;
        double yr = Math.toRadians(a.yaw());
        s.cam.x = a.x() + Math.sin(yr) * 4; s.cam.y = a.y() + 2.4; s.cam.z = a.z() - Math.cos(yr) * 4;
    }

    /** Camera target of follow / POV cameras: {x, y, z, eyeY, yaw, pitch} (interpolated), or null. */
    public double[] cameraTarget(int id, float partial) {
        Actor a = byId(id);
        if (a == null || !inWorld(a)) return null;
        var p = a.pose(partial, skins);
        double eye = p.y + (p.sneaking ? 1.27 : 1.62) * a.scale + (p.poseTo != null ? p.poseTo.lift * a.scale : 0);
        return new double[]{p.x, p.y, p.z, eye, p.bodyYaw + p.headYaw, p.pitch};
    }

    // ================================================================ recording
    /** Records 'a' for the current scene from the start. */
    public void startRecording(Actor a) { startRecording(a, 0); }

    /**
     * Records 'a' for the current scene starting at a tick of the timeline. If the actor already has a take that
     * plays at that moment, the new take keeps it up to there and you take over from that point (punch-in).
     * The scene's other actors play along. A new take is added; older takes are kept (pick one on the timeline).
     */
    public void startRecording(Actor a, int fromTick) {
        if (recording()) cancelRecording();
        stopScene();
        releasePreview();
        currentScene = a.group;          // a take always belongs to the NPC's own group (= its scene)
        recScene = a.group;
        fromTick = Math.max(0, fromTick);
        Track tr = a.trackOrNew(recScene);
        Track.Take base = tr.take();
        revertBlocks(recScene);
        if (fromTick > 0) applyBlocksBefore(recScene, fromTick);
        take = new Recording();
        boolean punch = fromTick > 0 && base != null && fromTick > base.offset && fromTick < base.end();
        recTake = new Track.Take(take, null);
        recTake.offset = punch ? base.offset : fromTick;
        if (punch) {
            // keep the old take up to here (timed, moved and turned the way it plays)
            for (int t = base.offset; t < fromTick; t++) take.frames.add(base.frame(t));
            for (var b : base.rec.blocks) if (base.inside(b.tick()) && base.sceneTick(b.tick()) < fromTick) {
                int[] p = base.transformBlock(b.x(), b.y(), b.z());
                take.blocks.add(new Recording.BlockAction((int) (base.sceneTick(b.tick()) - base.offset), p[0], p[1], p[2], b.before(), b.after()));
            }
            for (var it : base.rec.items) if (base.inside(it.tick()) && base.sceneTick(it.tick()) < fromTick) {
                double[] d = base.transformDir(it.dx(), it.dz());
                take.items.add(new Recording.ItemAction((int) (base.sceneTick(it.tick()) - base.offset), it.kind(), it.item(), it.count(), it.x(), it.y(), it.z(), d[0], it.dy(), d[1]));
            }
            Recording.Frame f = base.frame(fromTick);
            s.player.setPos(f.x, f.y, f.z);
            s.player.yaw = f.yaw; s.player.pitch = f.pitch;
            recordPose = f.pose; recordEmotion = f.emotion;
        } else { recordPose = ""; recordEmotion = 0; }
        recordingActor = a;
        recCopiedBlocks = take.blocks.size();
        recCast.clear();
        for (Actor o : actors) {
            Track ot = o.track(recScene);
            Track.Take tk = ot == null ? null : ot.take();
            if (o == a || tk == null || !o.sceneEnabled) continue;
            if (fromTick >= tk.end()) continue;
            if (o.dead()) o.revive();
            o.sceneTrack = ot; o.sceneTake = tk; o.lastSrc = -1;
            Recording.Frame f = tk.frame(Math.max(fromTick, tk.offset));
            o.markX = f.x; o.markY = f.y; o.markZ = f.z; o.markYaw = f.yaw;
            o.snapToMark();
            o.mode = Actor.Mode.ON_MARK;
            recCast.add(o);
        }
        a.mode = Actor.Mode.ON_MARK;
        countdown = Math.max(0, countdownSeconds) * 20;
        takeStarted = false;
        recTick = fromTick;
        recStart = fromTick;
        if (countdown == 0) beginTake();
    }

    private int recStart;

    private void beginTake() {
        takeStarted = true;
        recTick = recStart;
        for (Actor o : recCast) o.mode = othersPlayWhileRecording ? Actor.Mode.PLAYING : Actor.Mode.ON_MARK;
    }

    /** Stops and keeps the take (a new take of the actor's track in the current scene). Returns the actor, whose take(scene) is null if nothing was kept. */
    public Actor stopRecording() {
        Actor a = recordingActor;
        if (a == null) return null;
        recordingActor = null;
        recNewActor = null;
        if (!takeStarted || take.length() < 2) {
            take = null;
            finishRecordingCast(a);
            Track t = a.track(recScene);
            if (t != null && t.takes.isEmpty()) a.removeTrack(recScene);
            return a;
        }
        String file = takeFile(a, recScene);
        try { take.save(dir.resolve("recordings").resolve(file)); } catch (IOException e) { s.chat("§cCould not save the take: " + e.getMessage()); }
        recTake.file = file;
        Track tr = a.trackOrNew(recScene);
        tr.takes.add(recTake);
        tr.active = tr.takes.size() - 1;
        Recording.Frame last = take.frame(take.length() - 1);
        a.place(last.x, last.y, last.z, last.yaw);
        take = null;
        recTake = null;
        finishRecordingCast(a);
        save();
        return a;
    }

    private String takeFile(Actor a, String scene) {
        String base = "actor" + a.id + "-" + scene.replaceAll("[^A-Za-z0-9_-]", "_");
        String f = base + ".rec";
        for (int i = 2; Files.exists(dir.resolve("recordings").resolve(f)) || fileUsed(f); i++) f = base + "-" + i + ".rec";
        return f;
    }

    private boolean fileUsed(String f) {
        for (Actor a : all()) for (Track t : a.tracks.values()) for (Track.Take tk : t.takes) if (f.equals(tk.file)) return true;
        return false;
    }

    private void deleteFile(String f) {
        if (f == null) return;
        try { Files.deleteIfExists(dir.resolve("recordings").resolve(f)); } catch (IOException ignored) {}
    }

    private void finishRecordingCast(Actor a) {
        a.toAI();
        for (Actor o : recCast) o.toAI();
        recCast.clear();
        recordPose = ""; recordEmotion = 0;
    }

    /**
     * Throws the recording away: the actor keeps the takes it had, blocks the player changed while recording are put
     * back. An actor made just for this recording (record key / + Record new) is removed. Returns true if it was.
     */
    public boolean cancelRecording() {
        Actor a = recordingActor;
        recordingActor = null;
        Recording r = take;
        take = null;
        recTake = null;
        if (r != null) for (int i = r.blocks.size() - 1; i >= recCopiedBlocks; i--) {
            var b = r.blocks.get(i);
            s.world.setBlock(b.x(), b.y(), b.z(), b.before());
        }
        boolean removed = false;
        if (a != null) {
            finishRecordingCast(a);
            Track t = a.track(recScene);
            if (t != null && t.takes.isEmpty()) a.removeTrack(recScene);
            if (a == recNewActor && !a.hasTakes()) { remove(a); removed = true; }
        }
        recNewActor = null;
        return removed;
    }

    /** An actor created only to be recorded: removed again if the recording is cancelled. */
    public Actor recNewActor;
    private int recCopiedBlocks;

    /** Deletes the active take of an actor in a scene (older takes stay). */
    public void clearTake(Actor a, String scene) {
        Track t = a.track(scene);
        if (t == null) return;
        Track.Take tk = t.take();
        if (tk != null) { t.takes.remove(tk); deleteFile(tk.file); }
        t.active = Math.max(0, Math.min(t.active, t.takes.size() - 1));
        if (t.takes.isEmpty()) a.removeTrack(scene);
        save();
    }

    private void blockChanged(int x, int y, int z, int before, int after) {
        if (take != null && takeStarted && playerActing && recordBlocks) take.blocks.add(new Recording.BlockAction(Math.max(0, take.length() - 1), x, y, z, before, after));
    }

    /** Replayed gun shots hurt (on: actors in a scene can shoot each other dead; off: they only show the hits). */
    public boolean replayGunDamage;

    /** The player picked up (0), dropped (1), shot / threw (2) something, fired a gun (3), reloaded one (4) or struck with one (5) while recording. */
    public void recordItem(int kind, String item, int count, double dx, double dy, double dz) {
        if (take == null || !takeStarted) return;
        var p = s.player;
        take.items.add(new Recording.ItemAction(Math.max(0, take.length() - 1), kind, item, count, p.x, p.y, p.z, dx, dy, dz));
    }

    /** Chat from the player: becomes a line while recording (only if chat lines are on). Returns the text to show, or null for normal chat. */
    public String chatLine(String text) {
        if (take == null || !takeStarted || !recordChat) return null;
        take.chat.add(new Recording.ChatAction(Math.max(0, take.length() - 1), text));
        return "<" + recordingActor.name + "> " + text;
    }

    // ================================================================ ticking
    public void tick() {
        tickCount++;
        World w = s.world;
        if (recordingActor != null) {
            if (countdown > 0) {
                countdown--;
                if (countdown % 20 == 0 && countdown > 0) s.chat("§c" + countdown / 20 + "...");
                if (countdown == 0) { beginTake(); s.chat("§cRecording " + recordingActor.name + " §7(scene \"" + recScene + "\", " + derpcraft.game.Keys.n(derpcraft.game.Keys.RECORD) + " = stop, " + derpcraft.game.Keys.n(derpcraft.game.Keys.RECORD_CANCEL) + " = cancel)"); }
            } else {
                var p = s.player;
                Recording.Frame f = new Recording.Frame();
                f.x = p.x; f.y = p.y; f.z = p.z; f.yaw = p.yaw; f.pitch = p.pitch;
                int flags = 0;
                if (p.sneaking) flags |= Recording.FLAG_SNEAK;
                if (p.sprinting) flags |= Recording.FLAG_SPRINT;
                if (p.flying) flags |= Recording.FLAG_FLYING;
                if (p.onGround) flags |= Recording.FLAG_GROUND;
                if (s.swingStartedThisTick()) flags |= Recording.FLAG_SWING;
                if (s.backReaching()) flags |= Recording.FLAG_BACK_REACH;
                float[] vs = s.vehicles == null ? null : s.vehicles.recordState();
                if (vs != null) {
                    flags |= Recording.FLAG_SIT;
                    f.vehicle = (int) vs[0]; f.seat = (int) vs[1]; f.vyaw = vs[2]; f.vpitch = vs[3]; f.vroll = vs[4]; f.steer = vs[5]; f.doors = (int) vs[6]; f.vflip = vs[7]; f.vslip = vs[8]; f.vflags = (int) vs[9];
                }
                f.flags = flags;
                ItemStack h = s.heldStack();
                f.held = h == null ? "" : h.item.name;
                ItemStack bk = s.backStack();
                f.back = bk == null ? "" : bk.item.name;
                f.pouches = s.pouchString();
                f.pose = recordPose == null ? "" : recordPose;
                f.emotion = recordEmotion;
                take.frames.add(f);
                for (Actor o : recCast) {
                    if (o.mode != Actor.Mode.PLAYING) continue;
                    if (recTick >= o.sceneTake.end()) o.toAI();
                    else playActor(o, o.sceneTrack, o.sceneTake, recTick);
                }
                recTick++;
                if (take.length() >= 20 * 60 * 20) { s.chat("§eRecording stopped: 20 minutes limit"); stopRecording(); }
            }
        }
        for (Iterator<SceneRun> it = runs.iterator(); it.hasNext(); ) {
            SceneRun run = it.next();
            tickRun(run);
            if (!run.gathering && run.tick >= run.length) {
                for (Actor a : run.cast) if (a.mode != Actor.Mode.AI) a.toAI();
                it.remove();
                for (Events.SceneHandler h : ModContext.SCENE_END) {
                    try { h.onSceneEnd(s); } catch (Throwable t) { t.printStackTrace(); }
                }
                if (loopScene) { String name = run.name; s.schedule(1, () -> playScene(name)); }
            }
        }
        chooseTargets();
        if (s.vehicles != null) s.vehicles.finishReplays();
        for (Actor a : actors) {
            if (a == recordingActor) { a.hold(); continue; }
            switch (a.mode) {
                case AI, GOTO_MARK -> a.tickAI(this, random);
                case ON_MARK -> { a.hold(); a.body.yaw = a.markYaw; }
                default -> {}
            }
        }
        separate();
        if (s.vehicles != null) s.vehicles.afterNpcs();
    }

    private void separate() {
        var p = s.player;
        for (int i = 0; i < actors.size(); i++) {
            Actor a = actors.get(i);
            if (a.mode != Actor.Mode.AI || a.dead()) continue;
            push(a, p.x, p.y, p.z, 0.7);
            for (int j = i + 1; j < actors.size(); j++) {
                Actor b = actors.get(j);
                if (b.mode != Actor.Mode.AI || b.dead()) continue;
                double dx = a.x() - b.x(), dz = a.z() - b.z();
                if (Math.abs(a.y() - b.y()) > 1.5 || dx * dx + dz * dz > 0.36) continue;
                push(a, b.x(), b.y(), b.z(), 0.6);
                push(b, a.x(), a.y(), a.z(), 0.6);
            }
        }
    }

    private void push(Actor a, double ox, double oy, double oz, double min) {
        double dx = a.x() - ox, dz = a.z() - oz, d2 = dx * dx + dz * dz;
        if (d2 >= min * min || Math.abs(a.y() - oy) > 1.5) return;
        double d = Math.sqrt(d2);
        if (d < 1e-3) { dx = random.nextDouble() - 0.5; dz = random.nextDouble() - 0.5; d = Math.sqrt(dx * dx + dz * dz); }
        a.body.mx += dx / d * 0.05;
        a.body.mz += dz / d * 0.05;
    }

    // ================================================================ rendering
    public void collectPoses(List<derpcraft.render.EntityRenderer.PlayerPose> out, float partial) {
        for (Actor a : actors) {
            if (a == recordingActor) continue;   // you are playing this one
            if (a.id == s.cam.followId && s.cam.mode == derpcraft.game.CameraRig.Mode.POV) continue;   // the camera is in its head
            var pose = a.pose(partial, skins);
            if (s.vehicles != null) s.vehicles.riderPose(a, pose, partial);
            out.add(pose);
        }
    }

    public void setCamera(Mat4 projView, double x, double y, double z) {
        lastProjView = new Mat4().set(projView);
        camX = x; camY = y; camZ = z;
    }

    /** Screen position (0..1, y down) and distance of a name tag above an actor, or null. */
    public float[] nameTagPos(Actor a) {
        if (lastProjView == null) return null;
        double rx = a.x() - camX, ry = a.y() + (a.dead() ? 0.8 : 2.1) - camY, rz = a.z() - camZ;
        double d2 = rx * rx + ry * ry + rz * rz;
        if (d2 > 48 * 48) return null;
        double d = Math.sqrt(d2);
        var hit = derpcraft.game.Raycast.cast(s.world, camX, camY, camZ, rx / d, ry / d, rz / d, d, false);
        if (hit != null && hit.block.solid && Math.sqrt(Math.pow(hit.hx - camX, 2) + Math.pow(hit.hy - camY, 2) + Math.pow(hit.hz - camZ, 2)) < d - 0.3) return null;
        float[] m = lastProjView.m;
        float cx = (float) (m[0] * rx + m[4] * ry + m[8] * rz + m[12]);
        float cy = (float) (m[1] * rx + m[5] * ry + m[9] * rz + m[13]);
        float cw = (float) (m[3] * rx + m[7] * ry + m[11] * rz + m[15]);
        if (cw <= 0.05f) return null;
        return new float[]{(cx / cw) * 0.5f + 0.5f, 1 - ((cy / cw) * 0.5f + 0.5f), (float) d};
    }

    // ================================================================ files
    public void save() {
        Properties p = new Properties();
        p.setProperty("nextId", String.valueOf(nextId));
        p.setProperty("loopScene", String.valueOf(loopScene));
        p.setProperty("linkCamera", String.valueOf(linkCamera));
        p.setProperty("othersPlay", String.valueOf(othersPlayWhileRecording));
        p.setProperty("recordBlocks", String.valueOf(recordBlocks));
        p.setProperty("recordChat", String.valueOf(recordChat));
        p.setProperty("replayGunDamage", String.valueOf(replayGunDamage));
        p.setProperty("walkToMarks", String.valueOf(walkToMarks));
        p.setProperty("attackCreative", String.valueOf(attackCreative));
        p.setProperty("countdown", String.valueOf(countdownSeconds));
        p.setProperty("currentScene", currentScene);
        StringBuilder rel = new StringBuilder();
        for (int i = 0; i < 3; i++) for (int j = 0; j < 3; j++) rel.append(attacks[i][j] ? '1' : '0');
        p.setProperty("attacks", rel.toString());
        int gi = 0;
        for (String g : groups()) { p.setProperty("group" + gi + ".name", g); p.setProperty("group" + gi + ".on", String.valueOf(groupEnabled(g))); gi++; }
        StringBuilder ids = new StringBuilder();
        for (Actor a : all()) {
            String k = "a" + a.id + ".";
            if (ids.length() > 0) ids.append(',');
            ids.append(a.id);
            p.setProperty(k + "name", a.name);
            p.setProperty(k + "skin", a.skin);
            p.setProperty(k + "behavior", a.behavior.name());
            p.setProperty(k + "pose", a.pose.name());
            p.setProperty(k + "held", a.held == null ? "" : a.held.item.name);
            p.setProperty(k + "showName", String.valueOf(a.showName));
            p.setProperty(k + "home", a.homeX + "," + a.homeY + "," + a.homeZ + "," + a.homeYaw);
            p.setProperty(k + "pos", a.x() + "," + a.y() + "," + a.z() + "," + a.yaw());
            p.setProperty(k + "faction", a.faction.name());
            p.setProperty(k + "onHit", a.onHit.name());
            p.setProperty(k + "guard", a.guard.name());
            p.setProperty(k + "ai", a.aggroRange + "," + a.followStart + "," + a.followStop + "," + a.maxHealth + "," + a.health + "," + a.damage);
            p.setProperty(k + "abilities", a.openDoors + "," + a.breakBlocks + "," + a.respawn);
            p.setProperty(k + "group", a.group);
            p.setProperty(k + "enabled", String.valueOf(a.enabled));
            p.setProperty(k + "sceneEnabled", String.valueOf(a.sceneEnabled));
            p.setProperty(k + "look", a.customPose + "|" + a.emotion + "|" + a.blink + "|" + a.scale);
            p.setProperty(k + "offhand", a.offhand == null ? "" : a.offhand.item.name);
            if (!a.attachments.isEmpty()) p.setProperty(k + "attach", Attachment.encode(a.attachments));
            StringBuilder ar = new StringBuilder();
            for (int i = 0; i < 4; i++) { if (i > 0) ar.append(','); ar.append(a.armor[i] == null ? "" : a.armor[i].item.name); }
            p.setProperty(k + "armor", ar.toString());
            StringBuilder wp = new StringBuilder();
            for (double[] w : a.waypoints) { if (wp.length() > 0) wp.append(';'); wp.append(w[0]).append(',').append(w[1]).append(',').append(w[2]); }
            p.setProperty(k + "patrol", a.patrolLoop + "|" + a.patrolWait + "|" + wp);
            p.setProperty(k + "seat", a.seat == null ? "" : a.seat[0] + "," + a.seat[1] + "," + a.seat[2]);
            p.setProperty(k + "watch", a.watchId + "|" + a.rideId + "|" + a.rideCreature);
            p.setProperty(k + "vehicle", a.vehicleId + "|" + a.vehicleSeat);
            int t = 0;
            for (var e : a.tracks.entrySet()) {
                Track tr = e.getValue();
                if (tr.takes.isEmpty()) continue;
                String tk = k + "track" + t + ".";
                p.setProperty(tk + "scene", e.getKey());
                p.setProperty(tk + "active", String.valueOf(tr.active));
                StringBuilder keys = new StringBuilder();
                for (Track.PoseKey pk : tr.keys) { if (keys.length() > 0) keys.append(';'); keys.append(pk.tick).append('|').append(pk.pose).append('|').append(pk.curve).append('|').append(pk.blend); }
                p.setProperty(tk + "keys", keys.toString());
                for (int j = 0; j < tr.takes.size(); j++) {
                    Track.Take x = tr.takes.get(j);
                    p.setProperty(tk + "take" + j + ".file", x.file == null ? "" : x.file);
                    p.setProperty(tk + "take" + j + ".timing", x.offset + "," + x.trimStart + "," + x.trimEnd + "," + x.speed + "," + x.dx + "," + x.dy + "," + x.dz + "," + x.rot);
                }
                t++;
            }
        }
        p.setProperty("actors", ids.toString());
        for (int i = 0; i < shots.size(); i++) {
            Shot sh = shots.get(i);
            p.setProperty("shot" + i, sh.name + "|" + sh.x + "|" + sh.y + "|" + sh.z + "|" + sh.yaw + "|" + sh.pitch + "|" + sh.focal);
        }
        int ci = 0;
        for (var e : camKeys.entrySet()) {
            if (e.getValue().isEmpty()) continue;
            StringBuilder sb = new StringBuilder();
            for (CamKey c : e.getValue()) { if (sb.length() > 0) sb.append(';'); sb.append(c.tick).append(',').append(c.type).append(',').append(c.ref); }
            p.setProperty("cam" + ci + ".scene", e.getKey());
            p.setProperty("cam" + ci + ".keys", sb.toString());
            ci++;
        }
        try {
            derpcraft.util.SafeFiles.store(p, dir.resolve("actors.txt"), "DerpCraft NPCs");
        } catch (IOException e) { System.err.println("Cannot save NPCs: " + e); }
    }

    private void load() {
        Path f = dir.resolve("actors.txt");
        if (!Files.exists(f)) return;
        Properties p = new Properties();
        try (Reader r = Files.newBufferedReader(f)) { p.load(r); } catch (IOException e) { return; }
        nextId = (int) num(p.getProperty("nextId"), 1);
        loopScene = Boolean.parseBoolean(p.getProperty("loopScene", "false"));
        linkCamera = Boolean.parseBoolean(p.getProperty("linkCamera", "true"));
        othersPlayWhileRecording = Boolean.parseBoolean(p.getProperty("othersPlay", "true"));
        recordBlocks = Boolean.parseBoolean(p.getProperty("recordBlocks", "true"));
        recordChat = Boolean.parseBoolean(p.getProperty("recordChat", "false"));
        replayGunDamage = Boolean.parseBoolean(p.getProperty("replayGunDamage", "false"));
        walkToMarks = Boolean.parseBoolean(p.getProperty("walkToMarks", "true"));
        attackCreative = Boolean.parseBoolean(p.getProperty("attackCreative", "false"));
        countdownSeconds = (int) num(p.getProperty("countdown"), 3);
        currentScene = p.getProperty("currentScene", "main");
        for (int gi = 0; p.getProperty("group" + gi + ".name") != null; gi++)
            groups.put(p.getProperty("group" + gi + ".name"), Boolean.parseBoolean(p.getProperty("group" + gi + ".on", "true")));
        for (int i = 0; p.getProperty("shot" + i) != null; i++) {
            String[] v = p.getProperty("shot" + i).split("\\|");
            if (v.length >= 7) shots.add(new Shot(v[0], num(v[1], 0), num(v[2], 0), num(v[3], 0), (float) num(v[4], 0), (float) num(v[5], 0), (float) num(v[6], 0)));
        }
        for (int i = 0; p.getProperty("cam" + i + ".scene") != null; i++) {
            List<CamKey> l = camKeys(p.getProperty("cam" + i + ".scene"));
            for (String kv : p.getProperty("cam" + i + ".keys", "").split(";")) {
                double[] v = nums(kv, 3);
                if (!kv.isBlank()) l.add(new CamKey((int) v[0], (int) v[1], (int) v[2]));
            }
        }
        String rel = p.getProperty("attacks");
        if (rel != null && rel.length() == 9) for (int i = 0; i < 9; i++) attacks[i / 3][i % 3] = rel.charAt(i) == '1';
        for (String sid : p.getProperty("actors", "").split(",")) {
            if (sid.isBlank()) continue;
            int id = (int) num(sid, -1);
            if (id < 0) continue;
            String k = "a" + id + ".";
            Actor a = new Actor(id, p.getProperty(k + "name", "Actor " + id));
            a.skin = p.getProperty(k + "skin", "Steve");
            try { a.behavior = Actor.Behavior.valueOf(p.getProperty(k + "behavior", "LOOK")); } catch (Exception ignored) {}
            try { a.pose = Actor.Pose.valueOf(p.getProperty(k + "pose", "NORMAL")); } catch (Exception ignored) {}
            try { a.faction = Actor.Faction.valueOf(p.getProperty(k + "faction", "NEUTRAL")); } catch (Exception ignored) {}
            try { a.onHit = Actor.OnHit.valueOf(p.getProperty(k + "onHit", "FIGHT")); } catch (Exception ignored) {}
            try { a.guard = Actor.Guard.valueOf(p.getProperty(k + "guard", "IGNORE")); } catch (Exception ignored) {}
            double[] ai = nums(p.getProperty(k + "ai", "10,6,2.5,20,20,0"), 6);
            a.aggroRange = (float) ai[0]; a.followStart = (float) ai[1]; a.followStop = (float) ai[2];
            a.maxHealth = (float) Math.max(1, ai[3]); a.health = (float) Math.max(1, ai[4]); a.damage = (float) ai[5];
            String[] ab = p.getProperty(k + "abilities", "true,false,true").split(",");
            a.openDoors = Boolean.parseBoolean(ab[0]);
            a.breakBlocks = ab.length > 1 && Boolean.parseBoolean(ab[1]);
            a.respawn = ab.length <= 2 || Boolean.parseBoolean(ab[2]);
            var it = derpcraft.item.Items.byName(p.getProperty(k + "held", ""));
            a.held = it == null ? null : new ItemStack(it, 1);
            a.showName = Boolean.parseBoolean(p.getProperty(k + "showName", "true"));
            double[] home = nums(p.getProperty(k + "home", "0,64,0,0"), 4);
            double[] pos = nums(p.getProperty(k + "pos", p.getProperty(k + "home", "0,64,0,0")), 4);
            a.homeX = home[0]; a.homeY = home[1]; a.homeZ = home[2]; a.homeYaw = (float) home[3];
            a.place(pos[0], pos[1], pos[2], (float) pos[3]);
            // takes (1.4 worlds had a single "recording" = scene "main")
            String old = p.getProperty(k + "recording");
            if (old != null) loadTake(a, "main", old + ".rec");
            for (int t = 0; p.getProperty(k + "take" + t + ".scene") != null; t++)
                loadTake(a, p.getProperty(k + "take" + t + ".scene"), p.getProperty(k + "take" + t + ".file"));
            for (int t = 0; p.getProperty(k + "track" + t + ".scene") != null; t++) {
                String tk = k + "track" + t + ".";
                Track tr = a.trackOrNew(p.getProperty(tk + "scene"));
                for (int j = 0; p.getProperty(tk + "take" + j + ".file") != null; j++) {
                    String file = p.getProperty(tk + "take" + j + ".file");
                    try {
                        Track.Take x = new Track.Take(Recording.load(dir.resolve("recordings").resolve(file)), file);
                        double[] tm = nums(p.getProperty(tk + "take" + j + ".timing", "0,0,0,1,0,0,0,0"), 8);
                        x.offset = (int) tm[0]; x.trimStart = (int) tm[1]; x.trimEnd = (int) tm[2]; x.speed = tm[3] <= 0 ? 1 : (float) tm[3];
                        x.dx = tm[4]; x.dy = tm[5]; x.dz = tm[6]; x.rot = (float) tm[7];
                        tr.takes.add(x);
                    } catch (IOException e) { System.err.println("Cannot load take " + file + ": " + e.getMessage()); }
                }
                tr.active = (int) num(p.getProperty(tk + "active"), 0);
                for (String kv : p.getProperty(tk + "keys", "").split(";")) {
                    String[] v = kv.split("\\|");
                    if (v.length >= 4) tr.keys.add(new Track.PoseKey((int) num(v[0], 0), v[1], (int) num(v[2], 0), (int) num(v[3], 10)));
                }
                if (tr.takes.isEmpty()) a.removeTrack(p.getProperty(tk + "scene"));
            }
            String[] look = p.getProperty(k + "look", "|0|true|1").split("\\|", -1);
            a.customPose = look[0];
            a.emotion = look.length > 1 ? (int) num(look[1], 0) : 0;
            a.blink = look.length <= 2 || Boolean.parseBoolean(look[2]);
            a.scale = look.length > 3 ? (float) Math.max(0.1, num(look[3], 1)) : 1;
            a.attachments.clear();
            a.attachments.addAll(Attachment.decode(p.getProperty(k + "attach", "")));
            var oh = derpcraft.item.Items.byName(p.getProperty(k + "offhand", ""));
            a.offhand = oh == null ? null : new ItemStack(oh, 1);
            String[] ar = p.getProperty(k + "armor", ",,,").split(",", -1);
            for (int i = 0; i < 4 && i < ar.length; i++) { var am = derpcraft.item.Items.byName(ar[i]); a.armor[i] = am == null ? null : new ItemStack(am, 1); }
            String[] pt = p.getProperty(k + "patrol", "true|20|").split("\\|", -1);
            a.patrolLoop = Boolean.parseBoolean(pt[0]);
            a.patrolWait = pt.length > 1 ? (int) num(pt[1], 20) : 20;
            if (pt.length > 2) for (String w : pt[2].split(";")) if (!w.isBlank()) a.waypoints.add(nums(w, 3));
            String seat = p.getProperty(k + "seat", "");
            if (!seat.isBlank()) { double[] sv = nums(seat, 3); a.seat = new int[]{(int) sv[0], (int) sv[1], (int) sv[2]}; }
            String[] wt = p.getProperty(k + "watch", "-1|-1|false").split("\\|");
            a.watchId = (int) num(wt[0], -1);
            a.rideId = wt.length > 1 ? (int) num(wt[1], -1) : -1;
            a.rideCreature = wt.length > 2 && Boolean.parseBoolean(wt[2]);
            String[] vh = p.getProperty(k + "vehicle", "-1|-1").split("\\|");
            a.vehicleId = (int) num(vh[0], -1);
            a.vehicleSeat = vh.length > 1 ? (int) num(vh[1], -1) : -1;
            // group (worlds before 1.8: the scene it has a take for, else the hub's scene)
            String grp = p.getProperty(k + "group");
            if (grp == null || grp.isBlank()) grp = a.tracks.isEmpty() ? currentScene : a.tracks.keySet().iterator().next();
            a.group = grp;
            // a take always belongs to its NPC: one stored under another name than the NPC's group plays with the group
            if (a.track(a.group) == null && a.tracks.size() == 1) {
                Track only = a.tracks.values().iterator().next();
                a.tracks.clear();
                a.tracks.put(a.group, only);
            }
            a.enabled = Boolean.parseBoolean(p.getProperty(k + "enabled", "true"));
            a.sceneEnabled = Boolean.parseBoolean(p.getProperty(k + "sceneEnabled", "true"));
            hidden.add(a);
            nextId = Math.max(nextId, id + 1);
        }
        groups();
        refreshPresence();
    }

    private void loadTake(Actor a, String scene, String file) {
        try {
            Track tr = a.trackOrNew(scene);
            tr.takes.add(new Track.Take(Recording.load(dir.resolve("recordings").resolve(file)), file));
        } catch (IOException e) { System.err.println("Cannot load take " + file + ": " + e.getMessage()); }
    }

    private static double num(String s, double d) { try { return Double.parseDouble(s); } catch (Exception e) { return d; } }

    private static double[] nums(String s, int n) {
        String[] v = s.split(",");
        double[] out = new double[n];
        for (int i = 0; i < n && i < v.length; i++) out[i] = num(v[i], 0);
        return out;
    }
}
