package derpcraft.gun;
import java.nio.file.*;
public class SmoothTest {
    public static void main(String[] a) throws Exception {
        for (float sm : new float[]{0, 1, 2}) {
            GunType t = new GunType(Path.of(a[0]).getFileName().toString().toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9_]", "_"), Path.of(a[0])); t.model();
            t.poseTurn[4][2] = 90; t.poseSmooth = sm;
            GunAnimator an = new GunAnimator(); an.equip();
            for (int i = 0; i < 120; i++) an.update(t, 1 / 60f);
            an.reloading = true; an.reloadEmpty = true;
            float maxRate = 0, prevAng = Float.NaN, reach = -1; float time = t.reloadEmptyTime;
            for (float s = 0; s <= time + 0.5f; s += 1 / 60f) {
                an.reload = Math.min(1, s / time); if (s > time) an.reloading = false;
                var p = an.update(t, 1 / 60f);
                // roll of the gun: angle of its x axis around the view z
                float ang = (float) Math.toDegrees(Math.atan2(p.gun.m[1], p.gun.m[0]));
                if (!Float.isNaN(prevAng)) maxRate = Math.max(maxRate, Math.abs(ang - prevAng) * 60);
                prevAng = ang;
            }
            System.out.printf("poseSmooth %.0f: fastest roll %.0f deg/s%n", sm, maxRate);
        }
    }
}
