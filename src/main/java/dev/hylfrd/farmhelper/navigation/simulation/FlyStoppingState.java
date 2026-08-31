package dev.hylfrd.farmhelper.navigation.simulation;

import dev.hylfrd.farmhelper.runtime.snapshot.MotionSnapshot;
import dev.hylfrd.farmhelper.runtime.snapshot.PositionSnapshot;
import dev.hylfrd.farmhelper.runtime.spatial.BoxSnapshot;

import java.util.Objects;

/** Immutable player state consumed and returned by the pure stopping predictor. */
public record FlyStoppingState(
        PositionSnapshot position,
        MotionSnapshot motion,
        BoxSnapshot body,
        boolean onGround,
        boolean noClip,
        boolean sneaking,
        double stepHeight,
        double fallDistance,
        FlyStoppingInput input,
        boolean collided,
        boolean collidedHorizontally,
        boolean collidedVertically
) {
    public FlyStoppingState {
        Objects.requireNonNull(position, "position");
        Objects.requireNonNull(motion, "motion");
        Objects.requireNonNull(body, "body");
        Objects.requireNonNull(input, "input");
        if (!body.hasPositiveVolume()) {
            throw new IllegalArgumentException("body must have positive volume");
        }
        if (!Double.isFinite(stepHeight) || stepHeight < 0.0D) {
            throw new IllegalArgumentException("stepHeight must be finite and non-negative");
        }
        if (!Double.isFinite(fallDistance) || fallDistance < 0.0D) {
            throw new IllegalArgumentException("fallDistance must be finite and non-negative");
        }
        if (!input.isZero()) {
            throw new IllegalArgumentException("stopping prediction requires zero input");
        }
    }
}
