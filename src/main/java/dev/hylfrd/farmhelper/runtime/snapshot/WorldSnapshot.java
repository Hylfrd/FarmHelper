package dev.hylfrd.farmhelper.runtime.snapshot;

import java.util.Objects;

/** Immutable world details; world existence is represented by the enclosing observation. */
public record WorldSnapshot(long epoch, Observation<ResourceIdentifier> dimension) {
    public WorldSnapshot {
        if (epoch < 0L) {
            throw new IllegalArgumentException("epoch must be non-negative");
        }
        Objects.requireNonNull(dimension, "dimension");
    }

    /** Compatibility constructor for pure fixtures that do not model lifecycle epochs. */
    public WorldSnapshot(Observation<ResourceIdentifier> dimension) {
        this(0L, dimension);
    }
}
