package derpcraft.gui;

import derpcraft.DerpCraft;
import derpcraft.game.Settings;
import derpcraft.render.GuiRenderer;
import derpcraft.render.post.Grading;
import derpcraft.render.post.PostProcessor;

import java.nio.file.*;
import java.util.*;

import static org.lwjgl.glfw.GLFW.*;

/** Shader options (coloured light, cinematic pipeline, shadows, ray tracing) and the live colour grading editor. */
public final class Graphics {
    private Graphics() {}

    // ============================================================================ shaders screen
    public static final class Shaders extends Screen {
        private final Screen parent;

        public Shaders(DerpCraft g, Screen parent) { super(g); this.parent = parent; }

        @Override public void init() {
            Settings s = game.settings;
            int x1 = width / 2 - 155, x2 = width / 2 + 5, y = height / 6;
            add(new Widget.Button(x1, y, 150, 20, () -> "Colored Light: " + (s.coloredLight ? "ON" : "OFF"), () -> s.coloredLight = !s.coloredLight));
            add(new Widget.Button(x2, y, 150, 20, () -> "Cinematic Pipeline: " + (s.cinematic ? "ON" : "OFF"), () -> s.cinematic = !s.cinematic));
            y += 24;
            String[] sh = {"OFF", "Low", "Medium", "High", "Ultra"};
            add(new Widget.Button(x1, y, 150, 20, () -> "Shadows: " + sh[s.shadows], () -> s.shadows = (s.shadows + 1) % sh.length));
            add(new Widget.Button(x2, y, 150, 20, () -> "Ray Tracing: " + (s.rayTracing == 0 ? "OFF" : "ON"), () -> s.rayTracing = 1 - s.rayTracing));
            y += 24;
            String[] q = {"Fast", "Balanced", "Quality"};
            add(new Widget.Button(x1, y, 150, 20, () -> "RT Quality: " + q[s.rtQuality], () -> s.rtQuality = (s.rtQuality + 1) % 3));
            add(new Widget.Button(x2, y, 150, 20, "Color Grading...", () -> game.setScreen(new GradingEditor(game, this))));
            y += 24;
            // ambient occlusion and fog
            add(new Widget.Slider(x1, y, 150, "Ambient Occlusion", 0, 2, 0.05f, () -> s.aoStrength, v -> s.aoStrength = (float) v,
                    v -> v <= 0.001f ? "OFF" : Math.round(v * 100) + "%"));
            add(new Widget.Button(x2, y, 150, 20, () -> "Fog: " + (s.fog ? "ON" : "OFF"), () -> s.fog = !s.fog));
            y += 24;
            add(new Widget.Slider(x1, y, 150, "Fog Distance", 4, 256, 1, () -> s.fogDistance, v -> { s.fogDistance = (float) v; s.fog = true; },
                    v -> Math.round(v) + " blocks"));
            add(new Widget.Slider(x2, y, 150, "Fog Start", 0, 0.95f, 0.05f, () -> s.fogStart, v -> { s.fogStart = (float) v; s.fog = true; },
                    v -> Math.round(v * 100) + "%"));
            y += 24;
            add(new Widget.Button(x1, y, 150, 20, () -> "Fog Color: " + Settings.FOG_TINT_NAMES[s.fogTint],
                    () -> { s.fogTint = (s.fogTint + 1) % Settings.FOG_TINTS.length; s.fog = true; }));
            add(new Widget.Button(x2, y, 150, 20, "Effects...", () -> game.setScreen(new Effects(game, this))));
            y += 24;
            add(new Widget.Button(x1, y, 310, 20, () -> "Presets & Transitions... (" + derpcraft.game.Keys.n(derpcraft.game.Keys.TRANSITION) + ")",
                    () -> game.setScreen(new Transitions(game, this))));
            infoY = y + 28;
            add(new Widget.Button(width / 2 - 100, height - 27, 200, 20, "Done", this::onClose));
        }

        private int infoY;

