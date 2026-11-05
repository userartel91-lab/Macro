package derpcraft.patch;

/**
 * Changes the game's own code while it loads, without editing the game (like Mixin in Fabric / Forge).
 * Mods get one in {@link derpcraft.api.DerpMod#patches}. Works when the game is started normally (Play.bat /
 * the Launch main class).
 *
 * <p>Classes are named fully ("derpcraft.game.Player"). Methods by name ("tick") - every overload - or with
 * simple parameter type names to pick one ("setBlock(int,int,int,Block,int)"). Constructors are "&lt;init&gt;"
 * (tail only). Read the game's sources to find what to patch.
 *
 * <pre>
 * public void patches(Patcher p) {
 *     // half hunger: change an argument
 *     p.head("derpcraft.game.PlayerStats", "addExhaustion", c -> c.setArg(0, c.argFloat(0) * 0.5f));
 *     // no fall damage: cancel the method
 *     p.head("derpcraft.game.PlayerStats", "damage", c -> { if ("fall".equals(c.arg(1))) c.cancel(false); });
 *     // change a result
 *     p.tail("derpcraft.block.Block", "lightEmitted", c -> ...);
 *     // skip / change a call inside a method
 *     p.beforeCall("derpcraft.game.Session", "tick", "derpcraft.world.World", "tickRandomBlocks", c -> c.cancel());
 *     // change a number in the code
 *     p.constant("derpcraft.game.Player", "tick", 0.08, 0.04);
 * }
 * </pre>
 * Rules: in patches() don't touch game classes yet (they must load after all patches are known); hooks run on the
 * game thread; an exception in a hook is logged and ignored.
 */
public interface Patcher {
    /** Runs at the start of the method. The hook can change arguments ({@link Call#setArg}) or {@link Call#cancel} the method. */
    void head(String className, String method, Hook hook);

    /** Runs before the method returns (every return). The hook can read and replace the result ({@link Call#setReturn}). */
    void tail(String className, String method, Hook hook);

    /** Replaces the whole method: the hook runs instead and its {@link Call#setReturn} value is returned. */
    void replace(String className, String method, Hook hook);

    /**
     * Runs before every call of callClass.callMethod inside the method. {@link Call#target} is the object being
     * called, the arguments are the call's arguments (can be changed), {@link Call#cancel(Object)} skips the call and
     * uses the given value as its result.
     */
    void beforeCall(String className, String method, String callClass, String callMethod, Hook hook);

    /** Replaces a constant (int, long, float, double or String) used in the method with another of the same type. */
    void constant(String className, String method, Object value, Object replacement);

    /** Id of the mod these patches belong to (shown in errors). */
    String modId();
}
