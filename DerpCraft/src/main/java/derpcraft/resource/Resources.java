package derpcraft.resource;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/**
 * Stack of active resource packs (highest priority first). Texture names use Minecraft 1.12.2 paths:
 * "stone" -> assets/minecraft/textures/blocks/stone.png, "mymod:ruby" -> assets/mymod/textures/blocks/ruby.png.
 */
public final class Resources {
    private final List<ResourcePack> packs = new ArrayList<>();

    public void setPacks(List<ResourcePack> list) {
        for (ResourcePack p : packs) if (!list.contains(p)) p.close();
        packs.clear();
        packs.addAll(list);
    }

    public BufferedImage image(String path) {
        for (ResourcePack p : packs) {
            BufferedImage img = p.image(path);
            if (img != null) return img;
        }
        return null;
    }

    public String text(String path) {
        for (ResourcePack p : packs) {
            String t = p.text(path);
            if (t != null) return t;
        }
        return null;
    }

    /** Pack that provides a path (so .mcmeta files come from the same pack as the image). */
    public ResourcePack provider(String path) {
        for (ResourcePack p : packs) if (p.has(path)) return p;
        return null;
    }

    public static String blockTexturePath(String name) {
        String ns = "minecraft", n = name;
        int c = name.indexOf(':');
        if (c >= 0) { ns = name.substring(0, c); n = name.substring(c + 1); }
        if (n.startsWith("items/")) return "assets/" + ns + "/textures/" + n + ".png";
        return "assets/" + ns + "/textures/blocks/" + n + ".png";
    }

    /** Lists the packs available in a folder (zips, jars and folders containing pack.mcmeta or assets). */
    public static List<Path> listPacks(Path dir) {
        List<Path> out = new ArrayList<>();
        try {
            Files.createDirectories(dir);
            try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir)) {
                for (Path p : ds) {
                    String n = p.getFileName().toString().toLowerCase();
                    if (Files.isDirectory(p) ? (Files.exists(p.resolve("pack.mcmeta")) || Files.isDirectory(p.resolve("assets")))
                            : (n.endsWith(".zip") || n.endsWith(".jar"))) out.add(p);
                }
            }
        } catch (IOException ignored) {}
        out.sort(Comparator.comparing(p -> p.getFileName().toString().toLowerCase()));
        // Offer the player's own Minecraft 1.12.2 installation as a pack (exact vanilla look)
        String appdata = System.getenv("APPDATA");
        if (appdata != null) {
            Path jar = Paths.get(appdata, ".minecraft", "versions", "1.12.2", "1.12.2.jar");
            if (Files.isRegularFile(jar)) out.add(0, jar);
        }
        return out;
    }
}
