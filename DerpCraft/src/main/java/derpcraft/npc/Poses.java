package derpcraft.npc;

import java.nio.file.*;
import java.util.*;

/**
 * The pose library: built-in gestures and poses made in the pose editor (saved in game/poses/). A pose turns the
 * parts of the player model (Blockbench / Minecraft biped: head, body, arms, legs), can lean the whole model, lift
 * or lower it, and has its own hit box (a sitting NPC is lower than a standing one).
 */
public final class Poses {
    private Poses() {}

    public static final int HEAD = 0, BODY = 1, RIGHT_ARM = 2, LEFT_ARM = 3, RIGHT_LEG = 4, LEFT_LEG = 5;

    public static final class Def {
        public String name;
        public boolean builtin;
        /** Degrees per part (x, y, z). Head is added to where the NPC looks; body bends at the waist. */
        public final float[][] rot = new float[6][3];
        /** Which parts the pose holds (others keep walking / idle animation). */
        public final boolean[] set = new boolean[6];
        /** Whole model: lean forward / back (x) and sideways (z) around the feet, degrees; lift in blocks. */
        public float tiltX, tiltZ, lift;
        /** Hit box in blocks. */
        public float width = 0.6f, height = 1.8f;

        public Def(String name) { this.name = name; }

        public Def part(int p, float x, float y, float z) { rot[p][0] = x; rot[p][1] = y; rot[p][2] = z; set[p] = true; return this; }

        public Def tilt(float x, float z, float lift) { tiltX = x; tiltZ = z; this.lift = lift; return this; }

        public Def box(float w, float h) { width = w; height = h; return this; }

        public Def copy(String newName) {
            Def d = new Def(newName);
            for (int i = 0; i < 6; i++) { d.rot[i] = rot[i].clone(); d.set[i] = set[i]; }
            d.tiltX = tiltX; d.tiltZ = tiltZ; d.lift = lift; d.width = width; d.height = height;
            return d;
        }
    }

    private static final Map<String, Def> POSES = new LinkedHashMap<>();
    /** Gesture keys while recording: Tab + 1..9. */
    public static final String[] SLOTS = new String[10];
    private static Path dir;

    static {
        add(new Def("Sit").part(RIGHT_ARM, -36, 0, 0).part(LEFT_ARM, -36, 0, 0).part(RIGHT_LEG, -81, 18, 4).part(LEFT_LEG, -81, -18, -4).tilt(0, 0, -0.625f).box(0.6f, 1.3f));
        add(new Def("Sit on ground").part(RIGHT_ARM, -25, 0, 8).part(LEFT_ARM, -25, 0, -8).part(RIGHT_LEG, -90, 8, 0).part(LEFT_LEG, -90, -8, 0).tilt(0, 0, -0.72f).box(0.6f, 1.05f));
        add(new Def("Kneel").part(RIGHT_LEG, -90, 0, 0).part(LEFT_LEG, 85, 0, 0).tilt(0, 0, -0.36f).box(0.6f, 1.45f));
        add(new Def("Kneel both").part(RIGHT_LEG, 90, 0, 0).part(LEFT_LEG, 90, 0, 0).tilt(0, 0, -0.68f).box(0.6f, 1.1f));
        add(new Def("Lie on back").part(RIGHT_ARM, 0, 0, 8).part(LEFT_ARM, 0, 0, -8).part(RIGHT_LEG, 0, 0, 2).part(LEFT_LEG, 0, 0, -2).tilt(-90, 0, 0.13f).box(1.2f, 0.4f));
        add(new Def("Lie face down").part(RIGHT_ARM, 0, 0, 8).part(LEFT_ARM, 0, 0, -8).tilt(90, 0, 0.13f).box(1.2f, 0.4f));
        add(new Def("Dead").part(HEAD, 0, 30, 0).part(RIGHT_ARM, 0, 0, 80).part(LEFT_ARM, 0, 0, -70).part(RIGHT_LEG, 0, 0, 12).part(LEFT_LEG, 0, 0, -6).tilt(-90, 0, 0.13f).box(1.4f, 0.4f));
        add(new Def("Arms crossed").part(RIGHT_ARM, -55, -38, 12).part(LEFT_ARM, -55, 38, -12));
        add(new Def("Hands on hips").part(RIGHT_ARM, 15, 0, 30).part(LEFT_ARM, 15, 0, -30));
        add(new Def("Point").part(RIGHT_ARM, -90, -6, 0));
        add(new Def("Hands up").part(RIGHT_ARM, -172, 0, -12).part(LEFT_ARM, -172, 0, 12));
        add(new Def("Salute").part(RIGHT_ARM, -128, -40, 0));
        add(new Def("Think").part(HEAD, 10, 0, 0).part(RIGHT_ARM, -118, -30, 0).part(LEFT_ARM, -40, 30, 0));
        add(new Def("Facepalm").part(HEAD, 25, 0, 0).part(RIGHT_ARM, -140, -22, 0));
        add(new Def("Bow").part(HEAD, 15, 0, 0).part(BODY, 45, 0, 0).part(RIGHT_ARM, 0, 0, 0).part(LEFT_ARM, 0, 0, 0).box(0.6f, 1.55f));
        add(new Def("Punch").part(BODY, 0, -25, 0).part(RIGHT_ARM, -90, -20, 0).part(LEFT_ARM, -60, 30, 0));
        add(new Def("Guard").part(RIGHT_ARM, -75, -30, 0).part(LEFT_ARM, -75, 30, 0));
        add(new Def("Carry").part(RIGHT_ARM, -55, 0, 0).part(LEFT_ARM, -55, 0, 0));
        add(new Def("Shrug").part(HEAD, 0, 0, 10).part(RIGHT_ARM, -20, 0, 40).part(LEFT_ARM, -20, 0, -40));
        add(new Def("Lean").part(BODY, 0, 0, 10).part(HEAD, 0, 0, -6).tilt(0, 4, 0));
        add(new Def("Scared").part(HEAD, -10, 0, 0).part(BODY, -12, 0, 0).part(RIGHT_ARM, -95, -25, 0).part(LEFT_ARM, -95, 25, 0));
        add(new Def("Aim").part(RIGHT_ARM, -90, -10, 0).part(LEFT_ARM, -90, 35, 0));
        String[] def = {null, "Arms crossed", "Point", "Hands up", "Salute", "Hands on hips", "Think", "Facepalm", "Bow", "Sit on ground"};
        System.arraycopy(def, 0, SLOTS, 0, 10);
    }

