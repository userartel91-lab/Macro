package derpcraft;

import derpcraft.block.Blocks;
import derpcraft.game.Keys;
import derpcraft.game.Session;
import derpcraft.game.Settings;
import derpcraft.gui.Screen;
import derpcraft.gui.Screens;
import derpcraft.mod.ModLoader;
import derpcraft.render.*;
import derpcraft.util.Mat4;
import derpcraft.resource.ResourcePack;
import derpcraft.util.Workers;
import derpcraft.world.WorldInfo;
import org.lwjgl.glfw.GLFWErrorCallback;
import org.lwjgl.glfw.GLFWVidMode;
import org.lwjgl.opengl.GL;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.*;
import java.text.SimpleDateFormat;
import java.util.*;

import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL45C.*;

/** Main class: window, main loop, input routing, screens and the current world session. */
public final class DerpCraft {
    public static final String VERSION = "1.14";

    public long window;
    public int fbWidth = 1280, fbHeight = 720, winWidth = 1280, winHeight = 720;
    public final Settings settings;
    public final Assets assets = new Assets();
    public final Workers workers = new Workers("DerpCraft-Worker", Workers.defaultThreads());
    public final ModLoader mods = new ModLoader();
    public GuiRenderer gui;
    public EntityRenderer entities;
    public derpcraft.render.post.PostProcessor post;
    public ShadowRenderer shadows;
    /** Current colour grade (saved to game/grading/current.txt). */
    public derpcraft.render.post.Grading grading = new derpcraft.render.post.Grading();
    public String gradingName = "Vanilla";
    public final Path gradingDir, lutsDir;
    /** Shader presets (game/shaders/*.txt) and the look transition between two presets (Left Alt + ,). */
    public final Path shadersDir;
    public final derpcraft.game.LookTransition transition = new derpcraft.game.LookTransition();
    public SkyRenderer sky;
    public Shader simpleShader;
    public Screen screen;
    public Session session;
    /** Wi-Fi remote control (HTTP API for the phone MIDlet), see derpcraft.remote.RemoteServer. */
    public derpcraft.remote.RemoteServer remote;
    /** Built-in screen recording (ffmpeg + NVENC), Ctrl + F2. */
    public derpcraft.record.ScreenRecorder recorder;
    private final derpcraft.record.FrameCapture capture = new derpcraft.record.FrameCapture();
    private boolean recPaused;
    private boolean remoteAnnounced;
    public final Path gameDir, savesDir, packsDir, modsDir, screenshotsDir;
    public int fps;
    public String glRenderer = "", glVersion = "";
    private double mouseX, mouseY, lastMouseX, lastMouseY, pendingDX, pendingDY;
    private boolean grabbed, firstMouse = true, running = true, screenshotRequested, ignoreNextChar;
    private long lastClick, prevClick;
    private int[] windowedPos = {100, 100, 1280, 720};
    private final boolean[] buttonsDown = new boolean[8];

    public DerpCraft(Path dir) {
        gameDir = dir;
        derpcraft.api.ModContext.gameDir = dir;
        savesDir = dir.resolve("saves");
        packsDir = dir.resolve("resourcepacks");
        modsDir = dir.resolve("mods");
        screenshotsDir = dir.resolve("screenshots");
        gradingDir = dir.resolve("grading");
        lutsDir = dir.resolve("luts");
        shadersDir = dir.resolve("shaders");
        assets.reflectionsFile = dir.resolve("reflections.txt");
        settings =new Settings(dir.resolve("options.txt"));
        recorder = new derpcraft.record.ScreenRecorder(dir);
    }

    public static void main(String[] args) {
        Path dir = Paths.get(System.getProperty("derpcraft.dir", ".")).toAbsolutePath().normalize();
        DerpCraft game = new DerpCraft(dir);
        try {
            game.run();
        } catch (Throwable t) {
            t.printStackTrace();
            try {
                StringWriter sw = new StringWriter();
                t.printStackTrace(new PrintWriter(sw));
                Files.createDirectories(dir.resolve("crash-reports"));
                Files.writeString(dir.resolve("crash-reports").resolve("crash-" + new SimpleDateFormat("yyyy-MM-dd_HH.mm.ss").format(new Date()) + ".txt"),
                        "DerpCraft " + VERSION + " crashed\n\nGL: " + game.glRenderer + " / " + game.glVersion + "\n\n" + sw);
            } catch (Exception ignored) {}
            System.exit(1);
        }
    }

    public void run() throws Exception {
        init();
        loop();
        shutdown();
    }

