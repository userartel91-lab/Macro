package derpcraft.gui;

import derpcraft.DerpCraft;
import derpcraft.game.Keys;
import derpcraft.model.Model3D;
import derpcraft.model.ModelSettings;
import derpcraft.model.Models;
import derpcraft.render.GuiRenderer;

import java.util.List;
import java.util.Locale;

/**
 * 3D Hub: the models in game/3d (Blockbench .json / .bbmodel, .obj) with a turntable preview. For each model:
 * the anchor point (what sits on a block or a body part), the size and the turn. Used by 3D blocks and NPCs.
 */
public final class ModelHub extends Screen {
    private static final int PW = 200;
    private final Screen parent;
    private static int page;          // 0 models, 1 placement
    private static int scroll;
    private static String selected;
    private static final ModelPreview.View view = new ModelPreview.View();
    private static boolean steve = true;
    private int rows;

    public ModelHub(DerpCraft g, Screen parent) {
        super(g);
        this.parent = parent;
        if (selected == null || !Models.exists(selected)) selected = Models.names().isEmpty() ? null : Models.names().get(0);
    }

    private Model3D model() { return selected == null ? null : Models.get(selected); }

    private ModelSettings st() { return Models.settings(selected); }

    @Override public void init() {
        int x = 4, w = PW - 8, y = 16, hw = w / 2 - 1;
        add(new Widget.Button(x, y, hw, 16, () -> (page == 0 ? "§e" : "") + "Models", () -> { page = 0; game.relayout(); }));
        Widget.Button pb = add(new Widget.Button(x + hw + 2, y, hw, 16, () -> (page == 1 ? "§e" : "") + "Place / size", () -> { page = 1; game.relayout(); }));
        pb.active = selected != null;
        y += 20;
        if (page == 0) modelsPage(x, y, w, hw);
        else placePage(x, y, w, hw);
        add(new Widget.Button(x, height - 20, hw, 16, () -> steve ? "Steve: shown" : "§7Steve: hidden", () -> steve = !steve));
        add(new Widget.Button(x + hw + 2, height - 20, hw, 16, "Done", this::onClose));
    }

    private void modelsPage(int x, int y, int w, int hw) {
        add(new Widget.Button(x, y, hw, 16, "Reload", () -> { Models.rescan(); if (selected != null && !Models.exists(selected)) selected = null; game.relayout(); }));
        add(new Widget.Button(x + hw + 2, y, hw, 16, "Open folder", this::openFolder));
        y += 20;
        List<String> names = Models.names();
        rows = Math.max(3, (height - y - 70) / 14);
        scroll = Math.max(0, Math.min(scroll, names.size() - rows));
        for (int r = 0; r < rows && scroll + r < names.size(); r++) {
            String n = names.get(scroll + r);
            add(new Widget.Button(x, y + r * 14, w, 13, () -> (n.equals(selected) ? "§e> " : "") + shortName(n, 30), () -> { selected = n; game.relayout(); }));
        }
    }

    private static String shortName(String n, int max) { return n.length() > max ? "..." + n.substring(n.length() - max + 3) : n; }

