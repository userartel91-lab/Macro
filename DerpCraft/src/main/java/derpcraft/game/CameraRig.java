package derpcraft.game;

import java.io.*;
import java.nio.file.*;
import java.util.*;

/**
 * Film camera: free camera (detached from the player), orbit around a point, keyframed camera paths on smooth
 * curves, lens (focal length / zoom, roll = horizon angle, anamorphic squeeze), focus (manual or autofocus with a
 * speed), and lens effects settings (depth of field, flares, motion blur, shake...). Updated every frame.
 */
public final class CameraRig {
    public enum Mode { PLAYER, FREE, ORBIT, PATH, FOLLOW, POV }

    public Mode mode = Mode.PLAYER;
    private Mode beforePath = Mode.PLAYER;

    // ---------------------------------------------------------------- detached camera
    public double x, y, z;
    public float yaw, pitch;
    private double vx, vy, vz;
    /** Free camera speed in blocks per second and how smoothly it starts/stops (0 = instant .. 0.95 = very soft). */
    public float speed = 5, smoothing = 0.7f;

    // ---------------------------------------------------------------- orbit
    public double ox, oy, oz;
    public float orbitRadius = 10, orbitAngle, orbitSpeed = 12, orbitHeight = 4;
    /** Orbit centre follows the player (Shift+O). */
    public boolean orbitPlayer;

    // ---------------------------------------------------------------- lens
    /** Focal length in mm on a 24 mm high sensor (0 = derived from the FOV option). */
    public float focal = 0, focalTarget = 0;
    /** Horizon angle (Dutch angle) in degrees. */
    public float roll, rollTarget;
    /** Anamorphic squeeze: 1 = spherical lens; 1.33..2 make oval bokeh and horizontal flares. */
    public float squeeze = 1;
    /** 0 off, 1 = 2.39:1, 2 = 2:1, 3 = 1.85:1, 4 = 4:3. */
    public int letterbox = 0;
    public static final String[] LETTERBOX = {"Off", "2.39:1", "2:1", "1.85:1", "4:3"};
    public static final float[] LETTERBOX_ASPECT = {0, 2.39f, 2f, 1.85f, 4f / 3f};
    public float distortion = 0, aberration = 0;
    /** Handheld camera shake amount (0..1) and speed. */
    public float shake = 0, shakeSpeed = 1;

    // ---------------------------------------------------------------- focus
    public boolean dof;
    public float aperture = 2.8f;
    /** 0 manual, 1 autofocus on the screen centre, 2 autofocus on the player. */
    public int afMode = 1;
    public static final String[] AF_MODES = {"Manual", "Center", "Player", "NPC"};
    /** FOLLOW / POV: the NPC (id) and where it is this frame {x, y, z, eyeY, yaw, pitch} (set by the session). */
    public int followId = -1;
    public double[] followTarget;
    /** FOLLOW: keep this distance (0 = the camera only turns, WASD still moves it). */
    public float followDistance = 0;
    public float afSpeed = 4, manualFocus = 8, bokehScale = 1;
    /** Current (smoothed) focus distance in blocks (= metres). */
    public float focus = 8;

    // ---------------------------------------------------------------- effects
    public float flare = 0, flareLength = 0.7f, ghosts = 0, motionBlur = 0;
    public int flareColor = 0;
    public static final String[] FLARE_COLORS = {"Blue", "Amber", "Neutral", "Purple"};
    public static final float[][] FLARE_TINTS = {{0.3f, 0.55f, 1.0f}, {1.0f, 0.62f, 0.25f}, {0.85f, 0.85f, 0.85f}, {0.7f, 0.4f, 1.0f}};
    public boolean grid, showInfo = true;

    // ---------------------------------------------------------------- selfie camera
    /** Selfie camera: distance in front of the face and shift to the player's right (blocks = metres), /selfie. */
    public float selfieDistance = 1.0f, selfieSide = 0.35f;
    public static final float SELFIE_DISTANCE = 1.0f, SELFIE_SIDE = 0.35f;

