package derpcraft.record;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.file.*;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.*;

/**
 * Built-in screen recorder. Frames (I420 YUV made on the GPU by {@link FrameCapture}, fixed size) are piped into
 * ffmpeg, which encodes them on the graphics card (NVIDIA NVENC, H.264 or H.265) or on the processor (x264) into an
 * MP4 in game/recordings.
 *
 * <p>The video has a fixed frame rate. Two ways to fill it:
 * <ul>
 * <li><b>Smooth</b> (default): the game runs on the video clock while recording. Every rendered frame is exactly one
 * video frame and the game advances exactly 1/fps per frame ({@link #frameLocked}); frames are never dropped (the game
 * waits for the encoder instead). Motion in the video is perfectly even. If the computer cannot render that many
 * frames a second, the game runs a bit slower than real time while recording, the video still plays at full speed.</li>
 * <li><b>Real time</b>: the game tells how much real time passed between rendered frames ({@link #advance}); a rendered
 * frame is grabbed only when the next video frame is due ({@link #framesDue}), and repeated when the game ran slower
 * than the video.</li>
 * </ul>
 * Time while the recording is paused (menus open) is simply not counted, so the video cuts straight from the frame
 * before the menu to the frame after it.
 *
 * <p>No OpenGL in here; everything may be called from any thread.
 */
public final class ScreenRecorder {
    public static final String[] ENCODERS = {"NVENC H.264", "NVENC H.265", "CPU (x264)"};
    public static final int[] FPS_CHOICES = {24, 25, 30, 50, 60, 120};
    /** Output heights; 0 = the size of the game window. */
    public static final int[] HEIGHTS = {0, 720, 1080, 1440, 2160};

    public static String heightName(int h) { return h == 0 ? "Window" : h == 2160 ? "2160p (4K)" : h + "p"; }

    private final Path gameDir, outDir;

    public ScreenRecorder(Path gameDir) {
        this.gameDir = gameDir;
        this.outDir = gameDir.resolve("recordings");
    }

    public Path folder() { return outDir; }

    // ------------------------------------------------------------------------------------------ ffmpeg lookup
    private volatile String ffmpeg;          // command that works, "" = not found, null = not searched yet
    private final Map<Integer, Boolean> encoderWorks = new ConcurrentHashMap<>();

    /** Finds ffmpeg: game/ffmpeg.exe, game/ffmpeg/bin/ffmpeg.exe, game/ffmpeg/ffmpeg.exe, then the PATH. Null when missing. */
    public synchronized String ffmpeg() {
        if (ffmpeg == null) {
            String exe = System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win") ? "ffmpeg.exe" : "ffmpeg";
            List<String> cands = new ArrayList<>();
            for (Path p : new Path[]{gameDir.resolve(exe), gameDir.resolve("ffmpeg").resolve("bin").resolve(exe),
                    gameDir.resolve("ffmpeg").resolve(exe), gameDir.getParent() == null ? null : gameDir.getParent().resolve(exe)})
                if (p != null && Files.isRegularFile(p)) cands.add(p.toAbsolutePath().toString());
            cands.add("ffmpeg");
            ffmpeg = "";
            for (String c : cands) if (run(List.of(c, "-hide_banner", "-version"), 5000) == 0) { ffmpeg = c; break; }
            if (!ffmpeg.isEmpty()) System.out.println("Screen recording: using " + ffmpeg);
        }
        return ffmpeg.isEmpty() ? null : ffmpeg;
    }

    /** Looks for ffmpeg again (after the player copied it into the game folder). */
    public synchronized void rescan() { ffmpeg = null; encoderWorks.clear(); }

    /** Whether the encoder works on this computer (a tiny test encode; NVENC needs an NVIDIA card and a fresh driver). */
    public boolean encoderWorks(int encoder) {
        String ff = ffmpeg();
        if (ff == null) return false;
        return encoderWorks.computeIfAbsent(encoder, e -> {
            List<String> cmd = new ArrayList<>(List.of(ff, "-hide_banner", "-loglevel", "error", "-f", "lavfi", "-i", "color=c=black:s=640x360:r=30:d=0.3"));
            cmd.addAll(encoderArgs(e, 30, 10));
            cmd.addAll(List.of("-f", "null", "-"));
            boolean ok = run(cmd, 15000) == 0;
            System.out.println("Screen recording: " + ENCODERS[e] + (ok ? " works" : " is not available"));
            return ok;
        });
    }

