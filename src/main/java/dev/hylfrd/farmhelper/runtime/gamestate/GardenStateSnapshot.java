package dev.hylfrd.farmhelper.runtime.gamestate;

import dev.hylfrd.farmhelper.runtime.snapshot.Observation;

import java.util.List;
import java.util.Objects;

/** Garden-only scoreboard, tab-list, and vacuum facts. */
public record GardenStateSnapshot(
        Observation<Boolean> guestPresent,
        Observation<Integer> totalPests,
        Observation<Integer> currentPlotPests,
        Observation<List<Integer>> infestedPlots,
        Observation<Integer> vacuumPests,
        Observation<Long> composterOrganicMatter,
        Observation<Long> composterFuel
) {
    public GardenStateSnapshot {
        Objects.requireNonNull(guestPresent, "guestPresent");
        Objects.requireNonNull(totalPests, "totalPests");
        Objects.requireNonNull(currentPlotPests, "currentPlotPests");
        Objects.requireNonNull(infestedPlots, "infestedPlots");
        Objects.requireNonNull(vacuumPests, "vacuumPests");
        Objects.requireNonNull(composterOrganicMatter, "composterOrganicMatter");
        Objects.requireNonNull(composterFuel, "composterFuel");
        infestedPlots = infestedPlots.map(List::copyOf);
    }
}
