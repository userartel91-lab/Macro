package derpcraft.resource;

import derpcraft.util.Noise;

import java.awt.*;
import java.awt.image.BufferedImage;

/** Procedurally generated stand-ins for Minecraft's GUI/environment textures (used when no pack provides them). */
public final class DefaultImages {
    private DefaultImages() {}

    public static BufferedImage get(String path) {
        return switch (path) {
            case "assets/minecraft/textures/environment/sun.png" -> sun();
            case "assets/minecraft/textures/environment/moon_phases.png" -> moon();
            case "assets/minecraft/textures/environment/clouds.png" -> clouds();
            case "assets/minecraft/textures/gui/widgets.png" -> widgets();
            case "assets/minecraft/textures/gui/icons.png" -> icons();
            case "assets/minecraft/textures/font/ascii.png" -> font();
            case "assets/minecraft/textures/gui/options_background.png" -> background();
            case "assets/minecraft/textures/particle/particles.png" -> particleSheet();
            case "assets/minecraft/textures/colormap/grass.png" -> colormap(0x47CD33, 0xBFB755, 0x80B497);
            case "assets/minecraft/textures/colormap/foliage.png" -> colormap(0x30BB0B, 0xAEA42A, 0x60A17B);
            default -> path.startsWith(ENTITY) ? entity(path.substring(ENTITY.length()))
                    : path.startsWith(CONTAINER) ? container(path.substring(CONTAINER.length()))
                    : new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
        };
    }

    /**
     * Stand-in for textures/particle/particles.png (128x128, 8 px sprites): row 0 = generic dots growing from 1 px to a full
     * disc (smoke and puffs use them in reverse as they age), sprite 48 = flame, 49 = glowing ember.
     */
    private static BufferedImage particleSheet() {
        BufferedImage i = img(128, 128);
        for (int f = 0; f < 8; f++) {
            double r = 0.5 + f * 0.5;
            for (int y = 0; y < 8; y++)
                for (int x = 0; x < 8; x++) {
                    double dx = x - 3.5, dy = y - 3.5;
                    if (dx * dx + dy * dy <= r * r) i.setRGB(f * 8 + x, y, 0xFFFFFFFF);
                }
        }
        int[][] flame = {
                {0, 0, 0, 1, 0, 0, 0, 0},
                {0, 0, 0, 1, 1, 0, 0, 0},
                {0, 0, 1, 2, 1, 0, 0, 0},
                {0, 0, 1, 2, 2, 1, 0, 0},
                {0, 1, 2, 2, 3, 1, 0, 0},
                {0, 1, 2, 3, 3, 2, 1, 0},
                {0, 1, 2, 3, 3, 2, 1, 0},
                {0, 0, 1, 2, 2, 1, 0, 0}};
        int[] flameCol = {0, 0xFFD02010, 0xFFF06010, 0xFFFFB020, 0xFFFFF0A0};
        for (int y = 0; y < 8; y++) for (int x = 0; x < 8; x++) if (flame[y][x] > 0) i.setRGB(x, 24 + y, flameCol[flame[y][x] + (flame[y][x] > 1 ? 1 : 0)]);
        for (int y = 0; y < 8; y++)
            for (int x = 0; x < 8; x++) {
                double dx = x - 3.5, dy = y - 3.5, d = Math.sqrt(dx * dx + dy * dy);
                if (d <= 3.2) i.setRGB(8 + x, 24 + y, d < 1.6 ? 0xFFFFE080 : d < 2.5 ? 0xFFFFA030 : 0xFFE05010);
            }
        return i;
    }

    private static final String ENTITY = "assets/minecraft/textures/entity/", CONTAINER = "assets/minecraft/textures/gui/container/";

