package derpcraft.patch;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/** What a {@link Hook} sees: the object, the arguments, the result. */
public final class Call {
    final String method;
    final Object self, target;
    final Object[] args;
    Object ret;
    boolean cancelled;

    Call(String method, Object self, Object target, Object[] args, Object ret) {
        this.method = method; this.self = self; this.target = target; this.args = args; this.ret = ret;
    }

    /** The patched method ("derpcraft.game.Player.tick"). */
    public String method() { return method; }

    /** The object whose method is running (null in static methods). */
    public Object self() { return self; }

    @SuppressWarnings("unchecked")
    public <T> T self(Class<T> type) { return (T) self; }

    /** beforeCall: the object being called (null for static calls). */
    public Object target() { return target; }

    public int argCount() { return args.length; }

    public Object arg(int i) { return args[i]; }

    @SuppressWarnings("unchecked")
    public <T> T arg(int i, Class<T> type) { return (T) args[i]; }

    public int argInt(int i) { return PatchRuntime.toI(args[i]); }
    public long argLong(int i) { return PatchRuntime.toJ(args[i]); }
    public float argFloat(int i) { return PatchRuntime.toF(args[i]); }
    public double argDouble(int i) { return PatchRuntime.toD(args[i]); }
    public boolean argBool(int i) { return PatchRuntime.toZ(args[i]); }

    /** Changes an argument (head / beforeCall). Numbers are converted to the parameter type. */
    public void setArg(int i, Object value) { args[i] = value; }

    /** Skips the method (head) or the call (beforeCall). For non-void methods give a result with {@link #cancel(Object)}. */
    public void cancel() { cancelled = true; }

    public void cancel(Object result) { cancelled = true; ret = result; }

    public boolean isCancelled() { return cancelled; }

    /** tail: the value the method is returning. */
    public Object returnValue() { return ret; }

    /** tail / replace: changes the returned value. */
    public void setReturn(Object value) { ret = value; }

    // ------------------------------------------------------------------ reflection helpers (private fields too)
    /** Reads a field of self (searches superclasses, private fields work). */
    public Object field(String name) { return get(self, name); }

    public void setField(String name, Object value) { set(self, name, value); }

    /** Calls a method of self by name (first one with that name and argument count). */
    public Object invoke(String name, Object... a) { return call(self, name, a); }

    public static Object get(Object obj, String name) {
        try { return find(obj, name).get(obj instanceof Class<?> ? null : obj); }
        catch (ReflectiveOperationException e) { throw new RuntimeException(e); }
    }

    public static void set(Object obj, String name, Object value) {
        try {
            Field f = find(obj, name);
            Class<?> t = f.getType();
            Object o = obj instanceof Class<?> ? null : obj;
            if (t == int.class) f.setInt(o, PatchRuntime.toI(value));
            else if (t == float.class) f.setFloat(o, PatchRuntime.toF(value));
            else if (t == double.class) f.setDouble(o, PatchRuntime.toD(value));
            else if (t == long.class) f.setLong(o, PatchRuntime.toJ(value));
            else if (t == boolean.class) f.setBoolean(o, PatchRuntime.toZ(value));
            else f.set(o, value);
        } catch (ReflectiveOperationException e) { throw new RuntimeException(e); }
    }

    public static Object call(Object obj, String name, Object... a) {
        Class<?> c = obj instanceof Class<?> k ? k : obj.getClass();
        for (; c != null; c = c.getSuperclass())
            for (Method m : c.getDeclaredMethods())
                if (m.getName().equals(name) && m.getParameterCount() == a.length) {
                    try { m.setAccessible(true); return m.invoke(obj instanceof Class<?> ? null : obj, a); }
                    catch (ReflectiveOperationException e) { throw new RuntimeException(e); }
                }
        throw new RuntimeException("No method " + name + " with " + a.length + " arguments");
    }

    private static Field find(Object obj, String name) throws NoSuchFieldException {
        Class<?> c = obj instanceof Class<?> k ? k : obj.getClass();
        for (; c != null; c = c.getSuperclass()) {
            try { Field f = c.getDeclaredField(name); f.setAccessible(true); return f; } catch (NoSuchFieldException ignored) {}
        }
        throw new NoSuchFieldException(name);
    }
}
