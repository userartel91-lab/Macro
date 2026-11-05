package derpcraft.gun;

import derpcraft.audio.Audio;
import derpcraft.game.Player;
import derpcraft.game.Raycast;
import derpcraft.game.Session;
import derpcraft.item.Item;
import derpcraft.item.ItemStack;

import java.nio.file.Path;
import java.util.List;
import java.util.Random;

/**
 * The gun in the player's hand: trigger, aiming, reloading, shots (hit-scan against blocks, creatures and NPCs),
 * recoil, melee (butt strikes / pistol whip: {@link #meleeKey()}), sounds, and the first person animation. Rounds left are kept in the gun item (tag "ammo"); in survival a
 * reload uses up one magazine item of the gun.
 */
public final class GunController {
    private final Session s;
    public final GunAnimator anim = new GunAnimator();
    private final Random rnd = new Random();

    private ItemStack lastStack;
    private int lastSlot = -1;
    private long tick;
    private double nextShot;
    private boolean triggerWas, aiming, aim2;
    private boolean reloading, reloadEmpty;
    private long reloadStart;
    private int reloadTicks, reloadStage;
    private float bloom;
    private int sprintLock;
    private long shotNanos;
    private float recover, hitMarker, headMarker;
    private long lastFrameNanos;

    public GunController(Session s) { this.s = s; }

    /** The gun kind in the hand (its model loads), or null. */
    public GunType held() {
        GunType t = Guns.of(s.heldStack());
        return t != null && t.model() != null ? t : null;
    }

    public boolean holding() { return held() != null; }

    public static int ammo(ItemStack st, GunType t) { return st == null ? 0 : st.hasTag() && st.tag().has("ammo") ? st.tag().getInt("ammo", t.magazine) : t.magazine; }

    private static void setAmmo(ItemStack st, int n) { st.tag().put("ammo", n); }

    public boolean reloading() { return reloading; }

    // ------------------------------------------------------------------ input
    /** Walking with a gun: slower, slower still through the sights, running costs a little more; aiming or firing stops sprinting. */
    public void adjustInput(Player.Input in, boolean triggerDown, boolean aimDown) {
        GunType t = held();
        if (t == null) return;
        boolean aim = aimDown && !reloading;
        if (aim || triggerDown && !reloading) in.sprint = false;
        float f = aim ? t.aimMoveSpeed : t.moveSpeed;
        if (in.sprint && in.forward > 0) f *= t.sprintSpeed;
        in.forward *= f;
        in.strafe *= f;
    }

    /** Reload key. */
    public void reloadKey() { GunType t = held(); if (t != null) startReload(t, s.heldStack()); }

    /** Mouse look is slower through the sights (the view is zoomed). */
    public float sensitivity() { return holding() ? 1 - (1 - 1 / heldZoom()) * anim.aimAmount() : 1; }

    /** Zoom through the sights: the first aim's, blending to the second aim's while Alt is held. */
    private float heldZoom() { GunType t = held(); return t == null ? 1 : t.aimZoom + (t.aim2Zoom - t.aimZoom) * anim.aim2Amount(); }

    /** Field of view multiplier (aiming zooms in). */
    public float fovFactor() { return holding() ? 1 / (1 + (heldZoom() - 1) * anim.aimAmount()) : 1; }

