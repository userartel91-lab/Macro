package derpcraft.game;

import derpcraft.api.CameraApi;
import derpcraft.api.GraphicsApi;
import derpcraft.render.post.Grading;

import java.util.*;

/** Implementations of the 1.3 mod APIs on top of the session. */
final class ModApis {
    private ModApis() {}

    static final class Camera implements CameraApi {
        private final Session s;
        Camera(Session s) { this.s = s; }
        private CameraRig c() { return s.cam; }

        @Override public String mode() { return c().mode.name().toLowerCase(Locale.ROOT); }
        @Override public void playerView() { c().stop(); c().toPlayer(); }
        @Override public void freeCamera() { c().startFree(s.lastCamX, s.lastCamY, s.lastCamZ, s.lastCamYaw, s.lastCamPitch); }
        @Override public void freeCamera(double x, double y, double z, float yaw, float pitch) { c().startFree(x, y, z, yaw, pitch); }
        @Override public double x() { return s.lastCamX; }
        @Override public double y() { return s.lastCamY; }
        @Override public double z() { return s.lastCamZ; }
        @Override public float yaw() { return s.lastCamYaw; }
        @Override public float pitch() { return s.lastCamPitch; }

        private void ensureFree() { if (c().mode != CameraRig.Mode.FREE) freeCamera(); }

        @Override public void setPosition(double x, double y, double z) {
            ensureFree();
            c().x = x; c().y = y; c().z = z;
            s.lastCamX = x; s.lastCamY = y; s.lastCamZ = z;
        }

        @Override public void setRotation(float yaw, float pitch) {
            ensureFree();
            c().yaw = yaw; c().pitch = Math.max(-90, Math.min(90, pitch));
            s.lastCamYaw = c().yaw; s.lastCamPitch = c().pitch;
        }

