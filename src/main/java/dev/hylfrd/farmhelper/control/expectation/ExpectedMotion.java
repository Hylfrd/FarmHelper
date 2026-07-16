package dev.hylfrd.farmhelper.control.expectation;

import dev.hylfrd.farmhelper.runtime.snapshot.MotionSnapshot;

import java.util.Objects;

/** Expected velocity and component tolerance for an owned movement interval. */
public record ExpectedMotion(MotionSnapshot motion, double tolerance) implements ExpectedActionPayload {
    public ExpectedMotion {
        Objects.requireNonNull(motion, "motion");
        if (!Double.isFinite(tolerance) || tolerance < 0.0D) {
            throw new IllegalArgumentException("tolerance must be finite and non-negative");
        }
    }
}