        @Override public void render(GuiRenderer g, int mx, int my, float partial) {
            if (game.session == null) g.background(0xFF404040);
            else g.gradient(0, 0, width, height, 0xC0101010, 0xD0101010);
            title(g, "Shaders", 15);
            Settings s = game.settings;
            int y = infoY;
            List<String> info = new ArrayList<>();
            info.add("Colored Light: lava, fire, redstone, sea lanterns light up in their colour,");
            info.add("stained glass tints the light that passes through it.");
            info.add("Cinematic Pipeline: HDR, bloom, colour grading, curves, LUTs");
            info.add("(F6 opens the grading editor).");
            info.add("Shadows: real-time sun and moon shadows. Ray Tracing: voxel path traced");
            info.add("shadows, sky occlusion and bounce light (best on RTX / modern GPUs).");
            if (s.rayTracing == 1 && s.shadows == 0) info.add("§eRay tracing replaces shadow maps when it is on.");
            info.add("Ambient Occlusion: dark block corners (100% = Minecraft, 0 = off);");
            info.add("World AO for items and models: Effects... -> World AO.");
            info.add("Fog: thick at Fog Distance, begins at Fog Start of it; the sky fades in.");
            info.add("Effects: waving plants, water waves, god rays, reflections.");
            for (String l : info) { if (y > height - 40) break; g.centered(l, width / 2f, y, 0xA0A0A0); y += 11; }
            super.render(g, mx, my, partial);
        }

        @Override public void onClose() { game.settings.save(); game.setScreen(parent); }
    }

    // ============================================================================ shader effects
    /** Waving plants, water waves, god rays and screen space reflections (Shaders -> Effects...). */
    public static final class Effects extends Screen {
        private final Screen parent;
        private int infoY;

        public Effects(DerpCraft g, Screen parent) { super(g); this.parent = parent; }

        private static String onOff(boolean b) { return b ? "ON" : "OFF"; }
        private static String pct(float v) { return v <= 0.001f ? "OFF" : Math.round(v * 100) + "%"; }

        @Override public void init() {
            Settings s = game.settings;
            int x1 = width / 2 - 155, x2 = width / 2 + 5, y = height / 6;
            add(new Widget.Button(x1, y, 150, 20, () -> "Waving Plants: " + onOff(s.waving), () -> s.waving = !s.waving));
            add(new Widget.Slider(x2, y, 150, "Wind", 0, 3, 0.05f, () -> s.windStrength,
                    v -> { s.windStrength = (float) v; s.waving = true; }, Effects::pct));
            y += 24;
            add(new Widget.Button(x1, y, 150, 20, () -> "Water: " + (s.waterShader ? "Waves" : "Vanilla"), () -> s.waterShader = !s.waterShader));
            add(new Widget.Slider(x2, y, 150, "Wave Height", 0, 3, 0.05f, () -> s.waveHeight,
                    v -> { s.waveHeight = (float) v; s.waterShader = true; }, v -> v <= 0.001f ? "Calm" : Math.round(v * 100) + "%"));
            y += 24;
            add(new Widget.Button(x1, y, 150, 20, () -> "God Rays: " + onOff(s.godRays), () -> s.godRays = !s.godRays));
            add(new Widget.Slider(x2, y, 150, "Rays", 0, 3, 0.05f, () -> s.godRayStrength,
                    v -> { s.godRayStrength = (float) v; s.godRays = true; }, Effects::pct));
            y += 24;
            String[] rf = {"OFF", "Water", "Water + Blocks"};
            add(new Widget.Button(x1, y, 150, 20, () -> "Reflections: " + rf[s.reflections], () -> s.reflections = (s.reflections + 1) % rf.length));
            add(new Widget.Slider(x2, y, 150, "Block Reflections", 0, 3, 0.05f, () -> s.blockReflections,
                    v -> { s.blockReflections = (float) v; s.reflections = 2; }, Effects::pct));
            y += 24;
            add(new Widget.Button(x1, y, 150, 20, () -> "World AO: " + onOff(s.ssao), () -> s.ssao = !s.ssao));
            Widget.Slider ao = add(new Widget.Slider(x2, y, 150, "World AO", 0, 2, 0.05f, () -> s.ssaoStrength,
                    v -> { s.ssaoStrength = (float) v; s.ssao = true; }, Effects::pct));
            ao.defaultValue = 1;
            y += 24;
            add(new Widget.Button(x1, y, 150, 20, "Reload reflections.txt", () -> game.assets.reloadMaterials()));
            add(new Widget.Button(x2, y, 150, 20, () -> "All Effects: " + (allOn(s) ? "ON" : "OFF"), () -> {
                boolean on = !allOn(s);
                s.waving = on; s.waterShader = on; s.godRays = on; s.reflections = on ? 2 : 0;
            }));
            infoY = y + 28;
            add(new Widget.Button(width / 2 - 100, height - 27, 200, 20, "Done", this::onClose));
        }

