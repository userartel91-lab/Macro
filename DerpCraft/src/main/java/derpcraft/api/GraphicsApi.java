package derpcraft.api;

import java.util.List;

/**
 * Shaders and colour grading. Get it with {@link GameApi#graphics()}. Changes act immediately and are saved with
 * the options like changes made in the menus.
 */
public interface GraphicsApi {
    /** Shadows: 0 off, 1 low, 2 medium, 3 high, 4 ultra. */
    int shadows();
    void setShadows(int quality);

    /** Voxel ray tracing on/off and quality 0 fast, 1 balanced, 2 quality. */
    boolean rayTracing();
    void setRayTracing(boolean on);
    void setRayTracingQuality(int quality);

    /** Coloured block light. */
    boolean coloredLight();
    void setColoredLight(boolean on);

    /** World ambient occlusion (SSAO: items, models, players, NPCs, vehicles); strength 1 = default, 0..2. */
    boolean worldAO();
    void setWorldAO(boolean on, float strength);

    /** Shader effects (Shaders -> Effects): waving plants with the wind strength (1 = default, 0..3). */
    boolean wavingPlants();
    void setWavingPlants(boolean on, float wind);

    /** Water shader: waves, sky reflection, sun glints; wave height (1 = default, 0..3). */
    boolean waterWaves();
    void setWaterWaves(boolean on, float height);

    /** God rays (volumetric light when shadows are on); strength (1 = default, 0..3). */
    boolean godRays();
    void setGodRays(boolean on, float strength);

    /** Screen space reflections: 0 off, 1 water, 2 water + glossy blocks; strength of the block ones (1 = default). */
    int reflections();
    void setReflections(int mode, float blockStrength);

    /** Shader presets: built in (Vanilla, Sunny, Overcast, Storm...) and saved ones (game/shaders). */
    List<String> shaderPresets();

    /**
     * Look transition (Left Alt + ,): From shader preset + colour preset ("(current)" = the look on screen when it
     * starts; colour "Off" = cinematic pipeline off) to the To ones in 'seconds'.
     */
    void setTransition(String fromShader, String fromColor, String toShader, String toColor, float seconds);

    /** Starts the transition, goes back when it is at To, turns a running one around (like Left Alt + ,). */
    void toggleTransition();

    boolean transitionRunning();

    /** HDR pipeline with bloom and colour grading. */
    boolean cinematicPipeline();
    void setCinematicPipeline(boolean on);

    /** Built-in, saved (game/grading/*.txt) and mod-registered grading presets. */
    List<String> gradingPresets();

    /** Loads a preset by name (turns the cinematic pipeline on). Returns false if it doesn't exist. */
    boolean loadGradingPreset(String name);

    /** The name of the current grading. */
    String gradingName();

    /**
     * Reads / changes one grading value. Keys are the ones in preset files: exposure, temperature, tint, tonemap
     * (0 none, 1 cinematic, 2 filmic, 3 reinhard, 4 aces), contrast, saturation, vibrance, lift, gamma, gain,
     * shadowHue, shadowAmount, highlightHue, highlightAmount, bloom, bloomThreshold, emissive, vignette, grain,
     * lutStrength. Unknown keys are ignored.
     */
    float grading(String key);
    void setGrading(String key, float value);

    /** Uses a .cube file from game/luts ("" = none). */
    void setLut(String fileName);

    /** The whole grading in the preset file format (can be stored and applied with {@link #applyGrading}). */
    String gradingText();
    void applyGrading(String presetText);
}
