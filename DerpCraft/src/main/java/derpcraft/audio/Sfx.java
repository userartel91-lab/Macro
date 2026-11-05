package derpcraft.audio;

import derpcraft.block.Block;
import derpcraft.game.Session;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Stream;

/**
 * The original Minecraft sounds: the "sounds" folder of the 1.12 assets goes to {@code game/sounds/} as it is
 * (game/sounds/dig/stone1.ogg, game/sounds/mob/zombie/say1.ogg, game/sounds/random/explode1.ogg...).
 * A sound is named by its path inside the folder without the extension and the number: "dig/stone", "mob/zombie/say".
 * A random one of the numbered variants (stone1, stone2...) or the plain file ("stone.ogg") plays each time. Missing
 * files are silent. The folder is read again every 3 seconds, so files can be added while the game runs.
 */
public final class Sfx {
    private Sfx() {}

    private static Path root;
    private static final Map<String, List<Path>> CACHE = new HashMap<>();
    private static long cacheAt;
    private static final Random RND = new Random();

    // ------------------------------------------------------------------ volume by category (Options > Music & Sounds)
    /** Categories as in the original: master, music, jukebox / note blocks, weather, blocks, hostile and friendly creatures, players, ambient. */
    public static final String[] CATS = {"master", "music", "record", "weather", "block", "hostile", "neutral", "player", "ambient"};
    public static final String[] LABELS = {"Master Volume", "Music", "Jukebox/Noteblocks", "Weather", "Blocks", "Hostile Creatures", "Friendly Creatures", "Players", "Ambient/Environment"};
    public static final int MASTER = 0, MUSIC = 1, RECORD = 2, WEATHER = 3, BLOCK = 4, HOSTILE = 5, NEUTRAL = 6, PLAYER = 7, AMBIENT = 8;
    /** Volume of every category, 0..1 (the master one goes to Audio.master). */
    public static final float[] VOL = {1, 1, 1, 1, 1, 1, 1, 1, 1};

    public static void setVolume(int cat, float v) {
        VOL[cat] = Float.isFinite(v) ? Math.max(0, Math.min(1, v)) : 1;
        if (cat == MASTER) Audio.master = VOL[MASTER];
    }

    private static final Set<String> HOSTILE_MOBS = Set.of("zombie", "skeleton", "creeper", "spider", "endermen", "blaze", "ghast", "witch", "slime",
            "silverfish", "husk", "stray", "wither", "wither_skeleton", "guardian", "vex", "magmacube", "evocation_illager", "vindication_illager",
            "illusion_illager", "zombie_villager", "enderdragon", "shulker");

    /** The category of a sound by its name (master only = the interface and everything unsorted). */
    public static int category(String n) {
        if (n.startsWith("music/")) return MUSIC;
        if (n.startsWith("records/") || n.startsWith("note/")) return RECORD;
        if (n.startsWith("ambient/weather")) return WEATHER;
        if (n.startsWith("ambient/") || n.startsWith("portal/") || n.startsWith("fire/") || n.equals("liquid/lava") || n.equals("liquid/lavapop") || n.equals("liquid/water")) return AMBIENT;
        if (n.startsWith("mob/")) {
            String f = n.substring(4);
            int cut = f.indexOf('/');
            return HOSTILE_MOBS.contains(cut < 0 ? f : f.substring(0, cut)) ? HOSTILE : NEUTRAL;
        }
        if (n.startsWith("dig/") || n.startsWith("block/") || n.startsWith("tile/") || n.equals("random/glass") || n.equals("random/fuse")
                || n.equals("random/explode")) return BLOCK;
        if (n.startsWith("step/") || n.startsWith("damage/") || n.startsWith("entity/player/") || n.startsWith("item/") || n.startsWith("liquid/")
                || n.equals("random/eat") || n.equals("random/burp") || n.equals("random/pop") || n.equals("random/break") || n.equals("random/bow")) return PLAYER;
        if (n.startsWith("entity/")) return NEUTRAL;
        return MASTER;
    }

    private static float gain(String name) {
        int c = category(name);
        return c == MASTER ? 1 : VOL[c];
    }

    public static void init(Path gameDir) {
        root = gameDir.resolve("sounds");
        try { Files.createDirectories(root); } catch (Exception ignored) {}
    }

    public static Path dir() { return root; }

