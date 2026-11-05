package derpcraft.api;

import java.util.*;

/**
 * Everything a mod can register. One instance per mod is passed to {@link DerpMod#init}.
 * The game also uses it for its own built-in commands.
 */
public final class ModContext {
    /** Registered commands by lowercase name (shared by all mods). */
    public static final Map<String, CommandEntry> COMMANDS = new TreeMap<>();
    public static final List<Events.TickHandler> TICK = new ArrayList<>();
    public static final List<Events.PlaceHandler> PLACE = new ArrayList<>();
    public static final List<Events.BreakHandler> BREAK = new ArrayList<>();
    public static final List<Events.ChatHandler> CHAT = new ArrayList<>();
    public static final List<Events.WorldHandler> WORLD_LOAD = new ArrayList<>();
    public static final List<Events.HudHandler> HUD = new ArrayList<>();
    public static final List<KeyEntry> KEYS = new ArrayList<>();
    public static final List<Events.FrameHandler> FRAME = new ArrayList<>();
    public static final List<Events.CameraPathHandler> PATH_END = new ArrayList<>();
    public static final List<Events.SceneHandler> SCENE_END = new ArrayList<>();
    public static final List<Events.TickHandler> PLAYER_TICK = new ArrayList<>();
    public static final List<Events.InteractHandler> INTERACT = new ArrayList<>();
    public static final List<Events.ItemUseHandler> ITEM_USE = new ArrayList<>();
    /** Colour grading presets added by mods: name -> preset text. */
    public static final Map<String, String> GRADING_PRESETS = new LinkedHashMap<>();

    public record CommandEntry(String name, String usage, Command handler, String modId) {}
    public record KeyEntry(String description, int glfwKey, Events.KeyHandler action, String modId) {}

    public final String modId;

    public ModContext(String modId) { this.modId = modId; }

    /** Starts a new block. The final name is "modid:name". */
    public BlockBuilder registerBlock(String name) {
        return new BlockBuilder(modId.equals("minecraft") || name.contains(":") ? name : modId + ":" + name);
    }

    /** Registers a chat command typed as /name (or //name). */
    public void addCommand(String name, String usage, Command handler) {
        COMMANDS.put(name.toLowerCase(Locale.ROOT), new CommandEntry(name.toLowerCase(Locale.ROOT), usage, handler, modId));
    }

    /** Called 20 times per second while a world is open. */
    public void onTick(Events.TickHandler h) { TICK.add(h); }

    public void onBlockPlace(Events.PlaceHandler h) { PLACE.add(h); }

    public void onBlockBreak(Events.BreakHandler h) { BREAK.add(h); }

    public void onChat(Events.ChatHandler h) { CHAT.add(h); }

    /** Called after a world finished loading. */
    public void onWorldLoad(Events.WorldHandler h) { WORLD_LOAD.add(h); }

    /** Draw on the in-game HUD every frame. */
    public void onHudRender(Events.HudHandler h) { HUD.add(h); }

    /** Runs 'action' when the key (GLFW key code, e.g. 72 = H) is pressed in game. */
    public void addKey(String description, int glfwKey, Events.KeyHandler action) { KEYS.add(new KeyEntry(description, glfwKey, action, modId)); }

    /**
     * Registers a simple item ("modid:name") with a texture from assets/&lt;modid&gt;/textures/items/&lt;texture&gt;.png.
     * Set fields on the returned item (maxStack, food, fuel, tool...) or subclass {@link derpcraft.item.Item}
     * and register it with {@link derpcraft.item.Items#register}. Call from {@link DerpMod#initItems}.
     */
    public derpcraft.item.Item registerItem(String name, String texture, String creativeTab) {
        derpcraft.item.Item it = new derpcraft.item.Item(name.contains(":") ? name : modId + ":" + name);
        it.displayName = derpcraft.block.Block.prettify(name.contains(":") ? name.substring(name.indexOf(':') + 1) : name);
        it.texture = (texture.contains(":") ? texture.substring(0, texture.indexOf(':') + 1) + "items/" + texture.substring(texture.indexOf(':') + 1) : modId + ":items/" + texture);
        it.tab = creativeTab;
        return derpcraft.item.Items.register(it);
    }

    /**
     * Adds a recipe in DerpCraft's one-line format:
     * <pre>
     * S result count | row1 row2 row3 | k=item k=item     (shaped, '.' = empty)
     * L result count | item item ...                      (shapeless)
     * F input output xp                                   (furnace)
     * </pre>
     * Example: {@code ctx.addRecipe("S mymod:ruby_block 1 | ### ### ### | #=mymod:ruby")}
     */
    public void addRecipe(String line) { derpcraft.item.Recipes.parse(line); }

    /** Called every rendered frame (before the camera updates). For smooth camera animation; keep it cheap. */
    public void onFrame(Events.FrameHandler h) { FRAME.add(h); }

    /** Called when a camera path finishes. */
    public void onCameraPathEnd(Events.CameraPathHandler h) { PATH_END.add(h); }

    /** Called when an NPC scene (recorded actors) finishes playing. */
    public void onSceneEnd(Events.SceneHandler h) { SCENE_END.add(h); }

