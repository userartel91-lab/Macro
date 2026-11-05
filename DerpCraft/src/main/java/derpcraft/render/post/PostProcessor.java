package derpcraft.render.post;

import derpcraft.render.Shader;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.file.*;
import java.util.*;

import static org.lwjgl.opengl.GL45C.*;

/**
 * HDR scene buffer and the cinematic post chain: bloom (dual Kawase mip chain) and the final colour grade
 * (see post_final.fsh). The world is rendered into an RGBA16F target; the GUI is drawn afterwards, ungraded.
 */
public final class PostProcessor {
    private static final int BLOOM_LEVELS = 6;
    private final Shader down = new Shader("post", "post_down", ""), up = new Shader("post", "post_up", ""), fin = new Shader("post", "post_final", ""),
            taa = new Shader("post", "post_taa", ""), copy = new Shader("post", "post_copy", "");
    private Shader coc, dof, dofMix, mblur, streak, ghosts, ssr, godRays, blur, ssao, ssaoApply;
    private int lensTmp, lensFbo, dofA, dofB, dofFboA, dofFboB, flareA, flareB, flareFboA, flareFboB, hw, hh;
    private derpcraft.util.Mat4 lensPrevProjView;
    private double lensPrevX, lensPrevY, lensPrevZ;
    private long lastLensFrame = -10;

    /** Camera lens settings for the current frame (filled in by the game every frame). */
    public static final class Lens {
        public boolean dof;
        public float focal = 35, aperture = 2.8f, focus = 8, bokehScale = 1, squeeze = 1;
        public float near = 0.05f, far = 512;
        public float motionBlur, flare, flareLength = 0.7f, ghosts, distortion, aberration, letterbox;
        public float[] flareTint = {0.35f, 0.55f, 1.0f};

    }
    public final Lens lens = new Lens();
    private final int[] histTexs = new int[2], histFbos = new int[2];
    private int histIndex;
    private boolean historyValid;
    private derpcraft.util.Mat4 prevProjView;
    private double prevX, prevY, prevZ;
    private long lastTemporalFrame = -10, frameCounter;
    private final int vao = glGenVertexArrays();
    private int sceneFbo, sceneColor, sceneDepth, width, height;
    /** Second colour attachment of the scene: terrain material for the reflections (see terrain.fsh fragMaterial). */
    private int sceneMaterial;
    private static final int[] ATT0 = {GL_COLOR_ATTACHMENT0}, ATT01 = {GL_COLOR_ATTACHMENT0, GL_COLOR_ATTACHMENT1};
    private static final float[] ZERO4 = new float[4];
    private final int[] bloomFbo = new int[BLOOM_LEVELS], bloomTex = new int[BLOOM_LEVELS], bw = new int[BLOOM_LEVELS], bh = new int[BLOOM_LEVELS];
    private final int curveTex, blackTex;
    private int lutTex;
    private float lutSize;
    private String lutName = "";
    private float[] curveKey = new float[0];
    private boolean curveKeyValid;
    private int histFbo, histTex;
    public static final int HIST_W = 160, HIST_H = 90;
    /** Luminance histogram (64 bins) and RGB histograms of the final image, updated while {@link #wantHistogram}. */
    public final float[][] histogram = new float[4][64];
    public boolean wantHistogram;
    private int histFrame;

