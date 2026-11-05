package derpcraft.gui;

import derpcraft.DerpCraft;
import derpcraft.game.Keys;
import derpcraft.game.Session;
import derpcraft.npc.Actor;
import derpcraft.npc.NpcManager;
import derpcraft.npc.Poses;
import derpcraft.npc.Track;
import derpcraft.render.GuiRenderer;

import java.util.List;
import java.util.Locale;

import static org.lwjgl.glfw.GLFW.*;

/**
 * Scene timeline of the current group: one row per actor with its take as a bar (drag it to start later, drag
 * the ends to trim), pose keyframes (diamonds) and a camera row with cuts between saved shots, following an NPC,
 * its eyes, the camera path or the player view. Click the ruler to move the playhead: the actors stand where they
 * are at that moment. Play from there, or record an actor from there (keeps the take before that point).
 */
public final class Timeline extends Screen {
    private final Session s;
    private final Screen parent;
    private static float zoom = 3;            // pixels per tick
    private static int scrollT, rowScroll;
    private static int playhead;
    /** Selected actor id (-2 = camera row, -1 = none), pose key index, camera cut index. */
    private static int selActor = -1, selKey = -1, selCam = -1;
    private static final int LW = 74, ROW = 12;
    private int top, barX0, barX1, rowsY, rowsVisible, camY;
    private int drag, dragX, origA, origB;
    private boolean wasPlaying;

    public Timeline(DerpCraft g, Session s, Screen parent) {
        super(g);
        this.s = s; this.parent = parent;
    }

    /** The world stands still while the timeline is open, except while its scene plays. */
    @Override public boolean pausesGame() { return !playing(); }

    private NpcManager n() { return s.npcs; }
    private String group() { return n().currentScene; }
    private List<Actor> rows() { return n().members(group()); }
    private Actor selected() { return selActor >= 0 ? n().byId(selActor) : null; }

    private Track.Take selTake() {
        Actor a = selected();
        Track t = a == null ? null : a.track(group());
        return t == null ? null : t.take();
    }

    private int length() {
        int len = n().sceneLength(group());
        for (NpcManager.CamKey k : n().camKeys(group())) len = Math.max(len, k.tick + 20);
        return Math.max(len, 20 * 10);
    }

    private int x(double tick) { return barX0 + (int) Math.round((tick - scrollT) * zoom); }
    private int tick(int x) { return (int) Math.round((x - barX0) / zoom + scrollT); }

    @Override public void init() {
        int ph = Math.min(height - 24, 176);
        top = height - ph;
        barX0 = LW + 6;
        barX1 = width - 6;
        rowsY = top + 32;
        int bottomRows = 40;
        rowsVisible = Math.max(1, (height - bottomRows - rowsY - ROW - 4) / ROW);
        camY = rowsY + rowsVisible * ROW + 2;
        int y = top + 3, x = 4;
        add(new Widget.Button(x, y, 20, 14, "|<", () -> setPlayhead(0))); x += 22;
        add(new Widget.Button(x, y, 40, 14, () -> playing() ? "Stop" : "Play", this::togglePlay)); x += 42;
        Widget.Button rec = add(new Widget.Button(x, y, 66, 14, "§cRec here", this::recordHere));
        rec.active = selected() != null;
        x += 68;
        add(new Widget.Button(x, y, 16, 14, "-", () -> { zoom = Math.max(0.2f, zoom / 1.4f); }));
        add(new Widget.Button(x + 18, y, 16, 14, "+", () -> { zoom = Math.min(16, zoom * 1.4f); }));
        x += 38;
        add(new Widget.Button(width - 44, y, 40, 14, "Back", this::onClose));
        bottomTools();
        if (!playing()) n().preview(group(), playhead);
    }

