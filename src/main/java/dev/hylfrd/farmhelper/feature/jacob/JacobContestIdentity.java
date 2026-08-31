package dev.hylfrd.farmhelper.feature.jacob;

/** Caller-assigned identity that prevents one contest's count from leaking into another. */
public record JacobContestIdentity(long value) {
    public JacobContestIdentity {
        if (value <= 0L) {
            throw new IllegalArgumentException("contest identity must be positive");
        }
    }
}
