package derpcraft.gui;

import derpcraft.model.Model3D;
import derpcraft.model.ModelSettings;
import derpcraft.render.GuiRenderer;
import derpcraft.render.Texture2D;
import derpcraft.util.Mat4;

/**
 * Turntable preview of a 3D model in a box of the screen: a 1-block grid on the ground, the anchor point (red
 * cross = the point that sits on the block / body part) and Steve for size.
 */
final class ModelPreview {
    private ModelPreview() {}

    /** The preview's turntable: drag to turn, wheel to zoom (shared by the Model Hub and the 3D block screen). */
    static final class View {
        float yaw = 150, pitch = 20, zoom = 1;
        private int lastX, lastY;

        void press(int mx, int my) { lastX = mx; lastY = my; }

        /** Turns the view while dragging right of 'minX'; false = the drag is not in the preview. */
        boolean drag(int mx, int my, int minX) {
            if (mx <= minX || lastX <= minX) return false;
            yaw += (mx - lastX) * 1.2f;
            pitch = Math.max(-89, Math.min(89, pitch + (my - lastY)));
            lastX = mx; lastY = my;
            return true;
        }

        void zoom(double amount) { zoom = (float) Math.max(0.2, Math.min(8, zoom * Math.pow(1.15, amount))); }
    }

    static void draw(GuiRenderer g, Model3D m, ModelSettings st, Mat4 extra, int x0, int y0, int x1, int y1, View v, Texture2D steve) {
        draw(g, m, st, extra, x0, y0, x1, y1, v.yaw, v.pitch, v.zoom, steve);
    }

    /**
     * @param extra placement on top of the model's own settings (a 3D block's turn / size / offset), or null
     */
    static void draw(GuiRenderer g, Model3D m, ModelSettings st, Mat4 extra, int x0, int y0, int x1, int y1,
                     float yaw, float pitch, float zoom, Texture2D steve) {
        g.fill(x0, y0, x1, y1, 0xD0181C24);
        if (m == null) return;
        // what has to fit: the model, the anchor and (if shown) Steve beside it
        float[] b = m.empty() ? new float[]{-0.5f, 0, -0.5f, 0.5f, 1, 0.5f} : st.bounds(m, extra);
        float minX = Math.min(b[0], 0), minY = Math.min(b[1], 0), minZ = Math.min(b[2], 0), maxX = Math.max(b[3], 0), maxY = Math.max(b[4], 0), maxZ = Math.max(b[5], 0);
        float steveX = maxX + 0.6f;
        if (steve != null) { maxX = steveX + 0.4f; maxY = Math.max(maxY, 1.9f); }
        float cx = (minX + maxX) / 2, cy = (minY + maxY) / 2, cz = (minZ + maxZ) / 2;
        float ext = Math.max(0.3f, Math.max(maxY - minY, Math.max(maxX - minX, maxZ - minZ)));
        float s = Math.min(x1 - x0, y1 - y0) * 0.62f / ext * zoom;
        Mat4 base = new Mat4().translate((x0 + x1) / 2f, (y0 + y1) / 2f + 6, 50).scale(s, -s, s);
        base.rotate((float) Math.toRadians(pitch), 1, 0, 0).rotate((float) Math.toRadians(yaw), 0, 1, 0);
        base.translate(-cx, -cy, -cz);
        GuiRenderer.Scene sc = g.new Scene();
        // ground grid (1 block squares) around the anchor
        int n = (int) Math.min(6, Math.ceil(ext / 2) + 1);
        float w = 0.012f * ext;
        for (int i = -n; i <= n; i++) {
            int c = i == 0 ? 0x70FFFFFF : 0x30FFFFFF;
            sc.quad(null, base, new float[]{i - w, 0, -n, i + w, 0, -n, i + w, 0, n, i - w, 0, n}, new float[8], c);
            sc.quad(null, base, new float[]{-n, 0, i - w, -n, 0, i + w, n, 0, i + w, n, 0, i - w}, new float[8], c);
        }
        // the model
        if (!m.empty()) {
            Mat4 mm = new Mat4().set(base);
            if (extra != null) mm.mul(extra);
            g.model3d(sc, m, st.apply(mm, m), 0xFFFFFF);
        }
        // Steve for size
        if (steve != null) {
            Mat4 sb = new Mat4().set(base).translate(steveX, 0, 0).rotate((float) Math.toRadians(180), 0, 1, 0).scale(-1, -1, 1);
            g.playerModel(sc, steve, sb, 0, 0, null, -1);
        }
        sc.draw();
        // anchor: red cross on top of everything
        float[] t = new float[4];
        base.transform(0, 0, 0, t);
        g.fill(t[0] - 4, t[1], t[0] + 5, t[1] + 1, 0xFFFF4040);
        g.fill(t[0], t[1] - 4, t[0] + 1, t[1] + 5, 0xFFFF4040);
    }
}
