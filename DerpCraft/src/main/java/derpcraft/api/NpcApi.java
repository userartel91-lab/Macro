package derpcraft.api;

import java.util.List;

/**
 * NPCs and recorded actors (the F10 NPC Hub). Get it with {@link GameApi#npcs()}. NPCs are addressed by name.
 * Skins: "Steve", the built-in outfits (Alex, Suit, Worker, Doctor, Police, Red Hoodie, Farmer, Knight, Villain, Kid)
 * and PNG files from game/skins (file name without .png).
 */
public interface NpcApi {
    /** Names of all NPCs in the world. */
    List<String> list();

    boolean exists(String name);

    /** Spawns an NPC (a unique name is made if taken); returns the final name. */
    String spawn(String name, double x, double y, double z, float yaw);

    void remove(String name);

    /** Position {x, y, z, yaw} or null. */
    double[] position(String name);

    /** Moves an NPC (and makes it its new home). */
    void teleport(String name, double x, double y, double z, float yaw);

    List<String> skins();
    void setSkin(String name, String skin);

    /** "stand", "look", "wander" or "follow". */
    void setBehavior(String name, String behavior);

    /** "normal", "sit", "sneak" or "wave". */
    void setPose(String name, String pose);

    /** Item in the NPC's hand ("diamond_sword", null = empty). */
    void setHeldItem(String name, String item);

    void setNameTag(String name, boolean visible);

    /** Prints a line of dialogue in chat as "&lt;name&gt; text". */
    void say(String name, String text);

    // ------------------------------------------------------------------ recordings and scenes
    /** Length of the NPC's take for the current scene in seconds (0 = none). */
    float takeSeconds(String name);

    /** Length of the NPC's take for a scene. */
    float takeSeconds(String name, String scene);

    /** Starts recording the player as this NPC (with the countdown from the hub). */
    void startRecording(String name);

    /** Stops recording and keeps the take. */
    void stopRecording();
    boolean recording();

    /** Plays all takes together from the start (restores the recorded blocks first). */
    boolean playScene();
    void stopScene();
    boolean scenePlaying();

    /** Undo recorded block changes and put all actors at their start. */
    void resetScene();

    /** Current scene time and length in seconds. */
    float sceneTime();
    float sceneLength();

    void setSceneLoop(boolean loop);

    /** Start the camera path together with the scene. */
    void setSceneCamera(boolean on);

    // ------------------------------------------------------------------ since 1.6: named scenes
    /** The scene recorded / played from the hub. */
    String currentScene();
    void setCurrentScene(String scene);

    /** All scene names with takes. */
    List<String> scenes();

    /** Scenes an NPC has takes for. */
    List<String> scenesOf(String name);

    /** Plays a named scene: the cast walks to their marks, plays, then returns to its AI. */
    boolean playScene(String scene);
    void stopScene(String scene);
    boolean scenePlaying(String scene);

    /** Actors walk to their start marks (true) or teleport there (false). */
    void setWalkToMarks(boolean walk);

    // ------------------------------------------------------------------ since 1.6: AI and combat
    /** "aggressive", "neutral" or "friendly". */
    void setFaction(String name, String faction);
    String faction(String name);

    /** Whether NPCs of faction 'attacker' attack NPCs of faction 'target' (e.g. "aggressive", "friendly"). */
    void setFactionAttacks(String attacker, String target, boolean attacks);

    /** Aggressive NPCs also attack a creative-mode player. */
    void setAttackCreative(boolean on);

    /** How close something must come before an aggressive NPC attacks (blocks). */
    void setAggroRange(String name, float blocks);

    /** Neutral NPCs after being hit: "fight" or "flee". */
    void setOnHit(String name, String reaction);

    /** When someone attacks the player: "ignore", "defend" or "flee". */
    void setGuard(String name, String reaction);

    /** Follow behaviour: start following beyond 'start' blocks, stop within 'stop' blocks. */
    void setFollowDistance(String name, float start, float stop);

