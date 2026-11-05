package derpcraft.api;

/**
 * The film camera (the same one the F9 panel controls). Get it with {@link GameApi#camera()}.
 * Distances are in blocks (= metres), angles in degrees, focal lengths in mm on a 24 mm high sensor.
 * Smooth values (roll, zoom, focus) glide towards what you set; use the {@code ...Now} variants to jump.
 *
 * <pre>
 * // a slow dolly-zoom style push in, driven every frame
 * ctx.onFrame((game, dt) -> {
 *     CameraApi cam = game.camera();
 *     if (cam.mode().equals("free")) cam.setFocalLength(cam.focalLength() + dt * 10);
 * });
 * </pre>
 */
public interface CameraApi {
    // ------------------------------------------------------------------ mode and position
    /** "player", "free", "orbit" or "path". */
    String mode();

    /** Back to the normal player view (stops free camera, orbit and path playback). */
    void playerView();

    /** Detaches the camera at the current view (like V). */
    void freeCamera();

    /** Detaches the camera at a given place and direction. */
    void freeCamera(double x, double y, double z, float yaw, float pitch);

    /** Current camera position and rotation (in any mode, including first/third person). */
    double x();
    double y();
    double z();
    float yaw();
    float pitch();

    /** Moves / turns the free camera (switches to free mode when needed). */
    void setPosition(double x, double y, double z);
    void setRotation(float yaw, float pitch);

    /** Makes the free camera look at a point. */
    void lookAt(double x, double y, double z);

    /** Free camera fly speed (blocks per second) and smoothing (0 = instant .. 0.95 = very soft). */
    void setFlySpeed(float blocksPerSecond, float smoothing);

    /** Orbit around a point: radius, height above the point, speed in degrees per second (negative = other way). */
    void orbit(double x, double y, double z, float radius, float height, float degreesPerSecond);

    /** Orbit around the player; the centre follows the player. */
    void orbitPlayer(float radius, float height, float degreesPerSecond);

    /** Tripod mode: the free / orbit camera stays and the keyboard and mouse control the player (like P). */
    void setTripod(boolean on);
    boolean tripod();

    // ------------------------------------------------------------------ lens
    /** Current focal length in mm (derived from the FOV option when no lens is set). */
    float focalLength();
    /** Zooms smoothly to 'mm' (8..300); 0 = back to the FOV option. */
    void setFocalLength(float mm);
    void setFocalLengthNow(float mm);

    /** Horizon angle (Dutch angle), degrees. */
    float roll();
    void setRoll(float degrees);
    void setRollNow(float degrees);

    /** Anamorphic squeeze 1..2 (1 = spherical lens): oval bokeh, stronger horizontal flares. */
    void setAnamorphic(float squeeze);

    /** Letterbox aspect: 0 = off, or 2.39, 2.0, 1.85, 1.33. */
    void setLetterbox(float aspect);

    /** Lens distortion -1..1 (positive = barrel) and chromatic aberration 0..1. */
    void setDistortion(float amount);
    void setChromaticAberration(float amount);

    /** Handheld camera shake: amount 0..1, speed multiplier. */
    void setShake(float amount, float speed);

    // ------------------------------------------------------------------ focus
    void setDepthOfField(boolean on);
    boolean depthOfField();

    /** f-number, 0.95..22. */
    void setAperture(float fNumber);

    /** "manual", "center" or "player". */
    void setAutofocus(String mode);

    /** How fast autofocus / focus pulls move (0.3 = slow cinematic pull .. 20 = snap). */
    void setAutofocusSpeed(float speed);

    /** Manual focus distance in blocks; switches autofocus to manual. The lens pulls focus at the AF speed. */
    void setFocusDistance(float blocks);

    /** The focus distance right now (after smoothing). */
    float focusDistance();

    /** Bokeh size multiplier 0.25..4. */
    void setBokehScale(float scale);

    // ------------------------------------------------------------------ effects
    /** Anamorphic streak flares from bright lights: strength 0..1, length 0..1, color "blue", "amber", "neutral" or "purple". */
    void setFlares(float strength, float length, String color);

    /** Lens ghosts and halo, 0..1. */
    void setGhosts(float strength);

    /** Camera motion blur, 0..1 (1 = 360 degree shutter). */
    void setMotionBlur(float amount);

    /** Rule of thirds overlay. */
    void setGrid(boolean on);

    // ------------------------------------------------------------------ paths
    /** Adds a keyframe at the current camera (position, rotation, roll, focal length, focus). */
    void addKeyframe();

    /** Adds a keyframe with explicit values (focalMm 0 = current lens, focus in blocks). */
    void addKeyframe(double x, double y, double z, float yaw, float pitch, float roll, float focalMm, float focus);

    int keyframeCount();
    void removeLastKeyframe();
    void clearKeyframes();

    /**
     * Plays the path. Easing: "linear", "smooth", "in" or "out". evenSpeed = constant speed along the curve
     * (false = equal time between keyframes). Returns false with fewer than 2 keyframes.
     */
    boolean playPath(float seconds, boolean loop, String easing, boolean evenSpeed);
    void stopPath();
    boolean pathPlaying();

    /** 0..1 while playing. */
    float pathProgress();

    /** Point on the current path at t (0..1): {x, y, z, yaw, pitch, roll, focalMm, focus}. */
    double[] samplePath(double t);

    /** Saves camera settings and the path of the current world. */
    void save();
}
