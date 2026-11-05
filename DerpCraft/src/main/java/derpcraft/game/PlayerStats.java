package derpcraft.game;

import derpcraft.block.Block;
import derpcraft.block.Blocks;
import derpcraft.world.World;

/** Survival stats with Minecraft 1.12 rules: health, hunger, saturation, exhaustion, air, experience. */
public final class PlayerStats {
    public float health = 20;
    public int food = 20;
    public float saturation = 5, exhaustion;
    private int foodTimer;
    public int air = 300;
    public int hurtTime, invulnerable, fireTicks;
    public int xpLevel, xpTotal;
    public float xpProgress;
    public boolean dead;
    public String deathMessage = "";

    public void reset() {
        health = 20; food = 20; saturation = 5; exhaustion = 0; air = 300; dead = false; fireTicks = 0; hurtTime = 0;
        xpLevel = 0; xpTotal = 0; xpProgress = 0;
    }

    public void addExhaustion(float e) { exhaustion = Math.min(40, exhaustion + e); }

    public boolean needsFood() { return food < 20; }

    public void eat(int f, float sat) {
        food = Math.min(20, food + f);
        saturation = Math.min(food, saturation + f * sat * 2);
    }

    public void heal(float h) { health = Math.min(20, health + h); }

    /** Applies damage reduced by armor. Returns true if it hurt. */
    /** No damage before this tick (getting out of a vehicle), except falling into the void / /kill. */
    public long safeUntil = Long.MIN_VALUE;

    public boolean damage(float amount, String cause, int armor, long tick) {
        if (dead || invulnerable > 0 && amount <= 0) return false;
        if (tick < safeUntil && !cause.equals("void")) return false;
        if (invulnerable > 10) return false;
        derpcraft.game.Session ses = derpcraft.game.Session.current;
        if (ses != null && derpcraft.api.EventBus.has(derpcraft.api.Events.PlayerDamage.class)) {
            var ev = derpcraft.api.EventBus.post(ses, new derpcraft.api.Events.PlayerDamage(amount, cause));
            if (ev.isCancelled() || ev.amount <= 0) return false;
            amount = ev.amount;
        }
        float reduced = amount * (1 - Math.min(20, armor) / 25f);
        if (cause.equals("starve") || cause.equals("drown") || cause.equals("void")) reduced = amount;
        health -= reduced;
        if (ses != null) Blood.body(ses.player.x, ses.player.y, ses.player.z, ses.player.height, reduced, cause);
        invulnerable = 20;
        hurtTime = 10;
        switch (cause) {
            case "fall" -> derpcraft.audio.Sfx.play(amount > 6 ? "damage/fallbig" : "damage/fallsmall", 1f, 1f);
            case "drown" -> derpcraft.audio.Sfx.play("entity/player/hurt/drown", 1f, 1f);
            case "fire", "lava" -> derpcraft.audio.Sfx.play("entity/player/hurt/fire_hurt", 1f, 1f);
            default -> derpcraft.audio.Sfx.play("damage/hit", 1f, 0.9f + (float) Math.random() * 0.2f);
        }
        addExhaustion(0.1f);
        if (health <= 0) {
            health = 0;
            dead = true;
            deathMessage = switch (cause) {
                case "fall" -> "Player hit the ground too hard";
                case "lava" -> "Player tried to swim in lava";
                case "fire" -> "Player burned to death";
                case "drown" -> "Player drowned";
                case "starve" -> "Player starved to death";
                case "cactus" -> "Player was pricked to death";
                case "void" -> "Player fell out of the world";
                case "suffocate" -> "Player suffocated in a wall";
                case "mob" -> "Player was slain";
                case "explosion" -> "Player blew up";
                case "vehicle" -> "Player died in a car crash";
                case "projectile" -> "Player was shot";
                case "npc" -> "Player was killed by an NPC";
                default -> "Player died";
            };
            if (ses != null && derpcraft.api.EventBus.has(derpcraft.api.Events.PlayerDeath.class))
                deathMessage = derpcraft.api.EventBus.post(ses, new derpcraft.api.Events.PlayerDeath(cause, deathMessage)).message;
        }
        return true;
    }

    public void addXp(int amount) {
        xpTotal += amount;
        xpProgress += amount / (float) xpForLevel(xpLevel);
        while (xpProgress >= 1) {
            xpProgress = (xpProgress - 1) * xpForLevel(xpLevel);
            xpLevel++;
            xpProgress /= xpForLevel(xpLevel);
        }
    }

    public static int xpForLevel(int level) {
        return level >= 30 ? 112 + (level - 30) * 9 : (level >= 15 ? 37 + (level - 15) * 5 : 7 + level * 2);
    }

    /** One survival tick: food, regeneration, drowning, lava/fire/cactus damage. */
    public void tick(World w, Player p, int armor, long tick, boolean survival) {
        if (hurtTime > 0) hurtTime--;
        if (invulnerable > 0) invulnerable--;
        if (!survival || dead) { air = 300; fireTicks = 0; return; }

        // hunger (normal difficulty)
        if (exhaustion > 4) {
            exhaustion -= 4;
            if (saturation > 0) saturation = Math.max(0, saturation - 1);
            else food = Math.max(0, food - 1);
        }
        foodTimer++;
        if (food >= 18 && health < 20) {
            if (foodTimer >= 80) { heal(1); addExhaustion(6); foodTimer = 0; }
        } else if (food <= 0) {
            if (foodTimer >= 80) { if (health > 1) damage(1, "starve", armor, tick); foodTimer = 0; }
        } else foodTimer = 0;

        // environment
        int bx = (int) Math.floor(p.x), bz = (int) Math.floor(p.z);
        int feet = (int) Math.floor(p.y), head = (int) Math.floor(p.y + p.eyeHeight);
        Block headBlock = w.getBlock(bx, head, bz);
        if (headBlock == Blocks.WATER) {
            if (--air <= -20) { air = 0; damage(2, "drown", armor, tick); }
        } else air = Math.min(300, air + 4);
        if (headBlock.isOpaqueCube() && !p.noclip) damage(1, "suffocate", armor, tick);

        boolean inLava = false, inFire = false, cactus = false;
        double h = Player.WIDTH / 2;
        for (int x = (int) Math.floor(p.x - h - 0.01); x <= (int) Math.floor(p.x + h + 0.01); x++)
            for (int z = (int) Math.floor(p.z - h - 0.01); z <= (int) Math.floor(p.z + h + 0.01); z++)
                for (int y = feet; y <= head; y++) {
                    Block b = w.getBlock(x, y, z);
                    if (b == Blocks.LAVA) inLava = true;
                    else if (b.name.equals("fire")) inFire = true;
                    else if (b == Blocks.CACTUS) cactus = true;
                }
        if (w.getBlock(bx, feet - 1, bz) == Blocks.byName("magma_block") && !p.sneaking && p.onGround) damage(1, "fire", armor, tick);
        if (inLava) { damage(4, "lava", armor, tick); fireTicks = 300; }
        else if (inFire) { damage(1, "fire", armor, tick); fireTicks = Math.max(fireTicks, 160); }
        if (cactus) damage(1, "cactus", armor, tick);
        if (fireTicks > 0) {
            if (p.inWater) fireTicks = 0;
            else { fireTicks--; if (fireTicks % 20 == 0 && !inLava) damage(1, "fire", armor, tick); }
        }
        if (p.y < -64) damage(4, "void", armor, tick);
    }
}
