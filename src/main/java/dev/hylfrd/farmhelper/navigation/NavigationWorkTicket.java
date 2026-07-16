package dev.hylfrd.farmhelper.navigation;

import dev.hylfrd.farmhelper.control.input.ControlOwner;

import java.util.Objects;

/** Exact per-phase/per-batch capability nested inside one navigation run ticket. */
public record NavigationWorkTicket(
        NavigationTicket runTicket,
        NavigationPhase phase,
        long revision
) {
    public NavigationWorkTicket {
        Objects.requireNonNull(runTicket, "runTicket");
        Objects.requireNonNull(phase, "phase");
        if (revision <= 0L) {
            throw new IllegalArgumentException("work revision must be positive");
        }
    }

    public ControlOwner owner() {
        return runTicket.owner();
    }

    public long generation() {
        return runTicket.generation();
    }

    public long worldEpoch() {
        return runTicket.worldEpoch();
    }
}
