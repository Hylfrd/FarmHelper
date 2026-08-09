package dev.hylfrd.farmhelper.feature.antistuck;

import java.util.Objects;

/**
 * Immutable recovery request carrying the exact owner, run identity, target hint, and counters.
 *
 * <p>A future adapter may enrich this value from the read-only {@code MacroRecoveryRequest}
 * handoff; the pure controller does not retain macro objects or perform macro recovery.</p>
 */
public record AntiStuckRecoveryRequest(
        AntiStuckIdentity identity,
        AntiStuckTargetEvidence directionTarget,
        int unstuckTries,
        int lagBackCounter
) {
    public AntiStuckRecoveryRequest {
        Objects.requireNonNull(identity, "identity");
        Objects.requireNonNull(directionTarget, "directionTarget");
        if (unstuckTries < 0) {
            throw new IllegalArgumentException("unstuckTries must be non-negative");
        }
        if (lagBackCounter < 0) {
            throw new IllegalArgumentException("lagBackCounter must be non-negative");
        }
    }

    public static AntiStuckRecoveryRequest withoutDirectionTarget(AntiStuckIdentity identity) {
        return new AntiStuckRecoveryRequest(identity, AntiStuckTargetEvidence.absent(), 0, 0);
    }
}
