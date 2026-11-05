package derpcraft.patch;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;

import java.io.InputStream;
import java.util.*;

/** All patches mods asked for, and the bytecode rewriting that applies them when a class loads. */
public final class PatchRegistry implements Opcodes {
    private PatchRegistry() {}

    enum Kind { HEAD, TAIL, REPLACE, CALL, CONSTANT }

    record Def(String modId, Kind kind, String cls, String method, String callOwner, String callName, Object value, Object replacement, Hook hook) {}

    private static final Map<String, List<Def>> byClass = new HashMap<>();
    private static final List<String> errors = new ArrayList<>();
    /** True once the patching class loader runs the game (patches can apply). */
    static volatile boolean active;
    static PatchLoader loader;

    public static boolean active() { return active; }

    public static List<String> errors() { synchronized (errors) { return new ArrayList<>(errors); } }

    public static int count() { synchronized (byClass) { int n = 0; for (List<Def> l : byClass.values()) n += l.size(); return n; } }

    static void error(String s) { synchronized (errors) { errors.add(s); } System.err.println("[patch] " + s); }

    /** A Patcher that records into the registry for one mod. */
    public static Patcher forMod(String modId) {
        return new Patcher() {
            public void head(String c, String m, Hook h) { add(new Def(modId, Kind.HEAD, c, m, null, null, null, null, h)); }
            public void tail(String c, String m, Hook h) { add(new Def(modId, Kind.TAIL, c, m, null, null, null, null, h)); }
            public void replace(String c, String m, Hook h) { add(new Def(modId, Kind.REPLACE, c, m, null, null, null, null, h)); }
            public void beforeCall(String c, String m, String co, String cm, Hook h) { add(new Def(modId, Kind.CALL, c, m, co, cm, null, null, h)); }
            public void constant(String c, String m, Object v, Object r) {
                if (v == null || r == null || v.getClass() != r.getClass()) { error(modId + ": constant patch needs two values of the same type"); return; }
                add(new Def(modId, Kind.CONSTANT, c, m, null, null, v, r, null));
            }
            public String modId() { return modId; }
        };
    }

    private static void add(Def d) {
        if (d.cls == null || d.method == null || (d.hook == null && d.kind != Kind.CONSTANT)) { error(d.modId + ": incomplete patch " + d); return; }
        if (d.method.startsWith("<init>") && (d.kind == Kind.HEAD || d.kind == Kind.REPLACE)) { error(d.modId + ": constructors support only tail / beforeCall / constant (" + d.cls + ")"); return; }
        if (loader != null && loader.getResource(d.cls.replace('.', '/') + ".class") == null) { error(d.modId + ": class " + d.cls + " not found, patch ignored"); return; }
        if (loader != null && loader.isLoaded(d.cls)) { error(d.modId + ": " + d.cls + " was already loaded, patch ignored (don't use game classes in patches())"); return; }
        synchronized (byClass) { byClass.computeIfAbsent(d.cls, k -> new ArrayList<>()).add(d); }
    }

    static boolean has(String cls) { synchronized (byClass) { return byClass.containsKey(cls); } }

