package derpcraft.gui;

import derpcraft.DerpCraft;
import derpcraft.game.Session;
import derpcraft.npc.Actor;
import derpcraft.npc.Poses;
import derpcraft.render.GuiRenderer;

import java.util.List;
import java.util.Locale;

import static org.lwjgl.glfw.GLFW.*;

/**
 * Pose editor: the Minecraft / Blockbench player model with sliders for every part (head, body bending at the waist,
 * arms, legs), leaning and lifting the whole model, and the hit box. Poses are saved in game/poses/ and work on
 * NPCs, as gestures while recording (Tab + number) and as keyframes on the scene timeline.
 */
public final class PoseEditor extends Screen {
    private static final int PW = 200;
    private final Session s;
    private final Screen parent;
    private final Actor actor;
    private static int part = Poses.RIGHT_ARM;      // 0..5 parts, 6 = whole model
    private static String current;
    private Poses.Def edit;
    private Widget.TextField nameField;
    private float spin = 25;
    private boolean dirty;
    private String status = "";
    private int statusTicks;

    public PoseEditor(DerpCraft g, Session s, Screen parent, Actor actor) {
        super(g);
        this.s = s; this.parent = parent; this.actor = actor;
        String start = actor != null && !actor.customPose.isEmpty() ? actor.customPose : current;
        Poses.Def d = Poses.get(start);
        if (d == null) d = Poses.get("Arms crossed");
        load(d);
    }

    private void load(Poses.Def d) {
        current = d.name;
        edit = d.copy(d.name);
        edit.builtin = d.builtin;
        dirty = false;
    }

    @Override public void init() {
        int x = 4, w = PW - 8, y = 6, hw = w / 2 - 1;
        add(new Widget.Button(x, y, 16, 16, "<", () -> cycle(-1)));
        add(new Widget.Button(x + 18, y, w - 36, 16, () -> (dirty ? "§e* " : "") + edit.name + (edit.builtin ? " §7(built-in)" : ""), () -> cycle(1)));
        add(new Widget.Button(x + w - 16, y, 16, 16, ">", () -> cycle(1)));
        y += 19;
        nameField = add(new Widget.TextField(x + 1, y + 2, w - 72, 12));
        nameField.text = edit.builtin ? edit.name + " 2" : edit.name;
        nameField.maxLength = 24;
        add(new Widget.Button(x + w - 68, y, 33, 16, "New", () -> {
            Poses.Def d = new Poses.Def(uniqueName(nameField.text.isBlank() ? "Pose" : nameField.text.trim()));
            d.builtin = false;
            edit = d; current = d.name; dirty = true;
            game.relayout();
        }));
        add(new Widget.Button(x + w - 33, y, 33, 16, "Copy", () -> {
            Poses.Def d = edit.copy(uniqueName(nameField.text.isBlank() || nameField.text.trim().equalsIgnoreCase(edit.name) ? edit.name + " 2" : nameField.text.trim()));
            d.builtin = false;
            edit = d; current = d.name; dirty = true;
            game.relayout();
        }));
        y += 20;
        String[] labels = {"Head", "Body", "R arm", "L arm", "R leg", "L leg", "Whole", "Models"};
        int bw = (w - 6) / 4;
        for (int i = 0; i < 8; i++) {
            final int p = i;
            int col = i % 4, row = i / 4;
            add(new Widget.Button(x + col * (bw + 2), y + row * 18, col == 3 ? w - 3 * (bw + 2) : bw, 16, () -> (part == p ? "§e" : "") + labels[p], () -> { part = p; game.relayout(); }));
        }
        y += 38;
        boolean locked = edit.builtin;
        if (part == 7) { modelsPage(x, y, w); add(new Widget.Button(x, height - 20, w, 16, "Done", this::onClose)); return; }
        if (part < 6) {
            Widget.Button hold = add(new Widget.Button(x, y, w, 16, () -> edit.set[part] ? "Pose holds this part: ON" : "§7Pose holds this part: OFF (animates)", () -> { edit.set[part] = !edit.set[part]; dirty = true; }));
            hold.active = !locked;
            y += 18;
            String[] ax = {"X (forward / back)", "Y (turn)", "Z (sideways)"};
            for (int k = 0; k < 3; k++) {
                final int kk = k;
                Widget.Slider sl = add(new Widget.Slider(x, y, w, 16, ax[k], -180, 180, 1, () -> edit.rot[part][kk], v -> { edit.rot[part][kk] = (float) v; edit.set[part] = true; dirty = true; },
                        v -> Math.round(v) + "°"));
                sl.defaultValue = 0;
                sl.active = !locked;
                y += 18;
            }
        } else {
            Widget.Slider a = add(new Widget.Slider(x, y, w, 16, "Lean (lie down: -90 / 90)", -90, 90, 1, () -> edit.tiltX, v -> { edit.tiltX = (float) v; dirty = true; }, v -> Math.round(v) + "°"));
            a.defaultValue = 0; a.active = !locked; y += 18;
            Widget.Slider b = add(new Widget.Slider(x, y, w, 16, "Lean sideways", -90, 90, 1, () -> edit.tiltZ, v -> { edit.tiltZ = (float) v; dirty = true; }, v -> Math.round(v) + "°"));
            b.defaultValue = 0; b.active = !locked; y += 18;
            Widget.Slider c = add(new Widget.Slider(x, y, w, 16, "Up / down", -1.5f, 1.5f, 0.01f, () -> edit.lift, v -> { edit.lift = (float) v; dirty = true; }, v -> String.format(Locale.ROOT, "%+.2f blocks", v)));
            c.defaultValue = 0; c.active = !locked; y += 18;
            y += 18;
        }
        Widget.Slider hwS = add(new Widget.Slider(x, y, hw, 16, "Box W", 0.2f, 2.5f, 0.05f, () -> edit.width, v -> { edit.width = (float) v; dirty = true; }, v -> String.format(Locale.ROOT, "%.2f", v)));
        hwS.defaultValue = 0.6; hwS.active = !locked;
        Widget.Slider hhS = add(new Widget.Slider(x + hw + 2, y, hw, 16, "Box H", 0.2f, 2.5f, 0.05f, () -> edit.height, v -> { edit.height = (float) v; dirty = true; }, v -> String.format(Locale.ROOT, "%.2f", v)));
        hhS.defaultValue = 1.8; hhS.active = !locked;
        y += 20;
        Widget.Button save = add(new Widget.Button(x, y, hw, 16, "§aSave", this::save));
        save.active = !locked;
        Widget.Button del = add(new Widget.Button(x + hw + 2, y, hw, 16, "§cDelete", () -> {
            Poses.delete(edit.name);
            load(Poses.get("Arms crossed"));
            flash("Deleted");
            game.relayout();
        }));
        del.active = !locked && Poses.get(edit.name) != null;
        y += 18;
        add(new Widget.Button(x, y, hw, 16, () -> "Key: " + slotLabel(), this::cycleSlot));
        Widget.Button apply = add(new Widget.Button(x + hw + 2, y, hw, 16, actor == null ? "No NPC" : "Put on " + shortName(actor.name), () -> {
            if (dirty && !edit.builtin) save();
            actor.customPose = edit.name;
            actor.pose = Actor.Pose.NORMAL;
            s.npcs.save();
            flash(actor.name + " now: " + edit.name);
        }));
        apply.active = actor != null;
        add(new Widget.Button(x, height - 20, w, 16, "Done", this::onClose));
    }

