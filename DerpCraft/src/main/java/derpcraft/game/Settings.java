package derpcraft.game;

import java.io.*;
import java.nio.file.*;
import java.util.Properties;

/** options.txt */
public final class Settings {
    public int renderDistance = 16;
    public float fov = 70;
    public float sensitivity = 0.5f;
    public int maxFps = 0;              // 0 = unlimited
    public boolean vsync = true;
    public int guiScale = 0;            // 0 = auto
    public float gamma = 0.0f;
    public boolean clouds = true;
    public boolean fovEffects = true;
    public boolean viewBobbing = true;
    public int mipLevels = 4;
    public String resourcePack = "";
    /** The player's skin: a name from the skins list (game/skins/*.png, the built-in outfits, "Steve"); Alt + F9. */
    public String playerSkin = "Steve";
    public boolean fullscreen = false;
    /** Coloured block light (lava orange, redstone red, sea lantern cyan, stained glass tints light). */
    public boolean coloredLight = false;
    /** HDR scene buffer + colour grading / bloom / LUT pipeline. */
    public boolean cinematic = false;
    /** Shadows: 0 off, 1 low, 2 medium, 3 high. */
    public int shadows = 0;
    /** Voxel ray tracing ("RTX"): 0 off, 1 on. */
    public int rayTracing = 0;
    public int rtQuality = 1;
    /** Ambient occlusion (the dark block corners): 0 = off, 1 = Minecraft, up to 2 = twice as dark. */
    public float aoStrength = 1;
    /**
     * Fog (Shaders screen): on / off, where it is thick (blocks), where it starts (share of that distance) and its tint
     * (0 = the sky's own fog colour, see FOG_TINTS).
     */
    public boolean fog = false;
    public float fogDistance = 48, fogStart = 0.1f;
    public int fogTint = 0;
    /** Shader effects (Shaders -> Effects...): leaves, grass, flowers, crops and vines sway in the wind; wind strength (1 = default). */
    public boolean waving = false;
    public float windStrength = 1;
    /** Water shader: moving waves, sky reflection and sun glints on the water surface; wave height (1 = default). */
    public boolean waterShader = false;
    public float waveHeight = 1;
    /** God rays: light shafts from the sun and moon (through leaves, windows; with shadows on - real volumetric light). */
    public boolean godRays = false;
    public float godRayStrength = 1;
    /** Screen space reflections: 0 off, 1 water, 2 water + glossy blocks (game/reflections.txt); strength of the block ones. */
    public int reflections = 0;
    public float blockReflections = 1;
    /**
     * World ambient occlusion (SSAO): contact shadows in corners and under / around everything - items, models,
     * players, NPCs, vehicles - not only the block corners (aoStrength). Strength 1 = default, 0..2.
     */
    public boolean ssao = true;
    public float ssaoStrength = 1;
    /**
     * Look transition (Shaders -> Transitions..., Left Alt + ,): from a shader preset + colour grade to another one in
     * transSeconds ("(current)" = the look on screen when it starts); curve 0 linear, 1 smooth (ease in and out).
     */
    public String transFromShader = ShaderLook.CURRENT, transFromColor = ShaderLook.CURRENT, transToShader = "Overcast", transToColor = "Cold Night";
    public float transSeconds = 10;
    public int transCurve = 1;
    /**
     * Set only while a look transition runs (LookTransition), never saved: coloured light amount (-1 = from
     * coloredLight), shadows / water shader / water reflections faded (1 = full), fog colour blended from tint
     * fogTintFrom (-1 = none) to fogTint by fogTintMix.
     */
    public float coloredLightMix = -1, shadowFade = 1, waterFade = 1, ssrWaterFade = 1, fogTintMix;
    public int fogTintFrom = -1;
    public static final String[] FOG_TINT_NAMES = {"Sky", "White", "Grey", "Blue", "Smog", "Green", "Red", "Black"};
    /** Fog tints (daylight colour; darker at night): null = the sky's fog colour. */
    public static final float[][] FOG_TINTS = {null, {0.86f, 0.88f, 0.9f}, {0.55f, 0.56f, 0.58f}, {0.5f, 0.62f, 0.8f},
            {0.62f, 0.55f, 0.4f}, {0.45f, 0.6f, 0.35f}, {0.6f, 0.25f, 0.2f}, {0.02f, 0.02f, 0.025f}};
    /** Dropped items lie flat on the ground and tumble when thrown. */
    public boolean itemPhysics = true;
    /** Pick up items by left-clicking them instead of walking over them. */
    public boolean pickupClick = false;
    /** Items that give light (torches, glowstone, lava bucket...) light up their surroundings in the hand, on the ground and in flight. */
    public boolean dynamicLights = true;
    /** Fire spreads to flammable blocks and burns them away (off: fire only burns for a while and leaves soot). */
    public boolean fireSpread = true;
    /** Bullets break blocks once enough damage is done (off: they only chip and crack them). Glass, torches, doors, cameras break either way. */
    public boolean bulletBlocks = true;
    /** Blood from hits, shots and falls (particles and splats on blocks). */
    public boolean blood = true;
    /** How much blood (x of the full amount; Options -> Gameplay). Small by default: a hit leaves a few drops, not a pool. */
    public float bloodAmount = 0.25f;
    /** Third person (F5) camera distance in blocks. */
    public float thirdPersonDistance = 4;
    /** Chat messages pop up over the game (off: they are only seen in the chat window, T). */
    public boolean chatMessages = true;
    /** Screen recording: pause while service menus are open (Esc, NPC Hub, camera, shaders...); off = records everything. */
    public boolean recPauseInMenus = true;
    /** Screen recording: 0 NVENC H.264, 1 NVENC H.265, 2 CPU x264. */
    public int recEncoder = 0;
    public int recFps = 60;
    /** Screen recording: true = smooth (the game runs on the video clock, every frame is a video frame, nothing is dropped);
     *  false = real time (frames are repeated or dropped to keep up with the clock). */
    public boolean recSmooth = true;
    /** Screen recording: video bitrate in Mbit/s. */
    public int recBitrate = 40;
    /** Screen recording: video height (720, 1080, 1440, 2160); 0 = the size of the game window. */
    public int recHeight = 1080;
    private final Path file;

