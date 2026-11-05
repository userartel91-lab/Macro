package derpcraft.gui;

import derpcraft.DerpCraft;
import derpcraft.game.PlayerInventory;
import derpcraft.game.Session;
import derpcraft.item.*;
import derpcraft.render.GuiRenderer;
import derpcraft.render.Texture2D;
import derpcraft.world.be.ContainerEntity;
import derpcraft.world.be.FurnaceEntity;
import derpcraft.world.be.SignEntity;

import java.util.*;
import java.util.function.Predicate;

import static org.lwjgl.glfw.GLFW.*;

/**
 * Minecraft 1.12 container screens: survival inventory, crafting table, chests, furnace, dispenser, hopper, the creative
 * inventory with its tabs, the sign editor and the death screen. Slot positions and click rules follow vanilla.
 */
public final class Containers {
    private Containers() {}

    // ============================================================================ inventories
    /** Two inventories shown as one (double chest). */
    public static final class Joined extends Inventory {
        private final Inventory a, b;

        public Joined(Inventory a, Inventory b) { super(0); this.a = a; this.b = b; }

        @Override public int size() { return a.size() + b.size(); }
        @Override public ItemStack get(int i) { return i < a.size() ? a.get(i) : b.get(i - a.size()); }
        @Override public void set(int i, ItemStack s) { if (i < a.size()) a.set(i, s); else b.set(i - a.size(), s); }
        @Override public void changed() { a.changed(); b.changed(); }
        // the base class works on its own (empty) slot array: route everything else to the two halves too
        @Override public void clear() { a.clear(); b.clear(); }
        @Override public boolean isEmpty() { return a.isEmpty() && b.isEmpty(); }
        @Override public ItemStack add(ItemStack s, int from, int to) {
            int n = a.size();
            ItemStack left = from < n ? a.add(s, from, Math.min(to, n)) : s;
            if (left != null && to > n) left = b.add(left, Math.max(0, from - n), to - n);
            return left;
        }
    }

    /** One slot on screen. */
    public static class Slot {
        public final Inventory inv;
        public final int index, x, y;
        public Predicate<ItemStack> filter = s -> true;
        public int maxStack = 64;
        /** Crafting/furnace output: items can only be taken. */
        public boolean output;
        /** Player inventory slot (shift-click moves between player and container). */
        public boolean player;

        public Slot(Inventory inv, int index, int x, int y) { this.inv = inv; this.index = index; this.x = x; this.y = y; }

        public ItemStack get() { return inv.get(index); }
        public void set(ItemStack s) {
            if (!ItemStack.isEmpty(s) && inv instanceof PlayerInventory && index >= PlayerInventory.ARMOR && index < PlayerInventory.ARMOR + 4 && inv.get(index) == null)
                derpcraft.audio.Sfx.armor(s.item.name);
            inv.set(index, ItemStack.isEmpty(s) ? null : s);
        }
        public boolean accepts(ItemStack s) { return !output && filter.test(s); }
        public int limit(ItemStack s) { return Math.min(maxStack, s.maxStack()); }
        /** Called after items were taken from the slot (crafting consumes ingredients). */
        public void onTake(ItemStack taken) {}
    }

    // ============================================================================ base screen
    public abstract static class Base extends Screen {
        protected final Session s;
        protected final PlayerInventory inv;
        protected int xSize = 176, ySize = 166, left, top;
        protected final List<Slot> slots = new ArrayList<>();
        protected String title = "", invLabel = "Inventory";
        private final Set<Slot> dragged = new LinkedHashSet<>();
        private int dragButton = -1;
        private Slot lastClicked;
        private long lastClickTime;

        protected Base(DerpCraft g, Session s) {
            super(g);
            this.s = s;
            this.inv = s.inv;
            pausesGame = false;
        }

        protected abstract Texture2D background();

        protected abstract void buildSlots();

        @Override public void init() {
            left = (width - xSize) / 2;
            top = (height - ySize) / 2;
            slots.clear();
            buildSlots();
        }

        protected void addPlayerSlots(int x, int y) {
            for (int r = 0; r < 3; r++)
                for (int c = 0; c < 9; c++) { Slot sl = new Slot(inv, 9 + r * 9 + c, x + c * 18, y + r * 18); sl.player = true; slots.add(sl); }
            for (int c = 0; c < 9; c++) { Slot sl = new Slot(inv, c, x + c * 18, y + 58); sl.player = true; slots.add(sl); }
        }

        protected Slot slotAt(int mx, int my) {
            for (Slot sl : slots) if (mx >= left + sl.x - 1 && mx < left + sl.x + 17 && my >= top + sl.y - 1 && my < top + sl.y + 17) return sl;
            return null;
        }

        protected boolean outside(int mx, int my) { return mx < left || my < top || mx >= left + xSize || my >= top + ySize; }

        // ---------------------------------------------------------------- rendering
        @Override public void render(GuiRenderer g, int mx, int my, float partial) {
            g.gradient(0, 0, width, height, 0xC0101010, 0xD0101010);
            drawBackground(g, mx, my, partial);
            drawLabels(g);
            Slot hover = slotAt(mx, my);
            for (Slot sl : slots) g.itemWithCount(sl.get(), left + sl.x, top + sl.y);
            if (hover != null) {
                g.fill(left + hover.x, top + hover.y, left + hover.x + 16, top + hover.y + 16, 0x80FFFFFF);
            }
            drawForeground(g, mx, my);
            if (inv.cursor != null) g.itemWithCount(inv.cursor, mx - 8, my - 8);
            else if (hover != null && hover.get() != null) g.tooltip(tooltip(hover.get()), mx, my);
        }

        protected void drawBackground(GuiRenderer g, int mx, int my, float partial) {
            g.blit(background(), left, top, 0, 0, xSize, ySize, 0xFFFFFFFF);
        }

        protected void drawLabels(GuiRenderer g) {
            g.text(title, left + 8, top + 6, 0x404040, false);
            if (invLabel != null) g.text(invLabel, left + 8, top + ySize - 96 + 2, 0x404040, false);
        }

        protected void drawForeground(GuiRenderer g, int mx, int my) {}

        protected List<String> tooltip(ItemStack st) {
            List<String> l = new ArrayList<>();
            l.add(st.displayName());
            if (st.item.tooltip != null) for (String t : st.item.tooltip) l.add("§7" + t);
            l.addAll(derpcraft.block.ModBlock.contentsTooltip(st));
            if (st.item.maxDamage > 0 && st.damage > 0) l.add("Durability: " + (st.item.maxDamage - st.damage) + " / " + st.item.maxDamage);
            if (st.item.isFood()) l.add("§9+" + st.item.food / 2f + " hunger");
            return l;
        }

        // ---------------------------------------------------------------- input
        @Override public void mouseClicked(int mx, int my, int button) {
            Slot sl = slotAt(mx, my);
            boolean shift = glfwGetKey(game.window, GLFW_KEY_LEFT_SHIFT) == GLFW_PRESS || glfwGetKey(game.window, GLFW_KEY_RIGHT_SHIFT) == GLFW_PRESS;
            if (button == 2) { middleClick(sl); return; }
            if (sl == null) {
                if (clickOutside(mx, my, button)) return;
                if (outside(mx, my) && inv.cursor != null) {
                    ItemStack c = inv.cursor;
                    if (button == 0) { s.drop(c, true); inv.cursor = null; }
                    else { s.drop(c.split(1), true); if (c.count <= 0) inv.cursor = null; }
                }
                return;
            }
            long now = System.currentTimeMillis();
            boolean dbl = button == 0 && sl == lastClicked && now - lastClickTime < 250 && inv.cursor != null && !shift;
            lastClicked = sl; lastClickTime = now;
            if (dbl) { collect(sl); return; }
            if (shift) { quickMove(sl); return; }
            click(sl, button);
            if (inv.cursor != null && !sl.output) { dragged.clear(); dragged.add(sl); dragButton = button; }
        }

