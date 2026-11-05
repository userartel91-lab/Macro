package derpcraft.render;

import derpcraft.util.Mat4;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

import static org.lwjgl.opengl.GL45C.*;

/**
 * Batched 3D quads for entities, block entities, items, particles and the first-person hand.
 * Supports Minecraft's ModelBox UV layout so entity textures from resource packs map correctly.
 */
public final class ModelBatch {
    private static final int FLOATS = 11;
    private final Shader shader = new Shader("model");
    private final int vao, vbo;
    private float[] data = new float[FLOATS * 6 * 4096];
    private int verts;
    private ByteBuffer buf = ByteBuffer.allocateDirect(1 << 20).order(ByteOrder.nativeOrder());
    private int curTex = -2;
    private float layer = -1;
    private final float[] tmp = new float[4];
    /** Current RGBA colour (0..1). */
    public float r = 1, g = 1, b = 1, a = 1;
    /** Share of sky light in the current colour (0..1): how much sun shadows darken it. */
    public float sky;

    public ModelBatch() {
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
        glEnableVertexAttribArray(3);
        glVertexAttribPointer(3, 1, GL_FLOAT, false, FLOATS * 4, 36L);
        glEnableVertexAttribArray(4);
        glVertexAttribPointer(4, 1, GL_FLOAT, false, FLOATS * 4, 40L);
        glBindVertexArray(0);
    }

    public void begin(Mat4 mvp, int arrayTexture, float fogStart, float fogEnd, float fr, float fg, float fb) {
        shader.use();
        shader.set("uMVP", mvp);
        shader.set("uTex", 0);
        shader.set("uArray", 1);
        shader.set("uFog", fogStart, fogEnd);
        shader.set("uFogColor", fr, fg, fb);
        shader.set("uAlphaCut", 0.1f);
        shader.set("uLit", 0);
        LightingState.setUniforms(shader, null);
        glActiveTexture(GL_TEXTURE1);
        glBindTexture(GL_TEXTURE_2D_ARRAY, arrayTexture);
        glActiveTexture(GL_TEXTURE0);
        curTex = -2;
    }

    /** World models: receive sun / moon shadows (shadow maps, entity shadows, ray traced visibility). */
    public void lit(WorldRenderer.FrameParams fp) {
        flush();
        shader.use();
        LightingState.setUniforms(shader, fp);
        LightingState.bindTextures(fp);
        shader.set("uLit", 1);
    }

    public void alphaCut(float a) { flush(); shader.use(); shader.set("uAlphaCut", a); }

    /** Use a 2D texture (entity textures, font). */
    public void texture(Texture2D t) {
        int id = t == null ? 0 : t.id;
        if (id != curTex) { flush(); curTex = id; glActiveTexture(GL_TEXTURE0); glBindTexture(GL_TEXTURE_2D, id); }
        layer = t == null ? -2 : -1;
    }

    /** Use a layer of the block/item texture array. */
    public void layer(int l) { layer = l; }

    /** No texture, colour only. */
    public void untextured() { layer = -2; }

    public void color(float r, float g, float b, float a) { this.r = r; this.g = g; this.b = b; this.a = a; }

    public void v(float x, float y, float z, float u, float v) {
        if ((verts + 1) * FLOATS > data.length) data = Arrays.copyOf(data, data.length * 2);
        int i = verts * FLOATS;
        data[i] = x; data[i + 1] = y; data[i + 2] = z; data[i + 3] = u; data[i + 4] = v;
        data[i + 5] = r; data[i + 6] = g; data[i + 7] = b; data[i + 8] = a; data[i + 9] = layer; data[i + 10] = sky;
        verts++;
    }

    /** Transformed vertex. */
    public void v(Mat4 m, float x, float y, float z, float u, float v) {
        m.transform(x, y, z, tmp);
        v(tmp[0], tmp[1], tmp[2], u, v);
    }

