package dev.hylfrd.farmhelper.runtime.snapshot;

/** Player velocity in blocks per tick. */
public record MotionSnapshot(double x, double y, double z) {
    public MotionSnapshot {
        requireFinite(x, "x");
        requireFinite(y, "y");
        requireFinite(z, "z");
    }

    private static void requireFinite(double value, String name) {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException(name + " must be finite");
        }
    }
}