        /** Click outside slots inside the panel (tabs, buttons). Return true if handled. */
        protected boolean clickOutside(int mx, int my, int button) { return false; }

        @Override public void mouseDragged(int mx, int my, int button) {
            if (dragButton != 1 || inv.cursor == null) return;
            Slot sl = slotAt(mx, my);
            if (sl == null || dragged.contains(sl) || !sl.accepts(inv.cursor)) return;
            ItemStack cur = sl.get();
            if (cur != null && !cur.stacksWith(inv.cursor)) return;
            dragged.add(sl);
            click(sl, 1); // right-drag places one item per slot
        }

        @Override public void mouseReleased(int mx, int my, int button) { dragButton = -1; dragged.clear(); }

        protected void middleClick(Slot sl) {
            if (sl == null || sl.get() == null || !s.creative() || inv.cursor != null) return;
            ItemStack st = sl.get().copy();
            st.count = st.maxStack();
            inv.cursor = st;
        }

        /** Vanilla left/right click on a slot. */
        protected void click(Slot sl, int button) {
            ItemStack cur = sl.get(), hand = inv.cursor;
            if (sl.output) {
                if (cur == null) return;
                if (hand == null) { inv.cursor = cur.copy(); sl.set(null); sl.onTake(cur); }
                else if (hand.stacksWith(cur) && hand.count + cur.count <= hand.maxStack()) { hand.count += cur.count; sl.set(null); sl.onTake(cur); }
                return;
            }
            if (button == 0) {
                if (hand == null) { if (cur != null) { inv.cursor = cur; sl.set(null); } }
                else if (cur == null) {
                    if (!sl.accepts(hand)) return;
                    int n = Math.min(hand.count, sl.limit(hand));
                    sl.set(hand.split(n));
                    if (hand.count <= 0) inv.cursor = null;
                } else if (cur.stacksWith(hand)) {
                    if (!sl.accepts(hand)) return;
                    int n = Math.min(hand.count, sl.limit(cur) - cur.count);
                    if (n > 0) { cur.count += n; hand.count -= n; sl.inv.changed(); }
                    if (hand.count <= 0) inv.cursor = null;
                } else if (sl.accepts(hand) && hand.count <= sl.limit(hand)) { sl.set(hand); inv.cursor = cur; }
            } else if (button == 1) {
                if (hand == null) {
                    if (cur != null) {
                        int n = (cur.count + 1) / 2;
                        inv.cursor = cur.split(n);
                        sl.set(cur.count <= 0 ? null : cur);
                    }
                } else if (cur == null) {
                    if (!sl.accepts(hand)) return;
                    sl.set(hand.split(1));
                    if (hand.count <= 0) inv.cursor = null;
                } else if (cur.stacksWith(hand)) {
                    if (!sl.accepts(hand) || cur.count >= sl.limit(cur)) return;
                    cur.count++; hand.count--; sl.inv.changed();
                    if (hand.count <= 0) inv.cursor = null;
                } else if (sl.accepts(hand) && hand.count <= sl.limit(hand)) { sl.set(hand); inv.cursor = cur; }
            }
        }

        /** Double click: gather items of the cursor's kind into the cursor. */
        private void collect(Slot clicked) {
            ItemStack hand = inv.cursor;
            if (hand == null) return;
            for (int pass = 0; pass < 2 && hand.count < hand.maxStack(); pass++)
                for (Slot sl : slots) {
                    ItemStack st = sl.get();
                    if (st == null || sl.output || !st.stacksWith(hand)) continue;
                    if (pass == 0 && st.count == st.maxStack()) continue;
                    int n = Math.min(st.count, hand.maxStack() - hand.count);
                    hand.count += n; st.count -= n;
                    sl.set(st.count <= 0 ? null : st);
                    if (hand.count >= hand.maxStack()) break;
                }
        }

        /** Shift-click. Default: player slots <-> container slots, or main <-> hotbar when there is no container. */
        protected void quickMove(Slot sl) {
            ItemStack st = sl.get();
            if (st == null) return;
            if (sl.output) {
                // take repeatedly (crafting)
                for (int guard = 0; guard < 64 && sl.get() != null; guard++) {
                    ItemStack out = sl.get();
                    if (!fits(out, playerSlots())) break;
                    ItemStack copy = out.copy();
                    sl.set(null);
                    merge(copy, playerSlots(), true);
                    sl.onTake(out);
                    if (!repeatOutput()) break;
                }
                return;
            }
            List<Slot> targets;
            if (sl.player) {
                targets = containerSlots(st);
                if (targets.isEmpty()) targets = slots.stream().filter(o -> o.player && (sl.index < 9) != (o.index < 9)).toList();
            } else targets = playerSlots();
            ItemStack left = merge(st.copy(), targets, !sl.player);
            sl.set(left);
        }

        protected boolean repeatOutput() { return true; }

        /** Container slots that a shift-clicked stack from the player inventory may go to. */
        protected List<Slot> containerSlots(ItemStack st) {
            List<Slot> l = new ArrayList<>();
            for (Slot o : slots) if (!o.player && !o.output && o.accepts(st)) l.add(o);
            return l;
        }

        protected List<Slot> playerSlots() {
            List<Slot> l = new ArrayList<>();
            for (Slot o : slots) if (o.player && o.index < PlayerInventory.MAIN) l.add(o);
            return l;
        }

        /** Merges into matching stacks, then empty slots. Returns the leftover (or null). */
        protected static ItemStack merge(ItemStack st, List<Slot> targets, boolean reverse) {
            List<Slot> order = new ArrayList<>(targets);
            if (reverse) Collections.reverse(order);
            for (Slot o : order) {
                ItemStack cur = o.get();
                if (cur != null && cur.stacksWith(st) && o.accepts(st)) {
                    int n = Math.min(st.count, o.limit(cur) - cur.count);
                    if (n > 0) { cur.count += n; st.count -= n; o.inv.changed(); }
                }
                if (st.count <= 0) return null;
            }
            for (Slot o : order) {
                if (o.get() == null && o.accepts(st)) {
                    int n = Math.min(st.count, o.limit(st));
                    o.set(st.split(n));
                }
                if (st.count <= 0) return null;
            }
            return st;
        }

        private static boolean fits(ItemStack st, List<Slot> targets) {
            int room = 0;
            for (Slot o : targets) {
                ItemStack cur = o.get();
                if (cur == null) room += st.maxStack();
                else if (cur.stacksWith(st)) room += st.maxStack() - cur.count;
                if (room >= st.count) return true;
            }
            return false;
        }