    /**
     * Camera-facing square around (x, y, z) without a matrix or temporary arrays (particles: tens of thousands a frame).
     * Right vector (rx, 0, rz), up vector (ux, uy, uz), half size s; (u0, v0) at the bottom left corner, (u1, v1) top right.
     */
    public void billboard(float x, float y, float z, float rx, float rz, float ux, float uy, float uz, float s, float u0, float v0, float u1, float v1) {
        if ((verts + 6) * FLOATS > data.length) data = Arrays.copyOf(data, Math.max(data.length * 2, (verts + 6) * FLOATS));
        float ax = (rx + ux) * s, ay = uy * s, az = (rz + uz) * s;        // right + up
        float bx = (rx - ux) * s, bz = (rz - uz) * s;                    // right - up (y: -ay)
        v(x - ax, y - ay, z - az, u0, v0);
        v(x - bx, y + ay, z - bz, u0, v1);
        v(x + ax, y + ay, z + az, u1, v1);
        v(x + ax, y + ay, z + az, u1, v1);
        v(x + bx, y - ay, z + bz, u1, v0);
        v(x - ax, y - ay, z - az, u0, v0);
    }

    /** Quad from 4 corners (any winding; culling is off for models). */
    public void quad(Mat4 m, float[] p, float[] uv) {
        int[] o = {0, 1, 2, 2, 3, 0};
        for (int k : o) v(m, p[k * 3], p[k * 3 + 1], p[k * 3 + 2], uv[k * 2], uv[k * 2 + 1]);
    }

    /**
     * Draws an uploaded mesh with a matrix (camera-relative) and the current colour as its light (vehicles). Flushes the
     * batch first; the batched path is restored afterwards.
     */
    public void drawStatic(StaticMesh mesh, derpcraft.util.Mat4 model) {
        if (mesh.triangles == 0) return;
        flush();
        shader.use();
        shader.set("uUseModel", 1);
        shader.set("uModel", model);
        shader.set("uTint", r, g, b, a);
        shader.set("uSkyU", sky);
        glBindVertexArray(mesh.vao);
        for (int i = 0; i < mesh.ranges.size(); i++) {
            Texture2D t = mesh.groups.get(i).texture();
            glActiveTexture(GL_TEXTURE0);
            glBindTexture(GL_TEXTURE_2D, t == null ? 0 : t.id);
            int[] rg = mesh.ranges.get(i);
            glDrawArrays(GL_TRIANGLES, rg[0], rg[1]);
        }
        glBindVertexArray(0);
        shader.set("uUseModel", 0);
        curTex = -2;
    }

    public void flush() {
        if (verts == 0) return;
        int bytes = verts * FLOATS * 4;
        if (buf.capacity() < bytes) buf = ByteBuffer.allocateDirect(Math.max(bytes, buf.capacity() * 2)).order(ByteOrder.nativeOrder());
        buf.clear();
        buf.asFloatBuffer().put(data, 0, verts * FLOATS);
        buf.limit(bytes);
        shader.use();
        glBindVertexArray(vao);
        glBindBuffer(GL_ARRAY_BUFFER, vbo);
        glBufferData(GL_ARRAY_BUFFER, buf, GL_STREAM_DRAW);
        glDrawArrays(GL_TRIANGLES, 0, verts);
        glBindVertexArray(0);
        verts = 0;
    }

    // ------------------------------------------------------------------------------------------ Minecraft ModelBox
    /**
     * Draws a Minecraft model box (pixel units, model space) with its standard texture unwrap:
     * texture offset (u,v), texture size tw x th. Face shading mimics vanilla entity lighting.
     */
    public void box(Mat4 m, float x, float y, float z, int w, int h, int d, int u, int v, int tw, int th, float shadeTop, float shadeSide) {
        float br = r, bg = g, bb = b;
        boxFaces(x, y, z, w, h, d, u, v, tw, th, shadeTop, shadeSide, (p, uv, shade) -> {
            r = br * shade; g = bg * shade; b = bb * shade;
            int[] o = {0, 1, 2, 2, 3, 0};
            for (int k : o) v(m, p[k * 3] / 16f, p[k * 3 + 1] / 16f, p[k * 3 + 2] / 16f, uv[k * 2], uv[k * 2 + 1]);
        });
        r = br; g = bg; b = bb;
    }

