package derpcraft.gun;

import derpcraft.game.Session;
import derpcraft.item.Item;
import derpcraft.item.ItemStack;
import derpcraft.item.Items;
import derpcraft.render.BlockTextures;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Gun kinds: every folder in game/guns is one (like the vehicles in game/transport). Each gives two items: the gun
 * "gun:&lt;name&gt;" and its magazine "gun:&lt;name&gt;_mag" (creative tab Combat). Everything is read again by
 * /guns reload (also new folders, models, textures and icons), and by itself when a file in game/guns changes.
 */
public final class Guns {
    private Guns() {}

    public static final String PREFIX = "gun:";
    private static Path dir;
    private static final Map<String, GunType> TYPES = new LinkedHashMap<>();
    /** Rebuilds the item texture atlas (set by the game: icons of new or changed guns). */
    public static Runnable rebuildIcons = () -> {};
    /** Changes on every reload (the renderer and the editor drop what they keep of the old gun objects). */
    private static int version;
    private static String signature = "", iconSignature = "";
    private static long lastWatch;
    /** Watch game/guns for changed files and reload by itself. */
    public static boolean autoReload = true;

    public static int version() { return version; }

    public static Path dir() { return dir; }

    public static void init(Path gameDir) {
        dir = gameDir.resolve("guns");
        boolean fresh = !Files.exists(dir);
        try { Files.createDirectories(dir); } catch (IOException ignored) {}
        copyExamples(fresh);
        try {
            Path help = dir.resolve("README.txt");
            Files.writeString(help, HELP, StandardCharsets.UTF_8);
            Path tpl = dir.resolve("_template.json");
            if (!Files.exists(tpl)) Files.writeString(tpl, TEMPLATE, StandardCharsets.UTF_8);
        } catch (IOException ignored) {}
        rescan();
        iconSignature = iconSignature();
    }

    /** The example guns that come with the game (model, textures, settings; the sounds are the player's own). */
    private static final String[] EXAMPLES = {"ak12", "fp9"};

    /**
     * Puts each example gun into game/guns once: all of them the first time, later only the ones added by a newer game
     * version (game/guns/.examples lists the ones already given, so a deleted example does not come back).
     */
    private static void copyExamples(boolean fresh) {
        Path list = dir.resolve(".examples");
        Set<String> given = new HashSet<>();
        try { if (Files.exists(list)) given.addAll(Files.readAllLines(list, StandardCharsets.UTF_8)); } catch (IOException ignored) {}
        if (!fresh && given.isEmpty()) given.add("ak12");          // a folder from before the list: the AK was given then
        boolean changed = false;
        for (String ex : EXAMPLES) {
            if (given.contains(ex)) continue;
            given.add(ex);
            changed = true;
            Path to = dir.resolve(ex);
            if (Files.exists(to)) continue;
            for (String f : new String[]{".obj", ".png", "_mag.png", "_item.png", ".json"}) {
                try (InputStream in = Guns.class.getResourceAsStream("/guns/" + ex + "/" + ex + f)) {
                    if (in == null) continue;
                    Files.createDirectories(to);
                    Files.copy(in, to.resolve(ex + f));
                } catch (IOException e) { System.err.println("Cannot copy the example gun " + ex + ": " + e); }
            }
        }
        if (changed) try { Files.write(list, given, StandardCharsets.UTF_8); } catch (IOException ignored) {}
    }

