package dev.hylfrd.farmhelper.feature.antistuck;

import dev.hylfrd.farmhelper.runtime.spatial.BlockPosition;

import java.util.Objects;
import java.util.Optional;

/** A target observation where absence, unknown data, and an adapter error are distinct. */
public record AntiStuckTargetEvidence(
        State state,
        Optional<BlockPosition> position
) {
    public enum State {
        PRESENT,
        ABSENT,
        UNKNOWN,
        ERROR
    }

    public AntiStuckTargetEvidence {
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(position, "position");
        position.ifPresent(value -> Objects.requireNonNull(value, "position value"));
        if (state == State.PRESENT && position.isEmpty()) {
            throw new IllegalArgumentException("present target evidence needs a position");
        }
        if (state != State.PRESENT && position.isPresent()) {
            throw new IllegalArgumentException("only present target evidence may contain a position");
        }
    }

    public static AntiStuckTargetEvidence present(BlockPosition position) {
        return new AntiStuckTargetEvidence(State.PRESENT, Optional.of(position));
    }

    public static AntiStuckTargetEvidence absent() {
        return new AntiStuckTargetEvidence(State.ABSENT, Optional.empty());
    }

    public static AntiStuckTargetEvidence unknown() {
        return new AntiStuckTargetEvidence(State.UNKNOWN, Optional.empty());
    }

    public static AntiStuckTargetEvidence error() {
        return new AntiStuckTargetEvidence(State.ERROR, Optional.empty());
    }

    public boolean isPresent() {
        return state == State.PRESENT;
    }

    public boolean isAbsent() {
        return state == State.ABSENT;
    }

    public boolean isUnknown() {
        return state == State.UNKNOWN;
    }

    public boolean isError() {
        return state == State.ERROR;
    }
}