    /** Receives one face of a model box: 4 corners (pixel units), normalized uvs, shade factor. */
    public interface FaceSink { void face(float[] p, float[] uv, float shade); }

    /** Generates the six faces of a Minecraft ModelBox (vanilla vertex order and texture unwrap). */
    public static void boxFaces(float x, float y, float z, int w, int h, int d, int u, int v, int tw, int th, float shadeTop, float shadeSide, FaceSink sink) {
        float x1 = x + w, y1 = y + h, z1 = z + d;
        // vanilla vertex naming: v7=(x,y,z) v0=(x1,y,z) v1=(x1,y1,z) v2=(x,y1,z) v3=(x,y,z1) v4=(x1,y,z1) v5=(x1,y1,z1) v6=(x,y1,z1)
        float[] V7 = {x, y, z}, V0 = {x1, y, z}, V1 = {x1, y1, z}, V2 = {x, y1, z}, V3 = {x, y, z1}, V4 = {x1, y, z1}, V5 = {x1, y1, z1}, V6 = {x, y1, z1};
        face(sink, new float[][]{V4, V0, V1, V5}, u + d + w, v + d, u + d + w + d, v + d + h, tw, th, shadeSide * 0.8f);   // +x
        face(sink, new float[][]{V7, V3, V6, V2}, u, v + d, u + d, v + d + h, tw, th, shadeSide * 0.8f);                  // -x
        face(sink, new float[][]{V4, V3, V7, V0}, u + d, v, u + d + w, v + d, tw, th, shadeTop);                         // -y (model top)
        face(sink, new float[][]{V1, V2, V6, V5}, u + d + w, v + d, u + d + w + w, v, tw, th, shadeTop * 0.5f);          // +y (model bottom)
        face(sink, new float[][]{V0, V7, V2, V1}, u + d, v + d, u + d + w, v + d + h, tw, th, shadeSide);                // -z
        face(sink, new float[][]{V3, V4, V5, V6}, u + d + w + d, v + d, u + d + w + d + w, v + d + h, tw, th, shadeSide); // +z
    }

    private static void face(FaceSink sink, float[][] q, float u1, float v1, float u2, float v2, int tw, int th, float shade) {
        // TexturedQuad: v[0]=(u2,v1) v[1]=(u1,v1) v[2]=(u1,v2) v[3]=(u2,v2)
        float[] uv = {u2 / tw, v1 / th, u1 / tw, v1 / th, u1 / tw, v2 / th, u2 / tw, v2 / th};
        float[] p = new float[12];
        for (int k = 0; k < 4; k++) { p[k * 3] = q[k][0]; p[k * 3 + 1] = q[k][1]; p[k * 3 + 2] = q[k][2]; }
        sink.face(p, uv, shade);
    }

    // ------------------------------------------------------------------------------------------ lighting
    private static float bright(float l) { float f = 1 - l / 15f; return (1 - f) / (f * 3 + 1); }

    /** CPU version of the terrain lightmap (Minecraft 1.12): returns rgb in out[0..2]. */
    public static void lightmap(float sky, float block, float sun, float gamma, float flicker, float[] out) {
        lightmap(sky, block, block, block, sun, gamma, flicker, out);
    }

    /** Lightmap with a separate block light level per colour channel (coloured light). */
    public static void lightmap(float sky, float br, float bgr, float bb, float sun, float gamma, float flicker, float[] out) {
        float s = bright(sky) * (sun * 0.95f + 0.05f);
        float lr = bright(br) * flicker, lg = bright(bgr) * flicker, lb = bright(bb) * flicker;
        float rg = s * (sun * 0.65f + 0.35f);
        float[] c = {rg + lr, rg + lg * ((lg * 0.6f + 0.4f) * 0.6f + 0.4f), s + lb * (lb * lb * 0.6f + 0.4f)};
        for (int i = 0; i < 3; i++) {
            float x = Math.max(0, Math.min(1, c[i] * 0.96f + 0.03f));
            float inv = 1 - x;
            float g4 = 1 - inv * inv * inv * inv;
            x = x * (1 - gamma) + g4 * gamma;
            out[i] = Math.max(0, Math.min(1, x * 0.96f + 0.03f));
        }
    }
}