        private static boolean allOn(Settings s) { return s.waving && s.waterShader && s.godRays && s.reflections == 2; }

        @Override public void render(GuiRenderer g, int mx, int my, float partial) {
            if (game.session == null) g.background(0xFF404040);
            else g.gradient(0, 0, width, height, 0xC0101010, 0xD0101010);
            title(g, "Shader Effects", 15);
            Settings s = game.settings;
            int y = infoY;
            List<String> info = new ArrayList<>();
            info.add("World AO: soft shadows in corners and under items, models, players, NPCs, cars.");
            info.add("Waving Plants: leaves, grass, flowers, crops and vines sway in the wind.");
            info.add("Water: moving waves, sky reflection and sun glints on the water.");
            info.add("God Rays: light shafts from the sun and moon. With Shadows on they are");
            info.add("real volumetric light (through leaves and windows), without - around the sun.");
            info.add("Reflections: the world in the water and on glossy blocks (glass, ice, metal,");
            info.add("quartz, concrete...: game/reflections.txt).");
            if (s.rayTracing == 1 && s.reflections > 0) info.add("§eWith ray tracing the water reflections are ray traced.");
            for (String l : info) { if (y > height - 40) break; g.centered(l, width / 2f, y, 0xA0A0A0); y += 11; }
            super.render(g, mx, my, partial);
        }

        @Override public void onClose() { game.settings.save(); game.setScreen(parent); }
    }

    // ============================================================================ presets and transitions
    /**
     * Shader presets and the look transition (Left Alt + , in the game): From shader preset + colour preset ->
     * To shader preset + colour preset in the chosen time.
     */
    public static final class Transitions extends Screen {
        private final Screen parent;
        private Widget.TextField name;
        private String status = "";
        private int statusTicks, infoY;

        public Transitions(DerpCraft g, Screen parent) { super(g); this.parent = parent; }

        private List<String> shaders(boolean from) { return derpcraft.game.ShaderLook.names(game.shadersDir, from); }

        private List<String> colors(boolean from) {
            List<String> l = new ArrayList<>();
            if (from) l.add(derpcraft.game.ShaderLook.CURRENT);
            l.add(derpcraft.game.LookTransition.COLOR_OFF);
            l.addAll(Grading.presetNames(game.gradingDir));
            return l;
        }

        private static String cycle(List<String> list, String cur, int d) {
            int i = list.indexOf(cur);
            return list.get(i < 0 ? 0 : Math.floorMod(i + d, list.size()));
        }

        /** One "< label >" picker; 'set' gets the next / previous entry of the list. */
        private void picker(int x, int y, java.util.function.Supplier<String> label, java.util.function.IntConsumer step) {
            add(new Widget.Button(x, y, 16, 20, "<", () -> step.accept(-1)));
            add(new Widget.Button(x + 18, y, 114, 20, label, () -> step.accept(1)));
            add(new Widget.Button(x + 134, y, 16, 20, ">", () -> step.accept(1)));
        }

        private void changed() { game.transition.reset(game); }

        private static String time(float s) {
            if (s < 60) return (s == Math.round(s) ? String.valueOf(Math.round(s)) : String.format(Locale.ROOT, "%.1f", s)) + " s";
            int t = Math.round(s);
            return t / 60 + ":" + String.format(Locale.ROOT, "%02d", t % 60) + " min";
        }