    private void placePage(int x, int y, int w, int hw) {
        if (selected == null) return;
        ModelSettings s = st();
        Model3D m = model();
        // size: logarithmic slider (x 1/128 .. x 16)
        Widget.Slider size = add(new Widget.Slider(x, y, w, 16, "Size", -7, 4, 0.05f, () -> Math.log(s.scale) / Math.log(2), v -> { s.scale = (float) Math.pow(2, v); changed(); },
                v -> String.format(Locale.ROOT, "x%.3g", Math.pow(2, v))));
        size.defaultValue = 0;
        y += 18;
        int tw3 = (w - 4) / 3;
        add(new Widget.Button(x, y, tw3, 16, "Fit 1 block", () -> fit(1)));
        add(new Widget.Button(x + tw3 + 2, y, tw3, 16, "Fit 2 blocks", () -> fit(2)));
        add(new Widget.Button(x + 2 * (tw3 + 2), y, w - 2 * (tw3 + 2), 16, "x 1/16", () -> { s.scale /= 16; changed(); }));
        y += 18;
        String[] ax = {"X", "Y", "Z"};
        for (int k = 0; k < 3; k++) {
            final int kk = k;
            Widget.Slider sl = add(new Widget.Slider(x + k * (tw3 + 2), y, tw3, 16, ax[k], 0.1f, 4, 0.05f, () -> kk == 0 ? s.sx : kk == 1 ? s.sy : s.sz,
                    v -> { if (kk == 0) s.sx = (float) v; else if (kk == 1) s.sy = (float) v; else s.sz = (float) v; changed(); }, v -> String.format(Locale.ROOT, "%.2f", v)));
            sl.defaultValue = 1;
        }
        y += 20;
        for (int k = 0; k < 3; k++) {
            final int kk = k;
            Widget.Slider sl = add(new Widget.Slider(x, y, w, 16, "Turn " + ax[k], -180, 180, 1, () -> kk == 0 ? s.rx : kk == 1 ? s.ry : s.rz,
                    v -> { if (kk == 0) s.rx = (float) v; else if (kk == 1) s.ry = (float) v; else s.rz = (float) v; changed(); }, v -> Math.round(v) + "°"));
            sl.defaultValue = 0;
            y += 18;
        }
        y += 2;
        // anchor: in the model's own units, inside (and a bit around) its bounds
        if (m != null && !m.empty()) {
            float[] lo = {m.minX, m.minY, m.minZ}, hi = {m.maxX, m.maxY, m.maxZ};
            for (int k = 0; k < 3; k++) {
                final int kk = k;
                float span = Math.max(1e-3f, hi[k] - lo[k]);
                Widget.Slider sl = add(new Widget.Slider(x, y, w, 16, "Anchor " + ax[k], lo[k] - span * 0.5f, hi[k] + span * 0.5f, span / 200f,
                        () -> s.anchor(m)[kk], v -> { if (kk == 0) s.ax = (float) v; else if (kk == 1) s.ay = (float) v; else s.az = (float) v; changed(); },
                        v -> String.format(Locale.ROOT, "%.3f", v)));
                sl.defaultValue = kk == 1 ? m.minY : (lo[k] + hi[k]) / 2;
                y += 18;
            }
            int tw4 = (w - 6) / 4;
            add(new Widget.Button(x, y, tw4, 16, "Bottom", () -> anchorY(m, 0)));
            add(new Widget.Button(x + tw4 + 2, y, tw4, 16, "Middle", () -> anchorY(m, 0.5f)));
            add(new Widget.Button(x + 2 * (tw4 + 2), y, tw4, 16, "Top", () -> anchorY(m, 1)));
            add(new Widget.Button(x + 3 * (tw4 + 2), y, w - 3 * (tw4 + 2), 16, "Auto", () -> { s.ax = s.ay = s.az = Float.NaN; changed(); game.relayout(); }));
            y += 18;
        }
        add(new Widget.Button(x, y, w, 16, "§cReset all", () -> {
            ModelSettings d = new ModelSettings();
            s.ax = d.ax; s.ay = d.ay; s.az = d.az; s.scale = 1; s.sx = s.sy = s.sz = 1; s.rx = s.ry = s.rz = 0;
            changed();
            game.relayout();
        }));
    }

    private void anchorY(Model3D m, float f) {
        ModelSettings s = st();
        s.ax = (m.minX + m.maxX) / 2; s.az = (m.minZ + m.maxZ) / 2;
        s.ay = m.minY + (m.maxY - m.minY) * f;
        changed();
    }