    /** Every variant of a sound ("dig/stone" -> dig/stone.ogg, stone1.ogg, stone2.ogg...), empty when there is none. */
    public static synchronized List<Path> variants(String name) {
        if (root == null) return List.of();
        long now = System.currentTimeMillis();
        if (now - cacheAt > 3000) { CACHE.clear(); cacheAt = now; }
        List<Path> l = CACHE.get(name);
        if (l != null) return l;
        l = new ArrayList<>();
        int cut = name.lastIndexOf('/');
        Path dir = cut < 0 ? root : root.resolve(name.substring(0, cut));
        String base = name.substring(cut + 1);
        if (Files.isDirectory(dir)) {
            try (Stream<Path> st = Files.list(dir)) {
                for (Path p : (Iterable<Path>) st.sorted()::iterator) {
                    String f = p.getFileName().toString().toLowerCase(Locale.ROOT);
                    int dot = f.lastIndexOf('.');
                    if (dot < 0 || !(f.endsWith(".ogg") || f.endsWith(".wav")) || !Files.isRegularFile(p)) continue;
                    String stem = f.substring(0, dot);
                    if (stem.startsWith(base) && stem.substring(base.length()).chars().allMatch(Character::isDigit)) l.add(p);
                }
            } catch (Exception ignored) {}
        }
        CACHE.put(name, l);
        return l;
    }

    public static boolean has(String name) { return !variants(name).isEmpty(); }

    /** The first of these names that has files (null = none): "hurt", "hit", "say" for a mob and so on. */
    public static String first(String... names) {
        for (String n : names) if (has(n)) return n;
        return null;
    }

    /** Plays a sound without a place (menus, the player's own). Pitch varies a little. */
    public static boolean play(String name, float volume, float pitch) {
        List<Path> l = variants(name);
        if (l.isEmpty()) return false;
        float gv = volume * gain(name);
        if (gv <= 0) return true;
        Audio.play(Audio.load(l.get(RND.nextInt(l.size()))), false, gv, pitch, 0);
        return true;
    }

    /** A sound at a place: quieter with distance (silent past 48 blocks), panned left / right from the player's view. */
    public static boolean at(Session s, String name, double x, double y, double z, float volume, float pitch) {
        if (name == null || s == null) return false;
        List<Path> l = variants(name);
        if (l.isEmpty()) return false;
        var p = s.player;
        double dx = x - p.x, dy = y - (p.y + p.eyeHeight), dz = z - p.z, d = Math.sqrt(dx * dx + dy * dy + dz * dz);
        float fall = (float) Math.max(0, 1 - d / 48);
        if (fall <= 0) return false;
        double yaw = Math.toRadians(p.yaw);
        float pan = d < 1e-3 ? 0 : (float) ((-Math.cos(yaw) * dx - Math.sin(yaw) * dz) / d);
        float gv = volume * fall * fall * gain(name);
        if (gv <= 0) return true;
        Audio.play(Audio.load(l.get(RND.nextInt(l.size()))), false, gv, pitch, Math.max(-1, Math.min(1, pan * 0.8f)));
        return true;
    }

    /** At a place with a slightly random pitch (0.9..1.1). */
    public static boolean at(Session s, String name, double x, double y, double z, float volume) {
        return at(s, name, x, y, z, volume, 0.9f + RND.nextFloat() * 0.2f);
    }

    /** The audio files directly in a folder of game/sounds ("music/game"), sorted. */
    public static synchronized List<Path> files(String dirName) {
        if (root == null) return List.of();
        List<Path> l = CACHE.get("dir:" + dirName);
        if (l != null) return l;
        l = new ArrayList<>();
        Path dir = root.resolve(dirName);
        if (Files.isDirectory(dir)) {
            try (Stream<Path> st = Files.list(dir)) {
                st.filter(p -> Files.isRegularFile(p) && (p.toString().toLowerCase(Locale.ROOT).endsWith(".ogg") || p.toString().toLowerCase(Locale.ROOT).endsWith(".wav")))
                        .sorted().forEach(l::add);
            } catch (Exception ignored) {}
        }
        CACHE.put("dir:" + dirName, l);
        return l;
    }

