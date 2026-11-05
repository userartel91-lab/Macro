package derpcraft.gun;
import java.nio.file.*;
public class SaveTest {
    public static void main(String[] a) throws Exception {
        Path d = Path.of(a[0]);
        String id = d.getFileName().toString().toLowerCase(java.util.Locale.ROOT); Files.writeString(d.resolve(id + ".json"), Files.readString(d.resolve(id + ".json")).replace("\"volume\": 1", "\"volume\": 1, \"myCustomKey\": [1, \"x\"]"));
        GunType t = new GunType(id, d); t.model();
        t.rpm = 777; t.hipPosition[1] = -0.3f; t.shoulderLeft[0] = -0.41f; t.auto = false; t.boltRight = false; t.rArmTurn[4][0] = 12;
        t.pointOverrides.put("sight", new float[]{0.03f, 0.35f, -0.06f}); t.applyPoints();
        t.save();
        System.out.println("saved " + id + ".json, read back:");
        GunType u = new GunType(id, d); u.model();
        for (GunType.Param p : GunType.PARAMS) if (Math.abs(p.value(t) - p.value(u)) > 1e-3) System.out.println("MISMATCH " + p.key() + p.index());
        System.out.println("auto " + u.auto + ", bolt hand right " + u.boltRight + ", sight " + java.util.Arrays.toString(u.sightPoint) + " warnings " + u.warnings);
        // the gun editor's undo: toJson / fromJson give back every value, the reload style and the point overrides
        String before = u.toJson();
        u.rpm = 123; u.pistol = !u.pistol; u.slideLock = !u.slideLock; u.pointOverrides.put("boltBack", new float[]{0, 0.2f, 0.5f}); u.applyPoints();
        u.fromJson(before);
        System.out.println("undo round trip: " + (before.equals(u.toJson()) ? "same" : "DIFFERENT") + ", bolt travel " + u.boltTravel);
        // presets: pistol, then rifle again
        GunType.posePreset(u, true);
        System.out.println("pistol preset: style " + (u.pistol ? "pistol" : "rifle") + ", slide lock " + u.slideLock + ", bolt hand " + (u.boltRight ? "right" : "left") + ", reload turn " + java.util.Arrays.toString(u.poseTurn[3]));
        GunType.posePreset(u, false);
        System.out.println("rifle preset: style " + (u.pistol ? "pistol" : "rifle") + ", reload turn " + java.util.Arrays.toString(u.poseTurn[3]));
    }
}
