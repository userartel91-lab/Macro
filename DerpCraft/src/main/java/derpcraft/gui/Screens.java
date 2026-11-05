package derpcraft.gui;

import derpcraft.game.Keys;

import derpcraft.DerpCraft;
import derpcraft.render.GuiRenderer;
import derpcraft.resource.Resources;
import derpcraft.world.WorldInfo;

import java.io.IOException;
import java.nio.file.*;
import java.text.SimpleDateFormat;
import java.util.*;

import static org.lwjgl.glfw.GLFW.*;

/** All menus. */
public final class Screens {
    private Screens() {}

    // ============================================================================ title
    public static final class Title extends Screen {
        private static final String[] SPLASHES = {"Machinima ready!", "Now with greedy meshing!", "100% more derp!", "Build big!",
                "Also try Minecraft!", "Lights, camera, action!", "Multi-draw indirect!", "2x2 textures!", "Moddable!"};
        private final String splash = SPLASHES[new Random().nextInt(SPLASHES.length)];

        public Title(DerpCraft g) { super(g); }

        @Override public void init() {
            int y = height / 4 + 48;
            add(new Widget.Button(width / 2 - 100, y, 200, 20, "Singleplayer", () -> game.setScreen(new WorldSelect(game, this))));
            add(new Widget.Button(width / 2 - 100, y + 24, 200, 20, "Resource Packs...", () -> game.setScreen(new Packs(game, this))));
            add(new Widget.Button(width / 2 - 100, y + 72, 98, 20, "Options...", () -> game.setScreen(new Options(game, this))));
            add(new Widget.Button(width / 2 + 2, y + 72, 98, 20, "Quit Game", game::quit));
        }

        @Override public void render(GuiRenderer g, int mx, int my, float partial) {
            var A = game.assets;
            if (A.panorama != null) {
                g.flush();
                game.drawPanorama((System.currentTimeMillis() % 1_000_000L) / 50f + partial);
                g.begin(game.fbWidth, game.fbHeight, game.settings.guiScale);
                g.gradient(0, 0, width, height, 0x80FFFFFF, 0x00FFFFFF);
                g.gradient(0, 0, width, height, 0x00000000, 0x80000000);
            } else g.background(0xFF404040);
            // logo (274 px wide like vanilla), splash rotated -20 degrees with a pulse
            float lw = 274, lh = lw * A.logo.getHeight() / A.logo.getWidth();
            float lx = width / 2f - lw / 2f, ly = 30;
            g.blitStretch(A.logoTex, lx, ly, lw, lh, 0, 0, 1, 1, 0xFFFFFFFF);
            float pulse = 1.8f - (float) Math.abs(Math.sin((System.currentTimeMillis() % 1000L) / 1000.0 * Math.PI * 2) * 0.1f);
            pulse = pulse * 100f / (g.textWidth(splash) + 32);
            g.textRotated(splash, width / 2f + 90, ly + lh + 2, -20, pulse, 0xFFFFFF00);
            g.text("DerpCraft " + DerpCraft.VERSION, 2, height - 10, 0xFFFFFF, true);
            if (game.remote != null && game.remote.running())
                g.text("Wi-Fi remote: " + game.remote.addressText() + "  PIN " + game.remote.pin, 2, height - 20, 0x80D0FF, true);
            String r = "Mods: " + game.mods.mods.size();
            g.text(r, width - g.textWidth(r) - 2, height - 10, 0xFFFFFF, true);
            super.render(g, mx, my, partial);
        }

        @Override public void onClose() {}
    }

    // ============================================================================ world list
    public static final class WorldSelect extends Screen {
        private final Screen parent;
        private final List<WorldInfo> worlds = new ArrayList<>();
        private int selected = -1, scroll;
        private Widget.Button play, delete;

        public WorldSelect(DerpCraft g, Screen parent) {
            super(g);
            this.parent = parent;
            reload();
        }

        private void reload() {
            worlds.clear();
            try (DirectoryStream<Path> ds = Files.newDirectoryStream(game.savesDir)) {
                for (Path p : ds) if (Files.exists(p.resolve("level.properties"))) try { worlds.add(WorldInfo.load(p)); } catch (IOException ignored) {}
            } catch (IOException ignored) {}
            worlds.sort((a, b) -> Long.compare(b.lastPlayed, a.lastPlayed));
            selected = worlds.isEmpty() ? -1 : 0;
        }

        @Override public void init() {
            play = add(new Widget.Button(width / 2 - 154, height - 52, 150, 20, "Play Selected World", () -> {
                if (selected >= 0) game.openWorld(worlds.get(selected));
            }));
            add(new Widget.Button(width / 2 + 4, height - 52, 150, 20, "Create New World", () -> game.setScreen(new CreateWorld(game, this))));
            delete = add(new Widget.Button(width / 2 - 154, height - 28, 150, 20, "Delete", () -> {
                if (selected < 0) return;
                WorldInfo w = worlds.get(selected);
                game.setScreen(new Confirm(game, "Are you sure you want to delete this world?", "'" + w.name + "' will be lost forever! (A long time!)", ok -> {
                    if (ok) { deleteDir(w.dir); reload(); }
                    game.setScreen(this);
                }));
            }));
            add(new Widget.Button(width / 2 + 4, height - 28, 150, 20, "Cancel", () -> game.setScreen(parent)));
        }

