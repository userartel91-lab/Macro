package derpcraft.vehicle;

import derpcraft.block.Block;
import derpcraft.block.Blocks;
import derpcraft.block.Dir;
import derpcraft.game.Player;
import derpcraft.game.Raycast;
import derpcraft.game.Session;
import derpcraft.item.Item;
import derpcraft.item.ItemStack;
import derpcraft.item.Items;
import derpcraft.model.Model3D;
import derpcraft.npc.Actor;
import derpcraft.util.Mat4;
import derpcraft.world.BlockEntityKey;
import derpcraft.world.Chunk;
import derpcraft.world.World;
import derpcraft.world.be.BlockEntity;

import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * The vehicles of a world: driving (player and NPCs), getting in and out, doors, blocks carried in the block places,
 * scene takes that drive a car, collisions with players and creatures, and saving (vehicles.txt in the world folder).
 */
public final class Vehicles {
    /** The open world's vehicles (collision queries from the physics code), or null. */
    public static volatile Vehicles current;
    /** A sitting rider's feet are this far under the seat surface (the sitting model's hips are 0.125 above its feet). */
    public static final float SIT_DROP = -0.125f;
    /** Eye height of a sitting player above the feet. */
    public static final float SIT_EYE = 0.995f;
    /** Model used by the car item and /vehicle when none is named. */
    public static String defaultModel;

    private final Session s;
    private final Path file;
    private final List<Vehicle> list = new ArrayList<>();
    private int nextId = 1;
    /** The vehicle the player sits in and the seat, or null / -1. */
    public Vehicle playerVehicle;
    public int playerSeat = -1;
    private boolean prevSneak;
    // camera sway of the rider: springs that follow the car's tilt and bounce a little late; {value, speed, previous value}
    private final float[] swPitch = new float[3], swRoll = new float[3], swY = new float[3];
    private double swLastY;
    private float swLastSpeed, swAccel, swVy;

    /** The cinematic camera (Right Alt + F8 while riding). */
    public final VehicleCamera camera = new VehicleCamera();
    // pushing a car from behind (hold Alt): the arms and where the player stands
    private boolean pushHeld;
    private float pushArms, prevPushArms, pushPhase;
    /** Speed of a push (blocks per tick, for an ordinary car) and the weight (kg) over which nobody can push. */
    public static final float PUSH_SPEED = 0.05f, PUSH_MAX_WEIGHT = 9000;
    /** How far behind a car (blocks) the player can push it. */
    public static final float PUSH_REACH = 1.3f;

    /** When the player got in (the key hints show for a few seconds). */
    public long enteredTick = Long.MIN_VALUE;
    private float savedEye = derpcraft.game.Player.EYE;
    private final Random random = new Random();
    /** Actors a scene take put into a vehicle this tick (snapped to their seats after all takes played). */
    private final List<Object[]> replayed = new ArrayList<>();

    public Vehicles(Session s, Path worldDir) {
        this.s = s;
        this.file = worldDir.resolve("vehicles.txt");
        load();
        current = this;
    }

    public List<Vehicle> list() { return list; }

    /** The way a seat faces in the world: the car's heading plus the seat's own turn. */
    static float seatFacing(Vehicle v, VehicleModel m, int seat, float carYaw) { return carYaw + (seat >= 0 && seat < m.seatYaw.size() ? m.seatYaw.get(seat) : 0); }

    public Vehicle byId(int id) { for (Vehicle v : list) if (v.id == id) return v; return null; }

    // ================================================================== start-up: sample model, item, command
    /** The car item (creative tab Transport): right click on the ground puts a vehicle there. */
    public static void registerItems() {
        Item car = new Item("car") {
            @Override public boolean use(Session s, ItemStack stack, Raycast.Hit hit) {
                if (hit == null || s.vehicles == null) return false;
                String m = resolveModel(null);
                if (m == null) { s.chat("§cNo vehicles: make a folder in game/transport with a model (see game/transport/README.txt), then /vehicle reload"); return true; }
                double x = hit.x + 0.5, y = hit.y + 1, z = hit.z + 0.5;
                if (hit.face != Dir.UP) { x = hit.x + 0.5 + Dir.DX[hit.face]; z = hit.z + 0.5 + Dir.DZ[hit.face]; y = hit.y + Dir.DY[hit.face]; }
                Vehicle v = s.vehicles.spawn(m, x, y, z, s.player.yaw);
                if (v == null) return false;
                if (!s.creative()) s.consumeHeld(1);
                return true;
            }
        };
        car.texture = "items/car";
        car.displayName = "Car";
        car.tab = "transport";
        car.maxStack = 1;
        Items.register(car);
    }

    /** The vehicle kind to use: the named one (or the first whose name contains it), the last used, or the first that loads. */
    public static String resolveModel(String want) {
        if (want != null && !want.isBlank()) {
            VehicleType t = Transports.get(want.trim());
            if (t != null) return t.name;
            for (String n : Transports.names()) if (n.toLowerCase(Locale.ROOT).contains(want.trim().toLowerCase(Locale.ROOT))) return n;
            return null;
        }
        if (defaultModel != null && Transports.get(defaultModel) != null && Transports.get(defaultModel).vm() != null) return Transports.get(defaultModel).name;
        for (String n : vehicleModels()) return n;
        return null;
    }

    /** Vehicle kinds (folders in game/transport) that load. */
    public static List<String> vehicleModels() {
        List<String> out = new ArrayList<>();
        for (String n : Transports.names()) if (Transports.get(n).vm() != null) out.add(n);
        return out;
    }

    public static void registerCommand(derpcraft.api.ModContext c) {
        c.addCommand("vehicle", "[name] | spawn [name] | model <name> | models | reload | list | remove [all] | info | edit | debug | explode | repair", (g, a) -> {
            Session s = (Session) g;
            Vehicles vs = s.vehicles;
            String sub = a.length == 0 ? "spawn" : a[0].toLowerCase(Locale.ROOT);
            String rest = a.length > 1 ? String.join(" ", Arrays.copyOfRange(a, 1, a.length)) : "";
            switch (sub) {
                case "models" -> {
                    if (Transports.names().isEmpty()) { s.chat("§eNo vehicles in game/transport (one folder per vehicle, see README.txt there)"); return; }
                    for (String n : Transports.names()) {
                        VehicleType t = Transports.get(n);
                        VehicleModel vm = t.vm();
                        s.chat(vm == null ? "§c" + n + ": " + t.error
                                : String.format(Locale.ROOT, "§f%s §7(%s, %.0f km/h, %d sounds%s)", t.displayName == null || t.displayName.equalsIgnoreCase(n) ? n : n + " \"" + t.displayName + "\"", t.modelFile.getFileName(), VehicleType.toKmh(t.maxSpeed), t.sounds.size(),
                                t.warnings.isEmpty() ? "" : ", §e" + String.join("; ", t.warnings) + "§7"));
                    }
                }
                case "repair" -> {
                    Vehicle v = vs.playerVehicle != null ? vs.playerVehicle : vs.nearest(s.player.x, s.player.z, 16);
                    if (v == null) throw new IllegalArgumentException("No vehicle within 16 blocks");
                    v.repair();
                    vs.save();
                    s.chat("Vehicle #" + v.id + " repaired");
                }
                case "explode" -> { if (!vs.explodeKey()) throw new IllegalArgumentException("No vehicle within 8 blocks"); }
                case "debug" -> {
                    Vehicle v = vs.playerVehicle != null ? vs.playerVehicle : vs.nearest(s.player.x, s.player.z, 16);
                    if (v == null) throw new IllegalArgumentException("No vehicle");
                    s.chat(vs.shakeReport(v));
                }
                case "edit", "editor" -> s.game.setScreen(new derpcraft.gui.VehicleEditor(s.game, s));
                case "reload" -> {
                    Transports.rescan();
                    s.chat("Vehicles reloaded: " + (vehicleModels().isEmpty() ? "none" : String.join(", ", vehicleModels())) + " (/vehicle models: details)");
                }
                case "model" -> {
                    String m = resolveModel(rest);
                    if (m == null) throw new IllegalArgumentException("No vehicle like " + rest + " (/vehicle models)");
                    defaultModel = m;
                    s.chat("Car item and /vehicle now use " + m);
                }
                case "list" -> {
                    if (vs.list.isEmpty()) { s.chat("No vehicles in this world"); return; }
                    for (Vehicle v : vs.list) s.chat(String.format(Locale.ROOT, "#%d %s at %.0f %.0f %.0f", v.id, v.model, v.x, v.y, v.z));
                }
                case "remove" -> {
                    if (rest.equalsIgnoreCase("all")) { int n = vs.list.size(); for (Vehicle v : new ArrayList<>(vs.list)) vs.remove(v, false); s.chat("Removed " + n + " vehicles"); return; }
                    Vehicle v = vs.nearest(s.player.x, s.player.z, 16);
                    if (v == null) throw new IllegalArgumentException("No vehicle within 16 blocks");
                    vs.remove(v, false);
                    s.chat("Removed vehicle #" + v.id);
                }
                case "info" -> {
                    Vehicle v = vs.nearest(s.player.x, s.player.z, 16);
                    VehicleModel m = v == null ? null : v.vm();
                    if (m == null) throw new IllegalArgumentException("No vehicle within 16 blocks");
                    s.chat(String.format(Locale.ROOT, "#%d %s: %d wheels, %d doors/lids, %d seats (driver %d), %d block places, steering %s, %.0f km/h%s",
                            v.id, v.model, m.wheels.size(), m.openables.size(), m.seats.size(), m.driverSeat + 1, m.slots.size(), m.steering != null ? "yes" : "no", VehicleType.toKmh(v.type().maxSpeed),
                            m.problem != null ? ", §c" + m.problem : ""));
                }
                default -> {
                    String want = sub.equals("spawn") ? rest : String.join(" ", a);
                    String m = resolveModel(want);
                    if (m == null) throw new IllegalArgumentException(want.isBlank() ? "No vehicles in game/transport (see README.txt there), then /vehicle reload" : "No vehicle like " + want + " (/vehicle models)");
                    double yr = Math.toRadians(s.player.yaw);
                    double x = s.player.x - Math.sin(yr) * 4, z = s.player.z + Math.cos(yr) * 4;
                    double y = Vehicle.groundAt(s.world, x, z, s.player.y + 2);
                    Vehicle v = vs.spawn(m, x, y, z, s.player.yaw);
                    if (v != null) s.chat("Vehicle #" + v.id + " (" + m + "). Right click: get in, " + derpcraft.game.Keys.n(derpcraft.game.Keys.SNEAK) + ": get out, left click on a door: open");
                }
            }
        });
    }

    // ================================================================== spawning, removing
    public Vehicle spawn(String model, double x, double y, double z, float yaw) {
        VehicleType ty = Transports.get(model);
        if (ty == null || ty.vm() == null) { s.chat("§c" + model + ": " + (ty == null ? "no such folder in game/transport" : ty.error)); return null; }
        Vehicle v = new Vehicle(nextId++, model);
        v.setPose(x, y, z, yaw);
        v.vm();
        list.add(v);
        defaultModel = model;
        save();
        return v;
    }

    /** Takes a vehicle out of the world: riders get out, carried blocks drop (and in survival the car item). */
    public void remove(Vehicle v, boolean drops) {
        for (int i = 0; i < v.riders.length; i++) if (v.riders[i] != Vehicle.NONE) eject(v, i);
        VehicleModel m = v.vm();
        if (drops && m != null) {
            double[] w = new double[3];
            for (int i = 0; i < m.slots.size(); i++) {
                Vehicle.Slot sl = v.slots[i];
                if (sl.state == 0) continue;
                float[] p = m.slots.get(i);
                v.toWorld(p[0], p[1] + 0.5, p[2], w);
                dropSlot(sl, w[0], w[1], w[2], true);
            }
            s.world.dropItem(v.x, v.y + 1, v.z, new ItemStack(Items.get("car")));
        }
        list.remove(v);
        save();
    }

    /** The vehicle the editor (Alt + End) works on: the one the player rides, the one under the crosshair, else the nearest within 16 blocks. */
    public Vehicle editTarget() {
        if (playerVehicle != null) return playerVehicle;
        if (s.targetVehicle != null && s.targetVehicle.v != null) return s.targetVehicle.v;
        return nearest(s.player.x, s.player.z, 16);
    }

    public Vehicle nearest(double x, double z, double max) {
        Vehicle best = null;
        double bd = max * max;
        for (Vehicle v : list) { double d = v.distSq(x, z); if (d < bd) { bd = d; best = v; } }
        return best;
    }

    // ================================================================== ticking
    public void tick() {
        World w = s.world;
        Vehicle pushed = pushHeld ? pushTarget() : null;
        prevPushArms = pushArms;
        pushArms += ((pushed != null ? 1 : 0) - pushArms) * 0.3f;
        if (pushArms < 0.01f && pushed == null) pushArms = 0;
        for (Vehicle v : list) {
            v.push = v == pushed ? pushSpeed(v) : 0;
            VehicleModel dm = v.vm();
            if (dm != null && v.hasDriver() && v.riders[dm.driverSeat] >= 0) v.lightsOn = night();       // NPC drivers switch the lights on at night
            VehicleModel m = v.vm();
            Chunk c = w.chunkAt((int) Math.floor(v.x), (int) Math.floor(v.z));
            if (c == null || c.state < Chunk.STATE_LIT) {     // not loaded: frozen (riders stay seated)
                v.px = v.x; v.py = v.y; v.pz = v.z; v.prevYaw = v.yaw; v.prevPitch = v.pitch; v.prevRoll = v.roll; v.prevSteer = v.steer; v.prevSpin = v.spin;
                placeRiders(v, m);
                continue;
            }
            checkRiders(v);
            boolean replayed = v.replayTick >= w.gameTime - 1;
            v.tick(w, replayed);
            v.throttle = 0; v.steerIn = 0; v.handbrake = false; v.speedLimit = Vehicle.NO_LIMIT;
            v.push = 0;
            if (m == null) continue;
            if (v == pushed) holdPusher(v, m);
            rollOverHurt(v);
            smash(v, m);
            if (v.wrecked) wreckEffects(v, m);
            if (v == playerVehicle || v == histOf) record(v);
            tireEffects(v, m);
            placeRiders(v, m);
            tickSlots(v, m);
            if (v.x != v.px || v.z != v.pz || v.y != v.py || v.yaw != v.prevYaw) carryAndPush(v, m);
        }
    }