        @Override public void keyPressed(int key, int mods) {
            if (key == GLFW_KEY_ESCAPE || derpcraft.game.Keys.INVENTORY.matches(key)) { onClose(); return; }
            Slot hover = slotAt(game.mouseGuiX(), game.mouseGuiY());
            if (hover == null) return;
            int hbKey = -1;
            for (int i = 0; i < 9; i++) if (derpcraft.game.Keys.HOTBAR[i].matches(key)) hbKey = i;
            if (hbKey >= 0 && inv.cursor == null) {
                int hb = hbKey;
                ItemStack a = hover.get(), b = inv.get(hb);
                if (hover.output) { if (b == null && a != null) { inv.set(hb, a.copy()); hover.set(null); hover.onTake(a); } return; }
                if (b != null && !hover.accepts(b)) return;
                hover.set(b);
                inv.set(hb, a);
            } else if (derpcraft.game.Keys.DROP.matches(key) && hover.get() != null && !hover.output) {
                ItemStack st = hover.get();
                boolean all = (mods & GLFW_MOD_CONTROL) != 0;
                s.drop(all ? st : st.split(1), true);
                hover.set(all || st.count <= 0 ? null : st);
            }
        }

        @Override public void onClose() {
            returnItems();
            if (inv.cursor != null) { s.giveOrDrop(inv.cursor); inv.cursor = null; }
            game.setScreen(null);
        }

        /** Gives back items from temporary slots (crafting grids). */
        protected void returnItems() {}
    }

    // ============================================================================ crafting support
    /** A crafting grid with its result slot. */
    public static final class Craft {
        public final Inventory grid, result = new Inventory(1);
        final int w, h;

        Craft(int w, int h) {
            this.w = w; this.h = h;
            grid = new Inventory(w * h);
            grid.onChange = this::update;
        }

        void update() {
            ItemStack[] a = new ItemStack[w * h];
            for (int i = 0; i < a.length; i++) a[i] = grid.get(i);
            ItemStack r = Recipes.match(a, w, h);
            if (!Objects.equals(String.valueOf(r), String.valueOf(result.get(0)))) result.set(0, r);
        }

        void consume() {
            Runnable old = grid.onChange;
            grid.onChange = () -> {};
            for (int i = 0; i < w * h; i++) {
                ItemStack st = grid.get(i);
                if (st == null) continue;
                ItemStack container = st.item.container;
                st.count--;
                grid.set(i, st.count <= 0 ? (container != null ? container.copy() : null) : st);
            }
            grid.onChange = old;
            update();
        }

        Slot resultSlot(int x, int y) {
            Slot sl = new Slot(result, 0, x, y) {
                @Override public void onTake(ItemStack taken) { consume(); }
            };
            sl.output = true;
            return sl;
        }

        void giveBack(Session s) {
            for (int i = 0; i < w * h; i++) if (grid.get(i) != null) { s.giveOrDrop(grid.get(i)); }
            grid.onChange = () -> {};
            grid.clear();
        }
    }

    private static void armorSlots(List<Slot> slots, PlayerInventory inv, int[][] pos) {
        for (int k = 0; k < 4; k++) {
            final int part = k;
            Slot sl = new Slot(inv, PlayerInventory.ARMOR + k, pos[k][0], pos[k][1]);
            sl.filter = st -> st.item.armorSlot == part;
            sl.maxStack = 1;
            sl.player = true;
            slots.add(sl);
        }
    }

    // ============================================================================ survival inventory
    public static final class PlayerInv extends Base {
        private final Craft craft = new Craft(2, 2);

        public PlayerInv(DerpCraft g, Session s) { super(g, s); invLabel = null; }

        @Override protected Texture2D background() { return game.assets.gui.get("inventory"); }

        @Override protected void buildSlots() {
            for (int r = 0; r < 2; r++) for (int c = 0; c < 2; c++) slots.add(new Slot(craft.grid, r * 2 + c, 98 + c * 18, 18 + r * 18));
            slots.add(craft.resultSlot(154, 28));
            armorSlots(slots, inv, new int[][]{{8, 8}, {8, 26}, {8, 44}, {8, 62}});
            addPlayerSlots(8, 84);
        }

        @Override protected List<Slot> containerSlots(ItemStack st) {
            List<Slot> l = new ArrayList<>();
            if (st.item.armorSlot >= 0) for (Slot o : slots) if (o.index == PlayerInventory.ARMOR + st.item.armorSlot && o.inv == inv && o.get() == null) l.add(o);
            return l;
        }

        @Override protected void drawLabels(GuiRenderer g) { g.text("Crafting", left + 97, top + 8, 0x404040, false); }

        @Override protected void drawBackground(GuiRenderer g, int mx, int my, float partial) {
            super.drawBackground(g, mx, my, partial);
            // vanilla GuiInventory.drawEntityOnScreen(left + 51, top + 75, 30, ...): body turns 20 deg, head 20 more, all leans with the mouse
            float cx = left + 51, cy = top + 75;
            float fx = (float) Math.atan((cx - mx) / 40f), fy = (float) Math.atan((cy - 50 - my) / 40f);
            g.playerModel(game.assets.steve, cx, cy, 30, -fx * 20, -fx * 20, -fy * 20, -fy * 20);
        }

        @Override protected void returnItems() { craft.giveBack(s); }
    }

    // ============================================================================ equipment (Left Alt + E)
    /**
     * The equipment: three pouches on the left of the belt and three on the right (PlayerInventory.POUCH..), gun
     * magazines only, at most POUCH_MAX each; the player in the middle. Reloads take magazines from the pouches first.
     */
    public static final class Equipment extends Base {
        public Equipment(DerpCraft g, Session s) { super(g, s); title = "Equipment"; }

        @Override protected Texture2D background() { return null; }

        static int pouchX(int k) { return k < 3 ? 26 : 134; }
        static int pouchY(int k) { return 18 + (k % 3) * 18; }

        @Override protected void buildSlots() {
            for (int k = 0; k < PlayerInventory.POUCHES; k++) {
                Slot sl = new Slot(inv, PlayerInventory.POUCH + k, pouchX(k), pouchY(k));
                sl.filter = st -> derpcraft.gun.Guns.isMag(st.item);
                sl.maxStack = PlayerInventory.POUCH_MAX;
                slots.add(sl);                           // not a player slot: shift-click moves magazines between here and the inventory
            }
            addPlayerSlots(8, 84);
        }

        @Override protected void drawBackground(GuiRenderer g, int mx, int my, float partial) {
            panel(g, left, top, xSize, ySize);
            for (int k = 0; k < PlayerInventory.POUCHES; k++) slotFrame(g, left + pouchX(k), top + pouchY(k));
            playerFrames(g, left + 8, top + 84);
            float cx = left + 88, cy = top + 76;
            float fx = (float) Math.atan((cx - mx) / 40f), fy = (float) Math.atan((cy - 50 - my) / 40f);
            g.playerModel(game.assets.steve, cx, cy, 28, -fx * 20, -fx * 20, -fy * 20, -fy * 20);
        }

        @Override protected void drawLabels(GuiRenderer g) {
            g.text(title, left + (xSize - g.textWidth(title)) / 2f, top + 6, 0x404040, false);
            g.text("Left", left + 8, top + 6, 0x404040, false);
            g.text("Right", left + xSize - 8 - g.textWidth("Right"), top + 6, 0x404040, false);
            g.text("Inventory", left + 8, top + 73, 0x404040, false);
        }

