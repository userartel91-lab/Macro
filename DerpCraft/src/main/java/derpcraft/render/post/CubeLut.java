package derpcraft.render.post;

import java.io.IOException;
import java.nio.file.*;
import java.util.Locale;

/** Adobe/Resolve .cube 3D LUT (LUT_3D_SIZE; input domain assumed 0..1). Data is RGB floats, red changing fastest. */
public final class CubeLut {
    public int size;
    public float[] data;

    public static CubeLut load(Path file) throws IOException {
        CubeLut l = new CubeLut();
        float[] dmin = {0, 0, 0}, dmax = {1, 1, 1};
        int n = 0;
        for (String raw : Files.readAllLines(file)) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            String up = line.toUpperCase(Locale.ROOT);
            String[] p = line.split("\\s+");
            if (up.startsWith("TITLE") || up.startsWith("LUT_1D")) {
                if (up.startsWith("LUT_1D")) throw new IOException("1D LUTs are not supported");
                continue;
            }
            if (up.startsWith("LUT_3D_SIZE")) {
                l.size = Integer.parseInt(p[1]);
                if (l.size < 2 || l.size > 128) throw new IOException("bad LUT size " + l.size);
                l.data = new float[l.size * l.size * l.size * 3];
                continue;
            }
            if (up.startsWith("DOMAIN_MIN")) { for (int i = 0; i < 3; i++) dmin[i] = Float.parseFloat(p[i + 1]); continue; }
            if (up.startsWith("DOMAIN_MAX")) { for (int i = 0; i < 3; i++) dmax[i] = Float.parseFloat(p[i + 1]); continue; }
            if (Character.isLetter(up.charAt(0))) continue;
            if (l.data == null) throw new IOException("LUT_3D_SIZE missing");
            if (n * 3 + 2 >= l.data.length) break;
            for (int i = 0; i < 3; i++) l.data[n * 3 + i] = Float.parseFloat(p[i]);
            n++;
        }
        if (l.data == null || n * 3 != l.data.length) throw new IOException("LUT has " + n + " entries, expected " + (l.data == null ? 0 : l.data.length / 3));
        return l;
    }
}