    /**
     * Adds a colour grading preset, shown in the F6 editor next to the built-in ones. 'presetText' uses the preset
     * file format (key=value lines, as saved in game/grading/*.txt), e.g. "tonemap=1\nexposure=0.3\nbloom=0.2".
     */
    public void addGradingPreset(String name, String presetText) { GRADING_PRESETS.put(name, presetText); }

    /** Same, read from a resource inside the mod jar (e.g. "/assets/mymod/grading/dusk.txt"). */
    public void addGradingPreset(String name, Class<?> modClass, String resource) {
        try (var in = modClass.getResourceAsStream(resource)) {
            if (in == null) { log("grading preset not found: " + resource); return; }
            addGradingPreset(name, new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
        } catch (java.io.IOException e) { log("cannot read " + resource + ": " + e.getMessage()); }
    }

    // ------------------------------------------------------------------ since 1.5
    /** Starts a new item "modid:name" (call from {@link DerpMod#initItems}). */
    public ItemBuilder item(String name) { return new ItemBuilder(modId, name); }

    /**
     * A recipe for machines of a type (see {@link BlockBuilder#machine}). Inputs: "item", "item*count" or "#tag";
     * input2 = null for one-input machines; ticks = 0 uses the machine's cook time. Call from initItems.
     * Example: {@code ctx.addMachineRecipe("mymod:grinding", "cobblestone", null, "gravel", 1, 0, 0.1f)}
     */
    public void addMachineRecipe(String type, String input1, String input2, String output, int count, int ticks, float xp) {
        derpcraft.item.MachineRecipes.add(type, input1, input2, output, count, ticks, xp);
    }

    /** Adds items to a recipe tag usable as "#name" in recipes (creates the tag if needed). */
    public void addItemTag(String tag, String... items) { derpcraft.item.Recipes.addTag(tag, items); }

    /** Every game tick while a world is open (after the world and the player were updated). */
    public void onPlayerTick(Events.TickHandler h) { PLAYER_TICK.add(h); }

    /** Right click on any block, before it reacts; cancel to block it. */
    public void onBlockInteract(Events.InteractHandler h) { INTERACT.add(h); }

    /** Right click with any item; cancel to stop the default action. */
    public void onItemUse(Events.ItemUseHandler h) { ITEM_USE.add(h); }

    private DataTag config;
    /** The game folder (set by the game at startup). */
    public static java.nio.file.Path gameDir = java.nio.file.Paths.get(".");

    /**
     * The mod's settings file game/config/&lt;modid&gt;.txt (one "key=value" per line, editable by players).
     * Defaults you put in that aren't in the file yet are written on {@link #saveConfig()}.
     */
    public DataTag config() {
        if (config != null) return config;
        config = new DataTag();
        java.nio.file.Path f = gameDir.resolve("config").resolve(modId + ".txt");
        try {
            if (java.nio.file.Files.exists(f))
                for (String line : java.nio.file.Files.readAllLines(f)) {
                    int i = line.indexOf('=');
                    if (i > 0 && !line.startsWith("#")) config.put(line.substring(0, i).trim(), line.substring(i + 1).trim());
                }
        } catch (java.io.IOException e) { log("cannot read config: " + e); }
        return config;
    }

    public void saveConfig() {
        if (config == null) return;
        StringBuilder sb = new StringBuilder("# " + modId + " settings\n");
        for (String k : config.keys()) sb.append(k).append('=').append(config.getString(k)).append('\n');
        try {
            java.nio.file.Files.createDirectories(gameDir.resolve("config"));
            java.nio.file.Files.writeString(gameDir.resolve("config").resolve(modId + ".txt"), sb.toString());
        } catch (java.io.IOException e) { log("cannot save config: " + e); }
    }

    public void log(String msg) { System.out.println("[" + modId + "] " + msg); }

    // ================================================================== 1.7
    /**
     * Listens to any event class in {@link Events}: {@code ctx.on(Events.PlayerDamage.class, (game, e) -> e.amount /= 2);}
     * Cancellable events can be cancelled, most have fields you may change.
     */
    public <E> void on(Class<E> eventType, Events.Handler<E> handler) { EventBus.add(eventType, handler); }

    /** Starts a new creature type ("modid:name"). See {@link MobBuilder}. */
    public MobBuilder registerMob(String name) { return new MobBuilder(modId, name); }

    /** Starts a new projectile type ("modid:name"): things that fly (thrown, shot). See {@link ProjectileBuilder}. */
    public ProjectileBuilder registerProjectile(String name) { return new ProjectileBuilder(modId, name); }

    /** World generation: called for every newly generated chunk (worker threads - only touch that chunk). */
    public void onChunkGenerate(WorldGen.Decorator decorator) { WorldGen.add(decorator); }

    /**
     * Adds an ore: 'veins' veins of up to 'size' blocks per chunk between minY and maxY, replacing 'in' (usually "stone").
     * Example: {@code ctx.addOre("mymod:ruby_ore", "stone", 6, 3, 5, 40);}
     */
    public void addOre(String ore, String in, int size, int veins, int minY, int maxY) { WorldGen.addOre(ore, in, size, veins, minY, maxY); }
}