    private void fit(float blocks) {
        Model3D m = model();
        if (m == null || m.empty()) return;
        ModelSettings s = st();
        float big = Math.max(m.sizeX() * s.sx, Math.max(m.sizeY() * s.sy, m.sizeZ() * s.sz));
        if (big > 0) s.scale = blocks / big;
        changed();
    }

    private void changed() { Models.saveSettings(); }

    private void openFolder() {
        try {
            if (System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win")) new ProcessBuilder("explorer", Models.dir().toString()).start();
            else java.awt.Desktop.getDesktop().open(Models.dir().toFile());
        } catch (Exception e) { System.err.println("Cannot open folder: " + e); }
    }

    // ------------------------------------------------------------------ preview
    @Override public void render(GuiRenderer g, int mx, int my, float partial) {
        g.fill(0, 0, PW, height, 0xC0000000);
        g.text("3D Hub", 4, 5, 0xFFFFFF, true);
        String hint = Keys.n(Keys.MODEL_HUB) + ": close";
        g.text(hint, PW - g.textWidth(hint) - 4, 5, 0x808080, false);
        Model3D m = model();
        int px0 = PW + 6, py0 = 6, px1 = width - 6, py1 = height - 40;
        ModelPreview.draw(g, m, st(), null, px0, py0, px1, py1, view, steve ? game.assets.steve : null);
        if (Models.names().isEmpty()) {
            String[] help = {"No models yet. Put files in game/3d:", " - Blockbench: File > Export > Java Block/Item (.json)", "   with its .png next to it, or the project .bbmodel", " - .obj (+ .mtl and textures)", "Then press Reload."};
            int yy = 30;
            for (String h : help) { g.text("§7" + h, px0 + 6, yy, 0xFFFFFF, true); yy += 10; }
        }
        if (m != null) {
            ModelSettings s = st();
            float[] b = s.bounds(m, null);
            g.text("§e" + shortName(m.name, 44), px0 + 4, py0 + 4, 0xFFFFFF, true);
            if (m.error != null) g.text("§c" + m.error, px0 + 4, py0 + 14, 0xFFFFFF, true);
            else {
                g.text(String.format(Locale.ROOT, "§7%d triangles, %d textures", m.triangles(), m.groups.size()), px0 + 4, py0 + 14, 0xFFFFFF, true);
                g.text(String.format(Locale.ROOT, "§7size %.2f x %.2f x %.2f blocks", b[3] - b[0], b[4] - b[1], b[5] - b[2]), px0 + 4, py0 + 24, 0xFFFFFF, true);
            }
            int wy = py0 + 34;
            for (int i = 0; i < m.warnings.size() && i < 3; i++) { g.text("§6" + m.warnings.get(i), px0 + 4, wy, 0xFFFFFF, true); wy += 10; }
        }
        g.text("§7drag: turn  wheel: zoom  red cross: anchor", px0 + 4, py1 + 4, 0xFFFFFF, true);
        g.text("§7grid: 1 block.  Used by 3D blocks and NPCs", px0 + 4, py1 + 14, 0xFFFFFF, true);
        super.render(g, mx, my, partial);
    }

    @Override public void mouseClicked(int mx, int my, int button) {
        view.press(mx, my);
        super.mouseClicked(mx, my, button);
    }

    @Override public void mouseDragged(int mx, int my, int button) {
        if (!view.drag(mx, my, PW)) super.mouseDragged(mx, my, button);
    }

    @Override public void scrolled(double amount) {
        if (game.mouseGuiX() > PW) { view.zoom(amount); return; }
        if (page != 0) return;
        int old = scroll;
        scroll = Math.max(0, Math.min(scroll - (int) Math.signum(amount) * 3, Models.names().size() - rows));
        if (scroll != old) game.relayout();
    }

    @Override public void keyPressed(int key, int mods) {
        if (Keys.MODEL_HUB.matches(key)) { onClose(); return; }
        super.keyPressed(key, mods);
    }

    @Override public void onClose() { Models.saveSettings(); game.setScreen(parent); }
}
