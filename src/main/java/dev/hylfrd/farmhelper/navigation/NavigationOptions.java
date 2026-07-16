package dev.hylfrd.farmhelper.navigation;

import java.util.Objects;

/** Complete parity-facing options for one run; there are no ambient navigation setters. */
public record NavigationOptions(
        NavigationMode mode,
        boolean follow,
        boolean smooth,
        double yOffset,
        double completionThreshold,
        boolean sprint,
        NavigationRotationPolicy rotationPolicy
) {
    public NavigationOptions {
        Objects.requireNonNull(mode, "mode");
        Objects.requireNonNull(rotationPolicy, "rotationPolicy");
        if (!Double.isFinite(yOffset)) {
            throw new IllegalArgumentException("yOffset must be finite");
        }
        if (!Double.isFinite(completionThreshold) || completionThreshold < 0.0D) {
            throw new IllegalArgumentException("completionThreshold must be finite and non-negative");
        }
    }

    public static NavigationOptions fly() {
        return new NavigationOptions(
                NavigationMode.FLY, false, true, 0.0D, 1.0D, true,
                NavigationRotationPolicy.FACE_PATH);
    }
}