    // ------------------------------------------------------------------ ticks
    /** One game tick while the player holds a gun ('active' = no menu, alive, the camera on the player). */
    public void tick(boolean trigger, boolean aimDown, boolean active) {
        tick++;
        if (hitMarker > 0) hitMarker -= 0.05f;
        if (headMarker > 0) headMarker -= 0.04f;
        ItemStack st = s.heldStack();
        checkSwitch(trigger);
        GunType t = held();
        if (t == null) { aiming = false; return; }
        if (!active) { trigger = false; aimDown = false; }
        aiming = (aimDown || previewAim) && !reloading && !s.player.sprinting && meleeKind < 0;
        // the second aim: Alt held while aiming (or held first, then aim)
        aim2 = active && derpcraft.game.Keys.AIM2.down(s.game.window);
        // the aim sound: aim1 on going into the first aim (right mouse button), aim2 into the second (Alt + right mouse button)
        int aimKind = aiming ? (aim2 ? 2 : 1) : 0;
        if (aimKind != 0 && aimKind != lastAimKind && (lastAimKind == 0 || aimKind == 2)) aimSound(t, aimKind);
        lastAimKind = aimKind;
        bloom = Math.max(0, bloom - 0.25f);
        if (s.player.sprinting) sprintLock = 5;          // sprint-out time before the gun can fire
        else if (sprintLock > 0) sprintLock--;
        // reload in progress
        if (reloading) {
            float r = previewReload ? previewReloadProgress(t) : (tick - reloadStart) / (float) reloadTicks;
            float q = reloadEmpty ? r / GunAnimator.EMPTY_MAG_END : r;
            float boltAt = GunAnimator.EMPTY_MAG_END + (1 - GunAnimator.EMPTY_MAG_END) * GunAnimator.BOLT_RELEASE;
            if (reloadStage == 0 && q >= GunAnimator.MAG_OUT) { reloadStage = 1; if (!playVariant(t, "unattach", 0.9f)) play(t, "mag_out", 0.9f); }
            if (!magDropped && q >= GunAnimator.MAG_DROP) { magDropped = true; dropMagazine(t); }
            if (reloadStage == 1 && q >= GunAnimator.MAG_IN) { reloadStage = 2; if (!playVariant(t, "attach", 0.9f)) play(t, "mag_in", 0.9f); anim.seated(); }
            // the bolt: slide1 when it starts to move back, slide2 when it is let go (else the single "bolt" sound)
            float boltFrom = GunAnimator.EMPTY_MAG_END + (1 - GunAnimator.EMPTY_MAG_END) * 0.28f;
            if (reloadStage == 2 && reloadEmpty && r >= boltFrom) { reloadStage = 3; playSlide(t, 0); }
            if (reloadStage == 3 && reloadEmpty && r >= boltAt) { reloadStage = 4; if (!playSlide(t, 1)) play(t, "bolt", 0.9f); }
            if (r >= 1) { if (previewReload) { reloading = false; previewReload = false; } else finishReload(t, st); }
            triggerWas = trigger;
            return;
        }
        if (meleeKind >= 0) {                            // striking: no shots until the gun is back
            triggerWas = trigger;
            nextShot = Math.max(nextShot, tick);
            return;
        }
        if (previewShots > 0) {                          // gun editor: a burst without bullets
            if (tick >= nextShot) { previewShot(t); previewShots--; nextShot = tick + Math.max(1, 1200.0 / t.rpm / previewSpeed); }
            return;
        }
        boolean pressed = trigger && !triggerWas;
        triggerWas = trigger;
        if (!trigger) { nextShot = Math.max(nextShot, tick); return; }
        if (sprintLock > 0) return;
        int ammo = ammo(st, t);
        if (ammo <= 0) {
            if (pressed) { play(t, "dry", 0.8f); startReload(t, st); }
            return;
        }
        if (!t.auto && !pressed) return;
        double interval = 1200.0 / t.rpm;                // ticks per shot
        if (nextShot < tick - 1) nextShot = tick;
        while (nextShot <= tick && ammo > 0) {
            shoot(t, st);
            ammo--;
            nextShot += interval;
            if (!t.auto) break;
        }
    }

    /** The gun type in the hand when the item last changed (null = not a gun). */
    private GunType lastType;
    /** The gun being put away (it goes down out of the picture before the next item comes up), or null; for how long. */
    private GunType holsterType;
    private float holsterTime;
    /** How long putting a gun away takes (seconds). */
    static final float HOLSTER = 0.2f;

    /**
     * Another item in the hand (checked every tick and every frame, so the new gun is never drawn for a frame in the old
     * one's pose): a reload or strike stops; the old gun goes down; a new gun comes up from below (after the old one).
     */
    private void checkSwitch(boolean trigger) {
        ItemStack st = s.heldStack();
        if (st == lastStack && s.selected() == lastSlot) return;
        lastStack = st; lastSlot = s.selected();
        GunType t = held();
        reloading = false;
        meleeKind = -1; meleeQueued = false;
        triggerWas = trigger;
        if (lastType != null && t != lastType && holsterType == null) { holsterType = lastType; holsterTime = 0; getSound(lastType, 0.6f); }
        lastType = t;
        if (t != null) { anim.equip(); getSound(t, 0.8f); nextShot = tick + 6; }
    }

    /** Whether a gun is still going down after it was put away. */
    public boolean holstering() { return holsterType != null; }

    private void startReload(GunType t, ItemStack st) {
        if (reloading || st == null || ammo(st, t) >= t.magazine) return;
        if (!s.creative() && magSlot(t) < 0) return;
        reloading = true;
        reloadEmpty = ammo(st, t) == 0;
        reloadStart = tick;
        reloadTicks = Math.max(4, Math.round((reloadEmpty ? t.reloadEmptyTime : t.reloadTime) * 20));
        reloadStage = 0;
        magDropped = false;
        play(t, "reload", 0.9f);
        if (s.npcs.recording()) s.npcs.recordItem(4, t.name, reloadEmpty ? 1 : 0, 0, 0, 0);
    }

    private void finishReload(GunType t, ItemStack st) {
        reloading = false;
        if (!s.creative()) {
            int slot = magSlot(t);
            if (slot < 0) return;                         // the magazine was dropped meanwhile
            ItemStack m = s.inv.get(slot);
            m.count--;
            s.inv.set(slot, m.count <= 0 ? null : m);
        }
        setAmmo(st, t.magazine);
        s.inv.changed();
    }

    /** Inventory slot with a magazine of this gun, or -1: the pouches first (Left Alt + E), then the inventory. */
    private int magSlot(GunType t) {
        Item mi = Guns.magItem(t);
        if (mi == null) return -1;
        int p0 = derpcraft.game.PlayerInventory.POUCH;
        for (int i = p0; i < p0 + derpcraft.game.PlayerInventory.POUCHES; i++) {
            ItemStack m = s.inv.get(i);
            if (m != null && m.item == mi) return i;
        }
        for (int i = 0; i < derpcraft.game.PlayerInventory.MAIN; i++) {
            ItemStack m = s.inv.get(i);
            if (m != null && m.item == mi) return i;
        }
        return -1;
    }

