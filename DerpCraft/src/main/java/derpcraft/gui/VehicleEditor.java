package derpcraft.gui;

import derpcraft.DerpCraft;
import derpcraft.game.Keys;
import derpcraft.game.Session;
import derpcraft.render.GuiRenderer;
import derpcraft.vehicle.Transports;
import derpcraft.vehicle.Vehicle;
import derpcraft.vehicle.VehicleModel;
import derpcraft.vehicle.VehicleType;
import derpcraft.vehicle.Vehicles;

import java.util.*;
import java.util.function.BiConsumer;
import java.util.function.ToDoubleFunction;

import static org.lwjgl.glfw.GLFW.*;

/**
 * Vehicle editor (Alt + End, /vehicle edit): every setting of a vehicle on sliders, like the gun editor (Alt + Home). It works on
 * the vehicle you ride, the one under the crosshair or the nearest one (a button picks the next one). The world keeps running
 * behind it, so the changes can be tried at once; every change is written to the vehicle's settings file a moment after the last
 * one (&lt;name&gt;.json, or vehicle.json when the .json is the model), so nothing needs a restart.
 *
 * <p>Pages: Speed, Seats (places and their positions, marked in the car), Blocks (the attachment points of carried blocks),
 * Size (scale and width / height / length), Suspension, Terrain (the step it climbs, wading), Weight, Handling (grip, drift,
 * police turn, rolling over). Sliders: drag (Shift = fine), right click = default, Ctrl + click = type a value, Ctrl + wheel =
 * one step (Shift: ten), - / + one step. Ctrl+Z / Ctrl+Y undo / redo, Tab = next page, Page Up / Down = the previous / next seat or block place.
 */
public final class VehicleEditor extends Screen {
    private final Session s;
    private static int page = 0, scroll = 0, seatIx = 0, blockIx = 0;
    private static final String[] PAGES = {"Speed", "Seats", "Blocks", "Size", "Suspension", "Terrain", "Weight", "Handling", "Doors", "Lights", "Surfaces", "Bullets"};
    private static final String[] PAGE_LABELS = {"Speed", "Seats", "Blocks", "Size", "Susp.", "Terrain", "Weight", "Handl.", "Doors", "Lights", "Surf.", "Bullets"};
    private static final int PW = 196, ROW = 16;
    private Vehicle target;
    private int rows = 8, totalRows;
    private String status = "";
    private int statusTicks;
    private long dirtySince, modelSince, modelLast;
    private boolean dirty, modelDirty;
    private Widget.TextField valueField;
    private Widget.Slider valueSlider;
    private final List<Widget.Slider> sliders = new ArrayList<>();
    private final Deque<Snap> undo = new ArrayDeque<>(), redo = new ArrayDeque<>();
    private Snap committed;

    // ------------------------------------------------------------------ the numbers
    /** A setting on a slider: where it lives, its range, how it is written; 'model' = the model has to be analysed again after it. */
    private record P(String page, String label, float min, float max, float step, ToDoubleFunction<VehicleType> get, BiConsumer<VehicleType, Float> set, boolean model, java.util.function.Function<Float, String> format) {}

    private static final List<P> PARAMS = new ArrayList<>();

    private static void p(String page, String label, float min, float max, float step, ToDoubleFunction<VehicleType> get, BiConsumer<VehicleType, Float> set) { PARAMS.add(new P(page, label, min, max, step, get, set, false, null)); }

    private static void p(String page, String label, float min, float max, float step, ToDoubleFunction<VehicleType> get, BiConsumer<VehicleType, Float> set, boolean model, java.util.function.Function<Float, String> fmt) {
        PARAMS.add(new P(page, label, min, max, step, get, set, model, fmt));
    }

    static {
        p("Speed", "Top speed km/h", 10, 300, 1, t -> t.maxSpeed * 72, (t, v) -> t.maxSpeed = v / 72f);
        p("Speed", "Reverse km/h", 5, 120, 1, t -> t.reverseSpeed * 72, (t, v) -> t.reverseSpeed = v / 72f);
        p("Speed", "Acceleration km/h/s", 2, 200, 1, t -> t.accel * 72 * 20, (t, v) -> t.accel = v / 72f / 20f);
        p("Speed", "Braking km/h/s", 10, 400, 1, t -> t.braking * 72 * 20, (t, v) -> t.braking = v / 72f / 20f);
        p("Speed", "Handbrake km/h/s", 10, 400, 1, t -> t.handbrake * 72 * 20, (t, v) -> t.handbrake = v / 72f / 20f);
        p("Speed", "Steering angle", 8, 60, 0.5f, t -> t.steerAngle, (t, v) -> t.steerAngle = v);
        p("Size", "Size (scale)", 0.25f, 4, 0.01f, t -> t.userScale(), (t, v) -> { float f = v / t.userScale(); t.setUserScale(v); t.resize(f, f, f); }, true, null);
        p("Size", "Width x", 0.4f, 2.5f, 0.01f, t -> t.sizeX, (t, v) -> { float f = v / t.sizeX; t.sizeX = v; t.resize(f, 1, 1); }, true, null);
        p("Size", "Height x", 0.4f, 2.5f, 0.01f, t -> t.sizeY, (t, v) -> { float f = v / t.sizeY; t.sizeY = v; t.resize(1, f, 1); }, true, null);
        p("Size", "Length x", 0.4f, 2.5f, 0.01f, t -> t.sizeZ, (t, v) -> { float f = v / t.sizeZ; t.sizeZ = v; t.resize(1, 1, f); }, true, null);
        p("Suspension", "Stiffness", 0.02f, 0.6f, 0.01f, t -> t.springStiffness, (t, v) -> t.springStiffness = v);
        p("Suspension", "Damping", 0.3f, 0.95f, 0.01f, t -> t.springDamping, (t, v) -> t.springDamping = v);
        p("Suspension", "Max pitch (nose)", 0, 15, 0.1f, t -> t.maxPitch, (t, v) -> t.maxPitch = v);
        p("Suspension", "Max roll (lean)", 0, 15, 0.1f, t -> t.maxRoll, (t, v) -> t.maxRoll = v);
        p("Suspension", "Ride height", -0.4f, 0.6f, 0.01f, t -> t.rideHeight, (t, v) -> t.rideHeight = v);
        p("Suspension", "Bounce limit", 0, 0.5f, 0.01f, t -> t.heaveLimit, (t, v) -> t.heaveLimit = v);
        p("Terrain", "Step height", 0.3f, 2.5f, 0.05f, t -> t.stepHeight, (t, v) -> t.stepHeight = v);
        p("Terrain", "Water: speed kept", 0.4f, 1, 0.01f, t -> t.waterDrag, (t, v) -> t.waterDrag = v);
        p("Weight", "Weight kg", 100, 12000, 10, t -> t.weight, (t, v) -> t.weight = v);
        for (int si = 0; si < derpcraft.vehicle.Surfaces.NAMES.length; si++) {
            final int i = si;
            p("Surfaces", derpcraft.vehicle.Surfaces.LABELS[i] + ": grip x", 0.02f, 1.5f, 0.01f, t -> t.surfGrip[i], (t, v) -> t.surfGrip[i] = v);
            p("Surfaces", derpcraft.vehicle.Surfaces.LABELS[i] + ": speed x", 0.2f, 1.5f, 0.01f, t -> t.surfSpeed[i], (t, v) -> t.surfSpeed[i] = v);
        }
        p("Handling", "Grip", 0.05f, 1, 0.01f, t -> t.grip, (t, v) -> t.grip = v);
        p("Handling", "Drift grip", 0, 0.3f, 0.005f, t -> t.driftGrip, (t, v) -> t.driftGrip = v);
        p("Handling", "Drift swing x", 0.5f, 3, 0.05f, t -> t.driftYaw, (t, v) -> t.driftYaw = v);
        p("Handling", "Drift from km/h", 5, 80, 1, t -> t.driftMinSpeed * 72, (t, v) -> t.driftMinSpeed = v / 72f);
        p("Handling", "Police turn from km/h", 30, 160, 1, t -> t.policeSpeed * 72, (t, v) -> t.policeSpeed = v / 72f);
        p("Handling", "Police turn deg/tick", 3, 20, 0.5f, t -> t.policeRate, (t, v) -> t.policeRate = v);
        p("Handling", "Smash from km/h", 10, 200, 1, t -> t.smashSpeed * 72, (t, v) -> t.smashSpeed = v / 72f);
        p("Handling", "Rollover (0 off, 1 on)", 0, 1, 1, t -> t.rolloverOn ? 1 : 0, (t, v) -> t.rolloverOn = v >= 0.5f, false, v -> v >= 0.5f ? "on" : "off");
        p("Handling", "Rollover from km/h", 20, 160, 1, t -> t.rolloverSpeed * 72, (t, v) -> t.rolloverSpeed = v / 72f);
        p("Handling", "Rollover limit", 0, 0.12f, 0.001f, t -> t.rollover, (t, v) -> t.rollover = v, false, v -> v <= 0 ? "never" : String.format(Locale.ROOT, "%.3f", v));
        p("Handling", "Roll damage x", 0, 3, 0.05f, t -> t.rollDamage, (t, v) -> t.rollDamage = v);
    }