    // ---------------------------------------------------------------- path
    /** Keyframes: x, y, z, yaw, pitch, roll, focal, focus. */
    public final List<double[]> keys = new ArrayList<>();
    public float pathDuration = 10;
    public boolean pathLoop, showPath = true, constantSpeed = true;
    /** 0 linear, 1 smooth (ease in and out), 2 ease in, 3 ease out. */
    public int easing = 1;
    public static final String[] EASINGS = {"Linear", "Smooth", "Ease In", "Ease Out"};
    private double playTime;
    private double[] arcTable;

    private double time;

    /** Effects that need the HDR scene buffer. */
    public boolean lensEffectsActive() {
        return dof || flare > 0 || ghosts > 0 || aberration > 0 || distortion != 0 || letterbox > 0 || motionBlur > 0;
    }

    public boolean detached() { return mode != Mode.PLAYER; }

    /** Tripod mode (P): the free / orbit camera stays where it is and the keyboard and mouse control the player. */
    public boolean playerControl;

    /** Whether keyboard and mouse currently drive the player (always during path playback, so you can act on camera). */
    public boolean controlsPlayer() { return mode == Mode.PLAYER || mode == Mode.PATH || playerControl; }

    public boolean playing() { return mode == Mode.PATH; }

    // ================================================================ modes
    /** Free camera starting from the current view. */
    public void startFree(double cx, double cy, double cz, float cyaw, float cpitch) {
        x = cx; y = cy; z = cz; yaw = cyaw; pitch = cpitch;
        vx = vy = vz = 0;
        mode = Mode.FREE;
        playerControl = false;
    }

    /** Orbit around (px,py,pz) starting from the current camera position. */
    public void startOrbit(double px, double py, double pz, double cx, double cy, double cz) {
        ox = px; oy = py; oz = pz;
        double dx = cx - px, dz = cz - pz;
        orbitRadius = (float) Math.max(2, Math.sqrt(dx * dx + dz * dz));
        orbitHeight = (float) (cy - py);
        orbitAngle = (float) Math.toDegrees(Math.atan2(dz, dx));
        mode = Mode.ORBIT;
    }

    public void toPlayer() { mode = Mode.PLAYER; playerControl = false; }

    public void addKey(double cx, double cy, double cz, float cyaw, float cpitch, float defaultFocal) {
        // keep yaw continuous with the previous key so paths turn the short way
        if (!keys.isEmpty()) {
            double prev = keys.get(keys.size() - 1)[3];
            while (cyaw - prev > 180) cyaw -= 360;
            while (cyaw - prev < -180) cyaw += 360;
        }
        keys.add(new double[]{cx, cy, cz, cyaw, cpitch, roll, focal > 0 ? focal : defaultFocal, dof && afMode == 0 ? manualFocus : focus});
        arcTable = null;
    }

    public void removeLastKey() { if (!keys.isEmpty()) keys.remove(keys.size() - 1); arcTable = null; }

    public void clearKeys() { keys.clear(); arcTable = null; if (mode == Mode.PATH) mode = beforePath; }

    public boolean play() {
        if (keys.size() < 2) return false;
        if (mode != Mode.PATH) beforePath = mode;
        mode = Mode.PATH;
        playTime = 0;
        arcTable = null;
        return true;
    }

    public void stop() { if (mode == Mode.PATH) mode = beforePath == Mode.PATH ? Mode.PLAYER : beforePath; }

    public float progress() { return (float) Math.min(1, playTime / Math.max(0.1, pathDuration)); }

