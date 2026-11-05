package derpcraft.gun;
import java.nio.file.*;
public class SyncTest {
    public static void main(String[] a) throws Exception {
        GunType t = new GunType(Path.of(a[0]).getFileName().toString().toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9_]", "_"), Path.of(a[0])); t.model();
        t.gunTurn[4][2] = 90; t.armsMove[4][1] = 0.04f;
        GunAnimator an = new GunAnimator(); an.equip();
        for (int i = 0; i < 120; i++) an.update(t, 1 / 60f);
        var f = GunAnimator.class.getDeclaredField("layer"); f.setAccessible(true);
        an.reloading = true; an.reloadEmpty = true; an.reload = 0.85f;     // bolt pose on
        float worst = 0;
        for (int i = 0; i < 60; i++) {
            an.update(t, 1 / 60f);
            float[] L = (float[]) f.get(an);
            float gun = L[11] / 90f, arms = L[13] / 0.04f;     // progress of the gun's roll and of the arms' move
            worst = Math.max(worst, Math.abs(gun - arms));
            if (i % 6 == 0) System.out.printf("frame %2d: gun %.2f arms %.2f%n", i, gun, arms);
        }
        System.out.printf("largest difference in progress: %.3f%n", worst);
    }
}