        @Override protected void drawForeground(GuiRenderer g, int mx, int my) {
            Slot hover = slotAt(mx, my);
            if (hover != null && hover.index >= PlayerInventory.POUCH && hover.get() == null && inv.cursor == null)
                g.tooltip(List.of("Pouch", "§7Gun magazines, up to " + PlayerInventory.POUCH_MAX), mx, my);
        }

        @Override public void keyPressed(int key, int mods) {
            if (derpcraft.game.Keys.EQUIPMENT.matches(key)) { onClose(); return; }
            super.keyPressed(key, mods);
        }
    }

    // ============================================================================ crafting table
    public static final class Crafting extends Base {
        private final Craft craft = new Craft(3, 3);

        public Crafting(DerpCraft g, Session s) { super(g, s); title = "Crafting"; }

        @Override protected Texture2D background() { return game.assets.gui.get("crafting_table"); }

        @Override protected void buildSlots() {
            for (int r = 0; r < 3; r++) for (int c = 0; c < 3; c++) slots.add(new Slot(craft.grid, r * 3 + c, 30 + c * 18, 17 + r * 18));
            slots.add(craft.resultSlot(124, 35));
            addPlayerSlots(8, 84);
        }

        @Override protected List<Slot> containerSlots(ItemStack st) { return List.of(); }

        @Override protected void drawLabels(GuiRenderer g) {
            g.text(title, left + 28, top + 6, 0x404040, false);
            g.text(invLabel, left + 8, top + ySize - 96 + 2, 0x404040, false);
        }

        @Override protected void returnItems() { craft.giveBack(s); }
    }

    // ============================================================================ chests
    public static final class Chest extends Base {
        private final Inventory items;
        private final ContainerEntity[] viewers;
        private final int rows;

        public Chest(DerpCraft g, Session s, String title, Inventory items, ContainerEntity... viewers) {
            super(g, s);
            this.title = title;
            this.items = items;
            this.viewers = viewers;
            this.rows = Math.max(1, items.size() / 9);
            ySize = 114 + rows * 18;
            for (ContainerEntity c : viewers) if (c != null) c.viewers++;
        }

        @Override protected Texture2D background() { return game.assets.gui.get("generic_54"); }

        @Override protected void buildSlots() {
            for (int r = 0; r < rows; r++) for (int c = 0; c < 9; c++) slots.add(new Slot(items, r * 9 + c, 8 + c * 18, 18 + r * 18));
            addPlayerSlots(8, 18 * rows + 31);
        }

        @Override protected void drawBackground(GuiRenderer g, int mx, int my, float partial) {
            Texture2D t = background();
            g.blit(t, left, top, 0, 0, xSize, rows * 18 + 17, 0xFFFFFFFF);
            g.blit(t, left, top + rows * 18 + 17, 0, 126, xSize, 96, 0xFFFFFFFF);
        }

        @Override public void onClose() {
            for (ContainerEntity c : viewers) if (c != null) c.viewers = Math.max(0, c.viewers - 1);
            derpcraft.audio.Sfx.play(Session.chestSound(title) + "/close", 0.8f, 0.95f);
            super.onClose();
        }
    }

    public static final class Dispenser extends Base {
        private final ContainerEntity c;

        public Dispenser(DerpCraft g, Session s, String title, ContainerEntity c) { super(g, s); this.c = c; this.title = title; }

        @Override protected Texture2D background() { return game.assets.gui.get("dispenser"); }

        @Override protected void buildSlots() {
            for (int r = 0; r < 3; r++) for (int col = 0; col < 3; col++) slots.add(new Slot(c.items, r * 3 + col, 62 + col * 18, 17 + r * 18));
            addPlayerSlots(8, 84);
        }

        @Override protected void drawLabels(GuiRenderer g) {
            g.text(title, left + xSize / 2f - g.textWidth(title) / 2f, top + 6, 0x404040, false);
            g.text(invLabel, left + 8, top + ySize - 96 + 2, 0x404040, false);
        }
    }

    public static final class Hopper extends Base {
        private final ContainerEntity c;

        public Hopper(DerpCraft g, Session s, ContainerEntity c) { super(g, s); this.c = c; title = "Item Hopper"; ySize = 133; }

        @Override protected Texture2D background() { return game.assets.gui.get("hopper"); }

        @Override protected void buildSlots() {
            for (int col = 0; col < 5; col++) slots.add(new Slot(c.items, col, 44 + col * 18, 20));
            addPlayerSlots(8, 51);
        }
    }

    // ============================================================================ furnace
    public static final class Furnace extends Base {
        private final FurnaceEntity f;

        public Furnace(DerpCraft g, Session s, FurnaceEntity f) { super(g, s); this.f = f; title = "Furnace"; }

        @Override protected Texture2D background() { return game.assets.gui.get("furnace"); }

        @Override protected void buildSlots() {
            slots.add(new Slot(f.items, 0, 56, 17));
            Slot fuel = new Slot(f.items, 1, 56, 53);
            fuel.filter = st -> st.item.fuel > 0 || st.item.name.equals("bucket");
            slots.add(fuel);
            Slot out = new Slot(f.items, 2, 116, 35) {
                @Override public void onTake(ItemStack taken) {
                    int xp = (int) f.xp;
                    if (xp > 0) { s.stats.addXp(xp); f.xp -= xp; }
                }
            };
            out.output = true;
            slots.add(out);
            addPlayerSlots(8, 84);
        }

        @Override protected boolean repeatOutput() { return false; }

        @Override protected List<Slot> containerSlots(ItemStack st) {
            if (Recipes.smelt(st.item) != null) return List.of(slots.get(0));
            if (st.item.fuel > 0) return List.of(slots.get(1));
            return List.of();
        }

        @Override protected void drawBackground(GuiRenderer g, int mx, int my, float partial) {
            super.drawBackground(g, mx, my, partial);
            Texture2D t = background();
            if (f.burnTime > 0) {
                int k = f.burnTime * 13 / Math.max(1, f.burnTotal);
                g.blit(t, left + 56, top + 36 + 12 - k, 176, 12 - k, 14, k + 1, 0xFFFFFFFF);
            }
            int l = f.cookTime * 24 / FurnaceEntity.COOK_TIME;
            if (l > 0) g.blit(t, left + 79, top + 34, 176, 14, l + 1, 16, 0xFFFFFFFF);
        }

        @Override protected void drawLabels(GuiRenderer g) {
            g.text(title, left + xSize / 2f - g.textWidth(title) / 2f, top + 6, 0x404040, false);
            g.text(invLabel, left + 8, top + ySize - 96 + 2, 0x404040, false);
        }
    }

    // ============================================================================ creative inventory
    public static final class Creative extends Base {
        /** Vanilla 1.12 tab order: index -> {id, title, icon item}. Column = index % 6, first row = index < 6. */
        static final String[][] TABS = {
                {"building", "Building Blocks", "bricks"}, {"decoration", "Decoration Blocks", "rose_bush"},
                {"redstone", "Redstone", "redstone"}, {"transport", "Transportation", "golden_rail"},
                {"misc", "Miscellaneous", "lava_bucket"}, {"search", "Search Items", "compass"},
                {"food", "Foodstuffs", "apple"}, {"tools", "Tools", "iron_axe"}, {"combat", "Combat", "golden_sword"},
                {"brewing", "Brewing", "potion"}, {"hotbar", "Saved Toolbars", "bookshelf"}, {"inventory", "Survival Inventory", "chest"}};
        private static int selectedTab = 0;
        private static final Set<String> KNOWN_TABS = new HashSet<>();
        static { for (String[] t : TABS) KNOWN_TABS.add(t[0]); }
        private final Inventory palette = new Inventory(45);
        private List<Item> items = new ArrayList<>();
        private float scroll;
        private boolean draggingScroll;
        private Widget.TextField search;
        private String searchText = "";

