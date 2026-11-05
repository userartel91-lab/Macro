package derpcraft.model;

import derpcraft.render.Texture2D;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;

/**
 * A 3D model loaded from game/3d (Blockbench .json / .bbmodel or .obj): triangles grouped by texture, in blocks,
 * y up. How it sits in the world (anchor point, size, turn) is in {@link ModelSettings}.
 */
public final class Model3D {
    /** Path inside game/3d with '/' (also the model's name). */
    public final String name;
    public final List<Group> groups = new ArrayList<>();
    /** Bounds of the raw mesh (blocks). */
    public float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE, minZ = Float.MAX_VALUE, maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE, maxZ = -Float.MAX_VALUE;
    /** Why loading failed (shown in the 3D Hub), or null. */
    public String error;
    public final List<String> warnings = new ArrayList<>();
    /**
     * Blockbench groups (.bbmodel outliner) with the triangles of the elements directly inside them: bone 0 is the root
     * (elements outside any group). Empty for other formats. Vehicles animate bones (wheels, doors, steering wheel).
     */
    public final List<Bone> bones = new ArrayList<>();

    /** A Blockbench group: name, parent bone, pivot (model space, blocks) and its own triangles per texture. */
    public static final class Bone {
        public final String name;
        public final int parent;
        public float px, py, pz;
        public final List<Group> groups = new ArrayList<>();
        /** False when the format has no pivots (OBJ objects): vehicles find one from the shape of the part. */
        public boolean pivotSet = true;

        public Bone(String name, int parent) { this.name = name; this.parent = parent; }

        /** This bone's copy of a model texture group (shares the texture). */
        public Group group(Group of) {
            for (Group g : groups) if (g.shared == of) return g;
            Group g = new Group();
            g.shared = of;
            g.image = of.image;
            g.color = of.color;
            groups.add(g);
            return g;
        }

        public int triangles() { int n = 0; for (Group g : groups) n += g.tris; return n; }
    }

    /** Triangles sharing a texture (or a plain colour). */
    public static final class Group {
        public BufferedImage image;
        /** Created on the render thread the first time it is drawn. */
        public Texture2D tex;
        /** ARGB colour multiplied with the texture (OBJ material colour). */
        public int color = 0xFFFFFFFF;
        /** Per vertex x, y, z, u, v; three vertices per triangle. */
        public float[] v = new float[15 * 64];
        /** Shade per triangle (light from above like Minecraft). */
        public float[] shade = new float[64];
        public int tris;
        /** Bone groups: the model's group whose texture this one uses (null = its own). */
        public Group shared;

        /** Copies triangle i of another group (vertices and shade). */
        public void copyTri(Group from, int i) {
            if ((tris + 1) * 15 > v.length) { v = java.util.Arrays.copyOf(v, v.length * 2); shade = java.util.Arrays.copyOf(shade, shade.length * 2); }
            System.arraycopy(from.v, i * 15, v, tris * 15, 15);
            shade[tris] = from.shade[i];
            tris++;
        }

        public void tri(float[] a, float[] b, float[] c) {
            if ((tris + 1) * 15 > v.length) { v = java.util.Arrays.copyOf(v, v.length * 2); shade = java.util.Arrays.copyOf(shade, shade.length * 2); }
            int i = tris * 15;
            System.arraycopy(a, 0, v, i, 5);
            System.arraycopy(b, 0, v, i + 5, 5);
            System.arraycopy(c, 0, v, i + 10, 5);
            shade[tris] = shadeOf(a, b, c);
            tris++;
        }

        public Texture2D texture() {
            if (shared != null) return shared.texture();
            if (tex == null && image != null) tex = new Texture2D(image, false, true);
            return tex;
        }
    }

    public Model3D(String name) { this.name = name; }

    public int triangles() { int n = 0; for (Group g : groups) n += g.tris; return n; }

    public boolean empty() { return triangles() == 0; }

    /** Adds a triangle (each vertex x, y, z, u, v) to a group and grows the bounds. */
    public void tri(Group g, float[] a, float[] b, float[] c) {
        g.tri(a, b, c);
        grow(a); grow(b); grow(c);
    }

    private void grow(float[] p) {
        minX = Math.min(minX, p[0]); minY = Math.min(minY, p[1]); minZ = Math.min(minZ, p[2]);
        maxX = Math.max(maxX, p[0]); maxY = Math.max(maxY, p[1]); maxZ = Math.max(maxZ, p[2]);
    }

    public float sizeX() { return empty() ? 0 : maxX - minX; }
    public float sizeY() { return empty() ? 0 : maxY - minY; }
    public float sizeZ() { return empty() ? 0 : maxZ - minZ; }

    /** Minecraft-like face shading from the triangle's normal: top 1, sides 0.8 / 0.6, bottom 0.5. */
    static float shadeOf(float[] a, float[] b, float[] c) {
        float ux = b[0] - a[0], uy = b[1] - a[1], uz = b[2] - a[2];
        float vx = c[0] - a[0], vy = c[1] - a[1], vz = c[2] - a[2];
        float nx = uy * vz - uz * vy, ny = uz * vx - ux * vz, nz = ux * vy - uy * vx;
        float l = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
        if (l < 1e-12f) return 0.8f;
        nx /= l; ny /= l; nz /= l;
        float up = ny >= 0 ? ny * 1.0f : -ny * 0.5f;
        float side = Math.abs(nx) * 0.6f + Math.abs(nz) * 0.8f;
        return Math.max(0.45f, Math.min(1, up + side * (1 - Math.abs(ny))));
    }

    /** Frees the GPU textures (the model is reloaded). */
    public void dispose() {
        for (Group g : groups) if (g.tex != null) { g.tex.delete(); g.tex = null; }
    }
}
