package derpcraft.model;

import derpcraft.util.Json;
import derpcraft.util.Mat4;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Blockbench models: "Java Block/Item" .json (elements with from / to, rotation, faces; textures as png files next
 * to the model or from the resource pack) and Blockbench project files .bbmodel (cubes, meshes, bones from the
 * outliner, textures embedded in the file).
 */
final class BlockbenchLoader {
    private BlockbenchLoader() {}

    private static final String[] FACES = {"north", "south", "east", "west", "up", "down"};

    static Model3D load(String name, Path file) throws Exception {
        String text = Files.readString(file, StandardCharsets.UTF_8);
        if (!text.isEmpty() && text.charAt(0) == '﻿') text = text.substring(1);
        Map<String, Object> root = Json.obj(Json.parse(text));
        Model3D m = new Model3D(name);
        if (root.containsKey("meta") || root.containsKey("outliner") || name.toLowerCase(Locale.ROOT).endsWith(".bbmodel")) project(m, root, file);
        else javaModel(m, root, file);
        return m;
    }

    // ================================================================== Java block / item model
    private static void javaModel(Model3D m, Map<String, Object> root, Path file) {
        Map<String, Object> textures = Json.obj(root.get("textures"));
        Map<String, Model3D.Group> groups = new HashMap<>();
        List<Object> elements = Json.arr(root.get("elements"));
        if (elements.isEmpty()) { m.error = root.containsKey("parent") ? "Only a parent model (\"" + root.get("parent") + "\"), no elements of its own" : "No elements"; return; }
        Mat4 px = new Mat4().scale(1 / 16f, 1 / 16f, 1 / 16f);
        // Blockbench exports its groups (name, origin, children = element indices or nested groups): they become bones
        int[] boneOfElement = new int[elements.size()];
        List<Object> jg = Json.arr(root.get("groups"));
        if (!jg.isEmpty()) {
            m.bones.add(new Model3D.Bone("", -1));
            for (Object g : jg) javaGroup(m, g, 0, boneOfElement);
        }
        int ei = -1;
        for (Object eo : elements) {
            ei++;
            Map<String, Object> e = Json.obj(eo);
            Model3D.Bone ebone = m.bones.isEmpty() ? null : m.bones.get(boneOfElement[ei]);
            Map<Model3D.Group, Integer> before = new IdentityHashMap<>();
            for (Model3D.Group g0 : groups.values()) before.put(g0, g0.tris);
            float[] from = vec(e.get("from")), to = vec(e.get("to"));
            Mat4 xf = new Mat4().set(px);
            Map<String, Object> rot = Json.obj(e.get("rotation"));
            if (!rot.isEmpty()) {
                float[] o = vec(rot.get("origin"));
                float a = (float) Json.num(rot.get("angle"), 0);
                String axis = String.valueOf(rot.getOrDefault("axis", "y"));
                xf.translate(o[0], o[1], o[2]);
                float r = (float) Math.toRadians(a);
                switch (axis) { case "x" -> xf.rotate(r, 1, 0, 0); case "z" -> xf.rotate(r, 0, 0, 1); default -> xf.rotate(r, 0, 1, 0); }
                if (Boolean.TRUE.equals(rot.get("rescale")) && Math.cos(r) > 0.1) {
                    float s = (float) (1 / Math.cos(r));
                    switch (axis) { case "x" -> xf.scale(1, s, s); case "z" -> xf.scale(s, s, 1); default -> xf.scale(s, 1, s); }
                }
                xf.translate(-o[0], -o[1], -o[2]);
            }
            Map<String, Object> faces = Json.obj(e.get("faces"));
            for (String fn : FACES) {
                Map<String, Object> f = Json.obj(faces.get(fn));
                if (f.isEmpty()) continue;
                String ref = String.valueOf(f.getOrDefault("texture", ""));
                Model3D.Group g = groups.computeIfAbsent(ref, k -> {
                    Model3D.Group ng = new Model3D.Group();
                    ng.image = javaTexture(m, textures, k, file);
                    m.groups.add(ng);
                    return ng;
                });
                float[] uv = f.containsKey("uv") ? vec4(f.get("uv")) : defaultUv(fn, from, to);
                for (int i = 0; i < 4; i++) uv[i] /= 16f;
                cubeFace(m, g, xf, fn, from, to, uv, (int) Json.num(f.get("rotation"), 0));
            }
            if (ebone != null)
                for (Model3D.Group g0 : groups.values()) {
                    int b0 = before.getOrDefault(g0, 0);
                    if (g0.tris <= b0) continue;
                    Model3D.Group bg = ebone.group(g0);
                    for (int i = b0; i < g0.tris; i++) bg.copyTri(g0, i);
                }
        }
    }