        public Creative(DerpCraft g, Session s) { super(g, s); xSize = 195; ySize = 136; }

        @Override protected Texture2D background() {
            String id = TABS[selectedTab][0];
            return game.assets.gui.get(id.equals("search") ? "creative_inventory/tab_item_search"
                    : id.equals("inventory") ? "creative_inventory/tab_inventory" : "creative_inventory/tab_items");
        }

        @Override public void init() {
            super.init();
            search = add(new Widget.TextField(left + 82, top + 6, 89, 9) {
                @Override public void render(GuiRenderer g, int mx, int my) {
                    if (!visible) return;
                    String t = text;
                    while (g.textWidth(t) > w - 2 && !t.isEmpty()) t = t.substring(1);
                    int end = g.text(t, x, y, 0xFFFFFF, true);
                    if (focused && (System.currentTimeMillis() / 300) % 2 == 0) g.text("_", x + end, y, 0xFFFFFF, true);
                }
            });
            search.text = searchText;
            search.maxLength = 50;
            search.onChange = () -> { searchText = search.text; refresh(); };
            setTab(selectedTab);
        }

        private boolean isTab(String id) { return TABS[selectedTab][0].equals(id); }

        private void setTab(int t) {
            boolean wasSearch = isTab("search");
            selectedTab = t;
            scroll = 0;
            // entering the search tab again starts with an empty search field
            if (isTab("search") && !wasSearch) { searchText = ""; search.text = ""; }
            search.visible = isTab("search");
            search.focused = isTab("search");
            slots.clear();
            if (isTab("inventory")) {
                armorSlots(slots, inv, new int[][]{{54, 6}, {54, 33}, {108, 6}, {108, 33}});
                for (int r = 0; r < 3; r++) for (int c = 0; c < 9; c++) { Slot sl = new Slot(inv, 9 + r * 9 + c, 9 + c * 18, 54 + r * 18); sl.player = true; slots.add(sl); }
            } else {
                for (int r = 0; r < 5; r++)
                    for (int c = 0; c < 9; c++) {
                        Slot sl = new Slot(palette, r * 9 + c, 9 + c * 18, 18 + r * 18);
                        sl.output = true;
                        slots.add(sl);
                    }
            }
            for (int c = 0; c < 9; c++) { Slot sl = new Slot(inv, c, 9 + c * 18, 112); sl.player = true; slots.add(sl); }
            refresh();
        }

        @Override protected void buildSlots() {}

        private void refresh() {
            items = new ArrayList<>();
            String id = TABS[selectedTab][0];
            String q = searchText.toLowerCase(Locale.ROOT);
            for (Item it : Items.all()) {
                if (!it.inCreative) continue;
                String tab = it.tab.equals("materials") ? "misc" : it.tab;
                if (!KNOWN_TABS.contains(tab)) tab = "misc";   // mod tabs that don't exist land in Miscellaneous
                if (id.equals("search") ? (q.isEmpty() || it.displayName.toLowerCase(Locale.ROOT).contains(q) || it.name.contains(q)) : tab.equals(id))
                    items.add(it);
            }
            fillPalette();
        }

        private int rowsTotal() { return (items.size() + 8) / 9; }

        private void fillPalette() {
            int first = Math.max(0, Math.round(scroll * Math.max(0, rowsTotal() - 5))) * 9;
            for (int i = 0; i < 45; i++) {
                int k = first + i;
                palette.set(i, k < items.size() ? new ItemStack(items.get(k), 1) : null);
            }
        }

        private int[] tabPos(int i) {
            int col = i % 6;
            int x = left + 28 * col;
            if (col == 5) x = left + xSize - 28;
            else if (col > 0) x += col;
            int y = i < 6 ? top - 28 : top + ySize - 4;
            return new int[]{x, y};
        }

        @Override protected void drawBackground(GuiRenderer g, int mx, int my, float partial) {
            Texture2D tabs = game.assets.gui.get("creative_inventory/tabs");
            for (int i = 0; i < TABS.length; i++) if (i != selectedTab) drawTab(g, tabs, i, false);
            g.blit(background(), left, top, 0, 0, xSize, ySize, 0xFFFFFFFF);
            if (!isTab("inventory")) {
                boolean can = rowsTotal() > 5;
                g.blit(tabs, left + 175, top + 18 + (int) ((112 - 17) * scroll), can ? 232 : 244, 0, 12, 15, 0xFFFFFFFF);
            }
            drawTab(g, tabs, selectedTab, true);
            if (isTab("inventory")) g.playerModel(game.assets.steve, left + 88, top + 45, 20, 0, 0, 0);
        }

        private void drawTab(GuiRenderer g, Texture2D tabs, int i, boolean sel) {
            int[] p = tabPos(i);
            int col = i % 6, v = (sel ? 32 : 0) + (i < 6 ? 0 : 64);
            g.blit(tabs, p[0], p[1], col * 28, v, 28, 32, 0xFFFFFFFF);
            Item icon = Items.byName(TABS[i][2]);
            if (icon == null) icon = Items.byName("stone");
            g.itemIcon(icon, p[0] + 6, p[1] + 8 + (i < 6 ? 1 : -1), 16);
        }

        @Override protected void drawLabels(GuiRenderer g) {
            if (!isTab("search") && !isTab("inventory")) g.text(TABS[selectedTab][1], left + 8, top + 6, 0x404040, false);
        }

        @Override public void render(GuiRenderer g, int mx, int my, float partial) {
            super.render(g, mx, my, partial);
            search.render(g, mx, my);
            if (inv.cursor == null)
                for (int i = 0; i < TABS.length; i++) {
                    int[] p = tabPos(i);
                    if (mx >= p[0] && mx < p[0] + 28 && my >= p[1] + 3 && my < p[1] + 29) g.tooltip(List.of(TABS[i][1]), mx, my);
                }
            if (isTab("inventory") && mx >= left + 173 && mx < left + 189 && my >= top + 112 && my < top + 128 && inv.cursor == null)
                g.tooltip(List.of("Destroy Item"), mx, my);
        }

        @Override protected void drawForeground(GuiRenderer g, int mx, int my) {}

        @Override protected boolean clickOutside(int mx, int my, int button) {
            for (int i = 0; i < TABS.length; i++) {
                int[] p = tabPos(i);
                if (mx >= p[0] && mx < p[0] + 28 && my >= p[1] + 3 && my < p[1] + 29) {
                    if (i != selectedTab) setTab(i);
                    else if (isTab("search") && !searchText.isEmpty()) { searchText = ""; search.text = ""; search.focused = true; scroll = 0; refresh(); }   // clicking the open search tab clears the text
                    return true;
                }
            }
            if (!isTab("inventory") && mx >= left + 175 && mx < left + 189 && my >= top + 18 && my < top + 130) {
                draggingScroll = true;
                dragScroll(my);
                return true;
            }
            if (isTab("inventory") && mx >= left + 173 && mx < left + 189 && my >= top + 112 && my < top + 128) {
                boolean shift = glfwGetKey(game.window, GLFW_KEY_LEFT_SHIFT) == GLFW_PRESS;
                if (shift) for (int i = 0; i < inv.size(); i++) inv.set(i, null);
                inv.cursor = null;
                return true;
            }
            if (search.visible && search.contains(mx, my)) { search.focused = true; return true; }
            return !outside(mx, my);
        }