    /** Checks ffmpeg and the encoders in the background (so the first recording starts without a hitch). */
    public void warmUp(int encoder) {
        Thread t = new Thread(() -> { if (ffmpeg() != null) encoderWorks(encoder); }, "DerpCraft-Recorder-Probe");
        t.setDaemon(true);
        t.start();
    }

    private static int run(List<String> cmd, long timeoutMs) {
        try {
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            p.getOutputStream().close();
            Thread drain = new Thread(() -> { try (InputStream in = p.getInputStream()) { in.transferTo(OutputStream.nullOutputStream()); } catch (IOException ignored) {} });
            drain.setDaemon(true);
            drain.start();
            if (!p.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) { p.destroyForcibly(); return -1; }
            return p.exitValue();
        } catch (Exception e) { return -1; }
    }

    /** The frames are BT.709 TV range YUV (converted on the GPU); tagged on the input and the output, so ffmpeg never converts. */
    private static final List<String> COLOR_TAGS = List.of("-colorspace", "bt709", "-color_primaries", "bt709", "-color_trc", "bt709", "-color_range", "tv");

    /** Encoder options; bitrate in Mbit/s. Variable bitrate around the target, a key frame every 2 seconds. */
    static List<String> encoderArgs(int encoder, int fps, int mbps) {
        String b = mbps + "M", max = Math.round(mbps * 1.5) + "M", buf = mbps * 2 + "M";
        boolean high = fps > 60;           // 120 fps is twice the work: a faster preset keeps the encoder ahead
        List<String> a = new ArrayList<>();
        switch (encoder) {
            case 0 -> a.addAll(List.of("-c:v", "h264_nvenc", "-preset", high ? "p4" : "p5", "-tune", "hq", "-rc", "vbr", "-profile:v", "high"));
            case 1 -> a.addAll(List.of("-c:v", "hevc_nvenc", "-preset", high ? "p4" : "p5", "-tune", "hq", "-rc", "vbr", "-tag:v", "hvc1"));
            default -> a.addAll(List.of("-c:v", "libx264", "-preset", high ? "superfast" : "veryfast", "-profile:v", "high"));
        }
        a.addAll(List.of("-pix_fmt", "yuv420p", "-b:v", b, "-maxrate", max, "-bufsize", buf, "-g", String.valueOf(fps * 2)));
        a.addAll(COLOR_TAGS);
        return a;
    }

    /** ffmpeg command for a recording of w x h I420 frames from stdin. */
    static List<String> command(String ff, int w, int h, int fps, int mbps, int encoder, String file) {
        List<String> cmd = new ArrayList<>(List.of(ff, "-hide_banner", "-loglevel", "error", "-y",
                "-f", "rawvideo", "-pix_fmt", "yuv420p", "-s", w + "x" + h, "-framerate", String.valueOf(fps)));
        cmd.addAll(COLOR_TAGS);
        cmd.addAll(List.of("-i", "-"));
        cmd.addAll(encoderArgs(encoder, fps, mbps));
        cmd.addAll(List.of("-r", String.valueOf(fps), "-movflags", "+faststart", file));
        return cmd;
    }

    // ------------------------------------------------------------------------------------------ recording state
    /** A finished frame: {@code bytes} of I420 in {@code data} (read only), shown for {@code repeat} video frames;
     *  {@code release} gives the buffer back to the capture once it was copied. */
    private record Frame(ByteBuffer data, int bytes, int repeat, Runnable release) {}
    private static final Frame END = new Frame(null, 0, 0, null);

