package derpcraft;

import derpcraft.patch.PatchLoader;

import java.lang.reflect.InvocationTargetException;
import java.net.URL;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;

/**
 * Main class: starts the game inside {@link PatchLoader} so mods can patch the game's code (see
 * {@link derpcraft.patch.Patcher}). -Dderpcraft.dir sets the game folder (mods are in its "mods" folder),
 * -Dderpcraft.main another main class (tools, tests). -Dderpcraft.nopatch=true starts without patching.
 */
public final class Launch {
    private Launch() {}

    public static void main(String[] args) throws Throwable {
        String main = System.getProperty("derpcraft.main", "derpcraft.DerpCraft");
        if (Boolean.getBoolean("derpcraft.nopatch")) {
            Class.forName(main).getMethod("main", String[].class).invoke(null, (Object) args);
            return;
        }
        Path dir = Paths.get(System.getProperty("derpcraft.dir", ".")).toAbsolutePath().normalize();
        List<URL> jars = new ArrayList<>();
        Path mods = dir.resolve("mods");
        if (Files.isDirectory(mods)) {
            try (DirectoryStream<Path> ds = Files.newDirectoryStream(mods, "*.jar")) {
                for (Path p : ds) jars.add(p.toUri().toURL());
            }
        }
        PatchLoader loader = new PatchLoader(jars.toArray(new URL[0]), Launch.class.getClassLoader());
        Thread.currentThread().setContextClassLoader(loader);
        try {
            Class.forName("derpcraft.mod.Bootstrap", true, loader).getMethod("run", String[].class, String.class).invoke(null, args, main);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }
}