    /** Riders that left (NPCs switched off, changed behaviour, dead) free their seats. */
    private void checkRiders(Vehicle v) {
        for (int i = 0; i < v.riders.length; i++) {
            int r = v.riders[i];
            if (r == Vehicle.PLAYER) { if (playerVehicle != v || playerSeat != i) v.riders[i] = Vehicle.NONE; continue; }
            if (r < 0) continue;
            Actor a = s.npcs.byId(r);
            boolean companion = a != null && a.behavior == Actor.Behavior.FOLLOW && a.rideAlong == v.id;
            if (a == null || !s.npcs.inWorld(a) || a.dead() || a.mode() != Actor.Mode.AI || !companion && (a.behavior != Actor.Behavior.DRIVE || a.vehicleId != v.id)) {
                if (a != null && a.rideAlong == v.id) a.rideAlong = -1;
                eject(v, i);
            }
        }
    }

    /** Puts everyone in their seats (after the vehicle moved). */
    private void placeRiders(Vehicle v, VehicleModel m) {
        if (m == null) return;
        double[] w = new double[3];
        for (int i = 0; i < v.riders.length && i < m.seats.size(); i++) {
            int r = v.riders[i];
            if (r == Vehicle.NONE) continue;
            float[] st = m.seats.get(i);
            v.bodyPointAt(m, st[0], st[1] + SIT_DROP, st[2], 1, w);
            if (r == Vehicle.PLAYER) {
                Player p = s.player;
                p.px = p.x; p.py = p.y; p.pz = p.z;
                p.x = w[0]; p.y = w[1]; p.z = w[2];
                p.mx = p.my = p.mz = 0;
                p.onGround = true; p.fallDistance = 0; p.flying = false; p.sprinting = false;
                p.yaw += Vehicle.wrap(v.yaw - v.prevYaw);
                sway(v);
                p.prevEyeHeight = p.eyeHeight;
                p.eyeHeight += (SIT_EYE - p.eyeHeight) * 0.4f;
            } else {
                Actor a = s.npcs.byId(r);
                if (a != null) a.sitIn(w[0], w[1], w[2], seatFacing(v, m, i, v.yaw));
            }
        }
    }

    /** Block entities in the block places keep working (furnaces burn, mod machines run) while carried. */
    private void tickSlots(Vehicle v, VehicleModel m) {
        for (int i = 0; i < m.slots.size(); i++) {
            Vehicle.Slot sl = v.slots[i];
            if (sl.state == 0 || sl.be == null || !sl.be.ticks()) continue;
            float[] p = m.slots.get(i);
            double[] w = new double[3];
            v.toWorld(p[0], p[1] + 0.5, p[2], w);
            sl.be.x = (int) Math.floor(w[0]); sl.be.y = (int) Math.floor(w[1]); sl.be.z = (int) Math.floor(w[2]);
            sl.be.world = null;                    // not in the world: furnaces don't swap blocks, we do it here
            try { sl.be.tick(); } catch (Throwable t) { t.printStackTrace(); }
            Block b = Blocks.byId(sl.state >> 4);
            if (sl.be instanceof derpcraft.world.be.FurnaceEntity f && (b.name.equals("furnace") || b.name.equals("lit_furnace"))) {
                Block want = Blocks.byName(f.burnTime > 0 ? "lit_furnace" : "furnace");
                if (want != null) sl.state = (want.id << 4) | (sl.state & 15);
            } else if (sl.be instanceof derpcraft.world.be.ModBlockEntity me && b instanceof derpcraft.block.ModBlock) {
                derpcraft.block.ModBlock base = me.spec.base(), lit = base.litVariant;
                if (lit != null) sl.state = ((me.working() ? lit : base).id << 4) | (sl.state & 15);
            }
        }
    }

    /** People standing on the car ride along; people in its way are pushed aside. */
    private void carryAndPush(Vehicle v, VehicleModel m) {
        Player p = s.player;
        double jx = v.x - v.px, jz = v.z - v.pz;
        if (playerVehicle != v && !s.spectator() && jx * jx + jz * jz <= TELEPORT * TELEPORT) carryOrPush(v, m, p, true);
        moved.add(v);
    }

    /** Vehicles that moved this tick (NPCs on them are carried after the NPCs' own tick). */
    private final List<Vehicle> moved = new ArrayList<>();

    /**
     * After the NPCs ticked: NPCs standing on a vehicle that moved ride along, NPCs in its way are pushed aside. Done after
     * their own step so their in-between position (for drawing) includes the ride and they glide instead of jumping.
     */
    public void afterNpcs() {
        tickBoards();
        for (Vehicle v : moved) {
            VehicleModel m = v.vm();
            if (m == null) continue;
            for (Actor a : s.npcs.actors) {
                if (a.seatedVehicleId() == v.id && v.seatOf(a.id) >= 0 || seatedAnywhere(a) || takeSeats.containsKey(a.id)) continue;
                double jx = v.x - v.px, jz = v.z - v.pz;
                if (jx * jx + jz * jz > TELEPORT * TELEPORT) continue;      // put somewhere by a scene (start, reset): not a ride
                if (a.dead()) { pushBody(v, m, a); continue; }
                if (!a.inGrace() && runOver(v, m, a)) continue;                  // hit hard: thrown, no gentle push
                if (a.mode() == Actor.Mode.AI) carryOrPush(v, m, a.body(), false);
            }
        }
        moved.clear();
    }

    /** A car that moved more than this in one tick was put there (a scene starting again), it did not drive into anyone. */
    static final double TELEPORT = 2.5;

    /** Below this speed (blocks per tick: about 9 km/h) a car only pushes people aside. */
    public static float HIT_SPEED = 0.12f;

    /** Where a point is in the car's footprint (local x, z in its current pose; yaw only), or null if outside it (grown by 'grow'). */
    private static double[] local(Vehicle v, VehicleModel m, double x, double z, double grow) {
        double a = Math.PI - Math.toRadians(v.yaw), c = Math.cos(a), sn = Math.sin(a);
        double ox = x - v.x, oz = z - v.z;
        double lx = ox * c - oz * sn, lz = ox * sn + oz * c;
        if (lx < m.minX - grow || lx > m.maxX + grow || lz < m.minZ - grow || lz > m.maxZ + grow) return null;
        return new double[]{lx, lz};
    }

    /**
     * A moving car runs into an NPC or an actor (actors in a take too: the hit knocks them out of it): damage from the
     * speed (30 km/h about 12, 50 km/h about 23: dead), the body thrown along the car's way and up; the car loses a
     * little speed. True if it hit.
     */
    private boolean runOver(Vehicle v, VehicleModel m, Actor a) {
        double vx = v.x - v.px, vz = v.z - v.pz, sp = Math.sqrt(vx * vx + vz * vz);
        if (sp < HIT_SPEED) return false;
        Player b = a.body();
        double[] l = local(v, m, b.x, b.z, b.width / 2);
        if (l == null) return false;
        float top = topAt(m, l[0], l[1]);
        if (top <= 0 || b.y >= v.y + top - 0.05 || b.y + b.height <= v.y + 0.05) return false;     // on the roof / under it / beside it
        float kmh = (float) (sp * 72);
        float heavy = Math.max(0.5f, Math.min(2f, v.type().weight / VehicleType.REF_WEIGHT));       // a heavy car hits harder and loses less speed
        float dmg = Math.max(1, (kmh - 8) * 0.55f * heavy);
        if (!a.hitByVehicle(s.npcs, dmg, vx, vz, sp)) return true;
        v.speed *= 1 - 0.1f / heavy;
        v.crashed = Math.max(v.crashed, (float) sp * 0.8f);
        return true;
    }

    /** A dead body in the car's way: the parts it touches are pushed along (run over, dragged aside). */
    private void pushBody(Vehicle v, VehicleModel m, Actor a) {
        if (!a.lyingBody()) return;
        double vx = v.x - v.px, vz = v.z - v.pz;
        if (vx * vx + vz * vz < 1e-4) return;
        double[] l = local(v, m, a.x(), a.z(), 1.2);
        if (l == null) return;
        a.pushBodyBy(p -> {
            double[] q = local(v, m, p[0], p[2], 0.15);
            if (q == null) return false;
            float top = topAt(m, q[0], q[1]);
            return top > 0 && p[1] < v.y + top && p[1] > v.y - 0.2;
        }, vx * 1.05, 0.04, vz * 1.05);
    }

    private boolean seatedAnywhere(Actor a) {
        Vehicle v = byId(a.seatedVehicleId());
        return v != null && v.seatOf(a.id) >= 0;
    }

    private void carryOrPush(Vehicle v, VehicleModel m, Player b, boolean isPlayer) {
        double dx = b.x - v.x, dz = b.z - v.z;
        double r = Math.hypot(Math.max(-m.minX, m.maxX), Math.max(-m.minZ, m.maxZ)) + 1;
        if (dx * dx + dz * dz > r * r) return;
        // local position in the old pose (yaw only)
        double a0 = Math.PI - Math.toRadians(v.prevYaw), c0 = Math.cos(a0), s0 = Math.sin(a0);
        double ox = b.x - v.px, oz = b.z - v.pz;
        double lx = ox * c0 - oz * s0, lz = ox * s0 + oz * c0;
        double hw = b.width / 2;
        if (lx < m.minX - hw || lx > m.maxX + hw || lz < m.minZ - hw || lz > m.maxZ + hw) return;
        float top = topAt(m, lx, lz);
        double a1 = Math.PI - Math.toRadians(v.yaw), c1 = Math.cos(a1), s1 = Math.sin(a1);
        if (top > 0 && b.onGround && Math.abs(b.y - (v.py + top)) < 0.15) {
            // standing on it: moves and turns with it
            b.x = v.x + lx * c1 + lz * s1; b.z = v.z - lx * s1 + lz * c1;
            b.y += v.y - v.py;
            if (isPlayer) b.yaw += Vehicle.wrap(v.yaw - v.prevYaw);
            return;
        }
        if (top <= 0 || b.y >= v.y + top - 0.05 || b.y + b.height <= v.y + 0.05) return;
        // pushed out of the side it is nearest to
        double[] d = {lx - (m.minX - hw), (m.maxX + hw) - lx, lz - (m.minZ - hw), (m.maxZ + hw) - lz};
        int k = 0;
        for (int i = 1; i < 4; i++) if (d[i] < d[k]) k = i;
        double nlx = lx, nlz = lz;
        switch (k) { case 0 -> nlx = m.minX - hw - 0.01; case 1 -> nlx = m.maxX + hw + 0.01; case 2 -> nlz = m.minZ - hw - 0.01; default -> nlz = m.maxZ + hw + 0.01; }
        double nx = v.x + nlx * c1 + nlz * s1, nz = v.z - nlx * s1 + nlz * c1;
        b.mx += (nx - b.x) * 0.5; b.mz += (nz - b.z) * 0.5;
        b.x = nx; b.z = nz;
    }

    /** Height of the car's top above its ground at a local point, or 0 outside. */
    static float topAt(VehicleModel m, double lx, double lz) {
        float t = 0;
        for (float[] sg : m.segments) if (lz >= sg[0] - 0.05 && lz <= sg[1] + 0.05 && lx >= sg[2] - 0.05 && lx <= sg[3] + 0.05) t = Math.max(t, sg[4]);
        return t;
    }

    // ================================================================== a wreck: the explosion (Ctrl + End), fire and smoke
    /** Ticks the flames burn after the explosion and the smoke goes on (about 40 seconds and four minutes). */
    public static final int FIRE_TICKS = 800, SMOKE_TICKS = 4800;

    /**
     * Ctrl + End: blows up the vehicle the player rides, looks at or stands next to (within 8 blocks). False when there is none (the key
     * is then the selfie key as before).
     */
    public boolean explodeKey() {
        Vehicle v = playerVehicle != null ? playerVehicle : s.targetVehicle != null && s.targetVehicle.v != null ? s.targetVehicle.v : nearest(s.player.x, s.player.z, 8);
        if (v == null || s.spectator()) return false;
        if (v.wrecked) { s.chat("§7It is already burnt out (/vehicle repair)"); return true; }
        explode(v, false);
        return true;
    }

    /**
     * The explosion: everybody inside is thrown out, the blast hurts what is near (not the blocks), the car turns black, burns and then
     * smokes for minutes, its parts are bent. 'visualOnly' (a scene take plays it): fire and sound, no damage.
     */
    public void explode(Vehicle v, boolean visualOnly) {
        VehicleModel m = v.vm();
        if (m == null || v.wrecked) return;
        if (!visualOnly) {
            hurtRiders(v, 7f);                    // those inside get hurt (and are safe from the blast itself for a moment: the hit makes them invulnerable)
            for (int i = 0; i < v.riders.length; i++) if (v.riders[i] != Vehicle.NONE) eject(v, i);
        }
        v.wreck(random.nextLong());
        v.speed *= 0.6f; v.lat *= 0.6f;
        double[] c = new double[3];
        v.bodyPointAt(m, (m.minX + m.maxX) / 2, m.maxY * 0.5f, (m.minZ + m.maxZ) / 2, 1, c);
        if (visualOnly) { if (s.game != null && s.game.entities != null) s.game.entities.explosion(c[0], c[1], c[2], 3f); }
        else derpcraft.entity.Explosions.explode(s, c[0], c[1], c[2], 2f, false);
        save();
    }

