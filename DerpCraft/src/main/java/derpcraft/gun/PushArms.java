package derpcraft.gun;

import derpcraft.util.Mat4;

/**
 * The first person arms of someone pushing a car: both arms stretched straight forward, palms flat at chest height, leaning
 * into it (a small rhythm with the pushing). Built like {@link FistAnimator}'s pose (same arm boxes, so the renderer draws
 * them with the same code). View space: camera at the origin looking to -Z, +X right, +Y up, 1 unit = 1 block.
 */
public final class PushArms {
    public final FistAnimator.Pose pose = new FistAnimator.Pose();
    private static final float[] HAND_R = {0.17f, -0.21f, -0.62f}, HAND_L = {-0.17f, -0.21f, -0.62f};
    private static final float[] REST_R = {0.50f, -0.80f, 0.12f}, REST_L = {-0.50f, -0.78f, 0.10f};
    private static final float[] SHOULDER_R = {0.26f, -0.38f, 0.30f}, SHOULDER_L = {-0.26f, -0.38f, 0.30f};

    /** The pose for 'amount' (0 = arms down out of the picture, 1 = pushing) and the pushing rhythm 'phase' (radians). */
    public FistAnimator.Pose frame(float amount, float phase) {
        float a = Math.max(0, Math.min(1, amount));
        a = a * a * (3 - 2 * a);
        float sway = (float) Math.sin(phase) * 0.018f * a;
        arm(pose.rightArm, pose.rightHand, REST_R, HAND_R, SHOULDER_R, a, sway, -1);
        arm(pose.leftArm, pose.leftHand, REST_L, HAND_L, SHOULDER_L, a, sway, 1);
        pose.up = a;
        return pose;
    }

    private static void arm(Mat4 out, float[] palm, float[] rest, float[] push, float[] shoulder, float a, float sway, int side) {
        float[] hand = new float[3], to = new float[3];
        for (int i = 0; i < 3; i++) hand[i] = rest[i] + (push[i] - rest[i]) * a;
        hand[2] += sway;
        for (int i = 0; i < 3; i++) to[i] = rest[i] + (shoulder[i] - rest[i]) * a;
        GunAnimator.armMatrix(out, hand, to, new float[3], new float[]{0, 0, 0}, FistAnimator.PIX, 1, 1, palm);
        out.rotate((float) Math.toRadians(side * 8 * a), 0, 1, 0);       // the palms flat on the car, turned a little in
    }
}
