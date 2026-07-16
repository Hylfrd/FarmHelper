package dev.hylfrd.farmhelper.navigation;

import java.util.Objects;
import java.util.Optional;

/** Immutable status for an active run or its exact terminal result. */
public record NavigationStatus(
        NavigationTicket ticket,
        NavigationRequest request,
        NavigationWorkTicket workTicket,
        Optional<SegmentedSpatialSnapshot> spatialSnapshot,
        Optional<NavigationResult> terminalResult
) {
    public NavigationStatus {
        Objects.requireNonNull(ticket, "ticket");
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(workTicket, "workTicket");
        spatialSnapshot = Objects.requireNonNull(spatialSnapshot, "spatialSnapshot");
        terminalResult = Objects.requireNonNull(terminalResult, "terminalResult");
        if (!ticket.owner().equals(request.owner()) || ticket.worldEpoch() != request.worldEpoch()) {
            throw new IllegalArgumentException("ticket does not identify request");
        }
        if (!workTicket.runTicket().equals(ticket)) {
            throw new IllegalArgumentException("work ticket does not identify run");
        }
        if (spatialSnapshot.isPresent() && !spatialSnapshot.orElseThrow().ticket().equals(ticket)) {
            throw new IllegalArgumentException("spatial snapshot ticket mismatch");
        }
        if (terminalResult.isPresent()
                && !terminalResult.orElseThrow().workTicket().equals(workTicket)) {
            throw new IllegalArgumentException("terminal result work ticket mismatch");
        }
    }

    public boolean active() {
        return terminalResult.isEmpty();
    }

    public NavigationPhase phase() {
        return workTicket.phase();
    }
}
