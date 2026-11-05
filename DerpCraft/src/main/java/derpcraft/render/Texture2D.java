package derpcraft.render;

import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.lwjgl.opengl.GL45C.*;

/** Simple 2D texture (GUI, font, sky). */
public final class Texture2D {
    public int id;
    public int width, height;

    /** Wraps a texture made elsewhere (a framebuffer's colour texture); the caller owns and deletes it. */
    public Texture2D(int id, int width, int height) { this.id = id; this.width = width; this.height = height; }

    public Texture2D(BufferedImage img, boolean linear, boolean repeat) {
        width = img.getWidth();
        height = img.getHeight();
        id = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, id);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, linear ? GL_LINEAR : GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, linear ? GL_LINEAR : GL_NEAREST);
        int wrap = repeat ? GL_REPEAT : GL_CLAMP_TO_EDGE;
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, wrap);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, wrap);
        glPixelStorei(GL_UNPACK_ALIGNMENT, 4);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, width, height, 0, GL_RGBA, GL_UNSIGNED_BYTE, toRGBA(img));
    }

    public static ByteBuffer toRGBA(BufferedImage img) {
        int w = img.getWidth(), h = img.getHeight();
        int[] px = img.getRGB(0, 0, w, h, null, 0, w);
        return toRGBA(px);
    }

    public static ByteBuffer toRGBA(int[] argb) {
        ByteBuffer b = ByteBuffer.allocateDirect(argb.length * 4).order(ByteOrder.nativeOrder());
        for (int p : argb) {
            b.put((byte) (p >> 16)).put((byte) (p >> 8)).put((byte) p).put((byte) (p >>> 24));
        }
        b.flip();
        return b;
    }

    public void bind(int unit) {
        glActiveTexture(GL_TEXTURE0 + unit);
        glBindTexture(GL_TEXTURE_2D, id);
    }

    public void delete() { glDeleteTextures(id); }
}