    // ------------------------------------------------------------------ shooting
    private void shoot(GunType t, ItemStack st) {
        setAmmo(st, ammo(st, t) - 1);
        var p = s.player;
        double ex = p.x, ey = p.y + p.eyeHeight, ez = p.z;
        float a = anim.aimAmount();
        double moving = Math.min(1, Math.hypot(p.x - p.px, p.z - p.pz) * 20 / 4.3);
        float spread = t.spreadHip + (t.spreadAim - t.spreadHip) * a + bloom * (1 - 0.6f * a) + (float) moving * t.spreadMove * (1 - 0.8f * a);
        if (!p.onGround && !p.flying) spread += 2;
        double r = spread * Math.sqrt(rnd.nextDouble()), ang = rnd.nextDouble() * Math.PI * 2;
        double yaw = Math.toRadians(p.yaw + r * Math.cos(ang)), pitch = Math.toRadians(p.pitch + r * Math.sin(ang));
        double dx = -Math.sin(yaw) * Math.cos(pitch), dy = -Math.sin(pitch), dz = Math.cos(yaw) * Math.cos(pitch);
        // the muzzle in the world (smoke, the flash light, where a tracer starts): the first person flash point
        double[] mw = muzzleWorld(ex, ey, ez, p.yaw, p.pitch, a);
        double mx = mw[0], my = mw[1], mz = mw[2];
        muzzleX = mx; muzzleY = my; muzzleZ = mz;
        // the bullet: what it hits first (a block, a creature, an NPC), its effects and the tracer
        Ballistics.Result res = Ballistics.fire(s, t, null, ex, ey, ez, dx, dy, dz, mx, my, mz, true, true);
        if (res.body()) hitMarker = 1;
        if (res.headshot()) { headMarker = 1; play(t, "headshot", 0.9f); }
        if (s.npcs.recording()) s.npcs.recordItem(3, t.name, 0, dx, dy, dz);       // the take replays this shot
        kickNanos = System.nanoTime();
        shotNanos = System.nanoTime();
        // recoil: the view climbs and wanders sideways; part of the climb comes back after the burst
        float up = t.recoilUp * (1 - 0.35f * a) * (0.85f + rnd.nextFloat() * 0.3f);
        p.pitch = Math.max(-90, p.pitch - up);
        p.yaw += (rnd.nextFloat() - 0.45f) * 2 * t.recoilSide * (1 - 0.3f * a);
        recover += up * 0.6f;
        bloom = Math.min(8, bloom + t.spreadShot);
        anim.shot(a > 0.5f, t);
        List<Path> sh = t.shootSounds;
        if (!sh.isEmpty()) Audio.play(Audio.load(sh.get(rnd.nextInt(sh.size()))), false, 0.9f * t.volume, 0.96f + rnd.nextFloat() * 0.08f, 0);
    }

    private double muzzleX, muzzleY, muzzleZ;
    /** The world camera of the last frame: the inverse of its view (rotation, roll, bob) and its position. */
    private derpcraft.util.Mat4 camInv;
    private double camX, camY, camZ;

    /** Set by the session every frame: the view matrix the world was drawn with and the camera position. */
    public void setCamera(derpcraft.util.Mat4 view, double x, double y, double z) {
        camInv = view.inverted();
        camX = x; camY = y; camZ = z;
    }
    /** The world's field of view this frame (degrees, set by the session): the first person gun has its own. */
    public float worldFov = 70;

    /**
     * Where the muzzle flash of the first person gun is in the world: the flash point of the last animation frame (view
     * space of the gun's own projection), moved so it lands on the same spot of the screen with the world's field of view,
     * then turned with the camera. In other views: in front of the eye, a little right and down.
     */
    private double[] muzzleWorld(double ex, double ey, double ez, float yawDeg, float pitchDeg, float aim) {
        // third person / a detached camera: the muzzle of the gun as drawn in the player's hands
        double[] tp = s.game.entities.player != null ? s.game.entities.playerMuzzle : null;
        if (tp != null) return tp.clone();
        GunAnimator.Pose ps0 = anim.pose;
        if (s.view == 0 && camInv != null && ps0.type != null && ps0.muzzle[2] < -0.01f) {
            // first person: the flash point on the screen, put into the world with the camera of the last frame exactly as
            // it was drawn (walking bob, roll, shake and the smooth movement between ticks included)
            float gunFov = s.game.entities.gunFov - 14 * ps0.aim;
            double k = Math.tan(Math.toRadians(worldFov) / 2) / Math.tan(Math.toRadians(gunFov) / 2);
            float[] o = new float[4];
            camInv.transform((float) (ps0.muzzle[0] * k), (float) (ps0.muzzle[1] * k), ps0.muzzle[2], o);
            return new double[]{camX + o[0], camY + o[1], camZ + o[2]};
        }
        double yaw = Math.toRadians(yawDeg), pitch = Math.toRadians(pitchDeg);
        double fx = -Math.sin(yaw) * Math.cos(pitch), fy = -Math.sin(pitch), fz = Math.cos(yaw) * Math.cos(pitch);
        double rx = -Math.cos(yaw), ry = 0, rz = -Math.sin(yaw);
        double ux = ry * fz - rz * fy, uy = rz * fx - rx * fz, uz = rx * fy - ry * fx;     // up = right x forward
        GunAnimator.Pose ps = anim.pose;
        if (s.view == 0 && ps.type != null && ps.muzzle[2] < -0.01f) {
            float gunFov = s.game.entities.gunFov - 14 * ps.aim;
            double k = Math.tan(Math.toRadians(worldFov) / 2) / Math.tan(Math.toRadians(gunFov) / 2);
            double x = ps.muzzle[0] * k, y = ps.muzzle[1] * k, z = -ps.muzzle[2];
            return new double[]{ex + rx * x + ux * y + fx * z, ey + ry * x + uy * y + fy * z, ez + rz * x + uz * y + fz * z};
        }
        double right = 0.2 * (1 - aim), down = 0.17 * (1 - aim) + 0.06;
        return new double[]{ex + fx * 0.9 + rx * right, ey + fy * 0.9 - down, ez + fz * 0.9 + rz * right};
    }

