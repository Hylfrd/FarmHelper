package dev.hylfrd.farmhelper.runtime.gamestate;

import dev.hylfrd.farmhelper.runtime.snapshot.Observation;

import java.util.Objects;

/** Current and upcoming Jacob contest observations. */
public record JacobStateSnapshot(
        Observation<JacobContestSnapshot> currentContest,
        Observation<JacobNextContestSnapshot> nextContest
) {
    public JacobStateSnapshot {
        Objects.requireNonNull(currentContest, "currentContest");
        Objects.requireNonNull(nextContest, "nextContest");
    }
}
