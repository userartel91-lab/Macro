package derpcraft.gui;

import derpcraft.DerpCraft;
import derpcraft.game.CameraRig;
import derpcraft.game.Keys;
import derpcraft.game.Session;
import derpcraft.render.GuiRenderer;

import java.util.Locale;
import java.util.function.DoubleConsumer;
import java.util.function.DoubleSupplier;
import java.util.function.Function;

import static org.lwjgl.glfw.GLFW.*;

/**
 * Camera panel (F9): lens, focus, lens effects and movement / path settings in a side panel, with the world still
 * running and rendering next to it so every change is visible immediately.
 */
public final class CameraPanel extends Screen {
    private final Session s;
    private static int page = 0;
    private static final String[] PAGES = {"Lens", "Focus", "FX", "Move", "NPC"};
    private static final int PW = 172;

    public CameraPanel(DerpCraft g, Session s) { super(g); this.s = s; }

    private CameraRig c() { return s.cam; }

    /** Logarithmic slider value mapping for ranges that span orders of magnitude. */
    private static double toLog(double v, double min, double max) { return Math.log(v / min) / Math.log(max / min); }
    private static double fromLog(double t, double min, double max) { return min * Math.pow(max / min, t); }

    @Override public void init() {
        int x = 4, w = PW - 8, y = 16;
        int tw = (w - 8) / 5;
        for (int i = 0; i < 5; i++) {
            final int p = i;
            add(new Widget.Button(x + i * (tw + 2), y, i == 4 ? w - 4 * (tw + 2) : tw, 16, () -> (page == p ? "§e" : "") + PAGES[p], () -> { page = p; game.relayout(); }));
        }
        y += 20;
        CameraRig c = c();
        switch (page) {
            case 0 -> {
                add(new Widget.Button(x, y, w, 16, () -> c.focal > 0 ? "Focal Length: Custom (0 = FOV)" : "Focal Length: from FOV setting", () -> c.focalTarget = 0)); y += 18;
                slider(x, y, w, "Focal", 0, 1, 0.002f, () -> toLog(Math.max(8, c.focal > 0 ? c.focal : fovFocal()), 8, 300),
                        v -> { c.focalTarget = (float) fromLog(v, 8, 300); c.focal = c.focalTarget; },
                        v -> Math.round(fromLog(v, 8, 300)) + " mm" + lensName((float) fromLog(v, 8, 300)), toLog(35, 8, 300)); y += 18;
                slider(x, y, w, "Horizon (Roll)", -45, 45, 0.5f, () -> c.rollTarget, v -> c.rollTarget = (float) v, v -> String.format(Locale.ROOT, "%+.1f°", v), 0); y += 18;
                slider(x, y, w, "Anamorphic", 1, 2, 0.01f, () -> c.squeeze, v -> c.squeeze = (float) v, v -> v <= 1.005f ? "Off (spherical)" : String.format(Locale.ROOT, "%.2fx squeeze", v), 1); y += 18;
                add(new Widget.Button(x, y, w, 16, () -> "Letterbox: " + CameraRig.LETTERBOX[c.letterbox], () -> c.letterbox = (c.letterbox + 1) % CameraRig.LETTERBOX.length)); y += 18;
                slider(x, y, w, "Distortion", -1, 1, 0.01f, () -> c.distortion, v -> c.distortion = (float) v, v -> v == 0 ? "None" : String.format(Locale.ROOT, "%+.2f %s", v, v > 0 ? "barrel" : "pincushion"), 0); y += 18;
                slider(x, y, w, "Chromatic Aberration", 0, 1, 0.01f, () -> c.aberration, v -> c.aberration = (float) v, v -> (int) (v * 100) + "%", 0); y += 18;
                slider(x, y, w, "Handheld Shake", 0, 1, 0.01f, () -> c.shake, v -> c.shake = (float) v, v -> (int) (v * 100) + "%", 0); y += 18;
                slider(x, y, w, "Shake Speed", 0.2f, 4, 0.05f, () -> c.shakeSpeed, v -> c.shakeSpeed = (float) v, v -> String.format(Locale.ROOT, "%.2fx", v), 1);
            }
            case 1 -> {
                add(new Widget.Button(x, y, w, 16, () -> "Depth of Field: " + (c.dof ? "ON" : "OFF"), () -> c.dof = !c.dof)); y += 18;
                add(new Widget.Button(x, y, w, 16, () -> "Autofocus: " + CameraRig.AF_MODES[c.afMode], () -> c.afMode = (c.afMode + 1) % CameraRig.AF_MODES.length)); y += 18;
                slider(x, y, w, "AF Speed", 0.3f, 20, 0.1f, () -> c.afSpeed, v -> c.afSpeed = (float) v, v -> String.format(Locale.ROOT, "%.1f", v) + (v < 1.5f ? " (slow pull)" : v > 10 ? " (snap)" : ""), 4); y += 18;
                slider(x, y, w, "Manual Focus", 0, 1, 0.002f, () -> toLog(Math.max(0.3, c.manualFocus), 0.3, 500),
                        v -> { c.manualFocus = (float) fromLog(v, 0.3, 500); c.afMode = 0; },
                        v -> String.format(Locale.ROOT, "%.1f m", fromLog(v, 0.3, 500)), toLog(8, 0.3, 500)); y += 18;
                slider(x, y, w, "Aperture", 0, 1, 0.005f, () -> toLog(c.aperture, 0.95, 22), v -> c.aperture = (float) fromLog(v, 0.95, 22),
                        v -> String.format(Locale.ROOT, "f/%.1f", fromLog(v, 0.95, 22)), toLog(2.8, 0.95, 22)); y += 18;
                slider(x, y, w, "Bokeh Size", 0.25f, 4, 0.05f, () -> c.bokehScale, v -> c.bokehScale = (float) v, v -> String.format(Locale.ROOT, "%.2fx", v), 1); y += 18;
                add(new Widget.Button(x, y, w / 2 - 1, 16, "Focus Here", () -> { s.cameraAction(Keys.FOCUS, false); }));
                add(new Widget.Button(x + w / 2 + 1, y, w / 2 - 1, 16, "AF Center", () -> { c.afMode = 1; c.dof = true; }));
            }
            case 2 -> {
                slider(x, y, w, "Anamorphic Flares", 0, 1, 0.01f, () -> c.flare, v -> c.flare = (float) v, v -> (int) (v * 100) + "%", 0); y += 18;
                slider(x, y, w, "Flare Length", 0, 1, 0.01f, () -> c.flareLength, v -> c.flareLength = (float) v, v -> (int) (v * 100) + "%", 0.7); y += 18;
                add(new Widget.Button(x, y, w, 16, () -> "Flare Color: " + CameraRig.FLARE_COLORS[c.flareColor], () -> c.flareColor = (c.flareColor + 1) % CameraRig.FLARE_COLORS.length)); y += 18;
                slider(x, y, w, "Lens Ghosts", 0, 1, 0.01f, () -> c.ghosts, v -> c.ghosts = (float) v, v -> (int) (v * 100) + "%", 0); y += 18;
                slider(x, y, w, "Motion Blur", 0, 1, 0.01f, () -> c.motionBlur, v -> c.motionBlur = (float) v, v -> v == 0 ? "OFF" : Math.round(v * 360) + "° shutter", 0); y += 18;
                add(new Widget.Button(x, y, w, 16, () -> "Thirds Grid (G): " + (c.grid ? "ON" : "OFF"), () -> c.grid = !c.grid)); y += 18;
                add(new Widget.Button(x, y, w, 16, () -> "Camera Info: " + (c.showInfo ? "ON" : "OFF"), () -> c.showInfo = !c.showInfo)); y += 18;
                add(new Widget.Button(x, y, w, 16, "Color Grading...", () -> game.setScreen(new Graphics.GradingEditor(game, this))));
            }
            case 4 -> {
                var n = s.npcs;
                if (c.followId < 0 || n.byId(c.followId) == null) { var l = n.actors; c.followId = l.isEmpty() ? -1 : l.get(0).id; }
                add(new Widget.Button(x, y, 16, 16, "<", () -> cycleNpc(-1)));
                add(new Widget.Button(x + 18, y, w - 36, 16, () -> { var a = n.byId(c.followId); return "NPC: " + (a == null ? "none" : a.name); }, () -> cycleNpc(1)));
                add(new Widget.Button(x + w - 16, y, 16, 16, ">", () -> cycleNpc(1))); y += 18;
                int hw = w / 2 - 1;
                add(new Widget.Button(x, y, hw, 16, () -> c.mode == CameraRig.Mode.FOLLOW ? "§eFollowing" : "Follow", () -> {
                    if (c.followId < 0) return;
                    if (!c.detached()) { c.x = lastX(); c.y = lastY(); c.z = lastZ(); c.yaw = s.player.yaw; c.pitch = s.player.pitch; }
                    c.follow(c.followId, false);
                }));
                add(new Widget.Button(x + hw + 2, y, hw, 16, () -> c.mode == CameraRig.Mode.POV ? "§eIts eyes" : "Its eyes (POV)", () -> { if (c.followId >= 0) c.follow(c.followId, true); })); y += 18;
                slider(x, y, w, "Follow distance", 0, 30, 0.5f, () -> c.followDistance, v -> c.followDistance = (float) v, v -> v == 0 ? "free (only turns)" : String.format(Locale.ROOT, "%.1f blocks", v), 0); y += 18;
                add(new Widget.Button(x, y, w, 16, () -> c.afMode == 3 ? "§eFocus on this NPC: ON" : "Focus on this NPC", () -> { c.dof = true; c.afMode = c.afMode == 3 ? 1 : 3; })); y += 18;
                add(new Widget.Button(x, y, w, 16, "Back to player view", () -> c.toPlayer())); y += 22;
                add(new Widget.Button(x, y, w, 16, () -> "Save this shot (" + n.shots.size() + " saved)", () -> {
                    n.shots.add(new derpcraft.npc.NpcManager.Shot("Shot " + (n.shots.size() + 1), lastX(), lastY(), lastZ(), s.lastCamYaw(), s.lastCamPitch(), c.focal));
                    n.save();
                    s.chat("Saved camera shot " + n.shots.size() + " (use it as a cut on the scene timeline)");
                })); y += 18;
            }
            default -> {
                add(new Widget.Button(x, y, w, 16, () -> "Mode: " + modeName(), this::cycleMode)); y += 18;
                slider(x, y, w, "Free Cam Speed", 0, 1, 0.005f, () -> toLog(c.speed, 0.25, 80), v -> c.speed = (float) fromLog(v, 0.25, 80),
                        v -> String.format(Locale.ROOT, "%.1f blocks/s", fromLog(v, 0.25, 80)), toLog(5, 0.25, 80)); y += 18;
                slider(x, y, w, "Smoothing", 0, 0.95f, 0.01f, () -> c.smoothing, v -> c.smoothing = (float) v, v -> (int) (v * 100) + "%", 0.7); y += 18;
                slider(x, y, w, "Orbit Speed", -60, 60, 0.5f, () -> c.orbitSpeed, v -> c.orbitSpeed = (float) v, v -> String.format(Locale.ROOT, "%+.1f°/s", v), 12); y += 18;
                slider(x, y, w, "Path Duration", 1, 120, 0.5f, () -> c.pathDuration, v -> c.pathDuration = (float) v, v -> String.format(Locale.ROOT, "%.1f s", v), 10); y += 18;
                int hw = w / 2 - 1;
                add(new Widget.Button(x, y, hw, 16, () -> "Ease: " + CameraRig.EASINGS[c.easing], () -> c.easing = (c.easing + 1) % CameraRig.EASINGS.length));
                add(new Widget.Button(x + hw + 2, y, hw, 16, () -> "Loop: " + (c.pathLoop ? "ON" : "OFF"), () -> c.pathLoop = !c.pathLoop)); y += 18;
                add(new Widget.Button(x, y, hw, 16, () -> c.constantSpeed ? "Even Speed" : "Per Key", () -> c.constantSpeed = !c.constantSpeed));
                add(new Widget.Button(x + hw + 2, y, hw, 16, () -> "Show Path: " + (c.showPath ? "ON" : "OFF"), () -> c.showPath = !c.showPath)); y += 18;
                int qw = (w - 6) / 4;
                add(new Widget.Button(x, y, qw, 16, "+Key", () -> s.cameraAction(Keys.PATH_KEY, false)));
                add(new Widget.Button(x + qw + 2, y, qw, 16, "-Key", () -> s.cameraAction(Keys.PATH_DELETE, false)));
                add(new Widget.Button(x + 2 * (qw + 2), y, qw, 16, "Clear", () -> s.cameraAction(Keys.PATH_DELETE, true)));
                add(new Widget.Button(x + 3 * (qw + 2), y, w - 3 * (qw + 2), 16, () -> c.playing() ? "Stop" : "Play", () -> {
                    if (s.cameraAction(Keys.PATH_PLAY, false) && c.playing()) onClose();
                }));
            }
        }
        add(new Widget.Button(x, height - 20, w / 2 - 1, 16, "Reset Lens", this::resetLens));
        add(new Widget.Button(x + w / 2 + 1, height - 20, w / 2 - 1, 16, "Done", this::onClose));
    }

