package derpcraft.gun;
import java.nio.file.*;
public class Hold3PSearch {
    public static void main(String[] a) throws Exception {
        GunType t = new GunType(Path.of(a[0]).getFileName().toString().toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9_]", "_"), Path.of(a[0])); t.model();
        GunHold3P h = new GunHold3P();
        float[] rP = {-5, 2, 0}, lP = {5, 2, 0};
        float best = 1e9f; float[] bestG = null; float bestTw = 0;
        for (float tw : new float[]{0, 10, 20})
        for (float x = -6; x <= -1.5f; x += 0.5f) for (float y = 0; y <= 4; y += 0.5f) for (float z = -9; z <= -3; z += 0.5f) {
            GunHold3P.TWIST = tw;
            GunHold3P.GRIP = new float[]{x / 16, y / 16, z / 16};
            float worst = 0, stretchPen = 0;
            for (float p : new float[]{-60, 0, 60}) for (float yw : new float[]{-15, 0, 15}) {
                h.compute(t, p, yw, rP, lP);
                float[] g = new float[4], l = new float[4];
                h.gun.transform(t.rag[0], t.rag[1], t.rag[2], g); h.gun.transform(t.lag[0], t.lag[1], t.lag[2], l);
                float gap = Math.max(Hold3PTest.palmGap(rP, h.rArm, h.rStretch, g, -1), Hold3PTest.palmGap(lP, h.lArm, h.lStretch, l, 1));
                worst = Math.max(worst, gap);
                if (p == 0 && yw == 0) stretchPen = Math.abs(h.rStretch - 1) + Math.abs(h.lStretch - 1);
            }
            // hands on the points first; then arms near their natural length; the grip on the right (x < 0) and forward
            float score = worst + stretchPen * 0.03f + Math.max(0, x + 2.5f) * 0.01f + Math.max(0, z + 6) * 0.006f;
            if (score < best) { best = score; bestG = new float[]{x, y, z}; bestTw = tw; }
        }
        System.out.printf("best twist %.0f, grip (px) %.1f %.1f %.1f, score %.3f%n", bestTw, bestG[0], bestG[1], bestG[2], best);
        GunHold3P.TWIST = bestTw;
        GunHold3P.GRIP = new float[]{bestG[0] / 16, bestG[1] / 16, bestG[2] / 16};
        for (float[] lk : new float[][]{{0, 0}, {-75, 0}, {75, 0}, {0, 15}, {0, -15}, {0, 50}}) {
            h.compute(t, lk[0], lk[1], rP, lP);
            float[] g = new float[4], l = new float[4];
            h.gun.transform(t.rag[0], t.rag[1], t.rag[2], g); h.gun.transform(t.lag[0], t.lag[1], t.lag[2], l);
            System.out.printf("pitch %3.0f yaw %3.0f: stretch R %.2f L %.2f, gaps %.3f %.3f%n", lk[0], lk[1], h.rStretch, h.lStretch,
                Hold3PTest.palmGap(rP, h.rArm, h.rStretch, g, -1), Hold3PTest.palmGap(lP, h.lArm, h.lStretch, l, 1));
        }
    }
}