    // ------------------------------------------------------------------------------------------ entity textures
    /** Paints the six faces of a Minecraft ModelBox unwrap (texture offset u,v; size w,h,d) with a border. */
    private static void boxTex(BufferedImage i, int u, int v, int w, int h, int d, int fill, int border) {
        int[][] faces = {{u + d, v, w, d}, {u + d + w, v, w, d}, {u, v + d, d, h}, {u + d, v + d, w, h}, {u + d + w, v + d, d, h}, {u + d + w + d, v + d, w, h}};
        for (int[] f : faces)
            for (int y = 0; y < f[3]; y++)
                for (int x = 0; x < f[2]; x++) {
                    int px = f[0] + x, py = f[1] + y;
                    if (px >= i.getWidth() || py >= i.getHeight()) continue;
                    boolean edge = x == 0 || y == 0 || x == f[2] - 1 || y == f[3] - 1;
                    int c = edge ? border : fill;
                    if (!edge && ((px * 7 + py * 13) % 5 == 0)) c = shadeRGB(c, 0.92f);
                    i.setRGB(px, py, c);
                }
    }

    private static int shadeRGB(int c, float f) {
        int r = Math.min(255, (int) (((c >> 16) & 255) * f)), g = Math.min(255, (int) (((c >> 8) & 255) * f)), b = Math.min(255, (int) ((c & 255) * f));
        return (c & 0xFF000000) | r << 16 | g << 8 | b;
    }

    private static final int[] DYE = {0xF9FFFE, 0xF9801D, 0xC74EBD, 0x3AB3DA, 0xFED83D, 0x80C71F, 0xF38BAA, 0x474F52,
            0x9D9D97, 0x169C9C, 0x8932B8, 0x3C44AA, 0x835432, 0x5E7C16, 0xB02E26, 0x1D1D21};

    /** RGB of the dye colour with this index in {@link derpcraft.block.Blocks#COLORS}. */
    public static int dyeColor(int idx) { return DYE[Math.max(0, Math.min(15, idx))]; }

    private static BufferedImage entity(String p) {
        if (p.startsWith("chest/")) {
            boolean dbl = p.contains("double"), ender = p.contains("ender"), trapped = p.contains("trapped");
            BufferedImage i = img(dbl ? 128 : 64, 64);
            int w = dbl ? 30 : 14;
            int wood = ender ? 0xFF1F3A3A : 0xFFA4732E, edge = ender ? 0xFF0B1717 : 0xFF4A2F12;
            boxTex(i, 0, 0, w, 5, 14, wood, edge);
            boxTex(i, 0, 19, w, 10, 14, wood, edge);
            boxTex(i, 0, 0, 2, 4, 1, trapped ? 0xFFB02020 : ender ? 0xFF2FA080 : 0xFFC8C8C8, 0xFF303030);
            return i;
        }
        if (p.equals("sign.png")) {
            BufferedImage i = img(64, 32);
            boxTex(i, 0, 0, 24, 12, 2, 0xFFB08850, 0xFF6B5030);
            boxTex(i, 0, 14, 2, 14, 2, 0xFF6B5534, 0xFF4A3A22);
            return i;
        }
        if (p.equals("steve.png")) return person(0xFFB4846D, 0xFF2B1E0E, 0xFF00A8A8, 0xFF3A3A9A, false, 0xFF555555);
        if (p.startsWith("bed/")) {
            String color = p.substring(4, p.length() - 4);
            int idx = java.util.Arrays.asList(derpcraft.block.Blocks.COLORS).indexOf(color);
            int c = 0xFF000000 | DYE[Math.max(0, idx)];
            BufferedImage i = img(64, 64);
            boxTex(i, 0, 0, 16, 16, 6, c, shadeRGB(c, 0.7f));
            boxTex(i, 0, 22, 16, 16, 6, c, shadeRGB(c, 0.7f));
            for (int y = 6; y < 11; y++) for (int x = 7; x < 21; x++) i.setRGB(x, y, 0xFFF0F0F0);  // pillow
            for (int l = 0; l < 4; l++) boxTex(i, 50, l * 6, 3, 3, 3, 0xFF9D804F, 0xFF5B4A2E);
            return i;
        }
        return img(64, 64);
    }