        private void dragScroll(int my) {
            scroll = Math.max(0, Math.min(1, (my - top - 18 - 7.5f) / (112 - 15f)));
            fillPalette();
        }

        @Override public void mouseDragged(int mx, int my, int button) {
            if (draggingScroll) { dragScroll(my); return; }
            super.mouseDragged(mx, my, button);
        }

        @Override public void mouseReleased(int mx, int my, int button) { draggingScroll = false; super.mouseReleased(mx, my, button); }

        @Override public void scrolled(double amount) {
            int extra = rowsTotal() - 5;
            if (extra <= 0) return;
            scroll = Math.max(0, Math.min(1, scroll - (float) (amount / extra)));
            fillPalette();
        }

        @Override protected void click(Slot sl, int button) {
            if (sl.inv == palette) {
                ItemStack p = sl.get();
                if (inv.cursor != null) {
                    if (p != null && inv.cursor.item == p.item && inv.cursor.count < inv.cursor.maxStack()) inv.cursor.count++;
                    else inv.cursor = null; // clicking the palette deletes the held item
                    return;
                }
                if (p == null) return;
                inv.cursor = new ItemStack(p.item, button == 1 ? 1 : p.item.maxStack);
                return;
            }
            super.click(sl, button);
        }

        @Override protected void quickMove(Slot sl) {
            if (sl.inv == palette) {
                ItemStack p = sl.get();
                if (p != null) merge(new ItemStack(p.item, p.item.maxStack), slots.stream().filter(o -> o.player && o.index < 9).toList(), false);
                return;
            }
            if (!isTab("inventory") && sl.player) { sl.set(null); return; } // vanilla: shift-click on the hotbar clears it
            super.quickMove(sl);
        }

        @Override public void keyPressed(int key, int mods) {
            if (search.visible && search.focused) {
                if (key == GLFW_KEY_ESCAPE) { onClose(); return; }
                search.key(key, mods);
                return;
            }
            super.keyPressed(key, mods);
        }

        @Override public void charTyped(int cp) {
            if (!isTab("search")) {
                if (cp == 'e' || cp == 'E' || cp >= '1' && cp <= '9') return;
                setTab(5);
                search.text = "";
            }
            search.focused = true;
            search.typed(cp);
        }

        @Override protected void returnItems() {}
    }

    // ============================================================================ sign editor
    public static final class SignEdit extends Screen {
        private final SignEntity sign;
        private int line, ticks;

        public SignEdit(DerpCraft g, SignEntity sign) { super(g); this.sign = sign; pausesGame = false; }

        @Override public void init() {
            add(new Widget.Button(width / 2 - 100, height / 4 + 120, 200, 20, "Done", this::onClose));
        }

        @Override public void tick() { super.tick(); ticks++; }

        @Override public void render(GuiRenderer g, int mx, int my, float partial) {
            g.gradient(0, 0, width, height, 0xC0101010, 0xD0101010);
            title(g, "Edit sign message:", 40);
            // front of the sign board (ModelSign: 24x12 face at u=2, v=2 of a 64x32 texture)
            Texture2D t = game.assets.sign;
            int bw = 96, bh = 48, bx = width / 2 - bw / 2, by = 60;
            g.blitStretch(t, bx, by, bw, bh, 2 / 64f, 2 / 32f, 26 / 64f, 14 / 32f, 0xFFFFFFFF);
            g.blitStretch(t, width / 2f - 4, by + bh, 8, 56, 2 / 64f, 16 / 32f, 4 / 64f, 30 / 32f, 0xFFFFFFFF);
            for (int i = 0; i < 4; i++) {
                String l = sign.lines[i];
                if (i == line && (ticks / 6) % 2 == 0) l = "> " + l + " <";
                g.text(l, width / 2f - g.textWidth(l) / 2f, by + 5 + i * 10, 0x000000, false);
            }
            super.render(g, mx, my, partial);
        }

        @Override public void keyPressed(int key, int mods) {
            switch (key) {
                case GLFW_KEY_ESCAPE -> onClose();
                case GLFW_KEY_UP -> line = (line + 3) % 4;
                case GLFW_KEY_DOWN, GLFW_KEY_ENTER, GLFW_KEY_KP_ENTER, GLFW_KEY_TAB -> line = (line + 1) % 4;
                case GLFW_KEY_BACKSPACE -> { String l = sign.lines[line]; if (!l.isEmpty()) sign.lines[line] = l.substring(0, l.length() - 1); }
                default -> {}
            }
        }

        @Override public void charTyped(int cp) {
            String l = sign.lines[line] + new String(Character.toChars(cp));
            if (cp >= 32 && game.gui.textWidth(l) <= 90) sign.lines[line] = l;
        }

        @Override public void onClose() {
            sign.markDirty();
            game.setScreen(null);
        }
    }

    // ============================================================================ vanilla-style drawing helpers
    static final int BG = 0xFFC6C6C6, LIGHT = 0xFFFFFFFF, DARK = 0xFF555555, SLOT_DARK = 0xFF373737, SLOT = 0xFF8B8B8B;

    /** Vanilla container panel (black outline, cut corners, bevel). */
    public static void panel(GuiRenderer g, int x, int y, int w, int h) {
        g.fill(x + 2, y, x + w - 2, y + h, 0xFF000000);
        g.fill(x, y + 2, x + w, y + h - 2, 0xFF000000);
        g.fill(x + 1, y + 1, x + w - 1, y + h - 1, 0xFF000000);
        g.fill(x + 2, y + 1, x + w - 2, y + h - 1, BG);
        g.fill(x + 1, y + 2, x + w - 1, y + h - 2, BG);
        g.fill(x + 2, y + 1, x + w - 3, y + 3, LIGHT); g.fill(x + 1, y + 2, x + 3, y + h - 3, LIGHT); g.fill(x + 3, y + 3, x + 4, y + 4, LIGHT);
        g.fill(x + 3, y + h - 3, x + w - 2, y + h - 1, DARK); g.fill(x + w - 3, y + 3, x + w - 1, y + h - 2, DARK); g.fill(x + w - 4, y + h - 4, x + w - 3, y + h - 3, DARK);
    }

    /** Vanilla slot frame for an item drawn at (x, y). */
    public static void slotFrame(GuiRenderer g, int x, int y) {
        g.fill(x - 1, y - 1, x + 17, y + 17, SLOT_DARK);
        g.fill(x, y, x + 17, y + 17, LIGHT);
        g.fill(x, y, x + 16, y + 16, SLOT);
        g.fill(x + 16, y - 1, x + 17, y, SLOT); g.fill(x - 1, y + 16, x, y + 17, SLOT);
    }

    static void playerFrames(GuiRenderer g, int x, int y) {
        for (int r = 0; r < 3; r++) for (int c = 0; c < 9; c++) slotFrame(g, x + c * 18, y + r * 18);
        for (int c = 0; c < 9; c++) slotFrame(g, x + c * 18, y + 58);
    }

