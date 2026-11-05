package derpcraft.render;

import derpcraft.block.Block;
import derpcraft.block.Blocks;
import derpcraft.block.Dir;
import derpcraft.block.FunctionalBlocks;
import derpcraft.entity.Entity;
import derpcraft.entity.ItemEntity;
import derpcraft.game.Raycast;
import derpcraft.item.Item;
import derpcraft.item.ItemStack;
import derpcraft.util.Mat4;
import derpcraft.world.Chunk;
import derpcraft.world.World;
import derpcraft.world.be.ContainerEntity;
import derpcraft.world.be.SignEntity;

import java.util.*;

import static org.lwjgl.opengl.GL45C.*;

/**
 * Draws everything that is not chunk geometry: chests, signs, beds, dropped items, particles, the block-breaking
 * crack overlay and the first-person hand with the held item. Models follow Minecraft 1.12's entity layouts.
 */
public final class EntityRenderer {
    public final ModelBatch mb = new ModelBatch();
    private final Assets assets;
    private final Map<Integer, float[]> spriteMeshes = new HashMap<>();
    private int meshVersion = -1;
    public final List<Particle> particles = new ArrayList<>();
    /** Most particles alive at once; new ones are skipped above this. */
    public static final int MAX_PARTICLES = 20000;
    private final float[] light = new float[3];
    private float sun = 1, gamma, flicker = 1.5f;
    private double camX, camY, camZ;
    private World world;
    private final Random rnd = new Random();

    public static final class Particle {
        public double x, y, z, px, py, pz, mx, my, mz;
        public int age, maxAge;
        public float size, u0, v0, uSize = 0.25f, gravity = 0.04f;
        public int layer;
        public float r = 1, g = 1, b = 1;
        /** >= 0: a sprite of textures/particle/particles.png (16 x 16 cells) instead of a piece of a block texture. */
        public int sprite = -1;
        /** Sprite 0..7 counting down with age (smoke, puffs), like vanilla's 7 - age * 8 / maxAge. */
        public boolean ageSprite;
        /** Full brightness and shrinking (flames, embers). */
        public boolean glow;
        /** Colour that r, g, b turn into over the particle's life (tintEnd), fading out at the end (fade), growing by 'grow' times its size (grow). */
        public float er, eg, eb, grow;
        public boolean tintEnd, fade;
        /** A drop of blood: where it lands on a block it leaves a splat ({@link #blood}). */
        public boolean blood;
        /** Hot fragment of an explosion: flies in an arc (little drag, strong gravity), bounces off the ground and leaves a spark trail. */
        public boolean ballistic;
        /** Share of the speed kept every tick. */
        public float drag = 0.98f;
        /** Wind: horizontal push (blocks/tick^2 per block of height above 'windY'), 0 = none. */
        public float windX, windZ, windY;
        /** Light at the particle (lightmap colour and sky share), refreshed every other tick instead of every frame. */
        float lr = -1, lg, lb, lsky;
    }

    public EntityRenderer(Assets assets) { this.assets = assets; }

    // ------------------------------------------------------------------------------------------ lighting helpers
    private boolean colored;

    /** Dynamic lights of this frame (world positions, set by render()). */
    private WorldRenderer.FrameParams dynFp;
    private final float[] dyn = new float[3];

