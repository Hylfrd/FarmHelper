package dev.hylfrd.farmhelper.feature.antistuck;

import java.util.Objects;
import java.util.OptionalInt;
import java.util.OptionalLong;

/**
 * Immutable output from the controller; it contains no client or control mutation.
 *
 * <p>{@code rewarpRequested} is only a terminal output signal for the current recovery boundary.
 * Warp selection and execution belong to a later integration owner.</p>
 */
public record AntiStuckDecision(
        AntiStuckDecisionKind kind,
        AntiStuckDecisionReason reason,
        AntiStuckState state,
        AntiStuckInputIntent inputIntent,
        OptionalLong dueAtMillis,
        OptionalInt scheduledDelayMillis,
        boolean rewarpRequested,
        int unstuckTries,
        int lagBackCounter
) {
    public AntiStuckDecision {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(reason, "reason");
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(inputIntent, "inputIntent");
        Objects.requireNonNull(dueAtMillis, "dueAtMillis");
        Objects.requireNonNull(scheduledDelayMillis, "scheduledDelayMillis");
        if (dueAtMillis.isPresent() && dueAtMillis.getAsLong() < 0L) {
            throw new IllegalArgumentException("dueAtMillis must be non-negative");
        }
        if (scheduledDelayMillis.isPresent() && scheduledDelayMillis.getAsInt() < 0) {
            throw new IllegalArgumentException("scheduled delay must be non-negative");
        }
        if (unstuckTries < 0) {
            throw new IllegalArgumentException("unstuckTries must be non-negative");
        }
        if (lagBackCounter < 0) {
            throw new IllegalArgumentException("lagBackCounter must be non-negative");
        }
        if (rewarpRequested && kind != AntiStuckDecisionKind.REWARP) {
            throw new IllegalArgumentException("only a REWARP decision may request rewarp");
        }
    }

    public boolean terminal() {
        return kind == AntiStuckDecisionKind.REWARP
                || kind == AntiStuckDecisionKind.FAIL_CLOSED
                || kind == AntiStuckDecisionKind.TERMINAL
                || (kind == AntiStuckDecisionKind.STOPPED
                && state == AntiStuckState.DISABLE);
    }
}
