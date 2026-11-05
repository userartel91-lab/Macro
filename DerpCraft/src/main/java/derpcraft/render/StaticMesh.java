package derpcraft.render;

import derpcraft.model.Model3D;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

import static org.lwjgl.opengl.GL45C.*;

/**
 * Triangles of a model part uploaded to the GPU once (vehicles): drawn with a matrix and a light colour instead of being
 * transformed on the CPU every frame. Same vertex layout as {@link ModelBatch}; one range per texture group.
 */
public final class StaticMesh {
    private static final int FLOATS = 11;
    final int vao, vbo;
    /** Per range: first vertex, vertex count. */
    final List<int[]> ranges = new ArrayList<>();
    final List<Model3D.Group> groups = new ArrayList<>();
    public final int triangles;

    public StaticMesh(List<Model3D.Group> src) {
        int n = 0;
        for (Model3D.Group g : src) n += g.tris;
        triangles = n;
        ByteBuffer b = ByteBuffer.allocateDirect(Math.max(1, n * 3 * FLOATS * 4)).order(ByteOrder.nativeOrder());
        var fb = b.asFloatBuffer();
        int first = 0;
        for (Model3D.Group g : src) {
            if (g.tris == 0) continue;
            float cr = ((g.color >> 16) & 255) / 255f, cg = ((g.color >> 8) & 255) / 255f, cb = (g.color & 255) / 255f, ca = (g.color >>> 24) / 255f;
            boolean textured = g.image != null || g.shared != null && g.shared.image != null;
            for (int t = 0; t < g.tris; t++) {
                float sh = g.shade[t];
                for (int k = 0; k < 3; k++) {
                    int o = t * 15 + k * 5;
                    fb.put(g.v[o]).put(g.v[o + 1]).put(g.v[o + 2]).put(g.v[o + 3]).put(g.v[o + 4]);
                    fb.put(cr * sh).put(cg * sh).put(cb * sh).put(ca);
                    fb.put(textured ? -1 : -2).put(0);
                }
            }
            ranges.add(new int[]{first, g.tris * 3});
            groups.add(g);
            first += g.tris * 3;
        }
        vao = glGenVertexArrays();
        vbo = glGenBuffers();
        glBindVertexArray(vao);
        glBindBuffer(GL_ARRAY_BUFFER, vbo);
        glBufferData(GL_ARRAY_BUFFER, b, GL_STATIC_DRAW);
        glEnableVertexAttribArray(0);
        glVertexAttribPointer(0, 3, GL_FLOAT, false, FLOATS * 4, 0L);
        glEnableVertexAttribArray(1);
        glVertexAttribPointer(1, 2, GL_FLOAT, false, FLOATS * 4, 12L);
        glEnableVertexAttribArray(2);
        glVertexAttribPointer(2, 4, GL_FLOAT, false, FLOATS * 4, 20L);
        glEnableVertexAttribArray(3);
        glVertexAttribPointer(3, 1, GL_FLOAT, false, FLOATS * 4, 36L);
        glEnableVertexAttribArray(4);
        glVertexAttribPointer(4, 1, GL_FLOAT, false, FLOATS * 4, 40L);
        glBindVertexArray(0);
    }

    public void delete() { glDeleteBuffers(vbo); glDeleteVertexArrays(vao); }
}