    private float fovFocal() { return (float) (12.0 / Math.tan(Math.toRadians(game.settings.fov) / 2)); }

    private static String lensName(float mm) {
        return mm < 16 ? " ultra wide" : mm < 28 ? " wide" : mm < 60 ? "" : mm < 135 ? " portrait" : " tele";
    }

    private String modeName() {
        return switch (c().mode) { case FREE -> "Free Camera"; case ORBIT -> "Orbit"; case PATH -> "Playing Path"; case FOLLOW -> "Following NPC"; case POV -> "NPC's eyes"; default -> "Player"; };
    }

    private void cycleMode() {
        CameraRig c = c();
        switch (c.mode) {
            case PLAYER -> s.cameraAction(Keys.FREE_CAM, false);
            case FREE -> s.cameraAction(Keys.ORBIT, false);
            case ORBIT -> c.toPlayer();
            case PATH -> c.stop();
            default -> c.toPlayer();
        }
    }

    private double lastX() { return s.lastCamX(); }
    private double lastY() { return s.lastCamY(); }
    private double lastZ() { return s.lastCamZ(); }

    private void cycleNpc(int dir) {
        var l = s.npcs.actors;
        if (l.isEmpty()) return;
        int i = 0;
        for (int k = 0; k < l.size(); k++) if (l.get(k).id == c().followId) i = k;
        c().followId = l.get(Math.floorMod(i + dir, l.size())).id;
        if (c().mode == CameraRig.Mode.FOLLOW || c().mode == CameraRig.Mode.POV) c().follow(c().followId, c().mode == CameraRig.Mode.POV);
    }

