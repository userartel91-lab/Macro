package derpcraft.vehicle;

import derpcraft.model.Model3D;
import derpcraft.model.Models;
import derpcraft.util.Json;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * One kind of vehicle: a folder game/transport/&lt;name&gt;/ with the model (&lt;name&gt;.obj, &lt;name&gt;.bbmodel or a Blockbench
 * &lt;name&gt;.json), its textures, its sounds (.wav) and its settings (&lt;name&gt;.json, or vehicle.json when the .json is the
 * model). Everything is optional except the model; missing settings keep the defaults below.
 */
public final class VehicleType {
    public final String name;
    public final Path dir;
    public String displayName;
    /** The model file used, or null. */
    public Path modelFile;
    /** The settings file read, or null. */
    public Path configFile;
    public String error;
    public final List<String> warnings = new ArrayList<>();

    // ---------------------------------------------------------------- driving (game units: blocks and ticks)
    /** Top speed forward / backward (blocks per tick; 1 block per tick = 72 km/h). */
    public float maxSpeed = 1.2f, reverseSpeed = 0.35f;
    /** Gas (blocks per tick per tick, before the falloff near top speed), brakes, handbrake. */
    public float accel = 0.016f, braking = 0.05f, handbrake = 0.06f;
    /** Front wheel angle at full lock (degrees), the highest step the wheels climb (blocks). */
    public float steerAngle = 32, stepHeight = 1.05f;
    // ---------------------------------------------------------------- body springs
    public float springStiffness = 0.18f, springDamping = 0.72f, maxPitch = 3.5f, maxRoll = 4f;
    /** The body's height over the wheels (blocks, negative = lower) and how far it may bounce (blocks). */
    public float rideHeight = 0, heaveLimit = 0.18f;
    /** Weight (kg). 1200 = an ordinary car: heavier ones are pushed slowly, hit harder, roll over less; lighter ones the other way. */
    public float weight = REF_WEIGHT;
    /** Water: the share of the speed kept every tick while the car wades (1 = not slowed). */
    public float waterDrag = 0.85f;
    // ---------------------------------------------------------------- handling: drift, police turn, rolling over
    /** How fast sideways speed dies out every tick on the road (share), and while drifting (Space held with gas and steering). */
    public float grip = 0.5f, driftGrip = 0.04f;
    /** Drift: how much quicker the nose swings round than normal, the lowest speed for it (blocks per tick). */
    public float driftYaw = 1.6f, driftMinSpeed = 0.3f;
    /** Police turn (Space + a sharp turn at high speed): the lowest speed (blocks per tick) and the highest swing (degrees per tick). */
    public float policeSpeed = 0.875f, policeRate = 9;
    /** Rolling over in sharp turns: sideways force (blocks per tick squared) that tips the car (0 = never), damage factor to the people inside. */
    public float rollover = 0.033f, rollDamage = 1;
    /** Rolling over on / off, and the speed (blocks per tick) under which the car never rolls over. */
    public boolean rolloverOn = true;
    /** From this speed (blocks per tick; 30 km/h) the car smashes glass, planks, leaves, fences and trapdoors instead of stopping at them. */
    public float smashSpeed = 30 / 72f;
    public float rolloverSpeed = 0.7f;
    // ---------------------------------------------------------------- surfaces
    /** Per surface (road, dirt, sand, snow, ice: see {@link Surfaces}): the share of the grip the tyres keep, and of the top speed. */
    public final float[] surfGrip = {1, 0.9f, 0.75f, 0.6f, 0.12f}, surfSpeed = {1, 1, 0.8f, 0.85f, 1};
    // ---------------------------------------------------------------- lights
    /** A light: a cone of light from the lamp (start) to where the beam ends (end), 'width' = the radius there, colour and strength. */
    public static final class Light {
        public float[] a = new float[3], b = new float[3], color = {1, 0.95f, 0.8f};
        public float width = 3, intensity = 1;
        public Light copy() { Light l = new Light(); l.a = a.clone(); l.b = b.clone(); l.color = color.clone(); l.width = width; l.intensity = intensity; return l; }
    }
    /** The lights from the settings ("lights" in the file; empty list = none), used instead of the two headlights and two tail lights made up from the model. */
    public final List<Light> lights = new ArrayList<>();
    public boolean lightsSet;
    // ---------------------------------------------------------------- model placement
    /** Size multiplier and turn around y (degrees) applied to the model before anything else (front must end up at -z). */
    public float scale = 1, turn = 0;
    /** Width (x), height (y) and length (z) multipliers on top of the size. */
    public float sizeX = 1, sizeY = 1, sizeZ = 1;
    /** OBJ only: model units per block (an OBJ in centimetres: 100). */
    public float objUnits = 1;
    /** Opening angles (doors 70, hood 60, trunk 65) and per part overrides: name -> angle (negative = the other way). */
    public float doorAngle = 70, hoodAngle = 60, trunkAngle = 65;
    public final Map<String, Float> angles = new HashMap<>();
    /** Per part pivots in blocks in the car's own coordinates (origin between the axles on the ground; /vehicle info and the check tool print them): name -> {x, y, z}. */
    public final Map<String, float[]> pivots = new HashMap<>();
    /** Group / object names that are really another part: "bag" -> "trunk", "kapot" -> "hood". */
    public final Map<String, String> roles = new HashMap<>();
    /** Seats in the car's own coordinates {x, y, z} (the middle of the sitting surface); empty = the seat groups, else made up near the steering wheel. */
    public final List<float[]> seats = new ArrayList<>();
    /** The camera point (eyes) of each seat {x, y, z} in the car's own coordinates, independent of how the rider sits; a null entry = the usual (above the seat). */
    public final List<float[]> eyes = new ArrayList<>();
    /** The turn of each seat around the vertical (degrees, 0 = facing the front, + = to the right): a seat in the boot faces backwards or sideways. */
    public final List<Float> seatYaws = new ArrayList<>();
    /** Places for blocks {x, y, z} (bottom centre) in the car's own coordinates, up to 4; used instead of the model's block1..4 when "blocks" is in the settings. */
    public final List<float[]> blocks = new ArrayList<>();
    public boolean blocksSet;
    // ---------------------------------------------------------------- sounds
    /** Sound files by event: engine, idle, start, horn, door_open, door_close, skid, crash. */
    public final Map<String, Path> sounds = new HashMap<>();
    public float volume = 1, enginePitchMin = 0.7f, enginePitchMax = 1.8f;
    /** Gears for the engine sound (the pitch climbs and drops back at each shift); 0 = one smooth climb. */
    public int gears = 4;

