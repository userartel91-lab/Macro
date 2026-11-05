package derpcraft.game;

import java.util.*;

import static org.lwjgl.glfw.GLFW.*;

/**
 * Key bindings (Options -> Controls). Every action of the game has a key that can be changed; mouse buttons work
 * too (code MOUSE + button). Saved in options.txt as key_&lt;id&gt;=&lt;code&gt;, like Minecraft.
 */
public final class Keys {
    private Keys() {}

    /** Mouse buttons are stored as MOUSE + button (0 left, 1 right, 2 middle, 3.. side buttons). */
    public static final int MOUSE = 1000;
    public static final int NONE = -1;

    /** The game window (set at start): combos check whether their modifier key is held. */
    public static long window;

    public static final class Binding {
        public final String id, label, category;
        public final int def, defMod;
        public int key;
        /** Modifier that must be held with the key (Left/Right Ctrl, Shift, Alt as GLFW key codes; NONE = no modifier). */
        public int mod = NONE;

        Binding(String category, String id, String label, int def) { this(category, id, label, def, NONE); }

        Binding(String category, String id, String label, int def, int defMod) {
            this.category = category; this.id = id; this.label = label; this.def = def; this.key = def; this.defMod = defMod; this.mod = defMod;
        }

        /** The key was pressed: true when it is this binding's key and its modifier (if any) is held. A plain binding gives way to
         *  a combo on the same key whose modifier is held (End vs Left Ctrl + End). */
        public boolean matches(int code) {
            if (key == NONE || key != code) return false;
            if (mod != NONE) return modDown(mod);
            for (Binding o : ALL) if (o != this && o.key == code && o.mod != NONE && modDown(o.mod)) return false;
            return true;
        }

        /** True while the key (or mouse button) is held (with its modifier). */
        public boolean down(long window) {
            if (HELD.containsKey(this)) return true;       // held from the Wi-Fi remote (MIDlet)
            if (key == NONE) return false;
            if (SWALLOWED.contains(key)) return false;     // used in a combo (F3 + D): does not also walk
            if (mod != NONE && glfwGetKey(window, mod) != GLFW_PRESS) return false;
            if (key >= MOUSE) return glfwGetMouseButton(window, key - MOUSE) == GLFW_PRESS;
            return glfwGetKey(window, key) == GLFW_PRESS;
        }

        public boolean isDefault() { return key == def && mod == defMod; }

        public void reset() { key = def; mod = defMod; }

        public String keyName() { return key == NONE || mod == NONE ? name(key) : name(mod) + " + " + name(key); }
    }

    /** Ctrl, Shift, Alt and Win keys (left and right) can be the first key of a combo. */
    public static boolean isModifier(int code) { return code >= 340 && code <= 347; }

    private static boolean modDown(int mod) { return VIRTUAL_MODS.contains(mod) || (window != 0 && glfwGetKey(window, mod) == GLFW_PRESS); }

    /** Bindings held down from the Wi-Fi remote: binding -> time (ms) when the hold runs out unless the phone renews it. */
    public static final java.util.concurrent.ConcurrentHashMap<Binding, Long> HELD = new java.util.concurrent.ConcurrentHashMap<>();
    /** Keys pressed as part of an F3 combo (F3 + D): they do nothing else (no strafing) until they are let go. */
    public static final Set<Integer> SWALLOWED = java.util.concurrent.ConcurrentHashMap.newKeySet();
    /** Modifier keys (GLFW codes) the remote "holds" while it presses a combo binding (e.g. Ctrl + End). */
    public static final Set<Integer> VIRTUAL_MODS = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** The binding with this id (also mod keys), or null. */
    public static Binding byId(String id) {
        for (Binding b : ALL) if (b.id.equalsIgnoreCase(id)) return b;
        return null;
    }

    public static final List<Binding> ALL = new ArrayList<>();
    public static final String[] CATEGORIES = {"Movement", "Gameplay", "Hotbar", "View", "Camera", "NPC and scenes", "Mods"};

