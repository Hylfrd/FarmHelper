package dev.hylfrd.farmhelper.failsafe;

import java.util.Objects;

/** Immutable detector evidence admission carrying the exact macro and world identity. */
public record FailsafeCandidate(
        FailsafeType type,
        long macroGeneration,
        long worldEpoch
) {
    public FailsafeCandidate {
        Objects.requireNonNull(type, "type");
        if (macroGeneration < 0L) {
            throw new IllegalArgumentException("macroGeneration must be non-negative");
        }
        if (worldEpoch < 0L) {
            throw new IllegalArgumentException("worldEpoch must be non-negative");
        }
    }

    public FailsafeCandidate(FailsafeType type, Identity identity) {
        this(type, Objects.requireNonNull(identity, "identity").macroGeneration(), identity.worldEpoch());
    }

    public FailsafeType detector() {
        return type;
    }

    public long generation() {
        return macroGeneration;
    }

    public long epoch() {
        return worldEpoch;
    }

    public Identity identity() {
        return new Identity(macroGeneration, worldEpoch);
    }

    public boolean matchesIdentity(long expectedMacroGeneration, long expectedWorldEpoch) {
        return macroGeneration == expectedMacroGeneration && worldEpoch == expectedWorldEpoch;
    }

    public boolean sameLifecycle(FailsafeCandidate other) {
        return other != null && matchesIdentity(other.macroGeneration, other.worldEpoch);
    }

    /** Exact lifecycle identity shared by all detector candidates in one macro/world run. */
    public record Identity(long macroGeneration, long worldEpoch) {
        public Identity {
            if (macroGeneration < 0L) {
                throw new IllegalArgumentException("macroGeneration must be non-negative");
            }
            if (worldEpoch < 0L) {
                throw new IllegalArgumentException("worldEpoch must be non-negative");
            }
        }
    }
}
