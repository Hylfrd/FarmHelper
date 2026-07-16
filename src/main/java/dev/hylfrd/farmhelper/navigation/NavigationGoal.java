package dev.hylfrd.farmhelper.navigation;

/** Immutable unadjusted destination. Request options apply the vertical offset exactly once. */
public record NavigationGoal(double x, double y, double z) {
    public NavigationGoal {
        requireFinite(x, "x");
        requireFinite(y, "y");
        requireFinite(z, "z");
    }

    public NavigationGoal withYOffset(double offset) {
        requireFinite(offset, "offset");
        double adjusted = y + offset;
        requireFinite(adjusted, "adjusted y");
        return new NavigationGoal(x, adjusted, z);
    }

    private static void requireFinite(double value, String name) {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException(name + " must be finite");
        }
    }
}
