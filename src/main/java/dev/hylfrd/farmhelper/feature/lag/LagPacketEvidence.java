package dev.hylfrd.farmhelper.feature.lag;

import dev.hylfrd.farmhelper.runtime.snapshot.Observation;
import dev.hylfrd.farmhelper.runtime.snapshot.PositionSnapshot;

import java.util.Objects;

/** Immutable client-thread evidence captured when a server time packet arrives. */
public record LagPacketEvidence(
        long worldEpoch,
        Observation<PositionSnapshot> playerPosition
) {
    public LagPacketEvidence {
        if (worldEpoch < 0L) {
            throw new IllegalArgumentException("worldEpoch must not be negative");
        }
        Objects.requireNonNull(playerPosition, "playerPosition");
    }
}
