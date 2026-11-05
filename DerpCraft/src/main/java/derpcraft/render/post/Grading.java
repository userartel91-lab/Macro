package derpcraft.render.post;

import java.io.*;
import java.nio.file.*;
import java.util.*;

/**
 * Colour grading settings: exposure, white balance, tone mapping, colour controls, split toning, curves,
 * 3D LUT, bloom, vignette and grain. Saved as simple "key=value" text files (presets) in game/grading/.
 */
public final class Grading {
    public static final String[] TONEMAPS = {"None (Vanilla)", "Cinematic", "Filmic", "Reinhard", "ACES"};

    public float exposure = 0;          // EV stops
    public float temperature = 6500;    // Kelvin, 6500 = neutral
    public float tint = 0;              // -1 green .. +1 magenta
    public int tonemap = 0;
    public float contrast = 1, saturation = 1, vibrance = 0;
    public float lift = 0, gamma = 1, gain = 1;
    public float shadowHue = 210, shadowAmount = 0, highlightHue = 40, highlightAmount = 0;
    public float bloom = 0, bloomThreshold = 1.0f;
    public float emissive = 0;          // extra HDR glow of light-emitting blocks
    public float vignette = 0, grain = 0;
    public String lut = "";
    public float lutStrength = 1;
    /**
     * While a look transition runs (not saved): the picture is a mix of tone map 'tonemap' and 'tonemap2'
     * (0 = only the first). Tone maps are different curves, they cannot be interpolated as numbers.
     */
    public int tonemap2;
    public float tonemapMix;
    /** Curve control points per channel (0 master, 1 red, 2 green, 3 blue), x/y in 0..1, sorted by x. */
    public final List<float[]>[] curves = newCurves();

    @SuppressWarnings("unchecked")
    private static List<float[]>[] newCurves() {
        List<float[]>[] c = new List[4];
        for (int i = 0; i < 4; i++) { c[i] = new ArrayList<>(); c[i].add(new float[]{0, 0}); c[i].add(new float[]{1, 1}); }
        return c;
    }

    public boolean curvesIdentity() {
        for (List<float[]> l : curves) for (float[] p : l) if (Math.abs(p[0] - p[1]) > 1e-4f) return false;
        return true;
    }

    public void resetCurve(int ch) {
        curves[ch].clear();
        curves[ch].add(new float[]{0, 0});
        curves[ch].add(new float[]{1, 1});
    }

    public Grading copy() {
        Grading g = new Grading();
        g.read(write());
        return g;
    }

    // ------------------------------------------------------------------ curves
    /**
     * Monotone cubic (Fritsch-Carlson) interpolation through the points: smooth like Photoshop/Resolve curves but
     * without overshoot. Returns 'n' samples of y for x = i / (n - 1).
     */
    public static float[] bake(List<float[]> pts, int n) {
        int m = pts.size();
        float[] xs = new float[m], ys = new float[m];
        for (int i = 0; i < m; i++) { xs[i] = pts.get(i)[0]; ys[i] = pts.get(i)[1]; }
        float[] out = new float[n];
        if (m == 1) { Arrays.fill(out, ys[0]); return out; }
        float[] d = new float[m - 1], t = new float[m];
        for (int i = 0; i < m - 1; i++) d[i] = (ys[i + 1] - ys[i]) / Math.max(1e-5f, xs[i + 1] - xs[i]);
        t[0] = d[0];
        t[m - 1] = d[m - 2];
        for (int i = 1; i < m - 1; i++) t[i] = d[i - 1] * d[i] <= 0 ? 0 : (d[i - 1] + d[i]) / 2;
        for (int i = 0; i < m - 1; i++) {
            if (d[i] == 0) { t[i] = 0; t[i + 1] = 0; continue; }
            float a = t[i] / d[i], b = t[i + 1] / d[i];
            float s = a * a + b * b;
            if (s > 9) { float k = 3 / (float) Math.sqrt(s); t[i] = k * a * d[i]; t[i + 1] = k * b * d[i]; }
        }
        for (int j = 0; j < n; j++) {
            float x = j / (float) (n - 1);
            float y;
            if (x <= xs[0]) y = ys[0];
            else if (x >= xs[m - 1]) y = ys[m - 1];
            else {
                int i = 0;
                while (i < m - 2 && x > xs[i + 1]) i++;
                float h = Math.max(1e-5f, xs[i + 1] - xs[i]), u = (x - xs[i]) / h;
                float h00 = 2 * u * u * u - 3 * u * u + 1, h10 = u * u * u - 2 * u * u + u, h01 = -2 * u * u * u + 3 * u * u, h11 = u * u * u - u * u;
                y = h00 * ys[i] + h10 * h * t[i] + h01 * ys[i + 1] + h11 * h * t[i + 1];
            }
            out[j] = Math.max(0, Math.min(1, y));
        }
        return out;
    }