    // ------------------------------------------------------------------------------------------ tools at the bottom
    private void bottomTools() {
        int y1 = height - 36, y2 = height - 18, x = 4, w = width - 8;
        NpcManager n = n();
        Actor a = selected();
        Track t = a == null ? null : a.track(group());
        if (a != null && t != null && selKey >= 0 && selKey < t.keys.size()) {
            Track.PoseKey k = t.keys.get(selKey);
            add(new Widget.Button(x, y1, 16, 16, "<", () -> cycleKeyPose(k, -1)));
            add(new Widget.Button(x + 18, y1, 150, 16, () -> "Pose: " + (k.pose.isEmpty() ? "none (normal)" : k.pose), () -> cycleKeyPose(k, 1)));
            add(new Widget.Button(x + 170, y1, 16, 16, ">", () -> cycleKeyPose(k, 1)));
            add(new Widget.Button(x + 190, y1, 110, 16, () -> "Curve: " + Track.CURVES[k.curve], () -> { k.curve = (k.curve + 1) % Track.CURVES.length; n.save(); }));
            Widget.Slider bl = add(new Widget.Slider(x, y2, 186, 16, "Blend", 0, 100, 1, () -> k.blend, v -> { k.blend = (int) Math.round(v); n.save(); },
                    v -> String.format(Locale.ROOT, "%.2f s", v / 20f)));
            bl.defaultValue = 10;
            add(new Widget.Button(x + 190, y2, 70, 16, "§cDelete key", () -> { t.keys.remove(k); selKey = -1; n.save(); game.relayout(); }));
            add(new Widget.Button(x + 262, y2, 70, 16, "Pose editor", () -> game.setScreen(new PoseEditor(game, s, this, null))));
        } else if (selActor == -2 && selCam >= 0 && selCam < n.camKeys(group()).size()) {
            NpcManager.CamKey k = n.camKeys(group()).get(selCam);
            add(new Widget.Button(x, y1, 130, 16, () -> "Cut: " + NpcManager.CamKey.TYPES[k.type], () -> { k.type = (k.type + 1) % NpcManager.CamKey.TYPES.length; k.ref = defaultRef(k.type); n.save(); }));
            add(new Widget.Button(x + 132, y1, 16, 16, "<", () -> cycleRef(k, -1)));
            add(new Widget.Button(x + 150, y1, 150, 16, () -> refLabel(k), () -> cycleRef(k, 1)));
            add(new Widget.Button(x + 302, y1, 16, 16, ">", () -> cycleRef(k, 1)));
            add(new Widget.Button(x, y2, 90, 16, "§cDelete cut", () -> { n.camKeys(group()).remove(k); selCam = -1; n.save(); game.relayout(); }));
            add(new Widget.Button(x + 92, y2, 120, 16, "Save current view", this::saveShot));
        } else if (selActor == -2) {
            add(new Widget.Button(x, y1, 130, 16, "+ Cut at playhead", () -> {
                var l = n.camKeys(group());
                l.add(new NpcManager.CamKey(playhead, n.shots.isEmpty() ? 0 : 1, n.shots.isEmpty() ? 0 : n.shots.size() - 1));
                l.sort((p, q) -> Integer.compare(p.tick, q.tick));
                for (int i = 0; i < l.size(); i++) if (l.get(i).tick == playhead) selCam = i;
                n.save();
                game.relayout();
            }));
            add(new Widget.Button(x + 132, y1, 120, 16, "Save current view", this::saveShot));
            add(new Widget.Button(x + 254, y1, 130, 16, () -> "Camera link: " + (n.linkCamera ? "ON" : "OFF"), () -> { n.linkCamera = !n.linkCamera; n.save(); }));
        } else if (a != null) {
            int nt = t == null ? 0 : t.takes.size();
            add(new Widget.Button(x, y1, 16, 16, "<", () -> cycleTake(-1))).active = nt > 1;
            add(new Widget.Button(x + 18, y1, 70, 16, () -> { Track tt = a.track(group()); return tt == null || tt.takes.isEmpty() ? "No take" : "Take " + (tt.active + 1) + "/" + tt.takes.size(); }, () -> cycleTake(1)));
            add(new Widget.Button(x + 90, y1, 16, 16, ">", () -> cycleTake(1))).active = nt > 1;
            Widget.Button del = add(new Widget.Button(x + 110, y1, 70, 16, "§cDelete take", () -> { n.clearTake(a, group()); game.relayout(); }));
            del.active = nt > 0;
            Widget.Slider sp = add(new Widget.Slider(x + 184, y1, 150, 16, "Speed", 0, 1, 0.005f, () -> {
                Track.Take tk = selTake();
                return tk == null ? 0.5 : Math.log(tk.speed / 0.25) / Math.log(16);
            }, v -> { Track.Take tk = selTake(); if (tk != null) { tk.speed = (float) (0.25 * Math.pow(16, v)); if (Math.abs(tk.speed - 1) < 0.04f) tk.speed = 1; n.save(); } },
                    v -> String.format(Locale.ROOT, "%.2fx", 0.25 * Math.pow(16, v))));
            sp.defaultValue = 0.5;
            sp.active = nt > 0;
            add(new Widget.Button(x + 338, y1, 60, 16, "Offset 0", () -> { Track.Take tk = selTake(); if (tk != null) { tk.offset = 0; n.save(); } }));
            int bx = x;
            Widget.Button mv = add(new Widget.Button(bx, y2, 70, 16, "Move to me", () -> {
                Track.Take tk = selTake();
                if (tk == null) return;
                var f = tk.frame(tk.offset);
                tk.dx += s.player.x - f.x; tk.dy += s.player.y - f.y; tk.dz += s.player.z - f.z;
                n.save(); n.preview(group(), playhead);
            }));
            mv.active = nt > 0;
            add(new Widget.Button(bx + 72, y2, 40, 16, "-15°", () -> turn(-15))).active = nt > 0;
            add(new Widget.Button(bx + 114, y2, 40, 16, "+15°", () -> turn(15))).active = nt > 0;
            add(new Widget.Button(bx + 156, y2, 50, 16, "Reset", () -> {
                Track.Take tk = selTake();
                if (tk == null) return;
                tk.dx = tk.dy = tk.dz = 0; tk.rot = 0; tk.trimStart = tk.trimEnd = 0; tk.speed = 1;
                n.save(); n.preview(group(), playhead);
            })).active = nt > 0;
            add(new Widget.Button(bx + 208, y2, 80, 16, "+ Pose key", () -> {
                Track tt = a.track(group());
                if (tt == null) { flash("Record a take first"); return; }
                tt.keys.add(new Track.PoseKey(playhead, Poses.SLOTS[1] == null ? "Arms crossed" : Poses.SLOTS[1], 1, 10));
                tt.sortKeys();
                for (int i = 0; i < tt.keys.size(); i++) if (tt.keys.get(i).tick == playhead) selKey = i;
                n.save();
                game.relayout();
            }));
            add(new Widget.Button(bx + 290, y2, 108, 16, () -> "Plays in scene: " + (a.sceneEnabled ? "ON" : "OFF"), () -> { a.sceneEnabled = !a.sceneEnabled; n.save(); }));
        }
    }

