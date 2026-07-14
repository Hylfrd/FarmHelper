package dev.hylfrd.farmhelper.runtime.gamestate;

import dev.hylfrd.farmhelper.runtime.snapshot.Observation;

import java.math.BigDecimal;
import java.util.Objects;

/** Parsed purse, currencies, and player speed. */
public record EconomySnapshot(
        Observation<BigDecimal> purse,
        Observation<Long> bits,
        Observation<Long> copper,
        Observation<Integer> speed
) {
    public EconomySnapshot {
        Objects.requireNonNull(purse, "purse");
        Objects.requireNonNull(bits, "bits");
        Objects.requireNonNull(copper, "copper");
        Objects.requireNonNull(speed, "speed");
    }
}