    /** Weight (kg) the car's physics are tuned for. */
    public static final float REF_WEIGHT = 1200;
    public static final String[] SOUND_EVENTS = {"engine", "idle", "start", "horn", "door_open", "door_close", "skid", "crash"};

    private Model3D model;
    private VehicleModel vm;
    private boolean loaded;

    VehicleType(String name, Path dir) {
        this.name = name;
        this.dir = dir;
        this.displayName = name;
    }

    /**
     * The lights the vehicle has: the ones from the settings, else two headlights (a cone 14 blocks ahead, 3 wide) and two weak red
     * tail lights made up from the size of the model.
     */
    public List<Light> effectiveLights() {
        if (lightsSet) return lights;
        VehicleModel m = vm();
        List<Light> out = new ArrayList<>();
        if (m == null) return out;
        float x = (m.maxX - m.minX) * 0.3f, y = Math.max(m.wheelRadius + 0.2f, m.maxY * 0.4f), cx = (m.minX + m.maxX) / 2;
        for (int side = -1; side <= 1; side += 2) {
            Light h = new Light();
            h.a = new float[]{cx + side * x, y, m.minZ + 0.1f};
            h.b = new float[]{cx + side * x * 0.8f, y * 0.35f, m.minZ - 14};
            h.width = 3.2f;
            out.add(h);
            Light t = new Light();
            t.a = new float[]{cx + side * x, y, m.maxZ - 0.1f};
            t.b = new float[]{cx + side * x, y * 0.6f, m.maxZ + 2.5f};
            t.width = 0.7f; t.intensity = 0.7f; t.color = new float[]{1, 0.08f, 0.04f};
            out.add(t);
        }
        return out;
    }

    /** A kind with all the default settings (the editor's "default" values). */
    public static VehicleType defaults() { return new VehicleType("", Path.of(".")); }

    /** The analysed model (loaded on first use), or null when it could not be loaded. */
    public synchronized VehicleModel vm() {
        if (!loaded) load();
        return vm;
    }