    // ------------------------------------------------------------------ undo
    /** All the editor's numbers, seats, block places and pivots at a moment. */
    private record Snap(float[] vals, List<float[]> seats, List<float[]> blocks, boolean blocksSet, Map<String, float[]> pivots, List<float[]> eyes, List<Float> yaws, List<VehicleType.Light> lights, boolean lightsSet, Map<String, Float> angles, Map<String, Boolean> bullets) {
        @Override public boolean equals(Object o) {
            return o instanceof Snap n && Arrays.equals(vals, n.vals) && same(seats, n.seats) && same(eyes, n.eyes) && yaws.equals(n.yaws) && same(blocks, n.blocks) && blocksSet == n.blocksSet && lightsSet == n.lightsSet && sameLights(lights, n.lights) && angles.equals(n.angles) && bullets.equals(n.bullets);
        }
        @Override public int hashCode() { return Arrays.hashCode(vals); }
        private static boolean sameLights(List<VehicleType.Light> a, List<VehicleType.Light> b) {
            if (a.size() != b.size()) return false;
            for (int i = 0; i < a.size(); i++) { VehicleType.Light x = a.get(i), y = b.get(i); if (!Arrays.equals(x.a, y.a) || !Arrays.equals(x.b, y.b) || !Arrays.equals(x.color, y.color) || x.width != y.width || x.intensity != y.intensity) return false; }
            return true;
        }
        private static boolean same(List<float[]> a, List<float[]> b) {
            if (a.size() != b.size()) return false;
            for (int i = 0; i < a.size(); i++) if (!Arrays.equals(a.get(i), b.get(i))) return false;      // (null = null)
            return true;
        }
    }

    private static Snap snap(VehicleType t) {
        float[] v = new float[PARAMS.size()];
        for (int i = 0; i < v.length; i++) v[i] = (float) PARAMS.get(i).get.applyAsDouble(t);
        List<float[]> se = new ArrayList<>(), bl = new ArrayList<>();
        for (float[] a : t.seats) se.add(a.clone());
        for (float[] a : t.blocks) bl.add(a.clone());
        Map<String, float[]> pv = new HashMap<>();
        for (var e : t.pivots.entrySet()) pv.put(e.getKey(), e.getValue().clone());
        List<float[]> ey = new ArrayList<>();
        for (float[] a : t.eyes) ey.add(a == null ? null : a.clone());
        List<VehicleType.Light> lg = new ArrayList<>();
        for (VehicleType.Light l : t.lights) lg.add(l.copy());
        return new Snap(v, se, bl, t.blocksSet, pv, ey, new ArrayList<>(t.seatYaws), lg, t.lightsSet, new HashMap<>(t.angles), new HashMap<>(t.bullets));
    }

    private static void restore(VehicleType t, Snap sn) {
        for (int i = 0; i < sn.vals.length; i++) PARAMS.get(i).set.accept(t, sn.vals[i]);
        t.seats.clear(); for (float[] a : sn.seats) t.seats.add(a.clone());
        t.seatYaws.clear(); t.seatYaws.addAll(sn.yaws);
        t.lights.clear(); for (VehicleType.Light l : sn.lights) t.lights.add(l.copy());
        t.lightsSet = sn.lightsSet;
        t.angles.clear(); t.angles.putAll(sn.angles);
        t.bullets.clear(); t.bullets.putAll(sn.bullets);
        t.eyes.clear(); for (float[] a : sn.eyes) t.eyes.add(a == null ? null : a.clone());
        t.blocks.clear(); for (float[] a : sn.blocks) t.blocks.add(a.clone());
        t.blocksSet = sn.blocksSet;
        t.pivots.clear(); for (var e : sn.pivots.entrySet()) t.pivots.put(e.getKey(), e.getValue().clone());
    }

    public VehicleEditor(DerpCraft g, Session s) {
        super(g);
        this.s = s;
        pausesGame = false;              // the car keeps living (and driving) while it is tuned
        target = s.vehicles == null ? null : s.vehicles.editTarget();
    }

    @Override public boolean recorded() { return false; }

    private VehicleType type() { return target == null ? null : target.type(); }

    private VehicleModel vm() { return target == null ? null : target.vm(); }

    // ------------------------------------------------------------------ layout
    @Override public void init() {
        if (valueField != null && valueSlider == null) valueField = null;
        sliders.clear();
        int x = 4, w = PW - 8, y = 16;
        if (target != null && !s.vehicles.list().contains(target)) target = null;
        VehicleType t = type();
        if (target == null || t == null || vm() == null) { s.vehicles.markVehicle = null; initNone(x, y, w); return; }
        if (committed == null) committed = snap(t);
        int perRow = 4, pw = (w - 2 * (perRow - 1)) / perRow;
        for (int i = 0; i < PAGES.length; i++) {
            final int k = i;
            int row = i / perRow, col = i % perRow;
            add(new Widget.Button(x + col * (pw + 2), y + row * (ROW + 2), col == perRow - 1 ? w - col * (pw + 2) : pw, ROW,
                    () -> (page == k ? "§a" : "") + PAGE_LABELS[k] + (pageChanged(PAGES[k]) ? "§7*" : ""),
                    () -> { page = k; scroll = 0; game.relayout(); }));
        }
        y += ((PAGES.length + perRow - 1) / perRow) * (ROW + 2);
        int bottom = height - 3 * (ROW + 2) - 14;
        List<Runnable> rowsToAdd = new ArrayList<>();
        int[] yy = {y};
        String pg = PAGES[page];
        VehicleType def = VehicleType.defaults();
        switch (pg) {
            case "Seats" -> seatsPage(rowsToAdd, yy, x, w);
            case "Blocks" -> blocksPage(rowsToAdd, yy, x, w);
            case "Doors" -> doorsPage(rowsToAdd, yy, x, w);
            case "Lights" -> lightsPage(rowsToAdd, yy, x, w);
            case "Bullets" -> bulletsPage(rowsToAdd, yy, x, w);
            default -> {
                for (P p : PARAMS) if (p.page.equals(pg)) paramRow(rowsToAdd, yy, x, w, p, def);
                if (pg.equals("Size")) rowsToAdd.add(() -> add(new Widget.Button(x, yy[0], w, ROW, "Reset the size", () -> { for (P p : PARAMS) if (p.page.equals("Size")) resetParam(p, def); game.relayout(); })));
            }
        }
        rows = Math.max(3, (bottom - yy[0] - 4) / (ROW + 2));
        scroll = Math.max(0, Math.min(scroll, rowsToAdd.size() - rows));
        for (int i = scroll; i < rowsToAdd.size() && i < scroll + rows; i++) { rowsToAdd.get(i).run(); yy[0] += ROW + 2; }
        totalRows = rowsToAdd.size();
        bottomButtons(x, w);
        if (valueField != null) widgets.add(valueField);
        refreshMarks();
    }

    private void initNone(int x, int y, int w) {
        sliders.clear();
        List<String> names = Vehicles.vehicleModels();
        int n = 0;
        for (String name : names) {
            if (n >= 8) break;
            add(new Widget.Button(x, y + 56 + n * (ROW + 2), w, ROW, "Spawn " + name + " ahead", () -> spawnAhead(name)));
            n++;
        }
        add(new Widget.Button(x, height - ROW - 6, w, ROW, "Close", this::onClose));
    }

    private void spawnAhead(String name) {
        double yr = Math.toRadians(s.player.yaw);
        double px = s.player.x - Math.sin(yr) * 4, pz = s.player.z + Math.cos(yr) * 4;
        double py = s.world.topSolidY((int) Math.floor(px), (int) Math.floor(pz)) + 1;
        Vehicle v = s.vehicles.spawn(name, px, py, pz, s.player.yaw);
        if (v != null) { target = v; committed = null; undo.clear(); redo.clear(); game.relayout(); }
    }

    private boolean pageChanged(String pg) {
        VehicleType t = type();
        if (t == null) return false;
        VehicleType d = VehicleType.defaults();
        if (pg.equals("Seats")) return !t.seats.isEmpty();
        if (pg.equals("Blocks")) return t.blocksSet;
        if (pg.equals("Bullets")) return !t.bullets.isEmpty();
        if (pg.equals("Lights")) return t.lightsSet;
        if (pg.equals("Doors")) return !t.angles.isEmpty() || !t.pivots.isEmpty();
        for (P p : PARAMS) if (p.page.equals(pg) && differs(p, t, d)) return true;
        return false;
    }

