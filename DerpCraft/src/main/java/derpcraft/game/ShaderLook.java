package derpcraft.game;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * The shader part of the settings as one "look": coloured light, shadows, ray tracing, ambient occlusion, fog and the
 * effects (wind, water, god rays, reflections). Shader presets are looks: built in, or saved in game/shaders/*.txt
 * (Shaders -> Transitions...). Colour grading is not part of it (it has its own presets, game/grading).
 */
public final class ShaderLook {
    public boolean coloredLight;
    public int shadows, rayTracing, rtQuality = 1;
    public float ao = 1;
    public boolean fog;
    public float fogDistance = 48, fogStart = 0.1f;
    public int fogTint;
    public boolean waving;
    public float wind = 1;
    public boolean water;
    public float waveHeight = 1;
    public boolean godRays;
    public float rays = 1;
    public int reflections;
    public float blockReflections = 1;
    public boolean ssao = true;
    public float ssaoStrength = 1;

    /** Pseudo preset name: the look on screen when the transition starts. */
    public static final String CURRENT = "(current)";

    public static ShaderLook of(Settings s) {
        ShaderLook l = new ShaderLook();
        l.coloredLight = s.coloredLight; l.shadows = s.shadows; l.rayTracing = s.rayTracing; l.rtQuality = s.rtQuality; l.ao = s.aoStrength;
        l.fog = s.fog; l.fogDistance = s.fogDistance; l.fogStart = s.fogStart; l.fogTint = s.fogTint;
        l.waving = s.waving; l.wind = s.windStrength; l.water = s.waterShader; l.waveHeight = s.waveHeight;
        l.godRays = s.godRays; l.rays = s.godRayStrength; l.reflections = s.reflections; l.blockReflections = s.blockReflections;
        l.ssao = s.ssao; l.ssaoStrength = s.ssaoStrength;
        return l;
    }

    public void applyTo(Settings s) {
        s.coloredLight = coloredLight; s.shadows = shadows; s.rayTracing = rayTracing; s.rtQuality = rtQuality; s.aoStrength = ao;
        s.fog = fog; s.fogDistance = fogDistance; s.fogStart = fogStart; s.fogTint = fogTint;
        s.waving = waving; s.windStrength = wind; s.waterShader = water; s.waveHeight = waveHeight;
        s.godRays = godRays; s.godRayStrength = rays; s.reflections = reflections; s.blockReflections = blockReflections;
        s.ssao = ssao; s.ssaoStrength = ssaoStrength;
    }

    // ------------------------------------------------------------------ text format (same keys as options.txt)
    public String write() {
        return "coloredLight=" + coloredLight + "\nshadows=" + shadows + "\nrayTracing=" + rayTracing + "\nrtQuality=" + rtQuality
                + "\naoStrength=" + ao + "\nfog=" + fog + "\nfogDistance=" + fogDistance + "\nfogStart=" + fogStart
                + "\nfogTint=" + Settings.FOG_TINT_NAMES[Math.max(0, Math.min(Settings.FOG_TINTS.length - 1, fogTint))]
                + "\nwaving=" + waving + "\nwindStrength=" + wind + "\nwaterShader=" + water + "\nwaveHeight=" + waveHeight
                + "\ngodRays=" + godRays + "\ngodRayStrength=" + rays + "\nreflections=" + reflections + "\nblockReflections=" + blockReflections
                + "\nssao=" + ssao + "\nssaoStrength=" + ssaoStrength + "\n";
    }

    /** Reads the keys of {@link #write} (missing keys keep the defaults: everything off). */
    public static ShaderLook read(String text) {
        Map<String, String> m = new HashMap<>();
        for (String line : text.split("\n")) {
            String t = line.trim();
            int i = t.indexOf('=');
            if (i > 0 && !t.startsWith("#")) m.put(t.substring(0, i).trim(), t.substring(i + 1).trim());
        }
        ShaderLook l = new ShaderLook();
        l.coloredLight = b(m, "coloredLight", false);
        l.shadows = Math.max(0, Math.min(4, (int) f(m, "shadows", 0)));
        l.rayTracing = Math.max(0, Math.min(1, (int) f(m, "rayTracing", 0)));
        l.rtQuality = Math.max(0, Math.min(2, (int) f(m, "rtQuality", 1)));
        l.ao = clamp(f(m, "aoStrength", 1), 0, 2);
        l.fog = b(m, "fog", false);
        l.fogDistance = clamp(f(m, "fogDistance", 48), 4, 512);
        l.fogStart = clamp(f(m, "fogStart", 0.1f), 0, 0.95f);
        String tint = m.getOrDefault("fogTint", "Sky");
        l.fogTint = 0;
        for (int i = 0; i < Settings.FOG_TINT_NAMES.length; i++) if (Settings.FOG_TINT_NAMES[i].equalsIgnoreCase(tint)) l.fogTint = i;
        try { l.fogTint = Math.max(0, Math.min(Settings.FOG_TINTS.length - 1, Integer.parseInt(tint))); } catch (NumberFormatException ignored) {}
        l.waving = b(m, "waving", false);
        l.wind = clamp(f(m, "windStrength", 1), 0, 3);
        l.water = b(m, "waterShader", false);
        l.waveHeight = clamp(f(m, "waveHeight", 1), 0, 3);
        l.godRays = b(m, "godRays", false);
        l.rays = clamp(f(m, "godRayStrength", 1), 0, 3);
        l.reflections = Math.max(0, Math.min(2, (int) f(m, "reflections", 0)));
        l.blockReflections = clamp(f(m, "blockReflections", 1), 0, 3);
        l.ssao = b(m, "ssao", true);
        l.ssaoStrength = clamp(f(m, "ssaoStrength", 1), 0, 2);
        return l;
    }

