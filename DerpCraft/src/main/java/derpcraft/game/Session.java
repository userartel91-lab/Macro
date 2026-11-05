package derpcraft.game;

import derpcraft.DerpCraft;
import derpcraft.api.EventBus;
import derpcraft.api.Events;
import derpcraft.api.GameApi;
import derpcraft.api.ModContext;
import derpcraft.block.Block;
import derpcraft.block.Blocks;
import derpcraft.block.Dir;
import derpcraft.block.FunctionalBlocks;
import derpcraft.entity.Entity;
import derpcraft.entity.ItemEntity;
import derpcraft.gui.Containers;
import derpcraft.item.Inventory;
import derpcraft.item.Item;
import derpcraft.item.ItemStack;
import derpcraft.item.Items;
import derpcraft.render.*;
import derpcraft.util.Frustum;
import derpcraft.util.Mat4;
import derpcraft.world.*;
import derpcraft.world.be.ContainerEntity;
import derpcraft.world.be.FurnaceEntity;
import derpcraft.world.be.SignEntity;

import java.util.*;

import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL45C.*;

/** A world being played: owns the world, chunk loading, rendering, the player, HUD and editing tools. */
public final class Session implements GameApi {
    /** The open world's session (for mod block handlers), or null on the title screen. */
    public static Session current;
    public final DerpCraft game;
    public final World world;
    public final ChunkManager chunks;
    public final WorldRenderer renderer;
    /** World copy on the GPU for ray tracing (created when ray tracing is first turned on). */
    private VoxelVolume voxels;
    private int frameIndex;
    public final Player player = new Player();
    public final PlayerInventory inv = new PlayerInventory();
    public final Inventory enderChest = new Inventory(27);
    public final PlayerStats stats = new PlayerStats();
    /** "survival", "creative" or "spectator". */
    public String gameMode = "creative";
    private String modeBeforeSpectator = "creative";
    public final Commands commands;
    public final WorldEdit edit = new WorldEdit(this);
    public Raycast.Hit target;
    public boolean hideHud, showDebug, cinematic;
    public long ticks;
    private int breakCooldown, placeCooldown;
    private int nameTicks;
    // survival mining
    private int mineX, mineY = -1, mineZ;
    public float mineProgress;
    // first person hand animation
    private int swingTicks;
    private boolean swinging;
    private float swing, prevSwing, equip = 1, prevEquip = 1;
    private ItemStack shownItem;
    private int eatTicks;
    private boolean wasOnGround = true;
    /** Camera: 0 first person, 1 third person behind, 2 third person in front (F5). */
    public int view;
    /** View bobbing like EntityPlayer.cameraYaw / cameraPitch (1.12): bob amount follows the walking speed, tilt the fall speed. */
    private float bobAmount, prevBobAmount, bobTilt, prevBobTilt;
    /** Selfie camera position (world) this frame: the head looks into it. */
    private double selfieCamX, selfieCamY, selfieCamZ;
    /** Selfie camera held in the right hand: on (Left Ctrl + End), free = the camera no longer turns with the head (Right Ctrl + End). */
    public boolean selfie, selfieFree;
    /** Free selfie: the head direction the camera stays at (the player can turn the head away from it). */
    private float selfieYaw, selfiePitch;
    /** Film camera: free / orbit / keyframed paths, lens and focus. */
    public final CameraRig cam = new CameraRig();
    /** NPCs, recorded actors and scenes of this world (F10). */
    public derpcraft.npc.NpcManager npcs;
    /** Creatures and projectiles of mods. */
    public derpcraft.entity.Mobs mobs;
    /** Cars and other vehicles (models with wheel groups) of this world. */
    public derpcraft.vehicle.Vehicles vehicles;
    /** The part of a vehicle under the crosshair (nearer than any block), or null. */
    public derpcraft.vehicle.Vehicles.Hit targetVehicle;
    double lastCamX, lastCamY, lastCamZ;
    float lastCamYaw, lastCamPitch, lastFocal = 35;
    /** Dropped item under the crosshair (click pickup mode). */
    public ItemEntity targetItem;
    // player model animation
    private float limbSwing, limbAmount, prevLimbSwing, prevLimbAmount, bodyYaw, prevBodyYaw;
    private final EntityRenderer.PlayerPose pose = new EntityRenderer.PlayerPose();
    private Item lastHeldItem;
    private final List<String[]> chat = new ArrayList<>();          // {text, tick}
    private float fovFactor = 1, prevFovFactor = 1, zoom = 1;
    private double smoothDX, smoothDY;
    private final Frustum frustum = new Frustum();
    private final WorldRenderer.FrameParams fp = new WorldRenderer.FrameParams();
    private final Batch lines = new Batch();
    /** The gun in the hand: shooting, aiming, reloading, its first person animation. */
    public final derpcraft.gun.GunController guns = new derpcraft.gun.GunController(this);
    /** Fist fighting from the first person (Alt + Q). */
    public final Fists fists = new Fists(this);
    /** Security cameras and the screens that show them. */
    public final derpcraft.camera.CameraNetwork cameraNet;
    private long lastAutosave;

    public Session(DerpCraft game, WorldInfo info) {
        this.game = game;
        world = new World(info);
        chunks = new ChunkManager(world, game.workers);
        renderer = new WorldRenderer(world, game.workers, game.assets.blocks);
        renderer.setTextures(game.assets.tables, game.assets.grassMap, game.assets.foliageMap);
        cameraNet = new derpcraft.camera.CameraNetwork(this);
        game.entities.cameras = cameraNet;
        chunks.onLoad = renderer::chunkLoaded;
        chunks.onUnload = renderer::chunkUnloaded;
        commands = new Commands(this);

        player.yaw = info.yaw;
        player.pitch = info.pitch;
        gameMode = switch (info.gameMode) { case "survival", "spectator" -> info.gameMode; default -> "creative"; };
        player.noclip = spectator();
        player.flying = (info.flying && creative()) || player.noclip;
        player.flySpeedMultiplier = info.flySpeed;
        inv.deserialize(info.inventory);
        enderChest.deserialize(info.enderChest);
        if (info.inventory.isEmpty() && !survival()) {
            // worlds from 1.0 stored a creative hotbar of block names
            String[] hb = info.hotbar.isEmpty()
                    ? new String[]{"stone", "grass", "dirt", "cobblestone", "oak_planks", "oak_log", "glass", "stone_bricks", "torch"}
                    : info.hotbar.split(",", -1);
            for (int i = 0; i < 9 && i < hb.length; i++) {
                Item it = hb[i].isEmpty() ? null : itemFor(Blocks.byName(hb[i]));
                if (it != null) inv.set(i, new ItemStack(it, it.maxStack));
            }
        }
        stats.health = info.health; stats.food = info.food; stats.saturation = info.saturation;
        stats.xpLevel = info.xpLevel; stats.xpProgress = info.xpProgress;
        if (stats.health <= 0) stats.health = 20;
        game.entities.particles.clear();

        double sx = info.px, sz = info.pz;
        if (info.py < 0) { int[] sp = chunks.generator.findSpawn(); sx = sp[0] + 0.5; sz = sp[1] + 0.5; }
        chunks.loadNow((int) Math.floor(sx) >> 4, (int) Math.floor(sz) >> 4, 1);
        world.light.await(10000);
        double sy = info.py;
        if (sy < 0) {
            int top = Math.max(world.topSolidY((int) Math.floor(sx), (int) Math.floor(sz)), chunks.generator.spawnY((int) Math.floor(sx), (int) Math.floor(sz)) - 1);
            sy = top + 1;
        }
        player.setPos(sx, sy, sz);
        lastAutosave = System.currentTimeMillis();
        cam.loadSettings(game.gameDir.resolve("camera.txt"));
        cam.loadPath(info.dir.resolve("camera_path.txt"));
        npcs = new derpcraft.npc.NpcManager(this, info.dir);
        mobs = new derpcraft.entity.Mobs(this, info.dir);
        vehicles = new derpcraft.vehicle.Vehicles(this, info.dir);
        current = this;
        mobs.load();
        loadWorldData();
        for (Events.WorldHandler h : ModContext.WORLD_LOAD) safe(() -> h.onWorld(this));
    }

    public boolean creative() { return gameMode.equals("creative"); }
    public boolean survival() { return gameMode.equals("survival"); }
    public boolean spectator() { return gameMode.equals("spectator"); }

    public void setGameMode(String mode) {
        if (!mode.equals("spectator")) modeBeforeSpectator = mode;
        else if (!spectator()) modeBeforeSpectator = gameMode;
        gameMode = mode;
        if (mode.equals("spectator") && vehicles != null && vehicles.riding()) vehicles.exitPlayer();
        player.noclip = spectator();
        if (spectator()) player.flying = true;
        else if (survival()) player.flying = false;
        mineProgress = 0;
    }

    public void toggleSpectator() { setGameMode(spectator() ? modeBeforeSpectator : "spectator"); }

    /** Item that represents a block (wall_sign -> sign, lit_furnace -> furnace...). */
    public static Item itemFor(Block b) {
        if (b == null || b.isAir()) return null;
        if (b.item != null) return b.item;
        Item it = Items.byName(b.name);
        if (it == null && b.dropItem != null && !b.dropItem.isEmpty()) it = Items.byName(b.dropItem);
        if (it == null && b.name.startsWith("lit_")) it = Items.byName(b.name.substring(4));
        if (it == null && b.name.startsWith("wall_")) it = Items.byName(b.name.substring(5));
        return it;
    }

    public ItemStack heldStack() { return inv.held(); }

    public int selected() { return inv.selected; }

    /** Uses up items from the held stack (not in creative). */
    public void consumeHeld(int n) {
        if (creative()) return;
        ItemStack h = inv.held();
        if (h == null) return;
        h.count -= n;
        inv.set(inv.selected, h.count <= 0 ? null : h);
    }

    /** Eats food: returns false when not hungry (unless 'always'). */
    public boolean eat(int food, float saturation, boolean always) {
        if (!always && !stats.needsFood() && !creative()) return false;
        stats.eat(food, saturation);
        return true;
    }

    // ------------------------------------------------------------------------------------------ ticking
    /** One game tick (20 a second). Not called while the world is paused (DerpCraft.worldPaused). */
    public void tick() {
        ticks++;
        tickNpcTab();
        WorldInfo info = world.info;
        if (info.daylightCycle) info.time++;
        world.gameTime++;
        game.assets.blocks.tick();

        Player.Input in = new Player.Input();
        boolean active = game.screen == null && !stats.dead && cam.controlsPlayer();
        if (active) {
            long win = game.window;
            if (Keys.FORWARD.down(win)) in.forward += 1;
            if (Keys.BACK.down(win)) in.forward -= 1;
            if (Keys.LEFT.down(win)) in.strafe += 1;
            if (Keys.RIGHT.down(win)) in.strafe -= 1;
            in.jump = Keys.JUMP.down(win);
            in.deepSneak = Keys.SNEAK_DEEP.down(win);
            in.sneak = Keys.SNEAK.down(win) || in.deepSneak;
            in.sprint = Keys.SPRINT.down(win) || (in.forward > 0 && player.sprinting);
            if (survival() && stats.food <= 6) in.sprint = false;
        }
        if (eatTicks > 0) { in.forward *= 0.2f; in.strafe *= 0.2f; in.sprint = false; }
        // a gun in the hand: slower walking, aiming / firing stops sprinting
        boolean gunInput = active && !cam.detached() && !mouseBlocked;
        guns.adjustInput(in, gunInput && Keys.ATTACK.down(game.window), gunInput && Keys.USE.down(game.window));
        // in a vehicle the keys drive it (Shift gets out); the player does not walk
        if (vehicles.riding()) vehicles.playerInput(in, active);
        // Alt held behind a parked car: pushes it
        vehicles.pushInput(active && !guns.holding() && !fists.fighting() && pushKeyDown());
        // don't simulate before the ground under the player exists
        Chunk here = world.chunkAt((int) Math.floor(player.x), (int) Math.floor(player.z));
        double ox = player.x, oz = player.z;
        if (here != null && here.state >= Chunk.STATE_LIT) {
            if (!vehicles.riding()) player.tick(world, in, creative());
        }
        animateModel();
        // view bobbing amount (EntityPlayer.onLivingUpdate)
        prevBobAmount = bobAmount; prevBobTilt = bobTilt;
        float hs = (float) Math.min(0.1, Math.sqrt((player.x - player.px) * (player.x - player.px) + (player.z - player.pz) * (player.z - player.pz)));
        boolean grounded = player.onGround && !player.flying && stats.health > 0;
        if (ticks % 20 == 0) ambientSounds();
        // water: a splash on entering, swimming strokes while moving in it
        if (player.inWater && !wasInWaterSfx && !player.flying) derpcraft.audio.Sfx.play("liquid/splash", 0.8f, 1f);
        wasInWaterSfx = player.inWater;
        if (player.inWater && !player.flying && ticks % 18 == 0 && Math.abs(player.x - ox) + Math.abs(player.z - oz) > 0.03)
            derpcraft.audio.Sfx.play("liquid/swim", 0.5f, 0.9f + world.random.nextFloat() * 0.2f);
        // footsteps (game/sounds/step/<material>): one per stride, none when sneaking
        int stride = (int) Math.floor(player.walkDist);
        if (stride != lastStride) {
            lastStride = stride;
            if (grounded && !player.sneaking && !player.inWater)
                derpcraft.audio.Sfx.step(this, world.getBlock((int) Math.floor(player.x), (int) Math.floor(player.y - 0.2), (int) Math.floor(player.z)),
                        player.x, player.y, player.z, player.sprinting ? 0.5f : 0.35f);
        }
        float tilt = (float) (Math.atan(-(player.y - player.py) * 0.2) * 15);
        if (!grounded) hs = 0;
        if (grounded || stats.health <= 0 || player.flying) tilt = 0;
        bobAmount += (hs - bobAmount) * 0.4f;
        bobTilt += (tilt - bobTilt) * 0.8f;
        if (!survival() && player.y < -64) player.setPos(player.x, Math.max(world.topSolidY((int) Math.floor(player.x), (int) Math.floor(player.z)) + 1, 64), player.z);

        // survival: fall damage, exhaustion, environment
        int armor = inv.armorPoints();
        if (player.landed > 0) {
            float d = player.landed;
            player.landed = 0;
            int dmg = (int) Math.ceil(d - 3);
            if (survival() && dmg > 0 && !player.inWater) {
                stats.damage(dmg, "fall", armor, ticks);
            }
        }
        if (survival()) {
            double moved = Math.sqrt((player.x - ox) * (player.x - ox) + (player.z - oz) * (player.z - oz));
            if (player.sprinting) stats.addExhaustion(0.1f * (float) moved);
            if (wasOnGround && !player.onGround && player.my > 0 && !player.onLadder) stats.addExhaustion(player.sprinting ? 0.2f : 0.05f);
        }
        wasOnGround = player.onGround;
        stats.tick(world, player, armor, ticks, survival());
        if (stats.dead && !(game.screen instanceof Containers.Death)) die();

        prevFovFactor = fovFactor;
        float target = 1f;
        if (game.settings.fovEffects) {
            if (player.flying) target *= 1.1f;
            if (player.sprinting) target *= 1.15f;
        }
        fovFactor += (target - fovFactor) * 0.5f;

        // mouse buttons held
        if (breakCooldown > 0) breakCooldown--;
        if (placeCooldown > 0) placeCooldown--;
        boolean interact = active && !cam.detached();
        npcs.playerActing = true;
        // a click that closed a menu (Done, Back to Game) must not break or place a block: wait until it is released
        if (mouseBlocked && !Keys.ATTACK.down(game.window) && !Keys.USE.down(game.window)) mouseBlocked = false;
        boolean left = interact && !mouseBlocked && Keys.ATTACK.down(game.window);
        boolean right = interact && !mouseBlocked && Keys.USE.down(game.window);
        boolean gunHeld = guns.holding();
        guns.tick(left && gunHeld, right && gunHeld && targetVehicle == null, interact);
        boolean fistMode = !gunHeld && fists.fighting();                          // Alt + Q: the mouse buttons punch
        fists.tick(left && fistMode, right && fistMode && targetVehicle == null);
        tickBackGun();
        tickBleeding();
        String gunReload = derpcraft.gun.Guns.watch();                             // a file in game/guns changed
        if (gunReload != null) chat(gunReload);
        if (gunHeld) mineProgress = 0;                                              // a gun: left = fire, right = aim
        else if (fistMode && targetVehicle == null) mineProgress = 0;               // fists: left / right = punches
        else if (left && altDown() && isTorchThrow(inv.held())) mineProgress = 0;     // Alt + left click throws the torch instead of breaking
        else if (left && targetVehicle != null) {
            mineProgress = 0;
            if (breakCooldown == 0) vehicles.mineSlot(targetVehicle);          // survival: a block carried by the car breaks after its mining time
        }
        else if (left && (targetItem != null || targetActor != null || targetMob != null)) mineProgress = 0;
        else if (left) {
            if (survival()) mineTick();
            else if (creative() && breakCooldown == 0) breakBlock();
        } else mineProgress = 0;
        tickBow(right && !gunHeld && !fistMode);
        if (right && !gunHeld && !(fistMode && targetVehicle == null)) {
            if (eatTicks > 0) eatTick();
            else if (placeCooldown == 0 && targetVehicle == null) useItem();
        } else eatTicks = 0;
        npcs.playerActing = false;

        // hand animation
        prevSwing = swing;
        if (swinging) { if (++swingTicks >= 6) { swingTicks = 0; swinging = false; } }
        else swingTicks = 0;
        swing = swingTicks / 6f;
        prevEquip = equip;
        ItemStack held = inv.held();
        boolean same = held == shownItem || held != null && shownItem != null && held.item == shownItem.item;
        equip += Math.max(-0.4f, Math.min(0.4f, (same ? 1 : 0) - equip));
        if (equip < 0.1f) shownItem = held;
        if (same) shownItem = held;
        Item hi = held == null ? null : held.item;
        if (hi != lastHeldItem) { nameTicks = 40; lastHeldItem = hi; eatTicks = 0; }

        // world simulation
        world.runScheduledTicks();
        world.tickBlockEntities();
        npcs.randomTicking = true;                 // grass spreading, leaves decaying...: never part of a take or a scene snapshot
        try { randomTicks(); } finally { npcs.randomTicking = false; }
        tickEntities();
        mobs.tick();
        vehicles.tick();
        npcs.tick();
        runTasks();
        if (!ModContext.PLAYER_TICK.isEmpty()) for (Events.TickHandler h : ModContext.PLAYER_TICK) safe(() -> h.onTick(this));
        game.entities.tickParticles(world);
        stepOnPlates();

        if (nameTicks > 0) nameTicks--;
        for (Events.TickHandler h : ModContext.TICK) safe(() -> h.onTick(this));
        if (System.currentTimeMillis() - lastAutosave > 300_000) {
            storePlayer();
            saveWorldData();            // mod world data was only written when the world was closed (lost on a crash)
            mobs.save();
            vehicles.save();
            chunks.saveAllAsync();
            lastAutosave = System.currentTimeMillis();
        }
    }

    private boolean pushKeyDown() {
        long win = game.window;
        return Keys.PUSH.down(win) || Keys.PUSH.key == GLFW_KEY_LEFT_ALT && Keys.PUSH.mod == Keys.NONE && glfwGetKey(win, GLFW_KEY_RIGHT_ALT) == GLFW_PRESS;
    }

    /** Right Alt + F8 (Shift: the next fixed shot): the cinematic cameras that follow the vehicle. */
    public void vehicleCamera(boolean shift) {
        if (!vehicles.riding()) { chat("§eGet into a vehicle first"); return; }
        vehicles.camera.press(shift);
        chat("Vehicle camera: " + vehicles.camera.name() + (vehicles.camera.active() ? " §7(" + Keys.n(Keys.VEHICLE_CAMERA) + ": off, Shift: next shot)" : ""));
    }

    /** Third person camera distance: shrinks so the camera never goes into solid blocks (like vanilla). */
    private double cameraDistance(double x, double y, double z, double dx, double dy, double dz, double max) {
        double[][] corners = {{0.1, 0.1, 0.1}, {-0.1, 0.1, 0.1}, {0.1, -0.1, 0.1}, {-0.1, -0.1, 0.1}, {0.1, 0.1, -0.1}, {-0.1, 0.1, -0.1}, {0.1, -0.1, -0.1}, {-0.1, -0.1, -0.1}};
        for (double t = 0.3; t <= max; t += 0.05) {
            for (double[] c : corners) {
                int bx = (int) Math.floor(x + dx * t + c[0]), by = (int) Math.floor(y + dy * t + c[1]), bz = (int) Math.floor(z + dz * t + c[2]);
                Block b = world.getBlock(bx, by, bz);
                if (b.solid && b.shape != Block.Shape.CROSS && !b.isAir()) return Math.max(0.3, t - 0.1);
            }
        }
        return max;
    }

