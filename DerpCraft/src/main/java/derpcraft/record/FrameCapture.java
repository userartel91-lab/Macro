package derpcraft.record;

import derpcraft.render.Shader;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GLCapabilities;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicIntegerArray;

import static org.lwjgl.opengl.GL45C.*;

/**
 * Grabs finished frames for the {@link ScreenRecorder} without stalling the game. On the GPU the picture is scaled to
 * the video size (keeping its shape, black bars if the window changed shape), flipped so rows come top-down, and
 * converted to I420 YUV (shaders/record_yuv.fsh). It is then copied into one of several pixel buffers in the
 * background. A frame is handed to the recorder a frame or two later, when its fence says the copy is done.
 *
 * <p>With OpenGL 4.4 the pixel buffers stay mapped in cached system memory and the recorder's writer thread reads
 * them directly: the main thread copies nothing. A buffer is reused only after the writer let it go.
 * Main (GL) thread only, except the release callbacks.
 */
public final class FrameCapture {
    private static final int SLOTS = 6;
    private static final int FREE = 0, GPU = 1, CPU = 2;

    private final Object lock = new Object();
    private Ring ring;
    private final List<Ring> retired = new ArrayList<>();
    private Shader yuv;
    private int vao;
    private long nextSeq = 1;

    /** The GPU objects for one video size. */
    private final class Ring {
        final int w, h, outW, outH, bytes, readBytes;
        final int srcTex, srcFbo, outTex, outFbo;
        final int[] pbo = new int[SLOTS], repeat = new int[SLOTS];
        final long[] fence = new long[SLOTS], seq = new long[SLOTS];
        final ByteBuffer[] view = new ByteBuffer[SLOTS];
        final AtomicIntegerArray state = new AtomicIntegerArray(SLOTS);
        final Runnable[] release = new Runnable[SLOTS];
        final boolean persistent;

        Ring(int w, int h) {
            this.w = w; this.h = h;
            bytes = ScreenRecorder.frameBytes(w, h);            // I420: Y w*h, U and V (w/2)*(h/2) each
            outW = w;
            outH = (bytes / 4 + outW) / outW;                    // 4 bytes a texel, rounded up
            readBytes = outW * outH * 4;
            srcTex = texture(w, h, GL_LINEAR);
            srcFbo = fbo(srcTex);
            outTex = texture(outW, outH, GL_NEAREST);
            outFbo = fbo(outTex);
            GLCapabilities caps = GL.getCapabilities();
            persistent = caps.OpenGL44 || caps.GL_ARB_buffer_storage;
            for (int i = 0; i < SLOTS; i++) {
                pbo[i] = glGenBuffers();
                glBindBuffer(GL_PIXEL_PACK_BUFFER, pbo[i]);
                if (persistent) {
                    int flags = GL_MAP_READ_BIT | GL_MAP_PERSISTENT_BIT | GL_MAP_COHERENT_BIT;
                    glBufferStorage(GL_PIXEL_PACK_BUFFER, readBytes, flags | GL_CLIENT_STORAGE_BIT);
                    view[i] = glMapBufferRange(GL_PIXEL_PACK_BUFFER, 0, readBytes, flags);
                } else {
                    glBufferData(GL_PIXEL_PACK_BUFFER, readBytes, GL_STREAM_READ);
                    view[i] = ByteBuffer.allocateDirect(bytes).order(ByteOrder.nativeOrder());
                }
                int slot = i;
                release[i] = () -> { state.set(slot, FREE); synchronized (lock) { lock.notifyAll(); } };
            }
            glBindBuffer(GL_PIXEL_PACK_BUFFER, 0);
        }

        int free() {
            for (int i = 0; i < SLOTS; i++) if (state.get(i) == FREE) return i;
            return -1;
        }

        int oldestOnGpu() {
            int best = -1;
            for (int i = 0; i < SLOTS; i++) if (state.get(i) == GPU && (best < 0 || seq[i] < seq[best])) best = i;
            return best;
        }

