package derpcraft.audio;

import javax.sound.sampled.*;
import java.io.BufferedInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A small software mixer on the Java sound output (no extra libraries): any number of voices, each with its own
 * volume, pitch (playback speed, for engines) and stereo pan, looping or one-shot. Sounds are .wav files (PCM, any rate,
 * mono or stereo, 8/16/24 bit) or .ogg (Vorbis, through LWJGL's stb_vorbis). Without a sound device everything is silently off.
 */
public final class Audio {
    private Audio() {}

    public static final int RATE = 44100;
    private static final int FRAMES = 512;
    private static volatile boolean started, ok;
    private static SourceDataLine line;
    private static final CopyOnWriteArrayList<Voice> VOICES = new CopyOnWriteArrayList<>();
    private static final Map<String, Sample> CACHE = new HashMap<>();
    /** Tests without a sound card: voices are kept and mixed by hand. */
    static boolean offline;
    /** Master volume (0..2). */
    public static volatile float master = 1;

    /** Decoded sound: float samples (interleaved when stereo) at its own rate. */
    public static final class Sample {
        public final float[] data;
        public final int channels;
        public final float rate;
        Sample(float[] data, int channels, float rate) { this.data = data; this.channels = channels; this.rate = rate; }
        public int frames() { return data.length / channels; }
        public float seconds() { return frames() / rate; }
    }

    /** A playing sound. Change volume / pitch / pan any time (they glide over a few milliseconds, no clicks). */
    public static final class Voice {
        final Sample s;
        final boolean loop;
        public volatile float volume, pitch = 1, pan;
        volatile boolean stopped;
        double pos;
        float curVol = -1, curPan;

        Voice(Sample s, boolean loop) { this.s = s; this.loop = loop; }

        public void stop() { stopped = true; }

        public boolean playing() { return !stopped; }
    }

    /** Opens the sound output (once). Returns false when there is none. */
    public static synchronized boolean start() {
        if (started) return ok;
        started = true;
        try {
            AudioFormat f = new AudioFormat(RATE, 16, 2, true, false);
            line = AudioSystem.getSourceDataLine(f);
            line.open(f, FRAMES * 4 * 4);
            line.start();
            ok = true;
            Thread t = new Thread(Audio::run, "audio-mixer");
            t.setDaemon(true);
            t.setPriority(Thread.MAX_PRIORITY - 1);
            t.start();
        } catch (Throwable e) {
            System.err.println("No sound output: " + e);
            ok = false;
        }
        return ok;
    }


    /** Plays a sound; null when the sample is null or there is no sound output. */
    public static Voice play(Sample s, boolean loop, float volume, float pitch, float pan) {
        if (s == null || !start() && !offline) return null;
        Voice v = new Voice(s, loop);
        v.volume = volume; v.pitch = pitch; v.pan = pan;
        VOICES.add(v);
        return v;
    }

    public static void stopAll() { for (Voice v : VOICES) v.stop(); VOICES.clear(); }

    /** Loads (and caches) a .wav file; null when it cannot be read. */
    public static synchronized Sample load(Path p) {
        if (p == null) return null;
        String key;
        try { key = p.toAbsolutePath() + "|" + Files.getLastModifiedTime(p).toMillis(); } catch (Exception e) { return null; }
        if (CACHE.containsKey(key)) return CACHE.get(key);
        Sample s = null;
        if (p.getFileName().toString().toLowerCase(java.util.Locale.ROOT).endsWith(".ogg")) {
            try { s = decodeOgg(Files.readAllBytes(p)); }
            catch (Throwable e) { System.err.println("Cannot read sound " + p.getFileName() + ": " + e); }
            CACHE.put(key, s);
            return s;
        }
        try (AudioInputStream in0 = AudioSystem.getAudioInputStream(new BufferedInputStream(Files.newInputStream(p)))) {
            s = decode(in0);
        } catch (Exception e) {
            System.err.println("Cannot read sound " + p.getFileName() + " (use PCM .wav or .ogg): " + e.getMessage());
        }
        CACHE.put(key, s);
        return s;
    }

    /** Ogg Vorbis through stb_vorbis (LWJGL): the whole file to float samples. */
    static Sample decodeOgg(byte[] file) {
        java.nio.ByteBuffer mem = org.lwjgl.system.MemoryUtil.memAlloc(file.length);
        try (org.lwjgl.system.MemoryStack stack = org.lwjgl.system.MemoryStack.stackPush()) {
            mem.put(file).flip();
            java.nio.IntBuffer channels = stack.mallocInt(1), rate = stack.mallocInt(1);
            java.nio.ShortBuffer pcm = org.lwjgl.stb.STBVorbis.stb_vorbis_decode_memory(mem, channels, rate);
            if (pcm == null) throw new IllegalArgumentException("not an Ogg Vorbis file");
            try {
                int srcCh = Math.max(1, channels.get(0)), ch = Math.min(2, srcCh);
                int frames = pcm.remaining() / srcCh;
                float[] d = new float[frames * ch];
                for (int i = 0; i < frames; i++)
                    for (int c = 0; c < ch; c++) d[i * ch + c] = pcm.get(i * srcCh + c) / 32768f;
                return new Sample(d, ch, rate.get(0));
            } finally { org.lwjgl.system.libc.LibCStdlib.free(pcm); }
        } finally { org.lwjgl.system.MemoryUtil.memFree(mem); }
    }

    /** Any PCM stream to float samples. */
    static Sample decode(AudioInputStream in0) throws Exception {
        AudioFormat src = in0.getFormat();
        int ch = Math.max(1, Math.min(2, src.getChannels()));
        AudioFormat want = new AudioFormat(AudioFormat.Encoding.PCM_SIGNED, src.getSampleRate(), 16, src.getChannels(), src.getChannels() * 2, src.getSampleRate(), false);
        boolean already = src.getEncoding() == AudioFormat.Encoding.PCM_SIGNED && src.getSampleSizeInBits() == 16 && !src.isBigEndian();
        if (!already && !AudioSystem.isConversionSupported(want, src)) throw new UnsupportedAudioFileException("format " + src + " (save it as 16 bit PCM .wav)");
        AudioInputStream in = already ? in0 : AudioSystem.getAudioInputStream(want, in0);
        byte[] b = in.readAllBytes();
        int srcCh = src.getChannels();
        int frames = b.length / (2 * srcCh);
        float[] d = new float[frames * ch];
        for (int i = 0; i < frames; i++)
            for (int c = 0; c < ch; c++) {
                int o = (i * srcCh + c) * 2;
                d[i * ch + c] = (short) ((b[o] & 255) | (b[o + 1] << 8)) / 32768f;
            }
        return new Sample(d, ch, src.getSampleRate());
    }

    // ------------------------------------------------------------------ mixing
    private static void run() {
        float[] mix = new float[FRAMES * 2];
        byte[] out = new byte[FRAMES * 4];
        while (true) {
            mix(mix, FRAMES);
            for (int i = 0; i < FRAMES * 2; i++) {
                float x = mix[i] * master;
                x = x > 1 ? 1 : x < -1 ? -1 : x;                // hard limit (sum of many voices)
                int v = (int) (x * 32767);
                out[i * 2] = (byte) v; out[i * 2 + 1] = (byte) (v >> 8);
            }
            line.write(out, 0, out.length);                    // blocks: paces the loop to the sound card
        }
    }

    /** Mixes all voices into 'mix' (stereo, 'frames' frames); finished one-shots are removed. Package-visible for tests. */
    static void mix(float[] mix, int frames) {
        java.util.Arrays.fill(mix, 0, frames * 2, 0);
        for (Voice v : VOICES) {
            if (v.stopped) { VOICES.remove(v); continue; }
            Sample s = v.s;
            int n = s.frames();
            if (n == 0) { v.stopped = true; continue; }
            double step = s.rate / RATE * Math.max(0.05f, Math.min(4f, v.pitch));
            float tv = Math.max(0, v.volume), tp = Math.max(-1, Math.min(1, v.pan));
            if (v.curVol < 0) { v.curVol = tv; v.curPan = tp; }
            float dv = (tv - v.curVol) / frames, dp = (tp - v.curPan) / frames;
            for (int i = 0; i < frames; i++) {
                if (v.pos >= n) {
                    if (v.loop) v.pos -= n * Math.floor(v.pos / n);
                    else { v.stopped = true; break; }
                }
                int i0 = (int) v.pos;
                float fr = (float) (v.pos - i0);
                int i1 = i0 + 1 >= n ? (v.loop ? 0 : i0) : i0 + 1;
                float l, r;
                if (s.channels == 1) { float a = s.data[i0], b = s.data[i1]; l = r = a + (b - a) * fr; }
                else { l = s.data[i0 * 2] + (s.data[i1 * 2] - s.data[i0 * 2]) * fr; r = s.data[i0 * 2 + 1] + (s.data[i1 * 2 + 1] - s.data[i0 * 2 + 1]) * fr; }
                // equal power pan
                double ang = (v.curPan + 1) * Math.PI / 4;
                float gl = (float) Math.cos(ang) * 1.4142f, gr = (float) Math.sin(ang) * 1.4142f;
                mix[i * 2] += l * v.curVol * Math.min(1, gl);
                mix[i * 2 + 1] += r * v.curVol * Math.min(1, gr);
                v.curVol += dv; v.curPan += dp;
                v.pos += step;
            }
        }
    }
}
