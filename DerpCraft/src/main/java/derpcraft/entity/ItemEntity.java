package derpcraft.entity;

import derpcraft.item.ItemStack;
import derpcraft.world.World;

/** A dropped item stack lying in the world. */
public final class ItemEntity extends Entity {
    public ItemStack stack;
    public int pickupDelay = 10;
    public final float spin;
    /** Global "item physics" option: items settle on the ground instead of bouncing and bobbing. */
    public static boolean physics;
    /** Pickup animation: ticks left (-1 = not being collected), total length and start position. */
    public int collect = -1, collectTotal = 3;
    public double sx, sy, sz;
    /** Items are added to the inventory when the animation ends (click pickup). */
    public boolean addWhenCollected;

    /** Starts flying to the player. */
    public void startCollect(int ticks, boolean addAtEnd) {
        collect = collectTotal = ticks;
        sx = x; sy = y; sz = z;
        addWhenCollected = addAtEnd;
    }

    public ItemEntity(World w, double x, double y, double z, ItemStack stack) {
        super(w, x, y, z);
        this.stack = stack;
        this.spin = (float) (Math.random() * Math.PI * 2);
    }

    @Override public void tick() {
        if (collect >= 0) { super.tick(); if (collect > 0) collect--; return; }
        super.tick();
        if (pickupDelay > 0) pickupDelay--;
        my -= 0.04;
        move(mx, my, mz);
        float f = onGround ? (physics ? 0.45f : 0.6f * 0.98f) : 0.98f;
        mx *= f; my *= 0.98; mz *= f;
        if (onGround) my *= physics ? 0 : -0.5;
        if (age >= 6000 || y < -64) dead = true;
        // merge with nearby equal stacks every second
        if (age % 20 == 0 && !dead) {      // a despawning stack must not swallow its neighbours
            for (Entity e : world.entities) {
                if (e == this || e.dead || !(e instanceof ItemEntity o) || o.collect >= 0 || collect >= 0) continue;
                if (!o.stack.stacksWith(stack) || distSq(o.x, o.y, o.z) > 0.5 * 0.5 * 4) continue;
                int room = stack.maxStack() - stack.count;
                if (room <= 0) break;
                int n = Math.min(room, o.stack.count);
                stack.count += n;
                o.stack.count -= n;
                if (o.stack.count <= 0) o.dead = true;
            }
        }
    }
}