    /**
     * A simple 64x64 player skin in the 2x2-texture style: skin tone, hair, shirt, trousers, sleeves and shoes.
     * Used for Steve and the built-in NPC skins.
     */
    public static BufferedImage person(int skin, int hair, int shirt, int pants, boolean longSleeves, int shoes) {
        BufferedImage i = img(64, 64);
        int skinD = shadeRGB(skin, 0.86f), shirtD = shadeRGB(shirt, 0.78f), pantsD = shadeRGB(pants, 0.75f);
        boxTex(i, 0, 0, 8, 8, 8, skin, skinD);                                                   // head
        for (int x = 0; x < 32; x++) for (int y = 0; y < 10; y++) if ((i.getRGB(x, y) >>> 24) != 0 && (y < 8 || x < 8 || x >= 16)) i.setRGB(x, y, hair); // hair
        for (int x = 9; x < 15; x++) i.setRGB(x, 12, 0xFF493B8C);                                // eyes row
        i.setRGB(11, 12, skin); i.setRGB(12, 12, skin);
        i.setRGB(9, 12, 0xFFFFFFFF); i.setRGB(14, 12, 0xFFFFFFFF);
        boxTex(i, 16, 16, 8, 12, 4, shirt, shirtD);                                              // body
        boxTex(i, 0, 16, 4, 12, 4, pants, pantsD);                                               // right leg
        boxTex(i, 16, 48, 4, 12, 4, pants, pantsD);                                              // left leg
        for (int x = 0; x < 16; x++) for (int y = 29; y < 32; y++) i.setRGB(x, y, shoes);
        for (int x = 16; x < 32; x++) for (int y = 61; y < 64; y++) i.setRGB(x, y, shoes);
        for (int x = 4; x < 12; x++) for (int y = 16; y < 20; y++) if (x >= 8) i.setRGB(x, y, shoes);
        int armMain = longSleeves ? shirt : skin, armDark = longSleeves ? shirtD : skinD;
        boxTex(i, 40, 16, 4, 12, 4, armMain, armDark);                                           // right arm
        boxTex(i, 32, 48, 4, 12, 4, armMain, armDark);                                           // left arm
        if (longSleeves) {
            // hands
            for (int x = 40; x < 56; x++) for (int y = 29; y < 32; y++) i.setRGB(x, y, skin);
            for (int x = 32; x < 48; x++) for (int y = 61; y < 64; y++) i.setRGB(x, y, skin);
            for (int x = 48; x < 52; x++) for (int y = 16; y < 20; y++) i.setRGB(x, y, skin);
        } else {
            for (int y = 20; y < 24; y++) for (int x = 40; x < 56; x++) i.setRGB(x, y, shirt);
            for (int x = 44; x < 48; x++) for (int y = 16; y < 20; y++) i.setRGB(x, y, shirt);
            for (int y = 52; y < 56; y++) for (int x = 32; x < 48; x++) i.setRGB(x, y, shirt);
            for (int x = 36; x < 40; x++) for (int y = 48; y < 52; y++) i.setRGB(x, y, shirt);
        }
        return i;
    }

    // ------------------------------------------------------------------------------------------ container GUIs
    private static final int BG = 0xFFC6C6C6, LIGHT = 0xFFFFFFFF, DARK = 0xFF555555, SLOT_DARK = 0xFF373737, SLOT = 0xFF8B8B8B;

    /** Vanilla panel: black outline with cut corners, white top-left bevel, grey bottom-right bevel. */
    private static void panel(Graphics2D g, int x, int y, int w, int h) {
        rect(g, x + 2, y, w - 4, h, 0xFF000000);
        rect(g, x, y + 2, w, h - 4, 0xFF000000);
        rect(g, x + 1, y + 1, w - 2, h - 2, 0xFF000000);
        rect(g, x + 2, y + 1, w - 4, h - 2, BG);
        rect(g, x + 1, y + 2, w - 2, h - 4, BG);
        rect(g, x + 2, y + 1, w - 5, 2, LIGHT); rect(g, x + 1, y + 2, 2, h - 5, LIGHT); rect(g, x + 3, y + 3, 1, 1, LIGHT);
        rect(g, x + 3, y + h - 3, w - 5, 2, DARK); rect(g, x + w - 3, y + 3, 2, h - 5, DARK); rect(g, x + w - 4, y + h - 4, 1, 1, DARK);
    }

