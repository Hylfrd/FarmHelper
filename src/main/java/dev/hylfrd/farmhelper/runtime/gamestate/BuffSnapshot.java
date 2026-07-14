package dev.hylfrd.farmhelper.runtime.gamestate;

import dev.hylfrd.farmhelper.runtime.snapshot.Observation;

import java.util.Objects;

/** Shared buff states parsed from the tab list and footer. */
public record BuffSnapshot(
        Observation<BuffStatus> cookie,
        Observation<BuffStatus> godPotion,
        Observation<BuffStatus> pestRepellent,
        Observation<BuffStatus> pestHunter,
        Observation<BuffStatus> sprayonator,
        Observation<BuffStatus> composter
) {
    public BuffSnapshot {
        Objects.requireNonNull(cookie, "cookie");
        Objects.requireNonNull(godPotion, "godPotion");
        Objects.requireNonNull(pestRepellent, "pestRepellent");
        Objects.requireNonNull(pestHunter, "pestHunter");
        Objects.requireNonNull(sprayonator, "sprayonator");
        Objects.requireNonNull(composter, "composter");
    }
}