        @Override public void render(GuiRenderer g, int mx, int my, float partial) {
            g.background(0xFF404040);
            g.fill(0, 32, width, height - 64, 0xA0000000);
            title(g, "Select World", 16);
            int y = 36 - scroll;
            SimpleDateFormat df = new SimpleDateFormat("dd/MM/yy HH:mm");
            for (int i = 0; i < worlds.size(); i++, y += 36) {
                if (y < 32 || y > height - 64 - 32) continue;
                WorldInfo w = worlds.get(i);
                int x = width / 2 - 110;
                if (i == selected) { g.fill(x - 2, y - 2, x + 222, y + 34, 0xFF808080); g.fill(x - 1, y - 1, x + 221, y + 33, 0xFF000000); }
                g.text(w.name, x + 2, y + 1, 0xFFFFFF, true);
                g.text(w.dir.getFileName() + " (" + df.format(new Date(w.lastPlayed)) + ")", x + 2, y + 12, 0x808080, true);
                g.text(("flat".equals(w.generator) ? "Superflat" : "Default") + ", Creative", x + 2, y + 22, 0x808080, true);
            }
            if (worlds.isEmpty()) g.centered("No worlds yet - create one!", width / 2f, height / 2f - 20, 0xA0A0A0);
            play.active = delete.active = selected >= 0;
            super.render(g, mx, my, partial);
        }

        @Override public void mouseClicked(int mx, int my, int button) {
            if (my >= 32 && my < height - 64) {
                int i = (my - 36 + scroll) / 36;
                if (i >= 0 && i < worlds.size() && mx > width / 2 - 112 && mx < width / 2 + 112) {
                    if (i == selected && game.isDoubleClick()) { game.openWorld(worlds.get(i)); return; }
                    selected = i;
                    return;
                }
            }
            super.mouseClicked(mx, my, button);
        }

        @Override public void scrolled(double a) { scroll = Math.max(0, Math.min(Math.max(0, worlds.size() * 36 - (height - 100)), scroll - (int) (a * 18))); }

        @Override public void onClose() { game.setScreen(parent); }
    }

    static void deleteDir(Path dir) {
        try (var st = Files.walk(dir)) {
            st.sorted(Comparator.reverseOrder()).forEach(p -> { try { Files.delete(p); } catch (IOException ignored) {} });
        } catch (IOException ignored) {}
    }

    // ============================================================================ create world
    public static final class CreateWorld extends Screen {
        private final Screen parent;
        private Widget.TextField name, seed;
        private boolean flat, more;
        private String flatLayers = "bedrock,2*dirt,grass";
        private int mode = 0;
        private static final String[] MODES = {"Survival", "Creative", "Spectator"};
        private static final String[][] MODE_INFO = {{"Search for resources, crafting, gain", "levels, health and hunger"},
                {"Unlimited resources, free flying and", "destroy blocks instantly"}, {"You can look but don't touch", "(fly through blocks - camera mode)"}};

        public CreateWorld(DerpCraft g, Screen parent) { super(g); this.parent = parent; }

        @Override public void init() {
            String oldName = name == null ? "New World" : name.text, oldSeed = seed == null ? "" : seed.text;
            name = add(new Widget.TextField(width / 2 - 100, 60, 200, 20));
            name.text = oldName;
            name.focused = !more;
            seed = add(new Widget.TextField(width / 2 - 100, 60, 200, 20));
            seed.text = oldSeed;
            seed.hint = "Leave blank for a random seed";
            seed.focused = more;
            name.visible = !more; seed.visible = more;
            if (!more) add(new Widget.Button(width / 2 - 75, 115, 150, 20, () -> "Game Mode: " + MODES[mode], () -> mode = (mode + 1) % 3));
            else add(new Widget.Button(width / 2 - 155, 100, 150, 20, () -> "World Type: " + (flat ? "Superflat" : "Default"), () -> flat = !flat));
            add(new Widget.Button(width / 2 - 75, 187, 150, 20, () -> more ? "Done" : "More World Options...", () -> { more = !more; game.relayout(); }));
            add(new Widget.Button(width / 2 - 155, height - 28, 150, 20, "Create New World", this::create));
            add(new Widget.Button(width / 2 + 5, height - 28, 150, 20, "Cancel", () -> game.setScreen(parent)));
            name.onEnter = this::create;
        }

