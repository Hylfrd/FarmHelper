package dev.hylfrd.farmhelper.feature.antistuck;

/** Immutable player position and yaw needed by the upstream horizontal escape geometry. */
public record AntiStuckPlayerPose(
        double x,
        double y,
        double z,
        double yaw
) {
    public AntiStuckPlayerPose {
        requireFinite(x, "x");
        requireFinite(y, "y");
        requireFinite(z, "z");
        requireFinite(yaw, "yaw");
    }

    private static void requireFinite(double value, String name) {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException(name + " must be finite");
        }
    }
}
