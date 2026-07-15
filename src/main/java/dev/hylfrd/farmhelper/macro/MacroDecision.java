package dev.hylfrd.farmhelper.macro;

import dev.hylfrd.farmhelper.control.input.InputAction;

import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** One pure macro decision; client adapters alone apply its managed side effects. */
public record MacroDecision(
        Set<InputAction> inputs,
        Optional<MacroRotationRequest> rotation,
        Optional<MacroWarpRequest> warp,
        String status,
        MacroRotationDisposition rotationDisposition
) {
    public MacroDecision {
        Objects.requireNonNull(inputs, "inputs");
        Objects.requireNonNull(rotation, "rotation");
        Objects.requireNonNull(warp, "warp");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(rotationDisposition, "rotationDisposition");
        inputs = Set.copyOf(inputs);
        if (status.isBlank()) {
            throw new IllegalArgumentException("status must not be blank");
        }
        if (rotationDisposition == MacroRotationDisposition.REPLACE && rotation.isEmpty()) {
            throw new IllegalArgumentException("REPLACE requires a rotation request");
        }
        if (rotationDisposition != MacroRotationDisposition.REPLACE && rotation.isPresent()) {
            throw new IllegalArgumentException("rotation request requires REPLACE");
        }
    }

    public MacroDecision(
            Set<InputAction> inputs,
            Optional<MacroRotationRequest> rotation,
            Optional<MacroWarpRequest> warp,
            String status
    ) {
        this(inputs, rotation, warp, status, rotation.isPresent()
                ? MacroRotationDisposition.REPLACE : MacroRotationDisposition.KEEP);
    }

    public static MacroDecision idle(String status) {
        return new MacroDecision(Set.of(), Optional.empty(), Optional.empty(), status);
    }

    public static MacroDecision failClosed(String status) {
        return new MacroDecision(Set.of(), Optional.empty(), Optional.empty(), status,
                MacroRotationDisposition.RELEASE);
    }
}
