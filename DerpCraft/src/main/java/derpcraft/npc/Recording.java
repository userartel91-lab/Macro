package derpcraft.npc;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * A recorded performance: the player's position, look and actions for every game tick (20 per second), plus the
 * blocks they changed and what they said in chat. Played back by an {@link Actor}.
 */
public final class Recording {
    public static final int FLAG_SNEAK = 1, FLAG_SWING = 2, FLAG_SPRINT = 4, FLAG_FLYING = 8, FLAG_GROUND = 16, FLAG_SIT = 32, FLAG_WAVE = 64;
    /** The arm reaches over the shoulder for the gun on the back (Left Alt + F). */
    public static final int FLAG_BACK_REACH = 128;
    /** (version 7) the player was hurt this tick (the actor flashes red on replay). */
    public static final int FLAG_HURT = 256;

    public static final class Frame {
        public double x, y, z;
        public float yaw, pitch;
        public int flags;
        /** Held item name ("" = empty hand). */
        public String held = "";
        /** The gun carried on the back (item name, "" = none; Left Alt + F). */
        public String back = "";
        /** The magazines in the six belt pouches (Left Alt + E): "item:count" for each, joined by ';' ("" = none at all). */
        public String pouches = "";
        /** Gesture / pose name from the pose library ("" = none). */
        public String pose = "";
        /** Face: 0 normal, see {@link Emotions}. */
        public int emotion;
        /** In a vehicle: its id (-1 = not in one), the seat, its yaw / pitch / roll, the steering and the open doors (bits). */
        public int vehicle = -1, seat;
        public float vyaw, vpitch, vroll, steer;
        public int doors;
        /** (version 6) in a vehicle: the roll-over angle, how much the tyres slide, bit 0 = lights on, bit 1 = blown up. */
        public float vflip, vslip;
        public int vflags;
        /** (version 7) the body posture ({@link derpcraft.game.Stance} ordinal: stand, sneak, deep crouch, sit, crawl, lie). */
        public int stance;
        /** (version 7) fist fighting (Left Alt + Q): the guard and both punches, 0..1. */
        public float fists, punchL, punchR;

        public Frame copy() {
            Frame f = new Frame();
            f.x = x; f.y = y; f.z = z; f.yaw = yaw; f.pitch = pitch; f.flags = flags; f.held = held; f.back = back; f.pouches = pouches; f.pose = pose; f.emotion = emotion;
            f.vehicle = vehicle; f.seat = seat; f.vyaw = vyaw; f.vpitch = vpitch; f.vroll = vroll; f.steer = steer; f.doors = doors;
            f.vflip = vflip; f.vslip = vslip; f.vflags = vflags;
            f.stance = stance; f.fists = fists; f.punchL = punchL; f.punchR = punchR;
            return f;
        }
    }

    /**
     * Something done with items: 0 picked up, 1 dropped, 2 shot / threw a projectile (item = projectile id, dx..dz direction, speed in count/100),
     * 3 gun shot, 4 reload, 5 gun melee, 6 a hit on a creature or an NPC (item = "npc:ID" or "mob:TYPE", count = damage x 10, x..z = where the target was).
     */
    public record ItemAction(int tick, int kind, String item, int count, double x, double y, double z, double dx, double dy, double dz) {}

    /** A block change: tick, position, state before and after. */
    public record BlockAction(int tick, int x, int y, int z, int before, int after) {}

    /** A chat line said at a tick. */
    public record ChatAction(int tick, String text) {}

    public final List<Frame> frames = new ArrayList<>();
    public final List<BlockAction> blocks = new ArrayList<>();
    public final List<ChatAction> chat = new ArrayList<>();
    public final List<ItemAction> items = new ArrayList<>();
    /** (version 8) the vehicles near the player when the take began and how many bullet holes each had then (vehicle id -> holes). */
    public final Map<Integer, Integer> vehicleHoles = new LinkedHashMap<>();

    public int length() { return frames.size(); }

    public float seconds() { return frames.size() / 20f; }

    public Frame frame(int tick) {
        if (frames.isEmpty()) return null;
        return frames.get(Math.max(0, Math.min(frames.size() - 1, tick)));
    }

    // ------------------------------------------------------------------ files
    private static final int MAGIC = 0x44435243, VERSION = 8;  // "DCRC"