        @Override public void init() {
            Settings s = game.settings;
            int x1 = width / 2 - 155, x2 = width / 2 + 5, y = height / 6 - 8;
            picker(x1, y, () -> "From: " + s.transFromShader, d -> { s.transFromShader = cycle(shaders(true), s.transFromShader, d); changed(); });
            picker(x2, y, () -> "Color: " + s.transFromColor, d -> { s.transFromColor = cycle(colors(true), s.transFromColor, d); changed(); });
            y += 24;
            picker(x1, y, () -> "To: " + s.transToShader, d -> { s.transToShader = cycle(shaders(false), s.transToShader, d); changed(); });
            picker(x2, y, () -> "Color: " + s.transToColor, d -> { s.transToColor = cycle(colors(false), s.transToColor, d); changed(); });
            y += 24;
            // 0.5 s .. 5 min, finer at the short end
            Widget.Slider dur = add(new Widget.Slider(x1, y, 150, "Time", 0, 1, 0.002f,
                    () -> Math.sqrt(Math.max(0, (s.transSeconds - 0.5f) / 299.5f)),
                    v -> { float sec = 0.5f + 299.5f * (float) (v * v); s.transSeconds = sec < 10 ? Math.round(sec * 2) / 2f : Math.round(sec); },
                    v -> time(s.transSeconds)));
            dur.defaultValue = Math.sqrt(9.5 / 299.5);
            add(new Widget.Button(x2, y, 150, 20, () -> "Curve: " + (s.transCurve == 1 ? "Smooth" : "Linear"), () -> s.transCurve = 1 - s.transCurve));
            y += 24;
            add(new Widget.Button(x1, y, 150, 20, "Show From", () -> show(false)));
            add(new Widget.Button(x2, y, 150, 20, "Show To", () -> show(true)));
            y += 24;
            add(new Widget.Button(x1, y, 310, 20, () -> game.transition.running() ? "Turn the transition around" : "Start the transition now", () -> {
                game.settings.save();
                game.setScreen(null);
                if (game.session != null) game.session.chat(game.transition.toggle(game));
            }));
            y += 28;
            name = add(new Widget.TextField(x1, y + 3, 150, 14));
            name.hint = "preset name";
            name.maxLength = 40;
            name.onEnter = this::save;
            add(new Widget.Button(x2, y, 150, 20, "Save shaders as preset", this::save));
            infoY = y + 28;
            add(new Widget.Button(width / 2 - 100, height - 27, 200, 20, "Done", this::onClose));
        }

        private void show(boolean to) {
            String err = game.transition.show(game, to);
            flash(err != null ? err : "Showing " + (to ? "To" : "From") + " look");
        }

        private void save() {
            String n = name.text.trim().replaceAll("[^A-Za-z0-9 _&-]", "_");
            if (n.isEmpty()) { flash("§eType a name for the preset"); return; }
            if (Arrays.asList(derpcraft.game.ShaderLook.BUILTIN).contains(n)) n = n + " (my)";
            try {
                derpcraft.game.ShaderLook.of(game.settings).save(game.shadersDir, n);
                name.text = n;
                flash("Saved shader preset \"" + n + "\" (game\\shaders\\" + n + ".txt)");
            } catch (Exception e) { flash("§cSave failed: " + e.getMessage()); }
        }

        private void flash(String s) { status = s; statusTicks = 80; }

        @Override public void tick() { super.tick(); if (statusTicks > 0) statusTicks--; }

        @Override public void render(GuiRenderer g, int mx, int my, float partial) {
            if (game.session == null) g.background(0xFF404040);
            else g.gradient(0, 0, width, height, 0xC0101010, 0xD0101010);
            title(g, "Presets & Transitions", 8);
            int y = infoY;
            List<String> info = new ArrayList<>();
            if (statusTicks > 0) info.add(status);
            String key = derpcraft.game.Keys.n(derpcraft.game.Keys.TRANSITION);
            info.add("In the game " + key + " moves From -> To in the set time; again: back to From.");
            info.add("Pressed during a transition it turns around. (current) = the look on screen.");
            info.add("Shader preset: shadows, fog, coloured light, effects. Color: grading presets (F6),");
            info.add("Off = cinematic pipeline off. Everything fades smoothly; ray tracing switches half way.");
            if (game.transition.running()) info.add("§eRunning: " + game.transition.label() + "  " + Math.round(game.transition.progress() * 100) + "%");
            for (String l : info) { if (y > height - 40) break; g.centered(l, width / 2f, y, 0xA0A0A0); y += 11; }
            super.render(g, mx, my, partial);
        }