    /** A glowing tracer from the muzzle to where the bullet stops (the gun's colour, speed, length, width, brightness). */
    private void tracer(GunType t, double mx, double my, double mz, double hx, double hy, double hz) {
        float[] c = t.tracerColor;
        s.game.entities.tracer(mx, my, mz, hx, hy, hz, t.tracerSpeed, t.tracerLength, t.tracerWidth, c[0], c[1], c[2], t.tracerGlow);
    }

    /** A shot's flash lights up the surroundings for a moment: position and strength (0..1), or null. */
    public double[] flashLight() {
        float age = (System.nanoTime() - shotNanos) / 1e9f;
        if (age > 0.06f || shotNanos == 0) return null;
        return new double[]{muzzleX, muzzleY, muzzleZ, 1 - age / 0.06f};
    }

    /** The gun was put on the back (Left Alt + F): its equip sound, quieter. */
    public void backSound(GunType t) {
        if (t != null && !playShared(t, "back", 5, 0.7f)) play(t, "equip", 0.55f);
    }

    private void play(GunType t, String ev, float vol) {
        Path p = t.sounds.get(ev);
        if (p != null) Audio.play(Audio.load(p), false, vol * t.volume, 1, 0);
    }

    /** A random numbered variant from the gun's own folder (unattach1-5, attach1-5); false when it has none. */
    private boolean playVariant(GunType t, String group, float vol) {
        java.util.List<Path> l = t.variants.get(group);
        if (l == null || l.isEmpty()) return false;
        Audio.play(Audio.load(l.get(rnd.nextInt(l.size()))), false, vol * t.volume, 1, 0);
        return true;
    }

    /** slide1 (index 0, the bolt starts) / slide2 (index 1, it ends) of the gun; false when that file is missing. */
    private boolean playSlide(GunType t, int index) {
        java.util.List<Path> l = t.variants.get("slide");
        if (l == null) return false;
        String want = "slide" + (index + 1) + ".";
        for (Path p : l)
            if (p.getFileName().toString().startsWith(want)) { Audio.play(Audio.load(p), false, 0.9f * t.volume, 1, 0); return true; }
        return false;
    }

    /** A random sound from game/guns/sfx/ named base1..baseN (.ogg / .wav; attack: game/guns/sfx/attack/). False when none exists. */
    private boolean playShared(GunType t, String base, int n, float vol) {
        Path dir = Guns.dir();
        if (dir == null) return false;
        dir = dir.resolve("sfx");
        if (base.equals("attack")) dir = dir.resolve("attack");
        java.util.List<Path> l = new java.util.ArrayList<>();
        for (int i = 1; i <= n; i++)
            for (String ext : new String[]{".ogg", ".wav"}) {
                Path f = dir.resolve(base + i + ext);
                if (java.nio.file.Files.isRegularFile(f)) { l.add(f); break; }
            }
        if (l.isEmpty()) return false;
        Audio.play(Audio.load(l.get(rnd.nextInt(l.size()))), false, vol * (t == null ? 1 : t.volume), 1, 0);
        return true;
    }

    private int lastAimKind;

    /** game/guns/sfx/aim1.ogg (first aim) or aim2.ogg (second aim); else the gun's own "aim" sound. */
    private void aimSound(GunType t, int kind) {
        Path dir = Guns.dir();
        if (dir != null)
            for (String ext : new String[]{".ogg", ".wav"}) {
                Path f = dir.resolve("sfx").resolve("aim" + kind + ext);
                if (java.nio.file.Files.isRegularFile(f)) { Audio.play(Audio.load(f), false, 0.8f * t.volume, 1, 0); return; }
            }
        play(t, "aim", 0.7f);
    }

