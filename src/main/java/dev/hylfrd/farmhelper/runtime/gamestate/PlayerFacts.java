package dev.hylfrd.farmhelper.runtime.gamestate;

import dev.hylfrd.farmhelper.runtime.snapshot.Observation;

import java.util.Objects;

/** Text-parser inputs which are intentionally not inferred from Minecraft objects. */
public record PlayerFacts(
        Observation<Boolean> inventoryEmpty,
        Observation<Integer> experienceLevel,
        Observation<Double> walkSpeedFactor
) {
    public PlayerFacts {
        Objects.requireNonNull(inventoryEmpty, "inventoryEmpty");
        Objects.requireNonNull(experienceLevel, "experienceLevel");
        Objects.requireNonNull(walkSpeedFactor, "walkSpeedFactor");
    }

    public static PlayerFacts unknown() {
        return new PlayerFacts(Observation.unknown(), Observation.unknown(), Observation.unknown());
    }
}