        boolean idle() {
            for (int i = 0; i < SLOTS; i++) if (state.get(i) != FREE) return false;
            return true;
        }

        void delete() {
            for (int i = 0; i < SLOTS; i++) {
                if (fence[i] != 0) { glDeleteSync(fence[i]); fence[i] = 0; }
                if (pbo[i] != 0) {
                    if (persistent) { glBindBuffer(GL_PIXEL_PACK_BUFFER, pbo[i]); glUnmapBuffer(GL_PIXEL_PACK_BUFFER); }
                    glDeleteBuffers(pbo[i]);
                    pbo[i] = 0;
                }
            }
            glBindBuffer(GL_PIXEL_PACK_BUFFER, 0);
            glDeleteFramebuffers(srcFbo);
            glDeleteFramebuffers(outFbo);
            glDeleteTextures(srcTex);
            glDeleteTextures(outTex);
        }
    }

    private static int texture(int w, int h, int filter) {
        int t = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, t);
        glTexStorage2D(GL_TEXTURE_2D, 1, GL_RGBA8, w, h);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, filter);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, filter);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        glBindTexture(GL_TEXTURE_2D, 0);
        return t;
    }

    private static int fbo(int tex) {
        int f = glGenFramebuffers();
        glBindFramebuffer(GL_FRAMEBUFFER, f);
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, tex, 0);
        glBindFramebuffer(GL_FRAMEBUFFER, 0);
        return f;
    }

    /**
     * Queues a copy of the current (default) framebuffer, fbW x fbH, scaled to w x h, standing for {@code n} video
     * frames. {@code wait}: when every buffer is busy, wait for the encoder instead of dropping the frame (smooth mode).
     */
    public void grab(ScreenRecorder rec, int fbW, int fbH, int w, int h, int n, boolean wait) {
        Ring r = ring(w, h);
        int slot = r.free();
        if (slot < 0) { poll(rec, true); slot = r.free(); }           // finished copies go to the encoder first
        if (slot < 0 && wait) slot = awaitFree(r, 3000);             // the encoder is behind: let it catch up
        if (slot < 0) { rec.skip(n); return; }

        boolean scissor = glIsEnabled(GL_SCISSOR_TEST), blend = glIsEnabled(GL_BLEND), depth = glIsEnabled(GL_DEPTH_TEST), cull = glIsEnabled(GL_CULL_FACE);
        glDisable(GL_SCISSOR_TEST);
        glDisable(GL_BLEND);
        glDisable(GL_DEPTH_TEST);
        glDisable(GL_CULL_FACE);
        // 1. scale into the video size, keeping the window's shape; flipped, so row 0 is the top of the picture
        glBindFramebuffer(GL_DRAW_FRAMEBUFFER, r.srcFbo);
        glViewport(0, 0, w, h);
        glClearColor(0, 0, 0, 1);
        glClear(GL_COLOR_BUFFER_BIT);
        double s = Math.min(w / (double) fbW, h / (double) fbH);
        int dw = (int) Math.round(fbW * s), dh = (int) Math.round(fbH * s);
        int dx = (w - dw) / 2, dy = (h - dh) / 2;
        glBindFramebuffer(GL_READ_FRAMEBUFFER, 0);
        glBlitFramebuffer(0, 0, fbW, fbH, dx, dy + dh, dx + dw, dy, GL_COLOR_BUFFER_BIT, dw == fbW && dh == fbH ? GL_NEAREST : GL_LINEAR);
        // 2. RGB -> I420 on the GPU
        if (yuv == null) { yuv = new Shader("post", "record_yuv", ""); vao = glGenVertexArrays(); }
        glBindFramebuffer(GL_FRAMEBUFFER, r.outFbo);
        glViewport(0, 0, r.outW, r.outH);
        yuv.use();
        yuv.set("uSrc", 0);
        yuv.seti("uSize", w, h);
        yuv.set("uOutWidth", r.outW);
        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_2D, r.srcTex);
        glBindVertexArray(vao);
        glDrawArrays(GL_TRIANGLES, 0, 3);
        glBindVertexArray(0);
        glUseProgram(0);
        // 3. copy into the pixel buffer in the background
        glBindBuffer(GL_PIXEL_PACK_BUFFER, r.pbo[slot]);
        glPixelStorei(GL_PACK_ALIGNMENT, 1);
        glReadPixels(0, 0, r.outW, r.outH, GL_RGBA, GL_UNSIGNED_BYTE, 0L);
        glBindBuffer(GL_PIXEL_PACK_BUFFER, 0);
        r.fence[slot] = glFenceSync(GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
        r.repeat[slot] = n;
        r.seq[slot] = nextSeq++;
        r.state.set(slot, GPU);
        glFlush();                                                   // start the copy now, not at the next swap

        glBindFramebuffer(GL_FRAMEBUFFER, 0);
        glViewport(0, 0, fbW, fbH);
        if (scissor) glEnable(GL_SCISSOR_TEST);
        if (blend) glEnable(GL_BLEND);
        if (depth) glEnable(GL_DEPTH_TEST);
        if (cull) glEnable(GL_CULL_FACE);
    }

    /** Hands finished copies to the recorder, oldest first. {@code wait}: also the unfinished ones (pause / stop). */
    public void poll(ScreenRecorder rec, boolean wait) {
        Ring r = ring;
        if (r != null) while (true) {
            int slot = r.oldestOnGpu();
            if (slot < 0) break;
            int res = glClientWaitSync(r.fence[slot], wait ? GL_SYNC_FLUSH_COMMANDS_BIT : 0, wait ? 1_000_000_000L : 0L);
            if (res == GL_TIMEOUT_EXPIRED && !wait) break;
            glDeleteSync(r.fence[slot]);
            r.fence[slot] = 0;
            if (res == GL_WAIT_FAILED || res == GL_TIMEOUT_EXPIRED) { r.state.set(slot, FREE); rec.skip(r.repeat[slot]); continue; }
            if (!r.persistent) {
                glBindBuffer(GL_PIXEL_PACK_BUFFER, r.pbo[slot]);
                ByteBuffer m = glMapBufferRange(GL_PIXEL_PACK_BUFFER, 0, r.bytes, GL_MAP_READ_BIT);
                boolean ok = m != null;
                if (ok) { r.view[slot].put(0, m, 0, r.bytes); glUnmapBuffer(GL_PIXEL_PACK_BUFFER); }
                glBindBuffer(GL_PIXEL_PACK_BUFFER, 0);
                if (!ok) { r.state.set(slot, FREE); rec.skip(r.repeat[slot]); continue; }
            }
            r.state.set(slot, CPU);
            rec.submit(r.view[slot], r.bytes, r.repeat[slot], r.release[slot]);
        }
        deleteRetired();
    }

    private int awaitFree(Ring r, long ms) {
        long end = System.nanoTime() + ms * 1_000_000L;
        synchronized (lock) {
            int slot;
            while ((slot = r.free()) < 0) {
                long left = (end - System.nanoTime()) / 1_000_000L;
                if (left <= 0) return -1;
                try { lock.wait(left); } catch (InterruptedException e) { return -1; }
            }
            return slot;
        }
    }

    private Ring ring(int w, int h) {
        if (ring != null && ring.w == w && ring.h == h) return ring;
        if (ring != null) retired.add(ring);      // the writer may still read its buffers: deleted once it let them go
        ring = new Ring(w, h);
        deleteRetired();
        return ring;
    }

    private void deleteRetired() {
        if (retired.isEmpty()) return;
        retired.removeIf(r -> {
            if (r.oldestOnGpu() >= 0 || !r.idle()) return false;
            r.delete();
            return true;
        });
    }

    /** Frees the GPU objects (game closing; call after the recorder finished). Buffers the writer still holds are left alone. */
    public void release() {
        if (ring != null) { retired.add(ring); ring = null; }
        for (Ring r : retired) for (int i = 0; i < SLOTS; i++) if (r.state.get(i) == GPU) r.state.set(i, FREE);
        deleteRetired();
    }
}
