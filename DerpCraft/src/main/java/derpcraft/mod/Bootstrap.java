package derpcraft.mod;

import derpcraft.api.DerpMod;
import derpcraft.patch.PatchRegistry;

import java.util.ArrayList;
import java.util.List;
import java.util.ServiceLoader;

/**
 * Runs inside the patching class loader: creates the mods, collects their patches, then starts the game.
 * The game classes load after this, so every patch applies.
 */
public final class Bootstrap {
    private Bootstrap() {}

    /** Mods created before the game started (null when the game was started without {@link derpcraft.Launch}). */
    static List<DerpMod> preloaded;
    static final List<String> errors = new ArrayList<>();

    public static void run(String[] args, String mainClass) throws Throwable {
        List<DerpMod> mods = new ArrayList<>();
        try {
            for (DerpMod m : ServiceLoader.load(DerpMod.class, Bootstrap.class.getClassLoader())) mods.add(m);
        } catch (Throwable t) {
            errors.add("Cannot create mods: " + t);
            t.printStackTrace();
        }
        for (DerpMod m : mods) {
            try { m.patches(PatchRegistry.forMod(m.id())); }
            catch (Throwable t) { errors.add(m.id() + ": patches failed: " + t); t.printStackTrace(); }
        }
        preloaded = mods;
        int n = PatchRegistry.count();
        if (n > 0) System.out.println("[patch] " + n + " patch(es) from mods registered");
        Class.forName(mainClass, true, Bootstrap.class.getClassLoader()).getMethod("main", String[].class).invoke(null, (Object) args);
    }
}
