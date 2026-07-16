package dev.hylfrd.farmhelper.navigation;

import dev.hylfrd.farmhelper.runtime.spatial.SpatialSnapshot;

import java.util.Objects;

/** One independently bounded capture in an ordered logical navigation snapshot. */
public record SpatialSegment(
        int index,
        NavigationWorkTicket workTicket,
        long requestToken,
        SpatialSnapshot snapshot
) {
    public SpatialSegment {
        Objects.requireNonNull(workTicket, "workTicket");
        Objects.requireNonNull(snapshot, "snapshot");
        if (index < 0) {
            throw new IllegalArgumentException("segment index must be non-negative");
        }
        if (requestToken <= 0L) {
            throw new IllegalArgumentException("segment request token must be positive");
        }
        if (snapshot.requestToken() != requestToken) {
            throw new IllegalArgumentException("segment and snapshot request tokens differ");
        }
        if (workTicket.phase() != NavigationPhase.CAPTURING) {
            throw new IllegalArgumentException("spatial segments require a CAPTURING work ticket");
        }
        if (snapshot.worldEpoch() != workTicket.worldEpoch()) {
            throw new IllegalArgumentException("segment snapshot has a stale world epoch");
        }
    }

    public NavigationTicket ticket() {
        return workTicket.runTicket();
    }

    public int capturedCellCount() {
        int count = 0;
        for (var chunk : snapshot.chunks().values()) {
            count = Math.addExact(count, chunk.blocks().size());
        }
        return count;
    }
}
