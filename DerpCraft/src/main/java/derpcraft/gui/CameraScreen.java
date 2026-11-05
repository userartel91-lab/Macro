package derpcraft.gui;

import derpcraft.DerpCraft;
import derpcraft.camera.CameraEntity;
import derpcraft.camera.CameraNetwork;
import derpcraft.game.Session;
import derpcraft.render.GuiRenderer;
import derpcraft.render.Texture2D;

import java.util.Locale;

/**
 * Security camera menu (hub style: panel on the left, the world stays visible). A new camera asks for its code
 * first; an existing one is unlocked by typing its code. Then: name, code, where it looks, zoom, range, quality,
 * night vision, on / off, and a live picture.
 */
public final class CameraScreen extends Screen {
    static final int PW = 200;
    private final Session s;
    private final CameraEntity ce;
    private boolean unlocked;
    private String typed = "", message = "";
    private boolean showCode;

    public CameraScreen(DerpCraft g, Session s, CameraEntity ce, boolean unlocked) {
        super(g);
        this.s = s;
        this.ce = ce;
        this.unlocked = unlocked || ce.code.isEmpty();
        if (this.unlocked) typed = ce.code;
    }

    /** Text field that shows stars instead of its text (codes). */
    static final class CodeField extends Widget.TextField {
        boolean reveal;
        CodeField(int x, int y, int w, int h) { super(x, y, w, h); maxLength = 32; }
        @Override public void render(GuiRenderer g, int mx, int my) {
            if (reveal) { super.render(g, mx, my); return; }
            String real = text;
            text = "*".repeat(real.length());
            super.render(g, mx, my);
            text = real;
        }
    }

    @Override public void init() {
        int x = 4, w = PW - 8, y = 18, hw = w / 2 - 1;
        if (!unlocked) {
            y += 22;
            CodeField f = add(new CodeField(x, y, w, 16));
            f.text = typed; f.focused = true; f.hint = "camera code";
            f.onChange = () -> typed = f.text;
            f.onEnter = this::unlock;
            y += 22;
            add(new Widget.Button(x, y, w, 16, "Unlock", this::unlock));
            add(new Widget.Button(x, height - 20, w, 16, "Cancel", this::onClose));
            return;
        }
        // name and code
        y += 10;
        Widget.TextField name = add(new Widget.TextField(x, y, w, 16));
        name.text = ce.name; name.maxLength = 32; name.hint = "name (shown on screens' menu)";
        name.onChange = () -> { ce.name = name.text; ce.changed(); };
        y += 30;
        CodeField code = add(new CodeField(x, y, w - 42, 16));
        code.text = typed; code.reveal = showCode; code.hint = "code for the screens";
        code.focused = ce.code.isEmpty();
        code.onChange = () -> { typed = code.text; ce.code = code.text.trim(); ce.changed(); };
        add(new Widget.Button(x + w - 40, y, 40, 16, () -> showCode ? "Hide" : "Show", () -> { showCode = !showCode; game.relayout(); }));
        y += 20;
        // view
        Widget.Slider turn = add(new Widget.Slider(x, y, w, 16, "Turn", -180, 180, 1, () -> wrap(ce.yaw), v -> { ce.yaw = (float) v; ce.changed(); }, v -> Math.round(v) + "°"));
        turn.defaultValue = 0;
        y += 18;
        Widget.Slider tilt = add(new Widget.Slider(x, y, w, 16, "Tilt down", -90, 90, 1, () -> ce.pitch, v -> { ce.pitch = (float) v; ce.changed(); }, v -> Math.round(v) + "°"));
        tilt.defaultValue = 10;
        y += 18;
        Widget.Slider fov = add(new Widget.Slider(x, y, w, 16, "Field of view", 15, 110, 1, () -> ce.fov, v -> { ce.fov = (float) v; ce.changed(); }, v -> Math.round(v) + "°"));
        fov.defaultValue = 70;
        y += 18;
        Widget.Slider range = add(new Widget.Slider(x, y, w, 16, "Range", 16, 256, 16, () -> ce.range, v -> { ce.range = (int) Math.round(v); ce.changed(); }, v -> Math.round(v) + " blocks"));
        range.defaultValue = 96;
        y += 18;
        add(new Widget.Button(x, y, w, 16, "Aim where I look", () -> {
            ce.yaw = s.player.yaw; ce.pitch = Math.max(-90, Math.min(90, s.player.pitch)); ce.changed();
        }));
        y += 20;
        add(new Widget.Button(x, y, hw, 16, () -> "Quality: " + CameraEntity.QUALITY[ce.quality], () -> { ce.quality = (ce.quality + 1) % CameraEntity.QUALITY.length; ce.changed(); }));
        add(new Widget.Button(x + hw + 2, y, hw, 16, () -> ce.nightVision ? "§aNight vision" : "§7Night vision", () -> { ce.nightVision = !ce.nightVision; ce.changed(); }));
        y += 18;
        add(new Widget.Button(x, y, w, 16, () -> ce.on ? "§aCamera: ON" : "§cCamera: OFF", () -> { ce.on = !ce.on; ce.changed(); }));
        add(new Widget.Button(x, height - 20, w, 16, "Done", this::onClose));
    }

