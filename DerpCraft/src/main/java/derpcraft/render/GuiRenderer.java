package derpcraft.render;

import derpcraft.block.Block;
import derpcraft.block.Dir;
import derpcraft.util.Mat4;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

import static org.lwjgl.opengl.GL45C.*;

/** Batched 2D renderer in GUI-scaled pixels (origin top-left), with Minecraft-style text and block icons. */
public final class GuiRenderer {
    private static final int FLOATS = 9;
    private static final int[] COLOR_CODES = new int[16];

    static {
        for (int i = 0; i < 16; i++) {
            int j = (i >> 3 & 1) * 85;
            int r = (i >> 2 & 1) * 170 + j, g = (i >> 1 & 1) * 170 + j, b = (i & 1) * 170 + j;
            if (i == 6) r += 85;
            COLOR_CODES[i] = r << 16 | g << 8 | b;
        }
    }

    private final Shader shader = new Shader("gui");
    private final int vao, vbo;
    private float[] data = new float[FLOATS * 6 * 2048];
    private int verts;
    private ByteBuffer buf = ByteBuffer.allocateDirect(1 << 18).order(ByteOrder.nativeOrder());
    private int curTex = -1;
    public Assets assets;
    public int width, height, scale = 2;
    public int plainsGrass = 0x79C05A, plainsFoliage = 0x59AE30;

    public GuiRenderer() {
        vao = glGenVertexArrays();
        vbo = glGenBuffers();
        glBindVertexArray(vao);
        glBindBuffer(GL_ARRAY_BUFFER, vbo);
        glEnableVertexAttribArray(0);
        glVertexAttribPointer(0, 2, GL_FLOAT, false, FLOATS * 4, 0L);
        glEnableVertexAttribArray(1);
        glVertexAttribPointer(1, 2, GL_FLOAT, false, FLOATS * 4, 8L);
        glEnableVertexAttribArray(2);
        glVertexAttribPointer(2, 4, GL_FLOAT, false, FLOATS * 4, 16L);
        glEnableVertexAttribArray(3);
        glVertexAttribPointer(3, 1, GL_FLOAT, false, FLOATS * 4, 32L);
        glBindVertexArray(0);
    }

    /** Computes GUI scale like Minecraft ("Auto" = as large as possible while >= 320x240). */
    public void begin(int fbW, int fbH, int guiScaleSetting) {
        int s = 1;
        int max = guiScaleSetting == 0 ? 1000 : guiScaleSetting;
        while (s < max && fbW / (s + 1) >= 320 && fbH / (s + 1) >= 240) s++;
        scale = s;
        width = (int) Math.ceil(fbW / (double) s);
        height = (int) Math.ceil(fbH / (double) s);
        shader.use();
        shader.set("uProj", new Mat4().ortho(0, fbW / (float) s, fbH / (float) s, 0, -1000, 1000));
        shader.set("uTex", 0);
        shader.set("uArray", 1);
        glDisable(GL_DEPTH_TEST);
        glDisable(GL_CULL_FACE);
        glEnable(GL_BLEND);
        glBlendFuncSeparate(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA, GL_ONE, GL_ONE_MINUS_SRC_ALPHA);
        glActiveTexture(GL_TEXTURE1);
        glBindTexture(GL_TEXTURE_2D_ARRAY, assets.blocks.texId);
        glActiveTexture(GL_TEXTURE0);
        curTex = -1;
    }

    private void tex(Texture2D t) {
        int id = t == null ? 0 : t.id;
        if (id != curTex) {
            flush();
            curTex = id;
            glActiveTexture(GL_TEXTURE0);
            glBindTexture(GL_TEXTURE_2D, id);
        }
    }

    private void v(float x, float y, float u, float v, int argb, float layer) {
        if ((verts + 1) * FLOATS > data.length) data = Arrays.copyOf(data, data.length * 2);
        int i = verts * FLOATS;
        data[i] = x; data[i + 1] = y; data[i + 2] = u; data[i + 3] = v;
        data[i + 4] = ((argb >> 16) & 255) / 255f; data[i + 5] = ((argb >> 8) & 255) / 255f;
        data[i + 6] = (argb & 255) / 255f; data[i + 7] = (argb >>> 24) / 255f;
        data[i + 8] = layer;
        verts++;
    }

