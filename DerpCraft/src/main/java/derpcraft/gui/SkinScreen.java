package derpcraft.gui;

import derpcraft.DerpCraft;
import derpcraft.game.Keys;
import derpcraft.game.Session;
import derpcraft.npc.Skins;
import derpcraft.render.GuiRenderer;
import derpcraft.render.Texture2D;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The player's skin (Left Alt + F9): the same list of skins the NPCs use (Steve, the built-in outfits and every PNG in
 * game/skins/), a click puts one on at once. It is the skin of the player model (third person, the shadow, the
 * selfie), of the hand in the first person and of the arms that hold the guns and fight with the fists; slim (Alex)
 * skins get slim arms everywhere. Saved in options.txt (playerSkin), the same in every world.
 * Laid out like the other hubs: the list on the left, a big front and back picture of the skin on the right (the one
 * under the mouse, else the one worn), the world behind keeps running (switch the view to see the model).
 */
public final class SkinScreen extends Screen {
    private final Session s;
    private static final int PW = 196, ROW = 16;
    private static int scroll;
    private static String filter = "";
    private Widget.TextField search;
    private int rows, total;
    private String hovered;
    private final List<String> shown = new ArrayList<>();

    public SkinScreen(DerpCraft g, Session s) {
        super(g);
        this.s = s;
        pausesGame = false;              // the model keeps moving: switch the view to see the skin on it
    }

    @Override public boolean recorded() { return false; }

    private Skins skins() { return s.npcs == null ? null : s.npcs.skins; }

    @Override public void init() {
        int x = 4, w = PW - 8, y = 18;
        search = add(new Widget.TextField(x, y, w, ROW));
        search.text = filter;
        search.hint = "Search...";
        search.onChange = () -> { filter = search.text; scroll = 0; game.relayout(); search.focused = true; };
        y += ROW + 4;
        shown.clear();
        Skins sk = skins();
        if (sk != null) for (String n : sk.names()) if (filter.isBlank() || n.toLowerCase(Locale.ROOT).contains(filter.toLowerCase(Locale.ROOT).trim())) shown.add(n);
        int bottom = height - 2 * (ROW + 2) - 6;
        rows = Math.max(3, (bottom - y) / (ROW + 2));
        total = shown.size();
        scroll = Math.max(0, Math.min(scroll, total - rows));
        for (int i = scroll; i < total && i < scroll + rows; i++) {
            String n = shown.get(i);
            final int ry = y;
            add(new Widget.Button(x + 16, ry, w - 16, ROW, () -> (n.equals(current()) ? "§a" : "") + n + (slim(n) ? " §7(slim)" : ""), () -> wear(n)) {
                @Override public void render(GuiRenderer g, int mx, int my) {
                    super.render(g, mx, my);
                    if (contains(mx, my)) hovered = n;
                }
            });
            y += ROW + 2;
        }
        int by = height - 2 * (ROW + 2) - 2, bw = (w - 2) / 2;
        add(new Widget.Button(x, by, bw, ROW, () -> s.view == 0 ? "View: 1st person" : s.view == 1 ? "View: 3rd person" : "View: 3rd front", s::cycleView));
        add(new Widget.Button(x + bw + 2, by, w - bw - 2, ROW, "Open skins folder", () -> game.openFolder(game.gameDir.resolve("skins"))));
        by += ROW + 2;
        add(new Widget.Button(x, by, bw, ROW, "Reload list", () -> { Skins k = skins(); if (k != null) k.refresh(true); game.relayout(); }));
        add(new Widget.Button(x + bw + 2, by, w - bw - 2, ROW, "Done", this::onClose));
    }

    private String current() {
        Skins.Skin k = s.playerSkin();
        return k == null ? "Steve" : k.name();
    }

    private boolean slim(String n) { Skins k = skins(); return k != null && k.get(n).slim(); }

    /** Puts a skin on (at once: model, hand, gun and fist arms) and remembers it. */
    private void wear(String n) {
        game.settings.playerSkin = n;
        game.settings.save();
    }

    @Override public void scrolled(double amount) {
        int old = scroll;
        scroll = Math.max(0, Math.min(scroll - (int) Math.signum(amount) * 3, Math.max(0, total - rows)));
        if (scroll != old) game.relayout();
    }

    @Override public void keyPressed(int key, int mods) {
        if (!(search != null && search.focused) && Keys.SKINS.matches(key)) { onClose(); return; }
        super.keyPressed(key, mods);
    }

