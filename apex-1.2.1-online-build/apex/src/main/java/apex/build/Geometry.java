package apex.build;

/** Pure coordinate math for schematic rotation (no Minecraft classes, unit-testable). */
public final class Geometry {
    private Geometry() {}

    public static int norm(int quarterTurnsCw) { return ((quarterTurnsCw % 4) + 4) % 4; }

    /**
     * Rotates a cell of an sx-by-sz (x,z) footprint clockwise (seen from above) by quarterTurnsCw * 90 degrees.
     * The result is relative to the rotated footprint's own minimum corner, i.e. it stays within [0, newSx) x [0, newSz).
     * Matches BlockRotation.CLOCKWISE_90: east (+x) turns into south (+z).
     */
    public static int[] rotate(int x, int z, int sx, int sz, int quarterTurnsCw) {
        return switch (norm(quarterTurnsCw)) {
            case 0 -> new int[]{x, z};
            case 1 -> new int[]{sz - 1 - z, x};
            case 2 -> new int[]{sx - 1 - x, sz - 1 - z};
            default -> new int[]{z, sx - 1 - x};
        };
    }

    public static int[] rotatedSize(int sx, int sz, int quarterTurnsCw) {
        return norm(quarterTurnsCw) % 2 == 0 ? new int[]{sx, sz} : new int[]{sz, sx};
    }
}
