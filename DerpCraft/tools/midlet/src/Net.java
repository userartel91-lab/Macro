import java.io.InputStream;
import java.util.Hashtable;
import java.util.Vector;
import javax.microedition.io.Connector;
import javax.microedition.io.HttpConnection;
import javax.microedition.io.SocketConnection;
import java.io.DataInputStream;
import java.io.OutputStream;
import javax.microedition.lcdui.Image;

/**
 * Talks to the game over Wi-Fi (HTTP GET to the game's remote server). One background thread does every request,
 * so the screen never freezes. Held keys are renewed while the finger / key is down; the game lets go of them by
 * itself if the phone goes silent.
 */
public class Net implements Runnable {
    static final int SIMPLE = 0, STATUS = 1, PING = 2, KEYS = 3, OPTIONS = 4, WORLDS = 5, LOG = 6, FRAME = 7, OPEN = 8;

    static class Req {
        String path; int kind;
        Req(String p, int k) { path = p; kind = k; }
    }

    final DerpRemote app;
    String host = "", pin = "";
    int port = 25580;
    boolean png;

    private final Vector queue = new Vector();
    private final Vector held = new Vector();     // binding ids held down
    private Thread thread;
    private volatile boolean running;

    /** Look speed while a look button is held: degrees per step. */
    volatile float lookX, lookY;
    /** Viewfinder: keep asking for frames. */
    volatile boolean autoFrame;
    volatile int frameW = 356, frameH = 200;
    volatile boolean wantStatus, wantLog;
    long chatN = -1;

    long lastOk, lastHold, lastLook, lastStatus, lastLog;
    String lastError = "";

    Net(DerpRemote app) { this.app = app; }

    void start() {
        if (running) return;
        running = true;
        thread = new Thread(this);
        thread.start();
    }

    void stop() {
        running = false;
        synchronized (this) { notifyAll(); }
    }

    String base() { return "http://" + host + ":" + port; }

    // ------------------------------------------------------------------------------------------ requests
    void send(String path, int kind) {
        synchronized (this) {
            if (kind != SIMPLE) {
                // one waiting request of a kind is enough (status, frame...)
                for (int i = 0; i < queue.size(); i++) if (((Req) queue.elementAt(i)).kind == kind && kind != OPEN) return;
            }
            queue.addElement(new Req(path, kind));
            notifyAll();
        }
    }

    /** Sent before anything else that is waiting (key presses must not wait behind a frame). */
    void sendFirst(String path) {
        synchronized (this) {
            queue.insertElementAt(new Req(path, SIMPLE), 0);
            notifyAll();
        }
    }

    void tap(String id) { sendFirst("/key?k=" + enc(id)); }

    void tapShift(String id) { sendFirst("/key?k=" + enc(id) + "&s=1"); }

    void cmd(String c) { sendFirst("/cmd?c=" + enc(c)); }

    void raw(int code) { sendFirst("/raw?c=" + code); }

    void hold(String id, boolean down) {
        synchronized (this) {
            if (down) { if (!held.contains(id)) held.addElement(id); }
            else held.removeElement(id);
        }
        sendFirst("/key?k=" + enc(id) + "&a=" + (down ? "down" : "up"));
    }

    boolean isHeld(String id) { synchronized (this) { return held.contains(id); } }

    void releaseAll() {
        synchronized (this) { held.removeAllElements(); }
        lookX = lookY = 0;
        sendFirst("/release");
    }

    // ------------------------------------------------------------------------------------------ live viewfinder
    /** Live stream over a socket (port + 1): the game pushes JPEG frames, one per credit byte we send. */
    volatile boolean streaming;
    /** The phone cannot open sockets (or the stream port is blocked): the viewfinder asks frame by frame over HTTP. */
    volatile boolean streamFailed;
    private SocketConnection streamConn;
    /** Frames per second x 10, shown in the status line. */
    volatile int fps10;
    private long fpsStart;
    private int fpsFrames;

    void startStream() {
        if (streaming) return;
        streaming = true;
        new Thread(new Runnable() { public void run() { streamLoop(); } }).start();
    }

    void stopStream() {
        streaming = false;
        SocketConnection c = streamConn;
        if (c != null) try { c.close(); } catch (Exception e) { }
    }

    /** Counts shown frames (stream or single). */
    void countFrame() {
        long now = System.currentTimeMillis();
        if (fpsStart == 0) fpsStart = now;
        fpsFrames++;
        if (now - fpsStart >= 1000) {
            fps10 = (int) (fpsFrames * 10000L / (now - fpsStart));
            fpsFrames = 0;
            fpsStart = now;
        }
    }