    private void resetLens() {
        CameraRig c = c();
        c.focalTarget = 0; c.rollTarget = 0; c.squeeze = 1; c.letterbox = 0; c.distortion = 0; c.aberration = 0; c.shake = 0;
        c.dof = false; c.flare = 0; c.ghosts = 0; c.motionBlur = 0;
    }

    private void slider(int x, int y, int w, String name, float min, float max, float step,
                        DoubleSupplier get, DoubleConsumer set, Function<Float, String> fmt, double def) {
        Widget.Slider sl = add(new Widget.Slider(x, y, w, 16, name, min, max, step, get, set, fmt));
        sl.defaultValue = def;
    }

    @Override public void render(GuiRenderer g, int mx, int my, float partial) {
        g.fill(0, 0, PW, height, 0xB0000000);
        g.text("Camera", 4, 5, 0xFFFFFF, true);
        String hint = Keys.n(Keys.CAMERA_PANEL) + ": close";
        g.text(hint, PW - g.textWidth(hint) - 4, 5, 0x808080, false);
        super.render(g, mx, my, partial);
        for (Widget w : widgets)
            if (w instanceof Widget.Slider sl && sl.contains(mx, my)) { g.text("Right click: reset", PW + 6, height - 14, 0xA0A0A0, true); break; }
        if (page == 3) {
            String[] help = {k(Keys.FREE_CAM) + "  free camera", k(Keys.ORBIT) + "  orbit (Shift: around player)", k(Keys.TRIPOD) + "  tripod: control the player",
                    k(Keys.PATH_KEY) + "  add keyframe   " + k(Keys.PATH_DELETE) + "  delete last", "Shift+" + k(Keys.PATH_DELETE) + "  clear path   " + k(Keys.PATH_PLAY) + "  play / stop",
                    k(Keys.ROLL_LEFT) + " / " + k(Keys.ROLL_RIGHT) + "  roll   " + k(Keys.LEVEL) + "  level horizon", k(Keys.ZOOM_IN) + " / " + k(Keys.ZOOM_OUT) + "  zoom   " + k(Keys.ZOOM_RESET) + "  reset",
                    k(Keys.FOCUS) + "  focus here   Shift+" + k(Keys.FOCUS) + "  autofocus", k(Keys.DOF) + "  depth of field", "(keys: Options > Controls)"};
            int yy = 40;
            for (String h : help) { g.text(h, PW + 6, yy, 0xC0C0C0, true); yy += 10; }
        }
    }

    private static String k(Keys.Binding b) { return Keys.n(b); }

    @Override public void keyPressed(int key, int mods) {
        if (Keys.CAMERA_PANEL.matches(key)) { onClose(); return; }
        super.keyPressed(key, mods);
    }

    @Override public void onClose() {
        s.saveCamera();
        game.setScreen(null);
    }
}