    // ------------------------------------------------------------------ models on the body (3D Hub models)
    private static int attachSel;

    private derpcraft.npc.Attachment att() {
        if (actor == null || actor.attachments.isEmpty()) return null;
        attachSel = Math.max(0, Math.min(attachSel, actor.attachments.size() - 1));
        return actor.attachments.get(attachSel);
    }

    private void modelsPage(int x, int y, int w) {
        if (actor == null) return;
        int hw = w / 2 - 1, tw3 = (w - 4) / 3;
        java.util.List<derpcraft.npc.Attachment> list = actor.attachments;
        add(new Widget.Button(x, y, 16, 16, "<", () -> { if (!list.isEmpty()) attachSel = Math.floorMod(attachSel - 1, list.size()); game.relayout(); }));
        add(new Widget.Button(x + 18, y, w - 36, 16, () -> list.isEmpty() ? "§7No models on " + shortName(actor.name) : "Model " + (attachSel + 1) + " / " + list.size() + ": " + partName(att().part), () -> {}));
        add(new Widget.Button(x + w - 16, y, 16, 16, ">", () -> { if (!list.isEmpty()) attachSel = Math.floorMod(attachSel + 1, list.size()); game.relayout(); }));
        y += 18;
        Widget.Button addB = add(new Widget.Button(x, y, hw, 16, "§a+ Add model", () -> {
            var names = derpcraft.model.Models.names();
            if (names.isEmpty()) { flash("No models in game/3d (3D Hub)"); return; }
            var cur = att();
            String m = cur != null ? cur.model : names.get(0);
            int p = cur != null ? cur.part : derpcraft.npc.Poses.HEAD;
            list.add(new derpcraft.npc.Attachment(m, p));
            attachSel = list.size() - 1;
            s.npcs.save();
            game.relayout();
        }));
        addB.active = !derpcraft.model.Models.names().isEmpty();
        Widget.Button del = add(new Widget.Button(x + hw + 2, y, hw, 16, "§cRemove", () -> { if (att() != null) { list.remove(attachSel); s.npcs.save(); game.relayout(); } }));
        del.active = att() != null;
        y += 18;
        var a = att();
        if (a == null) { add(new Widget.Button(x, y, w, 16, "3D Hub (models)...", () -> game.setScreen(new ModelHub(game, this)))); return; }
        add(new Widget.Button(x, y, 16, 16, "<", () -> cycleModel(a, -1)));
        add(new Widget.Button(x + 18, y, w - 36, 16, () -> { String n = a.model; return n.length() > 26 ? "..." + n.substring(n.length() - 23) : n; }, () -> game.setScreen(new ModelHub(game, this))));
        add(new Widget.Button(x + w - 16, y, 16, 16, ">", () -> cycleModel(a, 1)));
        y += 18;
        add(new Widget.Button(x, y, w, 16, () -> "On: " + partName(a.part) + " (click: next part)", () -> {
            a.part = (a.part + 1) % 6;
            float[] d = derpcraft.npc.Attachment.defaultOffset(a.part);
            a.ox = d[0]; a.oy = d[1]; a.oz = d[2];
            s.npcs.save();
        }));
        y += 18;
        String[] ax = {"X", "Y", "Z"};
        for (int k = 0; k < 3; k++) {
            final int kk = k;
            Widget.Slider sl = add(new Widget.Slider(x + k * (tw3 + 2), y, k == 2 ? w - 2 * (tw3 + 2) : tw3, 16, ax[k], -16, 16, 0.25f,
                    () -> kk == 0 ? a.ox : kk == 1 ? a.oy : a.oz, v -> { float f = (float) v; if (kk == 0) a.ox = f; else if (kk == 1) a.oy = f; else a.oz = f; },
                    v -> Math.abs(v - Math.round(v)) < 1e-3 ? String.valueOf(Math.round(v)) : String.format(Locale.ROOT, "%.2f", v)));
            sl.defaultValue = derpcraft.npc.Attachment.defaultOffset(a.part)[k];
        }
        y += 18;
        for (int k = 0; k < 3; k++) {
            final int kk = k;
            Widget.Slider sl = add(new Widget.Slider(x + k * (tw3 + 2), y, k == 2 ? w - 2 * (tw3 + 2) : tw3, 16, "r" + ax[k], -180, 180, 5,
                    () -> kk == 0 ? a.rx : kk == 1 ? a.ry : a.rz, v -> { float f = (float) v; if (kk == 0) a.rx = f; else if (kk == 1) a.ry = f; else a.rz = f; },
                    v -> Math.round(v) + "°"));
            sl.defaultValue = 0;
        }
        y += 18;
        Widget.Slider size = add(new Widget.Slider(x, y, w, 16, "Size", -4, 3, 0.05f, () -> Math.log(a.scale) / Math.log(2), v -> a.scale = (float) Math.pow(2, v),
                v -> String.format(Locale.ROOT, "x%.3g", Math.pow(2, v))));
        size.defaultValue = 0;
    }

