package derpcraft.remote;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import derpcraft.DerpCraft;
import derpcraft.game.Keys;
import derpcraft.game.Session;
import derpcraft.world.WorldInfo;

import java.io.IOException;
import java.io.OutputStream;
import java.io.Reader;
import java.io.Writer;
import java.net.*;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/**
 * Wi-Fi remote control: a tiny HTTP server on the home network, used by the DerpCraft Remote MIDlet on a phone
 * (or anything that can open a URL: a browser, curl, a script). Every request runs on the game's main thread
 * between frames, so it can do whatever a key, a mouse click or a chat command can do.
 *
 * <p>Settings live in game/remote.txt (enabled, port, pin). Every request except /ping needs {@code p=<pin>}.
 * Answers are plain UTF-8 text: the first line is {@code OK} or {@code ERR <reason>}, then {@code key=value}
 * lines or tab-separated rows. The full list of requests is in REMOTE.md.
 */
public final class RemoteServer {
    public static final int DEFAULT_PORT = 25580;
    /** A key held from the phone lets go by itself this long after the phone's last "down" (lost Wi-Fi = no stuck keys). */
    public static final long HOLD_MS = 1200;

    private final DerpCraft game;
    private final Path file;
    private final ConcurrentLinkedQueue<Runnable> tasks = new ConcurrentLinkedQueue<>();
    private HttpServer http;
    private ExecutorService pool;

    public boolean enabled = true;
    public int port = DEFAULT_PORT;
    public String pin = "";
    /** Last phone that talked to the game, and when (shown by /remote). */
    public volatile String lastClient = "";
    public volatile long lastSeen;
    public volatile String error = "";

    public RemoteServer(DerpCraft game) {
        this.game = game;
        this.file = game.gameDir.resolve("remote.txt");
        load();
    }

    // ------------------------------------------------------------------------------------------ settings
    private void load() {
        Properties p = new Properties();
        if (Files.exists(file)) try (Reader r = Files.newBufferedReader(file)) { p.load(r); } catch (IOException ignored) {}
        enabled = Boolean.parseBoolean(p.getProperty("enabled", "true").trim());
        try { port = Integer.parseInt(p.getProperty("port", "" + DEFAULT_PORT).trim()); } catch (NumberFormatException e) { port = DEFAULT_PORT; }
        if (port < 1024 || port > 65535) port = DEFAULT_PORT;
        pin = p.getProperty("pin", "").trim();
        if (!pin.matches("\\d{4,8}")) { pin = String.format("%04d", new java.security.SecureRandom().nextInt(10000)); }
        save();
    }

    public void save() {
        Properties p = new Properties();
        p.setProperty("enabled", "" + enabled);
        p.setProperty("port", "" + port);
        p.setProperty("pin", pin);
        try (Writer w = Files.newBufferedWriter(file)) { p.store(w, "DerpCraft Wi-Fi remote (MIDlet). pin = code the phone must send"); } catch (IOException ignored) {}
    }

    // ------------------------------------------------------------------------------------------ server
    public synchronized void start() {
        if (!enabled || http != null) return;
        try {
            http = HttpServer.create(new InetSocketAddress(port), 16);
            pool = Executors.newFixedThreadPool(3, r -> { Thread t = new Thread(r, "DerpCraft-Remote"); t.setDaemon(true); return t; });
            http.setExecutor(pool);
            http.createContext("/", this::handle);
            http.start();
            viewfinder.start(port + 1);
            error = "";
            System.out.println("Wi-Fi remote: " + addressText() + "  PIN " + pin);
        } catch (IOException e) {
            http = null;
            error = "port " + port + " is busy (" + e.getMessage() + ")";
            System.err.println("Wi-Fi remote could not start: " + error);
        }
    }

    public synchronized void stop() {
        if (http != null) { http.stop(0); http = null; }
        viewfinder.stop();
        if (pool != null) { pool.shutdownNow(); pool = null; }
        Keys.HELD.clear();
    }

    public boolean running() { return http != null; }