        private void create() {
            String n = name.text.trim().isEmpty() ? "New World" : name.text.trim();
            String folder = n.replaceAll("[^A-Za-z0-9 _-]", "_");
            Path dir = game.savesDir.resolve(folder);
            while (Files.exists(dir)) { folder += "-"; dir = game.savesDir.resolve(folder); }
            WorldInfo info = new WorldInfo(dir);
            info.name = n;
            String s = seed.text.trim();
            if (s.isEmpty()) info.seed = new Random().nextLong();
            else try { info.seed = Long.parseLong(s); } catch (NumberFormatException e) { info.seed = s.hashCode(); }
            info.generator = flat ? "flat" : "default";
            info.flatLayers = flatLayers;
            info.time = 1000;
            info.gameMode = MODES[mode].toLowerCase(Locale.ROOT);
            try { info.save(); } catch (IOException e) { System.err.println(e); }
            game.openWorld(info);
        }

        @Override public void render(GuiRenderer g, int mx, int my, float partial) {
            g.background(0xFF404040);
            title(g, "Create New World", 20);
            if (!more) {
                g.text("World Name", width / 2f - 100, 47, 0xA0A0A0, true);
                g.text("Will be saved in: " + name.text.trim().replaceAll("[^A-Za-z0-9 _-]", "_"), width / 2f - 100, 85, 0xA0A0A0, true);
                g.text(MODE_INFO[mode][0], width / 2f - 100, 137, 0xA0A0A0, true);
                g.text(MODE_INFO[mode][1], width / 2f - 100, 149, 0xA0A0A0, true);
            } else {
                g.text("Seed for the world generator", width / 2f - 100, 47, 0xA0A0A0, true);
                g.text("Leave blank for a random seed", width / 2f - 100, 85, 0xA0A0A0, true);
                if (flat) g.text("Flat grass world - perfect for building cities", width / 2f - 100, 125, 0xA0A0A0, true);
            }
            super.render(g, mx, my, partial);
        }

        @Override public void mouseClicked(int mx, int my, int button) {
            for (Widget w : widgets) if (w instanceof Widget.TextField tf && !tf.visible) tf.focused = false;
            super.mouseClicked(mx, my, button);
            for (Widget w : widgets) if (w instanceof Widget.TextField tf && !tf.visible) tf.focused = false;
        }

        @Override public void onClose() { game.setScreen(parent); }
    }

    // ============================================================================ gameplay
    public static final class Gameplay extends Screen {
        private final Screen parent;

        public Gameplay(DerpCraft g, Screen parent) { super(g); this.parent = parent; }

        @Override public void init() {
            var s = game.settings;
            int x1 = width / 2 - 155, x2 = width / 2 + 5, y = height / 6;
            add(new Widget.Button(x1, y, 150, 20, () -> "Item Physics: " + (s.itemPhysics ? "ON" : "OFF"), () -> s.itemPhysics = !s.itemPhysics));
            add(new Widget.Button(x2, y, 150, 20, () -> "Item Pickup: " + (s.pickupClick ? "Click" : "Automatic"), () -> s.pickupClick = !s.pickupClick));
            y += 24;
            add(new Widget.Slider(x1, y, 150, "3rd Person Distance", 1, 16, 0.5f, () -> s.thirdPersonDistance, v -> s.thirdPersonDistance = (float) v,
                    v -> String.format(java.util.Locale.ROOT, "%.1f", v))).defaultValue = 4;
            add(new Widget.Button(x2, y, 150, 20, "Color Grading...", () -> game.setScreen(new Graphics.GradingEditor(game, this))));
            y += 24;
            add(new Widget.Button(x1, y, 150, 20, () -> "Chat Messages: " + (s.chatMessages ? "ON" : "OFF"), () -> s.chatMessages = !s.chatMessages));
            add(new Widget.Button(x2, y, 150, 20, "Screen Recording...", () -> game.setScreen(new Recording(game, this))));
            y += 24;
            add(new Widget.Button(x1, y, 150, 20, () -> "Dynamic Light: " + (s.dynamicLights ? "ON" : "OFF"), () -> s.dynamicLights = !s.dynamicLights));
            add(new Widget.Button(x2, y, 150, 20, () -> "Fire Spread: " + (s.fireSpread ? "ON" : "OFF"), () -> s.fireSpread = !s.fireSpread));
            y += 24;
            add(new Widget.Button(x1, y, 150, 20, () -> "Bullets Break Blocks: " + (s.bulletBlocks ? "ON" : "OFF"), () -> s.bulletBlocks = !s.bulletBlocks));
            add(new Widget.Button(x2, y, 150, 20, () -> "Blood: " + (s.blood ? "ON" : "OFF"), () -> s.blood = !s.blood));
            add(new Widget.Button(width / 2 - 100, height - 27, 200, 20, "Done", this::onClose));
        }

        @Override public void render(GuiRenderer g, int mx, int my, float partial) {
            if (game.session == null) g.background(0xFF404040);
            else g.gradient(0, 0, width, height, 0xC0101010, 0xD0101010);
            title(g, "Gameplay", 15);
            int y = height / 6 + 102;
            String[] info = {"Bullets Break Blocks OFF: shots only chip and crack blocks (glass, doors, cameras still break).",
                    "Item Physics: dropped items lie flat on the ground and tumble when thrown.",
                    "Item Pickup - Automatic: walk over items like in Minecraft.",
                    "Item Pickup - Click: aim at an item and left click, it flies into your hands.",
                    "Chat Messages OFF: no chat pop-ups over the game (T still shows them).",
                    "F5 switches between first person, behind and in front of the player."};
            for (String l : info) { g.centered(l, width / 2f, y, 0xA0A0A0); y += 11; }
            super.render(g, mx, my, partial);
        }

