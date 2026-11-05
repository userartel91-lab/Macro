package derpcraft.render;

import derpcraft.util.Frustum;
import derpcraft.util.Mat4;
import derpcraft.util.Workers;
import derpcraft.world.Chunk;
import derpcraft.world.World;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;

import static org.lwjgl.opengl.GL45C.*;

/**
 * Renders chunk sections with glMultiDrawElementsIndirect from one shared vertex arena.
 * Each section is split into render layer x face direction groups so whole groups facing away are skipped.
 */
public final class WorldRenderer {
    private static final int MAX_QUADS_PER_DRAW = 1 << 16;

    private final World world;
    private final Workers workers;
    public RenderTables tables;
    public final BlockTextures textures;
    private final GpuArena arena;
    private int vao, ebo, instanceBuf, indirectBuf, biomeTex;
    private Shader solid, cutout, shadowSolid, shadowCutout;
    /** Called on the render thread after a section got a new mesh (block or light change). */
    public interface MeshListener { void meshed(int cx, int sy, int cz); }
    public MeshListener onMeshed = (cx, sy, cz) -> {};
    private Texture2D grassMap, foliageMap;

    private final HashMap<Long, Sec> sections = new HashMap<>();
    private final ArrayList<Sec> meshed = new ArrayList<>();
    private final LinkedHashSet<Sec> needs = new LinkedHashSet<>();
    private final ConcurrentLinkedQueue<long[]> dirtyQueue = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<Result> results = new ConcurrentLinkedQueue<>();
    private final ThreadLocal<ChunkMesher> meshers = ThreadLocal.withInitial(ChunkMesher::new);
    private final ChunkMesher mainMesher = new ChunkMesher();
    private int inFlight, scanTimer;
    private final ArrayList<Sec> cand = new ArrayList<>();
    public int renderDistance = 12;
    public int visibleSections, drawCalls;
    public long uploadedThisFrame;
    /** Changes whenever a section mesh is added, replaced or removed (lets shadow maps be reused). */
    public long geometryVersion;
    /** Wind (wave.glsl): strength (0 = off) and the clock in seconds of game time. Set every frame by the game. */
    public float wave, waveTime;

    private static final class Sec {
        final long key; final int cx, sy, cz;
        int dirtyVersion, builtVersion;
        boolean inFlight;
        long alloc = -1; int allocLen;
        int[] groupStart;
        int listIndex = -1;
        float dist;
        /** Box of the geometry relative to the section origin (blocks), padded for wind sway: see {@link ChunkMesher.Mesh#bounds}. */
        float bx0, by0, bz0, bx1 = 16, by1 = 16, bz1 = 16;
        Sec(long key, int cx, int sy, int cz) { this.key = key; this.cx = cx; this.sy = sy; this.cz = cz; }
    }

    private record Result(Sec sec, int version, ChunkMesher.Mesh mesh) {}

    public WorldRenderer(World world, Workers workers, BlockTextures textures) {
        this.world = world;
        this.workers = workers;
        this.textures = textures;
        arena = new GpuArena(4L << 20);
        arena.onResize = this::setupVao;
        solid = new Shader("terrain", "terrain", "");
        cutout = new Shader("terrain", "terrain", "#define ALPHA_TEST");
        shadowSolid = new Shader("shadow", "shadow", "");
        shadowCutout = new Shader("shadow", "shadow", "#define ALPHA_TEST");
        ebo = glGenBuffers();
        glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, ebo);
        ByteBuffer idx = ByteBuffer.allocateDirect(MAX_QUADS_PER_DRAW * 6 * 4).order(ByteOrder.nativeOrder());
        for (int q = 0; q < MAX_QUADS_PER_DRAW; q++) {
            int b = q * 4;
            idx.putInt(b).putInt(b + 1).putInt(b + 2).putInt(b + 2).putInt(b + 3).putInt(b);
        }
        idx.flip();
        glBufferData(GL_ELEMENT_ARRAY_BUFFER, idx, GL_STATIC_DRAW);
        instanceBuf = glGenBuffers();
        indirectBuf = glGenBuffers();
        vao = glGenVertexArrays();
        setupVao();

