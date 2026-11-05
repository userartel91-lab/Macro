package derpcraft.remote;

import derpcraft.DerpCraft;
import derpcraft.game.Settings;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Locale;

/** Game options (options.txt) that the phone and the /option command can read and change. */
public final class RemoteOptions {
    private RemoteOptions() {}

    /** type: "bool", "int" or "float"; min/max/step for numbers. */
    public record Opt(String name, String type, float min, float max, float step, String label) {}

    public static final List<Opt> ALL = List.of(
            new Opt("renderDistance", "int", 2, 64, 1, "Render distance (chunks)"),
            new Opt("fov", "float", 30, 110, 5, "FOV"),
            new Opt("sensitivity", "float", 0, 2, 0.05f, "Mouse sensitivity"),
            new Opt("maxFps", "int", 0, 300, 10, "Max FPS (0 = unlimited)"),
            new Opt("vsync", "bool", 0, 1, 1, "VSync"),
            new Opt("guiScale", "int", 0, 4, 1, "GUI scale (0 = auto)"),
            new Opt("gamma", "float", 0, 1, 0.1f, "Brightness"),
            new Opt("clouds", "bool", 0, 1, 1, "Clouds"),
            new Opt("fovEffects", "bool", 0, 1, 1, "FOV effects"),
            new Opt("viewBobbing", "bool", 0, 1, 1, "View bobbing"),
            new Opt("mipLevels", "int", 0, 4, 1, "Mipmap levels"),
            new Opt("fullscreen", "bool", 0, 1, 1, "Fullscreen"),
            new Opt("cinematic", "bool", 0, 1, 1, "Cinematic pipeline (grading)"),
            new Opt("coloredLight", "bool", 0, 1, 1, "Colored light"),
            new Opt("shadows", "int", 0, 4, 1, "Shadows (0 = off)"),
            new Opt("rayTracing", "int", 0, 1, 1, "Ray tracing"),
            new Opt("rtQuality", "int", 0, 2, 1, "RT quality"),
            new Opt("ssao", "bool", 0, 1, 1, "World ambient occlusion"),
            new Opt("ssaoStrength", "float", 0, 2, 0.1f, "World AO strength"),
            new Opt("aoStrength", "float", 0, 2, 0.1f, "Block corner AO"),
            new Opt("waving", "bool", 0, 1, 1, "Waving plants"),
            new Opt("windStrength", "float", 0, 3, 0.1f, "Wind strength"),
            new Opt("waterShader", "bool", 0, 1, 1, "Water waves"),
            new Opt("waveHeight", "float", 0, 3, 0.1f, "Wave height"),
            new Opt("godRays", "bool", 0, 1, 1, "God rays"),
            new Opt("godRayStrength", "float", 0, 3, 0.1f, "God rays strength"),
            new Opt("reflections", "int", 0, 2, 1, "Reflections (0 off, 1 water, 2 + blocks)"),
            new Opt("blockReflections", "float", 0, 3, 0.1f, "Block reflections strength"),
            new Opt("itemPhysics", "bool", 0, 1, 1, "Item physics"),
            new Opt("pickupClick", "bool", 0, 1, 1, "Pick up items by click"),
            new Opt("thirdPersonDistance", "float", 1, 32, 1, "3rd person distance"),
            new Opt("recSmooth", "bool", 0, 1, 1, "Smooth screen recording"));

    public static Opt byName(String name) {
        for (Opt o : ALL) if (o.name.equalsIgnoreCase(name)) return o;
        return null;
    }

    private static Field field(Opt o) {
        try { return Settings.class.getField(o.name); } catch (NoSuchFieldException e) { throw new IllegalStateException(e); }
    }

    public static String get(DerpCraft game, Opt o) {
        try {
            Object v = field(o).get(game.settings);
            if (v instanceof Boolean b) return b ? "1" : "0";
            if (v instanceof Float f) return f == f.intValue() ? String.valueOf(f.intValue()) : String.format(Locale.ROOT, "%.2f", f);
            return String.valueOf(v);
        } catch (IllegalAccessException e) { throw new IllegalStateException(e); }
    }

    /**
     * Sets an option (main thread) and applies it like the Options screen does. Value: a number, on/off/true/false/1/0,
     * "toggle", or "+" / "-" for one step up / down. Returns the new value.
     */
    public static String set(DerpCraft game, Opt o, String value) {
        Settings s = game.settings;
        Field f = field(o);
        String v = value.trim().toLowerCase(Locale.ROOT).replace(',', '.');
        try {
            if (o.type.equals("bool")) {
                boolean cur = f.getBoolean(s);
                boolean nv = switch (v) {
                    case "1", "on", "true", "yes", "вкл" -> true;
                    case "0", "off", "false", "no", "выкл" -> false;
                    case "toggle", "", "+", "-" -> !cur;
                    default -> throw new IllegalArgumentException(o.name + ": on or off");
                };
                if (o.name.equals("fullscreen")) { if (nv != s.fullscreen) game.toggleFullscreen(); }
                else f.setBoolean(s, nv);
                if (o.name.equals("vsync")) game.applyVsync();
            } else {
                float cur = o.type.equals("int") ? f.getInt(s) : f.getFloat(s);
                float nv;
                if (v.equals("+")) nv = cur + o.step;
                else if (v.equals("-")) nv = cur - o.step;
                else try { nv = Float.parseFloat(v); } catch (NumberFormatException e) { throw new IllegalArgumentException(o.name + ": a number " + o.min + ".." + o.max); }
                nv = Math.max(o.min, Math.min(o.max, nv));
                if (o.step < 1) nv = Math.round(nv / o.step) * o.step;
                if (o.type.equals("int")) f.setInt(s, Math.round(nv)); else f.setFloat(s, nv);
                switch (o.name) {
                    case "guiScale" -> game.relayout();
                    case "mipLevels" -> game.reloadAssets();
                    default -> {}
                }
            }
        } catch (IllegalAccessException e) { throw new IllegalStateException(e); }
        s.save();
        return get(game, o);
    }
}
