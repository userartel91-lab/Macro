package derpcraft.render;

import java.awt.image.BufferedImage;

/** Minecraft style bitmap font from font/ascii.png (16x16 glyph grid), with per-glyph widths. */
public final class Font {
    public final Texture2D tex;
    public final int[] widths = new int[256];
    public final int cell;

    public Font(BufferedImage img) {
        tex = new Texture2D(img, false, false);
        cell = img.getWidth() / 16;
        float scale = 8f / cell;
        for (int c = 0; c < 256; c++) {
            int cx = (c % 16) * cell, cy = (c / 16) * cell;
            int last = -1;
            for (int x = cell - 1; x >= 0 && last < 0; x--)
                for (int y = 0; y < cell; y++)
                    if ((img.getRGB(cx + x, cy + y) >>> 24) > 16) { last = x; break; }
            widths[c] = c == 32 ? 4 : (last < 0 ? 0 : (int) ((last + 1) * scale + 0.999f) + 1);
        }
    }

    public int width(String s) {
        int w = 0;
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch == '§' && i + 1 < s.length()) { i++; continue; }
            w += widths[ch < 256 ? ch : '?'];
        }
        return w;
    }

    public void delete() { tex.delete(); }
}
