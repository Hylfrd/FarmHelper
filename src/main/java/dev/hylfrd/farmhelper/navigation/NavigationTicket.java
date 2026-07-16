package dev.hylfrd.farmhelper.navigation;

import dev.hylfrd.farmhelper.control.input.ControlOwner;

import java.util.Objects;

/** Exact public identity for every callback, capture, phase transition, and terminal result. */
public record NavigationTicket(ControlOwner owner, long generation, long worldEpoch) {
    public NavigationTicket {
        Objects.requireNonNull(owner, "owner");
        if (generation <= 0L) {
            throw new IllegalArgumentException("generation must be positive");
        }
        if (worldEpoch < 0L) {
            throw new IllegalArgumentException("worldEpoch must be non-negative");
        }
    }
}