    /** Limb swing and body rotation like EntityLivingBase (called every tick). */
    private void animateModel() {
        prevLimbSwing = limbSwing; prevLimbAmount = limbAmount; prevBodyYaw = bodyYaw;
        double dx = player.x - player.px, dz = player.z - player.pz;
        float f = (float) Math.min(1, Math.sqrt(dx * dx + dz * dz) * 4);
        if (player.flying) f *= 0.3f;
        limbAmount += (f - limbAmount) * 0.4f;
        limbSwing += limbAmount;
        if (vehicles.riding()) {
            // sitting in a seat: no steps, the body faces the vehicle's front
            limbAmount = prevLimbAmount = 0;
            bodyYaw = vehicles.seatFacingNow();
            return;
        }
        if (selfieActive() && selfieFree) {
            // the body keeps facing the camera held in the stretched arm, only the head turns
            bodyYaw += wrap(selfieYaw - bodyYaw) * 0.3f;
            return;
        }
        float target = bodyYaw;
        if (dx * dx + dz * dz > 0.0025 * 0.0025) target = (float) Math.toDegrees(Math.atan2(dz, dx)) - 90;
        // a gun in the hands: the body faces where the player looks (the gun is held to the shoulders, so they turn with it)
        boolean gunHeld = derpcraft.gun.Guns.of(inv.held()) != null;
        if (swinging || gunHeld) target = player.yaw;
        bodyYaw += wrap(target - bodyYaw) * (gunHeld ? 0.5f : 0.3f);
        float off = wrap(player.yaw - bodyYaw);
        if (off < -50) off = -50;
        if (off > 50) off = 50;
        bodyYaw = player.yaw - off;
        if (off * off > 2500) bodyYaw += off * 0.2f;
    }

    private void updatePose(float partial, double x, double y, double z) {
        pose.x = x; pose.y = y; pose.z = z;
        float by = prevBodyYaw + wrap(bodyYaw - prevBodyYaw) * partial;
        var rv = vehicles.playerVehicle;
        if (rv != null) by = rv.yawAt(partial) + (vehicles.seatFacingNow() - rv.yaw);
        pose.bodyYaw = by;
        pose.headYaw = wrap(player.yaw + (rv == null ? 0 : wrap(rv.yawAt(partial) - rv.yaw)) - by);
        pose.sitting = rv != null;
        pose.vehRoll = rv == null ? 0 : rv.flipAt(partial);
        if (rv != null) pose.headYaw = Math.max(-85, Math.min(85, pose.headYaw));
        // free selfie: the head turns away from the camera, but not further than a neck can
        if (selfieActive() && !selfieFree) {
            // the camera hangs a little to the right of the look: turn the head so the face looks straight into the lens
            double hx = x, hy = y + player.eyeHeight - 0.119 + 0.25, hz = z;
            double dx = selfieCamX - hx, dy = selfieCamY - hy, dz = selfieCamZ - hz;
            float ly = (float) Math.toDegrees(Math.atan2(-dx, dz)), lp = (float) -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
            pose.headYaw = Math.max(-85, Math.min(85, wrap(ly - by)));
            pose.pitch = Math.max(-90, Math.min(90, lp));
        }
        if (selfieActive() && selfieFree) pose.headYaw = Math.max(-85, Math.min(85, pose.headYaw));
        pose.selfie = selfieActive();
        pose.selfieX = selfieHandX; pose.selfieY = selfieHandY; pose.selfieZ = selfieHandZ;
        pose.pitch = player.pitch;
        pose.limbSwing = prevLimbSwing + (limbSwing - prevLimbSwing) * partial;
        pose.limbAmount = prevLimbAmount + (limbAmount - prevLimbAmount) * partial;
        float sw = swing - prevSwing < 0 ? prevSwing + (1 + swing - prevSwing) * partial : prevSwing + (swing - prevSwing) * partial;
        pose.swing = sw >= 1 ? sw - 1 : sw;
        pose.age = ticks + partial;
        pose.sneaking = player.sneaking;
        pose.stanceVis = player.visual(partial);
        pose.held = inv.held();
        pose.gunKick = guns.thirdPersonKick();
        pose.gunReload = guns.thirdPersonReload();
        pose.gunReloadEmpty = guns.reloadEmpty();
        pose.gunMelee = guns.thirdPersonMelee();
        pose.gunMeleeTime = guns.thirdPersonMeleeTime();
        // fist fighting: the guard and the punches (the item in the hand is not shown)
        pose.fists = fists.guard();
        pose.punchLeft = fists.punchLeft();
        pose.punchRight = fists.punchRight();
        pose.push = vehicles.pushAmount(partial);
        if (pose.fists > 0 && fists.fighting()) pose.held = null;
        // the gun carried on the back (Left Alt + F) and the arm reaching over the shoulder for it
        pose.backGun = inv.back();
        pose.backReach = backReachAmount(partial);
        if (pose.pouches == null) pose.pouches = new ItemStack[PlayerInventory.POUCHES];
        for (int k = 0; k < PlayerInventory.POUCHES; k++) pose.pouches[k] = pouchPreview != null ? new ItemStack(pouchPreview, 1) : inv.get(PlayerInventory.POUCH + k);
        // while recording the player shows the gesture and face they act with (third person views)
        boolean rec = npcs != null && npcs.recording();
        pose.poseTo = rec ? derpcraft.npc.Poses.get(npcs.recordPose) : null;
        pose.poseFrom = null;
        pose.poseT = 1;
        // the player's skin (Alt + F9): the model, its shadow, and the first person arms (hand, guns, fists)
        derpcraft.npc.Skins.Skin sk = playerSkin();
        pose.slim = sk != null && sk.slim();
        pose.skin = sk == null ? null : rec && npcs.recordEmotion > 0 ? npcs.skins.face(sk.name(), npcs.recordEmotion) : sk.texture();
        game.entities.playerSkin = sk == null ? null : sk.texture();
        game.entities.playerSlim = pose.slim;
    }

    /** EntityRenderer.applyBobbing (1.12): sway and bounce with the steps, a little roll and nod, tilt when falling.
     *  Also shakes the selfie camera in the hand while walking and running. */
    private Mat4 bobbing(float partial) {
        float step = player.walkDist - player.prevWalkDist;
        float f1 = -(player.walkDist + step * partial);
        float f2 = prevBobAmount + (bobAmount - prevBobAmount) * partial;
        float f3 = prevBobTilt + (bobTilt - prevBobTilt) * partial;
        if (f2 < 1e-4f && Math.abs(f3) < 1e-3f) return null;
        double p = f1 * Math.PI;
        Mat4 m = new Mat4().translate((float) Math.sin(p) * f2 * 0.5f, -(float) Math.abs(Math.cos(p) * f2), 0);
        m.rotate((float) Math.toRadians(Math.sin(p) * f2 * 3), 0, 0, 1);
        m.rotate((float) Math.toRadians(Math.abs(Math.cos(p - 0.2) * f2) * 5), 1, 0, 0);
        m.rotate((float) Math.toRadians(f3), 1, 0, 0);
        return m;
    }

    /** F5: first person -> behind -> in front. */
    public void cycleView() { view = (view + 1) % 3; }

    /** The six pouches as recorded in a take ("item:count;item:count;...", "" when all are empty). */
    public String pouchString() {
        StringBuilder sb = new StringBuilder();
        boolean any = false;
        for (int k = 0; k < PlayerInventory.POUCHES; k++) {
            ItemStack st = inv.get(PlayerInventory.POUCH + k);
            if (k > 0) sb.append(';');
            if (st != null) { sb.append(st.item.name).append(':').append(st.count); any = true; }
        }
        return any ? sb.toString() : "";
    }

    /** Set by the gun editor while it tunes a magazine's place in the pouches: that magazine shows in all six pouches. */
    public derpcraft.item.Item pouchPreview;

    private void randomTicks() {
        int pcx = (int) Math.floor(player.x) >> 4, pcz = (int) Math.floor(player.z) >> 4;
        java.util.Random r = world.random;
        for (int dx = -6; dx <= 6; dx++)
            for (int dz = -6; dz <= 6; dz++) {
                Chunk c = world.getChunk(pcx + dx, pcz + dz);
                if (c == null || c.state < Chunk.STATE_LIT) continue;
                for (int sy = 0; sy < 16; sy++) {
                    Section sec = c.sections[sy];
                    if (sec == null || sec.isEmpty()) continue;
                    for (int k = 0; k < 3; k++) {
                        int i = r.nextInt(4096);
                        int st = sec.get(i);
                        if (st == 0) continue;
                        Block b = Blocks.byId(st >> 4);
                        if (!b.ticksRandomly()) continue;
                        b.randomTick(world, (c.cx << 4) + (i & 15), sy * 16 + (i >> 8), (c.cz << 4) + ((i >> 4) & 15), st & 15, r);
                    }
                }
            }
    }

    private void tickEntities() {
        List<Entity> list = world.entities;
        double[] bb = player.aabb();
        boolean click = game.settings.pickupClick;
        ItemEntity.physics = game.settings.itemPhysics;
        List<Entity> spawned = new ArrayList<>();
        for (int i = 0; i < list.size(); i++) {
            Entity e = list.get(i);
            if (world.chunkAt((int) Math.floor(e.x), (int) Math.floor(e.z)) == null) continue;
            e.tick();
            if (!(e instanceof ItemEntity ie) || e.dead) continue;
            if (ie.collect == 0) {
                // pickup animation finished
                if (ie.addWhenCollected) {
                    ItemStack left = inv.addItem(ie.stack);
                    if (left != null) {
                        ItemEntity back = new ItemEntity(world, player.x, player.y + 0.3, player.z, left);
                        back.pickupDelay = 40;
                        spawned.add(back);
                    }
                }
                ie.dead = true;
                derpcraft.audio.Sfx.play("random/pop", 0.4f, 0.8f + world.random.nextFloat() * 0.4f);
                continue;
            }
            if (click || ie.collect >= 0 || ie.pickupDelay > 0 || spectator() || stats.dead) continue;
            if (ie.x > bb[0] - 1 && ie.x < bb[3] + 1 && ie.y > bb[1] - 0.5 && ie.y < bb[4] + 0.5 && ie.z > bb[2] - 1 && ie.z < bb[5] + 1) {
                if (EventBus.has(Events.ItemPickup.class) && EventBus.post(this, new Events.ItemPickup(ie.stack)).isCancelled()) { ie.pickupDelay = 20; continue; }
                int before = ie.stack.count;
                ItemStack left = inv.addItem(ie.stack);
                int taken = before - (left == null ? 0 : left.count);
                if (taken <= 0) continue;
                if (npcs.recording()) npcs.recordItem(0, ie.stack.item.name, taken, 0, 0, 0);
                if (left == null) ie.startCollect(3, false);
                else {
                    // only part fits: a copy of the picked part flies to the player, the rest stays
                    ItemEntity fly = new ItemEntity(world, ie.x, ie.y, ie.z, ie.stack.copy(taken));
                    fly.startCollect(3, false);
                    spawned.add(fly);
                    ie.stack = left;
                }
            }
        }
        list.addAll(spawned);
        list.removeIf(e -> e.dead);
    }

    /** Click pickup: the arm swings and the item flies into the inventory. */
    public boolean pickUpTargetItem() {
        ItemEntity ie = targetItem;
        if (ie == null || ie.dead || ie.collect >= 0 || spectator()) return false;
        if (EventBus.has(Events.ItemPickup.class) && EventBus.post(this, new Events.ItemPickup(ie.stack)).isCancelled()) return false;
        swingArm();
        ie.startCollect(5, true);
        if (npcs.recording()) npcs.recordItem(0, ie.stack.item.name, ie.stack.count, 0, 0, 0);
        targetItem = null;
        return true;
    }

    /** Dropped item hit by the look ray before any block (expanded box for easy clicking). */
    private ItemEntity findTargetItem(double ex, double ey, double ez, double dx, double dy, double dz, double maxDist) {
        ItemEntity best = null;
        double bestT = maxDist;
        for (Entity e : world.entities) {
            if (!(e instanceof ItemEntity ie) || ie.dead || ie.collect >= 0) continue;
            double r = 0.3, x0 = ie.x - r, y0 = ie.y - 0.05, z0 = ie.z - r, x1 = ie.x + r, y1 = ie.y + 0.45, z1 = ie.z + r;
            double t = derpcraft.util.RayBox.t(ex, ey, ez, dx, dy, dz, bestT, x0, y0, z0, x1, y1, z1);
            if (t >= 0 && t < bestT) { bestT = t; best = ie; }
        }
        return best;
    }

    private void stepOnPlates() {
        if (spectator() || !player.onGround) return;
        int x = (int) Math.floor(player.x), y = (int) Math.floor(player.y + 0.01), z = (int) Math.floor(player.z);
        if (world.getBlock(x, y, z) instanceof FunctionalBlocks.PlateBlock p) p.stepOn(world, x, y, z);
        if (world.getBlock(x, y, z) instanceof derpcraft.block.ModBlock mb && mb.stepped != null) mb.steppedOn(world, x, y, z);
        int by = (int) Math.floor(player.y - 0.01);
        if (by != y && world.getBlock(x, by, z) instanceof derpcraft.block.ModBlock mb && mb.stepped != null) mb.steppedOn(world, x, by, z);
    }

    // ------------------------------------------------------------------------------------------ mod services
    private record Task(long at, Runnable r) {}
    private final PriorityQueue<Task> tasks = new PriorityQueue<>(Comparator.comparingLong(Task::at));
    private final Map<String, derpcraft.api.DataTag> worldData = new HashMap<>();

    private void runTasks() {
        while (!tasks.isEmpty() && tasks.peek().at() <= ticks) safe(tasks.poll().r());
    }

    private void loadWorldData() {
        var f = world.info.dir.resolve("moddata.txt");
        if (!java.nio.file.Files.exists(f)) return;
        try {
            for (String line : java.nio.file.Files.readAllLines(f)) {
                int i = line.indexOf('=');
                if (i > 0) worldData.put(line.substring(0, i), derpcraft.api.DataTag.decode(line.substring(i + 1)));
            }
        } catch (Exception e) { System.err.println("Cannot read moddata.txt: " + e); }
    }

    private void saveWorldData() {
        // an emptied store is written too, else the old file would bring the removed data back
        if (worldData.isEmpty() && !java.nio.file.Files.exists(world.info.dir.resolve("moddata.txt"))) return;
        StringBuilder sb = new StringBuilder();
        for (var e : worldData.entrySet()) if (!e.getValue().isEmpty()) sb.append(e.getKey().replace("=", "_").replace("\n", " ")).append('=').append(e.getValue().encode()).append('\n');
        try { derpcraft.util.SafeFiles.writeString(world.info.dir.resolve("moddata.txt"), sb); } catch (Exception e) { System.err.println("Cannot save moddata.txt: " + e); }
    }

    private void die() {
        if (vehicles.riding()) vehicles.exitPlayer();
        if (!creative()) {
            ItemStack back = inv.back();
            if (back != null && back.hasTag()) back.tag().remove(PlayerInventory.BACK_SLOT_TAG);   // the gun on the back drops too
            for (int i = 0; i < inv.size(); i++) {
                ItemStack st = inv.get(i);
                if (st != null) { world.dropItem(player.x, player.y + 1, player.z, st); inv.set(i, null); }
            }
            if (stats.xpLevel > 0) stats.addXp(0);
        }
        mineProgress = 0;
        game.setScreen(new Containers.Death(game, this));
    }

    public void respawn() {
        stats.reset();
        if (EventBus.has(Events.PlayerRespawn.class)) EventBus.post(this, new Events.PlayerRespawn());
        WorldInfo info = world.info;
        double sx, sy, sz;
        if (info.spawnY >= 0) { sx = info.spawnX + 0.5; sy = info.spawnY; sz = info.spawnZ + 0.5; }
        else {
            int[] sp = chunks.generator.findSpawn();
            sx = sp[0] + 0.5; sz = sp[1] + 0.5;
            chunks.loadNow((int) Math.floor(sx) >> 4, (int) Math.floor(sz) >> 4, 1);
            sy = Math.max(world.topSolidY((int) Math.floor(sx), (int) Math.floor(sz)), chunks.generator.spawnY((int) Math.floor(sx), (int) Math.floor(sz)) - 1) + 1;
        }
        player.setPos(sx, sy, sz);
        player.fallDistance = 0; player.landed = 0;
        player.mx = player.my = player.mz = 0;
        player.stance = Stance.STAND;
        game.setScreen(null);
    }

    public void save() {
        storePlayer();
        saveWorldData();
        mobs.save();
        vehicles.save();
        chunks.saveAll();
    }

    private void storePlayer() {
        WorldInfo info = world.info;
        info.px = player.x; info.py = player.y; info.pz = player.z;
        info.yaw = player.yaw; info.pitch = player.pitch;
        info.flying = player.flying;
        info.flySpeed = player.flySpeedMultiplier;
        info.gameMode = gameMode;
        info.inventory = inv.serialize();
        if (info.inventory.isEmpty()) info.inventory = ";";
        info.enderChest = enderChest.serialize();
        info.health = stats.health; info.food = stats.food; info.saturation = stats.saturation;
        info.xpLevel = stats.xpLevel; info.xpProgress = stats.xpProgress;
    }

    public void close() {
        if (vehicles != null && vehicles.riding()) vehicles.exitPlayer();      // saved standing next to the car, not inside it
        storePlayer();
        saveWorldData();
        if (current == this) current = null;
        saveCamera();
        if (npcs.recording()) npcs.stopRecording();
        npcs.save();
        mobs.save();
        vehicles.close();
        if (EventBus.has(Events.WorldClose.class)) EventBus.post(this, new Events.WorldClose());
        if (voxels != null) voxels.delete();
        cameraNet.delete();
        if (game.entities.cameras == cameraNet) game.entities.cameras = null;
        chunks.close();
        renderer.close();
        lines.delete();
    }

    // ------------------------------------------------------------------------------------------ building
    private void updateTarget(float partial) {
        // from the eyes exactly where the camera is (the eye height eases while sneaking)
        float eye = player.prevEyeHeight + (player.eyeHeight - player.prevEyeHeight) * partial;
        double ex = lerp(player.px, player.x, partial), ey = lerp(player.py, player.y, partial) + eye, ez = lerp(player.pz, player.z, partial);
        double[] rideFeet = vehicles.playerFeetAt(partial);
        if (rideFeet != null) { ex = rideFeet[0]; ey = rideFeet[1] + eye; ez = rideFeet[2]; }
        double[] ce0 = vehicles.customEye(partial);
        if (ce0 != null) { ex = ce0[0]; ey = ce0[1]; ez = ce0[2]; }
        float lookYaw = player.yaw, lookPitch = player.pitch;
        float[] sw = vehicles.cameraSway(partial);           // in a car the crosshair sways with the view
        if (sw != null && !selfieActive()) {
            ey += sw[0];
            lookPitch = Math.max(-90, Math.min(90, lookPitch + sw[1]));
            lookYaw += wrap(vehicles.playerVehicle.yawAt(partial) - vehicles.playerVehicle.yaw);
        }
        float yr = (float) Math.toRadians(lookYaw), pr = (float) Math.toRadians(lookPitch);
        double dx = -Math.sin(yr) * Math.cos(pr), dy = -Math.sin(pr), dz = Math.cos(yr) * Math.cos(pr);
        target = Raycast.cast(world, ex, ey, ez, dx, dy, dz, 5.0, false);
        targetItem = null;
        targetVehicle = null;
        if (!spectator() && !vehicles.list().isEmpty()) {
            // a vehicle nearer than the block: the crosshair is on the car (doors, carried blocks, getting in)
            double vmax = target == null ? 5.0 : Math.sqrt((target.hx - ex) * (target.hx - ex) + (target.hy - ey) * (target.hy - ey) + (target.hz - ez) * (target.hz - ez));
            ItemStack hs = inv.held();
            targetVehicle = vehicles.pick(ex, ey, ez, dx, dy, dz, vmax, hs != null && hs.item.block != null);
            if (targetVehicle != null) target = null;
        }
        double reach = 3.5;
        if (target != null) reach = Math.min(reach, Math.sqrt((target.hx - ex) * (target.hx - ex) + (target.hy - ey) * (target.hy - ey) + (target.hz - ez) * (target.hz - ez)));
        if (targetVehicle != null) reach = Math.min(reach, targetVehicle.t);
        targetActor = spectator() ? null : npcs.pick(ex, ey, ez, dx, dy, dz, reach);
        targetMob = null;
        if (!spectator()) {
            double mr = targetActor != null ? targetActor.rayHit(ex, ey, ez, dx, dy, dz, reach) : reach;
            targetMob = mobs.pick(ex, ey, ez, dx, dy, dz, mr < 0 ? reach : mr);
            if (targetMob != null) targetActor = null;
            if (targetActor != null || targetMob != null) targetVehicle = null;
        }
        if (game.settings.pickupClick && !spectator()) {
            double max = 5.0;
            if (target != null) max = Math.sqrt((target.hx - ex) * (target.hx - ex) + (target.hy - ey) * (target.hy - ey) + (target.hz - ez) * (target.hz - ez));
            targetItem = findTargetItem(ex, ey, ez, dx, dy, dz, max);
        }
    }

    /** An NPC hit the player: damage in survival, knockback always. */
    public void npcHitPlayer(double ax, double az, float dmg) { hurtPlayer(dmg, "npc", ax, az); }