    /** Fire (the first 40 seconds, at the engine and the cabin) and smoke (four minutes, thinning out) of a wreck, every tick. */
    private void wreckEffects(Vehicle v, VehicleModel m) {
        derpcraft.render.EntityRenderer er = s.game == null ? null : s.game.entities;
        if (er == null) return;
        int age = v.wreckAge;
        if (age > SMOKE_TICKS) return;
        double[] w = new double[3];
        float cz = m.minZ + (m.maxZ - m.minZ) * 0.22f, cy = m.maxY * 0.62f;
        if (age < FIRE_TICKS) {
            float fire = 1 - age / (float) FIRE_TICKS;
            int n = random.nextFloat() < 0.35f + 0.65f * fire ? 1 + (fire > 0.5f ? 1 : 0) : 0;
            for (int k = 0; k < n; k++) {
                float x = (random.nextFloat() - 0.5f) * (m.maxX - m.minX) * 0.7f, z = random.nextBoolean() ? cz + (random.nextFloat() - 0.5f) * 0.8f : (m.minZ + m.maxZ) / 2 + (random.nextFloat() - 0.5f) * 1.2f;
                v.bodyPointAt(m, x, cy + random.nextFloat() * 0.2f, z, 1, w);
                er.wreckFlame(w[0], w[1], w[2]);
            }
        }
        // the smoke: thick at first, thinner and thinner (one puff in ever more ticks)
        float dense = 1 - age / (float) SMOKE_TICKS;
        int every = 1 + (int) ((1 - dense) * 6);
        if ((age + v.id) % every == 0) {
            v.bodyPointAt(m, (random.nextFloat() - 0.5f) * 0.8f, cy + 0.2f, cz + (random.nextFloat() - 0.5f) * 0.8f, 1, w);
            er.wreckSmoke(w[0], w[1], w[2], 0.35f + dense * 0.65f);
        }
    }

    /** Wrecks that are still burning (for their light). */
    public List<Vehicle> burning() {
        List<Vehicle> l = new ArrayList<>();
        for (Vehicle v : list) if (v.wrecked && v.wreckAge < FIRE_TICKS) l.add(v);
        return l;
    }

    // ================================================================== the editor: markers, block places
    /** A box in a vehicle's local coordinates drawn over it while the editor is open (seats, block places). */
    public static final class Mark {
        public final float[] min, max;
        public final float r, g, b;
        public Mark(float[] min, float[] max, float r, float g, float b) { this.min = min; this.max = max; this.r = r; this.g = g; this.b = b; }
    }

    /** The vehicle the editor marks and the marks (set by the editor each time it lays out). */
    public Vehicle markVehicle;
    public final List<Mark> marks = new ArrayList<>();

    /** A block place of this kind was removed (index): what it carried drops, the places after it move up. */
    public void blockPlaceRemoved(String model, int index) {
        for (Vehicle v : list) {
            if (!v.model.equalsIgnoreCase(model) || index >= v.slots.length) continue;
            Vehicle.Slot sl = v.slots[index];
            if (sl.state != 0) dropSlot(sl, v.x, v.y + 1, v.z, true);
            for (int i = index; i + 1 < v.slots.length; i++) { Vehicle.Slot t = v.slots[i]; v.slots[i] = v.slots[i + 1]; v.slots[i + 1] = t; }
        }
        save();
    }

    // ================================================================== pushing, rolling over, tyre marks
    /** Alt held (or not) this tick: set by the session. */
    public void pushInput(boolean held) { pushHeld = held && playerVehicle == null; }

    // ---- /vehicle debug: how much the body moves from tick to tick (the last 5 seconds of the vehicle that is looked at)
    private final double[][] hist = new double[100][8];
    private int histN;
    private Vehicle histOf;

    private void record(Vehicle v) {
        if (v != histOf) { histOf = v; histN = 0; }
        double[] h = hist[histN++ % hist.length];
        h[0] = v.y; h[1] = v.pitch; h[2] = v.roll; h[3] = v.yaw; h[4] = v.susPitch[0]; h[5] = v.susRoll[0]; h[6] = v.susHeave[0]; h[7] = v.speed;
    }

    /** Biggest change of height / nose / lean / heading / body springs / speed from one tick to the next. */
    public String shakeReport(Vehicle v) {
        if (v != histOf || histN < 10) return "Drive or stand by the vehicle for a few seconds, then /vehicle debug again";
        int n = Math.min(histN, hist.length);
        double[] mx = new double[8];
        double sw = 0;
        for (int i = 1; i < n; i++) {
            double[] a = hist[(histN - i - 1) % hist.length], b = hist[(histN - i) % hist.length];
            for (int k = 0; k < 8; k++) mx[k] = Math.max(mx[k], Math.abs(b[k] - a[k]));
        }
        VehicleType t = v.type();
        return String.format(Locale.ROOT, "#%d %s on %s, %.0f km/h; per tick max: height %.3f, nose %.2f, lean %.2f, yaw %.2f, springs %.2f/%.2f/%.3f, speed %.3f; stiffness %.2f damping %.2f",
                v.id, v.model, Surfaces.NAMES[v.surface], v.kmh(), mx[0], mx[1], mx[2], mx[3], mx[4], mx[5], mx[6], mx[7], t.springStiffness, t.springDamping);
    }

    /** The headlights of the vehicle the player rides on / off (key N). */
    public void toggleLights() {
        Vehicle v = playerVehicle;
        if (v == null) return;
        v.lightsOn = !v.lightsOn;
        s.chat("Lights " + (v.lightsOn ? "on" : "off"));
    }

    private boolean night() { long t = s.world.info.time % 24000; return t >= 13000 && t <= 23000; }

    /** The cinematic vehicle camera is on (riding, Right Alt + F8). */
    public boolean cinemaActive() { return playerVehicle != null && camera.active(); }

    /** The roll of the view while the car rolls over (degrees, 0 normally). */
    public float cameraFlip(float partial) { return playerVehicle == null ? 0 : -playerVehicle.flipAt(partial); }

    /** A parked car the player stands behind and could push (for the hint), or null. */
    public Vehicle pushable() { return playerVehicle == null && !pushHeld ? pushTarget() : null; }

    /** How far the arms are out (0..1) between the ticks, for the player's model and the first person arms. */
    public float pushAmount(float partial) { return prevPushArms + (pushArms - prevPushArms) * partial; }

    /** The rhythm of the pushing (radians), for the first person arms. */
    public float pushPhase() { return pushPhase; }

    /** The empty car the player stands behind (close enough, in line with it) and can push, or null. */
    private Vehicle pushTarget() {
        Player p = s.player;
        if (s.spectator() || s.stats.dead || s.cam.detached()) return null;
        Vehicle best = null;
        double bd = Double.MAX_VALUE;
        for (Vehicle v : list) {
            VehicleModel m = v.vm();
            if (m == null || v.hasDriver() || v.flipping() || !v.onGround || Math.abs(v.speed) > 0.2f || v.type().weight > PUSH_MAX_WEIGHT) continue;
            if (Math.abs(p.y - v.y) > 1.3) continue;
            double a = Math.PI - Math.toRadians(v.yaw), c = Math.cos(a), sn = Math.sin(a);
            double ox = p.x - v.x, oz = p.z - v.z;
            double lx = ox * c - oz * sn, lz = ox * sn + oz * c;
            if (lz < m.maxZ - 0.2 || lz > m.maxZ + PUSH_REACH + p.width / 2 || lx < m.minX - 0.25 || lx > m.maxX + 0.25) continue;
            double d = lz - m.maxZ;
            if (d < bd) { bd = d; best = v; }
        }
        return best;
    }

    /** How fast a person pushes this car: slower the heavier it is (an ordinary car 1200 kg: 3.6 km/h). */
    static float pushSpeed(Vehicle v) { return pushSpeedOf(v.type()); }

    /** The push speed (blocks per tick) for a kind of vehicle. */
    public static float pushSpeedOf(VehicleType t) {
        return PUSH_SPEED * Math.max(0.15f, Math.min(1.5f, VehicleType.REF_WEIGHT / t.weight));
    }

    /** Lets whoever sits in a seat out (the seat is going away). */
    public void ejectSeat(Vehicle v, int seat) { if (seat >= 0 && seat < v.riders.length && v.riders[seat] != Vehicle.NONE) eject(v, seat); }

    /** The pusher stays at the back of the car, hands on it, and walks with it. */
    private void holdPusher(Vehicle v, VehicleModel m) {
        Player p = s.player;
        double a = Math.PI - Math.toRadians(v.yaw), c = Math.cos(a), sn = Math.sin(a);
        double ox = p.x - v.x, oz = p.z - v.z;
        double lx = ox * c - oz * sn;
        lx = Math.max(m.minX + 0.1, Math.min(m.maxX - 0.1, lx));
        double lz = m.maxZ + p.width / 2 + 0.04;
        double nx = v.x + lx * c + lz * sn, nz = v.z - lx * sn + lz * c;
        if (Math.abs(v.speed) > 0.003f) pushPhase += 0.45f + Math.abs(v.speed) * 6;
        // not into a wall behind: only if the spot is free
        if (free(s.world, nx, p.y, nz, p.width, p.height)) { p.x = nx; p.z = nz; p.mx = p.mz = 0; }
    }

    /** The people in a car that rolls over are hurt when it starts to roll and again when it lands (the speed decides). */
    private void rollOverHurt(Vehicle v) {
        float start = v.flipImpact, land = v.flipLanded;
        v.flipImpact = 0; v.flipLanded = 0;
        if (start <= 0 && land <= 0) return;
        float k = v.type().rollDamage;
        if (start > 0) hurtRiders(v, (2f + Math.min(1.4f, start) * 5.5f) * k);
        if (land > 0) hurtRiders(v, (1f + Math.min(1.4f, land) * 3f) * k);
    }

    private void hurtRiders(Vehicle v, float dmg) {
        if (dmg <= 0) return;
        for (int i = 0; i < v.riders.length; i++) {
            int r = v.riders[i];
            if (r == Vehicle.PLAYER) {
                if (!s.survival()) continue;
                s.stats.invulnerable = 0;             // the roll hurts even right after another hit
                s.hurtPlayer(dmg, "vehicle", v.x, v.z);
            } else if (r >= 0) {
                Actor a = s.npcs.byId(r);
                if (a != null) a.hurt(s.npcs, dmg, null, false, null, "vehicle");
            }
        }
    }

    /** A car at speed (30 km/h by default) goes through glass, planks, leaves, fences and trapdoors and breaks them. */
    private void smash(Vehicle v, VehicleModel m) {
        if (Math.abs(v.speed) < v.type().smashSpeed || !v.onGround) return;
        int n = 0;
        for (int[] c : v.smashTargets(s.world, m, Math.max(0.3f, Math.abs(v.speed) + 0.2f))) {
            if (n++ >= 12) break;
            s.harvest(c[0], c[1], c[2], s.survival());
            v.speed *= 0.985f;                       // a little slower for every block
            v.crashed = Math.max(v.crashed, 0.2f);
        }
    }

    /** Tyre marks behind the wheels that slide, and smoke from them. */
    private void tireEffects(Vehicle v, VehicleModel m) {
        derpcraft.render.EntityRenderer er = s.game == null ? null : s.game.entities;
        if (er == null) return;
        boolean sliding = v.slip > 0.2f && v.onGround && !v.flipping() && m.wheels.size() >= 2;
        if (v.skidPrev.length != m.wheels.size()) v.skidPrev = new double[m.wheels.size()][];
        double[] w = new double[3];
        float midZ = (m.frontZ + m.rearZ) / 2;
        for (int i = 0; i < m.wheels.size(); i++) {
            VehicleModel.Part p = m.wheels.get(i);
            boolean rear = p.pz >= midZ - 0.01f;
            if (!sliding || !(rear || v.police > 0 || v.slip > 0.85f)) { v.skidPrev[i] = null; continue; }
            v.toWorld(p.px, 0, p.pz, w);
            double gy = Vehicle.groundAt(s.world, w[0], w[2], v.gy + 0.3);
            if (gy < v.gy - 0.6 || gy > v.gy + 0.6) { v.skidPrev[i] = null; continue; }
            double[] prev = v.skidPrev[i];
            float width = Math.max(0.12f, Math.min(0.4f, (p.x1 - p.x0) * 0.8f));
            if (v.surface == Surfaces.ICE) { v.skidPrev[i] = null; continue; }          // ice: no marks, no smoke
            if (prev != null && (prev[0] - w[0]) * (prev[0] - w[0]) + (prev[2] - w[2]) * (prev[2] - w[2]) < 9)
                er.skidMark(prev[0], prev[1], prev[2], w[0], gy + 0.02, w[2], width, Math.min(1, 0.35f + v.slip * 0.6f) * (v.surface == Surfaces.ROAD ? 1 : v.surface == Surfaces.SAND ? 0.35f : 0.6f), s.world.gameTime);
            v.skidPrev[i] = new double[]{w[0], gy + 0.02, w[2]};
            if (v.slip > 0.5f || (s.world.gameTime & 1) == 0) er.tireSmoke(w[0], gy + 0.08, w[2], v.x - v.px, v.z - v.pz, v.slip, Surfaces.dust(v.surface));
        }
    }

    // ================================================================== the player in a vehicle
    public boolean riding() { return playerVehicle != null; }

    /** The way the player's seat faces now (the car's yaw plus the seat's turn). */
    public float seatFacingNow() {
        VehicleModel m = playerVehicle == null ? null : playerVehicle.vm();
        return m == null ? 0 : seatFacing(playerVehicle, m, playerSeat, playerVehicle.yaw);
    }

    public boolean playerDrives() {
        VehicleModel m = playerVehicle == null ? null : playerVehicle.vm();
        return m != null && playerSeat == m.driverSeat;
    }

    /** Keys of this tick while riding: the driver steers (W/S gas and brake / reverse, A/D steering, Space handbrake), Shift gets out. */
    public void playerInput(Player.Input in, boolean active) {
        Vehicle v = playerVehicle;
        if (v == null) return;
        boolean sneak = active && in.sneak;
        if (sneak && !prevSneak && !v.flipping()) { prevSneak = true; exitPlayer(); return; }
        prevSneak = sneak;
        if (!active || !playerDrives()) return;
        v.throttle = Math.max(-1, Math.min(1, in.forward));
        v.steerIn = Math.max(-1, Math.min(1, in.strafe));
        v.handbrake = in.jump;
    }