        @Override public void onClose() { game.settings.save(); game.setScreen(parent); }
    }

    // ============================================================================ screen recording
    public static final class Recording extends Screen {
        private final Screen parent;
        private static final int[] BITRATES = {5, 10, 15, 20, 30, 40, 50, 60, 80, 100, 130, 160, 200};

        public Recording(DerpCraft g, Screen parent) { super(g); this.parent = parent; }

        private static int next(int[] list, int cur) {
            for (int i = 0; i < list.length; i++) if (list[i] == cur) return list[(i + 1) % list.length];
            return list[0];
        }

        @Override public void init() {
            var s = game.settings;
            var rec = game.recorder;
            int x1 = width / 2 - 155, x2 = width / 2 + 5, y = 34;
            add(new Widget.Button(width / 2 - 100, y, 200, 20, () -> rec.recording() ? "§cStop Recording" : "Start Recording", () -> {
                if (rec.recording()) game.stopRecording();
                else {
                    game.settings.save();
                    game.startRecording();
                    if (rec.recording() && game.session != null) game.setScreen(null);   // straight back to filming
                }
            }));
            y += 26;
            add(new Widget.Button(x1, y, 150, 20, () -> "Encoder: " + derpcraft.record.ScreenRecorder.ENCODERS[s.recEncoder],
                    () -> { s.recEncoder = (s.recEncoder + 1) % 3; rec.warmUp(s.recEncoder); }));
            add(new Widget.Button(x2, y, 150, 20, () -> "Resolution: " + derpcraft.record.ScreenRecorder.heightName(s.recHeight),
                    () -> s.recHeight = next(derpcraft.record.ScreenRecorder.HEIGHTS, s.recHeight)));
            y += 22;
            add(new Widget.Button(x1, y, 150, 20, () -> "Frame Rate: " + s.recFps + " fps",
                    () -> s.recFps = next(derpcraft.record.ScreenRecorder.FPS_CHOICES, s.recFps)));
            add(new Widget.Button(x2, y, 150, 20, () -> "Bitrate: " + s.recBitrate + " Mbit/s", () -> s.recBitrate = next(BITRATES, s.recBitrate)));
            y += 22;
            add(new Widget.Button(x1, y, 150, 20, () -> "Pause in Menus: " + (s.recPauseInMenus ? "ON" : "OFF"), () -> s.recPauseInMenus = !s.recPauseInMenus));
            add(new Widget.Button(x2, y, 150, 20, () -> "Timing: " + (s.recSmooth ? "Smooth" : "Real Time"), () -> s.recSmooth = !s.recSmooth));
            y += 22;
            add(new Widget.Button(width / 2 - 75, y, 150, 20, "Open Folder", () -> game.openFolder(rec.folder())));
            add(new Widget.Button(width / 2 - 100, height - 27, 98, 20, "Find ffmpeg", () -> { rec.rescan(); rec.warmUp(s.recEncoder); game.recordError = null; }));
            add(new Widget.Button(width / 2 + 2, height - 27, 98, 20, "Done", this::onClose));
        }

        @Override public void render(GuiRenderer g, int mx, int my, float partial) {
            if (game.session == null) g.background(0xFF404040);
            else g.gradient(0, 0, width, height, 0xC0101010, 0xD0101010);
            title(g, "Screen Recording", 15);
            var s = game.settings;
            var rec = game.recorder;
            int y = 34 + 26 + 22 * 4 + 6;
            List<String> info = new ArrayList<>();
            if (rec.recording()) {
                info.add("§cREC§r " + rec.width() + "x" + rec.height() + " @ " + rec.fps() + " fps, " + rec.encoderName()
                        + "  " + derpcraft.record.ScreenRecorder.timecode(rec.seconds()) + (rec.dropped() > 0 ? "  §e" + rec.dropped() + " dropped" : ""));
                info.add("§7New settings apply to the next recording.");
            } else {
                int[] sz = derpcraft.record.ScreenRecorder.outputSize(game.fbWidth, game.fbHeight, s.recHeight);
                info.add("Video: " + sz[0] + "x" + sz[1] + ", " + s.recFps + " fps, " + s.recBitrate + " Mbit/s, MP4  §7(~"
                        + Math.round(s.recBitrate * 60 / 8.0) + " MB a minute)");
            }
            info.add("Start / stop in the game: " + Keys.n(Keys.SCREEN_RECORD) + "  §7(Options -> Controls)");
            info.add(s.recSmooth ? "Smooth: every frame is filmed, never dropped; the game slows down if the PC cannot keep up."
                    : "Real time: frames are repeated or dropped to keep up with the clock.");
            info.add(s.recPauseInMenus ? "Pauses in service menus (Esc, NPC Hub, camera, shaders, chat...)"
                    : "Records everything, menus included.");
            if (s.recPauseInMenus) info.add("Game menus (inventory, chests, furnace...) are recorded.");
            info.add("The REC mark in the corner is never in the video.");
            if (game.recordError != null) info.add("§c" + game.recordError);
            else info.add("§7Needs ffmpeg.exe in the game folder; NVENC: NVIDIA card.");
            for (String l : info) { g.centered(l, width / 2f, y, 0xC0C0C0); y += 11; }
            super.render(g, mx, my, partial);
        }

