package dev.hylfrd.farmhelper.control.rotation;

import dev.hylfrd.farmhelper.control.input.ControlOwner;

import java.util.Objects;

/** Immutable callback value for either completion or cancellation. */
public record RotationResult(
        ControlOwner owner,
        float targetYaw,
        float targetPitch,
        float progress,
        RotationTerminalReason reason) {
    public RotationResult {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(reason, "reason");
        if (!Float.isFinite(targetYaw) || !Float.isFinite(targetPitch) || !Float.isFinite(progress)) {
            throw new IllegalArgumentException("Rotation result values must be finite");
        }
    }

    public boolean completed() {
        return reason == RotationTerminalReason.COMPLETED;
    }
}
