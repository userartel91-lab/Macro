package derpcraft.render;

import java.nio.ByteBuffer;

import static org.lwjgl.opengl.GL45C.*;

/**
 * Binds the sun / shadow / ray tracing inputs shared by terrain.fsh and model.fsh (lighting.glsl):
 * texture units 5 = shadow map, 6 = voxel volume, 7 = block table, 8 = entity shadow map.
 */
public final class LightingState {
    private static int dummyShadow, dummyVoxels, dummyTable;

    private LightingState() {}

    private static void makeDummies() {
        dummyShadow = glGenTextures();
        glBindTexture(GL_TEXTURE_2D_ARRAY, dummyShadow);
        glTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_COMPARE_MODE, GL_COMPARE_REF_TO_TEXTURE);
        glTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
        glTexImage3D(GL_TEXTURE_2D_ARRAY, 0, GL_DEPTH_COMPONENT32F, 1, 1, 1, 0, GL_DEPTH_COMPONENT, GL_FLOAT, (ByteBuffer) null);
        dummyVoxels = glGenTextures();
        glBindTexture(GL_TEXTURE_3D, dummyVoxels);
        glTexParameteri(GL_TEXTURE_3D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_3D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        glTexImage3D(GL_TEXTURE_3D, 0, GL_RG16UI, 1, 1, 1, 0, GL_RG_INTEGER, GL_UNSIGNED_SHORT, (ByteBuffer) null);
        dummyTable = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, dummyTable);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, 1, 2, 0, GL_RGBA, GL_UNSIGNED_BYTE, (ByteBuffer) null);
    }

    /** Binds textures for units 5..8 ('fp' may be null: dummies only). */
    public static void bindTextures(WorldRenderer.FrameParams fp) {
        if (dummyShadow == 0) makeDummies();
        boolean on = fp != null;
        glActiveTexture(GL_TEXTURE5);
        glBindTexture(GL_TEXTURE_2D_ARRAY, on && fp.cascades > 0 ? fp.shadowTex : dummyShadow);
        glActiveTexture(GL_TEXTURE6);
        glBindTexture(GL_TEXTURE_3D, on && fp.rt == 1 ? fp.voxelTex : dummyVoxels);
        glActiveTexture(GL_TEXTURE7);
        glBindTexture(GL_TEXTURE_2D, on && fp.rt == 1 ? fp.blockTable : dummyTable);
        glActiveTexture(GL_TEXTURE8);
        glBindTexture(GL_TEXTURE_2D_ARRAY, on && fp.entityCascades > 0 ? fp.entityShadowTex : dummyShadow);
        glActiveTexture(GL_TEXTURE0);
    }

    /** Sets the lighting.glsl uniforms on the (bound) shader; 'fp' null = everything off. */
    public static void setUniforms(Shader sh, WorldRenderer.FrameParams fp) {
        sh.set("uShadowMap", 5);
        sh.set("uVoxels", 6);
        sh.set("uBlockTable", 7);
        sh.set("uEntityShadowMap", 8);
        sh.set("uShadowFade", fp == null ? 1f : fp.shadowFade);
        if (fp == null) {
            sh.set("uCascades", 0);
            sh.set("uEntityCascades", 0);
            sh.set("uRT", 0);
            sh.set("uLightStrength", 0f);
            return;
        }
        sh.set("uLightDir", fp.lightX, fp.lightY, fp.lightZ);
        sh.set("uLightStrength", fp.lightStrength);
        sh.set("uSunColor", fp.sunR, fp.sunG, fp.sunB);
        sh.set("uSkyColor", fp.skyR, fp.skyG, fp.skyB);
        sh.set("uCascades", fp.cascades);
        if (fp.cascades > 0 || fp.entityCascades > 0) glUniformMatrix4fv(sh.loc("uShadowMats"), false, fp.shadowMats);
        sh.set("uShadowTexel", fp.shadowTexel);
        sh.set("uShadowTaps", fp.shadowTaps);
        sh.set("uEntityCascades", fp.entityCascades);
        sh.set("uEntityShadowTexel", fp.entityShadowTexel);
        sh.set("uRT", fp.rt);
        int bx = (int) Math.floor(fp.camX), by = (int) Math.floor(fp.camY), bz = (int) Math.floor(fp.camZ);
        sh.seti3("uCamBlock", bx, by, bz);
        sh.set("uCamFrac", (float) (fp.camX - bx), (float) (fp.camY - by), (float) (fp.camZ - bz));
        sh.seti("uVoxelMin", fp.voxelMinX, fp.voxelMinZ);
    }
}