    private static boolean differs(P p, VehicleType t, VehicleType d) { return Math.abs(p.get.applyAsDouble(t) - p.get.applyAsDouble(d)) > p.step * 0.01; }

    private void paramRow(List<Runnable> rowsToAdd, int[] yy, int x, int w, P p, VehicleType def) {
        rowsToAdd.add(() -> {
            float dv = (float) p.get.applyAsDouble(def);
            slider(x, yy[0], w, p.label, p.min, p.max, p.step,
                    () -> { VehicleType t = type(); return t == null ? 0 : p.get.applyAsDouble(t); },
                    v -> { VehicleType t = type(); if (t != null) { begin(); p.set.accept(t, (float) v); changed(p.model); } },
                    dv, () -> { VehicleType t = type(); return t != null && differs(p, t, def); }, p.format);
        });
    }

    private void resetParam(P p, VehicleType def) {
        VehicleType t = type();
        if (t == null) return;
        begin();
        p.set.accept(t, (float) p.get.applyAsDouble(def));
        changed(p.model);
    }

    private void slider(int x, int y, int w, String label, float min, float max, float step, java.util.function.DoubleSupplier get, java.util.function.DoubleConsumer set,
                        double def, java.util.function.BooleanSupplier highlight, java.util.function.Function<Float, String> format) {
        int bw = 14;
        add(new Widget.Button(x, y, bw, ROW, "-", () -> set.accept(clamp(get.getAsDouble() - step * mult(), min, max))));
        Widget.Slider sl = add(new Widget.Slider(x + bw + 1, y, w - 2 * bw - 2, ROW, label, min, max, step, get, set, format != null ? format : v -> fmt(v, step)));
        sl.defaultValue = def;
        sl.highlight = highlight;
        sliders.add(sl);
        add(new Widget.Button(x + w - bw, y, bw, ROW, "+", () -> set.accept(clamp(get.getAsDouble() + step * mult(), min, max))));
    }

    // ------------------------------------------------------------------ doors, hood, trunk
    private static int doorIx = 0, lightIx = 0;

    private VehicleModel.Part doorNow() { VehicleModel m = vm(); return m == null || m.openables.isEmpty() ? null : m.openables.get(Math.max(0, Math.min(doorIx, m.openables.size() - 1))); }

    private void doorsPage(List<Runnable> rowsToAdd, int[] yy, int x, int w) {
        VehicleType t = type();
        VehicleModel m = vm();
        int n = m.openables.size();
        if (n == 0) { rowsToAdd.add(() -> add(new Widget.Button(x, yy[0], w, ROW, "§7No doors, hood or trunk in the model", () -> {}))); return; }
        doorIx = Math.max(0, Math.min(doorIx, n - 1));
        rowsToAdd.add(() -> picker(x, yy[0], w, n, doorIx, i -> { doorIx = i; game.relayout(); }));
        rowsToAdd.add(() -> {
            int bw = (w - 2) / 2;
            add(new Widget.Button(x, yy[0], bw, ROW, () -> doorOpen() ? "Close it" : "Open it (test)", () -> toggleDoor(false)));
            add(new Widget.Button(x + bw + 2, yy[0], w - bw - 2, ROW, "Open / close all", () -> toggleDoor(true)));
        });
        rowsToAdd.add(() -> slider(x, yy[0], w, "Angle (- other way)", -120, 120, 1,
                () -> { VehicleModel.Part p = doorNow(); return p == null ? 0 : p.openAngle; }, v -> setDoorAngle((float) v), 70, () -> false, null));
        String[] ax = {"Hinge X (right)", "Hinge Y (up)", "Hinge Z (back)"};
        float[][] range = {{-4, 4}, {-1, 3}, {-6, 6}};
        for (int k = 0; k < 3; k++) {
            final int c = k;
            rowsToAdd.add(() -> slider(x, yy[0], w, ax[c], range[c][0], range[c][1], 0.01f,
                    () -> { VehicleModel.Part p = doorNow(); return p == null ? 0 : c == 0 ? p.px : c == 1 ? p.py : p.pz; }, v -> setDoorPivot(c, (float) v), 0, () -> false, null));
        }
        rowsToAdd.add(() -> add(new Widget.Button(x, yy[0], w, ROW, "This part as in the model (reset)", () -> {
            VehicleModel.Part p = doorNow();
            if (p == null) return;
            begin(); t.angles.remove(p.name.toLowerCase(Locale.ROOT)); t.pivots.remove(p.name.toLowerCase(Locale.ROOT)); changed(true); rebuildNow(); game.relayout();
        })));
    }

    // ------------------------------------------------------------------ bullets
    private static int bulletIx = 0;

    /** Bullets page: every part of the model, whether bullets go through it (glass, thin body panels) or stop in it. */
    private void bulletsPage(List<Runnable> rowsToAdd, int[] yy, int x, int w) {
        VehicleType t = type();
        VehicleModel m = vm();
        rowsToAdd.add(() -> {
            int bw = (w - 4) / 3;
            add(new Widget.Button(x, yy[0], bw, ROW, "All pass", () -> setAllBullets(false)));
            add(new Widget.Button(x + bw + 2, yy[0], bw, ROW, "All stop", () -> setAllBullets(true)));
            add(new Widget.Button(x + 2 * (bw + 2), yy[0], w - 2 * (bw + 2), ROW, "Defaults", () -> { begin(); t.bullets.clear(); changed(false); game.relayout(); }));
        });
        for (int i = 0; i < m.parts.size(); i++) {
            final int k = i;
            VehicleModel.Part p = m.parts.get(i);
            rowsToAdd.add(() -> add(new Widget.Button(x, yy[0], w, ROW, () -> (k == bulletIx ? "§e> §r" : "") + p.name + " §7(" + roleName(p) + ")§r: " + (t.stopsBullets(p) ? "§cstops" : "§apasses"), () -> {
                bulletIx = k;
                begin();
                boolean stop = !t.stopsBullets(p);
                String key = p.name.toLowerCase(Locale.ROOT);
                if (stop == (p.role == VehicleModel.WHEEL)) t.bullets.remove(key); else t.bullets.put(key, stop);
                changed(false);
            })));
        }
    }

    private static String roleName(VehicleModel.Part p) {
        return switch (p.role) { case VehicleModel.WHEEL -> "wheel"; case VehicleModel.OPENABLE -> "door/lid"; case VehicleModel.STEERING -> "wheel (steering)"; default -> "body"; };
    }

    private void setAllBullets(boolean stop) {
        VehicleType t = type();
        VehicleModel m = vm();
        if (t == null || m == null) return;
        begin();
        t.bullets.clear();
        for (VehicleModel.Part p : m.parts) if (stop != (p.role == VehicleModel.WHEEL)) t.bullets.put(p.name.toLowerCase(Locale.ROOT), stop);
        changed(false);
        game.relayout();
    }

    private boolean doorOpen() {
        VehicleModel m = vm();
        VehicleModel.Part p = doorNow();
        int i = p == null ? -1 : m.openables.indexOf(p);
        return i >= 0 && i < target.openTarget.length && target.openTarget[i];
    }

    private void toggleDoor(boolean all) {
        VehicleModel m = vm();
        if (m == null || target == null) return;
        if (all) { boolean any = false; for (boolean b : target.openTarget) any |= b; for (int i = 0; i < target.openTarget.length; i++) target.openTarget[i] = !any; return; }
        int i = m.openables.indexOf(doorNow());
        if (i >= 0 && i < target.openTarget.length) target.openTarget[i] = !target.openTarget[i];
    }

    private void setDoorAngle(float a) {
        VehicleType t = type();
        VehicleModel.Part p = doorNow();
        if (t == null || p == null) return;
        begin();
        p.openAngle = a;
        float flip = (a < 0 ? -1 : 1) * p.geoSign;
        t.angles.put(p.name.toLowerCase(Locale.ROOT), flip * Math.abs(a));
        changed(false);
        if (!doorOpen()) toggleDoor(false);                 // shown open to see it
    }

    private void setDoorPivot(int c, float v) {
        VehicleType t = type();
        VehicleModel.Part p = doorNow();
        if (t == null || p == null) return;
        begin();
        if (c == 0) p.px = v; else if (c == 1) p.py = v; else p.pz = v;
        t.pivots.put(p.name.toLowerCase(Locale.ROOT), new float[]{p.px, p.py, p.pz});
        changed(false);
    }

