package derpcraft.api;

import java.util.ArrayList;
import java.util.List;

/**
 * A Minecraft-style entity model: parts (pivot + rotation) made of boxes with texture coordinates, exactly like
 * ModelRenderer in Minecraft 1.12 - so models from Blockbench ("Java Block/Item... Modded entity") or old mods can be
 * copied number for number. Units are pixels, y points down, the ground is at y = 24.
 *
 * <pre>
 * Model crab = Model.create(64, 32)
 *     .part("body", 0, 19, 0).box(-5, -3, -4, 10, 5, 8, 0, 0)
 *     .part("claw_r", -6, 18, -3).anim(Model.ARM_A).box(-2, -1, -3, 3, 3, 4, 36, 0)
 *     .part("claw_l", 6, 18, -3).anim(Model.ARM_B).box(-1, -1, -3, 3, 3, 4, 36, 8)
 *     .part("legs_r", -4, 21, 0).anim(Model.LEG_A).box(-3, 0, -3, 2, 3, 6, 0, 16)
 *     .part("legs_l", 4, 21, 0).anim(Model.LEG_B).box(1, 0, -3, 2, 3, 6, 16, 16);
 * </pre>
 */
public final class Model {
    /** Part animations. */
    public static final int NONE = 0, HEAD = 1, LEG_A = 2, LEG_B = 3, ARM_A = 4, ARM_B = 5, TAIL = 6, WING_L = 7, WING_R = 8, BODY = 9;

    public final int texWidth, texHeight;
    public final List<Part> parts = new ArrayList<>();
    /** Arms held forward (zombie pose) instead of swinging. */
    public boolean armsForward;
    /** The model is a Minecraft player skin (64x64 or 64x32): any skin texture fits. */
    public boolean playerSkin;

    public static final class Part {
        public final String name;
        public final float px, py, pz;
        public float rx, ry, rz;
        public int anim;
        public final List<float[]> boxes = new ArrayList<>();   // x, y, z, w, h, d, u, v, inflate
        Part(String name, float px, float py, float pz) { this.name = name; this.px = px; this.py = py; this.pz = pz; }
    }

    private Model(int w, int h) { texWidth = w; texHeight = h; }

    /** An empty model for a texture of the given size (usually 64 x 32 or 64 x 64). */
    public static Model create(int texWidth, int texHeight) { return new Model(texWidth, texHeight); }

    private Part last() {
        if (parts.isEmpty()) throw new IllegalStateException("call part(...) first");
        return parts.get(parts.size() - 1);
    }

    /** Starts a new part with its pivot point (the point it rotates around). */
    public Model part(String name, float pivotX, float pivotY, float pivotZ) { parts.add(new Part(name, pivotX, pivotY, pivotZ)); return this; }

    /** Rest rotation of the current part in degrees. */
    public Model rotate(float xDeg, float yDeg, float zDeg) {
        Part p = last();
        p.rx = (float) Math.toRadians(xDeg); p.ry = (float) Math.toRadians(yDeg); p.rz = (float) Math.toRadians(zDeg);
        return this;
    }

    /** How the current part moves: HEAD looks around, LEG_A / LEG_B and ARM_A / ARM_B swing while walking (opposite phases), TAIL wags, WING_L / WING_R flap. */
    public Model anim(int anim) { last().anim = anim; return this; }

    /** A box of the current part: offset from the pivot, size and texture offset (u, v), all in pixels. */
    public Model box(float x, float y, float z, int w, int h, int d, int u, int v) { return box(x, y, z, w, h, d, u, v, 0); }

    /** Box grown by 'inflate' pixels on every side (second skin layer, armour). */
    public Model box(float x, float y, float z, int w, int h, int d, int u, int v, float inflate) {
        last().boxes.add(new float[]{x, y, z, w, h, d, u, v, inflate});
        return this;
    }

    public Model armsForward() { armsForward = true; return this; }

    // ------------------------------------------------------------------ ready-made models
    /** Player-shaped (Steve skin layout 64 x 64, works with any player skin). */
    public static Model humanoid() {
        Model m = create(64, 64);
        m.playerSkin = true;
        m.part("head", 0, 0, 0).anim(HEAD).box(-4, -8, -4, 8, 8, 8, 0, 0).box(-4, -8, -4, 8, 8, 8, 32, 0, 0.5f)
         .part("body", 0, 0, 0).box(-4, 0, -2, 8, 12, 4, 16, 16).box(-4, 0, -2, 8, 12, 4, 16, 32, 0.25f)
         .part("right_arm", -5, 2, 0).anim(ARM_A).box(-3, -2, -2, 4, 12, 4, 40, 16).box(-3, -2, -2, 4, 12, 4, 40, 32, 0.25f)
         .part("left_arm", 5, 2, 0).anim(ARM_B).box(-1, -2, -2, 4, 12, 4, 32, 48).box(-1, -2, -2, 4, 12, 4, 48, 48, 0.25f)
         .part("right_leg", -1.9f, 12, 0).anim(LEG_A).box(-2, 0, -2, 4, 12, 4, 0, 16).box(-2, 0, -2, 4, 12, 4, 0, 32, 0.25f)
         .part("left_leg", 1.9f, 12, 0).anim(LEG_B).box(-2, 0, -2, 4, 12, 4, 16, 48).box(-2, 0, -2, 4, 12, 4, 0, 48, 0.25f);
        return m;
    }

    /** Four-legged animal like the pig (legHeight 6) or the cow (legHeight 12), texture 64 x 32. */
    public static Model quadruped(int legHeight) {
        Model m = create(64, 32);
        float top = 24 - legHeight;
        m.part("head", 0, top - 6, -6).anim(HEAD).box(-4, -4, -8, 8, 8, 8, 0, 0)
         .part("body", 0, top - 7, 2).rotate(90, 0, 0).box(-5, -10, -7, 10, 16, 8, 28, 8)
         .part("leg_back_right", -3, top, 7).anim(LEG_A).box(-2, 0, -2, 4, legHeight, 4, 0, 16)
         .part("leg_back_left", 3, top, 7).anim(LEG_B).box(-2, 0, -2, 4, legHeight, 4, 0, 16)
         .part("leg_front_right", -3, top, -5).anim(LEG_B).box(-2, 0, -2, 4, legHeight, 4, 0, 16)
         .part("leg_front_left", 3, top, -5).anim(LEG_A).box(-2, 0, -2, 4, legHeight, 4, 0, 16);
        return m;
    }

    /** A bouncing cube (slime-like), 'size' pixels, texture 64 x 32. */
    public static Model cube(int size) {
        Model m = create(64, 32);
        float h = size / 2f;
        m.part("body", 0, 24 - h, 0).anim(BODY).box(-h, -h, -h, size, size, size, 0, 0);
        return m;
    }
}
