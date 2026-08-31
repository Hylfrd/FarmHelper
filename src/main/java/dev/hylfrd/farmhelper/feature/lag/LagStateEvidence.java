package dev.hylfrd.farmhelper.feature.lag;

import dev.hylfrd.farmhelper.runtime.snapshot.ConnectionSnapshot;
import dev.hylfrd.farmhelper.runtime.snapshot.Observation;

import java.util.Objects;

/** Immutable connection and world identity evidence for one lag observation. */
public record LagStateEvidence(
        long worldEpoch,
        Observation<ConnectionSnapshot> connection
) {
    public LagStateEvidence {
        if (worldEpoch < 0L) {
            throw new IllegalArgumentException("worldEpoch must not be negative");
        }
        Objects.requireNonNull(connection, "connection");
    }
}