    // ------------------------------------------------------------------ lights
    private List<VehicleType.Light> lightsEdit() {
        VehicleType t = type();
        if (!t.lightsSet) { List<VehicleType.Light> d = t.effectiveLights(); t.lights.clear(); for (VehicleType.Light l : d) t.lights.add(l.copy()); t.lightsSet = true; }
        return t.lights;
    }

    private VehicleType.Light lightNow() {
        VehicleType t = type();
        List<VehicleType.Light> l = t == null ? List.of() : t.effectiveLights();
        return l.isEmpty() ? null : l.get(Math.max(0, Math.min(lightIx, l.size() - 1)));
    }

    private void lightsPage(List<Runnable> rowsToAdd, int[] yy, int x, int w) {
        VehicleType t = type();
        target.lightsOn = true;                              // seen while editing
        int n = t.effectiveLights().size();
        lightIx = Math.max(0, Math.min(lightIx, Math.max(0, n - 1)));
        if (n > 0) rowsToAdd.add(() -> picker(x, yy[0], w, n, lightIx, i -> { lightIx = i; game.relayout(); }));
        rowsToAdd.add(() -> {
            int bw = (w - 4) / 3;
            add(new Widget.Button(x, yy[0], bw, ROW, "Add light", this::addLight));
            add(new Widget.Button(x + bw + 2, yy[0], bw, ROW, "Remove", this::removeLight));
            add(new Widget.Button(x + 2 * (bw + 2), yy[0], w - 2 * (bw + 2), ROW, () -> target.lightsOn ? "Lights on" : "§7Lights off", () -> target.lightsOn = !target.lightsOn));
        });
        if (n > 0) {
            String[] nm = {"Start X (lamp)", "Start Y", "Start Z", "End X (beam end)", "End Y", "End Z"};
            float[][] rg = {{-4, 4}, {-1, 4}, {-8, 8}};
            for (int k = 0; k < 6; k++) {
                final int c = k;
                rowsToAdd.add(() -> slider(x, yy[0], w, nm[c], c == 5 ? -30 : rg[c % 3][0], c == 5 ? 8 : rg[c % 3][1], 0.01f,
                        () -> { VehicleType.Light l = lightNow(); return l == null ? 0 : (c < 3 ? l.a : l.b)[c % 3]; }, v -> setLight(c, (float) v), 0, () -> false, null));
            }
            rowsToAdd.add(() -> slider(x, yy[0], w, "Beam width (end)", 0.1f, 10, 0.05f, () -> { VehicleType.Light l = lightNow(); return l == null ? 0 : l.width; }, v -> setLight(6, (float) v), 3, () -> false, null));
            rowsToAdd.add(() -> slider(x, yy[0], w, "Intensity", 0, 3, 0.05f, () -> { VehicleType.Light l = lightNow(); return l == null ? 0 : l.intensity; }, v -> setLight(7, (float) v), 1, () -> false, null));
            String[] cn = {"Red", "Green", "Blue"};
            for (int k = 0; k < 3; k++) {
                final int c = k;
                rowsToAdd.add(() -> slider(x, yy[0], w, cn[c], 0, 1, 0.01f, () -> { VehicleType.Light l = lightNow(); return l == null ? 0 : l.color[c]; }, v -> setLight(8 + c, (float) v), 1, () -> false, null));
            }
        }
        rowsToAdd.add(() -> add(new Widget.Button(x, yy[0], w, ROW, "Lights of the model (reset)", () -> { begin(); t.lights.clear(); t.lightsSet = false; changed(false); game.relayout(); })));
    }

    private void setLight(int c, float v) {
        VehicleType t = type();
        if (t == null) return;
        VehicleType.Light cur = lightNow();
        if (cur == null) return;
        begin();
        int ix = t.effectiveLights().indexOf(cur);
        List<VehicleType.Light> l = lightsEdit();
        if (ix < 0 || ix >= l.size()) return;
        VehicleType.Light e = l.get(ix);
        if (c < 3) e.a[c] = v; else if (c < 6) e.b[c - 3] = v; else if (c == 6) e.width = v; else if (c == 7) e.intensity = v; else e.color[c - 8] = v;
        changed(false);
    }

    private void addLight() {
        VehicleType t = type();
        VehicleModel m = vm();
        if (t == null || m == null) return;
        List<VehicleType.Light> l = lightsEdit();
        if (l.size() >= 8) { say("§cNot more than 8 lights"); return; }
        begin();
        VehicleType.Light n = new VehicleType.Light();
        n.a = new float[]{0, m.maxY * 0.5f, m.minZ + 0.1f};
        n.b = new float[]{0, m.maxY * 0.2f, m.minZ - 14};
        l.add(n);
        lightIx = l.size() - 1;
        changed(false);
        game.relayout();
    }

    private void removeLight() {
        VehicleType t = type();
        if (t == null) return;
        List<VehicleType.Light> l = lightsEdit();
        if (l.isEmpty()) return;
        begin();
        l.remove(Math.min(lightIx, l.size() - 1));
        lightIx = Math.max(0, Math.min(lightIx, l.size() - 1));
        changed(false);
        game.relayout();
    }

    // ------------------------------------------------------------------ seats and block places
    private void seatsPage(List<Runnable> rowsToAdd, int[] yy, int x, int w) {
        VehicleType t = type();
        int n = vm().seats.size();
        seatIx = Math.max(0, Math.min(seatIx, n - 1));
        rowsToAdd.add(() -> picker(x, yy[0], w, n, seatIx, i -> { seatIx = i; game.relayout(); }));
        rowsToAdd.add(() -> {
            int bw = (w - 4) / 3;
            add(new Widget.Button(x, yy[0], bw, ROW, "Add seat", this::addSeat));
            add(new Widget.Button(x + bw + 2, yy[0], bw, ROW, "Remove last", this::removeSeat));
            add(new Widget.Button(x + 2 * (bw + 2), yy[0], w - 2 * (bw + 2), ROW, "Mirror", this::mirrorSeat));
        });
        String[] ax = {"X (right)", "Y (up)", "Z (back)"};
        float[][] range = {{-3, 3}, {-1, 3}, {-8, 8}};
        for (int k = 0; k < 3; k++) {
            final int c = k;
            rowsToAdd.add(() -> slider(x, yy[0], w, ax[c], range[c][0], range[c][1], 0.01f,
                    () -> { float[] p = seatNow(); return p == null ? 0 : p[c]; },
                    v -> setSeat(c, (float) v), seatDefault(c), () -> false, null));
        }
        // the turn of the seat around the vertical (a seat in the boot faces backwards)
        rowsToAdd.add(() -> slider(x, yy[0], w, "Seat turn (yaw)", -180, 180, 1,
                () -> { VehicleModel m = vm(); return m == null || seatIx >= m.seatYaw.size() ? 0 : m.seatYaw.get(seatIx); },
                v -> setSeatYaw((float) v), 0, () -> false, null));
        // the camera point of this seat: independent of how the rider sits
        rowsToAdd.add(() -> add(new Widget.Button(x, yy[0], w, ROW, () -> "Camera: " + (eyeNow() == null ? "§7default (above the seat)" : "§acustom point §7(click: default)"), this::toggleEye)));
        if (eyeNow() != null) {
            String[] eax = {"Cam X (right)", "Cam Y (up)", "Cam Z (back)"};
            float[][] er = {{-3, 3}, {-1, 4}, {-8, 8}};
            for (int k = 0; k < 3; k++) {
                final int c = k;
                rowsToAdd.add(() -> slider(x, yy[0], w, eax[c], er[c][0], er[c][1], 0.01f,
                        () -> { float[] p = eyeNow(); return p == null ? 0 : p[c]; }, v -> setEye(c, (float) v), 0, () -> false, null));
            }
        }
        rowsToAdd.add(() -> add(new Widget.Button(x, yy[0], w, ROW, "Seats of the model (reset)", () -> { begin(); t.seats.clear(); t.eyes.clear(); t.seatYaws.clear(); changed(true); rebuildNow(); game.relayout(); })));
    }

    private void setSeatYaw(float v) {
        VehicleType t = type();
        VehicleModel m = vm();
        if (t == null || m == null || seatIx >= m.seatYaw.size()) return;
        begin();
        while (t.seatYaws.size() < m.seats.size()) t.seatYaws.add(0f);
        t.seatYaws.set(seatIx, v);
        m.seatYaw.set(seatIx, v);
        changed(false);
    }

    private float[] eyeNow() { VehicleModel m = vm(); return m == null || seatIx >= m.eyes.size() ? null : m.eyes.get(seatIx); }

    private void padEyes() {
        VehicleType t = type();
        VehicleModel m = vm();
        if (t == null || m == null) return;
        while (t.eyes.size() < m.seats.size()) t.eyes.add(null);
    }

