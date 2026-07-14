package dev.hylfrd.farmhelper.runtime.gamestate;

import dev.hylfrd.farmhelper.runtime.snapshot.Observation;

import java.util.List;
import java.util.Objects;

/** Fields belonging to the next visible Jacob contest. */
public record JacobNextContestSnapshot(
        Observation<Integer> startsInSeconds,
        Observation<List<GardenCrop>> crops
) {
    public JacobNextContestSnapshot {
        Objects.requireNonNull(startsInSeconds, "startsInSeconds");
        Objects.requireNonNull(crops, "crops");
        crops = crops.map(List::copyOf);
    }
}
