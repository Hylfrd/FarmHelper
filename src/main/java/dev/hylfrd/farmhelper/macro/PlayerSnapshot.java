package dev.hylfrd.farmhelper.macro;

import dev.hylfrd.farmhelper.runtime.snapshot.Observation;

import java.util.Objects;

/**
 * Compatibility projection used by the Stage 1 macro context.
 * New runtime decisions should consume {@code runtime.snapshot.PlayerSnapshot}.
 */
public record PlayerSnapshot(
        Observation.State state,
        double x,
        double y,
        double z,
        float yaw,
        float pitch
) {
    public PlayerSnapshot {
        Objects.requireNonNull(state, "state");
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) {
            throw new IllegalArgumentException("coordinates must be finite");
        }
        if (!Float.isFinite(yaw) || !Float.isFinite(pitch)) {
            throw new IllegalArgumentException("rotation must be finite");
        }
    }

    public PlayerSnapshot(double x, double y, double z, float yaw, float pitch) {
        this(Observation.State.PRESENT, x, y, z, yaw, pitch);
    }

    public static PlayerSnapshot absent() {
        return new PlayerSnapshot(Observation.State.ABSENT, 0.0D, 0.0D, 0.0D, 0.0F, 0.0F);
    }

    public static PlayerSnapshot unknown() {
        return new PlayerSnapshot(Observation.State.UNKNOWN, 0.0D, 0.0D, 0.0D, 0.0F, 0.0F);
    }

    public boolean present() {
        return state == Observation.State.PRESENT;
    }
}
