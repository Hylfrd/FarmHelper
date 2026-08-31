package dev.hylfrd.farmhelper.feature.reconnect;

import java.util.Objects;
import java.util.OptionalLong;

/** Immutable result of a start, tick, or manual-cancel request. */
public record ReconnectDecision(
        ReconnectOutcome outcome,
        ReconnectReason reason,
        ReconnectState state,
        ReconnectAction action,
        ReconnectActionStatus actionStatus,
        OptionalLong dueAtMillis,
        OptionalLong deadlineAtMillis,
        int connectionAttempts
) {
    public ReconnectDecision {
        Objects.requireNonNull(outcome, "outcome");
        Objects.requireNonNull(reason, "reason");
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(actionStatus, "actionStatus");
        Objects.requireNonNull(dueAtMillis, "dueAtMillis");
        Objects.requireNonNull(deadlineAtMillis, "deadlineAtMillis");
        if (connectionAttempts < 0) {
            throw new IllegalArgumentException("connectionAttempts must be non-negative");
        }
        if (dueAtMillis.isPresent() && dueAtMillis.getAsLong() < 0L) {
            throw new IllegalArgumentException("dueAtMillis must be non-negative");
        }
        if (deadlineAtMillis.isPresent() && deadlineAtMillis.getAsLong() < 0L) {
            throw new IllegalArgumentException("deadlineAtMillis must be non-negative");
        }
        boolean running = outcome == ReconnectOutcome.RUNNING;
        if (running != (state != ReconnectState.STOPPED)) {
            throw new IllegalArgumentException("only a running decision may have an active state");
        }
        if (running != (dueAtMillis.isPresent() && deadlineAtMillis.isPresent())) {
            throw new IllegalArgumentException("active timing must exist exactly while running");
        }
        if ((action == ReconnectAction.NONE)
                != (actionStatus == ReconnectActionStatus.NOT_REQUESTED)) {
            throw new IllegalArgumentException("action and actionStatus do not agree");
        }
    }
}