    /** All sound names found in game/sounds (numbers cut off) that contain 'filter': for the /sound command. */
    public static List<String> names(String filter) {
        TreeSet<String> out = new TreeSet<>();
        if (root == null || !Files.isDirectory(root)) return new ArrayList<>();
        try (Stream<Path> st = Files.walk(root)) {
            st.filter(Files::isRegularFile).forEach(p -> {
                String r = root.relativize(p).toString().replace('\\', '/');
                int dot = r.lastIndexOf('.');
                if (dot < 0) return;
                String ext = r.substring(dot).toLowerCase(Locale.ROOT);
                if (!ext.equals(".ogg") && !ext.equals(".wav")) return;
                r = r.substring(0, dot);
                while (!r.isEmpty() && Character.isDigit(r.charAt(r.length() - 1)) && r.length() > 1 && !r.endsWith("/")) r = r.substring(0, r.length() - 1);
                if (filter == null || filter.isEmpty() || r.contains(filter)) out.add(r);
            });
        } catch (Exception ignored) {}
        return new ArrayList<>(out);
    }

    // ------------------------------------------------------------------ doors, armor, notes, music
    /** A door / trapdoor / gate opened or closed. kind: wooden_door, iron_door, wooden_trapdoor, iron_trapdoor, fence_gate. */
    public static void openClose(String kind, boolean open, int x, int y, int z) {
        Session s = Session.current;
        if (s != null) at(s, "block/" + kind + (open ? "/open" : "/close"), x + 0.5, y + 0.5, z + 0.5, 1f);
    }

    /** A click of a pressure plate / button (random/click). */
    public static void click(int x, int y, int z) {
        Session s = Session.current;
        if (s != null) at(s, "random/click", x + 0.5, y + 0.2, z + 0.5, 0.8f, 0.6f);
    }

    /** Putting armor on: item/armor/equip_<leather|chain|iron|gold|diamond|generic>. */
    public static void armor(String itemName) {
        String n = itemName == null ? "" : itemName.toLowerCase(Locale.ROOT);
        String m = n.contains("leather") ? "leather" : n.contains("chain") ? "chain" : n.contains("iron") ? "iron"
                : n.contains("gold") ? "gold" : n.contains("diamond") ? "diamond" : "generic";
        if (!play("item/armor/equip_" + m, 0.8f, 1f)) play("item/armor/equip_generic", 0.8f, 1f);
    }

    /** A note block: the instrument by the block under it, the note 0..15 as semitones. */
    public static void note(Session s, Block under, int note, int x, int y, int z) {
        String n = under == null ? "" : under.name;
        String inst = n.contains("sand") || n.contains("gravel") ? "snare" : n.contains("glass") ? "hat"
                : n.contains("plank") || n.contains("log") || n.contains("wood") ? "bassattack"
                : n.contains("stone") || n.contains("brick") || n.contains("obsidian") || n.contains("ore") ? "bd"
                : n.contains("gold") ? "bell" : n.contains("clay") ? "flute" : n.contains("wool") ? "guitar" : n.contains("ice") ? "icechime" : "harp";
        at(s, "note/" + inst, x + 0.5, y + 0.5, z + 0.5, 3f, (float) Math.pow(2, (note - 4) / 12.0));
    }

    private static Audio.Voice musicVoice;
    private static boolean musicMenu;
    private static float musicWait = 2;

    /**
     * Background music, every frame: in the menus music/menu, in the game music/game (music/game/creative too in creative)
     * with long pauses between the tracks. 'dt' in seconds.
     */
    public static void music(boolean menu, boolean creative, float dt) {
        if (musicVoice != null && musicVoice.playing()) {
            musicVoice.volume = (musicMenu ? 0.35f : 0.3f) * VOL[MUSIC];
            if (musicMenu != menu) { musicVoice.stop(); musicVoice = null; musicWait = menu ? 1 : 20; }
            return;
        }
        musicVoice = null;
        if ((musicWait -= dt) > 0) return;
        List<Path> l = new ArrayList<>(files(menu ? "music/menu" : "music/game"));
        if (!menu && creative) l.addAll(files("music/game/creative"));
        if (l.isEmpty()) { musicWait = 10; return; }
        musicMenu = menu;
        musicVoice = Audio.play(Audio.load(l.get(RND.nextInt(l.size()))), false, (menu ? 0.35f : 0.3f) * VOL[MUSIC], 1, 0);
        musicWait = menu ? 3 : 90 + RND.nextFloat() * 180;
    }

