package dev.hylfrd.farmhelper.navigation;

import java.util.Objects;
import java.util.Optional;

/** Exact-generation capability; stale handles cannot mutate or replace a newer run. */
public final class NavigationHandle {
    private final NavigationController controller;
    private final NavigationTicket ticket;
    private volatile NavigationStatus terminalStatus;

    NavigationHandle(NavigationController controller, NavigationTicket ticket) {
        this.controller = Objects.requireNonNull(controller, "controller");
        this.ticket = Objects.requireNonNull(ticket, "ticket");
    }

    public NavigationTicket ticket() {
        return ticket;
    }

    public Optional<NavigationStatus> status() {
        NavigationStatus terminal = terminalStatus;
        return terminal == null ? controller.status(ticket) : Optional.of(terminal);
    }

    public boolean advance(NavigationPhase expected, NavigationPhase next) {
        return controller.advance(ticket, expected, next);
    }

    public boolean acceptCapture(SegmentedSpatialSnapshot snapshot) {
        return controller.acceptCapture(ticket, snapshot);
    }

    public boolean complete() {
        return controller.complete(ticket);
    }

    public boolean fail(NavigationFailureReason reason) {
        return controller.fail(ticket, reason);
    }

    public boolean cancel() {
        return cancel(NavigationCancellationReason.OWNER_REQUESTED);
    }

    public boolean cancel(NavigationCancellationReason reason) {
        return controller.cancel(ticket, reason);
    }

    public Optional<NavigationHandle> replace(
            NavigationRequest request,
            NavigationStartObservation observation
    ) {
        return controller.replace(ticket, request, observation);
    }

    void terminated(NavigationStatus status) {
        if (!status.ticket().equals(ticket) || status.terminalResult().isEmpty()) {
            throw new IllegalArgumentException("terminal status does not identify this handle");
        }
        terminalStatus = status;
    }
}