    /** The game's addresses on the local network (what to type into the phone). */
    public static List<String> lanAddresses() {
        List<String> out = new ArrayList<>();
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback() || ni.isVirtual()) continue;
                String n = (ni.getDisplayName() + " " + ni.getName()).toLowerCase(Locale.ROOT);
                if (n.contains("virtualbox") || n.contains("vmware") || n.contains("hyper-v") || n.contains("vethernet") || n.contains("docker")) continue;
                for (InetAddress a : Collections.list(ni.getInetAddresses()))
                    if (a instanceof Inet4Address && !a.isLoopbackAddress() && !a.isLinkLocalAddress()) out.add(a.getHostAddress());
            }
        } catch (SocketException ignored) {}
        // home Wi-Fi addresses first
        out.sort(Comparator.comparingInt(a -> a.startsWith("192.168.") ? 0 : a.startsWith("10.") ? 1 : 2));
        return out;
    }

    private String addrCache;
    private long addrTime;

    /** "192.168.1.5:25580" (cached for a few seconds: the title screen asks every frame). */
    public String addressText() {
        long now = System.currentTimeMillis();
        if (addrCache == null || now - addrTime > 5000) { addrCache = makeAddressText(); addrTime = now; }
        return addrCache;
    }

    private String makeAddressText() {
        List<String> a = lanAddresses();
        if (a.isEmpty()) return "no network (connect the PC to Wi-Fi / router), port " + port;
        StringBuilder sb = new StringBuilder();
        for (String s : a) { if (sb.length() > 0) sb.append(" or "); sb.append(s).append(':').append(port); }
        return sb.toString();
    }

    /** One line for the chat / title: where the phone should connect. */
    public String info() {
        if (!enabled) return "Wi-Fi remote is OFF (/remote on)";
        if (!running()) return "§cWi-Fi remote: " + (error.isEmpty() ? "not running" : error) + " (/remote port <n>)";
        return "§bWi-Fi remote: §f" + addressText() + "  §bPIN §f" + pin + (viewfinder.error.isEmpty() ? "" : "  §c(" + viewfinder.error + ")");
    }

    // ------------------------------------------------------------------------------------------ main thread
    /** Called by the game loop every frame: runs the phone's requests and lets go of keys the phone stopped holding. */
    public void pump() {
        Runnable r;
        int n = 0;
        while (n++ < 64 && (r = tasks.poll()) != null) {
            try { r.run(); } catch (Throwable t) { t.printStackTrace(); }
        }
        if (!Keys.HELD.isEmpty()) {
            long now = System.currentTimeMillis();
            Keys.HELD.entrySet().removeIf(e -> e.getValue() < now);
        }
    }

    /** Runs code on the main thread and waits for its answer. */
    private String onMain(Callable<String> c, int seconds) throws Exception {
        CompletableFuture<String> f = new CompletableFuture<>();
        tasks.add(() -> {
            try { f.complete(c.call()); }
            catch (IllegalArgumentException e) { f.complete("ERR " + (e.getMessage() == null ? "bad request" : e.getMessage())); }
            catch (Throwable t) { f.complete("ERR " + t); t.printStackTrace(); }
        });
        try { return f.get(seconds, TimeUnit.SECONDS); }
        catch (TimeoutException e) { return "ERR game is busy (loading?)"; }
    }

    // ------------------------------------------------------------------------------------------ viewfinder
    /** Frames for the phone: single shots (/frame) and the live stream on port + 1. */
    public final Viewfinder viewfinder = new Viewfinder(this);

    // ------------------------------------------------------------------------------------------ HTTP
    private void handle(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath();
        Map<String, String> q = query(ex.getRequestURI().getRawQuery());
        String answer;
        try {
            lastClient = ex.getRemoteAddress().getAddress().getHostAddress();
            lastSeen = System.currentTimeMillis();
            if (path.equals("/") || path.equals("/ping")) {
                answer = "OK\ngame=DerpCraft " + DerpCraft.VERSION + "\napi=1\nauth=" + (q.containsKey("p") && pinOk(lastClient, q.get("p")) ? "ok" : "pin");
            } else if (!pinOk(lastClient, q.get("p"))) {
                answer = locked(lastClient) ? "ERR too many wrong PINs, try again in a minute" : "ERR wrong PIN";
            } else if (path.equals("/frame")) {
                int w = clamp(intArg(q, "w", 432), 32, 1920), h = clamp(intArg(q, "h", 243), 32, 1080);
                boolean png = "png".equalsIgnoreCase(q.get("f"));
                float quality = clamp(intArg(q, "q", 55), 10, 95) / 100f;
                byte[] img = viewfinder.shot(w, h, png, quality);
                ex.getResponseHeaders().set("Content-Type", png ? "image/png" : "image/jpeg");
                ex.getResponseHeaders().set("Cache-Control", "no-cache");
                ex.sendResponseHeaders(200, img.length);
                try (OutputStream os = ex.getResponseBody()) { os.write(img); }
                return;
            } else {
                answer = onMain(() -> request(path, q), path.equals("/open") || path.equals("/leave") ? 90 : 5);
            }
        } catch (TimeoutException e) {
            answer = "ERR no frame (is the game minimized?)";
        } catch (Exception e) {
            answer = "ERR " + e;
        }
        byte[] b = answer.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
        ex.getResponseHeaders().set("Cache-Control", "no-cache");
        ex.sendResponseHeaders(200, b.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(b); }
    }

    // ------------------------------------------------------------------------------------------ PIN guessing
    /** Wrong PINs per address: after 8 in a row the address is refused for a minute (a 4-digit PIN is quick to guess). */
    private static final int MAX_WRONG = 8;
    private static final long LOCK_MS = 60_000;
    private final ConcurrentHashMap<String, long[]> wrong = new ConcurrentHashMap<>();   // address -> {count, locked until}

    boolean locked(String addr) {
        long[] w = wrong.get(addr);
        return w != null && w[1] > System.currentTimeMillis();
    }

    /** Checks a PIN sent by 'addr' (HTTP and viewfinder stream). */
    boolean pinOk(String addr, String given) {
        if (locked(addr)) return false;
        if (given == null) return false;                 // no PIN sent: not a guess
        if (pin.equals(given)) { wrong.remove(addr); return true; }
        long[] w = wrong.computeIfAbsent(addr, k -> new long[2]);
        synchronized (w) {
            if (++w[0] >= MAX_WRONG) { w[0] = 0; w[1] = System.currentTimeMillis() + LOCK_MS; }
        }
        return false;
    }

    private static Map<String, String> query(String raw) {
        Map<String, String> m = new HashMap<>();
        if (raw == null) return m;
        for (String kv : raw.split("&")) {
            if (kv.isEmpty()) continue;
            int i = kv.indexOf('=');
            String k = i < 0 ? kv : kv.substring(0, i), v = i < 0 ? "" : kv.substring(i + 1);
            m.put(URLDecoder.decode(k, StandardCharsets.UTF_8), URLDecoder.decode(v, StandardCharsets.UTF_8));
        }
        return m;
    }

    private static int clamp(int v, int lo, int hi) { return Math.max(lo, Math.min(hi, v)); }

    private static int intArg(Map<String, String> q, String k, int d) {
        try { return Integer.parseInt(q.getOrDefault(k, "" + d).trim()); } catch (NumberFormatException e) { return d; }
    }

    private static float floatArg(Map<String, String> q, String k, float d) {
        try { return Float.parseFloat(q.getOrDefault(k, "" + d).trim().replace(',', '.')); } catch (NumberFormatException e) { return d; }
    }

    private Session needWorld() {
        if (game.session == null) throw new IllegalArgumentException("no world open (open one: Worlds)");
        return game.session;
    }

    private static String plain(String s) { return s.replaceAll("§.", "").replace('\n', ' '); }

    // ------------------------------------------------------------------------------------------ requests (main thread)
    private String request(String path, Map<String, String> q) {
        switch (path) {
            case "/status": return status();
            case "/keys": {
                StringBuilder sb = new StringBuilder("OK\n");
                for (Keys.Binding b : Keys.ALL) sb.append(b.id).append('\t').append(b.category).append('\t').append(b.label).append('\t').append(b.keyName()).append('\n');
                return sb.toString();
            }
            case "/key": {
                // k = binding id (Options -> Controls, see /keys); for down / up several ids separated by commas
                String a = q.getOrDefault("a", "tap");
                long before = chatCount();
                for (String id : q.getOrDefault("k", "").split(",")) {
                    if (id.isBlank()) continue;
                    Keys.Binding b = Keys.byId(id.trim());
                    if (b == null) throw new IllegalArgumentException("unknown key " + id);
                    switch (a) {
                        case "down" -> game.remoteHold(b, true);
                        case "up" -> game.remoteHold(b, false);
                        default -> game.remoteTap(b, "1".equals(q.get("s")));
                    }
                }
                return "OK\n" + chatSince(before, 6);
            }
            case "/release": { Keys.HELD.clear(); return "OK"; }
            case "/raw": {
                long before = chatCount();
                game.remoteRawKey(intArg(q, "c", 256), intArg(q, "m", 0));
                return "OK\n" + chatSince(before, 6);
            }
            case "/type": { game.remoteType(q.getOrDefault("t", "")); return "OK"; }
            case "/click": {
                long before = chatCount();
                game.remoteClick(clamp01(floatArg(q, "x", 0.5f)), clamp01(floatArg(q, "y", 0.5f)), clamp(intArg(q, "b", 0), 0, 7));
                return "OK\n" + chatSince(before, 6);
            }
            case "/look": { game.remoteLook(floatArg(q, "dx", 0), floatArg(q, "dy", 0)); return "OK"; }
            case "/scroll": { game.remoteScroll(floatArg(q, "d", 1)); return "OK"; }
            case "/cmd": {
                Session s = needWorld();
                String c = q.getOrDefault("c", "").trim();
                if (c.isEmpty()) throw new IllegalArgumentException("empty command");
                long before = s.chatCount;
                s.submitChat(c.startsWith("/") ? c : "/" + c);
                return "OK\n" + chatSince(before, 20);
            }
            case "/say": {
                Session s = needWorld();
                long before = s.chatCount;
                s.submitChat(q.getOrDefault("t", ""));
                return "OK\n" + chatSince(before, 20);
            }
            case "/log": {
                long since = Long.parseLong(q.getOrDefault("since", "0").trim());
                Session s = game.session;
                if (s == null) return "OK\nn=0\n";
                return "OK\nn=" + s.chatCount + "\n" + chatSince(since, 40);
            }
            case "/worlds": {
                List<WorldInfo> list = new ArrayList<>();
                try (DirectoryStream<Path> ds = Files.newDirectoryStream(game.savesDir)) {
                    for (Path p : ds) if (Files.exists(p.resolve("level.properties"))) try { list.add(WorldInfo.load(p)); } catch (IOException ignored) {}
                } catch (IOException ignored) {}
                list.sort((a, b) -> Long.compare(b.lastPlayed, a.lastPlayed));
                StringBuilder sb = new StringBuilder("OK\n");
                for (WorldInfo w : list) sb.append(w.dir.getFileName()).append('\t').append(plain(w.name)).append('\t').append(w.gameMode).append('\n');
                return sb.toString();
            }
            case "/open": {
                String w = q.getOrDefault("w", "");
                if (w.isEmpty() || w.contains("/") || w.contains("\\") || w.contains("..")) throw new IllegalArgumentException("bad world name");
                Path dir = game.savesDir.resolve(w);
                if (!Files.exists(dir.resolve("level.properties"))) throw new IllegalArgumentException("no world " + w);
                try { game.openWorld(WorldInfo.load(dir)); } catch (IOException e) { throw new IllegalArgumentException("cannot read " + w); }
                return game.session != null ? "OK\nopened=" + w : "ERR world failed to load";
            }
            case "/leave": { game.closeWorld(); return "OK"; }
            case "/options": {
                StringBuilder sb = new StringBuilder("OK\n");
                for (RemoteOptions.Opt o : RemoteOptions.ALL)
                    sb.append(o.name()).append('\t').append(o.type()).append('\t').append(RemoteOptions.get(game, o)).append('\t')
                            .append(fmt(o.min())).append('\t').append(fmt(o.max())).append('\t').append(fmt(o.step())).append('\t').append(o.label()).append('\n');
                return sb.toString();
            }
            case "/option": {
                RemoteOptions.Opt o = RemoteOptions.byName(q.getOrDefault("n", ""));
                if (o == null) throw new IllegalArgumentException("unknown option " + q.get("n"));
                return "OK\n" + o.name() + "=" + RemoteOptions.set(game, o, q.getOrDefault("v", ""));
            }
            default: throw new IllegalArgumentException("unknown request " + path + " (see REMOTE.md)");
        }
    }

    private static float clamp01(float v) { return Math.max(0, Math.min(1, v)); }

    private static String fmt(float v) { return v == (int) v ? String.valueOf((int) v) : String.valueOf(v); }

    private long chatCount() { return game.session == null ? 0 : game.session.chatCount; }

    /** Chat lines added after line number 'since' (at most 'max', the newest), one per line, without colour codes. */
    private String chatSince(long since, int max) {
        Session s = game.session;
        if (s == null) return "";
        List<String> h = s.chatHistory();
        long first = s.chatCount - h.size();          // number of the line before h.get(0)
        long from = Math.max(since, Math.max(first, s.chatCount - max));
        StringBuilder sb = new StringBuilder();
        for (long n = from; n < s.chatCount; n++) {
            int i = (int) (n - first);
            if (i >= 0 && i < h.size()) sb.append("> ").append(plain(h.get(i))).append('\n');
        }
        return sb.toString();
    }

    private String status() {
        StringBuilder sb = new StringBuilder("OK\n");
        Session s = game.session;
        kv(sb, "world", s == null ? "" : plain(s.world.info.name));
        kv(sb, "screen", game.screen == null ? "" : game.screen.getClass().getSimpleName());
        kv(sb, "fps", game.fps);
        if (s == null) return sb.toString();
        long t = s.world.info.time;
        kv(sb, "mode", s.gameMode);
        kv(sb, "time", Math.floorMod(t, 24000L));
        kv(sb, "day", Math.floorDiv(t, 24000L) + 1);
        kv(sb, "cycle", s.world.info.daylightCycle ? 1 : 0);
        kv(sb, "x", String.format(Locale.ROOT, "%.1f", s.player.x));
        kv(sb, "y", String.format(Locale.ROOT, "%.1f", s.player.y));
        kv(sb, "z", String.format(Locale.ROOT, "%.1f", s.player.z));
        kv(sb, "yaw", Math.round(s.player.yaw));
        kv(sb, "pitch", Math.round(s.player.pitch));
        kv(sb, "hp", Math.round(s.stats.health));
        kv(sb, "food", s.stats.food);
        kv(sb, "fly", s.player.flying ? 1 : 0);
        kv(sb, "flyspeed", String.format(Locale.ROOT, "%.2f", s.player.flySpeedMultiplier));
        kv(sb, "slot", s.selected() + 1);
        var held = s.heldStack();
        kv(sb, "held", held == null ? "" : plain(held.item.displayName) + (held.count > 1 ? " x" + held.count : ""));
        var cam = s.camera();
        kv(sb, "cam", cam.mode());
        kv(sb, "tripod", cam.tripod() ? 1 : 0);
        kv(sb, "keys", cam.keyframeCount());
        kv(sb, "path", cam.pathPlaying() ? 1 : 0);
        kv(sb, "progress", Math.round(cam.pathProgress() * 100));
        kv(sb, "focal", Math.round(cam.focalLength()));
        kv(sb, "roll", Math.round(cam.roll()));
        kv(sb, "dof", cam.depthOfField() ? 1 : 0);
        kv(sb, "focus", String.format(Locale.ROOT, "%.1f", cam.focusDistance()));
        kv(sb, "view", s.view);
        kv(sb, "hud", s.hideHud ? 0 : 1);
        kv(sb, "smooth", s.cinematic ? 1 : 0);
        kv(sb, "selfie", s.selfie ? (s.selfieFree ? 2 : 1) : 0);
        kv(sb, "rec", s.npcs.recording() ? 1 : 0);
        kv(sb, "scene", s.npcs.currentScene);
        kv(sb, "playing", s.npcs.scenePlaying(s.npcs.currentScene) ? 1 : 0);
        kv(sb, "chat", s.chatCount);
        return sb.toString();
    }

    private static void kv(StringBuilder sb, String k, Object v) { sb.append(k).append('=').append(v).append('\n'); }
}