    private void quad(float x0, float y0, float x1, float y1, float u0, float v0, float u1, float v1, int c, float layer) {
        v(x0, y0, u0, v0, c, layer); v(x0, y1, u0, v1, c, layer); v(x1, y1, u1, v1, c, layer);
        v(x1, y1, u1, v1, c, layer); v(x1, y0, u1, v0, c, layer); v(x0, y0, u0, v0, c, layer);
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

    public void end() { flush(); glDisable(GL_BLEND); }

    // ------------------------------------------------------------------ primitives
    public void fill(float x0, float y0, float x1, float y1, int argb) { quad(x0, y0, x1, y1, 0, 0, 0, 0, argb, -2); }

    public void gradient(float x0, float y0, float x1, float y1, int top, int bottom) {
        v(x0, y0, 0, 0, top, -2); v(x0, y1, 0, 0, bottom, -2); v(x1, y1, 0, 0, bottom, -2);
        v(x1, y1, 0, 0, bottom, -2); v(x1, y0, 0, 0, top, -2); v(x0, y0, 0, 0, top, -2);
    }

    /** Draws a region of a GUI texture given in Minecraft's 256x256 coordinate space (works for HD packs). */
    public void blit(Texture2D t, float x, float y, float u, float v, float w, float h, int color) {
        tex(t);
        quad(x, y, x + w, y + h, u / 256f, v / 256f, (u + w) / 256f, (v + h) / 256f, color, -1);
    }

    public void blitStretch(Texture2D t, float x, float y, float w, float h, float u0, float v0, float u1, float v1, int color) {
        tex(t);
        quad(x, y, x + w, y + h, u0, v0, u1, v1, color, -1);
    }

    /** Tiled dirt background like the options screens. */
    public void background(int color) {
        tex(assets.background);
        quad(0, 0, width, height, 0, 0, width / 32f, height / 32f, color, -1);
    }

    // ------------------------------------------------------------------ text
    public int text(String s, float x, float y, int color, boolean shadow) {
        if (shadow) drawText(s, x + 1, y + 1, color, true);
        return drawText(s, x, y, color, false);
    }

    public void centered(String s, float cx, float y, int color) { text(s, cx - textWidth(s) / 2f, y, color, true); }

    public int textWidth(String s) { return assets.font.width(s); }

    private int drawText(String s, float x, float y, int color, boolean shadow) {
        Font f = assets.font;
        tex(f.tex);
        if ((color >>> 24) == 0) color |= 0xFF000000;
        int base = color;
        int cur = shadow ? shade(base) : base;
        float sx = x;
        float c = 1f / 16f;
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch == '§' && i + 1 < s.length()) {
                int code = "0123456789abcdef".indexOf(Character.toLowerCase(s.charAt(++i)));
                int rgb = code >= 0 ? COLOR_CODES[code] : (base & 0xFFFFFF);
                int col = (base & 0xFF000000) | rgb;
                cur = shadow ? shade(col) : col;
                continue;
            }
            if (ch >= 256) ch = '?';
            int w = f.widths[ch];
            if (ch != ' ') {
                float u = (ch % 16) * c, v = (ch / 16) * c;
                quad(x, y, x + 8, y + 8, u, v, u + c, v + c, cur, -1);
            }
            x += w;
        }
        return (int) (x - sx);
    }

    private static int shade(int c) { return (c & 0xFF000000) | ((c & 0xFCFCFC) >> 2); }

    // ------------------------------------------------------------------ item icons
    private static final java.util.Set<String> FLAT_BLOCKS = java.util.Set.of("ladder", "vine", "lever", "rail", "golden_rail", "detector_rail",
            "activator_rail", "web", "waterlily", "redstone_torch", "torch", "iron_bars");
    private final float[] tv = new float[4];

    /** Blocks whose item is a flat sprite (plants, torches, panes, rails, ladders...) in the GUI, in hand and on the ground. */
    public static boolean isFlatBlock(Block b) {
        Block.Shape sh = b.shape;
        return sh == Block.Shape.CROSS || sh == Block.Shape.TORCH || sh == Block.Shape.PANE || FLAT_BLOCKS.contains(b.name)
                || b instanceof derpcraft.block.FunctionalBlocks.RailBlock;
    }

    /** Block icon at 16x16 (scaled by size/16). */
    public void blockIcon(Block b, float x, float y, float size) {
        if (b == null || b.isAir() || assets.tables == null) return;
        if (b.item != null) itemIcon(b.item, x, y, size);
        else model(b, x, y, size);
    }

    public void item(derpcraft.item.ItemStack s, float x, float y) { if (s != null) itemIcon(s.item, x, y, 16); }

    /** Item icon with stack count and durability bar (vanilla renderItemOverlays). */
    public void itemWithCount(derpcraft.item.ItemStack s, float x, float y) {
        if (s == null) return;
        itemIcon(s.item, x, y, 16);
        if (s.count != 1) {
            String n = String.valueOf(s.count);
            text(n, x + 17 - textWidth(n), y + 9, 0xFFFFFF, true);
        }
        if (s.damage > 0 && s.item.maxDamage > 0) {
            float hp = 1 - s.damage / (float) s.item.maxDamage;
            int w = Math.round(13 * hp);
            int col = java.awt.Color.HSBtoRGB(hp / 3f, 1, 1) & 0xFFFFFF;
            fill(x + 2, y + 13, x + 15, y + 15, 0xFF000000);
            fill(x + 2, y + 13, x + 2 + w, y + 14, 0xFF000000 | col);
        }
    }

    public void itemIcon(derpcraft.item.Item it, float x, float y, float size) {
        if (it == null || assets.tables == null) return;
        Block b = it.block;
        if (b instanceof derpcraft.block.FunctionalBlocks.BedBlock bed && assets.beds[bed.color] != null) { bedIcon(bed, x, y, size / 16f); return; }
        if (it.texture != null || b == null) {
            flat(assets.blocks.layer(it.texture), it.tint, x, y, size);
            return;
        }
        model(b, x, y, size);
    }

    private void flat(int layer, int tint, float x, float y, float size) {
        tex(null);
        quad(x, y, x + size, y + size, 0, 0, 1, 1, 0xFF000000 | tint, layer);
    }

    private int tintOf(Block b) {
        return switch (b.tint) { case GRASS -> plainsGrass; case FOLIAGE -> plainsFoliage; case FIXED -> b.fixedTint; default -> 0xFFFFFF; };
    }

    /** 3D block model projected on the CPU with the vanilla GUI transform (rotation 30/225, scale 0.625). */
    private void model(Block b, float x, float y, float size) {
        RenderTables t = assets.tables;
        int tint = tintOf(b);
        Block.Shape sh = b.shape;
        int meta = 0;
        if (isFlatBlock(b)) {
            int m8 = b instanceof derpcraft.block.FunctionalBlocks.DoublePlantBlock ? 8 : 0;
            String tn = b.textureName(Dir.NORTH, m8);
            flat(tn == null ? 0 : assets.blocks.layer(tn), b.tint == Block.Tint.NONE ? 0xFFFFFF : tint, x, y, size);
            return;
        }
        float s = size / 16f;
        if (sh == Block.Shape.ENTITY && b instanceof derpcraft.block.FunctionalBlocks.ChestBlock cb) { chestIcon(cb, x, y, s); return; }
        Mat4 m = new Mat4().translate(x + 8 * s, y + 8 * s, 0).scale(10 * s, -10 * s, 10 * s)
                .rotate((float) Math.toRadians(30), 1, 0, 0).rotate((float) Math.toRadians(225), 0, 1, 0)
                .translate(-0.5f, -0.5f, -0.5f).scale(1 / 16f, 1 / 16f, 1 / 16f);
        int conn = sh == Block.Shape.FENCE ? 0b0011 : sh == Block.Shape.WALL ? 0b1011 : 0;
        RenderTables.Quad[] quads = t.boxQuads((b.id << 4) | meta, conn);
        java.util.List<float[]> faces = new java.util.ArrayList<>();
        for (RenderTables.Quad q : quads) {
            // backface culling with the transformed normal
            int d = q.dir;
            float cx = 0, cy = 0, cz = 0;
            for (int k = 0; k < 4; k++) { cx += q.pos[k * 3]; cy += q.pos[k * 3 + 1]; cz += q.pos[k * 3 + 2]; }
            cx /= 4; cy /= 4; cz /= 4;
            m.transform(cx, cy, cz, tv);
            float z0 = tv[2], sx0 = tv[0];
            m.transform(cx + Dir.DX[d] * 4, cy + Dir.DY[d] * 4, cz + Dir.DZ[d] * 4, tv);
            if (tv[2] - z0 <= 0.001f) continue;
            float shade = d == Dir.UP ? 1f : d == Dir.DOWN ? 0.5f : (tv[0] < sx0 ? 0.8f : 0.6f);
            boolean tinted = q.tint != 0 && (b.sideOverlay == null || d == Dir.UP);
            int col = mul(tinted ? tint : 0xFFFFFF, shade);
            float[] f = new float[1 + 8 + 8 + 2];
            f[0] = z0;
            for (int k = 0; k < 4; k++) {
                m.transform(q.pos[k * 3], q.pos[k * 3 + 1], q.pos[k * 3 + 2], tv);
                f[1 + k * 2] = tv[0]; f[2 + k * 2] = tv[1];
                f[9 + k * 2] = q.uv[k * 2] / 16f; f[10 + k * 2] = q.uv[k * 2 + 1] / 16f;
            }
            f[17] = q.layer; f[18] = Float.intBitsToFloat(col);
            faces.add(f);
            if (b.sideOverlay != null && d >= 2) {
                float[] o = f.clone();
                o[0] += 0.0001f;
                o[17] = t.overlay[b.id];
                o[18] = Float.intBitsToFloat(mul(tint, shade));
                faces.add(o);
            }
        }
        drawFaces(faces, null);
    }

    /**
     * Draws CPU-projected faces far ones first (painter's algorithm, no depth buffer needed). Each face: depth, 4 screen
     * corners, 4 uvs, layer of the block texture array (-1 = the bound 2D texture tx), colour bits.
     */
    private void drawFaces(java.util.List<float[]> faces, Texture2D tx) {
        faces.sort((p1, p2) -> Float.compare(p1[0], p2[0]));
        tex(tx);
        for (float[] f : faces) {
            int col = Float.floatToRawIntBits(f[18]);
            for (int k : QUAD_ORDER) v(f[1 + k * 2], f[2 + k * 2], f[9 + k * 2], f[10 + k * 2], col, f[17]);
        }
    }

    private static final int[] QUAD_ORDER = {0, 1, 2, 2, 3, 0};

    /** Chest item: the chest entity model, vanilla builtin/entity GUI transform (rotation 30/45). */
    private void chestIcon(derpcraft.block.FunctionalBlocks.ChestBlock cb, float x, float y, float s) {
        Texture2D tx = cb.texture.equals("ender") ? assets.chestEnder : cb.texture.equals("trapped") ? assets.chestTrapped : assets.chestNormal;
        Mat4 base = new Mat4().translate(x + 8 * s, y + 8 * s, 0).scale(10 * s, -10 * s, 10 * s)
                .rotate((float) Math.toRadians(30), 1, 0, 0).rotate((float) Math.toRadians(45), 0, 1, 0)
                .translate(-0.5f, -0.5f, -0.5f)
                .translate(0, 1, 1).scale(1, -1, -1);
        modelBoxes(tx, base, new float[][]{{1, 7, 15, 0, -5, -14, 14, 5, 14, 0, 0, 64, 64}, {8, 7, 15, -1, -2, -15, 2, 4, 1, 0, 0, 64, 64},
                {1, 6, 1, 0, 0, 0, 14, 10, 14, 0, 19, 64, 64}}, 0xFFFFFF);
    }

    /**
     * Bed item: the whole bed model (both halves and the legs) like vanilla 1.12's builtin/entity bed, GUI transform
     * rotation 30/160, translation 2/3, scale 0.5325. The halves are placed like a bed facing south in the world.
     */
    private void bedIcon(derpcraft.block.FunctionalBlocks.BedBlock bed, float x, float y, float s) {
        Mat4 gui = new Mat4().translate(x + 8 * s, y + 8 * s, 0).scale(16 * s, -16 * s, 16 * s)
                .translate(2 / 16f, 3 / 16f, 0)
                .rotate((float) Math.toRadians(30), 1, 0, 0).rotate((float) Math.toRadians(160), 0, 1, 0)
                .scale(0.5325f, 0.5325f, 0.5325f).translate(-0.5f, -0.5f, -0.5f);
        float halfPi = (float) Math.PI / 2;
        // ModelBed legs {u, v, x, y, z} and their turn; legs 1 and 3 belong to the head (same as EntityRenderer.bed)
        int[][] legs = {{50, 0, 0, 6, -16}, {50, 6, 0, 6, 0}, {50, 12, -16, 6, -16}, {50, 18, -16, 6, 0}};
        float[] legZ = {0, halfPi, halfPi * 3, (float) Math.PI};
        java.util.List<float[]> faces = new java.util.ArrayList<>();
        for (int part = 0; part < 2; part++) {
            boolean head = part == 0;
            Mat4 piece = new Mat4().translate(1, 9 / 16f, head ? 1 : 0).rotate(halfPi, 1, 0, 0).rotate((float) Math.PI, 0, 0, 1);
            boxFaces3d(faces, gui, piece, 0, 0, 0, 16, 16, 6, 0, head ? 0 : 22);
            for (int k = 0; k < 4; k++) {
                if (((k & 1) == 1) != head) continue;
                Mat4 lm = new Mat4().set(piece).rotate(legZ[k], 0, 0, 1).rotate(halfPi, 1, 0, 0);
                boxFaces3d(faces, gui, lm, legs[k][2], legs[k][3], legs[k][4], 3, 3, 3, legs[k][0], legs[k][1]);
            }
        }
        drawFaces(faces, assets.beds[bed.color]);
    }

    /**
     * Faces of one model box (pixel units, 64x64 entity texture) for a GUI scene: 'local' places it in block space,
     * 'gui' projects block space onto the screen. Back faces are dropped and each face is shaded by where it points in
     * block space (top bright, bottom dark, sides in between), like the block icons.
     */
    private void boxFaces3d(java.util.List<float[]> faces, Mat4 gui, Mat4 local, float bx, float by, float bz, int w, int h, int d, int u, int v) {
        Mat4 full = new Mat4().set(gui).mul(local);
        float[] c = new float[4], fc = new float[4];
        local.transform((bx + w / 2f) / 16f, (by + h / 2f) / 16f, (bz + d / 2f) / 16f, c);
        ModelBatch.boxFaces(bx, by, bz, w, h, d, u, v, 64, 64, 1, 1, (p, uv, shade) -> {
            float mx = 0, my = 0, mz = 0;
            for (int k = 0; k < 4; k++) { mx += p[k * 3]; my += p[k * 3 + 1]; mz += p[k * 3 + 2]; }
            local.transform(mx / 64f, my / 64f, mz / 64f, fc);                 // face centre in block space
            float nx = fc[0] - c[0], ny = fc[1] - c[1], nz = fc[2] - c[2];      // outwards
            float len = (float) Math.sqrt(nx * nx + ny * ny + nz * nz) + 1e-6f;
            nx /= len; ny /= len; nz /= len;
            gui.transform(fc[0], fc[1], fc[2], tv);
            float z0 = tv[2];
            gui.transform(fc[0] + nx * 0.25f, fc[1] + ny * 0.25f, fc[2] + nz * 0.25f, tv);
            if (tv[2] - z0 <= 1e-5f) return;                                     // faces away from the viewer
            float sh = ny > 0.5f ? 1f : ny < -0.5f ? 0.5f : Math.abs(nx) > Math.abs(nz) ? 0.6f : 0.8f;
            float[] f = new float[19];
            for (int k = 0; k < 4; k++) {
                full.transform(p[k * 3] / 16f, p[k * 3 + 1] / 16f, p[k * 3 + 2] / 16f, tv);
                f[1 + k * 2] = tv[0]; f[2 + k * 2] = tv[1];
                f[9 + k * 2] = uv[k * 2]; f[10 + k * 2] = uv[k * 2 + 1];
            }
            f[0] = z0;
            f[17] = -1;
            f[18] = Float.intBitsToFloat(mul(0xFFFFFF, sh));
            faces.add(f);
        });
    }

    /**
     * Draws Minecraft model boxes projected on the CPU (painter's algorithm, no depth buffer needed).
     * Each part: pivotX, pivotY, pivotZ, x, y, z, w, h, d, u, v, texW, texH (pixel units).
     */
    public void modelBoxes(Texture2D tx, Mat4 base, float[][] parts, int rgb) {
        java.util.List<float[]> faces = new java.util.ArrayList<>();
        for (float[] pt : parts) {
            Mat4 m = new Mat4().set(base).translate(pt[0] / 16f, pt[1] / 16f, pt[2] / 16f).scale(1 / 16f, 1 / 16f, 1 / 16f);
            ModelBatch.boxFaces(pt[3], pt[4], pt[5], (int) pt[6], (int) pt[7], (int) pt[8], (int) pt[9], (int) pt[10], (int) pt[11], (int) pt[12], 1, 0.8f, (p, uv, shade) -> {
                float[] f = new float[19];
                float zs = 0;
                for (int k = 0; k < 4; k++) {
                    m.transform(p[k * 3], p[k * 3 + 1], p[k * 3 + 2], tv);
                    f[1 + k * 2] = tv[0]; f[2 + k * 2] = tv[1]; zs += tv[2];
                    f[9 + k * 2] = uv[k * 2]; f[10 + k * 2] = uv[k * 2 + 1];
                }
                f[0] = zs / 4;
                f[17] = -1;
                f[18] = Float.intBitsToFloat(mul(rgb, shade));
                faces.add(f);
            });
        }
        drawFaces(faces, tx);
    }

    /**
     * The player model in a pose from the pose library (null = standing), feet at (cx, feetY), turned by yaw. All
     * faces are sorted together so posed limbs overlap correctly. Draws the hit box outline when hitbox is set.
     */
    public void playerModel(Texture2D skin, float cx, float feetY, float scale, float yaw, float headYaw, float headPitch, derpcraft.npc.Poses.Def pose) {
        playerModel(skin, cx, feetY, scale, yaw, headYaw, headPitch, pose, false, -1);
    }

    public void playerModel(Texture2D skin, float cx, float feetY, float scale, float yaw, float headYaw, float headPitch, derpcraft.npc.Poses.Def pose,
                            boolean hitbox, int highlightPart) {
        Scene sc = new Scene();
        playerModel(sc, skin, cx, feetY, scale, yaw, headYaw, headPitch, pose, highlightPart);
        sc.draw();
        if (hitbox && pose != null) {
            // hit box seen from the front: width x height from the feet
            float hw = pose.width / 2 * scale, hh = pose.height * scale;
            int c = 0xFFFFD040;
            fill(cx - hw, feetY - hh, cx + hw, feetY - hh + 1, c);
            fill(cx - hw, feetY - 1, cx + hw, feetY, c);
            fill(cx - hw, feetY - hh, cx - hw + 1, feetY, c);
            fill(cx + hw - 1, feetY - hh, cx + hw, feetY, c);
        }
    }

    // ------------------------------------------------------------------ 3D scenes (CPU projected, sorted back to front)
    /** Faces of several models (skins, 3D models with many textures) drawn together, far ones first. */
    public final class Scene {
        private final java.util.List<float[]> faces = new java.util.ArrayList<>();
        private final java.util.List<Texture2D> texs = new java.util.ArrayList<>();
        private final float[] t = new float[4];

        private int texIndex(Texture2D tx) {
            if (tx == null) return -1;
            int i = texs.indexOf(tx);
            if (i < 0) { texs.add(tx); i = texs.size() - 1; }
            return i;
        }

        /** Quad: 4 corners p (x,y,z each) through m, uv (4 pairs), colour. */
        public void quad(Texture2D tx, Mat4 m, float[] p, float[] uv, int argb) {
            float[] f = new float[19];
            float zs = 0;
            for (int k = 0; k < 4; k++) {
                m.transform(p[k * 3], p[k * 3 + 1], p[k * 3 + 2], t);
                f[1 + k * 2] = t[0]; f[2 + k * 2] = t[1]; zs += t[2];
                f[9 + k * 2] = uv[k * 2]; f[10 + k * 2] = uv[k * 2 + 1];
            }
            f[0] = zs / 4;
            f[17] = Float.intBitsToFloat(argb);
            f[18] = texIndex(tx);
            faces.add(f);
        }

        /** Triangle from vertex data (x,y,z,u,v) at offset i of 'v' (three vertices). */
        public void tri(Texture2D tx, Mat4 m, float[] v, int i, int argb) {
            float[] f = new float[19];
            float zs = 0;
            for (int k = 0; k < 4; k++) {
                int o = i + Math.min(k, 2) * 5;
                m.transform(v[o], v[o + 1], v[o + 2], t);
                f[1 + k * 2] = t[0]; f[2 + k * 2] = t[1]; if (k < 3) zs += t[2];
                f[9 + k * 2] = v[o + 3]; f[10 + k * 2] = v[o + 4];
            }
            f[0] = zs / 3;
            f[17] = Float.intBitsToFloat(argb);
            f[18] = texIndex(tx);
            faces.add(f);
        }

        public int size() { return faces.size(); }

        public void draw() {
            faces.sort((p1, p2) -> Float.compare(p1[0], p2[0]));
            for (float[] f : faces) {
                int ti = (int) f[18];
                if (ti >= 0) tex(texs.get(ti));
                int col = Float.floatToRawIntBits(f[17]);
                float layer = ti >= 0 ? -1 : -2;
                for (int k : QUAD_ORDER) v(f[1 + k * 2], f[2 + k * 2], f[9 + k * 2], f[10 + k * 2], col, layer);
            }
            faces.clear();
        }
    }

    /** A 3D model from game/3d through 'm' (its placement settings already applied by the caller), tinted by rgb. */
    public void model3d(Scene sc, derpcraft.model.Model3D model, Mat4 m, int rgb) {
        if (model == null) return;
        for (derpcraft.model.Model3D.Group g : model.groups) {
            Texture2D tx = g.texture();
            int gc = g.color;
            for (int i = 0; i < g.tris; i++) {
                int c = mul(mulRgb(gc, rgb), g.shade[i]);
                sc.tri(tx, m, g.v, i * 15, (gc & 0xFF000000) | (c & 0xFFFFFF));
            }
        }
    }

    private static int mulRgb(int a, int b) {
        int r = ((a >> 16) & 255) * ((b >> 16) & 255) / 255, g = ((a >> 8) & 255) * ((b >> 8) & 255) / 255, bl = (a & 255) * (b & 255) / 255;
        return 0xFF000000 | (r << 16) | (g << 8) | bl;
    }

    /**
     * The player model added to a scene; returns the matrices of head, body, right arm, left arm, right leg, left
     * leg in block units (y down, like Minecraft model space) so things can be attached to them.
     */
    public Mat4[] playerModel(Scene sc, Texture2D skin, float cx, float feetY, float scale, float yaw, float headYaw, float headPitch, derpcraft.npc.Poses.Def pose, int highlightPart) {
        Mat4 base = new Mat4().translate(cx, feetY, 50).scale(scale, scale, -scale);
        base.rotate((float) Math.toRadians(yaw), 0, 1, 0);
        return playerModel(sc, skin, base, headYaw, headPitch, pose, highlightPart);
    }

    /** Same with a ready base matrix (feet at its origin, y down); from a y-up frame use frame.scale(-1, -1, 1). */
    public Mat4[] playerModel(Scene sc, Texture2D skin, Mat4 base, float headYaw, float headPitch, derpcraft.npc.Poses.Def pose, int highlightPart) {
        base = new Mat4().set(base);
        float lift = pose == null ? 0 : pose.lift;
        if (lift != 0) base.translate(0, -lift, 0);
        if (pose != null && pose.tiltX != 0) base.rotate((float) Math.toRadians(-pose.tiltX), 1, 0, 0);
        if (pose != null && pose.tiltZ != 0) base.rotate((float) Math.toRadians(-pose.tiltZ), 0, 0, 1);
        base.translate(0, -1.5f, 0);
        float[][] rot = new float[6][3];
        if (pose != null) for (int i = 0; i < 6; i++) if (pose.set[i]) rot[i] = pose.rot[i].clone();
        rot[0][0] += headPitch; rot[0][1] += headYaw;
        Mat4 upper = base;
        if (rot[1][0] != 0 || rot[1][1] != 0 || rot[1][2] != 0) {
            upper = new Mat4().set(base).translate(0, 12 / 16f, 0);
            upper.rotate((float) Math.toRadians(-rot[1][2]), 0, 0, 1).rotate((float) Math.toRadians(-rot[1][1]), 0, 1, 0).rotate((float) Math.toRadians(-rot[1][0]), 1, 0, 0);
            upper.translate(0, -12 / 16f, 0);
        }
        float[][] pivots = {{0, 0, 0}, {0, 0, 0}, {-5, 2, 0}, {5, 2, 0}, {-1.9f, 12, 0}, {1.9f, 12, 0}};
        float[][][] boxes = {
                {{-4, -8, -4, 8, 8, 8, 0, 0}, {-4.5f, -8.5f, -4.5f, 9, 9, 9, 32, 0}},
                {{-4, 0, -2, 8, 12, 4, 16, 16}},
                {{-3, -2, -2, 4, 12, 4, 40, 16}},
                {{-1, -2, -2, 4, 12, 4, 32, 48}},
                {{-2, 0, -2, 4, 12, 4, 0, 16}},
                {{-2, 0, -2, 4, 12, 4, 16, 48}}};
        Mat4[] parts = new Mat4[6];
        for (int i = 0; i < 6; i++) {
            Mat4 m = new Mat4().set(i <= 3 ? upper : base).translate(pivots[i][0] / 16f, pivots[i][1] / 16f, pivots[i][2] / 16f);
            if (i != 1) m.rotate((float) Math.toRadians(-rot[i][2]), 0, 0, 1).rotate((float) Math.toRadians(-rot[i][1]), 0, 1, 0).rotate((float) Math.toRadians(-rot[i][0]), 1, 0, 0);
            parts[i] = new Mat4().set(m);
            m.scale(1 / 16f, 1 / 16f, 1 / 16f);
            int col = i == highlightPart ? 0xFFB0FFB0 : 0xFFFFFF;
            for (float[] b : boxes[i]) {
                if (b[3] == 9) {   // hat layer: inflated head box
                    Mat4 hm = new Mat4().set(m).translate(-4.5f, -8.5f, -4.5f).scale(9 / 8f, 9 / 8f, 9 / 8f).translate(4, 8, 4);
                    sceneBox(sc, skin, hm, -4, -8, -4, 8, 8, 8, 32, 0, col);
                } else sceneBox(sc, skin, m, b[0], b[1], b[2], (int) b[3], (int) b[4], (int) b[5], (int) b[6], (int) b[7], col);
            }
        }
        return parts;
    }

    private void sceneBox(Scene sc, Texture2D skin, Mat4 m, float x, float y, float z, int w, int h, int d, int u, int v, int rgb) {
        ModelBatch.boxFaces(x, y, z, w, h, d, u, v, 64, 64, 1, 0.8f, (p, uv, shade) -> sc.quad(skin, m, p, uv, 0xFF000000 | mul(rgb, shade)));
    }

    /** The player model (64x64 skin layout) standing with its feet at (cx, feetY), turned by yaw/headPitch degrees. */
    public void playerModel(Texture2D skin, float cx, float feetY, float scale, float yaw, float headYaw, float headPitch) {
        playerModel(skin, cx, feetY, scale, yaw, headYaw, headPitch, 0);
    }

    /** Like vanilla drawEntityOnScreen: 'tilt' leans the whole model forward / back (degrees) around its feet. */
    public void playerModel(Texture2D skin, float cx, float feetY, float scale, float yaw, float headYaw, float headPitch, float tilt) {
        Mat4 base = new Mat4().translate(cx, feetY, 50).scale(scale, scale, -scale);
        if (tilt != 0) base.rotate((float) Math.toRadians(tilt), 1, 0, 0);
        base.translate(0, -1.5f, 0).rotate((float) Math.toRadians(yaw), 0, 1, 0);
        modelBoxes(skin, base, new float[][]{
                {0, 0, 0, -4, 0, -2, 8, 12, 4, 16, 16, 64, 64},       // body
                {-5, 2, 0, -3, -2, -2, 4, 12, 4, 40, 16, 64, 64},     // right arm
                {5, 2, 0, -1, -2, -2, 4, 12, 4, 32, 48, 64, 64},      // left arm
                {-1.9f, 12, 0, -2, 0, -2, 4, 12, 4, 0, 16, 64, 64},   // right leg
                {1.9f, 12, 0, -2, 0, -2, 4, 12, 4, 16, 48, 64, 64}}, 0xFFFFFF);
        Mat4 head = new Mat4().set(base).rotate((float) Math.toRadians(headYaw), 0, 1, 0).rotate((float) Math.toRadians(headPitch), 1, 0, 0);
        modelBoxes(skin, head, new float[][]{{0, 0, 0, -4, -8, -4, 8, 8, 8, 0, 0, 64, 64}}, 0xFFFFFF);
    }

    /** Text rotated around (cx, cy) and scaled (title screen splash). */
    public void textRotated(String s, float cx, float cy, float angleDeg, float scale, int argb) {
        Font f = assets.font;
        tex(f.tex);
        float w = textWidth(s);
        float c = (float) Math.cos(Math.toRadians(angleDeg)), sn = (float) Math.sin(Math.toRadians(angleDeg));
        float x = -w / 2f;
        float cc = 1f / 16f;
        int[] o = {0, 1, 2, 2, 3, 0};
        float[] lx = new float[4], ly = new float[4];
        for (int pass = 0; pass < 2; pass++) {
            int col = pass == 0 ? (argb & 0xFF000000) | ((argb & 0xFCFCFC) >> 2) : argb;
            float off = pass == 0 ? 1 : 0;
            x = -w / 2f;
            for (int i = 0; i < s.length(); i++) {
                char ch = s.charAt(i);
                if (ch >= 256) ch = '?';
                if (ch != ' ') {
                    float u = (ch % 16) * cc, v = (ch / 16) * cc;
                    float[] px = {x + off, x + off, x + 8 + off, x + 8 + off}, py = {-4 + off, 4 + off, 4 + off, -4 + off};
                    float[] uu = {u, u, u + cc, u + cc}, vv = {v, v + cc, v + cc, v};
                    for (int k = 0; k < 4; k++) { lx[k] = cx + (px[k] * c - py[k] * sn) * scale; ly[k] = cy + (px[k] * sn + py[k] * c) * scale; }
                    for (int k : o) v(lx[k], ly[k], uu[k], vv[k], col, -1);
                }
                x += f.widths[ch];
            }
        }
    }

    private static int mul(int rgb, float f) {
        int r = (int) (((rgb >> 16) & 255) * f), g = (int) (((rgb >> 8) & 255) * f), b = (int) ((rgb & 255) * f);
        return 0xFF000000 | r << 16 | g << 8 | b;
    }

    /** Vanilla tooltip box (purple border) with lines. First line white, others grey. */
    public void tooltip(java.util.List<String> lines, int mx, int my) {
        if (lines.isEmpty()) return;
        int w = 0;
        for (String l : lines) w = Math.max(w, textWidth(l));
        int x = mx + 12, y = my - 12, h = 8 + (lines.size() > 1 ? 2 + (lines.size() - 1) * 10 : 0);
        if (x + w > width - 4) x -= 28 + w;
        if (y + h + 6 > height) y = height - h - 6;
        int bg = 0xF0100010, b1 = 0x505000FF, b2 = 0x5028007F;
        fill(x - 3, y - 4, x + w + 3, y - 3, bg);
        fill(x - 3, y + h + 3, x + w + 3, y + h + 4, bg);
        fill(x - 3, y - 3, x + w + 3, y + h + 3, bg);
        fill(x - 4, y - 3, x - 3, y + h + 3, bg);
        fill(x + w + 3, y - 3, x + w + 4, y + h + 3, bg);
        gradient(x - 3, y - 3 + 1, x - 3 + 1, y + h + 3 - 1, b1, b2);
        gradient(x + w + 2, y - 3 + 1, x + w + 3, y + h + 3 - 1, b1, b2);
        fill(x - 3, y - 3, x + w + 3, y - 3 + 1, b1);
        fill(x - 3, y + h + 2, x + w + 3, y + h + 3, b2);
        for (int i = 0; i < lines.size(); i++) {
            text(lines.get(i), x, y, i == 0 ? 0xFFFFFF : 0xA8A8A8, true);
            y += i == 0 ? 12 : 10;
        }
    }
}
