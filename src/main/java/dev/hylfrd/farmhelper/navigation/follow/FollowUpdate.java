package dev.hylfrd.farmhelper.navigation.follow;

import dev.hylfrd.farmhelper.navigation.NavigationRequest;
import dev.hylfrd.farmhelper.navigation.NavigationTicket;

import java.util.Objects;
import java.util.Optional;

/** Deterministic result of one START-phase follow tick or an explicit cancellation. */
public record FollowUpdate(
        Action action,
        int startTicksUntilRecalculation,
        Optional<NavigationTicket> activeTicket,
        Optional<NavigationRequest> replacementRequest,
        Optional<FollowTerminationReason> terminationReason
) {
    public enum Action {
        WAITING,
        RECALCULATION_DEFERRED,
        RECALCULATED,
        TERMINATED
    }

    public FollowUpdate {
        Objects.requireNonNull(action, "action");
        activeTicket = Objects.requireNonNull(activeTicket, "activeTicket");
        replacementRequest = Objects.requireNonNull(replacementRequest, "replacementRequest");
        terminationReason = Objects.requireNonNull(terminationReason, "terminationReason");
        if (startTicksUntilRecalculation < 0 || startTicksUntilRecalculation > 12) {
            throw new IllegalArgumentException(
                    "startTicksUntilRecalculation must be between zero and twelve");
        }
        boolean valid = switch (action) {
            case WAITING -> startTicksUntilRecalculation > 0
                    && activeTicket.isPresent()
                    && replacementRequest.isEmpty()
                    && terminationReason.isEmpty();
            case RECALCULATION_DEFERRED -> startTicksUntilRecalculation == 12
                    && activeTicket.isPresent()
                    && replacementRequest.isEmpty()
                    && terminationReason.isEmpty();
            case RECALCULATED -> startTicksUntilRecalculation == 12
                    && activeTicket.isPresent()
                    && replacementRequest.isPresent()
                    && terminationReason.isEmpty();
            case TERMINATED -> startTicksUntilRecalculation == 0
                    && activeTicket.isEmpty()
                    && replacementRequest.isEmpty()
                    && terminationReason.isPresent();
        };
        if (!valid) {
            throw new IllegalArgumentException("follow update fields contradict action " + action);
        }
    }

    static FollowUpdate waiting(NavigationTicket ticket, int ticksUntilRecalculation) {
        return new FollowUpdate(Action.WAITING, ticksUntilRecalculation,
                Optional.of(ticket), Optional.empty(), Optional.empty());
    }

    static FollowUpdate deferred(NavigationTicket ticket) {
        return new FollowUpdate(Action.RECALCULATION_DEFERRED, 12,
                Optional.of(ticket), Optional.empty(), Optional.empty());
    }

    static FollowUpdate recalculated(
            NavigationTicket ticket,
            NavigationRequest request
    ) {
        return new FollowUpdate(Action.RECALCULATED, 12,
                Optional.of(ticket), Optional.of(request), Optional.empty());
    }

    static FollowUpdate terminated(FollowTerminationReason reason) {
        return new FollowUpdate(Action.TERMINATED, 0,
                Optional.empty(), Optional.empty(), Optional.of(reason));
    }
}