    private void cycleModel(derpcraft.npc.Attachment a, int dir) {
        var names = derpcraft.model.Models.names();
        if (names.isEmpty()) return;
        int i = names.indexOf(a.model);
        a.model = names.get(Math.floorMod(i + dir, names.size()));
        s.npcs.save();
    }

    private static String partName(int p) { return new String[]{"Head", "Body", "Right arm", "Left arm", "Right leg", "Left leg"}[Math.max(0, Math.min(5, p))]; }

    private static String shortName(String n) { return n.length() > 9 ? n.substring(0, 8) + "." : n; }

    private String uniqueName(String base) {
        String n = base;
        for (int i = 2; Poses.get(n) != null; i++) n = base + " " + i;
        return n;
    }

    private void save() {
        if (edit.builtin) { flash("Built-in poses can't change: Copy it first"); return; }
        Poses.put(edit);
        current = edit.name;
        dirty = false;
        flash("Saved \"" + edit.name + "\" (game/poses)");
    }

    private void cycle(int dir) {
        List<String> names = Poses.names();
        if (names.isEmpty()) return;
        int i = Math.max(0, names.indexOf(Poses.get(edit.name) == null ? names.get(0) : Poses.get(edit.name).name));
        load(Poses.get(names.get(Math.floorMod(i + dir, names.size()))));
        game.relayout();
    }

