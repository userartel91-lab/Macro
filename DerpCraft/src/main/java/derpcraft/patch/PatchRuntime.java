package derpcraft.patch;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Called by the injected code. Not for mods. */
public final class PatchRuntime {
    private PatchRuntime() {}

    record Point(PatchRegistry.Def def, String method) {}

    static final List<Point> points = new ArrayList<>();
    private static final Set<Integer> reported = new HashSet<>();

    static synchronized int add(PatchRegistry.Def def, String method) { points.add(new Point(def, method)); return points.size() - 1; }

    private static void run(Point p, Call c) {
        try { p.def().hook().run(c); }
        catch (Throwable t) {
            if (reported.add(points.indexOf(p))) {
                System.err.println("[patch] " + p.def().modId() + " " + p.def().kind() + " " + p.method() + " failed: " + t);
                t.printStackTrace();
            }
        }
    }

    public static Call head(int id, Object self, Object[] args) {
        Point p = points.get(id);
        Call c = new Call(p.method(), self, null, args, null);
        run(p, c);
        if (p.def().kind() == PatchRegistry.Kind.REPLACE) c.cancelled = true;
        return c;
    }

    public static Object tail(Object ret, int id, Object self, Object[] args) {
        Point p = points.get(id);
        Call c = new Call(p.method(), self, null, args, ret);
        run(p, c);
        return c.ret;
    }

    public static Call call(int id, Object self, Object target, Object[] args) {
        Point p = points.get(id);
        Call c = new Call(p.method(), self, target, args, null);
        run(p, c);
        return c;
    }

    // conversions used by the injected code (lenient: any Number, Boolean, Character, null = 0)
    public static int toI(Object o) { return o instanceof Number n ? n.intValue() : o instanceof Character ch ? ch : o instanceof Boolean b ? (b ? 1 : 0) : 0; }
    public static long toJ(Object o) { return o instanceof Number n ? n.longValue() : toI(o); }
    public static float toF(Object o) { return o instanceof Number n ? n.floatValue() : toI(o); }
    public static double toD(Object o) { return o instanceof Number n ? n.doubleValue() : toI(o); }
    public static boolean toZ(Object o) { return o instanceof Boolean b ? b : o instanceof Number n && n.intValue() != 0; }
    public static short toS(Object o) { return (short) toI(o); }
    public static byte toB(Object o) { return (byte) toI(o); }
    public static char toC(Object o) { return o instanceof Character ch ? ch : (char) toI(o); }
}