    private static Binding b(String cat, String id, String label, int def) { return b(cat, id, label, def, NONE); }

    private static Binding b(String cat, String id, String label, int def, int defMod) {
        Binding k = new Binding(cat, id, label, def, defMod);
        ALL.add(k);
        Integer saved = SAVED.get(id);
        if (saved != null) { k.key = saved; Integer m = SAVED_MOD.get(id); k.mod = m != null ? m : NONE; }
        return k;
    }

    /** Saved codes read before the bindings exist (mod keys are added later). */
    private static final Map<String, Integer> SAVED = new HashMap<>();
    /** Saved combo modifiers (keymod_&lt;id&gt; in options.txt). */
    private static final Map<String, Integer> SAVED_MOD = new HashMap<>();

    // ------------------------------------------------------------------ movement
    public static final Binding FORWARD = b("Movement", "forward", "Walk forward", 'W');
    public static final Binding BACK = b("Movement", "back", "Walk backwards", 'S');
    public static final Binding LEFT = b("Movement", "left", "Strafe left", 'A');
    public static final Binding RIGHT = b("Movement", "right", "Strafe right", 'D');
    public static final Binding JUMP = b("Movement", "jump", "Jump / fly up", ' ');
    public static final Binding SNEAK = b("Movement", "sneak", "Sneak / fly down", 340);
    public static final Binding SPRINT = b("Movement", "sprint", "Sprint / fast camera", 341);
    public static final Binding SNEAK_DEEP = b("Movement", "sneakDeep", "Deep sneak (hold)", 340, 342);
    public static final Binding POSE_STAND = b("Movement", "poseStand", "Pose: stand", '1', 342);
    public static final Binding POSE_SIT = b("Movement", "poseSit", "Pose: sit", '2', 342);
    public static final Binding POSE_LIE = b("Movement", "poseLie", "Pose: lie down", '3', 342);
    public static final Binding POSE_CRAWL = b("Movement", "poseCrawl", "Pose: crawl", '4', 342);
    // ------------------------------------------------------------------ gameplay
    public static final Binding ATTACK = b("Gameplay", "attack", "Attack / break", MOUSE);
    public static final Binding USE = b("Gameplay", "use", "Use item / place block", MOUSE + 1);
    public static final Binding PICK = b("Gameplay", "pickItem", "Pick block", MOUSE + 2);
    public static final Binding INVENTORY = b("Gameplay", "inventory", "Inventory", 'E');
    public static final Binding DROP = b("Gameplay", "drop", "Drop item (Ctrl: stack)", 'Q');
    public static final Binding CHAT = b("Gameplay", "chat", "Chat", 'T');
    public static final Binding COMMAND = b("Gameplay", "command", "Command line", '/');
    public static final Binding ZOOM = b("Gameplay", "zoom", "Zoom (hold)", 'C');
    public static final Binding HORN = b("Gameplay", "horn", "Vehicle horn (hold)", 'H');
    /** With a gun in hand it wins over "Level the horizon" (same default key R). */
    public static final Binding RELOAD = b("Gameplay", "reload", "Reload gun", 'R');
    /** Gun editor (Alt + Home): tune the gun in hand, saved to its json at once. */
    public static final Binding GUN_EDITOR = b("Gameplay", "gunEditor", "Gun editor", 268, 342);
    /** Vehicle editor (Alt + End): tune the vehicle you ride / look at / stand next to (speed, seats, block places, size, suspension...). */
    public static final Binding VEHICLE_EDITOR = b("Gameplay", "vehicleEditor", "Vehicle editor", 269, 342);
    /** In a vehicle: the headlights and tail lights on / off. */
    public static final Binding VEHICLE_LIGHTS = b("Gameplay", "vehicleLights", "Vehicle lights on / off", 'N');
    /** Ctrl + End: blows up the vehicle you ride / look at / stand next to (without one it is the selfie key). */
    public static final Binding VEHICLE_EXPLODE = b("Gameplay", "vehicleExplode", "Blow up a vehicle (Ctrl + End; the selfie key without a vehicle)", 269, 341);
    /** Held behind a parked car: push it (slowly, with both arms out). */
    public static final Binding PUSH = b("Gameplay", "pushVehicle", "Push a vehicle from behind (hold)", 342);
    /** Right Alt + F8 in a vehicle: the cinematic third person cameras that follow it (Shift: the next fixed shot). */
    public static final Binding VEHICLE_CAMERA = b("View", "vehicleCamera", "Vehicle cinematic camera (Shift: next shot)", 297, 346);
    /** Left Alt + Q: fist fighting from the first person on / off (left mouse = left hand, right mouse = right hand). */
    public static final Binding FISTS = b("Gameplay", "fists", "Fist fighting on / off", 'Q', 342);
    /** Held while aiming: the gun's second aim (canted sight, magnifier...). */
    public static final Binding AIM2 = b("Gameplay", "aim2", "Second aim (hold while aiming)", 342);
    /** With a gun in hand: a butt strike / pistol whip (pressed again soon: the next kind of strike). */
    public static final Binding MELEE = b("Gameplay", "melee", "Gun melee: butt strike (gun in hand)", MOUSE + 2);
    /** Left Alt + F: the gun in the hand goes onto the back (its slot empties); pressed again it comes back to that slot and the hand. */
    public static final Binding BACK_GUN = b("Gameplay", "backGun", "Gun on the back / in the hand", 'F', 342);
    /** Left Alt + E: the equipment (pouches for magazines, 3 on the left and 3 on the right of the belt). */
    public static final Binding EQUIPMENT = b("Gameplay", "equipment", "Equipment (magazine pouches)", 'E', 342);
    /** Left Alt + B: bleeding on / off (blood drips from the bottom of the view and leaves drops and smears on the ground). */
    public static final Binding BLEED = b("Gameplay", "bleed", "Bleeding on / off", 'B', 342);
    // ------------------------------------------------------------------ hotbar
    public static final Binding[] HOTBAR = new Binding[9];
    static { for (int i = 0; i < 9; i++) HOTBAR[i] = b("Hotbar", "hotbar." + (i + 1), "Hotbar slot " + (i + 1), '1' + i); }
    // ------------------------------------------------------------------ view
    public static final Binding HIDE_HUD = b("View", "hideHud", "Hide HUD and hand", 290);
    public static final Binding SCREENSHOT = b("View", "screenshot", "Screenshot", 291);
    public static final Binding SCREEN_RECORD = b("View", "screenRecord", "Screen recording start / stop", 291, 341);
    public static final Binding DEBUG = b("View", "debug", "Debug info", 292);
    public static final Binding SPECTATOR = b("View", "spectator", "Spectator mode", 293);
    public static final Binding PERSPECTIVE = b("View", "togglePerspective", "Third person view", 294);
    public static final Binding GRADING = b("View", "grading", "Color grading editor", 295);
    public static final Binding SHADERS = b("View", "shaders", "Shaders", 296);
    /** Left Alt + ,: look transition (shader preset + colour grade) set up in Shaders -> Transitions... */
    public static final Binding TRANSITION = b("View", "transition", "Shader / colour transition", ',', 342);
    public static final Binding SMOOTH_CAM = b("View", "smoothCamera", "Cinematic (smooth) camera", 297);
    public static final Binding FULLSCREEN = b("View", "fullscreen", "Fullscreen", 300);
    public static final Binding MODEL_HUB = b("View", "modelHub", "3D Hub (models)", 301);
    // ------------------------------------------------------------------ camera
    public static final Binding CAMERA_PANEL = b("Camera", "cameraPanel", "Camera panel", 298);
    /** Left Alt + F9: the player's skin (the list of the NPC skins). */
    public static final Binding SKINS = b("View", "skins", "Player skin", 298, 342);
    public static final Binding FREE_CAM = b("Camera", "freeCamera", "Free camera", 'V');
    public static final Binding ORBIT = b("Camera", "orbit", "Orbit (Shift: around you)", 'O');
    public static final Binding TRIPOD = b("Camera", "tripod", "Tripod (act, camera stays)", 'P');
    public static final Binding PATH_KEY = b("Camera", "pathKey", "Camera path: add key", 'K');
    public static final Binding PATH_DELETE = b("Camera", "pathDelete", "Delete key (Shift: clear)", 'J');
    public static final Binding PATH_PLAY = b("Camera", "pathPlay", "Play / stop camera path", 'L');
    public static final Binding ROLL_LEFT = b("Camera", "rollLeft", "Roll horizon left (hold)", 'Z');
    public static final Binding ROLL_RIGHT = b("Camera", "rollRight", "Roll horizon right (hold)", 'X');
    public static final Binding LEVEL = b("Camera", "level", "Level the horizon", 'R');
    public static final Binding ZOOM_IN = b("Camera", "zoomIn", "Lens zoom in (hold)", '=');
    public static final Binding ZOOM_OUT = b("Camera", "zoomOut", "Lens zoom out (hold)", '-');
    public static final Binding ZOOM_RESET = b("Camera", "zoomReset", "Reset zoom", '0');
    public static final Binding DOF = b("Camera", "dof", "Depth of field on / off", 'B');
    public static final Binding FOCUS = b("Camera", "focus", "Focus here (Shift: auto)", 'F');
    public static final Binding GRID = b("Camera", "grid", "Rule of thirds grid", 'G');
    public static final Binding SELFIE = b("Camera", "selfie", "Selfie camera on / off", 269, 341);
    public static final Binding SELFIE_FREE = b("Camera", "selfieFree", "Selfie: free camera (turn head)", 269, 345);
    // ------------------------------------------------------------------ NPC
    public static final Binding NPC_HUB = b("NPC and scenes", "npcHub", "NPC Hub (stops recording)", 299);
    public static final Binding SCENE_PLAY = b("NPC and scenes", "scenePlay", "Play / stop the scene", ',');
    public static final Binding RECORD = b("NPC and scenes", "record", "Record / stop recording", '.');
    public static final Binding RECORD_CANCEL = b("NPC and scenes", "recordCancel", "Cancel recording", '/');
    public static final Binding GESTURE = b("NPC and scenes", "gesture", "Gesture: hold + 1..9", 258);
    public static final Binding FACE = b("NPC and scenes", "face", "Face: hold + 1..7", 342);

