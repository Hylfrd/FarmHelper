package dev.hylfrd.farmhelper.control.inventory;

import java.util.Objects;
import java.util.Optional;

/** Structured failure detail. A captured failure permits suppressed diagnostic evidence. */
public record InventoryDiagnostic(
        InventoryOperationToken token,
        InventoryCancelReason reason,
        int stepIndex,
        String message,
        Optional<Failure> failure) {
    public InventoryDiagnostic {
        Objects.requireNonNull(token, "token");
        Objects.requireNonNull(reason, "reason");
        if (stepIndex < 0) {
            throw new IllegalArgumentException("stepIndex must not be negative");
        }
        Objects.requireNonNull(message, "message");
        Objects.requireNonNull(failure, "failure");
    }

    /** Immutable exception metadata; Throwable never becomes part of the domain snapshot. */
    public record Failure(String type, Optional<String> message) {
        public Failure {
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(message, "message");
        }

        public static Failure from(Throwable failure) {
            Objects.requireNonNull(failure, "failure");
            return new Failure(failure.getClass().getName(), Optional.ofNullable(failure.getMessage()));
        }
    }
}