    // ============================================================================ mod storage (any size)
    /**
     * Storage of 1..54 slots with an automatic layout: one row up to 9 slots, otherwise balanced rows (12 = 2x6,
     * 30 = 4x8). Multiples of 9 use the resource pack's chest texture, other sizes are drawn in the same style.
     */
    public static final class Storage extends Base {
        private final derpcraft.world.be.ModBlockEntity be;
        private final int n, rows, cols;

        public Storage(DerpCraft g, Session s, String title, derpcraft.world.be.ModBlockEntity be) {
            super(g, s);
            this.be = be;
            this.title = title;
            this.n = be.items.size();
            rows = (n + 8) / 9;
            cols = (n + rows - 1) / rows;
            ySize = 114 + rows * 18;
            be.viewers++;
        }

        @Override protected Texture2D background() { return game.assets.gui.get("generic_54"); }

        private boolean vanilla() { return cols == 9 && n % 9 == 0; }

        private int slotX(int i) {
            int r = i / cols, inRow = Math.min(cols, n - r * cols);
            return (xSize - inRow * 18) / 2 + 1 + (i % cols) * 18;
        }

        @Override protected void buildSlots() {
            for (int i = 0; i < n; i++) {
                Slot sl = new Slot(be.items, i, slotX(i), 18 + (i / cols) * 18);
                if (be.spec.slotFilter != null) { final int idx = i; sl.filter = st -> be.spec.slotFilter.accepts(idx, st); }
                slots.add(sl);
            }
            addPlayerSlots(8, 18 * rows + 31);
        }

        @Override protected void drawBackground(GuiRenderer g, int mx, int my, float partial) {
            if (vanilla()) {
                Texture2D t = background();
                g.blit(t, left, top, 0, 0, xSize, rows * 18 + 17, 0xFFFFFFFF);
                g.blit(t, left, top + rows * 18 + 17, 0, 126, xSize, 96, 0xFFFFFFFF);
                return;
            }
            panel(g, left, top, xSize, ySize);
            for (int i = 0; i < n; i++) slotFrame(g, left + slotX(i), top + 18 + (i / cols) * 18);
            playerFrames(g, left + 8, top + 18 * rows + 31);
        }

        @Override public void onClose() {
            be.viewers = Math.max(0, be.viewers - 1);
            super.onClose();
        }
    }

    // ============================================================================ mod machine (furnace-like)
    public static final class Machine extends Base {
        private final derpcraft.world.be.ModBlockEntity m;

        public Machine(DerpCraft g, Session s, String title, derpcraft.world.be.ModBlockEntity m) { super(g, s); this.m = m; this.title = title; m.viewers++; }

        @Override protected Texture2D background() { return game.assets.gui.get("furnace"); }

        private boolean vanillaLayout() { return m.spec.machineInputs == 1 && m.spec.machineFuel; }

        @Override protected void buildSlots() {
            String type = m.spec.machineType;
            if (m.spec.machineInputs == 1) {
                Slot in = new Slot(m.items, 0, 56, 17);
                in.filter = st -> derpcraft.item.MachineRecipes.isInput(type, st.item, 0);
                slots.add(in);
            } else {
                Slot a = new Slot(m.items, 0, 47, 17), b = new Slot(m.items, 1, 65, 17);
                a.filter = st -> derpcraft.item.MachineRecipes.isInput(type, st.item, 0);
                b.filter = st -> derpcraft.item.MachineRecipes.isInput(type, st.item, 1);
                slots.add(a); slots.add(b);
            }
            if (m.spec.machineFuel) {
                Slot fuel = new Slot(m.items, m.fuelSlot(), 56, 53);
                fuel.filter = st -> st.item.fuel > 0;
                slots.add(fuel);
            }
            Slot out = new Slot(m.items, m.outputSlot(), 116, 35) {
                @Override public void onTake(ItemStack taken) {
                    int xp = (int) m.xp;
                    if (xp > 0) { s.stats.addXp(xp); m.xp -= xp; }
                }
            };
            out.output = true;
            slots.add(out);
            addPlayerSlots(8, 84);
        }

        @Override protected boolean repeatOutput() { return false; }

        @Override protected List<Slot> containerSlots(ItemStack st) {
            List<Slot> l = new ArrayList<>();
            for (Slot o : slots) if (!o.player && !o.output && o.accepts(st)) l.add(o);
            return l;
        }

        @Override protected void drawBackground(GuiRenderer g, int mx, int my, float partial) {
            Texture2D t = background();
            if (vanillaLayout()) super.drawBackground(g, mx, my, partial);
            else {
                panel(g, left, top, xSize, ySize);
                for (Slot sl : slots) if (!sl.player) slotFrame(g, left + sl.x, top + sl.y);
                playerFrames(g, left + 8, top + 84);
                g.blit(t, left + 79, top + 34, 79, 34, 24, 17, 0xFFFFFFFF);
                if (m.spec.machineFuel) g.blit(t, left + 56, top + 36, 56, 36, 14, 14, 0xFFFFFFFF);
            }
            if (m.spec.machineFuel && m.burnTime > 0) {
                int k = m.burnTime * 13 / Math.max(1, m.burnTotal);
                g.blit(t, left + 56, top + 36 + 12 - k, 176, 12 - k, 14, k + 1, 0xFFFFFFFF);
            }
            int l = m.cookTime * 24 / Math.max(1, m.cookTotal > 0 ? m.cookTotal : m.spec.machineTicks);
            if (l > 0) g.blit(t, left + 79, top + 34, 176, 14, l + 1, 16, 0xFFFFFFFF);
        }

        @Override protected void drawLabels(GuiRenderer g) {
            g.text(title, left + xSize / 2f - g.textWidth(title) / 2f, top + 6, 0x404040, false);
            g.text(invLabel, left + 8, top + ySize - 96 + 2, 0x404040, false);
        }

        @Override public void onClose() {
            m.viewers = Math.max(0, m.viewers - 1);
            super.onClose();
        }
    }

    // ============================================================================ text editor (mod text blocks)
    public static final class TextEdit extends Screen {
        private final String[] lines;
        private final int maxWidth, color;
        private final Runnable done;
        private final String heading;
        private int line, ticks;

        public TextEdit(DerpCraft g, String heading, String[] lines, int maxWidth, int color, Runnable done) {
            super(g);
            this.heading = heading; this.lines = lines; this.maxWidth = maxWidth; this.color = color; this.done = done;
            pausesGame = false;
        }

        @Override public void init() { add(new Widget.Button(width / 2 - 100, Math.min(height - 24, 70 + lines.length * 10 + 40), 200, 20, "Done", this::onClose)); }

        @Override public void tick() { super.tick(); ticks++; }

        @Override public void render(GuiRenderer g, int mx, int my, float partial) {
            g.gradient(0, 0, width, height, 0xC0101010, 0xD0101010);
            title(g, heading, 40);
            int bw = Math.max(110, maxWidth + 16), bh = lines.length * 10 + 10, bx = width / 2 - bw / 2, by = 60;
            int lum = ((color >> 16 & 255) * 3 + (color >> 8 & 255) * 6 + (color & 255)) / 10;
            g.fill(bx - 1, by - 1, bx + bw + 1, by + bh + 1, 0xFF000000);
            g.fill(bx, by, bx + bw, by + bh, lum > 128 ? 0xFF303038 : 0xFFE8E0C8);
            for (int i = 0; i < lines.length; i++) {
                String l = lines[i];
                if (i == line && (ticks / 6) % 2 == 0) l = "> " + l + " <";
                g.text(l, width / 2f - g.textWidth(l) / 2f, by + 5 + i * 10, color, false);
            }
            super.render(g, mx, my, partial);
        }

