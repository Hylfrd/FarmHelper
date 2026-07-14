package dev.hylfrd.farmhelper.runtime.gamestate;

import dev.hylfrd.farmhelper.runtime.snapshot.Observation;

import java.util.Objects;

/** Fields belonging to a currently visible Jacob contest. */
public record JacobContestSnapshot(
        Observation<Integer> remainingSeconds,
        Observation<GardenCrop> crop,
        Observation<Long> collected,
        Observation<JacobMedal> medal
) {
    public JacobContestSnapshot {
        Objects.requireNonNull(remainingSeconds, "remainingSeconds");
        Objects.requireNonNull(crop, "crop");
        Objects.requireNonNull(collected, "collected");
        Objects.requireNonNull(medal, "medal");
    }
}
