package derpcraft.game;

import derpcraft.DerpCraft;
import derpcraft.render.post.Grading;

/**
 * Look transitions (Left Alt + ,): the shader settings and the colour grade move smoothly from look A to look B in
 * the chosen time (Shaders -> Transitions...). A = "From" shader preset + colour preset ("(current)" = what is on
 * screen when it starts), B = "To". Pressed again at B it goes back to A; pressed while running it turns around.
 * <p>
 * Numbers are interpolated. Things that are on in only one of the looks fade: shadows, coloured light, water shader,
 * reflections, wind, god rays (strength from 0), fog (from the render distance), the cinematic pipeline (from the
 * neutral grade). Shadow quality and ray tracing cannot fade: the higher shadow quality is used during the
 * transition, ray tracing switches half way.
 */
public final class LookTransition {
    /** Colour list entry: the cinematic pipeline off (the neutral grade). */
    public static final String COLOR_OFF = "Off";

    private ShaderLook a, b;
    private Grading ga, gb;
    private boolean cineA, cineB;
    private String nameA, nameB, labelA, labelB;
    /** 0 = at A, 1 = at B. */
    private float pos;
    /** +1 towards B, -1 towards A, 0 = still. */
    private int dir;
    private boolean ready;

    public boolean running() { return dir != 0; }
    public float progress() { return pos; }
    /** "A -> B" of the transition that runs (for the HUD). */
    public String label() { return dir >= 0 ? labelA + " -> " + labelB : labelB + " -> " + labelA; }

    /** Forget the captured looks (the setup changed): the next start begins at "From" again. */
    public void reset(DerpCraft game) {
        if (dir != 0) finish(game.settings, game);
        ready = false;
        pos = 0;
    }

    /** Left Alt + ,: start towards the other end, or turn a running transition around. Returns an error for the chat, or null (a start or a
     *  turn is silent: no chat lines over the picture while filming). */
    public String toggle(DerpCraft game) {
        if (dir != 0) {
            dir = -dir;
            return null;
        }
        if (!ready || pos <= 0) {
            String err = capture(game);
            if (err != null) return err;
            pos = 0;
            dir = 1;
            apply(game, 0);
        } else dir = -1;
        return null;
    }

    /** Reads looks A and B from the setup; returns an error message or null. */
    private String capture(DerpCraft game) {
        Settings st = game.settings;
        ShaderLook la = st.transFromShader.equals(ShaderLook.CURRENT) ? ShaderLook.of(st) : ShaderLook.byName(st.transFromShader, game.shadersDir);
        ShaderLook lb = ShaderLook.byName(st.transToShader, game.shadersDir);
        if (la == null) return "§cTransition: no shader preset \"" + st.transFromShader + "\"";
        if (lb == null) return "§cTransition: no shader preset \"" + st.transToShader + "\"";
        Object[] ca = color(game, st.transFromColor), cb = color(game, st.transToColor);
        if (ca == null) return "§cTransition: no colour preset \"" + st.transFromColor + "\"";
        if (cb == null) return "§cTransition: no colour preset \"" + st.transToColor + "\"";
        a = la; b = lb;
        ga = (Grading) ca[0]; cineA = (Boolean) ca[1]; nameA = (String) ca[2];
        gb = (Grading) cb[0]; cineB = (Boolean) cb[1]; nameB = (String) cb[2];
        String sa = st.transFromShader.equals(ShaderLook.CURRENT) ? "current" : st.transFromShader;
        String na = st.transFromColor.equals(ShaderLook.CURRENT) ? (cineA ? game.gradingName : COLOR_OFF) : st.transFromColor;
        labelA = sa + " / " + na;
        labelB = st.transToShader + " / " + st.transToColor;
        ready = true;
        return null;
    }

    /** {grade, cinematic on, name} of a colour list entry, or null. */
    private static Object[] color(DerpCraft game, String name) {
        if (name.equals(ShaderLook.CURRENT)) return new Object[]{game.grading.copy(), game.settings.cinematic, game.gradingName};
        if (name.equals(COLOR_OFF)) return new Object[]{new Grading(), false, "Vanilla"};
        Grading g = Grading.byName(name, game.gradingDir);
        return g == null ? null : new Object[]{g, true, name};
    }