    /** Taking the gun out / putting it away: game/guns/sfx/get1-5, else the gun's own equip sound. */
    private void getSound(GunType t, float vol) {
        if (!playShared(t, "get", 5, vol)) play(t, "equip", vol);
    }

    // ------------------------------------------------------------------ melee
    /** The strike playing (GunType.MELEE index, -1 = none), its clock (seconds), the last kind and the time since it ended. */
    private int meleeKind = -1, lastMelee = -1;
    private float meleeClock, sinceMelee = 99, hitStop;
    private boolean meleeImpacted, meleeQueued, meleePreview;

    /** Whether a melee strike is playing. */
    public boolean meleeing() { return meleeKind >= 0; }

    /**
     * Melee key: a strike with the gun. Pressed again soon after one (within the gun's combo window, or already on its way
     * back) the next kind follows: jab -> swing -> smash -> jab... Not while reloading.
     */
    public void meleeKey() {
        GunType t = held();
        if (t == null || reloading || s.spectator()) return;
        if (meleeKind >= 0) {
            // on the way back already: the next strike follows right away
            float[] m = t.meleeTime[meleeKind];
            if (meleeClock >= GunAnimator.meleeImpact(t, meleeKind) + m[2] * 0.5f) meleeQueued = true;
            return;
        }
        int kind = lastMelee >= 0 && sinceMelee <= t.meleeCombo ? (lastMelee + 1) % GunType.MELEE.length : 0;
        startMelee(t, kind, false);
    }

    private void startMelee(GunType t, int kind, boolean preview) {
        meleeKind = kind;
        meleeClock = 0;
        hitStop = 0;
        meleeImpacted = false;
        meleeQueued = false;
        meleePreview = preview;
        if (!preview) {
            s.player.sprinting = false;
            if (s.survival()) s.stats.addExhaustion(0.1f);
            if (s.npcs.recording()) {
                var p = s.player;
                double yaw = Math.toRadians(p.yaw), pitch = Math.toRadians(p.pitch);
                s.npcs.recordItem(5, t.name, kind, -Math.sin(yaw) * Math.cos(pitch), -Math.sin(pitch), Math.cos(yaw) * Math.cos(pitch));
            }
        }
        if (!playShared(t, "attack", 5, 0.8f)) meleeSound(t, "melee", "swing", 0.8f);
    }

    /** The strike's clock: the hit at its impact moment, a short hit stop when it lands, the next one of a combo. */
    private void meleeFrame(GunType t, float dt) {
        if (meleeKind < 0) { sinceMelee += dt; return; }
        if (hitStop > 0) { hitStop -= dt; return; }
        meleeClock += dt;
        if (!meleeImpacted && meleeClock >= GunAnimator.meleeImpact(t, meleeKind)) {
            meleeImpacted = true;
            boolean landed = !meleePreview && meleeImpact(t, meleeKind);
            anim.meleeHit(landed);
            if (landed) hitStop = 0.06f;
        }
        if (meleeClock >= GunAnimator.meleeLength(t, meleeKind)) {
            lastMelee = meleeKind;
            meleeKind = -1;
            sinceMelee = 0;
            if (meleeQueued) {
                boolean pv = meleePreview;
                startMelee(t, (lastMelee + 1) % GunType.MELEE.length, pv);
                if (pv && previewCombo > 1) { previewCombo--; meleeQueued = true; }      // the editor's combo: all three
            }
        }
    }

    /** Damage of the butt strike: what is under the crosshair within reach. Returns whether it struck something. */
    private boolean meleeImpact(GunType t, int kind) {
        var p = s.player;
        double ex = p.x, ey = p.y + p.eyeHeight, ez = p.z;
        double yaw = Math.toRadians(p.yaw), pitch = Math.toRadians(p.pitch);
        double dx = -Math.sin(yaw) * Math.cos(pitch), dy = -Math.sin(pitch), dz = Math.cos(yaw) * Math.cos(pitch);
        double reach = t.meleeReach;
        Raycast.Hit h = Raycast.cast(s.world, ex, ey, ez, dx, dy, dz, reach, false);
        double best = h == null ? reach : Math.sqrt((h.hx - ex) * (h.hx - ex) + (h.hy - ey) * (h.hy - ey) + (h.hz - ez) * (h.hz - ez));
        derpcraft.entity.Mob mob = s.mobs.pick(ex, ey, ez, dx, dy, dz, best);
        if (mob != null) { double tm = mob.rayHit(ex, ey, ez, dx, dy, dz, best); if (tm >= 0) best = tm; else mob = null; }
        derpcraft.npc.Actor npc = s.npcs.pick(ex, ey, ez, dx, dy, dz, best);
        if (npc != null) { double tn = npc.rayHit(ex, ey, ez, dx, dy, dz, best); if (tn < 0 || tn > best) npc = null; else mob = null; }
        float dmg = t.meleeDamage[kind];
        if (!p.onGround && p.my < 0 && !p.inWater && !p.flying) dmg *= 1.5f;      // a critical hit when falling
        boolean recording = s.npcs.recording();                                   // a take: the strike plays but does not hurt
        if (mob != null) {
            if (!recording) {
                mob.hurt(dmg, "player", true, null, null, p.x, p.z);
                double k = t.meleeKnockback;
                mob.push(dx * k, 0.1 * Math.min(1, k * 2), dz * k);
            }
            hitMarker = 1;
            meleeSound(t, "melee_hit", "hit", 1);
            return true;
        }
        if (npc != null) {
            if (!recording) npc.hurt(s.npcs, dmg, null, true);
            hitMarker = 1;
            meleeSound(t, "melee_hit", "hit", 1);
            return true;
        }
        if (h == null) return false;
        var fx = s.game.entities;
        for (int i = 0; i < 6; i++) fx.spawnDigParticle(s.world, h);
        String n = h.block.name;
        s.npcs.playerActing = true;              // a take records what the strike breaks (it plays on the take's tick)
        try {
            if ((n.equals("glass") || n.endsWith("_glass") || n.contains("glass_pane")) && !n.contains("bottle")) {
                fx.spawnBreakParticles(s.world, h.x, h.y, h.z, h.block, h.meta);
                s.world.setBlock(h.x, h.y, h.z, 0);
            } else if (h.block instanceof derpcraft.block.FunctionalBlocks.DoorBlock door && !door.iron) {
                Ballistics.shootDoor(s, t, door, h, false, MELEE_DOOR_DAMAGE);
            }
        } finally { s.npcs.playerActing = false; }
        meleeSound(t, "melee_hit", "hit", 0.6f);
        return true;
    }

