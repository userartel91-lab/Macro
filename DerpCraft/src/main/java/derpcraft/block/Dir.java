package derpcraft.block;

/** Face directions. Order matches Minecraft: down, up, north(-Z), south(+Z), west(-X), east(+X). */
public final class Dir {
    public static final int DOWN = 0, UP = 1, NORTH = 2, SOUTH = 3, WEST = 4, EAST = 5;
    public static final int[] DX = {0, 0, 0, 0, -1, 1};
    public static final int[] DY = {-1, 1, 0, 0, 0, 0};
    public static final int[] DZ = {0, 0, -1, 1, 0, 0};
    public static final int[] OPPOSITE = {1, 0, 3, 2, 5, 4};
    /** Horizontal directions in clockwise order starting south (matches yaw quarter turns). */
    public static final int[] HORIZONTAL = {SOUTH, WEST, NORTH, EAST};

    private Dir() {}

    /** Rotates a horizontal face by quarter turns clockwise (seen from above). Vertical faces unchanged. */
    public static int rotateY(int face, int quarterTurns) {
        if (face < 2) return face;
        int idx = 0;
        for (int i = 0; i < 4; i++) if (HORIZONTAL[i] == face) idx = i;
        return HORIZONTAL[(idx + quarterTurns) & 3];
    }
}