    public PostProcessor() {
        curveTex = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, curveTex);
        params(GL_LINEAR, GL_CLAMP_TO_EDGE);
        blackTex = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, blackTex);
        params(GL_NEAREST, GL_CLAMP_TO_EDGE);
        ByteBuffer b = ByteBuffer.allocateDirect(4).order(ByteOrder.nativeOrder());
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, 1, 1, 0, GL_RGBA, GL_UNSIGNED_BYTE, b);
    }

    private static void params(int filter, int wrap) {
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, filter);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, filter);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, wrap);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, wrap);
    }

    private static int colorTex(int w, int h, int fmt) {
        int t = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, t);
        params(GL_LINEAR, GL_CLAMP_TO_EDGE);
        glTexImage2D(GL_TEXTURE_2D, 0, fmt, w, h, 0, GL_RGBA, GL_FLOAT, (ByteBuffer) null);
        return t;
    }

    private static int fbo(int color, int depth) {
        int f = glGenFramebuffers();
        glBindFramebuffer(GL_FRAMEBUFFER, f);
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, color, 0);
        if (depth != 0) glFramebufferTexture2D(GL_FRAMEBUFFER, GL_DEPTH_ATTACHMENT, GL_TEXTURE_2D, depth, 0);
        int st = glCheckFramebufferStatus(GL_FRAMEBUFFER);
        if (st != GL_FRAMEBUFFER_COMPLETE) System.err.println("Framebuffer incomplete: 0x" + Integer.toHexString(st));
        return f;
    }

    private void resize(int w, int h) {
        if (w == width && h == height && sceneFbo != 0) return;
        free();
        width = w; height = h;
        sceneColor = colorTex(w, h, GL_RGBA16F);
        sceneDepth = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, sceneDepth);
        params(GL_NEAREST, GL_CLAMP_TO_EDGE);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_DEPTH_COMPONENT24, w, h, 0, GL_DEPTH_COMPONENT, GL_FLOAT, (ByteBuffer) null);
        sceneFbo = fbo(sceneColor, sceneDepth);
        sceneMaterial = colorTex(w, h, GL_RGBA16F);
        glBindTexture(GL_TEXTURE_2D, sceneMaterial);
        params(GL_NEAREST, GL_CLAMP_TO_EDGE);
        glBindFramebuffer(GL_FRAMEBUFFER, sceneFbo);
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT1, GL_TEXTURE_2D, sceneMaterial, 0);
        glDrawBuffers(ATT0);
        int lw = w, lh = h;
        for (int i = 0; i < BLOOM_LEVELS; i++) {
            lw = Math.max(1, lw / 2); lh = Math.max(1, lh / 2);
            bw[i] = lw; bh[i] = lh;
            bloomTex[i] = colorTex(lw, lh, GL_R11F_G11F_B10F);
            bloomFbo[i] = fbo(bloomTex[i], 0);
        }
        histTex = colorTex(HIST_W, HIST_H, GL_RGBA8);
        histFbo = fbo(histTex, 0);
        for (int i = 0; i < 2; i++) { histTexs[i] = colorTex(w, h, GL_RGBA16F); histFbos[i] = fbo(histTexs[i], 0); }
        historyValid = false;
        lensTmp = colorTex(w, h, GL_RGBA16F); lensFbo = fbo(lensTmp, 0);
        hw = Math.max(1, w / 2); hh = Math.max(1, h / 2);
        dofA = colorTex(hw, hh, GL_RGBA16F); dofFboA = fbo(dofA, 0);
        dofB = colorTex(hw, hh, GL_RGBA16F); dofFboB = fbo(dofB, 0);
        flareA = colorTex(bw[1], bh[1], GL_R11F_G11F_B10F); flareFboA = fbo(flareA, 0);
        flareB = colorTex(bw[1], bh[1], GL_R11F_G11F_B10F); flareFboB = fbo(flareB, 0);
        lensPrevProjView = null;
        glBindFramebuffer(GL_FRAMEBUFFER, 0);
    }

    private void free() {
        if (sceneFbo == 0) return;
        glDeleteFramebuffers(sceneFbo);
        glDeleteTextures(sceneColor);
        glDeleteTextures(sceneDepth);
        glDeleteTextures(sceneMaterial);
        for (int i = 0; i < BLOOM_LEVELS; i++) { glDeleteFramebuffers(bloomFbo[i]); glDeleteTextures(bloomTex[i]); }
        glDeleteFramebuffers(histFbo);
        glDeleteTextures(histTex);
        for (int i = 0; i < 2; i++) { glDeleteFramebuffers(histFbos[i]); glDeleteTextures(histTexs[i]); }
        for (int f : new int[]{lensFbo, dofFboA, dofFboB, flareFboA, flareFboB}) glDeleteFramebuffers(f);
        for (int t : new int[]{lensTmp, dofA, dofB, flareA, flareB}) glDeleteTextures(t);
        sceneFbo = 0;
    }

    /** Binds the HDR scene target; render the world (and the hand) after this. */
    public void beginScene(int w, int h) { beginScene(w, h, false); }

    /**
     * Binds the HDR scene target. 'material': the terrain writes its material for the reflections this frame
     * (the material attachment is cleared; WorldRenderer enables it while it draws, FrameParams.materialOut).
     */
    public void beginScene(int w, int h, boolean material) {
        frameCounter++;
        resize(w, h);
        glBindFramebuffer(GL_FRAMEBUFFER, sceneFbo);
        glViewport(0, 0, w, h);
        if (material) {
            glDrawBuffers(ATT01);
            glClearBufferfv(GL_COLOR, 1, ZERO4);
            glDrawBuffers(ATT0);
        }
    }

    /**
     * Temporal accumulation of the scene (call after the world, before the hand). projView is the camera-relative
     * projection*view of this frame, (x,y,z) the camera position. 'blend' = weight of the new frame.
     */
    public void temporal(derpcraft.util.Mat4 projView, double x, double y, double z, float blend) {
        boolean reset = !historyValid || prevProjView == null || frameCounter - lastTemporalFrame > 1
                || Math.abs(x - prevX) + Math.abs(y - prevY) + Math.abs(z - prevZ) > 8;
        lastTemporalFrame = frameCounter;
        glDisable(GL_DEPTH_TEST);
        glDisable(GL_BLEND);
        glDisable(GL_CULL_FACE);
        glBindVertexArray(vao);
        int cur = histIndex ^ 1;
        glBindFramebuffer(GL_FRAMEBUFFER, histFbos[cur]);
        glViewport(0, 0, width, height);
        taa.use();
        taa.set("uCurrent", 0);
        taa.set("uHistory", 1);
        taa.set("uDepth", 2);
        glActiveTexture(GL_TEXTURE0); glBindTexture(GL_TEXTURE_2D, sceneColor);
        glActiveTexture(GL_TEXTURE1); glBindTexture(GL_TEXTURE_2D, histTexs[histIndex]);
        glActiveTexture(GL_TEXTURE2); glBindTexture(GL_TEXTURE_2D, sceneDepth);
        glActiveTexture(GL_TEXTURE0);
        taa.set("uInvProjView", projView.inverted());
        taa.set("uPrevProjView", prevProjView == null ? projView : prevProjView);
        taa.set("uCamDelta", (float) (x - prevX), (float) (y - prevY), (float) (z - prevZ));
        taa.set("uTexel", 1f / width, 1f / height);
        taa.set("uBlend", blend);
        taa.set("uReset", reset ? 1 : 0);
        glDrawArrays(GL_TRIANGLES, 0, 3);
        // write the result back into the scene buffer
        glBindFramebuffer(GL_FRAMEBUFFER, sceneFbo);
        copy.use();
        copy.set("uSrc", 0);
        glBindTexture(GL_TEXTURE_2D, histTexs[cur]);
        glDrawArrays(GL_TRIANGLES, 0, 3);
        glBindVertexArray(0);
        histIndex = cur;
        historyValid = true;
        prevProjView = new derpcraft.util.Mat4().set(projView);
        prevX = x; prevY = y; prevZ = z;
        glEnable(GL_DEPTH_TEST);
    }

    /**
     * Ambient occlusion for the whole picture (blocks, items, models, players, NPCs, vehicles): computed from the depth
     * buffer at half resolution, then blurred, upsampled and multiplied into the scene. Call after the world and its
     * entities, before {@link #screenEffects} and the hand. 'strength' 1 = default, 'radius' in blocks.
     */
    public void ambientOcclusion(derpcraft.util.Mat4 projView, float strength, float radius) {
        ambientOcclusion(projView, strength, radius, lens.near, lens.far, false);
    }

    /**
     * The same with its own projection: 'hand' = the first person pass after the hand / gun / fists are drawn (their
     * own projection, the depth buffer then holds only them), so they get contact shadows of their own too.
     */
    public void ambientOcclusion(derpcraft.util.Mat4 projView, float strength, float radius, float near, float far, boolean hand) {
        if (strength <= 0) return;
        if (ssao == null) { ssao = new Shader("post", "post_ssao", ""); ssaoApply = new Shader("post", "post_ssao_apply", ""); }
        glDisable(GL_DEPTH_TEST);
        glDisable(GL_BLEND);
        glDisable(GL_CULL_FACE);
        glDepthMask(false);
        glBindVertexArray(vao);
        glBindFramebuffer(GL_FRAMEBUFFER, dofFboA);
        glViewport(0, 0, hw, hh);
        ssao.use();
        ssao.set("uDepth", 1);
        glActiveTexture(GL_TEXTURE1); glBindTexture(GL_TEXTURE_2D, sceneDepth);
        ssao.set("uProjView", projView);
        ssao.set("uInvProjView", projView.inverted());
        ssao.set("uNear", near);
        ssao.set("uFar", far);
        ssao.set("uTexel", 1f / width, 1f / height);
        ssao.set("uRadius", radius);
        ssao.set("uHand", hand ? 1f : 0f);
        glDrawArrays(GL_TRIANGLES, 0, 3);
        glBindFramebuffer(GL_FRAMEBUFFER, sceneFbo);
        // the apply pass reads the scene depth: detached meanwhile (sampling an attached texture is a feedback loop)
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_DEPTH_ATTACHMENT, GL_TEXTURE_2D, 0, 0);
        glViewport(0, 0, width, height);
        glEnable(GL_BLEND);
        glBlendFuncSeparate(GL_ZERO, GL_SRC_COLOR, GL_ZERO, GL_ONE);
        ssaoApply.use();
        ssaoApply.set("uAO", 0);
        ssaoApply.set("uDepth", 1);
        ssaoApply.set("uNear", near);
        ssaoApply.set("uFar", far);
        ssaoApply.set("uStrength", strength);
        glActiveTexture(GL_TEXTURE0); glBindTexture(GL_TEXTURE_2D, dofA);
        glDrawArrays(GL_TRIANGLES, 0, 3);
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_DEPTH_ATTACHMENT, GL_TEXTURE_2D, sceneDepth, 0);
        glDisable(GL_BLEND);
        glBlendFuncSeparate(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA, GL_ONE, GL_ONE_MINUS_SRC_ALPHA);
        glBindVertexArray(0);
        glDepthMask(true);
        glEnable(GL_DEPTH_TEST);
    }

    /**
     * Screen space effects on the world image (call after {@link #temporal}, before {@link #lensPass} and the hand):
     * reflections on water and glossy blocks ('reflections', block strength 'blocks') and god rays ('rays' = strength,
     * 0 = off). projView is camera-relative; 'fp' carries the sun, sky and shadow maps of this frame.
     */
    public void screenEffects(derpcraft.render.WorldRenderer.FrameParams fp, derpcraft.util.Mat4 projView,
                              boolean reflections, float blocks, float rays) {
        if (!reflections && rays <= 0) return;
        if (ssr == null) {
            ssr = new Shader("post", "post_ssr", "");
            godRays = new Shader("post", "post_godrays", "");
            blur = new Shader("post", "post_blur", "");
        }
        glDisable(GL_DEPTH_TEST);
        glDisable(GL_BLEND);
        glDisable(GL_CULL_FACE);
        glDepthMask(false);
        glBindVertexArray(vao);
        derpcraft.util.Mat4 inv = projView.inverted();
        if (reflections) {
            glBindFramebuffer(GL_FRAMEBUFFER, lensFbo);
            glViewport(0, 0, width, height);
            ssr.use();
            ssr.set("uScene", 0);
            ssr.set("uDepth", 1);
            ssr.set("uMaterial", 2);
            glActiveTexture(GL_TEXTURE2); glBindTexture(GL_TEXTURE_2D, sceneMaterial);
            glActiveTexture(GL_TEXTURE1); glBindTexture(GL_TEXTURE_2D, sceneDepth);
            glActiveTexture(GL_TEXTURE0); glBindTexture(GL_TEXTURE_2D, sceneColor);
            ssr.set("uProjView", projView);
            ssr.set("uInvProjView", inv);
            ssr.set("uNear", lens.near);
            ssr.set("uFar", lens.far);
            ssr.set("uSkyColor", fp.skyR, fp.skyG, fp.skyB);
            ssr.set("uFogColor", fp.fogR, fp.fogG, fp.fogB);
            ssr.set("uSunColor", fp.sunR, fp.sunG, fp.sunB);
            ssr.set("uLightDir", fp.lightX, fp.lightY, fp.lightZ);
            ssr.set("uLightStrength", fp.lightStrength);
            ssr.set("uBlocks", blocks);
            ssr.set("uWater", fp.ssrWater);
            ssr.set("uFog", fp.fogStart, fp.fogEnd);
            glDrawArrays(GL_TRIANGLES, 0, 3);
            // over the scene, weighted by the Fresnel term in alpha (the scene's own alpha is kept)
            glBindFramebuffer(GL_FRAMEBUFFER, sceneFbo);
            glEnable(GL_BLEND);
            glBlendFuncSeparate(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA, GL_ZERO, GL_ONE);
            copy.use();
            copy.set("uSrc", 0);
            glBindTexture(GL_TEXTURE_2D, lensTmp);
            glDrawArrays(GL_TRIANGLES, 0, 3);
            glDisable(GL_BLEND);
        }
        if (rays > 0 && fp.lightStrength > 0.001f) {
            // light direction as the sky draws the sun / moon (the shadow light has a small tilt)
            float lx = fp.lightX, ly = fp.lightY, ll = (float) Math.sqrt(lx * lx + ly * ly);
            float[] sun = new float[4];
            projView.transform(lx / ll * 1000, ly / ll * 1000, 0, sun);
            float fade = 0;
            float su = 0.5f, sv = 0.5f;
            if (sun[3] > 0) {
                su = sun[0] / sun[3] * 0.5f + 0.5f; sv = sun[1] / sun[3] * 0.5f + 0.5f;
                float out = Math.max(Math.max(-su, su - 1), Math.max(-sv, sv - 1));
                fade = 1 - Math.max(0, Math.min(1, out / 0.35f));
            }
            boolean volumetric = fp.cascades > 0;
            glBindFramebuffer(GL_FRAMEBUFFER, dofFboA);
            glViewport(0, 0, hw, hh);
            godRays.use();
            godRays.set("uDepth", 1);
            glActiveTexture(GL_TEXTURE1); glBindTexture(GL_TEXTURE_2D, sceneDepth);
            derpcraft.render.LightingState.bindTextures(fp);
            derpcraft.render.LightingState.setUniforms(godRays, fp);
            godRays.set("uInvProjView", inv);
            godRays.set("uMode", volumetric ? 1 : 0);
            godRays.set("uMaxDist", Math.min(lens.far, 192f));
            godRays.set("uSunUV", su, sv);
            godRays.set("uSunFade", fade);
            godRays.set("uAspect", width / (float) Math.max(1, height));
            if (volumetric || fade > 0) {
                glDrawArrays(GL_TRIANGLES, 0, 3);
                blur.use();
                blur.set("uSrc", 0);
                glActiveTexture(GL_TEXTURE0);
                glBindFramebuffer(GL_FRAMEBUFFER, dofFboB);
                glBindTexture(GL_TEXTURE_2D, dofA);
                blur.set("uStep", 1.5f / hw, 0f);
                blur.set("uGain", 1f);
                glDrawArrays(GL_TRIANGLES, 0, 3);
                // second blur direction straight into the scene, added
                glBindFramebuffer(GL_FRAMEBUFFER, sceneFbo);
                glViewport(0, 0, width, height);
                glEnable(GL_BLEND);
                glBlendFuncSeparate(GL_ONE, GL_ONE, GL_ZERO, GL_ONE);
                glBindTexture(GL_TEXTURE_2D, dofB);
                blur.set("uStep", 0f, 1.5f / hh);
                blur.set("uGain", rays);
                glDrawArrays(GL_TRIANGLES, 0, 3);
                glDisable(GL_BLEND);
            }
        }
        glActiveTexture(GL_TEXTURE0);
        glBlendFuncSeparate(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA, GL_ONE, GL_ONE_MINUS_SRC_ALPHA);
        glBindFramebuffer(GL_FRAMEBUFFER, sceneFbo);
        glViewport(0, 0, width, height);
        glBindVertexArray(0);
        glDepthMask(true);
        glEnable(GL_DEPTH_TEST);
    }

    private void ensureLensShaders() {
        if (coc != null) return;
        coc = new Shader("post", "post_coc", "");
        dof = new Shader("post", "post_dof", "");
        dofMix = new Shader("post", "post_dofmix", "");
        mblur = new Shader("post", "post_mblur", "");
        streak = new Shader("post", "post_streak", "");
        ghosts = new Shader("post", "post_ghosts", "");
    }

    /**
     * Camera lens effects on the world image (call after {@link #temporal}, before the hand): motion blur and depth
     * of field. projView is camera-relative, (x,y,z) the camera position.
     */
    public void lensPass(derpcraft.util.Mat4 projView, double x, double y, double z) {
        Lens L = lens;
        if (!L.dof && L.motionBlur <= 0) { lensPrevProjView = null; return; }
        ensureLensShaders();
        glDisable(GL_DEPTH_TEST);
        glDisable(GL_BLEND);
        glDisable(GL_CULL_FACE);
        glDepthMask(false);
        glBindVertexArray(vao);
        boolean reset = lensPrevProjView == null || frameCounter - lastLensFrame > 1
                || Math.abs(x - lensPrevX) + Math.abs(y - lensPrevY) + Math.abs(z - lensPrevZ) > 16;
        lastLensFrame = frameCounter;
        if (L.motionBlur > 0 && !reset) {
            glBindFramebuffer(GL_FRAMEBUFFER, lensFbo);
            glViewport(0, 0, width, height);
            mblur.use();
            mblur.set("uScene", 0);
            mblur.set("uDepth", 2);
            glActiveTexture(GL_TEXTURE2); glBindTexture(GL_TEXTURE_2D, sceneDepth);
            glActiveTexture(GL_TEXTURE0); glBindTexture(GL_TEXTURE_2D, sceneColor);
            mblur.set("uInvProjView", projView.inverted());
            mblur.set("uPrevProjView", lensPrevProjView);
            mblur.set("uCamDelta", (float) (x - lensPrevX), (float) (y - lensPrevY), (float) (z - lensPrevZ));
            mblur.set("uShutter", L.motionBlur);
            mblur.set("uMaxLen", 0.08f);
            glDrawArrays(GL_TRIANGLES, 0, 3);
            glBindFramebuffer(GL_FRAMEBUFFER, sceneFbo);
            copy.use();
            copy.set("uSrc", 0);
            glBindTexture(GL_TEXTURE_2D, lensTmp);
            glDrawArrays(GL_TRIANGLES, 0, 3);
        }
        lensPrevProjView = new derpcraft.util.Mat4().set(projView);
        lensPrevX = x; lensPrevY = y; lensPrevZ = z;
        if (L.dof) {
            float maxFull = Math.max(4, height * 0.03f * Math.max(1, L.bokehScale * 0.75f));
            float maxHalf = Math.min(48, maxFull * 0.5f);
            glBindFramebuffer(GL_FRAMEBUFFER, dofFboA);
            glViewport(0, 0, hw, hh);
            coc.use();
            coc.set("uScene", 0);
            coc.set("uDepth", 2);
            glActiveTexture(GL_TEXTURE2); glBindTexture(GL_TEXTURE_2D, sceneDepth);
            glActiveTexture(GL_TEXTURE0); glBindTexture(GL_TEXTURE_2D, sceneColor);
            coc.set("uTexel", 1f / width, 1f / height);
            coc.set("uNear", L.near);
            coc.set("uFar", L.far);
            coc.set("uFocal", L.focal);
            coc.set("uAperture", Math.max(0.7f, L.aperture));
            coc.set("uFocus", Math.max(0.1f, L.focus));
            coc.set("uPxPerMm", height / 24f * L.bokehScale);
            coc.set("uMaxCoc", maxHalf);
            glDrawArrays(GL_TRIANGLES, 0, 3);
            glBindFramebuffer(GL_FRAMEBUFFER, dofFboB);
            dof.use();
            dof.set("uSrc", 0);
            glBindTexture(GL_TEXTURE_2D, dofA);
            dof.set("uTexel", 1f / hw, 1f / hh);
            dof.set("uMaxCoc", maxHalf);
            dof.set("uSqueeze", Math.max(1, L.squeeze));
            // about 100 taps whatever the blur size
            dof.set("uRadScale", Math.max(0.75f, maxHalf * maxHalf / 200f));
            glDrawArrays(GL_TRIANGLES, 0, 3);
            glBindFramebuffer(GL_FRAMEBUFFER, sceneFbo);
            glViewport(0, 0, width, height);
            glEnable(GL_BLEND);
            glBlendFuncSeparate(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA, GL_ZERO, GL_ONE);
            dofMix.use();
            dofMix.set("uSrc", 0);
            glBindTexture(GL_TEXTURE_2D, dofB);
            glDrawArrays(GL_TRIANGLES, 0, 3);
            glDisable(GL_BLEND);
            glBlendFuncSeparate(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA, GL_ONE, GL_ONE_MINUS_SRC_ALPHA);
        }
        glBindFramebuffer(GL_FRAMEBUFFER, sceneFbo);
        glViewport(0, 0, width, height);
        glBindVertexArray(0);
        glDepthMask(true);
        glEnable(GL_DEPTH_TEST);
    }

    /** Anamorphic streak and ghosts from the thresholded bright image (bloom levels 1 and 3). */
    private void flares() {
        ensureLensShaders();
        Lens L = lens;
        glViewport(0, 0, bw[1], bh[1]);
        streak.use();
        streak.set("uSrc", 0);
        streak.set("uTexel", 1f / bw[1], 1f / bh[1]);
        streak.set("uAtten", 0.95f + 0.045f * Math.max(0, Math.min(1, L.flareLength)));
        int src = bloomTex[1];
        int[] fbos = {flareFboA, flareFboB}, texs = {flareA, flareB};
        float s = Math.max(1, L.squeeze);
        for (int i = 0; i < 4; i++) {
            glBindFramebuffer(GL_FRAMEBUFFER, fbos[i & 1]);
            glActiveTexture(GL_TEXTURE0);
            glBindTexture(GL_TEXTURE_2D, src);
            streak.set("uStep", (float) Math.pow(4, i));
            // a wider squeeze gives a stronger, longer streak
            float k = i == 3 ? L.flare * 0.5f * (0.6f + 0.4f * s) : 1f;
            streak.set("uThreshold", i == 0 ? 0.25f : 0f);
            streak.set("uGain", i == 3 ? 24f : 0f);
            if (i == 3) streak.set("uTint", L.flareTint[0] * k, L.flareTint[1] * k, L.flareTint[2] * k);
            else streak.set("uTint", 1f, 1f, 1f);
            glDrawArrays(GL_TRIANGLES, 0, 3);
            src = texs[i & 1];
        }
        // result is in flareB; add the ghosts on top
        if (L.ghosts > 0) {
            glEnable(GL_BLEND);
            glBlendFunc(GL_ONE, GL_ONE);
            ghosts.use();
            ghosts.set("uSrc", 0);
            glBindTexture(GL_TEXTURE_2D, bloomTex[3]);
            ghosts.set("uAspect", width / (float) Math.max(1, height));
            ghosts.set("uSqueeze", s);
            ghosts.set("uStrength", L.ghosts * 0.5f);
            ghosts.set("uThreshold", 0.35f);
            glDrawArrays(GL_TRIANGLES, 0, 3);
            glDisable(GL_BLEND);
        }
    }

    /** Runs bloom and the grade, writing to the default framebuffer. */
    public void endScene(Grading g, float timeSeconds, Path lutDir) {
        glDisable(GL_DEPTH_TEST);
        glDisable(GL_CULL_FACE);
        glDisable(GL_BLEND);
        glBindVertexArray(vao);
        float exposure = (float) Math.pow(2, g.exposure);
        boolean bloom = g.bloom > 0.001f;
        boolean flare = lens.flare > 0 || lens.ghosts > 0;
        if (bloom || flare) bloomDown(g, exposure);
        if (flare) flares();
        if (bloom) bloomUp();
        updateCurves(g);
        boolean useLut = updateLut(g.lut, lutDir);

        fin.use();
        fin.set("uScene", 0);
        fin.set("uBloom", 1);
        fin.set("uCurves", 2);
        fin.set("uLut", 3);
        fin.set("uFlare", 4);
        glActiveTexture(GL_TEXTURE4); glBindTexture(GL_TEXTURE_2D, flare ? flareB : blackTex);
        glActiveTexture(GL_TEXTURE0); glBindTexture(GL_TEXTURE_2D, sceneColor);
        glActiveTexture(GL_TEXTURE1); glBindTexture(GL_TEXTURE_2D, bloom ? bloomTex[0] : blackTex);
        glActiveTexture(GL_TEXTURE2); glBindTexture(GL_TEXTURE_2D, curveTex);
        glActiveTexture(GL_TEXTURE3); glBindTexture(GL_TEXTURE_3D, lutTex);
        glActiveTexture(GL_TEXTURE0);
        fin.set("uExposure", exposure);
        fin.set("uBloomStrength", bloom ? g.bloom / BLOOM_LEVELS : 0f);
        float[] wb = g.whiteBalance();
        fin.set("uWB", wb[0], wb[1], wb[2]);
        fin.set("uTonemap", g.tonemap);
        fin.set("uTonemap2", g.tonemap2);
        fin.set("uTonemapMix", g.tonemapMix);
        fin.set("uContrast", g.contrast);
        fin.set("uSaturation", g.saturation);
        fin.set("uVibrance", g.vibrance);
        fin.set("uLift", g.lift);
        fin.set("uGamma", Math.max(0.05f, g.gamma));
        fin.set("uGain", g.gain);
        float[] st = toneTint(g.shadowHue, g.shadowAmount), ht = toneTint(g.highlightHue, g.highlightAmount);
        fin.set("uShadowTint", st[0], st[1], st[2]);
        fin.set("uHighlightTint", ht[0], ht[1], ht[2]);
        fin.set("uUseCurves", g.curvesIdentity() ? 0 : 1);
        fin.set("uUseLut", useLut ? 1 : 0);
        fin.set("uLutSize", lutSize);
        fin.set("uLutStrength", g.lutStrength);
        fin.set("uVignette", g.vignette);
        fin.set("uGrain", g.grain);
        fin.set("uTime", timeSeconds);
        fin.set("uRes", (float) width, (float) height);
        fin.set("uFlareOn", flare ? 1f : 0f);
        fin.set("uDistortion", lens.distortion);
        fin.set("uAberration", lens.aberration);
        fin.set("uLetterbox", lens.letterbox);

        if (wantHistogram && ++histFrame % 6 == 0) {
            glBindFramebuffer(GL_FRAMEBUFFER, histFbo);
            glViewport(0, 0, HIST_W, HIST_H);
            glDrawArrays(GL_TRIANGLES, 0, 3);
            readHistogram();
        }
        glBindFramebuffer(GL_FRAMEBUFFER, 0);
        glViewport(0, 0, width, height);
        glDrawArrays(GL_TRIANGLES, 0, 3);
        glBindVertexArray(0);
    }

    private static float[] toneTint(float hue, float amount) {
        java.awt.Color c = java.awt.Color.getHSBColor(hue / 360f, 1, 1);
        float r = c.getRed() / 255f, g = c.getGreen() / 255f, b = c.getBlue() / 255f;
        float l = r * 0.2126f + g * 0.7152f + b * 0.0722f;
        float k = amount * 0.35f;
        return new float[]{(r - l) * k, (g - l) * k, (b - l) * k};
    }

    private void bloomDown(Grading g, float exposure) {
        down.use();
        down.set("uSrc", 0);
        down.set("uThreshold", g.bloomThreshold);
        down.set("uExposure", exposure);
        int src = sceneColor, sw = width, sh = height;
        for (int i = 0; i < BLOOM_LEVELS; i++) {
            glBindFramebuffer(GL_FRAMEBUFFER, bloomFbo[i]);
            glViewport(0, 0, bw[i], bh[i]);
            glActiveTexture(GL_TEXTURE0);
            glBindTexture(GL_TEXTURE_2D, src);
            down.set("uPrefilter", i == 0 ? 1 : 0);
            down.set("uTexel", 1f / sw, 1f / sh);
            glDrawArrays(GL_TRIANGLES, 0, 3);
            src = bloomTex[i]; sw = bw[i]; sh = bh[i];
        }
    }

    private void bloomUp() {
        up.use();
        up.set("uSrc", 0);
        glEnable(GL_BLEND);
        glBlendFunc(GL_ONE, GL_ONE);
        for (int i = BLOOM_LEVELS - 2; i >= 0; i--) {
            glBindFramebuffer(GL_FRAMEBUFFER, bloomFbo[i]);
            glViewport(0, 0, bw[i], bh[i]);
            glBindTexture(GL_TEXTURE_2D, bloomTex[i + 1]);
            up.set("uTexel", 1f / bw[i + 1], 1f / bh[i + 1]);
            glDrawArrays(GL_TRIANGLES, 0, 3);
        }
        glDisable(GL_BLEND);
        glBlendFuncSeparate(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA, GL_ONE, GL_ONE_MINUS_SRC_ALPHA);
    }

    private void updateCurves(Grading g) {
        // compared as raw numbers every frame (building a text key allocated strings for every point each frame)
        int n = 0;
        for (List<float[]> l : g.curves) n += 1 + l.size() * 2;
        boolean same = curveKeyValid && curveKey.length == n;
        if (curveKey.length != n) curveKey = new float[n];
        int k = 0;
        for (List<float[]> l : g.curves) {
            float c = l.size();
            if (curveKey[k] != c) { same = false; curveKey[k] = c; }
            k++;
            for (float[] p : l) {
                if (curveKey[k] != p[0] || curveKey[k + 1] != p[1]) { same = false; curveKey[k] = p[0]; curveKey[k + 1] = p[1]; }
                k += 2;
            }
        }
        if (same) return;
        curveKeyValid = true;
        float[][] baked = new float[4][];
        for (int c = 0; c < 4; c++) baked[c] = Grading.bake(g.curves[c], 256);
        ByteBuffer b = ByteBuffer.allocateDirect(256 * 4).order(ByteOrder.nativeOrder());
        for (int i = 0; i < 256; i++) {
            b.put((byte) Math.round(baked[1][i] * 255)).put((byte) Math.round(baked[2][i] * 255))
                    .put((byte) Math.round(baked[3][i] * 255)).put((byte) Math.round(baked[0][i] * 255));
        }
        b.flip();
        glBindTexture(GL_TEXTURE_2D, curveTex);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, 256, 1, 0, GL_RGBA, GL_UNSIGNED_BYTE, b);
    }

    /** Loads a .cube LUT from the luts folder when the name changes. Returns true when one is active. */
    private boolean updateLut(String name, Path dir) {
        if (name == null || name.isEmpty()) { lutName = ""; return false; }
        if (name.equals(lutName)) return lutTex != 0 && lutSize > 0;
        lutName = name;
        lutSize = 0;
        try {
            CubeLut lut = CubeLut.load(dir.resolve(name));
            if (lutTex == 0) lutTex = glGenTextures();
            glBindTexture(GL_TEXTURE_3D, lutTex);
            glTexParameteri(GL_TEXTURE_3D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
            glTexParameteri(GL_TEXTURE_3D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
            glTexParameteri(GL_TEXTURE_3D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
            glTexParameteri(GL_TEXTURE_3D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
            glTexParameteri(GL_TEXTURE_3D, GL_TEXTURE_WRAP_R, GL_CLAMP_TO_EDGE);
            ByteBuffer buf = ByteBuffer.allocateDirect(lut.data.length * 4).order(ByteOrder.nativeOrder());
            FloatBuffer fb = buf.asFloatBuffer();
            fb.put(lut.data);
            glPixelStorei(GL_UNPACK_ALIGNMENT, 4);
            glTexImage3D(GL_TEXTURE_3D, 0, GL_RGB16F, lut.size, lut.size, lut.size, 0, GL_RGB, GL_FLOAT, buf);
            lutSize = lut.size;
            return true;
        } catch (Exception e) {
            System.err.println("Cannot load LUT " + name + ": " + e.getMessage());
            return false;
        }
    }

    private void readHistogram() {
        ByteBuffer px = ByteBuffer.allocateDirect(HIST_W * HIST_H * 4).order(ByteOrder.nativeOrder());
        glPixelStorei(GL_PACK_ALIGNMENT, 1);
        glReadPixels(0, 0, HIST_W, HIST_H, GL_RGBA, GL_UNSIGNED_BYTE, px);
        float[][] h = new float[4][64];
        for (int i = 0; i < HIST_W * HIST_H; i++) {
            int r = px.get(i * 4) & 255, g = px.get(i * 4 + 1) & 255, b = px.get(i * 4 + 2) & 255;
            int l = (r * 54 + g * 183 + b * 19) >> 8;
            h[0][l >> 2]++; h[1][r >> 2]++; h[2][g >> 2]++; h[3][b >> 2]++;
        }
        for (int c = 0; c < 4; c++) {
            float max = 1;
            for (int i = 1; i < 63; i++) max = Math.max(max, h[c][i]);
            for (int i = 0; i < 64; i++) histogram[c][i] = Math.min(1, h[c][i] / max);
        }
    }

    /** All .cube files in the luts folder. */
    public static List<String> listLuts(Path dir) {
        List<String> out = new ArrayList<>();
        try (var s = Files.list(dir)) {
            s.filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".cube")).forEach(p -> out.add(p.getFileName().toString()));
        } catch (Exception ignored) {}
        Collections.sort(out);
        return out;
    }

    public void delete() { free(); }
}