    /** Slot frame for an item at (x,y). */
    private static void slot(Graphics2D g, int x, int y) {
        rect(g, x - 1, y - 1, 18, 18, SLOT_DARK);
        rect(g, x, y, 17, 17, LIGHT);
        rect(g, x, y, 16, 16, SLOT);
        rect(g, x + 16, y - 1, 1, 1, SLOT); rect(g, x - 1, y + 16, 1, 1, SLOT);
    }

    private static void bigSlot(Graphics2D g, int x, int y) {
        rect(g, x - 5, y - 5, 26, 26, SLOT_DARK);
        rect(g, x - 4, y - 4, 25, 25, LIGHT);
        rect(g, x - 4, y - 4, 24, 24, SLOT);
    }

    private static void playerInv(Graphics2D g, int x, int y) {
        for (int r = 0; r < 3; r++) for (int c = 0; c < 9; c++) slot(g, x + c * 18, y + r * 18);
        for (int c = 0; c < 9; c++) slot(g, x + c * 18, y + 58);
    }

    private static void arrow(Graphics2D g, int x, int y, int color) {
        rect(g, x, y + 6, 16, 5, color);
        for (int k = 0; k < 8; k++) rect(g, x + 15 + k, y + k, 1, 17 - 2 * k, color);
    }

    private static void flame(Graphics2D g, int x, int y, int color) {
        for (int k = 0; k < 14; k++) {
            int half = (int) (Math.sin(k / 13.0 * Math.PI * 0.85 + 0.25) * 6.5);
            rect(g, x + 7 - half, y + k, Math.max(1, half * 2), 1, color);
        }
    }

