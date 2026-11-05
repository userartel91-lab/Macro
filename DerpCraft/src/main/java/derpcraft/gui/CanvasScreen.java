package derpcraft.gui;

import derpcraft.DerpCraft;
import derpcraft.camera.CameraEntity;
import derpcraft.camera.CameraNetwork;
import derpcraft.camera.CanvasBlock;
import derpcraft.camera.CanvasEntity;
import derpcraft.game.Session;
import derpcraft.render.GuiRenderer;

import java.util.Locale;

/**
 * Screen canvas menu (hub style). Camera: connect by typing a camera's code. Size: width, height, position and turn,
 * to fit the picture onto a decoration (a 3D monitor). Crop: cut the picture's edges. The world stays visible on the
 * right, so the canvas can be fitted while looking at it.
 */
public final class CanvasScreen extends Screen {
    private static final int PW = CameraScreen.PW;
    private final Session s;
    private final CanvasEntity ce;
    private static int page;
    private String typed = "", message = "";

    public CanvasScreen(DerpCraft g, Session s, CanvasEntity ce) {
        super(g);
        this.s = s;
        this.ce = ce;
        if (ce.code.isEmpty()) page = 0;
    }

    @Override public void init() {
        int x = 4, w = PW - 8, y = 16, tw = (w - 4) / 3;
        String[] tabs = {"Camera", "Size", "Crop"};
        for (int i = 0; i < 3; i++) {
            final int p = i;
            add(new Widget.Button(x + i * (tw + 2), y, i == 2 ? w - 2 * (tw + 2) : tw, 16, () -> (page == p ? "§e" : "") + tabs[p], () -> { page = p; game.relayout(); }));
        }
        y += 22;
        if (page == 0) {
            y += 12;
            CameraScreen.CodeField f = add(new CameraScreen.CodeField(x, y, w, 16));
            f.text = typed; f.hint = "camera code"; f.focused = ce.code.isEmpty();
            f.onChange = () -> typed = f.text;
            f.onEnter = this::connect;
            y += 20;
            int hw = w / 2 - 1;
            add(new Widget.Button(x, y, hw, 16, "Connect", this::connect));
            add(new Widget.Button(x + hw + 2, y, hw, 16, "Disconnect", () -> { ce.code = ""; ce.markDirty(); message = "§7Disconnected"; }));
            y += 50;
            Widget.Slider br = add(new Widget.Slider(x, y, w, 16, "Brightness", 0.1f, 2, 0.05f, () -> ce.brightness, v -> { ce.brightness = (float) v; ce.markDirty(); },
                    v -> Math.round(v * 100) + "%"));
            br.defaultValue = 1;
        } else if (page == 1) {
            y = slider(x, y, w, "Width", 0.05f, 8, () -> ce.width, v -> ce.width = v, 1, " blocks");
            y = slider(x, y, w, "Height", 0.05f, 8, () -> ce.height, v -> ce.height = v, 0.5625f, " blocks");
            int hw = w / 2 - 1;
            add(new Widget.Button(x, y, hw, 16, "Height = 16:9", () -> { ce.height = Math.max(0.05f, ce.width * 9 / 16f); ce.markDirty(); }));
            add(new Widget.Button(x + hw + 2, y, hw, 16, "Crop to fill", () -> { ce.cropToFill(); ce.markDirty(); }));
            y += 20;
            y = slider(x, y, w, "Move right", -4, 4, () -> ce.right, v -> ce.right = v, 0, " blocks");
            y = slider(x, y, w, "Move up", -4, 4, () -> ce.up, v -> ce.up = v, 0, " blocks");
            y = slider(x, y, w, "Move out", -4, 4, () -> ce.out, v -> ce.out = v, 0, " blocks");
            Widget.Slider roll = add(new Widget.Slider(x, y, w, 16, "Turn", -180, 180, 1, () -> ce.roll, v -> { ce.roll = (float) v; ce.markDirty(); }, v -> Math.round(v) + "°"));
            roll.defaultValue = 0;
            y += 18;
            int t3 = (w - 4) / 3;
            add(new Widget.Button(x, y, t3, 16, "-90°", () -> { ce.roll = wrap(ce.roll - 90); ce.markDirty(); }));
            add(new Widget.Button(x + t3 + 2, y, t3, 16, "+90°", () -> { ce.roll = wrap(ce.roll + 90); ce.markDirty(); }));
            boolean flat = CanvasBlock.back(s.world.getMeta(ce.x, ce.y, ce.z)) < 2;
            Widget.Button top = add(new Widget.Button(x + 2 * (t3 + 2), y, w - 2 * (t3 + 2), 16, "Top side", () -> { ce.facing = (ce.facing + 1) & 3; ce.markDirty(); }));
            top.active = flat;                                     // floor / ceiling: which way the picture's top points
            y += 20;
            add(new Widget.Button(x, y, w, 16, "Reset size and place", () -> {
                ce.width = 1; ce.height = 0.5625f; ce.right = ce.up = ce.out = 0; ce.roll = 0; ce.markDirty();
            }));
        } else {
            y = slider(x, y, w, "Crop left", 0, 0.45f, () -> ce.cropL, v -> ce.cropL = v, 0, "%");
            y = slider(x, y, w, "Crop right", 0, 0.45f, () -> ce.cropR, v -> ce.cropR = v, 0, "%");
            y = slider(x, y, w, "Crop top", 0, 0.45f, () -> ce.cropT, v -> ce.cropT = v, 0, "%");
            y = slider(x, y, w, "Crop bottom", 0, 0.45f, () -> ce.cropB, v -> ce.cropB = v, 0, "%");
            int hw = w / 2 - 1;
            add(new Widget.Button(x, y, hw, 16, "No crop", () -> { ce.cropL = ce.cropR = ce.cropT = ce.cropB = 0; ce.markDirty(); }));
            add(new Widget.Button(x + hw + 2, y, hw, 16, "Crop to fill", () -> { ce.cropToFill(); ce.markDirty(); }));
        }
        add(new Widget.Button(x, height - 20, w, 16, "Done", this::onClose));
    }

