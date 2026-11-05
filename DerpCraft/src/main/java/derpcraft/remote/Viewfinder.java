package derpcraft.remote;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import java.awt.image.BufferedImage;
import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The phone's viewfinder: single frames over HTTP (/frame) and a live stream over a plain socket (port + 1).
 *
 * <p>Stream protocol: the phone connects and sends one line {@code DCV1 <pin> <maxW> <maxH> <quality 10-95>}; the game
 * answers {@code OK <w> <h>} (or {@code ERR ...}). Then every frame is a 4-byte big-endian length and a JPEG. The phone
 * sends one byte {@code +} for every frame it is ready to receive (credits), usually 2 at the start and one after each
 * decoded frame: the game never sends more than the phone can show, so there is no growing delay. At most
 * {@link #MAX_FPS} frames a second.
 */
public final class Viewfinder {
    public static final int MAX_FPS = 30;

    private final RemoteServer server;
    /** Where frames come from: the GPU (FrameGrabber); swappable for tests. */
    interface Source { ByteBuffer grab(int srcW, int srcH, int w, int h); }

    private Source grabber = new FrameGrabber();

    private final List<Shot> shots = new ArrayList<>();
    private final List<Stream> streams = new CopyOnWriteArrayList<>();
    private final ExecutorService encoder = Executors.newSingleThreadExecutor(r -> { Thread t = new Thread(r, "DerpCraft-Viewfinder"); t.setDaemon(true); return t; });
    private ServerSocket listen;
    /** When the next stream frame is due (a schedule, so a game at 45 fps still feeds a full 30). */
    private long nextStreamGrab;
    public volatile String error = "";

    private record Shot(int maxW, int maxH, boolean png, float quality, CompletableFuture<byte[]> out) {}

    Viewfinder(RemoteServer server) { this.server = server; }

    // ------------------------------------------------------------------------------------------ server
    synchronized void start(int port) {
        if (listen != null) return;
        try {
            listen = new ServerSocket(port, 4);
            error = "";
            Thread t = new Thread(this::acceptLoop, "DerpCraft-Viewfinder-Listen");
            t.setDaemon(true);
            t.start();
        } catch (IOException e) {
            listen = null;
            error = "viewfinder port " + port + " is busy";
            System.err.println("Viewfinder stream could not start: " + e);
        }
    }

    synchronized void stop() {
        try { if (listen != null) listen.close(); } catch (IOException ignored) {}
        listen = null;
        for (Stream s : streams) s.close();
        streams.clear();
    }

    private void acceptLoop() {
        ServerSocket ss = listen;
        while (ss != null && !ss.isClosed()) {
            try {
                Socket s = ss.accept();
                if (streams.size() >= 4) { s.close(); continue; }
                s.setTcpNoDelay(true);
                new Stream(s).start();
            } catch (IOException e) {
                if (ss.isClosed()) return;
            }
        }
    }

    // ------------------------------------------------------------------------------------------ main thread
    /** Called after each rendered frame (before the buffer swap): grabs the picture if a shot or a stream wants it. */
    public void afterFrame(int fbW, int fbH) {
        if (fbW <= 0 || fbH <= 0) return;
        List<Shot> todo;
        synchronized (shots) { todo = shots.isEmpty() ? List.of() : new ArrayList<>(shots); shots.clear(); }
        long now = System.nanoTime();
        boolean streamDue = false;
        if (!streams.isEmpty() && now >= nextStreamGrab)
            for (Stream s : streams) if (s.wantsFrame()) { streamDue = true; break; }
        if (todo.isEmpty() && !streamDue) return;

        Map<Long, ByteBuffer> grabbed = new HashMap<>();
        for (Shot s : todo) {
            int[] sz = fit(s.maxW, s.maxH, fbW, fbH);
            ByteBuffer buf = grabbed.computeIfAbsent(key(sz), k -> grabber.grab(fbW, fbH, sz[0], sz[1]));
            encoder.execute(() -> {
                try { s.out.complete(encode(toImage(buf, sz[0], sz[1]), s.png, s.quality, null)); }
                catch (Throwable t) { s.out.completeExceptionally(t); }
            });
        }
        if (streamDue) {
            long step = 1_000_000_000L / MAX_FPS;
            nextStreamGrab = Math.max(nextStreamGrab + step, now - step / 2);
            for (Stream s : streams) {
                if (!s.wantsFrame()) continue;
                int[] sz = fit(s.maxW, s.maxH, fbW, fbH);
                s.offer(grabbed.computeIfAbsent(key(sz), k -> grabber.grab(fbW, fbH, sz[0], sz[1])), sz[0], sz[1]);
            }
        }
    }

    private static long key(int[] sz) { return (long) sz[0] << 32 | sz[1]; }

    /** Largest size with the screen's aspect that fits in maxW x maxH (never bigger than the screen). */
    static int[] fit(int maxW, int maxH, int fbW, int fbH) {
        double k = Math.min(1, Math.min(maxW / (double) fbW, maxH / (double) fbH));
        return new int[]{Math.max(16, (int) Math.round(fbW * k)), Math.max(16, (int) Math.round(fbH * k))};
    }

    // ------------------------------------------------------------------------------------------ single frames (HTTP)
    byte[] shot(int maxW, int maxH, boolean png, float quality) throws Exception {
        CompletableFuture<byte[]> f = new CompletableFuture<>();
        synchronized (shots) { shots.add(new Shot(maxW, maxH, png, quality, f)); }
        return f.get(2, TimeUnit.SECONDS);
    }

    // ------------------------------------------------------------------------------------------ encoding
    private static BufferedImage toImage(ByteBuffer rgba, int w, int h) {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        int[] row = new int[w];
        for (int y = 0; y < h; y++) {
            int base = (h - 1 - y) * w * 4;
            for (int x = 0; x < w; x++) {
                int i = base + x * 4;
                row[x] = (rgba.get(i) & 255) << 16 | (rgba.get(i + 1) & 255) << 8 | (rgba.get(i + 2) & 255);
            }
            img.setRGB(0, y, w, 1, row, 0, w);
        }
        return img;
    }

    /** JPEG (or PNG); pass a writer to reuse it (streams), or null. */
    private static byte[] encode(BufferedImage img, boolean png, float quality, ImageWriter reuse) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream(32 * 1024);
        if (png) { ImageIO.write(img, "png", bos); return bos.toByteArray(); }
        ImageWriter w = reuse != null ? reuse : ImageIO.getImageWritersByFormatName("jpeg").next();
        try (MemoryCacheImageOutputStream out = new MemoryCacheImageOutputStream(bos)) {
            w.setOutput(out);
            ImageWriteParam p = w.getDefaultWriteParam();
            p.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            p.setCompressionQuality(quality);
            w.write(null, new IIOImage(img, null, null), p);
        } finally { if (reuse == null) w.dispose(); }
        return bos.toByteArray();
    }

    // ------------------------------------------------------------------------------------------ live stream
    private final class Stream extends Thread {
        private final Socket socket;
        private final AtomicInteger credits = new AtomicInteger();
        private volatile boolean closed;
        private ByteBuffer pending;
        private int pw, ph;
        volatile int maxW = 376, maxH = 198;
        private float quality = 0.5f;

        Stream(Socket s) {
            super("DerpCraft-Viewfinder-Stream");
            setDaemon(true);
            socket = s;
        }

        /** A free slot and a credit: the next frame can be grabbed even while the last one is still being sent. */
        synchronized boolean wantsFrame() { return !closed && pending == null && credits.get() > 0; }

        synchronized void offer(ByteBuffer buf, int w, int h) {
            pending = buf; pw = w; ph = h;
            credits.decrementAndGet();          // the credit is used now, so frames in flight never exceed what the phone asked for
            notifyAll();
        }

        void close() {
            closed = true;
            try { socket.close(); } catch (IOException ignored) {}
            synchronized (this) { notifyAll(); }
        }

        @Override public void run() {
            ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
            try {
                socket.setSoTimeout(10_000);
                InputStream in = socket.getInputStream();
                DataOutputStream out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream(), 64 * 1024));
                String[] hello = readLine(in).trim().split("\\s+");
                if (hello.length < 2 || !hello[0].equals("DCV1") || !server.pinOk(socket.getInetAddress().getHostAddress(), hello[1])) {
                    out.write("ERR wrong PIN\n".getBytes(StandardCharsets.UTF_8));
                    out.flush();
                    return;
                }
                try {
                    if (hello.length > 3) { maxW = clamp(Integer.parseInt(hello[2]), 32, 1920); maxH = clamp(Integer.parseInt(hello[3]), 32, 1080); }
                    if (hello.length > 4) quality = clamp(Integer.parseInt(hello[4]), 10, 95) / 100f;
                } catch (NumberFormatException ignored) {}
                out.write("OK\n".getBytes(StandardCharsets.UTF_8));
                out.flush();
                socket.setSoTimeout(0);
                server.lastClient = socket.getInetAddress().getHostAddress();
                streams.add(this);
                // credits arrive on their own thread: one byte per frame the phone can take
                Thread reader = new Thread(() -> {
                    try {
                        int b;
                        while ((b = in.read()) >= 0) if (b == '+') { credits.incrementAndGet(); server.lastSeen = System.currentTimeMillis(); }
                    } catch (IOException ignored) {}
                    close();
                }, "DerpCraft-Viewfinder-Credits");
                reader.setDaemon(true);
                reader.start();
                while (!closed) {
                    ByteBuffer buf;
                    int w, h;
                    synchronized (this) {
                        while (!closed && pending == null) { try { wait(500); } catch (InterruptedException e) { return; } }
                        if (closed) break;
                        buf = pending; w = pw; h = ph; pending = null;
                    }
                    byte[] jpg = encode(toImage(buf, w, h), false, quality, writer);
                    out.writeInt(jpg.length);
                    out.write(jpg);
                    out.flush();
                }
            } catch (IOException e) {
                // phone went away
            } finally {
                writer.dispose();
                streams.remove(this);
                close();
            }
        }
    }

    private static int clamp(int v, int lo, int hi) { return Math.max(lo, Math.min(hi, v)); }

    private static String readLine(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder();
        int c;
        while ((c = in.read()) >= 0 && c != '\n' && sb.length() < 200) sb.append((char) c);
        return sb.toString();
    }
}
