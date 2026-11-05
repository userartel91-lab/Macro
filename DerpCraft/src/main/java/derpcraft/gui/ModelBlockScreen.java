package derpcraft.gui;

import derpcraft.DerpCraft;
import derpcraft.game.Session;
import derpcraft.model.Model3D;
import derpcraft.model.ModelEntity;
import derpcraft.model.Models;
import derpcraft.render.GuiRenderer;
import derpcraft.util.Mat4;

import java.util.List;
import java.util.Locale;

/** Settings of a 3D block: which model (from game/3d), its turn, size, offset and collision. */
public final class ModelBlockScreen extends Screen {
    private static final int PW = 200;
    private final ModelEntity me;
    private static int page;
    private static int scroll;
    private static final ModelPreview.View view = new ModelPreview.View();
    private int rows;

    public ModelBlockScreen(DerpCraft g, Session s, ModelEntity me) {
        super(g);
        this.me = me;
    }

    @Override public void init() {
        int x = 4, w = PW - 8, y = 16, hw = w / 2 - 1;
        add(new Widget.Button(x, y, hw, 16, () -> (page == 0 ? "§e" : "") + "Model", () -> { page = 0; game.relayout(); }));
        add(new Widget.Button(x + hw + 2, y, hw, 16, () -> (page == 1 ? "§e" : "") + "Place", () -> { page = 1; game.relayout(); }));
        y += 20;
        if (page == 0) {
            add(new Widget.Button(x, y, hw, 16, "Reload files", () -> { Models.rescan(); game.relayout(); }));
            add(new Widget.Button(x + hw + 2, y, hw, 16, "3D Hub...", () -> game.setScreen(new ModelHub(game, this))));
            y += 20;
            List<String> names = Models.names();
            rows = Math.max(3, (height - y - 50) / 14);
            scroll = Math.max(0, Math.min(scroll, names.size() - rows));
            for (int r = 0; r < rows && scroll + r < names.size(); r++) {
                String n = names.get(scroll + r);
                add(new Widget.Button(x, y + r * 14, w, 13, () -> (n.equals(me.model) ? "§e> " : "") + (n.length() > 30 ? "..." + n.substring(n.length() - 27) : n), () -> {
                    me.model = n;
                    ModelEntity.lastModel = n;
                    me.changed();
                }));
            }
        } else {
            String[] ax = {"Turn (around)", "Tilt forward", "Tilt sideways"};
            for (int k = 0; k < 3; k++) {
                final int kk = k;
                Widget.Slider sl = add(new Widget.Slider(x, y, w, 16, ax[k], -180, 180, 1, () -> kk == 0 ? me.yaw : kk == 1 ? me.pitch : me.roll,
                        v -> { float f = (float) v; if (kk == 0) me.yaw = f; else if (kk == 1) me.pitch = f; else me.roll = f; me.changed(); }, v -> Math.round(v) + "°"));
                sl.defaultValue = 0;
                y += 18;
            }
            int tw4 = (w - 6) / 4;
            add(new Widget.Button(x, y, tw4, 16, "-90°", () -> { me.yaw = wrap(me.yaw - 90); me.changed(); }));
            add(new Widget.Button(x + tw4 + 2, y, tw4, 16, "-15°", () -> { me.yaw = wrap(me.yaw - 15); me.changed(); }));
            add(new Widget.Button(x + 2 * (tw4 + 2), y, tw4, 16, "+15°", () -> { me.yaw = wrap(me.yaw + 15); me.changed(); }));
            add(new Widget.Button(x + 3 * (tw4 + 2), y, w - 3 * (tw4 + 2), 16, "+90°", () -> { me.yaw = wrap(me.yaw + 90); me.changed(); }));
            y += 20;
            Widget.Slider size = add(new Widget.Slider(x, y, w, 16, "Size", -4, 4, 0.05f, () -> Math.log(me.scale) / Math.log(2), v -> { me.scale = (float) Math.pow(2, v); me.changed(); },
                    v -> String.format(Locale.ROOT, "x%.3g", Math.pow(2, v))));
            size.defaultValue = 0;
            y += 20;
            String[] off = {"Move X", "Move Y (up)", "Move Z"};
            for (int k = 0; k < 3; k++) {
                final int kk = k;
                Widget.Slider sl = add(new Widget.Slider(x, y, w, 16, off[k], -2, 2, 1 / 32f, () -> kk == 0 ? me.ox : kk == 1 ? me.oy : me.oz,
                        v -> { float f = (float) v; if (kk == 0) me.ox = f; else if (kk == 1) me.oy = f; else me.oz = f; me.changed(); },
                        v -> String.format(Locale.ROOT, "%+.2f blocks", v)));
                sl.defaultValue = 0;
                y += 18;
            }
            y += 2;
            add(new Widget.Button(x, y, w, 16, () -> me.collision ? "§aCollision: ON (model's box)" : "§7Collision: OFF (walk through)", () -> { me.collision = !me.collision; me.changed(); }));
        }
        add(new Widget.Button(x, height - 20, w, 16, "Done", this::onClose));
    }

    private static float wrap(float a) { a = ((a + 180) % 360 + 360) % 360 - 180; return a; }

    /** The block's own placement (turn, size, offset) relative to its bottom centre. */
    private Mat4 extra() {
        Mat4 r = new Mat4().translate(me.ox, me.oy, me.oz);
        if (me.yaw != 0) r.rotate((float) Math.toRadians(-me.yaw), 0, 1, 0);
        if (me.pitch != 0) r.rotate((float) Math.toRadians(me.pitch), 1, 0, 0);
        if (me.roll != 0) r.rotate((float) Math.toRadians(me.roll), 0, 0, 1);
        if (me.scale != 1) r.scale(me.scale, me.scale, me.scale);
        return r;
    }

    @Override public void render(GuiRenderer g, int mx, int my, float partial) {
        g.fill(0, 0, PW, height, 0xC0000000);
        g.text("3D Block", 4, 5, 0xFFFFFF, true);
        g.text("Esc: close", PW - g.textWidth("Esc: close") - 4, 5, 0x808080, false);
        Model3D m = Models.get(me.model);
        int px0 = PW + 6, py0 = 6, px1 = width - 6, py1 = height - 30;
        ModelPreview.draw(g, m, Models.settings(me.model), extra(), px0, py0, px1, py1, view, null);
        if (Models.names().isEmpty()) {
            g.text("§7No models in game/3d yet (3D Hub: Open folder).", px0 + 6, 30, 0xFFFFFF, true);
        } else if (m == null) g.text("§7Choose a model on the left.", px0 + 6, 30, 0xFFFFFF, true);
        else {
            g.text("§e" + m.name, px0 + 4, py0 + 4, 0xFFFFFF, true);
            if (m.error != null) g.text("§c" + m.error, px0 + 4, py0 + 14, 0xFFFFFF, true);
            else {
                double[] b = me.collisionBox();
                String sz = b == null ? "" : String.format(Locale.ROOT, "   box %.2f x %.2f x %.2f", b[3] - b[0], b[4] - b[1], b[5] - b[2]);
                g.text("§7" + m.triangles() + " triangles" + sz, px0 + 4, py0 + 14, 0xFFFFFF, true);
            }
        }
        g.text("§7drag: turn view   wheel: zoom   red cross: the block's centre", px0 + 4, py1 + 4, 0xFFFFFF, true);
        super.render(g, mx, my, partial);
    }

    @Override public void mouseClicked(int mx, int my, int button) { view.press(mx, my); super.mouseClicked(mx, my, button); }

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

    @Override public void onClose() { me.changed(); game.setScreen(null); }
}
