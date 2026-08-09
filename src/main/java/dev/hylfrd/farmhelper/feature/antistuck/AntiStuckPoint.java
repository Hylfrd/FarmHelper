package dev.hylfrd.farmhelper.feature.antistuck;

/** Finite Minecraft-free point used for side and target calculations. */
public record AntiStuckPoint(double x, double y, double z) {
    public AntiStuckPoint {
        requireFinite(x, "x");
        requireFinite(y, "y");
        requireFinite(z, "z");
    }

    public AntiStuckPoint add(AntiStuckPoint other) {
        if (other == null) {
            throw new NullPointerException("other");
        }
        return new AntiStuckPoint(x + other.x, y + other.y, z + other.z);
    }

    private static void requireFinite(double value, String name) {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException(name + " must be finite");
        }
    }
}