    /** The seat's camera: the usual place above the seat, or a point of its own. */
    private void toggleEye() {
        VehicleType t = type();
        VehicleModel m = vm();
        float[] s0 = seatNow();
        if (t == null || m == null || s0 == null) return;
        begin();
        padEyes();
        if (eyeNow() == null) {
            float[] e = {s0[0], s0[1] + Vehicles.SIT_DROP + Vehicles.SIT_EYE, s0[2]};
            t.eyes.set(seatIx, e.clone());
            m.eyes.set(seatIx, e);
        } else { t.eyes.set(seatIx, null); m.eyes.set(seatIx, null); }
        changed(false);
        game.relayout();
    }

    private void setEye(int c, float v) {
        VehicleType t = type();
        float[] e = eyeNow();
        if (t == null || e == null) return;
        begin();
        padEyes();
        e[c] = v;
        if (seatIx < t.eyes.size() && t.eyes.get(seatIx) != null) t.eyes.get(seatIx)[c] = v;
        changed(false);
    }

    private double seatDefault(int c) { VehicleModel m = vm(); float[] d = m == null || m.seats.isEmpty() ? new float[]{0, 0.55f, 0} : m.seats.get(0); return d[c]; }

    private float[] seatNow() { VehicleModel m = vm(); return m == null || seatIx >= m.seats.size() ? null : m.seats.get(seatIx); }

    /** The settings keep the seats once the editor touches them (they are the numbers of the seats). */
    private void materializeSeats() {
        VehicleType t = type();
        VehicleModel m = vm();
        if (t == null || m == null || !t.seats.isEmpty()) return;
        for (float[] a : m.seats) t.seats.add(a.clone());
    }

    private void setSeat(int c, float v) {
        VehicleType t = type();
        VehicleModel m = vm();
        if (t == null || m == null || seatIx >= m.seats.size()) return;
        begin();
        materializeSeats();
        if (seatIx < t.seats.size()) t.seats.get(seatIx)[c] = v;
        m.seats.get(seatIx)[c] = v;
        changed(true);
    }

    private void addSeat() {
        VehicleType t = type();
        VehicleModel m = vm();
        if (t == null || m == null) return;
        if (m.seats.size() >= 8) { say("§cNot more than 8 seats"); return; }
        begin();
        materializeSeats();
        float[] last = t.seats.get(t.seats.size() - 1);
        // the other side of the car, else behind the last seat (the first spot no other seat is on)
        float[][] tries = {{-last[0], last[1], last[2]}, {last[0], last[1], last[2] + 0.85f}, {-last[0], last[1], last[2] + 0.85f}, {last[0], last[1], last[2] + 1.7f}, {-last[0], last[1], last[2] + 1.7f}};
        float[] n = tries[tries.length - 1];
        for (float[] c : tries) {
            boolean free = true;
            for (float[] o : t.seats) if (Math.abs(o[0] - c[0]) < 0.35f && Math.abs(o[2] - c[2]) < 0.35f) free = false;
            if (free) { n = c; break; }
        }
        t.seats.add(n.clone());
        while (t.eyes.size() < t.seats.size()) t.eyes.add(null);
        while (t.seatYaws.size() < t.seats.size() - 1) t.seatYaws.add(0f);
        t.seatYaws.add(0f);
        seatIx = t.seats.size() - 1;
        changed(true);
        rebuildNow();
        game.relayout();
    }

    private void removeSeat() {
        VehicleType t = type();
        VehicleModel m = vm();
        if (t == null || m == null) return;
        if (m.seats.size() <= 1) { say("§cA vehicle needs a seat"); return; }
        begin();
        materializeSeats();
        int last = t.seats.size() - 1;
        for (Vehicle v : s.vehicles.list()) if (v.model.equalsIgnoreCase(t.name) && last < v.riders.length && v.riders[last] != Vehicle.NONE) s.vehicles.ejectSeat(v, last);
        t.seats.remove(last);
        if (last < t.eyes.size()) t.eyes.remove(last);
        if (last < t.seatYaws.size()) t.seatYaws.remove(last);
        seatIx = Math.min(seatIx, t.seats.size() - 1);
        changed(true);
        rebuildNow();
        game.relayout();
    }

    private void mirrorSeat() {
        float[] p = seatNow();
        if (p == null) return;
        setSeat(0, -p[0]);
        game.relayout();
    }

    private void blocksPage(List<Runnable> rowsToAdd, int[] yy, int x, int w) {
        VehicleType t = type();
        int n = vm().slots.size();
        blockIx = Math.max(0, Math.min(blockIx, Math.max(0, n - 1)));
        if (n > 0) rowsToAdd.add(() -> picker(x, yy[0], w, n, blockIx, i -> { blockIx = i; game.relayout(); }));
        rowsToAdd.add(() -> {
            int bw = (w - 2) / 2;
            add(new Widget.Button(x, yy[0], bw, ROW, "Add place", this::addBlock));
            add(new Widget.Button(x + bw + 2, yy[0], w - bw - 2, ROW, "Remove last", this::removeBlock));
        });
        if (n > 0) {
            String[] ax = {"X (right)", "Y (bottom)", "Z (back)"};
            float[][] range = {{-4, 4}, {-1, 4}, {-8, 8}};
            for (int k = 0; k < 3; k++) {
                final int c = k;
                rowsToAdd.add(() -> slider(x, yy[0], w, ax[c], range[c][0], range[c][1], 0.01f,
                        () -> { float[] p = blockNow(); return p == null ? 0 : p[c]; },
                        v -> setBlock(c, (float) v), 0, () -> false, null));
            }
        }
        rowsToAdd.add(() -> add(new Widget.Button(x, yy[0], w, ROW, "Places of the model (reset)", () -> { begin(); t.blocks.clear(); t.blocksSet = false; changed(true); rebuildNow(); game.relayout(); })));
    }

    private float[] blockNow() { VehicleModel m = vm(); return m == null || blockIx >= m.slots.size() ? null : m.slots.get(blockIx); }

    private void materializeBlocks() {
        VehicleType t = type();
        VehicleModel m = vm();
        if (t == null || m == null || t.blocksSet) return;
        t.blocksSet = true;
        t.blocks.clear();
        for (float[] a : m.slots) t.blocks.add(a.clone());
    }

    private void setBlock(int c, float v) {
        VehicleType t = type();
        VehicleModel m = vm();
        if (t == null || m == null || blockIx >= m.slots.size()) return;
        begin();
        materializeBlocks();
        if (blockIx < t.blocks.size()) t.blocks.get(blockIx)[c] = v;
        m.slots.get(blockIx)[c] = v;
        changed(true);
    }

    private void addBlock() {
        VehicleType t = type();
        VehicleModel m = vm();
        if (t == null || m == null) return;
        if (m.slots.size() >= VehicleModel.MAX_SLOTS) { say("§cNot more than " + VehicleModel.MAX_SLOTS + " block places"); return; }
        begin();
        materializeBlocks();
        int k = t.blocks.size();
        t.blocks.add(new float[]{0, m.maxY, (m.minZ + m.maxZ) / 2 + (k % 2 == 0 ? -1 : 1) * ((k + 1) / 2) * 1.1f});       // on the roof, the first in the middle, the next ones fore and aft
        blockIx = t.blocks.size() - 1;
        changed(true);
        rebuildNow();
        game.relayout();
    }

    private void removeBlock() {
        VehicleType t = type();
        VehicleModel m = vm();
        if (t == null || m == null || m.slots.isEmpty()) return;
        begin();
        materializeBlocks();
        int last = t.blocks.size() - 1;
        if (last < 0) return;
        t.blocks.remove(last);
        s.vehicles.blockPlaceRemoved(t.name, last);
        blockIx = Math.max(0, Math.min(blockIx, t.blocks.size() - 1));
        changed(true);
        rebuildNow();
        game.relayout();
    }

    private void picker(int x, int y, int w, int n, int sel, java.util.function.IntConsumer pick) {
        int bw = (w - 2 * (n - 1)) / Math.max(1, n);
        for (int i = 0; i < n; i++) {
            final int k = i;
            add(new Widget.Button(x + i * (bw + 2), y, i == n - 1 ? w - i * (bw + 2) : bw, ROW, (k == sel ? "§e" : "") + (k + 1), () -> pick.accept(k)));
        }
    }

