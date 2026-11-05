import derpcraft.block.Blocks;
import derpcraft.game.Session;
import derpcraft.npc.Actor;
import derpcraft.npc.NpcManager;

import java.util.Locale;

/**
 * NPC combat settings in the real game: respawn time, the attack range only through sight, 'Help allies', 'Assist me'
 * for followers (also against Friendly NPCs), the player hurting Friendly NPCs, and less blood.
 * Each check prints "[smoke] CHECK <what> OK|FAIL ...".
 */
public class Scenario {
    static Session S() { return Session.current; }
    static NpcManager N() { return S().npcs; }
    static int fails, gy;
    static double px, pz;
    static Actor a, b, c, far, f, t, e;

    static void check(String what, boolean ok, String info) {
        if (!ok) fails++;
        System.out.println("[smoke] CHECK " + what + " " + (ok ? "OK" : "FAIL") + " " + info);
    }

    static Actor npc(String name, double dx, double dz, Actor.Faction fac) {
        Actor x = N().spawn(name, px + dx, gy + 1, pz + dz, 180);
        x.faction = fac; x.behavior = Actor.Behavior.STAND;
        return x;
    }

    static void build() {
        SmokeMain.at(60, () -> {
            Session s = S();
            s.game.settings.chatMessages = false;
            s.player.yaw = 0; s.player.pitch = 25;
            px = Math.floor(s.player.x) + 0.5; pz = Math.floor(s.player.z) + 0.5;
            gy = s.world.topSolidY((int) Math.floor(px), (int) Math.floor(pz));
            check("blood is less by default", s.game.settings.bloodAmount < 0.5f, "amount " + s.game.settings.bloodAmount);
            s.view = 0;
        });
        // ---- blood: five hits of 5 on an NPC in front
        SmokeMain.wait(5, () -> { t = npc("Target", 0, 4, Actor.Faction.NEUTRAL); t.maxHealth = t.health = 200; t.respawn = true; t.knockback = false; });
        for (int i = 0; i < 5; i++) SmokeMain.wait(4, () -> t.hurt(N(), 5, null, false, null, "hit"));
        SmokeMain.wait(40, () -> SmokeMain.shot("c01_blood_after_5_hits"));
        // ---- respawn time
        SmokeMain.wait(5, () -> { t.respawnSeconds = 2; t.health = 1; t.hurt(N(), 5, null, false, null, "hit"); check("dies", t.dead(), ""); });
        SmokeMain.wait(30, () -> check("still down after 1.5 s", t.dead(), ""));
        SmokeMain.wait(20, () -> { check("up after 2 s", !t.dead(), ""); N().remove(t); });
        // ---- help allies: A helps B (same faction, in range), FAR (same faction, too far) does not
        SmokeMain.wait(5, () -> {
            a = npc("A", 3, 6, Actor.Faction.NEUTRAL); a.helpAllies = true; a.aggroRange = 10;
            b = npc("B", 0, 8, Actor.Faction.NEUTRAL);
            far = npc("Far", -20, 8, Actor.Faction.NEUTRAL); far.helpAllies = true; far.aggroRange = 10;
            c = npc("C", 0, 12, Actor.Faction.AGGRESSIVE); c.maxHealth = c.health = 200;
        });
        SmokeMain.wait(5, () -> {
            b.hurt(N(), 1, c, false);
            check("Help allies: attacks the attacker", aTarget(a) == c, "target " + name(aTarget(a)));
            check("Help allies: not from too far", aTarget(far) != c, "target " + name(aTarget(far)));
        });
        SmokeMain.wait(60, () -> { check("ally fights", c.health < 200, "C hp " + c.health); for (Actor x : new Actor[]{a, b, far, c}) N().remove(x); });
        // ---- the attack range sees only what is in sight: a wall in between, then no wall
        SmokeMain.wait(5, () -> {
            S().gameMode = "survival"; S().stats.health = 20;
            e = npc("Enemy", 0, 7, Actor.Faction.AGGRESSIVE); e.aggroRange = 10;
            for (int x = -3; x <= 3; x++) for (int y = 1; y <= 4; y++) S().world.setBlock((int) Math.floor(px) + x, gy + y, (int) Math.floor(pz) + 4, Blocks.byName("stone"), 0);
        });
        SmokeMain.wait(40, () -> check("enemy behind a wall does not see you", !aTargetsPlayer(e), ""));
        SmokeMain.wait(2, () -> { for (int x = -3; x <= 3; x++) for (int y = 1; y <= 4; y++) S().world.setBlock((int) Math.floor(px) + x, gy + y, (int) Math.floor(pz) + 4, 0); });
        SmokeMain.wait(30, () -> { check("enemy in sight and range attacks", aTargetsPlayer(e), ""); N().remove(e); S().gameMode = "creative"; S().stats.health = 20; });
        // ---- Assist me: a follower attacks what you attack, even a Friendly NPC you cannot hurt
        SmokeMain.wait(5, () -> {
            f = npc("Buddy", -2, 1, Actor.Faction.NEUTRAL); f.behavior = Actor.Behavior.FOLLOW; f.assistPlayer = true;
            t = npc("Friend", 2, 5, Actor.Faction.FRIENDLY); t.maxHealth = t.health = 40;
            N().playerHurtsFriendly = false;
        });
        SmokeMain.wait(5, () -> {
            N().playerAttack(t);
            check("you cannot hurt Friendly (switch off)", t.health == 40, "hp " + t.health);
            check("Assist me: follower goes for your target", aTarget(f) == t, "target " + name(aTarget(f)));
        });
        SmokeMain.wait(80, () -> { check("follower hurts the Friendly NPC", t.health < 40, "hp " + t.health); SmokeMain.shot("c02_assist"); });
        SmokeMain.wait(5, () -> {
            f.calm(); f.assistPlayer = false; t.health = 40;
            N().playerHurtsFriendly = true;
            N().playerAttack(t);
            check("you can hurt Friendly (switch on)", t.health < 40, "hp " + t.health);
            check("without Assist me the follower stays out", aTarget(f) == null, "target " + name(aTarget(f)));
        });
        SmokeMain.wait(5, () -> { System.out.println("[smoke] fails: " + fails); System.out.println("[smoke] DONE"); System.exit(0); });
    }

    static Actor aTarget(Actor x) {
        try { var fl = Actor.class.getDeclaredField("targetActor"); fl.setAccessible(true); return (Actor) fl.get(x); } catch (Exception ex) { throw new RuntimeException(ex); }
    }

    static boolean aTargetsPlayer(Actor x) {
        try { var fl = Actor.class.getDeclaredField("targetPlayer"); fl.setAccessible(true); return fl.getBoolean(x); } catch (Exception ex) { throw new RuntimeException(ex); }
    }

    static String name(Actor x) { return x == null ? "-" : x.name; }
}
