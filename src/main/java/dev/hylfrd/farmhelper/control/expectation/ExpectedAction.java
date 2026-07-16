package dev.hylfrd.farmhelper.control.expectation;

import dev.hylfrd.farmhelper.control.input.ControlOwner;

import java.util.Objects;

/** Immutable typed expectation entry with exact run identity and validity interval. */
public record ExpectedAction(
        ActionToken token,
        ControlOwner owner,
        long generation,
        long worldEpoch,
        long validFromNanos,
        long deadlineNanos,
        ExpectedActionPayload payload
) {
    public ExpectedAction {
        Objects.requireNonNull(token, "token");
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(payload, "payload");
        if (generation <= 0L) {
            throw new IllegalArgumentException("generation must be positive");
        }
        if (worldEpoch < 0L) {
            throw new IllegalArgumentException("worldEpoch must be non-negative");
        }
        if (deadlineNanos <= validFromNanos) {
            throw new IllegalArgumentException("deadline must be after validFrom");
        }
    }

    public boolean activeAt(long nowNanos) {
        return nowNanos >= validFromNanos && nowNanos < deadlineNanos;
    }

    public boolean expiredAt(long nowNanos) {
        return nowNanos >= deadlineNanos;
    }
}