        @Override public void onClose() { game.settings.save(); game.setScreen(parent); }
    }

    // ============================================================================ confirm
    public static final class Confirm extends Screen {
        private final String l1, l2;
        private final java.util.function.Consumer<Boolean> result;

        public Confirm(DerpCraft g, String l1, String l2, java.util.function.Consumer<Boolean> result) { super(g); this.l1 = l1; this.l2 = l2; this.result = result; }

        @Override public void init() {
            add(new Widget.Button(width / 2 - 155, height / 6 + 96, 150, 20, "Yes", () -> result.accept(true)));
            add(new Widget.Button(width / 2 + 5, height / 6 + 96, 150, 20, "Cancel", () -> result.accept(false)));
        }

        @Override public void render(GuiRenderer g, int mx, int my, float partial) {
            g.background(0xFF404040);
            g.centered(l1, width / 2f, 70, 0xFFFFFF);
            g.centered(l2, width / 2f, 90, 0xFFFFFF);
            super.render(g, mx, my, partial);
        }

        @Override public void onClose() { result.accept(false); }
    }

    // ============================================================================ options
    public static final class Options extends Screen {
        private final Screen parent;

        public Options(DerpCraft g, Screen parent) { super(g); this.parent = parent; }

        @Override public void init() {
            var s = game.settings;
            int x1 = width / 2 - 155, x2 = width / 2 + 5, y = height / 6 - 12;
            add(new Widget.Slider(x1, y, 150, "Render Distance", 2, 64, 1, () -> s.renderDistance, v -> s.renderDistance = (int) v, v -> (int) (float) v + " chunks"));
            add(new Widget.Slider(x2, y, 150, "FOV", 30, 110, 1, () -> s.fov, v -> s.fov = (float) v, v -> v == 70 ? "Normal" : v >= 110 ? "Quake Pro" : String.valueOf((int) (float) v)));
            y += 21;
            add(new Widget.Slider(x1, y, 150, "Sensitivity", 0, 1, 0.01f, () -> s.sensitivity, v -> s.sensitivity = (float) v, v -> (int) (v * 200) + "%"));
            add(new Widget.Slider(x2, y, 150, "Brightness", 0, 1, 0.01f, () -> s.gamma, v -> s.gamma = (float) v, v -> v == 0 ? "Moody" : v == 1 ? "Bright" : "+" + (int) (v * 100) + "%"));
            y += 21;
            add(new Widget.Slider(x1, y, 150, "Max Framerate", 10, 260, 10, () -> s.maxFps == 0 ? 260 : s.maxFps, v -> s.maxFps = v >= 260 ? 0 : (int) v, v -> v >= 260 ? "Unlimited" : (int) (float) v + " fps"));
            add(new Widget.Button(x2, y, 150, 20, () -> "Use VSync: " + (s.vsync ? "ON" : "OFF"), () -> { s.vsync = !s.vsync; game.applyVsync(); }));
            y += 21;
            add(new Widget.Button(x1, y, 150, 20, () -> "Clouds: " + (s.clouds ? "Fancy" : "OFF"), () -> s.clouds = !s.clouds));
            add(new Widget.Button(x2, y, 150, 20, () -> "GUI Scale: " + (s.guiScale == 0 ? "Auto" : String.valueOf(s.guiScale)), () -> { s.guiScale = (s.guiScale + 1) % 5; game.relayout(); }));
            y += 21;
            add(new Widget.Button(x1, y, 150, 20, () -> "FOV Effects: " + (s.fovEffects ? "ON" : "OFF"), () -> s.fovEffects = !s.fovEffects));
            add(new Widget.Button(x2, y, 150, 20, () -> "View Bobbing: " + (s.viewBobbing ? "ON" : "OFF"), () -> s.viewBobbing = !s.viewBobbing));
            y += 21;
            add(new Widget.Button(x1, y, 150, 20, () -> "Mipmap Levels: " + (s.mipLevels == 0 ? "OFF" : String.valueOf(s.mipLevels)), () -> { s.mipLevels = (s.mipLevels + 1) % 5; game.reloadAssets(); }));
            add(new Widget.Button(x2, y, 150, 20, () -> "Fullscreen: " + (s.fullscreen ? "ON" : "OFF"), game::toggleFullscreen));
            y += 21;
            add(new Widget.Button(x1, y, 150, 20, "Resource Packs...", () -> game.setScreen(new Packs(game, this))));
            add(new Widget.Button(x2, y, 150, 20, "Controls...", () -> game.setScreen(new Controls(game, this))));
            y += 21;
            add(new Widget.Button(x1, y, 150, 20, "Shaders...", () -> game.setScreen(new Graphics.Shaders(game, this))));
            add(new Widget.Button(x2, y, 150, 20, "Gameplay...", () -> game.setScreen(new Gameplay(game, this))));
            y += 21;
            add(new Widget.Button(x1, y, 150, 20, "Music & Sounds...", () -> game.setScreen(new SoundOptions(game, this))));
            add(new Widget.Button(width / 2 - 100, height - 27, 200, 20, "Done", this::onClose));
        }