    // ------------------------------------------------------------------ mod keys
    private static final Map<String, Binding> MOD = new LinkedHashMap<>();

    /** The binding of a mod key (created the first time, with the key the mod asked for as default). */
    public static Binding mod(String modId, String description, int def) {
        String id = "mod." + modId + "." + description.replaceAll("[^A-Za-z0-9]+", "_").toLowerCase(Locale.ROOT);
        return MOD.computeIfAbsent(id, i -> {
            Binding k = new Binding("Mods", i, description + " (" + modId + ")", def);
            Integer saved = SAVED.get(i);
            if (saved != null) { k.key = saved; Integer m = SAVED_MOD.get(i); k.mod = m != null ? m : NONE; }
            ALL.add(k);
            return k;
        });
    }

    // ------------------------------------------------------------------ options.txt
    public static void load(Properties p) {
        for (String k : p.stringPropertyNames()) {
            boolean m = k.startsWith("keymod_");
            if (!m && !k.startsWith("key_")) continue;
            try { (m ? SAVED_MOD : SAVED).put(k.substring(m ? 7 : 4), Integer.parseInt(p.getProperty(k).trim())); } catch (NumberFormatException ignored) {}
        }
        for (Binding b : ALL) {
            Integer v = SAVED.get(b.id);
            if (v == null) { b.reset(); continue; }
            b.key = v;
            Integer m = SAVED_MOD.get(b.id);
            b.mod = m != null ? m : NONE;
        }
    }