    // ------------------------------------------------------------------ transitions
    /** A grade between 'a' (t = 0) and 'b' (t = 1): every value, curve, tone map and LUT blends smoothly. */
    public static Grading blend(Grading a, Grading b, float t) {
        Grading g = new Grading();
        g.exposure = mix(a.exposure, b.exposure, t);
        // white balance in mired (1e6 / K): even steps look even
        g.temperature = 1e6f / mix(1e6f / Math.max(1000, a.temperature), 1e6f / Math.max(1000, b.temperature), t);
        g.tint = mix(a.tint, b.tint, t);
        g.contrast = mix(a.contrast, b.contrast, t); g.saturation = mix(a.saturation, b.saturation, t); g.vibrance = mix(a.vibrance, b.vibrance, t);
        g.lift = mix(a.lift, b.lift, t); g.gamma = mix(a.gamma, b.gamma, t); g.gain = mix(a.gain, b.gain, t);
        g.shadowAmount = mix(a.shadowAmount, b.shadowAmount, t);
        g.shadowHue = hue(a.shadowHue, a.shadowAmount, b.shadowHue, b.shadowAmount, t);
        g.highlightAmount = mix(a.highlightAmount, b.highlightAmount, t);
        g.highlightHue = hue(a.highlightHue, a.highlightAmount, b.highlightHue, b.highlightAmount, t);
        g.bloom = mix(a.bloom, b.bloom, t); g.bloomThreshold = mix(a.bloomThreshold, b.bloomThreshold, t);
        g.emissive = mix(a.emissive, b.emissive, t);
        g.vignette = mix(a.vignette, b.vignette, t); g.grain = mix(a.grain, b.grain, t);
        g.tonemap = a.tonemap;
        g.tonemap2 = b.tonemap;
        g.tonemapMix = a.tonemap == b.tonemap ? 0 : t;
        // LUTs: the same one changes strength; two different ones fade out and in, one after the other
        String la = a.lut == null ? "" : a.lut, lb = b.lut == null ? "" : b.lut;
        if (la.equals(lb)) { g.lut = la; g.lutStrength = mix(a.lutStrength, b.lutStrength, t); }
        else if (la.isEmpty()) { g.lut = lb; g.lutStrength = b.lutStrength * t; }
        else if (lb.isEmpty()) { g.lut = la; g.lutStrength = a.lutStrength * (1 - t); }
        else if (t < 0.5f) { g.lut = la; g.lutStrength = a.lutStrength * (1 - 2 * t); }
        else { g.lut = lb; g.lutStrength = b.lutStrength * (2 * t - 1); }
        if (!(a.curvesIdentity() && b.curvesIdentity()))
            for (int c = 0; c < 4; c++) {
                float[] ca = bake(a.curves[c], 17), cb = bake(b.curves[c], 17);
                g.curves[c].clear();
                for (int i = 0; i < 17; i++) g.curves[c].add(new float[]{i / 16f, mix(ca[i], cb[i], t)});
            }
        return g;
    }

    private static float mix(float a, float b, float t) { return a + (b - a) * t; }

    /** Hue of a split tone: along the shorter way round; a tone that is off takes the hue of the other one. */
    private static float hue(float ha, float amountA, float hb, float amountB, float t) {
        if (amountA < 0.001f) return hb;
        if (amountB < 0.001f) return ha;
        float d = ((hb - ha) % 360 + 540) % 360 - 180;
        return ((ha + d * t) % 360 + 360) % 360;
    }

    // ------------------------------------------------------------------ white balance
    /** RGB multiplier for a colour temperature (relative to 6500 K) and green/magenta tint. */
    public float[] whiteBalance() {
        float[] k = kelvin(temperature), ref = kelvin(6500);
        float r = ref[0] / k[0], g = ref[1] / k[1], b = ref[2] / k[2];
        g *= 1 - tint * 0.25f;
        float lum = r * 0.2126f + g * 0.7152f + b * 0.0722f;
        return new float[]{r / lum, g / lum, b / lum};
    }

