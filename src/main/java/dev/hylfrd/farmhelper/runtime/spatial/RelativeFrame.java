package dev.hylfrd.farmhelper.runtime.spatial;

/**
 * Integer right/up/forward frame derived from player yaw. Right is the player's right-hand side.
 * Cardinal ties advance to the larger base angle, including 315 degrees wrapping to zero.
 */
public record RelativeFrame(int rightX, int rightZ, int forwardX, int forwardZ) {
    public RelativeFrame {
        if (rightX != -forwardZ || rightZ != forwardX) {
            throw new IllegalArgumentException("right must be the clockwise screen-space basis of forward");
        }
        if (forwardX == 0 && forwardZ == 0) {
            throw new IllegalArgumentException("forward must not be zero");
        }
        if (Math.abs(forwardX) > 1 || Math.abs(forwardZ) > 1) {
            throw new IllegalArgumentException("frame basis must be an integer grid step");
        }
    }

    public static RelativeFrame cardinal(double yaw) {
        double normalized = normalizeYaw(yaw);
        int quarter = ((int) Math.floor((normalized + 45.0D) / 90.0D)) & 3;
        return switch (quarter) {
            case 0 -> new RelativeFrame(-1, 0, 0, 1);
            case 1 -> new RelativeFrame(0, -1, -1, 0);
            case 2 -> new RelativeFrame(1, 0, 0, -1);
            case 3 -> new RelativeFrame(0, 1, 1, 0);
            default -> throw new IllegalStateException("unreachable quarter");
        };
    }

    /** Independent eight-direction integer frame for diagonal sugar-cane geometry. */
    public static RelativeFrame eightWay(double yaw) {
        double normalized = normalizeYaw(yaw);
        double remainder = normalized % 45.0D;
        int octant = (int) (remainder <= 22.5D
                ? Math.floor(normalized / 45.0D)
                : Math.ceil(normalized / 45.0D)) & 7;
        int[][] forwards = {
                {0, 1}, {-1, 1}, {-1, 0}, {-1, -1},
                {0, -1}, {1, -1}, {1, 0}, {1, 1}
        };
        int forwardX = forwards[octant][0];
        int forwardZ = forwards[octant][1];
        return new RelativeFrame(-forwardZ, forwardX, forwardX, forwardZ);
    }

    public BlockPosition blockAt(
            double playerX,
            double playerY,
            double playerZ,
            int right,
            int up,
            int forward
    ) {
        requireFinite(playerX, "playerX");
        requireFinite(playerY, "playerY");
        requireFinite(playerZ, "playerZ");
        int x = floorToInt(playerX + rightX * (double) right + forwardX * (double) forward);
        int y = Math.addExact(gridAnchorY(playerY), up);
        int z = floorToInt(playerZ + rightZ * (double) right + forwardZ * (double) forward);
        return new BlockPosition(x, y, z);
    }

    public static int gridAnchorY(double y) {
        requireFinite(y, "y");
        double floor = Math.floor(y);
        double fraction = y - floor;
        return floorToInt(fraction > 0.7D ? Math.ceil(y) : floor);
    }

    public static double normalizeYaw(double yaw) {
        requireFinite(yaw, "yaw");
        double normalized = yaw % 360.0D;
        return normalized < 0.0D ? normalized + 360.0D : normalized;
    }

    private static int floorToInt(double value) {
        double floor = Math.floor(value);
        if (floor < Integer.MIN_VALUE || floor > Integer.MAX_VALUE) {
            throw new ArithmeticException("coordinate lies outside the integer block range");
        }
        return (int) floor;
    }

    private static void requireFinite(double value, String name) {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException(name + " must be finite");
        }
    }
}
