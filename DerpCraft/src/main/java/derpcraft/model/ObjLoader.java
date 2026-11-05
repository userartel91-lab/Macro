package derpcraft.model;

import java.awt.image.BufferedImage;
import java.io.BufferedReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Wavefront .obj: vertices, texture coordinates, faces (any polygon, negative indices), materials from .mtl files
 * (map_Kd texture, Kd colour, d / Tr transparency). 1 unit = 1 block; change the size in the 3D Hub.
 */
final class ObjLoader {
    private ObjLoader() {}

    private static final class Material {
        BufferedImage image;
        int color = 0xFFFFFFFF;
        boolean textured;
    }

    static Model3D load(String name, Path file) throws Exception {
        Model3D m = new Model3D(name);
        List<float[]> pos = new ArrayList<>(), tex = new ArrayList<>();
        Map<String, Material> materials = new HashMap<>();
        Map<String, Model3D.Group> groups = new HashMap<>();
        Map<String, Model3D.Bone> bones = new HashMap<>();
        Map<String, BufferedImage> files = new HashMap<>();
        Model3D.Group cur = null;
        Model3D.Bone bone = null;
        String curName = "", curObject = "";
        m.bones.add(new Model3D.Bone("", -1));
        String base = file.getFileName().toString();
        base = base.contains(".") ? base.substring(0, base.lastIndexOf('.')) : base;
        try (BufferedReader r = Files.newBufferedReader(file, StandardCharsets.ISO_8859_1)) {
            String line;
            while ((line = r.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.charAt(0) == '#') continue;
                String[] p = line.split("\\s+");
                switch (p[0]) {
                    case "v" -> pos.add(new float[]{f(p, 1), f(p, 2), f(p, 3)});
                    case "vt" -> tex.add(new float[]{f(p, 1), p.length > 2 ? f(p, 2) : 0});
                    case "mtllib" -> {
                        String lib = line.substring(6).trim();
                        try { mtl(file.getParent().resolve(lib), materials, m); } catch (Exception e) { m.warnings.add("Cannot read " + lib + ": " + e.getMessage()); }
                    }
                    case "usemtl" -> { curName = p.length > 1 ? line.substring(6).trim() : ""; cur = null; }
                    case "o", "g" -> {
                        // an object / group = a part (wheel1, door1, hood...); faces before any name belong to the root
                        String on = p.length > 1 ? line.substring(1).trim() : "";
                        if (p[0].equals("g") && on.isEmpty()) on = "";
                        curObject = on; cur = null; bone = null;
                    }
                    case "f" -> {
                        if (cur == null) {
                            // texture: the material's map_Kd, else a png named like the material, the object, the object without
                            // its number (wheel1 -> wheel.png) or the file (nexia.png); otherwise the material colour
                            Material mat = materials.get(curName);
                            BufferedImage img = mat != null && mat.textured ? mat.image : null;
                            String key;
                            if (img != null) key = "m:" + curName;
                            else {
                                String found = null;
                                for (String cand : new String[]{curName, curObject, stripNumber(curObject), base}) {
                                    if (cand == null || cand.isEmpty()) continue;
                                    BufferedImage fi = files.computeIfAbsent(cand.toLowerCase(Locale.ROOT), k -> pngNear(file.getParent(), cand));
                                    if (fi != null) { img = fi; found = cand.toLowerCase(Locale.ROOT); break; }
                                }
                                key = found != null ? "f:" + found + "|" + curName : "c:" + curName;
                            }
                            final BufferedImage fimg = img;
                            final Material fmat = mat;
                            cur = groups.computeIfAbsent(key, k -> {
                                Model3D.Group g = new Model3D.Group();
                                if (fimg != null) { g.image = fimg; g.color = fmat != null && !fmat.textured ? (fmat.color & 0xFF000000) | 0xFFFFFF : fmat != null ? fmat.color : 0xFFFFFFFF; }
                                else if (fmat != null) g.color = fmat.color;
                                else g.color = 0xFFC8C8C8;
                                m.groups.add(g);
                                return g;
                            });
                        }
                        if (bone == null) {
                            final String on = curObject;
                            bone = on.isEmpty() ? m.bones.get(0) : bones.computeIfAbsent(on, k -> {
                                Model3D.Bone b = new Model3D.Bone(k, 0);
                                b.pivotSet = false;
                                m.bones.add(b);
                                return b;
                            });
                        }
                        List<float[]> poly = new ArrayList<>();
                        for (int i = 1; i < p.length; i++) {
                            String[] idx = p[i].split("/");
                            int vi = index(idx[0], pos.size());
                            if (vi < 0 || vi >= pos.size()) continue;
                            float[] v = pos.get(vi);
                            float u = 0, vv = 0;
                            if (idx.length > 1 && !idx[1].isEmpty()) {
                                int ti = index(idx[1], tex.size());
                                if (ti >= 0 && ti < tex.size()) { u = tex.get(ti)[0]; vv = 1 - tex.get(ti)[1]; }
                            }
                            poly.add(new float[]{v[0], v[1], v[2], u, vv});
                        }
                        Model3D.Group bg = bone.group(cur);
                        for (int i = 1; i + 1 < poly.size(); i++) {
                            m.tri(cur, poly.get(0), poly.get(i), poly.get(i + 1));
                            bg.copyTri(cur, cur.tris - 1);
                        }
                    }
                    default -> {}
                }
            }
        }
        m.groups.removeIf(g -> g.tris == 0);
        if (m.bones.size() == 1 && m.bones.get(0).triangles() == 0) m.bones.clear();
        return m;
    }

