package dev.hylfrd.farmhelper.navigation;

import java.util.Objects;
import java.util.Optional;

/** Immutable status for an active run or its exact terminal result. */
public record NavigationStatus(
        NavigationTicket ticket,
        NavigationRequest request,
        NavigationPhase phase,
        Optional<SegmentedSpatialSnapshot> spatialSnapshot,
        Optional<NavigationResult> terminalResult
) {
    public NavigationStatus {
        Objects.requireNonNull(ticket, "ticket");
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(phase, "phase");
        spatialSnapshot = Objects.requireNonNull(spatialSnapshot, "spatialSnapshot");
        terminalResult = Objects.requireNonNull(terminalResult, "terminalResult");
        if (!ticket.owner().equals(request.owner()) || ticket.worldEpoch() != request.worldEpoch()) {
            throw new IllegalArgumentException("ticket does not identify request");
        }
        if (spatialSnapshot.isPresent() && !spatialSnapshot.orElseThrow().ticket().equals(ticket)) {
            throw new IllegalArgumentException("spatial snapshot ticket mismatch");
        }
        if (terminalResult.isPresent() && !terminalResult.orElseThrow().ticket().equals(ticket)) {
            throw new IllegalArgumentException("terminal result ticket mismatch");
        }
    }

    public boolean active() {
        return terminalResult.isEmpty();
    }
}