        @Override public void onClose() { game.settings.save(); game.setScreen(parent); }
    }

    // ============================================================================ colour grading
    public static final class GradingEditor extends Screen {
        private final Screen parent;
        private static int page = 0;
        private static int curveChannel = 0;
        private static final String[] PAGES = {"Light", "Color", "Effects", "Curves"};
        private Widget.TextField presetName;
        private List<String> presets;
        private int presetIndex;
        private String status = "";
        private int statusTicks;
        private CurveEditor curves;
        private static final int PW = 168;

        public GradingEditor(DerpCraft g, Screen parent) { super(g); this.parent = parent; }

        private Grading gr() { return game.grading; }

        private void refreshPresets() { presets = Grading.presetNames(game.gradingDir); }

        @Override public void init() {
            refreshPresets();
            int x = 4, w = PW - 8, y = 4;
            Settings st = game.settings;
            y += 12;
            add(new Widget.Button(x, y, w, 16, () -> "Cinematic Pipeline: " + (st.cinematic ? "ON" : "OFF"), () -> st.cinematic = !st.cinematic));
            y += 18;
            add(new Widget.Button(x, y, 14, 16, "<", () -> pickPreset(-1)));
            presetName = add(new Widget.TextField(x + 17, y + 2, w - 34 - 52, 12));
            if (presetName.text.isEmpty()) presetName.text = game.gradingName;
            add(new Widget.Button(x + w - 14 - 52 - 2 + 2, y, 14, 16, ">", () -> pickPreset(1)));
            add(new Widget.Button(x + w - 50, y, 50, 16, "Save", this::save));
            y += 18;
            int tw = (w - 6) / 4;
            for (int i = 0; i < 4; i++) {
                final int p = i;
                Widget.Button b = add(new Widget.Button(x + i * (tw + 2), y, tw, 16, () -> (page == p ? "§e" : "") + PAGES[p], () -> { page = p; game.relayout(); }));
                b.active = true;
            }
            y += 20;
            switch (page) {
                case 0 -> {
                    slider(x, y, w, "Exposure", -3, 3, 0.05f, () -> gr().exposure, v -> gr().exposure = (float) v, v -> String.format(Locale.ROOT, "%+.2f EV", v), 0); y += 18;
                    add(new Widget.Button(x, y, w, 16, () -> "Tone Map: " + Grading.TONEMAPS[gr().tonemap], () -> gr().tonemap = (gr().tonemap + 1) % Grading.TONEMAPS.length)); y += 18;
                    slider(x, y, w, "Temperature", 2000, 12000, 50, () -> gr().temperature, v -> gr().temperature = (float) v, v -> (int) (float) v + " K", 6500); y += 18;
                    slider(x, y, w, "Tint", -1, 1, 0.01f, () -> gr().tint, v -> gr().tint = (float) v, v -> String.format(Locale.ROOT, "%+.2f", v), 0); y += 18;
                    slider(x, y, w, "Contrast", 0.5f, 1.8f, 0.01f, () -> gr().contrast, v -> gr().contrast = (float) v, v -> String.format(Locale.ROOT, "%.2f", v), 1); y += 18;
                    slider(x, y, w, "Lift (Shadows)", -0.2f, 0.2f, 0.005f, () -> gr().lift, v -> gr().lift = (float) v, v -> String.format(Locale.ROOT, "%+.3f", v), 0); y += 18;
                    slider(x, y, w, "Gamma (Mids)", 0.4f, 2.0f, 0.01f, () -> gr().gamma, v -> gr().gamma = (float) v, v -> String.format(Locale.ROOT, "%.2f", v), 1); y += 18;
                    slider(x, y, w, "Gain (Highlights)", 0.5f, 1.6f, 0.01f, () -> gr().gain, v -> gr().gain = (float) v, v -> String.format(Locale.ROOT, "%.2f", v), 1);
                }
                case 1 -> {
                    slider(x, y, w, "Saturation", 0, 2, 0.01f, () -> gr().saturation, v -> gr().saturation = (float) v, v -> (int) (v * 100) + "%", 1); y += 18;
                    slider(x, y, w, "Vibrance", -1, 1, 0.01f, () -> gr().vibrance, v -> gr().vibrance = (float) v, v -> String.format(Locale.ROOT, "%+.2f", v), 0); y += 18;
                    slider(x, y, w, "Shadows Hue", 0, 360, 1, () -> gr().shadowHue, v -> gr().shadowHue = (float) v, v -> (int) (float) v + "°", 210); y += 18;
                    slider(x, y, w, "Shadows Tint", 0, 1, 0.01f, () -> gr().shadowAmount, v -> gr().shadowAmount = (float) v, v -> (int) (v * 100) + "%", 0); y += 18;
                    slider(x, y, w, "Highlights Hue", 0, 360, 1, () -> gr().highlightHue, v -> gr().highlightHue = (float) v, v -> (int) (float) v + "°", 40); y += 18;
                    slider(x, y, w, "Highlights Tint", 0, 1, 0.01f, () -> gr().highlightAmount, v -> gr().highlightAmount = (float) v, v -> (int) (v * 100) + "%", 0); y += 18;
                    add(new Widget.Button(x, y, w, 16, () -> "LUT: " + (gr().lut.isEmpty() ? "None" : gr().lut), this::nextLut)); y += 18;
                    slider(x, y, w, "LUT Strength", 0, 1, 0.01f, () -> gr().lutStrength, v -> gr().lutStrength = (float) v, v -> (int) (v * 100) + "%", 1);
                }
                case 2 -> {
                    slider(x, y, w, "Bloom", 0, 1.5f, 0.01f, () -> gr().bloom, v -> gr().bloom = (float) v, v -> (int) (v * 100) + "%", 0); y += 18;
                    slider(x, y, w, "Bloom Threshold", 0, 2, 0.01f, () -> gr().bloomThreshold, v -> gr().bloomThreshold = (float) v, v -> String.format(Locale.ROOT, "%.2f", v), 1.0); y += 18;
                    slider(x, y, w, "Emissive Glow", 0, 4, 0.05f, () -> gr().emissive, v -> gr().emissive = (float) v, v -> String.format(Locale.ROOT, "%.2f", v), 0); y += 18;
                    slider(x, y, w, "Vignette", 0, 1, 0.01f, () -> gr().vignette, v -> gr().vignette = (float) v, v -> (int) (v * 100) + "%", 0); y += 18;
                    slider(x, y, w, "Film Grain", 0, 1, 0.01f, () -> gr().grain, v -> gr().grain = (float) v, v -> (int) (v * 100) + "%", 0); y += 18;
                    add(new Widget.Button(x, y, w, 16, () -> "Colored Light: " + (st.coloredLight ? "ON" : "OFF"), () -> st.coloredLight = !st.coloredLight)); y += 18;
                    add(new Widget.Button(x, y, w, 16, "Shaders...", () -> game.setScreen(new Shaders(game, this))));
                }
                default -> {
                    String[] ch = {"RGB", "Red", "Green", "Blue"};
                    int cw = (w - 6) / 4;
                    for (int i = 0; i < 4; i++) {
                        final int c = i;
                        add(new Widget.Button(x + i * (cw + 2), y, cw, 16, () -> (curveChannel == c ? "§e" : "") + ch[c], () -> curveChannel = c));
                    }
                    y += 18;
                    int size = Math.min(w, height - y - 24);
                    curves = add(new CurveEditor(x + (w - size) / 2, y, size, size));
                    y += size + 2;
                    add(new Widget.Button(x, y, w / 2 - 1, 16, "Reset Curve", () -> gr().resetCurve(curveChannel)));
                    add(new Widget.Button(x + w / 2 + 1, y, w / 2 - 1, 16, "Reset All", () -> { for (int c = 0; c < 4; c++) gr().resetCurve(c); }));
                }
            }
            add(new Widget.Button(x, height - 20, w / 2 - 1, 16, "Reset", () -> { game.grading = new Grading(); game.gradingName = "Vanilla"; presetName.text = "Vanilla"; }));
            add(new Widget.Button(x + w / 2 + 1, height - 20, w / 2 - 1, 16, "Done", this::onClose));
        }