    /** The player's skin (Settings.playerSkin from the NPC skins list; Steve when it is gone), or null before the NPCs exist. */
    public derpcraft.npc.Skins.Skin playerSkin() {
        if (npcs == null) return null;
        String acting = npcs.actingSkin();          // recording "as the NPC": you wear its skin
        return npcs.skins.get(acting != null ? acting : game.settings.playerSkin);
    }

    /** Something hit the player from (fromX, fromZ): damage in survival, knockback always. */
    public void hurtPlayer(float dmg, String cause, double fromX, double fromZ) {
        if (spectator()) return;
        if (ticks < stats.safeUntil && !cause.equals("void")) return;        // just got out of a vehicle: nothing hurts or pushes for a second
        boolean hurt = !survival() || stats.damage(dmg, cause, inv.armorPoints(), ticks);
        if (!hurt) return;
        double kx = player.x - fromX, kz = player.z - fromZ, l = Math.sqrt(kx * kx + kz * kz) + 1e-6;
        if (!player.flying) pushPlayer(kx / l * 0.4, player.onGround ? 0.35 : 0, kz / l * 0.4);
    }

    /**
     * Once a second: the sounds of things around the player (fire, lava, water, a portal, a lit furnace) from a few random
     * blocks nearby, and now and then a cave sound when the player is deep under the surface.
     */
    private void ambientSounds() {
        if (spectator()) return;
        var r = world.random;
        int px = (int) Math.floor(player.x), py = (int) Math.floor(player.y), pz = (int) Math.floor(player.z);
        for (int i = 0; i < 24; i++) {
            int x = px + r.nextInt(17) - 8, y = py + r.nextInt(9) - 4, z = pz + r.nextInt(17) - 8;
            Block b = world.getBlock(x, y, z);
            if (b.isAir()) continue;
            String n = b.name;
            if (n.equals("fire") && r.nextInt(3) == 0) derpcraft.audio.Sfx.at(this, "fire/fire", x + 0.5, y + 0.5, z + 0.5, 0.6f);
            else if (n.equals("lava") || n.equals("flowing_lava")) {
                if (r.nextInt(40) == 0) derpcraft.audio.Sfx.at(this, "liquid/lavapop", x + 0.5, y + 0.8, z + 0.5, 0.6f);
                else if (r.nextInt(80) == 0) derpcraft.audio.Sfx.at(this, "liquid/lava", x + 0.5, y + 0.8, z + 0.5, 0.6f);
            } else if ((n.equals("water") || n.equals("flowing_water")) && r.nextInt(200) == 0) derpcraft.audio.Sfx.at(this, "liquid/water", x + 0.5, y + 0.5, z + 0.5, 0.4f);
            else if (n.contains("portal") && r.nextInt(8) == 0) derpcraft.audio.Sfx.at(this, "portal/portal", x + 0.5, y + 0.5, z + 0.5, 0.6f);
        }
        if (r.nextInt(45) == 0 && world.topSolidY(px, pz) > py + 6 && !player.inWater)
            derpcraft.audio.Sfx.play("ambient/cave/cave", 0.7f, 1f);
    }

    private int lastStride;
    private boolean wasInWaterSfx;

    /** Creature under the crosshair, or null. */
    public derpcraft.entity.Mob targetMob;

    /** NPC under the crosshair (within reach, nearer than the targeted block), or null. */
    public derpcraft.npc.Actor targetActor;

    /** True on the tick an arm swing started (for recordings). */
    public boolean swingStartedThisTick() { return swinging && swingTicks == 0; }

    /** Spawns an NPC on the block under the crosshair (or 3 blocks ahead), facing the player. */
    public derpcraft.npc.Actor spawnNpc(String name) {
        double x, y, z;
        if (target != null) {
            int f = target.face;
            x = target.x + derpcraft.block.Dir.DX[f] + 0.5; y = target.y + derpcraft.block.Dir.DY[f]; z = target.z + derpcraft.block.Dir.DZ[f] + 0.5;
            if (derpcraft.block.Dir.DY[f] < 0) y -= 0.8;
        } else {
            float yr = (float) Math.toRadians(player.yaw);
            x = player.x - Math.sin(yr) * 3; y = player.y; z = player.z + Math.cos(yr) * 3;
        }
        return npcs.spawn(name, x, y, z, player.yaw + 180);
    }

    public void swingArm() {
        if (!swinging || swingTicks >= 3 || swingTicks < 0) { swingTicks = -1; swinging = true; }
    }

    /** Survival mining: progress per tick like Minecraft (speed / hardness / 30, or / 100 without the right tool). */
    private void mineTick() {
        if (breakCooldown > 0) return;
        Raycast.Hit h = target;
        if (h == null) { mineProgress = 0; return; }
        if (h.x != mineX || h.y != mineY || h.z != mineZ) { mineX = h.x; mineY = h.y; mineZ = h.z; mineProgress = 0; }
        swingArm();
        float hard = h.block.hardness;
        if (hard < 0) return;
        float delta;
        if (hard == 0) delta = 1;
        else {
            ItemStack held = inv.held();
            Item it = held == null ? null : held.item;
            float speed = it == null ? 1 : it.speedAgainst(h.block);
            if (player.eyeInWater(world, 1)) speed /= 5;
            if (!player.onGround && !player.flying) speed /= 5;
            boolean harvest = !h.block.requiresTool || it != null && it.canHarvest(h.block);
            delta = speed / hard / (harvest ? 30f : 100f);
        }
        mineProgress += delta;
        if (ticks % 4 == 0) game.entities.spawnDigParticle(world, h);
        if (mineProgress >= 1) {
            mineProgress = 0;
            breakBlock();
            breakCooldown = 5;
        }
    }

    /** Breaks the targeted block (instantly in creative, after mining in survival). */
    public void breakBlock() {
        if (target == null || spectator()) return;
        breakCooldown = 5;
        swingArm();
        Raycast.Hit h = target;
        ItemStack held = inv.held();
        if (creative() && held != null && held.item.isTool(Item.ToolType.SWORD)) return;
        // item frame: the first punch takes the item out, the next breaks the frame
        if (h.block instanceof FunctionalBlocks.ItemFrameBlock && world.getBlockEntity(h.x, h.y, h.z) instanceof derpcraft.world.be.ItemFrameEntity fe && fe.item() != null) {
            world.dropItem(h.x + 0.5, h.y + 0.5, h.z + 0.5, fe.item());
            derpcraft.audio.Sfx.at(this, "entity/itemframe/remove_item", h.x + 0.5, h.y + 0.5, h.z + 0.5, 0.8f);
            fe.items.set(0, null);
            return;
        }
        Events.BlockBreak ev = new Events.BlockBreak(h.x, h.y, h.z, h.block);
        for (Events.BreakHandler bh : ModContext.BREAK) safe(() -> bh.onBreak(this, ev));
        EventBus.post(this, ev);
        if (ev.isCancelled()) return;
        harvest(h.x, h.y, h.z, survival());
        int wear = survival() && held != null ? held.item.wearOnBreak(h.block) : 0;
        if (wear > 0) {
            if (held.damageItem(wear)) { inv.set(inv.selected, held.count <= 0 ? null : held); derpcraft.audio.Sfx.play("random/break", 0.9f, 1f); }
            else inv.changed();
        }
        if (survival()) stats.addExhaustion(0.005f);
        updateTarget(1);
    }

    /** Removes a block with particles, drops (when 'drops') and support checks for blocks around it. */
    public void harvest(int x, int y, int z, boolean drops) {
        int st = world.getState(x, y, z);
        Block b = Blocks.byId(st >> 4);
        if (b.isAir()) return;
        int meta = st & 15;
        game.entities.spawnBreakParticles(world, x, y, z, b, meta);
        if (drops) {
            ItemStack held = inv.held();
            Item it = held == null ? null : held.item;
            boolean canHarvest = !b.requiresTool || it != null && it.canHarvest(b);
            if (canHarvest) {
                List<ItemStack> list;
                Item self = itemFor(b);
                boolean shearable = b.name.endsWith("leaves") || b.name.endsWith("leaves2") || b.name.equals("web") || b.name.equals("vine")
                        || b.name.equals("tall_grass") || b.name.equals("fern") || b.name.equals("dead_bush");
                if (it != null && it.isTool(Item.ToolType.SHEARS) && shearable && (self != null || b.name.equals("web")))
                    list = List.of(new ItemStack(self != null ? self : Items.get("string"), 1));
                else list = b.dropsWithEntity(world, x, y, z, meta, world.random, world.getBlockEntity(x, y, z));
                for (ItemStack d : list) world.dropItem(x + 0.5, y + 0.3, z + 0.5, d);
            }
        } else if (b instanceof derpcraft.block.ModBlock mb && mb.persistent && world.getBlockEntity(x, y, z) instanceof derpcraft.world.be.ModBlockEntity be
                && (be.items != null && !be.items.isEmpty() || !be.data.isEmpty())) {
            // like shulker boxes: a filled portable container drops even in creative
            for (ItemStack d : b.dropsWithEntity(world, x, y, z, meta, world.random, be)) world.dropItem(x + 0.5, y + 0.3, z + 0.5, d);
        }
        derpcraft.audio.Sfx.dig(this, b, x, y, z, 1f);
        world.setBlock(x, y, z, 0);
        b.onRemoved(world, x, y, z, meta);
        checkSupport(x, y, z, drops);
    }

    /** Something other than the player removed a block (explosion...): the plants, torches, signs... that stood on it fall off and drop. */
    public void breakDependents(int x, int y, int z) { checkSupport(x, y, z, true); }

    /** Breaks neighbours that can no longer stay (plants, torches, doors, rails, signs...). */
    private void checkSupport(int x, int y, int z, boolean drops) {
        // a block that lost its support always drops as an item, also in creative (like the original): the broken block
        // itself drops nothing there, but the torch on it must not just vanish
        if (creative()) drops = true;
        // item frames hang on any side of a block, also the top and the bottom
        for (int d = 0; d < 6; d++) {
            int nx = x + Dir.DX[d], ny = y + Dir.DY[d], nz = z + Dir.DZ[d];
            int st = world.getState(nx, ny, nz);
            if (Blocks.byId(st >> 4) instanceof FunctionalBlocks.ItemFrameBlock && FunctionalBlocks.ItemFrameBlock.back(st & 15) == Dir.OPPOSITE[d]) harvest(nx, ny, nz, drops);
        }
        for (int d = 1; d < 6; d++) {
            int nx = x + Dir.DX[d], ny = y + Dir.DY[d], nz = z + Dir.DZ[d];
            int st = world.getState(nx, ny, nz);
            Block n = Blocks.byId(st >> 4);
            int meta = st & 15;
            if (n.isAir()) continue;
            if (d == Dir.UP && n instanceof derpcraft.block.GravityBlock) { world.scheduleTick(nx, ny, nz, 2); continue; }
            boolean wallThing = n instanceof FunctionalBlocks.LadderBlock || n instanceof FunctionalBlocks.VineBlock
                    || n instanceof FunctionalBlocks.SignBlock sb && sb.wall || n.shape == Block.Shape.TORCH && meta >= 1 && meta <= 8;
            boolean falls;
            if (d == Dir.UP) {
                falls = !wallThing && (n.needsSupport || n.shape == Block.Shape.CROSS || n.shape == Block.Shape.SNOW_LAYER
                        || n.shape == Block.Shape.CARPET || n.shape == Block.Shape.TORCH || n instanceof FunctionalBlocks.PlateBlock);
            } else {
                int towardRemoved = Dir.OPPOSITE[d];
                if (n.shape == Block.Shape.TORCH && meta >= 1 && meta <= 8) falls = Dir.HORIZONTAL[(meta - 1) & 3] == towardRemoved;
                else if (n instanceof FunctionalBlocks.SignBlock sb && sb.wall) {
                    int back = switch (meta & 3) { case 1 -> Dir.EAST; case 2 -> Dir.SOUTH; case 3 -> Dir.WEST; default -> Dir.NORTH; };
                    falls = back == towardRemoved;
                } else if (n instanceof FunctionalBlocks.LadderBlock) falls = !n.canPlaceAt(world, nx, ny, nz, meta);
                else falls = false;
            }
            if (falls) harvest(nx, ny, nz, drops);
        }
    }

    // ------------------------------------------------------------------------------------------ bow
    /** Ticks the right mouse button has been held with a bow (draw strength). */
    public int bowCharge;

    /** Slot of the arrow to shoot (fire arrows first), or -1. */
    private int arrowSlot() {
        int best = -1;
        for (int i = 0; i < PlayerInventory.MAIN; i++) {
            ItemStack st = inv.get(i);
            if (st == null || st.count <= 0) continue;
            if (st.item.name.equals("fire_arrow")) return i;
            if (st.item.name.equals("arrow") && best < 0) best = i;
        }
        return best;
    }

    /** Hold right click with a bow to draw it, let go to shoot (needs an arrow; creative: free). */
    private void tickBow(boolean down) {
        ItemStack h = inv.held();
        boolean bow = h != null && h.item.name.equals("bow") && !spectator() && !stats.dead && !(game.screen != null);
        if (bow && down && (creative() || arrowSlot() >= 0)) { if (bowCharge < 100) bowCharge++; return; }
        if (bow && bowCharge > 0) shootBow(bowCharge);
        bowCharge = 0;
    }

    private void shootBow(int charge) {
        float f = charge / 20f;
        f = (f * f + f * 2) / 3;
        if (f < 0.1f) return;
        if (f > 1) f = 1;
        int slot = arrowSlot();
        boolean fire = slot >= 0 && inv.get(slot).item.name.equals("fire_arrow");
        if (slot < 0 && !creative()) return;
        if (!creative() && slot >= 0) {
            ItemStack st = inv.get(slot);
            st.count--;
            inv.set(slot, st.count <= 0 ? null : st);
        }
        String id = (fire ? derpcraft.entity.ProjectileType.FIRE_ARROW : derpcraft.entity.ProjectileType.ARROW).id;
        float yr = (float) Math.toRadians(player.yaw), pr = (float) Math.toRadians(player.pitch);
        double dx = -Math.sin(yr) * Math.cos(pr), dy = -Math.sin(pr), dz = Math.cos(yr) * Math.cos(pr);
        float speed = f * 2.8f;
        var p = mobs.shoot(id, player.x + dx * 0.4, player.y + player.eyeHeight - 0.1 + dy * 0.4, player.z + dz * 0.4, dx, dy, dz, speed, true, null);
        if (npcs.recording()) npcs.recordItem(2, id, Math.round(speed * 100), dx, dy, dz);
        if (p != null) { p.dmgScale = 0.35f + 0.65f * f; p.mx += player.mx; p.mz += player.mz; }
        derpcraft.audio.Sfx.play("random/bow", 0.9f, 0.9f + f * 0.2f);
        if (!creative()) damageHeld(1);
        swingArm();
    }

    /** Alt + 1..4: stand, sit, lie down, crawl. The camera and the hit box follow smoothly (see {@link Player#tick}). */
    public void setStance(Stance st) {
        if (spectator() || stats.dead) return;
        player.stance = st;
    }

    /** Whether an Alt key is held (placing torches straight out, throwing torches). */
    public boolean altDown() {
        long win = game.window;
        return glfwGetKey(win, GLFW_KEY_LEFT_ALT) == GLFW_PRESS || glfwGetKey(win, GLFW_KEY_RIGHT_ALT) == GLFW_PRESS;
    }

    /** Right click: use the block, then the held item (food, buckets, hoes, bone meal...), then place it. */
    public void useItem() {
        if (spectator() || stats.dead) return;
        placeCooldown = 4;
        Raycast.Hit h = target;
        ItemStack held = inv.held();
        if (h != null && (!ModContext.INTERACT.isEmpty() || EventBus.has(Events.BlockInteract.class))) {
            Events.BlockInteract ev = new Events.BlockInteract(h.x, h.y, h.z, h.block, held);
            for (Events.InteractHandler ih : ModContext.INTERACT) safe(() -> ih.onInteract(this, ev));
            EventBus.post(this, ev);
            if (ev.isCancelled()) return;
        }
        if (h != null && !(player.sneaking && held != null)) {
            if (h.block.onUse(this, h.x, h.y, h.z, h.meta)) { swingArm(); return; }
        }
        if (held == null) return;
        if (!ModContext.ITEM_USE.isEmpty() || EventBus.has(Events.ItemUse.class)) {
            Events.ItemUse ev = new Events.ItemUse(held, h == null ? null : blockAt(h.x, h.y, h.z));
            for (Events.ItemUseHandler ih : ModContext.ITEM_USE) safe(() -> ih.onItemUse(this, ev));
            EventBus.post(this, ev);
            if (ev.isCancelled()) return;
        }
        Item it = held.item;
        if (it.isFood()) {
            if (stats.needsFood() || it.alwaysEdible || creative()) eatTicks = 1;
            return;
        }
        if (specialUse(held, h)) { swingArm(); return; }
        if (h != null && it.use(this, held, h)) swingArm();
    }

    private void eatTick() {
        ItemStack held = inv.held();
        if (held == null || !held.item.isFood()) { eatTicks = 0; return; }
        if (eatTicks > 0 && eatTicks % 4 == 0) derpcraft.audio.Sfx.play("random/eat", 0.5f, 0.9f + world.random.nextFloat() * 0.2f);
        if (++eatTicks >= 32) {
            eatTicks = 0;
            stats.eat(held.item.food, held.item.saturation);
            derpcraft.audio.Sfx.play("random/burp", 0.8f, 1f);
            ItemStack container = held.item.container;
            consumeHeld(1);
            if (container != null && !creative()) giveOrDrop(container.copy());
            placeCooldown = 4;
        }
    }

    /** Adds to the inventory, dropping what doesn't fit. */
    public void giveOrDrop(ItemStack s) {
        ItemStack left = inv.addItem(s);
        if (left != null) drop(left, false);
    }

    private boolean specialUse(ItemStack held, Raycast.Hit h) {
        Item it = held.item;
        String n = it.name;
        if (n.equals("bucket")) {
            Raycast.Hit lh = castLiquid();
            if (lh == null || (lh.block != Blocks.WATER && lh.block != Blocks.LAVA) || lh.meta != 0) return false;
            Item filled = Items.byName(lh.block == Blocks.WATER ? "water_bucket" : "lava_bucket");
            world.setBlock(lh.x, lh.y, lh.z, 0);
            derpcraft.audio.Sfx.play(lh.block == Blocks.WATER ? "item/bucket/fill" : "item/bucket/fill_lava", 0.9f, 1f);
            if (!creative()) {
                if (held.count == 1) inv.set(inv.selected, new ItemStack(filled));
                else { consumeHeld(1); giveOrDrop(new ItemStack(filled)); }
            }
            return true;
        }
        if (h == null) return false;
        int ax = h.x + Dir.DX[h.face], ay = h.y + Dir.DY[h.face], az = h.z + Dir.DZ[h.face];
        if (h.block.replaceable) { ax = h.x; ay = h.y; az = h.z; }
        if (n.equals("water_bucket") || n.equals("lava_bucket")) {
            if (!world.getBlock(ax, ay, az).replaceable) return false;
            world.setBlock(ax, ay, az, n.equals("water_bucket") ? Blocks.WATER : Blocks.LAVA, 0);
            derpcraft.audio.Sfx.play(n.equals("water_bucket") ? "item/bucket/empty" : "item/bucket/empty_lava", 0.9f, 1f);
            if (!creative()) inv.set(inv.selected, new ItemStack(Items.get("bucket")));
            return true;
        }
        if (it.isTool(Item.ToolType.HOE)) {
            if ((h.block == Blocks.GRASS || h.block == Blocks.DIRT && h.meta == 0) && h.face != Dir.DOWN
                    && world.getBlock(h.x, h.y + 1, h.z).isAir()) {
                world.setBlock(h.x, h.y, h.z, Blocks.byName("farmland"), 0);
                derpcraft.audio.Sfx.at(this, "item/hoe/till", h.x + 0.5, h.y + 1, h.z + 0.5, 1f);
                damageHeld(1);
                return true;
            }
            return false;
        }
        if (n.equals("bone_meal")) {
            boolean used = false;
            if (h.block instanceof FunctionalBlocks.CropBlock c) used = c.grow(world, h.x, h.y, h.z, h.meta, world.random);
            else if (h.block instanceof FunctionalBlocks.SaplingBlock sp) { sp.grow(world, h.x, h.y, h.z, world.random); used = true; }
            else if (h.block == Blocks.GRASS && world.getBlock(h.x, h.y + 1, h.z).isAir()) {
                Block tall = Blocks.TALL_GRASS, dand = Blocks.DANDELION, rose = Blocks.POPPY;   // (the old 1.12 ids never matched)
                for (int i = 0; i < 24; i++) {
                    int bx = h.x + world.random.nextInt(7) - 3, bz = h.z + world.random.nextInt(7) - 3;
                    for (int by = h.y + 2; by >= h.y - 1; by--) {
                        if (world.getBlock(bx, by, bz) == Blocks.GRASS && world.getBlock(bx, by + 1, bz).isAir()) {
                            int roll = world.random.nextInt(10);
                            Block p = roll < 8 ? tall : roll == 8 ? dand : rose;
                            if (p != null) world.setBlock(bx, by + 1, bz, p, 0);
                            break;
                        }
                    }
                }
                used = true;
            }
            if (used) consumeHeld(1);
            return used;
        }
        if (h != null && h.block == Blocks.TNT && (n.equals("flint_and_steel") || n.equals("admin_flint_and_steel"))) {
            primeTnt(h.x, h.y, h.z, 80);              // flint and steel on TNT lights it
            if (n.equals("flint_and_steel")) damageHeld(1);
            return true;
        }
        if (n.equals("flint_and_steel")) {
            Block at = world.getBlock(ax, ay, az);
            if (!at.isAir() && !(at.replaceable && at.shape != Block.Shape.LIQUID && at != FunctionalBlocks.FireBlock.INSTANCE)) return false;
            if (!FunctionalBlocks.FireBlock.ignite(world, ax, ay, az, false)) return false;
            derpcraft.audio.Sfx.at(this, "fire/ignite", ax + 0.5, ay + 0.5, az + 0.5, 0.9f);
            damageHeld(1);
            return true;
        }
        if (n.equals("admin_flint_and_steel")) {
            // eternal fire: burns for ever, leaves soot and smoke, never spreads or destroys anything; on an ordinary fire it makes that one eternal
            return FunctionalBlocks.FireBlock.ignite(world, ax, ay, az, true);
        }
        if (it.armorSlot >= 0) {
            int slot = PlayerInventory.ARMOR + it.armorSlot;
            if (inv.get(slot) != null) return false;
            derpcraft.audio.Sfx.armor(it.name);
            inv.set(slot, held.copy());
            inv.set(inv.selected, null);
            return true;
        }
        return false;
    }

