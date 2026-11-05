package derpcraft.mod;

import derpcraft.api.DerpMod;
import derpcraft.api.ModContext;
import derpcraft.resource.ResourcePack;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.*;
import java.util.*;

/** Finds mods/*.jar, loads their DerpMod implementations via ServiceLoader and exposes their assets. */
public final class ModLoader {
    public final List<DerpMod> mods = new ArrayList<>();
    public final List<ResourcePack> modPacks = new ArrayList<>();
    public final List<String> errors = new ArrayList<>();

    public void load(Path modsDir) {
        List<URL> urls = new ArrayList<>();
        List<Path> jars = new ArrayList<>();
        try {
            Files.createDirectories(modsDir);
            try (DirectoryStream<Path> ds = Files.newDirectoryStream(modsDir, "*.jar")) {
                for (Path p : ds) { urls.add(p.toUri().toURL()); jars.add(p); }
            }
        } catch (Exception e) {
            errors.add("Cannot read mods folder: " + e);
            return;
        }
        errors.addAll(Bootstrap.errors);
        errors.addAll(derpcraft.patch.PatchRegistry.errors());
        if (urls.isEmpty()) return;
        Iterable<DerpMod> source;
        if (Bootstrap.preloaded != null) source = Bootstrap.preloaded;    // started through Launch: mods already exist, patches applied
        else source = ServiceLoader.load(DerpMod.class, new URLClassLoader(urls.toArray(new URL[0]), ModLoader.class.getClassLoader()));
        Set<String> ids = new HashSet<>();
        List<DerpMod> found = new ArrayList<>();
        for (DerpMod mod : source) {
            if (!ids.add(mod.id())) { errors.add("Duplicate mod id " + mod.id()); continue; }
            found.add(mod);
            if (Bootstrap.preloaded == null) {
                // started without the launcher: patches can't apply, tell the user if the mod has any
                int[] n = {0};
                try { mod.patches(new CountingPatcher(mod.id(), n)); } catch (Throwable ignored) {}
                if (n[0] > 0) errors.add(mod.id() + ": its " + n[0] + " code patch(es) need the game started with derpcraft.Launch (Play.bat does that)");
            }
        }
        for (DerpMod mod : sortByDependencies(found)) {
            try {
                mod.init(new ModContext(mod.id()));
                mods.add(mod);
                System.out.println("Loaded mod " + mod.name() + " " + mod.version());
            } catch (Throwable t) {
                errors.add(mod.id() + ": " + t);
                t.printStackTrace();
            }
        }
        for (Path p : jars) {
            try { modPacks.add(ResourcePack.open(p)); } catch (Exception e) { errors.add(p.getFileName() + ": " + e); }
        }
    }

    /** Dependencies first; mods with missing dependencies are skipped with an error. */
    private List<DerpMod> sortByDependencies(List<DerpMod> mods) {
        Map<String, DerpMod> byId = new LinkedHashMap<>();
        for (DerpMod m : mods) byId.put(m.id(), m);
        List<DerpMod> out = new ArrayList<>();
        Set<String> done = new HashSet<>(), visiting = new HashSet<>();
        for (DerpMod m : mods) visit(m, byId, out, done, visiting);
        return out;
    }

    private boolean visit(DerpMod m, Map<String, DerpMod> byId, List<DerpMod> out, Set<String> done, Set<String> visiting) {
        if (done.contains(m.id())) return true;
        if (!visiting.add(m.id())) { errors.add(m.id() + ": circular dependency"); return false; }
        for (String d : m.dependencies()) {
            DerpMod dep = byId.get(d);
            if (dep == null) { errors.add(m.id() + " needs mod " + d); visiting.remove(m.id()); return false; }
            if (!visit(dep, byId, out, done, visiting)) { visiting.remove(m.id()); return false; }
        }
        visiting.remove(m.id());
        done.add(m.id());
        out.add(m);
        return true;
    }

    /** Second init phase: items and recipes (after the vanilla ones exist). */
    public void initItems() {
        for (DerpMod mod : mods) {
            try { mod.initItems(new ModContext(mod.id())); }
            catch (Throwable t) { errors.add(mod.id() + ": " + t); t.printStackTrace(); }
        }
        derpcraft.entity.MobType.createEggs();
    }

    /** Counts patches without registering them. */
    private record CountingPatcher(String modId, int[] n) implements derpcraft.patch.Patcher {
        public void head(String c, String m, derpcraft.patch.Hook h) { n[0]++; }
        public void tail(String c, String m, derpcraft.patch.Hook h) { n[0]++; }
        public void replace(String c, String m, derpcraft.patch.Hook h) { n[0]++; }
        public void beforeCall(String c, String m, String co, String cm, derpcraft.patch.Hook h) { n[0]++; }
        public void constant(String c, String m, Object v, Object r) { n[0]++; }
    }
}