        private void slider(int x, int y, int w, String name, float min, float max, float step,
                            java.util.function.DoubleSupplier get, java.util.function.DoubleConsumer set, java.util.function.Function<Float, String> fmt, double def) {
            Widget.Slider s = add(new Widget.Slider(x, y, w, 16, name, min, max, step, get, set, fmt));
            s.defaultValue = def;
        }

        private void pickPreset(int dir) {
            refreshPresets();
            int i = presets.indexOf(presetName.text);
            presetIndex = Math.floorMod((i < 0 ? presetIndex : i) + dir, presets.size());
            String n = presets.get(presetIndex);
            Grading loaded = Grading.byName(n, game.gradingDir);
            game.grading = loaded != null ? loaded : new Grading();
            game.gradingName = n;
            presetName.text = n;
            if (!game.settings.cinematic) { game.settings.cinematic = true; flash("Cinematic pipeline turned on"); }
        }

        private void save() {
            String n = presetName.text.trim().replaceAll("[^A-Za-z0-9 _&-]", "_");
            if (n.isEmpty()) n = "My Look";
            if (Arrays.asList(Grading.BUILTIN).contains(n) || derpcraft.api.ModContext.GRADING_PRESETS.containsKey(n)) n = n + " (my)";
            try {
                gr().save(game.gradingDir.resolve(n + ".txt"));
                game.gradingName = n;
                presetName.text = n;
                flash("Saved preset \"" + n + "\"");
            } catch (Exception e) { flash("§cSave failed: " + e.getMessage()); }
        }

