package dev.hylfrd.farmhelper.runtime.snapshot;

/** Exact player coordinates. Zero is a valid value and never means missing. */
public record PositionSnapshot(double x, double y, double z) {
    public PositionSnapshot {
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
