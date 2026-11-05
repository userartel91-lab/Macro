package derpcraft.npc;

import java.util.ArrayList;
import java.util.List;

/**
 * An actor's part in one scene: several takes (one active), each with its own timing on the scene timeline
 * (start delay, trimmed start / end, speed) and placement (moved / turned), plus pose keyframes.
 */
public final class Track {
    /** One recorded take and how it sits on the timeline. */
    public static final class Take {
        public Recording rec;
        public String file;
        /** Scene ticks before it starts. */
        public int offset;
        /** Ticks cut from the start / end of the recording. */
        public int trimStart, trimEnd;
        /** Playback speed (0.25 .. 4). */
        public float speed = 1;
        /** Moves / turns the whole take (turn around its first frame). */
        public double dx, dy, dz;
        public float rot;

        public Take(Recording rec, String file) { this.rec = rec; this.file = file; }

        public Take copySettingsFrom(Take o) {
            offset = o.offset; trimStart = o.trimStart; trimEnd = o.trimEnd; speed = o.speed; dx = o.dx; dy = o.dy; dz = o.dz; rot = o.rot;
            return this;
        }

        /** Recording ticks that play. */
        public int used() { return Math.max(1, rec.length() - trimStart - trimEnd); }

        /** Scene tick at which it ends. */
        public int end() { return offset + (int) Math.ceil(used() / speed); }

        /** Recording tick (fractional) at a scene tick. */
        public double sourceTick(double sceneTick) {
            double t = trimStart + (sceneTick - offset) * speed;
            return Math.max(trimStart, Math.min(trimStart + used() - 1, t));
        }

        /** Scene tick at which a recording tick happens. */
        public double sceneTick(double sourceTick) { return offset + (sourceTick - trimStart) / speed; }

        /** True if an action at this recording tick is inside the trimmed part. */
        public boolean inside(int sourceTick) { return sourceTick >= trimStart && sourceTick < trimStart + used(); }

        /** The (interpolated, moved, turned) frame at a scene tick. */
        public Recording.Frame frame(double sceneTick) {
            double t = sourceTick(sceneTick);
            int i = (int) Math.floor(t);
            Recording.Frame a = rec.frame(i), b = rec.frame(i + 1);
            if (a == null) return null;
            Recording.Frame f = a.copy();
            double k = t - i;
            if (b != null && k > 0 && i + 1 < rec.length()) {
                f.x = a.x + (b.x - a.x) * k; f.y = a.y + (b.y - a.y) * k; f.z = a.z + (b.z - a.z) * k;
                f.yaw = a.yaw + Actor.wrap(b.yaw - a.yaw) * (float) k; f.pitch = a.pitch + (b.pitch - a.pitch) * (float) k;
                if (a.vehicle >= 0 && b.vehicle == a.vehicle && b.seat == a.seat) {
                    f.vyaw = a.vyaw + Actor.wrap(b.vyaw - a.vyaw) * (float) k;
                    f.vpitch = a.vpitch + (b.vpitch - a.vpitch) * (float) k;
                    f.vroll = a.vroll + (b.vroll - a.vroll) * (float) k;
                    f.steer = a.steer + (b.steer - a.steer) * (float) k;
                }
            }
            transform(f);
            return f;
        }

        /** Applies the move / turn to a frame (in place). */
        public void transform(Recording.Frame f) {
            if (rot != 0 && rec.length() > 0) {
                Recording.Frame o = rec.frame(0);
                double r = Math.toRadians(rot), c = Math.cos(r), s = Math.sin(r);
                double x = f.x - o.x, z = f.z - o.z;
                f.x = o.x + x * c - z * s;
                f.z = o.z + x * s + z * c;
                f.yaw += rot;
                if (f.vehicle >= 0) f.vyaw += rot;
            }
            f.x += dx; f.y += dy; f.z += dz;
        }

        /** Block position after the move / turn. */
        public int[] transformBlock(int x, int y, int z) {
            if (rot == 0 && dx == 0 && dy == 0 && dz == 0) return new int[]{x, y, z};
            Recording.Frame f = new Recording.Frame();
            f.x = x + 0.5; f.y = y; f.z = z + 0.5;
            transform(f);
            return new int[]{(int) Math.floor(f.x), (int) Math.round(f.y), (int) Math.floor(f.z)};
        }

        /** Direction after the turn. */
        public double[] transformDir(double dx, double dz) {
            if (rot == 0) return new double[]{dx, dz};
            double r = Math.toRadians(rot), c = Math.cos(r), s = Math.sin(r);
            return new double[]{dx * c - dz * s, dx * s + dz * c};
        }
    }

    /** A pose keyframe on the scene timeline: from 'tick' the actor blends into 'pose' over 'blend' ticks with a curve. */
    public static final class PoseKey {
        public int tick, curve, blend = 10;
        public String pose;
        public PoseKey(int tick, String pose, int curve, int blend) { this.tick = tick; this.pose = pose; this.curve = curve; this.blend = blend; }
    }

    public static final String[] CURVES = {"Linear", "Smooth", "Ease in", "Ease out", "Instant"};

    public static float curve(int c, float t) {
        t = Math.max(0, Math.min(1, t));
        return switch (c) {
            case 1 -> t * t * (3 - 2 * t);
            case 2 -> t * t;
            case 3 -> 1 - (1 - t) * (1 - t);
            case 4 -> 1;
            default -> t;
        };
    }

    public final List<Take> takes = new ArrayList<>();
    public int active;
    public final List<PoseKey> keys = new ArrayList<>();

    public Take take() { return takes.isEmpty() ? null : takes.get(Math.max(0, Math.min(active, takes.size() - 1))); }

    public Recording rec() { Take t = take(); return t == null ? null : t.rec; }

    public int length() { Take t = take(); return t == null ? 0 : t.end(); }

    /** Pose keyframes as they apply at a scene tick: {from pose, to pose, blend 0..1}. */
    public Object[] poseAt(int tick) {
        PoseKey cur = null, prev = null;
        List<PoseKey> sorted = new ArrayList<>(keys);
        sorted.sort((a, b) -> Integer.compare(a.tick, b.tick));
        for (PoseKey k : sorted) { if (k.tick > tick) break; prev = cur; cur = k; }
        if (cur == null) return null;
        float t = cur.blend <= 0 ? 1 : curve(cur.curve, (tick - cur.tick) / (float) cur.blend);
        return new Object[]{prev == null ? "" : prev.pose, cur.pose, t};
    }

    public void sortKeys() { keys.sort((a, b) -> Integer.compare(a.tick, b.tick)); }
}