    @Override public void render(GuiRenderer g, int mx, int my, float partial) {
        hovered = null;
        g.fill(0, 0, PW, height, 0xB0000000);
        g.text("Player skin", 4, 5, 0xFFFFFF, true);
        String hint = Keys.n(Keys.SKINS) + ": close";
        g.text(hint, PW - g.textWidth(hint) - 4, 5, 0x808080, false);
        super.render(g, mx, my, partial);
        Skins sk = skins();
        if (sk == null) return;
        // the face of each skin in the list
        int y = 18 + ROW + 4;
        for (int i = scroll; i < total && i < scroll + rows; i++) {
            face(g, sk.get(shown.get(i)).texture(), 6, y + 2, 12);
            y += ROW + 2;
        }
        if (total > rows) g.text("§7wheel: scroll (" + (scroll + 1) + "-" + Math.min(total, scroll + rows) + " of " + total + ")", 6, height - 2 * (ROW + 2) - 14, 0xFFFFFF, false);
        if (total == 0) g.text("§7No skin matches", 8, 18 + ROW + 8, 0xFFFFFF, false);
        // the big picture: the skin under the mouse, else the one worn
        String n = hovered != null ? hovered : current();
        Skins.Skin k = sk.get(n);
        int scale = Math.max(2, Math.min(8, (height - 90) / 34));
        int px = PW + 16, py = 26;
        g.fill(px - 6, py - 18, px + 2 * 16 * scale + 22, py + 32 * scale + 26, 0x90000000);
        g.text((n.equals(current()) ? "§a" : "§e") + n + (k.slim() ? " §7(slim)" : "") + (n.equals(current()) ? " §7- worn" : " §7- click to wear"), px, py - 13, 0xFFFFFF, true);
        figure(g, k.texture(), k.slim(), px, py, scale, false);
        figure(g, k.texture(), k.slim(), px + 16 * scale + 12, py, scale, true);
        g.text("§7front", px + 8 * scale - 12, py + 32 * scale + 6, 0xFFFFFF, false);
        g.text("§7back", px + 16 * scale + 12 + 8 * scale - 10, py + 32 * scale + 6, 0xFFFFFF, false);
        int ty = py + 32 * scale + 32;
        String[] help = {"The same list as the NPC skins: Steve, the", "built-in outfits and every PNG in game\\skins\\",
                "(64x64 or old 64x32; slim arms are found", "by themselves). Worn by the player model,", "the hand, the gun arms and the fists.",
                "Open skins folder, drop a PNG in, Reload list."};
        for (String h : help) { g.text("§7" + h, px - 2, ty, 0xFFFFFF, true); ty += 10; }
    }

    /** The face (and the hat layer) of a skin, w x w GUI pixels. */
    private static void face(GuiRenderer g, Texture2D t, int x, int y, int w) {
        part(g, t, x, y, w / 8f, 8, 8, 8, 8, false);
        part(g, t, x, y, w / 8f, 40, 8, 8, 8, false);
    }

    /**
     * A flat picture of the whole player from the front or the back, 'sc' GUI pixels per skin pixel: head, body, arms
     * and legs with their outer layer (hat, jacket, sleeves, trousers). Old 64x32 skins: the left limbs are the right ones
     * mirrored, no outer layer but the hat.
     */
    private static void figure(GuiRenderer g, Texture2D t, boolean slim, int x, int y, int sc, boolean back) {
        boolean legacy = t.height < 64;
        int aw = slim ? 3 : 4;
        // x of each part in the picture (skin pixels): the right arm is on the left of the front picture
        int ax0 = 4 - aw, bx = 4, ax1 = 12, lx0 = 4, lx1 = 8;
        int[][] base, over;
        if (!back) {
            base = new int[][]{
                    {4, 0, 8, 8, 8, 8},                      // head front: picture x, y, u, v, w, h
                    {bx, 8, 20, 20, 8, 12},                   // body
                    {ax0, 8, 44, 20, aw, 12},                 // right arm
                    {ax1, 8, legacy ? 44 : 36, legacy ? 20 : 52, aw, 12},   // left arm
                    {lx0, 20, 4, 20, 4, 12},                  // right leg
                    {lx1, 20, legacy ? 4 : 20, legacy ? 20 : 52, 4, 12},    // left leg
            };
            over = new int[][]{{4, 0, 40, 8, 8, 8}, {bx, 8, 20, 36, 8, 12}, {ax0, 8, 44, 36, aw, 12}, {ax1, 8, 52, 52, aw, 12}, {lx0, 20, 4, 36, 4, 12}, {lx1, 20, 4, 52, 4, 12}};
        } else {
            // from behind the right arm and leg are on the right of the picture
            base = new int[][]{
                    {4, 0, 24, 8, 8, 8},
                    {bx, 8, 32, 20, 8, 12},
                    {ax1, 8, 44 + aw + 4, 20, aw, 12},
                    {ax0, 8, legacy ? 44 + aw + 4 : 36 + aw + 4, legacy ? 20 : 52, aw, 12},
                    {lx1, 20, 12, 20, 4, 12},
                    {lx0, 20, legacy ? 12 : 28, legacy ? 20 : 52, 4, 12},
            };
            over = new int[][]{{4, 0, 56, 8, 8, 8}, {bx, 8, 32, 36, 8, 12}, {ax1, 8, 44 + aw + 4, 36, aw, 12}, {ax0, 8, 52 + aw + 4, 52, aw, 12}, {lx1, 20, 12, 36, 4, 12}, {lx0, 20, 12, 52, 4, 12}};
        }
        for (int i = 0; i < base.length; i++) {
            int[] p = base[i];
            boolean mirror = legacy && (i == 3 || i == 5);       // an old skin's left limbs: the right ones mirrored
            part(g, t, x + p[0] * sc, y + p[1] * sc, sc, p[2], p[3], p[4], p[5], mirror);
        }
        for (int i = 0; i < over.length; i++) {
            if (legacy && i > 0) break;                           // old skins: only the hat
            int[] p = over[i];
            part(g, t, x + p[0] * sc, y + p[1] * sc, sc, p[2], p[3], p[4], p[5], false);
        }
    }

    /** One rectangle of the skin (u, v, w, h in skin pixels) drawn at x, y, 'sc' GUI pixels per skin pixel. */
    private static void part(GuiRenderer g, Texture2D t, float x, float y, float sc, int u, int v, int w, int h, boolean mirror) {
        float tw = 64, th = t.height < 64 ? 32 : 64;
        float u0 = u / tw, u1 = (u + w) / tw, v0 = v / th, v1 = (v + h) / th;
        if (mirror) { float k = u0; u0 = u1; u1 = k; }
        g.blitStretch(t, x, y, w * sc, h * sc, u0, v0, u1, v1, 0xFFFFFFFF);
    }
}