    /** "wheel1" -> "wheel", "door_2" -> "door" (a shared texture for numbered parts). */
    static String stripNumber(String s) {
        if (s == null) return null;
        String t = s.replaceAll("[_\\-. ]*[0-9]+$", "");
        return t.equals(s) ? null : t;
    }

    private static BufferedImage pngNear(Path dir, String name) {
        for (String n : new String[]{name + ".png", name.toLowerCase(Locale.ROOT) + ".png", "textures/" + name + ".png"}) {
            BufferedImage img = Models.image(dir.resolve(n));
            if (img != null) return img;
        }
        return null;
    }

    private static int index(String s, int size) {
        try {
            int i = Integer.parseInt(s);
            return i < 0 ? size + i : i - 1;
        } catch (NumberFormatException e) { return -1; }
    }

    private static float f(String[] p, int i) {
        try { return i < p.length ? Float.parseFloat(p[i]) : 0; } catch (NumberFormatException e) { return 0; }
    }

    private static void mtl(Path f, Map<String, Material> out, Model3D m) throws Exception {
        if (!Files.isRegularFile(f)) { m.warnings.add("Material file not found: " + f.getFileName()); return; }
        Material cur = null;
        for (String line : Files.readAllLines(f, StandardCharsets.ISO_8859_1)) {
            line = line.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            String[] p = line.split("\\s+");
            switch (p[0]) {
                case "newmtl" -> { cur = new Material(); out.put(line.substring(6).trim(), cur); }
                case "Kd" -> {
                    if (cur == null || cur.textured) break;
                    int r = Math.round(Math.min(1, f(p, 1)) * 255), g = Math.round(Math.min(1, f(p, 2)) * 255), b = Math.round(Math.min(1, f(p, 3)) * 255);
                    cur.color = (cur.color & 0xFF000000) | (r << 16) | (g << 8) | b;
                }
                case "d" -> { if (cur != null) cur.color = (cur.color & 0xFFFFFF) | (Math.round(Math.max(0, Math.min(1, f(p, 1))) * 255) << 24); }
                case "Tr" -> { if (cur != null) cur.color = (cur.color & 0xFFFFFF) | (Math.round(Math.max(0, Math.min(1, 1 - f(p, 1))) * 255) << 24); }
                case "map_Kd" -> {
                    if (cur == null) break;
                    String tn = p[p.length - 1];          // last token (options like -s come before)
                    Path tp = f.getParent().resolve(tn.replace('\\', '/'));
                    BufferedImage img = Models.image(tp);
                    if (img == null) img = Models.image(f.getParent().resolve(Path.of(tn.replace('\\', '/')).getFileName().toString()));
                    if (img == null) { m.warnings.add("Texture not found: " + tn); img = Models.missing(); }
                    cur.image = img;
                    cur.textured = true;
                    cur.color = (cur.color & 0xFF000000) | 0xFFFFFF;    // the texture carries the colour
                }
                default -> {}
            }
        }
    }
}