    // ------------------------------------------------------------------ blocks
    /** The sound group of a block: stone, grass, gravel, sand, snow, wood, cloth (null = silent: air, liquids...). */
    public static String material(Block b) {
        if (b == null || b.isAir() || b.shape == Block.Shape.LIQUID) return null;
        String n = b.name;
        if (n.equals("tnt")) return "grass";
        if (n.contains("snow")) return "snow";
        if (n.contains("sand") && !n.contains("stone")) return "sand";
        if (n.contains("gravel") || n.equals("clay") || n.contains("soul")) return "gravel";
        if (n.contains("wool") || n.contains("carpet") || n.contains("bed") || n.contains("web")) return "cloth";
        if (n.contains("plank") || n.contains("log") || n.contains("wood") || n.contains("door") || n.contains("fence") || n.contains("chest")
                || n.contains("table") || n.contains("bookshelf") || n.contains("sign") || n.contains("ladder") || n.contains("jukebox")
                || n.contains("note") || n.contains("barrel") || n.contains("trapdoor")) return "wood";
        if (n.contains("grass") || n.contains("dirt") || n.contains("leaves") || n.contains("sapling") || n.contains("flower")
                || n.contains("fern") || n.contains("vine") || n.contains("farmland") || n.contains("mycelium") || n.contains("podzol")
                || n.contains("cactus") || n.contains("melon") || n.contains("pumpkin") || n.contains("hay") || n.contains("sponge")
                || n.contains("tulip") || n.contains("rose") || n.contains("dandelion") || n.contains("mushroom") || n.contains("lily")) return "grass";
        return "stone";
    }

    /** A block was broken or placed: "dig/<group>". */
    public static void dig(Session s, Block b, int x, int y, int z, float volume) { dig(s, b, x, y, z, volume, false); }

    /** The same, 'place' = the block was put down (item frames and lilies have their own sounds then). */
    public static void dig(Session s, Block b, int x, int y, int z, float volume, boolean place) {
        if (b == null || b.isAir()) return;
        double cx = x + 0.5, cy = y + 0.5, cz = z + 0.5;
        String n = b.name;
        if (n.contains("item_frame") || n.contains("itemframe")) { at(s, "entity/itemframe/" + (place ? "place" : "break"), cx, cy, cz, volume); return; }
        if (place && n.contains("lily")) { at(s, "block/waterlily/place", cx, cy, cz, volume); return; }
        if (n.contains("glass") && !place) { at(s, "random/glass", cx, cy, cz, volume); return; }
        String m = material(b);
        if (m != null) at(s, "dig/" + m, cx, cy, cz, volume);
    }

    /** A footstep on a block: "step/<group>" (ladder on ladders). */
    public static void step(Session s, Block b, double x, double y, double z, float volume) {
        String m = b != null && b.name.contains("ladder") ? "ladder" : material(b);
        if (m != null) at(s, "step/" + m, x, y, z, volume);
    }

    // ------------------------------------------------------------------ mobs
    private static final Map<String, String> MOB_FOLDERS = Map.ofEntries(
            Map.entry("enderman", "endermen"), Map.entry("iron_golem", "irongolem"), Map.entry("zombie_pigman", "zombiepig"),
            Map.entry("pigman", "zombiepig"), Map.entry("zombie_pig", "zombiepig"), Map.entry("magma_cube", "magmacube"),
            Map.entry("polar_bear", "polarbear"), Map.entry("wither_skeleton", "wither_skeleton"), Map.entry("ender_dragon", "enderdragon"),
            Map.entry("evoker", "evocation_illager"), Map.entry("vindicator", "vindication_illager"), Map.entry("illusioner", "illusion_illager"),
            Map.entry("zombie_villager", "zombie_villager"), Map.entry("mooshroom", "cow"), Map.entry("cave_spider", "spider"),
            Map.entry("snow_golem", "snowman"));

    /** The folder of a mob type id ("derpcraft:zombie" -> "mob/zombie"). */
    public static String mobFolder(String typeId) {
        String n = typeId.substring(typeId.indexOf(':') + 1).toLowerCase(Locale.ROOT);
        return "mob/" + MOB_FOLDERS.getOrDefault(n, n);
    }

    /** A mob sound: event say / hurt / death / step (hurt falls back to hit, then say). */
    public static void mob(Session s, String typeId, String event, double x, double y, double z, float volume) {
        String f = mobFolder(typeId);
        String name = switch (event) {
            case "hurt" -> first(f + "/hurt", f + "/hit", f + "/say");
            case "death" -> first(f + "/death", f + "/hurt", f + "/say");
            default -> has(f + "/" + event) ? f + "/" + event : null;
        };
        if (name != null) at(s, name, x, y, z, volume);
    }
}
