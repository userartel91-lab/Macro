package derpcraft.camera;

import derpcraft.game.Session;
import derpcraft.render.EntityRenderer;
import derpcraft.render.Texture2D;
import derpcraft.render.WorldRenderer;
import derpcraft.util.Frustum;
import derpcraft.util.Mat4;
import derpcraft.world.Chunk;
import derpcraft.world.be.BlockEntity;

import java.nio.ByteBuffer;
import java.util.*;

import static org.lwjgl.opengl.GL45C.*;

/**
 * The cameras of the loaded world and their pictures. Screens (canvases) ask for a camera by code while they are
 * drawn; only cameras asked for in the last frames are rendered, a few per frame, each into its own small
 * framebuffer. A camera view is the terrain, the sky and the entities (no shadows, clouds or post effects).
 */
public final class CameraNetwork {
    /** Set when a camera is placed, loaded or changed: the code list is rebuilt on the next frame. */
    static volatile boolean dirty = true;

    /** A camera was added or removed (shot to pieces...): the list is read again. */
    public static void markDirty() { dirty = true; }
    /** Camera pictures rendered per frame at most, and the fewest frames between two pictures of one camera. */
    private static final int PER_FRAME = 2, MIN_GAP = 2;
    /** A picture nobody looked at for this many frames is freed. */
    private static final int FORGET_FRAMES = 600;

    public enum Status { OK, NO_CAMERA, OFF, WAITING }

    private final Session s;
    private final Map<String, CameraEntity> byCode = new HashMap<>();
    private final Map<String, View> views = new HashMap<>();
    private final WorldRenderer.FrameParams cfp = new WorldRenderer.FrameParams();
    private final Frustum frustum = new Frustum();
    private long frame;
    private int scanTimer;
    /** Camera currently being rendered (its screens are not drawn in its own picture: no feedback loop). */
    private String rendering;

    private static final class View {
        int fbo, tex, depth, w, h;
        Texture2D texture;
        long wanted = -1000, rendered = -1000;
        CameraEntity camera;
    }

    public CameraNetwork(Session s) { this.s = s; }

    // ------------------------------------------------------------------ cameras by code
    private void scan() {
        byCode.clear();
        var p = s.player;
        Map<String, Double> best = new HashMap<>();
        for (Chunk c : s.world.chunks.values()) {
            if (c.blockEntities.isEmpty()) continue;
            for (BlockEntity be : c.blockEntities.values()) {
                if (!(be instanceof CameraEntity ce) || ce.code.isEmpty()) continue;
                if (s.world.getBlock(ce.x, ce.y, ce.z) != CameraBlock.INSTANCE) continue;
                // two cameras with one code: the nearer one to the player wins
                double d = (ce.x - p.x) * (ce.x - p.x) + (ce.z - p.z) * (ce.z - p.z);
                Double old = best.get(ce.code);
                if (old == null || d < old) { best.put(ce.code, d); byCode.put(ce.code, ce); }
            }
        }
    }

    /** The camera with this code in the loaded world, or null. */
    public CameraEntity camera(String code) {
        if (code == null || code.isEmpty()) return null;
        CameraEntity ce = byCode.get(code);
        if (ce != null && (s.world.getBlockEntity(ce.x, ce.y, ce.z) != ce || !code.equals(ce.code))) { dirty = true; return null; }
        return ce;
    }

    public Status status(String code) {
        CameraEntity ce = camera(code);
        if (ce == null) return Status.NO_CAMERA;
        if (!ce.on) return Status.OFF;
        View v = views.get(code);
        return v == null || v.rendered < 0 || v.camera != ce ? Status.WAITING : Status.OK;
    }

    /**
     * Called by a screen while it is drawn (main view only): the camera picture to show, or null (no camera, off, not
     * rendered yet, or the picture being rendered right now). Asking keeps the camera's picture up to date.
     */
    public Texture2D feed(String code) {
        if (code == null || code.isEmpty() || code.equals(rendering)) return null;
        CameraEntity ce = camera(code);
        if (ce == null || !ce.on) return null;
        View v = views.computeIfAbsent(code, k -> new View());
        if (rendering == null) v.wanted = frame;
        return v.texture != null && v.camera == ce && v.rendered >= 0 ? v.texture : null;
    }

