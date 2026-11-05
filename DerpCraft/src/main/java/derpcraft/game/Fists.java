package derpcraft.game;

import derpcraft.audio.Audio;
import derpcraft.gun.FistAnimator;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Fist fighting from the first person (Alt + Q on / off): the left mouse button punches with the left hand, the right
 * mouse button with the right hand (held: one punch after another). The arms are the player's own (the same as the guns'),
 * animated by {@link FistAnimator}: a straight, then a hook when the same hand goes again soon after.
 *
 * <p>A punch hits what is under the crosshair at the moment it reaches out (a creature, an NPC; a block only gets a
 * thud and some dust): a straight does 2, a hook 3, half again when falling (a critical hit), a running punch knocks
 * the target back. While a take is being recorded the punches play but do not hurt (like other hits). In fist mode the
 * mouse does not break, place or use anything (vehicles still work: doors, getting in). Off while a gun is in the hand,
 * in a vehicle and in spectator mode. Optional sounds: game/fists/swing1..3, hit1..3 and get1..3 (fists on / off, Alt + Q) (.ogg / .wav).
 */
public final class Fists {
    private final Session s;
    public final FistAnimator anim = new FistAnimator();
    /** Fist mode on (Alt + Q). */
    private boolean on;
    /** The session lets the fists come up (the hand, an item or a gun is down out of the picture first). */
    public boolean allowed = true;
    private long lastFrameNanos;
    private int tick, lastLeft = -100, lastRight = -100;
    private final Random rnd = new Random();
    /** Damage of a straight and of a hook; how far (blocks) a punch reaches a block. */
    public static final float STRAIGHT_DAMAGE = 2, HOOK_DAMAGE = 3, BLOCK_REACH = 3;

    public Fists(Session s) { this.s = s; }

    /** Alt + Q. */
    public void toggle() {
        on = !on;
        play("get", 0.8f);                // game/fists/get1-3: the fists come up / go down
    }

    public boolean on() { return on; }

    /** Whether the fists fight now (on, no gun in the hand, on foot, not spectating, alive). */
    public boolean fighting() {
        return on && !s.guns.holding() && !s.spectator() && !s.vehicles.riding() && !s.stats.dead;
    }

    /** A click: a punch with that hand (if it is free). */
    public void press(boolean left) {
        if (!fighting() || !allowed) return;
        if (throwPunch(left)) { if (left) lastLeft = tick; else lastRight = tick; }
    }

    private boolean throwPunch(boolean left) {
        if (!anim.punch(left)) return false;
        s.swingArm();                                   // takes record the swing; the third person shows the punch
        if (s.survival()) s.stats.addExhaustion(0.05f);
        play("swing", 0.7f);
        return true;
    }

    /** One game tick: held buttons keep punching; punches that reached out hit what they reach. */
    public void tick(boolean leftHeld, boolean rightHeld) {
        tick++;
        if (!fighting()) { anim.takeImpacts(); return; }
        // a held button: the next punch as soon as that hand is free (not faster than every 6 ticks)
        if (leftHeld && tick - lastLeft >= 6 && anim.ready(true) && throwPunch(true)) lastLeft = tick;
        if (rightHeld && tick - lastRight >= 6 && anim.ready(false) && throwPunch(false)) lastRight = tick;
        int imp = anim.takeImpacts();
        if ((imp & 1) != 0) impact(true);
        if ((imp & 2) != 0) impact(false);
    }