    // ------------------------------------------------------------------ the buttons at the bottom
    private void bottomButtons(int x, int w) {
        int by = height - 3 * (ROW + 2) - 4, bw3 = (w - 4) / 3;
        add(new Widget.Button(x, by, bw3, ROW, () -> "#" + (target == null ? "-" : target.id) + " next", this::nextVehicle));
        add(new Widget.Button(x + bw3 + 2, by, bw3, ROW, () -> s.view == 0 ? "1st pers." : s.view == 1 ? "3rd pers." : "3rd front", () -> s.cycleView()));
        add(new Widget.Button(x + 2 * (bw3 + 2), by, w - 2 * (bw3 + 2), ROW, "Close", this::onClose));
        by += ROW + 2;
        add(new Widget.Button(x, by, bw3, ROW, () -> (undo.isEmpty() && !dirty ? "§7" : "") + "Undo", this::undo));
        add(new Widget.Button(x + bw3 + 2, by, bw3, ROW, () -> (redo.isEmpty() ? "§7" : "") + "Redo", this::redo));
        add(new Widget.Button(x + 2 * (bw3 + 2), by, w - 2 * (bw3 + 2), ROW, "Reset page", this::resetPage));
        by += ROW + 2;
        int bw2 = (w - 2) / 2;
        add(new Widget.Button(x, by, bw2, ROW, "Reload files", () -> { flush(); Transports.rescan(); committed = null; undo.clear(); redo.clear(); say("Reloaded " + Vehicles.vehicleModels().size() + " vehicle kinds"); game.relayout(); }));
        add(new Widget.Button(x + bw2 + 2, by, w - bw2 - 2, ROW, "Save", this::save));
    }

    private void nextVehicle() {
        List<Vehicle> l = new ArrayList<>(s.vehicles.list());
        if (l.isEmpty()) return;
        l.sort(Comparator.comparingDouble(v -> v.distSq(s.player.x, s.player.z)));
        int i = target == null ? -1 : l.indexOf(target);
        flush();
        target = l.get((i + 1) % l.size());
        committed = null; undo.clear(); redo.clear();
        game.relayout();
    }

    private void resetPage() {
        VehicleType t = type();
        if (t == null) return;
        String pg = PAGES[page];
        begin();
        VehicleType d = VehicleType.defaults();
        if (pg.equals("Seats")) { t.seats.clear(); t.eyes.clear(); t.seatYaws.clear(); changed(true); rebuildNow(); }
        else if (pg.equals("Blocks")) { t.blocks.clear(); t.blocksSet = false; changed(true); rebuildNow(); }
        else if (pg.equals("Bullets")) { t.bullets.clear(); changed(false); }
        else for (P p : PARAMS) if (p.page.equals(pg)) { p.set.accept(t, (float) p.get.applyAsDouble(d)); changed(p.model); }
        game.relayout();
    }

    // ------------------------------------------------------------------ changes, undo, saving
    private void begin() {
        VehicleType t = type();
        if (t == null || dirty || committed == null) return;
        if (undo.isEmpty() || !undo.peek().equals(committed)) undo.push(committed);
        while (undo.size() > 200) undo.removeLast();
        redo.clear();
    }

    private void changed(boolean model) {
        long now = System.currentTimeMillis();
        if (!dirty) { dirty = true; }
        dirtySince = now;
        if (model) { if (!modelDirty) modelSince = now; modelDirty = true; modelLast = now; }
    }

    private void undo() {
        VehicleType t = type();
        if (t == null) return;
        flush();
        if (undo.isEmpty()) { say("§7Nothing to undo"); return; }
        redo.push(snap(t));
        restore(t, undo.pop());
        committed = snap(t);
        modelDirty = true; modelLast = modelSince = System.currentTimeMillis();
        write();
        say("Undo §7(" + undo.size() + " more)");
        game.relayout();
    }

    private void redo() {
        VehicleType t = type();
        if (t == null) return;
        flush();
        if (redo.isEmpty()) { say("§7Nothing to redo"); return; }
        undo.push(snap(t));
        restore(t, redo.pop());
        committed = snap(t);
        modelDirty = true; modelLast = modelSince = System.currentTimeMillis();
        write();
        say("Redo §7(" + redo.size() + " more)");
        game.relayout();
    }

    private void rebuildNow() {
        VehicleType t = type();
        if (t == null) return;
        t.rebuild();
        modelDirty = false;
    }

    private void flush() { if (dirty) save(); if (modelDirty) rebuildNow(); }

    private void save() {
        VehicleType t = type();
        if (t == null) return;
        if (modelDirty) rebuildNow();
        if (write()) say("§aSaved " + t.settingsFile().getFileName());
    }

    private boolean write() {
        VehicleType t = type();
        if (t == null) return false;
        try { t.save(); committed = snap(t); dirty = false; return true; }
        catch (Exception e) { say("§cCannot save: " + e.getMessage()); dirty = false; return false; }
    }

    private void say(String m) { status = m; statusTicks = 80; }

    @Override public void tick() {
        super.tick();
        if (statusTicks > 0) statusTicks--;
        long now = System.currentTimeMillis();
        // the model is analysed again a moment after the last change (not at every step of a drag; at least twice a second)
        if (modelDirty && (now - modelLast > 200 || now - modelSince > 600)) { rebuildNow(); if (modelSince > 0 && now - modelSince > 600) modelSince = now; }
        if (dirty && now - dirtySince > 400) write();
        if (target != null && !s.vehicles.list().contains(target)) { target = null; game.relayout(); }
        refreshMarks();
    }

    @Override public void onClose() {
        if (valueField != null) closeValueField(false);
        flush();
        s.vehicles.markVehicle = null;
        s.vehicles.marks.clear();
        super.onClose();
    }