    private interface FloatGet { float get(); }
    private interface FloatSet { void set(float v); }

    /** A slider for one canvas value (blocks are shown with 2 decimals, crops as percent). Returns the next row's y. */
    private int slider(int x, int y, int w, String name, float min, float max, FloatGet get, FloatSet set, float def, String unit) {
        boolean pct = unit.equals("%");
        Widget.Slider sl = add(new Widget.Slider(x, y, w, 16, name, min, max, pct ? 0.005f : 1 / 64f, get::get, v -> { set.set((float) v); ce.markDirty(); },
                v -> pct ? Math.round(v * 100) + "%" : String.format(Locale.ROOT, "%.2f", v) + unit));
        sl.defaultValue = def;
        return y + 18;
    }

    private void connect() {
        String c = typed.trim();
        if (c.isEmpty()) { message = "§cType the camera's code"; return; }
        CameraEntity cam = s.cameraNet.camera(c);
        ce.code = c;                    // connected even if the camera is not loaded now: it shows up when it is
        ce.markDirty();
        message = cam == null ? "§eNo camera with this code nearby (saved anyway)" : "§aConnected: " + cam.label();
        typed = "";
        game.relayout();
    }

    private static float wrap(float a) { return ((a + 180) % 360 + 360) % 360 - 180; }

    @Override public void render(GuiRenderer g, int mx, int my, float partial) {
        g.fill(0, 0, PW, height, 0xC0000000);
        g.text("Screen Canvas", 4, 5, 0xFFFFFF, true);
        g.text("Esc: close", PW - g.textWidth("Esc: close") - 4, 5, 0x808080, false);
        CameraNetwork net = s.cameraNet;
        if (page == 0) {
            g.text("Camera code:", 4, 40, 0xFFFFFF, true);
            CameraEntity cam = net.camera(ce.code);
            String state = ce.code.isEmpty() ? "§7Not connected"
                    : cam == null ? "§eCamera not found (not loaded or code changed)"
                    : (cam.on ? "§aShowing: " : "§7Off: ") + cam.label();
            g.text(state, 4, 94, 0xFFFFFF, true);
            if (!message.isEmpty()) g.text(message, 4, 104, 0xFFFFFF, true);
        } else g.text("§7Right click a slider: default value", 4, page == 1 ? 208 : 132, 0xFFFFFF, true);
        // the picture as the canvas shows it (with its crop)
        int pw = PW - 8, ph = pw * 9 / 16, py = height - 24 - ph;
        if (py >= (page == 0 ? 142 : page == 1 ? 220 : 144)) {
            g.fill(3, py - 1, 5 + pw, py + ph + 1, 0xFF505050);
            var t = net.feed(ce.code);
            if (t != null) g.blitStretch(t, 4, py, pw, ph, ce.cropL, 1 - ce.cropT, 1 - ce.cropR, ce.cropB, 0xFFFFFFFF);
            else CameraScreen.preview(g, net, ce.code, 4, py, pw);
        }
        super.render(g, mx, my, partial);
    }

    @Override public void onClose() { ce.markDirty(); game.setScreen(null); }
}
