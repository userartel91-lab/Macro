package derpcraft.gui;

import derpcraft.DerpCraft;
import derpcraft.render.GuiRenderer;

import java.util.ArrayList;
import java.util.List;

import static org.lwjgl.glfw.GLFW.*;

/** Base class for menus. Coordinates are GUI pixels. */
public abstract class Screen {
    protected final DerpCraft game;
    protected final List<Widget> widgets = new ArrayList<>();
    public int width, height;
    /**
     * Whether the world stands still while this menu is open. One rule for every menu (DerpCraft.worldPaused): service
     * menus (Esc, NPC Hub, camera panel, timeline, shaders, grading, options, model and scene blocks...) pause it like
     * Esc; menus that belong to playing (inventory, chests, furnace, signs, books, chat, death screen) set this false.
     * The world still renders behind every menu, it just does not move.
     */
    protected boolean pausesGame = true;
    private Widget focused;

    protected Screen(DerpCraft game) { this.game = game; }

    /** Whether the world is paused while this menu is open (see {@link #pausesGame}). */
    public boolean pausesGame() { return pausesGame; }

    /** Whether a screen recording keeps running while this menu is open (when "Pause in Menus" is on).
     *  Game menus are filmed, service menus are cut out; chat is cut out too although the world keeps going. */
    public boolean recorded() { return !pausesGame(); }

    /** (Re)builds widgets for the current size. */
    public void init() {}

    public final void resize(int w, int h) {
        width = w; height = h;
        widgets.clear();
        init();
    }

    protected <T extends Widget> T add(T w) { widgets.add(w); return w; }

    public void render(GuiRenderer g, int mx, int my, float partial) {
        for (Widget w : widgets) w.render(g, mx, my);
    }

    public void tick() { for (Widget w : widgets) w.tick(); }

    public void mouseClicked(int mx, int my, int button) {
        focused = null;
        for (Widget w : widgets) if (w instanceof Widget.TextField tf) tf.focused = false;
        for (Widget w : new ArrayList<>(widgets)) {
            if (w.visible && w.contains(mx, my)) {
                focused = w;
                if (w.click(mx, my, button)) return;
            }
        }
    }

    public void mouseDragged(int mx, int my, int button) { if (focused != null) focused.drag(mx, my); }

    public void mouseReleased(int mx, int my, int button) { if (focused instanceof Widget.Slider) focused = null; }

    public void scrolled(double amount) {}

    public void keyPressed(int key, int mods) {
        if (key == GLFW_KEY_ESCAPE) { onClose(); return; }
        for (Widget w : widgets) if (w instanceof Widget.TextField tf && tf.focused) tf.key(key, mods);
    }

    public void charTyped(int codepoint) {
        for (Widget w : widgets) if (w instanceof Widget.TextField tf && tf.focused) tf.typed(codepoint);
    }

    /** Escape pressed. Default: back to the previous context. */
    public void onClose() { game.setScreen(null); }

    protected void title(GuiRenderer g, String s, int y) { g.centered(s, width / 2f, y, 0xFFFFFF); }
}