    private void streamLoop() {
        SocketConnection c = null;
        DataInputStream in = null;
        OutputStream out = null;
        try {
            c = (SocketConnection) Connector.open("socket://" + host + ":" + (port + 1), Connector.READ_WRITE, true);
            streamConn = c;
            try { c.setSocketOption(SocketConnection.DELAY, 0); } catch (Exception e) { }
            try { c.setSocketOption(SocketConnection.RCVBUF, 65536); } catch (Exception e) { }
            in = c.openDataInputStream();
            out = c.openOutputStream();
            out.write(("DCV1 " + pin + " " + frameW + " " + frameH + " " + streamQuality + "\n").getBytes());
            out.flush();
            StringBuffer line = new StringBuffer();
            int ch;
            while ((ch = in.read()) >= 0 && ch != '\n') line.append((char) ch);
            if (!line.toString().startsWith("OK")) throw new Exception(line.length() == 0 ? "нет ответа" : line.toString());
            // two frames in flight: the game sends the next one while this one is being decoded
            out.write('+');
            out.write('+');
            out.flush();
            fpsStart = 0;
            fpsFrames = 0;
            while (streaming) {
                int len = in.readInt();
                if (len <= 0 || len > 4000000) throw new Exception("bad frame");
                byte[] b = new byte[len];
                in.readFully(b);
                out.write('+');
                out.flush();
                lastOk = System.currentTimeMillis();
                Image img = Image.createImage(b, 0, len);
                countFrame();
                app.pad.onFrame(img);
            }
        } catch (Throwable t) {
            if (streaming) {
                streamFailed = true;
                autoFrame = true;      // keep the viewfinder going the slow way
                String m = t.getMessage() == null ? t.toString() : t.getMessage();
                app.pad.onMessage("! Поток не открылся (" + m + "), кадры по одному");
            }
        } finally {
            streaming = false;
            streamConn = null;
            try { if (in != null) in.close(); } catch (Exception e) { }
            try { if (out != null) out.close(); } catch (Exception e) { }
            try { if (c != null) c.close(); } catch (Exception e) { }
            app.pad.streamEnded();
        }
    }

    /** JPEG quality for the stream (lower = smaller frames, decodes a bit faster). */
    volatile int streamQuality = 45;

    // ------------------------------------------------------------------------------------------ thread
    public void run() {
        while (running) {
            Req r = null;
            synchronized (this) {
                if (queue.isEmpty()) { try { wait(60); } catch (InterruptedException e) { } }
                if (!queue.isEmpty()) { r = (Req) queue.elementAt(0); queue.removeElementAt(0); }
            }
            if (!running) break;
            long now = System.currentTimeMillis();
            if (r == null) {
                // nothing waiting: renew holds, turn the view, ask for the state
                String ids = null;
                synchronized (this) {
                    if (!held.isEmpty() && now - lastHold > 350) {
                        StringBuffer sb = new StringBuffer();
                        for (int i = 0; i < held.size(); i++) { if (i > 0) sb.append(','); sb.append(enc((String) held.elementAt(i))); }
                        ids = sb.toString();
                    }
                }
                if (ids != null) { lastHold = now; r = new Req("/key?a=down&k=" + ids, SIMPLE); }
                else if ((lookX != 0 || lookY != 0) && now - lastLook > 70) { lastLook = now; r = new Req("/look?dx=" + lookX + "&dy=" + lookY, SIMPLE); }
                else if (autoFrame) r = new Req("/frame?w=" + frameW + "&h=" + frameH + (png ? "&f=png" : "&q=50"), FRAME);
                else if (wantLog && now - lastLog > 2000) { lastLog = now; r = new Req("/log?since=" + Math.max(0, chatN), LOG); }
                else if (wantStatus && now - lastStatus > 1500) { lastStatus = now; r = new Req("/status", STATUS); }
            }
            if (r != null) perform(r);
        }
    }

