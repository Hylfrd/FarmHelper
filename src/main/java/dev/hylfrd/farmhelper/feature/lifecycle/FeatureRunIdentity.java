package dev.hylfrd.farmhelper.feature.lifecycle;

import dev.hylfrd.farmhelper.failsafe.FailsafeCandidate;

/** Exact macro/world identity shared by feature and failsafe work in one run. */
public record FeatureRunIdentity(long macroGeneration, long worldEpoch) {
    public FeatureRunIdentity {
        if (macroGeneration <= 0L) {
            throw new IllegalArgumentException("macroGeneration must be positive");
        }
        if (worldEpoch < 0L) {
            throw new IllegalArgumentException("worldEpoch must be non-negative");
        }
    }

    public boolean matchesMacro(long candidateGeneration) {
        return macroGeneration == candidateGeneration;
    }

    public FailsafeCandidate.Identity failsafeIdentity() {
        return new FailsafeCandidate.Identity(macroGeneration, worldEpoch);
    }
}