    /** Damage of a butt strike on a wooden door (Ballistics.DOOR_HEALTH breaks it: three strikes). */
    public static final float MELEE_DOOR_DAMAGE = 10;

    /** A melee sound: the gun's own (melee / melee_hit in its folder), else the fists' (game/fists/swing*, hit*), else none. */
    private void meleeSound(GunType t, String ev, String fistName, float vol) {
        Path p = t.sounds.get(ev);
        if (p == null) {
            Path dir = s.game.gameDir.resolve("fists");
            java.util.List<Path> l = new java.util.ArrayList<>();
            for (int i = 0; i <= 3; i++)
                for (String ext : new String[]{".ogg", ".wav"}) {
                    Path f = dir.resolve(fistName + (i == 0 ? "" : String.valueOf(i)) + ext);
                    if (java.nio.file.Files.isRegularFile(f)) l.add(f);
                }
            if (!l.isEmpty()) p = l.get(rnd.nextInt(l.size()));
        }
        if (p != null) Audio.play(Audio.load(p), false, vol * t.volume, 0.92f + rnd.nextFloat() * 0.16f, 0);
    }

    /** Gun editor: plays a strike of one kind (animation and sound only); 'combo' = all kinds one after another. */
    public void previewMelee(int kind, boolean combo) {
        GunType t = held();
        if (t == null || reloading || meleeKind >= 0) return;
        startMelee(t, Math.max(0, Math.min(GunType.MELEE.length - 1, kind)), true);
        previewCombo = combo ? GunType.MELEE.length - 1 : 0;
        if (combo) meleeQueued = true;
    }

    private int previewCombo;

    /** Third person: the strike playing (kind, -1 = none) and its clock (seconds). */
    public int thirdPersonMelee() { return meleeKind; }
    public float thirdPersonMeleeTime() { return meleeClock; }