    // ================================================================ per frame
    /**
     * Advances the camera. Movement input: forward/strafe/up in -1..1, 'fast' = sprint key.
     * Returns nothing; read x,y,z,yaw,pitch (detached modes) and roll/focal afterwards.
     */
    public void update(float dt, float forward, float strafe, float up, boolean fast) {
        time += dt;
        updateLens(dt);
        switch (mode) {
            case FREE -> freeMove(dt, forward, strafe, up, fast);
            case FOLLOW -> {
                freeMove(dt, forward, strafe, up, fast);
                if (followTarget != null) {
                    double tx = followTarget[0], ty = followTarget[3] - 0.2, tz = followTarget[2];
                    if (followDistance > 0) {
                        double dx = x - tx, dz = z - tz, d = Math.sqrt(dx * dx + dz * dz) + 1e-6;
                        double k = 1 - Math.exp(-dt * 3);
                        x += (tx + dx / d * followDistance - x) * k;
                        z += (tz + dz / d * followDistance - z) * k;
                    }
                    double dx = tx - x, dy = ty - y, dz = tz - z;
                    float wy = (float) Math.toDegrees(Math.atan2(-dx, dz)), wp = (float) -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
                    double k = smoothing <= 0.01f ? 1 : 1 - Math.exp(-dt * (2.5 / smoothing));
                    float dyaw = wy - yaw;
                    while (dyaw > 180) dyaw -= 360;
                    while (dyaw < -180) dyaw += 360;
                    yaw += dyaw * (float) k;
                    pitch += (wp - pitch) * (float) k;
                }
            }
            case POV -> {
                if (followTarget != null) {
                    x = followTarget[0]; y = followTarget[3]; z = followTarget[2];
                    double k = smoothing <= 0.01f ? 1 : 1 - Math.exp(-dt * (6 / smoothing));
                    float dyaw = (float) followTarget[4] - yaw;
                    while (dyaw > 180) dyaw -= 360;
                    while (dyaw < -180) dyaw += 360;
                    yaw += dyaw * (float) k;
                    pitch += ((float) followTarget[5] - pitch) * (float) k;
                }
            }
            case ORBIT -> {
                orbitRadius = Math.max(1, orbitRadius - forward * dt * 6);
                orbitHeight += up * dt * 4;
                orbitSpeed += strafe * dt * 20;
                orbitAngle += orbitSpeed * dt;
                double a = Math.toRadians(orbitAngle);
                x = ox + Math.cos(a) * orbitRadius;
                z = oz + Math.sin(a) * orbitRadius;
                y = oy + orbitHeight;
                lookAt(ox, oy, oz);
            }
            case PATH -> {
                playTime += dt;
                double t = playTime / Math.max(0.1, pathDuration);
                if (t >= 1) {
                    if (pathLoop) { playTime = 0; t = 0; }
                    else { t = 1; mode = beforePath == Mode.PATH ? Mode.PLAYER : beforePath; }
                }
                double[] s = samplePath(ease(t));
                x = s[0]; y = s[1]; z = s[2]; yaw = (float) s[3]; pitch = (float) s[4];
                roll = rollTarget = (float) s[5];
                focal = focalTarget = (float) s[6];
                if (dof && afMode == 0) manualFocus = (float) s[7];
            }
            default -> {}
        }
    }

    /** Roll and zoom ease towards their targets. Also runs while the world is paused (the camera panel's changes show). */
    public void updateLens(float dt) {
        roll += (rollTarget - roll) * (1 - (float) Math.exp(-dt * 8));
        if (focalTarget > 0) focal = focal <= 0 ? focalTarget : focal + (focalTarget - focal) * (1 - (float) Math.exp(-dt * 6));
        else focal = 0;
    }

    private void freeMove(float dt, float forward, float strafe, float up, boolean fast) {
        float yr = (float) Math.toRadians(yaw);
        double sp = speed * (fast ? 3 : 1);
        double tx = (-Math.sin(yr) * forward + Math.cos(yr) * strafe) * sp;
        double tz = (Math.cos(yr) * forward + Math.sin(yr) * strafe) * sp;
        double ty = up * sp;
        double k = smoothing <= 0.01f ? 1 : 1 - Math.exp(-dt * (1.2 / smoothing));
        vx += (tx - vx) * k; vy += (ty - vy) * k; vz += (tz - vz) * k;
        x += vx * dt; y += vy * dt; z += vz * dt;
    }

    /** Camera follows an NPC (keeps it in frame) or looks through its eyes. */
    public void follow(int npcId, boolean pov) {
        if (mode == Mode.PATH) stop();
        followId = npcId;
        playerControl = false;
        mode = pov ? Mode.POV : Mode.FOLLOW;
    }

    private void lookAt(double tx, double ty, double tz) {
        double dx = tx - x, dy = ty - y, dz = tz - z;
        yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        pitch = (float) -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
    }

    /** Mouse look for detached modes (orbit ignores it). */
    public void look(float dYaw, float dPitch) {
        if (mode != Mode.FREE) return;
        yaw += dYaw;
        pitch = Math.max(-90, Math.min(90, pitch + dPitch));
    }

