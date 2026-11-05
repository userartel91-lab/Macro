package derpcraft.npc;

import derpcraft.render.Assets;
import derpcraft.render.Texture2D;
import derpcraft.resource.DefaultImages;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.util.*;

/**
 * NPC skins: "Steve" (the player skin of the resource pack), built-in outfits, and any PNG skin dropped into
 * game/skins/ (64x64 or old 64x32 Minecraft skins; slim "Alex" arms are detected automatically).
 */
public final class Skins {
    public record Skin(String name, Texture2D texture, boolean slim) {}

    /** Built-in outfits: name, skin tone, hair, shirt, trousers, long sleeves, shoes. */
    private static final Object[][] BUILTIN = {
            {"Alex", 0xFFE8B896, 0xFFD06A1E, 0xFF4E9A3C, 0xFF6B4A2E, false, 0xFF4A4A4A},
            {"Suit", 0xFFC89878, 0xFF1C1C1C, 0xFF26262C, 0xFF26262C, true, 0xFF101010},
            {"Worker", 0xFFB4846D, 0xFF5A3A1E, 0xFFE07A1E, 0xFF3E4A6A, true, 0xFF3A2A1A},
            {"Doctor", 0xFFE0B090, 0xFF7A7A7A, 0xFFF2F2F2, 0xFF8AA0B8, true, 0xFFE0E0E0},
            {"Police", 0xFF9A6A50, 0xFF1A1A1A, 0xFF22305A, 0xFF1C2440, true, 0xFF0E0E0E},
            {"Red Hoodie", 0xFFD8A888, 0xFF3A2410, 0xFFB02828, 0xFF3A3A40, true, 0xFFE8E8E8},
            {"Farmer", 0xFFC09070, 0xFFC8A040, 0xFF8AA04A, 0xFF4A6AA0, false, 0xFF5A3A1A},
            {"Knight", 0xFFB4846D, 0xFF3A2A1A, 0xFFA8A8B0, 0xFF808088, true, 0xFF606068},
            {"Villain", 0xFFB0B8A8, 0xFF0A0A0A, 0xFF3A1050, 0xFF14141A, true, 0xFF0A0A0A},
            {"Kid", 0xFFF0C0A0, 0xFFE0C060, 0xFFF0D040, 0xFF4060C0, false, 0xFFC03030},
    };

    private final Assets assets;
    private final Path dir;
    private final Map<String, Skin> skins = new LinkedHashMap<>();
    private final Map<String, BufferedImage> images = new HashMap<>();
    private final Map<String, Texture2D> faces = new HashMap<>();
    private int assetsVersion = -1;
    private long dirStamp = -1;

    public Skins(Assets assets, Path dir) { this.assets = assets; this.dir = dir; }

    /** All skin names (Steve first). */
    public List<String> names() { refresh(true); return new ArrayList<>(skins.keySet()); }

    /** The skin by name, or Steve. */
    public Skin get(String name) {
        refresh(false);
        Skin s = name == null ? null : skins.get(name);
        return s != null ? s : skins.get("Steve");
    }

