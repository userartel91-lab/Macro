package derpcraft.render;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

import static org.lwjgl.opengl.GL45C.*;

/** Vertex builder for the "simple" shader: pos3 uv2 rgba4. Can draw immediately or bake into a static mesh. */
public final class Batch {
    private static final int FLOATS = 9;
    private final int vao, vbo;
    private float[] data = new float[FLOATS * 4096];
    private int verts;
    private ByteBuffer buf = ByteBuffer.allocateDirect(1 << 16).order(ByteOrder.nativeOrder());
    private boolean isStatic;
    private int staticCount;

    public Batch() {
        vao = glGenVertexArrays();
        vbo = glGenBuffers();
        glBindVertexArray(vao);
        glBindBuffer(GL_ARRAY_BUFFER, vbo);
        glEnableVertexAttribArray(0);
        glVertexAttribPointer(0, 3, GL_FLOAT, false, FLOATS * 4, 0L);
        glEnableVertexAttribArray(1);
        glVertexAttribPointer(1, 2, GL_FLOAT, false, FLOATS * 4, 12L);
        glEnableVertexAttribArray(2);
        glVertexAttribPointer(2, 4, GL_FLOAT, false, FLOATS * 4, 20L);
        glBindVertexArray(0);
    }

    public Batch begin() { verts = 0; return this; }

    public Batch v(float x, float y, float z, float u, float v, float r, float g, float b, float a) {
        if ((verts + 1) * FLOATS > data.length) data = Arrays.copyOf(data, Math.max(data.length * 2, FLOATS * 64));
        int i = verts * FLOATS;
        data[i] = x; data[i + 1] = y; data[i + 2] = z; data[i + 3] = u; data[i + 4] = v;
        data[i + 5] = r; data[i + 6] = g; data[i + 7] = b; data[i + 8] = a;
        verts++;
        return this;
    }

    /** Adds a quad as two triangles (corners in order). */
    public Batch quad(float[] c, float r, float g, float b, float a) {
        int[] o = {0, 1, 2, 2, 3, 0};
        for (int k : o) v(c[k * 5], c[k * 5 + 1], c[k * 5 + 2], c[k * 5 + 3], c[k * 5 + 4], r, g, b, a);
        return this;
    }


    private void upload(int usage) {
        int bytes = verts * FLOATS * 4;
        if (buf.capacity() < bytes) buf = ByteBuffer.allocateDirect(Math.max(bytes, buf.capacity() * 2)).order(ByteOrder.nativeOrder());
        buf.clear();
        buf.asFloatBuffer().put(data, 0, verts * FLOATS);
        buf.limit(bytes);
        glBindBuffer(GL_ARRAY_BUFFER, vbo);
        glBufferData(GL_ARRAY_BUFFER, buf, usage);
    }

    /** Uploads current vertices once; later draw() calls reuse them. */
    public Batch bake() {
        upload(GL_STATIC_DRAW);
        isStatic = true;
        staticCount = verts;
        data = new float[0];
        return this;
    }

    public void draw(int mode) {
        if (!isStatic) {
            if (verts == 0) return;
            upload(GL_STREAM_DRAW);
        }
        int n = isStatic ? staticCount : verts;
        if (n == 0) return;
        glBindVertexArray(vao);
        glDrawArrays(mode, 0, n);
        glBindVertexArray(0);
    }

    public void delete() { glDeleteBuffers(vbo); glDeleteVertexArrays(vao); }
}
