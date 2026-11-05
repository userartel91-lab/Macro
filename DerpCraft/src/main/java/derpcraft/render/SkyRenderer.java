package derpcraft.render;

import derpcraft.util.Mat4;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.util.Random;

import static org.lwjgl.opengl.GL45C.*;

/** Minecraft 1.12 style sky: colours, fog colour, sunrise glow, sun, moon, stars and fancy 3D clouds. */
public final class SkyRenderer {
    private final Shader skyShader = new Shader("sky");
    private final Shader simple;
    private final Batch stars = new Batch(), dynamic = new Batch();
    private Batch clouds;
    private int cloudsVersion = -1;
    private final int emptyVao = glGenVertexArrays();

    // per frame environment
    public float celestial, sunBrightness, starBrightness;
    public float skyR, skyG, skyB, fogR, fogG, fogB;
    public float[] sunrise;
    public float cloudR, cloudG, cloudB;

    public SkyRenderer(Shader simple) {
        this.simple = simple;
        buildStars();
    }

    public static float celestialAngle(long time, float partial) {
        int i = (int) (time % 24000L);
        float f = (i + partial) / 24000.0F - 0.25F;
        if (f < 0) ++f;
        if (f > 1) --f;
        float f1 = f;
        f = 1.0F - (float) ((Math.cos(f * Math.PI) + 1.0D) / 2.0D);
        return f1 + (f - f1) / 3.0F;
    }

    /** Computes sky/fog colours for this frame (Minecraft formulas). */
    public void update(long time, float partial, float lookX, float lookZ, int renderDistance, float temperature) {
        celestial = celestialAngle(time, partial);
        float c = (float) Math.cos(celestial * Math.PI * 2);
        float f1 = 1.0F - (c * 2.0F + 0.2F);
        f1 = Math.max(0, Math.min(1, f1));
        sunBrightness = (1.0F - f1) * 0.8F + 0.2F;

        float sb = Math.max(0, Math.min(1, c * 2.0F + 0.5F));
        float tf = Math.max(-1, Math.min(1, temperature / 3.0F));
        int rgb = Color.HSBtoRGB(0.62222224F - tf * 0.05F, 0.5F + tf * 0.1F, 1.0F);
        skyR = ((rgb >> 16) & 255) / 255f * sb;
        skyG = ((rgb >> 8) & 255) / 255f * sb;
        skyB = (rgb & 255) / 255f * sb;

        float fr = 0.7529412F * (sb * 0.94F + 0.06F), fg = 0.84705883F * (sb * 0.94F + 0.06F), fb = sb * 0.91F + 0.09F;
        sunrise = sunriseColors(celestial);
        if (renderDistance >= 4 && sunrise != null) {
            float side = Math.sin(celestial * Math.PI * 2) > 0 ? -1 : 1;
            float len = (float) Math.sqrt(lookX * lookX + lookZ * lookZ);
            float dot = len > 0 ? lookX / len * side : 0;
            if (dot > 0) {
                dot *= sunrise[3];
                fr = fr * (1 - dot) + sunrise[0] * dot;
                fg = fg * (1 - dot) + sunrise[1] * dot;
                fb = fb * (1 - dot) + sunrise[2] * dot;
            }
        }
        float f10 = 1.0F - (float) Math.pow(0.25 + 0.75 * renderDistance / 32.0, 0.25);
        fogR = fr + (skyR - fr) * f10;
        fogG = fg + (skyG - fg) * f10;
        fogB = fb + (skyB - fb) * f10;

        float s = 1.0F - (c * 2.0F + 0.25F);
        s = Math.max(0, Math.min(1, s));
        starBrightness = s * s * 0.5F;

        float cl = Math.max(0, Math.min(1, c * 2.0F + 0.5F));
        cloudR = cl * 0.9F + 0.1F; cloudG = cloudR; cloudB = cl * 0.85F + 0.15F;
    }

    public static float[] sunriseColors(float celestial) {
        float f1 = (float) Math.cos(celestial * Math.PI * 2);
        if (f1 >= -0.4F && f1 <= 0.4F) {
            float f3 = f1 / 0.4F * 0.5F + 0.5F;
            float f4 = 1.0F - (1.0F - (float) Math.sin(f3 * Math.PI)) * 0.99F;
            f4 *= f4;
            return new float[]{f3 * 0.3F + 0.7F, f3 * f3 * 0.7F + 0.2F, 0.2F, f4};
        }
        return null;
    }