    public Settings(Path file) { this.file = file; }

    public void load() {
        if (!Files.exists(file)) return;
        Properties p = new Properties();
        try (Reader r = Files.newBufferedReader(file)) { p.load(r); } catch (IOException e) { return; }
        renderDistance = i(p, "renderDistance", renderDistance);
        fov = f(p, "fov", fov);
        for (int i = 0; i < derpcraft.audio.Sfx.CATS.length; i++) derpcraft.audio.Sfx.setVolume(i, f(p, "soundCategory_" + derpcraft.audio.Sfx.CATS[i], 1));
        sensitivity = f(p, "mouseSensitivity", sensitivity);
        maxFps = i(p, "maxFps", maxFps);
        vsync = b(p, "enableVsync", vsync);
        guiScale = i(p, "guiScale", guiScale);
        gamma = f(p, "gamma", gamma);
        clouds = b(p, "renderClouds", clouds);
        fovEffects = b(p, "fovEffects", fovEffects);
        // "viewBobbing" since 1.14 (the old "bobView" was saved OFF by default while bobbing was barely visible: start ON again)
        viewBobbing = b(p, "viewBobbing", viewBobbing);
        mipLevels = i(p, "mipmapLevels", mipLevels);
        resourcePack = p.getProperty("resourcePack", resourcePack);
        playerSkin = p.getProperty("playerSkin", playerSkin);
        fullscreen = b(p, "fullscreen", fullscreen);
        coloredLight = b(p, "coloredLight", coloredLight);
        cinematic = b(p, "cinematic", cinematic);
        shadows = i(p, "shadows", shadows);
        rayTracing = i(p, "rayTracing", rayTracing);
        rtQuality = i(p, "rtQuality", rtQuality);
        aoStrength = f(p, "aoStrength", aoStrength);
        fog = b(p, "fog", fog);
        fogDistance = f(p, "fogDistance", fogDistance);
        fogStart = f(p, "fogStart", fogStart);
        fogTint = i(p, "fogTint", fogTint);
        waving = b(p, "waving", waving);
        windStrength = f(p, "windStrength", windStrength);
        waterShader = b(p, "waterShader", waterShader);
        waveHeight = f(p, "waveHeight", waveHeight);
        godRays = b(p, "godRays", godRays);
        godRayStrength = f(p, "godRayStrength", godRayStrength);
        reflections = i(p, "reflections", reflections);
        blockReflections = f(p, "blockReflections", blockReflections);
        ssao = b(p, "ssao", ssao);
        ssaoStrength = f(p, "ssaoStrength", ssaoStrength);
        transFromShader = p.getProperty("transFromShader", transFromShader);
        transFromColor = p.getProperty("transFromColor", transFromColor);
        transToShader = p.getProperty("transToShader", transToShader);
        transToColor = p.getProperty("transToColor", transToColor);
        transSeconds = f(p, "transSeconds", transSeconds);
        transCurve = i(p, "transCurve", transCurve);
        itemPhysics = b(p, "itemPhysics", itemPhysics);
        pickupClick = b(p, "pickupClick", pickupClick);
        dynamicLights = b(p, "dynamicLights", dynamicLights);
        fireSpread = b(p, "fireSpread", fireSpread);
        bulletBlocks = b(p, "bulletBlocks", bulletBlocks);
        blood = b(p, "blood", blood);
        bloodAmount = Math.max(0, Math.min(2, f(p, "bloodAmount", bloodAmount)));
        thirdPersonDistance = f(p, "thirdPersonDistance", thirdPersonDistance);
        chatMessages = b(p, "chatMessages", chatMessages);
        recPauseInMenus = b(p, "recPauseInMenus", recPauseInMenus);
        recEncoder = clamp(i(p, "recEncoder", recEncoder), 0, 2);
        recFps = clamp(i(p, "recFps", recFps), 10, 240);
        recSmooth = b(p, "recSmooth", recSmooth);
        recBitrate = clamp(i(p, "recBitrate", recBitrate), 2, 300);
        recHeight = clamp(i(p, "recHeight", recHeight), 0, 4320);
        Keys.load(p);
        // a hand-edited / damaged options.txt must not break the game (render distance 500 = out of memory)
        renderDistance = clamp(renderDistance, 2, 64);
        fov = Float.isFinite(fov) ? Math.max(30, Math.min(110, fov)) : 70;
        sensitivity = Float.isFinite(sensitivity) ? Math.max(0, Math.min(2, sensitivity)) : 0.5f;
        maxFps = clamp(maxFps, 0, 1000);
        guiScale = clamp(guiScale, 0, 8);
        gamma = Float.isFinite(gamma) ? Math.max(0, Math.min(1, gamma)) : 0;
        mipLevels = clamp(mipLevels, 0, 4);
        shadows = clamp(shadows, 0, 4);
        rayTracing = clamp(rayTracing, 0, 2);
        rtQuality = clamp(rtQuality, 0, 2);
        aoStrength = Float.isFinite(aoStrength) ? Math.max(0, Math.min(2, aoStrength)) : 1;
        fogDistance = Float.isFinite(fogDistance) ? Math.max(4, Math.min(512, fogDistance)) : 48;
        fogStart = Float.isFinite(fogStart) ? Math.max(0, Math.min(0.95f, fogStart)) : 0.1f;
        fogTint = clamp(fogTint, 0, FOG_TINTS.length - 1);
        windStrength = range(windStrength, 0, 3, 1);
        waveHeight = range(waveHeight, 0, 3, 1);
        godRayStrength = range(godRayStrength, 0, 3, 1);
        reflections = clamp(reflections, 0, 2);
        blockReflections = range(blockReflections, 0, 3, 1);
        transSeconds = range(transSeconds, 0.5f, 600, 10);
        ssaoStrength = range(ssaoStrength, 0, 2, 1);
        transCurve = clamp(transCurve, 0, 1);
        thirdPersonDistance = Float.isFinite(thirdPersonDistance) ? Math.max(1, Math.min(32, thirdPersonDistance)) : 4;
    }

