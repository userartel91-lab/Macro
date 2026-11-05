package derpcraft.patch;

/** Code a mod injects into the game. Gets the {@link Call}: arguments, this object, return value. */
@FunctionalInterface
public interface Hook {
    void run(Call call) throws Throwable;
}