    // ================================================================================== transformation
    static byte[] transform(String cls, byte[] bytes) {
        List<Def> defs;
        synchronized (byClass) { defs = byClass.get(cls); if (defs == null) return bytes; defs = new ArrayList<>(defs); }
        ClassNode cn = new ClassNode();
        new ClassReader(bytes).accept(cn, ClassReader.SKIP_FRAMES);
        Set<Def> used = new HashSet<>();
        for (MethodNode mn : cn.methods) {
            if ((mn.access & (ACC_ABSTRACT | ACC_NATIVE)) != 0) continue;
            for (Def d : defs) {
                if (!matches(mn, d.method)) continue;
                String where = cls + "." + mn.name;
                try {
                    boolean ok = switch (d.kind) {
                        case HEAD, REPLACE -> { head(mn, PatchRuntime.add(d, where)); yield true; }
                        case TAIL -> { tail(mn, PatchRuntime.add(d, where)); yield true; }
                        case CALL -> call(mn, d, where);
                        case CONSTANT -> constant(mn, d);
                    };
                    if (ok) used.add(d);
                } catch (Exception e) {
                    error(d.modId + ": cannot patch " + where + ": " + e);
                }
            }
        }
        for (Def d : defs) if (!used.contains(d)) error(d.modId + ": " + d.kind.name().toLowerCase(Locale.ROOT) + " patch found nothing to change in " + cls + "." + d.method
                + (d.kind == Kind.CALL ? " (call " + d.callOwner + "." + d.callName + ")" : d.kind == Kind.CONSTANT ? " (constant " + d.value + ")" : ""));
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS) {
            @Override protected String getCommonSuperClass(String a, String b) { return commonSuper(a, b); }
        };
        cn.accept(cw);
        return cw.toByteArray();
    }

    /** "name" matches every overload; "name(int,Block)" matches parameter simple names. */
    static boolean matches(MethodNode mn, String spec) {
        int p = spec.indexOf('(');
        String name = p < 0 ? spec.trim() : spec.substring(0, p).trim();
        if (!name.equals("*") && !name.equals(mn.name)) return false;
        if (name.equals("*") && (mn.name.startsWith("<"))) return false;
        if (p < 0) return true;
        String inner = spec.substring(p + 1, spec.lastIndexOf(')')).trim();
        String[] want = inner.isEmpty() ? new String[0] : inner.split(",");
        Type[] at = Type.getArgumentTypes(mn.desc);
        if (want.length != at.length) return false;
        for (int i = 0; i < at.length; i++) {
            String cn = at[i].getClassName();                                  // e.g. derpcraft.block.Block, int, float[]
            String simple = cn.substring(cn.lastIndexOf('.') + 1).replace('$', '.');
            String w = want[i].trim();
            if (!w.equals(cn) && !w.equals(simple) && !simple.endsWith("." + w)) return false;
        }
        return true;
    }

    // ---------------------------------------------------------------------------------- code generation helpers
    private static final String RT = "derpcraft/patch/PatchRuntime", CALL = "derpcraft/patch/Call";

    private static int newLocal(MethodNode mn, Type t) { int i = mn.maxLocals; mn.maxLocals += t.getSize(); return i; }

    private static AbstractInsnNode pushInt(int v) {
        if (v >= -1 && v <= 5) return new InsnNode(ICONST_0 + v);
        if (v >= Byte.MIN_VALUE && v <= Byte.MAX_VALUE) return new IntInsnNode(BIPUSH, v);
        if (v >= Short.MIN_VALUE && v <= Short.MAX_VALUE) return new IntInsnNode(SIPUSH, v);
        return new LdcInsnNode(v);
    }

    /** Boxes the value on top of the stack. */
    private static void box(InsnList il, Type t) {
        String owner = switch (t.getSort()) {
            case Type.INT -> "java/lang/Integer"; case Type.FLOAT -> "java/lang/Float"; case Type.DOUBLE -> "java/lang/Double";
            case Type.LONG -> "java/lang/Long"; case Type.BOOLEAN -> "java/lang/Boolean"; case Type.BYTE -> "java/lang/Byte";
            case Type.SHORT -> "java/lang/Short"; case Type.CHAR -> "java/lang/Character"; default -> null;
        };
        if (owner != null) il.add(new MethodInsnNode(INVOKESTATIC, owner, "valueOf", "(" + t.getDescriptor() + ")L" + owner + ";", false));
    }

    /** Turns the Object on top of the stack into type t. */
    private static void unbox(InsnList il, Type t) {
        String m = switch (t.getSort()) {
            case Type.INT -> "toI"; case Type.FLOAT -> "toF"; case Type.DOUBLE -> "toD"; case Type.LONG -> "toJ";
            case Type.BOOLEAN -> "toZ"; case Type.BYTE -> "toB"; case Type.SHORT -> "toS"; case Type.CHAR -> "toC"; default -> null;
        };
        if (m != null) il.add(new MethodInsnNode(INVOKESTATIC, RT, m, "(Ljava/lang/Object;)" + t.getDescriptor(), false));
        else if (t.getSort() == Type.ARRAY) il.add(new TypeInsnNode(CHECKCAST, t.getDescriptor()));
        else if (!t.getInternalName().equals("java/lang/Object")) il.add(new TypeInsnNode(CHECKCAST, t.getInternalName()));
    }

    /** new Object[]{ locals... } on the stack. */
    private static void argArray(InsnList il, Type[] types, int[] locals) {
        il.add(pushInt(types.length));
        il.add(new TypeInsnNode(ANEWARRAY, "java/lang/Object"));
        for (int i = 0; i < types.length; i++) {
            il.add(new InsnNode(DUP));
            il.add(pushInt(i));
            il.add(new VarInsnNode(types[i].getOpcode(ILOAD), locals[i]));
            box(il, types[i]);
            il.add(new InsnNode(AASTORE));
        }
    }

    private static int[] argLocals(MethodNode mn, Type[] at) {
        int[] l = new int[at.length];
        int idx = (mn.access & ACC_STATIC) != 0 ? 0 : 1;
        for (int i = 0; i < at.length; i++) { l[i] = idx; idx += at[i].getSize(); }
        return l;
    }

    private static AbstractInsnNode self(MethodNode mn) { return (mn.access & ACC_STATIC) != 0 ? new InsnNode(ACONST_NULL) : new VarInsnNode(ALOAD, 0); }

    // ---------------------------------------------------------------------------------- head / replace
    private static void head(MethodNode mn, int id) {
        Type[] at = Type.getArgumentTypes(mn.desc);
        Type rt = Type.getReturnType(mn.desc);
        int[] al = argLocals(mn, at);
        int arr = newLocal(mn, Type.getType(Object[].class)), call = newLocal(mn, Type.getType(Object.class));
        InsnList il = new InsnList();
        il.add(pushInt(id));
        il.add(self(mn));
        argArray(il, at, al);
        il.add(new InsnNode(DUP));
        il.add(new VarInsnNode(ASTORE, arr));
        il.add(new MethodInsnNode(INVOKESTATIC, RT, "head", "(ILjava/lang/Object;[Ljava/lang/Object;)L" + CALL + ";", false));
        il.add(new VarInsnNode(ASTORE, call));
        for (int i = 0; i < at.length; i++) {      // arguments the hook changed
            il.add(new VarInsnNode(ALOAD, arr));
            il.add(pushInt(i));
            il.add(new InsnNode(AALOAD));
            unbox(il, at[i]);
            il.add(new VarInsnNode(at[i].getOpcode(ISTORE), al[i]));
        }
        LabelNode go = new LabelNode();
        il.add(new VarInsnNode(ALOAD, call));
        il.add(new MethodInsnNode(INVOKEVIRTUAL, CALL, "isCancelled", "()Z", false));
        il.add(new JumpInsnNode(IFEQ, go));
        if (rt.getSort() == Type.VOID) il.add(new InsnNode(RETURN));
        else {
            il.add(new VarInsnNode(ALOAD, call));
            il.add(new MethodInsnNode(INVOKEVIRTUAL, CALL, "returnValue", "()Ljava/lang/Object;", false));
            unbox(il, rt);
            il.add(new InsnNode(rt.getOpcode(IRETURN)));
        }
        il.add(go);
        mn.instructions.insert(il);
    }

    // ---------------------------------------------------------------------------------- tail
    private static void tail(MethodNode mn, int id) {
        Type[] at = Type.getArgumentTypes(mn.desc);
        Type rt = Type.getReturnType(mn.desc);
        int[] al = argLocals(mn, at);
        List<AbstractInsnNode> returns = new ArrayList<>();
        for (AbstractInsnNode n : mn.instructions) if (n.getOpcode() >= IRETURN && n.getOpcode() <= RETURN) returns.add(n);
        for (AbstractInsnNode r : returns) {
            InsnList il = new InsnList();
            if (rt.getSort() == Type.VOID) il.add(new InsnNode(ACONST_NULL));
            else box(il, rt);
            il.add(pushInt(id));
            il.add(self(mn));
            argArray(il, at, al);
            il.add(new MethodInsnNode(INVOKESTATIC, RT, "tail", "(Ljava/lang/Object;ILjava/lang/Object;[Ljava/lang/Object;)Ljava/lang/Object;", false));
            if (rt.getSort() == Type.VOID) il.add(new InsnNode(POP));
            else unbox(il, rt);
            mn.instructions.insertBefore(r, il);
        }
    }

    // ---------------------------------------------------------------------------------- before call
    private static boolean ownerMatches(String internal, String want) {
        if (want == null || want.equals("*")) return true;
        String dotted = internal.replace('/', '.');
        return dotted.equals(want) || dotted.endsWith("." + want) || dotted.replace('$', '.').endsWith("." + want);
    }

    private static boolean call(MethodNode mn, Def d, String where) {
        List<MethodInsnNode> calls = new ArrayList<>();
        for (AbstractInsnNode n : mn.instructions)
            if (n instanceof MethodInsnNode m && !m.name.equals("<init>") && m.name.equals(d.callName) && ownerMatches(m.owner, d.callOwner)) calls.add(m);
        for (MethodInsnNode m : calls) {
            int id = PatchRuntime.add(d, where + " -> " + m.owner.replace('/', '.') + "." + m.name);
            boolean stat = m.getOpcode() == INVOKESTATIC;
            Type[] at = Type.getArgumentTypes(m.desc);
            Type rt = Type.getReturnType(m.desc);
            int[] al = new int[at.length];
            for (int i = 0; i < at.length; i++) al[i] = newLocal(mn, at[i]);
            int recv = stat ? -1 : newLocal(mn, Type.getType(Object.class));
            int arr = newLocal(mn, Type.getType(Object[].class)), c = newLocal(mn, Type.getType(Object.class));
            InsnList il = new InsnList();
            for (int i = at.length - 1; i >= 0; i--) il.add(new VarInsnNode(at[i].getOpcode(ISTORE), al[i]));
            if (!stat) il.add(new VarInsnNode(ASTORE, recv));
            il.add(pushInt(id));
            il.add(self(mn));
            il.add(stat ? new InsnNode(ACONST_NULL) : new VarInsnNode(ALOAD, recv));
            argArray(il, at, al);
            il.add(new InsnNode(DUP));
            il.add(new VarInsnNode(ASTORE, arr));
            il.add(new MethodInsnNode(INVOKESTATIC, RT, "call", "(ILjava/lang/Object;Ljava/lang/Object;[Ljava/lang/Object;)L" + CALL + ";", false));
            il.add(new VarInsnNode(ASTORE, c));
            LabelNode go = new LabelNode(), end = new LabelNode();
            il.add(new VarInsnNode(ALOAD, c));
            il.add(new MethodInsnNode(INVOKEVIRTUAL, CALL, "isCancelled", "()Z", false));
            il.add(new JumpInsnNode(IFEQ, go));
            if (rt.getSort() != Type.VOID) {
                il.add(new VarInsnNode(ALOAD, c));
                il.add(new MethodInsnNode(INVOKEVIRTUAL, CALL, "returnValue", "()Ljava/lang/Object;", false));
                unbox(il, rt);
            }
            il.add(new JumpInsnNode(GOTO, end));
            il.add(go);
            if (!stat) { il.add(new VarInsnNode(ALOAD, recv)); il.add(new TypeInsnNode(CHECKCAST, m.owner)); }
            for (int i = 0; i < at.length; i++) {
                il.add(new VarInsnNode(ALOAD, arr));
                il.add(pushInt(i));
                il.add(new InsnNode(AALOAD));
                unbox(il, at[i]);
            }
            mn.instructions.insertBefore(m, il);
            mn.instructions.insert(m, end);
        }
        return !calls.isEmpty();
    }

    // ---------------------------------------------------------------------------------- constants
    private static Object constantOf(AbstractInsnNode n) {
        int op = n.getOpcode();
        if (op >= ICONST_M1 && op <= ICONST_5) return op - ICONST_0;
        if (op == LCONST_0 || op == LCONST_1) return (long) (op - LCONST_0);
        if (op >= FCONST_0 && op <= FCONST_2) return (float) (op - FCONST_0);
        if (op == DCONST_0 || op == DCONST_1) return (double) (op - DCONST_0);
        if ((op == BIPUSH || op == SIPUSH) && n instanceof IntInsnNode i) return i.operand;
        if (n instanceof LdcInsnNode l) return l.cst;
        return null;
    }

    private static boolean constant(MethodNode mn, Def d) {
        List<AbstractInsnNode> hits = new ArrayList<>();
        for (AbstractInsnNode n : mn.instructions) { Object v = constantOf(n); if (v != null && v.equals(d.value)) hits.add(n); }
        for (AbstractInsnNode n : hits) {
            Object r = d.replacement;
            AbstractInsnNode rep = r instanceof Integer i ? pushInt(i) : new LdcInsnNode(r);
            mn.instructions.set(n, rep);
        }
        return !hits.isEmpty();
    }

    // ---------------------------------------------------------------------------------- class hierarchy for frames
    private static final Map<String, String[]> infoCache = new HashMap<>();   // name -> {super, isInterface}

    private static String[] info(String name) {
        synchronized (infoCache) {
            String[] i = infoCache.get(name);
            if (i != null) return i;
        }
        String[] r = {"java/lang/Object", "0"};
        ClassLoader l = loader != null ? loader : PatchRegistry.class.getClassLoader();
        try (InputStream in = l.getResourceAsStream(name + ".class")) {
            if (in != null) {
                ClassReader cr = new ClassReader(in);
                r = new String[]{cr.getSuperName(), (cr.getAccess() & ACC_INTERFACE) != 0 ? "1" : "0"};
            }
        } catch (Exception ignored) {}
        synchronized (infoCache) { infoCache.put(name, r); }
        return r;
    }

    static String commonSuper(String a, String b) {
        if (a.equals(b)) return a;
        if (info(a)[1].equals("1") || info(b)[1].equals("1")) return "java/lang/Object";
        List<String> chain = new ArrayList<>();
        for (String c = a; c != null; c = info(c)[0]) { chain.add(c); if (c.equals("java/lang/Object")) break; }
        for (String c = b; c != null; c = info(c)[0]) { if (chain.contains(c)) return c; if (c.equals("java/lang/Object")) break; }
        return "java/lang/Object";
    }
}