    private void damageHeld(int n) {
        if (creative()) return;
        ItemStack held = inv.held();
        if (held == null) return;
        if (held.damageItem(n)) inv.set(inv.selected, held.count <= 0 ? null : held);
        else inv.changed();
    }

    private Raycast.Hit castLiquid() {
        double ex = player.x, ey = player.y + player.eyeHeight, ez = player.z;
        float yr = (float) Math.toRadians(player.yaw), pr = (float) Math.toRadians(player.pitch);
        return Raycast.cast(world, ex, ey, ez, -Math.sin(yr) * Math.cos(pr), -Math.sin(pr), Math.cos(yr) * Math.cos(pr), 5.0, true);
    }

    /**
     * Places a block from an item (called by {@link Item#use}). Handles wall signs, support checks, block entities and
     * using up the item in survival. Returns true if something was placed.
     */
    public boolean placeBlock(Block b, ItemStack stack, Raycast.Hit h) {
        if (h == null || b == null || spectator()) return false;
        int x = h.x, y = h.y, z = h.z;
        if (!h.block.replaceable) { x += Dir.DX[h.face]; y += Dir.DY[h.face]; z += Dir.DZ[h.face]; }
        if (y < 0 || y > 255) return false;
        Block existing = world.getBlock(x, y, z);
        if (!existing.replaceable || existing == b && b.shape != Block.Shape.SLAB) return false;
        float hitY = (float) (h.hy - Math.floor(h.hy));
        if (h.face == Dir.UP) hitY = 0;
        if (h.face == Dir.DOWN) hitY = 1;
        int meta = b.placementMeta(h.face, hitY, player.yaw);
        // torches on a wall lean 22.5 degrees; with Alt held they stick straight out (90 degrees)
        if (b.placement == Block.Placement.TORCH && meta >= 1 && meta <= 4 && altDown()) meta += 4;
        if (b instanceof FunctionalBlocks.SignBlock) {
            if (h.face == Dir.DOWN) return false;
            if (h.face == Dir.UP) meta = (int) Math.floor((player.yaw + 180) * 16 / 360 + 0.5) & 15;
            else {
                b = Blocks.byName("wall_sign");
                meta = switch (h.face) { case Dir.WEST -> 1; case Dir.NORTH -> 2; case Dir.EAST -> 3; default -> 0; };
            }
        }
        if (b instanceof FunctionalBlocks.ItemFrameBlock && !h.block.replaceable) meta = Dir.OPPOSITE[h.face];   // hangs on the block that was clicked
        if (!b.canPlaceAt(world, x, y, z, meta)) return false;
        if (b.shape == Block.Shape.CROSS && b.needsSupport && !world.getBlock(x, y - 1, z).solid) return false;
        if (b.solid && !player.noclip) {
            double[] bb = player.aabb();
            for (float[] c : b.collisionShape(meta, 0))
                if (bb[0] < x + c[3] && bb[3] > x + c[0] && bb[1] < y + c[4] && bb[4] > y + c[1] && bb[2] < z + c[5] && bb[5] > z + c[2]) return false;
        }
        Events.BlockPlace ev = new Events.BlockPlace(x, y, z, b, meta);
        for (Events.PlaceHandler ph : ModContext.PLACE) safe(() -> ph.onPlace(this, ev));
        EventBus.post(this, ev);
        if (ev.isCancelled() || ev.block == null) return false;
        world.setBlock(x, y, z, ev.block, ev.meta);
        derpcraft.audio.Sfx.dig(this, ev.block, x, y, z, 0.9f, true);
        ev.block.placedFromItem(world, x, y, z, stack);
        ev.block.onPlaced(world, x, y, z, ev.meta);
        var be = world.getBlockEntity(x, y, z);
        if (stack != null && !creative()) {
            stack.count--;
            inv.set(inv.selected, stack.count <= 0 ? null : stack);
        }
        if (be instanceof SignEntity se) game.setScreen(new Containers.SignEdit(game, se));
        if (be instanceof derpcraft.world.be.ModBlockEntity mbe && mbe.lines != null && mbe.spec.editOnPlace && !(stack != null && stack.hasTag() && stack.tag().has("text"))) openModBlock(x, y, z);
        updateTarget(1);
        return true;
    }

    /** Legacy entry point (mods, key bindings): places the held item at the target. */

    public void pickBlock() {
        if (target == null || spectator()) return;
        Item it = itemFor(target.block);
        if (it == null) return;
        if (creative()) { inv.pick(it); nameTicks = 40; return; }
        for (int i = 0; i < PlayerInventory.HOTBAR; i++) if (inv.get(i) != null && inv.get(i).item == it) { inv.selected = i; return; }
        for (int i = PlayerInventory.HOTBAR; i < PlayerInventory.MAIN; i++)
            if (inv.get(i) != null && inv.get(i).item == it) {
                ItemStack a = inv.get(i);
                inv.set(i, inv.held());
                inv.set(inv.selected, a);
                return;
            }
    }

    public void select(int slot) { inv.selected = Math.floorMod(slot, 9); }

    // ------------------------------------------------------------------------------------------ gun on the back (Left Alt + F)
    /** Ticks of the arm reaching over the shoulder (third person, takes) and when in it the drawn gun gets to the hand. */
    public static final int BACK_REACH = 9, BACK_DRAW = 4;
    /** Ticks since the arm started reaching for the back (-1 = not reaching); ticks left until the drawn gun is in the hand (0 = none). */
    private int backReach = -1, backDrawTicks;

    /** The gun carried on the back, or null. */
    public ItemStack backStack() { return inv.back(); }

    /** Whether the arm is reaching for the back now (recorded in takes). */
    public boolean backReaching() { return backReach >= 0; }

    /** How far the arm is up behind the shoulder (0..1), for the third person model. */
    private float backReachAmount(float partial) {
        if (backReach < 0) return 0;
        float t = Math.min(1, (backReach + partial) / BACK_REACH);
        return (float) Math.sin(Math.PI * t);
    }

    /**
     * Left Alt + F. A gun in the hand goes onto the back: its slot empties (the slot is kept in the gun's tag). With a gun
     * on the back it comes back: into the slot it was taken from, selected, in the hand (the arm reaches over the
     * shoulder first). A gun in the hand while another one is on the back: they change places.
     */
    public void toggleBackGun() {
        if (spectator() || stats.dead || backDrawTicks > 0) return;
        ItemStack back = inv.back();
        ItemStack held = inv.held();
        boolean heldGun = held != null && derpcraft.gun.Guns.of(held) != null;
        if (back == null) {
            if (!heldGun) { chat("§7Take a gun in the hand to put it on the back (" + Keys.n(Keys.BACK_GUN) + ")"); return; }
            putOnBack(inv.selected);
            return;
        }
        backDrawTicks = BACK_DRAW;            // the arm goes up first; drawBackGun() at the top of the reach
        backReach = 0;
    }

    /** The gun in slot 'slot' goes onto the back (the back must be empty). */
    private void putOnBack(int slot) {
        ItemStack g = inv.get(slot);
        if (g == null) return;
        g.tag().put(PlayerInventory.BACK_SLOT_TAG, slot);
        inv.set(slot, null);
        inv.set(PlayerInventory.BACK, g);
        backReach = 0;
        guns.backSound(derpcraft.gun.Guns.of(g));
    }

    /** The gun on the back comes to its slot and the hand (a gun in the hand goes onto the back in its place). */
    private void drawBackGun() {
        ItemStack back = inv.back();
        if (back == null || stats.dead) return;
        int slot = back.hasTag() ? back.tag().getInt(PlayerInventory.BACK_SLOT_TAG, inv.selected) : inv.selected;
        if (slot < 0 || slot >= PlayerInventory.MAIN) slot = inv.selected;
        ItemStack held = inv.held();
        boolean swap = held != null && derpcraft.gun.Guns.of(held) != null;
        int heldSlot = inv.selected;
        // where the gun goes: its own slot; if something else lies there, that moves to a free slot (hotbar first)
        ItemStack occupant = swap && slot == heldSlot ? null : inv.get(slot);
        int free = -1;
        if (occupant != null) {
            for (int i = 0; i < PlayerInventory.MAIN && free < 0; i++)
                if (i != slot && inv.get(i) == null && !(swap && i == heldSlot)) free = i;
            if (free < 0 && swap) free = heldSlot;        // the gun from the hand goes to the back, its slot is free
            if (free < 0) { chat("§cNo free slot for " + occupant.displayName() + ": the gun stays on the back"); return; }
        }
        inv.set(PlayerInventory.BACK, null);
        back.tag().remove(PlayerInventory.BACK_SLOT_TAG);
        if (swap) {                                       // the gun in the hand takes the place on the back
            held.tag().put(PlayerInventory.BACK_SLOT_TAG, heldSlot);
            inv.set(heldSlot, null);
            inv.set(PlayerInventory.BACK, held);
        }
        if (occupant != null) inv.set(free, occupant);
        inv.set(slot, back);
        if (slot < PlayerInventory.HOTBAR) inv.selected = slot;
        else if (inv.held() == null) { inv.set(inv.selected, back); inv.set(slot, null); }   // from the main inventory: into the hand's slot
        else {                                            // the hand's slot is busy: they change places
            ItemStack h = inv.held();
            inv.set(inv.selected, back);
            inv.set(slot, h);
        }
        nameTicks = 40;
    }

    // ------------------------------------------------------------------ bleeding (Left Alt + B)
    /** Bleeding: blood drips from the bottom of the view (first person) or the body, leaving drops and smears on the ground. */
    public boolean bleeding;
    private int bleedTick;
    /** Where on the bottom quarter of the view the blood comes from: three streams (across the view -0.85..0.85). */
    private final double[] bleedAt = new double[3];
    private float lastFov = 70, lastAspect = 16 / 9f;

    public void toggleBleeding() {
        if (stats.dead || spectator()) return;
        if (!game.settings.blood) { chat("§7Blood is off (Options -> Gameplay)"); return; }
        bleeding = !bleeding;
        bleedTick = 0;
        java.util.Random r = world.random;
        for (int i = 0; i < bleedAt.length; i++) bleedAt[i] = (i - 1) * 0.55 + (r.nextDouble() - 0.5) * 0.3;
    }

    /** Every tick while bleeding: drops from the lower quarter of the view, a smear on the ground under the feet. */
    private void tickBleeding() {
        if (!bleeding) return;
        if (stats.dead || spectator() || !game.settings.blood) { bleeding = false; return; }
        bleedTick++;
        var er = game.entities;
        java.util.Random r = world.random;
        double mvx = player.x - player.px, mvy = player.y - player.py, mvz = player.z - player.pz;
        boolean firstPerson = view == 0 && !cam.detached() && !selfieActive();
        if (firstPerson) {
            double yr = Math.toRadians(lastCamYaw), pr = Math.toRadians(lastCamPitch);
            double sy = Math.sin(yr), cy = Math.cos(yr), sp = Math.sin(pr), cp = Math.cos(pr);
            double fx = -sy * cp, fy = -sp, fz = cy * cp;               // forward
            double rx = -cy, rz = -sy;                                  // right
            double ux = -sy * sp, uy = cp, uz = cy * sp;                // up
            double d = 0.6, halfH = d * Math.tan(Math.toRadians(lastFov) / 2), halfW = halfH * lastAspect;
            for (double at : bleedAt) {
                if (r.nextInt(6) != 0) continue;
                double hx = (at + (r.nextDouble() - 0.5) * 0.08) * halfW;
                double vy = -halfH * (0.5 + 0.5 * r.nextDouble());     // the lower quarter of the view
                float size = r.nextInt(6) == 0 ? 0.03f : 0.012f + r.nextFloat() * 0.012f;
                er.bleedDrop(lastCamX + fx * d + rx * hx + ux * vy, lastCamY + fy * d + uy * vy, lastCamZ + fz * d + rz * hx + uz * vy,
                        mvx, mvy * 0.5 - 0.01, mvz, size);
            }
        } else if (r.nextInt(3) == 0) {
            // seen from outside: from the chest and the belly
            er.bleedDrop(player.x + (r.nextDouble() - 0.5) * 0.35, player.y + 0.7 + r.nextDouble() * 0.5, player.z + (r.nextDouble() - 0.5) * 0.35,
                    mvx, 0, mvz, 0.012f + r.nextFloat() * 0.014f);
        }
        // on the ground under the feet: a smear while moving, a growing pool while standing
        boolean moving = mvx * mvx + mvz * mvz > 0.0009;
        if (player.onGround && bleedTick % (moving ? 5 : 14) == 0 && !vehicles.riding())
            er.bleedDrop(player.x + (r.nextDouble() - 0.5) * 0.2, player.y + 0.25, player.z + (r.nextDouble() - 0.5) * 0.2, 0, -0.1, 0,
                    moving ? 0.06f + r.nextFloat() * 0.03f : 0.035f + r.nextFloat() * 0.02f);
    }

    /** Every tick: the reach for the back and a draw under way. */
    private void tickBackGun() {
        if (stats.dead) { backReach = -1; backDrawTicks = 0; return; }
        if (backReach >= 0 && ++backReach >= BACK_REACH) backReach = -1;
        if (backDrawTicks > 0 && --backDrawTicks == 0) drawBackGun();
    }

    /** Throws the held item (Q) or the whole stack (Ctrl+Q). */
    public void dropHeld(boolean all) {
        ItemStack h = inv.held();
        if (h == null || spectator()) return;
        ItemStack out = all ? h : h.split(1);
        inv.set(inv.selected, all || h.count <= 0 ? null : h);
        if (npcs.recording()) {
            float yr = (float) Math.toRadians(player.yaw), pr = (float) Math.toRadians(player.pitch);
            npcs.recordItem(1, out.item.name, out.count, -Math.sin(yr) * Math.cos(pr), -Math.sin(pr), Math.cos(yr) * Math.cos(pr));
        }
        drop(out, true);
        swingArm();
    }

    /** Spawns an item in front of the player. */
    public void drop(ItemStack s, boolean thrown) {
        if (ItemStack.isEmpty(s)) return;
        float yr = (float) Math.toRadians(player.yaw), pr = (float) Math.toRadians(player.pitch);
        ItemEntity e = new ItemEntity(world, player.x, player.y + player.eyeHeight - 0.3, player.z, s.copy());
        double f = 0.3;
        e.mx = -Math.sin(yr) * Math.cos(pr) * f + (world.random.nextDouble() - 0.5) * 0.02;
        e.mz = Math.cos(yr) * Math.cos(pr) * f + (world.random.nextDouble() - 0.5) * 0.02;
        e.my = -Math.sin(pr) * f + 0.1;
        e.pickupDelay = 40;
        world.entities.add(e);
    }

    // ------------------------------------------------------------------------------------------ containers
    public void openCrafting() { game.setScreen(new Containers.Crafting(game, this)); }
    /** The sound folder of a container by its title: chest, enderchest, shulker_box. */
    public static String chestSound(String title) {
        String t = title == null ? "" : title.toLowerCase(java.util.Locale.ROOT);
        return "block/" + (t.contains("ender") ? "enderchest" : t.contains("shulker") ? "shulker_box" : "chest");
    }

    public void openChest(ContainerEntity c, String title) {
        derpcraft.audio.Sfx.play(chestSound(title) + "/open", 0.8f, 0.95f);
        game.setScreen(new Containers.Chest(game, this, title.equals("Trapped Chest") ? "Chest" : title, c.items, c));
    }
    public void openDoubleChest(ContainerEntity top, ContainerEntity bottom) {
        game.setScreen(new Containers.Chest(game, this, "Large Chest", new Containers.Joined(top.items, bottom.items), top, bottom));
    }
    public void openEnderChest(ContainerEntity c) { game.setScreen(new Containers.Chest(game, this, "Ender Chest", enderChest, c)); }
    public void openStorage(ContainerEntity c, String title) {
        if (c.type().equals("hopper")) game.setScreen(new Containers.Hopper(game, this, c));
        else game.setScreen(new Containers.Dispenser(game, this, title, c));
    }
    public void openFurnace(FurnaceEntity f) { game.setScreen(new Containers.Furnace(game, this, f)); }

    /** Opens the GUI of a mod block: storage, machine or text editor. */
    public void openModBlock(int x, int y, int z) {
        if (!(world.getBlockEntity(x, y, z) instanceof derpcraft.world.be.ModBlockEntity be)) return;
        var spec = be.spec;
        String title = spec.storageTitle != null ? spec.storageTitle : spec.displayName;
        if (spec.machineType != null) game.setScreen(new Containers.Machine(game, this, title, be));
        else if (spec.storageSlots > 0) game.setScreen(new Containers.Storage(game, this, title, be));
        else if (be.lines != null) game.setScreen(new Containers.TextEdit(game, "Edit " + spec.displayName + ":", be.lines, spec.textMaxWidth, spec.textColor, be::markDirty));
    }
    public void openInventory() {
        if (creative()) game.setScreen(new Containers.Creative(game, this));
        else if (survival()) game.setScreen(new Containers.PlayerInv(game, this));
    }

    /** Beds: skip the night and set the respawn point. */
    public void trySleep(int x, int y, int z) {
        long t = world.info.time % 24000;
        world.info.spawnX = x; world.info.spawnY = y + 1; world.info.spawnZ = z;
        if (t < 12541 || t > 23458) { chat("You can only sleep at night"); return; }
        world.info.time += 24000 - t;
        chat("Respawn point set");
    }

    // ------------------------------------------------------------------------------------------ input
    /** A menu opened or closed: the cinematic mouse smoothing starts from rest (no drift or jump afterwards). */
    public void resetMouseSmoothing() { smoothDX = smoothDY = 0; }

    public void mouseMoved(double dx, double dy, float dt) {
        float f = game.settings.sensitivity * 0.6f + 0.2f;
        float f1 = f * f * f * 8.0f * 0.15f;
        if (zoom < 0.9f) f1 *= zoom;
        // long lenses: slower look so framing stays controllable
        if (lastFocal > 50) f1 *= 50f / lastFocal;
        f1 *= guns.sensitivity();
        if (cinematic) {
            float a = 1 - (float) Math.exp(-dt * 4);
            smoothDX += (dx - smoothDX) * a;
            smoothDY += (dy - smoothDY) * a;
            dx = smoothDX; dy = smoothDY;
        }
        if (!cam.controlsPlayer()) { cam.look((float) dx * f1, (float) dy * f1); return; }
        player.yaw += (float) dx * f1;
        player.pitch = Math.max(-90, Math.min(90, player.pitch + (float) dy * f1));
    }

    /** Set when a menu closes: held mouse buttons do nothing until they are released. */
    public boolean mouseBlocked;

    private static boolean isTorchThrow(ItemStack h) {
        return h != null && (h.item.name.equals("dynamite") || h.item.isBlockItem() && (h.item.block == Blocks.TORCH || h.item.name.equals("redstone_torch")));
    }

    /** Lights the TNT block at x,y,z: it turns into a primed TNT (hops, falls, explodes after 'fuse' ticks). */
    public void primeTnt(int x, int y, int z, int fuse) {
        if (world.getBlock(x, y, z) != Blocks.TNT) return;
        derpcraft.audio.Sfx.at(this, "random/fuse", x + 0.5, y + 0.5, z + 0.5, 1f, 1f);
        world.setBlock(x, y, z, 0);
        spawnPrimedTnt(x + 0.5, y + 0.06, z + 0.5, fuse, 0, 0);
        checkSupport(x, y, z, true);
    }

    /** A primed TNT at a position (the centre of the bottom of the block); (kx, kz) = sideways push, 0 = a small random one. */
    public void spawnPrimedTnt(double x, double y, double z, int fuse, double kx, double kz) {
        if (kx == 0 && kz == 0) { kx = (world.random.nextDouble() - 0.5) * 0.6; kz = (world.random.nextDouble() - 0.5) * 0.6; }
        var p = mobs.shoot(derpcraft.entity.ProjectileType.TNT_PRIMED.id, x, y, z, kx, 1, kz, 0.2f, false, null);
        if (p != null) p.fuse = fuse;
    }

