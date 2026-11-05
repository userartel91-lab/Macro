package derpcraft.remote;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.lwjgl.opengl.GL45C.*;

/**
 * Shrinks the finished frame on the GPU (a chain of half-size linear blits, so it is smooth, not aliased) and reads
 * back only the small picture. A 376x198 viewfinder frame is 300 KB instead of 8 MB at 1080p, so streaming it to the
 * phone 30 times a second costs the game almost nothing. Main (GL) thread only.
 */
final class FrameGrabber implements Viewfinder.Source {
    private int[] fbos = new int[0], rbs = new int[0], ws = new int[0], hs = new int[0];

    /** RGBA rows bottom-up, w*h*4 bytes (a new buffer each time: it is handed to another thread). */
    @Override public ByteBuffer grab(int srcW, int srcH, int w, int h) {
        // stage sizes: halve while the next half is still at least twice the target, then the target itself
        int n = 0, cw = srcW, ch = srcH;
        int[] sw = new int[16], sh = new int[16];
        while (n < 14 && cw / 2 >= w * 2 && ch / 2 >= h * 2) { cw /= 2; ch /= 2; sw[n] = cw; sh[n] = ch; n++; }
        sw[n] = w; sh[n] = h; n++;
        ensure(n, sw, sh);

        boolean scissor = glIsEnabled(GL_SCISSOR_TEST);
        if (scissor) glDisable(GL_SCISSOR_TEST);
        int readFb = 0, rw = srcW, rh = srcH;
        for (int i = 0; i < n; i++) {
            glBindFramebuffer(GL_READ_FRAMEBUFFER, readFb);
            glBindFramebuffer(GL_DRAW_FRAMEBUFFER, fbos[i]);
            glBlitFramebuffer(0, 0, rw, rh, 0, 0, ws[i], hs[i], GL_COLOR_BUFFER_BIT, GL_LINEAR);
            readFb = fbos[i]; rw = ws[i]; rh = hs[i];
        }
        ByteBuffer buf = ByteBuffer.allocateDirect(w * h * 4).order(ByteOrder.nativeOrder());
        glBindFramebuffer(GL_READ_FRAMEBUFFER, readFb);
        glPixelStorei(GL_PACK_ALIGNMENT, 1);
        glReadPixels(0, 0, w, h, GL_RGBA, GL_UNSIGNED_BYTE, buf);
        glBindFramebuffer(GL_FRAMEBUFFER, 0);
        if (scissor) glEnable(GL_SCISSOR_TEST);
        return buf;
    }

    /** (Re)creates the render targets when the chain of sizes changed. */
    private void ensure(int n, int[] sw, int[] sh) {
        boolean same = fbos.length == n;
        for (int i = 0; same && i < n; i++) same = ws[i] == sw[i] && hs[i] == sh[i];
        if (same) return;
        release();
        fbos = new int[n]; rbs = new int[n]; ws = new int[n]; hs = new int[n];
        for (int i = 0; i < n; i++) {
            ws[i] = sw[i]; hs[i] = sh[i];
            rbs[i] = glGenRenderbuffers();
            glBindRenderbuffer(GL_RENDERBUFFER, rbs[i]);
            glRenderbufferStorage(GL_RENDERBUFFER, GL_RGBA8, ws[i], hs[i]);
            fbos[i] = glGenFramebuffers();
            glBindFramebuffer(GL_FRAMEBUFFER, fbos[i]);
            glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_RENDERBUFFER, rbs[i]);
        }
        glBindRenderbuffer(GL_RENDERBUFFER, 0);
        glBindFramebuffer(GL_FRAMEBUFFER, 0);
    }

    void release() {
        for (int f : fbos) glDeleteFramebuffers(f);
        for (int r : rbs) glDeleteRenderbuffers(r);
        fbos = rbs = ws = hs = new int[0];
    }
}