    /** Reloads when the resource pack changed, or (checkFolder) the skins folder changed. Cheap without checkFolder. */
    public void refresh(boolean checkFolder) {
        if (!checkFolder && assetsVersion == assets.version && !skins.isEmpty()) return;
        long stamp = checkFolder || dirStamp == -1 ? folderStamp() : dirStamp;
        if (assetsVersion == assets.version && stamp == dirStamp && !skins.isEmpty()) return;
        for (Skin s : skins.values()) if (s.texture() != assets.steve && s.texture() != null) s.texture().delete();
        for (Texture2D t : faces.values()) t.delete();
        faces.clear();
        images.clear();
        skins.clear();
        assetsVersion = assets.version;
        dirStamp = stamp;
        skins.put("Steve", new Skin("Steve", assets.steve, false));
        BufferedImage steveImg = assets.image("assets/minecraft/textures/entity/steve.png");
        if (steveImg != null) images.put("Steve", normalize(steveImg));
        for (Object[] b : BUILTIN) {
            BufferedImage img = DefaultImages.person((int) b[1], (int) b[2], (int) b[3], (int) b[4], (boolean) b[5], (int) b[6]);
            skins.put((String) b[0], new Skin((String) b[0], new Texture2D(img, false, false), false));
            images.put((String) b[0], img);
        }
        try (var st = Files.list(dir)) {
            st.filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".png")).sorted().forEach(p -> {
                try {
                    BufferedImage img = ImageIO.read(p.toFile());
                    if (img == null || img.getWidth() < 64 || img.getHeight() < 32) return;
                    img = normalize(img);
                    String n = p.getFileName().toString();
                    n = n.substring(0, n.length() - 4);
                    skins.put(n, new Skin(n, new Texture2D(img, false, false), isSlim(img)));
                    images.put(n, img);
                } catch (Exception e) { System.err.println("Bad skin " + p + ": " + e.getMessage()); }
            });
        } catch (Exception ignored) {}
    }

    /** The skin's texture with a face (see {@link Emotions}); the plain skin for 0. */
    public Texture2D face(String name, int emotion) {
        Skin s = get(name);
        if (emotion <= 0) return s.texture();
        String key = s.name() + "#" + emotion;
        Texture2D t = faces.get(key);
        if (t != null) return t;
        BufferedImage img = images.get(s.name());
        if (img == null) return s.texture();
        t = new Texture2D(Emotions.apply(img, emotion), false, false);
        faces.put(key, t);
        return t;
    }

    private long folderStamp() {
        try {
            Files.createDirectories(dir);
            long h = Files.getLastModifiedTime(dir).toMillis();
            try (var st = Files.list(dir)) { for (Path p : (Iterable<Path>) st::iterator) h = h * 31 + Files.getLastModifiedTime(p).toMillis() + p.hashCode(); }
            return h;
        } catch (Exception e) { return 0; }
    }

    /** Scales HD skins down to 64 px wide and converts old 64x32 skins to the 64x64 layout. */
    static BufferedImage normalize(BufferedImage src) {
        BufferedImage img = src;
        if (img.getWidth() != 64) {
            int h = img.getHeight() * 64 / img.getWidth();
            BufferedImage s = new BufferedImage(64, h, BufferedImage.TYPE_INT_ARGB);
            var g = s.createGraphics();
            g.drawImage(img, 0, 0, 64, h, null);
            g.dispose();
            img = s;
        }
        BufferedImage out = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
        var g = out.createGraphics();
        g.drawImage(img, 0, 0, null);
        g.dispose();
        if (img.getHeight() == 32) {
            // legacy skin: mirror the right leg and arm onto the left ones
            copyLimb(out, 0, 16, 16, 48);
            copyLimb(out, 40, 16, 32, 48);
        }
        return out;
    }

    private static void copyLimb(BufferedImage img, int su, int sv, int du, int dv) {
        // 4x12x4 box: top/bottom row (4 px high) and the sides (12 px high), mirrored horizontally
        int[][] faces = {{4, 0, 4, 4}, {8, 0, 4, 4}, {0, 4, 4, 12}, {4, 4, 4, 12}, {8, 4, 4, 12}, {12, 4, 4, 12}};
        int[] dst = {4, 8, 8, 4, 0, 12};   // mirrored: inner/outer sides swap
        for (int f = 0; f < faces.length; f++) {
            int[] fc = faces[f];
            for (int y = 0; y < fc[3]; y++)
                for (int x = 0; x < fc[2]; x++) {
                    int c = img.getRGB(su + fc[0] + x, sv + fc[1] + y);
                    img.setRGB(du + dst[f] + (fc[2] - 1 - x), dv + fc[1] + y, c);
                }
        }
    }

    /** Alex-style skins leave the 4th column of the arm transparent. */
    static boolean isSlim(BufferedImage img) {
        return (img.getRGB(54, 20) >>> 24) == 0 && (img.getRGB(54, 31) >>> 24) == 0;
    }
}
