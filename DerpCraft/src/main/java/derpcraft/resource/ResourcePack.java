package derpcraft.resource;


import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.zip.*;

/** A Minecraft-style resource pack: a folder or a .zip/.jar containing assets/... */
public abstract class ResourcePack implements Closeable {
    public final String id;

    protected ResourcePack(String id) { this.id = id; }

    public abstract InputStream open(String path) throws IOException;
    public abstract boolean has(String path);
    @Override public void close() {}

    public BufferedImage image(String path) {
        if (!has(path)) return null;
        try (InputStream in = open(path)) { return ImageIO.read(in); }
        catch (Exception e) { System.err.println("Bad image " + path + " in " + id + ": " + e); return null; }
    }

    public String text(String path) {
        if (!has(path)) return null;
        try (InputStream in = open(path)) { return new String(in.readAllBytes(), StandardCharsets.UTF_8); }
        catch (IOException e) { return null; }
    }

    public static ResourcePack open(Path p) throws IOException {
        if (Files.isDirectory(p)) return new Folder(p);
        return new Zip(p);
    }

    public static final class Folder extends ResourcePack {
        private final Path root;
        public Folder(Path root) { super(root.getFileName().toString()); this.root = root; }
        @Override public InputStream open(String path) throws IOException { return Files.newInputStream(root.resolve(path)); }
        @Override public boolean has(String path) { return Files.isRegularFile(root.resolve(path)); }
    }

    public static final class Zip extends ResourcePack {
        private final ZipFile zip;
        private final String prefix;

        public Zip(Path file) throws IOException {
            super(file.getFileName().toString());
            zip = new ZipFile(file.toFile());
            // some packs are zipped with an extra top-level folder
            String pre = "";
            if (zip.getEntry("assets/") == null && zip.getEntry("pack.mcmeta") == null) {
                var en = zip.entries();
                while (en.hasMoreElements()) {
                    String n = en.nextElement().getName();
                    int k = n.indexOf("/assets/");
                    if (k > 0) { pre = n.substring(0, k + 1); break; }
                }
            }
            prefix = pre;
        }
        @Override public InputStream open(String path) throws IOException {
            ZipEntry e = zip.getEntry(prefix + path);
            if (e == null) throw new FileNotFoundException(path);
            return zip.getInputStream(e);
        }
        @Override public boolean has(String path) { return zip.getEntry(prefix + path) != null; }
        @Override public void close() { try { zip.close(); } catch (IOException ignored) {} }
    }
}