        @Override public void lookAt(double x, double y, double z) {
            ensureFree();
            double dx = x - c().x, dy = y - c().y, dz = z - c().z;
            setRotation((float) Math.toDegrees(Math.atan2(-dx, dz)), (float) -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz))));
        }

        @Override public void setFlySpeed(float bps, float smoothing) { c().speed = Math.max(0.05f, bps); c().smoothing = Math.max(0, Math.min(0.95f, smoothing)); }

        @Override public void orbit(double x, double y, double z, float radius, float height, float dps) {
            CameraRig r = c();
            r.startOrbit(x, y, z, s.lastCamX, s.lastCamY, s.lastCamZ);
            r.orbitPlayer = false;
            r.orbitRadius = Math.max(0.5f, radius); r.orbitHeight = height; r.orbitSpeed = dps;
        }

        @Override public void orbitPlayer(float radius, float height, float dps) {
            orbit(s.player.x, s.player.y + 1, s.player.z, radius, height, dps);
            c().orbitPlayer = true;
        }

        @Override public void setTripod(boolean on) { c().playerControl = on && (c().mode == CameraRig.Mode.FREE || c().mode == CameraRig.Mode.ORBIT); }
        @Override public boolean tripod() { return c().playerControl; }

        @Override public float focalLength() { return s.lastFocal; }
        @Override public void setFocalLength(float mm) {
            if (mm <= 0) { c().focalTarget = 0; return; }
            if (c().focalTarget <= 0) c().focal = s.lastFocal;
            c().focalTarget = Math.max(8, Math.min(300, mm));
        }
        @Override public void setFocalLengthNow(float mm) { c().focalTarget = mm <= 0 ? 0 : Math.max(8, Math.min(300, mm)); c().focal = c().focalTarget; }

        @Override public float roll() { return c().roll; }
        @Override public void setRoll(float d) { c().rollTarget = d; }
        @Override public void setRollNow(float d) { c().rollTarget = d; c().roll = d; }

        @Override public void setAnamorphic(float squeeze) { c().squeeze = Math.max(1, Math.min(2, squeeze)); }

        @Override public void setLetterbox(float aspect) {
            int best = 0;
            if (aspect > 0) {
                float bd = Float.MAX_VALUE;
                for (int i = 1; i < CameraRig.LETTERBOX_ASPECT.length; i++) {
                    float d = Math.abs(CameraRig.LETTERBOX_ASPECT[i] - aspect);
                    if (d < bd) { bd = d; best = i; }
                }
            }
            c().letterbox = best;
        }

        @Override public void setDistortion(float a) { c().distortion = Math.max(-1, Math.min(1, a)); }
        @Override public void setChromaticAberration(float a) { c().aberration = Math.max(0, Math.min(1, a)); }
        @Override public void setShake(float amount, float speed) { c().shake = Math.max(0, Math.min(1, amount)); c().shakeSpeed = Math.max(0.05f, speed); }

        @Override public void setDepthOfField(boolean on) { c().dof = on; }
        @Override public boolean depthOfField() { return c().dof; }
        @Override public void setAperture(float f) { c().aperture = Math.max(0.95f, Math.min(22, f)); }

        @Override public void setAutofocus(String mode) {
            c().afMode = switch (mode.toLowerCase(Locale.ROOT)) { case "manual" -> 0; case "player" -> 2; default -> 1; };
        }

        @Override public void setAutofocusSpeed(float speed) { c().afSpeed = Math.max(0.1f, speed); }
        @Override public void setFocusDistance(float blocks) { c().afMode = 0; c().manualFocus = Math.max(0.2f, blocks); }
        @Override public float focusDistance() { return c().focus; }
        @Override public void setBokehScale(float scale) { c().bokehScale = Math.max(0.25f, Math.min(4, scale)); }

        @Override public void setFlares(float strength, float length, String color) {
            c().flare = Math.max(0, Math.min(1, strength));
            c().flareLength = Math.max(0, Math.min(1, length));
            if (color != null) for (int i = 0; i < CameraRig.FLARE_COLORS.length; i++)
                if (CameraRig.FLARE_COLORS[i].equalsIgnoreCase(color)) c().flareColor = i;
        }

        @Override public void setGhosts(float g) { c().ghosts = Math.max(0, Math.min(1, g)); }
        @Override public void setMotionBlur(float a) { c().motionBlur = Math.max(0, Math.min(1, a)); }
        @Override public void setGrid(boolean on) { c().grid = on; }

        @Override public void addKeyframe() { c().addKey(s.lastCamX, s.lastCamY, s.lastCamZ, s.lastCamYaw, s.lastCamPitch, s.lastFocal); }

        @Override public void addKeyframe(double x, double y, double z, float yaw, float pitch, float roll, float focalMm, float focus) {
            CameraRig r = c();
            r.addKey(x, y, z, yaw, pitch, s.lastFocal);
            double[] k = r.keys.get(r.keys.size() - 1);
            k[5] = roll;
            if (focalMm > 0) k[6] = focalMm;
            if (focus > 0) k[7] = focus;
        }

        @Override public int keyframeCount() { return c().keys.size(); }
        @Override public void removeLastKeyframe() { c().removeLastKey(); }
        @Override public void clearKeyframes() { c().clearKeys(); }

        @Override public boolean playPath(float seconds, boolean loop, String easing, boolean evenSpeed) {
            CameraRig r = c();
            r.pathDuration = Math.max(0.1f, seconds);
            r.pathLoop = loop;
            r.constantSpeed = evenSpeed;
            if (easing != null) r.easing = switch (easing.toLowerCase(Locale.ROOT)) { case "linear" -> 0; case "in" -> 2; case "out" -> 3; default -> 1; };
            return r.play();
        }

        @Override public void stopPath() { c().stop(); }
        @Override public boolean pathPlaying() { return c().playing(); }
        @Override public float pathProgress() { return c().playing() ? c().progress() : 0; }
        @Override public double[] samplePath(double t) { return c().samplePath(Math.max(0, Math.min(1, t))); }
        @Override public void save() { s.saveCamera(); }
    }

    static final class Graphics implements GraphicsApi {
        private final Session s;
        Graphics(Session s) { this.s = s; }
        private derpcraft.game.Settings st() { return s.game.settings; }

        @Override public int shadows() { return st().shadows; }
        @Override public void setShadows(int q) { st().shadows = Math.max(0, Math.min(4, q)); }
        @Override public boolean rayTracing() { return st().rayTracing == 1; }
        @Override public void setRayTracing(boolean on) { st().rayTracing = on ? 1 : 0; }
        @Override public void setRayTracingQuality(int q) { st().rtQuality = Math.max(0, Math.min(2, q)); }
        @Override public boolean coloredLight() { return st().coloredLight; }
        @Override public void setColoredLight(boolean on) { st().coloredLight = on; }
        @Override public boolean worldAO() { return st().ssao; }
        @Override public void setWorldAO(boolean on, float strength) { st().ssao = on; st().ssaoStrength = Float.isFinite(strength) ? Math.max(0, Math.min(2, strength)) : 1; }
        @Override public boolean wavingPlants() { return st().waving; }
        @Override public void setWavingPlants(boolean on, float wind) { st().waving = on; st().windStrength = clamp3(wind); }
        @Override public boolean waterWaves() { return st().waterShader; }
        @Override public void setWaterWaves(boolean on, float height) { st().waterShader = on; st().waveHeight = clamp3(height); }
        @Override public boolean godRays() { return st().godRays; }
        @Override public void setGodRays(boolean on, float strength) { st().godRays = on; st().godRayStrength = clamp3(strength); }
        @Override public int reflections() { return st().reflections; }
        @Override public void setReflections(int mode, float blockStrength) { st().reflections = Math.max(0, Math.min(2, mode)); st().blockReflections = clamp3(blockStrength); }
        @Override public List<String> shaderPresets() { return ShaderLook.names(s.game.shadersDir, false); }
        @Override public void setTransition(String fromShader, String fromColor, String toShader, String toColor, float seconds) {
            var st = st();
            if (fromShader != null) st.transFromShader = fromShader;
            if (fromColor != null) st.transFromColor = fromColor;
            if (toShader != null) st.transToShader = toShader;
            if (toColor != null) st.transToColor = toColor;
            if (Float.isFinite(seconds) && seconds > 0) st.transSeconds = Math.max(0.5f, Math.min(600, seconds));
            s.game.transition.reset(s.game);
        }
        @Override public void toggleTransition() { s.chat(s.game.transition.toggle(s.game)); }
        @Override public boolean transitionRunning() { return s.game.transition.running(); }
        private static float clamp3(float v) { return Float.isFinite(v) ? Math.max(0, Math.min(3, v)) : 1; }
        @Override public boolean cinematicPipeline() { return st().cinematic; }
        @Override public void setCinematicPipeline(boolean on) { st().cinematic = on; }

        @Override public List<String> gradingPresets() { return Grading.presetNames(s.game.gradingDir); }

        @Override public boolean loadGradingPreset(String name) {
            Grading g = Grading.byName(name, s.game.gradingDir);
            if (g == null) return false;
            s.game.grading = g;
            s.game.gradingName = name;
            st().cinematic = true;
            return true;
        }

        @Override public String gradingName() { return s.game.gradingName; }

        private static Map<String, String> parse(String text) {
            Map<String, String> m = new LinkedHashMap<>();
            for (String line : text.split("\n")) {
                int i = line.indexOf('=');
                if (i > 0) m.put(line.substring(0, i).trim(), line.substring(i + 1).trim());
            }
            return m;
        }

        @Override public float grading(String key) {
            try { return Float.parseFloat(parse(s.game.grading.write()).getOrDefault(key, "0")); } catch (NumberFormatException e) { return 0; }
        }

        @Override public void setGrading(String key, float value) {
            Map<String, String> m = parse(s.game.grading.write());
            if (!m.containsKey(key) || key.startsWith("curve") || key.equals("lut")) return;
            m.put(key, key.equals("tonemap") ? String.valueOf((int) value) : String.valueOf(value));
            applyMap(m);
        }

        @Override public void setLut(String fileName) {
            Map<String, String> m = parse(s.game.grading.write());
            m.put("lut", fileName == null ? "" : fileName);
            applyMap(m);
        }

        private void applyMap(Map<String, String> m) {
            StringBuilder sb = new StringBuilder();
            for (var e : m.entrySet()) sb.append(e.getKey()).append('=').append(e.getValue()).append('\n');
            s.game.grading.read(sb.toString());
        }

        @Override public String gradingText() { return s.game.grading.write(); }
        @Override public void applyGrading(String text) { s.game.grading.read(text); }
    }

    static final class Npcs implements derpcraft.api.NpcApi {
        private final Session s;
        Npcs(Session s) { this.s = s; }
        private derpcraft.npc.NpcManager n() { return s.npcs; }
        @Override public List<String> groups() { return n().groups(); }
        @Override public List<String> poses() { return derpcraft.npc.Poses.names(); }
        @Override public void setEmotion(String name, String face) {
            var a = get(name);
            for (int i = 0; i < derpcraft.npc.Emotions.NAMES.length; i++) if (derpcraft.npc.Emotions.NAMES[i].equalsIgnoreCase(face)) a.emotion = i;
            n().save();
        }
        @Override public void setOffhandItem(String name, String item) {
            var it = item == null ? null : derpcraft.item.Items.byName(item);
            get(name).offhand = it == null ? null : new derpcraft.item.ItemStack(it, 1);
            n().save();
        }
        @Override public void setArmor(String name, int slot, String item) {
            var it = item == null ? null : derpcraft.item.Items.byName(item);
            get(name).armor[Math.max(0, Math.min(3, slot))] = it == null ? null : new derpcraft.item.ItemStack(it, 1);
            n().save();
        }
        @Override public void setScale(String name, float scale) { get(name).scale = Math.max(0.1f, scale); n().save(); }
        @Override public void addWaypoint(String name, double x, double y, double z) { get(name).waypoints.add(new double[]{x, y, z}); n().save(); }
        @Override public void clearWaypoints(String name) { get(name).waypoints.clear(); n().save(); }
        @Override public void setSeat(String name, int x, int y, int z) { var a = get(name); a.seat = new int[]{x, y, z}; a.behavior = derpcraft.npc.Actor.Behavior.SEAT; n().save(); }
        @Override public void setRide(String name, String target) {
            var a = get(name);
            var t = n().byName(target);
            if (t == null) throw new IllegalArgumentException("No NPC named " + target);
            a.rideId = t.id; a.rideCreature = false; a.behavior = derpcraft.npc.Actor.Behavior.RIDE;
            n().save();
        }
        @Override public int takeCount(String name, String scene) { var t = get(name).track(scene); return t == null ? 0 : t.takes.size(); }
        @Override public void setActiveTake(String name, String scene, int index) {
            var t = get(name).track(scene);
            if (t == null || t.takes.isEmpty()) return;
            t.active = Math.max(0, Math.min(t.takes.size() - 1, index));
            n().save();
        }
        @Override public void setTakeTiming(String name, String scene, int offsetTicks, float speed) {
            var t = get(name).track(scene);
            var tk = t == null ? null : t.take();
            if (tk == null) return;
            tk.offset = Math.max(0, offsetTicks); tk.speed = Math.max(0.05f, speed);
            n().save();
        }
        @Override public void addPoseKey(String name, String scene, int tick, String pose, String curve, int blendTicks) {
            var t = get(name).track(scene);
            if (t == null) throw new IllegalArgumentException(name + " has no take in " + scene);
            int c = 1;
            for (int i = 0; i < derpcraft.npc.Track.CURVES.length; i++) if (derpcraft.npc.Track.CURVES[i].equalsIgnoreCase(curve)) c = i;
            t.keys.add(new derpcraft.npc.Track.PoseKey(Math.max(0, tick), pose == null ? "" : pose, c, Math.max(0, blendTicks)));
            t.sortKeys();
            n().save();
        }
        @Override public boolean playSceneFrom(String scene, int tick) { return n().playScene(scene, null, tick); }
        @Override public List<String> models3d() { return new ArrayList<>(derpcraft.model.Models.names()); }
        @Override public void addModel(String name, String model, String part, float x, float y, float z, float turnX, float turnY, float turnZ, float size) {
            String p = part == null ? "head" : part.toLowerCase(Locale.ROOT).replace("_", " ");
            int idx = switch (p) { case "body" -> 1; case "right arm" -> 2; case "left arm" -> 3; case "right leg" -> 4; case "left leg" -> 5; default -> 0; };
            var a = new derpcraft.npc.Attachment(model, idx);
            a.ox = x; a.oy = y; a.oz = z; a.rx = turnX; a.ry = turnY; a.rz = turnZ; a.scale = size <= 0 ? 1 : size;
            get(name).attachments.add(a);
            n().save();
        }
        @Override public void clearModels(String name) { get(name).attachments.clear(); n().save(); }
        @Override public List<String> groupMembers(String g) { List<String> l = new ArrayList<>(); for (var a : n().members(g)) l.add(a.name); return l; }
        @Override public String createGroup(String name) { return n().newGroup(name); }
        @Override public String copyGroup(String g, boolean takes) { return n().copyGroup(g, takes); }
        @Override public void renameGroup(String g, String nv) { n().renameGroup(g, nv); }
        @Override public void deleteGroup(String g) { n().deleteGroup(g); }
        @Override public void setGroupEnabled(String g, boolean on) { n().setGroupEnabled(g, on); }
        @Override public boolean groupEnabled(String g) { return n().groupEnabled(g); }
        @Override public String groupOf(String name) { return get(name).group; }
        @Override public void moveToGroup(String name, String g) { n().moveToGroup(get(name), g); }
        @Override public void setEnabled(String name, boolean on) { n().setEnabled(get(name), on); }
        @Override public boolean enabled(String name) { return get(name).enabled; }
        @Override public void setSceneEnabled(String name, boolean on) { get(name).sceneEnabled = on; n().save(); }
        @Override public boolean playScene(String scene, List<String> actors) {
            List<Integer> ids = new ArrayList<>();
            for (String a : actors) { var x = n().byName(a, scene); if (x == null) x = n().byName(a); if (x != null) ids.add(x.id); }
            return n().playScene(scene, ids);
        }
        private derpcraft.npc.Actor get(String name) {
            var a = n().byName(name);
            if (a == null) throw new IllegalArgumentException("No NPC named " + name);
            return a;
        }

        @Override public List<String> list() { List<String> l = new ArrayList<>(); for (var a : n().all()) l.add(a.name); return l; }
        @Override public boolean exists(String name) { return n().byName(name) != null; }
        @Override public String spawn(String name, double x, double y, double z, float yaw) { return n().spawn(name, x, y, z, yaw).name; }
        @Override public void remove(String name) { var a = n().byName(name); if (a != null) n().remove(a); }
        @Override public double[] position(String name) { var a = n().byName(name); return a == null ? null : new double[]{a.x(), a.y(), a.z(), a.yaw()}; }
        @Override public void teleport(String name, double x, double y, double z, float yaw) { var a = get(name); a.place(x, y, z, yaw); a.setHome(); n().save(); }
        @Override public List<String> skins() { return n().skins.names(); }

        @Override public void setSkin(String name, String skin) {
            var a = get(name);
            for (String sk : n().skins.names()) if (sk.equalsIgnoreCase(skin)) { a.skin = sk; n().save(); return; }
            throw new IllegalArgumentException("No skin " + skin);
        }

        @Override public void setBehavior(String name, String behavior) {
            var a = get(name);
            a.behavior = switch (behavior.toLowerCase(Locale.ROOT)) {
                case "stand" -> derpcraft.npc.Actor.Behavior.STAND;
                case "wander" -> derpcraft.npc.Actor.Behavior.WANDER;
                case "follow" -> derpcraft.npc.Actor.Behavior.FOLLOW;
                case "patrol" -> derpcraft.npc.Actor.Behavior.PATROL;
                case "seat", "sit" -> derpcraft.npc.Actor.Behavior.SEAT;
                case "watch" -> derpcraft.npc.Actor.Behavior.WATCH;
                case "ride" -> derpcraft.npc.Actor.Behavior.RIDE;
                case "drive" -> derpcraft.npc.Actor.Behavior.DRIVE;
                default -> derpcraft.npc.Actor.Behavior.LOOK;
            };
            if (a.behavior == derpcraft.npc.Actor.Behavior.DRIVE && s.vehicles != null && s.vehicles.byId(a.vehicleId) == null) {
                var v = s.vehicles.nearest(a.x(), a.z(), 48);
                if (v != null) a.vehicleId = v.id;
            }
            a.setHome();
            n().save();
        }

        @Override public void setPose(String name, String pose) {
            var a = get(name);
            a.customPose = "";
            var lib = derpcraft.npc.Poses.get(pose);
            if (lib != null && !pose.equalsIgnoreCase("sit")) { a.customPose = lib.name; a.pose = derpcraft.npc.Actor.Pose.NORMAL; n().save(); return; }
            a.pose = switch (pose.toLowerCase(Locale.ROOT)) {
                case "sit" -> derpcraft.npc.Actor.Pose.SIT;
                case "sneak" -> derpcraft.npc.Actor.Pose.SNEAK;
                case "wave" -> derpcraft.npc.Actor.Pose.WAVE;
                default -> derpcraft.npc.Actor.Pose.NORMAL;
            };
            n().save();
        }

        @Override public void setHeldItem(String name, String item) {
            var a = get(name);
            var it = item == null || item.isEmpty() ? null : derpcraft.item.Items.byName(item);
            a.held = it == null ? null : new derpcraft.item.ItemStack(it, 1);
            n().save();
        }

        @Override public void setNameTag(String name, boolean visible) { get(name).showName = visible; n().save(); }
        @Override public void say(String name, String text) { s.chat("<" + name + "> " + text); }
        @Override public float takeSeconds(String name) { return takeSeconds(name, n().currentScene); }
        @Override public float takeSeconds(String name, String scene) { var a = n().byName(name); var t = a == null ? null : a.take(scene); return t == null ? 0 : t.seconds(); }
        @Override public void startRecording(String name) { n().startRecording(get(name)); }
        @Override public void stopRecording() { n().stopRecording(); }
        @Override public boolean recording() { return n().recording(); }
        @Override public boolean playScene() { return n().playScene(); }
        @Override public void stopScene() { n().stopScene(); }
        @Override public boolean scenePlaying() { return n().scenePlaying(); }
        @Override public void resetScene() { n().resetScene(); }
        @Override public float sceneTime() { var r = n().runs(); return r.isEmpty() ? 0 : r.get(0).tick / 20f; }
        @Override public float sceneLength() { return n().sceneLength() / 20f; }
        @Override public void setSceneLoop(boolean loop) { n().loopScene = loop; n().save(); }
        @Override public void setSceneCamera(boolean on) { n().linkCamera = on; n().save(); }

        @Override public String currentScene() { return n().currentScene; }
        @Override public void setCurrentScene(String scene) { if (scene != null && !scene.isBlank()) { n().currentScene = scene; n().save(); } }
        @Override public List<String> scenes() { return n().sceneNames(); }
        @Override public List<String> scenesOf(String name) { return new ArrayList<>(get(name).scenes()); }
        @Override public boolean playScene(String scene) { return n().playScene(scene); }
        @Override public void stopScene(String scene) { n().stopScene(scene); }
        @Override public boolean scenePlaying(String scene) { return n().scenePlaying(scene); }
        @Override public void setWalkToMarks(boolean walk) { n().walkToMarks = walk; n().save(); }

        private static derpcraft.npc.Actor.Faction parseFaction(String f) { return derpcraft.npc.Actor.Faction.valueOf(f.trim().toUpperCase(Locale.ROOT)); }
        @Override public void setFaction(String name, String f) { get(name).faction = parseFaction(f); n().save(); }
        @Override public String faction(String name) { return get(name).faction.name().toLowerCase(Locale.ROOT); }
        @Override public void setFactionAttacks(String a, String b, boolean on) { n().attacks[parseFaction(a).ordinal()][parseFaction(b).ordinal()] = on; n().save(); }
        @Override public void setAttackCreative(boolean on) { n().attackCreative = on; n().save(); }
        @Override public void setAggroRange(String name, float blocks) { get(name).aggroRange = Math.max(1, Math.min(64, blocks)); n().save(); }
        @Override public void setOnHit(String name, String r) { get(name).onHit = r.equalsIgnoreCase("flee") ? derpcraft.npc.Actor.OnHit.FLEE : derpcraft.npc.Actor.OnHit.FIGHT; n().save(); }
        @Override public void setGuard(String name, String r) {
            get(name).guard = switch (r.toLowerCase(Locale.ROOT)) { case "defend" -> derpcraft.npc.Actor.Guard.DEFEND; case "flee" -> derpcraft.npc.Actor.Guard.FLEE; default -> derpcraft.npc.Actor.Guard.IGNORE; };
            n().save();
        }
        @Override public void setFollowDistance(String name, float start, float stop) { var a = get(name); a.followStart = Math.max(1, start); a.followStop = Math.max(0.5f, Math.min(stop, a.followStart)); n().save(); }
        @Override public void setAbilities(String name, boolean doors, boolean breaking) { var a = get(name); a.openDoors = doors; a.breakBlocks = breaking; n().save(); }
        @Override public void setHealth(String name, float max) { var a = get(name); a.maxHealth = Math.max(1, max); a.health = a.maxHealth; n().save(); }
        @Override public float health(String name) { return get(name).health; }
        @Override public void setDamage(String name, float d) { get(name).damage = Math.max(0, d); n().save(); }
        @Override public void setRespawn(String name, boolean r) { get(name).respawn = r; n().save(); }
        @Override public void hurt(String name, float amount) { get(name).hurt(n(), amount, null, false); }
        @Override public void kill(String name) { var a = get(name); a.hurt(n(), a.health + 1000, null, false); }
        @Override public void revive(String name) { get(name).revive(); }
        @Override public boolean dead(String name) { return get(name).dead(); }
        @Override public void attack(String name, String target) { n().order(get(name), target == null ? null : get(target)); }
    }
}