    /** Whether the camera of this code films in night vision (the screen tints its picture). */
    public boolean nightVision(String code) { CameraEntity ce = camera(code); return ce != null && ce.nightVision; }

    // ------------------------------------------------------------------ rendering
    /**
     * Renders the camera pictures screens asked for. Call once per frame before the main view (it leaves the default
     * framebuffer bound; the caller sets its viewport again). 'player' = the player model to show, or null.
     */
    public void render(WorldRenderer.FrameParams main, float partial, EntityRenderer.PlayerPose player) {
        frame++;
        if (dirty || ++scanTimer >= 40) { dirty = false; scanTimer = 0; scan(); }
        // pictures nobody looks at any more
        for (Iterator<Map.Entry<String, View>> it = views.entrySet().iterator(); it.hasNext(); ) {
            View v = it.next().getValue();
            if (frame - v.wanted > FORGET_FRAMES) { free(v); it.remove(); }
        }
        // the stalest wanted pictures first
        List<Map.Entry<String, View>> todo = new ArrayList<>();
        for (var e : views.entrySet()) {
            View v = e.getValue();
            if (frame - v.wanted > 2 || frame - v.rendered < MIN_GAP) continue;
            todo.add(e);
        }
        if (todo.isEmpty()) return;
        todo.sort(Comparator.comparingLong(e -> e.getValue().rendered));
        EntityRenderer er = s.game.entities;
        EntityRenderer.PlayerPose oldPlayer = er.player;
        double oldYaw = er.yawRad, oldPitch = er.pitchRad;
        int done = 0;
        try {
            for (var e : todo) {
                if (done >= PER_FRAME) break;
                CameraEntity ce = camera(e.getKey());
                if (ce == null || !ce.on) continue;
                View v = e.getValue();
                renderView(v, ce, e.getKey(), main, partial, player);
                done++;
            }
        } finally {
            rendering = null;
            er.player = oldPlayer;
            er.yawRad = oldYaw; er.pitchRad = oldPitch;
            er.viewPass = false;
            glBindFramebuffer(GL_FRAMEBUFFER, 0);
        }
    }

    private void renderView(View v, CameraEntity ce, String code, WorldRenderer.FrameParams main, float partial, EntityRenderer.PlayerPose player) {
        int[] size = CameraEntity.SIZES[Math.max(0, Math.min(CameraEntity.SIZES.length - 1, ce.quality))];
        ensure(v, size[0], size[1]);
        v.camera = ce;
        rendering = code;
        var game = s.game;
        var world = s.world;
        double ex = ce.eyeX(), ey = ce.eyeY(), ez = ce.eyeZ();
        int rd = game.settings.renderDistance;
        float range = Math.min(ce.range, rd * 16f);
        Mat4 proj = new Mat4().perspective((float) Math.toRadians(ce.fov), v.w / (float) v.h, 0.05f, Math.max(64, range * 1.5f));
        Mat4 view = new Mat4().rotate((float) Math.toRadians(ce.pitch), 1, 0, 0).rotate((float) Math.toRadians(ce.yaw + 180), 0, 1, 0);
        Mat4 pv = proj.mul(view);
        frustum.set(pv);
        copy(main, cfp);
        cfp.projView = pv;
        cfp.camX = ex; cfp.camY = ey; cfp.camZ = ez;
        cfp.fogEnd = range; cfp.fogStart = range * 0.7f;
        if (ce.nightVision) { cfp.gamma = 1; cfp.sun = Math.max(main.sun, 0.75f); }

        glBindFramebuffer(GL_FRAMEBUFFER, v.fbo);
        glViewport(0, 0, v.w, v.h);
        glClearColor(cfp.fogR, cfp.fogG, cfp.fogB, 1);
        glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
        game.sky.draw(pv, game.assets, world.info.time, partial, rd, false);
        int oldRd = s.renderer.renderDistance;
        s.renderer.renderDistance = Math.min(oldRd, (int) Math.ceil(range / 16));     // only the sections the camera can see
        try { s.renderer.draw(cfp, frustum); } finally { s.renderer.renderDistance = oldRd; }
        EntityRenderer er = game.entities;
        er.viewPass = true;
        er.player = player;
        er.yawRad = Math.toRadians(ce.yaw);
        er.pitchRad = Math.toRadians(ce.pitch);
        er.render(world, pv, partial, ex, ey, ez, cfp, null, 0, (int) range, frustum);
        // the picture is shown with alpha testing: make every pixel opaque whatever the passes wrote into alpha
        glColorMask(false, false, false, true);
        glClearColor(0, 0, 0, 1);
        glClear(GL_COLOR_BUFFER_BIT);
        glColorMask(true, true, true, true);
        v.rendered = frame;
    }