    public void save(Path file) throws IOException {
        Files.createDirectories(file.getParent());
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(new GZIPOutputStream(Files.newOutputStream(tmp))))) {
            out.writeInt(MAGIC);
            out.writeInt(VERSION);
            out.writeInt(frames.size());
            String last = null, lastPose = null, lastBack = null, lastPouches = null;
            for (Frame f : frames) {
                out.writeDouble(f.x); out.writeDouble(f.y); out.writeDouble(f.z);
                out.writeFloat(f.yaw); out.writeFloat(f.pitch);
                out.writeShort(f.flags);
                boolean same = f.held.equals(last);
                out.writeBoolean(same);
                if (!same) out.writeUTF(f.held);
                last = f.held;
                boolean samePose = f.pose.equals(lastPose);
                out.writeBoolean(samePose);
                if (!samePose) out.writeUTF(f.pose);
                lastPose = f.pose;
                out.writeByte(f.emotion);
                out.writeInt(f.vehicle);
                if (f.vehicle >= 0) {
                    out.writeByte(f.seat);
                    out.writeFloat(f.vyaw); out.writeFloat(f.vpitch); out.writeFloat(f.vroll); out.writeFloat(f.steer);
                    out.writeInt(f.doors);
                    out.writeFloat(f.vflip); out.writeFloat(f.vslip); out.writeByte(f.vflags);
                }
                boolean sameBack = f.back.equals(lastBack);
                out.writeBoolean(sameBack);
                if (!sameBack) out.writeUTF(f.back);
                lastBack = f.back;
                boolean samePouches = f.pouches.equals(lastPouches);
                out.writeBoolean(samePouches);
                if (!samePouches) out.writeUTF(f.pouches);
                lastPouches = f.pouches;
                out.writeByte(f.stance);
                out.writeByte(unit(f.fists)); out.writeByte(unit(f.punchL)); out.writeByte(unit(f.punchR));
            }
            out.writeInt(blocks.size());
            for (BlockAction b : blocks) { out.writeInt(b.tick); out.writeInt(b.x); out.writeInt(b.y); out.writeInt(b.z); out.writeInt(b.before); out.writeInt(b.after); }
            out.writeInt(chat.size());
            for (ChatAction c : chat) { out.writeInt(c.tick); out.writeUTF(c.text); }
            out.writeInt(items.size());
            for (ItemAction a : items) {
                out.writeInt(a.tick()); out.writeByte(a.kind()); out.writeUTF(a.item()); out.writeInt(a.count());
                out.writeDouble(a.x()); out.writeDouble(a.y()); out.writeDouble(a.z()); out.writeDouble(a.dx()); out.writeDouble(a.dy()); out.writeDouble(a.dz());
            }
            out.writeInt(vehicleHoles.size());
            for (var e : vehicleHoles.entrySet()) { out.writeInt(e.getKey()); out.writeInt(e.getValue()); }
        }
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
    }

    private static int unit(float v) { return Math.round(Math.max(0, Math.min(1, v)) * 255); }

    /** Actions recorded after the last frame (the take stopped in the same tick) move onto the last frame, so they still play. */
    /** Keeps the actions in tick order (the playback finds them by binary search). Stable: same-tick actions keep their order. */
    public void sortActions() {
        blocks.sort(java.util.Comparator.comparingInt(BlockAction::tick));
        chat.sort(java.util.Comparator.comparingInt(ChatAction::tick));
        items.sort(java.util.Comparator.comparingInt(ItemAction::tick));
    }

    public void clampTicks() {
        sortActions();
        int last = Math.max(0, frames.size() - 1);
        blocks.replaceAll(b -> b.tick() > last ? new BlockAction(last, b.x(), b.y(), b.z(), b.before(), b.after()) : b);
        chat.replaceAll(c -> c.tick() > last ? new ChatAction(last, c.text()) : c);
        items.replaceAll(a -> a.tick() > last ? new ItemAction(last, a.kind(), a.item(), a.count(), a.x(), a.y(), a.z(), a.dx(), a.dy(), a.dz()) : a);
    }

    public static Recording load(Path file) throws IOException {
        Recording r = new Recording();
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(new GZIPInputStream(Files.newInputStream(file))))) {
            if (in.readInt() != MAGIC) throw new IOException("not a recording");
            int version = in.readInt();
            int n = in.readInt();
            String last = "", lastPose = "", lastBack = "", lastPouches = "";
            for (int i = 0; i < n; i++) {
                Frame f = new Frame();
                f.x = in.readDouble(); f.y = in.readDouble(); f.z = in.readDouble();
                f.yaw = in.readFloat(); f.pitch = in.readFloat();
                f.flags = in.readShort();
                if (!in.readBoolean()) last = in.readUTF();
                f.held = last;
                if (version >= 2) {
                    if (!in.readBoolean()) lastPose = in.readUTF();
                    f.pose = lastPose;
                    f.emotion = in.readByte();
                }
                if (version >= 3) {
                    f.vehicle = in.readInt();
                    if (f.vehicle >= 0) {
                        f.seat = in.readByte();
                        f.vyaw = in.readFloat(); f.vpitch = in.readFloat(); f.vroll = in.readFloat(); f.steer = in.readFloat();
                        f.doors = in.readInt();
                        if (version >= 6) { f.vflip = in.readFloat(); f.vslip = in.readFloat(); f.vflags = in.readByte(); }
                    }
                }
                if (version >= 4) {
                    if (!in.readBoolean()) lastBack = in.readUTF();
                    f.back = lastBack;
                }
                if (version >= 5) {
                    if (!in.readBoolean()) lastPouches = in.readUTF();
                    f.pouches = lastPouches;
                }
                if (version >= 7) {
                    f.stance = in.readByte();
                    f.fists = (in.readByte() & 255) / 255f; f.punchL = (in.readByte() & 255) / 255f; f.punchR = (in.readByte() & 255) / 255f;
                }
                r.frames.add(f);
            }
            int nb = in.readInt();
            for (int i = 0; i < nb; i++) r.blocks.add(new BlockAction(in.readInt(), in.readInt(), in.readInt(), in.readInt(), in.readInt(), in.readInt()));
            int nc = in.readInt();
            for (int i = 0; i < nc; i++) r.chat.add(new ChatAction(in.readInt(), in.readUTF()));
            if (version >= 2) {
                int ni = in.readInt();
                for (int i = 0; i < ni; i++)
                    r.items.add(new ItemAction(in.readInt(), in.readByte(), in.readUTF(), in.readInt(), in.readDouble(), in.readDouble(), in.readDouble(), in.readDouble(), in.readDouble(), in.readDouble()));
            }
            if (version >= 8) {
                int nv = in.readInt();
                for (int i = 0; i < nv; i++) r.vehicleHoles.put(in.readInt(), in.readInt());
            }
        }
        r.sortActions();
        return r;
    }
}