        private void nextLut() {
            List<String> luts = PostProcessor.listLuts(game.lutsDir);
            if (luts.isEmpty()) { flash("Put .cube files into game\\luts\\"); gr().lut = ""; return; }
            int i = luts.indexOf(gr().lut);
            gr().lut = i + 1 >= luts.size() ? "" : luts.get(i + 1);
        }

        private void flash(String s) { status = s; statusTicks = 60; }

        @Override public void tick() { super.tick(); if (statusTicks > 0) statusTicks--; }

        @Override public void render(GuiRenderer g, int mx, int my, float partial) {
            game.post.wantHistogram = page == 3;
            g.fill(0, 0, PW, height, 0xB0000000);
            g.text("Color Grading", 4, 5, 0xFFFFFF, true);
            String hint = "Hold Tab: before";
            g.text(hint, PW - g.textWidth(hint) - 4, 5, 0x808080, false);
            super.render(g, mx, my, partial);
            if (statusTicks > 0) g.text(status, PW + 6, height - 14, 0xFFFFFF, true);
            if (!game.settings.cinematic) g.text("§eCinematic pipeline is OFF - grading is not applied", PW + 6, 6, 0xFFFFFF, true);
            if (page != 3) {
                for (Widget w : widgets)
                    if (w instanceof Widget.Slider sl && sl.contains(mx, my)) { g.text("Right click: reset", PW + 6, height - 26, 0xA0A0A0, true); break; }
            } else g.text("Click: add point, drag: move, right click: delete", PW + 6, height - 26, 0xA0A0A0, true);
        }

        @Override public void keyPressed(int key, int mods) {
            if (key == GLFW_KEY_F6) { onClose(); return; }
            super.keyPressed(key, mods);
        }

        @Override public void mouseReleased(int mx, int my, int button) {
            if (curves != null) curves.dragging = -1;
            super.mouseReleased(mx, my, button);
        }

        /** True while Tab is held: show the ungraded image for comparison. */
        public boolean comparing() { return glfwGetKey(game.window, GLFW_KEY_TAB) == GLFW_PRESS; }

        @Override public void onClose() {
            game.post.wantHistogram = false;
            game.saveGrading();
            game.settings.save();
            game.setScreen(parent);
        }

        // ------------------------------------------------------------------ curve editor
        final class CurveEditor extends Widget {
            private int dragging = -1;

            CurveEditor(int x, int y, int w, int h) { super(x, y, w, h); }

            private List<float[]> pts() { return gr().curves[curveChannel]; }

            private float px(float v) { return x + 1 + v * (w - 2); }
            private float py(float v) { return y + h - 1 - v * (h - 2); }

