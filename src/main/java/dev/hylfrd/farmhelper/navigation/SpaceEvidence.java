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
        if ((status == SpaceStatus.PASSABLE) != (reason == SpaceEvidenceReason.PASSABLE)) {
            throw new IllegalArgumentException("only fully known PASSABLE evidence is traversable");
        }
    }

    public boolean traversable() {
        return status == SpaceStatus.PASSABLE && reason == SpaceEvidenceReason.PASSABLE;
    }
}
