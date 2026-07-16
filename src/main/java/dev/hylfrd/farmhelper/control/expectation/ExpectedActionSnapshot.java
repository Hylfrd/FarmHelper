package dev.hylfrd.farmhelper.control.expectation;

import java.util.List;
import java.util.Objects;

/** Immutable reader view after deterministic expiry pruning at one monotonic instant. */
public record ExpectedActionSnapshot(long capturedAtNanos, List<ExpectedAction> actions) {
    public ExpectedActionSnapshot {
        Objects.requireNonNull(actions, "actions");
        actions = List.copyOf(actions);
    }
}