            @Override public void render(GuiRenderer g, int mx, int my) {
                int[] cols = {0xFFFFFFFF, 0xFFFF5050, 0xFF50FF50, 0xFF5080FF};
                g.fill(x - 1, y - 1, x + w + 1, y + h + 1, 0xFF606060);
                g.fill(x, y, x + w, y + h, 0xFF141414);
                // histogram of the graded image
                float[] hist = game.post.histogram[curveChannel];
                int hc = (cols[curveChannel] & 0xFFFFFF) | 0x50000000;
                for (int i = 0; i < 64; i++) {
                    float x0 = px(i / 64f), x1 = px((i + 1) / 64f);
                    g.fill(x0, py(hist[i] * 0.95f), x1, y + h - 1, hc);
                }
                for (int i = 1; i < 4; i++) {
                    g.fill(px(i / 4f), y, px(i / 4f) + 1, y + h, 0x30FFFFFF);
                    g.fill(x, py(i / 4f), x + w, py(i / 4f) + 1, 0x30FFFFFF);
                }
                // diagonal reference and the curve
                int n = Math.max(32, w);
                float[] baked = Grading.bake(pts(), n);
                for (int i = 0; i < n; i++) {
                    float t = i / (float) (n - 1);
                    g.fill(px(t), py(t), px(t) + 1, py(t) + 1, 0x40FFFFFF);
                }
                for (int i = 0; i < n - 1; i++) {
                    float x0 = px(i / (float) (n - 1)), y0 = py(baked[i]), y1 = py(baked[i + 1]);
                    g.fill(x0, Math.min(y0, y1), x0 + 1, Math.max(y0, y1) + 1, cols[curveChannel]);
                }
                List<float[]> p = pts();
                int hover = find(mx, my);
                for (int i = 0; i < p.size(); i++) {
                    float cx = px(p.get(i)[0]), cy = py(p.get(i)[1]);
                    int c = i == dragging || i == hover ? 0xFFFFFF00 : 0xFFFFFFFF;
                    g.fill(cx - 2, cy - 2, cx + 3, cy + 3, 0xFF000000);
                    g.fill(cx - 1, cy - 1, cx + 2, cy + 2, c);
                }
                if (dragging >= 0 && dragging < p.size()) {
                    float[] d = p.get(dragging);
                    g.text(String.format(Locale.ROOT, "%d -> %d", Math.round(d[0] * 255), Math.round(d[1] * 255)), x + 3, y + 3, 0xFFFFFF, true);
                }
            }

            private int find(int mx, int my) {
                List<float[]> p = pts();
                for (int i = 0; i < p.size(); i++)
                    if (Math.abs(px(p.get(i)[0]) - mx) <= 4 && Math.abs(py(p.get(i)[1]) - my) <= 4) return i;
                return -1;
            }

            @Override public boolean click(int mx, int my, int button) {
                List<float[]> p = pts();
                int i = find(mx, my);
                if (button == 1) {
                    if (i > 0 && i < p.size() - 1) p.remove(i);
                    dragging = -1;
                    return true;
                }
                if (i < 0) {
                    float vx = Math.max(0, Math.min(1, (mx - x - 1) / (float) (w - 2)));
                    float vy = Math.max(0, Math.min(1, (y + h - 1 - my) / (float) (h - 2)));
                    int at = 0;
                    while (at < p.size() && p.get(at)[0] < vx) at++;
                    if (at == 0 || at == p.size()) return true;
                    p.add(at, new float[]{vx, vy});
                    i = at;
                }
                dragging = i;
                return true;
            }

            @Override public void drag(int mx, int my) {
                List<float[]> p = pts();
                if (dragging < 0 || dragging >= p.size()) return;
                float vx = Math.max(0, Math.min(1, (mx - x - 1) / (float) (w - 2)));
                float vy = Math.max(0, Math.min(1, (y + h - 1 - my) / (float) (h - 2)));
                float[] d = p.get(dragging);
                if (dragging == 0) vx = Math.min(vx, p.get(1)[0] - 0.01f);
                else if (dragging == p.size() - 1) vx = Math.max(vx, p.get(dragging - 1)[0] + 0.01f);
                else vx = Math.max(p.get(dragging - 1)[0] + 0.01f, Math.min(p.get(dragging + 1)[0] - 0.01f, vx));
                d[0] = vx;
                d[1] = vy;
            }
        }
    }
}