    // ------------------------------------------------------------------------------------------ init
    private void init() throws Exception {
        for (Path p : new Path[]{savesDir, packsDir, modsDir, screenshotsDir, gradingDir, lutsDir}) Files.createDirectories(p);
        if (Files.exists(gradingDir.resolve("current.txt"))) {
            grading = derpcraft.render.post.Grading.load(gradingDir.resolve("current.txt"));
            String n = Files.readAllLines(gradingDir.resolve("current.txt")).stream().filter(l -> l.startsWith("# name=")).map(l -> l.substring(7)).findFirst().orElse("Custom");
            gradingName = n;
        }
        settings.load();
        Blocks.registerDefaults();
        derpcraft.npc.SceneBlock.register();
        derpcraft.model.Model3DBlock.register();
        derpcraft.camera.CameraBlock.register();
        derpcraft.model.Models.init(gameDir);
        derpcraft.vehicle.Transports.init(gameDir);
        derpcraft.gun.Guns.init(gameDir);
        derpcraft.audio.Sfx.init(gameDir);
        derpcraft.model.Models.vanillaImages = p -> assets.resources.image(p);
        derpcraft.npc.Poses.load(gameDir);
        mods.load(modsDir);
        derpcraft.item.Items.registerDefaults();
        derpcraft.vehicle.Vehicles.registerItems();
        derpcraft.gun.Guns.registerItems();
        derpcraft.gun.Guns.rebuildIcons = this::reloadAssets;
        derpcraft.item.Recipes.registerDefaults();
        mods.initItems();
        Blocks.freeze();

        GLFWErrorCallback.createPrint(System.err).set();
        if (!glfwInit()) throw new IllegalStateException("Unable to initialize GLFW");
        glfwDefaultWindowHints();
        glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);
        glfwWindowHint(GLFW_RESIZABLE, GLFW_TRUE);
        glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE);
        glfwWindowHint(GLFW_OPENGL_FORWARD_COMPAT, GLFW_TRUE);
        int[][] versions = {{4, 6}, {4, 5}, {4, 3}};
        for (int[] v : versions) {
            glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, v[0]);
            glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, v[1]);
            window = glfwCreateWindow(winWidth, winHeight, "DerpCraft " + VERSION, 0, 0);
            Keys.window = window;
            if (window != 0) break;
        }
        if (window == 0) throw new IllegalStateException("Could not create an OpenGL 4.3+ window. Please update your graphics driver.");
        GLFWVidMode vm = glfwGetVideoMode(glfwGetPrimaryMonitor());
        if (vm != null) glfwSetWindowPos(window, (vm.width() - winWidth) / 2, (vm.height() - winHeight) / 2);
        glfwMakeContextCurrent(window);
        GL.createCapabilities();
        glRenderer = glGetString(GL_RENDERER);
        glVersion = "OpenGL " + glGetString(GL_VERSION);
        System.out.println(glRenderer + " / " + glVersion);
        applyVsync();
        installCallbacks();
        int[] w = new int[1], h = new int[1];
        glfwGetFramebufferSize(window, w, h);
        fbWidth = w[0]; fbHeight = h[0];

        simpleShader = new Shader("simple");
        gui = new GuiRenderer();
        gui.assets = assets;
        entities = new EntityRenderer(assets);
        post = new derpcraft.render.post.PostProcessor();
        shadows = new ShadowRenderer();
        entities.dummyGui = gui;
        sky = new SkyRenderer(simpleShader);
        reloadAssets();
        glfwShowWindow(window);
        if (settings.fullscreen) { settings.fullscreen = false; toggleFullscreen(); }
        setScreen(new Screens.Title(this));
        remote = new derpcraft.remote.RemoteServer(this);
        remote.start();
        recorder.warmUp(settings.recEncoder);
        if (!mods.errors.isEmpty()) for (String e : mods.errors) System.err.println("Mod error: " + e);
    }

    private void installCallbacks() {
        glfwSetFramebufferSizeCallback(window, (win, w, h) -> {
            if (w <= 0 || h <= 0) return;
            fbWidth = w; fbHeight = h;
            relayout();
        });
        glfwSetWindowSizeCallback(window, (win, w, h) -> { if (w > 0 && h > 0) { winWidth = w; winHeight = h; } });
        glfwSetKeyCallback(window, (win, key, scancode, action, mods) -> {
            if (action == GLFW_RELEASE) { onKeyRelease(key); return; }
            onKey(key, mods, action == GLFW_REPEAT);
        });
        glfwSetCharCallback(window, (win, cp) -> {
            if (ignoreNextChar) { ignoreNextChar = false; return; }
            if (screen != null) screen.charTyped(cp);
        });
        glfwSetMouseButtonCallback(window, (win, button, action, mods) -> {
            if (button < 0 || button >= 8) return;
            buttonsDown[button] = action == GLFW_PRESS;
            if (action == GLFW_PRESS) {
                prevClick = lastClick;
                lastClick = System.currentTimeMillis();
                if (screen != null) screen.mouseClicked(mouseGuiX(), mouseGuiY(), button);
                else if (session != null) {
                    if (!grabbed) grab(true);
                    else if (!derpcraft.api.EventBus.has(derpcraft.api.Events.MouseClick.class)
                            || !derpcraft.api.EventBus.post(session, new derpcraft.api.Events.MouseClick(button)).isCancelled()) gameInput(derpcraft.game.Keys.MOUSE + button, mods, false);
                }
            } else if (screen != null) screen.mouseReleased(mouseGuiX(), mouseGuiY(), button);
        });
        glfwSetCursorPosCallback(window, (win, x, y) -> {
            if (grabbed) {
                if (firstMouse) { firstMouse = false; }
                else { pendingDX += x - lastMouseX; pendingDY += y - lastMouseY; }
                lastMouseX = x; lastMouseY = y;
            }
            mouseX = x; mouseY = y;
            if (screen != null && buttonsDown[0]) screen.mouseDragged(mouseGuiX(), mouseGuiY(), 0);
        });
        glfwSetScrollCallback(window, (win, dx, dy) -> {
            if (screen != null) screen.scrolled(dy);
            else if (session != null) session.scroll(dy);
        });
    }

    public int mouseGuiX() { return (int) (mouseX * fbWidth / Math.max(1, winWidth) / gui.scale); }
    public int mouseGuiY() { return (int) (mouseY * fbHeight / Math.max(1, winHeight) / gui.scale); }
    public boolean isDoubleClick() { return lastClick - prevClick < 350; }

    private void onKey(int key, int mods, boolean repeat) {
        if (screen instanceof Screens.Controls c && c.capturing()) { if (!repeat) screen.keyPressed(key, mods); return; }
        if (Keys.FULLSCREEN.matches(key) && !repeat) { toggleFullscreen(); return; }
        if (Keys.SCREEN_RECORD.matches(key) && !repeat) { toggleRecording(); return; }
        if (Keys.SCREENSHOT.matches(key) && !repeat) { screenshotRequested = true; return; }
        if (screen != null) { screen.keyPressed(key, mods); return; }
        if (session == null) return;
        if (!repeat && derpcraft.api.EventBus.has(derpcraft.api.Events.KeyPress.class)
                && derpcraft.api.EventBus.post(session, new derpcraft.api.Events.KeyPress(key, mods)).isCancelled()) return;
        if (key == GLFW_KEY_ESCAPE) { if (!repeat) setScreen(new Screens.Pause(this)); return; }
        gameInput(key, mods, repeat);
    }

    /** F3 is held: combos like F3 + D (clear chat); F3 alone toggles the debug screen when released, like vanilla. */
    private boolean f3Down, f3Combo;

    private void onKeyRelease(int key) {
        Keys.SWALLOWED.remove(key);
        if (screen instanceof Screens.Controls c && c.capturing()) { c.keyReleased(key); return; }
        if (Keys.DEBUG.matches(key) && f3Down) {
            f3Down = false;
            if (!f3Combo && screen == null && session != null) session.showDebug = !session.showDebug;
        }
    }

    /** A key or mouse button (Keys.MOUSE + button) pressed in the game, matched against the key bindings. */
    private void gameInput(int code, int mods, boolean repeat) {
        Session s = session;
        if (s == null) return;
        if (Keys.DEBUG.matches(code)) { if (!repeat) { f3Down = true; f3Combo = false; } return; }
        if (f3Down && Keys.DEBUG.down(window)) {
            if (code == GLFW_KEY_D) {
                Keys.SWALLOWED.add(code);       // D stays a part of the combo until let go: the player does not strafe right
                if (!repeat) { s.clearChat(); s.edit.hideFrame(); f3Combo = true; }
                return;
            }
        } else f3Down = false;
        // while recording: hold the gesture / face key + a number
        if (s.npcs.recording() && code >= GLFW_KEY_0 && code <= GLFW_KEY_9) {
            boolean gesture = Keys.GESTURE.down(window), face = Keys.FACE.down(window) || (Keys.FACE.key == 342 && (mods & GLFW_MOD_ALT) != 0);
            if (gesture || face) {
                if (repeat) return;
                int n = code - GLFW_KEY_0;
                if (face) s.npcs.recordEmotion = n == 0 ? 0 : Math.min(derpcraft.npc.Emotions.NAMES.length - 1, n - 1);
                else {
                    String g = n == 0 ? "" : derpcraft.npc.Poses.SLOTS[n];
                    s.npcs.recordPose = g == null || g.equals(s.npcs.recordPose) ? "" : g;
                }
                return;
            }
        }
        if (!repeat && Keys.TRANSITION.matches(code)) { s.chat(transition.toggle(this)); return; }
        if (!repeat) {
            if (Keys.RECORD_CANCEL.matches(code) && s.npcs.recording()) { s.cancelRecordingKey(); return; }
            if (Keys.RECORD.matches(code)) { s.recordKey(); return; }
            if (Keys.SCENE_PLAY.matches(code)) { s.playSceneKey(); return; }
            if (Keys.NPC_HUB.matches(code)) {
                if (s.npcs.recording()) s.stopRecordingKey();
                else setScreen(new derpcraft.gui.NpcHub(this, s, null));
                return;
            }
        }
        if (!repeat && Keys.GUN_EDITOR.matches(code)) { setScreen(new derpcraft.gui.GunEditor(this, s)); return; }
        if (!repeat && Keys.VEHICLE_EDITOR.matches(code)) { setScreen(new derpcraft.gui.VehicleEditor(this, s)); return; }
        if (!repeat && Keys.VEHICLE_EXPLODE.matches(code) && s.vehicles.explodeKey()) return;
        if (!repeat && Keys.VEHICLE_LIGHTS.matches(code) && s.vehicles.riding()) { s.vehicles.toggleLights(); return; }
        if (!repeat && Keys.VEHICLE_CAMERA.matches(code)) { s.vehicleCamera((mods & GLFW_MOD_SHIFT) != 0); return; }
        if (!repeat && Keys.FISTS.matches(code)) { s.fists.toggle(); return; }
        if (!repeat && Keys.BACK_GUN.matches(code)) { s.toggleBackGun(); return; }
        if (!repeat && Keys.BLEED.matches(code)) { s.toggleBleeding(); return; }
        if (!repeat && Keys.EQUIPMENT.matches(code) && !s.spectator()) { ignoreNextChar = true; setScreen(new derpcraft.gui.Containers.Equipment(this, s)); return; }
        if (!repeat && Keys.SKINS.matches(code)) { setScreen(new derpcraft.gui.SkinScreen(this, s)); return; }
        if (!repeat && Keys.RELOAD.matches(code) && s.guns.holding()) { s.guns.reloadKey(); return; }
        if (!repeat && Keys.MELEE.matches(code) && s.guns.holding() && !s.cam.detached() && !s.stats.dead) { s.guns.meleeKey(); return; }
        if (s.cameraKey(code, mods, repeat)) return;
        if (!repeat) {
            // Alt + 1..4: body posture (before the hotbar: a plain number gives way to the Alt combo)
            if (Keys.POSE_STAND.matches(code)) { s.setStance(derpcraft.game.Stance.STAND); return; }
            if (Keys.POSE_SIT.matches(code)) { s.setStance(derpcraft.game.Stance.SIT); return; }
            if (Keys.POSE_LIE.matches(code)) { s.setStance(derpcraft.game.Stance.LIE); return; }
            if (Keys.POSE_CRAWL.matches(code)) { s.setStance(derpcraft.game.Stance.CRAWL); return; }
        }
        if (Keys.DROP.matches(code)) { s.dropHeld((mods & GLFW_MOD_CONTROL) != 0); return; }
        for (int i = 0; i < 9; i++) if (Keys.HOTBAR[i].matches(code)) { s.select(i); return; }
        if (repeat) return;
        if (Keys.ATTACK.matches(code)) s.mouseButton(0);
        else if (Keys.USE.matches(code)) s.mouseButton(1);
        else if (Keys.PICK.matches(code)) s.mouseButton(2);
        else if (Keys.INVENTORY.matches(code)) { ignoreNextChar = code < Keys.MOUSE; s.openInventory(); }
        else if (Keys.CHAT.matches(code)) { ignoreNextChar = code < Keys.MOUSE; setScreen(new Screens.ChatScreen(this, "")); }
        else if (Keys.COMMAND.matches(code)) { ignoreNextChar = code < Keys.MOUSE; setScreen(new Screens.ChatScreen(this, "/")); }
        else if (Keys.HIDE_HUD.matches(code)) s.hideHud = !s.hideHud;
        else if (Keys.SPECTATOR.matches(code)) { s.toggleSpectator(); s.chat("Game mode: " + s.gameMode); }
        else if (Keys.PERSPECTIVE.matches(code)) s.cycleView();
        else if (Keys.GRADING.matches(code)) setScreen(new derpcraft.gui.Graphics.GradingEditor(this, null));
        else if (Keys.SHADERS.matches(code)) setScreen(new derpcraft.gui.Graphics.Shaders(this, null));
        else if (Keys.MODEL_HUB.matches(code)) setScreen(new derpcraft.gui.ModelHub(this, null));
        else if (Keys.SMOOTH_CAM.matches(code)) s.cinematic = !s.cinematic;
        else if (Keys.FULLSCREEN.matches(code)) toggleFullscreen();
        else if (Keys.SCREENSHOT.matches(code)) screenshotRequested = true;
        for (var k : derpcraft.api.ModContext.KEYS) if (Keys.mod(k.modId(), k.description(), k.glfwKey()).matches(code)) {
            try { k.action().onKey(s); } catch (Throwable t) { t.printStackTrace(); }
        }
    }

    // ------------------------------------------------------------------------------------------ Wi-Fi remote (main thread only)
    private static int modBit(int glfwKey) {
        return switch (glfwKey) {
            case GLFW_KEY_LEFT_SHIFT, GLFW_KEY_RIGHT_SHIFT -> GLFW_MOD_SHIFT;
            case GLFW_KEY_LEFT_CONTROL, GLFW_KEY_RIGHT_CONTROL -> GLFW_MOD_CONTROL;
            case GLFW_KEY_LEFT_ALT, GLFW_KEY_RIGHT_ALT -> GLFW_MOD_ALT;
            case GLFW_KEY_LEFT_SUPER, GLFW_KEY_RIGHT_SUPER -> GLFW_MOD_SUPER;
            default -> 0;
        };
    }

    /** The phone pressed and released a key binding, exactly as if its key was tapped on the keyboard (combos included). */
    public void remoteTap(Keys.Binding b, boolean shift) {
        int code = b.key;
        if (code == Keys.NONE) throw new IllegalArgumentException("no key set for \"" + b.label + "\" (Options -> Controls)");
        int mods = shift ? GLFW_MOD_SHIFT : 0;
        if (b.mod != Keys.NONE) { Keys.VIRTUAL_MODS.add(b.mod); mods |= modBit(b.mod); }
        if (shift) Keys.VIRTUAL_MODS.add(GLFW_KEY_LEFT_SHIFT);
        try {
            if (code >= Keys.MOUSE) { if (screen == null && session != null) gameInput(code, mods, false); }
            else { onKey(code, mods, false); onKeyRelease(code); }
        } finally { Keys.VIRTUAL_MODS.clear(); }
    }

    /** The phone holds a binding down (movement, zoom, roll, attack...) or lets it go. A hold ends by itself unless renewed. */
    public void remoteHold(Keys.Binding b, boolean down) {
        if (!down) { Keys.HELD.remove(b); return; }
        boolean was = Keys.HELD.containsKey(b);
        Keys.HELD.put(b, System.currentTimeMillis() + derpcraft.remote.RemoteServer.HOLD_MS);
        // mouse actions (break, place) also act at the moment they are pressed, like a real click
        if (!was && screen == null && session != null && b.key >= Keys.MOUSE) gameInput(b.key, 0, false);
    }

    /** A raw GLFW key (Esc, Enter, arrows...) pressed and released: works in menus too. */
    public void remoteRawKey(int code, int mods) {
        onKey(code, mods, false);
        onKeyRelease(code);
    }

    /** Text typed into the open menu (chat line, search fields, names...). */
    public void remoteType(String text) {
        if (screen == null) return;
        text.codePoints().forEach(cp -> screen.charTyped(cp));
    }

    /** A click at a point of the picture (0..1 across and down): in a menu it clicks there; in the game it is a mouse click. */
    public void remoteClick(double nx, double ny, int button) {
        if (screen != null) {
            gui.begin(fbWidth, fbHeight, settings.guiScale);
            int gx = (int) (nx * gui.width), gy = (int) (ny * gui.height);
            screen.mouseClicked(gx, gy, button);
            if (screen != null) screen.mouseReleased(gx, gy, button);
        } else if (session != null) gameInput(Keys.MOUSE + button, 0, false);
    }

    /** Turns the view (degrees): the player, or the free / orbit camera when it is the camera that is driven. */
    public void remoteLook(float dYaw, float dPitch) {
        Session s = session;
        if (s == null || screen != null) return;
        if (!s.cam.controlsPlayer()) { s.cam.look(dYaw, dPitch); return; }
        s.player.yaw += dYaw;
        s.player.pitch = Math.max(-90, Math.min(90, s.player.pitch + dPitch));
    }

    /** Mouse wheel from the phone (hotbar, fly speed, menus). */
    public void remoteScroll(double amount) {
        if (screen != null) screen.scrolled(amount);
        else if (session != null) session.scroll(amount);
    }

    // ------------------------------------------------------------------------------------------ screens & state
    public void setScreen(Screen s) {
        if (session != null && derpcraft.api.EventBus.has(derpcraft.api.Events.ScreenOpen.class)
                && derpcraft.api.EventBus.post(session, new derpcraft.api.Events.ScreenOpen(s == null ? null : s.getClass().getSimpleName(), s)).isCancelled()) return;
        if (s == null && screen != null && session != null) session.mouseBlocked = true;
        if (session != null && (s == null) != (screen == null)) session.resetMouseSmoothing();
        screen = s;
        if (s != null) {
            gui.begin(fbWidth, fbHeight, settings.guiScale);
            s.resize(gui.width, gui.height);
        }
        grab(s == null && session != null);
    }

    private void grab(boolean g) {
        if (g == grabbed) return;
        grabbed = g;
        glfwSetInputMode(window, GLFW_CURSOR, g ? GLFW_CURSOR_DISABLED : GLFW_CURSOR_NORMAL);
        if (g && glfwRawMouseMotionSupported()) glfwSetInputMode(window, GLFW_RAW_MOUSE_MOTION, GLFW_TRUE);
        if (!g) glfwSetCursorPos(window, winWidth / 2.0, winHeight / 2.0);
        firstMouse = true;
        pendingDX = pendingDY = 0;
    }

    /** Takes a screenshot at the end of this frame (like F2). */
    public void requestScreenshot() { screenshotRequested = true; }

    public void relayout() {
        gui.begin(fbWidth, fbHeight, settings.guiScale);
        if (screen != null) screen.resize(gui.width, gui.height);
    }

    public void openWorld(WorldInfo info) {
        if (session != null) closeWorld();
        drawMessage("Loading world...");
        try {
            session = new Session(this, info);
            setScreen(null);
            // where the phone connects (once per game start, so it does not get in the way of filming)
            if (remote != null && !remoteAnnounced) { remoteAnnounced = true; session.chat(remote.info()); }
        } catch (Exception e) {
            e.printStackTrace();
            session = null;
            setScreen(new Screens.Title(this));
        }
    }

    public void closeWorld() {
        if (session == null) return;
        drawMessage("Saving world...");
        session.close();
        session = null;
        setScreen(new Screens.Title(this));
    }

    private void drawMessage(String msg) {
        glViewport(0, 0, fbWidth, fbHeight);
        glClearColor(0, 0, 0, 1);
        glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
        gui.begin(fbWidth, fbHeight, settings.guiScale);
        gui.background(0xFF404040);
        gui.centered(msg, gui.width / 2f, gui.height / 2f - 4, 0xFFFFFF);
        gui.end();
        glfwSwapBuffers(window);
    }

    public void reloadAssets() {
        List<ResourcePack> list = new ArrayList<>();
        if (!settings.resourcePack.isEmpty()) {
            Path p = Paths.get(settings.resourcePack);
            if (!p.isAbsolute()) p = packsDir.resolve(p);
            try { if (Files.exists(p)) list.add(ResourcePack.open(p)); }
            catch (Exception e) { System.err.println("Cannot open resource pack " + p + ": " + e); }
        }
        list.addAll(mods.modPacks);
        assets.resources.setPacks(list);
        assets.mipLevels = settings.mipLevels;
        assets.reload();
        gui.plainsGrass = assets.plainsGrass;
        gui.plainsFoliage = assets.plainsFoliage;
        if (session != null) session.renderer.setTextures(assets.tables, assets.grassMap, assets.foliageMap);
    }

    /** Rotating title screen panorama (only when the resource pack has gui/title/background/panorama_N.png). */
    public void drawPanorama(float t) {
        if (assets.panorama == null) return;
        Mat4 proj = new Mat4().perspective((float) Math.toRadians(120), fbWidth / (float) Math.max(1, fbHeight), 0.05f, 10f);
        Mat4 mvp = proj.rotate((float) Math.toRadians(Math.sin(t / 400f) * 25 + 20), 1, 0, 0).rotate((float) Math.toRadians(-t * 0.1f), 0, 1, 0);
        glDisable(GL_DEPTH_TEST);
        glDisable(GL_CULL_FACE);
        glDisable(GL_BLEND);
        ModelBatch mb = entities.mb;
        mb.begin(mvp, assets.blocks.texId, 0, 0, 0, 0, 0);
        mb.color(1, 1, 1, 1);
        // vanilla face order: 0 north(front), 1 east, 2 south, 3 west, 4 up, 5 down
        float[][] faces = {
                {-1, 1, -1, -1, -1, -1, 1, -1, -1, 1, 1, -1}, {1, 1, -1, 1, -1, -1, 1, -1, 1, 1, 1, 1},
                {1, 1, 1, 1, -1, 1, -1, -1, 1, -1, 1, 1}, {-1, 1, 1, -1, -1, 1, -1, -1, -1, -1, 1, -1},
                {-1, 1, 1, -1, 1, -1, 1, 1, -1, 1, 1, 1}, {-1, -1, -1, -1, -1, 1, 1, -1, 1, 1, -1, -1}};
        float[] uv = {0, 0, 0, 1, 1, 1, 1, 0};
        for (int i = 0; i < 6; i++) {
            mb.texture(assets.panorama[i]);
            mb.quad(new Mat4(), faces[i], uv);
        }
        mb.flush();
        glEnable(GL_BLEND);
    }

    public void applyVsync() { glfwSwapInterval(settings.vsync && !vsyncSuspended ? 1 : 0); }

    public void toggleFullscreen() {
        settings.fullscreen = !settings.fullscreen;
        long mon = glfwGetPrimaryMonitor();
        GLFWVidMode vm = glfwGetVideoMode(mon);
        if (settings.fullscreen && vm != null) {
            int[] x = new int[1], y = new int[1];
            glfwGetWindowPos(window, x, y);
            windowedPos = new int[]{x[0], y[0], winWidth, winHeight};
            glfwSetWindowMonitor(window, mon, 0, 0, vm.width(), vm.height(), vm.refreshRate());
        } else {
            glfwSetWindowMonitor(window, 0, windowedPos[0], windowedPos[1], windowedPos[2], windowedPos[3], 0);
        }
        applyVsync();
    }

    public void openPackFolder() { openFolder(packsDir); }

    public void openFolder(Path dir) {
        try {
            Files.createDirectories(dir);
            if (System.getProperty("os.name").toLowerCase().contains("win")) new ProcessBuilder("explorer", dir.toString()).start();
            else java.awt.Desktop.getDesktop().open(dir.toFile());
        } catch (Exception e) { System.err.println("Cannot open folder: " + e); }
    }

    // ------------------------------------------------------------------------------------------ screen recording
    /**
     * The one pause rule for every menu: the world stands still while a service menu is open (Esc, NPC Hub, camera panel,
     * timeline, shaders, grading, options...), see {@link Screen#pausesGame()}. Game menus (inventory, chests, chat...)
     * let it run. While paused nothing ticks and the frame between two ticks is frozen too, so the camera stays exactly
     * where it was (no jerk when a menu opens while running).
     */
    public boolean worldPaused() { return session != null && screen != null && screen.pausesGame(); }

    /** Whether the screen recording skips frames right now (a menu that is not filmed is open). */
    private boolean recordingHidden() { return settings.recPauseInMenus && screen != null && !screen.recorded(); }

    /** Smooth recording is on and running: the game is driven by the video clock (every frame = one video frame). */
    private boolean videoClock() { return recorder.recording() && settings.recSmooth && !recordingHidden() && !recorder.failed(); }

    public void toggleRecording() {
        if (recorder.recording()) stopRecording();
        else startRecording();
    }

    public void startRecording() {
        if (recorder.recording()) return;
        int[] size = derpcraft.record.ScreenRecorder.outputSize(fbWidth, fbHeight, settings.recHeight);
        String err = recorder.start(size[0], size[1], settings.recFps, settings.recBitrate, settings.recEncoder);
        if (err != null) {
            System.err.println("Screen recording: " + err);
            if (session != null) session.chat("§c" + err);
        }
        recordError = err;
    }

    /** Last reason a recording could not start (shown in the recording settings). */
    public String recordError;

    public void stopRecording() {
        if (!recorder.recording()) return;
        capture.poll(recorder, true);         // the frames still on their way from the GPU
        recorder.stop();
    }

    /** Hands the frame just rendered to the recorder. {@code smooth}: this frame was rendered on the video clock. */
    private void recordFrame(double realDt, boolean smooth) {
        for (String m; (m = recorder.pollMessage()) != null; ) if (session != null) session.chat(m); else System.out.println(m);
        if (!recorder.recording()) return;
        if (recorder.failed()) { stopRecording(); return; }
        if (recordingHidden()) {
            if (!recPaused) capture.poll(recorder, true);
            recPaused = true;
            return;
        }
        recPaused = false;
        capture.poll(recorder, false);
        int w = recorder.width(), h = recorder.height();
        if (smooth) {
            // every rendered frame is the next video frame; when the encoder is behind, wait for it rather than drop
            capture.grab(recorder, fbWidth, fbHeight, w, h, 1, true);
            recorder.frameLocked();
            return;
        }
        int n = recorder.framesDue();
        if (n > 0) {
            recorder.claim(n);
            capture.grab(recorder, fbWidth, fbHeight, w, h, n, false);
        }
        recorder.advance(realDt);
    }

    /** Smooth recording: how fast the game runs compared to real time (below 1 when the computer cannot keep up). */
    private double recSpeed = 1, speedReal, speedVideo;

    private void measureSpeed(boolean smooth, double realDt, double videoDt) {
        if (!smooth) { recSpeed = 1; speedReal = speedVideo = 0; return; }
        speedReal += realDt;
        speedVideo += videoDt;
        if (speedReal >= 1) { recSpeed = speedVideo / speedReal; speedReal = speedVideo = 0; }
    }

    /** Small REC mark and time code in the top left corner, drawn after the frame was grabbed (not in the video). */
    private void drawRecordingMark() {
        if (!recorder.recording()) return;
        int s = Math.max(1, gui.scale / 2);
        gui.begin(fbWidth, fbHeight, s);
        boolean paused = recPaused;
        String tc = derpcraft.record.ScreenRecorder.timecode(recorder.seconds());
        String label = paused ? "PAUSE " + tc : "REC " + tc;
        if (!paused && recSpeed < 0.97) label += String.format(Locale.ROOT, "  x%.2f", recSpeed);   // smooth: slower than real time
        int tw = gui.textWidth(label);
        gui.fill(1, 1, 14 + tw, 12, 0x90000000);
        boolean blink = paused || (System.currentTimeMillis() / 500) % 2 == 0;
        if (paused) { gui.fill(4, 3, 6, 10, 0xFFFFD040); gui.fill(7, 3, 9, 10, 0xFFFFD040); }
        else if (blink) gui.fill(4, 4, 10, 10, 0xFFFF3030);
        gui.text(label, 12, 3, paused ? 0xFFFFD040 : 0xFFFFFFFF, false);
        gui.end();
        gui.begin(fbWidth, fbHeight, settings.guiScale);   // mouse positions between frames use the normal GUI scale
        gui.end();
    }

    /** Height of the REC mark in GUI pixels at the current GUI scale (the NPC recording read-out moves below it). */
    public int recordingMarkHeight() {
        if (!recorder.recording()) return 0;
        int s = Math.max(1, gui.scale / 2);
        return (int) Math.ceil(13.0 * s / Math.max(1, gui.scale));
    }

    public void quit() { running = false; }

    // ------------------------------------------------------------------------------------------ loop
    private long nextFrame;
    /** VSync is switched off while the game runs on the video clock (a 60 Hz screen must not hold a 120 fps video back). */
    private boolean vsyncSuspended;

    private void loop() {
        long last = System.nanoTime(), fpsTimer = System.currentTimeMillis();
        double guiAcc = 0, worldAcc = 0;
        int frames = 0;
        while (running && !glfwWindowShouldClose(window)) {
            long frameStart = System.nanoTime();
            glfwPollEvents();
            if (remote != null) remote.pump();
            double realDt = Math.min(0.25, (frameStart - last) / 1e9);
            last = frameStart;
            // smooth recording: the game advances exactly one video frame per rendered frame
            boolean smooth = videoClock();
            if (smooth != vsyncSuspended) {
                vsyncSuspended = smooth;
                applyVsync();
                nextFrame = 0;
            }
            double dt = smooth ? 1.0 / recorder.fps() : realDt;
            measureSpeed(smooth, realDt, dt);

            // menus tick 20 times a second; the world only when no service menu pauses it
            guiAcc += dt * 20;
            for (int n = 0; guiAcc >= 1; n++) {
                if (n == 10) { guiAcc = 0; break; }
                guiAcc -= 1;
                if (screen != null) screen.tick();
            }
            boolean paused = worldPaused();
            if (session != null && !paused) {
                worldAcc += dt * 20;
                for (int n = 0; worldAcc >= 1 && session != null; n++) {
                    if (n == 10) { worldAcc = 0; break; }
                    worldAcc -= 1;
                    session.tick();
                }
                paused = worldPaused();         // a tick may have opened a menu (death screen)
            }
            float partial = (float) worldAcc;   // frozen while paused: the picture does not move at all

            if (session != null && grabbed && screen == null) {
                session.mouseMoved(pendingDX, pendingDY, (float) dt);
            }
            pendingDX = pendingDY = 0;
            derpcraft.audio.Sfx.music(session == null, session != null && session.creative(), (float) realDt);
            render(partial, (float) dt, paused);
            recordFrame(realDt, smooth);           // before the REC mark is drawn: it is never in the video
            if (screenshotRequested) { screenshotRequested = false; screenshot(); }
            if (remote != null) remote.viewfinder.afterFrame(fbWidth, fbHeight);
            drawRecordingMark();
            glfwSwapBuffers(window);

            frames++;
            if (System.currentTimeMillis() - fpsTimer >= 1000) { fps = frames; frames = 0; fpsTimer += 1000; }
            int limit = smooth ? recorder.fps() : settings.maxFps > 0 && !settings.vsync ? settings.maxFps : 0;
            if (limit > 0) limitFrameRate(limit);
            else nextFrame = 0;
        }
    }

    /** Deadline-based frame limiter: sleeps coarsely, then spins the last ~1.5 ms. Thread.sleep alone has 1-15 ms
     *  granularity on Windows, which made the frame rate uneven (visible judder in recordings). */
    private void limitFrameRate(int fpsLimit) {
        long target = 1_000_000_000L / fpsLimit;
        long now = System.nanoTime();
        if (nextFrame == 0 || now - nextFrame > target * 4) nextFrame = now;   // first frame / long hitch
        nextFrame += target;
        long wait;
        while ((wait = nextFrame - System.nanoTime()) > 0) {
            if (wait > 2_000_000L) {
                try { Thread.sleep((wait - 1_500_000L) / 1_000_000L); } catch (InterruptedException ignored) { break; }
            } else Thread.onSpinWait();
        }
    }

    private void render(float partial, float dt, boolean paused) {
        glViewport(0, 0, fbWidth, fbHeight);
        if (session != null) {
            session.render(partial, dt, paused);
        } else {
            glClearColor(0, 0, 0, 1);
            glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
        }
        gui.begin(fbWidth, fbHeight, settings.guiScale);
        if (session != null) session.renderHud(gui, partial);
        if (screen != null) {
            if (screen.width != gui.width || screen.height != gui.height) screen.resize(gui.width, gui.height);
            screen.render(gui, mouseGuiX(), mouseGuiY(), partial);
        }
        gui.end();
    }

    /** Saves the current frame (before the HUD is hidden? use F1 first for clean shots) to screenshots/. */
    public void screenshot() {
        int w = fbWidth, h = fbHeight;
        ByteBuffer buf = ByteBuffer.allocateDirect(w * h * 4).order(ByteOrder.nativeOrder());
        glPixelStorei(GL_PACK_ALIGNMENT, 1);
        glReadPixels(0, 0, w, h, GL_RGBA, GL_UNSIGNED_BYTE, buf);
        String base = new SimpleDateFormat("yyyy-MM-dd_HH.mm.ss").format(new Date());
        // several shots in the same second get _1, _2... like vanilla
        shotIndex = base.equals(lastShotBase) ? shotIndex + 1 : 0;
        lastShotBase = base;
        String name = base + (shotIndex == 0 ? "" : "_" + shotIndex) + ".png";
        Path out = screenshotsDir.resolve(name);
        workers.execute(-10, () -> {
            BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
            int[] row = new int[w];
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    int i = ((h - 1 - y) * w + x) * 4;
                    row[x] = (buf.get(i) & 255) << 16 | (buf.get(i + 1) & 255) << 8 | (buf.get(i + 2) & 255);
                }
                img.setRGB(0, y, w, 1, row, 0, w);
            }
            try { ImageIO.write(img, "png", out.toFile()); } catch (Exception e) { System.err.println(e); }
        });
        if (session != null) session.chat("Saved screenshot as " + name);
    }

    public void saveGrading() {
        try {
            grading.save(gradingDir.resolve("current.txt"));
            Path f = gradingDir.resolve("current.txt");
            Files.writeString(f, "# name=" + gradingName + "\n" + Files.readString(f));
        } catch (Exception e) { System.err.println("Cannot save grading: " + e); }
    }

    private String lastShotBase = "";
    private int shotIndex;

    private void shutdown() {
        if (recorder.recording()) { stopRecording(); recorder.awaitFinish(60_000); }
        capture.release();
        if (remote != null) remote.stop();
        if (session != null) { session.close(); session = null; }
        settings.save();
        saveGrading();
        workers.shutdown();
        glfwDestroyWindow(window);
        glfwTerminate();
    }
}
