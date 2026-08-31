package dev.hylfrd.farmhelper.feature.jacob;

import java.util.Objects;

/** Immutable result pairing one detector update with its stable post-update snapshot. */
public record JacobThresholdResult(
        JacobThresholdUpdate update,
        JacobThresholdSnapshot snapshot
) {
    public JacobThresholdResult {
        Objects.requireNonNull(update, "update");
        Objects.requireNonNull(snapshot, "snapshot");
    }
}