    private static BufferedImage container(String p) {
        BufferedImage i = img(256, 256);
        Graphics2D g = i.createGraphics();
        switch (p) {
            case "inventory.png" -> {
                panel(g, 0, 0, 176, 166);
                for (int k = 0; k < 4; k++) slot(g, 8, 8 + k * 18);
                rect(g, 26, 8, 51, 72, 0xFF000000);
                for (int r = 0; r < 2; r++) for (int c = 0; c < 2; c++) slot(g, 98 + c * 18, 18 + r * 18);
                slot(g, 154, 28);
                arrow(g, 134, 28, 0xFF8B8B8B);
                playerInv(g, 8, 84);
            }
            case "crafting_table.png" -> {
                panel(g, 0, 0, 176, 166);
                for (int r = 0; r < 3; r++) for (int c = 0; c < 3; c++) slot(g, 30 + c * 18, 17 + r * 18);
                bigSlot(g, 124, 35);
                arrow(g, 89, 34, 0xFF8B8B8B);
                playerInv(g, 8, 84);
            }
            case "generic_54.png" -> {
                panel(g, 0, 0, 176, 222);
                rect(g, 3, 125, 170, 1, BG);
                for (int r = 0; r < 6; r++) for (int c = 0; c < 9; c++) slot(g, 8 + c * 18, 18 + r * 18);
                playerInv(g, 8, 140);
            }
            case "furnace.png" -> {
                panel(g, 0, 0, 176, 166);
                slot(g, 56, 17); slot(g, 56, 53); bigSlot(g, 116, 35);
                flame(g, 56, 36, 0xFF8B8B8B);
                arrow(g, 79, 34, 0xFF8B8B8B);
                flame(g, 176, 0, 0xFFF8A020);
                arrow(g, 176, 14, 0xFFFFFFFF);
                playerInv(g, 8, 84);
            }
            case "dispenser.png" -> {
                panel(g, 0, 0, 176, 166);
                for (int r = 0; r < 3; r++) for (int c = 0; c < 3; c++) slot(g, 62 + c * 18, 17 + r * 18);
                playerInv(g, 8, 84);
            }
            case "hopper.png" -> {
                panel(g, 0, 0, 176, 133);
                for (int c = 0; c < 5; c++) slot(g, 44 + c * 18, 20);
                playerInv(g, 8, 51);
            }
            case "creative_inventory/tabs.png" -> {
                for (int col = 0; col < 6; col++)
                    for (int row = 0; row < 4; row++) {
                        int x = col * 28, y = row * 32;
                        boolean sel = (row & 1) == 1, bottom = row >= 2;
                        int top = bottom ? y + (sel ? 0 : 4) : y, h = sel ? 32 : 28;
                        rect(g, x + 1, top, 26, h, 0xFF000000);
                        rect(g, x, top + (bottom ? 0 : 1), 28, h - 1, 0xFF000000);
                        rect(g, x + 1, top + (bottom ? 0 : 1), 26, h - 1, sel ? BG : 0xFFA9A9A9);
                        if (!bottom) { rect(g, x + 1, top + 1, 26, 2, LIGHT); rect(g, x + 1, top + 1, 2, h - 1, LIGHT); }
                        else { rect(g, x + 1, top + h - 3, 26, 2, DARK); rect(g, x + 1, top, 2, h - 2, LIGHT); }
                        if (!sel) rect(g, x, bottom ? top : top + h - 1, 28, 1, 0xFF000000);
                    }
                rect(g, 232, 0, 12, 15, 0xFFC6C6C6); rect(g, 232, 0, 11, 1, LIGHT); rect(g, 232, 0, 1, 14, LIGHT);
                rect(g, 233, 14, 11, 1, DARK); rect(g, 243, 1, 1, 14, DARK);
                rect(g, 244, 0, 12, 15, 0xFFA0A0A0);
            }
            case "creative_inventory/tab_items.png", "creative_inventory/tab_item_search.png" -> {
                panel(g, 0, 0, 195, 136);
                for (int r = 0; r < 5; r++) for (int c = 0; c < 9; c++) slot(g, 9 + c * 18, 18 + r * 18);
                for (int c = 0; c < 9; c++) slot(g, 9 + c * 18, 112);
                rect(g, 174, 17, 14, 112, SLOT_DARK); rect(g, 175, 18, 13, 111, LIGHT); rect(g, 175, 18, 12, 110, SLOT);
                if (p.contains("search")) { rect(g, 80, 4, 89, 12, 0xFF000000); rect(g, 81, 5, 87, 10, 0xFF202020); }
            }
            case "creative_inventory/tab_inventory.png" -> {
                panel(g, 0, 0, 195, 136);
                for (int k = 0; k < 4; k++) slot(g, 54 + (k / 2) * 54, 6 + (k % 2) * 27);
                rect(g, 73, 6, 32, 43, 0xFF000000);
                for (int r = 0; r < 3; r++) for (int c = 0; c < 9; c++) slot(g, 9 + c * 18, 54 + r * 18);
                for (int c = 0; c < 9; c++) slot(g, 9 + c * 18, 112);
                slot(g, 173, 112);
            }
            default -> {}
        }
        g.dispose();
        return i;
    }

    // ------------------------------------------------------------------------------------------ logo
    private static final String[] GLYPHS = {
            "D", "1110 1001 1001 1001 1001 1001 1110",
            "E", "1111 1000 1000 1110 1000 1000 1111",
            "R", "1110 1001 1001 1110 1010 1001 1001",
            "P", "1110 1001 1001 1110 1000 1000 1000",
            "C", "0111 1000 1000 1000 1000 1000 0111",
            "A", "0110 1001 1001 1111 1001 1001 1001",
            "F", "1111 1000 1000 1110 1000 1000 1000",
            "T", "11111 00100 00100 00100 00100 00100 00100"};

