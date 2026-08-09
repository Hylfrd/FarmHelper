package dev.hylfrd.farmhelper.feature.antistuck;

import dev.hylfrd.farmhelper.control.input.ControlOwner;

import java.util.Objects;

/** Exact owner, lifecycle, and run-revision identity for one AntiStuck run. */
public record AntiStuckIdentity(
        ControlOwner owner,
        long macroGeneration,
        long worldEpoch,
        long runRevision
) {
    /** Keeps the original constructor as the first revision of a lifecycle. */
    public AntiStuckIdentity(
            ControlOwner owner,
            long macroGeneration,
            long worldEpoch
    ) {
        this(owner, macroGeneration, worldEpoch, 1L);
    }

    public AntiStuckIdentity {
        Objects.requireNonNull(owner, "owner");
        if (macroGeneration <= 0L) {
            throw new IllegalArgumentException("macroGeneration must be positive");
        }
        if (worldEpoch < 0L) {
            throw new IllegalArgumentException("worldEpoch must be non-negative");
        }
        if (runRevision <= 0L) {
            throw new IllegalArgumentException("runRevision must be positive");
        }
    }

    public boolean sameLifecycle(AntiStuckIdentity other) {
        Objects.requireNonNull(other, "other");
        return owner.equals(other.owner)
                && macroGeneration == other.macroGeneration
                && worldEpoch == other.worldEpoch;
    }
}