    private int defaultRef(int type) {
        return switch (type) {
            case 1 -> n().shots.isEmpty() ? 0 : 0;
            case 2, 3 -> rows().isEmpty() ? -1 : rows().get(0).id;
            default -> 0;
        };
    }

    private String refLabel(NpcManager.CamKey k) {
        return switch (k.type) {
            case 1 -> k.ref >= 0 && k.ref < n().shots.size() ? n().shots.get(k.ref).name : "no shots: save a view";
            case 2, 3 -> { Actor a = n().byId(k.ref); yield a == null ? "no NPC" : a.name; }
            case 4 -> "the path from F9 / K";
            default -> "you";
        };
    }

    private void cycleRef(NpcManager.CamKey k, int dir) {
        switch (k.type) {
            case 1 -> { if (!n().shots.isEmpty()) k.ref = Math.floorMod(k.ref + dir, n().shots.size()); }
            case 2, 3 -> {
                List<Actor> r = rows();
                if (r.isEmpty()) return;
                int i = 0;
                for (int q = 0; q < r.size(); q++) if (r.get(q).id == k.ref) i = q;
                k.ref = r.get(Math.floorMod(i + dir, r.size())).id;
            }
            default -> {}
        }
        n().save();
    }

    private void saveShot() {
        var c = s.cam;
        n().shots.add(new NpcManager.Shot("Shot " + (n().shots.size() + 1), s.lastCamX(), s.lastCamY(), s.lastCamZ(), s.lastCamYaw(), s.lastCamPitch(), c.focal));
        n().save();
        flash("Saved \"Shot " + n().shots.size() + "\" (your current view; use V for a free camera)");
    }