        @Override public void render(GuiRenderer g, int mx, int my, float partial) {
            if (game.session == null) g.background(0xFF404040); else g.fill(0, 0, width, height, 0x80101010);
            title(g, "Options", 15);
            super.render(g, mx, my, partial);
        }

        @Override public void onClose() { game.settings.save(); game.setScreen(parent); }
    }

    // ============================================================================ music & sounds
    /** Volume per sound category, as in the original game (Master, Music, Jukebox/Noteblocks, Weather, Blocks, Hostile, Friendly, Players, Ambient). */
    public static final class SoundOptions extends Screen {
        private final Screen parent;

        public SoundOptions(DerpCraft g, Screen parent) { super(g); this.parent = parent; }

        @Override public void init() {
            int x1 = width / 2 - 155, x2 = width / 2 + 5, y = height / 6 - 12;
            var L = derpcraft.audio.Sfx.LABELS;
            add(new Widget.Slider(width / 2 - 155, y, 310, L[0], 0, 1, 0.01f, () -> derpcraft.audio.Sfx.VOL[0], v -> derpcraft.audio.Sfx.setVolume(0, (float) v), SoundOptions::pct));
            y += 26;
            for (int i = 1; i < L.length; i++) {
                final int k = i;
                add(new Widget.Slider((i & 1) == 1 ? x1 : x2, y, 150, L[i], 0, 1, 0.01f, () -> derpcraft.audio.Sfx.VOL[k], v -> derpcraft.audio.Sfx.setVolume(k, (float) v), SoundOptions::pct));
                if ((i & 1) == 0) y += 22;
            }
            add(new Widget.Button(width / 2 - 100, height - 27, 200, 20, "Done", this::onClose));
        }

        private static String pct(float v) { return v <= 0 ? "OFF" : (int) (v * 100) + "%"; }

        @Override public void render(GuiRenderer g, int mx, int my, float partial) {
            if (game.session == null) g.background(0xFF404040); else g.fill(0, 0, width, height, 0x80101010);
            title(g, "Music & Sounds", 15);
            super.render(g, mx, my, partial);
        }

        @Override public void onClose() { game.settings.save(); game.setScreen(parent); }
    }

    // ============================================================================ controls (key bindings)
    public static final class Controls extends Screen {
        private static final int ROW = 18, TOP = 32;
        private static final String[] FIXED = {
                "Mouse wheel: hotbar / free cam speed / orbit radius", "Shift + Orbit / Delete key / Focus: the other variant", "Esc: menu, closes windows",
                "Russian layout: the same keys (labelled B, YU and the dot)"};
        private final Screen parent;
        private static int scroll;
        private Keys.Binding waiting;
        /** Ctrl / Shift / Alt held while choosing a key: the next key makes a combo; released alone, it is the key itself. */
        private int pendingMod = Keys.NONE;
        private final List<Object> rows = new ArrayList<>();

        public Controls(DerpCraft g, Screen parent) { super(g); this.parent = parent; }

        /** True while it waits for a key (then F2 / F11 are assigned instead of used). */
        public boolean capturing() { return waiting != null; }

        private int visibleRows() { return Math.max(3, (height - TOP - 44) / ROW); }

        @Override public void init() {
            for (var k : derpcraft.api.ModContext.KEYS) Keys.mod(k.modId(), k.description(), k.glfwKey());
            rows.clear();
            for (String cat : Keys.CATEGORIES) {
                boolean any = false;
                for (Keys.Binding b : Keys.ALL) if (b.category.equals(cat)) { if (!any) { rows.add(cat); any = true; } rows.add(b); }
            }
            rows.add("Fixed");
            for (String f : FIXED) rows.add(new String[]{f});
            int vis = visibleRows();
            scroll = Math.max(0, Math.min(scroll, rows.size() - vis));
            int cx = width / 2;
            for (int r = 0; r < vis && scroll + r < rows.size(); r++) {
                Object o = rows.get(scroll + r);
                if (!(o instanceof Keys.Binding b)) continue;
                int y = TOP + r * ROW;
                add(new Widget.Button(cx + 6, y, 100, 16, () -> waiting == b ? (pendingMod != Keys.NONE ? "§e" + Keys.name(pendingMod) + " + ???" : "§e> ??? <") : (Keys.conflict(b) ? "§c" : "") + b.keyName(), () -> waiting = b));
                add(new Widget.Button(cx + 110, y, 50, 16, () -> b.isDefault() ? "§8Reset" : "Reset", () -> { b.reset(); game.settings.save(); }));
            }
            add(new Widget.Button(cx - 154, height - 26, 100, 20, "Reset All", () -> {
                for (Keys.Binding b : Keys.ALL) b.reset();
                game.settings.save();
                game.relayout();
            }));
            add(new Widget.Button(cx - 50, height - 26, 204, 20, "Done", this::onClose));
        }