    private void unlock() {
        if (typed.trim().equals(ce.code)) { unlocked = true; message = ""; }
        else { message = "§cWrong code"; typed = ""; }
        game.relayout();
    }

    private static float wrap(float a) { return ((a + 180) % 360 + 360) % 360 - 180; }

    @Override public void render(GuiRenderer g, int mx, int my, float partial) {
        g.fill(0, 0, PW, height, 0xC0000000);
        g.text("Security Camera", 4, 5, 0xFFFFFF, true);
        g.text("Esc: close", PW - g.textWidth("Esc: close") - 4, 5, 0x808080, false);
        if (!unlocked) {
            g.text("§7" + ce.label(), 4, 18, 0xFFFFFF, true);
            g.text("Enter the camera's code:", 4, 30, 0xFFFFFF, true);
            if (!message.isEmpty()) g.text(message, 4, 86, 0xFFFFFF, true);
        } else {
            g.text("§7Name", 4, 19, 0xFFFFFF, true);
            g.text("§7Code §8(screens connect with it)", 4, 49, 0xFFFFFF, true);
            if (ce.code.isEmpty()) g.text("§cNo code yet: no screen can connect", 4, 208, 0xFFFFFF, true);
            else if (height - 132 >= 208) preview(g, s.cameraNet, ce.code, 4, height - 132, PW - 8);
        }
        super.render(g, mx, my, partial);
    }

    /** Live picture of a camera (16:9, 'w' wide), with its state when there is no picture. Shared with the canvas menu. */
    static void preview(GuiRenderer g, CameraNetwork net, String code, int x, int y, int w) {
        int h = w * 9 / 16;
        g.fill(x - 1, y - 1, x + w + 1, y + h + 1, 0xFF505050);
        Texture2D t = net == null ? null : net.feed(code);
        if (t != null) g.blitStretch(t, x, y, w, h, 0, 1, 1, 0, 0xFFFFFFFF);    // framebuffer rows are bottom-up
        else {
            g.fill(x, y, x + w, y + h, 0xFF0A0F1A);
            CameraNetwork.Status st = net == null ? CameraNetwork.Status.NO_CAMERA : net.status(code);
            String msg = code == null || code.isEmpty() ? "no code" : switch (st) {
                case OFF -> "camera is off";
                case WAITING -> "connecting...";
                default -> "no signal";
            };
            g.centered("§7" + msg.toUpperCase(Locale.ROOT), x + w / 2f, y + h / 2f - 4, 0xFFFFFF);
        }
    }

    @Override public void onClose() { ce.changed(); game.setScreen(null); }
}
