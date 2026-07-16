package dev.hylfrd.farmhelper.navigation;

import dev.hylfrd.farmhelper.runtime.spatial.SpaceStatus;

import java.util.List;
import java.util.Objects;

/** Conservative result retaining why navigation may or may not traverse a body volume. */
public record SpaceEvidence(
        SpaceStatus status,
        SpaceEvidenceReason reason,
        List<Integer> segmentIndexes
) {
    public SpaceEvidence {
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(reason, "reason");
        Objects.requireNonNull(segmentIndexes, "segmentIndexes");
        segmentIndexes = List.copyOf(segmentIndexes);
        SpaceStatus expected = switch (reason) {
            case PASSABLE -> SpaceStatus.PASSABLE;
            case COLLISION, FLUID_OBSTRUCTION, NO_SUPPORT -> SpaceStatus.BLOCKED;
            case UNKNOWN_EVIDENCE, MISSING_EVIDENCE, UNLOADED, COLLISION_ERROR,
                    SEGMENT_GAP, CONFLICT, OUTSIDE_BOUNDS, STALE_TICKET,
                    QUERY_TOO_LARGE -> SpaceStatus.UNKNOWN;
        };
        if (status != expected) {
            throw new IllegalArgumentException(
                    "space status " + status + " is incompatible with reason " + reason);
        }
    }

    public boolean traversable() {
        return status == SpaceStatus.PASSABLE && reason == SpaceEvidenceReason.PASSABLE;
    }
}