    /**
     * The rider's head on the car's suspension: the view follows the car's pitch and roll a little late (with a small
     * overshoot), sinks and bounces after bumps and steps, leans back when speeding up, forward when braking and out of turns.
     */
    private void sway(Vehicle v) {
        double seatY = s.player.y;
        if (swLastY == 0 || Math.abs(seatY - swLastY) > 3) { swLastY = seatY; swY[0] = swY[1] = 0; }
        swAccel += (v.speed - swLastSpeed - swAccel) * 0.25f;     // smoothed: a jerky pedal does not shake the head
        float accel = swAccel;
        swLastSpeed = v.speed;
        float dyaw = Vehicle.wrap(v.yaw - v.prevYaw);
        float pitchT = -v.pitch * 0.85f + Math.max(-4, Math.min(4, -accel * 160));
        float rollT = -v.roll * 0.85f + Math.max(-5, Math.min(5, -dyaw * Math.abs(v.speed) * 2.5f));
        spring(swPitch, pitchT, 0.22f, 0.62f);
        spring(swRoll, rollT, 0.22f, 0.62f);
        // vertical: the head lags behind the seat and catches up with a soft bounce
        // the head follows the seat's vertical acceleration (a bump, a landing), not a steady climb
        float sdy = (float) (seatY - swLastY), sacc = sdy - swVy;
        swVy = sdy;
        swY[0] -= Math.max(-0.5f, Math.min(0.5f, sacc)) * 0.8f;
        swLastY = seatY;
        spring(swY, 0, 0.3f, 0.55f);
        swY[0] = Math.max(-0.35f, Math.min(0.35f, swY[0]));
    }

    private static void spring(float[] s, float target, float k, float damp) {
        s[2] = s[0];
        s[1] = (s[1] + (target - s[0]) * k) * damp;
        s[0] += s[1];
    }

    /** Camera sway while riding, between ticks: {height offset, pitch offset, roll offset (degrees)}, or null. */
    public float[] cameraSway(float partial) {
        if (playerVehicle == null) return null;
        return new float[]{swY[2] + (swY[0] - swY[2]) * partial, swPitch[2] + (swPitch[0] - swPitch[2]) * partial, swRoll[2] + (swRoll[0] - swRoll[2]) * partial};
    }

    /** Gets into the free seat nearest to a local point (the driver's seat when the point is null). */
    public boolean enterPlayer(Vehicle v, double[] local) {
        VehicleModel m = v.vm();
        if (m == null || s.spectator()) return false;
        if (v.wrecked) return false;
        int seat = freeSeatNear(v, m, local, true);
        if (seat < 0) {
            if (freeSeatNear(v, m, local, false) < 0) s.chat("§eNo free seat");
            return false;
        }
        if (playerVehicle != null) {
            if (playerVehicle.riders.length > playerSeat && playerSeat >= 0) playerVehicle.riders[playerSeat] = Vehicle.NONE;
        } else savedEye = s.player.eyeHeight;
        playerVehicle = v;
        playerSeat = seat;
        enteredTick = s.ticks;
        // the sway starts from rest, already tilted like the car
        swPitch[0] = swPitch[2] = -v.pitch * 0.85f; swRoll[0] = swRoll[2] = -v.roll * 0.85f; swPitch[1] = swRoll[1] = 0;
        swY[0] = swY[1] = swY[2] = 0; swLastY = 0; swLastSpeed = v.speed; swVy = 0;
        v.riders[seat] = Vehicle.PLAYER;
        if (seat == m.driverSeat) v.driverKilled = false;
        Player p = s.player;
        p.stance = derpcraft.game.Stance.STAND;
        p.flying = false;
        prevSneak = true;            // a held Shift does not throw the player out at once
        float look = m.seatYaw.get(seat) != 0 ? Vehicle.wrap(v.yaw + m.seatYaw.get(seat)) : p.yaw;       // a turned seat: you look the way it faces
        placeRiders(v, m);
        p.yaw = look;
        p.px = p.x; p.py = p.y; p.pz = p.z;
        return true;
    }

    /** The free seat nearest to a local point; with 'doorOpen' only seats whose door is open (or that have no door). */
    private int freeSeatNear(Vehicle v, VehicleModel m, double[] local, boolean doorOpen) {
        int best = -1;
        double bd = Double.MAX_VALUE;
        for (int i = 0; i < m.seats.size(); i++) {
            if (i < v.riders.length && v.riders[i] != Vehicle.NONE && !(v.riders[i] == Vehicle.PLAYER && playerVehicle == v)) continue;
            if (doorOpen && !v.doorOpenFor(m, i)) continue;
            float[] st = m.seats.get(i);
            double d = local == null ? (i == m.driverSeat ? 0 : 1 + i) : (st[0] - local[0]) * (st[0] - local[0]) + (st[2] - local[2]) * (st[2] - local[2]);
            if (d < bd) { bd = d; best = i; }
        }
        return best;
    }

    public void exitPlayer() {
        Vehicle v = playerVehicle;
        if (v == null) return;
        if (playerSeat >= 0 && playerSeat < v.riders.length) v.riders[playerSeat] = Vehicle.NONE;
        double[] out = exitPoint(v, playerSeat, s.player.width, 1.8f);
        playerVehicle = null;
        playerSeat = -1;
        camera.off();
        Player p = s.player;
        p.setPos(out[0], out[1], out[2]);
        p.eyeHeight = p.prevEyeHeight = savedEye > 0.5f ? savedEye : Player.EYE;
        s.stats.safeUntil = s.ticks + EXIT_SAFE_TICKS;     // getting out of a moving / rolled car does not hurt for a second
    }

    /** Ticks without any damage after getting out of a vehicle (the player and NPCs). */
    public static final int EXIT_SAFE_TICKS = 20;

    /** A free spot next to a seat (its side of the car, then the other side, then the roof): {x, y, z}. */
    double[] exitPoint(Vehicle v, int seat, float width, float height) {
        VehicleModel m = v.vm();
        World w = s.world;
        double[] o = new double[3];
        if (m == null) return new double[]{v.x, v.y + 1, v.z, 0};
        float[] st = seat >= 0 && seat < m.seats.size() ? m.seats.get(seat) : new float[]{0, 0, 0};
        float side = st[0] < 0 ? -1 : 1;
        for (int tryNo = 0; tryNo < 4; tryNo++) {
            double lx, lz = st[2];
            if (tryNo == 0) lx = side < 0 ? m.minX - width / 2 - 0.15 : m.maxX + width / 2 + 0.15;
            else if (tryNo == 1) lx = side < 0 ? m.maxX + width / 2 + 0.15 : m.minX - width / 2 - 0.15;
            else if (tryNo == 2) { lx = 0; lz = m.maxZ + width / 2 + 0.2; }
            else { lx = 0; lz = m.minZ - width / 2 - 0.2; }
            v.toWorld(lx, 0, lz, o);
            double gy = Vehicle.groundAt(w, o[0], o[2], v.y + 0.5);
            if (gy < v.y - 3) gy = v.y;
            if (free(w, o[0], gy, o[2], width, height)) return new double[]{o[0], gy, o[2], 0};
        }
        return new double[]{v.x, v.y + m.maxY + 0.05, v.z, 0};
    }

    private static boolean free(World w, double x, double y, double z, float width, float height) {
        double r = width / 2;
        for (int bx = (int) Math.floor(x - r); bx <= (int) Math.floor(x + r); bx++)
            for (int bz = (int) Math.floor(z - r); bz <= (int) Math.floor(z + r); bz++)
                for (int by = (int) Math.floor(y + 0.01); by <= (int) Math.floor(y + height - 0.01); by++) {
                    int st = w.getState(bx, by, bz);
                    if (st == 0) continue;
                    Block b = Blocks.byId(st >> 4);
                    if (!b.solid) continue;
                    for (float[] cb : b.collisionShape(st & 15, 0))
                        if (bx + cb[3] > x - r && bx + cb[0] < x + r && by + cb[4] > y + 0.01 && by + cb[1] < y + height && bz + cb[5] > z - r && bz + cb[2] < z + r) return false;
                }
        return true;
    }

    /** Lets an NPC out of a seat (beside the car). */
    void eject(Vehicle v, int seat) {
        int r = v.riders[seat];
        v.riders[seat] = Vehicle.NONE;
        if (r == Vehicle.PLAYER) { if (playerVehicle == v) exitPlayer(); return; }
        Actor a = r >= 0 ? s.npcs.byId(r) : null;
        if (a == null) return;
        double[] o = exitPoint(v, seat, 0.6f, 1.8f);
        a.place(o[0], o[1], o[2], v.yaw);
        a.setHome();
        a.grace(80);
        a.exitGrace(EXIT_SAFE_TICKS);
        if (a.rideAlong == v.id) a.rideAlong = -1;
    }

    // ================================================================== NPCs
    /**
     * DRIVE behaviour of an NPC (called from its AI each tick). Walks to its vehicle and gets in; the driver follows the
     * NPC's patrol points (or the player when there are none). Returns false when the vehicle is gone.
     */
    public boolean npcDrive(Actor a) {
        Vehicle v = byId(a.vehicleId);
        VehicleModel m = v == null ? null : v.vm();
        if (m == null || v.wrecked) return false;
        int seat = v.seatOf(a.id);
        if (seat < 0) {
            double d2 = v.distSq(a.x(), a.z());
            if (d2 > 3.6 * 3.6 && d2 < 64 * 64) return a.walkTo(v.x, v.y, v.z, d2 > 10 * 10);
            int want = a.vehicleSeat >= 0 && a.vehicleSeat < m.seats.size() ? a.vehicleSeat : m.driverSeat;
            if (v.riders[want] != Vehicle.NONE) {          // taken: any free seat
                want = -1;
                for (int i = 0; i < m.seats.size(); i++) if (v.riders[i] == Vehicle.NONE) { want = i; break; }
                if (want < 0) return a.walkTo(a.x(), a.y(), a.z(), false);
            }
            int d = want < m.seatDoor.length ? m.seatDoor[want] : -1;
            if (d >= 0 && d < v.open.length && v.open[d] < 0.9f) {
                // opens the door and waits until it is open; it shuts it again after getting in
                if (!v.openTarget[d]) { v.openTarget[d] = true; v.autoClose[d] = true; }
                a.lookAt(v.x, v.z);
                return true;
            }
            v.riders[want] = a.id;
            seat = want;
            if (want == m.driverSeat) v.driverKilled = false;
            placeRiders(v, m);
            if (d >= 0 && d < v.autoClose.length && v.autoClose[d]) { v.closeTimer[d] = 14; v.autoClose[d] = false; }
        }
        if (seat != m.driverSeat) return true;
        // driving: to the next patrol point, or after the player
        double tx, tz, stop;
        if (!a.waypoints.isEmpty()) {
            a.driveWp = Math.floorMod(a.driveWp, a.waypoints.size());
            double[] wp = a.waypoints.get(a.driveWp);
            double reach = 3.2 + Math.abs(v.speed) * 6;      // turns towards the next point a little early instead of slowing down
            if (v.distSq(wp[0], wp[2]) < reach * reach) {
                if (a.waypoints.size() > 1) {
                    if (a.patrolLoop) a.driveWp = (a.driveWp + 1) % a.waypoints.size();
                    else {
                        if (a.driveWp + a.driveDir < 0 || a.driveWp + a.driveDir >= a.waypoints.size()) a.driveDir = -a.driveDir;
                        a.driveWp += a.driveDir;
                    }
                }
                wp = a.waypoints.get(a.driveWp);
            }
            tx = wp[0]; tz = wp[2]; stop = a.waypoints.size() > 1 ? 0 : 3;
        } else {
            Player p = s.player;
            if (playerVehicle == v) { v.handbrake = true; return true; }      // the player rides along: wait
            tx = p.x; tz = p.z; stop = 9;
        }
        v.autopilot(tx, tz, stop, 0.55f, !a.waypoints.isEmpty() && a.waypoints.size() > 1);
        return true;
    }

    /** Steers towards a point and keeps a cruising speed; backs out after getting stuck. */

    // ================================================================== NPCs following the player ride along
    /**
     * FOLLOW behaviour: when the player sits in a vehicle that stands (or nearly), the NPC walks to a free passenger
     * seat's door, opens it and gets in; it stays in while the player is in this vehicle or near it, and gets out (door
     * opens) only when the player is farther than its follow distance. Returns true while it does that (the AI does
     * nothing else this tick), false = ordinary following.
     */
    public boolean npcFollow(Actor a) {
        Vehicle cur = a.rideAlong >= 0 ? byId(a.rideAlong) : null;
        if (cur != null) {
            int seat = cur.seatOf(a.id);
            VehicleModel cm = cur.vm();
            if (seat < 0 || cm == null) { a.rideAlong = -1; return false; }
            if (cur.wrecked) { eject(cur, seat); return false; }
            if (playerVehicle == cur || a.waiting) return true;               // rides along / waits in the car
            Player p = s.player;
            double dx = p.x - a.x(), dz = p.z - a.z();
            if (dx * dx + dz * dz <= a.followStart * a.followStart || Math.abs(cur.speed) > 0.05f) return true;
            // the player walked away: the door opens, it gets out and follows
            int d = seat < cm.seatDoor.length ? cm.seatDoor[seat] : -1;
            if (d >= 0 && d < cur.open.length && cur.open[d] < 0.9f) {
                if (!cur.openTarget[d]) { cur.openTarget[d] = true; cur.autoClose[d] = true; }
                return true;
            }
            eject(cur, seat);
            if (d >= 0 && d < cur.closeTimer.length) { cur.closeTimer[d] = 14; cur.autoClose[d] = false; }
            return true;
        }
        Vehicle v = playerVehicle;
        VehicleModel m = v == null ? null : v.vm();
        if (m == null || v.wrecked || a.waiting || Math.abs(v.speed) > 0.08f) return false;
        double d2 = v.distSq(a.x(), a.z());
        if (d2 > 40 * 40) return false;
        // the free passenger seat nearest to the NPC
        int want = -1;
        double bd = Double.MAX_VALUE;
        double[] door3 = null;
        for (int i = 0; i < m.seats.size() && i < v.riders.length; i++) {
            if (i == m.driverSeat || v.riders[i] != Vehicle.NONE) continue;
            double[] o = exitPoint(v, i, 0.6f, 1.8f);
            double dd = (o[0] - a.x()) * (o[0] - a.x()) + (o[2] - a.z()) * (o[2] - a.z());
            if (dd < bd) { bd = dd; want = i; door3 = o; }
        }
        if (want < 0) return false;                            // no room: it just follows
        if (bd > 0.8 * 0.8) return a.walkTo(door3[0], door3[1], door3[2], bd > 8 * 8);
        int d = want < m.seatDoor.length ? m.seatDoor[want] : -1;
        if (d >= 0 && d < v.open.length && v.open[d] < 0.9f) {
            if (!v.openTarget[d]) { v.openTarget[d] = true; v.autoClose[d] = true; }
            a.lookAt(v.x, v.z);
            return true;
        }
        v.riders[want] = a.id;
        a.rideAlong = v.id;
        placeRiders(v, m);
        if (d >= 0 && d < v.autoClose.length && v.autoClose[d]) { v.closeTimer[d] = 14; v.autoClose[d] = false; }
        return true;
    }