    void setAbilities(String name, boolean openDoors, boolean breakBlocks);

    void setHealth(String name, float maxHealth);
    float health(String name);

    /** Damage per hit (0 = from the held item). */
    void setDamage(String name, float damage);

    /** Respawn at home 5 s after dying (false = stays down until revived). */
    void setRespawn(String name, boolean respawn);

    void hurt(String name, float amount);
    void kill(String name);
    void revive(String name);
    boolean dead(String name);

    /** Makes an NPC attack another NPC (or the player when target is null) for a while. */
    void attack(String name, String target);

    // ------------------------------------------------------------------ since 1.8: scene groups, switching NPCs off
    /** Scene groups (each is a scene with its own NPCs). The current one is {@link #currentScene()}. */
    List<String> groups();

    /** NPC names in a group. */
    List<String> groupMembers(String group);

    /** Creates an empty group; returns its final name (a number is added if taken). */
    String createGroup(String name);

    /** Copies a group with its NPCs, with or without their takes; returns the new group's name. */
    String copyGroup(String group, boolean withTakes);

    void renameGroup(String group, String newName);
    void deleteGroup(String group);

    /** Switches a whole group in / out of the world. */
    void setGroupEnabled(String group, boolean on);
    boolean groupEnabled(String group);

    String groupOf(String name);
    void moveToGroup(String name, String group);

    /** Takes an NPC out of the world (kept) or puts it back. */
    void setEnabled(String name, boolean on);
    boolean enabled(String name);

    /** Off = the NPC keeps its AI but does not play in scenes. */
    void setSceneEnabled(String name, boolean on);

    /** Plays a scene with only some of its NPCs. */
    boolean playScene(String scene, List<String> actors);

    // ------------------------------------------------------------------ since 1.9: looks, poses, takes and timeline
    /** Poses of the pose library (built-in and made in the pose editor); use them with {@link #setPose}. */
    List<String> poses();

    /** Face: "normal", "happy", "angry", "sad", "surprised", "scared", "eyes closed". */
    void setEmotion(String name, String face);

    void setOffhandItem(String name, String item);

    /** Slot 0 helmet (or any item / block as a hat), 1 chestplate, 2 leggings, 3 boots; null item = none. */
    void setArmor(String name, int slot, String item);

    /** Size: 1 = normal. */
    void setScale(String name, float scale);

    /** Patrol points (behaviour "patrol"). */
    void addWaypoint(String name, double x, double y, double z);
    void clearWaypoints(String name);

    /** Sits on a block (chair: stairs, slab...). */
    void setSeat(String name, int x, int y, int z);

    /** Rides another NPC. */
    void setRide(String name, String target);

    /** Takes of an NPC in a scene; which one plays. */
    int takeCount(String name, String scene);
    void setActiveTake(String name, String scene, int index);

    /** Timeline: the active take starts 'offsetTicks' after the scene starts and plays at 'speed'. */
    void setTakeTiming(String name, String scene, int offsetTicks, float speed);

    /** Timeline: from 'tick' the NPC blends into a pose over 'blendTicks' ("linear", "smooth", "ease in", "ease out", "instant"). */
    void addPoseKey(String name, String scene, int tick, String pose, String curve, int blendTicks);

    /** Plays a scene from a tick of its timeline. */
    boolean playSceneFrom(String scene, int tick);

    // ------------------------------------------------------------------ since 1.12: 3D models (game/3d) on NPCs
    /** Model files in game/3d ("hats/cap.json", "barrel.obj"...). */
    List<String> models3d();

    /**
     * Puts a model on a body part ("head", "body", "right arm", "left arm", "right leg", "left leg"). Offset in
     * pixels from the part (x right, y up, z forward), turn in degrees, size 1 = as set in the 3D Hub.
     */
    void addModel(String name, String model, String part, float x, float y, float z, float turnX, float turnY, float turnZ, float size);

    void clearModels(String name);
}