    private void buildStars() {
        Random random = new Random(10842L);
        stars.begin();
        for (int i = 0; i < 1500; ++i) {
            double d0 = random.nextFloat() * 2.0F - 1.0F, d1 = random.nextFloat() * 2.0F - 1.0F, d2 = random.nextFloat() * 2.0F - 1.0F;
            double d3 = 0.15F + random.nextFloat() * 0.1F;
            double d4 = d0 * d0 + d1 * d1 + d2 * d2;
            if (d4 < 1.0D && d4 > 0.01D) {
                d4 = 1.0D / Math.sqrt(d4);
                d0 *= d4; d1 *= d4; d2 *= d4;
                double d5 = d0 * 100.0D, d6 = d1 * 100.0D, d7 = d2 * 100.0D;
                double d8 = Math.atan2(d0, d2), d9 = Math.sin(d8), d10 = Math.cos(d8);
                double d11 = Math.atan2(Math.sqrt(d0 * d0 + d2 * d2), d1), d12 = Math.sin(d11), d13 = Math.cos(d11);
                double d14 = random.nextDouble() * Math.PI * 2.0D, d15 = Math.sin(d14), d16 = Math.cos(d14);
                float[] q = new float[12];
                for (int j = 0; j < 4; ++j) {
                    double d18 = ((j & 2) - 1) * d3, d19 = ((j + 1 & 2) - 1) * d3;
                    double d21 = d18 * d16 - d19 * d15, d22 = d19 * d16 + d18 * d15;
                    double d23 = d21 * d12, d24 = -d21 * d13;
                    double d25 = d24 * d9 - d22 * d10, d26 = d22 * d9 + d24 * d10;
                    q[j * 3] = (float) (d5 + d25); q[j * 3 + 1] = (float) (d6 + d23); q[j * 3 + 2] = (float) (d7 + d26);
                }
                for (int k : new int[]{0, 1, 2, 2, 3, 0}) stars.v(q[k * 3], q[k * 3 + 1], q[k * 3 + 2], 0, 0, 1, 1, 1, 1);
            }
        }
        stars.bake();
    }

    /** HDR multipliers for the sun and moon discs (above 1 only with the HDR pipeline, e.g. for lens flares). */
    public float sunIntensity = 1, moonIntensity = 1;
    /** Fog from the Shaders screen: the distance the sky planes fade into the fog colour (-1 = the render distance's) and how much of the sun, moon and stars shows through (0..1). */
    public float fogFar = -1, celestialFade = 1;

    /** Sky dome, sunrise, sun/moon, stars. Called first in the frame with the rotation-only matrix. */
    public void draw(Mat4 projView, Assets assets, long time, float partial, int renderDistance, boolean underwater) {
        glDisable(GL_DEPTH_TEST);
        glDepthMask(false);
        glDisable(GL_CULL_FACE);
        Mat4 inv = invert(projView);
        skyShader.use();
        skyShader.set("uInvProjView", inv);
        skyShader.set("uSkyColor", skyR, skyG, skyB);
        skyShader.set("uFogColor", fogR, fogG, fogB);
        skyShader.set("uVoidColor", skyR * 0.2F + 0.04F, skyG * 0.2F + 0.04F, skyB * 0.6F + 0.1F);
        float[] sr = underwater ? null : sunrise;
        skyShader.set("uSunrise", sr == null ? 0 : sr[0], sr == null ? 0 : sr[1], sr == null ? 0 : sr[2], sr == null ? 0 : sr[3] * celestialFade);
        skyShader.set("uSunSide", Math.sin(celestial * Math.PI * 2) < 0 ? 1f : -1f);
        skyShader.set("uFar", fogFar > 0 ? fogFar : Math.max(32f, renderDistance * 16f * 0.8f));
        glBindVertexArray(emptyVao);
        glDrawArrays(GL_TRIANGLES, 0, 3);
        glBindVertexArray(0);
        if (underwater || celestialFade <= 0) { glDepthMask(true); glEnable(GL_DEPTH_TEST); return; }

        // celestial bodies: additive
        glEnable(GL_BLEND);
        glBlendFunc(GL_SRC_ALPHA, GL_ONE);
        simple.use();
        simple.set("uFog", 0f, 0f);
        Mat4 model = new Mat4().rotate((float) Math.toRadians(-90), 0, 1, 0).rotate(celestial * (float) Math.PI * 2, 1, 0, 0);
        simple.set("uMVP", projView);
        simple.set("uModel", model);
        simple.set("uColor", 1, 1, 1, 1);
        simple.set("uUseTex", 1);
        simple.set("uTex", 0);
        float s = 30;
        float cf = celestialFade, si = sunIntensity * cf, mi = moonIntensity * cf;
        simple.set("uColor", si, si, si, 1);
        assets.sun.bind(0);
        dynamic.begin().quad(new float[]{-s, 100, -s, 0, 0, s, 100, -s, 1, 0, s, 100, s, 1, 1, -s, 100, s, 0, 1}, 1, 1, 1, 1).draw(GL_TRIANGLES);
        int phase = (int) (time / 24000L % 8L + 8) % 8;
        float u0 = (phase % 4) / 4f, v0 = (phase / 4) / 2f, u1 = u0 + 0.25f, v1 = v0 + 0.5f;
        s = 20;
        simple.set("uColor", mi, mi, mi, 1);
        assets.moon.bind(0);
        dynamic.begin().quad(new float[]{-s, -100, s, u1, v1, s, -100, s, u0, v1, s, -100, -s, u0, v0, -s, -100, -s, u1, v0}, 1, 1, 1, 1).draw(GL_TRIANGLES);
        if (starBrightness * cf > 0) {
            simple.set("uUseTex", 0);
            float sb = starBrightness * cf;
            simple.set("uColor", sb, sb, sb, sb);
            stars.draw(GL_TRIANGLES);
        }
        simple.set("uModel", new Mat4());
        simple.set("uColor", 1, 1, 1, 1);
        glDisable(GL_BLEND);
        glDepthMask(true);
        glEnable(GL_DEPTH_TEST);
    }