    private String slotLabel() {
        for (int i = 1; i < 10; i++) if (edit.name.equalsIgnoreCase(Poses.SLOTS[i])) return derpcraft.game.Keys.n(derpcraft.game.Keys.GESTURE) + "+" + i;
        return "none";
    }

    /** Gesture key while recording: none -> Tab+1 .. Tab+9 -> none. */
    private void cycleSlot() {
        int cur = 0;
        for (int i = 1; i < 10; i++) if (edit.name.equalsIgnoreCase(Poses.SLOTS[i])) cur = i;
        if (cur > 0) Poses.SLOTS[cur] = null;
        int next = (cur + 1) % 10;
        if (next > 0) Poses.SLOTS[next] = edit.name;
        Poses.saveSlots();
    }

    private void flash(String m) { status = m; statusTicks = 80; }

    @Override public void tick() { super.tick(); if (statusTicks > 0) statusTicks--; }

    @Override public void mouseDragged(int mx, int my, int button) {
        if (mx > PW) { spin += (mx - lastX) * 1.5f; lastX = mx; return; }
        super.mouseDragged(mx, my, button);
    }

    private int lastX;

    @Override public void mouseClicked(int mx, int my, int button) {
        lastX = mx;
        super.mouseClicked(mx, my, button);
    }

    @Override public void scrolled(double amount) { spin += (float) amount * 15; }

    @Override public void render(GuiRenderer g, int mx, int my, float partial) {
        g.fill(0, 0, PW, height, 0xC0000000);
        int cx = PW + (width - PW) / 2;
        g.gradient(PW + 8, 8, width - 8, height - 8, 0xE0303848, 0xE0181C24);
        float scale = Math.min(70, (height - 50) / 2.4f);
        float feet = height - 30;
        var skin = actor != null ? s.npcs.skins.face(actor.skin, actor.emotion) : game.assets.steve;
        int hl = part < 6 ? part : part == 7 && att() != null ? att().part : -1;
        GuiRenderer.Scene sc = g.new Scene();
        derpcraft.util.Mat4[] parts = g.playerModel(sc, skin, cx, feet, scale, spin, 0, 0, edit, hl);
        if (actor != null) for (derpcraft.npc.Attachment a : actor.attachments) {
            derpcraft.model.Model3D md = derpcraft.model.Models.get(a.model);
            if (md != null && !md.empty()) g.model3d(sc, md, a.matrix(parts[a.part], md), 0xFFFFFF);
        }
        sc.draw();
        if (part != 7) {
            float hw = edit.width / 2 * scale, hh = edit.height * scale;
            int c = 0xFFFFD040;
            g.fill(cx - hw, feet - hh, cx + hw, feet - hh + 1, c);
            g.fill(cx - hw, feet - 1, cx + hw, feet, c);
            g.fill(cx - hw, feet - hh, cx - hw + 1, feet, c);
            g.fill(cx + hw - 1, feet - hh, cx + hw, feet, c);
        }
        // ground line
        g.fill(PW + 20, feet, width - 20, feet + 1, 0x60FFFFFF);
        g.text("§7Drag / wheel: turn the model", PW + 12, 12, 0xFFFFFF, true);
        if (part == 7) g.text(actor == null ? "§eOpen the Pose editor from an NPC (Look page) to put models on it" : "§7Models worn by " + actor.name + ". X right, Y up, Z forward (px)", PW + 12, 22, 0xFFFFFF, true);
        else g.text("§7Yellow: hit box " + String.format(Locale.ROOT, "%.2f x %.2f", edit.width, edit.height), PW + 12, 22, 0xFFFFFF, true);
        if (edit.builtin && part != 7) g.text("§eBuilt-in pose: Copy it to change it", PW + 12, 34, 0xFFFFFF, true);
        super.render(g, mx, my, partial);
        if (statusTicks > 0) g.text(status, PW + 12, height - 22, 0xFFFFFF, true);
    }

    @Override public void keyPressed(int key, int mods) {
        if (key == GLFW_KEY_ENTER && nameField.focused) {
            applyName();
            nameField.focused = false;
            game.relayout();
            return;
        }
        super.keyPressed(key, mods);
    }

    /** Renames the (own) pose to what is typed in the name field. */
    private void applyName() {
        String n = nameField.text.trim();
        if (!n.isEmpty() && !edit.builtin && !n.equalsIgnoreCase(edit.name)) {
            Poses.delete(edit.name);
            edit.name = uniqueName(n);
            save();
        }
    }

    @Override public void onClose() {
        if (actor != null) s.npcs.save();
        applyName();
        if (dirty && !edit.builtin) save();
        game.setScreen(parent);
    }
}