    public static void save(Properties p) {
        for (var e : SAVED.entrySet()) p.setProperty("key_" + e.getKey(), String.valueOf(e.getValue()));
        for (var e : SAVED_MOD.entrySet()) p.setProperty("keymod_" + e.getKey(), String.valueOf(e.getValue()));
        for (Binding b : ALL) {
            p.setProperty("key_" + b.id, String.valueOf(b.key)); SAVED.put(b.id, b.key);
            p.setProperty("keymod_" + b.id, String.valueOf(b.mod)); SAVED_MOD.put(b.id, b.mod);
        }
    }

    /** Bindings that share a key with another one (shown red). Command and Cancel recording may share: one works only while recording. */
    public static boolean conflict(Binding b) {
        if (b.key == NONE) return false;
        for (Binding o : ALL) {
            if (o == b || o.key != b.key || o.mod != b.mod) continue;
            if (pairOk(b, o) || pairOk(o, b)) continue;
            return true;
        }
        return false;
    }

    private static boolean pairOk(Binding a, Binding b) {
        // used only while recording / while a modifier is held
        if (a == RECORD_CANCEL) return b == COMMAND || b == CHAT;
        if (a == SNEAK) return b == ORBIT || b == PATH_DELETE || b == FOCUS;
        if (a == MELEE) return b == PICK;                  // middle mouse: a strike with a gun in hand, else pick block
        return false;
    }

