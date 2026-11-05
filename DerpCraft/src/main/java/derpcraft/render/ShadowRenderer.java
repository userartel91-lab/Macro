package derpcraft.render;

import derpcraft.util.Frustum;
import derpcraft.util.Mat4;

import java.nio.ByteBuffer;

import static org.lwjgl.opengl.GL45C.*;

/**
 * Cascaded shadow maps for the sun (or the moon at night). Cascades are centred around the camera and snapped to
 * whole shadow-map texels in world space, so shadows don't shimmer while the camera moves (important for filming).
 * A second, smaller map with the same cascades holds only entities (chests, signs, beds, dropped items, the player),
 * so they also cast shadows when the terrain is ray traced.
 */
public final class ShadowRenderer {
    public static final int MAX_CASCADES = 4;

    /** One depth texture array with a framebuffer per layer. */
    private static final class MapSet {
        int tex, size, generation;
        final int[] fbos = new int[MAX_CASCADES];

        void ensure(int s) {
            if (s == size && tex != 0) return;
            free();
            generation++;
            size = s;
            tex = glGenTextures();
            glBindTexture(GL_TEXTURE_2D_ARRAY, tex);
            glTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
            glTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
            glTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
            glTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
            glTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_COMPARE_MODE, GL_COMPARE_REF_TO_TEXTURE);
            glTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_COMPARE_FUNC, GL_LEQUAL);
            glTexImage3D(GL_TEXTURE_2D_ARRAY, 0, GL_DEPTH_COMPONENT32F, size, size, MAX_CASCADES, 0, GL_DEPTH_COMPONENT, GL_FLOAT, (ByteBuffer) null);
            for (int i = 0; i < MAX_CASCADES; i++) {
                fbos[i] = glGenFramebuffers();
                glBindFramebuffer(GL_FRAMEBUFFER, fbos[i]);
                glFramebufferTextureLayer(GL_FRAMEBUFFER, GL_DEPTH_ATTACHMENT, tex, 0, i);
                glDrawBuffer(GL_NONE);
                glReadBuffer(GL_NONE);
            }
            glBindFramebuffer(GL_FRAMEBUFFER, 0);
        }

        void free() {
            if (tex == 0) return;
            glDeleteTextures(tex);
            for (int i = 0; i < MAX_CASCADES; i++) if (fbos[i] != 0) { glDeleteFramebuffers(fbos[i]); fbos[i] = 0; }
            tex = 0;
            size = 0;
        }
    }

    /** Draws shadow casters (entities) with the cascade matrix and its radius. */
    public interface Casters { void draw(Mat4 vp, float radius); }

    private final MapSet terrain = new MapSet(), entities = new MapSet();
    /**
     * Terrain cascades are kept when nothing changed (same matrix, same chunk meshes, same map): a still camera with
     * frozen time (tripod shots) no longer re-draws up to 4 terrain depth passes every frame.
     */
    private final float[] cachedMats = new float[MAX_CASCADES * 16];
    private final long[] cachedGeom = new long[MAX_CASCADES];
    private final int[] cachedTex = new int[MAX_CASCADES];
    /** Wind clock the nearest cascade was drawn at (swaying leaves: that cascade is redrawn every frame). */
    private float cachedWaveTime = Float.NaN, cachedWave;
    /** Matrices from camera-relative position to cascade clip space, flattened for the shader. */
    public final float[] matrices = new float[MAX_CASCADES * 16];
    public final float[] radii = new float[MAX_CASCADES];
    public int activeCascades, entityCascades;
    private final Frustum frustum = new Frustum();
    /** Direction towards the light (normalized) and the light's strength (0 at the horizon). */
    public float lx, ly = 1, lz, strength;

    /** Quality presets: {cascades, resolution, radius of the largest cascade}. Index 0 = entity shadows only. */
    private static final int[][] PRESETS = {{2, 1024, 64}, {2, 1024, 64}, {3, 2048, 128}, {3, 2048, 200}, {4, 4096, 320}};

    public int texture() { return terrain.tex; }
    public int size() { return terrain.size; }
    public int entityTexture() { return entities.tex; }
    public int entitySize() { return entities.size; }

    /** Sun/moon direction and strength from the celestial angle (0 = noon). */
    public void setLight(float celestial) {
        double a = celestial * Math.PI * 2;
        float sx = (float) -Math.sin(a), sy = (float) Math.cos(a);
        boolean moon = sy < 0;
        if (moon) { sx = -sx; sy = -sy; }
        // a tiny tilt so shadows aren't perfectly axis aligned at noon (looks more natural)
        lx = sx; ly = sy; lz = 0.12f;
        float len = (float) Math.sqrt(lx * lx + ly * ly + lz * lz);
        lx /= len; ly /= len; lz /= len;
        float horizon = smooth(0.02f, 0.18f, sy);
        strength = (moon ? 0.45f : 1f) * horizon;
    }

    private static float smooth(float a, float b, float x) {
        float t = Math.max(0, Math.min(1, (x - a) / (b - a)));
        return t * t * (3 - 2 * t);
    }

    /**
     * Renders all cascades: terrain (if 'drawTerrain') and the entity casters. View direction (dx,dy,dz) shifts each
     * cascade forward so more texels land in front of the camera.
     */
    public void render(WorldRenderer wr, int quality, boolean drawTerrain, Casters casters,
                       double camX, double camY, double camZ, float dx, float dy, float dz) {
        activeCascades = 0;
        entityCascades = 0;
        if (strength <= 0.001f) return;
        int[] preset = PRESETS[Math.max(0, Math.min(PRESETS.length - 1, quality))];
        int cascades = preset[0], size = preset[1];
        if (drawTerrain) terrain.ensure(size);
        entities.ensure(Math.min(2048, size));
        // light basis: d = towards the light, r = world z axis made perpendicular to d, u = d x r.
        // The sun travels in the x/y plane, so r stays (almost) fixed to the world while the sun moves: the texel grid
        // of the shadow map no longer spins over the block edges all day, which made the shadows crawl and jump.
        float[] d = {lx, ly, lz};
        float[] r = {-lz * lx, -lz * ly, 1 - lz * lz};
        normalize(r);
        float[] u = cross(d, r);
        normalize(u);
        glEnable(GL_DEPTH_TEST);
        glDepthFunc(GL_LEQUAL);
        glDepthMask(true);
        glDisable(GL_CULL_FACE);
        glDisable(GL_BLEND);
        glEnable(GL_POLYGON_OFFSET_FILL);
        glPolygonOffset(1.5f, 3f);
        float maxR = preset[2];
        for (int c = 0; c < cascades; c++) {
            float radius = maxR * (float) Math.pow(0.32, cascades - 1 - c);
            if (c == 0) radius = Math.max(radius, 16);
            radii[c] = radius;
            // centre: camera + a bit forward, in absolute light space, snapped to texels
            double cxw = camX + dx * radius * 0.4, cyw = camY + dy * radius * 0.4, czw = camZ + dz * radius * 0.4;
            // snap to the coarser of the two maps (terrain / entities), so both stay aligned to the world while the camera moves
            double texel = 2.0 * radius / Math.min(size, 2048);
            double lsx = cxw * r[0] + cyw * r[1] + czw * r[2], lsy = cxw * u[0] + cyw * u[1] + czw * u[2];
            lsx = Math.floor(lsx / texel) * texel;
            lsy = Math.floor(lsy / texel) * texel;
            double camLsx = camX * r[0] + camY * r[1] + camZ * r[2], camLsy = camX * u[0] + camY * u[1] + camZ * u[2];
            float ox = (float) (lsx - camLsx), oy = (float) (lsy - camLsy);
            Mat4 view = new Mat4();
            float[] m = view.m;
            m[0] = r[0]; m[4] = r[1]; m[8] = r[2];
            m[1] = u[0]; m[5] = u[1]; m[9] = u[2];
            m[2] = d[0]; m[6] = d[1]; m[10] = d[2];
            m[12] = -ox; m[13] = -oy; m[14] = 0;
            // depth range: from 300 blocks towards the light to radius + 300 blocks away from it
            Mat4 proj = new Mat4().ortho(-radius, radius, -radius, radius, -300, radius + 300);
            Mat4 vp = new Mat4().set(proj).mul(view);
            System.arraycopy(vp.m, 0, matrices, c * 16, 16);
            // Swaying leaves: only the nearest cascade follows the wind (and is redrawn while the clock runs); the larger
            // ones keep still leaves, the difference is far below one of their texels.
            boolean sway = c == 0 && wr.wave > 0;
            boolean cached = drawTerrain && cachedTex[c] == terrain.generation && cachedGeom[c] == wr.geometryVersion
                    && java.util.Arrays.equals(cachedMats, c * 16, c * 16 + 16, vp.m, 0, 16)
                    && (c != 0 || (sway ? cachedWaveTime == wr.waveTime && cachedWave == wr.wave : Float.isNaN(cachedWaveTime)));
            if (drawTerrain && !cached) {
                System.arraycopy(vp.m, 0, cachedMats, c * 16, 16);
                cachedGeom[c] = wr.geometryVersion;
                cachedTex[c] = terrain.generation;
                if (c == 0) { cachedWaveTime = sway ? wr.waveTime : Float.NaN; cachedWave = wr.wave; }
                frustum.set(vp);
                glViewport(0, 0, terrain.size, terrain.size);
                glBindFramebuffer(GL_FRAMEBUFFER, terrain.fbos[c]);
                glClear(GL_DEPTH_BUFFER_BIT);
                wr.drawShadow(vp, frustum, camX, camY, camZ, radius * 1.5f, sway);
            }
            glViewport(0, 0, entities.size, entities.size);
            glBindFramebuffer(GL_FRAMEBUFFER, entities.fbos[c]);
            glClear(GL_DEPTH_BUFFER_BIT);
            if (casters != null) casters.draw(vp, radius);
        }
        activeCascades = drawTerrain ? cascades : 0;
        entityCascades = cascades;
        glDisable(GL_POLYGON_OFFSET_FILL);
        glEnable(GL_CULL_FACE);
        glBindFramebuffer(GL_FRAMEBUFFER, 0);
    }

    private static float[] cross(float[] a, float[] b) {
        return new float[]{a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]};
    }

    private static void normalize(float[] v) {
        float l = (float) Math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]);
        v[0] /= l; v[1] /= l; v[2] /= l;
    }

    public void delete() { terrain.free(); entities.free(); }
}
