package dev.hylfrd.farmhelper.runtime.gamestate;

import dev.hylfrd.farmhelper.runtime.snapshot.Observation;

import java.util.Objects;

/** One immutable parse of all shared game-state text. */
public record GameStateSnapshot(
        long generation,
        Observation<SemanticLocation> location,
        Observation<Boolean> skyBlock,
        Observation<Boolean> inGarden,
        Observation<Integer> serverClosingSeconds,
        EconomySnapshot economy,
        JacobStateSnapshot jacob,
        BuffSnapshot buffs,
        GardenStateSnapshot garden
) {
    public GameStateSnapshot {
        if (generation < 0) {
            throw new IllegalArgumentException("generation must be non-negative");
        }
        Objects.requireNonNull(location, "location");
        Objects.requireNonNull(skyBlock, "skyBlock");
        Objects.requireNonNull(inGarden, "inGarden");
        Objects.requireNonNull(serverClosingSeconds, "serverClosingSeconds");
        Objects.requireNonNull(economy, "economy");
        Objects.requireNonNull(jacob, "jacob");
        Objects.requireNonNull(buffs, "buffs");
        Objects.requireNonNull(garden, "garden");
    }
}