    private void cycleKeyPose(Track.PoseKey k, int dir) {
        List<String> names = new java.util.ArrayList<>();
        names.add("");
        names.addAll(Poses.names());
        int i = 0;
        for (int q = 0; q < names.size(); q++) if (names.get(q).equalsIgnoreCase(k.pose)) i = q;
        k.pose = names.get(Math.floorMod(i + dir, names.size()));
        n().save();
        if (!playing()) n().preview(group(), playhead);
    }

    private void cycleTake(int dir) {
        Actor a = selected();
        Track t = a == null ? null : a.track(group());
        if (t == null || t.takes.size() < 2) return;
        t.active = Math.floorMod(t.active + dir, t.takes.size());
        n().save();
        if (!playing()) n().preview(group(), playhead);
    }

    private void turn(float deg) {
        Track.Take tk = selTake();
        if (tk == null) return;
        tk.rot += deg;
        n().save();
        n().preview(group(), playhead);
    }

    private String status = "";
    private int statusTicks;
    private void flash(String m) { status = m; statusTicks = 80; }

    // ------------------------------------------------------------------------------------------ actions
    private boolean playing() { return n().scenePlaying(group()); }

    private void togglePlay() {
        NpcManager n = n();
        if (playing()) { n.stopScene(group()); n.preview(group(), playhead); return; }
        boolean walk = n.walkToMarks;
        n.walkToMarks = false;                     // from the timeline: start right away
        if (!n.playScene(group(), null, playhead)) flash("Nothing to play from here");
        n.walkToMarks = walk;
    }

    private void recordHere() {
        Actor a = selected();
        if (a == null) return;
        n().startRecording(a, playhead);
        game.setScreen(null);
        s.chat("§eRecording " + a.name + " from " + time(playhead) + (a.take(group()) != null ? " (the take before this point is kept)" : "") + ". " + Keys.n(Keys.RECORD) + " = stop, " + Keys.n(Keys.RECORD_CANCEL) + " = cancel");
    }

    private void setPlayhead(int t) {
        playhead = Math.max(0, t);
        if (!playing()) n().preview(group(), playhead);
    }

    private static String time(int ticks) { return String.format(Locale.ROOT, "%d:%02d.%d", ticks / 1200, (ticks / 20) % 60, (ticks % 20) / 2); }

    // ------------------------------------------------------------------------------------------ mouse
    @Override public void mouseClicked(int mx, int my, int button) {
        drag = 0;
        for (Widget w : widgets) if (w.visible && w.contains(mx, my)) { super.mouseClicked(mx, my, button); return; }
        if (my < top) { super.mouseClicked(mx, my, button); return; }
        dragX = mx;
        if (my >= top + 20 && my < rowsY) {                     // ruler
            if (mx >= barX0) { setPlayhead(tick(mx)); drag = 1; }
            return;
        }
        List<Actor> rows = rows();
        if (my >= rowsY && my < rowsY + rowsVisible * ROW) {
            int idx = rowScroll + (my - rowsY) / ROW;
            if (idx >= rows.size()) { if (mx >= barX0) { setPlayhead(tick(mx)); drag = 1; } return; }
            Actor a = rows.get(idx);
            int oldSel = selActor, oldKey = selKey;
            selActor = a.id; selCam = -1; selKey = -1;
            if (mx < barX0) { game.relayout(); return; }
            Track t = a.track(group());
            Track.Take tk = t == null ? null : t.take();
            // pose keys first
            if (t != null) for (int i = 0; i < t.keys.size(); i++) {
                if (Math.abs(mx - x(t.keys.get(i).tick)) <= 3) {
                    selKey = i; drag = 5; origA = t.keys.get(i).tick;
                    game.relayout();
                    return;
                }
            }
            if (tk != null) {
                int xs = x(tk.offset), xe = x(tk.end());
                if (Math.abs(mx - xs) <= 3) { drag = 3; origA = tk.trimStart; origB = tk.offset; }
                else if (Math.abs(mx - xe) <= 3) { drag = 4; origA = tk.trimEnd; }
                else if (mx > xs && mx < xe) { drag = 2; origA = tk.offset; }
            }
            if (drag == 0) { setPlayhead(tick(mx)); drag = 1; }
            if (oldSel != selActor || oldKey != selKey) game.relayout();
            return;
        }
        if (my >= camY && my < camY + ROW) {
            selActor = -2; selKey = -1; selCam = -1;
            var l = n().camKeys(group());
            for (int i = 0; i < l.size(); i++) if (mx >= barX0 && Math.abs(mx - x(l.get(i).tick)) <= 4) { selCam = i; drag = 6; origA = l.get(i).tick; }
            if (selCam < 0 && mx >= barX0) { setPlayhead(tick(mx)); drag = 1; }
            game.relayout();
        }
    }