    /** Main view lighting without its shadows, ray tracing or emissive glow (they belong to the main camera). */
    private static void copy(WorldRenderer.FrameParams a, WorldRenderer.FrameParams b) {
        b.fogR = a.fogR; b.fogG = a.fogG; b.fogB = a.fogB;
        b.sun = a.sun; b.gamma = a.gamma; b.flicker = a.flicker; b.coloredLight = a.coloredLight;
        b.emissive = 0;
        b.lightX = a.lightX; b.lightY = a.lightY; b.lightZ = a.lightZ; b.lightStrength = a.lightStrength;
        b.sunR = a.sunR; b.sunG = a.sunG; b.sunB = a.sunB; b.skyR = a.skyR; b.skyG = a.skyG; b.skyB = a.skyB;
        b.cascades = 0; b.entityCascades = 0; b.rt = 0;
        b.shadowTaps = 1; b.shadowMats = a.shadowMats; b.shadowTex = a.shadowTex; b.entityShadowTex = a.entityShadowTex;
        b.dynCount = a.dynCount;
        System.arraycopy(a.dynPos, 0, b.dynPos, 0, a.dynPos.length);
        System.arraycopy(a.dynCol, 0, b.dynCol, 0, a.dynCol.length);
        b.frame = a.frame;
        b.water = a.water; b.waterFade = a.waterFade;                       // waves and sky reflection; screen space reflections are the main view's
        b.ssrWater = 0; b.ssrBlocks = 0; b.materialOut = false;
    }

    private static void ensure(View v, int w, int h) {
        if (v.fbo != 0 && v.w == w && v.h == h) return;
        free(v);
        v.w = w; v.h = h;
        v.tex = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, v.tex);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, w, h, 0, GL_RGBA, GL_UNSIGNED_BYTE, (ByteBuffer) null);
        v.depth = glGenRenderbuffers();
        glBindRenderbuffer(GL_RENDERBUFFER, v.depth);
        glRenderbufferStorage(GL_RENDERBUFFER, GL_DEPTH_COMPONENT24, w, h);
        glBindRenderbuffer(GL_RENDERBUFFER, 0);
        v.fbo = glGenFramebuffers();
        glBindFramebuffer(GL_FRAMEBUFFER, v.fbo);
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, v.tex, 0);
        glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_DEPTH_ATTACHMENT, GL_RENDERBUFFER, v.depth);
        if (glCheckFramebufferStatus(GL_FRAMEBUFFER) != GL_FRAMEBUFFER_COMPLETE) System.err.println("Camera framebuffer incomplete");
        glBindFramebuffer(GL_FRAMEBUFFER, 0);
        v.texture = new Texture2D(v.tex, w, h);
        v.rendered = -1000;
    }

    private static void free(View v) {
        if (v.fbo != 0) glDeleteFramebuffers(v.fbo);
        if (v.tex != 0) glDeleteTextures(v.tex);
        if (v.depth != 0) glDeleteRenderbuffers(v.depth);
        v.fbo = v.tex = v.depth = 0;
        v.texture = null;
    }

    /** Frees every picture (world closed). */
    public void delete() {
        for (View v : views.values()) free(v);
        views.clear();
        byCode.clear();
    }
}
