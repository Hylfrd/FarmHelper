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
        MacroRotationDisposition rotationDisposition,
        Optional<MacroRecoveryRequest> recovery
) {
    public MacroDecision {
        Objects.requireNonNull(inputs, "inputs");
        Objects.requireNonNull(rotation, "rotation");
        Objects.requireNonNull(warp, "warp");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(rotationDisposition, "rotationDisposition");
        Objects.requireNonNull(recovery, "recovery");
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
        if (recovery.isPresent() && (!inputs.isEmpty() || rotation.isPresent() || warp.isPresent()
                || rotationDisposition != MacroRotationDisposition.RELEASE)) {
            throw new IllegalArgumentException("recovery handoff must release every active control");
        }
    }

    public MacroDecision(
            Set<InputAction> inputs,
            Optional<MacroRotationRequest> rotation,
            Optional<MacroWarpRequest> warp,
            String status,
            MacroRotationDisposition rotationDisposition
    ) {
        this(inputs, rotation, warp, status, rotationDisposition, Optional.empty());
    }

    public MacroDecision(
            Set<InputAction> inputs,
            Optional<MacroRotationRequest> rotation,
            Optional<MacroWarpRequest> warp,
            String status
    ) {
        this(inputs, rotation, warp, status, rotation.isPresent()
                ? MacroRotationDisposition.REPLACE : MacroRotationDisposition.KEEP,
                Optional.empty());
    }

    public static MacroDecision idle(String status) {
        return new MacroDecision(Set.of(), Optional.empty(), Optional.empty(), status);
    }

    public static MacroDecision failClosed(String status) {
        return new MacroDecision(Set.of(), Optional.empty(), Optional.empty(), status,
                MacroRotationDisposition.RELEASE);
    }

    public static MacroDecision recoveryHandoff(String status, MacroRecoveryReason reason) {
        return new MacroDecision(Set.of(), Optional.empty(), Optional.empty(), status,
                MacroRotationDisposition.RELEASE,
                Optional.of(new MacroRecoveryRequest(reason)));
    }
}