    /** Approximate blackbody colour (Tanner Helland). */
    static float[] kelvin(float kelvin) {
        double t = Math.max(1000, Math.min(40000, kelvin)) / 100.0, r, g, b;
        if (t <= 66) { r = 255; g = 99.4708025861 * Math.log(t) - 161.1195681661; }
        else { r = 329.698727446 * Math.pow(t - 60, -0.1332047592); g = 288.1221695283 * Math.pow(t - 60, -0.0755148492); }
        if (t >= 66) b = 255;
        else if (t <= 19) b = 0;
        else b = 138.5177312231 * Math.log(t - 10) - 305.0447927307;
        return new float[]{(float) clamp(r) / 255f, (float) clamp(g) / 255f, (float) Math.max(1, clamp(b)) / 255f};
    }

    private static double clamp(double v) { return Math.max(0, Math.min(255, v)); }

    // ------------------------------------------------------------------ serialization
    public String write() {
        StringBuilder sb = new StringBuilder();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("exposure", exposure); m.put("temperature", temperature); m.put("tint", tint); m.put("tonemap", tonemap);
        m.put("contrast", contrast); m.put("saturation", saturation); m.put("vibrance", vibrance);
        m.put("lift", lift); m.put("gamma", gamma); m.put("gain", gain);
        m.put("shadowHue", shadowHue); m.put("shadowAmount", shadowAmount); m.put("highlightHue", highlightHue); m.put("highlightAmount", highlightAmount);
        m.put("bloom", bloom); m.put("bloomThreshold", bloomThreshold); m.put("emissive", emissive);
        m.put("vignette", vignette); m.put("grain", grain); m.put("lut", lut); m.put("lutStrength", lutStrength);
        for (var e : m.entrySet()) sb.append(e.getKey()).append('=').append(e.getValue()).append('\n');
        String[] names = {"curveMaster", "curveRed", "curveGreen", "curveBlue"};
        for (int c = 0; c < 4; c++) {
            sb.append(names[c]).append('=');
            for (int i = 0; i < curves[c].size(); i++) {
                float[] p = curves[c].get(i);
                if (i > 0) sb.append(' ');
                sb.append(String.format(Locale.ROOT, "%.4f:%.4f", p[0], p[1]));
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    public void read(String text) {
        Map<String, String> m = new HashMap<>();
        for (String line : text.split("\n")) {
            int i = line.indexOf('=');
            if (i > 0 && !line.startsWith("#")) m.put(line.substring(0, i).trim(), line.substring(i + 1).trim());
        }
        exposure = f(m, "exposure", 0); temperature = f(m, "temperature", 6500); tint = f(m, "tint", 0);
        tonemap = (int) f(m, "tonemap", 0);
        contrast = f(m, "contrast", 1); saturation = f(m, "saturation", 1); vibrance = f(m, "vibrance", 0);
        lift = f(m, "lift", 0); gamma = f(m, "gamma", 1); gain = f(m, "gain", 1);
        shadowHue = f(m, "shadowHue", 210); shadowAmount = f(m, "shadowAmount", 0);
        highlightHue = f(m, "highlightHue", 40); highlightAmount = f(m, "highlightAmount", 0);
        bloom = f(m, "bloom", 0); bloomThreshold = f(m, "bloomThreshold", 1.0f); emissive = f(m, "emissive", 0);
        vignette = f(m, "vignette", 0); grain = f(m, "grain", 0);
        lut = m.getOrDefault("lut", ""); lutStrength = f(m, "lutStrength", 1);
        String[] names = {"curveMaster", "curveRed", "curveGreen", "curveBlue"};
        for (int c = 0; c < 4; c++) {
            resetCurve(c);
            String v = m.get(names[c]);
            if (v == null || v.isBlank()) continue;
            List<float[]> pts = new ArrayList<>();
            for (String p : v.trim().split("\\s+")) {
                String[] xy = p.split(":");
                try { pts.add(new float[]{Float.parseFloat(xy[0]), Float.parseFloat(xy[1])}); } catch (Exception ignored) {}
            }
            if (pts.size() >= 2) { pts.sort(Comparator.comparingDouble(a -> a[0])); curves[c].clear(); curves[c].addAll(pts); }
        }
    }

    private static float f(Map<String, String> m, String k, float d) {
        try { return Float.parseFloat(m.getOrDefault(k, "" + d)); } catch (NumberFormatException e) { return d; }
    }

    public void save(Path file) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, "# DerpCraft colour grading preset\n" + write());
    }

    public static Grading load(Path file) {
        Grading g = new Grading();
        try { g.read(Files.readString(file)); } catch (IOException ignored) {}
        return g;
    }

    // ------------------------------------------------------------------ built-in presets
    public static final String[] BUILTIN = {"Vanilla", "Cinematic", "Golden Hour", "Cold Night", "Bleach Bypass", "Vivid", "Noir", "Teal & Orange"};

    /** All preset names: built-in, mod presets, then saved files in 'dir'. */
    public static List<String> presetNames(Path dir) {
        List<String> out = new ArrayList<>(Arrays.asList(BUILTIN));
        for (String n : derpcraft.api.ModContext.GRADING_PRESETS.keySet()) if (!out.contains(n)) out.add(n);
        try (var st = Files.list(dir)) {
            st.map(p -> p.getFileName().toString()).filter(n -> n.endsWith(".txt") && !n.equals("current.txt")).sorted()
                    .map(n -> n.substring(0, n.length() - 4)).filter(n -> !out.contains(n)).forEach(out::add);
        } catch (IOException ignored) {}
        return out;
    }

    /** A preset by name (a saved file wins over a built-in / mod preset of the same name), or null. */
    public static Grading byName(String name, Path dir) {
        Path f = dir.resolve(name + ".txt");
        if (Files.exists(f)) return load(f);
        String mod = derpcraft.api.ModContext.GRADING_PRESETS.get(name);
        if (mod != null) { Grading g = new Grading(); g.read(mod); return g; }
        if (Arrays.asList(BUILTIN).contains(name)) return builtin(name);
        return null;
    }

    public static Grading builtin(String name) {
        Grading g = new Grading();
        switch (name) {
            case "Cinematic" -> {
                g.tonemap = 1; g.exposure = 0.3f; g.contrast = 1.08f; g.saturation = 1.05f; g.bloom = 0.15f; g.bloomThreshold = 1.2f; g.emissive = 1.5f; g.vignette = 0.3f;
                g.curves[0].add(1, new float[]{0.25f, 0.21f}); g.curves[0].add(2, new float[]{0.75f, 0.79f});
            }
            case "Golden Hour" -> {
                g.tonemap = 2; g.temperature = 4300; g.exposure = 0.25f; g.saturation = 1.15f; g.bloom = 0.25f; g.bloomThreshold = 1.1f; g.emissive = 1.5f;
                g.highlightHue = 35; g.highlightAmount = 0.25f; g.shadowHue = 260; g.shadowAmount = 0.1f; g.vignette = 0.25f;
            }
            case "Cold Night" -> {
                g.tonemap = 1; g.temperature = 9500; g.exposure = 0.1f; g.saturation = 0.8f; g.bloom = 0.4f; g.emissive = 2.5f;
                g.shadowHue = 220; g.shadowAmount = 0.3f; g.lift = 0.02f; g.vignette = 0.4f;
            }
            case "Bleach Bypass" -> {
                g.tonemap = 2; g.saturation = 0.45f; g.contrast = 1.3f; g.vignette = 0.35f; g.grain = 0.15f;
            }
            case "Vivid" -> { g.tonemap = 1; g.exposure = 0.2f; g.saturation = 1.25f; g.vibrance = 0.4f; g.contrast = 1.1f; g.bloom = 0.15f; g.bloomThreshold = 1.2f; g.emissive = 1; }
            case "Noir" -> {
                g.saturation = 0; g.contrast = 1.35f; g.vignette = 0.55f; g.grain = 0.25f; g.tonemap = 2;
                g.curves[0].add(1, new float[]{0.3f, 0.22f}); g.curves[0].add(2, new float[]{0.7f, 0.8f});
            }
            case "Teal & Orange" -> {
                g.tonemap = 1; g.saturation = 1.1f; g.contrast = 1.12f; g.bloom = 0.15f; g.bloomThreshold = 1.2f; g.emissive = 1;
                g.shadowHue = 190; g.shadowAmount = 0.35f; g.highlightHue = 30; g.highlightAmount = 0.3f; g.vignette = 0.25f;
            }
            default -> {}
        }
        return g;
    }
}