    private static void add(Def d) { d.builtin = true; POSES.put(d.name.toLowerCase(Locale.ROOT), d); }

    public static Def get(String name) { return name == null || name.isEmpty() ? null : POSES.get(name.toLowerCase(Locale.ROOT)); }

    public static List<String> names() { List<String> l = new ArrayList<>(); for (Def d : POSES.values()) l.add(d.name); return l; }

    public static List<Def> all() { return new ArrayList<>(POSES.values()); }

    /** Adds or replaces a user pose and saves it. */
    public static void put(Def d) {
        d.builtin = false;
        Def old = get(d.name);
        if (old != null && old.builtin) return;
        POSES.put(d.name.toLowerCase(Locale.ROOT), d);
        save(d);
    }

    public static void delete(String name) {
        Def d = get(name);
        if (d == null || d.builtin) return;
        POSES.remove(name.toLowerCase(Locale.ROOT));
        try { if (dir != null) Files.deleteIfExists(dir.resolve(file(name))); } catch (Exception ignored) {}
        for (int i = 0; i < 10; i++) if (name.equalsIgnoreCase(SLOTS[i])) SLOTS[i] = null;
        saveSlots();
    }

    private static String file(String name) { return name.replaceAll("[^A-Za-z0-9_ -]", "_") + ".txt"; }

    // ------------------------------------------------------------------ files (game/poses)
    public static void load(Path gameDir) {
        dir = gameDir.resolve("poses");
        try {
            Files.createDirectories(dir);
            try (var st = Files.list(dir)) {
                for (Path p : (Iterable<Path>) st::iterator) {
                    String fn = p.getFileName().toString();
                    if (!fn.endsWith(".txt") || fn.equals("slots.txt")) continue;
                    Properties pr = new Properties();
                    try (var r = Files.newBufferedReader(p)) { pr.load(r); }
                    Def d = new Def(pr.getProperty("name", fn.substring(0, fn.length() - 4)));
                    for (int i = 0; i < 6; i++) {
                        String v = pr.getProperty("part" + i);
                        if (v == null) continue;
                        String[] a = v.split(",");
                        d.part(i, f(a, 0), f(a, 1), f(a, 2));
                    }
                    String[] t = pr.getProperty("tilt", "0,0,0").split(",");
                    d.tilt(f(t, 0), f(t, 1), f(t, 2));
                    String[] b = pr.getProperty("box", "0.6,1.8").split(",");
                    d.box(f(b, 0), f(b, 1));
                    if (get(d.name) == null || !get(d.name).builtin) POSES.put(d.name.toLowerCase(Locale.ROOT), d);
                }
            }
            Path sl = dir.resolve("slots.txt");
            if (Files.exists(sl)) {
                Properties pr = new Properties();
                try (var r = Files.newBufferedReader(sl)) { pr.load(r); }
                for (int i = 1; i < 10; i++) { String v = pr.getProperty(String.valueOf(i)); if (v != null) SLOTS[i] = v.isEmpty() ? null : v; }
            }
        } catch (Exception e) { System.err.println("Cannot load poses: " + e); }
    }

    private static float f(String[] a, int i) { try { return Float.parseFloat(a[i].trim()); } catch (Exception e) { return 0; } }

    private static void save(Def d) {
        if (dir == null) return;
        Properties pr = new Properties();
        pr.setProperty("name", d.name);
        for (int i = 0; i < 6; i++) if (d.set[i]) pr.setProperty("part" + i, d.rot[i][0] + "," + d.rot[i][1] + "," + d.rot[i][2]);
        pr.setProperty("tilt", d.tiltX + "," + d.tiltZ + "," + d.lift);
        pr.setProperty("box", d.width + "," + d.height);
        try (var w = Files.newBufferedWriter(dir.resolve(file(d.name)))) { pr.store(w, "DerpCraft pose"); }
        catch (Exception e) { System.err.println("Cannot save pose: " + e); }
    }

    public static void saveSlots() {
        if (dir == null) return;
        Properties pr = new Properties();
        for (int i = 1; i < 10; i++) pr.setProperty(String.valueOf(i), SLOTS[i] == null ? "" : SLOTS[i]);
        try (var w = Files.newBufferedWriter(dir.resolve("slots.txt"))) { pr.store(w, "Gesture keys: Tab + number while recording"); }
        catch (Exception e) { System.err.println("Cannot save gesture keys: " + e); }
    }
}