        @Override public void render(GuiRenderer g, int mx, int my, float partial) {
            if (game.session == null) g.background(0xFF404040); else g.fill(0, 0, width, height, 0xB0101010);
            title(g, "Controls", 8);
            g.centered(waiting != null ? "§ePress a key, a mouse button or a combo (Ctrl/Shift/Alt + key) for \"" + waiting.label + "\"  (Esc: cancel, Backspace: none)"
                    : "§7Click a key to change it. Red: used twice. Wheel: scroll", width / 2f, 20, 0xFFFFFF);
            int vis = visibleRows(), cx = width / 2;
            for (int r = 0; r < vis && scroll + r < rows.size(); r++) {
                Object o = rows.get(scroll + r);
                int y = TOP + r * ROW;
                if (o instanceof String cat) g.centered("§e" + cat, cx, y + 4, 0xFFFFFF);
                else if (o instanceof String[] t) g.centered("§7" + t[0], cx, y + 4, 0xFFFFFF);
                else if (o instanceof Keys.Binding b) g.text(b.label, cx - 4 - g.textWidth(b.label), y + 4, 0xFFFFFF, true);
            }
            if (rows.size() > vis) {
                int barH = height - TOP - 44, th = Math.max(10, barH * vis / rows.size()), ty = TOP + (barH - th) * scroll / Math.max(1, rows.size() - vis);
                g.fill(cx + 166, TOP, cx + 168, TOP + barH, 0x40FFFFFF);
                g.fill(cx + 166, ty, cx + 168, ty + th, 0xC0FFFFFF);
            }
            super.render(g, mx, my, partial);
        }

        private void assign(int code) { assign(code, Keys.NONE); }

        private void assign(int code, int mod) {
            Keys.Binding b = waiting;
            waiting = null;
            pendingMod = Keys.NONE;
            b.key = code;
            b.mod = code == Keys.NONE ? Keys.NONE : mod;
            game.settings.save();
            game.relayout();
        }

        @Override public void keyPressed(int key, int mods) {
            if (waiting != null) {
                if (key == GLFW_KEY_ESCAPE) { waiting = null; pendingMod = Keys.NONE; }
                else if (key == GLFW_KEY_BACKSPACE && pendingMod == Keys.NONE) assign(Keys.NONE);
                else if (Keys.isModifier(key) && pendingMod == Keys.NONE) pendingMod = key;
                else if (Keys.isModifier(key)) { /* a second modifier: keep the first one */ }
                else assign(key, pendingMod);
                return;
            }
            super.keyPressed(key, mods);
        }

        /** A modifier released before any other key: it is bound alone (Left Shift for sneak...). */
        public void keyReleased(int key) {
            if (waiting != null && pendingMod != Keys.NONE && key == pendingMod) assign(key);
        }

        @Override public void mouseClicked(int mx, int my, int button) {
            if (waiting != null) { assign(Keys.MOUSE + button, pendingMod); return; }
            super.mouseClicked(mx, my, button);
        }

        @Override public void scrolled(double amount) {
            int old = scroll;
            scroll = Math.max(0, Math.min(scroll - (int) Math.signum(amount) * 3, rows.size() - visibleRows()));
            if (scroll != old) game.relayout();
        }

        @Override public void onClose() { waiting = null; game.settings.save(); game.setScreen(parent); }
    }

    // ============================================================================ resource packs
    public static final class Packs extends Screen {
        private final Screen parent;
        private final List<Path> packs;
        private String chosen;

        public Packs(DerpCraft g, Screen parent) {
            super(g);
            this.parent = parent;
            packs = Resources.listPacks(g.packsDir);
            chosen = g.settings.resourcePack;
        }

        @Override public void init() {
            add(new Widget.Button(width / 2 - 154, height - 28, 150, 20, "Open Pack Folder", game::openPackFolder));
            add(new Widget.Button(width / 2 + 4, height - 28, 150, 20, "Done", this::onClose));
        }

        @Override public void render(GuiRenderer g, int mx, int my, float partial) {
            g.background(0xFF404040);
            g.fill(0, 32, width, height - 36, 0xA0000000);
            title(g, "Select Resource Pack", 16);
            int y = 38;
            row(g, y, "Default (DerpCraft 2x2)", "Built-in programmer art", chosen.isEmpty());
            y += 30;
            for (Path p : packs) {
                String id = p.toString();
                String n = p.getFileName().toString();
                if (n.equals("1.12.2.jar")) n = "Minecraft 1.12.2 (your installation)";
                row(g, y, n, id.endsWith(".jar") && n.startsWith("Minecraft") ? "Vanilla textures from your game" : "Minecraft 1.12.2 resource pack", id.equals(chosen));
                y += 30;
            }
            g.centered("Put 1.12.2 resource packs (.zip) into the resourcepacks folder", width / 2f, height - 46, 0x808080);
            super.render(g, mx, my, partial);
        }