    // ------------------------------------------------------------------------------------------ clouds
    private void buildClouds(BufferedImage img) {
        if (clouds != null) clouds.delete();
        int w = img.getWidth(), h = img.getHeight();
        boolean[] on = new boolean[w * h];
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) on[y * w + x] = (img.getRGB(x, y) >>> 24) > 127;
        Batch b = new Batch().begin();
        float S = 12, H = 4;
        for (int z = 0; z < h; z++)
            for (int x = 0; x < w; x++) {
                if (!on[z * w + x]) continue;
                float x0 = x * S, x1 = x0 + S, z0 = z * S, z1 = z0 + S;
                b.quad(new float[]{x0, H, z0, 0, 0, x0, H, z1, 0, 0, x1, H, z1, 0, 0, x1, H, z0, 0, 0}, 1, 1, 1, 0.8f);
                b.quad(new float[]{x0, 0, z0, 0, 0, x1, 0, z0, 0, 0, x1, 0, z1, 0, 0, x0, 0, z1, 0, 0}, 0.7f, 0.7f, 0.7f, 0.8f);
                if (!on[z * w + (x + w - 1) % w]) b.quad(new float[]{x0, 0, z0, 0, 0, x0, 0, z1, 0, 0, x0, H, z1, 0, 0, x0, H, z0, 0, 0}, 0.9f, 0.9f, 0.9f, 0.8f);
                if (!on[z * w + (x + 1) % w]) b.quad(new float[]{x1, 0, z0, 0, 0, x1, H, z0, 0, 0, x1, H, z1, 0, 0, x1, 0, z1, 0, 0}, 0.9f, 0.9f, 0.9f, 0.8f);
                if (!on[((z + h - 1) % h) * w + x]) b.quad(new float[]{x0, 0, z0, 0, 0, x0, H, z0, 0, 0, x1, H, z0, 0, 0, x1, 0, z0, 0, 0}, 0.8f, 0.8f, 0.8f, 0.8f);
                if (!on[((z + 1) % h) * w + x]) b.quad(new float[]{x0, 0, z1, 0, 0, x1, 0, z1, 0, 0, x1, H, z1, 0, 0, x0, H, z1, 0, 0}, 0.8f, 0.8f, 0.8f, 0.8f);
            }
        clouds = b.bake();
        cloudSize = w * S;
    }

    private float cloudSize = 3072;

    /** Fancy clouds at y=128, drawn in two passes (depth then colour) like vanilla. */
    public void drawClouds(Mat4 projView, Assets assets, double camX, double camY, double camZ, double ticks, float fogEnd) {
        if (cloudsVersion != assets.version) { buildClouds(assets.cloudsImage); cloudsVersion = assets.version; }
        double drift = ticks * 0.03;
        double size = cloudSize;
        int tx0 = (int) Math.floor((camX - drift - size / 2) / size), tz0 = (int) Math.floor((camZ - size / 2) / size);
        simple.use();
        simple.set("uMVP", projView);
        simple.set("uUseTex", 0);
        simple.set("uColor", cloudR, cloudG, cloudB, 1);
        simple.set("uFogColor", fogR, fogG, fogB);
        simple.set("uFog", fogEnd * 0.5f, fogEnd * 1.6f);
        glEnable(GL_DEPTH_TEST);
        glEnable(GL_CULL_FACE);
        glCullFace(GL_BACK);
        for (int pass = 0; pass < 2; pass++) {
            if (pass == 0) { glColorMask(false, false, false, false); glDisable(GL_BLEND); }
            else { glColorMask(true, true, true, true); glEnable(GL_BLEND); glBlendFuncSeparate(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA, GL_ONE, GL_ONE_MINUS_SRC_ALPHA); glDepthFunc(GL_LEQUAL); }
            for (int i = 0; i < 2; i++)
                for (int j = 0; j < 2; j++) {
                    double ox = (tx0 + i) * size + drift - camX, oz = (tz0 + j) * size - camZ, oy = 128 - camY;
                    simple.set("uModel", new Mat4().translate((float) ox, (float) oy, (float) oz));
                    clouds.draw(GL_TRIANGLES);
                }
        }
        glDisable(GL_BLEND);
        simple.set("uModel", new Mat4());
        simple.set("uColor", 1, 1, 1, 1);
        simple.set("uFog", 0f, 0f);
    }

    /** General 4x4 inverse. */
    public static Mat4 invert(Mat4 src) {
        float[] m = src.m, inv = new float[16];
        inv[0] = m[5] * m[10] * m[15] - m[5] * m[11] * m[14] - m[9] * m[6] * m[15] + m[9] * m[7] * m[14] + m[13] * m[6] * m[11] - m[13] * m[7] * m[10];
        inv[4] = -m[4] * m[10] * m[15] + m[4] * m[11] * m[14] + m[8] * m[6] * m[15] - m[8] * m[7] * m[14] - m[12] * m[6] * m[11] + m[12] * m[7] * m[10];
        inv[8] = m[4] * m[9] * m[15] - m[4] * m[11] * m[13] - m[8] * m[5] * m[15] + m[8] * m[7] * m[13] + m[12] * m[5] * m[11] - m[12] * m[7] * m[9];
        inv[12] = -m[4] * m[9] * m[14] + m[4] * m[10] * m[13] + m[8] * m[5] * m[14] - m[8] * m[6] * m[13] - m[12] * m[5] * m[10] + m[12] * m[6] * m[9];
        inv[1] = -m[1] * m[10] * m[15] + m[1] * m[11] * m[14] + m[9] * m[2] * m[15] - m[9] * m[3] * m[14] - m[13] * m[2] * m[11] + m[13] * m[3] * m[10];
        inv[5] = m[0] * m[10] * m[15] - m[0] * m[11] * m[14] - m[8] * m[2] * m[15] + m[8] * m[3] * m[14] + m[12] * m[2] * m[11] - m[12] * m[3] * m[10];
        inv[9] = -m[0] * m[9] * m[15] + m[0] * m[11] * m[13] + m[8] * m[1] * m[15] - m[8] * m[3] * m[13] - m[12] * m[1] * m[11] + m[12] * m[3] * m[9];
        inv[13] = m[0] * m[9] * m[14] - m[0] * m[10] * m[13] - m[8] * m[1] * m[14] + m[8] * m[2] * m[13] + m[12] * m[1] * m[10] - m[12] * m[2] * m[9];
        inv[2] = m[1] * m[6] * m[15] - m[1] * m[7] * m[14] - m[5] * m[2] * m[15] + m[5] * m[3] * m[14] + m[13] * m[2] * m[7] - m[13] * m[3] * m[6];
        inv[6] = -m[0] * m[6] * m[15] + m[0] * m[7] * m[14] + m[4] * m[2] * m[15] - m[4] * m[3] * m[14] - m[12] * m[2] * m[7] + m[12] * m[3] * m[6];
        inv[10] = m[0] * m[5] * m[15] - m[0] * m[7] * m[13] - m[4] * m[1] * m[15] + m[4] * m[3] * m[13] + m[12] * m[1] * m[7] - m[12] * m[3] * m[5];
        inv[14] = -m[0] * m[5] * m[14] + m[0] * m[6] * m[13] + m[4] * m[1] * m[14] - m[4] * m[2] * m[13] - m[12] * m[1] * m[6] + m[12] * m[2] * m[5];
        inv[3] = -m[1] * m[6] * m[11] + m[1] * m[7] * m[10] + m[5] * m[2] * m[11] - m[5] * m[3] * m[10] - m[9] * m[2] * m[7] + m[9] * m[3] * m[6];
        inv[7] = m[0] * m[6] * m[11] - m[0] * m[7] * m[10] - m[4] * m[2] * m[11] + m[4] * m[3] * m[10] + m[8] * m[2] * m[7] - m[8] * m[3] * m[6];
        inv[11] = -m[0] * m[5] * m[11] + m[0] * m[7] * m[9] + m[4] * m[1] * m[11] - m[4] * m[3] * m[9] - m[8] * m[1] * m[7] + m[8] * m[3] * m[5];
        inv[15] = m[0] * m[5] * m[10] - m[0] * m[6] * m[9] - m[4] * m[1] * m[10] + m[4] * m[2] * m[9] + m[8] * m[1] * m[6] - m[8] * m[2] * m[5];
        float det = m[0] * inv[0] + m[1] * inv[4] + m[2] * inv[8] + m[3] * inv[12];
        Mat4 r = new Mat4();
        if (det != 0) for (int i = 0; i < 16; i++) r.m[i] = inv[i] / det;
        return r;
    }
}