    /** Reads the folders again (numbers, sounds; models load on next use). Does not touch items: see {@link #reload}. */
    public static synchronized void rescan() {
        Map<String, GunType> old = new HashMap<>(TYPES);
        TYPES.clear();
        if (dir != null && Files.isDirectory(dir)) {
            try (var st = Files.list(dir)) {
                List<Path> ds = new ArrayList<>(st.filter(Files::isDirectory).toList());
                ds.sort(Comparator.comparing(p -> p.getFileName().toString().toLowerCase(Locale.ROOT)));
                for (Path d : ds) {
                    String n = d.getFileName().toString();
                    if (n.startsWith(".") || n.startsWith("_")) continue;
                    String id = n.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_]", "_");
                    TYPES.put(id, new GunType(id, d));
                }
            } catch (IOException ignored) {}
        }
        for (GunType t : old.values()) if (t.model != null) t.model.dispose();
        version++;
        signature = signature();
    }

    /**
     * Full reload without restarting (main thread): folders, numbers, sounds, models and their textures, new guns get
     * their items, item and magazine icons are rebuilt when they changed. Returns a line for the chat.
     */
    public static String reload() {
        rescan();
        int added = registerItems();
        for (GunType t : all()) t.model();
        String icons = iconSignature();
        boolean iconsChanged = !icons.equals(iconSignature);
        iconSignature = icons;
        if (iconsChanged || added > 0) rebuildIcons.run();
        StringBuilder sb = new StringBuilder("§aGuns reloaded: " + names());
        if (added > 0) sb.append(" §7(").append(added).append(" new)");
        for (GunType t : all()) if (t.error != null) sb.append(" §c").append(t.name).append(": ").append(t.error);
        return sb.toString();
    }

    /** Called every tick: reloads by itself when a file in game/guns changed (checked once a second). */
    public static String watch() {
        if (!autoReload || dir == null) return null;
        long now = System.currentTimeMillis();
        if (now - lastWatch < 1000) return null;
        lastWatch = now;
        String sig = signature();
        if (sig.equals(signature)) return null;
        return reload();
    }

    /** After the editor wrote a json: the watcher must not reload because of it. */
    public static void savedByEditor() { signature = signature(); }

    /** Names, sizes and times of every file under game/guns. */
    private static String signature() {
        if (dir == null) return "";
        StringBuilder sb = new StringBuilder();
        try (var st = Files.walk(dir, 3)) {
            for (Path p : st.filter(Files::isRegularFile).sorted().toList()) {
                String n = p.getFileName().toString();
                if (n.endsWith(".tmp") || n.equals("README.txt")) continue;
                sb.append(dir.relativize(p)).append(':').append(Files.size(p)).append(':').append(Files.getLastModifiedTime(p).toMillis()).append(';');
            }
        } catch (IOException ignored) {}
        return sb.toString();
    }

    private static String iconSignature() {
        StringBuilder sb = new StringBuilder();
        for (GunType t : all())
            for (Path p : new Path[]{t.itemIcon, t.magIcon}) {
                sb.append(t.name).append('=');
                try { if (p != null) sb.append(p.getFileName()).append(':').append(Files.getLastModifiedTime(p).toMillis()).append(':').append(Files.size(p)); }
                catch (IOException ignored) {}
                sb.append(';');
            }
        return sb.toString();
    }

    public static synchronized GunType get(String id) { return id == null ? null : TYPES.get(id); }

    public static synchronized List<GunType> all() { return new ArrayList<>(TYPES.values()); }

    /** The gun kind of an item stack (the gun itself, not its magazine), or null. */
    public static GunType of(ItemStack st) { return st == null ? null : of(st.item); }

    public static GunType of(Item it) {
        if (it == null || !it.name.startsWith(PREFIX) || it.name.endsWith("_mag")) return null;
        return get(it.name.substring(PREFIX.length()));
    }

    public static Item magItem(GunType t) { return Items.byName(PREFIX + t.name + "_mag"); }

    /** Whether this is a gun's magazine item (the only thing the pouches take, Left Alt + E). */
    public static boolean isMag(Item it) { return it != null && it.name.startsWith(PREFIX) && it.name.endsWith("_mag"); }

    /** The gun kind a magazine item belongs to, or null. */
    public static GunType ofMag(Item it) {
        if (!isMag(it)) return null;
        return get(it.name.substring(PREFIX.length(), it.name.length() - 4));
    }

    // ---------------------------------------------------------------- items
    /** Gun and magazine items of every folder; existing ones get the new names, tooltips and icon files. Returns how many are new. */
    public static int registerItems() {
        int added = 0;
        for (GunType t : all()) {
            Item gun = Items.byName(PREFIX + t.name);
            if (gun == null) {
                gun = new Item(PREFIX + t.name);
                gun.maxStack = 1;
                gun.tab = "combat";
                gun.texture = PREFIX + t.name + "_item";
                Items.register(gun);
                added++;
            }
            gun.displayName = t.displayName;
            gun.tooltip = List.of(String.format(Locale.ROOT, "%.0f rpm, %d rounds, damage %.1f", t.rpm, t.magazine, t.damage),
                    "Aim: right mouse   Reload: " + derpcraft.game.Keys.n(derpcraft.game.Keys.RELOAD) + "   Melee: " + derpcraft.game.Keys.n(derpcraft.game.Keys.MELEE));
            if (t.itemIcon != null) BlockTextures.EXTERNAL.put(gun.texture, t.itemIcon); else BlockTextures.EXTERNAL.remove(gun.texture);
            Item mag = Items.byName(PREFIX + t.name + "_mag");
            if (mag == null) {
                mag = new Item(PREFIX + t.name + "_mag");
                mag.maxStack = 16;
                mag.tab = "combat";
                mag.texture = PREFIX + t.name + "_mag";
                Items.register(mag);
            }
            mag.displayName = t.displayName + " Magazine";
            if (t.magIcon != null) BlockTextures.EXTERNAL.put(mag.texture, t.magIcon); else BlockTextures.EXTERNAL.remove(mag.texture);
        }
        return added;
    }

    /** /gun and /guns: list | reload | give &lt;name&gt; [mags] | edit | auto on|off */
    public static void registerCommand(derpcraft.api.ModContext c) {
        for (String cmd : new String[]{"gun", "guns"})
            c.addCommand(cmd, "list | reload | give <name> [mags] | edit | auto on|off", (g, a) -> {
                Session s = (Session) g;
                String sub = a.length == 0 ? "list" : a[0].toLowerCase(Locale.ROOT);
                switch (sub) {
                    case "reload", "r" -> s.chat(reload());
                    case "edit", "editor" -> s.game.setScreen(new derpcraft.gui.GunEditor(s.game, s));
                    case "auto" -> {
                        autoReload = a.length < 2 ? !autoReload : a[1].equalsIgnoreCase("on");
                        s.chat("Auto reload of game/guns: " + (autoReload ? "§aon" : "§coff"));
                    }
                    case "give" -> {
                        if (a.length < 2) throw new IllegalArgumentException("/guns give <name> [mags]");
                        GunType t = get(a[1].toLowerCase(Locale.ROOT));
                        Item it = t == null ? null : Items.byName(PREFIX + t.name);
                        if (it == null) throw new IllegalArgumentException("No gun " + a[1] + " (" + names() + ")");
                        s.giveOrDrop(new ItemStack(it, 1));
                        int mags = a.length > 2 ? Integer.parseInt(a[2]) : 4;
                        Item mi = magItem(t);
                        if (mi != null && mags > 0) s.giveOrDrop(new ItemStack(mi, Math.min(64, mags)));
                    }
                    default -> {
                        if (TYPES.isEmpty()) { s.chat("§eNo guns: make a folder in game/guns (see game/guns/README.txt)"); return; }
                        for (GunType t : all()) {
                            t.model();
                            s.chat(t.error != null ? "§c" + t.name + ": " + t.error
                                    : String.format(Locale.ROOT, "§f%s §7(%s, %.0f rpm, %d rounds, %d shot sounds%s)", t.name, t.displayName, t.rpm, t.magazine, t.shootSounds.size(),
                                    t.warnings.isEmpty() ? "" : ", §e" + String.join("; ", t.warnings) + "§7"));
                        }
                    }
                }
            });
    }

    static String names() {
        StringJoiner j = new StringJoiner(", ");
        for (GunType t : all()) j.add(t.name);
        return j.length() == 0 ? "none" : j.toString();
    }

    static final String TEMPLATE = """
            {
              "name": "My gun",
              "fireRate": 600,
              "mode": "auto",
              "magazine": 30,
              "damage": 6,
              "range": 160,
              "reloadTime": 2.2,
              "reloadEmptyTime": 2.8,
              "moveSpeed": 0.95,
              "aimMoveSpeed": 0.6,
              "sprintSpeed": 0.9,
              "spreadHip": 3.5,
              "spreadAim": 0.25,
              "spreadPerShot": 0.35,
              "spreadMove": 1.5,
              "recoil": 0.9,
              "recoilSide": 0.45,
              "aimZoom": 1.35,
              "aimTime": 0.22,
              "firstPersonScale": 0.4,
              "thirdPersonScale": 0.42,
              "hipPosition": [0.2, -0.24, -0.4],
              "volume": 1,
              "tracerChance": 0.3,
              "tracerSpeed": 320,
              "tracerLength": 5,
              "tracerWidth": 0.05,
              "tracerGlow": 4,
              "tracerColor": [1, 0.82, 0.25],
              "reloadStyle": "rifle",
              "slideLock": false,
              "boltShot": 0.9,
              "meleeReach": 2.6,
              "meleeKnockback": 0.45,
              "meleeCombo": 0.45,
              "jabDamage": 4,
              "swingDamage": 5,
              "smashDamage": 7
            }
            """;

    static final String HELP = """
            DerpCraft guns: one folder per gun, the folder name is the gun's id (game/guns/ak12).

              ak12.obj        model (Blockbench OBJ export, 1 unit = 1 block, barrel to -Z). Objects:
                                gun parts (any name), "mag" = magazine, "slide" = bolt / charging handle
                                helper boxes, never drawn: rag = right hand (grip), lag = left hand (handguard),
                                las = left hand on the bolt (reload), ram = right hand on the magazine
                              The arms are the player's own (skin, normal Minecraft arms) holding these points: no arms in the model.
              ak12.png        texture
              ak12_item.png   inventory icon        ak12_mag.png   magazine icon
              shoot1.ogg      shot sound (shoot1..shoot3: a random one per shot), .ogg or .wav
              mag_out, mag_in, bolt, dry, equip, aim (.ogg / .wav): optional reload and handling sounds
              melee, melee_hit (.ogg / .wav): optional butt strike sounds (else game/fists swing*, hit*)
              unattach1..5   magazine taken off (a random one; else mag_out)
              attach1..5     magazine put in (a random one; else mag_in)
              slide1, slide2 bolt starts / ends moving when racked after an empty reload (else bolt)
            Shared by all guns, game/guns/sfx/ (.ogg / .wav, a random one of the numbers each time):
              attack/attack1..5   butt strike (Left Alt + middle mouse button)
              aim1, aim2          going into the aim (right mouse button) / the second aim (Alt + right mouse button)
              drop1..3            a dropped magazine hits the ground (reloads)
              get1..5             taking a gun out and putting it away
              back1..5            gun onto the back (Left Alt + F)
            Missing files fall back to the old single sounds (equip, melee, mag_out, mag_in, bolt).
              ak12.json       numbers, see _template.json (everything optional). Easiest: the gun editor.
                              Tracers: tracerChance (share of glowing bullets, 0..1), tracerSpeed (blocks / second),
                              tracerLength, tracerWidth (blocks), tracerGlow (brightness), tracerColor [r, g, b].
                              reloadStyle: "rifle" (the magazine rocks out, AK) or "pistol" (it slides out of the grip,
                              the slide is racked); slideLock: true = the slide stays back when empty; boltShot: how far
                              the bolt goes back per shot (1 = all the way). Bolt limits: points boltFront / boltBack.

            Gun editor: Alt + Home (or /guns edit) with the gun in hand: every number with sliders, the hand points,
            test buttons (shoot, reload, aim, run, slow motion). Changes are saved to the json at once; Ctrl+Z undoes.
            Example guns: ak12 (rifle) and fp9 (pistol).
            /guns reload rereads everything (new folders, models, textures, icons); a changed file in game/guns
            is also picked up by itself within a second (/guns auto off to stop that).
            In game: creative tab Combat, or /guns give ak12. Left mouse: shoot, right mouse: aim, R: reload,
            middle mouse: butt strike (again soon: the next kind, jab -> swing -> smash). Melee poses, times and damage:
            gun editor, View -> Melee (json: jabWindPosition, jabHitRotation, jabWindTime..., jabDamage, meleeReach...).
            """;
}
