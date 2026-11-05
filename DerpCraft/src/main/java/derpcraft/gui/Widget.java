package derpcraft.gui;

import derpcraft.render.GuiRenderer;

import java.util.function.*;

import static org.lwjgl.glfw.GLFW.*;

/** Minecraft-like widgets: buttons, sliders, text fields. */
public abstract class Widget {
    public int x, y, w, h;
    public boolean visible = true, active = true;

    protected Widget(int x, int y, int w, int h) { this.x = x; this.y = y; this.w = w; this.h = h; }

    public boolean contains(int mx, int my) { return mx >= x && my >= y && mx < x + w && my < y + h; }
    public abstract void render(GuiRenderer g, int mx, int my);
    public boolean click(int mx, int my, int button) { return false; }
    public void drag(int mx, int my) {}
    public void tick() {}

    /** Draws the button background from widgets.png (left and right halves, like vanilla). */
    static void buttonBg(GuiRenderer g, int x, int y, int w, int h, int state) {
        int v = 46 + state * 20;
        int half = w / 2;
        if (h >= 20) {
            g.blit(g.assets.widgets, x, y, 0, v, half, h, 0xFFFFFFFF);
            g.blit(g.assets.widgets, x + half, y, 200 - (w - half), v, w - half, h, 0xFFFFFFFF);
        } else {
            // shorter than vanilla: top part plus the 3 pixel bottom edge
            int top = h - 3;
            g.blit(g.assets.widgets, x, y, 0, v, half, top, 0xFFFFFFFF);
            g.blit(g.assets.widgets, x + half, y, 200 - (w - half), v, w - half, top, 0xFFFFFFFF);
            g.blit(g.assets.widgets, x, y + top, 0, v + 17, half, 3, 0xFFFFFFFF);
            g.blit(g.assets.widgets, x + half, y + top, 200 - (w - half), v + 17, w - half, 3, 0xFFFFFFFF);
        }
    }

    public static class Button extends Widget {
        public Supplier<String> label;
        public Runnable onClick;

        public Button(int x, int y, int w, int h, String label, Runnable onClick) { this(x, y, w, h, () -> label, onClick); }

        public Button(int x, int y, int w, int h, Supplier<String> label, Runnable onClick) {
            super(x, y, w, h);
            this.label = label;
            this.onClick = onClick;
        }

        @Override public void render(GuiRenderer g, int mx, int my) {
            if (!visible) return;
            boolean hover = active && contains(mx, my);
            buttonBg(g, x, y, w, h, !active ? 0 : hover ? 2 : 1);
            int color = !active ? 0xA0A0A0 : hover ? 0xFFFFA0 : 0xE0E0E0;
            String l = label.get();
            while (g.textWidth(l) > w - 4 && l.length() > 1) l = l.substring(0, l.length() - 1);
            g.centered(l, x + w / 2f, y + (h - 8) / 2f, color);
        }

        @Override public boolean click(int mx, int my, int button) {
            if (!active || button != 0) return false;
            derpcraft.audio.Sfx.play("random/click", 0.7f, 1f);
            onClick.run();
            return true;
        }
    }

    public static class Slider extends Widget {
        private final String name;
        private final float min, max, step;
        private final DoubleSupplier get;
        private final DoubleConsumer set;
        private final Function<Float, String> format;

        public Slider(int x, int y, int w, String name, float min, float max, float step, DoubleSupplier get, DoubleConsumer set, Function<Float, String> format) {
            this(x, y, w, 20, name, min, max, step, get, set, format);
        }

        public Slider(int x, int y, int w, int h, String name, float min, float max, float step, DoubleSupplier get, DoubleConsumer set, Function<Float, String> format) {
            super(x, y, w, h);
            this.name = name; this.min = min; this.max = max; this.step = step; this.get = get; this.set = set; this.format = format;
        }

