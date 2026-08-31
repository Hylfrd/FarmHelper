package dev.hylfrd.farmhelper.navigation.simulation;

import dev.hylfrd.farmhelper.runtime.snapshot.PositionSnapshot;

import java.util.Objects;
import java.util.Optional;

/** Immutable result that never exposes a partial state when required evidence is unknown. */
public record FlyStoppingPrediction(
        FlyStoppingPredictionOutcome outcome,
        Optional<FlyStoppingState> state,
        int ticks,
        Optional<FlyStoppingFailure> failure
) {
    public FlyStoppingPrediction {
        Objects.requireNonNull(outcome, "outcome");
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(failure, "failure");
        if (ticks < 0 || ticks > FlyStoppingSimulator.MAX_TICKS) {
            throw new IllegalArgumentException("ticks are outside the simulation budget");
        }
        boolean ticksMatch = switch (outcome) {
            case STOPPED -> ticks >= 1;
            case TICK_LIMIT -> ticks == FlyStoppingSimulator.MAX_TICKS;
            case UNKNOWN -> ticks < FlyStoppingSimulator.MAX_TICKS;
        };
        if (!ticksMatch) {
            throw new IllegalArgumentException("outcome and tick count do not agree");
        }
        boolean payloadMatches = switch (outcome) {
            case STOPPED, TICK_LIMIT -> state.isPresent() && failure.isEmpty();
            case UNKNOWN -> state.isEmpty() && failure.isPresent();
        };
        if (!payloadMatches) {
            throw new IllegalArgumentException("outcome and payload do not agree");
        }
    }

    static FlyStoppingPrediction stopped(FlyStoppingState state, int ticks) {
        return known(FlyStoppingPredictionOutcome.STOPPED, state, ticks);
    }

    static FlyStoppingPrediction tickLimit(FlyStoppingState state) {
        return known(FlyStoppingPredictionOutcome.TICK_LIMIT, state,
                FlyStoppingSimulator.MAX_TICKS);
    }

    static FlyStoppingPrediction unknown(FlyStoppingFailure failure, int completedTicks) {
        return new FlyStoppingPrediction(FlyStoppingPredictionOutcome.UNKNOWN, Optional.empty(),
                completedTicks, Optional.of(Objects.requireNonNull(failure, "failure")));
    }

    private static FlyStoppingPrediction known(
            FlyStoppingPredictionOutcome outcome,
            FlyStoppingState state,
            int ticks
    ) {
        return new FlyStoppingPrediction(outcome,
                Optional.of(Objects.requireNonNull(state, "state")), ticks, Optional.empty());
    }

    public boolean isKnown() {
        return outcome != FlyStoppingPredictionOutcome.UNKNOWN;
    }

    public boolean isStopped() {
        return outcome == FlyStoppingPredictionOutcome.STOPPED;
    }

    public boolean isUnknown() {
        return outcome == FlyStoppingPredictionOutcome.UNKNOWN;
    }

    public Optional<PositionSnapshot> position() {
        return state.map(FlyStoppingState::position);
    }
}
