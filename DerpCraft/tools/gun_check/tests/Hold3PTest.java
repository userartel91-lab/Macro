package derpcraft.gun;
import java.nio.file.*;
public class Hold3PTest {
    public static void main(String[] a) throws Exception {
        GunType t = new GunType(Path.of(a[0]).getFileName().toString().toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9_]", "_"), Path.of(a[0])); t.model();
        GunHold3P h = new GunHold3P();
        float[] rP = {-5, 2, 0}, lP = {5, 2, 0};
        for (float[] lk : new float[][]{{0, 0}, {-40, 0}, {40, 0}, {-75, 0}, {75, 0}, {0, 15}, {0, -15}}) {
            h.compute(t, lk[0], lk[1], rP, lP);
            float[] g = new float[4], l = new float[4];
            h.gun.transform(t.rag[0], t.rag[1], t.rag[2], g); h.gun.transform(t.lag[0], t.lag[1], t.lag[2], l);
            float rr = palmGap(rP, h.rArm, h.rStretch, g, -1), ll = palmGap(lP, h.lArm, h.lStretch, l, 1);
            System.out.printf("pitch %3.0f yaw %3.0f: stretch R %.2f L %.2f, palm off grip %.3f, off guard %.3f blocks%n", lk[0], lk[1], h.rStretch, h.lStretch, rr, ll);
        }
    }
    static float palmGap(float[] p, float[] ang, float st, float[] target) { return palmGap(p, ang, st, target, 0); }
    static float palmGap(float[] p, float[] ang, float st, float[] target, float side) {
        derpcraft.util.Mat4 m = new derpcraft.util.Mat4().translate(p[0] / 16f, p[1] / 16f, p[2] / 16f);
        m.rotate(ang[1], 0, 1, 0); m.rotate(ang[0], 1, 0, 0); m.scale(1, st, 1);
        float[] o = new float[4]; m.transform(side / 16f, GunHold3P.PALM / 16f / st * st, 0, o);
        return GunCheck.dist(o, target);
    }
}
