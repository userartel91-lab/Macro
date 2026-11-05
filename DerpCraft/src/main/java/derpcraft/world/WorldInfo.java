package derpcraft.world;

import java.io.*;
import java.nio.file.*;
import java.util.Properties;

/** World metadata stored in level.properties inside the world folder. */
public final class WorldInfo {
    public String name = "New World";
    public long seed;
    public String generator = "default"; // "default" or "flat"
    public String flatLayers = "bedrock,2*dirt,grass";
    public double px, py = -1, pz;
    public float yaw, pitch;
    public long time = 1000;
    public boolean daylightCycle = true;
    public String gameMode = "creative";
    public boolean flying;
    public float flySpeed = 1f;
    public String hotbar = "";
    public long lastPlayed;
    public String inventory = "";
    public String enderChest = "";
    public float health = 20, saturation = 5;
    public int food = 20, xpLevel;
    public float xpProgress;
    /** Respawn point (bed or world spawn); spawnY < 0 = not set yet. */
    public int spawnX, spawnY = -1, spawnZ;
    public final Path dir;

    public WorldInfo(Path dir) { this.dir = dir; }

    public static WorldInfo load(Path dir) throws IOException {
        WorldInfo w = new WorldInfo(dir);
        Properties p = new Properties();
        try (Reader r = Files.newBufferedReader(dir.resolve("level.properties"))) { p.load(r); }
        w.name = p.getProperty("name", dir.getFileName().toString());
        w.seed = Long.parseLong(p.getProperty("seed", "0"));
        w.generator = p.getProperty("generator", "default");
        w.flatLayers = p.getProperty("flatLayers", w.flatLayers);
        w.px = Double.parseDouble(p.getProperty("px", "0"));
        w.py = Double.parseDouble(p.getProperty("py", "-1"));
        w.pz = Double.parseDouble(p.getProperty("pz", "0"));
        w.yaw = Float.parseFloat(p.getProperty("yaw", "0"));
        w.pitch = Float.parseFloat(p.getProperty("pitch", "0"));
        w.time = Long.parseLong(p.getProperty("time", "1000"));
        w.daylightCycle = Boolean.parseBoolean(p.getProperty("daylightCycle", "true"));
        w.gameMode = p.getProperty("gameMode", "creative");
        w.flying = Boolean.parseBoolean(p.getProperty("flying", "false"));
        w.flySpeed = Float.parseFloat(p.getProperty("flySpeed", "1"));
        w.hotbar = p.getProperty("hotbar", "");
        w.lastPlayed = Long.parseLong(p.getProperty("lastPlayed", "0"));
        w.inventory = p.getProperty("inventory", "");
        w.enderChest = p.getProperty("enderChest", "");
        w.health = Float.parseFloat(p.getProperty("health", "20"));
        w.saturation = Float.parseFloat(p.getProperty("saturation", "5"));
        w.food = Integer.parseInt(p.getProperty("food", "20"));
        w.xpLevel = Integer.parseInt(p.getProperty("xpLevel", "0"));
        w.xpProgress = Float.parseFloat(p.getProperty("xpProgress", "0"));
        w.spawnX = Integer.parseInt(p.getProperty("spawnX", "0"));
        w.spawnY = Integer.parseInt(p.getProperty("spawnY", "-1"));
        w.spawnZ = Integer.parseInt(p.getProperty("spawnZ", "0"));
        return w;
    }

    public void save() throws IOException {
        Files.createDirectories(dir);
        Properties p = new Properties();
        p.setProperty("name", name);
        p.setProperty("seed", Long.toString(seed));
        p.setProperty("generator", generator);
        p.setProperty("flatLayers", flatLayers);
        p.setProperty("px", Double.toString(px));
        p.setProperty("py", Double.toString(py));
        p.setProperty("pz", Double.toString(pz));
        p.setProperty("yaw", Float.toString(yaw));
        p.setProperty("pitch", Float.toString(pitch));
        p.setProperty("time", Long.toString(time));
        p.setProperty("daylightCycle", Boolean.toString(daylightCycle));
        p.setProperty("gameMode", gameMode);
        p.setProperty("flying", Boolean.toString(flying));
        p.setProperty("flySpeed", Float.toString(flySpeed));
        p.setProperty("hotbar", hotbar);
        p.setProperty("lastPlayed", Long.toString(System.currentTimeMillis()));
        p.setProperty("inventory", inventory);
        p.setProperty("enderChest", enderChest);
        p.setProperty("health", Float.toString(health));
        p.setProperty("saturation", Float.toString(saturation));
        p.setProperty("food", Integer.toString(food));
        p.setProperty("xpLevel", Integer.toString(xpLevel));
        p.setProperty("xpProgress", Float.toString(xpProgress));
        p.setProperty("spawnX", Integer.toString(spawnX));
        p.setProperty("spawnY", Integer.toString(spawnY));
        p.setProperty("spawnZ", Integer.toString(spawnZ));
        Path tmp = dir.resolve("level.properties.tmp");
        try (Writer w = Files.newBufferedWriter(tmp)) { p.store(w, "DerpCraft world"); }
        Files.move(tmp, dir.resolve("level.properties"), StandardCopyOption.REPLACE_EXISTING);
    }
}
