package derpcraft.render;

import derpcraft.resource.DefaultTextures;
import derpcraft.resource.Resources;
import derpcraft.util.Json;

import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.*;

import static org.lwjgl.opengl.GL45C.*;

/**
 * All block textures in one GL_TEXTURE_2D_ARRAY (one layer per texture). Supports Minecraft 1.12.2 packs of any
 * resolution, animated strips with .mcmeta, and alpha-aware mipmaps.
 */
public final class BlockTextures {
    public static final int MAX_RES = 256;

    public int texId;
    public int res = 2, levels = 1;
    private final Map<String, Integer> layers = new HashMap<>();
    private final List<String> names = new ArrayList<>();
    private final List<Anim> anims = new ArrayList<>();
    /** Average colour per layer (ARGB), used for GUI fallbacks. */
    public int[] average = new int[0];
    /** Small (<=32px) copies of each layer's first frame, for extruded held/dropped item meshes and particles. */
    public int[][] small = new int[0][];
    public int smallRes = 16;

    private static final class Anim {
        int layer;
        int[][][] frames;      // [frame][mipLevel][pixels]
        int[] order, time;
        int pos, ticks;
    }

    /** Number of animation phases of "name#k" copies. */
    public static final int PHASES = 8;

    /** The texture name without the "#k" phase suffix. */
    public static String baseName(String n) {
        int h = n.indexOf('#');
        return h < 0 ? n : n.substring(0, h);
    }

    /** Textures that ship inside the game (assets/minecraft/textures/blocks missing from the resource pack): the soot overlays. */
    /** Textures from files outside the resource packs (gun icons from game/guns), by texture name. */
    public static final java.util.Map<String, java.nio.file.Path> EXTERNAL = new java.util.concurrent.ConcurrentHashMap<>();

    private static BufferedImage externalImage(String name) {
        java.nio.file.Path p = EXTERNAL.get(name);
        if (p == null) return null;
        try { return javax.imageio.ImageIO.read(p.toFile()); } catch (java.io.IOException e) { return null; }
    }

    private static BufferedImage builtinImage(String name) {
        try (java.io.InputStream in = BlockTextures.class.getResourceAsStream("/textures/blocks/" + name + ".png")) {
            return in == null ? null : javax.imageio.ImageIO.read(in);
        } catch (java.io.IOException e) { return null; }
    }

    public int layer(String name) {
        Integer l = layers.get(name);
        return l == null ? 0 : l;
    }

    public boolean has(String name) { return layers.containsKey(name); }

    public int count() { return names.size(); }