    private static int clamp(int v, int lo, int hi) { return Math.max(lo, Math.min(hi, v)); }

    private static float range(float v, float lo, float hi, float def) { return Float.isFinite(v) ? Math.max(lo, Math.min(hi, v)) : def; }

    /** Any effect that needs the HDR scene buffer and the post chain (reflections, god rays). */
    public boolean screenSpaceEffects() { return reflections > 0 || godRays || worldAO() > 0; }

    /** Strength of the world ambient occlusion this frame (0 = off). */
    public float worldAO() { return ssao ? ssaoStrength : 0; }

    public void save() {
        Properties p = new Properties();
        p.setProperty("renderDistance", "" + renderDistance);
        p.setProperty("fov", "" + fov);
        for (int i = 0; i < derpcraft.audio.Sfx.CATS.length; i++) p.setProperty("soundCategory_" + derpcraft.audio.Sfx.CATS[i], "" + derpcraft.audio.Sfx.VOL[i]);
        p.setProperty("mouseSensitivity", "" + sensitivity);
        p.setProperty("maxFps", "" + maxFps);
        p.setProperty("enableVsync", "" + vsync);
        p.setProperty("guiScale", "" + guiScale);
        p.setProperty("gamma", "" + gamma);
        p.setProperty("renderClouds", "" + clouds);
        p.setProperty("fovEffects", "" + fovEffects);
        p.setProperty("viewBobbing", "" + viewBobbing);
        p.setProperty("mipmapLevels", "" + mipLevels);
        p.setProperty("resourcePack", resourcePack);
        p.setProperty("playerSkin", playerSkin);
        p.setProperty("fullscreen", "" + fullscreen);
        p.setProperty("coloredLight", "" + coloredLight);
        p.setProperty("cinematic", "" + cinematic);
        p.setProperty("shadows", "" + shadows);
        p.setProperty("rayTracing", "" + rayTracing);
        p.setProperty("rtQuality", "" + rtQuality);
        p.setProperty("aoStrength", "" + aoStrength);
        p.setProperty("fog", "" + fog);
        p.setProperty("fogDistance", "" + fogDistance);
        p.setProperty("fogStart", "" + fogStart);
        p.setProperty("fogTint", "" + fogTint);
        p.setProperty("waving", "" + waving);
        p.setProperty("windStrength", "" + windStrength);
        p.setProperty("waterShader", "" + waterShader);
        p.setProperty("waveHeight", "" + waveHeight);
        p.setProperty("godRays", "" + godRays);
        p.setProperty("godRayStrength", "" + godRayStrength);
        p.setProperty("reflections", "" + reflections);
        p.setProperty("blockReflections", "" + blockReflections);
        p.setProperty("ssao", "" + ssao);
        p.setProperty("ssaoStrength", "" + ssaoStrength);
        p.setProperty("transFromShader", transFromShader);
        p.setProperty("transFromColor", transFromColor);
        p.setProperty("transToShader", transToShader);
        p.setProperty("transToColor", transToColor);
        p.setProperty("transSeconds", "" + transSeconds);
        p.setProperty("transCurve", "" + transCurve);
        p.setProperty("itemPhysics", "" + itemPhysics);
        p.setProperty("pickupClick", "" + pickupClick);
        p.setProperty("dynamicLights", "" + dynamicLights);
        p.setProperty("fireSpread", "" + fireSpread);
        p.setProperty("bulletBlocks", "" + bulletBlocks);
        p.setProperty("blood", "" + blood);
        p.setProperty("bloodAmount", "" + bloodAmount);
        p.setProperty("thirdPersonDistance", "" + thirdPersonDistance);
        p.setProperty("chatMessages", "" + chatMessages);
        p.setProperty("recPauseInMenus", "" + recPauseInMenus);
        p.setProperty("recEncoder", "" + recEncoder);
        p.setProperty("recFps", "" + recFps);
        p.setProperty("recSmooth", "" + recSmooth);
        p.setProperty("recBitrate", "" + recBitrate);
        p.setProperty("recHeight", "" + recHeight);
        Keys.save(p);
        try { derpcraft.util.SafeFiles.store(p, file, "DerpCraft options"); } catch (IOException ignored) {}
    }

    private static int i(Properties p, String k, int d) { try { return Integer.parseInt(p.getProperty(k, "" + d).trim()); } catch (Exception e) { return d; } }
    private static float f(Properties p, String k, float d) { try { return Float.parseFloat(p.getProperty(k, "" + d).trim()); } catch (Exception e) { return d; } }
    private static boolean b(Properties p, String k, boolean d) { return Boolean.parseBoolean(p.getProperty(k, "" + d).trim()); }
}