    /** The way a seat of a vehicle faces now (degrees). */
    public float seatYaw(Vehicle v, int seat) { VehicleModel m = v.vm(); return m == null ? v.yaw : seatFacing(v, m, seat, v.yaw); }

    /** What a vehicle an NPC sits in by its AI is doing, for recording its take (same layout as recordState), or null. */
    public float[] seatState(Actor a) {
        Vehicle v = byId(a.seatedVehicleId());
        int seat = v == null ? -1 : v.seatOf(a.id);
        if (seat < 0) return null;
        int doors = 0;
        for (int i = 0; i < v.openTarget.length && i < 30; i++) if (v.openTarget[i]) doors |= 1 << i;
        return new float[]{v.id, seat, v.yaw, v.pitch, v.roll, v.steer, doors, v.flip, v.slip, (v.lightsOn ? 1 : 0) | (v.wrecked ? 2 : 0)};
    }

    /** The vehicle an NPC sits in (driving, riding along, or put there by a take), or null. */
    public Vehicle seatedIn(Actor a) {
        Vehicle v = byId(a.seatedVehicleId());
        if (v != null && v.seatOf(a.id) >= 0) return v;
        long[] ts = takeSeats.get(a.id);
        return ts == null ? null : byId((int) ts[0]);
    }

    // ================================================================== bullets
    /** A vehicle part a bullet meets: where along the ray, and whether the part stops it. */
    public record BulletHit(Vehicle v, VehicleModel.Part part, double t, boolean stops) {}

    /**
     * The parts of vehicles a bullet ray meets before 'max' (nearest first; one entry per part), except those of 'skip'
     * (the shooter's own vehicle). Which parts stop bullets is set on the editor's Bullets page.
     */
    public List<BulletHit> bulletHits(double ox, double oy, double oz, double dx, double dy, double dz, double max, Vehicle skip) {
        List<BulletHit> out = new ArrayList<>();
        double[] lo = new double[3], ld = new double[3];
        for (Vehicle v : list) {
            if (v == skip) continue;
            VehicleModel m = v.vm();
            if (m == null) continue;
            double r = Math.max(Math.hypot(Math.max(-m.minX, m.maxX), Math.max(-m.minZ, m.maxZ)), m.maxY) + 1.5;
            double cx = v.x - ox, cy = v.y + m.maxY / 2 - oy, cz = v.z - oz;
            double along = cx * dx + cy * dy + cz * dz;
            double perp2 = cx * cx + cy * cy + cz * cz - along * along;
            if (perp2 > r * r || along < -r || along > max + r) continue;
            v.toLocal(ox, oy, oz, lo);
            v.localDir(dx, dy, dz, ld);
            VehicleType type = v.type();
            for (VehicleModel.Part p : m.parts) {
                Mat4 inv = v.partAnim(m, p, 1).inverted();
                float[] o4 = new float[4], d4 = new float[4];
                inv.transform((float) lo[0], (float) lo[1], (float) lo[2], o4);
                inv.transform((float) (lo[0] + ld[0]), (float) (lo[1] + ld[1]), (float) (lo[2] + ld[2]), d4);
                float rdx = d4[0] - o4[0], rdy = d4[1] - o4[1], rdz = d4[2] - o4[2];
                if (!rayBox(o4[0], o4[1], o4[2], rdx, rdy, rdz, p.x0 - 0.01, p.y0 - 0.01, p.z0 - 0.01, p.x1 + 0.01, p.y1 + 0.01, p.z1 + 0.01, max)) continue;
                double t = rayTris(p, o4[0], o4[1], o4[2], rdx, rdy, rdz, max);
                if (t < max) out.add(new BulletHit(v, p, t, type.stopsBullets(p)));
            }
        }
        out.sort(java.util.Comparator.comparingDouble(BulletHit::t));
        return out;
    }

    // ================================================================== scene takes
    /** A take frame of an actor in a vehicle: the vehicle goes where the take was recorded (called by the NPC manager). */
    public void replay(Actor a, int vehicleId, int seat, double x, double y, double z, float vyaw, float vpitch, float vroll, float steer, int doors, boolean snap) {
        replay(a, vehicleId, seat, x, y, z, vyaw, vpitch, vroll, steer, doors, 0, 0, 0, snap);
    }

    /** The same with what a take of version 6 records too: the roll-over angle, how much the tyres slide, the lights (bit 0) and the wreck (bit 1). */
    public void replay(Actor a, int vehicleId, int seat, double x, double y, double z, float vyaw, float vpitch, float vroll, float steer, int doors, float flip, float slip, int vflags, boolean snap) {
        Vehicle v = byId(vehicleId);
        VehicleModel m = v == null ? null : v.vm();
        if (m == null) return;
        if (v.driverKilled && !snap) {
            // the driver died: the car is nobody's now, it rolls on and stops by itself; passengers just sit in it
            if (seat != m.driverSeat) replayed.add(new Object[]{a, v, seat});
            return;
        }
        v.lightsOn = (vflags & 1) != 0;
        v.replaySlip = slip;
        boolean wr = (vflags & 2) != 0;
        if (wr && !v.wrecked) { v.wreckedByTake = true; explode(v, true); }
        else if (!wr && v.wrecked && v.wreckedByTake) v.repair();
        boolean boarding = boardingVehicle(v);
        v.flip = flip;
        long now = s.world.gameTime;
        boolean driverDone = v.replayTick == now && v.replaySeat == m.driverSeat;
        if (!(driverDone && seat != m.driverSeat)) {
            v.placeBySeat(seat, x, y, z, vyaw, vpitch, vroll);
            v.steer = steer;
            if (!boarding) for (int i = 0; i < v.openTarget.length; i++) v.openTarget[i] = (doors & (1 << i)) != 0;
            v.replayTick = now;
            v.replaySeat = seat;
            if (snap) {        // timeline preview (the world may be paused): no in-between movement
                v.px = v.x; v.py = v.y; v.pz = v.z; v.prevYaw = v.yaw; v.prevPitch = v.pitch; v.prevRoll = v.roll; v.prevSteer = v.steer; v.prevSpin = v.spin;
                for (int i = 0; i < v.open.length; i++) v.open[i] = v.prevOpen[i] = v.openTarget[i] ? 1 : 0;
            }
        }
        replayed.add(new Object[]{a, v, seat});
        if (snap) finishReplays();
    }

    /** After all takes of the tick played: riders sit exactly on the seats of where the vehicle is now. */
    public void finishReplays() {
        double[] w = new double[3];
        for (Object[] o : replayed) {
            Actor a = (Actor) o[0];
            Vehicle v = (Vehicle) o[1];
            int seat = (int) o[2];
            VehicleModel m = v.vm();
            if (m == null || seat >= m.seats.size()) continue;
            float[] st = m.seats.get(seat);
            v.bodyPointAt(m, st[0], st[1] + SIT_DROP, st[2], 1, w);
            a.sitInTake(w[0], w[1], w[2], seatFacing(v, m, seat, v.yaw));
            takeSeats.put(a.id, new long[]{v.id, seat, s.world.gameTime});
        }
        replayed.clear();
        if (playerVehicle != null && playerVehicle.replayTick == s.world.gameTime) placeRiders(playerVehicle, playerVehicle.vm());
    }

    // ================================================================== getting in and out in scenes
    /** How long the walk to the seat / out of it takes (ticks), the share of an exit spent sitting while the door opens, and the share of an entry spent walking to the door. */
    public static final int BOARD_TICKS = 20;
    private static final float EXIT_SEATED = 0.3f, ENTER_DOOR = 0.55f;

    private static final class Board {
        Actor a; Vehicle v; int seat, door, total, t; boolean enter;
        double[] door3; double lx, ly, lz, sx0, sy0, sz0;
    }

    private final List<Board> boards = new ArrayList<>();

    private Board boardOf(Actor a) { for (Board b : boards) if (b.a == a) return b; return null; }

    /** Whether the actor is walking to / from a car in a scene. */
    public boolean boarding(Actor a) { return boardOf(a) != null; }

    /** The actor was put somewhere (scene reset or start): a walk to / from a car is dropped. */
    public void cancelBoard(Actor a) { boards.removeIf(b -> b.a == a); takeSeats.remove(a.id); }

    private boolean boardingVehicle(Vehicle v) { for (Board b : boards) if (b.v == v) return true; return false; }

    /** A car a scene drives that is burnt out (earlier replay, or by hand) is whole again when the scene starts. */
    public void repairForScene(int vehicleId) {
        Vehicle v = byId(vehicleId);
        if (v != null && v.wrecked) { v.repair(); v.wreckedByTake = false; }
        if (v != null) v.driverKilled = false;
    }

    /**
     * An actor died. If a take had it at the wheel, the car is left without a driver: it is no longer moved by any take
     * and rolls out on its own speed. A walk into / out of a car stops.
     */
    public void actorDied(Actor a) {
        long[] ts = takeSeats.remove(a.id);
        boards.removeIf(b -> b.a == a);
        Vehicle v = ts == null ? null : byId((int) ts[0]);
        VehicleModel m = v == null ? null : v.vm();
        if (m != null && (int) ts[1] == m.driverSeat) {
            v.driverKilled = true;
            v.throttle = 0; v.steerIn = 0; v.handbrake = false;
            v.replayTick = -100;           // physics from the next tick (its speed is the take's last one)
        }
        if (a.rideAlong >= 0) { Vehicle c = byId(a.rideAlong); if (c != null) { int st = c.seatOf(a.id); if (st >= 0) c.riders[st] = Vehicle.NONE; } a.rideAlong = -1; }
    }

    private static void setDoor(Vehicle v, int door, boolean open) {
        if (door >= 0 && door < v.openTarget.length) { v.openTarget[door] = open; if (door < v.autoClose.length) v.autoClose[door] = false; }
    }

    /**
     * The take puts the actor into a seat in 'ticks' ticks: the car stands where the take has it, the actor walks to the
     * door (from where it is now), the door opens, the actor steps in; the take takes over at the seat.
     */
    public void beginEnter(Actor a, int vehicleId, int seat, double x, double y, double z, float vyaw, float vpitch, float vroll, int ticks) {
        Vehicle v = byId(vehicleId);
        VehicleModel m = v == null ? null : v.vm();
        if (m == null || v.wrecked || boarding(a) || seat < 0 || seat >= m.seats.size()) return;
        v.placeBySeat(seat, x, y, z, vyaw, vpitch, vroll);
        v.px = v.x; v.py = v.y; v.pz = v.z; v.prevYaw = v.yaw; v.prevPitch = v.pitch; v.prevRoll = v.roll; v.prevSteer = v.steer; v.prevSpin = v.spin;
        Board b = new Board();
        b.a = a; b.v = v; b.seat = seat; b.enter = true; b.total = ticks;
        b.door = seat < m.seatDoor.length ? m.seatDoor[seat] : -1;
        b.door3 = exitPoint(v, seat, 0.6f, 1.8f);
        b.lx = b.sx0 = a.x(); b.ly = b.sy0 = a.y(); b.lz = b.sz0 = a.z();
        setDoor(v, b.door, true);
        boards.add(b);
    }

    /** The take stopped putting the actor into a seat (it ended, or the scene was stopped): it opens the door and gets out. */
    private void beginExit(Actor a, Vehicle v, int seat) {
        VehicleModel m = v.vm();
        if (m == null || seat < 0 || seat >= m.seats.size() || boarding(a)) return;
        double[] w = new double[3];
        float[] st = m.seats.get(seat);
        v.bodyPointAt(m, st[0], st[1] + SIT_DROP, st[2], 1, w);
        double dx = a.x() - w[0], dz = a.z() - w[2];
        if (dx * dx + dz * dz > 2.5 * 2.5) return;                      // it was taken somewhere else (scene reset)
        Board b = new Board();
        b.a = a; b.v = v; b.seat = seat; b.enter = false; b.total = BOARD_TICKS;
        b.door = seat < m.seatDoor.length ? m.seatDoor[seat] : -1;
        b.door3 = exitPoint(v, seat, 0.6f, 1.8f);
        b.lx = w[0]; b.ly = w[1]; b.lz = w[2];
        setDoor(v, b.door, true);
        boards.add(b);
        a.exitGrace(BOARD_TICKS + EXIT_SAFE_TICKS);       // no damage while climbing out and a second after
    }

    /**
     * Actors in takes get into and out of cars at once, like the player (true = the old walk to / from the door with the
     * door opening, which looked crooked).
     */
    public static boolean walkInOut = false;

