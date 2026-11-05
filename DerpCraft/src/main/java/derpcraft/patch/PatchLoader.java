package derpcraft.patch;

import java.io.InputStream;
import java.net.URL;
import java.net.URLClassLoader;

/**
 * Class loader the game runs in when started through {@link derpcraft.Launch}: it loads the game and the mods
 * itself, so it can rewrite classes that mods patch. Java, LWJGL, ASM and this package come from the parent.
 */
public final class PatchLoader extends URLClassLoader {
    static { registerAsParallelCapable(); }

    private static final String[] PARENT = {"java.", "javax.", "jdk.", "sun.", "com.sun.", "org.lwjgl.", "org.objectweb.asm.", "derpcraft.patch.",
            "derpcraft.Launch", "org.w3c.", "org.xml.", "org.ietf.", "org.jcp."};

    public PatchLoader(URL[] mods, ClassLoader parent) {
        super("derpcraft", mods, parent);
        PatchRegistry.loader = this;
        PatchRegistry.active = true;
    }

    /** Adds another jar (a mod) to the loader. */

    boolean isLoaded(String name) { return findLoadedClass(name) != null; }

    private static boolean parentOnly(String name) {
        for (String p : PARENT) if (name.startsWith(p)) return true;
        return false;
    }

    @Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        if (parentOnly(name)) return super.loadClass(name, resolve);
        synchronized (getClassLoadingLock(name)) {
            Class<?> c = findLoadedClass(name);
            if (c == null) {
                URL u = getResource(name.replace('.', '/') + ".class");
                if (u == null) throw new ClassNotFoundException(name);
                byte[] b;
                try (InputStream in = u.openStream()) { b = in.readAllBytes(); }
                catch (Exception e) { throw new ClassNotFoundException(name, e); }
                if (PatchRegistry.has(name)) {
                    try { b = PatchRegistry.transform(name, b); }
                    catch (Throwable t) { PatchRegistry.error("patching " + name + " failed, loading it unpatched: " + t); t.printStackTrace(); }
                }
                c = defineClass(name, b, 0, b.length);
            }
            if (resolve) resolveClass(c);
            return c;
        }
    }
}