    /** A group of an exported Java model (and its sub-groups): a bone with the group's origin as the pivot. */
    private static void javaGroup(Model3D m, Object o, int parent, int[] boneOfElement) {
        if (o instanceof Number n) { int i = n.intValue(); if (i >= 0 && i < boneOfElement.length) boneOfElement[i] = parent; return; }
        Map<String, Object> g = Json.obj(o);
        if (g.isEmpty()) return;
        Model3D.Bone b = new Model3D.Bone(String.valueOf(g.getOrDefault("name", "")), parent);
        float[] or = vec(g.get("origin"));
        b.px = or[0] / 16f; b.py = or[1] / 16f; b.pz = or[2] / 16f;
        int idx = m.bones.size();
        m.bones.add(b);
        for (Object c : Json.arr(g.get("children"))) javaGroup(m, c, idx, boneOfElement);
    }

    private static BufferedImage javaTexture(Model3D m, Map<String, Object> textures, String ref, Path file) {
        String v = ref;
        for (int i = 0; i < 8 && v.startsWith("#"); i++) {
            Object t = textures.get(v.substring(1));
            if (t == null) break;
            v = String.valueOf(t);
        }
        if (v.isEmpty() || v.startsWith("#")) { m.warnings.add("Texture " + ref + " is not set"); return Models.missing(); }
        if (v.startsWith("data:")) { BufferedImage d = dataUri(v); if (d != null) return d; }
        String ns = "minecraft", path = v;
        int c = v.indexOf(':');
        if (c > 0) { ns = v.substring(0, c); path = v.substring(c + 1); }
        String base = path.contains("/") ? path.substring(path.lastIndexOf('/') + 1) : path;
        Path d = file.getParent(), root = Models.dir() != null ? Models.dir() : d;
        Path[] tries = {d.resolve(path + ".png"), d.resolve(base + ".png"), d.resolve("textures").resolve(path + ".png"), d.resolve("textures").resolve(base + ".png"),
                root.resolve(path + ".png"), root.resolve(base + ".png"), root.resolve("textures").resolve(path + ".png"), d.resolve(path), d.resolve(base)};
        for (Path p : tries) { BufferedImage img = Models.image(p); if (img != null) return animated(img); }
        BufferedImage img = Models.vanillaImages.apply("assets/" + ns + "/textures/" + path + ".png");
        if (img != null) return animated(img);
        m.warnings.add("Texture not found: " + v + " (put " + base + ".png next to the model)");
        return Models.missing();
    }

    /** Animated textures (a strip of square frames): the first frame. */
    private static BufferedImage animated(BufferedImage img) {
        if (img.getHeight() > img.getWidth() && img.getHeight() % img.getWidth() == 0) return img.getSubimage(0, 0, img.getWidth(), img.getWidth());
        return img;
    }

    private static float[] defaultUv(String face, float[] f, float[] t) {
        return switch (face) {
            case "north" -> new float[]{16 - t[0], 16 - t[1], 16 - f[0], 16 - f[1]};
            case "south" -> new float[]{f[0], 16 - t[1], t[0], 16 - f[1]};
            case "east" -> new float[]{16 - t[2], 16 - t[1], 16 - f[2], 16 - f[1]};
            case "west" -> new float[]{f[2], 16 - t[1], t[2], 16 - f[1]};
            case "up" -> new float[]{f[0], f[2], t[0], t[2]};
            default -> new float[]{f[0], 16 - t[2], t[0], 16 - f[2]};
        };
    }

