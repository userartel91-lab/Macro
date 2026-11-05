package derpcraft.model;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.*;
import java.util.*;
import java.util.function.Function;

/**
 * The 3D model library: every .json / .bbmodel (Blockbench) and .obj (with .mtl) file under game/3d, loaded on
 * first use. Placement settings of each model are kept in game/3d/models.txt.
 */
public final class Models {
    private Models() {}

    private static Path dir;
    private static final Map<String, Model3D> LOADED = new HashMap<>();
    private static final Map<String, ModelSettings> SETTINGS = new HashMap<>();
    private static List<String> names = new ArrayList<>();
    /** Images from the resource pack ("block/stone" style texture names in Java block models). */
    public static Function<String, BufferedImage> vanillaImages = p -> null;
    private static int version;

    /** Changes whenever models are reloaded or their placement changes (cached collision boxes are rebuilt). */
    public static int version() { return version; }

    /** Call after changing a model's settings. */
    public static void touch() { version++; }

    public static void init(Path gameDir) {
        dir = gameDir.resolve("3d");
        boolean fresh = !Files.exists(dir);
        try { Files.createDirectories(dir); } catch (IOException ignored) {}
        if (fresh) copySamples();
        else copyNewSamples();
        loadSettings();
        rescan();
    }

    public static Path dir() { return dir; }

    private static final String[] SAMPLES = {"table.json", "table_wood.png", "cap.json", "cap.png", "watch.json", "watch.png", "lamp.bbmodel", "barrel.obj", "barrel.mtl", "lid.png",
            "security_camera.bbmodel", "monitor.bbmodel"};
    /** Examples added in later versions: copied into an existing game/3d/examples when missing. */
    private static final String[] NEW_SAMPLES = {"security_camera.bbmodel", "monitor.bbmodel"};

    private static void copyNewSamples() {
        Path ex = dir.resolve("examples");
        for (String n : NEW_SAMPLES) {
            Path to = ex.resolve(n);
            if (Files.exists(to)) continue;
            try (var in = Models.class.getResourceAsStream("/samples3d/" + n)) {
                if (in == null) continue;
                Files.createDirectories(ex);
                Files.copy(in, to);
            } catch (IOException e) { System.err.println("Cannot copy example model " + n + ": " + e); }
        }
    }

    /** The first time: a few example models in game/3d/examples (Blockbench json + png, .bbmodel, .obj + .mtl). */
    private static void copySamples() {
        Path ex = dir.resolve("examples");
        try {
            Files.createDirectories(ex);
            for (String n : SAMPLES) try (var in = Models.class.getResourceAsStream("/samples3d/" + n)) {
                if (in != null) Files.copy(in, ex.resolve(n), StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) { System.err.println("Cannot copy example models: " + e); }
    }

    /** Looks for model files again (new files, deleted files). Loaded models are reloaded on next use. */
    public static void rescan() {
        List<String> l = new ArrayList<>();
        if (dir != null && Files.isDirectory(dir)) {
            try (var st = Files.walk(dir, 8)) {
                st.filter(Files::isRegularFile).forEach(p -> {
                    String n = p.getFileName().toString().toLowerCase(Locale.ROOT);
                    if (n.endsWith(".json") || n.endsWith(".bbmodel") || n.endsWith(".obj")) l.add(dir.relativize(p).toString().replace('\\', '/'));
                });
            } catch (IOException ignored) {}
        }
        l.sort(String.CASE_INSENSITIVE_ORDER);
        names = l;
        for (Model3D m : LOADED.values()) m.dispose();
        LOADED.clear();
        version++;
    }

    public static List<String> names() { return Collections.unmodifiableList(names); }

    public static boolean exists(String name) { return name != null && names.contains(name); }

    /** The model (loaded on first use), or null if there is no such file. */
    public static Model3D get(String name) {
        if (name == null || name.isEmpty() || dir == null) return null;
        Model3D m = LOADED.get(name);
        if (m != null) return m;
        Path p = dir.resolve(name);
        if (!Files.isRegularFile(p)) return null;
        m = load(name, p);
        LOADED.put(name, m);
        return m;
    }

    /** Loads any model file (.bbmodel, Blockbench .json, .obj) outside game/3d (vehicles in game/transport); not cached. */
    public static Model3D loadFile(String name, Path p) { return load(name, p); }

    private static Model3D load(String name, Path p) {
        String n = name.toLowerCase(Locale.ROOT);
        try {
            Model3D m = n.endsWith(".obj") ? ObjLoader.load(name, p) : BlockbenchLoader.load(name, p);
            if (m.empty() && m.error == null) m.error = "No faces in this file";
            return m;
        } catch (Exception e) {
            Model3D m = new Model3D(name);
            m.error = e.getClass().getSimpleName() + ": " + e.getMessage();
            return m;
        }
    }

    // ------------------------------------------------------------------ settings (game/3d/models.txt)
    public static ModelSettings settings(String name) {
        return SETTINGS.computeIfAbsent(name == null ? "" : name, k -> new ModelSettings());
    }

    public static void saveSettings() {
        version++;
        if (dir == null) return;
        Properties p = new Properties();
        for (var e : SETTINGS.entrySet()) if (!e.getKey().isEmpty()) p.setProperty(e.getKey(), e.getValue().encode());
        try (Writer w = Files.newBufferedWriter(dir.resolve("models.txt"))) {
            p.store(w, "3D Hub: anchor x,y,z (auto = bottom centre), size, size x,y,z, turn x,y,z");
        } catch (IOException e) { System.err.println("Cannot save models.txt: " + e); }
    }

    private static void loadSettings() {
        SETTINGS.clear();
        Path f = dir.resolve("models.txt");
        if (!Files.exists(f)) return;
        Properties p = new Properties();
        try (Reader r = Files.newBufferedReader(f)) { p.load(r); } catch (IOException e) { return; }
        for (String k : p.stringPropertyNames()) SETTINGS.put(k, ModelSettings.decode(p.getProperty(k)));
    }

    // ------------------------------------------------------------------ helpers for loaders
    /** A grey / magenta checker for missing textures. */
    static BufferedImage missing() {
        BufferedImage img = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 16; y++) for (int x = 0; x < 16; x++) img.setRGB(x, y, ((x / 8 + y / 8) & 1) == 0 ? 0xFFF800F8 : 0xFF202020);
        return img;
    }

    /** Reads an image file, or null. */
    static BufferedImage image(Path p) {
        try { if (Files.isRegularFile(p)) return javax.imageio.ImageIO.read(p.toFile()); } catch (IOException ignored) {}
        return null;
    }
}