    /**
     * A take stopped putting the actor into a seat. Still in the take: its frame already put it where the player got out.
     * The take is over (or the scene stopped) while it sat: it is put beside its seat at once, like the player getting out.
     */
    private void exitAtOnce(Actor a, Vehicle v, int seat) {
        VehicleModel m = v.vm();
        if (m == null || seat < 0 || seat >= m.seats.size()) return;
        a.exitGrace(EXIT_SAFE_TICKS);
        if (a.mode() == Actor.Mode.PLAYING) return;
        double[] w = new double[3];
        float[] st = m.seats.get(seat);
        v.bodyPointAt(m, st[0], st[1] + SIT_DROP, st[2], 1, w);
        double dx = a.x() - w[0], dz = a.z() - w[2];
        if (dx * dx + dz * dz > 2.5 * 2.5) return;                      // it was taken somewhere else (scene reset)
        double[] o = exitPoint(v, seat, 0.6f, 1.8f);
        a.place(o[0], o[1], o[2], seatFacing(v, m, seat, v.yaw));
        if (a.mode() == Actor.Mode.AI) a.setHome();
        a.grace(80);
    }

    private static double ease(double u) { u = Math.max(0, Math.min(1, u)); return u * u * (3 - 2 * u); }

    /** Every tick after the NPCs: the walks into and out of cars (they override what the take or the AI did this tick). */
    private void tickBoards() {
        long now = s.world.gameTime;
        for (var it = takeSeats.entrySet().iterator(); it.hasNext(); ) {
            var e = it.next();
            long[] ts = e.getValue();
            if (ts[2] >= now) continue;
            it.remove();
            Actor a = s.npcs.byId(e.getKey());
            Vehicle v = byId((int) ts[0]);
            if (a == null || v == null || v.wrecked || a.dead() || !s.npcs.inWorld(a) || s.npcs.previewing(a)) continue;
            if (walkInOut) beginExit(a, v, (int) ts[1]);
            else exitAtOnce(a, v, (int) ts[1]);
        }
        double[] w = new double[3];
        for (var it = boards.iterator(); it.hasNext(); ) {
            Board b = it.next();
            Actor a = b.a;
            VehicleModel m = b.v.vm();
            long[] ts = takeSeats.get(a.id);
            boolean reseated = !b.enter && ts != null && ts[2] >= now;
            if (m == null || a.dead() || !s.npcs.inWorld(a) || reseated || b.v.wrecked || b.seat >= m.seats.size()) {
                if (!b.enter) setDoorClose(b);
                it.remove();
                continue;
            }
            b.t++;
            float[] st = m.seats.get(b.seat);
            b.v.bodyPointAt(m, st[0], st[1] + SIT_DROP, st[2], 1, w);
            double sx = w[0], sy = w[1], sz = w[2], dxp = b.door3[0], dyp = b.door3[1], dzp = b.door3[2];
            double px, py, pz, tx, tz;
            double frac = b.t / (double) b.total;
            if (b.enter) {
                if (frac < ENTER_DOOR) {
                    double k = ease(frac / ENTER_DOOR);
                    px = b.sx0 + (dxp - b.sx0) * k; py = b.sy0 + (dyp - b.sy0) * k; pz = b.sz0 + (dzp - b.sz0) * k;
                    tx = dxp; tz = dzp;
                } else {
                    double k = ease((frac - ENTER_DOOR) / (1 - ENTER_DOOR));
                    px = dxp + (sx - dxp) * k; py = dyp + (sy - dyp) * k; pz = dzp + (sz - dzp) * k;
                    tx = sx; tz = sz;
                }
            } else if (frac < EXIT_SEATED) {
                px = sx; py = sy; pz = sz; tx = dxp; tz = dzp;
            } else {
                // out to the door, and towards where the take / the AI has the actor now (so there is no jump when the walk ends)
                double kk = ease((frac - 0.7) / 0.3);
                double gx = dxp + (a.x() - dxp) * kk, gy = dyp + (a.y() - dyp) * kk, gz = dzp + (a.z() - dzp) * kk;
                double k = ease((frac - EXIT_SEATED) / (1 - EXIT_SEATED));
                px = sx + (gx - sx) * k; py = sy + (gy - sy) * k; pz = sz + (gz - sz) * k;
                tx = gx; tz = gz;
            }
            float yaw = Math.abs(tx - px) + Math.abs(tz - pz) > 0.02 ? (float) Math.toDegrees(Math.atan2(-(tx - px), tz - pz)) : a.yaw();
            if (!b.enter && frac < EXIT_SEATED) yaw = seatFacing(b.v, m, b.seat, b.v.yaw);
            a.glide(px, py, pz, yaw, b.lx, b.ly, b.lz);
            b.lx = px; b.ly = py; b.lz = pz;
            if (b.t >= b.total) {
                if (!b.enter) {
                    if (a.mode() == Actor.Mode.AI) a.setHome();
                    a.grace(80);
                    a.exitGrace(EXIT_SAFE_TICKS);
                    setDoorClose(b);
                }
                it.remove();
            }
        }
    }

    private static void setDoorClose(Board b) {
        int d = b.door;
        if (d >= 0 && d < b.v.closeTimer.length) b.v.closeTimer[d] = 14;
    }

    /** Actors a take put into a seat: actor id -> {vehicle id, seat, game tick} (drawn on the seat between ticks). */
    private final Map<Integer, long[]> takeSeats = new HashMap<>();

    /**
     * Feet of the player in the vehicle between ticks, from the same pose the car is drawn with (no wobble of the cabin
     * against the camera), or null when not riding.
     */
    public double[] playerFeetAt(float partial) {
        Vehicle v = playerVehicle;
        VehicleModel m = v == null ? null : v.vm();
        if (m == null || playerSeat < 0 || playerSeat >= m.seats.size()) return null;
        float[] st = m.seats.get(playerSeat);
        double[] w = new double[3];
        v.bodyPointAt(m, st[0], st[1] + SIT_DROP, st[2], partial, w);
        return w;
    }

    /** The camera point set for the player's seat (editor: "Camera"), in the world between the ticks, or null for the usual place above the seat. */
    public double[] customEye(float partial) {
        Vehicle v = playerVehicle;
        VehicleModel m = v == null ? null : v.vm();
        if (m == null || playerSeat < 0 || playerSeat >= m.eyes.size() || m.eyes.get(playerSeat) == null) return null;
        float[] e = m.eyes.get(playerSeat);
        double[] w = new double[3];
        v.bodyPointAt(m, e[0], e[1], e[2], partial, w);
        return w;
    }

    /** The player's eyes while the car rolls over (the point above the seat in the car's own up direction), or null when it does not. */
    public double[] flipEye(float partial, float eye) {
        Vehicle v = playerVehicle;
        VehicleModel m = v == null ? null : v.vm();
        if (m == null || playerSeat < 0 || playerSeat >= m.seats.size() || Math.abs(v.flipAt(partial)) < 1f) return null;
        float[] st = m.seats.get(playerSeat);
        double[] w = new double[3];
        v.bodyPointAt(m, st[0], st[1] + SIT_DROP + eye, st[2], partial, w);
        return w;
    }

    /** An NPC sitting in a vehicle is drawn exactly on its seat (and facing the front) between ticks. */
    public void riderPose(Actor a, derpcraft.render.EntityRenderer.PlayerPose p, float partial) {
        Vehicle v = byId(a.seatedVehicleId());
        int seat = v == null ? -1 : v.seatOf(a.id);
        Board bd = boardOf(a);
        if (bd != null && !bd.enter && bd.t < bd.total * EXIT_SEATED) { v = bd.v; seat = bd.seat; }
        else if (seat < 0) {
            long[] ts = takeSeats.get(a.id);
            if (ts == null || ts[2] < s.world.gameTime - 1) { if (ts != null) takeSeats.remove(a.id); return; }
            v = byId((int) ts[0]);
            seat = (int) ts[1];
            if (v == null) return;
        }
        VehicleModel m = v.vm();
        if (m == null || seat >= m.seats.size()) return;
        float[] st = m.seats.get(seat);
        double[] w = new double[3];
        v.bodyPointAt(m, st[0], st[1] + SIT_DROP, st[2], partial, w);
        float by = v.yawAt(partial);
        p.x = w[0]; p.y = w[1]; p.z = w[2];
        p.vehRoll = v.flipAt(partial);
        p.headYaw = Math.max(-85, Math.min(85, Vehicle.wrap(p.headYaw + p.bodyYaw - by)));   // the head keeps looking where it looked
        p.bodyYaw = seatFacing(v, m, seat, by);
    }

    /** What the player's vehicle is doing, for recording a take: {id, seat, yaw, pitch, roll, steer, doors}, or null. */
    public float[] recordState() {
        Vehicle v = playerVehicle;
        if (v == null) return null;
        int doors = 0;
        for (int i = 0; i < v.openTarget.length && i < 30; i++) if (v.openTarget[i]) doors |= 1 << i;
        return new float[]{v.id, playerSeat, v.yaw, v.pitch, v.roll, v.steer, doors, v.flip, v.slip, (v.lightsOn ? 1 : 0) | (v.wrecked ? 2 : 0)};
    }

    // ================================================================== looking at a vehicle
    public static final int HIT_BODY = 0, HIT_DOOR = 1, HIT_SLOT = 2, HIT_EMPTY_SLOT = 3;

    /** What the crosshair points at on a vehicle. */
    public static final class Hit {
        public Vehicle v;
        public int kind, index;
        public double t;
        /** Hit point in the vehicle's local coordinates. */
        public final double[] local = new double[3];
    }

    /**
     * The vehicle part hit by a ray (nearer than 'max'). Inside the player's own vehicle only doors and block places
     * count (the cabin would block every look). Empty block places count when 'placing' (a block in hand).
     */
    public Hit pick(double ox, double oy, double oz, double dx, double dy, double dz, double max, boolean placing) {
        Hit best = null;
        double[] lo = new double[3], ld = new double[3];
        for (Vehicle v : list) {
            VehicleModel m = v.vm();
            if (m == null) continue;
            double r = Math.max(Math.hypot(Math.max(-m.minX, m.maxX), Math.max(-m.minZ, m.maxZ)), m.maxY) + 1.5;
            double cx = v.x - ox, cy = v.y + m.maxY / 2 - oy, cz = v.z - oz;
            double along = cx * dx + cy * dy + cz * dz;
            double perp2 = cx * cx + cy * cy + cz * cz - along * along;
            if (perp2 > r * r || along < -r || along > max + r) continue;
            v.toLocal(ox, oy, oz, lo);
            v.localDir(dx, dy, dz, ld);
            boolean own = v == playerVehicle;
            double lim = best == null ? max : best.t;
            // doors, hood and trunk count with their whole box (the window, the frame, the gaps), the body by its triangles;
            // a door wins over body parts just in front of it (sills, pillars, mirrors) so it is easy to click
            double bodyT = Double.MAX_VALUE, doorT = Double.MAX_VALUE;
            int door = -1;
            for (VehicleModel.Part p : m.parts) {
                boolean opens = p.role == VehicleModel.OPENABLE;
                if (own && !opens) continue;
                Mat4 inv = v.partAnim(m, p, 1).inverted();
                float[] o4 = new float[4], d4 = new float[4];
                inv.transform((float) lo[0], (float) lo[1], (float) lo[2], o4);
                inv.transform((float) (lo[0] + ld[0]), (float) (lo[1] + ld[1]), (float) (lo[2] + ld[2]), d4);
                float rdx = d4[0] - o4[0], rdy = d4[1] - o4[1], rdz = d4[2] - o4[2];
                if (opens) {
                    double e = 0.04;
                    double t = rayBoxT(o4[0], o4[1], o4[2], rdx, rdy, rdz, p.x0 - e, p.y0 - e, p.z0 - e, p.x1 + e, p.y1 + e, p.z1 + e);
                    if (t > 0 && t < doorT) { doorT = t; door = m.openables.indexOf(p); }
                    continue;
                }
                if (!rayBox(o4[0], o4[1], o4[2], rdx, rdy, rdz, p.x0 - 0.01, p.y0 - 0.01, p.z0 - 0.01, p.x1 + 0.01, p.y1 + 0.01, p.z1 + 0.01, Math.min(lim, bodyT))) continue;
                bodyT = Math.min(bodyT, rayTris(p, o4[0], o4[1], o4[2], rdx, rdy, rdz, Math.min(lim, bodyT)));
            }
            boolean doorWins = door >= 0 && doorT <= bodyT + 0.35;
            double tp = doorWins ? doorT : bodyT;
            if (tp < lim) {
                lim = tp;
                if (best == null) best = new Hit();
                best.v = v; best.t = tp;
                best.kind = doorWins ? HIT_DOOR : HIT_BODY;
                best.index = doorWins ? door : -1;
                for (int k = 0; k < 3; k++) best.local[k] = lo[k] + ld[k] * tp;
            }
            for (int i = 0; i < m.slots.size(); i++) {
                Vehicle.Slot sl = v.slots[i];
                if (sl.state == 0 && !placing) continue;
                float[] sp = m.slots.get(i);
                List<float[]> boxes;
                if (sl.state != 0) {
                    Block b = Blocks.byId(sl.state >> 4);
                    boxes = b.selectionShape(sl.state & 15, 0);
                    if (boxes.isEmpty()) boxes = List.of(new float[]{0, 0, 0, 1, 1, 1});
                } else boxes = List.of(new float[]{0, 0, 0, 1, 1, 1});
                for (float[] bx : boxes) {
                    double x0 = sp[0] - 0.5 + bx[0], y0 = sp[1] + bx[1], z0 = sp[2] - 0.5 + bx[2], x1 = sp[0] - 0.5 + bx[3], y1 = sp[1] + bx[4], z1 = sp[2] - 0.5 + bx[5];
                    double t = rayBoxT(lo[0], lo[1], lo[2], ld[0], ld[1], ld[2], x0, y0, z0, x1, y1, z1);
                    if (t >= 0 && t < lim) {
                        lim = t;
                        if (best == null) best = new Hit();
                        best.v = v; best.t = t;
                        best.kind = sl.state != 0 ? HIT_SLOT : HIT_EMPTY_SLOT;
                        best.index = i;
                        for (int k = 0; k < 3; k++) best.local[k] = lo[k] + ld[k] * t;
                    }
                }
            }
        }
        return best;
    }