    /** Alt + left click with a torch: throws it (it hurts and ignites what it hits, lights the place where it lands and burns out). */
    private boolean throwTorch() {
        if (!isTorchThrow(inv.held()) || spectator() || stats.dead) return false;
        if (breakCooldown > 0) return true;
        String thrown = inv.held().item.name;
        boolean dynamite = thrown.equals("dynamite");
        shootFromPlayer((dynamite ? derpcraft.entity.ProjectileType.DYNAMITE : thrown.equals("redstone_torch") ? derpcraft.entity.ProjectileType.THROWN_FLARE
                : derpcraft.entity.ProjectileType.THROWN_TORCH).id, dynamite ? 0.8f : 0.9f);
        consumeHeld(1);
        breakCooldown = 8;
        return true;
    }

    public void mouseButton(int button) {
        if (stats.dead || cam.detached()) return;
        if (guns.holding() && (button == GLFW_MOUSE_BUTTON_LEFT || button == GLFW_MOUSE_BUTTON_RIGHT && targetVehicle == null)) return;
        // fist fighting: left / right mouse = a punch with that hand (a vehicle under the crosshair still works as usual)
        if (fists.fighting() && targetVehicle == null && (button == GLFW_MOUSE_BUTTON_LEFT || button == GLFW_MOUSE_BUTTON_RIGHT)) {
            fists.press(button == GLFW_MOUSE_BUTTON_LEFT);
            return;
        }
        if (button == GLFW_MOUSE_BUTTON_LEFT && altDown() && throwTorch()) return;
        if (targetVehicle != null) {
            // doors / hood / trunk (left click), carried blocks (break, use, place), getting in (right click)
            var vh = targetVehicle;
            if (button == GLFW_MOUSE_BUTTON_LEFT) { swingArm(); vehicles.leftClick(vh); breakCooldown = 6; return; }
            if (button == GLFW_MOUSE_BUTTON_RIGHT && vehicles.rightClick(vh)) { placeCooldown = 6; return; }
            if (button == GLFW_MOUSE_BUTTON_RIGHT) { useItem(); return; }      // food, buckets... (the car is not a block to place on)
        }
        if (button == GLFW_MOUSE_BUTTON_RIGHT && !npcs.recording()) {
            float yr = (float) Math.toRadians(player.yaw), pr = (float) Math.toRadians(player.pitch);
            double dx = -Math.sin(yr) * Math.cos(pr), dy = -Math.sin(pr), dz = Math.cos(yr) * Math.cos(pr);
            double max = 5;
            if (target != null) max = Math.sqrt(Math.pow(target.hx - player.x, 2) + Math.pow(target.hy - player.y - player.eyeHeight, 2) + Math.pow(target.hz - player.z, 2));
            var actor = npcs.pick(player.x, player.y + player.eyeHeight, player.z, dx, dy, dz, max);
            if (actor != null) { swingArm(); game.setScreen(new derpcraft.gui.NpcHub(game, this, actor)); return; }
        }
        if (targetMob != null) {
            derpcraft.entity.Mob m = targetMob;
            if (button == GLFW_MOUSE_BUTTON_LEFT) {
                swingArm();
                ItemStack h = inv.held();
                float dmg = h != null ? Math.max(1, h.item.attackDamage) : 1;
                if (!player.onGround && player.my < 0 && !player.inWater) dmg *= 1.5f;   // critical hit when falling
                if (npcs.recording()) npcs.recordHit("mob:" + m.type.id, dmg, m.x(), m.y(), m.z());   // the take hits it too
                int wear = h != null && survival() ? h.item.wearOnHit() : 0;
                if (m.hurt(dmg, "player", true, null, null, player.x, player.z) && wear > 0) {
                    if (h.damageItem(wear)) inv.set(inv.selected, h.count <= 0 ? null : h);
                    else inv.changed();
                }
                if (player.sprinting) { m.push(-Math.sin(Math.toRadians(player.yaw)) * 0.5, 0.1, Math.cos(Math.toRadians(player.yaw)) * 0.5); player.sprinting = false; }
                breakCooldown = 6;
                return;
            }
            if (button == GLFW_MOUSE_BUTTON_RIGHT && m.type.onInteract != null) {
                boolean[] done = {false};
                safe(() -> done[0] = m.type.onInteract.onInteract(this, m));
                if (done[0]) { swingArm(); return; }
            }
        }
        if (button == GLFW_MOUSE_BUTTON_LEFT && targetActor != null && targetActor != npcs.recordingActor()) {
            swingArm();
            if (npcs.recording()) {
                ItemStack h = inv.held();
                npcs.recordHit("npc:" + targetActor.id, h != null ? Math.max(1, h.item.attackDamage) : 1, targetActor.x(), targetActor.y(), targetActor.z());
            }
            npcs.playerAttack(targetActor);
            breakCooldown = 6;
            return;
        }
        npcs.playerActing = true;
        try { mouseButtonInner(button); } finally { npcs.playerActing = false; }
    }

    private void mouseButtonInner(int button) {
        if (button == GLFW_MOUSE_BUTTON_LEFT) {
            if (targetItem != null && pickUpTargetItem()) { breakCooldown = 6; return; }
            if (creative() && target != null) breakBlock(); else swingArm();    // nothing there: punch the air
        }
        else if (button == GLFW_MOUSE_BUTTON_RIGHT) useItem();
        else if (button == GLFW_MOUSE_BUTTON_MIDDLE) pickBlock();
    }

    public void scroll(double amount) {
        if (amount == 0) return;
        if (cam.mode == CameraRig.Mode.FREE) cam.speed = Math.max(0.25f, Math.min(80, cam.speed * (float) Math.pow(1.2, amount)));
        else if (cam.mode == CameraRig.Mode.ORBIT) cam.orbitRadius = Math.max(1, Math.min(200, cam.orbitRadius * (float) Math.pow(0.9, amount)));
        else select(inv.selected - (int) Math.signum(amount));
    }

    // ------------------------------------------------------------------------------------------ film camera
    private static double[] dir(float yawDeg, float pitchDeg) {
        double yr = Math.toRadians(yawDeg), pr = Math.toRadians(pitchDeg);
        return new double[]{-Math.sin(yr) * Math.cos(pr), -Math.sin(pr), Math.cos(yr) * Math.cos(pr)};
    }