        @Override public void render(GuiRenderer g, int mx, int my) {
            buttonBg(g, x, y, w, h, 0);
            float t = (float) ((get.getAsDouble() - min) / (max - min));
            t = Math.max(0, Math.min(1, t));
            int kx = x + (int) (t * (w - 8));
            boolean hover = contains(mx, my);
            buttonBg(g, kx, y, 8, h, hover ? 2 : 1);
            boolean hl = highlight != null && highlight.getAsBoolean();
            String val = format.apply((float) get.getAsDouble()), nm = name, text = nm + ": " + val;
            while (g.textWidth(text) > w - 4 && nm.length() > 1) { nm = nm.substring(0, nm.length() - 1); text = nm + ".: " + val; }
            g.centered(text, x + w / 2f, y + (h - 8) / 2f, !active ? 0xA0A0A0 : hover ? 0xFFFFA0 : hl ? 0x9CE8FF : 0xE0E0E0);
        }

        /** Value restored by right click (NaN = none). */
        public double defaultValue = Double.NaN;
        /** When it says true the text is tinted (e.g. the value is not the default). */
        public java.util.function.BooleanSupplier highlight;
        /** Fine drag (Shift held when clicked): the value moves a tenth as fast as the mouse, from where it was. */
        private boolean fine;
        private int fineX;
        private double fineStart;

        public float min() { return min; }
        public float max() { return max; }
        public float step() { return step; }
        public double value() { return get.getAsDouble(); }
        /** Sets the value (kept in the range, on the step). */
        public void setValue(double v) { set.accept(Math.max(min, Math.min(max, v))); }

        @Override public boolean click(int mx, int my, int button) {
            if (!active) return true;
            if (button == 1) { if (!Double.isNaN(defaultValue)) set.accept(defaultValue); return true; }
            long win = glfwGetCurrentContext();
            fine = win != 0 && (glfwGetKey(win, GLFW_KEY_LEFT_SHIFT) == GLFW_PRESS || glfwGetKey(win, GLFW_KEY_RIGHT_SHIFT) == GLFW_PRESS);
            if (fine) { fineX = mx; fineStart = get.getAsDouble(); return true; }
            drag(mx, my);
            return true;
        }

        @Override public void drag(int mx, int my) {
            if (!active) return;
            float v;
            if (fine) v = (float) (fineStart + (mx - fineX) / (float) (w - 8) * (max - min) * 0.1f);
            else v = min + Math.max(0, Math.min(1, (mx - x - 4) / (float) (w - 8))) * (max - min);
            v = Math.round(v / step) * step;
            set.accept(Math.max(min, Math.min(max, v)));
        }
    }

    public static class TextField extends Widget {
        public String text = "";
        public boolean focused;
        public int maxLength = 256;
        public String hint = "";
        private int blink;
        public Runnable onEnter = () -> {};
        public Runnable onChange = () -> {};

        public TextField(int x, int y, int w, int h) { super(x, y, w, h); }

        @Override public void render(GuiRenderer g, int mx, int my) {
            if (!visible) return;
            g.fill(x - 1, y - 1, x + w + 1, y + h + 1, focused ? 0xFFFFFFFF : 0xFFA0A0A0);
            g.fill(x, y, x + w, y + h, 0xFF000000);
            String shown = text;
            while (g.textWidth(shown) > w - 8 && shown.length() > 0) shown = shown.substring(1);
            if (text.isEmpty() && !focused) g.text(hint, x + 4, y + (h - 8) / 2f, 0x707070, false);
            int end = g.text(shown, x + 4, y + (h - 8) / 2f, 0xE0E0E0, true);
            if (focused && (blink / 6) % 2 == 0) g.fill(x + 4 + end, y + (h - 8) / 2f - 1, x + 5 + end, y + (h - 8) / 2f + 9, 0xFFD0D0D0);
        }

        @Override public void tick() { blink++; }

        @Override public boolean click(int mx, int my, int button) { focused = true; return true; }

        public void typed(int cp) {
            if (cp < 32 || text.length() >= maxLength) return;
            text += new String(Character.toChars(cp));
            onChange.run();
        }

        public void key(int key, int mods) {
            if (key == GLFW_KEY_BACKSPACE && !text.isEmpty()) {
                if ((mods & GLFW_MOD_CONTROL) != 0) {
                    String t = text.stripTrailing();
                    int i = t.lastIndexOf(' ');
                    text = i < 0 ? "" : t.substring(0, i + 1);
                } else text = text.substring(0, text.length() - 1);
                onChange.run();
            } else if (key == GLFW_KEY_ENTER || key == GLFW_KEY_KP_ENTER) onEnter.run();
        }
    }
}