    // ------------------------------------------------------------------ names
    public static String name(int code) {
        if (code == NONE) return "None";
        if (code >= MOUSE) {
            int b = code - MOUSE;
            return switch (b) { case 0 -> "Left mouse"; case 1 -> "Right mouse"; case 2 -> "Middle mouse"; default -> "Mouse " + (b + 1); };
        }
        if ((code >= 'A' && code <= 'Z') || (code >= '0' && code <= '9')) return String.valueOf((char) code);
        if (code >= 290 && code <= 314) return "F" + (code - 289);
        if (code >= 320 && code <= 329) return "Num " + (code - 320);
        return switch (code) {
            case 32 -> "Space"; case 39 -> "'"; case 44 -> ","; case 45 -> "-"; case 46 -> "."; case 47 -> "/";
            case 59 -> ";"; case 61 -> "="; case 91 -> "["; case 92 -> "\\"; case 93 -> "]"; case 96 -> "`";
            case 161 -> "World 1"; case 162 -> "World 2";
            case 256 -> "Esc"; case 257 -> "Enter"; case 258 -> "Tab"; case 259 -> "Backspace"; case 260 -> "Insert"; case 261 -> "Delete";
            case 262 -> "Right"; case 263 -> "Left"; case 264 -> "Down"; case 265 -> "Up"; case 266 -> "Page Up"; case 267 -> "Page Down";
            case 268 -> "Home"; case 269 -> "End"; case 280 -> "Caps Lock"; case 281 -> "Scroll Lock"; case 282 -> "Num Lock";
            case 283 -> "Print Screen"; case 284 -> "Pause";
            case 330 -> "Num ."; case 331 -> "Num /"; case 332 -> "Num *"; case 333 -> "Num -"; case 334 -> "Num +"; case 335 -> "Num Enter"; case 336 -> "Num =";
            case 340 -> "Left Shift"; case 341 -> "Left Ctrl"; case 342 -> "Left Alt"; case 343 -> "Left Win";
            case 344 -> "Right Shift"; case 345 -> "Right Ctrl"; case 346 -> "Right Alt"; case 347 -> "Right Win"; case 348 -> "Menu";
            default -> "#" + code;
        };
    }

    /** Short name for hints ("F10", ".", "Tab"). */
    public static String n(Binding b) {
        String k = b.keyName();
        return k.length() == 1 && !Character.isLetterOrDigit(k.charAt(0)) ? "[" + k + "]" : k;
    }
}
