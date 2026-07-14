package dev.hylfrd.farmhelper.control.rotation;

import dev.hylfrd.farmhelper.control.input.ControlOwner;

import java.util.Objects;
import java.util.Optional;

/** Immutable diagnostic state that never exposes the controller's mutable timer or lease. */
public record RotationSnapshot(
        boolean active,
        boolean paused,
        Optional<ControlOwner> owner,
        Optional<Float> targetYaw,
        Optional<Float> targetPitch,
        float progress,
        Optional<RotationTerminalReason> terminalReason,
        long revision) {
    public RotationSnapshot {
        owner = Objects.requireNonNull(owner, "owner");
        targetYaw = Objects.requireNonNull(targetYaw, "targetYaw");
        targetPitch = Objects.requireNonNull(targetPitch, "targetPitch");
        terminalReason = Objects.requireNonNull(terminalReason, "terminalReason");
        if (!Float.isFinite(progress) || progress < 0.0F || progress > 1.0F) {
            throw new IllegalArgumentException("progress must be finite and in [0, 1]");
        }
        if (paused && !active) {
            throw new IllegalArgumentException("Only an active rotation can be paused");
        }
        if (active && (owner.isEmpty() || targetYaw.isEmpty() || targetPitch.isEmpty())) {
            throw new IllegalArgumentException("Active rotation diagnostics require owner and target");
        }
        if (active && terminalReason.isPresent()) {
            throw new IllegalArgumentException("Active rotation cannot have a terminal reason");
        }
    }

    public static RotationSnapshot idle() {
        return new RotationSnapshot(
                false,
                false,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                0.0F,
                Optional.empty(),
                0L);
    }

    public boolean movementBlocked() {
        return active;
    }
}