    // ------------------------------------------------------------------ marks in the world
    private void refreshMarks() {
        Vehicles vs = s.vehicles;
        vs.marks.clear();
        VehicleModel m = vm();
        if (target == null || m == null) { vs.markVehicle = null; return; }
        vs.markVehicle = target;
        String pg = PAGES[page];
        // the origin (between the axles, on the ground) and the way forward
        vs.marks.add(new Vehicles.Mark(new float[]{-0.06f, 0, -0.06f}, new float[]{0.06f, 0.12f, 0.06f}, 1, 1, 1));
        vs.marks.add(new Vehicles.Mark(new float[]{-0.02f, 0, m.minZ - 0.4f}, new float[]{0.02f, 0.04f, 0}, 1, 0.25f, 0.25f));
        for (int i = 0; i < m.seats.size(); i++) {
            float[] p = m.seats.get(i);
            boolean sel = pg.equals("Seats") && i == seatIx, drv = i == m.driverSeat;
            float r = sel ? 1 : drv ? 1 : 0.2f, g = sel ? 0.9f : drv ? 0.6f : 1f, b = sel ? 0.1f : 0.2f;
            if (!pg.equals("Seats") && !pg.equals("Size")) { r *= 0.5f; g *= 0.5f; b *= 0.5f; }
            vs.marks.add(new Vehicles.Mark(new float[]{p[0] - 0.25f, p[1], p[2] - 0.25f}, new float[]{p[0] + 0.25f, p[1] + 0.05f, p[2] + 0.25f}, r, g, b));
            double fy = Math.toRadians(i < m.seatYaw.size() ? m.seatYaw.get(i) : 0);
            float nx = p[0] + (float) Math.sin(fy) * 0.45f, nz = p[2] - (float) Math.cos(fy) * 0.45f;      // a nub in front of the seat: the way it faces
            vs.marks.add(new Vehicles.Mark(new float[]{nx - 0.06f, p[1], nz - 0.06f}, new float[]{nx + 0.06f, p[1] + 0.3f, nz + 0.06f}, 1, 0.3f, 0.3f));
            float[] ey = i < m.eyes.size() ? m.eyes.get(i) : null;
            if (ey != null) vs.marks.add(new Vehicles.Mark(new float[]{ey[0] - 0.07f, ey[1] - 0.07f, ey[2] - 0.07f}, new float[]{ey[0] + 0.07f, ey[1] + 0.07f, ey[2] + 0.07f}, 1, 0.3f, 1));
            vs.marks.add(new Vehicles.Mark(new float[]{p[0] - 0.25f, p[1], p[2] + 0.2f}, new float[]{p[0] + 0.25f, p[1] + 0.55f, p[2] + 0.25f}, r, g, b));
        }
        for (int i = 0; i < m.slots.size(); i++) {
            float[] p = m.slots.get(i);
            boolean sel = pg.equals("Blocks") && i == blockIx;
            float k = pg.equals("Blocks") ? 1 : 0.5f;
            vs.marks.add(new Vehicles.Mark(new float[]{p[0] - 0.5f, p[1], p[2] - 0.5f}, new float[]{p[0] + 0.5f, p[1] + 1, p[2] + 0.5f}, sel ? 0.2f : 0.2f * k, sel ? 1f : 0.45f * k, sel ? 1f : 1f * k));
        }
        if (pg.equals("Terrain")) {     // how high a step it climbs, ahead of the front
            VehicleType t = type();
            vs.marks.add(new Vehicles.Mark(new float[]{m.minX, 0, m.minZ - 1}, new float[]{m.maxX, t.stepHeight, m.minZ - 0.2f}, 1, 0.6f, 0.1f));
        }
        if (pg.equals("Bullets")) {          // red boxes = parts that stop bullets, yellow = the one picked
            VehicleType t = type();
            for (int i = 0; i < m.parts.size(); i++) {
                VehicleModel.Part p = m.parts.get(i);
                boolean sel = i == bulletIx;
                if (!sel && !t.stopsBullets(p)) continue;
                float e = sel ? 0.03f : 0.01f;
                vs.marks.add(new Vehicles.Mark(new float[]{p.x0 - e, p.y0 - e, p.z0 - e}, new float[]{p.x1 + e, p.y1 + e, p.z1 + e}, sel ? 1 : 0.9f, sel ? 0.9f : 0.15f, sel ? 0.1f : 0.1f));
            }
        }
        if (pg.equals("Doors")) {
            VehicleModel.Part p = doorNow();
            if (p != null) {
                vs.marks.add(new Vehicles.Mark(new float[]{p.x0, p.y0, p.z0}, new float[]{p.x1, p.y1, p.z1}, 0.2f, 0.9f, 1));
                vs.marks.add(new Vehicles.Mark(new float[]{p.px - 0.05f, p.py - 0.05f, p.pz - 0.05f}, new float[]{p.px + 0.05f, p.py + 0.05f, p.pz + 0.05f}, 1, 0.5f, 0.1f));
                if (p.axis == 1) vs.marks.add(new Vehicles.Mark(new float[]{p.px - 0.015f, p.py - 0.5f, p.pz - 0.015f}, new float[]{p.px + 0.015f, p.py + 0.5f, p.pz + 0.015f}, 1, 0.5f, 0.1f));
                else vs.marks.add(new Vehicles.Mark(new float[]{p.px - 0.5f, p.py - 0.015f, p.pz - 0.015f}, new float[]{p.px + 0.5f, p.py + 0.015f, p.pz + 0.015f}, 1, 0.5f, 0.1f));
            }
        }
        if (pg.equals("Lights")) {
            List<VehicleType.Light> ls = type().effectiveLights();
            for (int i = 0; i < ls.size(); i++) {
                VehicleType.Light l = ls.get(i);
                boolean sel = i == lightIx;
                float k = sel ? 1 : 0.5f;
                vs.marks.add(new Vehicles.Mark(new float[]{l.a[0] - 0.07f, l.a[1] - 0.07f, l.a[2] - 0.07f}, new float[]{l.a[0] + 0.07f, l.a[1] + 0.07f, l.a[2] + 0.07f}, 1 * k, 0.9f * k, 0.1f * k));
                vs.marks.add(new Vehicles.Mark(new float[]{l.b[0] - l.width, l.b[1] - l.width, l.b[2] - 0.03f}, new float[]{l.b[0] + l.width, l.b[1] + l.width, l.b[2] + 0.03f}, 0.3f * k, 0.8f * k, 1 * k));
                for (int s2 = 1; s2 < 8; s2++) {
                    float f = s2 / 8f, px = l.a[0] + (l.b[0] - l.a[0]) * f, py = l.a[1] + (l.b[1] - l.a[1]) * f, pz = l.a[2] + (l.b[2] - l.a[2]) * f;
                    vs.marks.add(new Vehicles.Mark(new float[]{px - 0.025f, py - 0.025f, pz - 0.025f}, new float[]{px + 0.025f, py + 0.025f, pz + 0.025f}, 0.9f * k, 0.9f * k, 0.5f * k));
                }
            }
        }
        if (pg.equals("Size")) vs.marks.add(new Vehicles.Mark(new float[]{m.minX, 0, m.minZ}, new float[]{m.maxX, m.maxY, m.maxZ}, 0.8f, 0.8f, 0.8f));
    }

    // ------------------------------------------------------------------ input
    private Widget.Slider sliderAt(int mx, int my) {
        for (Widget.Slider sl : sliders) if (sl.contains(mx, my)) return sl;
        return null;
    }

    @Override public void scrolled(double amount) {
        int mx = game.mouseGuiX(), my = game.mouseGuiY();
        Widget.Slider sl = ctrl() ? sliderAt(mx, my) : null;
        if (sl != null) { sl.setValue(sl.value() + Math.signum(amount) * sl.step() * mult()); return; }
        int old = scroll;
        scroll = Math.max(0, Math.min(scroll - (int) Math.signum(amount), Math.max(0, totalRows - rows)));
        if (scroll != old) game.relayout();
    }

    @Override public void mouseClicked(int mx, int my, int button) {
        if (valueField != null && !valueField.contains(mx, my)) closeValueField(false);
        if (button == 0 && ctrl() && valueField == null) {
            Widget.Slider sl = sliderAt(mx, my);
            if (sl != null) { openValueField(sl); return; }
        }
        super.mouseClicked(mx, my, button);
        if (valueField != null) valueField.focused = true;
    }

    private void openValueField(Widget.Slider sl) {
        valueSlider = sl;
        valueField = new Widget.TextField(sl.x, sl.y, sl.w, sl.h);
        valueField.text = fmt((float) sl.value(), Math.min(sl.step(), 0.001f)).replaceAll("0+$", "").replaceAll("\\.$", "");
        valueField.focused = true;
        valueField.onEnter = () -> closeValueField(true);
        widgets.add(valueField);
    }

    private void closeValueField(boolean apply) {
        if (valueField == null) return;
        if (apply && valueSlider != null) {
            try { valueSlider.setValue(Double.parseDouble(valueField.text.trim().replace(',', '.'))); }
            catch (NumberFormatException e) { say("§cNot a number: " + valueField.text); }
        }
        widgets.remove(valueField);
        valueField = null;
        valueSlider = null;
    }

    @Override public void keyPressed(int key, int mods) {
        if (valueField != null) {
            if (key == GLFW_KEY_ESCAPE) { closeValueField(false); return; }
            if (key == GLFW_KEY_ENTER || key == GLFW_KEY_KP_ENTER) { closeValueField(true); return; }
            valueField.key(key, mods);
            return;
        }
        boolean c = (mods & GLFW_MOD_CONTROL) != 0, sh = (mods & GLFW_MOD_SHIFT) != 0;
        if (Keys.VEHICLE_EDITOR.matches(key)) { onClose(); return; }
        if (c && key == GLFW_KEY_Z) { if (sh) redo(); else undo(); return; }
        if (c && key == GLFW_KEY_Y) { redo(); return; }
        if (c && key == GLFW_KEY_S) { save(); return; }
        if (key == GLFW_KEY_TAB) { page = (page + (sh ? PAGES.length - 1 : 1)) % PAGES.length; scroll = 0; game.relayout(); return; }
        if (key == GLFW_KEY_PAGE_UP || key == GLFW_KEY_PAGE_DOWN) {
            int d = key == GLFW_KEY_PAGE_DOWN ? 1 : -1;
            VehicleModel m = vm();
            if (m != null && PAGES[page].equals("Seats")) seatIx = Math.floorMod(seatIx + d, Math.max(1, m.seats.size()));
            else if (m != null && PAGES[page].equals("Blocks") && !m.slots.isEmpty()) blockIx = Math.floorMod(blockIx + d, m.slots.size());
            game.relayout();
            return;
        }
        super.keyPressed(key, mods);
    }

    private boolean down(int key) { return glfwGetKey(game.window, key) == GLFW_PRESS; }
    private boolean shift() { return down(GLFW_KEY_LEFT_SHIFT) || down(GLFW_KEY_RIGHT_SHIFT); }
    private boolean ctrl() { return down(GLFW_KEY_LEFT_CONTROL) || down(GLFW_KEY_RIGHT_CONTROL); }
    private int mult() { return shift() ? 10 : 1; }
    private static double clamp(double v, float lo, float hi) { return Math.max(lo, Math.min(hi, v)); }

    private static String fmt(float v, float step) {
        int d = step >= 1 ? 0 : step >= 0.1f ? 1 : step >= 0.01f ? 2 : step >= 0.001f ? 3 : 4;
        return String.format(Locale.ROOT, "%." + d + "f", v);
    }