        biomeTex = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, biomeTex);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RG8, 4096, 4096, 0, GL_RG, GL_UNSIGNED_BYTE, (ByteBuffer) null);

        world.listener = (cx, sy, cz, urgent) -> dirtyQueue.add(new long[]{cx, sy, cz, urgent ? 1 : 0});
    }

    private void setupVao() {
        glBindVertexArray(vao);
        glBindBuffer(GL_ARRAY_BUFFER, arena.buffer);
        glEnableVertexAttribArray(0);
        glVertexAttribIPointer(0, 3, GL_UNSIGNED_INT, 12, 0L);
        glBindBuffer(GL_ARRAY_BUFFER, instanceBuf);
        glEnableVertexAttribArray(1);
        glVertexAttribPointer(1, 3, GL_FLOAT, false, 16, 0L);
        glVertexAttribDivisor(1, 1);
        glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, ebo);
        glBindVertexArray(0);
    }

    /** Must be called after (re)loading textures. */
    public void setTextures(RenderTables tables, Texture2D grass, Texture2D foliage) {
        this.tables = tables;
        this.grassMap = grass;
        this.foliageMap = foliage;
        for (Sec s : sections.values()) { s.dirtyVersion++; needs.add(s); }
        geometryVersion++;
    }

    public static long key(int cx, int sy, int cz) { return ((long) (cx & 0xFFFFFFF) << 36) | ((long) (cz & 0xFFFFFFF) << 8) | sy; }

    // ------------------------------------------------------------------ chunk events
    private final ByteBuffer biomeBuf = ByteBuffer.allocateDirect(512).order(ByteOrder.nativeOrder());

    public void chunkLoaded(Chunk c) {
        ByteBuffer b = biomeBuf;
        b.clear();
        for (int i = 0; i < 256; i++) b.put(c.temperature[i]).put(c.humidity[i]);
        b.flip();
        glBindTexture(GL_TEXTURE_2D, biomeTex);
        glPixelStorei(GL_UNPACK_ALIGNMENT, 2);
        glTexSubImage2D(GL_TEXTURE_2D, 0, (c.cx * 16) & 4095, (c.cz * 16) & 4095, 16, 16, GL_RG, GL_UNSIGNED_BYTE, b);
        glPixelStorei(GL_UNPACK_ALIGNMENT, 4);
    }

    public void chunkUnloaded(Chunk c) {
        for (int sy = 0; sy < 16; sy++) {
            Sec s = sections.remove(key(c.cx, sy, c.cz));
            if (s != null) { releaseMesh(s); needs.remove(s); }
        }
    }

    private void releaseMesh(Sec s) {
        geometryVersion++;
        if (s.alloc >= 0) { arena.free(s.alloc, s.allocLen); s.alloc = -1; s.allocLen = 0; }
        if (s.listIndex >= 0) {
            Sec last = meshed.remove(meshed.size() - 1);
            if (last != s) { meshed.set(s.listIndex, last); last.listIndex = s.listIndex; }
            s.listIndex = -1;
        }
    }

    // ------------------------------------------------------------------ mesh scheduling
    private boolean ready(int cx, int cz) {
        for (int dx = -1; dx <= 1; dx++)
            for (int dz = -1; dz <= 1; dz++) {
                Chunk c = world.getChunk(cx + dx, cz + dz);
                if (c == null || c.state < Chunk.STATE_LIT) return false;
            }
        return true;
    }

    /** Processes dirty sections, schedules meshing and uploads finished meshes. Call once per frame. */
    public void update(double camX, double camY, double camZ) {
        int pcx = (int) Math.floor(camX) >> 4, pcz = (int) Math.floor(camZ) >> 4;
        long[] d;
        List<Sec> urgent = null;
        while ((d = dirtyQueue.poll()) != null) {
            int cx = (int) d[0], sy = (int) d[1], cz = (int) d[2];
            if (world.getChunk(cx, cz) == null) continue;
            long k = key(cx, sy, cz);
            Sec s = sections.get(k);
            if (s == null) { s = new Sec(k, cx, sy, cz); sections.put(k, s); }
            s.dirtyVersion++;
            needs.add(s);
            if (d[3] != 0) { if (urgent == null) urgent = new ArrayList<>(); urgent.add(s); }
        }
        if (tables == null) return;
        // player edits: rebuild right now on this thread so the change is visible this frame
        if (urgent != null) {
            for (Sec s : urgent) {
                if (!ready(s.cx, s.cz)) continue;
                int v = s.dirtyVersion;
                apply(s, mainMesher.build(world, tables, s.cx, s.sy, s.cz));
                s.builtVersion = v;
                if (!s.inFlight) needs.remove(s);
            }
        }
        // schedule background meshing, nearest first. Scanning 'needs' is O(n) with 9 map lookups per entry, so it
        // only runs when enough worker slots are free (or every few frames), and empty sections are resolved here
        // without a worker round trip (most of the sky above the terrain).
        int maxInFlight = Math.max(32, Workers.defaultThreads() * 16);
        int r = renderDistance + 1;
        scanTimer++;
        if (inFlight < maxInFlight && !needs.isEmpty() && (inFlight <= maxInFlight / 2 || scanTimer >= 4)) {
            scanTimer = 0;
            int free = maxInFlight - inFlight;
            cand.clear();
            for (Iterator<Sec> it = needs.iterator(); it.hasNext(); ) {
                Sec s = it.next();
                if (s.builtVersion == s.dirtyVersion) { it.remove(); continue; }
                if (s.inFlight) continue;
                int dx = s.cx - pcx, dz = s.cz - pcz;
                if (dx * dx + dz * dz > r * r) continue;
                Chunk c = world.getChunk(s.cx, s.cz);
                if (c == null) { it.remove(); continue; }
                derpcraft.world.Section sec = c.sections[s.sy];
                if (sec == null || sec.isEmpty()) {
                    // nothing to draw: no need to wait for neighbours or a worker
                    int v = s.dirtyVersion;
                    apply(s, ChunkMesher.Mesh.EMPTY);
                    s.builtVersion = v;
                    it.remove();
                    continue;
                }
                if (!ready(s.cx, s.cz)) continue;
                s.dist = dx * dx + dz * dz + Math.abs(s.sy * 16 - (float) camY) / 64f;
                cand.add(s);
            }
            // only the 'free' nearest are needed: partial selection instead of sorting everything
            if (cand.size() > free) {
                cand.sort((a, b) -> Float.compare(a.dist, b.dist));
            }
            for (int ci = 0, cn = Math.min(free, cand.size()); ci < cn; ci++) {
                Sec s = cand.get(ci);
                s.inFlight = true;
                inFlight++;
                needs.remove(s);
                final int version = s.dirtyVersion;
                final RenderTables t = tables;
                workers.execute((long) (s.dist * 16), () -> results.add(new Result(s, version, meshers.get().build(world, t, s.cx, s.sy, s.cz))));
            }
            cand.clear();
        }
        // uploads with a time budget
        long until = System.nanoTime() + 4_000_000L;
        uploadedThisFrame = 0;
        Result res;
        while ((res = results.poll()) != null) {
            Sec s = res.sec;
            s.inFlight = false;
            inFlight--;
            if (sections.get(s.key) != s) continue;
            if (res.version > s.builtVersion) {
                apply(s, res.mesh);
                s.builtVersion = res.version;
            }
            if (s.builtVersion != s.dirtyVersion) needs.add(s);
            if (System.nanoTime() > until) break;
        }
    }

    /** Room around the geometry box: plants and leaves sway up to about half a block in the wind (wave.glsl). */
    private static final float BOUNDS_PAD = 1f;

    private void apply(Sec s, ChunkMesher.Mesh m) {
        Chunk ch = world.getChunk(s.cx, s.cz);
        if (ch != null) ch.entityBlocks[s.sy] = m.entityBlocks;
        onMeshed.meshed(s.cx, s.sy, s.cz);
        geometryVersion++;
        int n = m.vertexCount();
        if (s.alloc >= 0) { arena.free(s.alloc, s.allocLen); s.alloc = -1; s.allocLen = 0; }
        if (n == 0) {
            if (s.listIndex >= 0) releaseMesh(s);
            s.groupStart = null;
            return;
        }
        s.alloc = arena.alloc(n);
        s.allocLen = n;
        s.groupStart = m.groupStart;
        int[] b = m.bounds;
        s.bx0 = b[0] / 16f - BOUNDS_PAD; s.by0 = b[1] / 16f - BOUNDS_PAD; s.bz0 = b[2] / 16f - BOUNDS_PAD;
        s.bx1 = b[3] / 16f + BOUNDS_PAD; s.by1 = b[4] / 16f + BOUNDS_PAD; s.bz1 = b[5] / 16f + BOUNDS_PAD;
        arena.upload(s.alloc, m.verts, 0, m.verts.length);
        uploadedThisFrame += m.verts.length * 4L;
        if (s.listIndex < 0) { s.listIndex = meshed.size(); meshed.add(s); }
    }

    // ------------------------------------------------------------------ drawing
    private int[] cmds = new int[4096 * 5];
    private float[] inst = new float[4096 * 4];
    private final ArrayList<Sec> visible = new ArrayList<>();
    private long[] sortKeys = new long[8192];
    private ByteBuffer cmdBuf = ByteBuffer.allocateDirect(1 << 20).order(ByteOrder.nativeOrder());
    private ByteBuffer instBuf = ByteBuffer.allocateDirect(1 << 18).order(ByteOrder.nativeOrder());

    public static final class FrameParams {
        public Mat4 projView = new Mat4();
        public double camX, camY, camZ;
        public float fogStart, fogEnd, fogR, fogG, fogB, sun, gamma = 0, flicker = 1.5f;
        /** Ambient occlusion strength of the block corners (1 = Minecraft). */
        public float ao = 1;
        /** 1 = coloured block light. */
        public float coloredLight;
        /** HDR glow of emissive textures (cinematic pipeline only). */
        public float emissive;
        // sun / moon, sky
        public float lightX, lightY = 1, lightZ, lightStrength, sunR = 1, sunG = 1, sunB = 1, skyR, skyG, skyB;
        // shadow maps
        public int cascades, shadowTaps = 1, shadowTex, entityCascades, entityShadowTex;
        public float entityShadowTexel;
        public float[] shadowMats;
        public float shadowTexel;
        /** Dynamic lights (held / dropped / thrown light sources): world x, y, z, level (0..15) and colour (max component 1). */
        public static final int MAX_DYN = 16;
        public int dynCount;
        public final float[] dynPos = new float[MAX_DYN * 4], dynCol = new float[MAX_DYN * 3];
        // ray tracing
        public int rt, rtRays = 1, frame, voxelTex, blockTable, voxelMinX, voxelMinZ;
        /** Water shader: wave height (0 = vanilla flat water without reflections). */
        public float water;
        /**
         * Screen space reflections (post chain): water on/off and the strength of the glossy blocks. When
         * 'materialOut' is set the terrain also writes its material (normal, reflectivity, distance) into the second
         * colour attachment of the scene buffer (see PostProcessor.screenEffects).
         */
        public float ssrWater, ssrBlocks;
        public boolean materialOut;
        /** Look transitions: shadows (1 = as they are, 0 = none) and the water shader fading in / out. */
        public float shadowFade = 1, waterFade = 1;
    }


    /** R8 texture: emission strength per texture layer (for HDR glow). */
    public int emissiveTex;

    public void draw(FrameParams fp, Frustum frustum) {
        if (tables == null) return;
        int bx = (int) Math.floor(fp.camX), by = (int) Math.floor(fp.camY), bz = (int) Math.floor(fp.camZ);
        float fx = (float) (fp.camX - bx), fy = (float) (fp.camY - by), fz = (float) (fp.camZ - bz);
        float maxD = (renderDistance + 1) * 16f;
        visible.clear();
        int cnt = 0;
        float maxD2 = maxD * maxD;
        for (int idx = 0, m = meshed.size(); idx < m; idx++) {
            Sec s = meshed.get(idx);
            float ox = s.cx * 16 - bx, oz = s.cz * 16 - bz;
            float cxr = ox + 8 - fx, czr = oz + 8 - fz;
            float hd = cxr * cxr + czr * czr;
            if (hd > maxD2) continue;
            float oy = s.sy * 16 - by;
            if (!frustum.testAabb(ox + s.bx0 - fx, oy + s.by0 - fy, oz + s.bz0 - fz, ox + s.bx1 - fx, oy + s.by1 - fy, oz + s.bz1 - fz)) continue;
            float cyr = oy + 8 - fy;
            if (cnt == sortKeys.length) sortKeys = Arrays.copyOf(sortKeys, cnt * 2);
            // distance in the high bits, list index in the low 24 bits -> primitive sort
            sortKeys[cnt++] = ((long) (hd + cyr * cyr) << 24) | idx;
        }
        Arrays.sort(sortKeys, 0, cnt);
        for (int i = 0; i < cnt; i++) visible.add(meshed.get((int) (sortKeys[i] & 0xFFFFFF)));
        int n = visible.size();
        visibleSections = n;
        if (inst.length < n * 4) inst = new float[n * 8];
        for (int i = 0; i < n; i++) {
            Sec s = visible.get(i);
            inst[i * 4] = s.cx * 16 - bx; inst[i * 4 + 1] = s.sy * 16 - by; inst[i * 4 + 2] = s.cz * 16 - bz; inst[i * 4 + 3] = 0;
        }
        // build command lists: solid (front->back), cutout (front->back), translucent (back->front)
        int count = 0;
        int[] layerStart = new int[4];
        for (int layer = 0; layer < 3; layer++) {
            layerStart[layer] = count;
            for (int j = 0; j < n; j++) {
                int i = layer == 2 ? n - 1 - j : j;
                Sec s = visible.get(i);
                float ox = inst[i * 4] - fx, oy = inst[i * 4 + 1] - fy, oz = inst[i * 4 + 2] - fz;
                for (int g = 0; g < 7; g++) {
                    int gi = layer * 7 + g;
                    int first = s.groupStart[gi], verts = s.groupStart[gi + 1] - first;
                    if (verts == 0) continue;
                    if (layer != 2 && g < 6 && !faceGroupVisible(g, ox, oy, oz)) continue;
                    count = addDraws(count, (int) s.alloc + first, verts / 4, i);
                }
            }
        }
        layerStart[3] = count;
        drawCalls = count;
        if (count == 0) return;
        upload(n, count);

        glBindVertexArray(vao);
        textures.bind(0);
        grassMap.bind(1);
        foliageMap.bind(2);
        glActiveTexture(GL_TEXTURE3);
        glBindTexture(GL_TEXTURE_2D, biomeTex);
        glActiveTexture(GL_TEXTURE4);
        glBindTexture(GL_TEXTURE_2D, emissiveTex);
        LightingState.bindTextures(fp);

        glEnable(GL_DEPTH_TEST);
        glDepthFunc(GL_LEQUAL);
        glEnable(GL_CULL_FACE);
        glDisable(GL_BLEND);
        glDepthMask(true);
        if (fp.materialOut) glDrawBuffers(MRT);

        setUniforms(solid, fp, bx, bz, fx, fy, fz, 0f);
        drawRange(layerStart[0], layerStart[1]);
        setUniforms(cutout, fp, bx, bz, fx, fy, fz, 0.5f);
        drawRange(layerStart[1], layerStart[2]);

        // translucent: blended, double sided (the material attachment is overwritten, not blended: the
        // reflection belongs to the nearest surface, e.g. the water and not the lake bed)
        glEnable(GL_BLEND);
        if (fp.materialOut) glDisablei(GL_BLEND, 1);
        glBlendFuncSeparate(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA, GL_ONE, GL_ONE_MINUS_SRC_ALPHA);
        glDisable(GL_CULL_FACE);
        cutout.set("uAlphaCut", 0.004f);
        drawRange(layerStart[2], layerStart[3]);
        glEnable(GL_CULL_FACE);
        glDisable(GL_BLEND);
        if (fp.materialOut) glDrawBuffers(ATT0);
        glBindVertexArray(0);
    }

    private static final int[] MRT = {GL_COLOR_ATTACHMENT0, GL_COLOR_ATTACHMENT1}, ATT0 = {GL_COLOR_ATTACHMENT0};

    /** Wind uniforms of wave.glsl (terrain and shadow shaders); 'on' false = no sway. */
    private void waveUniforms(Shader sh, double camX, double camY, double camZ, boolean on) {
        sh.set("uWave", on ? wave : 0f);
        sh.set("uWaveTime", waveTime);
        sh.seti3("uWaveCam", (int) Math.floor(camX) & 1023, (int) Math.floor(camY) & 1023, (int) Math.floor(camZ) & 1023);
        sh.set("uEmissive", 4);
    }

    /** Adds indirect draw commands for 'quads' quads from vertex 'base' of section instance 'instance'; returns the new count. */
    private int addDraws(int count, int base, int quads, int instance) {
        while (quads > 0) {
            int q = Math.min(quads, MAX_QUADS_PER_DRAW);
            if ((count + 1) * 5 > cmds.length) cmds = Arrays.copyOf(cmds, cmds.length * 2);
            int c = count * 5;
            cmds[c] = q * 6; cmds[c + 1] = 1; cmds[c + 2] = 0; cmds[c + 3] = base; cmds[c + 4] = instance;
            count++;
            quads -= q;
            base += q * 4;
        }
        return count;
    }

    /** Uploads this pass's section offsets (n instances) and draw commands. */
    private void upload(int n, int count) {
        instBuf = ensure(instBuf, n * 16);
        instBuf.asFloatBuffer().put(inst, 0, n * 4);
        instBuf.limit(n * 16);
        glBindBuffer(GL_ARRAY_BUFFER, instanceBuf);
        glBufferData(GL_ARRAY_BUFFER, instBuf, GL_STREAM_DRAW);
        cmdBuf = ensure(cmdBuf, count * 20);
        cmdBuf.asIntBuffer().put(cmds, 0, count * 5);
        cmdBuf.limit(count * 20);
        glBindBuffer(GL_DRAW_INDIRECT_BUFFER, indirectBuf);
        glBufferData(GL_DRAW_INDIRECT_BUFFER, cmdBuf, GL_STREAM_DRAW);
    }

    /**
     * Depth-only pass of solid and cutout geometry for a shadow cascade. 'lightProjView' maps camera-relative
     * positions into the cascade; sections are culled with 'lightFrustum' and a horizontal radius.
     */
    public void drawShadow(Mat4 lightProjView, Frustum lightFrustum, double camX, double camY, double camZ, float radius, boolean sway) {
        if (tables == null) return;
        int bx = (int) Math.floor(camX), by = (int) Math.floor(camY), bz = (int) Math.floor(camZ);
        float fx = (float) (camX - bx), fy = (float) (camY - by), fz = (float) (camZ - bz);
        float r2 = (radius + 24) * (radius + 24);
        int n = 0;
        if (inst.length < meshed.size() * 4) inst = new float[meshed.size() * 4 + 64];
        visible.clear();
        for (Sec s : meshed) {
            float ox = s.cx * 16 - bx, oy = s.sy * 16 - by, oz = s.cz * 16 - bz;
            float cxr = ox + 8 - fx, czr = oz + 8 - fz;
            if (cxr * cxr + czr * czr > r2) continue;
            if (!lightFrustum.testAabb(ox + s.bx0 - fx, oy + s.by0 - fy, oz + s.bz0 - fz, ox + s.bx1 - fx, oy + s.by1 - fy, oz + s.bz1 - fz)) continue;
            inst[n * 4] = ox; inst[n * 4 + 1] = oy; inst[n * 4 + 2] = oz; inst[n * 4 + 3] = 0;
            visible.add(s);
            n++;
        }
        if (n == 0) return;
        int count = 0;
        int[] layerStart = new int[3];
        for (int layer = 0; layer < 2; layer++) {
            layerStart[layer] = count;
            for (int i = 0; i < n; i++) {
                Sec s = visible.get(i);
                for (int g = 0; g < 7; g++) {
                    // group 6 of the cutout layer = crossed plants (grass, flowers). Their thin blades alias in the shadow
                    // map and shadow themselves, which made grass flicker while the sun moves: they cast no shadow.
                    if (layer == 1 && g == 6) continue;
                    int gi = layer * 7 + g;
                    int first = s.groupStart[gi], verts = s.groupStart[gi + 1] - first;
                    if (verts == 0) continue;
                    count = addDraws(count, (int) s.alloc + first, verts / 4, i);
                }
            }
        }
        layerStart[2] = count;
        if (count == 0) return;
        upload(n, count);
        glBindVertexArray(vao);
        textures.bind(0);
        glActiveTexture(GL_TEXTURE4);
        glBindTexture(GL_TEXTURE_2D, emissiveTex);
        glActiveTexture(GL_TEXTURE0);
        for (int pass = 0; pass < 2; pass++) {
            Shader sh = pass == 0 ? shadowSolid : shadowCutout;
            sh.use();
            sh.set("uProjView", lightProjView);
            sh.set("uCamFrac", fx, fy, fz);
            sh.set("uBlocks", 0);
            waveUniforms(sh, camX, camY, camZ, sway);
            drawRange(layerStart[pass], layerStart[pass + 1]);
        }
        glBindVertexArray(0);
    }

    private static boolean faceGroupVisible(int g, float ox, float oy, float oz) {
        return switch (g) {
            case 0 -> oy + 16 > 0;   // down faces: camera below the section top
            case 1 -> oy < 0;        // up faces: camera above the section bottom
            case 2 -> oz + 16 > 0;   // north (-z)
            case 3 -> oz < 0;        // south (+z)
            case 4 -> ox + 16 > 0;   // west (-x)
            default -> ox < 0;       // east (+x)
        };
    }

    private void drawRange(int from, int to) {
        if (to <= from) return;
        glMultiDrawElementsIndirect(GL_TRIANGLES, GL_UNSIGNED_INT, (long) from * 20, to - from, 20);
    }

    private void setUniforms(Shader sh, FrameParams fp, int bx, int bz, float fx, float fy, float fz, float alphaCut) {
        sh.use();
        sh.set("uProjView", fp.projView);
        sh.set("uCamFrac", fx, fy, fz);
        sh.seti("uCamBlockXZ", bx, bz);
        sh.set("uBlocks", 0);
        sh.set("uGrassMap", 1);
        sh.set("uFoliageMap", 2);
        sh.set("uBiome", 3);
        sh.set3v("uFixedTints", tables.fixedTints);
        sh.set("uFogColor", fp.fogR, fp.fogG, fp.fogB);
        sh.set("uFog", fp.fogStart, fp.fogEnd);
        sh.set("uAlphaCut", alphaCut);
        sh.set("uAO", fp.ao);
        sh.set("uSun", fp.sun);
        sh.set("uGamma", fp.gamma);
        sh.set("uFlicker", fp.flicker);
        sh.set("uColoredLight", fp.coloredLight);
        sh.set("uEmissive", 4);
        sh.set("uEmissiveBoost", fp.emissive);
        LightingState.setUniforms(sh, fp);
        sh.set("uFrame", fp.frame);
        sh.set("uRTRays", fp.rtRays);
        waveUniforms(sh, fp.camX, fp.camY, fp.camZ, true);
        sh.set("uWater", fp.water);
        sh.set("uWaterFade", fp.waterFade);
        sh.set("uSSRWater", fp.ssrWater);
        sh.set("uSSRBlocks", fp.ssrBlocks);
        // dynamic lights, relative to the camera
        sh.set("uDynCount", fp.dynCount);
        if (fp.dynCount > 0) {
            for (int i = 0; i < fp.dynCount; i++) {
                dynRel[i * 4] = (float) (fp.dynPos[i * 4] - fp.camX);
                dynRel[i * 4 + 1] = (float) (fp.dynPos[i * 4 + 1] - fp.camY);
                dynRel[i * 4 + 2] = (float) (fp.dynPos[i * 4 + 2] - fp.camZ);
                dynRel[i * 4 + 3] = fp.dynPos[i * 4 + 3];
            }
            sh.set4v("uDynPos", dynRel);
            sh.set3v("uDynCol", fp.dynCol);
        }
    }

    private final float[] dynRel = new float[FrameParams.MAX_DYN * 4];

    private static ByteBuffer ensure(ByteBuffer b, int bytes) {
        if (b.capacity() < bytes) b = ByteBuffer.allocateDirect(Math.max(bytes, b.capacity() * 2)).order(ByteOrder.nativeOrder());
        b.clear();
        return b;
    }

    public String stats() {
        return String.format("Sections: %d visible / %d meshed, draws %d, VRAM %d/%d MB, meshing %d",
                visibleSections, meshed.size(), drawCalls, arena.usedBytes() >> 20, arena.capacityBytes() >> 20, inFlight);
    }

    public void close() {
        glDeleteBuffers(arena.buffer);
        glDeleteBuffers(ebo);
        glDeleteBuffers(instanceBuf);
        glDeleteBuffers(indirectBuf);
        glDeleteVertexArrays(vao);
        glDeleteTextures(biomeTex);
    }
}
