package derpcraft.api;

import derpcraft.item.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * A custom inventory-style screen built from code (machines with several slots, shops, trade screens, control
 * panels). Open it with {@link GameApi#openMenu}. Coordinates are GUI pixels inside the panel, like vanilla
 * container screens (176 wide; the player inventory takes the bottom 83 pixels).
 *
 * <pre>
 * Menu m = Menu.create("Grinder", 176, 166)
 *     .slot(inv, 0, 56, 35).slot(inv, 1, 116, 35, Menu.OUTPUT)
 *     .progress(79, 34, Menu.ARROW, () -> data.getFloat("progress"))
 *     .button(8, 60, 50, 16, () -> "Start", () -> data.put("on", true))
 *     .playerInventory();
 * game.openMenu(m);
 * </pre>
 */
public final class Menu {
    public static final int ARROW = 0, FLAME = 1, BAR = 2;
    /** Slot flag: items can only be taken out. */
    public static final int OUTPUT = 1;

    public record SlotDef(InventoryApi inv, int index, int x, int y, int flags, Predicate<ItemStack> filter) {}
    public record LabelDef(int x, int y, Supplier<String> text, int color, boolean centered) {}
    public record ButtonDef(int x, int y, int w, int h, Supplier<String> label, Runnable action) {}
    public record ProgressDef(int x, int y, int kind, Supplier<Float> value, int w, int color) {}

    public final String title;
    public final int width, height;
    public final List<SlotDef> slots = new ArrayList<>();
    public final List<LabelDef> labels = new ArrayList<>();
    public final List<ButtonDef> buttons = new ArrayList<>();
    public final List<ProgressDef> bars = new ArrayList<>();
    public int playerX = -1, playerY = -1;
    public Runnable onTick, onClose;

    private Menu(String title, int width, int height) { this.title = title; this.width = width; this.height = height; }

    /** A panel of the given size (vanilla containers are 176 x 166). */
    public static Menu create(String title, int width, int height) { return new Menu(title, Math.max(60, width), Math.max(40, height)); }

    public Menu slot(InventoryApi inv, int index, int x, int y) { return slot(inv, index, x, y, 0); }

    public Menu slot(InventoryApi inv, int index, int x, int y, int flags) { slots.add(new SlotDef(inv, index, x, y, flags, null)); return this; }

    /** A slot that only accepts some items. */
    public Menu slot(InventoryApi inv, int index, int x, int y, Predicate<ItemStack> filter) { slots.add(new SlotDef(inv, index, x, y, 0, filter)); return this; }

    /** A grid of slots (first index, columns x rows) starting at x, y with 18 px spacing. */
    public Menu grid(InventoryApi inv, int first, int x, int y, int cols, int rows) {
        for (int r = 0; r < rows; r++) for (int c = 0; c < cols; c++) slot(inv, first + r * cols + c, x + c * 18, y + r * 18);
        return this;
    }

    /** The player's inventory at the bottom of the panel (vanilla position). */
    public Menu playerInventory() { return playerInventory(8, height - 82); }

    public Menu playerInventory(int x, int y) { playerX = x; playerY = y; return this; }

    public Menu label(int x, int y, String text) { return label(x, y, () -> text, 0x404040); }

    public Menu label(int x, int y, Supplier<String> text, int color) { labels.add(new LabelDef(x, y, text, color, false)); return this; }

    public Menu centeredLabel(int y, Supplier<String> text, int color) { labels.add(new LabelDef(width / 2, y, text, color, true)); return this; }

    public Menu button(int x, int y, int w, int h, Supplier<String> label, Runnable action) { buttons.add(new ButtonDef(x, y, w, h, label, action)); return this; }

    /** Progress indicator: ARROW (24x17) and FLAME (14x14) like the furnace, or BAR (w x 5, colour). value 0..1. */
    public Menu progress(int x, int y, int kind, Supplier<Float> value) { bars.add(new ProgressDef(x, y, kind, value, 50, 0xFF40C040)); return this; }

    public Menu bar(int x, int y, int w, int color, Supplier<Float> value) { bars.add(new ProgressDef(x, y, BAR, value, w, color)); return this; }

    /** Called every game tick while open. */
    public Menu onTick(Runnable r) { onTick = r; return this; }

    public Menu onClose(Runnable r) { onClose = r; return this; }
}
