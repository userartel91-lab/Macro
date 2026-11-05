package derpcraft.api;

/** A creature (mob) in the world, as mods see it. Get them from {@link GameApi#creatures()} or events. */
public interface Creature {
    /** Unique number while the world is open. */
    int id();
    /** Type id, e.g. "creatures:ghoul". */
    String type();
    double x();
    double y();
    double z();
    float yaw();
    void teleport(double x, double y, double z);
    /** Adds to the velocity (knockback, jumps). */
    void push(double dx, double dy, double dz);

    float health();
    float maxHealth();
    void setHealth(float health);
    /** Damages it like an attack (events fire, it reacts). */
    void hurt(float amount);
    void kill();
    /** Removes it without dying (no drops). */
    void remove();
    boolean alive();

    /** Name shown above it (null = none). */
    String customName();
    void setCustomName(String name);

    /** Make it attack the player / another creature; null target = calm down. */
    void attackPlayer();
    void attack(Creature target);
    void calm();
    /** Walks to a point (path finding); it goes back to its normal behaviour afterwards. */
    void walkTo(double x, double y, double z);

    /** Saved with the creature. */
    DataTag data();
    /** Persistent creatures never despawn (spawned by eggs and commands are). */
    boolean persistent();
    void setPersistent(boolean persistent);
}