    private Process proc;
    private Thread writer;
    private BlockingQueue<Frame> queue;
    private volatile boolean recording, failed;
    private int width, height, fps;
    private double recSeconds;
    private long slots;          // video frames handed out so far
    private int carry;           // frames dropped because the encoder was busy: the next frame is shown that much longer
    private volatile long dropped;
    private Path file;
    private String encoderName = "";
    private final Deque<String> stderr = new ArrayDeque<>();
    private final ConcurrentLinkedQueue<String> messages = new ConcurrentLinkedQueue<>();

    public boolean recording() { return recording; }
    public int width() { return width; }
    public int height() { return height; }
    public int fps() { return fps; }
    /** Recorded (unpaused) time in seconds: the time code. */
    public double seconds() { return recSeconds; }
    public long dropped() { return dropped; }
    public String encoderName() { return encoderName; }

    /** Messages for the player ("Recording saved as ..."), collected by the main thread. */
    public String pollMessage() { return messages.poll(); }

    /** Output size for a window of fbW x fbH: the chosen height (or the window's), width from the window's shape, both even. */
    public static int[] outputSize(int fbW, int fbH, int heightSetting) {
        int h = heightSetting <= 0 ? fbH : heightSetting;
        int w = (int) Math.round(h * (double) fbW / Math.max(1, fbH));
        return new int[]{Math.max(2, w & ~1), Math.max(2, h & ~1)};
    }