    private void perform(Req r) {
        String url = base() + r.path + (r.path.indexOf('?') < 0 ? "?" : "&") + "p=" + enc(pin);
        HttpConnection c = null;
        InputStream in = null;
        try {
            c = (HttpConnection) Connector.open(url, Connector.READ, true);
            c.setRequestMethod(HttpConnection.GET);
            int code = c.getResponseCode();
            if (code != HttpConnection.HTTP_OK) throw new Exception("HTTP " + code);
            in = c.openInputStream();
            byte[] data = readAll(in, (int) c.getLength());
            lastOk = System.currentTimeMillis();
            lastError = "";
            if (r.kind == FRAME) {
                String type = c.getType();
                if (type != null && type.startsWith("text")) throw new Exception(firstLine(utf8(data)));
                Image img = Image.createImage(data, 0, data.length);
                countFrame();
                app.pad.onFrame(img);
                return;
            }
            String text = utf8(data);
            if (!text.startsWith("OK")) {
                String e = firstLine(text);
                if (e.startsWith("ERR ")) e = e.substring(4);
                app.pad.onMessage("! " + e);
                if (r.kind == PING) app.onPing(false, e);
                return;
            }
            Vector lines = split(text, '\n');
            lines.removeElementAt(0);
            switch (r.kind) {
                case PING: {
                    boolean ok = false;
                    for (int i = 0; i < lines.size(); i++) if ("auth=ok".equals(lines.elementAt(i))) ok = true;
                    app.onPing(ok, ok ? "" : "PIN не подходит");
                    break;
                }
                case STATUS: app.pad.onStatus(keyValues(lines)); break;
                case LOG: {
                    for (int i = 0; i < lines.size(); i++) {
                        String l = (String) lines.elementAt(i);
                        if (l.startsWith("n=")) {
                            chatN = Long.parseLong(l.substring(2));   // a new world starts counting again: just follow it
                        } else if (l.startsWith("> ")) app.pad.onChat(l.substring(2), false);
                    }
                    break;
                }
                case KEYS: case OPTIONS: case WORLDS: {
                    Vector rows = new Vector();
                    for (int i = 0; i < lines.size(); i++) {
                        String l = (String) lines.elementAt(i);
                        if (l.length() > 0) rows.addElement(split(l, '\t'));
                    }
                    app.pad.onRows(r.kind, rows);
                    break;
                }
                default: {
                    for (int i = 0; i < lines.size(); i++) {
                        String l = (String) lines.elementAt(i);
                        if (l.startsWith("> ")) app.pad.onChat(l.substring(2), true);
                        else if (r.path.startsWith("/option") && l.indexOf('=') > 0) app.pad.onOptionValue(l);
                    }
                    if (r.kind == OPEN) app.pad.onMessage(r.path.startsWith("/open") ? "Мир открыт" : "Вышли в меню игры");
                }
            }
        } catch (Throwable t) {
            lastError = t.getMessage() == null ? t.toString() : t.getMessage();
            if (r.kind == PING) app.onPing(false, lastError);
            else app.pad.onMessage("! Нет связи: " + lastError);
            if (r.kind == FRAME) autoFrame = false;
            try { Thread.sleep(400); } catch (InterruptedException e) { }
        } finally {
            try { if (in != null) in.close(); } catch (Exception e) { }
            try { if (c != null) c.close(); } catch (Exception e) { }
        }
    }

    boolean online() { return System.currentTimeMillis() - lastOk < 4000; }

    // ------------------------------------------------------------------------------------------ helpers
    static byte[] readAll(InputStream in, int len) throws Exception {
        if (len > 0) {
            byte[] b = new byte[len];
            int off = 0;
            while (off < len) {
                int n = in.read(b, off, len - off);
                if (n < 0) break;
                off += n;
            }
            if (off == len) return b;
            byte[] c = new byte[off];
            System.arraycopy(b, 0, c, 0, off);
            return c;
        }
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[1024];
        int n;
        while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
        return bos.toByteArray();
    }

    static String utf8(byte[] b) {
        try { return new String(b, "UTF-8"); } catch (Exception e) { return new String(b); }
    }

    static String firstLine(String s) {
        int i = s.indexOf('\n');
        return i < 0 ? s : s.substring(0, i);
    }

    static Vector split(String s, char sep) {
        Vector v = new Vector();
        int start = 0;
        for (int i = 0; i <= s.length(); i++) {
            if (i == s.length() || s.charAt(i) == sep) {
                String part = s.substring(start, i);
                if (part.length() > 0 && part.charAt(part.length() - 1) == '\r') part = part.substring(0, part.length() - 1);
                v.addElement(part);
                start = i + 1;
            }
        }
        if (v.size() > 0 && ((String) v.lastElement()).length() == 0 && sep == '\n') v.removeElementAt(v.size() - 1);
        return v;
    }

    static Hashtable keyValues(Vector lines) {
        Hashtable h = new Hashtable();
        for (int i = 0; i < lines.size(); i++) {
            String l = (String) lines.elementAt(i);
            int e = l.indexOf('=');
            if (e > 0) h.put(l.substring(0, e), l.substring(e + 1));
        }
        return h;
    }

    /** URL encoding (UTF-8). */
    static String enc(String s) {
        StringBuffer sb = new StringBuffer();
        byte[] b;
        try { b = s.getBytes("UTF-8"); } catch (Exception e) { b = s.getBytes(); }
        String hex = "0123456789ABCDEF";
        for (int i = 0; i < b.length; i++) {
            int c = b[i] & 255;
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '-' || c == '_' || c == '.' || c == '~') sb.append((char) c);
            else { sb.append('%'); sb.append(hex.charAt(c >> 4)); sb.append(hex.charAt(c & 15)); }
        }
        return sb.toString();
    }
}