    /** Puts look A ("Show From") or B ("Show To") on screen at once. Returns an error message or null. */
    public String show(DerpCraft game, boolean to) {
        dir = 0;
        String err = capture(game);
        if (err != null) return err;
        pos = to ? 1 : 0;
        finish(game.settings, game);
        return null;
    }

    /** Advances a running transition by 'dt' seconds (0 while the game is paused). */
    public void update(DerpCraft game, float dt) {
        if (dir == 0 || dt <= 0) return;
        Settings st = game.settings;
        pos += dir * dt / Math.max(0.05f, st.transSeconds);
        if (pos >= 1 || pos <= 0) {
            pos = pos >= 1 ? 1 : 0;
            dir = 0;
            finish(st, game);
            return;
        }
        float t = st.transCurve == 1 ? pos * pos * (3 - 2 * pos) : pos;
        apply(game, t);
    }

    /** Ends at the side 'pos' is on: that look exactly, nothing half way left over. */
    private void finish(Settings st, DerpCraft game) {
        dir = 0;
        clearFades(st);
        if (!ready) return;
        boolean atB = pos >= 0.5f;
        (atB ? b : a).applyTo(st);
        if (game != null) {
            st.cinematic = atB ? cineB : cineA;
            game.grading = (atB ? gb : ga).copy();
            game.gradingName = atB ? nameB : nameA;
        }
    }

    private static void clearFades(Settings st) {
        st.coloredLightMix = -1;
        st.shadowFade = 1;
        st.waterFade = 1;
        st.ssrWaterFade = 1;
        st.fogTintFrom = -1;
        st.fogTintMix = 0;
    }

    private static float mix(float x, float y, float t) { return x + (y - x) * t; }
    private static float on(boolean b) { return b ? 1 : 0; }

    /** Settings and grade at 't' (0 = A, 1 = B). */
    private void apply(DerpCraft game, float t) {
        Settings st = game.settings;
        ShaderLook A = a, B = b;
        st.coloredLight = A.coloredLight || B.coloredLight;
        st.coloredLightMix = mix(on(A.coloredLight), on(B.coloredLight), t);
        st.shadows = Math.max(A.shadows, B.shadows);
        st.shadowFade = mix(on(A.shadows > 0), on(B.shadows > 0), t);
        st.rayTracing = t < 0.5f ? A.rayTracing : B.rayTracing;
        st.rtQuality = t < 0.5f ? A.rtQuality : B.rtQuality;
        st.aoStrength = mix(A.ao, B.ao, t);
        // fog: "off" is fog at the far end of the render distance, starting at 3/4 of it (what the game draws without fog)
        st.fog = A.fog || B.fog;
        st.fogDistance = mix(A.fog ? A.fogDistance : 512, B.fog ? B.fogDistance : 512, t);
        st.fogStart = mix(A.fog ? A.fogStart : 0.75f, B.fog ? B.fogStart : 0.75f, t);
        st.fogTintFrom = A.fog ? A.fogTint : 0;
        st.fogTint = B.fog ? B.fogTint : 0;
        st.fogTintMix = t;
        // wind, waves, rays, reflections: an effect that is off has strength 0
        st.waving = A.waving || B.waving;
        st.windStrength = mix(A.waving ? A.wind : 0, B.waving ? B.wind : 0, t);
        st.waterShader = A.water || B.water;
        st.waveHeight = mix(A.water ? A.waveHeight : B.waveHeight, B.water ? B.waveHeight : A.waveHeight, t);
        st.waterFade = mix(on(A.water), on(B.water), t);
        st.godRays = A.godRays || B.godRays;
        st.godRayStrength = mix(A.godRays ? A.rays : 0, B.godRays ? B.rays : 0, t);
        st.reflections = Math.max(A.reflections, B.reflections);
        st.ssrWaterFade = mix(on(A.reflections > 0), on(B.reflections > 0), t);
        st.blockReflections = mix(A.reflections == 2 ? A.blockReflections : 0, B.reflections == 2 ? B.blockReflections : 0, t);
        st.ssao = A.ssao || B.ssao;
        st.ssaoStrength = mix(A.ssao ? A.ssaoStrength : 0, B.ssao ? B.ssaoStrength : 0, t);
        // colour: a look without the cinematic pipeline is the neutral grade
        Grading neutral = new Grading();
        st.cinematic = cineA || cineB;
        game.grading = Grading.blend(cineA ? ga : neutral, cineB ? gb : neutral, t);
    }
}
