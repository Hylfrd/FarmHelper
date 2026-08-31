package dev.hylfrd.farmhelper.feature.jacob;

import java.util.Objects;

/** Immutable, normalized evidence for one crop count within one explicit contest identity. */
public record JacobContestEvidence(
        JacobContestIdentity contest,
        JacobCrop crop,
        long collectedCount,
        long sequence
) {
    public JacobContestEvidence {
        Objects.requireNonNull(contest, "contest");
        Objects.requireNonNull(crop, "crop");
        if (collectedCount < 0L) {
            throw new IllegalArgumentException("collected count must be non-negative");
        }
        if (sequence < 0L) {
            throw new IllegalArgumentException("sequence must be non-negative");
        }
    }
}
