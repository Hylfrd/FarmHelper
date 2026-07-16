package dev.hylfrd.farmhelper.navigation;

import java.util.Objects;
import java.util.Optional;

/** Immutable terminal result with mutually exclusive typed failure and cancellation reasons. */
public record NavigationResult(
        NavigationTicket ticket,
        NavigationTerminalState state,
        Optional<NavigationFailureReason> failureReason,
        Optional<NavigationCancellationReason> cancellationReason
) {
    public NavigationResult {
        Objects.requireNonNull(ticket, "ticket");
        Objects.requireNonNull(state, "state");
        failureReason = Objects.requireNonNull(failureReason, "failureReason");
        cancellationReason = Objects.requireNonNull(cancellationReason, "cancellationReason");
        boolean valid = switch (state) {
            case COMPLETED -> failureReason.isEmpty() && cancellationReason.isEmpty();
            case FAILED -> failureReason.isPresent() && cancellationReason.isEmpty();
            case CANCELLED -> failureReason.isEmpty() && cancellationReason.isPresent();
        };
        if (!valid) {
            throw new IllegalArgumentException("terminal state and reasons do not agree");
        }
    }

    public static NavigationResult completed(NavigationTicket ticket) {
        return new NavigationResult(ticket, NavigationTerminalState.COMPLETED,
                Optional.empty(), Optional.empty());
    }

    public static NavigationResult failed(NavigationTicket ticket, NavigationFailureReason reason) {
        return new NavigationResult(ticket, NavigationTerminalState.FAILED,
                Optional.of(Objects.requireNonNull(reason, "reason")), Optional.empty());
    }

    public static NavigationResult cancelled(
            NavigationTicket ticket,
            NavigationCancellationReason reason
    ) {
        return new NavigationResult(ticket, NavigationTerminalState.CANCELLED,
                Optional.empty(), Optional.of(Objects.requireNonNull(reason, "reason")));
    }
}