    /** Block light given at a point by the dynamic lights, per colour channel (level falls by one per block). */
    private void dynamicLevel(double x, double y, double z) {
        dyn[0] = dyn[1] = dyn[2] = 0;
        WorldRenderer.FrameParams fp = dynFp;
        if (fp == null) return;
        for (int i = 0; i < fp.dynCount; i++) {
            double dx = fp.dynPos[i * 4] - x, dy = fp.dynPos[i * 4 + 1] - y, dz = fp.dynPos[i * 4 + 2] - z;
            float lv = fp.dynPos[i * 4 + 3] - (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (lv <= 0) continue;
            for (int c = 0; c < 3; c++) dyn[c] = Math.max(dyn[c], lv * fp.dynCol[i * 3 + c]);
        }
    }

    /** Light at a point of world 'w' into {@link #light}; returns the share of sky light (0..1). */
    private float computeLight(World w, double x, double y, double z) {
        int bx = (int) Math.floor(x), by = (int) Math.floor(y), bz = (int) Math.floor(z);
        if (w.getBlock(bx, by, bz).isOpaqueCube()) by++;
        int sky = w.getSky(bx, by, bz);
        dynamicLevel(x, y, z);
        float stored = w.getBlockLight(bx, by, bz);
        float dynMax = Math.max(dyn[0], Math.max(dyn[1], dyn[2]));
        if (colored) {
            ModelBatch.lightmap(sky, Math.max(w.getBlockLight(0, bx, by, bz), dyn[0]), Math.max(w.getBlockLight(1, bx, by, bz), dyn[1]),
                    Math.max(w.getBlockLight(2, bx, by, bz), dyn[2]), sun, gamma, flicker, light);
        } else ModelBatch.lightmap(sky, Math.max(stored, dynMax), sun, gamma, flicker, light);
        float f = 1 - sky / 15f, skyPart = (1 - f) / (f * 3 + 1) * sun;
        float bl = 1 - Math.max(stored, dynMax) / 15f, blockPart = (1 - bl) / (bl * 3 + 1) * flicker;
        return skyPart / (skyPart + blockPart + 1e-3f);
    }

    private void lightAt(double x, double y, double z) {
        mb.sky = computeLight(world, x, y, z);
        mb.color(light[0], light[1], light[2], 1);
    }

    /** Pose of the player model (third person view and the player's shadow). */
    public static final class PlayerPose {
        public double x, y, z;
        public float bodyYaw, headYaw, pitch, limbSwing, limbAmount, swing, age;
        public boolean sneaking;
        /** The player's posture, smoothed (see {@link derpcraft.game.Stance} for the layout); null = NPCs, which use sneaking / sitting. */
        public float[] stanceVis;
        public ItemStack held;
        /** Skin texture (null = the player skin), slim (3 px) arms, sitting pose, waving arm. */
        public Texture2D skin;
        public boolean slim, sitting, waving;
        /** Red hurt flash (0..1) and death fall (0..1, the body tips over sideways like vanilla mobs). */
        public float hurt, death;
        /** Pose library: blend from poseFrom to poseTo by poseT (either may be null = normal animation). */
        public derpcraft.npc.Poses.Def poseFrom, poseTo;
        public float poseT = 1;
        public ItemStack offhand;
        /** Helmet (or hat), chestplate, leggings, boots. */
        public ItemStack[] armor;
        public float scale = 1;
        /** Selfie: the right arm stretches to the hand point (world); the held item is not drawn. */
        public boolean selfie;
        public double selfieX, selfieY, selfieZ;
        /** 3D models worn on body parts (game/3d). */
        public java.util.List<derpcraft.npc.Attachment> attachments;
        /** A gun in the hands: the kick of a shot (0..1) and a reload (0..1, -1 = none; empty = with the bolt). */
        public float gunKick, gunReload = -1;
        public boolean gunReloadEmpty;
        /** A melee strike with the gun: its kind (GunType.MELEE index, -1 = none) and its time (seconds). */
        public int gunMelee = -1;
        public float gunMeleeTime;
        /** Fist fighting: the guard up (0..1) and how far each fist is out in a punch (0..1). */
        public float fists, punchLeft, punchRight;
        /** Both arms stretched forward, pushing a car (0..1). */
        public float push;
        /** A rider's roll with the car while it rolls over (degrees, 0 normally). */
        public float vehRoll;
        /** The gun carried on the back (Left Alt + F; null = none) and the right arm reaching over the shoulder for it (0..1). */
        public ItemStack backGun;
        public float backReach;
        /** Magazines in the belt pouches (Left Alt + E; 6 entries, null = empty; the array itself may be null). */
        public ItemStack[] pouches;
        /** A body fallen dead (npc/Ragdoll.parts): 6 part matrices (head, body, right arm, left arm, right leg, left leg), relative to x, y, z; null = alive. */
        public float[] ragdoll;
    }

    /** NPC actors to draw (and cast shadows). */
    public final List<PlayerPose> actors = new ArrayList<>();

    /** View bobbing transform for the first person hand (null = none), set by the session every frame. */
    public Mat4 handBob;
    /** Player to draw in the main pass (third person), or null. */
    public PlayerPose player;
    /** Player that only casts a shadow (first person), or null. */
    public PlayerPose playerShadow;
    private boolean shadowPass;
    /** Items lie flat on the ground and tumble while falling. */
    public boolean itemPhysics;
    /** Position of the player the collected items fly to. */
    public double collectX, collectY, collectZ;

    // ------------------------------------------------------------------------------------------ world pass
    public void render(World w, Mat4 projView, float partial, double cx, double cy, double cz, WorldRenderer.FrameParams fp,
                       Raycast.Hit target, float breakProgress, int renderDistanceBlocks, derpcraft.util.Frustum frustum) {
        world = w;
        camX = cx; camY = cy; camZ = cz;
        dynFp = fp;
        sun = fp.sun; gamma = fp.gamma; flicker = fp.flicker; colored = fp.coloredLight > 0;
        if (meshVersion != assets.version) { spriteMeshes.clear(); meshVersion = assets.version; }
        glEnable(GL_DEPTH_TEST);
        glDisable(GL_CULL_FACE);
        glEnable(GL_BLEND);
        glBlendFuncSeparate(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA, GL_ONE, GL_ONE_MINUS_SRC_ALPHA);
        mb.begin(projView, assets.blocks.texId, fp.fogStart, fp.fogEnd, fp.fogR, fp.fogG, fp.fogB);

        if (fp.cascades > 0 || fp.entityCascades > 0 || fp.rt == 1) mb.lit(fp);
        fxTick = !viewPass && w.gameTime != lastFxTime;     // camera pictures don't spawn fire / torch particles
        if (fxTick) { lastFxTime = w.gameTime; fireCells.clear(); }
        vehicleRange = Math.min(renderDistanceBlocks, 256);
        drawModels(partial, Math.min(renderDistanceBlocks, 96), frustum);
        if (fxTick) fireFx();
        if (player != null) playerModel(player, partial);
        droppedMags(partial);
        for (PlayerPose a : actors) {
            double ax = a.x - camX, ay = a.y - camY, az = a.z - camZ;
            if (ax * ax + ay * ay + az * az > 160 * 160) continue;
            if (frustum != null) {
                // the box grows with the actor's scale (a giant was culled while its head was still on screen); a body
                // lying in a ragdoll reaches further sideways and a little below its point
                float sc = Math.max(0.1f, a.scale);
                float x0 = -sc, y0 = 0, z0 = -sc, x1 = sc, y1 = 2.1f * sc, z1 = sc;
                float[] rg = a.ragdoll;
                if (rg != null) {          // the joints of the six parts (relative to x, y, z), a limb's length around them
                    x0 = y0 = z0 = Float.MAX_VALUE; x1 = y1 = z1 = -Float.MAX_VALUE;
                    for (int k = 12; k < rg.length; k += 16) {
                        x0 = Math.min(x0, rg[k]); y0 = Math.min(y0, rg[k + 1]); z0 = Math.min(z0, rg[k + 2]);
                        x1 = Math.max(x1, rg[k]); y1 = Math.max(y1, rg[k + 1]); z1 = Math.max(z1, rg[k + 2]);
                    }
                    float e = 0.9f * sc;
                    x0 -= e; y0 -= e; z0 -= e; x1 += e; y1 += e; z1 += e;
                }
                if (!frustum.testAabb((float) ax + x0, (float) ay + y0, (float) az + z0, (float) ax + x1, (float) ay + y1, (float) az + z1)) continue;
            }
            playerModel(a, partial);
        }
        mb.flush();
        particles(partial, frustum);
        bulletHoles(frustum);
        bloodSplats(frustum);
        skidMarks(frustum);
        tracers(partial);
        if (target != null && breakProgress > 0) crack(target, breakProgress);
        if (!derpcraft.gun.Ballistics.DOOR_HITS.isEmpty()) doorCracks();
        if (!derpcraft.gun.Ballistics.BLOCK_HITS.isEmpty()) blockCracks();
        mb.flush();
        glDisable(GL_BLEND);
        glEnable(GL_CULL_FACE);
    }

    /** Chests, signs and beds (positions collected by the chunk mesher) and dropped items. */
    private void drawModels(float partial, int range, derpcraft.util.Frustum frustum) {
        World w = world;
        double cx = camX, cy = camY, cz = camZ;
        int pcx = (int) Math.floor(cx) >> 4, pcz = (int) Math.floor(cz) >> 4, cr = range / 16 + 1;
        for (int dx = -cr; dx <= cr; dx++)
            for (int dz = -cr; dz <= cr; dz++) {
                Chunk c = w.getChunk(pcx + dx, pcz + dz);
                if (c == null) continue;
                for (int sy = 0; sy < 16; sy++) {
                    int[] list = c.entityBlocks[sy];
                    if (list == null) continue;
                    for (int i : list) {
                        int x = (c.cx << 4) + (i & 15), y = sy * 16 + (i >> 8), z = (c.cz << 4) + ((i >> 4) & 15);
                        double ex = x + 0.5 - cx, ey = y + 0.5 - cy, ez = z + 0.5 - cz;
                        if (ex * ex + ey * ey + ez * ez > range * range) continue;
                        int st = w.getState(x, y, z);
                        Block b = Blocks.byId(st >> 4);
                        // a canvas picture may be moved and sized far beyond its block: no box test for it
                        if (b == derpcraft.camera.CanvasBlock.INSTANCE) {
                            if (!shadowPass && w.getBlockEntity(x, y, z) instanceof derpcraft.camera.CanvasEntity ce) canvas(ce, st & 15);
                            continue;
                        }
                        if (frustum != null && !frustum.testAabb((float) (ex - 1.5), (float) (ey - 1), (float) (ez - 1.5), (float) (ex + 1.5), (float) (ey + 1), (float) (ez + 1.5))) continue;
                        if (b instanceof FunctionalBlocks.FireBlock) {      // drawn by the chunk mesh; here only for the particles (once per tick)
                            if (fxTick && !shadowPass) fireCells.add(new int[]{x, y, z});
                            continue;
                        }
                        if (b instanceof FunctionalBlocks.BedBlock bed) bed(bed, x, y, z, st & 15);
                        else if (b.shape == Block.Shape.TORCH) {
                            if (shadowPass) continue;
                            int tm = st & 15;
                            if (tm >= 1 && tm <= 8) wallTorch(b, x, y, z, tm);       // standing torches are in the chunk mesh
                            if (fxTick) torchFx(b, x, y, z, tm);
                        }
                        else if (b instanceof FunctionalBlocks.ChestBlock chest && w.getBlockEntity(x, y, z) instanceof ContainerEntity ce) chest(chest, ce, st & 15, partial);
                        else if (b instanceof FunctionalBlocks.SignBlock sign && w.getBlockEntity(x, y, z) instanceof SignEntity se) sign(sign, se, st & 15);
                        else if (b instanceof FunctionalBlocks.ItemFrameBlock && w.getBlockEntity(x, y, z) instanceof derpcraft.world.be.ItemFrameEntity fe) itemFrame(fe, st & 15);
                        else if (b instanceof derpcraft.block.ModBlock blk && blk.textLines > 0 && !shadowPass && w.getBlockEntity(x, y, z) instanceof derpcraft.world.be.ModBlockEntity me) modText(blk, me, st & 15);
                        else if (b == derpcraft.model.Model3DBlock.INSTANCE && w.getBlockEntity(x, y, z) instanceof derpcraft.model.ModelEntity me) modelBlock(me);
                        else if (b == derpcraft.camera.CameraBlock.INSTANCE && w.getBlockEntity(x, y, z) instanceof derpcraft.camera.CameraEntity ce) cameraBlock(ce);
                    }
                }
            }
        for (Entity e : w.entities) {
            if (e instanceof ItemEntity ie) {
                // dropped items outside the view are skipped (piles of items from a broken chest cost a lot)
                if (frustum != null && ie.collect < 0) {
                    float ex = (float) (ie.x - cx), ey = (float) (ie.y - cy), ez = (float) (ie.z - cz);
                    if (!frustum.testAabb(ex - 0.75f, ey - 0.5f, ez - 0.75f, ex + 0.75f, ey + 1f, ez + 0.75f)) continue;
                }
                item(ie, partial);
            }
            else if (e instanceof derpcraft.entity.FallingBlock fb) fallingBlock(fb, partial);
            else if (e instanceof derpcraft.entity.Mob m) mob(m, partial, frustum);
            else if (e instanceof derpcraft.entity.Projectile pr) projectile(pr, partial);
        }
        vehicles(partial, shadowPass ? range : Math.max(range, vehicleRange), frustum);
    }

    /** How far cars are drawn (farther than chests and signs: they drive along long roads). */
    private int vehicleRange = 96;

    // ------------------------------------------------------------------------------------------ vehicles
    /** Cars: the body, the animated parts (wheels, doors, steering wheel) and the blocks carried in their block places. */
    private void vehicles(float partial, int range, derpcraft.util.Frustum frustum) {
        derpcraft.vehicle.Vehicles vs = derpcraft.vehicle.Vehicles.current;
        if (vs == null) return;
        for (derpcraft.vehicle.Vehicle v : vs.list()) {
            derpcraft.vehicle.VehicleModel vm = v.vm();
            if (vm == null) continue;
            double ix = v.px + (v.x - v.px) * partial, iy = v.py + (v.y - v.py) * partial, iz = v.pz + (v.z - v.pz) * partial;
            float rx = (float) (ix - camX), ry = (float) (iy - camY), rz = (float) (iz - camZ);
            float rad = Math.max(Math.max(-vm.minX, vm.maxX), Math.max(-vm.minZ, vm.maxZ)) + 0.5f;
            if (rx * rx + rz * rz > (range + rad) * (range + rad)) continue;
            if (frustum != null && !frustum.testAabb(rx - rad, ry - 0.5f, rz - rad, rx + rad, ry + vm.maxY + 1.5f, rz + rad)) continue;
            if (!shadowPass) lightAt(ix, iy + Math.min(1.2, vm.maxY * 0.7), iz);
            else mb.color(1, 1, 1, 1);
            if (v.charred > 0) { float k = 1 - 0.88f * v.charred; mb.color(mb.r * k, mb.g * k, mb.b * k, 1); }          // burnt black
            Mat4 base = v.baseMatrix(partial, camX, camY, camZ);
            Mat4 body = new Mat4().set(base).mul(v.bodyAnim(vm, partial));         // everything but the wheels rides on the springs
            if (meshVersion2 != derpcraft.vehicle.Transports.version()) {
                for (StaticMesh sm : partMeshes.values()) sm.delete();
                partMeshes.clear();
                meshVersion2 = derpcraft.vehicle.Transports.version();
            }
            for (derpcraft.vehicle.VehicleModel.Part p : vm.parts) {
                Mat4 m = p.role == derpcraft.vehicle.VehicleModel.BODY ? body
                        : new Mat4().set(p.role == derpcraft.vehicle.VehicleModel.WHEEL ? base : body).mul(v.partAnim(vm, p, partial));
                StaticMesh sm = partMeshes.get(p);
                if (sm == null) { sm = new StaticMesh(p.groups); partMeshes.put(p, sm); }
                mb.drawStatic(sm, m);
            }
            for (int i = 0; i < vm.slots.size(); i++) {
                int st = v.slots[i].state;
                if (st == 0) continue;
                float[] sp = vm.slots.get(i);
                Mat4 m = new Mat4().set(body).translate(sp[0], sp[1] + 0.5f, sp[2]);
                slotBlock(m, st);
            }
        }
    }

    /** Vehicle parts on the GPU (uploaded once per model load). */
    private final java.util.Map<derpcraft.vehicle.VehicleModel.Part, StaticMesh> partMeshes = new java.util.IdentityHashMap<>();
    private int meshVersion2 = -1;

    /** A block with its state (meta: lit furnace, lever position, turned stairs) as a unit cube around the origin of 'm'. */
    private void slotBlock(Mat4 m, int state) {
        Block b = Blocks.byId(state >> 4);
        Item it = derpcraft.game.Session.itemFor(b);
        int tint = it != null ? tintFor(it, dummyGui) : 0xFFFFFF;
        if (b instanceof FunctionalBlocks.ChestBlock || b instanceof FunctionalBlocks.BedBlock) { drawBlock(m, b, tint); return; }
        Block.Shape sh = b.shape;
        if (sh == Block.Shape.CUBE || sh == Block.Shape.SLAB || sh == Block.Shape.CARPET) { drawCube(m, b, state, tint); return; }
        if (sh == Block.Shape.CROSS || sh == Block.Shape.TORCH) {
            // two crossed planes like a plant
            int layer = assets.tables.faceLayer[state * 6 + 2];
            mb.layer(layer);
            float k = 0.45f;
            mb.quad(m, new float[]{-k, 0.5f, -k, -k, -0.5f, -k, k, -0.5f, k, k, 0.5f, k}, new float[]{0, 0, 0, 1, 1, 1, 1, 0});
            mb.quad(m, new float[]{-k, 0.5f, k, -k, -0.5f, k, k, -0.5f, -k, k, 0.5f, -k}, new float[]{0, 0, 0, 1, 1, 1, 1, 0});
            return;
        }
        drawBlockModel(m, b, state, tint);
    }

    /**
     * Depth-only pass into a shadow cascade: block entities, dropped items and the player.
     * 'vp' maps camera-relative positions into the cascade.
     */
    public void renderShadowCasters(World w, Mat4 vp, float partial, double cx, double cy, double cz, float radius) {
        world = w;
        camX = cx; camY = cy; camZ = cz;
        shadowPass = true;
        mb.begin(vp, assets.blocks.texId, 0, 0, 0, 0, 0);
        mb.alphaCut(0.5f);
        drawModels(partial, (int) Math.min(radius, 64), null);
        PlayerPose p = player != null ? player : playerShadow;
        if (p != null) playerModel(p, partial);
        for (PlayerPose a : actors) {
            double ax = a.x - cx, az = a.z - cz;
            if (ax * ax + az * az < radius * radius * 1.5) playerModel(a, partial);
        }
        mb.flush();
        mb.alphaCut(0.1f);
        shadowPass = false;
    }

    // ------------------------------------------------------------------------------------------ chests
    private void chest(FunctionalBlocks.ChestBlock cb, ContainerEntity ce, int meta, float partial) {
        int x = ce.x, y = ce.y, z = ce.z;
        int[] partner = cb.partner(world, x, y, z);
        boolean dbl = partner != null;
        if (dbl && (partner[0] < x || partner[1] < z)) return; // the other half draws the double chest
        int front = Dir.HORIZONTAL[meta & 3];
        int i = switch (front) { case Dir.NORTH -> 2; case Dir.SOUTH -> 3; case Dir.WEST -> 4; default -> 5; };
        int j = switch (i) { case 2 -> 180; case 4 -> 90; case 5 -> -90; default -> 0; };
        Texture2D tex = cb.texture.equals("ender") ? assets.chestEnder : cb.texture.equals("trapped")
                ? (dbl ? assets.chestTrappedDouble : assets.chestTrapped) : (dbl ? assets.chestNormalDouble : assets.chestNormal);
        mb.texture(tex);
        lightAt(x + 0.5, y + 0.5, z + 0.5);
        Mat4 m = new Mat4().translate((float) (x - camX), (float) (y + 1 - camY), (float) (z + 1 - camZ));
        m.scale(1, -1, -1);
        m.translate(0.5f, 0.5f, 0.5f);
        if (dbl) {
            if (i == 2 && partner[0] > x) m.translate(1, 0, 0);
            if (i == 5 && partner[1] > z) m.translate(0, 0, -1);
        }
        m.rotate((float) Math.toRadians(j), 0, 1, 0);
        m.translate(-0.5f, -0.5f, -0.5f);
        float lid = ce.prevLid + (ce.lid - ce.prevLid) * partial;
        if (dbl) {
            var other = world.getBlockEntity(partner[0], y, partner[1]);
            if (other instanceof ContainerEntity o) lid = Math.max(lid, o.prevLid + (o.lid - o.prevLid) * partial);
        }
        float f = 1 - lid;
        f = 1 - f * f * f;
        float angle = -(f * (float) Math.PI / 2f);
        int w = dbl ? 30 : 14, tw = dbl ? 128 : 64, th = 64;
        Mat4 lidM = new Mat4().set(m).translate(1 / 16f, 7 / 16f, 15 / 16f).rotate(angle, 1, 0, 0);
        mb.box(lidM, 0, -5, -14, w, 5, 14, 0, 0, tw, th, 1, 0.8f);
        Mat4 knob = new Mat4().set(m).translate((dbl ? 16 : 8) / 16f, 7 / 16f, 15 / 16f).rotate(angle, 1, 0, 0);
        mb.box(knob, -1, -2, -15, 2, 4, 1, 0, 0, tw, th, 1, 0.8f);
        Mat4 below = new Mat4().set(m).translate(1 / 16f, 6 / 16f, 1 / 16f);
        mb.box(below, 0, 0, 0, w, 10, 14, 0, 19, tw, th, 1, 0.8f);
    }

    // ------------------------------------------------------------------------------------------ signs
    private void sign(FunctionalBlocks.SignBlock sb, SignEntity se, int meta) {
        lightAt(se.x + 0.5, se.y + 0.5, se.z + 0.5);
        Mat4 m = new Mat4().translate((float) (se.x + 0.5 - camX), (float) (se.y + 0.5 - camY), (float) (se.z + 0.5 - camZ));
        if (!sb.wall) {
            m.rotate((float) Math.toRadians(-(meta * 360) / 16f), 0, 1, 0);
        } else {
            int wall = switch (meta & 3) { case 1 -> Dir.EAST; case 2 -> Dir.SOUTH; case 3 -> Dir.WEST; default -> Dir.NORTH; };
            float f2 = switch (wall) { case Dir.SOUTH -> 180; case Dir.EAST -> 90; case Dir.WEST -> -90; default -> 0; };
            m.rotate((float) Math.toRadians(-f2), 0, 1, 0);
            m.translate(0, -0.3125f, -0.4375f);
        }
        mb.texture(assets.sign);
        Mat4 mm = new Mat4().set(m).scale(0.6666667f, -0.6666667f, -0.6666667f);
        mb.box(mm, -12, -14, -1, 24, 12, 2, 0, 0, 64, 32, 1, 0.8f);
        if (!sb.wall) mb.box(mm, -1, -2, -1, 2, 14, 2, 0, 14, 64, 32, 1, 0.8f);
        // text
        Mat4 tm = new Mat4().set(m).translate(0, 0.33333334f, 0.046666667f).scale(0.010416667f, -0.010416667f, 0.010416667f);
        Font font = assets.font;
        mb.texture(font.tex);
        float lr = mb.r, lg = mb.g, lb = mb.b;
        mb.color(0, 0, 0, 1);
        for (int k = 0; k < 4; k++) {
            String s = se.lines[k];
            if (s == null || s.isEmpty()) continue;
            float tx = -font.width(s) / 2f, ty = k * 10 - 4 * 5;
            text(tm, s, tx, ty);
        }
        mb.color(lr, lg, lb, 1);
    }

    /** Text of a mod text block (sign boards, screens) on its front plane. */
    private void modText(derpcraft.block.ModBlock blk, derpcraft.world.be.ModBlockEntity e, int meta) {
        String[] lines = e.lines;
        if (lines == null) return;
        boolean any = false;
        for (String l : lines) if (l != null && !l.isEmpty()) { any = true; break; }
        if (!any) return;
        boolean turns = blk.placement == Block.Placement.FACING || blk.textStyle == derpcraft.block.ModBlock.TextStyle.PANEL;
        int q = turns ? meta & 3 : 0;
        int front = Dir.HORIZONTAL[q];
        float[] a = blk.textArea(meta);
        int x = e.x, y = e.y, z = e.z;
        lightAt(x + 0.5 + Dir.DX[front] * 0.6, y + 0.5, z + 0.5 + Dir.DZ[front] * 0.6);
        float lr = mb.r, lg = mb.g, lb = mb.b;
        int c = blk.textColor;
        float cr = (c >> 16 & 255) / 255f, cg = (c >> 8 & 255) / 255f, cb = (c & 255) / 255f;
        if (blk.textGlow) mb.color(cr, cg, cb, 1);
        else mb.color(cr * lr, cg * lg, cb * lb, 1);
        Mat4 m = new Mat4().translate((float) (x + 0.5 - camX), (float) (y - camY), (float) (z + 0.5 - camZ));
        if (q != 0) m.rotate((float) Math.toRadians(-q * 90), 0, 1, 0);
        m.translate((a[0] - 8) / 16f, a[1] / 16f, (a[2] - 8) / 16f + 0.004f);
        float sc = blk.textScale / 96f;
        m.scale(sc, -sc, sc);
        float areaPx = a[3] / 16f / sc;
        Font font = assets.font;
        mb.texture(font.tex);
        int n = lines.length;
        float top = -n * 10 / 2f + 1;
        for (int k = 0; k < n; k++) {
            String s = lines[k];
            if (s == null || s.isEmpty()) continue;
            float w = font.width(s);
            float fit = w > areaPx ? areaPx / w : 1;
            Mat4 lm = new Mat4().set(m).translate(0, top + k * 10 + 4, 0).scale(fit, fit, 1);
            text(lm, s, -w / 2f, -4);
        }
        mb.color(lr, lg, lb, 1);
    }

    private void text(Mat4 m, String s, float x, float y) {
        Font f = assets.font;
        float c = 1f / 16f;
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch >= 256) ch = '?';
            if (ch != ' ') {
                float u = (ch % 16) * c, v = (ch / 16) * c;
                mb.quad(m, new float[]{x, y, -0.01f, x, y + 8, -0.01f, x + 8, y + 8, -0.01f, x + 8, y, -0.01f},
                        new float[]{u, v, u, v + c, u + c, v + c, u + c, v});
            }
            x += f.widths[ch];
        }
    }

    // ------------------------------------------------------------------------------------------ wall torches
    /**
     * A torch on a wall, like Minecraft's torch_wall.json: the 2x10x2 px torch starts in the wall plane and leans 22.5 degrees
     * away from it; with Alt placed (meta 5-8) it sticks straight out (90 degrees).
     */
    private void wallTorch(Block b, int x, int y, int z, int meta) {
        int wall = Dir.HORIZONTAL[(meta - 1) & 3];          // the side the wall is on
        boolean straight = meta >= 5;
        float angle = straight ? (float) Math.PI / 2 : (float) Math.toRadians(22.5);
        float pivotY = straight ? 8f : 3.5f;
        float wx = Dir.DX[wall], wz = Dir.DZ[wall];
        String tn = b.textureName(Dir.NORTH, 0);
        if (tn == null) return;
        lightAt(x + 0.5, y + 0.5, z + 0.5);
        Mat4 m = new Mat4().translate((float) (x - camX) + 0.5f + wx * 0.5f, (float) (y - camY) + pivotY / 16f, (float) (z - camZ) + 0.5f + wz * 0.5f);
        m.rotate(angle, -wz, 0, wx);                         // the top turns away from the wall
        mb.layer(assets.blocks.layer(tn));
        float br = mb.r, bg = mb.g, bb = mb.b;
        // {corner x, y, z (px) x4}, {u, v (px) x4}, shade
        float[][][] faces = {
                {{-1, 10, -1, -1, 10, 1, 1, 10, 1, 1, 10, -1}, {7, 6, 7, 8, 9, 8, 9, 6}, {1f}},
                {{-1, 0, 1, -1, 0, -1, 1, 0, -1, 1, 0, 1}, {7, 13, 7, 15, 9, 15, 9, 13}, {0.5f}},
                {{1, 10, -1, 1, 0, -1, -1, 0, -1, -1, 10, -1}, {7, 6, 7, 16, 9, 16, 9, 6}, {0.8f}},
                {{-1, 10, 1, -1, 0, 1, 1, 0, 1, 1, 10, 1}, {7, 6, 7, 16, 9, 16, 9, 6}, {0.8f}},
                {{-1, 10, -1, -1, 0, -1, -1, 0, 1, -1, 10, 1}, {7, 6, 7, 16, 9, 16, 9, 6}, {0.6f}},
                {{1, 10, 1, 1, 0, 1, 1, 0, -1, 1, 10, -1}, {7, 6, 7, 16, 9, 16, 9, 6}, {0.6f}}};
        float[] p = new float[12], uv = new float[8];
        for (float[][] f : faces) {
            for (int i = 0; i < 12; i++) p[i] = f[0][i] / 16f;
            for (int i = 0; i < 8; i++) uv[i] = f[1][i] / 16f;
            mb.r = br * f[2][0]; mb.g = bg * f[2][0]; mb.b = bb * f[2][0];
            mb.quad(m, p, uv);
        }
        mb.r = br; mb.g = bg; mb.b = bb;
    }

    // ------------------------------------------------------------------------------------------ beds
    private void bed(FunctionalBlocks.BedBlock bb, int x, int y, int z, int meta) {
        boolean head = (meta & 8) != 0;
        int facing = Dir.HORIZONTAL[meta & 3];
        float f = 0, f1 = 0, f2 = 0;
        switch (facing) {
            case Dir.SOUTH -> { f = 180; f1 = 1; f2 = 1; }
            case Dir.WEST -> { f = -90; f2 = 1; }
            case Dir.EAST -> { f = 90; f1 = 1; }
            default -> {}
        }
        mb.texture(assets.beds[bb.color]);
        lightAt(x + 0.5, y + 0.6, z + 0.5);
        Mat4 m = new Mat4().translate((float) (x + f1 - camX), (float) (y + 0.5625f - camY), (float) (z + f2 - camZ));
        m.rotate((float) Math.toRadians(90), 1, 0, 0);
        m.rotate((float) Math.toRadians(f), 0, 0, 1);
        float halfPi = (float) Math.PI / 2;
        mb.box(m, 0, 0, 0, 16, 16, 6, 0, head ? 0 : 22, 64, 64, 1, 0.8f);
        // ModelBed legs: {u, v, x, y, z}, rotateAngleX = pi/2, rotateAngleZ per leg; legs 1 and 3 belong to the head
        int[][] legs = {{50, 0, 0, 6, -16}, {50, 6, 0, 6, 0}, {50, 12, -16, 6, -16}, {50, 18, -16, 6, 0}};
        float[] legZ = {0, halfPi, halfPi * 3, (float) Math.PI};
        for (int k = 0; k < 4; k++) {
            if (((k & 1) == 1) != head) continue;
            Mat4 lm = new Mat4().set(m).rotate(legZ[k], 0, 0, 1).rotate(halfPi, 1, 0, 0);
            mb.box(lm, legs[k][2], legs[k][3], legs[k][4], 3, 3, 3, legs[k][0], legs[k][1], 64, 64, 1, 0.8f);
        }
    }

    // ------------------------------------------------------------------------------------------ items
    /** Cached extruded sprite (x,y,z,u,v,shade per vertex, triangles) for a texture layer, item space [0,1]^2. */
    private float[] sprite(int layer) {
        float[] m = spriteMeshes.get(layer);
        if (m != null) return m;
        BlockTextures bt = assets.blocks;
        int[] px = layer < bt.small.length ? bt.small[layer] : null;
        int res = bt.smallRes;
        List<Float> out = new ArrayList<>();
        float t = 0.5f / 16f;
        float[][] faces = {
                {0, 0, t, 1, 0, t, 1, 1, t, 0, 1, t},     // front
                {1, 0, -t, 0, 0, -t, 0, 1, -t, 1, 1, -t}}; // back
        float[][] fuv = {{0, 1, 1, 1, 1, 0, 0, 0}, {1, 1, 0, 1, 0, 0, 1, 0}};
        for (int f = 0; f < 2; f++) addQuad(out, faces[f], fuv[f], f == 0 ? 1f : 0.85f);
        if (px != null) {
            for (int y = 0; y < res; y++)
                for (int x = 0; x < res; x++) {
                    if ((px[y * res + x] >>> 24) < 16) continue;
                    float x0 = x / (float) res, x1 = (x + 1) / (float) res, y0 = 1 - (y + 1) / (float) res, y1 = 1 - y / (float) res;
                    float u0 = x / (float) res, u1 = (x + 1) / (float) res, v0 = y / (float) res, v1 = (y + 1) / (float) res;
                    float[] uvp = {u0, v0, u1, v0, u1, v1, u0, v1};
                    if (x == 0 || (px[y * res + x - 1] >>> 24) < 16) addQuad(out, new float[]{x0, y0, -t, x0, y0, t, x0, y1, t, x0, y1, -t}, uvp, 0.75f);
                    if (x == res - 1 || (px[y * res + x + 1] >>> 24) < 16) addQuad(out, new float[]{x1, y0, t, x1, y0, -t, x1, y1, -t, x1, y1, t}, uvp, 0.75f);
                    if (y == 0 || (px[(y - 1) * res + x] >>> 24) < 16) addQuad(out, new float[]{x0, y1, t, x1, y1, t, x1, y1, -t, x0, y1, -t}, uvp, 0.95f);
                    if (y == res - 1 || (px[(y + 1) * res + x] >>> 24) < 16) addQuad(out, new float[]{x0, y0, -t, x1, y0, -t, x1, y0, t, x0, y0, t}, uvp, 0.6f);
                }
        }
        m = new float[out.size()];
        for (int i = 0; i < m.length; i++) m[i] = out.get(i);
        spriteMeshes.put(layer, m);
        return m;
    }

    private static void addQuad(List<Float> out, float[] p, float[] uv, float shade) {
        int[] o = {0, 1, 2, 2, 3, 0};
        for (int k : o) {
            out.add(p[k * 3]); out.add(p[k * 3 + 1]); out.add(p[k * 3 + 2]);
            out.add(uv[k * 2]); out.add(uv[k * 2 + 1]); out.add(shade);
        }
    }

    /** Draws an extruded sprite centred at the origin of 'm' (1 unit = 1 block). */
    private void drawSprite(Mat4 m, int layer) {
        float[] mesh = sprite(layer);
        float br = mb.r, bg = mb.g, bb = mb.b;
        mb.layer(layer);
        for (int i = 0; i < mesh.length; i += 6) {
            float s = mesh[i + 5];
            mb.r = br * s; mb.g = bg * s; mb.b = bb * s;
            mb.v(m, mesh[i] - 0.5f, mesh[i + 1] - 0.5f, mesh[i + 2], mesh[i + 3], mesh[i + 4]);
        }
        mb.r = br; mb.g = bg; mb.b = bb;
    }

    /** Draws a block as a cube of size 1 centred on the origin of 'm'. */
    private void drawCube(Mat4 m, Block b, int tintRGB) { drawCube(m, b, b.id << 4, tintRGB); }

    /** The same with a block state (the textures of its meta: a lit furnace's front, coloured wool...). */
    private void drawCube(Mat4 m, Block b, int st, int tintRGB) {
        RenderTables t = assets.tables;
        float br = mb.r, bg = mb.g, bb = mb.b;
        float tr = ((tintRGB >> 16) & 255) / 255f, tg = ((tintRGB >> 8) & 255) / 255f, tb = (tintRGB & 255) / 255f;
        float[] shades = {0.5f, 1f, 0.8f, 0.8f, 0.6f, 0.6f};
        float h = b.shape == Block.Shape.SLAB ? 0.5f : b.shape == Block.Shape.CARPET ? 1 / 16f : 1f;
        for (int f = 0; f < 6; f++) {
            int layer = t.faceLayer[st * 6 + f];
            boolean tintFace = b.tint != Block.Tint.NONE && (b.sideOverlay == null || f == Dir.UP);
            mb.r = br * shades[f] * (tintFace ? tr : 1); mb.g = bg * shades[f] * (tintFace ? tg : 1); mb.b = bb * shades[f] * (tintFace ? tb : 1);
            mb.layer(layer);
            cubeFace(m, f, h, 0);
            if (b.sideOverlay != null && f >= 2) {
                mb.r = br * shades[f] * tr; mb.g = bg * shades[f] * tg; mb.b = bb * shades[f] * tb;
                mb.layer(t.overlay[b.id]);
                cubeFace(m, f, h, 0.001f);
            }
        }
        mb.r = br; mb.g = bg; mb.b = bb;
    }

    private void cubeFace(Mat4 m, int f, float h, float e) {
        float x0 = -0.5f - e, x1 = 0.5f + e, y0 = -0.5f - e, y1 = -0.5f + h + e, z0 = -0.5f - e, z1 = 0.5f + e;
        float vt = 1 - h;
        float[] p; float[] uv;
        switch (f) {
            case Dir.UP -> { p = new float[]{x0, y1, z0, x0, y1, z1, x1, y1, z1, x1, y1, z0}; uv = new float[]{0, 0, 0, 1, 1, 1, 1, 0}; }
            case Dir.DOWN -> { p = new float[]{x0, y0, z1, x0, y0, z0, x1, y0, z0, x1, y0, z1}; uv = new float[]{0, 0, 0, 1, 1, 1, 1, 0}; }
            case Dir.NORTH -> { p = new float[]{x1, y1, z0, x1, y0, z0, x0, y0, z0, x0, y1, z0}; uv = new float[]{0, vt, 0, 1, 1, 1, 1, vt}; }
            case Dir.SOUTH -> { p = new float[]{x0, y1, z1, x0, y0, z1, x1, y0, z1, x1, y1, z1}; uv = new float[]{0, vt, 0, 1, 1, 1, 1, vt}; }
            case Dir.WEST -> { p = new float[]{x0, y1, z0, x0, y0, z0, x0, y0, z1, x0, y1, z1}; uv = new float[]{0, vt, 0, 1, 1, 1, 1, vt}; }
            default -> { p = new float[]{x1, y1, z1, x1, y0, z1, x1, y0, z0, x1, y1, z0}; uv = new float[]{0, vt, 0, 1, 1, 1, 1, vt}; }
        }
        mb.quad(m, p, uv);
    }

    /** Block items are drawn as the 3D block (cube, fence, gate, chest...); plants, torches, panes etc. stay flat sprites like in the GUI. */
    private boolean rendersAsCube(Item it) {
        if (it.block instanceof FunctionalBlocks.BedBlock) return true;     // the bed item has a flat icon texture, but is shown as the 3D bed
        return it.isBlockItem() && !GuiRenderer.isFlatBlock(it.block);
    }

    /** Draws the block of an item centred on the origin of 'm' (size 1): full cubes as cubes, everything else with its real model. */
    private void drawBlock(Mat4 m, Block b, int tintRGB) {
        if (b instanceof FunctionalBlocks.ChestBlock cb) { drawChestItem(m, cb); return; }
        if (b instanceof FunctionalBlocks.BedBlock bed && assets.beds[bed.color] != null) { drawBedItem(m, bed); return; }
        Block.Shape s = b.shape;
        if (s == Block.Shape.CUBE || s == Block.Shape.SLAB || s == Block.Shape.CARPET) { drawCube(m, b, tintRGB); return; }
        drawBlockModel(m, b, tintRGB);
    }

    /** The boxes of the block model (fences, gates, stairs, walls, decorative models...) scaled into a unit cube. */
    private void drawBlockModel(Mat4 m, Block b, int tintRGB) { drawBlockModel(m, b, b.id << 4, tintRGB); }

    private void drawBlockModel(Mat4 m, Block b, int state, int tintRGB) {
        Block.Shape s = b.shape;
        int conn = s == Block.Shape.FENCE ? 0b0011 : s == Block.Shape.WALL ? 0b1011 : 0;   // same look as the inventory icon
        RenderTables.Quad[] quads = assets.tables.boxQuads(state, conn);
        if (quads.length == 0) { drawCube(m, b, state, tintRGB); return; }
        float br = mb.r, bg = mb.g, bb = mb.b;
        float tr = ((tintRGB >> 16) & 255) / 255f, tg = ((tintRGB >> 8) & 255) / 255f, tb = (tintRGB & 255) / 255f;
        float[] shades = {0.5f, 1f, 0.8f, 0.8f, 0.6f, 0.6f};
        float[] p = new float[12], uv = new float[8];
        for (RenderTables.Quad q : quads) {
            for (int k = 0; k < 4; k++) {
                p[k * 3] = q.pos[k * 3] / 16f - 0.5f; p[k * 3 + 1] = q.pos[k * 3 + 1] / 16f - 0.5f; p[k * 3 + 2] = q.pos[k * 3 + 2] / 16f - 0.5f;
                uv[k * 2] = q.uv[k * 2] / 16f; uv[k * 2 + 1] = q.uv[k * 2 + 1] / 16f;
            }
            float sh = shades[q.dir];
            boolean tinted = q.tint != 0;
            mb.r = br * sh * (tinted ? tr : 1); mb.g = bg * sh * (tinted ? tg : 1); mb.b = bb * sh * (tinted ? tb : 1);
            mb.layer(q.layer);
            mb.quad(m, p, uv);
        }
        mb.r = br; mb.g = bg; mb.b = bb;
    }

    /** Chest item: the chest entity model (closed lid) fitted into a unit cube. */
    private void drawChestItem(Mat4 m, FunctionalBlocks.ChestBlock cb) {
        Texture2D tex = cb.texture.equals("ender") ? assets.chestEnder : cb.texture.equals("trapped") ? assets.chestTrapped : assets.chestNormal;
        mb.texture(tex);
        // chest model space: x right, y down from the top, z towards the back (same flip as chest())
        Mat4 c = new Mat4().set(m).translate(-0.5f, 0.5f, 0.5f).scale(1, -1, -1);
        Mat4 lid = new Mat4().set(c).translate(1 / 16f, 7 / 16f, 15 / 16f);
        mb.box(lid, 0, -5, -14, 14, 5, 14, 0, 0, 64, 64, 1, 0.8f);
        Mat4 knob = new Mat4().set(c).translate(8 / 16f, 7 / 16f, 15 / 16f);
        mb.box(knob, -1, -2, -15, 2, 4, 1, 0, 0, 64, 64, 1, 0.8f);
        Mat4 below = new Mat4().set(c).translate(1 / 16f, 6 / 16f, 1 / 16f);
        mb.box(below, 0, 0, 0, 14, 10, 14, 0, 19, 64, 64, 1, 0.8f);
    }

    /**
     * Bed item: both halves and the legs of the bed model (1 x 2 blocks, long side along z), fitted into the unit cube
     * around the origin of 'm'. The same placement as the inventory icon (GuiRenderer.bedIcon).
     */
    private void drawBedItem(Mat4 m, FunctionalBlocks.BedBlock bed) {
        mb.texture(assets.beds[bed.color]);
        Mat4 b = new Mat4().set(m).scale(BED_FIT, BED_FIT, BED_FIT).translate(-0.5f, -9 / 32f, -1f);
        float halfPi = (float) Math.PI / 2;
        int[][] legs = {{50, 0, 0, 6, -16}, {50, 6, 0, 6, 0}, {50, 12, -16, 6, -16}, {50, 18, -16, 6, 0}};
        float[] legZ = {0, halfPi, halfPi * 3, (float) Math.PI};
        for (int part = 0; part < 2; part++) {
            boolean head = part == 0;
            Mat4 piece = new Mat4().set(b).translate(1, 9 / 16f, head ? 1 : 0).rotate(halfPi, 1, 0, 0).rotate((float) Math.PI, 0, 0, 1);
            mb.box(piece, 0, 0, 0, 16, 16, 6, 0, head ? 0 : 22, 64, 64, 1, 0.8f);
            for (int k = 0; k < 4; k++) {
                if (((k & 1) == 1) != head) continue;
                Mat4 lm = new Mat4().set(piece).rotate(legZ[k], 0, 0, 1).rotate(halfPi, 1, 0, 0);
                mb.box(lm, legs[k][2], legs[k][3], legs[k][4], 3, 3, 3, legs[k][0], legs[k][1], 64, 64, 1, 0.8f);
            }
        }
    }

    /** How much of the unit cube the long side of a held bed fills. */
    private static final float BED_FIT = 0.75f;

    // ------------------------------------------------------------------------------------------ item frames
    /**
     * Item frame: a wooden ring (planks, 1 pixel wide) around a recessed background, hanging flat on the block it is attached to,
     * with its item in the middle turned by 45 degrees a step. Model space: pixels, the wall at z = 0, the front towards +z.
     */
    private void itemFrame(derpcraft.world.be.ItemFrameEntity fe, int meta) {
        int out = Dir.OPPOSITE[FunctionalBlocks.ItemFrameBlock.back(meta)];
        lightAt(fe.x + 0.5, fe.y + 0.5, fe.z + 0.5);
        Mat4 m = new Mat4().translate((float) (fe.x + 0.5 - camX), (float) (fe.y + 0.5 - camY), (float) (fe.z + 0.5 - camZ));
        switch (out) {
            case Dir.EAST -> m.rotate(rad(90), 0, 1, 0);
            case Dir.WEST -> m.rotate(rad(-90), 0, 1, 0);
            case Dir.NORTH -> m.rotate(rad(180), 0, 1, 0);
            case Dir.UP -> m.rotate(rad(-90), 1, 0, 0);
            case Dir.DOWN -> m.rotate(rad(90), 1, 0, 0);
            default -> {}
        }
        m.translate(-0.5f, -0.5f, -0.5f);
        BlockTextures bt = assets.blocks;
        int wood = bt.layer("planks_oak"), back = bt.layer("itemframe_background");
        float front = FACE_SHADE[out];      // the face the viewer sees is lit like a block face that way
        pixelBox(m, 3, 3, 0, 13, 13, 1, back, front);      // background: 10 x 10 pixels
        pixelBox(m, 2, 13, 0, 14, 14, 2, wood, front);     // the wooden ring is 1 pixel wide
        pixelBox(m, 2, 2, 0, 14, 3, 2, wood, front);
        pixelBox(m, 2, 3, 0, 3, 13, 2, wood, front);
        pixelBox(m, 13, 3, 0, 14, 13, 2, wood, front);
        ItemStack st = fe.item();
        if (st == null) return;
        Item it = st.item;
        Mat4 im = new Mat4().set(m).translate(0.5f, 0.5f, 0);
        im.rotate(rad(-fe.rotation * 45f), 0, 0, 1);
        if (rendersAsCube(it)) {
            im.translate(0, 0, 1 / 16f + 0.18f);
            im.scale(0.36f, 0.36f, 0.36f);
            drawBlock(im, it.block, tintFor(it, dummyGui));
        } else {
            im.translate(0, 0, 1.5f / 16f);
            im.scale(0.5f, 0.5f, 0.5f);
            int tint = tintFor(it, dummyGui);
            float br = mb.r, bg = mb.g, bb = mb.b;
            mb.r *= ((tint >> 16) & 255) / 255f; mb.g *= ((tint >> 8) & 255) / 255f; mb.b *= (tint & 255) / 255f;
            drawSprite(im, itemLayer(it));
            mb.r = br; mb.g = bg; mb.b = bb;
        }
    }

    /**
     * Box of a block texture layer in pixel units (0..16 per block) of 'm' (block units): the texture is cut out of the layer
     * where the box is, like a block model. The back (z = z0 side, against the wall) is not drawn.
     */
    private void pixelBox(Mat4 m, float x0, float y0, float z0, float x1, float y1, float z1, int layer, float front) {
        float br = mb.r, bg = mb.g, bb = mb.b;
        mb.layer(layer);
        float s = 1 / 16f;
        float u0 = x0 * s, u1 = x1 * s, vt = 1 - y1 * s, vb = 1 - y0 * s, w0 = z0 * s, w1 = z1 * s;
        x0 *= s; x1 *= s; y0 *= s; y1 *= s; z0 *= s; z1 *= s;
        // front (+z), left (-x), right (+x), top (+y), bottom (-y)
        face(m, new float[]{x0, y1, z1, x0, y0, z1, x1, y0, z1, x1, y1, z1}, new float[]{u0, vt, u0, vb, u1, vb, u1, vt}, front, br, bg, bb);
        face(m, new float[]{x0, y1, z0, x0, y0, z0, x0, y0, z1, x0, y1, z1}, new float[]{w0, vt, w0, vb, w1, vb, w1, vt}, 0.7f, br, bg, bb);
        face(m, new float[]{x1, y1, z1, x1, y0, z1, x1, y0, z0, x1, y1, z0}, new float[]{w1, vt, w1, vb, w0, vb, w0, vt}, 0.7f, br, bg, bb);
        face(m, new float[]{x0, y1, z0, x0, y1, z1, x1, y1, z1, x1, y1, z0}, new float[]{u0, w0, u0, w1, u1, w1, u1, w0}, 0.85f, br, bg, bb);
        face(m, new float[]{x0, y0, z1, x0, y0, z0, x1, y0, z0, x1, y0, z1}, new float[]{u0, w1, u0, w0, u1, w0, u1, w1}, 0.6f, br, bg, bb);
        mb.r = br; mb.g = bg; mb.b = bb;
    }

    private void face(Mat4 m, float[] p, float[] uv, float shade, float br, float bg, float bb) {
        mb.r = br * shade; mb.g = bg * shade; mb.b = bb * shade;
        mb.quad(m, p, uv);
    }

    /** Ticks the player has drawn the bow (0 = not drawing), set by the session. */
    public int bowDraw;

    /** Layer of a held item: the bow shows its three drawing frames (bow_pulling_0..2, vanilla timing 13 / 18 ticks). */
    private int heldLayer(Item it, boolean mine) {
        if (mine && bowDraw > 0 && it.name.equals("bow")) return assets.blocks.layer("items/bow_pulling_" + (bowDraw >= 18 ? 2 : bowDraw >= 13 ? 1 : 0));
        return itemLayer(it);
    }

    public int itemLayer(Item it) {
        if (it.texture != null) return assets.blocks.layer(it.texture);
        if (it.block != null) return assets.tables.faceLayer[(it.block.id << 4) * 6 + Dir.NORTH];
        return 0;
    }

    public int tintFor(Item it, GuiRenderer g) {
        if (it.block == null || it.texture != null) return it.tint;
        return switch (it.block.tint) { case GRASS -> assets.plainsGrass; case FOLIAGE -> assets.plainsFoliage; case FIXED -> it.block.fixedTint; default -> 0xFFFFFF; };
    }

    private void fallingBlock(derpcraft.entity.FallingBlock fb, float partial) {
        double x = fb.px + (fb.x - fb.px) * partial, y = fb.py + (fb.y - fb.py) * partial, z = fb.pz + (fb.z - fb.pz) * partial;
        double rx = x - camX, ry = y - camY, rz = z - camZ;
        if (rx * rx + ry * ry + rz * rz > 96 * 96) return;
        if (!shadowPass) lightAt(x, y + 0.5, z);
        Block b = Blocks.byId(fb.state >> 4);
        Item it = derpcraft.game.Session.itemFor(b);
        Mat4 m = new Mat4().translate((float) rx, (float) ry + 0.5f, (float) rz);
        drawCube(m, b, fb.state, it != null ? tintFor(it, dummyGui) : 0xFFFFFF);
    }

    private void item(ItemEntity ie, float partial) {
        double x = ie.px + (ie.x - ie.px) * partial, y = ie.py + (ie.y - ie.py) * partial, z = ie.pz + (ie.z - ie.pz) * partial;
        boolean collecting = ie.collect >= 0;
        if (collecting) {
            // vanilla pickup: the item accelerates towards the player
            float f = Math.min(1, (ie.collectTotal - ie.collect + partial) / ie.collectTotal);
            f *= f;
            x = ie.sx + (collectX - ie.sx) * f;
            y = ie.sy + (collectY - ie.sy) * f;
            z = ie.sz + (collectZ - ie.sz) * f;
        }
        double dx = x - camX, dz = z - camZ;
        if (dx * dx + dz * dz > 64 * 64) return;
        lightAt(x, y + 0.2, z);
        float age = ie.age + partial;
        Item it = ie.stack.item;
        boolean cube = rendersAsCube(it);
        float cubeSize = it.block instanceof FunctionalBlocks.BedBlock ? 0.35f : 0.25f;     // a bed is long: it needs more room than a cube
        int copies = ie.stack.count > 32 ? 3 : ie.stack.count > 1 ? 2 : 1;
        boolean flat = itemPhysics && (collecting || ie.onGround);
        float spinDeg = (float) Math.toDegrees(ie.spin);
        for (int c = 0; c < copies; c++) {
            Mat4 m;
            if (flat) {
                // lying on the ground: copies spread out a little, each with its own angle
                float ox = c == 0 ? 0 : (c == 1 ? 0.09f : -0.07f), oz = c == 0 ? 0 : (c == 1 ? 0.05f : 0.08f);
                float lift = cube ? cubeSize * 0.5f + c * 0.002f : 0.035f + c * 0.012f;
                m = new Mat4().translate((float) (x - camX) + ox, (float) (y - camY) + lift, (float) (z - camZ) + oz);
                m.rotate((float) Math.toRadians(spinDeg + c * 37), 0, 1, 0);
                if (!cube) m.rotate((float) Math.toRadians(90), 1, 0, 0);
            } else if (itemPhysics && !collecting) {
                // tumbling through the air
                m = new Mat4().translate((float) (x - camX) + c * 0.06f, (float) (y - camY) + 0.15f + c * 0.04f, (float) (z - camZ) + c * 0.05f);
                m.rotate((float) Math.toRadians(spinDeg), 0, 1, 0);
                m.rotate((float) Math.toRadians(age * 25), 1, 0, 0.4f);
            } else {
                float bob = collecting ? 0.1f : (float) Math.sin(age / 10f + ie.spin) * 0.1f + 0.1f;
                float rot = (age / 20f + ie.spin) * (float) (180 / Math.PI);
                m = new Mat4().translate((float) (x - camX) + c * 0.06f, (float) (y - camY) + bob + 0.15f + c * 0.04f, (float) (z - camZ) + c * 0.05f);
                m.rotate((float) Math.toRadians(rot), 0, 1, 0);
            }
            if (cube) {
                m.scale(cubeSize, cubeSize, cubeSize);
                drawBlock(m, it.block, tintFor(it, dummyGui));
            } else {
                m.scale(0.5f, 0.5f, 0.5f);
                float br = mb.r, bg = mb.g, bb = mb.b;
                int tint = tintFor(it, dummyGui);
                mb.r *= ((tint >> 16) & 255) / 255f; mb.g *= ((tint >> 8) & 255) / 255f; mb.b *= (tint & 255) / 255f;
                drawSprite(m, itemLayer(it));
                mb.r = br; mb.g = bg; mb.b = bb;
            }
        }
    }

    // ------------------------------------------------------------------------------------------ player model
    /**
     * Minecraft 1.12 ModelPlayer (64x64 skin, both layers) with the ModelBiped animations: walking, idle arm sway,
     * swinging, sneaking, head look; plus the held item in the right hand (LayerHeldItem).
     */
    private void playerModel(PlayerPose p, float partial) {
        if (p.ragdoll != null) { ragdollModel(p); return; }
        double rx = p.x - camX, ry = p.y - camY, rz = p.z - camZ;
        if (!shadowPass) lightAt(p.x, p.y + 1.2, p.z);
        float ls = p.limbSwing, la = Math.min(1, p.limbAmount);
        float PI = (float) Math.PI;
        // part angles {x, y, z} and pivots
        float[] head = {(float) Math.toRadians(p.pitch), (float) Math.toRadians(p.headYaw), 0};
        float[] body = {0, 0, 0};
        float[] rArm = {(float) Math.cos(ls * 0.6662f + PI) * 2 * la * 0.5f, 0, 0}, lArm = {(float) Math.cos(ls * 0.6662f) * 2 * la * 0.5f, 0, 0};
        float[] rLeg = {(float) Math.cos(ls * 0.6662f) * 1.4f * la, 0, 0}, lLeg = {(float) Math.cos(ls * 0.6662f + PI) * 1.4f * la, 0, 0};
        float[] rArmP = {-5, 2, 0}, lArmP = {5, 2, 0}, rLegP = {-1.9f, 12, 0}, lLegP = {1.9f, 12, 0}, headP = {0, 0, 0};
        if (p.held != null) rArm[0] = rArm[0] * 0.5f - PI / 10;
        if (p.offhand != null) lArm[0] = lArm[0] * 0.5f - PI / 10;
        if (p.swing > 0 && p.fists <= 0) {
            float f = p.swing;
            body[1] = (float) Math.sin(Math.sqrt(f) * PI * 2) * 0.2f;
            rArmP[2] = (float) Math.sin(body[1]) * 5; rArmP[0] = (float) -Math.cos(body[1]) * 5;
            lArmP[2] = (float) -Math.sin(body[1]) * 5; lArmP[0] = (float) Math.cos(body[1]) * 5;
            rArm[1] += body[1]; lArm[1] += body[1]; lArm[0] += body[1];
            float f1 = 1 - f; f1 = f1 * f1 * f1 * f1; f1 = 1 - f1;
            float f2 = (float) Math.sin(f1 * PI), f3 = (float) Math.sin(f * PI) * -(head[0] - 0.7f) * 0.75f;
            rArm[0] -= f2 * 1.2f + f3;
            rArm[1] += body[1] * 2;
            rArm[2] += (float) Math.sin(f * PI) * -0.4f;
        }
        if (p.fists > 0) {
            // fist fighting: both fists up in front of the face (a boxer's guard), each punch straight out where the head looks
            float g = p.fists, hp = head[0], hy = head[1];
            rArm[0] += (-1.35f + hp * 0.3f - rArm[0]) * g; rArm[1] += (-0.55f - rArm[1]) * g; rArm[2] += (0.1f - rArm[2]) * g;
            lArm[0] += (-1.25f + hp * 0.3f - lArm[0]) * g; lArm[1] += (0.6f - lArm[1]) * g; lArm[2] += (-0.1f - lArm[2]) * g;
            float er = Math.max(0, p.punchRight), el = Math.max(0, p.punchLeft);
            rArm[0] += (-PI / 2 + hp - rArm[0]) * er; rArm[1] += (-0.05f + hy - rArm[1]) * er; rArm[2] *= 1 - er;
            lArm[0] += (-PI / 2 + hp - lArm[0]) * el; lArm[1] += (0.05f + hy - lArm[1]) * el; lArm[2] *= 1 - el;
        }
        if (p.push > 0) {
            // pushing a car: both arms straight forward, palms flat, a little leaning in with every step
            float g = p.push, lean = (float) Math.sin(p.age * 0.5f) * 0.04f;
            rArm[0] += (-1.5f + head[0] * 0.2f + lean - rArm[0]) * g; rArm[1] += (0.1f - rArm[1]) * g; rArm[2] *= 1 - g;
            lArm[0] += (-1.5f + head[0] * 0.2f - lean - lArm[0]) * g; lArm[1] += (-0.1f - lArm[1]) * g; lArm[2] *= 1 - g;
        }
        // the gun on the back: the right arm reaches for it (putting it there / drawing it)
        derpcraft.gun.GunType backGun = p.backGun == null ? null : derpcraft.gun.Guns.of(p.backGun);
        if (backGun != null && backGun.model() == null) backGun = null;
        if (p.backReach > 0) derpcraft.gun.GunHold3P.backReach(backGun != null ? backGun : derpcraft.gun.Guns.of(p.held), p.backReach, rArm);
        if (p.waving) {
            rArm[0] = -2.6f + (float) Math.sin(p.age * 0.35f) * 0.15f;
            rArm[2] = 0.25f + (float) Math.sin(p.age * 0.35f) * 0.35f;
        }
        if (p.sitting) {
            // ModelBiped riding pose
            rArm[0] += -PI / 5; lArm[0] += -PI / 5;
            rLeg[0] = -1.4137167f; rLeg[1] = PI / 10; rLeg[2] = 0.07853982f;
            lLeg[0] = -1.4137167f; lLeg[1] = -PI / 10; lLeg[2] = -0.07853982f;
        }
        float[] sv = p.stanceVis;
        float dropY = 0;
        if (sv != null) {
            // the player: sneaking (Shift) and the deep crouch (Alt + Shift) blend in smoothly. The back leans, the hips move
            // back and down, the knees bend so the feet stay on the ground, and the whole body sinks.
            float sn = sv[derpcraft.game.Stance.P_SNEAK], dp = sv[derpcraft.game.Stance.P_DEEP];
            if (sn > 0.001f) {
                float lean = 0.5f * sn + 0.5f * dp;
                dropY = 0.24f * sn + 0.30f * dp;
                body[0] = lean;
                rArm[0] += 0.4f * (sn + dp); lArm[0] += 0.4f * (sn + dp);
                rLegP[1] = lLegP[1] = 12 * (float) Math.cos(lean) - 1.5f * sn;
                rLegP[2] = lLegP[2] = 9 * (float) Math.sin(lean);
                headP[1] = sn;
                float footY = 24 - 16 * dropY;
                float knee = (float) Math.acos(Math.max(0, Math.min(1, (footY - rLegP[1]) / 12)));
                rLeg[0] -= knee; lLeg[0] -= knee;
            }
        } else if (p.sneaking) {
            body[0] = 0.5f;
            rArm[0] += 0.4f; lArm[0] += 0.4f;
            rLegP[2] = 4; lLegP[2] = 4; rLegP[1] = 9; lLegP[1] = 9;
            headP[1] = 1;
        }
        // idle breathing of the arms
        rArm[2] += (float) Math.cos(p.age * 0.09f) * 0.05f + 0.05f; lArm[2] -= (float) Math.cos(p.age * 0.09f) * 0.05f + 0.05f;
        rArm[0] += (float) Math.sin(p.age * 0.067f) * 0.05f; lArm[0] -= (float) Math.sin(p.age * 0.067f) * 0.05f;

        // the player's posture (sit, lie, crawl): the limbs it holds replace the walk animation (keeping a share of it), the whole
        // body tilts around the neck and the head turns back so it still looks where the player looks
        float tiltVis = 0, liftVis = 0;
        if (sv != null) {
            float keep = sv[derpcraft.game.Stance.P_KEEP];
            float[][] limbs = {null, null, rArm, lArm, rLeg, lLeg};
            for (int i = 2; i < 6; i++) {
                float w = sv[derpcraft.game.Stance.P_HOLD + i];
                if (w < 0.001f) continue;
                for (int k = 0; k < 3; k++) {
                    float r = (float) Math.toRadians(sv[derpcraft.game.Stance.P_ROT + i * 3 + k]);
                    limbs[i][k] = limbs[i][k] * (1 - w) + (r + keep * limbs[i][k]) * w;
                }
            }
            tiltVis = sv[derpcraft.game.Stance.P_TILT];
            liftVis = sv[derpcraft.game.Stance.P_LIFT];
            head[0] += (float) Math.toRadians(tiltVis);
        }

        // poses from the pose library (blended): parts the pose holds replace the animation, the head is added
        float[] torso = {0, 0, 0};
        float tiltX = 0, tiltZ = 0, lift = 0;
        if (p.poseFrom != null || p.poseTo != null) {
            float t = p.poseTo == null && p.poseFrom == null ? 0 : p.poseT;
            float[][] parts = {head, torso, rArm, lArm, rLeg, lLeg};
            for (int i = 0; i < 6; i++) {
                float[] anim = parts[i].clone();
                float[] a = poseAngles(p.poseFrom, i, anim), b = poseAngles(p.poseTo, i, anim);
                for (int k = 0; k < 3; k++) parts[i][k] = a[k] + (b[k] - a[k]) * t;
            }
            float fx = p.poseFrom == null ? 0 : p.poseFrom.tiltX, tx = p.poseTo == null ? 0 : p.poseTo.tiltX;
            float fz = p.poseFrom == null ? 0 : p.poseFrom.tiltZ, tz = p.poseTo == null ? 0 : p.poseTo.tiltZ;
            float fl = p.poseFrom == null ? 0 : p.poseFrom.lift, tl = p.poseTo == null ? 0 : p.poseTo.lift;
            tiltX = fx + (tx - fx) * t; tiltZ = fz + (tz - fz) * t; lift = fl + (tl - fl) * t;
        }

        tiltX += tiltVis; lift += liftVis;
        // a punch turns the upper body into it (the punching shoulder forward), the head turns back by as much
        if (p.fists > 0) {
            float tw = -(Math.max(0, p.punchRight) - Math.max(0, p.punchLeft)) * 0.4f;
            torso[1] += tw;
            head[1] -= tw;
        }
        // a gun in the hands: the upper body turns a little (left shoulder forward), the head turns back by as much
        derpcraft.gun.GunType gun = p.held == null || p.selfie ? null : derpcraft.gun.Guns.of(p.held);
        if (gun != null && gun.model() == null) gun = null;
        if (gun != null) {
            float tw = (float) Math.toRadians(derpcraft.gun.GunHold3P.TWIST);
            torso[1] += tw;
            head[1] -= tw;
        }
        // lying / crawling: the neck (1.5 above the feet) stays over the player's position, the body trails away from it
        float shiftZ = tiltVis == 0 ? 0 : -1.501f * (float) Math.sin(Math.toRadians(tiltVis));

        if (p.hurt > 0 && !shadowPass) mb.color(mb.r, mb.g * 0.55f, mb.b * 0.55f, 1);
        float sc = p.scale <= 0 ? 1 : p.scale;
        Mat4 base = new Mat4().translate((float) rx, (float) ry, (float) rz);
        base.rotate((float) Math.toRadians(180 - p.bodyYaw), 0, 1, 0);
        if (p.vehRoll != 0) base.rotate((float) Math.toRadians(p.vehRoll), 0, 0, 1);       // in a car that rolls over the rider rolls with it
        if (lift != 0 || shiftZ != 0) base.translate(0, lift * sc, shiftZ * sc);
        if (tiltX != 0) base.rotate((float) Math.toRadians(tiltX), 1, 0, 0);
        if (tiltZ != 0) base.rotate((float) Math.toRadians(tiltZ), 0, 0, 1);
        if (sc != 1) base.scale(sc, sc, sc);
        if (p.death > 0) base.rotate((float) Math.toRadians(Math.sqrt(p.death) * 90), 0, 0, 1);
        base.scale(-1, -1, 1);
        base.translate(0, -1.501f, 0);
        if (sv != null) { if (dropY != 0) base.translate(0, dropY, 0); }
        else if (p.sneaking) base.translate(0, 0.2f, 0);
        if (p.sitting) base.translate(0, 10f / 16f, 0);
        // upper body bends at the waist (y = 12 px) for poses that turn the body
        Mat4 upper = base;
        if (torso[0] != 0 || torso[1] != 0 || torso[2] != 0) {
            upper = new Mat4().set(base).translate(0, 12 / 16f, 0);
            if (torso[2] != 0) upper.rotate(torso[2], 0, 0, 1);
            if (torso[1] != 0) upper.rotate(torso[1], 0, 1, 0);
            if (torso[0] != 0) upper.rotate(torso[0], 1, 0, 0);
            upper.translate(0, -12 / 16f, 0);
        }
        float armStretch = 1;
        if (p.selfie) {
            // the right arm points from the shoulder at the hand that holds the selfie camera (arm space: +y runs to the hand)
            float[] t = new float[4];
            upper.inverted().transform((float) (p.selfieX - camX), (float) (p.selfieY - camY), (float) (p.selfieZ - camZ), t);
            float dx = t[0] - rArmP[0] / 16f, dy = t[1] - rArmP[1] / 16f, dz = t[2] - rArmP[2] / 16f;
            float len = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (len > 1e-4f) {
                dx /= len; dy /= len; dz /= len;
                // Ry(b) * Rx(a) * (0,1,0) = (sin a sin b, cos a, sin a cos b), with a <= 0 (arm raised forward, palm down)
                float a = -(float) Math.acos(Math.max(-1, Math.min(1, dy)));
                float b = (float) Math.atan2(-dx, -dz);
                rArm[0] = a; rArm[1] = b; rArm[2] = 0;
                // a far selfie: the arm stretches to reach the camera (the hand ends 10 px from the shoulder)
                armStretch = Math.max(1, Math.min(2.6f, len / (10 / 16f)));
            }
        }
        Mat4 gunM = null;
        float leftStretch = 1;
        if (gun != null) {
            // both hands on the gun, the gun and the arms turned where the head looks
            hold3p.compute(gun, p.pitch, p.headYaw, rArmP, lArmP, p.gunKick, p.gunReload, p.gunReloadEmpty, p.gunMelee, p.gunMeleeTime);
            gunM = new Mat4().set(upper).mul(hold3p.gun);
            System.arraycopy(hold3p.rArm, 0, rArm, 0, 3);
            System.arraycopy(hold3p.lArm, 0, lArm, 0, 3);
            armStretch = hold3p.rStretch;
            leftStretch = hold3p.lStretch;
        }
        Texture2D skin = p.skin != null ? p.skin : assets.steve;
        mb.texture(skin);
        Mat4 headM = part(upper, headP, head, PM_HEAD);
        Mat4 bodyM = part(upper, ZERO3, body, PM_BODY);
        Mat4 arm, larm;
        if (p.slim) {
            rArmP[1] += 0.5f; lArmP[1] += 0.5f;
            arm = armPart(upper, rArmP, rArm, armStretch, PM_RARM_SLIM);
            larm = armPart(upper, lArmP, lArm, leftStretch, PM_LARM_SLIM);
        } else {
            arm = armPart(upper, rArmP, rArm, armStretch, PM_RARM);
            larm = armPart(upper, lArmP, lArm, leftStretch, PM_LARM);
        }
        Mat4 rlegM = part(base, rLegP, rLeg, PM_RLEG);
        Mat4 llegM = part(base, lLegP, lLeg, PM_LLEG);
        if (p.armor != null) armor(p.armor, headM, bodyM, arm, larm, rlegM, llegM, skin);
        if (backGun != null) {
            drawGun(new Mat4().set(bodyM).mul(derpcraft.gun.GunHold3P.back(backGun, backMat)), backGun, null, 0, true);
            mb.texture(skin);
        }
        pouches(p, bodyM, skin);
        if (gunM != null) {
            drawGun(gunM, gun, hold3p.magMoved ? hold3p.mag : null, hold3p.slide, hold3p.magMode == 0);
            if (hold3p.magMode == 2 && gun.mag != null) modelGroups(new Mat4().set(upper).mul(hold3p.magBody), gun.mag.groups);   // the new one in the hand
        }
        else if (p.held != null && !p.selfie) heldThirdPerson(arm, p.held, skin, false);   // selfie: the hand is busy with the camera
        if (p.offhand != null) heldThirdPerson(larm, p.offhand, skin, true);
        if (p.attachments != null && !p.attachments.isEmpty()) {
            Mat4[] parts = {headM, bodyM, arm, larm, rlegM, llegM};
            for (derpcraft.npc.Attachment a : p.attachments) {
                derpcraft.model.Model3D md = derpcraft.model.Models.get(a.model);
                if (md == null || md.empty()) continue;
                model3d(a.matrix(parts[Math.max(0, Math.min(5, a.part))], md), md);
            }
            mb.texture(skin);
        }
    }

    // ------------------------------------------------------------------------------------------ 3D models (game/3d)
    /** Draws a model with the current light colour. */
    public void model3d(Mat4 m, derpcraft.model.Model3D model) {
        if (model == null || model.empty()) return;
        modelGroups(m, model.groups);
    }

    /** Draws triangle groups of a model (a whole model or one part of it) with the current light colour. */
    public void modelGroups(Mat4 m, java.util.List<derpcraft.model.Model3D.Group> groups) {
        float lr = mb.r, lg = mb.g, lb = mb.b;
        for (derpcraft.model.Model3D.Group g : groups) {
            Texture2D t = g.texture();
            if (t != null) mb.texture(t); else mb.untextured();
            float cr = ((g.color >> 16) & 255) / 255f, cg = ((g.color >> 8) & 255) / 255f, cb = (g.color & 255) / 255f, ca = (g.color >>> 24) / 255f;
            float[] v = g.v;
            for (int i = 0; i < g.tris; i++) {
                float sh = g.shade[i];
                mb.color(lr * cr * sh, lg * cg * sh, lb * cb * sh, ca);
                int o = i * 15;
                mb.v(m, v[o], v[o + 1], v[o + 2], v[o + 3], v[o + 4]);
                mb.v(m, v[o + 5], v[o + 6], v[o + 7], v[o + 8], v[o + 9]);
                mb.v(m, v[o + 10], v[o + 11], v[o + 12], v[o + 13], v[o + 14]);
            }
        }
        mb.color(lr, lg, lb, 1);
    }

    // ------------------------------------------------------------------------------------------ guns
    /** The gun body, the magazine (its own model-space matrix: out and rocked on reloads, null = in place) and the bolt (pushed back 'slide'). */
    private void drawGun(Mat4 m, derpcraft.gun.GunType t, Mat4 mag, float slide) { drawGun(m, t, mag, slide, true); }

    /** 'withMag' false: the magazine is not on the gun (let go in a reload, or in the hand: drawn by the caller). */
    private void drawGun(Mat4 m, derpcraft.gun.GunType t, Mat4 mag, float slide, boolean withMag) {
        for (derpcraft.model.Model3D.Bone b : t.body) modelGroups(m, b.groups);
        if (t.mag != null && withMag) modelGroups(mag == null ? m : new Mat4().set(m).mul(mag), t.mag.groups);
        if (t.slide != null) modelGroups(slide == 0 ? m : new Mat4().set(m).translate(t.boltDir[0] * slide, t.boltDir[1] * slide, t.boltDir[2] * slide), t.slide.groups);
    }

    /** Third person gun hold (reused: one model at a time). */
    private final derpcraft.gun.GunHold3P hold3p = new derpcraft.gun.GunHold3P();
    /**
     * A body fallen dead (npc/Ragdoll): each part drawn where the physics put it (the same boxes, skin, armour, items in
     * the hands, the gun on the back and worn 3D models as the standing model).
     */
    private void ragdollModel(PlayerPose p) {
        float ox = (float) (p.x - camX), oy = (float) (p.y - camY), oz = (float) (p.z - camZ);
        if (!shadowPass) lightAt(p.x, p.y + 0.3, p.z);
        if (p.hurt > 0 && !shadowPass) mb.color(mb.r, mb.g * 0.55f, mb.b * 0.55f, 1);
        Mat4[] m = new Mat4[6];
        for (int k = 0; k < 6; k++) {
            m[k] = new Mat4();
            System.arraycopy(p.ragdoll, k * 16, m[k].m, 0, 16);
            m[k].m[12] += ox; m[k].m[13] += oy; m[k].m[14] += oz;
        }
        Texture2D skin = p.skin != null ? p.skin : assets.steve;
        mb.texture(skin);
        part(m[0], ZERO3, ZERO3, PM_HEAD);
        part(m[1], ZERO3, ZERO3, PM_BODY);
        part(m[2], ZERO3, ZERO3, p.slim ? PM_RARM_SLIM : PM_RARM);
        part(m[3], ZERO3, ZERO3, p.slim ? PM_LARM_SLIM : PM_LARM);
        part(m[4], ZERO3, ZERO3, PM_RLEG);
        part(m[5], ZERO3, ZERO3, PM_LLEG);
        if (p.armor != null) armor(p.armor, m[0], m[1], m[2], m[3], m[4], m[5], skin);
        derpcraft.gun.GunType gun = p.held == null ? null : derpcraft.gun.Guns.of(p.held);
        if (gun != null && gun.model() != null) {
            // still in the dead hand: the grip at the palm, the muzzle on along the arm
            float s3 = gun.thirdPersonScale;
            Mat4 g = new Mat4().set(m[2]).translate(-1 / 16f, 10 / 16f, 0).rotate((float) Math.toRadians(90), 1, 0, 0).scale(-s3, -s3, s3)
                    .translate(-gun.rag[0], -gun.rag[1], -gun.rag[2]);
            drawGun(g, gun, null, 0, true);
            mb.texture(skin);
        } else if (p.held != null) heldThirdPerson(m[2], p.held, skin, false);
        if (p.offhand != null) heldThirdPerson(m[3], p.offhand, skin, true);
        derpcraft.gun.GunType backGun = p.backGun == null ? null : derpcraft.gun.Guns.of(p.backGun);
        if (backGun != null && backGun.model() != null) {
            drawGun(new Mat4().set(m[1]).mul(derpcraft.gun.GunHold3P.back(backGun, backMat)), backGun, null, 0, true);
            mb.texture(skin);
        }
        pouches(p, m[1], skin);
        if (p.attachments != null && !p.attachments.isEmpty()) {
            for (derpcraft.npc.Attachment a : p.attachments) {
                derpcraft.model.Model3D md = derpcraft.model.Models.get(a.model);
                if (md == null || md.empty()) continue;
                model3d(a.matrix(m[Math.max(0, Math.min(5, a.part))], md), md);
            }
            mb.texture(skin);
        }
    }

    /** The gun on the back: model space -> body part (reused). */
    private final Mat4 backMat = new Mat4();

    /** Magazines standing in the belt pouches (one model per filled pouch). */
    private void pouches(PlayerPose p, Mat4 bodyM, Texture2D skin) {
        if (p.pouches == null) return;
        boolean any = false;
        for (int k = 0; k < p.pouches.length && k < 6; k++) {
            ItemStack st = p.pouches[k];
            derpcraft.gun.GunType t = st == null ? null : derpcraft.gun.Guns.ofMag(st.item);
            if (t == null || t.model() == null || t.mag == null) continue;
            modelGroups(new Mat4().set(bodyM).mul(derpcraft.gun.GunHold3P.pouch(t, k, backMat)), t.mag.groups);
            any = true;
        }
        if (any) mb.texture(skin);
    }

    /** Field of view of the first person gun (narrower through the sights, like the world's zoom). */
    public float gunFov = 70;

    /** First person gun: the model with its animated parts, the player's arms holding it, casings, the muzzle flash. */
    /** Projection of the last first person drawing (hand, item, gun, fists) and its depth range: ambient occlusion of the hand. */
    public Mat4 handProj;
    public float handNear, handFar;

    private Mat4 handProj(Mat4 p, float near, float far) { handProj = p; handNear = near; handFar = far; return p; }

    public void renderGun(World w, derpcraft.gun.GunAnimator.Pose g, float aspect, double px, double py, double pz, WorldRenderer.FrameParams fp) {
        world = w;
        sun = fp.sun; gamma = fp.gamma; flicker = fp.flicker; colored = fp.coloredLight > 0;
        glClear(GL_DEPTH_BUFFER_BIT);
        glEnable(GL_DEPTH_TEST);
        glDisable(GL_CULL_FACE);
        glEnable(GL_BLEND);
        glBlendFuncSeparate(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA, GL_ONE, GL_ONE_MINUS_SRC_ALPHA);
        Mat4 proj = handProj(new Mat4().perspective((float) Math.toRadians(gunFov - 14 * g.aim), aspect, 0.02f, 10f), 0.02f, 10f);
        mb.begin(proj, assets.blocks.texId, 0, 0, 0, 0, 0);
        lightAt(px, py, pz);
        derpcraft.gun.GunType t = g.type;
        drawGun(g.gun, t, g.mag, g.slide, g.magMode == 0);
        if (g.magMode == 2 && t.mag != null) modelGroups(g.magView, t.mag.groups);        // the new magazine in the hand
        if (g.dropMagShown && t.mag != null) modelGroups(g.dropMag, t.mag.groups);        // the old one falling out of the picture
        firstPersonArms(g.rightArm, g.leftArm);
        casings(g);
        if (g.marker != null) {               // gun editor: the point being tuned, a small cube that shows through the gun
            mb.flush();
            glDisable(GL_DEPTH_TEST);
            mb.untextured();
            float br = mb.r, bg = mb.g, bb = mb.b;
            if (g.marker2 != null) {          // the other end of a pair (the bolt's limits): cyan, and a thin line between them
                mb.color(0.2f, 0.9f, 1f, 0.9f);
                float[] q = g.marker2;
                mb.box(new Mat4().set(g.gun).translate(q[0], q[1], q[2]), -0.5f, -0.5f, -0.5f, 1, 1, 1, 0, 0, 64, 64, 1, 0.8f);
                float[] a = g.marker;
                float dx = q[0] - a[0], dy = q[1] - a[1], dz = q[2] - a[2], len = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
                for (int i = 1; i < 12 && len > 1e-4f; i++) {
                    float f = i / 12f;
                    mb.box(new Mat4().set(g.gun).translate(a[0] + dx * f, a[1] + dy * f, a[2] + dz * f).scale(0.3f, 0.3f, 0.3f), -0.5f, -0.5f, -0.5f, 1, 1, 1, 0, 0, 64, 64, 1, 0.8f);
                }
            }
            mb.color(1, 0.9f, 0.1f, 0.9f);
            float[] pm = g.marker;
            Mat4 mm = new Mat4().set(g.gun).translate(pm[0], pm[1], pm[2]);
            mb.box(mm, -0.5f, -0.5f, -0.5f, 1, 1, 1, 0, 0, 64, 64, 1, 0.8f);
            mb.flush();
            mb.color(br, bg, bb, 1);
            glEnable(GL_DEPTH_TEST);
        }
        if (g.flash > 0) muzzleFlash(g);
        mb.flush();
        glDisable(GL_BLEND);
        glEnable(GL_CULL_FACE);
    }

    /** The player's own arms in the first person (skin, sleeves): arm box matrices like GunAnimator.Pose's. */
    private void firstPersonArms(Mat4 rightArm, Mat4 leftArm) {
        Texture2D skin = playerSkin != null ? playerSkin : assets.steve;
        mb.texture(skin);
        boolean wide = skin.height >= 64;                    // 64x64 skins: own left arm and the sleeve layers
        int aw = playerSlim ? 3 : 4;
        // right arm: uv (40, 16), sleeve (40, 32); left arm: uv (32, 48), sleeve (48, 48) (64x32 skins: the right one mirrored)
        int th = skin.height >= 64 ? 64 : 32;
        float x0 = -aw / 2f;
        mb.box(rightArm, x0, 0, -2, aw, 12, 4, 40, 16, 64, th, 1, 0.8f);
        mb.box(leftArm, x0, 0, -2, aw, 12, 4, wide ? 32 : 40, wide ? 48 : 16, 64, th, 1, 0.8f);
        if (wide) {      // the sleeve layer, a little bigger around the arm
            mb.box(new Mat4().set(rightArm).translate(0, 6 / 16f, 0).scale(1.12f, 1.04f, 1.12f).translate(0, -6 / 16f, 0), x0, 0, -2, aw, 12, 4, 40, 32, 64, th, 1, 0.8f);
            mb.box(new Mat4().set(leftArm).translate(0, 6 / 16f, 0).scale(1.12f, 1.04f, 1.12f).translate(0, -6 / 16f, 0), x0, 0, -2, aw, 12, 4, 48, 48, 64, th, 1, 0.8f);
        }
    }

    /** First person fist fighting (Alt + Q): the player's two arms in the guard or punching, drawn like the gun arms. */
    public void renderFists(World w, derpcraft.gun.FistAnimator.Pose g, float aspect, double px, double py, double pz, WorldRenderer.FrameParams fp) {
        world = w;
        sun = fp.sun; gamma = fp.gamma; flicker = fp.flicker; colored = fp.coloredLight > 0;
        glClear(GL_DEPTH_BUFFER_BIT);
        glEnable(GL_DEPTH_TEST);
        glDisable(GL_CULL_FACE);
        glEnable(GL_BLEND);
        glBlendFuncSeparate(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA, GL_ONE, GL_ONE_MINUS_SRC_ALPHA);
        Mat4 proj = handProj(new Mat4().perspective((float) Math.toRadians(gunFov), aspect, 0.02f, 10f), 0.02f, 10f);
        mb.begin(proj, assets.blocks.texId, 0, 0, 0, 0, 0);
        lightAt(px, py, pz);
        firstPersonArms(g.rightArm, g.leftArm);
        mb.flush();
        glDisable(GL_BLEND);
        glEnable(GL_CULL_FACE);
    }

    /** The player's skin and arm width for the first person gun arms (null = Steve). */
    public Texture2D playerSkin;
    public boolean playerSlim;

    /** Spent casings: small brass boxes tumbling away from the ejection port. */
    private void casings(derpcraft.gun.GunAnimator.Pose g) {
        if (g.casings.isEmpty()) return;
        mb.untextured();
        float br = mb.r, bg = mb.g, bb = mb.b;
        float s = g.type.firstPersonScale;
        for (derpcraft.gun.GunAnimator.Casing c : g.casings) {
            Mat4 m = new Mat4().translate(c.x, c.y, c.z);
            m.rotate((float) Math.toRadians(c.spin), 0.3f, 1, 0.5f);
            m.scale(s, s, s);
            mb.color(br * 0.85f, bg * 0.62f, bb * 0.25f, 1);
            // 0.6 x 0.6 x 2 px casing (pixel units / 16 inside box())
            mb.box(m, -0.3f, -1f, -0.3f, 1, 2, 1, 0, 0, 64, 64, 1, 0.75f);
        }
        mb.color(br, bg, bb, 1);
    }

    private final Random flashRnd = new Random();

    /** Muzzle flash: a hot core and a star of flame tongues facing the camera, one frame per shot. */
    private void muzzleFlash(derpcraft.gun.GunAnimator.Pose g) {
        float cx = g.muzzle[0], cy = g.muzzle[1], cz = g.muzzle[2];
        float sc = (-cz * 0.12f + 0.03f) * g.type.flashSize;     // roughly the same size on screen near and far
        if (sc <= 0) return;
        mb.untextured();
        float br = mb.r, bg = mb.g, bb = mb.b;
        for (int i = 0; i < 4; i++) {
            double a = Math.toRadians(g.flashTurn + i * 45 + flashRnd.nextFloat() * 10);
            float len = sc * (i % 2 == 0 ? 1.4f : 0.8f) * (0.8f + flashRnd.nextFloat() * 0.5f), wid = sc * 0.18f;
            float ux = (float) Math.cos(a), uy = (float) Math.sin(a), vx = -uy, vy = ux;
            mb.color(1f, 0.72f, 0.3f, 0.75f);
            mb.v(cx - ux * len - vx * wid, cy - uy * len - vy * wid, cz, 0, 0);
            mb.v(cx + ux * len - vx * wid, cy + uy * len - vy * wid, cz, 0, 0);
            mb.v(cx + ux * len + vx * wid, cy + uy * len + vy * wid, cz, 0, 0);
            mb.v(cx + ux * len + vx * wid, cy + uy * len + vy * wid, cz, 0, 0);
            mb.v(cx - ux * len + vx * wid, cy - uy * len + vy * wid, cz, 0, 0);
            mb.v(cx - ux * len - vx * wid, cy - uy * len - vy * wid, cz, 0, 0);
        }
        float c = sc * 0.45f;
        mb.color(1f, 0.95f, 0.75f, 0.95f);
        mb.v(cx - c, cy - c, cz + 0.001f, 0, 0); mb.v(cx + c, cy - c, cz + 0.001f, 0, 0); mb.v(cx + c, cy + c, cz + 0.001f, 0, 0);
        mb.v(cx + c, cy + c, cz + 0.001f, 0, 0); mb.v(cx - c, cy + c, cz + 0.001f, 0, 0); mb.v(cx - c, cy - c, cz + 0.001f, 0, 0);
        mb.color(br, bg, bb, 1);
    }

    /** Shot: a puff of smoke and a few sparks at the muzzle (world). */
    public void gunMuzzle(double x, double y, double z, double dx, double dy, double dz) {
        if (assets.tables == null) return;
        int layer = assets.blocks.layer("wool_colored_white");
        for (int i = 0; i < 2; i++) {
            Particle p = new Particle();
            p.x = p.px = x + dx * 0.1; p.y = p.py = y + dy * 0.1; p.z = p.pz = z + dz * 0.1;
            p.mx = dx * 0.05 + jit(0.01); p.my = dy * 0.05 + 0.01; p.mz = dz * 0.05 + jit(0.01);
            setup(p, layer, 0xFFFFFF);
            p.gravity = -0.002f; p.size = 0.06f + rnd.nextFloat() * 0.05f; p.maxAge = 10 + rnd.nextInt(10);
            float c = 0.55f + rnd.nextFloat() * 0.2f; p.r = c; p.g = c; p.b = c;
            p.fade = true; p.grow = 2.5f; p.drag = 0.9f;
            sprite(p, 7, true, false);
        }
        Particle f = new Particle();
        f.x = f.px = x; f.y = f.py = y; f.z = f.pz = z;
        setup(f, layer, 0xFFFFFF);
        f.gravity = 0; f.size = 0.12f; f.maxAge = 2; f.r = f.g = f.b = 1;
        sprite(f, 48, false, true);
    }

    // ------------------------------------------------------------------------------------------ dropped magazines
    /** An empty magazine let go in a reload: falls, tumbles, bounces, lies on the ground for a minute. */
    private static final class DroppedMag {
        derpcraft.gun.GunType type;
        double x, y, z, px, py, pz, vx, vy, vz;
        float yaw, pitch, roll, ppitch, proll, spinP, spinR;
        long born;
        boolean resting, landed;
    }

    private final List<DroppedMag> droppedMags = new ArrayList<>();
    private long magTickDone = -1;

    /** A magazine starts falling at (x, y, z) (its middle), turned by 'yaw' (degrees, like the gun), with the thrower's speed (blocks / tick). */
    public void dropMagazine(derpcraft.gun.GunType t, double x, double y, double z, float yaw, double vx, double vy, double vz) {
        if (t == null || t.mag == null) return;
        DroppedMag d = new DroppedMag();
        d.type = t;
        d.x = d.px = x; d.y = d.py = y; d.z = d.pz = z;
        d.vx = vx + (rnd.nextDouble() - 0.5) * 0.03; d.vy = vy - 0.02; d.vz = vz + (rnd.nextDouble() - 0.5) * 0.03;
        d.yaw = yaw + (rnd.nextFloat() - 0.5f) * 30;
        d.pitch = d.ppitch = 25;
        d.spinP = 8 + rnd.nextFloat() * 10; d.spinR = (rnd.nextFloat() - 0.5f) * 14;
        d.born = world == null ? 0 : world.gameTime;
        if (droppedMags.size() >= 48) droppedMags.remove(0);
        droppedMags.add(d);
    }

    /** Moves the dropped magazines once per game tick (gravity, a bounce, settling on their side) and draws them. */
    private void droppedMags(float partial) {
        if (droppedMags.isEmpty() || world == null) return;
        World w = world;
        if (w.gameTime != magTickDone && !viewPass) {
            magTickDone = w.gameTime;
            for (int i = droppedMags.size() - 1; i >= 0; i--) {
                DroppedMag d = droppedMags.get(i);
                if (w.gameTime - d.born > 1200 || d.type.model == null) { droppedMags.remove(i); continue; }
                d.px = d.x; d.py = d.y; d.pz = d.z; d.ppitch = d.pitch; d.proll = d.roll;
                if (d.resting) {
                    // settle flat on its side (the thin way up), along the way it tumbled
                    float flat = Math.round(d.pitch / 180f) * 180f;
                    d.pitch += (flat - d.pitch) * 0.5f; d.roll += (90 - d.roll) * 0.5f;
                    if (!w.getBlock((int) Math.floor(d.x), (int) Math.floor(d.y - 0.05), (int) Math.floor(d.z)).solid) d.resting = false;
                    continue;
                }
                d.vy -= 0.04; d.vx *= 0.98; d.vz *= 0.98;
                double nx = d.x + d.vx, ny = d.y + d.vy, nz = d.z + d.vz;
                float half = Math.max(0.01f, halfThickness(d.type));
                if (w.getBlock((int) Math.floor(nx), (int) Math.floor(d.y), (int) Math.floor(nz)).solid) { d.vx *= -0.3; d.vz *= -0.3; nx = d.x; nz = d.z; }
                int by = (int) Math.floor(ny - half);
                if (d.vy < 0 && w.getBlock((int) Math.floor(nx), by, (int) Math.floor(nz)).solid) {
                    // hits the ground: a small bounce, less spin; slow enough = lies down
                    ny = by + 1 + half;
                    if (!d.landed) { d.landed = true; magDropSound(d, -d.vy); }
                    d.vy = -d.vy * 0.3; d.vx *= 0.55; d.vz *= 0.55; d.spinP *= 0.4f; d.spinR *= 0.4f;
                    if (Math.abs(d.vy) < 0.06) { d.vy = 0; d.vx = d.vz = 0; d.resting = true; }
                }
                d.x = nx; d.y = ny; d.z = nz;
                d.pitch += d.spinP; d.roll += d.spinR;
            }
        }
        for (DroppedMag d : droppedMags) {
            if (d.type.model == null || d.type.mag == null) continue;
            double x = d.px + (d.x - d.px) * partial, y = d.py + (d.y - d.py) * partial, z = d.pz + (d.z - d.pz) * partial;
            float pitch = d.ppitch + (d.pitch - d.ppitch) * partial, roll = d.proll + (d.roll - d.proll) * partial;
            float[] c = derpcraft.gun.GunType.bounds(d.type.mag);
            float s = d.type.thirdPersonScale;
            lightAt(x, y, z);
            Mat4 m = new Mat4().translate((float) (x - camX), (float) (y - camY), (float) (z - camZ));
            m.rotate((float) Math.toRadians(180 - d.yaw), 0, 1, 0);
            m.rotate((float) Math.toRadians(pitch), 1, 0, 0);
            m.rotate((float) Math.toRadians(roll), 0, 0, 1);
            m.scale(s, s, s);
            m.translate(-(c[0] + c[3]) / 2, -(c[1] + c[4]) / 2, -(c[2] + c[5]) / 2);
            modelGroups(m, d.type.mag.groups);
        }
    }

    /** The magazine hits the ground the first time: a random game/guns/sfx/drop1-3 (.ogg / .wav), louder the harder it lands. */
    private void magDropSound(DroppedMag d, double speed) {
        derpcraft.game.Session ses = derpcraft.game.Session.current;
        java.nio.file.Path dir = derpcraft.gun.Guns.dir();
        if (ses == null || dir == null) return;
        List<java.nio.file.Path> l = new ArrayList<>();
        for (int i = 1; i <= 3; i++)
            for (String ext : new String[]{".ogg", ".wav"}) {
                java.nio.file.Path f = dir.resolve("sfx").resolve("drop" + i + ext);
                if (java.nio.file.Files.isRegularFile(f)) { l.add(f); break; }
            }
        if (l.isEmpty()) return;
        float vol = (float) Math.min(1, 0.45 + speed * 2.5) * d.type.volume;
        derpcraft.gun.Ballistics.sound(ses, l.get(rnd.nextInt(l.size())), d.x, d.y, d.z, vol, 0.95f + rnd.nextFloat() * 0.1f);
    }

    /** Half the magazine's thickness when it lies on its side (its width, blocks). */
    private static float halfThickness(derpcraft.gun.GunType t) {
        float[] c = derpcraft.gun.GunType.bounds(t.mag);
        return (c[3] - c[0]) * t.thirdPersonScale / 2;
    }

    // ------------------------------------------------------------------------------------------ bullet holes
    /** A bullet hole on a block face: where, which face, the block it is on (gone when that block changes), when, how big. */
    private static final class Hole {
        int bx, by, bz, state, face;
        double x, y, z;
        long born;
        float size, turn;
    }

    private final List<Hole> holes = new ArrayList<>();
    /** Most holes at once (the oldest go first) and how long they last (ticks: 2 minutes, the last 20 s fading). */
    static final int MAX_HOLES = 600, HOLE_LIFE = 2400, HOLE_FADE = 400;

    /** A bullet hit a block: a hole on that face at the hit point. */
    public void bulletHole(World w, Raycast.Hit h) {
        if (h == null || h.block == null) return;
        Hole o = new Hole();
        o.bx = h.x; o.by = h.y; o.bz = h.z; o.face = h.face;
        o.state = w.getState(h.x, h.y, h.z);
        o.x = h.hx + Dir.DX[h.face] * 0.003; o.y = h.hy + Dir.DY[h.face] * 0.003; o.z = h.hz + Dir.DZ[h.face] * 0.003;
        o.born = w.gameTime;
        o.size = 0.035f + rnd.nextFloat() * 0.015f;
        o.turn = rnd.nextFloat() * (float) Math.PI * 2;
        if (holes.size() >= MAX_HOLES) holes.remove(0);
        holes.add(o);
    }

    /** Bullet holes: a dark round mark with a soft edge on the face (darkens what is under it), fading at the end. */
    private void bulletHoles(derpcraft.util.Frustum frustum) {
        if (holes.isEmpty() || world == null) return;
        long now = world.gameTime;
        mb.flush();
        glDepthMask(false);
        glEnable(GL_POLYGON_OFFSET_FILL);
        glPolygonOffset(-2, -2);
        mb.untextured();
        float sky = mb.sky;
        mb.sky = 0;
        for (int i = holes.size() - 1; i >= 0; i--) {
            Hole o = holes.get(i);
            long age = now - o.born;
            if (age > HOLE_LIFE || world.getState(o.bx, o.by, o.bz) != o.state) { holes.remove(i); continue; }
            float x = (float) (o.x - camX), y = (float) (o.y - camY), z = (float) (o.z - camZ);
            if (x * x + y * y + z * z > 64 * 64) continue;
            if (frustum != null && !frustum.testAabb(x - 0.1f, y - 0.1f, z - 0.1f, x + 0.1f, y + 0.1f, z + 0.1f)) continue;
            float a = age > HOLE_LIFE - HOLE_FADE ? (HOLE_LIFE - age) / (float) HOLE_FADE : 1;
            // two axes across the face
            float ux, uy, uz, vx, vy, vz;
            if (o.face < 2) { ux = 1; uy = 0; uz = 0; vx = 0; vy = 0; vz = 1; }
            else if (o.face < 4) { ux = 1; uy = 0; uz = 0; vx = 0; vy = 1; vz = 0; }
            else { ux = 0; uy = 0; uz = 1; vx = 0; vy = 1; vz = 0; }
            float r = o.size, outer = r * 1.9f;
            int n = 10;
            for (int k = 0; k < n; k++) {
                double a0 = o.turn + k * Math.PI * 2 / n, a1 = o.turn + (k + 1) * Math.PI * 2 / n;
                // a little ragged: every other corner of the dark part is bigger
                float r0 = r * (k % 2 == 0 ? 1.15f : 0.9f), r1 = r * ((k + 1) % 2 == 0 ? 1.15f : 0.9f);
                float c0 = (float) Math.cos(a0), s0 = (float) Math.sin(a0), c1 = (float) Math.cos(a1), s1 = (float) Math.sin(a1);
                float ix0 = x + (ux * c0 + vx * s0) * r0, iy0 = y + (uy * c0 + vy * s0) * r0, iz0 = z + (uz * c0 + vz * s0) * r0;
                float ix1 = x + (ux * c1 + vx * s1) * r1, iy1 = y + (uy * c1 + vy * s1) * r1, iz1 = z + (uz * c1 + vz * s1) * r1;
                float ox0 = x + (ux * c0 + vx * s0) * outer, oy0 = y + (uy * c0 + vy * s0) * outer, oz0 = z + (uz * c0 + vz * s0) * outer;
                float ox1 = x + (ux * c1 + vx * s1) * outer, oy1 = y + (uy * c1 + vy * s1) * outer, oz1 = z + (uz * c1 + vz * s1) * outer;
                // the hole: black in the middle, dark at its rim
                mb.color(0.03f, 0.03f, 0.03f, 0.95f * a); mb.v(x, y, z, 0, 0);
                mb.color(0.10f, 0.09f, 0.08f, 0.85f * a); mb.v(ix0, iy0, iz0, 0, 0); mb.v(ix1, iy1, iz1, 0, 0);
                // the scorched ring around it fades out
                mb.color(0.10f, 0.09f, 0.08f, 0.85f * a); mb.v(ix0, iy0, iz0, 0, 0); mb.v(ix1, iy1, iz1, 0, 0);
                mb.color(0.15f, 0.13f, 0.11f, 0); mb.v(ox1, oy1, oz1, 0, 0);
                mb.color(0.10f, 0.09f, 0.08f, 0.85f * a); mb.v(ix0, iy0, iz0, 0, 0);
                mb.color(0.15f, 0.13f, 0.11f, 0); mb.v(ox1, oy1, oz1, 0, 0); mb.v(ox0, oy0, oz0, 0, 0);
            }
        }
        mb.flush();
        mb.sky = sky;
        mb.color(1, 1, 1, 1);
        glDisable(GL_POLYGON_OFFSET_FILL);
        glDepthMask(true);
    }

    // ------------------------------------------------------------------------------------------ tracers
    /** A glowing bullet: flies from the muzzle to where it hit, a streak 'length' long behind its head. */
    public static final class Tracer {
        double sx, sy, sz;            // start (muzzle)
        float dx, dy, dz, dist;       // direction (unit) and how far it flies
        float speed, length, width, r, g, b, glow;
        long born;                    // game tick
    }

    private final List<Tracer> tracers = new ArrayList<>();

    /** A tracer from the muzzle to the hit point: speed (blocks / second), length and width (blocks), colour, brightness (> 1 blooms). */
    public void tracer(double sx, double sy, double sz, double ex, double ey, double ez, float speed, float length, float width,
                       float r, float g, float b, float glow) {
        double dx = ex - sx, dy = ey - sy, dz = ez - sz, d = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (d < 0.5) return;
        Tracer t = new Tracer();
        t.sx = sx; t.sy = sy; t.sz = sz;
        t.dx = (float) (dx / d); t.dy = (float) (dy / d); t.dz = (float) (dz / d); t.dist = (float) d;
        t.speed = Math.max(1, speed); t.length = length; t.width = width; t.r = r; t.g = g; t.b = b; t.glow = glow;
        t.born = world == null ? 0 : world.gameTime;
        if (tracers.size() > 200) tracers.remove(0);
        tracers.add(t);
    }

    /** Head and tail of a tracer along its path (blocks from the start), or null when it is gone. */
    private static float[] tracerSpan(Tracer t, long now, float partial) {
        float age = Math.max(0, (now - t.born + partial) / 20f);
        float head = Math.min(t.dist, age * t.speed + 0.6f), tail = Math.max(0, age * t.speed + 0.6f - t.length);
        if (tail >= t.dist) return null;
        return new float[]{head, tail};
    }

    /** Muzzle flashes of NPC guns (world position, time): a moment of light around them. */
    private final List<double[]> muzzleLights = new ArrayList<>();

    public void muzzleLight(double x, double y, double z) {
        if (muzzleLights.size() > 16) muzzleLights.remove(0);
        muzzleLights.add(new double[]{x, y, z, System.nanoTime()});
    }

    /** Glowing heads of the newest tracers light up their surroundings (dynamic lights, like the muzzle flash). */
    public void tracerLights(WorldRenderer.FrameParams fp, long now, float partial) {
        long nowNs = System.nanoTime();
        for (int i = muzzleLights.size() - 1; i >= 0; i--) {
            double[] f = muzzleLights.get(i);
            float age = (float) ((nowNs - f[3]) / 1e9);
            if (age > 0.06f) { muzzleLights.remove(i); continue; }
            int k = Math.min(fp.dynCount, WorldRenderer.FrameParams.MAX_DYN - 1);
            fp.dynPos[k * 4] = (float) f[0]; fp.dynPos[k * 4 + 1] = (float) f[1]; fp.dynPos[k * 4 + 2] = (float) f[2]; fp.dynPos[k * 4 + 3] = 13 * (1 - age / 0.06f);
            fp.dynCol[k * 3] = 1f; fp.dynCol[k * 3 + 1] = 0.7f; fp.dynCol[k * 3 + 2] = 0.35f;
            fp.dynCount = k + 1;
        }
        int added = 0;
        for (int i = tracers.size() - 1; i >= 0 && added < 3; i--) {
            Tracer t = tracers.get(i);
            float[] sp = tracerSpan(t, now, partial);
            if (sp == null || sp[0] >= t.dist) continue;       // only while it flies
            int k = Math.min(fp.dynCount, WorldRenderer.FrameParams.MAX_DYN - 1);
            fp.dynPos[k * 4] = (float) (t.sx + t.dx * sp[0]); fp.dynPos[k * 4 + 1] = (float) (t.sy + t.dy * sp[0]); fp.dynPos[k * 4 + 2] = (float) (t.sz + t.dz * sp[0]);
            fp.dynPos[k * 4 + 3] = Math.min(10, 3 + t.glow);
            fp.dynCol[k * 3] = t.r; fp.dynCol[k * 3 + 1] = t.g; fp.dynCol[k * 3 + 2] = t.b;
            fp.dynCount = k + 1;
            added++;
        }
    }

    /** Tracers: a soft wide glow and a hot thin core, facing the camera, fading towards the tail; added light (blooms in HDR). */
    private void tracers(float partial) {
        if (tracers.isEmpty() || world == null) return;
        long now = world.gameTime;
        mb.flush();
        glBlendFuncSeparate(GL_SRC_ALPHA, GL_ONE, GL_ZERO, GL_ONE);
        glDepthMask(false);
        mb.untextured();
        float sky = mb.sky;
        mb.sky = 0;
        for (int i = tracers.size() - 1; i >= 0; i--) {
            Tracer t = tracers.get(i);
            float[] sp = tracerSpan(t, now, partial);
            if (sp == null) { if (now - t.born > 2) tracers.remove(i); continue; }
            float hx = (float) (t.sx + t.dx * sp[0] - camX), hy = (float) (t.sy + t.dy * sp[0] - camY), hz = (float) (t.sz + t.dz * sp[0] - camZ);
            float tx = (float) (t.sx + t.dx * sp[1] - camX), ty = (float) (t.sy + t.dy * sp[1] - camY), tz = (float) (t.sz + t.dz * sp[1] - camZ);
            // across the streak and facing the camera: direction x (camera to the middle)
            float mx = (hx + tx) / 2, my = (hy + ty) / 2, mz = (hz + tz) / 2;
            float ax = t.dy * mz - t.dz * my, ay = t.dz * mx - t.dx * mz, az = t.dx * my - t.dy * mx;
            float al = (float) Math.sqrt(ax * ax + ay * ay + az * az);
            if (al < 1e-5f) continue;
            ax /= al; ay /= al; az /= al;
            // far tracers stay at least about a pixel wide
            float dist = (float) Math.sqrt(mx * mx + my * my + mz * mz), minW = dist * 0.0015f;
            streak(hx, hy, hz, tx, ty, tz, ax, ay, az, Math.max(t.width * 3.5f, minW * 3), t.r * t.glow * 0.5f, t.g * t.glow * 0.5f, t.b * t.glow * 0.5f, 0.35f);
            streak(hx, hy, hz, tx, ty, tz, ax, ay, az, Math.max(t.width, minW), Math.min(1, t.r + 0.25f) * t.glow, Math.min(1, t.g + 0.25f) * t.glow, Math.min(1, t.b + 0.25f) * t.glow, 1f);
        }
        mb.flush();
        mb.sky = sky;
        mb.color(1, 1, 1, 1);
        glDepthMask(true);
        glBlendFuncSeparate(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA, GL_ONE, GL_ONE_MINUS_SRC_ALPHA);
    }

    /** One ribbon from head h to tail t, half width w across (a), full colour at the head, nothing at the tail; a rounded head. */
    private void streak(float hx, float hy, float hz, float tx, float ty, float tz, float ax, float ay, float az, float w,
                        float r, float g, float b, float alpha) {
        float h = w / 2;
        mb.color(r, g, b, alpha);
        float h1x = hx + ax * h, h1y = hy + ay * h, h1z = hz + az * h, h2x = hx - ax * h, h2y = hy - ay * h, h2z = hz - az * h;
        mb.v(h1x, h1y, h1z, 0, 0); mb.v(h2x, h2y, h2z, 0, 0);
        mb.color(r, g, b, 0);
        mb.v(tx - ax * h * 0.3f, ty - ay * h * 0.3f, tz - az * h * 0.3f, 0, 0);
        mb.v(tx - ax * h * 0.3f, ty - ay * h * 0.3f, tz - az * h * 0.3f, 0, 0);
        mb.v(tx + ax * h * 0.3f, ty + ay * h * 0.3f, tz + az * h * 0.3f, 0, 0);
        mb.color(r, g, b, alpha);
        mb.v(h1x, h1y, h1z, 0, 0);
    }

    /** A bullet hits a block: chips of its texture, a spark and a little dust. */
    public void gunImpact(World w, Raycast.Hit h) {
        if (assets.tables == null) return;
        for (int i = 0; i < 6; i++) spawnDigParticle(w, h);
        double nx = Dir.DX[h.face], ny = Dir.DY[h.face], nz = Dir.DZ[h.face];
        int layer = assets.blocks.layer("wool_colored_white");
        for (int i = 0; i < 3; i++) {
            Particle p = new Particle();
            p.x = p.px = h.hx + nx * 0.02; p.y = p.py = h.hy + ny * 0.02; p.z = p.pz = h.hz + nz * 0.02;
            p.mx = nx * 0.12 + jit(0.12); p.my = ny * 0.12 + jit(0.12) + 0.05; p.mz = nz * 0.12 + jit(0.12);
            setup(p, layer, 0xFFFFFF);
            p.gravity = 0.03f; p.size = 0.02f; p.maxAge = 4 + rnd.nextInt(5); p.r = 1; p.g = 0.8f; p.b = 0.4f;
            sprite(p, 49, false, true);
        }
        Particle d = new Particle();
        d.x = d.px = h.hx + nx * 0.05; d.y = d.py = h.hy + ny * 0.05; d.z = d.pz = h.hz + nz * 0.05;
        d.mx = nx * 0.02; d.my = 0.01; d.mz = nz * 0.02;
        setup(d, layer, 0xFFFFFF);
        d.gravity = -0.001f; d.size = 0.1f; d.maxAge = 14 + rnd.nextInt(8); d.r = d.g = d.b = 0.6f; d.fade = true; d.grow = 1.8f;
        sprite(d, 7, true, false);
    }

    // ------------------------------------------------------------------------------------------ blood
    /** A splat of blood on a face of a block (gone when the block changes; fades after BLOOD_LIFE ticks); 'dark' also picks its texture. */
    private static final class Splat {
        int bx, by, bz, state, face;
        double x, y, z;
        long born, lit = Long.MIN_VALUE;
        float size, turn, lr, lg, lb, lsky, dark;
    }

    private final List<Splat> splats = new ArrayList<>();
    static final int MAX_SPLATS = 900, BLOOD_LIFE = 6000, BLOOD_FADE = 900;

    /**
     * Blood from a point: drops thrown along (dx, dy, dz) (a shot: out of the wound; 0 = around) that fall and leave
     * splats where they land, and a red mist for hard hits. 'damage' sets how much: a punch a few drops, a rifle round
     * a spray, a long fall a pool.
     */
    public void blood(World w, double x, double y, double z, double dx, double dy, double dz, float damage) {
        if (assets.tables == null || damage <= 0) return;
        int layer = assets.blocks.layer("wool_colored_white");
        double dl = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (dl > 1e-6) { dx /= dl; dy /= dl; dz /= dl; }
        int n = Math.max(3, Math.min(70, Math.round(damage * 3)));
        double speed = 0.06 + Math.min(0.25, damage * 0.012);
        for (int i = 0; i < n; i++) {
            Particle p = new Particle();
            p.x = p.px = x + jit(0.15); p.y = p.py = y + jit(0.15); p.z = p.pz = z + jit(0.15);
            double sp = speed * (0.3 + rnd.nextDouble());
            p.mx = dx * sp + jit(0.12); p.my = dy * sp + jit(0.08) + 0.06; p.mz = dz * sp + jit(0.12);
            setup(p, layer, 0xFFFFFF);
            p.gravity = 0.035f; p.size = 0.025f + rnd.nextFloat() * 0.04f; p.maxAge = 60 + rnd.nextInt(30);
            float c = 0.62f + rnd.nextFloat() * 0.2f;           // a solid red pixel, lit like the other particles
            p.r = c; p.g = c * 0.06f; p.b = c * 0.05f;
            p.blood = true;
        }
        int mist = Math.min(8, Math.round(damage / 3));
        for (int i = 0; i < mist; i++) {
            Particle p = new Particle();
            p.x = p.px = x; p.y = p.py = y; p.z = p.pz = z;
            p.mx = dx * 0.05 + jit(0.06); p.my = jit(0.05) + 0.02; p.mz = dz * 0.05 + jit(0.06);
            setup(p, layer, 0xFFFFFF);
            p.gravity = 0.004f; p.size = 0.06f + rnd.nextFloat() * 0.06f; p.maxAge = 10 + rnd.nextInt(10);
            p.r = 0.5f + rnd.nextFloat() * 0.15f; p.g = 0.02f; p.b = 0.02f; p.fade = true; p.grow = 1.6f;
            sprite(p, 7, true, false);
        }
    }

    /**
     * One drop of blood for bleeding (Left Alt + B): it falls with the given speed and leaves a splat where it lands;
     * a big one (size 0.06 and more) lands as a smear / pool that grows if more land on it.
     */
    public void bleedDrop(double x, double y, double z, double mx, double my, double mz, float size) {
        if (assets.tables == null) return;
        int layer = assets.blocks.layer("wool_colored_white");
        Particle p = new Particle();
        p.x = p.px = x; p.y = p.py = y; p.z = p.pz = z;
        p.mx = mx; p.my = my; p.mz = mz;
        setup(p, layer, 0xFFFFFF);
        p.gravity = 0.035f; p.size = size; p.maxAge = 100 + rnd.nextInt(40);
        float c = 0.62f + rnd.nextFloat() * 0.2f;
        p.r = c; p.g = c * 0.06f; p.b = c * 0.05f;
        p.blood = true;
    }

    /** A drop of blood hit a block between its place and (nx, ny, nz): a splat on the face it hit. */
    private void landBlood(World w, Particle p, double nx, double ny, double nz) {
        int bx = (int) Math.floor(nx), by = (int) Math.floor(ny), bz = (int) Math.floor(nz);
        int face;
        double x = nx, y = ny, z = nz;
        if ((int) Math.floor(p.y) != by) { face = p.my < 0 ? Dir.UP : Dir.DOWN; y = p.my < 0 ? by + 1 : by; x = p.x; z = p.z; }
        else if ((int) Math.floor(p.x) != bx) { face = p.mx < 0 ? Dir.EAST : Dir.WEST; x = p.mx < 0 ? bx + 1 : bx; z = p.z; y = p.y; }
        else { face = p.mz < 0 ? Dir.SOUTH : Dir.NORTH; z = p.mz < 0 ? bz + 1 : bz; x = p.x; y = p.y; }
        // drops landing near an older splat on the same face make it bigger instead (a pool grows)
        for (int i = splats.size() - 1; i >= Math.max(0, splats.size() - 40); i--) {
            Splat o = splats.get(i);
            if (o.face != face || o.bx != bx || o.by != by || o.bz != bz) continue;
            double ddx = o.x - x, ddy = o.y - y, ddz = o.z - z;
            if (ddx * ddx + ddy * ddy + ddz * ddz < o.size * o.size * 0.5) { o.size = Math.min(0.55f, o.size + p.size * 0.5f); o.born = w.gameTime; return; }
        }
        Splat o = new Splat();
        o.bx = bx; o.by = by; o.bz = bz; o.face = face;
        o.state = w.getState(bx, by, bz);
        o.x = x + Dir.DX[face] * 0.004; o.y = y + Dir.DY[face] * 0.004; o.z = z + Dir.DZ[face] * 0.004;
        // a hit wall gets a smaller splat than the ground
        o.size = (face == Dir.UP ? 2.6f : 1.8f) * p.size + rnd.nextFloat() * 0.03f;
        o.turn = rnd.nextFloat() * (float) Math.PI * 2;
        o.dark = 0.8f + rnd.nextFloat() * 0.35f;
        o.born = w.gameTime;
        if (splats.size() >= MAX_SPLATS) splats.remove(0);
        splats.add(o);
    }

    /** Blood textures (16 x 16, textures/blocks/blood_0..5.png, a resource pack can replace them): 0..2 drops, 3..5 pools. */
    public static final int BLOOD_VARIANTS = 6;

    /**
     * Blood splats: a 16 x 16 pixel-art texture (Minecraft style) lying on the face on the block's own pixel grid (one
     * texel = one pixel of the block), cut at the face's edges; small splats use a drop texture, bigger ones a pool.
     * Lit like the block, drying darker, fading at the end.
     */
    private void bloodSplats(derpcraft.util.Frustum frustum) {
        if (splats.isEmpty() || world == null) return;
        long now = world.gameTime;
        mb.flush();
        glDepthMask(false);
        glEnable(GL_POLYGON_OFFSET_FILL);
        glPolygonOffset(-2, -2);
        float sky = mb.sky;
        for (int i = splats.size() - 1; i >= 0; i--) {
            Splat o = splats.get(i);
            long age = now - o.born;
            if (age > BLOOD_LIFE || world.getState(o.bx, o.by, o.bz) != o.state) { splats.remove(i); continue; }
            double wx = o.x - camX, wy = o.y - camY, wz = o.z - camZ;
            if (wx * wx + wy * wy + wz * wz > 64 * 64) continue;
            if (frustum != null && !frustum.testAabb((float) wx - 0.6f, (float) wy - 0.6f, (float) wz - 0.6f, (float) wx + 0.6f, (float) wy + 0.6f, (float) wz + 0.6f)) continue;
            if (now - o.lit > 20 || o.lit > now) {
                o.lsky = computeLight(world, o.x + Dir.DX[o.face] * 0.3, o.y + Dir.DY[o.face] * 0.3, o.z + Dir.DZ[o.face] * 0.3);
                o.lr = light[0]; o.lg = light[1]; o.lb = light[2]; o.lit = now;
            }
            int variant = o.size < 0.11f ? (int) (o.dark * 997) % 3 : 3 + (int) (o.dark * 991) % 3;
            mb.layer(assets.blocks.layer("blood_" + variant));
            mb.sky = o.lsky;
            float a = age > BLOOD_LIFE - BLOOD_FADE ? (BLOOD_LIFE - age) / (float) BLOOD_FADE : 1;
            float dry = 1 - 0.2f * Math.min(1, age / 1200f);   // fresh -> dried a little darker over a minute
            // lit like the block, but never black: in the dark it stays a dull red
            mb.color(Math.max(0.4f, o.lr * dry), Math.max(0.4f, o.lg * dry), Math.max(0.4f, o.lb * dry), a);
            // the face's own 2D axes (u, v) and where the splat is on it (block pixels: 0..16), snapped to the pixel grid
            int f = o.face;
            double ou = f < 2 ? o.x - o.bx : f < 4 ? o.x - o.bx : o.z - o.bz;
            double ov = f < 2 ? o.z - o.bz : o.y - o.by;
            float cu = Math.round(ou * 16), cv = Math.round(ov * 16);
            float s0u = cu - 8, s0v = cv - 8;                    // the 16 x 16 texture centred there
            float u0 = Math.max(0, s0u), u1 = Math.min(16, s0u + 16), v0 = Math.max(0, s0v), v1 = Math.min(16, s0v + 16);
            if (u1 <= u0 || v1 <= v0) continue;
            // texture coordinates of the part on the face; quarter turns and a flip from the splat's turn (pixels stay square)
            boolean wall = f >= 2;                               // on a wall the drips run down: no turn
            float[] uv = {(u0 - s0u) / 16, (v0 - s0v) / 16, (u1 - s0u) / 16, (v1 - s0v) / 16};
            // the image's rows go down, a wall's v goes up: flip it there so the top of the picture is up
            if (wall) uv = new float[]{uv[0], 1 - uv[1], uv[2], 1 - uv[3]};
            int rot = wall ? 0 : (int) (o.turn / (Math.PI / 2)) & 3;
            float plane = f == Dir.UP ? o.by + 1.004f : f == Dir.DOWN ? o.by - 0.004f : f == Dir.SOUTH ? o.bz + 1.004f : f == Dir.NORTH ? o.bz - 0.004f
                    : f == Dir.EAST ? o.bx + 1.004f : o.bx - 0.004f;
            float[][] corners = {{u0, v0}, {u1, v0}, {u1, v1}, {u0, v1}};
            float[][] tex = {{uv[0], uv[1]}, {uv[2], uv[1]}, {uv[2], uv[3]}, {uv[0], uv[3]}};
            float[] px = new float[12], tc = new float[8];
            for (int c = 0; c < 4; c++) {
                float cu2 = corners[c][0] / 16, cv2 = corners[c][1] / 16;
                double X, Y, Z;
                if (f < 2) { X = o.bx + cu2; Y = plane; Z = o.bz + cv2; }
                else if (f < 4) { X = o.bx + cu2; Y = o.by + cv2; Z = plane; }
                else { X = plane; Y = o.by + cv2; Z = o.bz + cu2; }
                px[c * 3] = (float) (X - camX); px[c * 3 + 1] = (float) (Y - camY); px[c * 3 + 2] = (float) (Z - camZ);
                float tu = tex[c][0], tv = tex[c][1];
                for (int r = 0; r < rot; r++) { float t = tu; tu = tv; tv = 1 - t; }
                tc[c * 2] = tu; tc[c * 2 + 1] = tv;
            }
            // two triangles (both windings are drawn: culling is off for the decals)
            int[] tri = {0, 1, 2, 0, 2, 3};
            for (int q : tri) mb.v(px[q * 3], px[q * 3 + 1], px[q * 3 + 2], tc[q * 2], tc[q * 2 + 1]);
        }
        mb.flush();
        mb.sky = sky;
        mb.color(1, 1, 1, 1);
        glDisable(GL_POLYGON_OFFSET_FILL);
        glDepthMask(true);
    }

    // ------------------------------------------------------------------------------------------ tyre marks and smoke
    /** A dark strip on the ground behind a sliding wheel (from the last tick's place to this one). */
    private static final class Skid {
        double x0, y0, z0, x1, y1, z1;
        float width, alpha;
        long born;
    }

    private final List<Skid> skids = new ArrayList<>();
    static final int MAX_SKIDS = 2400, SKID_LIFE = 2400, SKID_FADE = 400;

    /** Tyre mark between two points on the ground (a wheel of width 'width' that slides; 'alpha' = how dark). */
    public void skidMark(double x0, double y0, double z0, double x1, double y1, double z1, float width, float alpha, long now) {
        Skid o = new Skid();
        o.x0 = x0; o.y0 = y0; o.z0 = z0; o.x1 = x1; o.y1 = y1; o.z1 = z1; o.width = width; o.alpha = alpha; o.born = now;
        if (skids.size() >= MAX_SKIDS) skids.remove(0);
        skids.add(o);
    }

    /** The marks: flat strips on the ground (a plain dark colour: no texture), fading out after two minutes. */
    private void skidMarks(derpcraft.util.Frustum frustum) {
        if (skids.isEmpty() || world == null || assets.tables == null) return;
        long now = world.gameTime;
        mb.flush();
        glDepthMask(false);
        glEnable(GL_POLYGON_OFFSET_FILL);
        glPolygonOffset(-3, -3);
        float sky = mb.sky;
        mb.layer(assets.blocks.layer("wool_colored_white"));
        for (int i = skids.size() - 1; i >= 0; i--) {
            Skid o = skids.get(i);
            long age = now - o.born;
            if (age > SKID_LIFE || age < 0) { skids.remove(i); continue; }
            double mx = (o.x0 + o.x1) / 2 - camX, my = (o.y0 + o.y1) / 2 - camY, mz = (o.z0 + o.z1) / 2 - camZ;
            if (mx * mx + my * my + mz * mz > 96 * 96) continue;
            if (frustum != null && !frustum.testAabb((float) mx - 0.8f, (float) my - 0.5f, (float) mz - 0.8f, (float) mx + 0.8f, (float) my + 0.5f, (float) mz + 0.8f)) continue;
            double dx = o.x1 - o.x0, dz = o.z1 - o.z0, l = Math.sqrt(dx * dx + dz * dz);
            if (l < 1e-4) continue;
            double nx = -dz / l * o.width / 2, nz = dx / l * o.width / 2;
            float a = o.alpha * (age > SKID_LIFE - SKID_FADE ? (SKID_LIFE - age) / (float) SKID_FADE : 1) * (1 - 0.35f * Math.min(1, age / 1200f));
            mb.sky = 0;
            mb.color(0.03f, 0.03f, 0.035f, a * 0.85f);
            float ax = (float) (o.x0 - nx - camX), az = (float) (o.z0 - nz - camZ), ay = (float) (o.y0 - camY);
            float bx = (float) (o.x0 + nx - camX), bz = (float) (o.z0 + nz - camZ);
            float cx = (float) (o.x1 + nx - camX), cz = (float) (o.z1 + nz - camZ), cy = (float) (o.y1 - camY);
            float ex = (float) (o.x1 - nx - camX), ez = (float) (o.z1 - nz - camZ);
            mb.v(ax, ay, az, 0.2f, 0.2f); mb.v(bx, ay, bz, 0.4f, 0.2f); mb.v(cx, cy, cz, 0.4f, 0.4f);
            mb.v(ax, ay, az, 0.2f, 0.2f); mb.v(cx, cy, cz, 0.4f, 0.4f); mb.v(ex, cy, ez, 0.2f, 0.4f);
        }
        mb.flush();
        mb.sky = sky;
        mb.color(1, 1, 1, 1);
        glDisable(GL_POLYGON_OFFSET_FILL);
        glDepthMask(true);
    }

    /** Tyre smoke from a sliding wheel: white-grey puffs that drift on with the car and thin out ('intensity' 0..1). */
    public void tireSmoke(double x, double y, double z, double carMx, double carMz, float intensity, float[] tint) {
        if (assets.tables == null || particles.size() > MAX_PARTICLES * 9 / 10) return;
        Particle p = new Particle();
        p.x = p.px = x + jit(0.2); p.y = p.py = y; p.z = p.pz = z + jit(0.2);
        p.mx = carMx * 0.35 + jit(0.05); p.my = 0.012 + rnd.nextDouble() * 0.022; p.mz = carMz * 0.35 + jit(0.05);
        setup(p, assets.blocks.layer("wool_colored_white"), 0xFFFFFF);
        p.gravity = -0.0012f;
        p.size = 0.12f + rnd.nextFloat() * 0.12f + intensity * 0.08f;
        p.maxAge = 16 + rnd.nextInt(14) + (int) (intensity * 8);
        float c = 0.78f + rnd.nextFloat() * 0.2f;
        p.r = c * tint[0]; p.g = c * tint[1]; p.b = c * tint[2];
        p.fade = true; p.grow = 2.0f;
        sprite(p, 7, true, false);
    }

    /**
     * A chip shot out of a block ('worn' = how much of it is shot away, 0..1): a crater (bigger as it wears) and
     * chunks of the block flying off the face, the bigger ones bouncing.
     */
    public void bulletCrater(World w, Raycast.Hit h, float worn) {
        if (assets.tables == null || h == null || h.block == null) return;
        bulletHole(w, h);
        if (!holes.isEmpty()) {
            Hole o = holes.get(holes.size() - 1);
            o.size *= 1.25f + worn * 1.4f;                     // the face crumbles: later holes are wider
        }
        int layer = assets.tables.faceLayer[((h.block.id << 4) | h.meta) * 6 + h.face];
        int tint = h.block.tint == Block.Tint.NONE || h.block.sideOverlay != null ? 0xFFFFFF : (h.block.tint == Block.Tint.FIXED ? h.block.fixedTint : 0x79C05A);
        double nx = Dir.DX[h.face], ny = Dir.DY[h.face], nz = Dir.DZ[h.face];
        int n = 4 + Math.round(worn * 6);
        for (int i = 0; i < n; i++) {
            Particle p = new Particle();
            p.x = p.px = h.hx + nx * 0.04; p.y = p.py = h.hy + ny * 0.04; p.z = p.pz = h.hz + nz * 0.04;
            double sp = 0.1 + rnd.nextDouble() * 0.16;
            p.mx = nx * sp + jit(0.18); p.my = ny * sp + jit(0.14) + 0.08; p.mz = nz * sp + jit(0.18);
            setup(p, layer, tint);
            p.size = 0.045f + rnd.nextFloat() * (0.05f + worn * 0.05f);
            p.uSize = 0.18f;
            p.maxAge = 18 + rnd.nextInt(22);
            p.gravity = 0.045f;
        }
    }

    /** A bullet hits a creature or an NPC: a red puff. */
    public void gunBlood(double x, double y, double z, double dx, double dy, double dz) {
        if (assets.tables == null) return;
        int layer = assets.blocks.layer("wool_colored_white");
        for (int i = 0; i < 5; i++) {
            Particle p = new Particle();
            p.x = p.px = x; p.y = p.py = y; p.z = p.pz = z;
            p.mx = dx * 0.05 + jit(0.06); p.my = jit(0.06) + 0.03; p.mz = dz * 0.05 + jit(0.06);
            setup(p, layer, 0xFFFFFF);
            p.gravity = 0.02f; p.size = 0.05f + rnd.nextFloat() * 0.04f; p.maxAge = 8 + rnd.nextInt(8);
            p.r = 0.55f + rnd.nextFloat() * 0.15f; p.g = 0.02f; p.b = 0.02f;
            sprite(p, 7, true, false);
        }
    }

    // ------------------------------------------------------------------------------------------ cameras and screens
    /** True while a camera picture is rendered (see CameraNetwork). */
    public boolean viewPass;
    /** Cameras of the open world (set by the session), or null. */
    public derpcraft.camera.CameraNetwork cameras;
    private final float[] canvasCorners = new float[15];

    /** Security camera block: the camera model turned to where it looks. */
    private void cameraBlock(derpcraft.camera.CameraEntity ce) {
        double cx = ce.x + 0.5 - camX, cy = ce.y + 0.5 - camY, cz = ce.z + 0.5 - camZ;
        if (viewPass && cx * cx + cy * cy + cz * cz < 1) return;          // the camera itself, in its own picture
        if (!shadowPass) lightAt(ce.x + 0.5, ce.y + 0.5, ce.z + 0.5);
        Mat4 m = new Mat4().translate((float) cx, (float) cy, (float) cz)
                .rotate((float) Math.toRadians(-ce.yaw), 0, 1, 0).rotate((float) Math.toRadians(ce.pitch), 1, 0, 0);
        derpcraft.model.Model3D md = derpcraft.camera.CameraBlock.model();
        if (md == null) drawCube(new Mat4().set(m).scale(0.35f, 0.3f, 0.5f), derpcraft.camera.CameraBlock.INSTANCE, 0xFFFFFF);
        else model3d(m.translate(-0.5f, -0.5f, -0.5f), md);
    }

    /** Screen canvas: the connected camera's picture (cropped, full bright), or a dark screen without a signal. */
    private void canvas(derpcraft.camera.CanvasEntity ce, int meta) {
        float[] c = canvasCorners;
        derpcraft.camera.CanvasBlock.corners(ce, meta, c);
        float ox = (float) camX, oy = (float) camY, oz = (float) camZ;
        float nx = c[12], ny = c[13], nz = c[14];
        // seen from behind: only the dark back of the screen
        float vx = c[0] - ox, vy = c[1] - oy, vz = c[2] - oz;
        boolean front = vx * nx + vy * ny + vz * nz < 0;
        mb.sky = 0;
        Texture2D t = front && cameras != null ? cameras.feed(ce.code) : null;
        if (t != null) {
            mb.texture(t);
            float b = ce.brightness;
            if (cameras.nightVision(ce.code)) mb.color(b * 0.8f, b, b * 0.8f, 1);
            else mb.color(b, b, b, 1);
            float u0 = ce.cropL, u1 = 1 - ce.cropR, v0 = ce.cropB, v1 = 1 - ce.cropT;
            canvasQuad(c, ox, oy, oz, u0, v0, u1, v1);
        } else {
            mb.untextured();
            if (!front) mb.color(0.04f, 0.04f, 0.045f, 1);
            else {
                var st = cameras == null ? derpcraft.camera.CameraNetwork.Status.NO_CAMERA : cameras.status(ce.code);
                switch (st) {
                    case OFF -> mb.color(0.01f, 0.01f, 0.01f, 1);
                    case WAITING -> mb.color(0.08f, 0.08f, 0.09f, 1);
                    default -> {                                           // no signal: dark blue with a slow flicker
                        float f = 0.85f + 0.15f * (float) Math.sin(System.nanoTime() / 2.5e8 + ce.x * 3 + ce.z);
                        mb.color(0.05f * f, 0.08f * f, 0.16f * f, 1);
                    }
                }
            }
            canvasQuad(c, ox, oy, oz, 0, 0, 1, 1);
        }
        mb.color(1, 1, 1, 1);
    }

    private static final int[] QUAD_ORDER = {0, 1, 2, 2, 3, 0};

    /** The canvas quad, camera-relative (corners bottom left, bottom right, top right, top left). */
    private void canvasQuad(float[] c, float ox, float oy, float oz, float u0, float v0, float u1, float v1) {
        for (int k : QUAD_ORDER) mb.v(c[k * 3] - ox, c[k * 3 + 1] - oy, c[k * 3 + 2] - oz, k == 0 || k == 3 ? u0 : u1, k < 2 ? v0 : v1);
    }

    private void modelBlock(derpcraft.model.ModelEntity me) {
        derpcraft.model.Model3D md = derpcraft.model.Models.get(me.model);
        if (md == null || md.empty()) {
            // no model chosen yet: a small marker so the block can be found
            if (shadowPass) return;
            lightAt(me.x + 0.5, me.y + 0.5, me.z + 0.5);
            Mat4 m = new Mat4().translate((float) (me.x + 0.5 - camX), (float) (me.y + 0.25 - camY), (float) (me.z + 0.5 - camZ)).scale(0.5f, 0.5f, 0.5f);
            drawCube(m, derpcraft.model.Model3DBlock.INSTANCE, 0xFFFFFF);
            return;
        }
        if (!shadowPass) lightAt(me.x + 0.5, me.y + 0.5, me.z + 0.5);
        model3d(me.matrix(me.x - camX, me.y - camY, me.z - camZ, md), md);
    }

    private static float[] poseAngles(derpcraft.npc.Poses.Def d, int part, float[] anim) {
        if (d == null || !d.set[part]) return anim;
        float[] r = {(float) Math.toRadians(d.rot[part][0]), (float) Math.toRadians(d.rot[part][1]), (float) Math.toRadians(d.rot[part][2])};
        if (part == derpcraft.npc.Poses.HEAD) for (int k = 0; k < 3; k++) r[k] += anim[k];
        return r;
    }

    // ------------------------------------------------------------------------------------------ armour and hats
    private static final derpcraft.api.Model ARMOR_TEX = derpcraft.api.Model.create(64, 32);

    private static String armorMaterial(Item it) {
        String n = it.name.substring(it.name.indexOf(':') + 1);
        if (it.armorSlot < 0) return null;
        if (n.startsWith("leather")) return "leather";
        if (n.startsWith("chainmail") || n.startsWith("chain")) return "chainmail";
        if (n.startsWith("iron")) return "iron";
        if (n.startsWith("gold")) return "gold";
        if (n.startsWith("diamond")) return "diamond";
        return n.contains("_") ? n.substring(0, n.indexOf('_')) : n;
    }

    private Texture2D armorTexture(String mat, int layer) {
        String path = "assets/minecraft/textures/models/armor/" + mat + "_layer_" + layer + ".png";
        if (mobTexVersion != assets.version) mobTexture(null, ARMOR_TEX);      // resets the cache on pack change
        Texture2D t = mobTextures.get(path);
        if (t != null) return t;
        java.awt.image.BufferedImage img = assets.resources.image(path);
        if (img == null) {
            int c = switch (mat) { case "leather" -> 0x8A5A30; case "chainmail" -> 0x6A6A6A; case "iron" -> 0xC8C8C8; case "gold" -> 0xF0D040; case "diamond" -> 0x50D8E0; default -> 0x909090; };
            img = new java.awt.image.BufferedImage(64, 32, java.awt.image.BufferedImage.TYPE_INT_ARGB);
            Random r = new Random(c);
            for (int yy = 0; yy < 32; yy++) for (int xx = 0; xx < 64; xx++) {
                float f = 0.8f + r.nextFloat() * 0.2f;
                img.setRGB(xx, yy, 0xFF000000 | ((int) (((c >> 16) & 255) * f) << 16) | ((int) (((c >> 8) & 255) * f) << 8) | (int) ((c & 255) * f));
            }
        }
        t = new Texture2D(img, false, false);
        mobTextures.put(path, t);
        return t;
    }

    private void armor(ItemStack[] a, Mat4 head, Mat4 body, Mat4 rArm, Mat4 lArm, Mat4 rLeg, Mat4 lLeg, Texture2D skin) {
        float[] zero = {0, 0, 0}, origin = {0, 0, 0};
        for (int slot = 0; slot < 4 && slot < a.length; slot++) {
            ItemStack st = a[slot];
            if (st == null) continue;
            String mat = armorMaterial(st.item);
            if (mat == null) {
                if (slot == 0) hat(head, st);
                continue;
            }
            mb.texture(armorTexture(mat, slot == 2 ? 2 : 1));
            switch (slot) {
                case 0 -> part(head, origin, zero, AR_HEAD, 64, 32);
                case 1 -> {
                    part(body, origin, zero, AR_BODY, 64, 32);
                    part(rArm, origin, zero, AR_RARM, 64, 32);
                    part(lArm, origin, zero, AR_LARM, 64, 32);
                }
                case 2 -> {
                    part(body, origin, zero, AR_LEGGINGS_BODY, 64, 32);
                    part(rLeg, origin, zero, AR_LEGGINGS_LEG, 64, 32);
                    part(lLeg, origin, zero, AR_LEGGINGS_LEG, 64, 32);
                }
                default -> {
                    part(rLeg, origin, zero, AR_BOOT, 64, 32);
                    part(lLeg, origin, zero, AR_BOOT, 64, 32);
                }
            }
        }
        mb.texture(skin);
    }

    /** Any block or item worn on the head (like a pumpkin in vanilla). */
    private void hat(Mat4 head, ItemStack st) {
        Mat4 m = new Mat4().set(head);
        Item it = st.item;
        if (rendersAsCube(it)) {
            m.translate(0, -0.25f, 0);
            m.scale(-0.625f, -0.625f, 0.625f);
            drawBlock(m, it.block, tintFor(it, dummyGui));
        } else {
            m.translate(0, -0.6f, 0);
            m.scale(-0.6f, -0.6f, 0.6f);
            drawSprite(m, itemLayer(it));
        }
    }

    /** One ModelRenderer: pivot (pixels), angles {x,y,z} (radians), boxes {x,y,z,w,h,d,u,v,inflate}. Returns the part matrix. */
    // player model and armour boxes (x, y, z, w, h, d, u, v, grow): constant, so not rebuilt for every model every frame
    private static final float[][] PM_HEAD = {{-4, -8, -4, 8, 8, 8, 0, 0, 0}, {-4, -8, -4, 8, 8, 8, 32, 0, 0.5f}};
    private static final float[][] PM_BODY = {{-4, 0, -2, 8, 12, 4, 16, 16, 0}, {-4, 0, -2, 8, 12, 4, 16, 32, 0.25f}};
    private static final float[][] PM_RARM_SLIM = {{-2, -2, -2, 3, 12, 4, 40, 16, 0}, {-2, -2, -2, 3, 12, 4, 40, 32, 0.25f}};
    private static final float[][] PM_LARM_SLIM = {{-1, -2, -2, 3, 12, 4, 32, 48, 0}, {-1, -2, -2, 3, 12, 4, 48, 48, 0.25f}};
    private static final float[][] PM_RARM = {{-3, -2, -2, 4, 12, 4, 40, 16, 0}, {-3, -2, -2, 4, 12, 4, 40, 32, 0.25f}};
    private static final float[][] PM_LARM = {{-1, -2, -2, 4, 12, 4, 32, 48, 0}, {-1, -2, -2, 4, 12, 4, 48, 48, 0.25f}};
    private static final float[][] PM_RLEG = {{-2, 0, -2, 4, 12, 4, 0, 16, 0}, {-2, 0, -2, 4, 12, 4, 0, 32, 0.25f}};
    private static final float[][] PM_LLEG = {{-2, 0, -2, 4, 12, 4, 16, 48, 0}, {-2, 0, -2, 4, 12, 4, 0, 48, 0.25f}};
    private static final float[][] AR_HEAD = {{-4, -8, -4, 8, 8, 8, 0, 0, 1f}};
    private static final float[][] AR_BODY = {{-4, 0, -2, 8, 12, 4, 16, 16, 1f}};
    private static final float[][] AR_RARM = {{-3, -2, -2, 4, 12, 4, 40, 16, 1f}};
    private static final float[][] AR_LARM = {{-1, -2, -2, 4, 12, 4, 40, 16, 1f}};
    private static final float[][] AR_LEGGINGS_BODY = {{-4, 0, -2, 8, 12, 4, 16, 16, 0.5f}};
    private static final float[][] AR_LEGGINGS_LEG = {{-2, 0, -2, 4, 12, 4, 0, 16, 0.5f}};
    private static final float[][] AR_BOOT = {{-2, 0, -2, 4, 12, 4, 0, 16, 1f}};
    private static final float[] ZERO3 = new float[3];
    private static final float[] FACE_SHADE = {0.5f, 1f, 0.8f, 0.8f, 0.6f, 0.6f};

    private Mat4 part(Mat4 base, float[] pivot, float[] ang, float[][] boxes) { return part(base, pivot, ang, boxes, 64, 64); }

    private Mat4 part(Mat4 base, float[] pivot, float[] ang, float[][] boxes, int tw, int th) {
        Mat4 m = new Mat4().set(base).translate(pivot[0] / 16f, pivot[1] / 16f, pivot[2] / 16f);
        if (ang[2] != 0) m.rotate(ang[2], 0, 0, 1);
        if (ang[1] != 0) m.rotate(ang[1], 0, 1, 0);
        if (ang[0] != 0) m.rotate(ang[0], 1, 0, 0);
        for (float[] b : boxes) {
            float d = b[8];
            if (d == 0) mb.box(m, b[0], b[1], b[2], (int) b[3], (int) b[4], (int) b[5], (int) b[6], (int) b[7], tw, th, 1, 0.8f);
            else {
                // second skin layer: same unwrap, box grown by 'd' pixels on every side
                float sx = (b[3] + 2 * d) / b[3], sy = (b[4] + 2 * d) / b[4], sz = (b[5] + 2 * d) / b[5];
                float cx = b[0] + b[3] / 2f, cy = b[1] + b[4] / 2f, cz = b[2] + b[5] / 2f;
                Mat4 g = new Mat4().set(m).translate(cx / 16f, cy / 16f, cz / 16f).scale(sx, sy, sz).translate(-cx / 16f, -cy / 16f, -cz / 16f);
                mb.box(g, b[0], b[1], b[2], (int) b[3], (int) b[4], (int) b[5], (int) b[6], (int) b[7], tw, th, 1, 0.8f);
            }
        }
        return m;
    }

    /** A limb part stretched along its length (selfie arm); stretch 1 = the normal part. */
    private Mat4 armPart(Mat4 base, float[] pivot, float[] ang, float stretch, float[][] boxes) {
        if (stretch == 1) return part(base, pivot, ang, boxes);
        Mat4 m = new Mat4().set(base).translate(pivot[0] / 16f, pivot[1] / 16f, pivot[2] / 16f);
        if (ang[2] != 0) m.rotate(ang[2], 0, 0, 1);
        if (ang[1] != 0) m.rotate(ang[1], 0, 1, 0);
        if (ang[0] != 0) m.rotate(ang[0], 1, 0, 0);
        m.scale(1, stretch, 1);
        return part(m, ZERO3, ZERO3, boxes);
    }

    /** Held item in the right hand of the third person model (LayerHeldItem + thirdperson_righthand transforms). */
    private void heldThirdPerson(Mat4 arm, ItemStack held, Texture2D skin, boolean left) {
        Mat4 m = new Mat4().set(arm);
        m.rotate(rad(-90), 1, 0, 0);
        m.rotate(rad(180), 0, 1, 0);
        m.translate(left ? -1 / 16f : 1 / 16f, 0.125f, -0.625f);
        Item it = held.item;
        if (rendersAsCube(it)) {
            m.translate(0, 2.5f / 16, 0);
            m.rotate(rad(45), 0, 1, 0);
            m.rotate(rad(75), 1, 0, 0);
            m.scale(0.375f, 0.375f, 0.375f);
            drawBlock(m, it.block, tintFor(it, dummyGui));
        } else {
            m.translate(0, 4f / 16, 0.5f / 16);
            m.rotate(rad(-90), 0, 1, 0);
            m.rotate(rad(55), 0, 0, 1);
            m.scale(0.85f, 0.85f, 0.85f);
            drawSprite(m, heldLayer(it, player != null && held == player.held));
        }
        mb.texture(skin);
    }

    /** GUI colours for plains tint (set by the game). */
    public GuiRenderer dummyGui;

    // ------------------------------------------------------------------------------------------ particles
    public void spawnBreakParticles(World w, int x, int y, int z, Block b, int meta) {
        int layer = assets.tables.faceLayer[((b.id << 4) | meta) * 6 + Dir.NORTH];
        int tint = b.tint == Block.Tint.NONE || b.sideOverlay != null ? 0xFFFFFF : (b.tint == Block.Tint.FIXED ? b.fixedTint : 0x79C05A);
        for (int i = 0; i < 4; i++)
            for (int j = 0; j < 4; j++)
                for (int k = 0; k < 4; k++) {
                    Particle p = new Particle();
                    p.x = p.px = x + (i + 0.5) / 4; p.y = p.py = y + (j + 0.5) / 4; p.z = p.pz = z + (k + 0.5) / 4;
                    p.mx = (p.x - x - 0.5) * 0.3 + (rnd.nextDouble() - 0.5) * 0.1;
                    p.my = (p.y - y - 0.5) * 0.3 + rnd.nextDouble() * 0.15;
                    p.mz = (p.z - z - 0.5) * 0.3 + (rnd.nextDouble() - 0.5) * 0.1;
                    setup(p, layer, tint);
                }
    }

    public void spawnDigParticle(World w, Raycast.Hit h) {
        int layer = assets.tables.faceLayer[((h.block.id << 4) | h.meta) * 6 + Dir.NORTH];
        Particle p = new Particle();
        p.x = p.px = h.hx + Dir.DX[h.face] * 0.1; p.y = p.py = h.hy + Dir.DY[h.face] * 0.1; p.z = p.pz = h.hz + Dir.DZ[h.face] * 0.1;
        p.mx = (rnd.nextDouble() - 0.5) * 0.1; p.my = rnd.nextDouble() * 0.1; p.mz = (rnd.nextDouble() - 0.5) * 0.1;
        setup(p, layer, 0xFFFFFF);
        p.size *= 0.6f;
    }

    /** Draws this particle with a sprite of particles.png (48 = flame, 49 = ember; ageSprite: the 8 generic dots, large to small). */
    private static void sprite(Particle p, int index, boolean ageSprite, boolean glow) {
        p.sprite = index; p.ageSprite = ageSprite; p.glow = glow;
    }

    private void setup(Particle p, int layer, int tint) {
        p.layer = layer;
        p.size = 0.1f * (rnd.nextFloat() * 0.5f + 0.5f);
        p.maxAge = 4 + rnd.nextInt(24);
        p.u0 = rnd.nextFloat() * 0.75f; p.v0 = rnd.nextFloat() * 0.75f;
        float c = 0.6f;
        p.r = c * ((tint >> 16) & 255) / 255f; p.g = c * ((tint >> 8) & 255) / 255f; p.b = c * (tint & 255) / 255f;
        if (particles.size() < MAX_PARTICLES) particles.add(p);
    }

    private void relight(World w, Particle p) {
        p.lsky = computeLight(w, p.x, p.y, p.z);
        p.lr = light[0]; p.lg = light[1]; p.lb = light[2];
    }

    /**
     * Moves the particles one tick. Dead ones are dropped in one pass (removing them one by one from the list was
     * quadratic: tens of thousands of particles took longer than the frame). The light at each particle is looked up
     * here every other tick instead of every frame.
     */
    public void tickParticles(World w) {
        int n = particles.size(), keep = 0;
        for (int i = 0; i < n; i++) {
            Particle p = particles.get(i);
            p.px = p.x; p.py = p.y; p.pz = p.z;
            if (++p.age >= p.maxAge) continue;
            if (p.ballistic) { tickBallistic(w, p); particles.set(keep++, p); continue; }
            p.my -= p.gravity;
            if (p.windX != 0 || p.windZ != 0) { float h = (float) (p.y - p.windY); p.mx += p.windX * h; p.mz += p.windZ * h; }
            double nx = p.x + p.mx, ny = p.y + p.my, nz = p.z + p.mz;
            if (w.getBlock((int) Math.floor(nx), (int) Math.floor(ny), (int) Math.floor(nz)).isOpaqueCube()) {
                if (p.blood) { landBlood(w, p, nx, ny, nz); continue; }           // the drop is gone, a splat is left
                p.my = 0; p.mx *= 0.7; p.mz *= 0.7;
            }
            else { p.x = nx; p.y = ny; p.z = nz; }
            p.mx *= 0.98; p.my *= 0.98; p.mz *= 0.98;
            if (!p.glow && (p.lr < 0 || ((p.age + i) & 1) == 0)) relight(w, p);
            particles.set(keep++, p);
        }
        if (keep < n) particles.subList(keep, n).clear();
    }

    private static boolean solidAt(World w, double x, double y, double z) {
        return w.getBlock((int) Math.floor(x), (int) Math.floor(y), (int) Math.floor(z)).isOpaqueCube();
    }

    /** One tick of a hot fragment: ballistic arc, bounces (each axis on its own) and a trail of fading sparks. */
    private void tickBallistic(World w, Particle p) {
        p.my -= p.gravity;
        double nx = p.x + p.mx, ny = p.y + p.my, nz = p.z + p.mz;
        if (solidAt(w, nx, p.y, p.z)) { p.mx = -p.mx * 0.5; nx = p.x; }
        if (solidAt(w, nx, ny, p.z)) {
            p.my = -p.my * 0.45; p.mx *= 0.75; p.mz *= 0.75;
            if (Math.abs(p.my) < 0.03) p.my = 0;
            ny = p.y;
        }
        if (solidAt(w, nx, ny, nz)) { p.mz = -p.mz * 0.5; nz = p.z; }
        p.x = nx; p.y = ny; p.z = nz;
        p.mx *= p.drag; p.my *= p.drag; p.mz *= p.drag;
        // trail: tiny sparks left behind, the colour the fragment has right now
        if (particles.size() < MAX_PARTICLES * 8 / 10 && rnd.nextFloat() < 0.55f) {
            float f = Math.min(1f, p.age / (float) Math.max(1, p.maxAge));
            Particle t = fx(p.x, p.y, p.z, jit(0.01), jit(0.01) + 0.004, jit(0.01), 49, false, true, p.size * (0.4f + rnd.nextFloat() * 0.25f), 5 + rnd.nextInt(6), 0.01f);
            colors(t, p.r + (p.er - p.r) * f, p.g + (p.eg - p.g) * f, p.b + (p.eb - p.b) * f, 0.55f, 0.04f, 0.02f);
        }
    }

    /** Particles further away than this are not drawn. */
    private static final double PARTICLE_RANGE = 192;

    private void particles(float partial, derpcraft.util.Frustum frustum) {
        if (particles.isEmpty()) return;
        // billboards face the camera: its right and up vectors from the camera angles
        float rx = (float) Math.cos(yawRad), rz = (float) Math.sin(yawRad);
        float ux = (float) (-Math.sin(yawRad) * Math.sin(pitchRad)), uy = (float) Math.cos(pitchRad), uz = (float) (Math.cos(yawRad) * Math.sin(pitchRad));
        boolean sheet = false;
        int n = particles.size();
        // pieces of block textures (breaking, digging)
        for (int i = 0; i < n; i++) {
            Particle p = particles.get(i);
            if (p.sprite >= 0) { sheet = true; continue; }
            float x = (float) (p.px + (p.x - p.px) * partial - camX), y = (float) (p.py + (p.y - p.py) * partial - camY), z = (float) (p.pz + (p.z - p.pz) * partial - camZ);
            if (!visible(x, y, z, p.size, frustum)) continue;
            if (p.lr < 0) relight(world, p);
            mb.sky = p.lsky;
            mb.color(p.lr * p.r, p.lg * p.g, p.lb * p.b, 1);
            mb.layer(p.layer);
            mb.billboard(x, y, z, rx, rz, ux, uy, uz, p.size, p.u0 + p.uSize, p.v0 + p.uSize, p.u0, p.v0);
        }
        if (!sheet || assets.particles == null) return;
        // sprites of the particle sheet of the resource pack (flames, smoke, embers)
        mb.texture(assets.particles);
        float cell = 1f / 16f;
        for (int i = 0; i < n; i++) {
            Particle p = particles.get(i);
            if (p.sprite < 0) continue;
            float x = (float) (p.px + (p.x - p.px) * partial - camX), y = (float) (p.py + (p.y - p.py) * partial - camY), z = (float) (p.pz + (p.z - p.pz) * partial - camZ);
            if (!visible(x, y, z, p.size, frustum)) continue;
            float f = Math.min(1f, (p.age + partial) / Math.max(1, p.maxAge));
            int idx = p.ageSprite ? Math.max(0, Math.min(7, 7 - (int) (f * 8))) : p.sprite;
            float u0 = (idx % 16) * cell, v0 = (idx / 16) * cell;
            float cr = p.r, cg = p.g, cb = p.b, ca = 1f;
            if (p.tintEnd) { cr += (p.er - cr) * f; cg += (p.eg - cg) * f; cb += (p.eb - cb) * f; }
            if (p.fade) ca = Math.max(0f, Math.min(1f, 1.6f * (1 - f)));
            if (p.glow) { mb.sky = 0; mb.color(cr, cg, cb, ca); }
            else {
                if (p.lr < 0) relight(world, p);
                mb.sky = p.lsky;
                mb.color(p.lr * cr, p.lg * cg, p.lb * cb, ca);
            }
            float s = p.size * (p.glow && !p.ageSprite ? 1 - f * f * 0.5f : 1f) * (1 + p.grow * f);
            mb.billboard(x, y, z, rx, rz, ux, uy, uz, s, u0 + cell, v0 + cell, u0, v0);
        }
    }

    /** Camera-relative particle position within range and in the view. */
    private static boolean visible(float x, float y, float z, float size, derpcraft.util.Frustum frustum) {
        if (x * x + y * y + z * z > PARTICLE_RANGE * PARTICLE_RANGE) return false;
        return frustum == null || frustum.testAabb(x - size, y - size, z - size, x + size, y + size, z + size);
    }

    private static final Mat4 IDENTITY = new Mat4();
    /** Camera angles for billboards (radians, Minecraft convention). */
    public double yawRad, pitchRad;

    // ------------------------------------------------------------------------------------------ crack overlay
    /** Shot doors: the block-breaking cracks on both halves, growing with every hit. */
    private void doorCracks() {
        var hits = derpcraft.gun.Ballistics.DOOR_HITS;
        hits.values().removeIf(v -> world.gameTime - v[1] > 200);
        for (var e : hits.entrySet()) {
            long k = e.getKey();
            int x = (int) (k >> 38), z = (int) ((k >> 12) & 0x3FFFFFF), y = (int) (k & 0xFFF);
            if (z >= 0x2000000) z -= 0x4000000;
            float progress = Math.min(0.99f, derpcraft.gun.Ballistics.doorDamage(e.getValue()));
            for (int yy = y; yy <= y + 1; yy++) {
                int st = world.getState(x, yy, z);
                if (!(Blocks.byId(st >> 4) instanceof FunctionalBlocks.DoorBlock)) continue;
                Raycast.Hit dh = new Raycast.Hit();
                dh.x = x; dh.y = yy; dh.z = z; dh.block = Blocks.byId(st >> 4); dh.meta = st & 15;
                crack(dh, progress);
            }
        }
    }

    /** Shot blocks: the block-breaking cracks, growing with every hit (blocks that bullets never break crack only part way). */
    private void blockCracks() {
        var hits = derpcraft.gun.Ballistics.BLOCK_HITS;
        hits.values().removeIf(v -> world.gameTime - v[1] > derpcraft.gun.Ballistics.BLOCK_HEAL);
        for (var e : hits.entrySet()) {
            long k = e.getKey();
            int x = (int) (k >> 38), z = (int) ((k >> 12) & 0x3FFFFFF), y = (int) (k & 0xFFF);
            if (x >= 0x2000000) x -= 0x4000000;
            if (z >= 0x2000000) z -= 0x4000000;
            double cx = x + 0.5 - camX, cy = y + 0.5 - camY, cz = z + 0.5 - camZ;
            if (cx * cx + cy * cy + cz * cz > 64 * 64) continue;
            int st = world.getState(x, y, z);
            Block b = Blocks.byId(st >> 4);
            if (b.isAir()) continue;
            float health = derpcraft.gun.Ballistics.blockHealth(b);
            float progress = health <= 0 ? Math.min(0.45f, derpcraft.gun.Ballistics.blockDamage(e.getValue(), 0))
                    : Math.min(0.99f, derpcraft.gun.Ballistics.blockDamage(e.getValue(), health));
            Raycast.Hit dh = new Raycast.Hit();
            dh.x = x; dh.y = y; dh.z = z; dh.block = b; dh.meta = st & 15;
            crack(dh, progress);
        }
    }

    private void crack(Raycast.Hit h, float progress) {
        int stage = Math.min(9, (int) (progress * 10));
        int layer = assets.blocks.layer("destroy_stage_" + stage);
        mb.flush();
        glBlendFuncSeparate(GL_DST_COLOR, GL_SRC_COLOR, GL_ONE, GL_ZERO);
        glEnable(GL_POLYGON_OFFSET_FILL);
        glPolygonOffset(-3, -3);
        mb.color(1, 1, 1, 1);
        mb.layer(layer);
        int conn = h.block.neighborInfo(world, h.x, h.y, h.z, h.meta);
        for (float[] b : h.block.selectionBoxes(h.meta, conn, h.x, h.y, h.z)) {
            float x0 = (float) (h.x + b[0] - camX), y0 = (float) (h.y + b[1] - camY), z0 = (float) (h.z + b[2] - camZ);
            float x1 = (float) (h.x + b[3] - camX), y1 = (float) (h.y + b[4] - camY), z1 = (float) (h.z + b[5] - camZ);
            float[][] faces = {
                    {x0, y0, z0, x1, y0, z0, x1, y0, z1, x0, y0, z1}, {x0, y1, z0, x0, y1, z1, x1, y1, z1, x1, y1, z0},
                    {x0, y0, z0, x0, y1, z0, x1, y1, z0, x1, y0, z0}, {x0, y0, z1, x1, y0, z1, x1, y1, z1, x0, y1, z1},
                    {x0, y0, z0, x0, y0, z1, x0, y1, z1, x0, y1, z0}, {x1, y0, z0, x1, y1, z0, x1, y1, z1, x1, y0, z1}};
            for (float[] f : faces) mb.quad(IDENTITY, f, new float[]{0, 1, 0, 0, 1, 0, 1, 1});
        }
        mb.flush();
        glDisable(GL_POLYGON_OFFSET_FILL);
        glBlendFuncSeparate(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA, GL_ONE, GL_ONE_MINUS_SRC_ALPHA);
    }

    // ------------------------------------------------------------------------------------------ first-person hand
    /**
     * Minecraft 1.12 first-person transforms (ItemRenderer): arm when empty-handed, otherwise the held block/item.
     * @param equip 0 = fully raised, 1 = lowered (item switching)
     * @param swing 0..1 swing progress
     */
    public void renderHand(World w, ItemStack held, float aspect, float swing, float equip, double px, double py, double pz,
                           WorldRenderer.FrameParams fp) {
        world = w;
        sun = fp.sun; gamma = fp.gamma; flicker = fp.flicker; colored = fp.coloredLight > 0;
        glClear(GL_DEPTH_BUFFER_BIT);
        glEnable(GL_DEPTH_TEST);
        glDisable(GL_CULL_FACE);
        glEnable(GL_BLEND);
        glBlendFuncSeparate(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA, GL_ONE, GL_ONE_MINUS_SRC_ALPHA);
        Mat4 proj = new Mat4().perspective((float) Math.toRadians(70), aspect, 0.05f, 10f);
        if (handBob != null) proj.mul(handBob);          // the hand bobs with the view (applyBobbing before renderItemInFirstPerson)
        handProj(proj, 0.05f, 10f);
        mb.begin(proj, assets.blocks.texId, 0, 0, 0, 0, 0);
        lightAt(px, py, pz);
        if (held == null) arm(swing, equip);
        else heldItem(held, swing, equip);
        mb.flush();
        glDisable(GL_BLEND);
        glEnable(GL_CULL_FACE);
    }

    private static float rad(float deg) { return (float) Math.toRadians(deg); }

    private void arm(float swing, float equip) {
        float side = 1;
        float f1 = (float) Math.sqrt(swing);
        float f2 = -0.3f * (float) Math.sin(f1 * Math.PI);
        float f3 = 0.4f * (float) Math.sin(f1 * Math.PI * 2);
        float f4 = -0.4f * (float) Math.sin(swing * Math.PI);
        Mat4 m = new Mat4().translate(side * (f2 + 0.64000005f), f3 - 0.6f + equip * -0.6f, f4 - 0.71999997f);
        m.rotate(rad(side * 45), 0, 1, 0);
        float f5 = (float) Math.sin(swing * swing * Math.PI), f6 = (float) Math.sin(f1 * Math.PI);
        m.rotate(rad(side * f6 * 70), 0, 1, 0);
        m.rotate(rad(side * f5 * -20), 0, 0, 1);
        m.translate(side * -1, 3.6f, 3.5f);
        m.rotate(rad(side * 120), 0, 0, 1);
        m.rotate(rad(200), 1, 0, 0);
        m.rotate(rad(side * -135), 0, 1, 0);
        m.translate(side * 5.6f, 0, 0);
        // ModelBiped right arm: rotation point (-5, 2, 0), box (-3, -2, -2) 4x12x4 at uv (40, 16)
        m.translate(-5 / 16f, 2 / 16f, 0);
        Texture2D skin = playerSkin != null ? playerSkin : assets.steve;
        mb.texture(skin);
        int th = skin.height >= 64 ? 64 : 32;
        // slim (Alex) arms: 3 pixels wide, from x -2 (ModelPlayer)
        if (playerSlim) mb.box(m, -2, -2, -2, 3, 12, 4, 40, 16, 64, th, 1, 0.8f);
        else mb.box(m, -3, -2, -2, 4, 12, 4, 40, 16, 64, th, 1, 0.8f);
        if (th >= 64) {          // the sleeve layer
            Mat4 sl = new Mat4().set(m).translate(0, 4 / 16f, 0).scale(1.12f, 1.04f, 1.12f).translate(0, -4 / 16f, 0);
            if (playerSlim) mb.box(sl, -2, -2, -2, 3, 12, 4, 40, 32, 64, th, 1, 0.8f);
            else mb.box(sl, -3, -2, -2, 4, 12, 4, 40, 32, 64, th, 1, 0.8f);
        }
    }

    private void heldItem(ItemStack held, float swing, float equip) {
        float side = 1;
        float sq = (float) Math.sqrt(swing);
        float fx = -0.4f * (float) Math.sin(sq * Math.PI), fy = 0.2f * (float) Math.sin(sq * Math.PI * 2), fz = -0.2f * (float) Math.sin(swing * Math.PI);
        Mat4 m = new Mat4().translate(side * fx, fy, fz);
        m.translate(side * 0.56f, -0.52f + equip * -0.6f, -0.72f);
        float f = (float) Math.sin(swing * swing * Math.PI);
        m.rotate(rad(side * (45 + f * -20)), 0, 1, 0);
        float f1 = (float) Math.sin(sq * Math.PI);
        m.rotate(rad(side * f1 * -20), 0, 0, 1);
        m.rotate(rad(f1 * -80), 1, 0, 0);
        m.rotate(rad(side * -45), 0, 1, 0);
        Item it = held.item;
        if (rendersAsCube(it)) {
            // block/block.json firstperson_righthand: rotation [0,45,0], scale 0.4
            m.rotate(rad(45), 0, 1, 0);
            m.scale(0.4f, 0.4f, 0.4f);
            drawBlock(m, it.block, tintFor(it, dummyGui));
        } else {
            // item/generated firstperson_righthand: rotation [0,-90,25], translation [1.13,3.2,1.13], scale 0.68
            m.translate(1.13f / 16, 3.2f / 16, 1.13f / 16);
            m.rotate(rad(-90), 0, 1, 0);
            m.rotate(rad(25), 0, 0, 1);
            m.scale(0.68f, 0.68f, 0.68f);
            int tint = tintFor(it, dummyGui);
            float br = mb.r, bg = mb.g, bb = mb.b;
            mb.r *= ((tint >> 16) & 255) / 255f; mb.g *= ((tint >> 8) & 255) / 255f; mb.b *= (tint & 255) / 255f;
            drawSprite(m, heldLayer(it, true));
            mb.r = br; mb.g = bg; mb.b = bb;
        }
    }

    // ------------------------------------------------------------------------------------------ creatures (1.7)
    private final Map<String, Texture2D> mobTextures = new HashMap<>();
    private int mobTexVersion = -1;

    /** Texture of a creature / projectile model: from the resource packs and mod jars, else a plain generated one. */
    private Texture2D mobTexture(String path, derpcraft.api.Model model) {
        if (mobTexVersion != assets.version) {
            for (Texture2D t : mobTextures.values()) if (t != null && t != assets.steve) t.delete();
            mobTextures.clear();
            mobTexVersion = assets.version;
        }
        Texture2D t = mobTextures.get(path);
        if (t != null) return t;
        java.awt.image.BufferedImage img = path == null ? null : assets.resources.image(path);
        if (img == null && model.playerSkin) t = assets.steve;
        else {
            if (img == null) {
                img = new java.awt.image.BufferedImage(model.texWidth, model.texHeight, java.awt.image.BufferedImage.TYPE_INT_ARGB);
                int c = 0x404040 | ((path == null ? 0 : path.hashCode()) & 0x9F9F9F);
                Random r = new Random(c);
                for (int yy = 0; yy < img.getHeight(); yy++)
                    for (int xx = 0; xx < img.getWidth(); xx++) {
                        float f = 0.85f + r.nextFloat() * 0.15f;
                        int rr = (int) (((c >> 16) & 255) * f), gg = (int) (((c >> 8) & 255) * f), bb = (int) ((c & 255) * f);
                        img.setRGB(xx, yy, 0xFF000000 | (rr << 16) | (gg << 8) | bb);
                    }
            }
            t = new Texture2D(img, false, false);
        }
        mobTextures.put(path, t);
        return t;
    }

    private static float lerpAngle(float a, float b, float t) {
        float d = (b - a) % 360;
        if (d > 180) d -= 360;
        if (d < -180) d += 360;
        return a + d * t;
    }

    private void mob(derpcraft.entity.Mob e, float partial, derpcraft.util.Frustum frustum) {
        var type = e.type;
        double x = e.px + (e.x - e.px) * partial, y = e.py + (e.y - e.py) * partial, z = e.pz + (e.z - e.pz) * partial;
        double rx = x - camX, ry = y - camY, rz = z - camZ;
        if (rx * rx + ry * ry + rz * rz > 128 * 128) return;
        float hw = type.width * type.scale, hh = type.height * type.scale;
        if (frustum != null && !frustum.testAabb((float) rx - hw - 0.5f, (float) ry, (float) rz - hw - 0.5f, (float) rx + hw + 0.5f, (float) ry + hh + 0.5f, (float) rz + hw + 0.5f)) return;
        if (!shadowPass) lightAt(x, y + type.height * 0.6, z);
        derpcraft.api.Model md = type.model;
        float bodyYaw = lerpAngle(e.prevBodyYaw, e.bodyYaw, partial), headYaw = lerpAngle(e.prevHeadYaw, e.headYaw, partial);
        float pitch = e.prevPitch + (e.pitch - e.prevPitch) * partial;
        float ls = e.prevLimbSwing + (e.limbSwing - e.prevLimbSwing) * partial, la = Math.min(1, e.prevLimbAmount + (e.limbAmount - e.prevLimbAmount) * partial);
        float age = e.age + partial, PI = (float) Math.PI;
        float swing = e.swingTicks >= 0 ? (e.swingTicks + partial) / 6f : 0;
        float cr = mb.r, cg = mb.g, cb = mb.b, ca = mb.a;
        if (!shadowPass && (e.hurtTime > 0 || e.deathTime > 0)) mb.color(mb.r, mb.g * 0.55f, mb.b * 0.55f, mb.a);
        else if (!shadowPass && e.fireTicks > 0) mb.color(mb.r * 1.1f, mb.g * 0.75f, mb.b * 0.45f, mb.a);
        Mat4 base = new Mat4().translate((float) rx, (float) ry, (float) rz);
        base.rotate((float) Math.toRadians(180 - bodyYaw), 0, 1, 0);
        if (e.deathTime > 0) base.rotate((float) Math.toRadians(Math.sqrt(Math.min(1, (e.deathTime + partial - 1) / 20f * 1.6f)) * 90), 0, 0, 1);
        float sc = type.scale;
        base.scale(-sc, -sc, sc);
        base.translate(0, -1.501f, 0);
        mb.texture(mobTexture(type.texture, md));
        for (derpcraft.api.Model.Part pt : md.parts) {
            float[] ang = {pt.rx, pt.ry, pt.rz};
            switch (pt.anim) {
                case derpcraft.api.Model.HEAD -> { ang[0] += (float) Math.toRadians(pitch); ang[1] += (float) Math.toRadians(headYaw - bodyYaw); }
                case derpcraft.api.Model.LEG_A -> ang[0] += (float) Math.cos(ls * 0.6662f) * 1.4f * la;
                case derpcraft.api.Model.LEG_B -> ang[0] += (float) Math.cos(ls * 0.6662f + PI) * 1.4f * la;
                case derpcraft.api.Model.ARM_A, derpcraft.api.Model.ARM_B -> {
                    boolean a = pt.anim == derpcraft.api.Model.ARM_A;
                    if (md.armsForward) {
                        ang[0] = -PI / 2 + (float) Math.sin(age * 0.067f) * 0.05f * (a ? 1 : -1);
                        ang[2] += (float) Math.cos(age * 0.09f) * 0.05f * (a ? 1 : -1);
                    } else ang[0] += (float) Math.cos(ls * 0.6662f + (a ? PI : 0)) * la;
                    if (swing > 0 && a) ang[0] -= (float) Math.sin(swing * PI) * 1.2f;
                    if (swing > 0 && !a && md.armsForward) ang[0] -= (float) Math.sin(swing * PI) * 1.2f;
                }
                case derpcraft.api.Model.TAIL -> ang[1] += (float) Math.sin(ls * 0.6662f) * 0.4f * la + (float) Math.sin(age * 0.15f) * 0.08f;
                case derpcraft.api.Model.WING_L -> ang[2] -= 0.3f + (float) Math.sin(age * 0.8f) * 0.6f;
                case derpcraft.api.Model.WING_R -> ang[2] += 0.3f + (float) Math.sin(age * 0.8f) * 0.6f;
                default -> {}
            }
            float[][] boxes = pt.boxes.toArray(new float[0][]);
            float py = pt.py;
            if (pt.anim == derpcraft.api.Model.BODY) py -= Math.abs((float) Math.sin(ls * 0.33f)) * 3 * la;
            part(base, new float[]{pt.px, py, pt.pz}, ang, boxes, md.texWidth, md.texHeight);
        }
        mb.color(cr, cg, cb, ca);
        if (!shadowPass && e.fireTicks > 0 && e.age % 3 == 0 && e.deathTime == 0) flame(x, y + rnd.nextDouble() * type.height, z, type.width);
    }

    private void projectile(derpcraft.entity.Projectile p, float partial) {
        double x = p.px + (p.x - p.px) * partial, y = p.py + (p.y - p.py) * partial, z = p.pz + (p.z - p.pz) * partial;
        double rx = x - camX, ry = y - camY, rz = z - camZ;
        if (rx * rx + ry * ry + rz * rz > 96 * 96) return;
        if (!shadowPass) lightAt(x, y, z);
        var t = p.type;
        if (t.flare && fxTick && !shadowPass) {
            if (!p.windSet) { p.windSet = true; p.windX = -Math.cos(yawRad); p.windZ = -Math.sin(yawRad); }   // to the left of the view
            flareFx(p);
        }
        if (t.fused && fxTick && !shadowPass) fuseFx(p);
        if (t.model != null) {
            Mat4 m = new Mat4().translate((float) rx, (float) ry, (float) rz);
            float yaw = (float) Math.atan2(p.mx, p.mz), pitch = (float) Math.atan2(p.my, Math.sqrt(p.mx * p.mx + p.mz * p.mz));
            m.rotate(yaw, 0, 1, 0);
            m.rotate(-pitch, 1, 0, 0);
            if (t.spin) m.rotate((float) Math.toRadians(p.prevSpin + (p.spin - p.prevSpin) * partial), 0, 0, 1);
            m.scale(-1, -1, 1);
            m.translate(0, -1.501f, 0);
            mb.texture(mobTexture(t.texture, t.model));
            for (derpcraft.api.Model.Part pt : t.model.parts)
                part(m, new float[]{pt.px, pt.py, pt.pz}, new float[]{pt.rx, pt.ry, pt.rz}, pt.boxes.toArray(new float[0][]), t.model.texWidth, t.model.texHeight);
            return;
        }
        Item it = t.spriteItem();
        if (it == null) return;
        if (t.blockModel && it.block != null) { primedBlock(p, it, rx, ry, rz, partial); return; }
        Mat4 m = new Mat4().translate((float) rx, (float) ry, (float) rz);
        if (t.sticks) {
            // arrow: the sprite points along the flight (also while stuck)
            double hl = Math.sqrt(p.dirX * p.dirX + p.dirZ * p.dirZ);
            m.rotate((float) Math.atan2(p.dirX, p.dirZ), 0, 1, 0);
            m.rotate((float) -Math.atan2(p.dirY, hl), 1, 0, 0);
            m.rotate((float) Math.toRadians(-90), 0, 1, 0);
            m.rotate((float) Math.toRadians(-45), 0, 0, 1);
            if (t.igniteBlocks && !shadowPass && p.age % 2 == 0) flame(p.x, p.y, p.z, 0.15f);
        } else if (p.resting) {
            // dynamite at rest: lies flat on the ground at the angle it came to a stop
            m.translate(0, 0.01f, 0);
            m.rotate((float) Math.toRadians(p.spin), 0, 1, 0);
            m.rotate((float) Math.toRadians(90), 1, 0, 0);
        } else if (p.landed) {
            // lying on the ground at the angle it landed with, with a flame
            m.translate(0, 0.01f, 0);
            m.rotate((float) Math.toRadians(p.spin), 0, 1, 0);
            m.rotate((float) Math.toRadians(90), 1, 0, 0);
            if (t.igniteBlocks && !shadowPass && p.age % 3 == 0) flame(p.x, p.y + 0.1, p.z, 0.2f);
        } else {
            m.rotate((float) -yawRad + (float) Math.PI, 0, 1, 0);
            m.rotate((float) pitchRad, 1, 0, 0);
            if (t.spin) m.rotate((float) Math.toRadians(p.prevSpin + (p.spin - p.prevSpin) * partial), 0, 0, 1);
            if (t.igniteBlocks && !shadowPass && p.age % 2 == 0) flame(p.x, p.y, p.z, 0.15f);
        }
        float s = Math.max(0.25f, t.size * 2);
        m.scale(s, s, s);
        if (rendersAsCube(it)) { m.scale(0.5f, 0.5f, 0.5f); drawBlock(m, it.block, tintFor(it, dummyGui)); }
        else {
            float br = mb.r, bg = mb.g, bb = mb.b;
            int tint = tintFor(it, dummyGui);
            mb.r *= ((tint >> 16) & 255) / 255f; mb.g *= ((tint >> 8) & 255) / 255f; mb.b *= (tint & 255) / 255f;
            drawSprite(m, itemLayer(it));
            mb.r = br; mb.g = bg; mb.b = bb;
        }
    }

    /** A lit TNT block: upright, flashing white in turns and swelling just before it goes off (like Minecraft's primed TNT). */
    private void primedBlock(derpcraft.entity.Projectile p, Item it, double rx, double ry, double rz, float partial) {
        float left = p.fuseTicks() - p.age - partial;
        Mat4 m = new Mat4().translate((float) rx, (float) ry + p.type.lift, (float) rz);
        if (left < 10) {
            float k = 1 - Math.max(0, left) / 10f;
            float swell = 1 + (float) Math.sin(k * Math.PI * 2 * 1.5f) * 0.02f + k * k * k * 0.3f;
            m.scale(swell, swell, swell);
        }
        float br = mb.r, bg = mb.g, bb = mb.b;
        if (((int) Math.max(0, left) / 5) % 2 == 0) { mb.r *= 3; mb.g *= 3; mb.b *= 3; }      // white flash
        drawBlock(m, it.block, 0xFFFFFF);
        mb.r = br; mb.g = bg; mb.b = bb;
    }

    /**
     * Lit fuse of a dynamite stick or a primed TNT, once per tick, with the usual particles: a small flame and sparks at the
     * fuse tip and thin grey smoke. In the last second it spits more sparks.
     */
    private void fuseFx(derpcraft.entity.Projectile p) {
        if (particles.size() > MAX_PARTICLES * 9 / 10) return;
        int left = p.fuseTicks() - p.age;
        double x = p.x, y = p.y + (p.type.blockModel ? p.type.lift * 2 + 0.05 : p.resting ? 0.08 : 0.12), z = p.z;
        int rush = left < 20 ? 2 : 1;
        Particle f = fx(x + jit(0.03), y, z + jit(0.03), jit(0.004), 0.006 + rnd.nextDouble() * 0.008, jit(0.004), 48, false, true,
                0.06f + rnd.nextFloat() * 0.05f, 4 + rnd.nextInt(3), -0.0008f);
        colors(f, 1f, 0.95f, 0.7f, 1f, 0.5f, 0.15f);
        for (int i = 0; i < rush + (rnd.nextFloat() < 0.5f ? 1 : 0); i++) {
            double a = rnd.nextDouble() * Math.PI * 2, sp = 0.02 + rnd.nextDouble() * 0.05;
            Particle s = fx(x, y, z, Math.cos(a) * sp, 0.02 + rnd.nextDouble() * 0.06, Math.sin(a) * sp, 49, false, true,
                    0.02f + rnd.nextFloat() * 0.02f, 8 + rnd.nextInt(10), 0.025f);
            colors(s, 1f, 0.9f, 0.5f, 1f, 0.3f, 0.05f);
        }
        if (rnd.nextFloat() < 0.6f) {
            Particle sm = fx(x + jit(0.04), y + 0.05, z + jit(0.04), jit(0.006), 0.02 + rnd.nextDouble() * 0.015, jit(0.006),
                    7, true, false, 0.07f + rnd.nextFloat() * 0.04f, 22 + rnd.nextInt(18), -0.0003f);
            float c = 0.2f + rnd.nextFloat() * 0.2f;
            colors(sm, c, c, c, c * 1.5f, c * 1.5f, c * 1.5f);
            sm.fade = true; sm.grow = 1.2f;
        }
    }

    /** White-grey puff (creature disappearing, projectile landing). */
    public void poof(double x, double y, double z, float w, float h) {
        if (assets.tables == null) return;
        int layer = assets.blocks.layer("wool_colored_white");
        for (int i = 0; i < 12; i++) {
            Particle p = new Particle();
            p.x = p.px = x + (rnd.nextDouble() - 0.5) * w * 1.5; p.y = p.py = y + (rnd.nextDouble() - 0.5) * h; p.z = p.pz = z + (rnd.nextDouble() - 0.5) * w * 1.5;
            p.mx = (rnd.nextDouble() - 0.5) * 0.06; p.my = rnd.nextDouble() * 0.06; p.mz = (rnd.nextDouble() - 0.5) * 0.06;
            setup(p, layer, 0xFFFFFF);
            p.gravity = -0.002f; p.size = 0.14f + rnd.nextFloat() * 0.1f; p.maxAge = 10 + rnd.nextInt(15);
            float c = 0.55f + rnd.nextFloat() * 0.3f; p.r = c; p.g = c; p.b = c;
            sprite(p, 7, true, false);
        }
    }

    // ------------------------------------------------------------------------------------------ fire particles
    private boolean fxTick;
    private long lastFxTime = -1;
    private final List<int[]> fireCells = new ArrayList<>();

    private static long pk(int x, int y, int z) { return ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | (y & 0xFFF); }

    /**
     * Once per tick, for the fires near the camera: flames dancing over them, sparks, and smoke. A lone fire gives a rare thin
     * wisp; where many fires burn together (3x3x3 around it) the topmost ones pour out thick dark smoke that rises high.
     */
    private void fireFx() {
        if (fireCells.isEmpty() || particles.size() > MAX_PARTICLES * 3 / 4) return;
        java.util.Set<Long> set = new java.util.HashSet<>();
        for (int[] c : fireCells) set.add(pk(c[0], c[1], c[2]));
        for (int[] c : fireCells) {
            int x = c[0], y = c[1], z = c[2];
            int crowd = 0;
            for (int dx = -1; dx <= 1; dx++) for (int dy = -1; dy <= 1; dy++) for (int dz = -1; dz <= 1; dz++) if (set.contains(pk(x + dx, y + dy, z + dz))) crowd++;
            boolean top = !set.contains(pk(x, y + 1, z));
            double cx = x + 0.5, cz = z + 0.5;
            if (rnd.nextFloat() < 0.55f) flameBig(cx + (rnd.nextDouble() - 0.5) * 0.7, y + 0.1 + rnd.nextDouble() * 0.7, cz + (rnd.nextDouble() - 0.5) * 0.7);
            if (rnd.nextFloat() < 0.06f) ember(cx, y + 0.4, cz);
            float smokeChance = crowd >= 7 ? 0.6f : crowd >= 4 ? 0.3f : crowd >= 2 ? 0.08f : 0.025f;
            if (top && rnd.nextFloat() < smokeChance) smoke(cx + (rnd.nextDouble() - 0.5) * 0.6, y + 0.95, cz + (rnd.nextDouble() - 0.5) * 0.6, crowd);
        }
    }

    /** A flame of a burning wreck (a bigger flame, now and then an ember). */
    public void wreckFlame(double x, double y, double z) {
        if (assets.tables == null || particles.size() > MAX_PARTICLES * 9 / 10) return;
        flameBig(x, y, z);
        if (rnd.nextInt(5) == 0) ember(x, y, z);
    }

    /** Dark smoke of a wreck; 'heavy' 0..1 (thicker and darker when fresh). */
    public void wreckSmoke(double x, double y, double z, float heavy) {
        if (assets.tables == null || particles.size() > MAX_PARTICLES * 9 / 10) return;
        smoke(x, y, z, (int) (4 + heavy * 12));
    }

    private void flameBig(double x, double y, double z) {
        Particle p = new Particle();
        p.x = p.px = x; p.y = p.py = y; p.z = p.pz = z;
        p.my = 0.025 + rnd.nextDouble() * 0.02;
        setup(p, assets.blocks.layer("wool_colored_white"), 0xFFFFFF);
        p.gravity = -0.003f; p.size = 0.07f + rnd.nextFloat() * 0.05f; p.maxAge = 6 + rnd.nextInt(7);
        p.r = p.g = p.b = 1f;
        sprite(p, 48, false, true);
    }

    // ------------------------------------------------------------------------------------------ torch and flare particles
    /** A particle of the sheet at a position with a velocity; the caller sets the colours and the rest. */
    private Particle fx(double x, double y, double z, double mx, double my, double mz, int sprite, boolean ageSprite, boolean glow,
                        float size, int maxAge, float gravity) {
        Particle p = new Particle();
        p.x = p.px = x; p.y = p.py = y; p.z = p.pz = z;
        p.mx = mx; p.my = my; p.mz = mz;
        setup(p, assets.blocks.layer("wool_colored_white"), 0xFFFFFF);
        p.size = size; p.maxAge = maxAge; p.gravity = gravity;
        p.r = p.g = p.b = 1f;
        sprite(p, sprite, ageSprite, glow);
        return p;
    }

    /** Colour at the start and at the end of the particle's life. */
    private static Particle colors(Particle p, float r, float g, float b, float er, float eg, float eb) {
        p.r = r; p.g = g; p.b = b; p.er = er; p.eg = eg; p.eb = eb; p.tintEnd = true;
        return p;
    }

    private double jit(double amount) { return (rnd.nextDouble() - 0.5) * amount; }

    /**
     * Placed torch, once per tick: a living flame at the tip (white-hot to orange), embers drifting up, now and then a spark
     * that pops out and falls, and a thin wisp of smoke. A redstone torch glows with tiny red dots instead. Torches far away
     * make fewer particles, and none beyond 44 blocks.
     */
    private void torchFx(Block b, int x, int y, int z, int meta) {
        double tx = x + 0.5, ty = y + 0.68, tz = z + 0.5;
        if (meta >= 1 && meta <= 8) {
            // the tip of a wall torch (see wallTorch): leaning 22.5 degrees away from the wall, or straight out
            int wall = Dir.HORIZONTAL[(meta - 1) & 3];
            double out = meta >= 5 ? -0.125 : 0.26;
            tx += Dir.DX[wall] * out; tz += Dir.DZ[wall] * out;
            ty = y + (meta >= 5 ? 0.56 : 0.84);
        }
        double dx = tx - camX, dy = ty - camY, dz = tz - camZ, d2 = dx * dx + dy * dy + dz * dz;
        if (d2 > 44 * 44 || particles.size() > MAX_PARTICLES * 3 / 4) return;
        float lod = d2 > 22 * 22 ? 0.45f : 1f;
        if (b.name.contains("redstone")) {
            if (rnd.nextFloat() < 0.45f * lod) {
                Particle p = fx(tx + jit(0.14), ty + jit(0.1), tz + jit(0.14), jit(0.012), 0.004 + rnd.nextDouble() * 0.01, jit(0.012),
                        7, true, true, 0.035f + rnd.nextFloat() * 0.03f, 10 + rnd.nextInt(10), -0.0004f);
                colors(p, 1f, 0.1f + rnd.nextFloat() * 0.15f, 0.08f, 0.6f, 0.02f, 0.02f);
            }
            if (rnd.nextFloat() < 0.03f * lod) {
                Particle p = fx(tx, ty, tz, jit(0.03), 0.02 + rnd.nextDouble() * 0.02, jit(0.03), 49, false, true, 0.03f, 14 + rnd.nextInt(10), -0.001f);
                colors(p, 1f, 0.5f, 0.45f, 0.9f, 0.05f, 0.05f);
            }
            return;
        }
        if (b.lightEmission < 8) return;
        // flame: two flickering tongues, white-hot at first, orange-red at the end
        if (rnd.nextFloat() < 0.8f * lod) {
            Particle p = fx(tx + jit(0.05), ty + jit(0.03), tz + jit(0.05), jit(0.004), 0.004 + rnd.nextDouble() * 0.006, jit(0.004),
                    48, false, true, 0.07f + rnd.nextFloat() * 0.05f, 5 + rnd.nextInt(5), -0.0006f);
            colors(p, 1f, 0.95f, 0.75f, 1f, 0.5f, 0.2f);
        }
        if (rnd.nextFloat() < 0.35f * lod) {
            Particle p = fx(tx + jit(0.03), ty + 0.02, tz + jit(0.03), jit(0.003), 0.008 + rnd.nextDouble() * 0.008, jit(0.003),
                    48, false, true, 0.045f + rnd.nextFloat() * 0.03f, 4 + rnd.nextInt(4), -0.001f);
            colors(p, 1f, 0.8f, 0.4f, 1f, 0.35f, 0.1f);
        }
        // embers: glowing specks rising and wandering, orange to dark red
        if (rnd.nextFloat() < 0.07f * lod) {
            Particle p = fx(tx + jit(0.06), ty + 0.03, tz + jit(0.06), jit(0.012), 0.014 + rnd.nextDouble() * 0.02, jit(0.012),
                    49, false, true, 0.022f + rnd.nextFloat() * 0.02f, 22 + rnd.nextInt(26), -0.0012f);
            colors(p, 1f, 0.75f, 0.3f, 0.9f, 0.18f, 0.03f);
        }
        // a spark pops out of the flame and falls
        if (rnd.nextFloat() < 0.012f * lod) {
            for (int i = 0; i < 2 + rnd.nextInt(3); i++) {
                double a = rnd.nextDouble() * Math.PI * 2, sp = 0.025 + rnd.nextDouble() * 0.04;
                Particle p = fx(tx, ty, tz, Math.cos(a) * sp, 0.03 + rnd.nextDouble() * 0.04, Math.sin(a) * sp, 49, false, true,
                        0.02f + rnd.nextFloat() * 0.015f, 12 + rnd.nextInt(10), 0.02f);
                colors(p, 1f, 0.9f, 0.55f, 1f, 0.3f, 0.05f);
            }
        }
        // thin smoke wisp, grey and fading, spreading as it rises
        if (rnd.nextFloat() < 0.09f * lod) {
            Particle p = fx(tx + jit(0.05), ty + 0.06, tz + jit(0.05), jit(0.006), 0.018 + rnd.nextDouble() * 0.012, jit(0.006),
                    7, true, false, 0.07f + rnd.nextFloat() * 0.04f, 28 + rnd.nextInt(22), -0.0003f);
            float c = 0.22f + rnd.nextFloat() * 0.18f;
            colors(p, c, c, c, c * 1.6f, c * 1.6f, c * 1.6f);
            p.fade = true; p.grow = 1.2f;
        }
    }

    /**
     * Signal flare (a thrown redstone torch), once per tick. A white-hot core burns at its tip, sparks spray out like from a
     * sparkler, and the smoke turns from glaring red at the flare into pink, then dull smoky red high up (it fades and
     * spreads, not a flat red column). In the air it leaves a sparkling trail; lying on the ground it sends the big curtain up.
     * The first moments after the throw give a burst of sparks.
     */
    private void flareFx(derpcraft.entity.Projectile pr) {
        if (particles.size() > MAX_PARTICLES * 9 / 10) return;
        double x = pr.x, y = pr.y + 0.1, z = pr.z;
        boolean lying = pr.landed;
        // burning core: white-hot, turning red; a larger, softer red glow behind it
        for (int i = 0; i < 2; i++) {
            Particle p = fx(x + jit(0.05), y + 0.05 + jit(0.04), z + jit(0.05), jit(0.01), 0.01 + rnd.nextDouble() * 0.015, jit(0.01),
                    48, false, true, 0.2f + rnd.nextFloat() * 0.14f, 4 + rnd.nextInt(4), -0.001f);
            colors(p, 1f, 0.82f, 0.78f, 1f, 0.12f, 0.08f);
        }
        if (rnd.nextFloat() < 0.5f) {
            Particle p = fx(x + jit(0.1), y + 0.08, z + jit(0.1), jit(0.01), 0.02, jit(0.01), 48, false, true, 0.42f + rnd.nextFloat() * 0.2f, 5 + rnd.nextInt(4), -0.001f);
            colors(p, 1f, 0.15f, 0.1f, 0.7f, 0.03f, 0.03f);
        }
        // sparks: fast, bright, falling; more of them in the air, and a burst right after the throw
        int sparks = (lying ? 3 : 4) + (pr.age <= 2 ? 14 : 0);
        for (int i = 0; i < sparks; i++) {
            double a = rnd.nextDouble() * Math.PI * 2, sp = 0.04 + rnd.nextDouble() * (pr.age <= 2 ? 0.28 : 0.16);
            double up = lying ? 0.05 + rnd.nextDouble() * 0.16 : -0.02 + rnd.nextDouble() * 0.1;
            Particle p = fx(x, y + 0.05, z, Math.cos(a) * sp + (lying ? 0 : pr.mx * 0.3), up, Math.sin(a) * sp + (lying ? 0 : pr.mz * 0.3), 49, false, true,
                    0.028f + rnd.nextFloat() * 0.035f, 12 + rnd.nextInt(18), 0.03f);
            if (rnd.nextFloat() < 0.3f) colors(p, 1f, 1f, 0.9f, 1f, 0.35f, 0.25f);      // white-hot
            else colors(p, 1f, 0.45f, 0.35f, 0.95f, 0.04f, 0.04f);
        }
        if (!lying) {
            // trail in the air: small puffs left behind that drift up
            if (rnd.nextFloat() < 0.8f) {
                Particle p = fx(x + jit(0.08), y + jit(0.08), z + jit(0.08), jit(0.01), 0.01 + rnd.nextDouble() * 0.01, jit(0.01),
                        7, true, true, 0.14f + rnd.nextFloat() * 0.1f, 22 + rnd.nextInt(18), -0.0008f);
                colors(p, 1f, 0.3f, 0.22f, 0.5f, 0.2f, 0.2f);
                p.fade = true; p.grow = 1.5f;
            }
            return;
        }
        // the curtain of smoke: three puffs a tick, glaring red at the bottom -> dull smoky red high up
        for (int i = 0; i < 3; i++) {
            Particle p = fx(x + jit(0.2), y, z + jit(0.2), jit(0.03), 0.1 + rnd.nextDouble() * 0.03, jit(0.03), 7, true, true,
                    // rises about 30-40 blocks (terminal speed ~0.12 blocks/tick) and drifts more and more sideways the higher it is, like wind
                    0.28f + rnd.nextFloat() * 0.22f, 270 + rnd.nextInt(70), -0.0025f);
            p.windX = (float) pr.windX * 0.00009f; p.windZ = (float) pr.windZ * 0.00009f; p.windY = (float) y;
            float roll = rnd.nextFloat();
            if (roll < 0.55f) colors(p, 1f, 0.10f, 0.07f, 0.55f, 0.2f, 0.2f);          // signal red
            else if (roll < 0.85f) colors(p, 1f, 0.32f, 0.24f, 0.62f, 0.3f, 0.3f);      // pink-red, lighter
            else colors(p, 0.8f, 0.05f, 0.05f, 0.38f, 0.14f, 0.14f);                    // dark red
            p.fade = true; p.grow = 1.1f;
        }
        // from time to time a bright flash puff at the base
        if (rnd.nextFloat() < 0.15f) {
            Particle p = fx(x + jit(0.2), y + 0.1, z + jit(0.2), jit(0.05), 0.04, jit(0.05), 7, true, true, 0.5f + rnd.nextFloat() * 0.3f, 12 + rnd.nextInt(8), -0.001f);
            colors(p, 1f, 0.6f, 0.5f, 1f, 0.15f, 0.1f);
            p.fade = true; p.grow = 1.4f;
        }
    }

    private void ember(double x, double y, double z) {
        Particle p = new Particle();
        p.x = p.px = x; p.y = p.py = y; p.z = p.pz = z;
        p.mx = (rnd.nextDouble() - 0.5) * 0.04; p.my = 0.03 + rnd.nextDouble() * 0.04; p.mz = (rnd.nextDouble() - 0.5) * 0.04;
        setup(p, assets.blocks.layer("wool_colored_white"), 0xFFFFFF);
        p.gravity = -0.004f; p.size = 0.025f + rnd.nextFloat() * 0.02f; p.maxAge = 14 + rnd.nextInt(16);
        p.r = p.g = p.b = 1f;
        sprite(p, 49, false, true);
    }

    private void smoke(double x, double y, double z, int crowd) {
        Particle p = new Particle();
        p.x = p.px = x; p.y = p.py = y; p.z = p.pz = z;
        p.mx = (rnd.nextDouble() - 0.5) * 0.012; p.my = 0.04 + rnd.nextDouble() * 0.02; p.mz = (rnd.nextDouble() - 0.5) * 0.012;
        setup(p, assets.blocks.layer("wool_colored_white"), 0xFFFFFF);
        float heavy = Math.min(1f, crowd / 12f);
        p.gravity = -0.0025f;
        p.size = 0.16f + rnd.nextFloat() * 0.1f + heavy * 0.22f;
        p.maxAge = 50 + rnd.nextInt(40) + (int) (heavy * 60);
        float c = 0.12f + rnd.nextFloat() * 0.14f + (1 - heavy) * 0.25f;
        p.r = c; p.g = c; p.b = c;
        sprite(p, 7, true, false);
    }

    /** Small flames on burning creatures. */
    private void flame(double x, double y, double z, float w) {
        int layer = assets.blocks.layer("wool_colored_white");
        Particle p = new Particle();
        p.x = p.px = x + (rnd.nextDouble() - 0.5) * w; p.y = p.py = y; p.z = p.pz = z + (rnd.nextDouble() - 0.5) * w;
        p.my = 0.03;
        setup(p, layer, 0xFFFFFF);
        p.gravity = -0.003f; p.size = 0.06f + rnd.nextFloat() * 0.04f; p.maxAge = 5 + rnd.nextInt(5);
        p.r = p.g = p.b = 1f;
        sprite(p, 48, false, true);
    }

    /** Explosion: fire flash and a cloud of smoke. */
    public void explosion(double x, double y, double z, float power) {
        if (assets.tables == null) return;
        int layer = assets.blocks.layer("wool_colored_white");
        int n = (int) (30 + power * 25);
        for (int i = 0; i < n; i++) {
            Particle p = new Particle();
            double a = rnd.nextDouble() * Math.PI * 2, b = rnd.nextDouble() * Math.PI - Math.PI / 2, sp = (0.1 + rnd.nextDouble() * 0.25) * Math.min(2, power / 2);
            p.x = p.px = x; p.y = p.py = y; p.z = p.pz = z;
            p.mx = Math.cos(a) * Math.cos(b) * sp; p.my = Math.sin(b) * sp * 0.7 + 0.05; p.mz = Math.sin(a) * Math.cos(b) * sp;
            setup(p, layer, 0xFFFFFF);
            boolean fire = i % 3 == 0;
            p.gravity = fire ? 0 : -0.004f;
            p.size = (fire ? 0.25f : 0.35f) + rnd.nextFloat() * 0.3f * Math.min(2, power / 2);
            p.maxAge = fire ? 6 + rnd.nextInt(6) : 20 + rnd.nextInt(30);
            if (fire) { p.r = p.g = p.b = 1f; sprite(p, 48, false, true); p.size *= 0.6f; }
            else { float c = 0.2f + rnd.nextFloat() * 0.3f; p.r = c; p.g = c; p.b = c; sprite(p, 7, true, false); }
        }
        ballisticBurst(x, y, z, power);
    }

    /**
     * Hot fragments of an explosion: bright yellow-white at the start, cooling through orange to dark red, flying out in
     * arcs (mostly upward), falling under gravity, bouncing along the ground and leaving a trail of sparks. A few big
     * glowing lumps among many small fast ones.
     */
    private void ballisticBurst(double x, double y, double z, float power) {
        if (particles.size() > MAX_PARTICLES * 3 / 4) return;
        int n = (int) Math.min(240, 28 + power * 24);
        float sc = Math.max(0.6f, Math.min(1.9f, power / 3f));
        for (int i = 0; i < n; i++) {
            double a = rnd.nextDouble() * Math.PI * 2, b = -0.1 + rnd.nextDouble() * 1.25;         // mostly upward
            boolean lump = i % 9 == 0;
            double sp = (0.1 + rnd.nextDouble() * (lump ? 0.3 : 0.55)) * sc;
            double mx = Math.cos(a) * Math.cos(b) * sp, my = Math.sin(b) * sp * 1.15 + 0.05, mz = Math.sin(a) * Math.cos(b) * sp;
            Particle p = fx(x + jit(0.4), y + jit(0.3), z + jit(0.4), mx, my, mz, lump ? 48 : 49, false, true,
                    lump ? 0.14f + rnd.nextFloat() * 0.08f : 0.05f + rnd.nextFloat() * 0.06f, 34 + rnd.nextInt(36), lump ? 0.05f : 0.042f);
            p.ballistic = true;
            p.drag = lump ? 0.975f : 0.985f;
            if (lump) colors(p, 1f, 0.7f, 0.25f, 0.75f, 0.06f, 0.01f);
            else colors(p, 1f, 0.97f, 0.6f, 0.88f, 0.1f, 0.02f);       // yellow-white -> deep red
        }
    }
}