    // ================================================================== Blockbench project (.bbmodel)
    private static void project(Model3D m, Map<String, Object> root, Path file) {
        Map<String, Object> res = Json.obj(root.get("resolution"));
        float resW = (float) Json.num(res.get("width"), 16), resH = (float) Json.num(res.get("height"), 16);
        // textures
        List<Object> tl = Json.arr(root.get("textures"));
        List<Model3D.Group> groups = new ArrayList<>();
        List<float[]> uvSize = new ArrayList<>();
        for (Object to : tl) {
            Map<String, Object> t = Json.obj(to);
            Model3D.Group g = new Model3D.Group();
            BufferedImage img = null;
            Object src = t.get("source");
            if (src instanceof String s && s.startsWith("data:")) img = dataUri(s);
            if (img == null) {
                String n = String.valueOf(t.getOrDefault("name", ""));
                String pth = String.valueOf(t.getOrDefault("path", ""));
                String fn = pth.isEmpty() ? n : Path.of(pth.replace('\\', '/')).getFileName().toString();
                if (!fn.isEmpty()) img = Models.image(file.getParent().resolve(fn));
                if (img == null && !n.isEmpty()) img = Models.image(file.getParent().resolve(n.endsWith(".png") ? n : n + ".png"));
            }
            if (img == null) { m.warnings.add("Texture missing: " + t.getOrDefault("name", "?")); img = Models.missing(); }
            g.image = animated(img);
            groups.add(g);
            float uw = (float) Json.num(t.get("uv_width"), resW), uh = (float) Json.num(t.get("uv_height"), resH);
            uvSize.add(new float[]{uw, uh});
        }
        Model3D.Group untextured = null;
        // bone transforms from the outliner
        Map<String, Mat4> xform = new HashMap<>();
        Map<String, Map<String, Object>> groupDefs = new HashMap<>();
        for (Object go : Json.arr(root.get("groups"))) { Map<String, Object> g = Json.obj(go); groupDefs.put(String.valueOf(g.get("uuid")), g); }
        Mat4 px = new Mat4().scale(1 / 16f, 1 / 16f, 1 / 16f);
        Set<String> hidden = new HashSet<>();
        Map<String, Integer> boneOf = new HashMap<>();
        m.bones.add(new Model3D.Bone("", -1));
        for (Object o : Json.arr(root.get("outliner"))) outline(o, px, xform, groupDefs, hidden, true, m, 0, boneOf);
        for (Object eo : Json.arr(root.get("elements"))) {
            Map<String, Object> e = Json.obj(eo);
            String uuid = String.valueOf(e.get("uuid"));
            if (hidden.contains(uuid) || Boolean.FALSE.equals(e.get("visibility")) || Boolean.FALSE.equals(e.get("export"))) continue;
            Mat4 parent = xform.getOrDefault(uuid, px);
            Model3D.Bone bone = m.bones.get(boneOf.getOrDefault(uuid, 0));
            Map<Model3D.Group, Integer> before = new IdentityHashMap<>();
            for (Model3D.Group g0 : groups) before.put(g0, g0.tris);
            if (untextured != null) before.put(untextured, untextured.tris);
            String type = String.valueOf(e.getOrDefault("type", "cube"));
            float[] origin = vec(e.get("origin"));
            float[] rot = vec(e.get("rotation"));
            Mat4 xf = new Mat4().set(parent).translate(origin[0], origin[1], origin[2]);
            if (rot[2] != 0) xf.rotate((float) Math.toRadians(rot[2]), 0, 0, 1);
            if (rot[1] != 0) xf.rotate((float) Math.toRadians(rot[1]), 0, 1, 0);
            if (rot[0] != 0) xf.rotate((float) Math.toRadians(rot[0]), 1, 0, 0);
            if (type.equals("mesh")) {
                Map<String, Object> verts = Json.obj(e.get("vertices"));
                for (Object fo : Json.obj(e.get("faces")).values()) {
                    Map<String, Object> f = Json.obj(fo);
                    Object ti = f.get("texture");
                    Model3D.Group g;
                    float uw = resW, uh = resH;
                    if (ti instanceof Number n && n.intValue() >= 0 && n.intValue() < groups.size()) { g = groups.get(n.intValue()); uw = uvSize.get(n.intValue())[0]; uh = uvSize.get(n.intValue())[1]; }
                    else { if (untextured == null) { untextured = new Model3D.Group(); untextured.color = 0xFFB0B0B0; m.groups.add(untextured); } g = untextured; }
                    List<Object> keys = Json.arr(f.get("vertices"));
                    Map<String, Object> uvs = Json.obj(f.get("uv"));
                    List<float[]> poly = new ArrayList<>();
                    float[] t4 = new float[4];
                    for (Object k : keys) {
                        float[] p = vec(verts.get(String.valueOf(k)));
                        float[] uv = vec2(uvs.get(String.valueOf(k)));
                        xf.transform(p[0], p[1], p[2], t4);
                        poly.add(new float[]{t4[0], t4[1], t4[2], uv[0] / uw, uv[1] / uh});
                    }
                    polygon(m, g, poly);
                }
            } else {
                float[] from = vec(e.get("from")), to = vec(e.get("to"));
                float inf = (float) Json.num(e.get("inflate"), 0);
                for (int k = 0; k < 3; k++) { from[k] -= inf; to[k] += inf; }
                Mat4 cube = new Mat4().set(xf).translate(-origin[0], -origin[1], -origin[2]);
                Map<String, Object> faces = Json.obj(e.get("faces"));
                for (String fn : FACES) {
                    Map<String, Object> f = Json.obj(faces.get(fn));
                    if (f.isEmpty()) continue;
                    Object ti = f.get("texture");
                    if (!(ti instanceof Number n) || n.intValue() < 0 || n.intValue() >= groups.size()) continue;   // face without texture: not drawn
                    float[] uv = vec4(f.get("uv"));
                    float[] sz = uvSize.get(n.intValue());
                    uv[0] /= sz[0]; uv[2] /= sz[0]; uv[1] /= sz[1]; uv[3] /= sz[1];
                    cubeFace(m, groups.get(n.intValue()), cube, fn, from, to, uv, (int) Json.num(f.get("rotation"), 0));
                }
            }
            // the element's new triangles also go to its bone
            List<Model3D.Group> touched = new ArrayList<>(groups);
            if (untextured != null) touched.add(untextured);
            for (Model3D.Group g0 : touched) {
                int b0 = before.getOrDefault(g0, 0);
                if (g0.tris <= b0) continue;
                Model3D.Group bg = bone.group(g0);
                for (int i = b0; i < g0.tris; i++) bg.copyTri(g0, i);
            }
        }
        for (Model3D.Group g : groups) if (g.tris > 0 && !m.groups.contains(g)) m.groups.add(g);
    }