        private void row(GuiRenderer g, int y, String a, String b, boolean sel) {
            int x = width / 2 - 120;
            if (sel) { g.fill(x - 2, y - 2, x + 242, y + 24, 0xFF808080); g.fill(x - 1, y - 1, x + 241, y + 23, 0xFF000000); }
            g.text(a, x + 2, y + 1, 0xFFFFFF, true);
            g.text(b, x + 2, y + 12, 0x808080, true);
        }

        @Override public void mouseClicked(int mx, int my, int button) {
            if (my >= 36 && my < height - 36 && Math.abs(mx - width / 2) < 122) {
                int i = (my - 36) / 30;
                if (i == 0) { chosen = ""; return; }
                if (i - 1 < packs.size()) { chosen = packs.get(i - 1).toString(); return; }
            }
            super.mouseClicked(mx, my, button);
        }

        @Override public void onClose() {
            if (!chosen.equals(game.settings.resourcePack)) {
                game.settings.resourcePack = chosen;
                game.settings.save();
                game.reloadAssets();
            }
            game.setScreen(parent);
        }
    }

    // ============================================================================ pause
    public static final class Pause extends Screen {
        public Pause(DerpCraft g) { super(g); }

        @Override public void init() {
            int y = height / 4 + 8;
            add(new Widget.Button(width / 2 - 100, y + 16, 200, 20, "Back to Game", () -> game.setScreen(null)));
            add(new Widget.Button(width / 2 - 100, y + 40, 200, 20, () -> game.recorder.recording() ? "Screen Recording (REC)..." : "Screen Recording...",
                    () -> game.setScreen(new Recording(game, this))));
            add(new Widget.Button(width / 2 - 100, y + 64, 98, 20, "Options...", () -> game.setScreen(new Options(game, this))));
            add(new Widget.Button(width / 2 + 2, y + 64, 98, 20, "Controls...", () -> game.setScreen(new Controls(game, this))));
            add(new Widget.Button(width / 2 - 100, y + 88, 200, 20, "3D Hub (models)...", () -> game.setScreen(new ModelHub(game, this))));
            add(new Widget.Button(width / 2 - 100, y + 112, 200, 20, "Save and Quit to Title", game::closeWorld));
        }

        @Override public void render(GuiRenderer g, int mx, int my, float partial) {
            g.gradient(0, 0, width, height, 0xC0101010, 0xD0101010);
            title(g, "Game Menu", 40);
            super.render(g, mx, my, partial);
        }
    }

    // ============================================================================ chat
    public static final class ChatScreen extends Screen {
        private final String initial;
        private Widget.TextField field;
        private static final List<String> HISTORY = new ArrayList<>();
        private int historyPos = -1;

        public ChatScreen(DerpCraft g, String initial) { super(g); this.initial = initial; pausesGame = false; }

        /** The world keeps going while typing (like vanilla), but the chat window is not filmed. */
        @Override public boolean recorded() { return false; }

        @Override public void init() {
            field = add(new Widget.TextField(2, height - 14, width - 4, 12));
            field.text = initial;
            field.focused = true;
            field.maxLength = 256;
            field.onEnter = () -> {
                String t = field.text;
                if (!t.isBlank()) { HISTORY.add(t); }
                game.setScreen(null);
                if (game.session != null) game.session.submitChat(t);
            };
        }

        @Override public void keyPressed(int key, int mods) {
            if (key == GLFW_KEY_UP && !HISTORY.isEmpty()) {
                historyPos = historyPos < 0 ? HISTORY.size() - 1 : Math.max(0, historyPos - 1);
                field.text = HISTORY.get(historyPos);
                return;
            }
            if (key == GLFW_KEY_DOWN && historyPos >= 0) {
                historyPos++;
                if (historyPos >= HISTORY.size()) { historyPos = -1; field.text = ""; } else field.text = HISTORY.get(historyPos);
                return;
            }
            if (key == GLFW_KEY_TAB) { complete(); return; }
            super.keyPressed(key, mods);
        }

        private void complete() {
            String t = field.text;
            if (!t.startsWith("/") || t.contains(" ")) return;
            String p = t.replaceFirst("^/+", "").toLowerCase();
            String slashes = t.substring(0, t.length() - t.replaceFirst("^/+", "").length());
            List<String> m = new ArrayList<>();
            for (String k : derpcraft.api.ModContext.COMMANDS.keySet()) if (k.startsWith(p)) m.add(k);
            if (m.size() == 1) field.text = slashes + m.get(0) + " ";
            else if (!m.isEmpty() && game.session != null) game.session.chat("§7" + String.join(", ", m));
        }

        @Override public void render(GuiRenderer g, int mx, int my, float partial) {
            g.fill(0, height - 16, width, height, 0x80000000);
            super.render(g, mx, my, partial);
        }
    }
}