    /** Blocky stone "DERPCRAFT" logo in the spirit of the Minecraft title (one 4px block per glyph pixel). */
    public static BufferedImage logo(Object assets) {
        String word = "DERPCRAFT";
        int cell = 4, depth = 3;
        int width = 0;
        java.util.Map<Character, String[]> map = new java.util.HashMap<>();
        for (int k = 0; k < GLYPHS.length; k += 2) map.put(GLYPHS[k].charAt(0), GLYPHS[k + 1].split(" "));
        for (char c : word.toCharArray()) width += map.get(c)[0].length() + 1;
        BufferedImage i = img(width * cell + depth + 2, 7 * cell + depth + 2);
        java.util.Random r = new java.util.Random(42);
        int ox = 1;
        for (char c : word.toCharArray()) {
            String[] rows = map.get(c);
            for (int y = 0; y < 7; y++)
                for (int x = 0; x < rows[y].length(); x++) {
                    if (rows[y].charAt(x) != '1') continue;
                    int bx = (ox + x) * cell, by = 1 + y * cell;
                    // extrusion (dark side), then the face
                    for (int d = depth; d >= 1; d--)
                        for (int py = 0; py < cell; py++) for (int px = 0; px < cell; px++) {
                            int tx = bx + px + d, ty = by + py + d;
                            if ((i.getRGB(tx, ty) >>> 24) == 0) i.setRGB(tx, ty, d == depth ? 0xFF1A1A1A : 0xFF3A3A3A);
                        }
                    for (int py = 0; py < cell; py++)
                        for (int px = 0; px < cell; px++) {
                            int v = 0x80 + r.nextInt(40) - (py == cell - 1 || px == cell - 1 ? 24 : 0) + (py == 0 || px == 0 ? 20 : 0);
                            i.setRGB(bx + px, by + py, 0xFF000000 | v << 16 | v << 8 | v);
                        }
                }
            ox += rows[0].length() + 1;
        }
        return i;
    }