    public void build(Resources res, Collection<String> textureNames, int maxMip) {
        if (texId != 0) glDeleteTextures(texId);
        layers.clear(); names.clear(); anims.clear();
        names.add("missingno");
        for (String n : textureNames) if (n != null && !layers.containsKey(n) && !n.equals("missingno")) { layers.put(n, names.size()); names.add(n); }
        layers.put("missingno", 0);

        int count = names.size();
        BufferedImage[] imgs = new BufferedImage[count];
        String[] metas = new String[count];
        int maxRes = 2;
        for (int i = 1; i < count; i++) {
            imgs[i] = externalImage(baseName(names.get(i)));
            if (imgs[i] != null) { maxRes = Math.max(maxRes, Math.min(MAX_RES, imgs[i].getWidth())); continue; }
            String path = Resources.blockTexturePath(baseName(names.get(i)));
            var provider = res.provider(path);
            if (provider != null) {
                imgs[i] = provider.image(path);
                metas[i] = provider.text(path + ".mcmeta");
            }
            if (imgs[i] == null) imgs[i] = builtinImage(baseName(names.get(i)));
            if (imgs[i] == null && names.get(i).startsWith("items/bed_")) {
                // packs before 1.12 have a single bed icon (items/bed.png) instead of one per colour
                String legacy = Resources.blockTexturePath("items/bed");
                var lp = res.provider(legacy);
                if (lp != null) imgs[i] = lp.image(legacy);
            }
            if (imgs[i] != null) maxRes = Math.max(maxRes, Math.min(MAX_RES, imgs[i].getWidth()));
        }
        int r = Integer.highestOneBit(maxRes);
        if (r < maxRes) r <<= 1;
        r = Math.min(r, MAX_RES);
        this.res = r;
        this.levels = Math.min(maxMip, Integer.numberOfTrailingZeros(r)) + 1;

        int[][][] levelData = new int[count][][];
        average = new int[count];
        small = new int[count][];
        smallRes = Math.min(r, 32);
        for (int i = 0; i < count; i++) {
            BufferedImage img = imgs[i];
            int[][] frames;
            if (img == null) {
                String n = names.get(i);
                n = baseName(n);
                int[] px = n.indexOf(':') < 0 || n.startsWith("minecraft:") ? DefaultTextures.get(n.startsWith("minecraft:") ? n.substring(10) : n) : DefaultTextures.get("missingno");
                frames = new int[][]{scale(px, 2, 2, r)};
            } else {
                int w = img.getWidth(), h = img.getHeight();
                int fcount = (h > w && h % w == 0) ? h / w : 1;
                int fh = fcount > 1 ? w : h;
                frames = new int[fcount][];
                for (int f = 0; f < fcount; f++) {
                    int[] px = img.getRGB(0, f * fh, w, fh, null, 0, w);
                    frames[f] = scale(px, w, fh, r);
                }
            }
            int startFrame = 0;
            if (frames.length > 1) {
                // "name#k": the animation of this copy is k/8 of the way through (many fires, all flickering differently)
                int hash = names.get(i).indexOf('#');
                if (hash > 0) startFrame = (Integer.parseInt(names.get(i).substring(hash + 1)) * frames.length / PHASES) % frames.length;
            }
            levelData[i] = mips(frames[startFrame], r, levels);
            average[i] = levelData[i][levels - 1][0];
            int sr = Math.min(r, 32);
            int lvl = Integer.numberOfTrailingZeros(r / sr);
            small[i] = lvl < levels ? levelData[i][lvl] : scale(frames[0], r, r, sr);
            if (frames.length > 1) {
                Anim a = new Anim();
                a.layer = i;
                a.frames = new int[frames.length][][];
                for (int f = 0; f < frames.length; f++) a.frames[f] = mips(frames[f], r, levels);
                parseAnim(a, metas[i], frames.length);
                if (startFrame > 0) {
                    // begin at the step that shows 'startFrame'
                    for (int k = 0; k < a.order.length; k++) if (a.order[k] >= startFrame) { a.pos = k; break; }
                    levelData[i] = a.frames.length > 0 ? a.frames[a.order[a.pos]] : levelData[i];
                }
                anims.add(a);
            }
        }

        texId = glGenTextures();
        glBindTexture(GL_TEXTURE_2D_ARRAY, texId);
        glPixelStorei(GL_UNPACK_ALIGNMENT, 4);
        for (int l = 0; l < levels; l++) {
            int s = r >> l;
            ByteBuffer buf = ByteBuffer.allocateDirect(count * s * s * 4).order(ByteOrder.nativeOrder());
            for (int i = 0; i < count; i++) putRGBA(buf, levelData[i][l]);
            buf.flip();
            glTexImage3D(GL_TEXTURE_2D_ARRAY, l, GL_RGBA8, s, s, count, 0, GL_RGBA, GL_UNSIGNED_BYTE, buf);
        }
        glTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_MAX_LEVEL, levels - 1);
        glTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_MIN_FILTER, levels > 1 ? GL_NEAREST_MIPMAP_LINEAR : GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_WRAP_S, GL_REPEAT);
        glTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_WRAP_T, GL_REPEAT);
    }

    private static void putRGBA(ByteBuffer b, int[] px) {
        for (int p : px) b.put((byte) (p >> 16)).put((byte) (p >> 8)).put((byte) p).put((byte) (p >>> 24));
    }

    private void parseAnim(Anim a, String meta, int frameCount) {
        int frametime = 1;
        List<Integer> order = new ArrayList<>(), time = new ArrayList<>();
        if (meta != null) {
            Map<String, Object> an = Json.obj(Json.obj(Json.parse(meta)).get("animation"));
            frametime = Math.max(1, (int) Json.num(an.get("frametime"), 1));
            for (Object f : Json.arr(an.get("frames"))) {
                if (f instanceof Number n) { order.add(n.intValue()); time.add(frametime); }
                else {
                    Map<String, Object> fo = Json.obj(f);
                    order.add((int) Json.num(fo.get("index"), 0));
                    time.add(Math.max(1, (int) Json.num(fo.get("time"), frametime)));
                }
            }
        }
        if (order.isEmpty()) for (int i = 0; i < frameCount; i++) { order.add(i); time.add(frametime); }
        a.order = order.stream().mapToInt(i -> Math.min(frameCount - 1, Math.max(0, i))).toArray();
        a.time = time.stream().mapToInt(Integer::intValue).toArray();
    }

    /** Advances animated textures; call 20 times per second. */
    public void tick() {
        if (anims.isEmpty()) return;
        glBindTexture(GL_TEXTURE_2D_ARRAY, texId);
        for (Anim a : anims) {
            if (++a.ticks < a.time[a.pos]) continue;
            a.ticks = 0;
            a.pos = (a.pos + 1) % a.order.length;
            int[][] mip = a.frames[a.order[a.pos]];
            for (int l = 0; l < levels; l++) {
                int s = res >> l;
                ByteBuffer buf = scratch(s * s * 4);
                putRGBA(buf, mip[l]);
                buf.flip();
                glTexSubImage3D(GL_TEXTURE_2D_ARRAY, l, 0, 0, a.layer, s, s, 1, GL_RGBA, GL_UNSIGNED_BYTE, buf);
            }
        }
    }

    private ByteBuffer scratch;
    private ByteBuffer scratch(int n) {
        if (scratch == null || scratch.capacity() < n) scratch = ByteBuffer.allocateDirect(Math.max(n, 65536)).order(ByteOrder.nativeOrder());
        scratch.clear();
        return scratch;
    }

    /** Nearest-neighbour scale to r x r. */
    static int[] scale(int[] px, int w, int h, int r) {
        if (w == r && h == r) return px;
        int[] out = new int[r * r];
        for (int y = 0; y < r; y++) {
            int sy = y * h / r;
            for (int x = 0; x < r; x++) out[y * r + x] = px[sy * w + x * w / r];
        }
        return out;
    }

    /** Alpha-weighted box filter mip chain. */
    static int[][] mips(int[] base, int r, int levels) {
        int[][] out = new int[levels][];
        out[0] = base;
        for (int l = 1; l < levels; l++) {
            int s = r >> l, ps = r >> (l - 1);
            int[] prev = out[l - 1], cur = new int[s * s];
            for (int y = 0; y < s; y++)
                for (int x = 0; x < s; x++) {
                    int[] q = {prev[(y * 2) * ps + x * 2], prev[(y * 2) * ps + x * 2 + 1], prev[(y * 2 + 1) * ps + x * 2], prev[(y * 2 + 1) * ps + x * 2 + 1]};
                    float a = 0, rr = 0, g = 0, b = 0, rAll = 0, gAll = 0, bAll = 0;
                    for (int p : q) {
                        float pa = (p >>> 24) / 255f;
                        float pr = ((p >> 16) & 255) / 255f, pg = ((p >> 8) & 255) / 255f, pb = (p & 255) / 255f;
                        a += pa; rr += pr * pr * pa; g += pg * pg * pa; b += pb * pb * pa;
                        rAll += pr * pr; gAll += pg * pg; bAll += pb * pb;
                    }
                    if (a > 0) { rr /= a; g /= a; b /= a; } else { rr = rAll / 4; g = gAll / 4; b = bAll / 4; }
                    a /= 4;
                    cur[y * s + x] = ((int) (a * 255 + 0.5f) << 24) | ((int) (Math.sqrt(rr) * 255 + 0.5) << 16)
                            | ((int) (Math.sqrt(g) * 255 + 0.5) << 8) | (int) (Math.sqrt(b) * 255 + 0.5);
                }
            out[l] = cur;
        }
        return out;
    }

    public void bind(int unit) {
        glActiveTexture(GL_TEXTURE0 + unit);
        glBindTexture(GL_TEXTURE_2D_ARRAY, texId);
    }
}