    // ------------------------------------------------------------------ frames
    /** Updates the animation for this frame; returns the pose to draw (null when no gun is held). */
    public GunAnimator.Pose frame(float partial, boolean paused) {
        long now = System.nanoTime();
        float dt = lastFrameNanos == 0 ? 0 : (now - lastFrameNanos) / 1e9f;
        lastFrameNanos = now;
        if (paused) dt = 0;
        checkSwitch(triggerWas);
        if (holsterType != null) {
            // the old gun goes down out of the picture; the next item (a new gun too) comes up after it
            holsterTime += dt;
            if (holsterTime < HOLSTER) {
                anim.aiming = false; anim.sprinting = false; anim.reloading = false; anim.melee = -1; anim.boltHold = -1;
                anim.lower = holsterTime / HOLSTER;
                anim.yaw = s.player.yaw; anim.pitch = s.player.pitch;
                GunAnimator.Pose hp = anim.update(holsterType, dt);
                hp.marker = null; hp.marker2 = null;
                return hp;
            }
            holsterType = null;
            if (held() != null) anim.equip();
        }
        GunType t = held();
        if (t == null) return null;
        if (previewReload && reloading) previewReloadTime += dt * previewSpeed;     // editor reloads: in seconds, slow motion too
        dt *= previewSpeed;
        meleeFrame(t, dt);
        var p = s.player;
        // the climb from recoil comes back while not firing
        if (recover > 0 && (now - shotNanos) / 1e9f > 0.12f) {
            float d = Math.min(recover, dt * 10);
            recover -= d;
            p.pitch = Math.min(90, p.pitch + d);
        }
        anim.aiming = aiming || previewAim && !reloading;
        anim.aim2 = aim2;
        boolean riding = s.vehicles != null && s.vehicles.riding();          // in a vehicle the gun is held still: no sprint, no walking sway
        anim.sprinting = !riding && p.sprinting || previewSprint;
        anim.reloading = reloading;
        anim.reloadEmpty = reloadEmpty;
        anim.reload = !reloading ? 0 : previewReload ? Math.min(1, previewReloadProgress(t)) : Math.min(1, (tick - reloadStart + partial) / (float) reloadTicks);
        // slide lock: an empty magazine keeps the slide back
        ItemStack held = s.heldStack();
        anim.boltLocked = t.slideLock && !reloading && previewShots == 0 && held != null && ammo(held, t) == 0;
        anim.boltHold = previewBolt;
        double mx = (p.x - p.px) * 20, mz = (p.z - p.pz) * 20;
        anim.speed = (float) Math.hypot(mx, mz);
        double yr = Math.toRadians(p.yaw);
        // view terms: forward = (-sin yaw, cos yaw), right = (-cos yaw, -sin yaw)
        anim.forward = (float) (-Math.sin(yr) * mx + Math.cos(yr) * mz);
        anim.strafe = (float) (-Math.cos(yr) * mx - Math.sin(yr) * mz);
        if (previewWalk || previewSprint) { anim.speed = previewSprint ? 5.6f : 4.3f; anim.forward = anim.speed; anim.strafe = 0; }
        anim.vertical = (float) (p.y - p.py);
        anim.onGround = p.onGround || p.flying;
        if (riding && !previewWalk && !previewSprint) { anim.speed = anim.forward = anim.strafe = anim.vertical = 0; anim.onGround = true; }
        // gun editor: hold one pose on screen while it is tuned
        if (previewPose != null && !reloading) switch (previewPose) {
            case "aim" -> { anim.aiming = true; anim.aim2 = false; anim.sprinting = false; }
            case "aim2" -> { anim.aiming = true; anim.aim2 = true; anim.sprinting = false; }
            case "sprint" -> { anim.aiming = false; anim.sprinting = true; anim.speed = 0; anim.forward = 0; }   // still: easier to tune
            case "reload" -> { anim.aiming = false; anim.sprinting = false; anim.reloading = true; anim.reloadEmpty = false;
                anim.reload = previewAt >= 0 ? previewAt : 0.2f; }
            case "bolt" -> { anim.aiming = false; anim.sprinting = false; anim.reloading = true; anim.reloadEmpty = true;
                anim.reload = GunAnimator.EMPTY_MAG_END + (1 - GunAnimator.EMPTY_MAG_END) * (previewAt >= 0 ? previewAt : 0.3f); }
            default -> { anim.aiming = false; anim.sprinting = false; }
        }
        anim.melee = meleeKind;
        anim.meleeTime = meleeClock;
        if (meleeKind >= 0) { anim.aiming = false; anim.sprinting = false; }
        // gun editor: a melee pose held at a moment of its strike ("melee:<kind>", previewAt = 0..1 of the strike)
        if (meleeKind < 0 && previewPose != null && previewPose.startsWith("melee:")) {
            int k = Integer.parseInt(previewPose.substring(6));
            anim.melee = k;
            anim.meleeTime = Math.max(0, previewAt) * GunAnimator.meleeLength(t, k);
            anim.aiming = false; anim.sprinting = false; anim.reloading = false;
        }
        anim.yaw = p.yaw; anim.pitch = p.pitch;
        GunAnimator.Pose pose = anim.update(t, dt);
        pose.marker = editorMarker == null ? null : switch (editorMarker) {
            case "rag" -> t.rag; case "lag" -> t.lag; case "las" -> t.las; case "ram" -> t.ram; case "sight" -> t.sightPoint; case "sight2" -> t.sight2Point;
            case "muzzle" -> t.muzzlePoint; case "magPivot" -> t.magPivot; case "boltFront" -> t.boltFront; case "boltBack" -> t.boltBack;
            case "flash" -> new float[]{t.muzzlePoint[0] + t.flashOffset[0], t.muzzlePoint[1] + t.flashOffset[1], t.muzzlePoint[2] + t.flashOffset[2]};
            default -> t.ejectPoint;
        };
        // the bolt's limits come as a pair: the other end in cyan, joined by a line
        pose.marker2 = "boltFront".equals(editorMarker) ? t.boltBack : "boltBack".equals(editorMarker) ? t.boltFront : null;
        return pose;
    }

    /** Progress (0..1) of a reload the gun editor plays, from its own clock (so slow motion slows it too). */
    private float previewReloadProgress(GunType t) {
        return previewReloadTime / Math.max(0.05f, reloadEmpty ? t.reloadEmptyTime : t.reloadTime);
    }

    // ------------------------------------------------------------------ gun editor preview
    /** Gun editor: hold the sights up, run, walk (animation only). */
    public boolean previewAim, previewSprint, previewWalk;
    /** Gun editor: a pose held on screen (hip, aim, sprint, reload, bolt), or null. */
    public String previewPose;
    /** Gun editor: where the held reload / bolt pose stops (0..1 of that part; below 0 = the usual moment). */
    public float previewAt = -1;
    /** Gun editor: the bolt held back this far (0..1 of its travel; below 0 = off), to see its limits. */
    public float previewBolt = -1;
    /** Gun editor: animation speed (slow motion below 1). */
    public float previewSpeed = 1;
    private float previewReloadTime;
    /** Gun editor: the point shown with a marker on the gun (rag, lag, las, ram, sight, muzzle, magPivot, eject), or null. */
    public String editorMarker;
    private boolean previewReload;
    private int previewShots;

