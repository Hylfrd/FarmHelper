package dev.hylfrd.farmhelper.macro;

import dev.hylfrd.farmhelper.runtime.snapshot.Observation;
import dev.hylfrd.farmhelper.runtime.snapshot.PlayerSnapshot;
import dev.hylfrd.farmhelper.runtime.spatial.SpatialSnapshot;

import java.util.Objects;

public record FarmingContext(
        long nowNanos,
        long worldEpoch,
        Observation<PlayerSnapshot> player,
        Observation<SpatialSnapshot> spatial,
        Observation<Boolean> inGarden,
        boolean developmentGarden,
        ServerResponsiveness serverResponsiveness,
        Observation<PlayerPosture> posture
) {
    public FarmingContext {
        if (nowNanos < 0L || worldEpoch < 0L) {
            throw new IllegalArgumentException("time and world epoch must not be negative");
        }
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(spatial, "spatial");
        Objects.requireNonNull(inGarden, "inGarden");
        Objects.requireNonNull(serverResponsiveness, "serverResponsiveness");
        Objects.requireNonNull(posture, "posture");
    }

    public FarmingContext(
            long nowNanos,
            long worldEpoch,
            Observation<PlayerSnapshot> player,
            Observation<SpatialSnapshot> spatial,
            Observation<Boolean> inGarden,
            boolean developmentGarden,
            ServerResponsiveness serverResponsiveness
    ) {
        this(nowNanos, worldEpoch, player, spatial, inGarden, developmentGarden,
                serverResponsiveness, Observation.unknown());
    }
}