    /**
     * Starts a recording. Returns null when it runs, else the reason it cannot (shown to the player).
     * When the chosen NVENC encoder does not work, falls back to the other NVENC codec, then to x264.
     */
    public synchronized String start(int w, int h, int fps, int mbps, int encoder) {
        if (recording) return null;
        String ff = ffmpeg();
        if (ff == null) {
            System.err.println("Screen recording: no ffmpeg in " + gameDir + " or on the PATH");
            return "ffmpeg not found: put ffmpeg.exe into the game folder";
        }
        int enc = -1;
        for (int e : new int[]{encoder, encoder == 0 ? 1 : 0, 2}) if (encoderWorks(e)) { enc = e; break; }
        if (enc < 0) return "ffmpeg could not encode video (no NVENC and no x264?)";
        if (enc != encoder) messages.add("§e" + ENCODERS[encoder] + " is not available, recording with " + ENCODERS[enc]);
        try {
            Files.createDirectories(outDir);
            String base = new SimpleDateFormat("yyyy-MM-dd_HH.mm.ss").format(new Date());
            file = outDir.resolve(base + ".mp4");
            for (int i = 1; Files.exists(file); i++) file = outDir.resolve(base + "_" + i + ".mp4");
            proc = new ProcessBuilder(command(ff, w, h, fps, mbps, enc, file.toString())).start();
        } catch (IOException e) {
            return "cannot start ffmpeg: " + e.getMessage();
        }
        this.width = w; this.height = h; this.fps = fps;
        encoderName = ENCODERS[enc];
        recSeconds = 0; slots = 0; carry = 0; dropped = 0; failed = false;
        synchronized (stderr) { stderr.clear(); }
        queue = new LinkedBlockingQueue<>();      // bounded by the capture's buffers
        Process p = proc;
        Thread err = new Thread(() -> {
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getErrorStream()))) {
                for (String l; (l = r.readLine()) != null; ) synchronized (stderr) { stderr.add(l); if (stderr.size() > 20) stderr.poll(); }
            } catch (IOException ignored) {}
        }, "DerpCraft-Recorder-Log");
        err.setDaemon(true);
        err.start();
        BlockingQueue<Frame> q = queue;
        Path out = file;
        writer = new Thread(() -> writeLoop(p, q, out, frameBytes(w, h)), "DerpCraft-Recorder");
        writer.setDaemon(false);   // the video is finished even when the game is closed while recording
        writer.setPriority(Thread.NORM_PRIORITY + 1);
        writer.start();
        recording = true;
        System.out.println("Recording " + w + "x" + h + " @ " + fps + " fps, " + mbps + " Mbit/s, " + encoderName + " -> " + file);
        return null;
    }

    /** Bytes of one I420 frame of w x h (both even). */
    public static int frameBytes(int w, int h) { return w * h * 3 / 2; }

    private void writeLoop(Process p, BlockingQueue<Frame> q, Path out, int size) {
        OutputStream os = p.getOutputStream();
        byte[] buf = new byte[size];
        boolean ok = true;
        try {
            while (true) {
                Frame f = q.take();
                if (f == END) break;
                try {
                    // copy out of the GPU buffer first, so the capture can reuse it while ffmpeg takes the bytes
                    f.data.get(0, buf, 0, Math.min(size, f.bytes));
                } finally { f.release.run(); }
                try {
                    if (ok) for (int i = 0; i < f.repeat; i++) os.write(buf, 0, size);
                } catch (IOException e) {
                    ok = false;
                    failed = true;
                }
            }
            try { os.close(); } catch (IOException ignored) {}
            boolean done = p.waitFor(60, TimeUnit.SECONDS);
            if (!done) p.destroyForcibly();
            if (ok && done && p.exitValue() == 0) {
                long mb = Files.size(out) >> 20;
                messages.add("Saved recording as " + out.getFileName() + " (" + mb + " MB)");
            } else {
                String why;
                synchronized (stderr) { why = stderr.isEmpty() ? "ffmpeg stopped" : stderr.peekLast(); }
                messages.add("§cRecording failed: " + why);
                System.err.println("Recording failed: " + String.join("\n", stderr));
            }
        } catch (Exception e) {
            messages.add("§cRecording failed: " + e);
            // let go of everything still queued, so the capture's buffers are free again
            for (Frame f; (f = q.poll()) != null; ) if (f.release != null) f.release.run();
        }
    }

    // ------------------------------------------------------------------------------------------ real time mode
    /** How many video frames the frame that was just rendered stands for (0 = no need to grab it). */
    public int framesDue() {
        if (!recording) return 0;
        long due = (long) Math.floor(recSeconds * fps + 1e-6) + 1 - slots;
        return (int) Math.max(0, Math.min(due, fps * 2L));
    }

    /** The grabbed frame was assigned {@code n} video frames (call right after {@link #framesDue} said so). */
    public void claim(int n) { slots += n; }

    /** Real time passed since the last rendered frame (only while the recording is running, not paused). */
    public void advance(double seconds) { if (recording) recSeconds += Math.max(0, Math.min(seconds, 0.5)); }

    // ------------------------------------------------------------------------------------------ smooth mode
    /** Smooth mode: the frame just rendered is exactly the next video frame; the time code moves on by 1/fps. */
    public void frameLocked() {
        if (!recording) return;
        slots++;
        recSeconds = slots / (double) fps;
    }

    // ------------------------------------------------------------------------------------------ frames
    /**
     * Hands a finished frame (I420, rows top-down) to the encoder; it stays on screen for {@code repeat} video frames.
     * {@code release} is called (on some thread) once the data was copied and the buffer may be reused.
     */
    public void submit(ByteBuffer data, int bytes, int repeat, Runnable release) {
        repeat += carry;
        carry = 0;
        if (!recording || failed) { release.run(); return; }
        queue.add(new Frame(data, bytes, repeat, release));
    }

    /** A due frame could not be grabbed (no free buffer): the next one is shown longer. */
    public void skip(int repeat) { carry += repeat; dropped++; }

    /** Stops: the encoder finishes the file in the background (a message says when it is saved). */
    public synchronized void stop() {
        if (!recording) return;
        recording = false;
        carry = 0;          // a last frame that was dropped: nothing to repeat any more, the video just ends a bit earlier
        queue.add(END);
        System.out.println("Recording stopped: " + String.format(Locale.ROOT, "%.1f", recSeconds) + " s, " + dropped + " frames dropped");
    }

    public boolean failed() { return failed; }

    /** Waits for the file to be finished (game closing). */
    public void awaitFinish(long ms) {
        Thread w = writer;
        if (w != null) try { w.join(ms); } catch (InterruptedException ignored) {}
    }

    /** "00:01:23" */
    public static String timecode(double seconds) {
        long s = (long) seconds;
        return String.format(Locale.ROOT, "%02d:%02d:%02d", s / 3600, (s / 60) % 60, s % 60);
    }
}
