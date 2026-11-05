package derpcraft.api;

/** A chat command handler. args are the words after the command name. Throw IllegalArgumentException to show usage. */
@FunctionalInterface
public interface Command {
    void run(GameApi game, String[] args);
}