    private static boolean b(Map<String, String> m, String k, boolean d) { String v = m.get(k); return v == null ? d : v.equalsIgnoreCase("true") || v.equals("1") || v.equalsIgnoreCase("on"); }
    private static float f(Map<String, String> m, String k, float d) { try { return Float.parseFloat(m.getOrDefault(k, "" + d).replace(',', '.')); } catch (NumberFormatException e) { return d; } }
    private static float clamp(float v, float lo, float hi) { return Float.isFinite(v) ? Math.max(lo, Math.min(hi, v)) : lo; }

    // ------------------------------------------------------------------ presets
    public static final String[] BUILTIN = {"Vanilla", "Sunny", "Golden Hour", "Overcast", "Foggy Morning", "Storm", "Smog", "Night City"};

    /** Built-in shader presets (weather and moods). */
    public static ShaderLook builtin(String name) {
        ShaderLook l = new ShaderLook();
        switch (name) {
            case "Sunny" -> {
                l.coloredLight = true; l.shadows = 3; l.waving = true; l.wind = 0.7f; l.water = true; l.waveHeight = 0.8f;
                l.godRays = true; l.rays = 0.8f; l.reflections = 2; l.blockReflections = 1;
            }
            case "Golden Hour" -> {
                l.coloredLight = true; l.shadows = 3; l.fog = true; l.fogDistance = 256; l.fogStart = 0.3f; l.fogTint = 0;
                l.waving = true; l.wind = 0.5f; l.water = true; l.waveHeight = 0.6f; l.godRays = true; l.rays = 1.6f; l.reflections = 2;
            }
            case "Overcast" -> {
                l.ssaoStrength = 1.5f;   // all light comes from the sky: corners and contact shadows stand out
                l.coloredLight = true; l.ao = 1.25f; l.fog = true; l.fogDistance = 160; l.fogStart = 0.2f; l.fogTint = 2;
                l.waving = true; l.wind = 1.4f; l.water = true; l.waveHeight = 1.3f; l.reflections = 1;
            }
            case "Foggy Morning" -> {
                l.coloredLight = true; l.shadows = 2; l.fog = true; l.fogDistance = 40; l.fogStart = 0; l.fogTint = 1;
                l.waving = true; l.wind = 0.25f; l.water = true; l.waveHeight = 0.3f; l.godRays = true; l.rays = 2; l.reflections = 1;
            }
            case "Storm" -> {
                l.ssaoStrength = 1.4f;
                l.coloredLight = true; l.ao = 1.35f; l.fog = true; l.fogDistance = 64; l.fogStart = 0; l.fogTint = 2;
                l.waving = true; l.wind = 2.8f; l.water = true; l.waveHeight = 2.6f; l.reflections = 2; l.blockReflections = 1.8f;
            }
            case "Smog" -> {
                l.coloredLight = true; l.shadows = 2; l.fog = true; l.fogDistance = 96; l.fogStart = 0.05f; l.fogTint = 4;
                l.waving = true; l.wind = 0.4f; l.water = true; l.waveHeight = 0.5f; l.godRays = true; l.rays = 1.4f; l.reflections = 1;
            }
            case "Night City" -> {
                l.coloredLight = true; l.fog = true; l.fogDistance = 192; l.fogStart = 0.25f; l.fogTint = 0;
                l.waving = true; l.wind = 0.5f; l.water = true; l.waveHeight = 0.7f; l.reflections = 2; l.blockReflections = 2.2f;
            }
            default -> l.ssao = false;   // Vanilla: everything off
        }
        return l;
    }

    /** "(current)" (if 'withCurrent'), built-in presets, then the saved ones in 'dir'. */
    public static List<String> names(Path dir, boolean withCurrent) {
        List<String> out = new ArrayList<>();
        if (withCurrent) out.add(CURRENT);
        out.addAll(Arrays.asList(BUILTIN));
        try (var st = Files.list(dir)) {
            st.map(p -> p.getFileName().toString()).filter(n -> n.endsWith(".txt")).sorted()
                    .map(n -> n.substring(0, n.length() - 4)).filter(n -> !out.contains(n)).forEach(out::add);
        } catch (IOException ignored) {}
        return out;
    }

    /** A preset by name (a saved file wins over a built-in of the same name), or null. */
    public static ShaderLook byName(String name, Path dir) {
        Path f = dir.resolve(name + ".txt");
        if (Files.exists(f)) {
            try { return read(Files.readString(f)); } catch (IOException e) { return null; }
        }
        return Arrays.asList(BUILTIN).contains(name) ? builtin(name) : null;
    }

    public void save(Path dir, String name) throws IOException {
        Files.createDirectories(dir);
        Files.writeString(dir.resolve(name + ".txt"), "# DerpCraft shader preset (Shaders -> Transitions...)\n" + write());
    }
}