    /** Walks the outliner: a group turns everything inside it around its origin. */
    private static void outline(Object o, Mat4 parent, Map<String, Mat4> xform, Map<String, Map<String, Object>> defs, Set<String> hidden, boolean visible,
                                Model3D model, int parentBone, Map<String, Integer> boneOf) {
        if (o instanceof String uuid) { xform.put(uuid, parent); boneOf.put(uuid, parentBone); if (!visible) hidden.add(uuid); return; }
        Map<String, Object> g = Json.obj(o);
        if (g.isEmpty()) return;
        Map<String, Object> def = defs.getOrDefault(String.valueOf(g.get("uuid")), g);
        float[] origin = vec(def.containsKey("origin") ? def.get("origin") : g.get("origin"));
        float[] rot = vec(def.containsKey("rotation") ? def.get("rotation") : g.get("rotation"));
        boolean vis = visible && !Boolean.FALSE.equals(def.get("visibility")) && !Boolean.FALSE.equals(def.get("export"));
        Mat4 m = new Mat4().set(parent).translate(origin[0], origin[1], origin[2]);
        if (rot[2] != 0) m.rotate((float) Math.toRadians(rot[2]), 0, 0, 1);
        if (rot[1] != 0) m.rotate((float) Math.toRadians(rot[1]), 0, 1, 0);
        if (rot[0] != 0) m.rotate((float) Math.toRadians(rot[0]), 1, 0, 0);
        m.translate(-origin[0], -origin[1], -origin[2]);
        Object nm = def.containsKey("name") ? def.get("name") : g.get("name");
        Model3D.Bone bone = new Model3D.Bone(nm == null ? "" : String.valueOf(nm), parentBone);
        float[] t = new float[4];
        parent.transform(origin[0], origin[1], origin[2], t);
        bone.px = t[0]; bone.py = t[1]; bone.pz = t[2];
        int idx = model.bones.size();
        model.bones.add(bone);
        for (Object c : Json.arr(g.get("children"))) outline(c, m, xform, defs, hidden, vis, model, idx, boneOf);
    }