    /** Smooth handheld shake offsets {yaw, pitch, roll} in degrees. */
    public float[] shakeOffsets() {
        if (shake <= 0) return new float[3];
        double t = time * shakeSpeed;
        return new float[]{(float) (noise(t * 0.9, 1) * shake * 1.6), (float) (noise(t * 1.1, 2) * shake * 1.1), (float) (noise(t * 0.7, 3) * shake * 0.8)};
    }

    private static double noise(double t, int seed) {
        // sum of sines with incommensurate frequencies: smooth and non repeating enough for camera shake
        return Math.sin(t * 1.7 + seed * 12.9) * 0.5 + Math.sin(t * 3.1 + seed * 7.3) * 0.3 + Math.sin(t * 5.9 + seed * 3.1) * 0.2;
    }

    /** Smoothed autofocus towards 'target' metres. */
    public void updateFocus(float dt, float target) {
        float t = afMode == 0 ? manualFocus : target;
        // focus pulls happen in 1/distance space like a real lens ring
        float inv = 1f / Math.max(0.2f, focus), invT = 1f / Math.max(0.2f, t);
        inv += (invT - inv) * (1 - (float) Math.exp(-dt * afSpeed));
        focus = 1f / inv;
    }

    // ================================================================ curves
    private double ease(double t) {
        return switch (easing) {
            case 1 -> t * t * (3 - 2 * t);
            case 2 -> t * t;
            case 3 -> 1 - (1 - t) * (1 - t);
            default -> t;
        };
    }

    /** Catmull-Rom spline through all keyframes; t in 0..1 (arc-length parameterized when constantSpeed). */
    public double[] samplePath(double t) {
        int n = keys.size();
        if (n == 0) return new double[8];
        if (n == 1) return keys.get(0).clone();
        double u = constantSpeed ? arcToParam(t) : t * (n - 1);
        return splineAt(u);
    }

    private double[] splineAt(double u) {
        int n = keys.size();
        int i = (int) Math.floor(u);
        if (i >= n - 1) { i = n - 2; }
        if (i < 0) i = 0;
        double f = Math.max(0, Math.min(1, u - i));
        double[] p0 = keys.get(Math.max(0, i - 1)), p1 = keys.get(i), p2 = keys.get(i + 1), p3 = keys.get(Math.min(n - 1, i + 2));
        double[] out = new double[8];
        for (int c = 0; c < 8; c++) out[c] = catmull(p0[c], p1[c], p2[c], p3[c], f);
        return out;
    }

    private static double catmull(double p0, double p1, double p2, double p3, double t) {
        double t2 = t * t, t3 = t2 * t;
        return 0.5 * ((2 * p1) + (-p0 + p2) * t + (2 * p0 - 5 * p1 + 4 * p2 - p3) * t2 + (-p0 + 3 * p1 - 3 * p2 + p3) * t3);
    }

    private double arcToParam(double t) {
        int n = keys.size();
        int steps = (n - 1) * 64;
        if (arcTable == null || arcTable.length != steps + 1) {
            arcTable = new double[steps + 1];
            double[] prev = splineAt(0);
            for (int i = 1; i <= steps; i++) {
                double[] p = splineAt(i / 64.0);
                double dx = p[0] - prev[0], dy = p[1] - prev[1], dz = p[2] - prev[2];
                arcTable[i] = arcTable[i - 1] + Math.sqrt(dx * dx + dy * dy + dz * dz) + 1e-6;
                prev = p;
            }
        }
        double target = t * arcTable[steps];
        int lo = 0, hi = steps;
        while (hi - lo > 1) { int mid = (lo + hi) / 2; if (arcTable[mid] < target) lo = mid; else hi = mid; }
        double seg = arcTable[hi] - arcTable[lo];
        double f = seg <= 0 ? 0 : (target - arcTable[lo]) / seg;
        return (lo + f) / 64.0;
    }