    private static boolean rayBox(float ox, float oy, float oz, float dx, float dy, float dz, double x0, double y0, double z0, double x1, double y1, double z1, double max) {
        double t = rayBoxT(ox, oy, oz, dx, dy, dz, x0, y0, z0, x1, y1, z1);
        return t >= 0 && t <= max || ox >= x0 && ox <= x1 && oy >= y0 && oy <= y1 && oz >= z0 && oz <= z1;
    }

    /** Distance along the ray to an axis-aligned box (0 when starting inside), or -1. */
    static double rayBoxT(double ox, double oy, double oz, double dx, double dy, double dz, double x0, double y0, double z0, double x1, double y1, double z1) {
        return derpcraft.util.RayBox.t(ox, oy, oz, dx, dy, dz, Double.MAX_VALUE, x0, y0, z0, x1, y1, z1);
    }

    /** Nearest triangle of a part along a ray (Moller-Trumbore), or Double.MAX_VALUE. */
    private static double rayTris(VehicleModel.Part p, float ox, float oy, float oz, float dx, float dy, float dz, double max) {
        double best = Double.MAX_VALUE;
        for (int gi = 0; gi < p.groups.size(); gi++) {
            Model3D.Group g = p.groups.get(gi);
            float[] v = g.v;
            float[] cb = gi < p.chunks.length ? p.chunks[gi] : null;
            int nc = cb == null ? 1 : cb.length / 6;
            for (int c = 0; c < nc; c++) {
            int t0 = cb == null ? 0 : c * VehicleModel.CHUNK, t1 = cb == null ? g.tris : Math.min(g.tris, t0 + VehicleModel.CHUNK);
            if (cb != null) {         // a box around these triangles first: most of them are skipped at once
                double bt = rayBoxT(ox, oy, oz, dx, dy, dz, cb[c * 6], cb[c * 6 + 1], cb[c * 6 + 2], cb[c * 6 + 3], cb[c * 6 + 4], cb[c * 6 + 5]);
                if (bt < 0 || bt > Math.min(best, max)) continue;
            }
            for (int t = t0; t < t1; t++) {
                int o = t * 15;
                float e1x = v[o + 5] - v[o], e1y = v[o + 6] - v[o + 1], e1z = v[o + 7] - v[o + 2];
                float e2x = v[o + 10] - v[o], e2y = v[o + 11] - v[o + 1], e2z = v[o + 12] - v[o + 2];
                float px = dy * e2z - dz * e2y, py = dz * e2x - dx * e2z, pz = dx * e2y - dy * e2x;
                float det = e1x * px + e1y * py + e1z * pz;
                if (Math.abs(det) < 1e-9f) continue;
                float inv = 1 / det;
                float sx = ox - v[o], sy = oy - v[o + 1], sz = oz - v[o + 2];
                float u = (sx * px + sy * py + sz * pz) * inv;
                if (u < 0 || u > 1) continue;
                float qx = sy * e1z - sz * e1y, qy = sz * e1x - sx * e1z, qz = sx * e1y - sy * e1x;
                float w = (dx * qx + dy * qy + dz * qz) * inv;
                if (w < 0 || u + w > 1) continue;
                float tt = (e2x * qx + e2y * qy + e2z * qz) * inv;
                if (tt > 1e-4f && tt < best && tt < max) best = tt;
            }
            }
        }
        return best;
    }

    /** World corners of a block place (for the outline): 8 points x, y, z. */
    public double[][] slotCorners(Hit h, float partial) {
        VehicleModel m = h.v.vm();
        float[] sp = m.slots.get(h.index);
        Vehicle.Slot sl = h.v.slots[h.index];
        float[] bx = {0, 0, 0, 1, 1, 1};
        if (sl.state != 0) {
            List<float[]> l = Blocks.byId(sl.state >> 4).selectionShape(sl.state & 15, 0);
            if (!l.isEmpty()) bx = l.get(0);
        }
        double[][] c = new double[8][3];
        for (int i = 0; i < 8; i++) {
            double lx = sp[0] - 0.5 + ((i & 1) == 0 ? bx[0] : bx[3]), ly = sp[1] + ((i & 2) == 0 ? bx[1] : bx[4]), lz = sp[2] - 0.5 + ((i & 4) == 0 ? bx[2] : bx[5]);
            h.v.bodyPointAt(m, lx, ly, lz, partial, c[i]);
        }
        return c;
    }

    // ================================================================== clicks
    /** Left click on a vehicle: a door / hood / trunk opens or closes, a carried block breaks (creative), Shift + click on the body picks the car up. */
    public void leftClick(Hit h) {
        Vehicle v = h.v;
        switch (h.kind) {
            case HIT_DOOR -> { if (h.index >= 0 && h.index < v.openTarget.length) v.openTarget[h.index] = !v.openTarget[h.index]; }
            case HIT_SLOT -> { if (s.creative()) breakSlot(v, h.index, false); }
            default -> {
                if (s.player.sneaking && playerVehicle != v) {
                    remove(v, s.survival());
                    s.chat("Vehicle #" + v.id + " picked up");
                }
            }
        }
        save();
    }

    /** Survival: holding the left button on a carried block breaks it after the mining time. Returns true while mining. */
    public boolean mineSlot(Hit h) {
        if (h == null || h.kind != HIT_SLOT || !s.survival()) return false;
        Vehicle.Slot sl = h.v.slots[h.index];
        Block b = Blocks.byId(sl.state >> 4);
        if (b.hardness < 0) return true;
        ItemStack held = s.heldStack();
        Item it = held == null ? null : held.item;
        float speed = it == null ? 1 : it.speedAgainst(b);
        boolean harvest = !b.requiresTool || it != null && it.canHarvest(b);
        sl.mining += b.hardness == 0 ? 1 : speed / b.hardness / (harvest ? 30f : 100f);
        s.swingArm();
        if (sl.mining >= 1) { breakSlot(h.v, h.index, true); save(); }
        return true;
    }

    /** Right click on a vehicle: use the carried block (chest, crafting table, furnace, lever...), put a block into an empty place, or get in. */
    public boolean rightClick(Hit h) {
        Vehicle v = h.v;
        ItemStack held = s.heldStack();
        switch (h.kind) {
            case HIT_SLOT -> {
                if (!(s.player.sneaking && held != null)) { if (useSlot(v, h.index)) { s.swingArm(); save(); return true; } }
                return false;
            }
            case HIT_EMPTY_SLOT -> {
                if (held != null && held.item.block != null && placeSlot(v, h.index, held)) { s.swingArm(); save(); return true; }
                return false;
            }
            default -> {
                if (playerVehicle == v && h.kind == HIT_DOOR) return false;
                if (enterPlayer(v, h.local)) { s.swingArm(); return true; }
                return false;
            }
        }
    }

    private boolean placeSlot(Vehicle v, int i, ItemStack held) {
        Block b = held.item.block;
        if (b == null || b.isAir() || b.shape == Block.Shape.LIQUID) return false;
        if (b instanceof derpcraft.block.FunctionalBlocks.DoorBlock) return false;     // two blocks high
        float rel = s.player.yaw - v.yaw - 180;
        int meta = b.placementMeta(Dir.UP, 0, rel);
        if (b.placement == Block.Placement.TORCH) meta = 0;                              // standing torch
        Vehicle.Slot sl = v.slots[i];
        sl.state = (b.id << 4) | (meta & 15);
        sl.be = b.blockEntityType() != null ? b.createBlockEntity() : null;
        sl.mining = 0;
        if (s.survival()) s.consumeHeld(1);
        return true;
    }

    private void breakSlot(Vehicle v, int i, boolean drops) {
        VehicleModel m = v.vm();
        Vehicle.Slot sl = v.slots[i];
        if (m == null || sl.state == 0) return;
        float[] p = m.slots.get(i);
        double[] w = new double[3];
        v.toWorld(p[0], p[1] + 0.5, p[2], w);
        Block b = Blocks.byId(sl.state >> 4);
        s.game.entities.spawnBreakParticles(s.world, (int) Math.floor(w[0]), (int) Math.floor(w[1]), (int) Math.floor(w[2]), b, sl.state & 15);
        dropSlot(sl, w[0], w[1], w[2], drops);
        s.swingArm();
    }

    /** Empties a block place: its block (when 'drops' and the held tool can harvest it) and its container items drop. */
    private void dropSlot(Vehicle.Slot sl, double x, double y, double z, boolean drops) {
        Block b = Blocks.byId(sl.state >> 4);
        World w = s.world;
        if (drops) {
            ItemStack held = s.heldStack();
            Item it = held == null ? null : held.item;
            if (!b.requiresTool || it != null && it.canHarvest(b))
                for (ItemStack d : b.dropsWithEntity(w, (int) Math.floor(x), (int) Math.floor(y), (int) Math.floor(z), sl.state & 15, w.random, sl.be)) w.dropItem(x, y, z, d);
        }
        if (sl.be != null && !sl.be.keepsContents() && sl.be.inventory() != null) {
            var inv = sl.be.inventory();
            for (int k = 0; k < inv.size(); k++) { ItemStack st = inv.get(k); if (st != null) w.dropItem(x, y, z, st); }
        }
        sl.state = 0; sl.be = null; sl.mining = 0;
    }

    /**
     * Uses a carried block. Blocks only know how to work at a world position, so for the click the block (and its block
     * entity) is put into a free air cell next to where it is drawn, used, and taken back with whatever changed (a lever
     * flipped, a trapdoor opened, a cake eaten). Container screens keep the block entity itself, so they work while driving.
     */
    private boolean useSlot(Vehicle v, int i) {
        VehicleModel m = v.vm();
        Vehicle.Slot sl = v.slots[i];
        if (m == null || sl.state == 0) return false;
        World w = s.world;
        float[] p = m.slots.get(i);
        double[] c = new double[3];
        v.toWorld(p[0], p[1] + 0.5, p[2], c);
        int[] cell = airCell(w, c[0], c[1], c[2]);
        if (cell == null) return false;
        int x = cell[0], y = cell[1], z = cell[2];
        Chunk ch = w.chunkAt(x, z);
        Block b = Blocks.byId(sl.state >> 4);
        var rec = w.blockRecorder;
        w.blockRecorder = null;                 // not a world edit: recordings don't see it
        boolean used;
        int after;
        BlockEntity be;
        try {
            w.setBlockKeepEntity(x, y, z, sl.state);
            int key = BlockEntityKey.key(x, y, z);
            if (sl.be != null) { sl.be.x = x; sl.be.y = y; sl.be.z = z; sl.be.world = w; ch.blockEntities.put(key, sl.be); }
            boolean[] r = {false};
            Session.safe(() -> r[0] = b.onUse(s, x, y, z, sl.state & 15));
            used = r[0];
            after = w.getState(x, y, z);
            be = ch.blockEntities.remove(key);
            w.setBlockKeepEntity(x, y, z, 0);
        } finally { w.blockRecorder = rec; }
        if (after != 0 && Blocks.byId(after >> 4).isAir()) after = 0;
        sl.state = after;
        String type = after == 0 ? null : Blocks.byId(after >> 4).blockEntityType();
        sl.be = type != null && be != null && type.equals(be.type()) ? be : type != null && sl.be != null && type.equals(sl.be.type()) ? sl.be : null;
        if (sl.be != null) sl.be.world = null;
        return used;
    }

    /** An air cell (loaded chunk) as close as possible to a point: the cell itself, then upwards, then around. */
    private static int[] airCell(World w, double x, double y, double z) {
        int bx = (int) Math.floor(x), by = (int) Math.floor(y), bz = (int) Math.floor(z);
        if (!w.isLoaded(bx, bz)) return null;
        for (int dy = 0; dy < 12; dy++) if (by + dy < Chunk.HEIGHT && w.getState(bx, by + dy, bz) == 0 && w.getBlockEntity(bx, by + dy, bz) == null) return new int[]{bx, by + dy, bz};
        for (int r = 1; r <= 3; r++)
            for (int dx = -r; dx <= r; dx++)
                for (int dz = -r; dz <= r; dz++)
                    for (int dy = -r; dy <= r; dy++) {
                        int cx = bx + dx, cy = by + dy, cz = bz + dz;
                        if (cy < 0 || cy >= Chunk.HEIGHT || !w.isLoaded(cx, cz)) continue;
                        if (w.getState(cx, cy, cz) == 0) return new int[]{cx, cy, cz};
                    }
        return null;
    }

    // ================================================================== collision for players, NPCs, creatures, items
    /** Collision boxes of vehicles overlapping a region (the player's own vehicle is left out for the player). */
    public static void colliders(World w, double x0, double y0, double z0, double x1, double y1, double z1, derpcraft.world.Collision.Boxes out, Object who) {
        Vehicles vs = current;
        if (vs == null || vs.s.world != w || vs.list.isEmpty()) return;
        for (Vehicle v : vs.list) {
            if (who != null && who == vs.s.player && v == vs.playerVehicle) continue;
            VehicleModel m = v.vm();
            if (m == null) continue;
            double r = Math.hypot(Math.max(-m.minX, m.maxX), Math.max(-m.minZ, m.maxZ));
            if (v.x + r < x0 || v.x - r > x1 || v.z + r < z0 || v.z - r > z1 || v.y > y1 || v.y + m.maxY < y0) continue;
            for (double[] b : v.worldBoxes(m)) {
                if (b[3] <= x0 || b[0] >= x1 || b[5] <= z0 || b[2] >= z1 || b[4] <= y0 || b[1] >= y1) continue;
                out.add(b);
            }
        }
    }

    // ================================================================== sounds
    /** Sound state of a vehicle: its looping voices and what it last did. */
    private static final class Sound {
        derpcraft.audio.Audio.Voice engine, idle, horn, brake, drift;
        derpcraft.audio.Audio.Sample brakeS, driftS;      // the random variant picked when the brake / drift began
        boolean engineOn;
        boolean[] open = new boolean[0];
        float pitch = -1;
        long skidAt = -100;
    }