    /** Distance from the camera to the first block along its view (for autofocus), or 'max'. */
    private float focusRay(double x, double y, double z, float yaw, float pitch, float max) {
        double[] d = dir(yaw, pitch);
        Raycast.Hit h = Raycast.cast(world, x, y, z, d[0], d[1], d[2], max, false);
        if (h == null) return max;
        double dx = h.hx - x, dy = h.hy - y, dz = h.hz - z;
        return (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    public void saveCamera() {
        cam.saveSettings(game.gameDir.resolve("camera.txt"));
        cam.savePath(world.info.dir.resolve("camera_path.txt"));
    }

    private static final Keys.Binding[] CAMERA_KEYS = {Keys.FREE_CAM, Keys.ORBIT, Keys.PATH_KEY, Keys.PATH_DELETE, Keys.PATH_PLAY, Keys.TRIPOD,
            Keys.GRID, Keys.DOF, Keys.LEVEL, Keys.ZOOM_RESET, Keys.FOCUS, Keys.CAMERA_PANEL, Keys.SELFIE, Keys.SELFIE_FREE};

    /** Camera hotkeys (a key code or Keys.MOUSE + button); returns true when the key was used. */
    public boolean cameraKey(int key, int mods, boolean repeat) {
        for (Keys.Binding b : CAMERA_KEYS) if (b.matches(key)) return repeat || cameraAction(b, (mods & GLFW_MOD_SHIFT) != 0);
        return false;
    }

    /** Selfie camera on / off; 'free' = it stays where it is and the head turns on its own. */
    public void setSelfie(boolean on, boolean free) {
        if (on && spectator()) { chat("§eSelfie camera: not in spectator mode"); return; }
        boolean was = selfie;
        selfie = on;
        if (!on) { selfieFree = false; chat("Selfie camera OFF"); return; }
        if (cam.detached()) cam.toPlayer();           // the film camera gives way to the camera in the hand
        if (free && (!selfieFree || !was)) { selfieYaw = player.yaw; selfiePitch = player.pitch; }
        selfieFree = free;
        if (!was) chat("Selfie camera ON: " + (free ? "free, turn your head. " : "follows your head. ")
                + Keys.n(Keys.SELFIE) + " = off, " + Keys.n(Keys.SELFIE_FREE) + " = " + (free ? "follow the head" : "free camera"));
        else chat(free ? "Selfie: free camera, the head turns on its own" : "Selfie: the camera follows the head");
    }

    /** The selfie camera is what the screen shows (the player model holds it). */
    public boolean selfieActive() { return selfie && !spectator(); }

    /** Where the hand holding the selfie camera is (world), set every frame. */
    private double selfieHandX, selfieHandY, selfieHandZ;

    /** Runs a camera action (also used by the camera panel's buttons). */
    public boolean cameraAction(Keys.Binding b, boolean shift) {
        if (b == Keys.FREE_CAM) {
            if (cam.detached()) { cam.toPlayer(); chat("Camera: player view"); }
            else { cam.startFree(lastCamX, lastCamY, lastCamZ, lastCamYaw, lastCamPitch); chat("Free camera: move keys + jump/sneak, sprint = fast, wheel = speed. " + Keys.n(Keys.FREE_CAM) + " = back"); }
        } else if (b == Keys.ORBIT) {
            if (cam.mode == CameraRig.Mode.ORBIT) { cam.startFree(cam.x, cam.y, cam.z, cam.yaw, cam.pitch); chat("Orbit off (free camera)"); return true; }
            double px, py, pz;
            double[] d = dir(lastCamYaw, lastCamPitch);
            Raycast.Hit h = shift ? null : Raycast.cast(world, lastCamX, lastCamY, lastCamZ, d[0], d[1], d[2], 160, false);
            if (h != null) { px = h.x + 0.5; py = h.y + 0.5; pz = h.z + 0.5; }
            else { px = player.x; py = player.y + 1.0; pz = player.z; }
            cam.startOrbit(px, py, pz, lastCamX, lastCamY, lastCamZ);
            cam.orbitPlayer = h == null;
            chat("Orbit around " + (h != null ? "the block in the centre" : "the player") + ": forward/back = radius, left/right = speed, jump/sneak = height. " + Keys.n(Keys.ORBIT) + " = stop");
        } else if (b == Keys.PATH_KEY) {
            cam.addKey(lastCamX, lastCamY, lastCamZ, lastCamYaw, lastCamPitch, lastFocal);
            saveCamera();
            chat("Keyframe " + cam.keys.size() + " added" + (cam.keys.size() >= 2 ? " (" + Keys.n(Keys.PATH_PLAY) + " = play)" : ""));
        } else if (b == Keys.PATH_DELETE) {
            if (shift) { cam.clearKeys(); chat("Camera path cleared"); }
            else { cam.removeLastKey(); chat("Keyframes: " + cam.keys.size()); }
            saveCamera();
        } else if (b == Keys.PATH_PLAY) {
            if (cam.playing()) { cam.stop(); chat("Path stopped"); }
            else if (!cam.play()) chat("Add at least 2 keyframes with " + Keys.n(Keys.PATH_KEY) + " first");
        } else if (b == Keys.TRIPOD) {
            if (cam.mode != CameraRig.Mode.FREE && cam.mode != CameraRig.Mode.ORBIT) return false;
            cam.playerControl = !cam.playerControl;
            chat(cam.playerControl ? "Tripod: the camera stays, you control the player (" + Keys.n(Keys.TRIPOD) + " = control the camera)" : "Controlling the camera");
        } else if (b == Keys.GRID) cam.grid = !cam.grid;
        else if (b == Keys.DOF) { cam.dof = !cam.dof; chat("Depth of field " + (cam.dof ? "ON" : "OFF")); }
        else if (b == Keys.LEVEL) cam.rollTarget = 0;
        else if (b == Keys.ZOOM_RESET) cam.focalTarget = 0;
        else if (b == Keys.FOCUS) {
            if (shift) { cam.afMode = 1; chat("Autofocus: center"); }
            else {
                cam.manualFocus = focusRay(lastCamX, lastCamY, lastCamZ, lastCamYaw, lastCamPitch, 500);
                cam.afMode = 0;
                cam.dof = true;
                chat(String.format(java.util.Locale.ROOT, "Focus locked at %.1f m", cam.manualFocus));
            }
        } else if (b == Keys.CAMERA_PANEL) game.setScreen(new derpcraft.gui.CameraPanel(game, this));
        else if (b == Keys.SELFIE) {
            if (selfie) setSelfie(false, false);
            else setSelfie(true, false);
        } else if (b == Keys.SELFIE_FREE) {
            if (!selfie) setSelfie(true, true);
            else setSelfie(true, !selfieFree);
        }
        else return false;
        return true;
    }

    // ------------------------------------------------------------------------------------------ Tab on an NPC
    /** The NPC looked at when Tab went down (not while recording), and whether Tab + a number was used meanwhile. */
    private derpcraft.npc.Actor tabActor;
    private boolean tabNumberUsed;

    /** Tab pressed (outside a recording): remembers the NPC under the crosshair; what happens is decided when Tab is let go or a number is pressed. */
    public void npcTabPressed() {
        tabActor = targetActor;
        tabNumberUsed = false;
    }

    /** Tab + a number while looking at an NPC. Returns true if it was used (the number does not pick a hotbar slot then). */
    public boolean npcTabNumber(int n) {
        derpcraft.npc.Actor a = tabActor;
        if (a == null || !npcs.inWorld(a) || a.dead()) return false;
        tabNumberUsed = true;
        if (n == 1) {
            var v = vehicles.seatedIn(a);
            if (v != null && a.rideAlong == v.id) vehicles.ejectSeat(v, v.seatOf(a.id));
            a.startTabPatrol(world, new java.util.Random());
            npcs.save();
            chat("§e" + a.name + " patrols here §7(" + a.waypoints.size() + " points; Tab on it: back to what it did)");
        }
        return true;
    }

    /**
     * Every tick: Tab let go after it was pressed on an NPC (without a number): an NPC that follows you stays where it is
     * and waits (again: follows you); one patrolling because of Tab + 1 goes back to what it did before.
     */
    private void tickNpcTab() {
        derpcraft.npc.Actor a = tabActor;
        if (a == null || Keys.GESTURE.down(game.window)) return;
        tabActor = null;
        if (tabNumberUsed || npcs.recording() || !npcs.inWorld(a) || a.dead()) return;
        if (a.tabPatrol) {
            a.endTabPatrol();
            chat("§e" + a.name + ": " + (a.behavior == derpcraft.npc.Actor.Behavior.FOLLOW ? "follows you again" : a.behavior.label.toLowerCase(java.util.Locale.ROOT)));
        } else if (a.behavior == derpcraft.npc.Actor.Behavior.FOLLOW) {
            a.waiting = !a.waiting;
            if (a.waiting) a.setHome();
            chat("§e" + a.name + (a.waiting ? " waits here §7(Tab on it again: follows you)" : " follows you again"));
        } else return;
        npcs.save();
    }

    // ------------------------------------------------------------------------------------------ scene keys
    /** Play / stop key: plays the current group's scene, or stops it. */
    public void playSceneKey() {
        if (npcs.recording()) { chat("§7Recording: " + Keys.n(Keys.RECORD) + " = stop, " + Keys.n(Keys.RECORD_CANCEL) + " = cancel"); return; }
        String g = npcs.currentScene;
        if (npcs.scenePlaying(g)) npcs.stopScene(g);          // no text on screen: the scene is being filmed
        else if (npcs.playScene(g)) { }
        else chat("§cNo takes in \"" + g + "\" yet: " + Keys.n(Keys.RECORD) + " records an actor");
    }

    /**
     * Record key: stops a recording, or records the NPC you look at, else the NPC selected in the NPC Hub, else a
     * new actor where you stand. A new take is added: the actor's old takes stay.
     */
    public void recordKey() { recordKey(null); }

    /** Record key with an NPC chosen already (the NPC Hub's selection), or null. */
    public void recordKey(derpcraft.npc.Actor prefer) {
        if (npcs.recording()) { stopRecordingKey(); return; }
        derpcraft.npc.Actor a = prefer != null ? prefer : targetActor;
        boolean made = false;
        if (a != null) npcs.currentScene = a.group;
        else {
            a = derpcraft.gui.NpcHub.selected(npcs);
            if (a != null && !a.group.equalsIgnoreCase(npcs.currentScene)) a = null;
        }
        if (a == null) {
            if (!npcs.groupEnabled(npcs.currentScene)) npcs.setGroupEnabled(npcs.currentScene, true);
            a = spawnNpc("Actor");
            a.behavior = derpcraft.npc.Actor.Behavior.STAND;
            made = true;
        }
        derpcraft.gui.NpcHub.select(a);
        npcs.startRecording(a);
        if (made) npcs.recNewActor = a;
        int cd = npcs.countdownSeconds;
        var t = a.track(npcs.currentScene);
        chat("§eRecording " + a.name + (t != null && !t.takes.isEmpty() ? " (take " + (t.takes.size() + 1) + ")" : "") + " for scene \"" + npcs.currentScene + "\""
                + (cd > 0 ? " in " + cd + " s" : "") + ". " + Keys.n(Keys.RECORD) + " = stop, " + Keys.n(Keys.RECORD_CANCEL) + " = cancel");
    }

    public void stopRecordingKey() {
        var a = npcs.stopRecording();
        if (a == null) return;
        var t = a.take(npcs.currentScene);
        var tr = a.track(npcs.currentScene);
        chat(t != null ? String.format(java.util.Locale.ROOT, "§aTake saved: %s in scene \"%s\", %.1f s (take %d)%s", a.name, npcs.currentScene, t.seconds(), tr.takes.size(),
                npcs.lastAiKept > 0 ? " §7+ " + npcs.lastAiKept + " NPC(s) of the scene with their AI" : "") : "§eNothing recorded");
    }

    /** Cancel key: throws the recording away; the actor keeps the takes it had, blocks changed while recording come back. */
    public void cancelRecordingKey() {
        var a = npcs.recordingActor();
        boolean removed = npcs.cancelRecording();
        if (a != null) chat(removed ? "§eRecording cancelled (the new actor was removed)" : "§eRecording cancelled: " + a.name + " keeps the takes it had");
    }

    // ------------------------------------------------------------------------------------------ rendering
    private static double lerp(double a, double b, float t) { return a + (b - a) * t; }

    /** Draws the world. {@code paused}: the world stands still (a service menu is open): the camera does not move,
     *  only lens changes (zoom, roll, focus) still ease in, so the camera panel shows them. */
    public void render(float partial, float frameSeconds, boolean paused) {
        Settings st = game.settings;
        renderer.renderDistance = st.renderDistance;
        renderer.emissiveTex = game.assets.emissiveTex;   // per-layer emission + water flags (rebuilt on pack reload)
        double cx = lerp(player.px, player.x, partial), cz = lerp(player.pz, player.z, partial);
        float eye = player.prevEyeHeight + (player.eyeHeight - player.prevEyeHeight) * partial;
        double cy = lerp(player.py, player.y, partial) + eye;
        double[] rideFeet = vehicles.playerFeetAt(partial);     // in a car: exactly the seat of the car as drawn
        if (rideFeet != null) { cx = rideFeet[0]; cy = rideFeet[1] + eye; cz = rideFeet[2]; }
        double[] flipEye = vehicles.flipEye(partial, eye);      // the car rolls over: the head goes round with the seat
        if (flipEye != null) { cx = flipEye[0]; cy = flipEye[1]; cz = flipEye[2]; }
        double eyeX = cx, eyeY = cy, eyeZ = cz;
        float camYaw = player.yaw, camPitch = player.pitch;
        // in a turning vehicle the view turns smoothly with it between ticks, and sways on the suspension
        float swayRoll = 0;
        if (vehicles.playerVehicle != null) {
            camYaw += wrap(vehicles.playerVehicle.yawAt(partial) - vehicles.playerVehicle.yaw);
            float[] sw = vehicles.cameraSway(partial);
            if (sw != null && !selfieActive()) {
                cy += sw[0];       // only the camera (the body model stays in the seat)
                camPitch = Math.max(-90, Math.min(90, camPitch + sw[1]));
                swayRoll = sw[2];
            }
        }
        boolean selfieView = selfieActive();
        // the camera point set in the vehicle editor: only the first person view moves there; the rider's model stays on the seat
        double[] customEye = vehicles.customEye(partial);
        if (customEye != null && this.view == 0 && !selfieView) { cx = customEye[0]; cy = customEye[1]; cz = customEye[2]; }
        if (selfieView) {
            // selfie: the camera is in the right hand, the arm stretched in front of the face, the lens looking back at it.
            // The rig is the head: it turns around the neck with the look (or stays at the frozen direction when free).
            float hy = selfieFree ? selfieYaw : player.yaw, hp = selfieFree ? selfiePitch : player.pitch;
            double feetY = cy - eye;
            double neckY = feetY + eye - 0.119;                               // the neck is 0.119 under the eyes, in every posture
            double[] f = dir(hy, hp);
            double yr = Math.toRadians(hy);
            double rx = -Math.cos(yr), rz = -Math.sin(yr);                    // the player's right (horizontal)
            double ux = -rz * f[1], uy = rz * f[0] - rx * f[2], uz = rx * f[1];       // the head's up = right x forward
            double hcx = eyeX + ux * 0.25, hcy = neckY + uy * 0.25, hcz = eyeZ + uz * 0.25;     // head centre
            double dist = cam.selfieDistance, side = cam.selfieSide, up = 0.05;
            double px = f[0] * dist + rx * side + ux * up, py = f[1] * dist + uy * up, pz = f[2] * dist + rz * side + uz * up;
            double len = Math.sqrt(px * px + py * py + pz * pz);
            double d = cameraDistance(hcx, hcy, hcz, px / len, py / len, pz / len, len);   // the arm bends at walls
            double k = d / len;
            cx = hcx + px * k; cy = hcy + py * k; cz = hcz + pz * k;
            // the hand grips the camera from below and outside, behind the lens: the arm enters the frame from the lower
            // corner and never comes close to the lens (no clipping into the arm)
            double wx = px / len, wy = py / len, wz = pz / len;                // from the face to the camera
            double hl = 0.2 * k, hb = 0.2 * k, hk = 0.1 * k;
            selfieHandX = cx + rx * hl - ux * hb + wx * hk;
            selfieHandY = cy - uy * hb + wy * hk;
            selfieHandZ = cz + rz * hl - uz * hb + wz * hk;
            selfieCamX = cx; selfieCamY = cy; selfieCamZ = cz;
            double lx = hcx - cx, ly = hcy - cy, lz = hcz - cz;
            camYaw = (float) Math.toDegrees(Math.atan2(-lx, lz));
            camPitch = (float) -Math.toDegrees(Math.atan2(ly, Math.sqrt(lx * lx + lz * lz)));
        } else if (view != 0) {
            // third person: back off from the eyes along the look direction, stopping before walls
            if (view == 2) { camYaw += 180; camPitch = -camPitch; }  // vanilla: the camera sits where the player looks
            float yr0 = (float) Math.toRadians(camYaw), pr0 = (float) Math.toRadians(camPitch);
            double fx = -Math.sin(yr0) * Math.cos(pr0), fy = -Math.sin(pr0), fz = Math.cos(yr0) * Math.cos(pr0);
            double d = cameraDistance(eyeX, eyeY, eyeZ, -fx, -fy, -fz, vehicles.riding() ? Math.max(st.thirdPersonDistance, 6.5) : st.thirdPersonDistance);
            cx -= fx * d; cy -= fy * d; cz -= fz * d;
        }
        // film camera: free / orbit / path, roll, zoom and handheld shake
        float dt = Math.min(0.1f, Math.max(0, frameSeconds));
        boolean keys = game.screen == null;
        long win = game.window;
        float fwd = 0, str = 0, upd = 0;
        boolean fast = false;
        if (keys && cam.detached() && !cam.controlsPlayer()) {
            if (Keys.FORWARD.down(win)) fwd += 1;
            if (Keys.BACK.down(win)) fwd -= 1;
            if (Keys.LEFT.down(win)) str += 1;
            if (Keys.RIGHT.down(win)) str -= 1;
            if (Keys.JUMP.down(win)) upd += 1;
            if (Keys.SNEAK.down(win)) upd -= 1;
            fast = Keys.SPRINT.down(win);
        }
        if (keys) {
            if (Keys.ROLL_LEFT.down(win)) cam.rollTarget = Math.max(-180, cam.rollTarget - dt * 30);
            if (Keys.ROLL_RIGHT.down(win)) cam.rollTarget = Math.min(180, cam.rollTarget + dt * 30);
            int zd = (Keys.ZOOM_IN.down(win) || glfwGetKey(win, GLFW_KEY_KP_ADD) == GLFW_PRESS ? 1 : 0)
                    - (Keys.ZOOM_OUT.down(win) || glfwGetKey(win, GLFW_KEY_KP_SUBTRACT) == GLFW_PRESS ? 1 : 0);
            if (zd != 0) {
                if (cam.focalTarget <= 0) cam.focal = cam.focalTarget = lastFocal;
                cam.focalTarget = Math.max(8, Math.min(300, cam.focalTarget * (float) Math.exp(zd * dt * 1.2)));
            }
        }
        if (paused) cam.updateLens(dt);
        else {
            if (!ModContext.FRAME.isEmpty()) for (Events.FrameHandler fh : ModContext.FRAME) safe(() -> fh.onFrame(this, dt));
            boolean wasPlaying = cam.playing();
            if (cam.mode == CameraRig.Mode.ORBIT && cam.orbitPlayer) { cam.ox = eyeX; cam.oy = eyeY - eye + 1.0; cam.oz = eyeZ; }
            if (cam.mode == CameraRig.Mode.FOLLOW || cam.mode == CameraRig.Mode.POV || cam.afMode == 3) cam.followTarget = npcs.cameraTarget(cam.followId, partial);
            cam.update(dt, fwd, str, upd, fast);
            if (wasPlaying && !cam.playing() && cam.progress() >= 1) for (Events.CameraPathHandler ph : ModContext.PATH_END) safe(() -> ph.onPathEnd(this));
        }
        if (cam.detached()) { cx = cam.x; cy = cam.y; cz = cam.z; camYaw = cam.yaw; camPitch = cam.pitch; }
        // the vehicle's cinematic cameras (Right Alt + F8): third person, following the car like in GTA
        boolean vehCam = vehicles.cinemaActive() && !cam.detached() && !selfieView && vehicles.playerVehicle.vm() != null;
        if (vehCam) {
            double[] vc = vehicles.camera.frame(vehicles.playerVehicle, vehicles.playerVehicle.vm(), partial, paused ? 0 : dt, this::cameraDistance);
            cx = vc[0]; cy = vc[1]; cz = vc[2]; camYaw = (float) vc[3]; camPitch = (float) vc[4]; swayRoll = (float) vc[5];
        }
        lastCamX = cx; lastCamY = cy; lastCamZ = cz; lastCamYaw = camYaw; lastCamPitch = camPitch;
        vehicles.sounds(cx, cy, cz, camYaw, paused, game.screen == null && Keys.HORN.down(game.window));
        float[] shakeOff = cam.shakeOffsets();
        camYaw += shakeOff[0];
        camPitch += shakeOff[1];
        float camRoll = cam.roll + shakeOff[2] + (cam.detached() ? 0 : swayRoll) + (cam.detached() || vehCam ? 0 : vehicles.cameraFlip(partial));

        chunks.update(cx, cz, st.renderDistance);
        renderer.update(cx, cy, cz);
        if (cam.detached() || vehCam) { target = null; targetVehicle = null; targetItem = null; targetActor = null; targetMob = null; }
        else updateTarget(partial);

        boolean zoomKey = game.screen == null && Keys.ZOOM.down(game.window);
        zoom += ((zoomKey ? 0.25f : 1f) - zoom) * Math.min(1, frameSeconds * 12);
        float fov = cam.focal > 0 ? (float) Math.toDegrees(2 * Math.atan(12.0 / cam.focal)) * zoom
                : st.fov * (prevFovFactor + (fovFactor - prevFovFactor) * partial) * zoom;
        lastFov = fov; lastAspect = game.fbWidth / (float) Math.max(1, game.fbHeight);
        boolean underwater = player.eyeInWater(world, partial) && !cam.detached();
        if (underwater) fov *= 60f / 70f;
        fov *= guns.fovFactor();
        if (vehCam) fov *= vehicles.camera.fovFactor();
        fov = Math.max(1, Math.min(170, fov));
        guns.worldFov = fov;
        lastFocal = (float) (12.0 / Math.tan(Math.toRadians(fov) / 2));

        int w = game.fbWidth, h = game.fbHeight;
        boolean cine = st.cinematic, rt = st.rayTracing == 1;
        boolean lensFx = cam.lensEffectsActive();
        boolean useFbo = cine || rt || lensFx || st.screenSpaceEffects();
        glViewport(0, 0, w, h);
        float far = Math.max(512, st.renderDistance * 16 * 2.5f);
        Mat4 proj = new Mat4().perspective((float) Math.toRadians(fov), w / (float) Math.max(1, h), 0.05f, far);
        Mat4 view = new Mat4().rotate((float) Math.toRadians(camRoll), 0, 0, 1).rotate((float) Math.toRadians(camPitch), 1, 0, 0).rotate((float) Math.toRadians(camYaw + 180), 0, 1, 0);
        Mat4 bob = st.viewBobbing && !cam.detached() && !spectator() ? bobbing(partial) : null;
        if (bob != null) view = new Mat4().set(bob).mul(view);
        game.entities.handBob = bob;
        guns.setCamera(view, cx, cy, cz);
        Mat4 projView = new Mat4().set(proj).mul(view);
        frustum.set(projView);
        npcs.setCamera(projView, cx, cy, cz);

        WorldInfo info = world.info;
        float yr = (float) Math.toRadians(camYaw);
        Chunk ch = world.chunkAt((int) Math.floor(cx), (int) Math.floor(cz));
        float temp = ch == null ? 0.8f : (ch.temperature[((int) Math.floor(cz) & 15) * 16 + ((int) Math.floor(cx) & 15)] & 255) / 255f * 1.2f;
        game.sky.update(info.time, info.daylightCycle ? partial : 0, -(float) Math.sin(yr), (float) Math.cos(yr), st.renderDistance, temp);
        var sky = game.sky;

        float fogEnd = st.renderDistance * 16f;
        fp.projView = projView;
        fp.camX = cx; fp.camY = cy; fp.camZ = cz;
        fp.fogStart = fogEnd * 0.75f; fp.fogEnd = fogEnd;
        fp.fogR = sky.fogR; fp.fogG = sky.fogG; fp.fogB = sky.fogB;
        fp.sun = sky.sunBrightness;
        fp.gamma = st.gamma;
        fp.flicker = 1.5f + (float) Math.sin(ticks * 0.37) * 0.02f;
        game.transition.update(game, paused ? 0 : frameSeconds);
        fp.coloredLight = st.coloredLightMix >= 0 ? st.coloredLightMix : st.coloredLight ? 1 : 0;
        fp.shadowFade = st.shadowFade;
        fp.waterFade = st.waterFade;
        fp.ao = st.aoStrength;
        // fog (Shaders screen): thick from fogDistance, starting at fogStart of it, tinted; the sky fades into it, the sun,
        // moon and stars fade out behind a thick one
        if (st.fog && !underwater) {
            float end = Math.min(fogEnd, st.fogDistance);
            fp.fogEnd = end;
            fp.fogStart = end * st.fogStart;
            float[] tint = Settings.FOG_TINTS[st.fogTint];
            float b = 0.1f + 0.9f * Math.max(0, Math.min(1, (sky.sunBrightness - 0.2f) / 0.8f));
            float skyR = fp.fogR, skyG = fp.fogG, skyB = fp.fogB;
            if (tint != null) { fp.fogR = tint[0] * b; fp.fogG = tint[1] * b; fp.fogB = tint[2] * b; }
            // look transition: from the fog colour of the other tint
            if (st.fogTintFrom >= 0 && st.fogTintFrom < Settings.FOG_TINTS.length && st.fogTintMix < 1) {
                float[] from = Settings.FOG_TINTS[st.fogTintFrom];
                float fr = from == null ? skyR : from[0] * b, fg = from == null ? skyG : from[1] * b, fb = from == null ? skyB : from[2] * b;
                float m = st.fogTintMix;
                fp.fogR = fr + (fp.fogR - fr) * m; fp.fogG = fg + (fp.fogG - fg) * m; fp.fogB = fb + (fp.fogB - fb) * m;
            }
            sky.fogR = fp.fogR; sky.fogG = fp.fogG; sky.fogB = fp.fogB;
            sky.fogFar = Math.max(4, end * 0.8f);
            sky.celestialFade = Math.max(0, Math.min(1, (end - 24) / Math.max(1, fogEnd * 0.75f - 24)));
        } else { sky.fogFar = -1; sky.celestialFade = 1; }
        fp.emissive = cine ? game.grading.emissive : 0;
        // lens flares need the light sources to be brighter than white
        if (cam.flare > 0 || cam.ghosts > 0) fp.emissive = Math.max(fp.emissive, 1f);
        if (underwater) {
            float b = Math.max(0.2f, sky.sunBrightness);
            fp.fogR = 0.02f * b; fp.fogG = 0.06f * b; fp.fogB = 0.25f * b;
            fp.fogStart = 0; fp.fogEnd = 20;
        }
        // sun / moon, shadows and the ray tracing volume
        ShadowRenderer sr = game.shadows;
        sr.setLight(sky.celestial);
        fp.lightX = sr.lx; fp.lightY = sr.ly; fp.lightZ = sr.lz;
        fp.lightStrength = sr.strength * (underwater ? 0.3f : 1f);
        boolean moon = Math.cos(sky.celestial * Math.PI * 2) < 0;
        float warm = Math.max(0, Math.min(1, (sr.ly - 0.05f) / 0.35f));
        if (moon) { fp.sunR = 0.55f; fp.sunG = 0.65f; fp.sunB = 0.95f; }
        else { fp.sunR = 1f; fp.sunG = 0.55f + 0.41f * warm; fp.sunB = 0.3f + 0.58f * warm; }
        fp.skyR = sky.skyR; fp.skyG = sky.skyG; fp.skyB = sky.skyB;
        // player model: third person view, and its shadow in first person
        updatePose(partial, eyeX, eyeY - eye, eyeZ);
        boolean showModel = (this.view != 0 || cam.detached() || selfieView || vehCam) && !spectator();
        game.entities.player = showModel ? pose : null;
        if (!showModel) game.entities.playerMuzzle = null;
        game.entities.actors.clear();
        npcs.collectPoses(game.entities.actors, partial);
        game.entities.playerShadow = !showModel && !spectator() ? pose : null;
        game.entities.bowDraw = bowCharge;
        DynamicLights.collect(this, fp, spectator() ? null : pose, game.entities.actors, cx, cy, cz);
        double[] flash = guns.flashLight();
        if (flash != null && st.dynamicLights) {
            int i = Math.min(fp.dynCount, WorldRenderer.FrameParams.MAX_DYN - 1);
            fp.dynPos[i * 4] = (float) flash[0]; fp.dynPos[i * 4 + 1] = (float) flash[1]; fp.dynPos[i * 4 + 2] = (float) flash[2]; fp.dynPos[i * 4 + 3] = (float) (13 * flash[3]);
            fp.dynCol[i * 3] = 1f; fp.dynCol[i * 3 + 1] = 0.7f; fp.dynCol[i * 3 + 2] = 0.35f;
            fp.dynCount = i + 1;
        }
        if (st.dynamicLights) game.entities.tracerLights(fp, world.gameTime, partial);
        game.entities.itemPhysics = st.itemPhysics;
        game.entities.collectX = eyeX; game.entities.collectY = eyeY - eye + 0.7; game.entities.collectZ = eyeZ;
        final float fPartial = partial;
        final double fcx = cx, fcy = cy, fcz = cz;
        if (st.shadows > 0 || rt) {
            float pr = (float) Math.toRadians(camPitch);
            sr.render(renderer, st.shadows, st.shadows > 0, (vpm, radius) -> game.entities.renderShadowCasters(world, vpm, fPartial, fcx, fcy, fcz, radius),
                    cx, cy, cz, -(float) Math.sin(yr) * (float) Math.cos(pr), -(float) Math.sin(pr), (float) Math.cos(yr) * (float) Math.cos(pr));
        } else { sr.activeCascades = 0; sr.entityCascades = 0; }
        fp.entityCascades = sr.entityCascades;
        fp.entityShadowTex = sr.entityTexture();
        fp.entityShadowTexel = sr.entitySize() == 0 ? 0 : 1f / sr.entitySize();
        fp.cascades = sr.activeCascades;
        fp.shadowMats = sr.matrices;
        fp.shadowTex = sr.texture();
        fp.shadowTexel = sr.size() == 0 ? 0 : 1f / sr.size();
        fp.shadowTaps = st.shadows <= 1 ? 4 : st.shadows == 2 ? 8 : 12;
        fp.rt = rt ? 1 : 0;
        if (rt) {
            if (voxels == null) {
                voxels = new VoxelVolume();
                renderer.onMeshed = (scx, ssy, scz) -> voxels.sectionChanged(scx, scz);
            }
            voxels.update(world, game.assets, cx, cz, 6);
            fp.voxelTex = voxels.texture();
            fp.blockTable = voxels.table();
            fp.voxelMinX = voxels.minX; fp.voxelMinZ = voxels.minZ;
            fp.rtRays = st.rtQuality == 0 ? 1 : st.rtQuality == 1 ? 2 : 4;
        }
        fp.frame = frameIndex++;
        // shader effects (Shaders -> Effects): wind, water, reflections. The clock is game time, so the wind stops
        // with the game (pause menus) and follows the recording clock.
        renderer.wave = st.waving ? st.windStrength : 0;
        renderer.waveTime = (float) ((world.gameTime % 72000L) + partial) / 20f;
        fp.water = st.waterShader ? Math.max(0.001f, st.waveHeight) : 0;
        boolean ssrOn = st.reflections > 0;
        fp.ssrWater = ssrOn && !rt ? st.ssrWaterFade : 0;   // ray tracing reflects the water itself
        fp.ssrBlocks = st.reflections == 2 ? st.blockReflections : 0;
        // focus and the lens settings for the post chain
        if (cam.dof) {
            float ft;
            if (cam.afMode == 2 && (this.view != 0 || cam.detached() || selfieView)) {
                double dx = eyeX - cx, dy = eyeY - cy, dz = eyeZ - cz;
                ft = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
            } else if (cam.afMode == 3 && cam.followTarget != null && cam.mode != CameraRig.Mode.POV) {
                double[] t = cam.followTarget;
                double dx = t[0] - cx, dy = t[3] - cy, dz = t[2] - cz;
                ft = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
            } else ft = focusRay(cx, cy, cz, camYaw, camPitch, Math.min(far * 0.9f, 1000));
            cam.updateFocus(dt, ft);
        }
        var lens = game.post.lens;
        lens.dof = cam.dof; lens.focal = lastFocal; lens.aperture = cam.aperture; lens.focus = cam.focus;
        lens.bokehScale = cam.bokehScale; lens.squeeze = cam.squeeze; lens.near = 0.05f; lens.far = far;
        lens.motionBlur = cam.motionBlur; lens.flare = cam.flare; lens.flareLength = cam.flareLength; lens.ghosts = cam.ghosts;
        lens.flareTint = CameraRig.FLARE_TINTS[cam.flareColor];
        lens.distortion = cam.distortion; lens.aberration = cam.aberration; lens.letterbox = CameraRig.LETTERBOX_ASPECT[cam.letterbox];
        // camera pictures for the screens (their own small framebuffers), before the main view
        cameraNet.render(fp, partial, spectator() ? null : pose);
        glViewport(0, 0, w, h);
        if (useFbo) game.post.beginScene(w, h, ssrOn);
        else glViewport(0, 0, w, h);
        glClearColor(fp.fogR, fp.fogG, fp.fogB, 1);
        glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);

        boolean flaresOn = cam.flare > 0 || cam.ghosts > 0;
        sky.sunIntensity = flaresOn ? 1.5f : 1f;
        sky.moonIntensity = flaresOn ? 1.3f : 1f;
        if (!underwater) sky.draw(projView, game.assets, info.time, partial, st.renderDistance, false);
        fp.materialOut = ssrOn && useFbo;
        renderer.draw(fp, frustum);
        fp.materialOut = false;
        game.entities.yawRad = Math.toRadians(player.yaw);
        game.entities.pitchRad = Math.toRadians(player.pitch);
        game.entities.render(world, projView, partial, cx, cy, cz, fp, survival() ? target : null, mineProgress, st.renderDistance * 16, frustum);
        if (st.clouds && !underwater) sky.drawClouds(projView, game.assets, cx, cy, cz, info.time + partial, st.fog ? fp.fogEnd : fogEnd);
        drawOutlines(projView, cx, cy, cz, partial);
        if (rt) game.post.temporal(projView, cx, cy, cz, st.rtQuality == 2 ? 0.08f : 0.12f);
        if (useFbo) game.post.ambientOcclusion(projView, st.worldAO(), 1.0f);
        if (useFbo) game.post.screenEffects(fp, projView, ssrOn, fp.ssrBlocks, st.godRays && !underwater ? st.godRayStrength : 0);
        if (lensFx) game.post.lensPass(projView, cx, cy, cz);
        // one thing in the hands at a time, each goes down before the next comes up: the plain hand / item, the fists,
        // a gun (it waits below until the hand or the fists are down; a gun put away goes down first)
        long hn = System.nanoTime();
        float hdt = handNanos == 0 || paused ? 0 : Math.min(0.1f, (hn - handNanos) / 1e9f);
        handNanos = hn;
        var gunPose = guns.frame(partial, paused);
        fists.allowed = gunPose == null && handDown >= 0.999f;
        var fistPose = fists.frame(paused);
        float pushAmt = vehicles.pushAmount(partial);        // pushing a car: both arms out
        var pushPose = pushAmt > 0.01f && gunPose == null && fistPose == null ? pushArms.frame(pushAmt, vehicles.pushPhase()) : null;
        boolean slotBusy = gunPose != null || fistPose != null || fists.fighting() || pushPose != null;
        handDown = slotBusy ? Math.min(1, handDown + hdt / HAND_DOWN) : Math.max(0, handDown - hdt / HAND_DOWN);
        if (gunPose != null && !guns.holstering() && (handDown < 0.999f || fistPose != null)) {
            guns.anim.equip();                 // the gun waits below the picture
            gunPose = null;
        }
        if (!hideHud && !spectator() && this.view == 0 && !cam.detached() && !selfieView && !vehCam) {
            float sw = swing - prevSwing < 0 ? prevSwing + (1 + swing - prevSwing) * partial : prevSwing + (swing - prevSwing) * partial;
            if (sw >= 1) sw -= 1;
            float eq = prevEquip + (equip - prevEquip) * partial;
            float eatBob = 0;
            if (eatTicks > 0) eatBob = (float) Math.abs(Math.cos((eatTicks + partial) / 4f * Math.PI) * 0.1f);
            if (gunPose != null) game.entities.renderGun(world, gunPose, w / (float) Math.max(1, h), cx, cy, cz, fp);
            else if (fistPose != null) game.entities.renderFists(world, fistPose, w / (float) Math.max(1, h), cx, cy, cz, fp);
            else if (pushPose != null) game.entities.renderFists(world, pushPose, w / (float) Math.max(1, h), cx, cy, cz, fp);
            else game.entities.renderHand(world, shownItem, w / (float) Math.max(1, h), sw, Math.max(1 - eq + eatBob, (float) Math.sqrt(handDown)), cx, cy, cz, fp);
            // world AO for the hand, the item in it, the gun and the arms: the depth buffer now holds only them
            var ents = game.entities;
            if (useFbo && st.worldAO() > 0 && ents.handProj != null)
                game.post.ambientOcclusion(ents.handProj, st.worldAO(), 0.25f, ents.handNear, ents.handFar, true);
        }
        if (useFbo) {
            boolean compare = game.screen instanceof derpcraft.gui.Graphics.GradingEditor ge && ge.comparing();
            game.post.endScene(compare || !cine ? NEUTRAL : game.grading, (System.nanoTime() % 1_000_000_000_000L) / 1e9f, game.lutsDir);
        }
    }

    private static final derpcraft.render.post.Grading NEUTRAL = new derpcraft.render.post.Grading();

    /** The plain hand / item in the first person: how far it is down (0 = up, 1 = out of the picture) while fists or a gun take the hands. */
    private float handDown;
    private long handNanos;
    /** The arms of someone pushing a car, first person. */
    private final derpcraft.gun.PushArms pushArms = new derpcraft.gun.PushArms();
    /** How long the hand takes to go down or come up (seconds). */
    private static final float HAND_DOWN = 0.18f;

    private void drawOutlines(Mat4 projView, double cx, double cy, double cz, float partial) {
        Shader s = game.simpleShader;
        s.use();
        s.set("uMVP", projView);
        s.set("uModel", new Mat4());
        s.set("uUseTex", 0);
        s.set("uColor", 1, 1, 1, 1);
        s.set("uFog", 0f, 0f);
        glEnable(GL_BLEND);
        glBlendFuncSeparate(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA, GL_ONE, GL_ZERO);
        glEnable(GL_DEPTH_TEST);
        glDepthMask(false);
        lines.begin();
        if (target != null && targetItem == null && !hideHud) {
            int conn = Player.connections(world, target.block, target.x, target.y, target.z);
            for (float[] b : target.block.selectionBoxes(target.meta, conn, target.x, target.y, target.z)) {
                double e = 0.002;
                box(target.x + b[0] - e - cx, target.y + b[1] - e - cy, target.z + b[2] - e - cz,
                        target.x + b[3] + e - cx, target.y + b[4] + e - cy, target.z + b[5] + e - cz, 0, 0, 0, 0.4f);
            }
        }
        if (targetVehicle != null && !hideHud) vehicleOutline(targetVehicle, cx, cy, cz, partial);
        if (targetItem != null && !hideHud) {
            ItemEntity ie = targetItem;
            double ix = lerp(ie.px, ie.x, partial), iy = lerp(ie.py, ie.y, partial), iz = lerp(ie.pz, ie.z, partial);   // where the item is drawn
            box(ix - 0.2 - cx, iy - cy, iz - 0.2 - cz, ix + 0.2 - cx, iy + 0.4 - cy, iz + 0.2 - cz, 1, 1, 1, 0.8f);
        }
        if (cam.showPath && !cam.playing() && !hideHud && !cam.keys.isEmpty()) drawPath(cx, cy, cz);
        int[] sel = edit.selection();
        if (sel != null && !hideHud && edit.frameVisible()) {
            box(sel[0] - cx - 0.01, sel[1] - cy - 0.01, sel[2] - cz - 0.01, sel[3] + 1 - cx + 0.01, sel[4] + 1 - cy + 0.01, sel[5] + 1 - cz + 0.01, 0.3f, 1f, 1f, 0.9f);
        }
        lines.draw(GL_LINES);
        if (vehicles.markVehicle != null && !vehicles.marks.isEmpty() && game.screen instanceof derpcraft.gui.VehicleEditor) {
            // the vehicle editor: seats and block places, through the bodywork
            glDisable(GL_DEPTH_TEST);
            lines.begin();
            vehicleMarks(vehicles.markVehicle, cx, cy, cz, partial);
            lines.draw(GL_LINES);
            glEnable(GL_DEPTH_TEST);
        }
        glDepthMask(true);
        glDisable(GL_BLEND);
    }

    /** Boxes of the vehicle editor (seats, block places, the origin) in the pose the car is drawn in. */
    private void vehicleMarks(derpcraft.vehicle.Vehicle v, double cx, double cy, double cz, float partial) {
        var m = v.vm();
        if (m == null) return;
        double[] c = new double[3];
        int[] e = {0, 1, 1, 3, 3, 2, 2, 0, 4, 5, 5, 7, 7, 6, 6, 4, 0, 4, 1, 5, 2, 6, 3, 7};
        for (var mk : vehicles.marks) {
            double[][] p = new double[8][3];
            for (int i = 0; i < 8; i++) {
                v.bodyPointAt(m, (i & 1) == 0 ? mk.min[0] : mk.max[0], (i & 2) == 0 ? mk.min[1] : mk.max[1], (i & 4) == 0 ? mk.min[2] : mk.max[2], partial, c);
                p[i] = c.clone();
            }
            for (int i : e) lines.v((float) (p[i][0] - cx), (float) (p[i][1] - cy), (float) (p[i][2] - cz), 0, 0, mk.r, mk.g, mk.b, 0.95f);
        }
    }

    /** The camera path: the curve, a marker at each keyframe and its view direction. */
    private void drawPath(double cx, double cy, double cz) {
        int n = cam.keys.size();
        if (n >= 2) {
            int steps = (n - 1) * 24;
            double[] prev = cam.samplePath(0);
            for (int i = 1; i <= steps; i++) {
                double[] p = cam.samplePath(i / (double) steps);
                lines.v((float) (prev[0] - cx), (float) (prev[1] - cy), (float) (prev[2] - cz), 0, 0, 1f, 0.85f, 0.2f, 0.9f);
                lines.v((float) (p[0] - cx), (float) (p[1] - cy), (float) (p[2] - cz), 0, 0, 1f, 0.85f, 0.2f, 0.9f);
                prev = p;
            }
        }
        for (int i = 0; i < n; i++) {
            double[] k = cam.keys.get(i);
            double e = 0.15;
            boolean first = i == 0;
            box(k[0] - e - cx, k[1] - e - cy, k[2] - e - cz, k[0] + e - cx, k[1] + e - cy, k[2] + e - cz, first ? 0.3f : 1f, 1f, first ? 0.3f : 1f, 1f);
            double[] d = dir((float) k[3], (float) k[4]);
            lines.v((float) (k[0] - cx), (float) (k[1] - cy), (float) (k[2] - cz), 0, 0, 0.3f, 0.9f, 1f, 1f);
            lines.v((float) (k[0] + d[0] - cx), (float) (k[1] + d[1] - cy), (float) (k[2] + d[2] - cz), 0, 0, 0.3f, 0.9f, 1f, 1f);
        }
    }

    /** Outline of a carried block / an empty block place under the crosshair (like a block's outline). */
    private void vehicleOutline(derpcraft.vehicle.Vehicles.Hit h, double cx, double cy, double cz, float partial) {
        double[][] c;
        if (h.kind == derpcraft.vehicle.Vehicles.HIT_SLOT || h.kind == derpcraft.vehicle.Vehicles.HIT_EMPTY_SLOT) c = vehicles.slotCorners(h, partial);
        else return;           // doors and the body: no outline
        boolean empty = h.kind == derpcraft.vehicle.Vehicles.HIT_EMPTY_SLOT;
        float r = empty ? 1 : 0, g = empty ? 1 : 0, b = empty ? 1 : 0, a = empty ? 0.7f : 0.4f;
        int[] e = {0, 1, 1, 3, 3, 2, 2, 0, 4, 5, 5, 7, 7, 6, 6, 4, 0, 4, 1, 5, 2, 6, 3, 7};
        for (int i : e) lines.v((float) (c[i][0] - cx), (float) (c[i][1] - cy), (float) (c[i][2] - cz), 0, 0, r, g, b, a);
    }

    private void box(double x0, double y0, double z0, double x1, double y1, double z1, float r, float g, float b, float a) {
        double[][] c = {{x0, y0, z0}, {x1, y0, z0}, {x1, y0, z1}, {x0, y0, z1}, {x0, y1, z0}, {x1, y1, z0}, {x1, y1, z1}, {x0, y1, z1}};
        int[] e = {0, 1, 1, 2, 2, 3, 3, 0, 4, 5, 5, 6, 6, 7, 7, 4, 0, 4, 1, 5, 2, 6, 3, 7};
        for (int i : e) lines.v((float) c[i][0], (float) c[i][1], (float) c[i][2], 0, 0, r, g, b, a);
    }

    // ------------------------------------------------------------------------------------------ HUD
    /** Gun crosshair: four ticks that open with the spread (hidden through the sights), a hit marker, the rounds left. */
    private void gunCrosshair(GuiRenderer g, int w, int h) {
        float aim = guns.anim.aimAmount();
        float cx = w / 2f, cy = h / 2f;
        if (aim < 0.6f) {
            float fovRad = (float) Math.toRadians(game.settings.fov);
            float gap = 3 + (float) Math.tan(Math.toRadians(guns.spread())) / (float) Math.tan(fovRad / 2) * h / 2;
            gap = Math.min(gap, 60);
            g.fill(cx - gap - 5, cy - 0.5f, cx - gap, cy + 0.5f, 0xFFFFFFFF);
            g.fill(cx + gap, cy - 0.5f, cx + gap + 5, cy + 0.5f, 0xFFFFFFFF);
            g.fill(cx - 0.5f, cy - gap - 5, cx + 0.5f, cy - gap, 0xFFFFFFFF);
            g.fill(cx - 0.5f, cy + gap, cx + 0.5f, cy + gap + 5, 0xFFFFFFFF);
        }
        float hm = guns.hitMarker();
        if (hm > 0) {
            for (int i = 2; i < 6; i++) {
                g.fill(cx - i - 1, cy - i - 1, cx - i + 1, cy - i + 1, 0xFFFFFFFF); g.fill(cx + i - 1, cy - i - 1, cx + i + 1, cy - i + 1, 0xFFFFFFFF);
                g.fill(cx - i - 1, cy + i - 1, cx - i + 1, cy + i + 1, 0xFFFFFFFF); g.fill(cx + i - 1, cy + i - 1, cx + i + 1, cy + i + 1, 0xFFFFFFFF);
            }
        }
    }

    /** Rounds in the gun and spare magazines (bottom right). */
    private void gunAmmo(GuiRenderer g, int w, int h) {
        derpcraft.gun.GunType t = guns.held();
        if (t == null) return;
        int ammo = derpcraft.gun.GunController.ammo(inv.held(), t);
        int mags = 0;
        var mi = derpcraft.gun.Guns.magItem(t);
        for (int i = 0; i < PlayerInventory.MAIN; i++) { var st = inv.get(i); if (st != null && st.item == mi) mags += st.count; }
        int pouched = 0;
        for (int i = PlayerInventory.POUCH; i < PlayerInventory.POUCH + PlayerInventory.POUCHES; i++) { var st = inv.get(i); if (st != null && st.item == mi) pouched += st.count; }
        mags += pouched;
        String a = (ammo == 0 ? "§c" : ammo <= t.magazine / 4 ? "§e" : "§f") + ammo, b = "§7 / " + t.magazine + (creative() ? "" : "  §f" + mags + "§7 mag" + (mags == 1 ? "" : "s") + (pouched > 0 ? " (" + pouched + " in pouches)" : ""));
        String text = guns.reloading() ? "§eReloading..." : a + b;
        g.text(text, w - g.textWidth(text) - 6, h - 34, 0xFFFFFF, true);
        g.text("§7" + t.displayName, w - g.textWidth(t.displayName) - 6, h - 44, 0xFFFFFF, true);
    }

    public void renderHud(GuiRenderer g, float partial) {
        if (cam.grid) thirdsGrid(g);
        if (hideHud || game.screen instanceof derpcraft.gui.Graphics.GradingEditor) return;
        int w = g.width, h = g.height;
        var A = game.assets;
        if (cam.showInfo) cameraInfo(g);
        npcHud(g);
        // crosshair (inverted colours like vanilla)
        g.flush();
        glBlendFuncSeparate(GL_ONE_MINUS_DST_COLOR, GL_ONE_MINUS_SRC_COLOR, GL_ONE, GL_ZERO);
        boolean gunHud = guns.holding() && !spectator();
        if (view == 0 && !cam.detached() && !selfieActive() && !vehicles.cinemaActive()) {
            if (!gunHud) g.blit(A.icons, w / 2f - 7, h / 2f - 7, 0, 0, 15, 15, 0xFFFFFFFF);
            else gunCrosshair(g, w, h);
        }
        g.flush();
        glBlendFuncSeparate(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA, GL_ONE, GL_ONE_MINUS_SRC_ALPHA);

        if (gunHud) gunAmmo(g, w, h);
        float hs = guns.headshotMarker();
        if (hs > 0 && view == 0 && !cam.detached()) {
            // a headshot: a bigger red cross over the white hit marker
            int col = ((int) (Math.min(1, hs * 1.5f) * 255) << 24) | 0xE01010;
            float cx = w / 2f, cy = h / 2f;
            for (int i = 3; i < 9; i++) {
                g.fill(cx - i - 1, cy - i - 1, cx - i + 1, cy - i + 1, col); g.fill(cx + i - 1, cy - i - 1, cx + i + 1, cy - i + 1, col);
                g.fill(cx - i - 1, cy + i - 1, cx - i + 1, cy + i + 1, col); g.fill(cx + i - 1, cy + i - 1, cx + i + 1, cy + i + 1, col);
            }
        }
        if (!spectator()) {
            // hotbar
            int hx = w / 2 - 91;
            g.blit(A.widgets, hx, h - 22, 0, 0, 182, 22, 0xFFFFFFFF);
            g.blit(A.widgets, hx - 1 + inv.selected * 20, h - 23, 0, 22, 24, 24, 0xFFFFFFFF);
            for (int i = 0; i < 9; i++) g.itemWithCount(inv.get(i), hx + 3 + i * 20, h - 19);
            ItemStack back = inv.back();
            if (back != null) {
                // the gun on the back (Left Alt + F): a slot left of the hotbar, like the off hand slot of later versions
                int bx = hx - 26, by = h - 22;
                g.fill(bx, by, bx + 22, by + 22, 0xFF000000);
                g.fill(bx + 1, by + 1, bx + 21, by + 21, 0xFF8B8B8B);
                g.fill(bx + 2, by + 2, bx + 20, by + 20, 0xFF373737);
                g.itemWithCount(back, bx + 3, by + 3);
            }
            if (survival()) survivalBars(g, w, h);
            ItemStack held = inv.held();
            if (nameTicks > 0 && held != null) {
                int alpha = Math.min(255, nameTicks * 256 / 10);
                String n = held.displayName();
                int ny = h - 59 + (survival() ? 0 : 14);
                g.text(n, w / 2f - g.textWidth(n) / 2f, ny, (alpha << 24) | 0xFFFFFF, true);
            }
        }

        vehicleHud(g, w, h);

        // chat
        boolean open = game.screen instanceof derpcraft.gui.Screens.ChatScreen;
        int y = h - 40;
        int shown = 0;
        // Options -> Gameplay -> Chat Messages OFF: nothing pops up over the game, the chat window (T) still shows them
        for (int i = chat.size() - 1; i >= 0 && (open || game.settings.chatMessages) && shown < (open ? 20 : 10); i--) {
            String[] line = chat.get(i);
            long age = ticks - Long.parseLong(line[1]);
            if (!open && age > 200) continue;
            float a = open ? 1 : Math.min(1, (200 - age) / 20f);
            int al = (int) (a * 255);
            if (al < 8) continue;
            g.fill(2, y - 1, 2 + 320, y + 8, ((int) (al * 0.5f)) << 24);
            g.text(line[0], 4, y, (al << 24) | 0xFFFFFF, true);
            y -= 9;
            shown++;
        }

        if (showDebug) debug(g);

        Events.Hud hud = new Events.Hud() {
            public int width() { return g.width; }
            public int height() { return g.height; }
            public void text(String s, int x, int yy, int argb) { g.text(s, x, yy, argb, true); }
            public void rect(int x, int yy, int ww, int hh, int argb) { g.fill(x, yy, x + ww, yy + hh, argb); }
            public void blockIcon(Block b, int x, int yy) { g.blockIcon(b, x, yy, 16); }
        };
        for (Events.HudHandler hh : ModContext.HUD) safe(() -> hh.onHud(this, hud));
    }

    /** In a vehicle: the speed (driver) and how to get out. */
    private void vehicleHud(GuiRenderer g, int w, int h) {
        var v = vehicles.playerVehicle;
        if (v == null) {
            if (vehicles.pushable() != null && !spectator()) {
                String t = "Hold " + Keys.n(Keys.PUSH) + ": push the car";
                g.text(t, w / 2f - g.textWidth(t) / 2f, h - 82, 0xB0FFFFFF, true);
            }
            return;
        }
        int y = h - 82;                     // above the survival bars and the held item's name
        if (vehicles.playerDrives()) {
            String sp = String.format(java.util.Locale.ROOT, "%d km/h%s", Math.round(v.kmh()), v.speed < -0.005f ? "  R" : "");
            g.text(sp, w / 2f - g.textWidth(sp) / 2f, y, 0xFFFFFFFF, true);
        }
        String hint = Keys.n(Keys.FORWARD) + "/" + Keys.n(Keys.BACK) + ": gas / brake   " + Keys.n(Keys.LEFT) + "/" + Keys.n(Keys.RIGHT) + ": steer   " + Keys.n(Keys.JUMP) + ": handbrake / drift   "
                + Keys.n(Keys.VEHICLE_LIGHTS) + ": lights   " + Keys.n(Keys.VEHICLE_CAMERA) + ": camera   " + Keys.n(Keys.SNEAK) + ": get out";
        if (!vehicles.playerDrives()) hint = Keys.n(Keys.SNEAK) + ": get out";
        long age = ticks - vehicles.enteredTick;
        if (age < 0 || age >= 80) return;                 // shown for 4 seconds after getting in, fading out at the end
        int alpha = (int) (0x90 * Math.min(1, (80 - age) / 20f));
        if (alpha > 4) g.text(hint, w / 2f - g.textWidth(hint) / 2f, y + 10, (alpha << 24) | 0xFFFFFF, true);
    }

    /** Name tags above NPCs and the recording indicator. */
    private void npcHud(GuiRenderer g) {
        if (!stats.dead) for (var a : npcs.actors) {
            if (!a.showName || a == npcs.recordingActor()) continue;
            float[] p = npcs.nameTagPos(a);
            if (p == null) continue;
            String n = a.name;
            float x = p[0] * g.width - g.textWidth(n) / 2f, y = p[1] * g.height - 4;
            int alpha = p[2] > 32 ? (int) (255 * Math.max(0, (48 - p[2]) / 16)) : 255;
            if (alpha < 10) continue;
            boolean hostile = a.faction == derpcraft.npc.Actor.Faction.AGGRESSIVE;
            int col = a.nameColor > 0 ? derpcraft.npc.Actor.NAME_COLORS[a.nameColor] : hostile ? 0xFF3C3C : 0xFFFFFF;
            g.fill(x - 1, y - 1, x + g.textWidth(n) + 1, y + 8, ((hostile ? alpha / 2 : alpha / 4) << 24) | (hostile ? 0x200000 : 0));
            g.text(n, x, y, (alpha << 24) | col, false);
        }
        if (npcs.recording()) {
            var a = npcs.recordingActor();
            int cd = npcs.countdownTicks();
            if (cd > 0) {
                String big = String.valueOf((cd + 19) / 20);
                g.textRotated(big, g.width / 2f, g.height / 2f - 30, 0, 4, 0xFFFF4040);
                g.centered("Recording " + a.name + " starts in", g.width / 2f, g.height / 2f - 62, 0xFFFFFF);
            } else {
                int t = npcs.recordTick();
                String s = String.format(java.util.Locale.ROOT, "REC  %s%s  [%s]  %d:%02d.%d", a.name, npcs.aiRecorded() > 0 ? " +" + npcs.aiRecorded() + " NPC" : "", npcs.currentScene, t / 1200, (t / 20) % 60, (t % 20) / 2);
                boolean blink = (ticks / 10) % 2 == 0;
                int oy = game.recordingMarkHeight();    // below the screen recording mark
                g.fill(4, 4 + oy, 8 + g.textWidth(s) + 4, 16 + oy, 0x90000000);
                g.text(s, 8, 6 + oy, blink ? 0xFFFF3030 : 0xFFFF9090, false);
                g.text(Keys.n(Keys.RECORD) + ": stop   " + Keys.n(Keys.RECORD_CANCEL) + ": cancel   " + Keys.n(Keys.GESTURE) + "+1..9: gesture   " + Keys.n(Keys.FACE) + "+1..7: face", 8, 18 + oy, 0xA0FFFFFF, true);
                String ges = npcs.recordPose == null || npcs.recordPose.isEmpty() ? "-" : npcs.recordPose;
                g.text("Gesture: " + ges + "   Face: " + derpcraft.npc.Emotions.NAMES[Math.max(0, Math.min(6, npcs.recordEmotion))], 8, 28 + oy, 0xA0FFFFFF, true);
            }
        }
    }

    /** Visible picture area inside the letterbox bars: {x0, y0, x1, y1} in GUI pixels. */
    private float[] frameRect(GuiRenderer g) {
        float w = g.width, h = g.height, a = CameraRig.LETTERBOX_ASPECT[cam.letterbox], sa = w / h;
        if (a <= 0) return new float[]{0, 0, w, h};
        if (sa < a) { float vh = w / a; return new float[]{0, (h - vh) / 2, w, (h + vh) / 2}; }
        float vw = h * a;
        return new float[]{(w - vw) / 2, 0, (w + vw) / 2, h};
    }

    /** Rule-of-thirds grid and a centre mark (G). Shown even with the HUD hidden; turn it off before recording. */
    private void thirdsGrid(GuiRenderer g) {
        float[] r = frameRect(g);
        int c = 0x60FFFFFF;
        for (int i = 1; i <= 2; i++) {
            int x = Math.round(r[0] + (r[2] - r[0]) * i / 3f), y = Math.round(r[1] + (r[3] - r[1]) * i / 3f);
            g.fill(x, (int) r[1], x + 1, (int) r[3], c);
            g.fill((int) r[0], y, (int) r[2], y + 1, c);
        }
        int mx = Math.round((r[0] + r[2]) / 2), my = Math.round((r[1] + r[3]) / 2);
        g.fill(mx - 3, my, mx + 4, my + 1, 0x90FFFFFF);
        g.fill(mx, my - 3, mx + 1, my + 4, 0x90FFFFFF);
    }

    /** Small camera readout in the top right corner: mode, lens, focus, roll and path state. */
    private void cameraInfo(GuiRenderer g) {
        List<String> out = new ArrayList<>();
        String mode = switch (cam.mode) {
            case FREE -> "§bFREE CAM";
            case ORBIT -> "§bORBIT";
            case PATH -> "§cPATH " + (int) (cam.progress() * 100) + "%";
            case FOLLOW -> "§bFOLLOW";
            case POV -> "§bPOV";
            default -> "";
        };
        boolean custom = cam.focal > 0 || Math.abs(cam.roll) > 0.05f || cam.dof || cam.detached() || !cam.keys.isEmpty();
        if (!custom && !(game.screen instanceof derpcraft.gui.CameraPanel)) return;
        StringBuilder l1 = new StringBuilder();
        if (!mode.isEmpty()) l1.append(mode).append("§r  ");
        l1.append(Math.round(lastFocal)).append("mm");
        if (cam.squeeze > 1.01f) l1.append(String.format(Locale.ROOT, " %.1fx", cam.squeeze));
        if (cam.dof) l1.append(String.format(Locale.ROOT, "  f/%.1f", cam.aperture));
        out.add(l1.toString());
        if (cam.dof) out.add(String.format(Locale.ROOT, "Focus %.1f m §7(%s)", cam.focus, CameraRig.AF_MODES[cam.afMode]));
        if (Math.abs(cam.roll) > 0.05f) out.add(String.format(Locale.ROOT, "Roll %+.1f°", cam.roll));
        if (cam.mode == CameraRig.Mode.FREE) out.add(String.format(Locale.ROOT, "§7Speed %.1f", cam.speed));
        if (!cam.keys.isEmpty()) out.add("§7Keys " + cam.keys.size() + String.format(Locale.ROOT, ", %.0fs", cam.pathDuration));
        int y = 4;
        for (String s : out) {
            g.text(s, g.width - g.textWidth(s) - 4, y, 0xFFFFFF, true);
            y += 10;
        }
        if (cam.playing()) {
            int x0 = g.width - 84, x1 = g.width - 4;
            g.fill(x0, y, x1, y + 2, 0x80000000);
            g.fill(x0, y, x0 + Math.round((x1 - x0) * cam.progress()), y + 2, 0xFFFF5050);
        }
    }

    /** Hearts, armor, food, air and the experience bar at their Minecraft 1.12 positions. */
    private void survivalBars(GuiRenderer g, int w, int h) {
        var icons = game.assets.icons;
        int left = w / 2 - 91, right = w / 2 + 91, top = h - 39;
        // experience
        g.blit(icons, left, h - 29, 0, 64, 182, 5, 0xFFFFFFFF);
        int fill = (int) (stats.xpProgress * 183);
        if (fill > 0) g.blit(icons, left, h - 29, 0, 69, fill, 5, 0xFFFFFFFF);
        if (stats.xpLevel > 0) {
            String lv = String.valueOf(stats.xpLevel);
            float lx = w / 2f - g.textWidth(lv) / 2f, ly = h - 35;
            g.text(lv, lx + 1, ly, 0, false); g.text(lv, lx - 1, ly, 0, false);
            g.text(lv, lx, ly + 1, 0, false); g.text(lv, lx, ly - 1, 0, false);
            g.text(lv, lx, ly, 0x80FF20, false);
        }
        // hearts (flash white after damage, shake when low)
        int hp = (int) Math.ceil(stats.health);
        boolean flash = stats.invulnerable > 10 && (stats.invulnerable / 3) % 2 == 1;
        java.util.Random r = new java.util.Random(ticks * 312871L);
        for (int i = 9; i >= 0; i--) {
            int x = left + i * 8, y = top;
            if (hp <= 4) y += r.nextInt(2);
            g.blit(icons, x, y, flash ? 25 : 16, 0, 9, 9, 0xFFFFFFFF);
            if (i * 2 + 1 < hp) g.blit(icons, x, y, 52, 0, 9, 9, 0xFFFFFFFF);
            else if (i * 2 + 1 == hp) g.blit(icons, x, y, 61, 0, 9, 9, 0xFFFFFFFF);
        }
        // armor
        int armor = inv.armorPoints();
        if (armor > 0)
            for (int i = 0; i < 10; i++) {
                int x = left + i * 8;
                int u = i * 2 + 1 < armor ? 34 : i * 2 + 1 == armor ? 25 : 16;
                g.blit(icons, x, top - 10, u, 9, 9, 9, 0xFFFFFFFF);
            }
        // food
        for (int i = 0; i < 10; i++) {
            int x = right - i * 8 - 9, y = top;
            if (stats.saturation <= 0 && ticks % (stats.food * 3 + 1) == 0) y += r.nextInt(3) - 1;
            g.blit(icons, x, y, 16, 27, 9, 9, 0xFFFFFFFF);
            if (i * 2 + 1 < stats.food) g.blit(icons, x, y, 52, 27, 9, 9, 0xFFFFFFFF);
            else if (i * 2 + 1 == stats.food) g.blit(icons, x, y, 61, 27, 9, 9, 0xFFFFFFFF);
        }
        // air
        if (player.eyeInWater(world, 1) || stats.air < 300) {
            int full = (int) Math.ceil((stats.air - 2) * 10.0 / 300.0), part = (int) Math.ceil(stats.air * 10.0 / 300.0) - full;
            for (int i = 0; i < full + part; i++) g.blit(icons, right - i * 8 - 9, top - 10, i < full ? 16 : 25, 18, 9, 9, 0xFFFFFFFF);
        }
    }

    private void debug(GuiRenderer g) {
        List<String> left = new ArrayList<>(), right = new ArrayList<>();
        int bx = (int) Math.floor(player.x), by = (int) Math.floor(player.y), bz = (int) Math.floor(player.z);
        left.add("DerpCraft " + DerpCraft.VERSION + " (" + game.fps + " fps)");
        left.add(renderer.stats());
        left.add("Chunks: " + world.chunks.size() + " loaded, " + chunks.pendingCount() + " pending, light queue " + world.light.pending.get());
        left.add("");
        left.add(String.format("XYZ: %.3f / %.5f / %.3f", player.x, player.y, player.z));
        left.add("Block: " + bx + " " + by + " " + bz);
        left.add("Chunk: " + (bx & 15) + " " + (by & 15) + " " + (bz & 15) + " in " + (bx >> 4) + " " + (by >> 4) + " " + (bz >> 4));
        String[] facing = {"south (Towards positive Z)", "west (Towards negative X)", "north (Towards negative Z)", "east (Towards positive X)"};
        int q = Math.floorMod(Math.round(player.yaw / 90f), 4);
        left.add(String.format("Facing: %s (%.1f / %.1f)", facing[q], wrap(player.yaw), player.pitch));
        left.add("Light: " + world.getSky(bx, by, bz) + " sky, " + world.getBlockLight(bx, by, bz) + " block");
        left.add("Mode: " + gameMode + ", entities " + world.entities.size() + ", particles " + game.entities.particles.size());
        left.add("Time: " + world.info.time % 24000 + " (day " + world.info.time / 24000 + ")");
        if (target != null) left.add("Looking at: " + target.block.name + " [" + target.meta + "] " + target.x + " " + target.y + " " + target.z);
        if (edit.selection() != null) { int[] s = edit.selection(); left.add("Selection: " + (s[3] - s[0] + 1) + "x" + (s[4] - s[1] + 1) + "x" + (s[5] - s[2] + 1)); }

        Runtime rt = Runtime.getRuntime();
        long used = (rt.totalMemory() - rt.freeMemory()) >> 20;
        right.add("Java: " + System.getProperty("java.version") + " 64bit");
        right.add("Mem: " + (used * 100 / Math.max(1, rt.maxMemory() >> 20)) + "% " + used + "/" + (rt.maxMemory() >> 20) + "MB");
        right.add("CPU: " + Runtime.getRuntime().availableProcessors() + "x, workers " + derpcraft.util.Workers.defaultThreads());
        right.add("");
        right.add("Display: " + game.fbWidth + "x" + game.fbHeight);
        right.add(game.glRenderer);
        right.add(game.glVersion);
        right.add("Mods: " + game.mods.mods.size());
        int y = 2;
        for (String s : left) { if (!s.isEmpty()) { g.fill(1, y - 1, 2 + g.textWidth(s) + 1, y + 8, 0x90505050); g.text(s, 2, y, 0xE0E0E0, false); } y += 9; }
        y = 2;
        for (String s : right) { if (!s.isEmpty()) { int tw = g.textWidth(s); g.fill(g.width - tw - 3, y - 1, g.width - 1, y + 8, 0x90505050); g.text(s, g.width - tw - 2, y, 0xE0E0E0, false); } y += 9; }
    }

    private static float wrap(float a) { a %= 360; if (a >= 180) a -= 360; if (a < -180) a += 360; return a; }

    // ------------------------------------------------------------------------------------------ chat
    /** How many chat lines were ever added (the Wi-Fi remote asks for the lines after the ones it has). */
    public volatile long chatCount;

    public void chat(String msg) {
        if (msg == null) return;                         // nothing to say (e.g. a look transition started)
        chatCount++;
        chat.add(new String[]{msg, Long.toString(ticks)});
        if (chat.size() > 100) chat.remove(0);
        System.out.println("[CHAT] " + msg.replaceAll("§.", ""));
    }

    /** F3 + D: clears the chat at once (like vanilla). */
    public void clearChat() { chat.clear(); }

    public List<String> chatHistory() {
        List<String> l = new ArrayList<>();
        for (String[] s : chat) l.add(s[0]);
        return l;
    }

    public void submitChat(String line) {
        line = line.trim();
        if (line.isEmpty()) return;
        if (line.startsWith("/")) { runCommand(line.substring(1)); return; }
        Events.Chat ev = new Events.Chat(line);
        for (Events.ChatHandler h : ModContext.CHAT) safe(() -> h.onChat(this, ev));
        EventBus.post(this, ev);
        if (ev.isCancelled()) return;
        String said = npcs.chatLine(ev.message);
        chat(said != null ? said : "<Player> " + ev.message);
    }

    public static void safe(Runnable r) {
        try { r.run(); } catch (Throwable t) { System.err.println("Mod handler failed: " + t); t.printStackTrace(); }
    }

    // ------------------------------------------------------------------------------------------ GameApi
    @Override public boolean inWorld() { return true; }
    @Override public Block getBlock(int x, int y, int z) { return world.getBlock(x, y, z); }
    @Override public int getMeta(int x, int y, int z) { return world.getMeta(x, y, z); }
    @Override public boolean setBlock(int x, int y, int z, Block b) { return world.setBlock(x, y, z, b, 0); }
    @Override public boolean setBlock(int x, int y, int z, Block b, int meta) { return world.setBlock(x, y, z, b, meta); }
    @Override public int fill(int x1, int y1, int z1, int x2, int y2, int z2, Block b) { return edit.fill(x1, y1, z1, x2, y2, z2, b, 0); }
    @Override public Block block(String name) { return Blocks.byName(name); }
    @Override public double playerX() { return player.x; }
    @Override public double playerY() { return player.y; }
    @Override public double playerZ() { return player.z; }
    @Override public float playerYaw() { return player.yaw; }
    @Override public float playerPitch() { return player.pitch; }
    @Override public void teleport(double x, double y, double z) { player.setPos(x, y, z); }
    @Override public void setRotation(float yaw, float pitch) { player.yaw = yaw; player.pitch = Math.max(-90, Math.min(90, pitch)); }
    @Override public long time() { return world.info.time; }
    @Override public void setTime(long t) { world.info.time = t; }
    @Override public void runCommand(String command) { commands.run(command); }
    private final ModApis.Camera cameraApi = new ModApis.Camera(this);
    private final ModApis.Graphics graphicsApi = new ModApis.Graphics(this);
    @Override public derpcraft.api.CameraApi camera() { return cameraApi; }
    @Override public derpcraft.api.GraphicsApi graphics() { return graphicsApi; }
    @Override public int view() { return view; }
    @Override public void setView(int v) { view = Math.floorMod(v, 3); }
    @Override public boolean hudHidden() { return hideHud; }
    @Override public void setHudHidden(boolean h) { hideHud = h; }
    @Override public String gameMode() { return gameMode; }
    @Override public boolean dropItem(double x, double y, double z, String item, int count) {
        Item it = derpcraft.item.Items.byName(item);
        if (it == null) it = derpcraft.item.Items.byName(item.contains(":") ? item : "minecraft:" + item);
        if (it == null || count <= 0) return false;
        world.dropItem(x, y, z, new ItemStack(it, Math.min(count, it.maxStack)));
        return true;
    }
    @Override public void setItemPhysics(boolean on) { game.settings.itemPhysics = on; }
    @Override public void setClickPickup(boolean on) { game.settings.pickupClick = on; }
    @Override public void screenshot() { game.requestScreenshot(); }
    private final ModApis.Npcs npcApi = new ModApis.Npcs(this);
    @Override public void schedule(int delayTicks, Runnable task) { tasks.add(new Task(ticks + Math.max(1, delayTicks), task)); }
    @Override public derpcraft.api.DataTag worldData(String key) { return worldData.computeIfAbsent(key, k -> new derpcraft.api.DataTag()); }
    @Override public derpcraft.api.BlockContext blockAt(int x, int y, int z) { return new BlockContextImpl(this, world, x, y, z); }
    @Override public derpcraft.api.DataTag blockData(int x, int y, int z) {
        return world.getBlockEntity(x, y, z) instanceof derpcraft.world.be.ModBlockEntity m ? m.data : null;
    }
    @Override public derpcraft.api.InventoryApi inventoryAt(int x, int y, int z) {
        var be = world.getBlockEntity(x, y, z);
        var inv = be == null ? null : be.inventory();
        return inv == null ? null : new InventoryView(inv);
    }
    @Override public derpcraft.api.InventoryApi createInventory(int size) { return new InventoryView(new derpcraft.item.Inventory(Math.max(1, size))); }
    @Override public derpcraft.api.InventoryApi playerInventory() { return new InventoryView(inv); }
    @Override public void openMenu(derpcraft.api.Menu menu) { game.setScreen(new Containers.MenuScreen(game, this, menu)); }
    @Override public ItemStack heldItem() { return inv.held(); }
    @Override public void setHeldItem(ItemStack stack) { inv.set(inv.selected, stack); }
    @Override public boolean giveItem(ItemStack stack) {
        if (ItemStack.isEmpty(stack)) return true;
        ItemStack left = inv.add(stack.copy(), 0, PlayerInventory.MAIN);
        if (left != null) world.dropItem(player.x, player.y + 1, player.z, left);
        return left == null;
    }
    @Override public ItemStack createItem(String item, int count) {
        Item it = Items.byName(item);
        if (it == null && !item.contains(":")) it = Items.byName("minecraft:" + item);
        return it == null ? null : new ItemStack(it, Math.max(1, count));
    }
    @Override public boolean playerSneaking() { return player.sneaking; }
    @Override public void pushPlayer(double dx, double dy, double dz) { player.mx += dx; player.my += dy; player.mz += dz; if (dy > 0) player.onGround = false; player.fallDistance = 0; }
    @Override public derpcraft.api.NpcApi npcs() { return npcApi; }

    // ------------------------------------------------------------------ GameApi 1.7
    @Override public java.util.List<derpcraft.api.Creature> creatures() { return new ArrayList<>(mobs.list()); }
    @Override public java.util.List<derpcraft.api.Creature> creaturesNear(double x, double y, double z, double r) {
        List<derpcraft.api.Creature> out = new ArrayList<>();
        for (var m : mobs.list()) if (m.distSq(x, y, z) <= r * r) out.add(m);
        return out;
    }
    @Override public derpcraft.api.Creature spawnCreature(String type, double x, double y, double z) {
        return mobs.spawn(derpcraft.entity.MobType.get(type), x, y, z, player.yaw + 180, false);
    }
    @Override public derpcraft.api.Creature targetedCreature() { return targetMob; }
    @Override public void setCreatureSpawning(boolean on) { mobs.spawning = on; }
    @Override public void shoot(String projectile, double x, double y, double z, double dx, double dy, double dz, float speed) {
        mobs.shoot(projectile, x, y, z, dx, dy, dz, speed, false, null);
    }
    @Override public void shootFromPlayer(String projectile, float speed) {
        float yr = (float) Math.toRadians(player.yaw), pr = (float) Math.toRadians(player.pitch);
        double dx = -Math.sin(yr) * Math.cos(pr), dy = -Math.sin(pr), dz = Math.cos(yr) * Math.cos(pr);
        var p = mobs.shoot(projectile, player.x + dx * 0.3, player.y + player.eyeHeight - 0.1, player.z + dz * 0.3, dx, dy, dz, speed, true, null);
        if (npcs.recording()) npcs.recordItem(2, projectile, Math.round(speed * 100), dx, dy, dz);
        if (p != null) { p.mx += player.mx; p.mz += player.mz; if (!player.onGround) p.my += player.my; }
        swingArm();
    }
    @Override public void explode(double x, double y, double z, float power, boolean breakBlocks) { derpcraft.entity.Explosions.explode(this, x, y, z, power, breakBlocks); }
    @Override public void hurtPlayer(float amount, String cause) { hurtPlayer(amount, cause, player.x, player.z); }
    @Override public float playerHealth() { return stats.health; }
    @Override public void setPlayerHealth(float h) { stats.health = Math.max(0, Math.min(20, h)); if (stats.health <= 0) { stats.dead = true; } }
    @Override public boolean isDay() { return derpcraft.entity.Mob.isDay(this); }
    @Override public int lightAt(int x, int y, int z) { return Math.max(Math.max(0, world.getSky(x, y, z) - (isDay() ? 0 : 11)), world.getBlockLight(x, y, z)); }

    public double lastCamX() { return lastCamX; }
    public double lastCamY() { return lastCamY; }
    public double lastCamZ() { return lastCamZ; }
    public float lastCamYaw() { return lastCamYaw; }
    public float lastCamPitch() { return lastCamPitch; }
}
