package derpcraft.npc;

import java.awt.image.BufferedImage;

/**
 * Faces drawn onto a copy of a skin: the eyes are found on the face automatically (pixels that differ from the
 * skin colour), then brows, eyelids and a mouth are painted around them in the skin's own colours.
 */
public final class Emotions {
    private Emotions() {}

    public static final String[] NAMES = {"Normal", "Happy", "Angry", "Sad", "Surprised", "Scared", "Eyes closed"};
    public static final int NORMAL = 0, HAPPY = 1, ANGRY = 2, SAD = 3, SURPRISED = 4, SCARED = 5, CLOSED = 6;

    /** A copy of the skin with the face changed. */
    public static BufferedImage apply(BufferedImage skin, int emotion) {
        BufferedImage img = new BufferedImage(skin.getWidth(), skin.getHeight(), BufferedImage.TYPE_INT_ARGB);
        img.getGraphics().drawImage(skin, 0, 0, null);
        if (emotion <= 0 || emotion >= NAMES.length) return img;
        int fx = 8, fy = 8;                                     // front of the head
        int base = skinColour(img, fx, fy);
        // find the eye row: the row (2..5) with the most pixels far from the skin colour
        int eyeRow = 4, best = -1;
        boolean[][] diff = new boolean[8][8];
        for (int y = 0; y < 8; y++) for (int x = 0; x < 8; x++) diff[y][x] = dist(img.getRGB(fx + x, fy + y), base) > 60;
        for (int y = 2; y <= 5; y++) {
            int n = 0;
            for (int x = 0; x < 8; x++) if (diff[y][x]) n++;
            if (n >= 2 && n <= 6 && n > best) { best = n; eyeRow = y; }
        }
        int[] eyeL = {1, 2}, eyeR = {5, 6};          // fall back to the usual eye columns
        if (best > 0) {
            int l0 = -1, l1 = -1, r0 = -1, r1 = -1;
            for (int x = 0; x < 4; x++) if (diff[eyeRow][x]) { if (l0 < 0) l0 = x; l1 = x; }
            for (int x = 4; x < 8; x++) if (diff[eyeRow][x]) { if (r0 < 0) r0 = x; r1 = x; }
            if (l0 >= 0) eyeL = new int[]{l0, l1};
            if (r0 >= 0) eyeR = new int[]{r0, r1};
        }
        int dark = shade(base, 0.45f), lid = shade(base, 0.72f), white = 0xFFF2F2F2;
        int brow = eyeRow - 1, mouth = Math.min(7, eyeRow + 2);
        switch (emotion) {
            case CLOSED -> {
                for (int[] e : new int[][]{eyeL, eyeR}) for (int x = e[0]; x <= e[1]; x++) set(img, fx + x, fy + eyeRow, lid);
            }
            case HAPPY -> {
                for (int x = 2; x <= 5; x++) set(img, fx + x, fy + mouth, dark);
                if (mouth - 1 > eyeRow) { set(img, fx + 1, fy + mouth - 1, dark); set(img, fx + 6, fy + mouth - 1, dark); }
            }
            case SAD -> {
                for (int x = 2; x <= 5; x++) set(img, fx + x, fy + mouth, dark);
                if (mouth + 1 < 8) { set(img, fx + 1, fy + mouth + 1, dark); set(img, fx + 6, fy + mouth + 1, dark); }
                if (brow - 1 >= 0) { set(img, fx + eyeL[1], fy + brow - 1, dark); set(img, fx + eyeR[0], fy + brow - 1, dark); }
                if (brow >= 0) { set(img, fx + eyeL[0], fy + brow, dark); set(img, fx + eyeR[1], fy + brow, dark); }
            }
            case ANGRY -> {
                if (brow >= 0) { set(img, fx + eyeL[1], fy + brow, dark); set(img, fx + eyeR[0], fy + brow, dark); }
                if (brow - 1 >= 0) { set(img, fx + eyeL[0], fy + brow - 1, dark); set(img, fx + eyeR[1], fy + brow - 1, dark); }
                for (int x = 2; x <= 5; x++) set(img, fx + x, fy + mouth, dark);
            }
            case SURPRISED, SCARED -> {
                if (brow >= 0) for (int[] e : new int[][]{eyeL, eyeR}) for (int x = e[0]; x <= e[1]; x++) set(img, fx + x, fy + brow, white);
                if (brow - 1 >= 0 && emotion == SCARED) for (int[] e : new int[][]{eyeL, eyeR}) for (int x = e[0]; x <= e[1]; x++) set(img, fx + x, fy + brow - 1, dark);
                set(img, fx + 3, fy + mouth, dark); set(img, fx + 4, fy + mouth, dark);
                if (mouth + 1 < 8) { set(img, fx + 3, fy + mouth + 1, dark); set(img, fx + 4, fy + mouth + 1, dark); }
                if (emotion == SCARED) { set(img, fx + 2, fy + mouth, dark); set(img, fx + 5, fy + mouth, dark); }
            }
            default -> {}
        }
        return img;
    }

    /** Most common colour of the face's forehead / cheeks. */
    private static int skinColour(BufferedImage img, int fx, int fy) {
        java.util.Map<Integer, Integer> count = new java.util.HashMap<>();
        int[][] spots = {{1, 6}, {6, 6}, {3, 2}, {4, 2}, {0, 5}, {7, 5}, {2, 7}, {5, 7}, {3, 5}, {4, 5}};
        for (int[] s : spots) count.merge(img.getRGB(fx + s[0], fy + s[1]) | 0xFF000000, 1, Integer::sum);
        return count.entrySet().stream().max(java.util.Map.Entry.comparingByValue()).get().getKey();
    }

    private static int dist(int a, int b) {
        return Math.abs(((a >> 16) & 255) - ((b >> 16) & 255)) + Math.abs(((a >> 8) & 255) - ((b >> 8) & 255)) + Math.abs((a & 255) - (b & 255));
    }

    private static int shade(int c, float f) {
        int r = Math.min(255, (int) (((c >> 16) & 255) * f)), g = Math.min(255, (int) (((c >> 8) & 255) * f)), b = Math.min(255, (int) ((c & 255) * f));
        return 0xFF000000 | r << 16 | g << 8 | b;
    }

    private static void set(BufferedImage img, int x, int y, int c) { if (x >= 0 && y >= 0 && x < img.getWidth() && y < img.getHeight()) img.setRGB(x, y, c); }
}