    public synchronized Model3D model() { if (!loaded) load(); return model; }

    private void load() {
        loaded = true;
        try {
            readConfig();
            if (modelFile == null) { error = "no model: put " + name + ".obj, " + name + ".bbmodel or a Blockbench " + name + ".json into " + dir.getFileName(); return; }
            Model3D m = Models.loadFile("transport/" + name + "/" + modelFile.getFileName(), modelFile);
            if (m.error != null) { error = m.error; return; }
            warnings.addAll(m.warnings);
            model = m;
            vm = VehicleModel.of(m, this);
            if (vm == null) error = "the model has no parts (groups / objects named wheel1, door1, seat1...)";
            else if (vm.problem != null) warnings.add(vm.problem);
        } catch (Exception e) {
            error = e.getClass().getSimpleName() + ": " + e.getMessage();
        }
    }

    /** Finds the files and reads the settings. */
    private void readConfig() throws Exception {
        Path json = find(name + ".json");
        Map<String, Object> cfg = null;
        if (json != null) {
            Map<String, Object> o = Json.obj(Json.parse(stripBom(Files.readString(json, StandardCharsets.UTF_8))));
            if (o.containsKey("elements")) {               // the .json is a Blockbench model: settings in vehicle.json
                modelFile = json;
                if (o.get("vehicle") instanceof Map<?, ?> mv) { @SuppressWarnings("unchecked") Map<String, Object> c = (Map<String, Object>) mv; cfg = c; configFile = json; }
            } else { cfg = o; configFile = json; }
        }
        Path vj = find("vehicle.json");
        if (vj != null && (cfg == null || modelFile == json)) { cfg = Json.obj(Json.parse(stripBom(Files.readString(vj, StandardCharsets.UTF_8)))); configFile = vj; }   // the editor saves here
        if (cfg == null) cfg = Map.of();
        String mf = str(cfg, "model", null);
        if (mf != null) { Path p = dir.resolve(mf); if (Files.isRegularFile(p)) modelFile = p; else warnings.add("model file not found: " + mf); }
        if (modelFile == null) for (String ext : new String[]{".obj", ".bbmodel"}) { Path p = find(name + ext); if (p != null) { modelFile = p; break; } }
        if (modelFile == null) {
            // any single model file in the folder
            try (var st = Files.list(dir)) {
                for (Path p : st.toList()) {
                    String n = p.getFileName().toString().toLowerCase(Locale.ROOT);
                    if (n.endsWith(".obj") || n.endsWith(".bbmodel")) { modelFile = p; break; }
                }
            }
        }
        displayName = str(cfg, "name", name);
        // speeds in km/h, accelerations in km/h per second (1 block = 1 m, 20 ticks a second)
        maxSpeed = kmh(cfg, "maxSpeed", maxSpeed);
        reverseSpeed = kmh(cfg, "reverseSpeed", reverseSpeed);
        accel = kmhs(cfg, "acceleration", accel);
        braking = kmhs(cfg, "braking", braking);
        handbrake = kmhs(cfg, "handbrake", handbrake);
        steerAngle = num(cfg, "steering", steerAngle);
        stepHeight = num(cfg, "stepHeight", stepHeight);
        weight = Math.max(50, num(cfg, "weight", weight));
        waterDrag = Math.max(0.3f, Math.min(1, num(cfg, "waterDrag", waterDrag)));
        Map<String, Object> hd = Json.obj(cfg.get("handling"));
        grip = Math.max(0.02f, Math.min(1, num(hd, "grip", grip)));
        driftGrip = Math.max(0, Math.min(1, num(hd, "driftGrip", driftGrip)));
        driftYaw = Math.max(0.5f, num(hd, "driftYaw", driftYaw));
        driftMinSpeed = hd.get("driftMinSpeed") instanceof Number n ? n.floatValue() / 72f : driftMinSpeed;
        policeSpeed = hd.get("policeSpeed") instanceof Number n ? n.floatValue() / 72f : policeSpeed;
        policeRate = Math.max(1, num(hd, "policeRate", policeRate));
        rollover = Math.max(0, num(hd, "rollover", rollover));
        rollDamage = Math.max(0, num(hd, "rollDamage", rollDamage));
        smashSpeed = hd.get("smashSpeed") instanceof Number n ? Math.max(0.05f, n.floatValue() / 72f) : smashSpeed;
        if (hd.get("rolloverEnabled") instanceof Boolean b) rolloverOn = b;
        rolloverSpeed = hd.get("rolloverSpeed") instanceof Number n ? n.floatValue() / 72f : rolloverSpeed;
        Map<String, Object> sp = Json.obj(cfg.get("suspension"));
        springStiffness = num(sp, "stiffness", springStiffness);
        springDamping = num(sp, "damping", springDamping);
        maxPitch = num(sp, "maxPitch", maxPitch);
        maxRoll = num(sp, "maxRoll", maxRoll);
        rideHeight = num(sp, "rideHeight", rideHeight);
        heaveLimit = Math.max(0, num(sp, "heaveLimit", heaveLimit));
        scale = num(cfg, "scale", scale);
        sizeX = Math.max(0.1f, num(cfg, "width", sizeX));
        sizeY = Math.max(0.1f, num(cfg, "height", sizeY));
        sizeZ = Math.max(0.1f, num(cfg, "length", sizeZ));
        turn = num(cfg, "turn", turn);
        objUnits = Math.max(1e-3f, num(cfg, "objUnitsPerBlock", objUnits));
        Map<String, Object> op = Json.obj(cfg.get("open"));
        doorAngle = num(op, "door", doorAngle);
        hoodAngle = num(op, "hood", hoodAngle);
        trunkAngle = num(op, "trunk", trunkAngle);
        for (var e : Json.obj(cfg.get("parts")).entrySet()) {
            Map<String, Object> pc = Json.obj(e.getValue());
            String k = e.getKey().toLowerCase(Locale.ROOT);
            if (pc.containsKey("angle")) angles.put(k, num(pc, "angle", 70));
            List<Object> pv = Json.arr(pc.get("pivot"));
            if (pv.size() >= 3) pivots.put(k, new float[]{(float) Json.num(pv.get(0), 0), (float) Json.num(pv.get(1), 0), (float) Json.num(pv.get(2), 0)});
        }
        for (var e : Json.obj(cfg.get("roles")).entrySet())
            if (e.getValue() instanceof String r) roles.put(e.getKey().toLowerCase(Locale.ROOT), r.toLowerCase(Locale.ROOT));
        for (Object so0 : Json.arr(cfg.get("seats"))) {
            List<Object> sv = Json.arr(so0);
            if (sv.size() >= 3) seats.add(new float[]{(float) Json.num(sv.get(0), 0), (float) Json.num(sv.get(1), 0.55), (float) Json.num(sv.get(2), 0)});
        }
        for (Object yo : Json.arr(cfg.get("seatYaw"))) seatYaws.add((float) Json.num(yo, 0));
        for (Object eo : Json.arr(cfg.get("eyes"))) {
            List<Object> ev = Json.arr(eo);
            eyes.add(ev.size() >= 3 ? new float[]{(float) Json.num(ev.get(0), 0), (float) Json.num(ev.get(1), 1), (float) Json.num(ev.get(2), 0)} : null);
        }
        Map<String, Object> sf = Json.obj(cfg.get("surfaces"));
        for (int i = 0; i < Surfaces.NAMES.length; i++) {
            Map<String, Object> sc = Json.obj(sf.get(Surfaces.NAMES[i]));
            surfGrip[i] = Math.max(0.01f, num(sc, "grip", surfGrip[i]));
            surfSpeed[i] = Math.max(0.1f, num(sc, "speed", surfSpeed[i]));
        }
        lightsSet = cfg.get("lights") instanceof List<?>;
        for (Object lo : Json.arr(cfg.get("lights"))) {
            Map<String, Object> lm = Json.obj(lo);
            List<Object> sa = Json.arr(lm.get("start")), ea = Json.arr(lm.get("end")), ca = Json.arr(lm.get("color"));
            if (sa.size() < 3 || ea.size() < 3) continue;
            Light l = new Light();
            for (int k = 0; k < 3; k++) { l.a[k] = (float) Json.num(sa.get(k), 0); l.b[k] = (float) Json.num(ea.get(k), 0); if (ca.size() >= 3) l.color[k] = (float) Json.num(ca.get(k), 1); }
            l.width = Math.max(0.05f, num(lm, "width", l.width));
            l.intensity = Math.max(0, num(lm, "intensity", l.intensity));
            lights.add(l);
        }
        blocksSet = cfg.get("blocks") instanceof List<?>;
        for (Object bo : Json.arr(cfg.get("blocks"))) {
            List<Object> bv = Json.arr(bo);
            if (bv.size() >= 3 && blocks.size() < VehicleModel.MAX_SLOTS) blocks.add(new float[]{(float) Json.num(bv.get(0), 0), (float) Json.num(bv.get(1), 0), (float) Json.num(bv.get(2), 0)});
        }
        Map<String, Object> so = Json.obj(cfg.get("sounds"));
        volume = num(so, "volume", volume);
        enginePitchMin = num(so, "enginePitchMin", enginePitchMin);
        enginePitchMax = num(so, "enginePitchMax", enginePitchMax);
        gears = (int) num(so, "gears", gears);
        for (String ev : SOUND_EVENTS) {
            String f = so.get(ev) instanceof String s ? s : null;
            Path p = f != null ? dir.resolve(f) : null;
            if (p != null && !Files.isRegularFile(p)) { warnings.add("sound not found: " + f); p = null; }
            if (p == null) p = find(ev + ".wav");
            if (p == null) p = find("sounds/" + ev + ".wav");
            if (p == null && ev.startsWith("door_")) p = find("door.wav");
            if (p != null) sounds.put(ev, p);
        }
        // the model's units
        if (modelFile != null && modelFile.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".obj")) scale /= objUnits;
    }

    /** A file in the folder (names are not case sensitive), or null. */
    private Path find(String file) {
        Path p = dir.resolve(file);
        if (Files.isRegularFile(p)) return p;
        Path parent = p.getParent();
        if (parent == null || !Files.isDirectory(parent)) return null;
        String want = p.getFileName().toString();
        try (var st = Files.list(parent)) {
            for (Path q : st.toList()) if (q.getFileName().toString().equalsIgnoreCase(want) && Files.isRegularFile(q)) return q;
        } catch (Exception ignored) {}
        return null;
    }

    private static String stripBom(String s) { return !s.isEmpty() && s.charAt(0) == '﻿' ? s.substring(1) : s; }

    private static float num(Map<String, Object> m, String k, float def) { return (float) Json.num(m.get(k), def); }

    private static String str(Map<String, Object> m, String k, String def) { return m.get(k) instanceof String s && !s.isBlank() ? s : def; }

    private static float kmh(Map<String, Object> m, String k, float def) { return m.get(k) instanceof Number n ? n.floatValue() / 72f : def; }

    private static float kmhs(Map<String, Object> m, String k, float def) { return m.get(k) instanceof Number n ? n.floatValue() / 72f / 20f : def; }

    // ---------------------------------------------------------------- the editor: rebuild, resize, save
    /** The model analysed again with the current settings (size, seats, block places); every vehicle of this kind picks it up. */
    public synchronized void rebuild() {
        if (model == null) return;
        VehicleModel.forget(model);
        vm = VehicleModel.of(model, this);
        if (vm != null) { warnings.removeIf(w -> w.startsWith("the model has")); if (vm.problem != null && !warnings.contains(vm.problem)) warnings.add(vm.problem); }
        Transports.bump();
    }

    /** The size as written in the settings ("scale"): for an OBJ the model units per block are not counted in it. */
    public float userScale() { return isObj() ? scale * objUnits : scale; }

    public void setUserScale(float v) { scale = isObj() ? v / objUnits : v; }

    private boolean isObj() { return modelFile != null && modelFile.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".obj"); }

    /** The model grows or shrinks along the car's axes: seats, block places and pivots written in the settings follow it. */
    public void resize(float fx, float fy, float fz) {
        for (float[] p : seats) { p[0] *= fx; p[1] *= fy; p[2] *= fz; }
        for (float[] p : eyes) if (p != null) { p[0] *= fx; p[1] *= fy; p[2] *= fz; }
        for (float[] p : blocks) { p[0] *= fx; p[1] *= fy; p[2] *= fz; }
        for (Light l : lights) { for (float[] p : new float[][]{l.a, l.b}) { p[0] *= fx; p[1] *= fy; p[2] *= fz; } l.width *= (fx + fz) / 2; }
        for (float[] p : pivots.values()) { p[0] *= fx; p[1] *= fy; p[2] *= fz; }
    }

    /** The file the editor writes: the settings file, else vehicle.json (when the .json is the model) or &lt;name&gt;.json. */
    public Path settingsFile() {
        if (configFile != null && !configFile.equals(modelFile)) return configFile;
        boolean modelIsJson = modelFile != null && modelFile.getFileName().toString().equalsIgnoreCase(name + ".json");
        return dir.resolve(modelIsJson ? "vehicle.json" : name + ".json");
    }

    /** Writes the settings the editor manages into the settings file; everything else in the file (sounds, roles, notes) stays. */
    @SuppressWarnings("unchecked")
    public synchronized void save() throws java.io.IOException {
        Path f = settingsFile();
        Map<String, Object> root = new LinkedHashMap<>();
        if (Files.isRegularFile(f)) {
            try { root = copy(Json.obj(Json.parse(stripBom(Files.readString(f, StandardCharsets.UTF_8))))); } catch (Exception e) { throw new java.io.IOException("cannot read " + f.getFileName() + ": " + e.getMessage()); }
        }
        root.putIfAbsent("name", displayName);
        root.put("maxSpeed", r(maxSpeed * 72, 1));
        root.put("reverseSpeed", r(reverseSpeed * 72, 1));
        root.put("acceleration", r(accel * 72 * 20, 1));
        root.put("braking", r(braking * 72 * 20, 1));
        root.put("handbrake", r(handbrake * 72 * 20, 1));
        root.put("steering", r(steerAngle, 1));
        root.put("stepHeight", r(stepHeight, 2));
        root.put("weight", r(weight, 0));
        root.put("waterDrag", r(waterDrag, 2));
        root.put("scale", r(userScale(), 3));
        if (sizeX != 1 || root.containsKey("width")) root.put("width", r(sizeX, 3));
        if (sizeY != 1 || root.containsKey("height")) root.put("height", r(sizeY, 3));
        if (sizeZ != 1 || root.containsKey("length")) root.put("length", r(sizeZ, 3));
        Map<String, Object> su = new LinkedHashMap<>(Json.obj(root.get("suspension")));
        su.put("stiffness", r(springStiffness, 3)); su.put("damping", r(springDamping, 3));
        su.put("maxPitch", r(maxPitch, 1)); su.put("maxRoll", r(maxRoll, 1));
        su.put("rideHeight", r(rideHeight, 3)); su.put("heaveLimit", r(heaveLimit, 3));
        root.put("suspension", su);
        Map<String, Object> hd = new LinkedHashMap<>(Json.obj(root.get("handling")));
        hd.put("grip", r(grip, 3)); hd.put("driftGrip", r(driftGrip, 3)); hd.put("driftYaw", r(driftYaw, 2));
        hd.put("driftMinSpeed", r(driftMinSpeed * 72, 1)); hd.put("policeSpeed", r(policeSpeed * 72, 1)); hd.put("policeRate", r(policeRate, 1));
        hd.put("rollover", r(rollover, 4)); hd.put("rollDamage", r(rollDamage, 2));
        hd.put("smashSpeed", r(smashSpeed * 72, 1)); hd.put("rolloverEnabled", rolloverOn); hd.put("rolloverSpeed", r(rolloverSpeed * 72, 1));
        root.put("handling", hd);
        if (!seats.isEmpty()) root.put("seats", points(seats)); else root.remove("seats");
        Map<String, Object> sfm = new LinkedHashMap<>(Json.obj(root.get("surfaces")));
        for (int i = 0; i < Surfaces.NAMES.length; i++) {
            Map<String, Object> sc = new LinkedHashMap<>(Json.obj(sfm.get(Surfaces.NAMES[i])));
            sc.put("grip", r(surfGrip[i], 3)); sc.put("speed", r(surfSpeed[i], 3));
            sfm.put(Surfaces.NAMES[i], sc);
        }
        root.put("surfaces", sfm);
        if (lightsSet) {
            List<Object> ll = new ArrayList<>();
            for (Light l : lights) {
                Map<String, Object> lm = new LinkedHashMap<>();
                lm.put("start", point(l.a)); lm.put("end", point(l.b)); lm.put("width", r(l.width, 2)); lm.put("intensity", r(l.intensity, 2));
                lm.put("color", new ArrayList<Object>(List.of(r(l.color[0], 2), r(l.color[1], 2), r(l.color[2], 2))));
                ll.add(lm);
            }
            root.put("lights", ll);
        } else root.remove("lights");
        boolean anyYaw = false;
        for (float y : seatYaws) if (y != 0) anyYaw = true;
        if (anyYaw) { List<Object> yl = new ArrayList<>(); for (float y : seatYaws) yl.add((double) r(y, 1)); root.put("seatYaw", yl); } else root.remove("seatYaw");
        boolean anyEye = false;
        for (float[] e : eyes) if (e != null) anyEye = true;
        if (anyEye) { List<Object> el = new ArrayList<>(); for (float[] e : eyes) el.add(e == null ? null : point(e)); root.put("eyes", el); } else root.remove("eyes");
        if (blocksSet) root.put("blocks", points(blocks)); else root.remove("blocks");
        if (!pivots.isEmpty()) {
            Map<String, Object> parts = new LinkedHashMap<>(Json.obj(root.get("parts")));
            for (var e : pivots.entrySet()) {
                Map<String, Object> pc = new LinkedHashMap<>(Json.obj(parts.get(e.getKey())));
                pc.put("pivot", point(e.getValue()));
                parts.put(e.getKey(), pc);
            }
            root.put("parts", parts);
        }
        StringBuilder sb = new StringBuilder();
        write(sb, root, 0);
        sb.append('\n');
        Files.writeString(f, sb.toString(), StandardCharsets.UTF_8);
        if (configFile == null || configFile.equals(modelFile)) configFile = f;
    }

    private static Map<String, Object> copy(Map<String, Object> m) { return new LinkedHashMap<>(m); }

    private static double r(double v, int digits) { double k = Math.pow(10, digits); return Math.round(v * k) / k; }

    private static List<Object> point(float[] p) { return new ArrayList<>(List.of(r(p[0], 3), r(p[1], 3), r(p[2], 3))); }

    private static List<Object> points(List<float[]> l) { List<Object> o = new ArrayList<>(); for (float[] p : l) o.add(point(p)); return o; }

    /** A small JSON writer: objects one key a line, lists of numbers on one line. */
    private static void write(StringBuilder sb, Object o, int ind) {
        if (o instanceof Map<?, ?> m) {
            if (m.isEmpty()) { sb.append("{}"); return; }
            sb.append("{\n");
            int i = 0;
            for (var e : m.entrySet()) {
                sb.append("  ".repeat(ind + 1));
                str(sb, String.valueOf(e.getKey()));
                sb.append(": ");
                write(sb, e.getValue(), ind + 1);
                sb.append(++i < m.size() ? ",\n" : "\n");
            }
            sb.append("  ".repeat(ind)).append('}');
        } else if (o instanceof List<?> l) {
            boolean flat = true;
            for (Object x : l) if (x instanceof Map || x instanceof List && !(allNumbers((List<?>) x))) flat = false;
            if (l.isEmpty()) { sb.append("[]"); return; }
            if (flat) {
                sb.append('[');
                for (int i = 0; i < l.size(); i++) { if (i > 0) sb.append(", "); write(sb, l.get(i), ind); }
                sb.append(']');
            } else {
                sb.append("[\n");
                for (int i = 0; i < l.size(); i++) { sb.append("  ".repeat(ind + 1)); write(sb, l.get(i), ind + 1); sb.append(i + 1 < l.size() ? ",\n" : "\n"); }
                sb.append("  ".repeat(ind)).append(']');
            }
        } else if (o instanceof Number n) {
            double d = n.doubleValue();
            if (d == Math.rint(d) && Math.abs(d) < 1e9) sb.append((long) d); else sb.append(new java.math.BigDecimal(d).round(new java.math.MathContext(8)).stripTrailingZeros().toPlainString());
        } else if (o instanceof Boolean b) sb.append(b);
        else if (o == null) sb.append("null");
        else str(sb, String.valueOf(o));
    }

    private static boolean allNumbers(List<?> l) { for (Object x : l) if (!(x instanceof Number)) return false; return true; }

    private static void str(StringBuilder sb, String s) {
        sb.append('"');
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\t' -> sb.append("\\t");
                default -> { if (c < 32) sb.append(String.format("\\u%04x", (int) c)); else sb.append(c); }
            }
        }
        sb.append('"');
    }

    /** A km/h value in game units (for showing settings). */
    public static float toKmh(float blocksPerTick) { return blocksPerTick * 72; }
}
