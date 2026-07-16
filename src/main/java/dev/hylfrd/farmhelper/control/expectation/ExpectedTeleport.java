package dev.hylfrd.farmhelper.control.expectation;

import dev.hylfrd.farmhelper.runtime.snapshot.PositionSnapshot;

import java.util.Objects;

/** Expected origin/destination pair for one owned teleport window. */
public record ExpectedTeleport(
        PositionSnapshot origin,
        PositionSnapshot destination,
        double tolerance
) implements ExpectedActionPayload {
    public ExpectedTeleport {
        Objects.requireNonNull(origin, "origin");
        Objects.requireNonNull(destination, "destination");
        if (!Double.isFinite(tolerance) || tolerance < 0.0D) {
            throw new IllegalArgumentException("tolerance must be finite and non-negative");
        }
    }
}