    private static BufferedImage img(int w, int h) { return new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB); }

    private static BufferedImage sun() {
        BufferedImage i = img(8, 8);
        for (int y = 2; y < 6; y++) for (int x = 2; x < 6; x++) i.setRGB(x, y, 0xFFFFF4C0);
        for (int y = 0; y < 8; y++) for (int x = 0; x < 8; x++) if (i.getRGB(x, y) == 0) i.setRGB(x, y, 0xFF000000);
        return i;
    }

    private static BufferedImage moon() {
        BufferedImage i = img(32, 16);
        for (int p = 0; p < 8; p++) {
            int ox = (p % 4) * 8, oy = (p / 4) * 8;
            for (int y = 0; y < 8; y++)
                for (int x = 0; x < 8; x++) {
                    boolean disc = x >= 2 && x < 6 && y >= 2 && y < 6;
                    int col = x - 2;
                    boolean lit = p == 0 || (p < 4 ? col < 4 - p : p > 4 && col >= 8 - p);
                    i.setRGB(ox + x, oy + y, !disc ? 0xFF000000 : (lit ? 0xFFE4E8F0 : 0xFF181818));
                }
        }
        return i;
    }

    private static BufferedImage clouds() {
        BufferedImage i = img(256, 256);
        Noise n = new Noise(1234);
        for (int y = 0; y < 256; y++)
            for (int x = 0; x < 256; x++) {
                // tileable: sample on a torus
                double a = x / 256.0 * Math.PI * 2, b = y / 256.0 * Math.PI * 2;
                double v = n.noise(Math.cos(a) * 6, Math.sin(a) * 6 + Math.cos(b) * 6, Math.sin(b) * 6)
                        + 0.5 * n.noise(Math.cos(a) * 14 + 50, Math.sin(a) * 14 + Math.cos(b) * 14, Math.sin(b) * 14);
                i.setRGB(x, y, v > 0.28 ? 0xFFFFFFFF : 0);
            }
        return i;
    }

    private static void rect(Graphics2D g, int x, int y, int w, int h, int argb) {
        g.setColor(new Color(argb, true));
        g.fillRect(x, y, w, h);
    }

    private static BufferedImage widgets() {
        BufferedImage i = img(256, 256);
        Graphics2D g = i.createGraphics();
        // hotbar 182x22
        rect(g, 0, 0, 182, 22, 0xFF000000);
        rect(g, 1, 1, 180, 20, 0xFF8B8B8B);
        for (int s = 0; s < 9; s++) {
            int x = 1 + s * 20;
            rect(g, x, 1, 20, 20, 0xFF373737);
            rect(g, x + 1, 2, 18, 18, 0xFF8B8B8B);
            rect(g, x + 2, 3, 16, 16, 0xFF6B6B6B);
        }
        // selection 24x24
        rect(g, 0, 22, 24, 24, 0xFFFFFFFF);
        rect(g, 2, 24, 20, 20, 0x00000000);
        g.setComposite(AlphaComposite.Clear);
        g.fillRect(2, 24, 20, 20);
        g.setComposite(AlphaComposite.SrcOver);
        rect(g, 1, 23, 22, 22, 0xFF000000);
        g.setComposite(AlphaComposite.Clear);
        g.fillRect(2, 24, 20, 20);
        g.setComposite(AlphaComposite.SrcOver);
        // buttons: disabled, normal, hover
        int[][] cols = {{0xFF2C2C2C, 0xFF3F3F3F, 0xFF1E1E1E}, {0xFF6F6F6F, 0xFFA0A0A0, 0xFF555555}, {0xFF7E88BF, 0xFFBDC6FF, 0xFF5A6398}};
        for (int b = 0; b < 3; b++) {
            int y = 46 + b * 20;
            rect(g, 0, y, 200, 20, 0xFF000000);
            rect(g, 1, y + 1, 198, 18, cols[b][0]);
            rect(g, 1, y + 1, 198, 1, cols[b][1]);
            rect(g, 1, y + 1, 1, 17, cols[b][1]);
            rect(g, 1, y + 17, 198, 2, cols[b][2]);
            rect(g, 198, y + 2, 1, 16, cols[b][2]);
        }
        g.dispose();
        return i;
    }

    private static void heart(BufferedImage i, int u, int v, int fill, boolean half, boolean outline) {
        String[] shape = {"011000110", "111101111", "111111111", "111111111", "011111110", "001111100", "000111000", "000010000"};
        for (int y = 0; y < 8; y++)
            for (int x = 0; x < 9; x++) {
                if (shape[y].charAt(x) != '1') continue;
                boolean border = y == 0 || x == 0 || x == 8 || y == 7 || shape[y - 1].charAt(x) != '1' || shape[y].charAt(x - 1) != '1' || shape[y].charAt(x + 1) != '1'
                        || y + 1 < 8 && shape[y + 1].charAt(x) != '1';
                if (outline) { i.setRGB(u + x, v + y, border ? 0xFF000000 : 0xFF3A3A3A); continue; }
                if (border || half && x > 4) continue;
                i.setRGB(u + x, v + y, (x == 2 && y == 1 || x == 1 && y == 2) ? 0xFFFFFFFF : fill);
            }
    }

    private static BufferedImage icons() {
        BufferedImage i = img(256, 256);
        for (int k = 0; k < 9; k++) { i.setRGB(7, 3 + k, 0xFFFFFFFF); i.setRGB(3 + k, 7, 0xFFFFFFFF); }
        // hearts: containers at u=16 (normal) / 25 (flash), full u=52, half u=61
        heart(i, 16, 0, 0, false, true);
        heart(i, 25, 0, 0, false, true);
        for (int y = 0; y < 9; y++) for (int x = 0; x < 9; x++) if (i.getRGB(25 + x, y) == 0xFF000000) i.setRGB(25 + x, y, 0xFFFFFFFF);
        heart(i, 52, 0, 0xFFE02020, false, false);
        heart(i, 61, 0, 0xFFE02020, true, false);
        // armor (v=9): empty 16, half 25, full 34
        for (int k = 0; k < 3; k++)
            for (int y = 0; y < 9; y++)
                for (int x = 0; x < 9; x++) {
                    boolean in = y < 8 && (y < 3 ? (x < 3 || x > 5) : x >= 1 && x <= 7) && !(y >= 6 && (x < 2 || x > 6));
                    if (!in) continue;
                    boolean edge = x == 0 || x == 8 || y == 0 || y == 7 || y == 2 && x > 2 && x < 6;
                    int c = edge ? 0xFF1E1E1E : k == 0 || k == 1 && x > 4 ? 0xFF3A3A3A : 0xFFD8D8D8;
                    i.setRGB(16 + k * 9 + x, 9 + y, c);
                }
        // air bubbles (v=18): full 16, popping 25
        for (int k = 0; k < 2; k++)
            for (int y = 0; y < 9; y++)
                for (int x = 0; x < 9; x++) {
                    double d = Math.hypot(x - 4, y - 4);
                    if (d > 4.2 || k == 1 && d < 2.5) continue;
                    i.setRGB(16 + k * 9 + x, 18 + y, d > 3.3 ? 0xFF1B3FA0 : (x == 3 && y == 2 ? 0xFFFFFFFF : 0xFF5A8AF0));
                }
        // food (v=27): background 16, full 52, half 61 (drumstick)
        for (int k = 0; k < 3; k++) {
            int u = k == 0 ? 16 : k == 1 ? 52 : 61;
            for (int y = 0; y < 9; y++)
                for (int x = 0; x < 9; x++) {
                    boolean meat = Math.hypot(x - 5.5, y - 3) < 3.4, bone = x + y >= 8 && x + y <= 9 && x < 4 || x <= 1 && y >= 6;
                    if (!meat && !bone) continue;
                    int c;
                    if (k == 0) c = meat && Math.hypot(x - 5.5, y - 3) > 2.6 || bone ? 0xFF000000 : 0xFF3A3A3A;
                    else {
                        if (k == 2 && x > 4) continue;
                        c = bone ? 0xFFF0E8D8 : Math.hypot(x - 5.5, y - 3) > 2.6 ? 0xFF6A2A0A : 0xFFB8642A;
                    }
                    i.setRGB(u + x, 27 + y, c);
                }
        }
        // experience bar (v=64 background, v=69 progress)
        Graphics2D g = i.createGraphics();
        rect(g, 0, 64, 182, 5, 0xFF000000); rect(g, 1, 65, 180, 3, 0xFF2A2A2A);
        rect(g, 0, 69, 182, 5, 0xFF000000); rect(g, 1, 70, 180, 3, 0xFF80FF20);
        for (int k = 1; k < 18; k++) { rect(g, k * 10 + 1, 64, 1, 5, 0xFF000000); rect(g, k * 10 + 1, 69, 1, 5, 0xFF000000); }
        g.dispose();
        return i;
    }

    private static BufferedImage background() {
        BufferedImage i = img(16, 16);
        int[] px = DefaultTextures.get("dirt");
        for (int y = 0; y < 16; y++) for (int x = 0; x < 16; x++) i.setRGB(x, y, px[(y / 8) * 2 + x / 8]);
        return i;
    }

    /** 16x16 grid of 8x8 glyphs, rendered from a system font without anti-aliasing. */
    private static BufferedImage font() {
        BufferedImage i = img(128, 128);
        Graphics2D g = i.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_OFF);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);
        Font f = new Font(Font.SANS_SERIF, Font.PLAIN, 9);
        g.setFont(f);
        g.setColor(Color.WHITE);
        for (int c = 32; c < 127; c++) {
            int cx = (c % 16) * 8, cy = (c / 16) * 8;
            g.setClip(cx, cy, 8, 8);
            g.drawString(String.valueOf((char) c), cx, cy + 7);
        }
        g.dispose();
        return i;
    }

    private static int lerp(int a, int b, double t) {
        int r = (int) (((a >> 16) & 255) * (1 - t) + ((b >> 16) & 255) * t);
        int g = (int) (((a >> 8) & 255) * (1 - t) + ((b >> 8) & 255) * t);
        int bl = (int) ((a & 255) * (1 - t) + (b & 255) * t);
        return 0xFF000000 | r << 16 | g << 8 | bl;
    }

    private static BufferedImage colormap(int wet, int dry, int cold) {
        BufferedImage i = img(256, 256);
        for (int y = 0; y < 256; y++)
            for (int x = 0; x < 256; x++) {
                double temp = 1 - x / 255.0;
                double rain = temp <= 0.001 ? 0 : Math.min(1, (1 - y / 255.0) / temp);
                i.setRGB(x, y, lerp(cold, lerp(dry, wet, rain), temp));
            }
        return i;
    }
}