    // ================================================================== shared geometry
    /**
     * One face of a cube (pixel units, y up, north = -z), uv {u0, v0, u1, v1} normalised, rotated by 0/90/180/270.
     */
    static void cubeFace(Model3D m, Model3D.Group g, Mat4 xf, String face, float[] f, float[] t, float[] uv, int rotation) {
        float x0 = f[0], y0 = f[1], z0 = f[2], x1 = t[0], y1 = t[1], z1 = t[2];
        float[][] c = switch (face) {      // corners: top left, bottom left, bottom right, top right (as the texture is seen)
            case "north" -> new float[][]{{x1, y1, z0}, {x1, y0, z0}, {x0, y0, z0}, {x0, y1, z0}};
            case "south" -> new float[][]{{x0, y1, z1}, {x0, y0, z1}, {x1, y0, z1}, {x1, y1, z1}};
            case "east" -> new float[][]{{x1, y1, z1}, {x1, y0, z1}, {x1, y0, z0}, {x1, y1, z0}};
            case "west" -> new float[][]{{x0, y1, z0}, {x0, y0, z0}, {x0, y0, z1}, {x0, y1, z1}};
            case "up" -> new float[][]{{x0, y1, z0}, {x0, y1, z1}, {x1, y1, z1}, {x1, y1, z0}};
            default -> new float[][]{{x0, y0, z1}, {x0, y0, z0}, {x1, y0, z0}, {x1, y0, z1}};
        };
        float[][] uvs = {{uv[0], uv[1]}, {uv[0], uv[3]}, {uv[2], uv[3]}, {uv[2], uv[1]}};
        int r = Math.floorMod(Math.round(rotation / 90f), 4);
        float[][] v = new float[4][];
        float[] tmp = new float[4];
        for (int i = 0; i < 4; i++) {
            xf.transform(c[i][0], c[i][1], c[i][2], tmp);
            float[] u = uvs[(i + r) % 4];
            v[i] = new float[]{tmp[0], tmp[1], tmp[2], u[0], u[1]};
        }
        // skip faces with no area (flat planes keep their two big faces)
        if (area(v[0], v[1], v[2]) < 1e-10f && area(v[0], v[2], v[3]) < 1e-10f) return;
        m.tri(g, v[0], v[1], v[2]);
        m.tri(g, v[0], v[2], v[3]);
    }

    private static float area(float[] a, float[] b, float[] c) {
        float ux = b[0] - a[0], uy = b[1] - a[1], uz = b[2] - a[2], vx = c[0] - a[0], vy = c[1] - a[1], vz = c[2] - a[2];
        float nx = uy * vz - uz * vy, ny = uz * vx - ux * vz, nz = ux * vy - uy * vx;
        return nx * nx + ny * ny + nz * nz;
    }

    /** A polygon (3+ vertices x,y,z,u,v) as triangles; quads are put in order around their centre first. */
    static void polygon(Model3D m, Model3D.Group g, List<float[]> poly) {
        if (poly.size() < 3) return;
        if (poly.size() == 4) poly = sortAround(poly);
        for (int i = 1; i + 1 < poly.size(); i++) m.tri(g, poly.get(0), poly.get(i), poly.get(i + 1));
    }

    private static List<float[]> sortAround(List<float[]> p) {
        float cx = 0, cy = 0, cz = 0;
        for (float[] v : p) { cx += v[0] / 4; cy += v[1] / 4; cz += v[2] / 4; }
        float[] a = p.get(0), b = p.get(1), c = p.get(2);
        float ux = b[0] - a[0], uy = b[1] - a[1], uz = b[2] - a[2], vx = c[0] - a[0], vy = c[1] - a[1], vz = c[2] - a[2];
        float nx = uy * vz - uz * vy, ny = uz * vx - ux * vz, nz = ux * vy - uy * vx;
        // basis in the face plane
        float ex = a[0] - cx, ey = a[1] - cy, ez = a[2] - cz;
        float fx = ny * ez - nz * ey, fy = nz * ex - nx * ez, fz = nx * ey - ny * ex;
        List<float[]> s = new ArrayList<>(p);
        final float Ex = ex, Ey = ey, Ez = ez, Fx = fx, Fy = fy, Fz = fz, Cx = cx, Cy = cy, Cz = cz;
        s.sort(Comparator.comparingDouble(v -> Math.atan2((v[0] - Cx) * Fx + (v[1] - Cy) * Fy + (v[2] - Cz) * Fz, (v[0] - Cx) * Ex + (v[1] - Cy) * Ey + (v[2] - Cz) * Ez)));
        return s;
    }

    // ================================================================== json helpers
    private static float[] vec(Object o) {
        List<Object> l = Json.arr(o);
        float[] v = new float[3];
        for (int i = 0; i < 3 && i < l.size(); i++) v[i] = (float) Json.num(l.get(i), 0);
        return v;
    }

    private static float[] vec2(Object o) {
        List<Object> l = Json.arr(o);
        return new float[]{l.size() > 0 ? (float) Json.num(l.get(0), 0) : 0, l.size() > 1 ? (float) Json.num(l.get(1), 0) : 0};
    }

    private static float[] vec4(Object o) {
        List<Object> l = Json.arr(o);
        float[] v = new float[4];
        for (int i = 0; i < 4 && i < l.size(); i++) v[i] = (float) Json.num(l.get(i), 0);
        return v;
    }

    static BufferedImage dataUri(String s) {
        try {
            int c = s.indexOf(',');
            byte[] b = Base64.getDecoder().decode(s.substring(c + 1).trim());
            return javax.imageio.ImageIO.read(new ByteArrayInputStream(b));
        } catch (Exception e) { return null; }
    }
}