    // ================================================================ persistence
    public void saveSettings(Path file) {
        Properties p = new Properties();
        Object[][] kv = {{"speed", speed}, {"smoothing", smoothing}, {"orbitSpeed", orbitSpeed}, {"squeeze", squeeze}, {"letterbox", letterbox},
                {"distortion", distortion}, {"aberration", aberration}, {"shake", shake}, {"shakeSpeed", shakeSpeed}, {"dof", dof},
                {"aperture", aperture}, {"afMode", afMode}, {"afSpeed", afSpeed}, {"manualFocus", manualFocus}, {"bokehScale", bokehScale},
                {"flare", flare}, {"flareLength", flareLength}, {"flareColor", flareColor}, {"ghosts", ghosts}, {"motionBlur", motionBlur}, {"grid", grid}, {"showInfo", showInfo},
                {"selfieDistance", selfieDistance}, {"selfieSide", selfieSide}, {"pathDuration", pathDuration}, {"pathLoop", pathLoop}, {"showPath", showPath}, {"constantSpeed", constantSpeed}, {"easing", easing}};
        for (Object[] e : kv) p.setProperty((String) e[0], String.valueOf(e[1]));
        try (Writer w = Files.newBufferedWriter(file)) { p.store(w, "DerpCraft camera"); } catch (IOException ignored) {}
    }

    public void loadSettings(Path file) {
        if (!Files.exists(file)) return;
        Properties p = new Properties();
        try (Reader r = Files.newBufferedReader(file)) { p.load(r); } catch (IOException e) { return; }
        speed = f(p, "speed", speed); smoothing = f(p, "smoothing", smoothing); orbitSpeed = f(p, "orbitSpeed", orbitSpeed);
        squeeze = f(p, "squeeze", squeeze); letterbox = Math.floorMod((int) f(p, "letterbox", letterbox), LETTERBOX.length);
        distortion = f(p, "distortion", distortion); aberration = f(p, "aberration", aberration);
        shake = f(p, "shake", shake); shakeSpeed = f(p, "shakeSpeed", shakeSpeed); dof = b(p, "dof", dof);
        aperture = f(p, "aperture", aperture); afMode = Math.floorMod((int) f(p, "afMode", afMode), AF_MODES.length); afSpeed = f(p, "afSpeed", afSpeed);
        manualFocus = f(p, "manualFocus", manualFocus); bokehScale = f(p, "bokehScale", bokehScale);
        flare = f(p, "flare", flare); flareLength = f(p, "flareLength", flareLength); flareColor = Math.floorMod((int) f(p, "flareColor", flareColor), FLARE_COLORS.length); ghosts = f(p, "ghosts", ghosts); motionBlur = f(p, "motionBlur", motionBlur);
        grid = b(p, "grid", grid); showInfo = b(p, "showInfo", showInfo);
        selfieDistance = Math.max(0.4f, Math.min(3f, f(p, "selfieDistance", selfieDistance)));
        selfieSide = Math.max(-1f, Math.min(1f, f(p, "selfieSide", selfieSide)));
        pathDuration = f(p, "pathDuration", pathDuration); pathLoop = b(p, "pathLoop", pathLoop); showPath = b(p, "showPath", showPath);
        constantSpeed = b(p, "constantSpeed", constantSpeed); easing = Math.floorMod((int) f(p, "easing", easing), EASINGS.length);
    }

    private static float f(Properties p, String k, float d) { try { return Float.parseFloat(p.getProperty(k, "" + d)); } catch (Exception e) { return d; } }
    private static boolean b(Properties p, String k, boolean d) { return Boolean.parseBoolean(p.getProperty(k, "" + d)); }

    public void savePath(Path file) {
        StringBuilder sb = new StringBuilder("# x y z yaw pitch roll focal focus\n");
        for (double[] k : keys) {
            for (int i = 0; i < 8; i++) sb.append(i > 0 ? " " : "").append(String.format(Locale.ROOT, "%.4f", k[i]));
            sb.append('\n');
        }
        try { derpcraft.util.SafeFiles.writeString(file, sb); } catch (IOException ignored) {}
    }

    public void loadPath(Path file) {
        keys.clear();
        arcTable = null;
        if (!Files.exists(file)) return;
        try {
            for (String line : Files.readAllLines(file)) {
                if (line.isBlank() || line.startsWith("#")) continue;
                String[] s = line.trim().split("\\s+");
                if (s.length < 8) continue;
                double[] k = new double[8];
                for (int i = 0; i < 8; i++) k[i] = Double.parseDouble(s[i]);
                keys.add(k);
            }
        } catch (Exception ignored) {}
    }
}
