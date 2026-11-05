package derpcraft.api;

/**
 * Entry point of a DerpCraft mod.
 *
 * <pre>
 * public class MyMod implements DerpMod {
 *     public String id() { return "mymod"; }
 *     public void init(ModContext ctx) {
 *         ctx.registerBlock("ruby_block").texture("mymod:ruby_block").light(7).register();
 *         ctx.addCommand("hello", "Says hello", (game, args) -> game.chat("Hello!"));
 *     }
 * }
 * </pre>
 * Package the mod as a jar with META-INF/services/derpcraft.api.DerpMod containing the class name,
 * and drop it into the "mods" folder. Textures go into assets/&lt;modid&gt;/textures/blocks/ inside the jar.
 */
public interface DerpMod {
    /** Unique lowercase id, also the texture namespace. */
    String id();

    default String name() { return id(); }

    default String version() { return "1.0"; }

    /** Ids of mods that must be initialised before this one (their blocks and items exist when yours load). */
    default String[] dependencies() { return new String[0]; }

    /** Called once at startup, before any world is loaded. Register blocks, commands, events and keys here. */
    void init(ModContext ctx);

    /**
     * Called after all blocks, items and the vanilla recipes exist (block items are created automatically).
     * Add items with {@link ModContext#registerItem} and recipes with {@link ModContext#addRecipe} here.
     */
    default void initItems(ModContext ctx) {}

    /**
     * Called first, before the game's classes load: change the game's own code here (see {@link derpcraft.patch.Patcher}).
     * Don't use game classes in this method - only register patches.
     */
    default void patches(derpcraft.patch.Patcher patcher) {}
}