        @Override public void keyPressed(int key, int mods) {
            int n = lines.length;
            switch (key) {
                case GLFW_KEY_ESCAPE -> onClose();
                case GLFW_KEY_UP -> line = (line + n - 1) % n;
                case GLFW_KEY_DOWN, GLFW_KEY_ENTER, GLFW_KEY_KP_ENTER, GLFW_KEY_TAB -> line = (line + 1) % n;
                case GLFW_KEY_BACKSPACE -> { String l = lines[line]; if (!l.isEmpty()) lines[line] = l.substring(0, l.length() - 1); }
                default -> {}
            }
        }

        @Override public void charTyped(int cp) {
            String l = lines[line] + new String(Character.toChars(cp));
            if (cp >= 32 && cp < 256 && game.gui.textWidth(l) <= maxWidth) lines[line] = l;
        }

        @Override public void onClose() {
            if (done != null) done.run();
            game.setScreen(null);
        }
    }

    // ============================================================================ custom menus (Menu API)
    /** Engine inventory over a mod's {@link derpcraft.api.InventoryApi}. */
    static final class ApiInventory extends Inventory {
        private final derpcraft.api.InventoryApi api;
        ApiInventory(derpcraft.api.InventoryApi api) { super(0); this.api = api; }
        @Override public int size() { return api.size(); }
        @Override public ItemStack get(int i) { return api.get(i); }
        @Override public void set(int i, ItemStack st) { api.set(i, st); }
        @Override public void changed() {}
    }

    static Inventory engine(derpcraft.api.InventoryApi api) {
        return api instanceof derpcraft.game.InventoryView v ? v.engine() : new ApiInventory(api);
    }

    public static final class MenuScreen extends Base {
        private final derpcraft.api.Menu menu;

        public MenuScreen(DerpCraft g, Session s, derpcraft.api.Menu menu) {
            super(g, s);
            this.menu = menu;
            this.title = menu.title;
            xSize = menu.width; ySize = menu.height;
            if (menu.playerX < 0) invLabel = null;
        }

        @Override protected Texture2D background() { return game.assets.gui.get("furnace"); }

        @Override public void init() {
            super.init();
            for (var b : menu.buttons) add(new Widget.Button(left + b.x(), top + b.y(), b.w(), b.h(), b.label(), () -> Session.safe(b.action())));
        }

        @Override protected void buildSlots() {
            for (var d : menu.slots) {
                Slot sl = new Slot(engine(d.inv()), d.index(), d.x(), d.y());
                sl.output = (d.flags() & derpcraft.api.Menu.OUTPUT) != 0;
                if (d.filter() != null) sl.filter = d.filter();
                slots.add(sl);
            }
            if (menu.playerX >= 0) addPlayerSlots(menu.playerX, menu.playerY);
        }

        @Override protected void drawBackground(GuiRenderer g, int mx, int my, float partial) {
            panel(g, left, top, xSize, ySize);
            for (Slot sl : slots) if (!sl.player) slotFrame(g, left + sl.x, top + sl.y);
            if (menu.playerX >= 0) playerFrames(g, left + menu.playerX, top + menu.playerY);
            Texture2D t = background();
            for (var p : menu.bars) {
                float v = 0;
                try { Float f = p.value().get(); v = f == null ? 0 : Math.max(0, Math.min(1, f)); } catch (Throwable ignored) {}
                int x = left + p.x(), y = top + p.y();
                switch (p.kind()) {
                    case derpcraft.api.Menu.ARROW -> {
                        g.blit(t, x, y, 79, 34, 24, 17, 0xFFFFFFFF);
                        int l = Math.round(v * 24);
                        if (l > 0) g.blit(t, x, y, 176, 14, l, 16, 0xFFFFFFFF);
                    }
                    case derpcraft.api.Menu.FLAME -> {
                        g.blit(t, x, y, 56, 36, 14, 14, 0xFFFFFFFF);
                        int k = Math.round(v * 13);
                        if (k > 0) g.blit(t, x, y + 13 - k, 176, 13 - k, 14, k + 1, 0xFFFFFFFF);
                    }
                    default -> {
                        g.fill(x, y, x + p.w(), y + 5, 0xFF373737);
                        g.fill(x + 1, y + 1, x + 1 + Math.round(v * (p.w() - 2)), y + 4, p.color());
                    }
                }
            }
        }

        @Override protected void drawLabels(GuiRenderer g) {
            g.text(title, left + 8, top + 6, 0x404040, false);
            if (invLabel != null && menu.playerX >= 0) g.text(invLabel, left + menu.playerX, top + menu.playerY - 11, 0x404040, false);
            for (var l : menu.labels) {
                String txt;
                try { txt = l.text().get(); } catch (Throwable e) { txt = "?"; }
                if (txt == null) continue;
                float x = l.centered() ? left + l.x() - g.textWidth(txt) / 2f : left + l.x();
                g.text(txt, x, top + l.y(), l.color(), false);
            }
        }

        @Override public void render(GuiRenderer g, int mx, int my, float partial) {
            super.render(g, mx, my, partial);
            for (Widget w : widgets) w.render(g, mx, my);
        }

        @Override protected boolean clickOutside(int mx, int my, int button) {
            for (Widget w : widgets) if (w.visible && w.contains(mx, my) && w.click(mx, my, button)) return true;
            return false;
        }

        @Override public void tick() {
            super.tick();
            if (menu.onTick != null) Session.safe(menu.onTick);
        }

        @Override public void onClose() {
            if (menu.onClose != null) Session.safe(menu.onClose);
            super.onClose();
        }
    }

    // ============================================================================ death screen
    public static final class Death extends Screen {
        private final Session s;
        private int ticks;
        private Widget.Button respawn, title;

        public Death(DerpCraft g, Session s) { super(g); this.s = s; pausesGame = false; }

        @Override public void init() {
            respawn = add(new Widget.Button(width / 2 - 100, height / 4 + 72, 200, 20, "Respawn", s::respawn));
            title = add(new Widget.Button(width / 2 - 100, height / 4 + 96, 200, 20, "Title screen", game::closeWorld));
            respawn.active = title.active = ticks >= 20;
        }

        @Override public void tick() { super.tick(); if (++ticks >= 20) respawn.active = title.active = true; }

        @Override public void render(GuiRenderer g, int mx, int my, float partial) {
            g.gradient(0, 0, width, height, 0x60500000, 0xA0803030);
            g.textRotated("You died!", width / 2f, 60 / 2f * 2 + 4, 0, 2, 0xFFFFFFFF);
            g.centered(s.stats.deathMessage, width / 2f, 85, 0xFFFFFF);
            g.centered("Score: §e" + s.stats.xpTotal, width / 2f, 100, 0xFFFFFF);
            super.render(g, mx, my, partial);
        }

        @Override public void keyPressed(int key, int mods) {}

        @Override public void onClose() {}
    }
}
