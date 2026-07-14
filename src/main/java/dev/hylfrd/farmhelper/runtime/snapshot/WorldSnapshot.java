package dev.hylfrd.farmhelper.runtime.snapshot;

import java.util.Objects;

/** Immutable world details; world existence is represented by the enclosing observation. */
public record WorldSnapshot(Observation<ResourceIdentifier> dimension) {
    public WorldSnapshot {
        Objects.requireNonNull(dimension, "dimension");
    }
}
