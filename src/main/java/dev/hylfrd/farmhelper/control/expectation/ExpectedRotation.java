package dev.hylfrd.farmhelper.control.expectation;

import dev.hylfrd.farmhelper.runtime.snapshot.RotationSnapshot;

import java.util.Objects;

/** Expected yaw/pitch and independent angular tolerances. */
public record ExpectedRotation(
        RotationSnapshot rotation,
        float yawTolerance,
        float pitchTolerance
) implements ExpectedActionPayload {
    public ExpectedRotation {
        Objects.requireNonNull(rotation, "rotation");
        if (!Float.isFinite(yawTolerance) || yawTolerance < 0.0F
                || !Float.isFinite(pitchTolerance) || pitchTolerance < 0.0F) {
            throw new IllegalArgumentException("rotation tolerances must be finite and non-negative");
        }
    }
}