    @Override public void mouseDragged(int mx, int my, int button) {
        if (drag == 0) { super.mouseDragged(mx, my, button); return; }
        int dt = (int) Math.round((mx - dragX) / zoom);
        Track.Take tk = selTake();
        Actor a = selected();
        switch (drag) {
            case 1 -> setPlayhead(tick(mx));
            case 2 -> { if (tk != null) { tk.offset = Math.max(0, origA + dt); n().preview(group(), playhead); } }
            case 3 -> {
                if (tk == null) break;
                int max = tk.rec.length() - tk.trimEnd - 2;
                int ts = Math.max(0, Math.min(max, origA + Math.round(dt * tk.speed)));
                tk.trimStart = ts;
                tk.offset = Math.max(0, origB + Math.round((ts - origA) / tk.speed));
            }
            case 4 -> {
                if (tk == null) break;
                int max = tk.rec.length() - tk.trimStart - 2;
                tk.trimEnd = Math.max(0, Math.min(max, origA - Math.round(dt * tk.speed)));
            }
            case 5 -> {
                Track t = a == null ? null : a.track(group());
                if (t != null && selKey >= 0 && selKey < t.keys.size()) t.keys.get(selKey).tick = Math.max(0, origA + dt);
            }
            case 6 -> {
                var l = n().camKeys(group());
                if (selCam >= 0 && selCam < l.size()) l.get(selCam).tick = Math.max(0, origA + dt);
            }
            default -> {}
        }
    }

    @Override public void mouseReleased(int mx, int my, int button) {
        if (drag >= 2) {
            Actor a = selected();
            Track t = a == null ? null : a.track(group());
            if (drag == 5 && t != null && selKey >= 0 && selKey < t.keys.size()) {
                Track.PoseKey k = t.keys.get(selKey);
                t.sortKeys();
                selKey = t.keys.indexOf(k);
            }
            if (drag == 6) {
                var l = n().camKeys(group());
                NpcManager.CamKey k = selCam >= 0 && selCam < l.size() ? l.get(selCam) : null;
                l.sort((p, q) -> Integer.compare(p.tick, q.tick));
                selCam = k == null ? -1 : l.indexOf(k);
            }
            n().save();
            if (!playing()) n().preview(group(), playhead);
        }
        drag = 0;
        super.mouseReleased(mx, my, button);
    }

    @Override public void scrolled(double amount) {
        int mx = game.mouseGuiX(), my = game.mouseGuiY();
        if (my >= rowsY && my < rowsY + rowsVisible * ROW && mx < barX0) {
            rowScroll = Math.max(0, Math.min(Math.max(0, rows().size() - rowsVisible), rowScroll - (int) Math.signum(amount)));
            return;
        }
        if (glfwGetKey(game.window, GLFW_KEY_LEFT_SHIFT) == GLFW_PRESS) { scrollT = Math.max(0, scrollT - (int) (amount * 40 / zoom)); return; }
        int at = tick(Math.max(barX0, mx));
        zoom = (float) Math.max(0.2, Math.min(16, zoom * Math.pow(1.2, amount)));
        scrollT = Math.max(0, at - (int) ((Math.max(barX0, mx) - barX0) / zoom));
    }

    @Override public void keyPressed(int key, int mods) {
        switch (key) {
            case GLFW_KEY_SPACE -> togglePlay();
            case GLFW_KEY_LEFT -> setPlayhead(playhead - ((mods & GLFW_MOD_SHIFT) != 0 ? 20 : 1));
            case GLFW_KEY_RIGHT -> setPlayhead(playhead + ((mods & GLFW_MOD_SHIFT) != 0 ? 20 : 1));
            case GLFW_KEY_HOME -> setPlayhead(0);
            case GLFW_KEY_DELETE -> {
                Actor a = selected();
                Track t = a == null ? null : a.track(group());
                if (t != null && selKey >= 0 && selKey < t.keys.size()) { t.keys.remove(selKey); selKey = -1; n().save(); game.relayout(); }
                else if (selActor == -2 && selCam >= 0 && selCam < n().camKeys(group()).size()) { n().camKeys(group()).remove(selCam); selCam = -1; n().save(); game.relayout(); }
            }
            default -> {
                if (Keys.NPC_HUB.matches(key)) onClose();
                else if (Keys.RECORD.matches(key)) recordHere();
                else if (Keys.SCENE_PLAY.matches(key)) togglePlay();
                else super.keyPressed(key, mods);
            }
        }
    }