    private final Map<Vehicle, Sound> sounds = new IdentityHashMap<>();

    /**
     * Sounds shared by all cars, game/cars/sfx/<name>1..5 (.ogg / .wav): dooropen, doorclose, brake, drift. The folder is
     * read again once a second, so files can be added while the game runs.
     */
    private final Map<String, List<Path>> shared = new HashMap<>();
    private long sharedAt = -1000;

    private List<Path> sharedFiles(String name) {
        long now = System.currentTimeMillis();
        if (now - sharedAt > 1000) { shared.clear(); sharedAt = now; }
        return shared.computeIfAbsent(name, n -> {
            List<Path> l = new ArrayList<>();
            Path dir = s.game.gameDir.resolve("cars").resolve("sfx");
            for (int i = 1; i <= 5; i++)
                for (String ext : new String[]{".ogg", ".wav"}) {
                    Path f = dir.resolve(n + i + ext);
                    if (java.nio.file.Files.isRegularFile(f)) { l.add(f); break; }
                }
            return l;
        });
    }

    /** A random sample of a shared sound, or null when there is none. */
    private derpcraft.audio.Audio.Sample sharedSample(String name) {
        List<Path> l = sharedFiles(name);
        return l.isEmpty() ? null : derpcraft.audio.Audio.load(l.get(random.nextInt(l.size())));
    }

    private static derpcraft.audio.Audio.Sample sample(VehicleType t, String ev) {
        Path p = t.sounds.get(ev);
        return p == null ? null : derpcraft.audio.Audio.load(p);
    }

    /**
     * Every frame: engines (pitch climbs with the speed through the gears, the idle sound at standstill), the start, the
     * horn (held by the driving player), doors, skidding and crashes. Quieter with distance and panned left / right from
     * where the camera looks. Silent while the world is paused.
     */
    public void sounds(double lx, double ly, double lz, float lyaw, boolean paused, boolean hornHeld) {
        boolean any = !list.isEmpty() || !sounds.isEmpty();
        if (!any) return;
        long now = s.world.gameTime;
        double ryx = -Math.cos(Math.toRadians(lyaw)), ryz = -Math.sin(Math.toRadians(lyaw));
        for (Vehicle v : list) {
            VehicleType t = v.type();
            Sound sd = sounds.computeIfAbsent(v, k -> new Sound());
            double dx = v.x - lx, dy = v.y + 0.6 - ly, dz = v.z - lz, d = Math.sqrt(dx * dx + dy * dy + dz * dz);
            boolean inside = v == playerVehicle;
            float att = inside ? 0.75f : (float) Math.pow(Math.max(0, 1 - d / 48), 2);
            float pan = inside || d < 0.5 ? 0 : (float) ((dx * ryx + dz * ryz) / d) * 0.8f;
            float vol = t.volume * att * (paused ? 0 : 1);
            // engine
            boolean on = v.hasDriver() || v.replayTick >= now - 2;
            if (on && !sd.engineOn && !paused && att > 0.01f) {
                derpcraft.audio.Audio.play(sample(t, "start"), false, vol, 1, pan);
            }
            sd.engineOn = on;
            float frac = Math.min(1, Math.abs(v.speed) / Math.max(0.05f, v.speed < 0 ? t.reverseSpeed : t.maxSpeed));
            float rpm;
            if (t.gears > 0 && frac > 0.02f) {
                float g = frac * t.gears;
                float inGear = frac >= 1 ? 1 : g - (float) Math.floor(g);
                rpm = 0.25f + 0.75f * inGear * (0.6f + 0.4f * (float) Math.floor(g) / t.gears) + 0.15f * (float) Math.floor(g) / t.gears;
            } else rpm = frac;
            rpm = Math.min(1, rpm + Math.max(0, v.lastThrottle) * 0.08f);
            float target = t.enginePitchMin + (t.enginePitchMax - t.enginePitchMin) * rpm;
            sd.pitch = sd.pitch < 0 ? target : sd.pitch + (target - sd.pitch) * 0.12f;      // the engine has some inertia
            derpcraft.audio.Audio.Sample idleS = sample(t, "idle");
            float idleMix = idleS == null ? 0 : Math.max(0, 1 - frac * 6);
            float load = 0.6f + 0.4f * Math.max(0, Math.abs(v.lastThrottle));
            sd.engine = loop(sd.engine, on && att > 0.005f ? sample(t, "engine") : null, vol * load * (1 - idleMix * 0.85f), sd.pitch, pan);
            sd.idle = loop(sd.idle, on && att > 0.005f ? idleS : null, vol * idleMix * 0.9f, 1, pan);
            // horn
            boolean horn = hornHeld && inside && playerDrives() && !paused;
            sd.horn = loop(sd.horn, horn ? sample(t, "horn") : null, vol, 1, pan);
            // doors
            if (sd.open.length != v.openTarget.length) sd.open = v.openTarget.clone();
            for (int i = 0; i < v.openTarget.length; i++) {
                if (v.openTarget[i] != sd.open[i] && !paused && att > 0.01f) {
                    derpcraft.audio.Audio.Sample ds = sharedSample(v.openTarget[i] ? "dooropen" : "doorclose");
                    if (ds == null) ds = sample(t, v.openTarget[i] ? "door_open" : "door_close");
                    derpcraft.audio.Audio.play(ds, false, vol, 0.95f + random.nextFloat() * 0.1f, pan);
                }
                sd.open[i] = v.openTarget[i];
            }
            // braking and drifting (game/cars/sfx/brake*, drift*): one random variant, looped while it lasts, so the sound is as
            // long as the braking / the drift
            float sv = (paused ? 0 : 1) * att;
            boolean brk = v.braking && !paused;
            if (brk && sd.brake == null) sd.brakeS = sharedSample("brake");
            if (!brk) sd.brakeS = null;
            sd.brake = loop(sd.brake, brk && att > 0.005f ? sd.brakeS : null, sv * Math.min(1, 0.4f + Math.abs(v.speed) * 2), 1, pan);
            boolean drf = v.drift > 0.3f && !paused;
            if (drf && sd.drift == null) sd.driftS = sharedSample("drift");
            if (!drf) sd.driftS = null;
            sd.drift = loop(sd.drift, drf && att > 0.005f ? sd.driftS : null, sv * Math.min(1, v.drift), 1, pan);
            // skidding, crashes
            if (v.skidding && now - sd.skidAt > 14 && !paused && att > 0.01f) {
                derpcraft.audio.Audio.play(sample(t, "skid"), false, vol * Math.min(1, Math.abs(v.speed) * 1.5f), 1, pan);
                sd.skidAt = now;
            }
            v.skidding = false;
            if (v.crashed > 0 && !paused && att > 0.01f) derpcraft.audio.Audio.play(sample(t, "crash"), false, vol * Math.min(1, v.crashed * 1.3f), 0.9f + random.nextFloat() * 0.2f, pan);
            v.crashed = 0;
        }
        // vehicles that are gone
        for (var it = sounds.entrySet().iterator(); it.hasNext(); ) {
            var e = it.next();
            if (list.contains(e.getKey())) continue;
            Sound sd = e.getValue();
            for (var vo : new derpcraft.audio.Audio.Voice[]{sd.engine, sd.idle, sd.horn, sd.brake, sd.drift}) if (vo != null) vo.stop();
            it.remove();
        }
    }

    /** Keeps a looping voice going with these settings, starts it, or stops it (sample null). */
    private static derpcraft.audio.Audio.Voice loop(derpcraft.audio.Audio.Voice v, derpcraft.audio.Audio.Sample smp, float vol, float pitch, float pan) {
        if (smp == null || vol <= 0.0005f) {
            if (v != null) { v.volume = 0; if (smp == null) { v.stop(); return null; } }
            return v;
        }
        if (v == null || !v.playing()) v = derpcraft.audio.Audio.play(smp, true, vol, pitch, pan);
        if (v != null) { v.volume = vol; v.pitch = pitch; v.pan = pan; }
        return v;
    }

    // ================================================================== files
    public void save() {
        Properties p = new Properties();
        p.setProperty("nextId", String.valueOf(nextId));
        if (defaultModel != null) p.setProperty("default", defaultModel);
        for (Vehicle v : list) {
            String k = "v" + v.id + ".";
            p.setProperty(k + "model", v.model);
            p.setProperty(k + "pose", v.x + "," + v.y + "," + v.z + "," + v.yaw + "," + v.pitch + "," + v.roll);
            StringBuilder o = new StringBuilder();
            for (boolean b : v.openTarget) o.append(b ? '1' : '0');
            p.setProperty(k + "open", o.toString());
            if (v.lightsOn) p.setProperty(k + "lights", "1");
            if (v.wrecked) p.setProperty(k + "wreck", v.wreckSeed + "," + v.wreckAge + "," + v.flip);
            for (int i = 0; i < v.slots.length; i++) {
                Vehicle.Slot sl = v.slots[i];
                if (sl.state == 0) continue;
                String be = "";
                if (sl.be != null) {
                    Map<String, String> m = new LinkedHashMap<>();
                    try { sl.be.save(m); } catch (Throwable t) { t.printStackTrace(); }
                    Properties bp = new Properties();
                    bp.putAll(m);
                    StringWriter sw = new StringWriter();
                    try { bp.store(sw, null); } catch (IOException ignored) {}
                    be = sl.be.type() + "|" + Base64.getEncoder().encodeToString(sw.toString().getBytes(StandardCharsets.UTF_8));
                }
                Block b = Blocks.byId(sl.state >> 4);
                p.setProperty(k + "slot" + i, b.name + ":" + (sl.state & 15) + (be.isEmpty() ? "" : "|" + be));
            }
        }
        try { derpcraft.util.SafeFiles.store(p, file, "DerpCraft vehicles"); }
        catch (IOException e) { System.err.println("Cannot save vehicles.txt: " + e); }
    }

    private void load() {
        if (!Files.exists(file)) return;
        Properties p = new Properties();
        try (var r = Files.newBufferedReader(file)) { p.load(r); } catch (IOException e) { System.err.println("Cannot read vehicles.txt: " + e); return; }
        try { nextId = Integer.parseInt(p.getProperty("nextId", "1")); } catch (NumberFormatException ignored) {}
        String def = p.getProperty("default");
        if (def != null && defaultModel == null) defaultModel = def;
        for (String key : p.stringPropertyNames()) {
            if (!key.startsWith("v") || !key.endsWith(".model")) continue;
            int id;
            try { id = Integer.parseInt(key.substring(1, key.indexOf('.'))); } catch (NumberFormatException e) { continue; }
            String k = "v" + id + ".";
            String kind = p.getProperty(key);
            if (kind.contains("/") || kind.toLowerCase(Locale.ROOT).endsWith(".bbmodel")) continue;   // the old built-in cars (removed)
            Vehicle v = new Vehicle(id, kind);
            String[] ps = p.getProperty(k + "pose", "0,64,0,0,0,0").split(",");
            double[] n = new double[6];
            for (int i = 0; i < 6 && i < ps.length; i++) try { n[i] = Double.parseDouble(ps[i]); } catch (NumberFormatException ignored) {}
            v.setPose(n[0], n[1], n[2], (float) n[3]);
            v.pitch = v.prevPitch = (float) n[4]; v.roll = v.prevRoll = (float) n[5];
            v.lightsOn = "1".equals(p.getProperty(k + "lights"));
            String wk = p.getProperty(k + "wreck");
            if (wk != null) {
                String[] wp = wk.split(",");
                try { v.vm(); v.wreck(Long.parseLong(wp[0])); v.wreckAge = Math.min(SMOKE_TICKS, Integer.parseInt(wp[1])); v.charred = 1; v.flip = v.prevFlip = wp.length > 2 ? Float.parseFloat(wp[2]) : 0; } catch (RuntimeException ignored) {}
            }
            String o = p.getProperty(k + "open", "");
            v.openTarget = new boolean[o.length()]; v.open = new float[o.length()]; v.prevOpen = new float[o.length()];
            for (int i = 0; i < o.length(); i++) { v.openTarget[i] = o.charAt(i) == '1'; v.open[i] = v.prevOpen[i] = v.openTarget[i] ? 1 : 0; }
            for (int i = 0; i < v.slots.length; i++) {
                String sv = p.getProperty(k + "slot" + i);
                if (sv == null || sv.isBlank()) continue;
                String[] parts = sv.split("\\|", 3);
                int c = parts[0].lastIndexOf(':');
                Block b = Blocks.byName(c > 0 ? parts[0].substring(0, c) : parts[0]);
                if (b == null || b.isAir()) continue;
                int meta = 0;
                if (c > 0) try { meta = Integer.parseInt(parts[0].substring(c + 1)); } catch (NumberFormatException ignored) {}
                v.slots[i].state = (b.id << 4) | (meta & 15);
                if (parts.length == 3) {
                    BlockEntity be = BlockEntity.create(parts[1]);
                    if (be == null) be = b.createBlockEntity();
                    if (be != null) {
                        Properties bp = new Properties();
                        try { bp.load(new StringReader(new String(Base64.getDecoder().decode(parts[2]), StandardCharsets.UTF_8))); } catch (Exception ignored) {}
                        Map<String, String> m = new HashMap<>();
                        for (String bk : bp.stringPropertyNames()) m.put(bk, bp.getProperty(bk));
                        try { be.load(m); } catch (Throwable t) { t.printStackTrace(); }
                        v.slots[i].be = be;
                    }
                } else if (b.blockEntityType() != null) v.slots[i].be = b.createBlockEntity();
            }
            list.add(v);
            nextId = Math.max(nextId, id + 1);
        }
        list.sort(Comparator.comparingInt(v -> v.id));
    }

    public void close() {
        if (playerVehicle != null) exitPlayer();
        derpcraft.audio.Audio.stopAll();
        sounds.clear();
        save();
        if (current == this) current = null;
    }
}