    /** Gun editor: one shot or a burst without bullets or ammo (kick, flash, casing, sound). */
    public void previewFire(int shots) { previewShots = Math.max(0, shots); nextShot = tick; }

    private void previewShot(GunType t) {
        anim.shot(anim.aimAmount() > 0.5f, t);
        shotNanos = System.nanoTime();
        var p = s.player;
        // where the shot would go (blocks only, nothing is hurt): for the flash light and a tracer
        double ex = p.x, ey = p.y + p.eyeHeight, ez = p.z;
        double yaw = Math.toRadians(p.yaw), pitch = Math.toRadians(p.pitch);
        double dx = -Math.sin(yaw) * Math.cos(pitch), dy = -Math.sin(pitch), dz = Math.cos(yaw) * Math.cos(pitch);
        Raycast.Hit h = Raycast.cast(s.world, ex, ey, ez, dx, dy, dz, t.range, false);
        double best = h == null ? t.range : Math.sqrt((h.hx - ex) * (h.hx - ex) + (h.hy - ey) * (h.hy - ey) + (h.hz - ez) * (h.hz - ez));
        float a = anim.aimAmount();
        double[] mw = muzzleWorld(ex, ey, ez, p.yaw, p.pitch, a);
        double mx = mw[0], my = mw[1], mz = mw[2];
        muzzleX = mx; muzzleY = my; muzzleZ = mz;
        s.game.entities.gunMuzzle(mx, my, mz, dx, dy, dz);
        if (h != null) { s.game.entities.gunImpact(s.world, h); s.game.entities.bulletHole(s.world, h); }
        if (rnd.nextFloat() < t.tracerChance) tracer(t, mx, my, mz, ex + dx * best, ey + dy * best, ez + dz * best);
        List<Path> sh = t.shootSounds;
        if (!sh.isEmpty()) Audio.play(Audio.load(sh.get(rnd.nextInt(sh.size()))), false, 0.9f * t.volume, 0.96f + rnd.nextFloat() * 0.08f, 0);
    }

    /** Gun editor: plays a reload (with rounds left or empty) without using a magazine. */
    public void previewReload(boolean empty) {
        GunType t = held();
        if (t == null || reloading) return;
        reloading = true; previewReload = true; reloadEmpty = empty; reloadStart = tick; reloadStage = 0; magDropped = false; previewReloadTime = 0;
        reloadTicks = Math.max(4, Math.round((empty ? t.reloadEmptyTime : t.reloadTime) * 20));
        play(t, "reload", 0.9f);
    }

    /** Gun editor: the gun is taken out again. */
    public void previewEquip() { GunType t = held(); if (t != null) { anim.equip(); getSound(t, 0.8f); } }

    private long kickNanos;
    private boolean magDropped;

    /** The old magazine is let go: it falls to the ground where the player's hands are (with the player's speed). */
    private void dropMagazine(GunType t) {
        var p = s.player;
        double[] at = GunHold3P.worldMagRelease(t, p.x, p.y, p.z, p.yaw, 0, p.pitch, 1, reloadEmpty);
        if (at != null) s.game.entities.dropMagazine(t, at[0], at[1], at[2], (float) at[3], p.x - p.px, p.y - p.py, p.z - p.pz);
    }

    /** Third person view of the player: the kick of the last shot (0..1, fades in 0.15 s). */
    public float thirdPersonKick() {
        if (kickNanos == 0) return 0;
        float age = (System.nanoTime() - kickNanos) / 1e9f;
        return Math.max(0, 1 - age / 0.15f);
    }

    /** Third person view of the player: the reload's progress (0..1, -1 = none) and whether it is empty (with the bolt). */
    public float thirdPersonReload() { return anim.reloading ? anim.reload : -1; }

    /** Whether the reload shown is an empty one (also the gun editor's held Bolt pose). */
    public boolean reloadEmpty() { return anim.reloading ? anim.reloadEmpty : reloadEmpty; }

    /** HUD state: hit marker (0..1). */
    public float hitMarker() { return Math.max(0, hitMarker); }

    /** A headshot just now (1 fading to 0): the red hit marker. */
    public float headshotMarker() { return Math.max(0, headMarker); }

    /** Current spread of the crosshair (degrees). */
    public float spread() {
        GunType t = held();
        if (t == null) return 0;
        var p = s.player;
        float a = anim.aimAmount();
        double moving = Math.min(1, Math.hypot(p.x - p.px, p.z - p.pz) * 20 / 4.3);
        return t.spreadHip + (t.spreadAim - t.spreadHip) * a + bloom * (1 - 0.6f * a) + (float) moving * t.spreadMove * (1 - 0.8f * a);
    }
}