    /** A punch is at full reach: what does it hit. */
    private void impact(boolean left) {
        var p = s.player;
        boolean hook = anim.type(left) == FistAnimator.HOOK;
        float dmg = hook ? HOOK_DAMAGE : STRAIGHT_DAMAGE;
        boolean crit = !p.onGround && p.my < 0 && !p.inWater && !p.flying;
        if (crit) dmg *= 1.5f;
        double yr = Math.toRadians(p.yaw);
        double fx = -Math.sin(yr), fz = Math.cos(yr);
        boolean recording = s.npcs != null && s.npcs.recording();
        if (s.targetMob != null) {
            if (recording) s.npcs.recordHit("mob:" + s.targetMob.type.id, dmg, s.targetMob.x(), s.targetMob.y(), s.targetMob.z());
            var m = s.targetMob;
            m.hurt(dmg, "player", true, null, null, p.x, p.z);
            // a hook knocks a little to the side, a running punch further back
            double side = hook ? (left ? 1 : -1) * 0.25 : 0, k = p.sprinting ? 0.5 : 0.15;
            m.push(fx * k + -fz * side, 0.08, fz * k + fx * side);
            if (p.sprinting) p.sprinting = false;
            landed(left);
            return;
        }
        if (s.targetActor != null) {
            if (recording) s.npcs.recordHit("npc:" + s.targetActor.id, dmg, s.targetActor.x(), s.targetActor.y(), s.targetActor.z());
            s.targetActor.hurt(s.npcs, dmg, null, true);
            landed(left);
            return;
        }
        Raycast.Hit h = s.target;
        if (h != null) {
            double ex = p.x, ey = p.y + p.eyeHeight, ez = p.z;
            double d = Math.sqrt((h.hx - ex) * (h.hx - ex) + (h.hy - ey) * (h.hy - ey) + (h.hz - ez) * (h.hz - ez));
            if (d <= BLOCK_REACH) {
                for (int i = 0; i < 4; i++) s.game.entities.spawnDigParticle(s.world, h);
                anim.hit(left, false);
                play("hit", 0.5f);
            }
        }
    }

    private void landed(boolean left) {
        anim.hit(left, true);
        play("hit", 1);
    }

    // ------------------------------------------------------------------ frames
    /** Updates the animation for this frame; the pose to draw, or null when the fists are down. */
    public FistAnimator.Pose frame(boolean paused) {
        long now = System.nanoTime();
        float dt = lastFrameNanos == 0 ? 0 : (now - lastFrameNanos) / 1e9f;
        lastFrameNanos = now;
        if (paused) dt = 0;
        var p = s.player;
        anim.active = fighting() && allowed;
        anim.sprinting = p.sprinting;
        anim.onGround = p.onGround || p.flying;
        double mx = (p.x - p.px) * 20, mz = (p.z - p.pz) * 20;
        anim.speed = (float) Math.hypot(mx, mz);
        double yr = Math.toRadians(p.yaw);
        anim.forward = (float) (-Math.sin(yr) * mx + Math.cos(yr) * mz);
        anim.strafe = (float) (-Math.cos(yr) * mx - Math.sin(yr) * mz);
        anim.vertical = (float) (p.y - p.py);
        anim.yaw = p.yaw; anim.pitch = p.pitch;
        FistAnimator.Pose pose = anim.update(dt);
        return anim.shown() ? pose : null;
    }

    /** Third person: how far the guard is up (0..1) and how far each fist is out (0..1). */
    public float guard() { return anim.shown() ? anim.pose.up : 0; }
    public float punchLeft() { return anim.shown() ? anim.pose.leftPunch : 0; }
    public float punchRight() { return anim.shown() ? anim.pose.rightPunch : 0; }

    // ------------------------------------------------------------------ sounds (optional: game/fists/swing1..3, hit1..3)
    private void play(String what, float vol) {
        List<Path> l = new ArrayList<>();
        Path dir = s.game.gameDir.resolve("fists");
        if (!Files.isDirectory(dir)) return;
        for (int i = 0; i <= 3; i++)
            for (String ext : new String[]{".ogg", ".wav"}) {
                Path f = dir.resolve(what + (i == 0 ? "" : String.valueOf(i)) + ext);
                if (Files.isRegularFile(f)) l.add(f);
            }
        if (l.isEmpty()) return;
        Audio.play(Audio.load(l.get(rnd.nextInt(l.size()))), false, vol, 0.92f + rnd.nextFloat() * 0.16f, 0);
    }
}
