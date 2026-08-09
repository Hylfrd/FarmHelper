package dev.hylfrd.farmhelper.feature.antistuck;

import java.util.Objects;

/** Immutable run-identity-fenced tick input with an explicit monotonic millisecond reading. */
public record AntiStuckTick(
        AntiStuckIdentity identity,
        long nowMillis,
        AntiStuckRecoveryEvidence evidence
) {
    public AntiStuckTick {
        Objects.requireNonNull(identity, "identity");
        if (nowMillis < 0L) {
            throw new IllegalArgumentException("nowMillis must be non-negative");
        }
        Objects.requireNonNull(evidence, "evidence");
        if (!identity.equals(evidence.identity())) {
            throw new IllegalArgumentException("tick and evidence identities must match");
        }
    }
}