    // ------------------------------------------------------------------ drawing
    @Override public void render(GuiRenderer g, int mx, int my, float partial) {
        g.fill(0, 0, PW, height, 0xB0000000);
        VehicleType t = type();
        VehicleModel m = vm();
        g.text("Vehicle Editor", 4, 5, 0xFFFFFF, true);
        String hint = Keys.n(Keys.VEHICLE_EDITOR) + ": close";
        if (g.textWidth("Vehicle Editor") + g.textWidth(hint) + 12 <= PW) g.text(hint, PW - g.textWidth(hint) - 4, 5, 0x808080, false);
        if (t == null || m == null) {
            g.text("§7No vehicle near. Get into one, look at one", 6, 24, 0xFFFFFF, false);
            g.text("§7or stand next to it (16 blocks), or spawn", 6, 34, 0xFFFFFF, false);
            g.text("§7a kind from game/transport:", 6, 44, 0xFFFFFF, false);
            if (Vehicles.vehicleModels().isEmpty()) g.text("§cNo vehicles in game/transport yet", 6, 62, 0xFFFFFF, false);
        } else {
            if (totalRows > rows) g.text("§7wheel: scroll (" + (scroll + 1) + "-" + Math.min(totalRows, scroll + rows) + " of " + totalRows + ")", 6, height - 3 * (ROW + 2) - 14, 0xFFFFFF, false);
            List<String> help = new ArrayList<>();
            help.add("§f" + t.displayName + " §7#" + target.id + " (" + String.format(Locale.ROOT, "%.0f km/h, %.0f kg", t.maxSpeed * 72, t.weight) + ")");
            help.add("");
            help.addAll(Arrays.asList(help(t, m)));
            help.add("");
            help.add("§7Drag (Shift: fine), right click: default,");
            help.add("§7Ctrl+click: type, Ctrl+wheel: step.");
            help.add("§7Ctrl+Z / Ctrl+Y: undo / redo, Tab: page.");
            helpBox(g, help.toArray(new String[0]), 24);
        }
        super.render(g, mx, my, partial);
        if (statusTicks > 0) g.text(status, PW + 6, height - 14, 0xFFFFFF, true);
    }

    private String[] help(VehicleType t, VehicleModel m) {
        String f = t.settingsFile().getFileName().toString();
        String size = String.format(Locale.ROOT, "%.2f x %.2f x %.2f blocks (w x h x l)", m.maxX - m.minX, m.maxY, m.maxZ - m.minZ);
        List<String> l = new ArrayList<>();
        switch (PAGES[page]) {
            case "Speed" -> {
                l.add("Top speed, reverse, the pedals (km/h and");
                l.add("km/h per second), the wheel lock.");
                l.add(String.format(Locale.ROOT, "0-%.0f km/h in about %.1f s", t.maxSpeed * 72 * 0.9f, estimate0to(t)));
            }
            case "Seats" -> {
                l.add("Seats: the number is the seat's place in");
                l.add("the list. Green plate = a seat, orange = the");
                l.add("driver's (the one nearest the steering wheel),");
                l.add("yellow = the one picked. The numbers are the");
                l.add("middle of the sitting surface (car's own");
                l.add("coordinates: 0 between the axles on the");
                l.add("ground, x right, y up, z back). Camera: the");
                l.add("view point of whoever sits there (magenta");
                l.add("cube), set apart from the seat.");
                float[] p = seatNow();
                if (p != null) {
                    l.add("");
                    String door = seatIx < m.seatDoor.length && m.seatDoor[seatIx] >= 0 ? m.openables.get(m.seatDoor[seatIx]).name : "none";
                    l.add(String.format(Locale.ROOT, "Seat %d%s, door: %s", seatIx + 1, seatIx == m.driverSeat ? " (DRIVER)" : "", door));
                }
                l.add(m.seats.size() + " seats");
            }
            case "Blocks" -> {
                l.add("Block places: where blocks carried by the car");
                l.add("stand (right click a block on an empty place;");
                l.add("up to " + VehicleModel.MAX_SLOTS + "). Blue cube = a place, cyan = the one");
                l.add("picked; the numbers are the middle of the");
                l.add("block's bottom (car's own coordinates).");
                l.add("");
                l.add(m.slots.size() + " block places" + (t.blocksSet ? "" : " (from the model)"));
            }
            case "Size" -> {
                l.add("Size: scale, and width / height / length on");
                l.add("top. Seats and block places written in the");
                l.add("settings grow with the car.");
                l.add("");
                l.add(size);
            }
            case "Suspension" -> {
                l.add("Body springs: stiffness (how hard it pulls back),");
                l.add("damping (how fast it settles), how far the nose");
                l.add("dives / rises and the body leans, the ride");
                l.add("height over the wheels, how far it bounces.");
            }
            case "Terrain" -> {
                l.add("Off-road ability: the highest step the wheels");
                l.add("climb (orange box ahead of the car), and how");
                l.add("much speed a wading car keeps every tick");
                l.add("(1 = water does not slow it).");
                l.add("");
                l.add(String.format(Locale.ROOT, "Climbs steps of %.2f block%s", t.stepHeight, t.stepHeight >= 1.5 ? "s (a truck)" : ""));
            }
            case "Weight" -> {
                l.add("Weight: a heavy car is pushed slowly (Alt behind");
                l.add("it), hits harder, slides on in a drift and rolls");
                l.add("over less; a light one the other way.");
                l.add("");
                l.add(String.format(Locale.ROOT, "Push speed %.1f km/h%s", Vehicles.pushSpeedOf(t) * 72, t.weight > Vehicles.PUSH_MAX_WEIGHT ? " (too heavy to push)" : ""));
                l.add(String.format(Locale.ROOT, "Stability against rolling: %.2f", Vehicle.stability(m, t)));
            }
            case "Doors" -> {
                l.add("Doors, hood, trunk: pick a part (cyan box),");
                l.add("its opening angle (minus = the other way)");
                l.add("and where the hinge is (orange cube + line).");
                l.add("'Open it' tests it in the car.");
                VehicleModel.Part p = doorNow();
                if (p != null) { l.add(""); l.add(String.format(Locale.ROOT, "%d / %d: %s (%s)", doorIx + 1, m.openables.size(), p.name, p.axis == 1 ? "door" : "lid")); }
            }
            case "Lights" -> {
                l.add("Lights: the lamp (yellow cube) and where the");
                l.add("beam ends (blue frame, its size = the width).");
                l.add("Light along the line between them (dynamic");
                l.add("light): dim at the lamp, brighter further on,");
                l.add("stopped by solid blocks (glass, grass: no).");
                l.add("Key " + Keys.n(Keys.VEHICLE_LIGHTS) + " switches them in a car. Red cone");
                l.add("short behind = a tail light. NPC drivers use");
                l.add("them at night. Needs Dynamic Lights on.");
                l.add(t.effectiveLights().size() + " lights" + (t.lightsSet ? "" : " (made up from the model)"));
            }
            case "Bullets" -> {
                l.add("Bullets: click a part to switch it. 'passes' =");
                l.add("a bullet goes through it (sparks where it");
                l.add("enters) and hits whoever sits behind it;");
                l.add("'stops' = it stays in the part (sparks).");
                l.add("Red box = stops, yellow = the one clicked.");
                l.add("By default wheels stop, the rest passes.");
                int st = 0;
                for (VehicleModel.Part p : m.parts) if (t.stopsBullets(p)) st++;
                l.add(st + " of " + m.parts.size() + " parts stop bullets");
            }
            case "Surfaces" -> {
                l.add("Grip and top speed on each kind of ground");
                l.add("(x of the usual): the block under the car");
                l.add("decides. Low grip on ice / snow: it slides");
                l.add("when steering, brakes long, no tyre marks");
                l.add("on ice; sand slows it down.");
                l.add("Now on: " + derpcraft.vehicle.Surfaces.LABELS[target.surface]);
            }
            default -> {
                l.add("Handling: grip = how fast sideways speed dies");
                l.add("out; drift (Space + gas + steering): the tyres");
                l.add("let go; police turn (Space + a sharp turn at");
                l.add("high speed): the car swings round. Rollover:");
                l.add("sideways force that tips it (0 = never).");
                l.add(String.format(Locale.ROOT, "Rolls at %.3f for this car", t.rollover * Vehicle.stability(m, t)));
            }
        }
        l.add("");
        l.add("§7File: " + f);
        return l.toArray(new String[0]);
    }

    private static double estimate0to(VehicleType t) {
        // gas falls off near the top speed: the time to 90 % of it from the same formula as the physics
        float v = 0, goal = t.maxSpeed * 0.9f;
        int ticks = 0;
        while (v < goal && ticks < 6000) { v += t.accel * Math.max(0, 1 - v / t.maxSpeed); ticks++; }
        return ticks / 20.0;
    }

    private void helpBox(GuiRenderer g, String[] lines, int y) {
        int w = 0;
        for (String h : lines) w = Math.max(w, g.textWidth(h));
        g.fill(PW + 3, y - 3, PW + 9 + w, y + lines.length * 10 - 1, 0x90000000);
        for (String h : lines) { g.text(h, PW + 6, y, 0xD0D0D0, true); y += 10; }
    }
}