    @Override public void tick() {
        super.tick();
        if (statusTicks > 0) statusTicks--;
        NpcManager.SceneRun r = n().run(group());
        boolean p = r != null;
        if (p && !r.gathering) {
            playhead = r.tick;
            int vis = (int) ((barX1 - barX0) / zoom);
            if (playhead > scrollT + vis - 10 || playhead < scrollT) scrollT = Math.max(0, playhead - vis / 5);
        }
        if (p != wasPlaying) { wasPlaying = p; game.relayout(); }
    }

    // ------------------------------------------------------------------------------------------ drawing
    private static final int[] COLORS = {0xFF4A90D9, 0xFFD9824A, 0xFF5CB85C, 0xFFB85CB8, 0xFFD9C44A, 0xFF4AC4D9, 0xFFD94A6A, 0xFF8A8AD9};

    @Override public void render(GuiRenderer g, int mx, int my, float partial) {
        NpcManager n = n();
        g.fill(0, top, width, height, 0xE0141418);
        g.fill(0, top, width, top + 1, 0xFF505060);
        String title = "Timeline: " + group() + "   " + time(playhead) + " / " + time(n.sceneLength(group()));
        g.text(title, 4 + 22 + 42 + 68 + 38 + 6, top + 6, 0xFFFFFF, false);
        // ruler
        int ry = top + 20;
        g.fill(barX0, ry, barX1, ry + 10, 0xFF22222A);
        float pps = zoom * 20;
        int step = pps >= 60 ? 1 : pps >= 25 ? 2 : pps >= 10 ? 5 : pps >= 4 ? 10 : 30;
        int firstSec = scrollT / 20;
        for (int sec = firstSec; ; sec++) {
            int xx = x(sec * 20);
            if (xx > barX1) break;
            if (xx < barX0) continue;
            boolean label = sec % step == 0;
            g.fill(xx, ry + (label ? 0 : 6), xx + 1, ry + 10, 0xFF707080);
            if (label) g.text(sec / 60 + ":" + String.format(Locale.ROOT, "%02d", sec % 60), xx + 2, ry + 1, 0xA0A0B0, false);
        }
        int end = n.sceneLength(group());
        if (x(end) >= barX0 && x(end) <= barX1) g.fill(x(end), ry, x(end) + 1, camY + ROW, 0x80FFFFFF);
        // tracks
        List<Actor> rows = rows();
        rowScroll = Math.max(0, Math.min(rowScroll, Math.max(0, rows.size() - rowsVisible)));
        for (int i = 0; i < rowsVisible; i++) {
            int idx = rowScroll + i;
            int y = rowsY + i * ROW;
            g.fill(0, y, width, y + ROW - 1, i % 2 == 0 ? 0x30FFFFFF : 0x18FFFFFF);
            if (idx >= rows.size()) continue;
            Actor a = rows.get(idx);
            boolean sel = a.id == selActor;
            String nm = a.name.length() > 11 ? a.name.substring(0, 10) + "." : a.name;
            g.text((sel ? "§e" : a.enabled && a.sceneEnabled ? "§f" : "§8") + nm, 4, y + 2, 0xFFFFFF, false);
            Track t = a.track(group());
            Track.Take tk = t == null ? null : t.take();
            if (tk != null) {
                int xs = Math.max(barX0, x(tk.offset)), xe = Math.min(barX1, x(tk.end()));
                int col = COLORS[a.id % COLORS.length];
                if (!a.sceneEnabled || !a.enabled) col = 0xFF505050;
                if (xe > xs) {
                    g.fill(xs, y + 1, xe, y + ROW - 2, (sel ? 0xFF000000 : 0xC0000000) | (col & 0xFFFFFF));
                    if (sel) { g.fill(xs, y + 1, xs + 2, y + ROW - 2, 0xFFFFFFFF); g.fill(xe - 2, y + 1, xe, y + ROW - 2, 0xFFFFFFFF); }
                    String lab = (t.takes.size() > 1 ? "T" + (t.active + 1) + " " : "") + (tk.speed != 1 ? String.format(Locale.ROOT, "%.2fx ", tk.speed) : "")
                            + (tk.rot != 0 ? Math.round(tk.rot) + "° " : "") + (tk.dx != 0 || tk.dz != 0 || tk.dy != 0 ? "moved" : "");
                    if (xe - xs > g.textWidth(lab) + 4) g.text(lab, xs + 3, y + 2, 0xE0FFFFFF, false);
                }
            }
            if (t != null) for (int k = 0; k < t.keys.size(); k++) {
                Track.PoseKey pk = t.keys.get(k);
                int kx = x(pk.tick);
                if (kx < barX0 || kx > barX1) continue;
                boolean ks = sel && k == selKey;
                int bl = (int) Math.round(pk.blend * zoom);
                if (bl > 1) g.fill(kx, y + ROW / 2 - 1, Math.min(barX1, kx + bl), y + ROW / 2, 0x80FFE060);
                diamond(g, kx, y + ROW / 2 - 1, ks ? 0xFFFFFFFF : 0xFFFFD040);
            }
        }
        // camera row
        g.fill(0, camY, width, camY + ROW - 1, selActor == -2 ? 0x50FFD040 : 0x30FFFFFF);
        g.text(selActor == -2 ? "§eCamera" : "Camera", 4, camY + 2, 0xFFFFFF, false);
        var cks = n.camKeys(group());
        for (int i = 0; i < cks.size(); i++) {
            NpcManager.CamKey k = cks.get(i);
            int kx = x(k.tick);
            if (kx < barX0 || kx > barX1) continue;
            int nx = i + 1 < cks.size() ? Math.min(barX1, x(cks.get(i + 1).tick)) : barX1;
            g.fill(kx, camY + 1, Math.max(kx + 2, nx), camY + ROW - 2, (selActor == -2 && i == selCam) ? 0xC0806020 : 0x70604020);
            g.fill(kx, camY, kx + 2, camY + ROW - 1, 0xFFFFC040);
            String lab = switch (k.type) {
                case 1 -> k.ref >= 0 && k.ref < n.shots.size() ? n.shots.get(k.ref).name : "Shot?";
                case 2 -> "Follow " + nameOf(k.ref);
                case 3 -> "Eyes " + nameOf(k.ref);
                case 4 -> "Path";
                default -> "Player";
            };
            if (nx - kx > g.textWidth(lab) + 6) g.text(lab, kx + 4, camY + 2, 0xFFFFE0A0, false);
        }
        // playhead
        int px = x(playhead);
        if (px >= barX0 && px <= barX1) g.fill(px, ry, px + 1, camY + ROW, 0xFFFF4040);
        // hints
        String hint;
        if (selActor >= 0 && selKey >= 0) hint = "Pose key: blends into the pose (curve, blend). Drag to move, Del deletes.";
        else if (selActor >= 0) hint = "Drag the bar = start later, drag its ends = trim. Rec here: from the playhead.";
        else if (selActor == -2) hint = "Cuts: saved shots (V = free cam), follow NPC, its eyes, path, you.";
        else hint = "Click a name, the Camera row or the ruler. Space: play, wheel: zoom.";
        if (selActor == -1) g.text("§7" + hint, 4, height - 30, 0xFFFFFF, false);
        else g.text("§7" + hint, 4, height - 46, 0xFFFFFF, false);
        super.render(g, mx, my, partial);
        if (statusTicks > 0) g.text(status, 6, top - 12, 0xFFFFFF, true);
    }

    private String nameOf(int id) { Actor a = n().byId(id); return a == null ? "?" : a.name; }

    private static void diamond(GuiRenderer g, int x, int y, int c) {
        g.fill(x - 1, y - 3, x + 1, y + 3, c);
        g.fill(x - 3, y - 1, x + 3, y + 1, c);
        g.fill(x - 2, y - 2, x + 2, y + 2, c);
    }

    @Override public void onClose() {
        if (!playing()) n().releasePreview();
        n().save();
        game.setScreen(parent);
    }
}
