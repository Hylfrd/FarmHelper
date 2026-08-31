package dev.hylfrd.farmhelper.feature.lag;

import dev.hylfrd.farmhelper.macro.ServerResponsiveness;
import dev.hylfrd.farmhelper.runtime.snapshot.Observation;
import dev.hylfrd.farmhelper.runtime.snapshot.PositionSnapshot;

import java.util.Objects;
import java.util.OptionalDouble;
import java.util.OptionalLong;

/** Immutable result of one lag observation. Durations are always monotonic nanoseconds. */
public record LagSnapshot(
        long worldEpoch,
        Status status,
        ServerResponsiveness responsiveness,
        boolean recentlyLagging,
        Observation<PositionSnapshot> lastPacketPosition,
        OptionalLong lastPacketAgeNanos,
        OptionalDouble tickRate
) {
    public enum Status {
        CURRENT,
        NOT_JOINED,
        STALE_IDENTITY,
        CONNECTION_UNKNOWN,
        CONNECTION_UNAVAILABLE,
        SERVER_UNKNOWN,
        CLOCK_REGRESSION
    }

    public LagSnapshot {
        if (worldEpoch < 0L) {
            throw new IllegalArgumentException("worldEpoch must not be negative");
        }
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(responsiveness, "responsiveness");
        Objects.requireNonNull(lastPacketPosition, "lastPacketPosition");
        Objects.requireNonNull(lastPacketAgeNanos, "lastPacketAgeNanos");
        Objects.requireNonNull(tickRate, "tickRate");
        if (lastPacketAgeNanos.isPresent() && lastPacketAgeNanos.getAsLong() < 0L) {
            throw new IllegalArgumentException("lastPacketAgeNanos must not be negative");
        }
        if (tickRate.isPresent()) {
            double value = tickRate.getAsDouble();
            if (!Double.isFinite(value) || value <= 0.0d || value > LagDetector.MAX_TICK_RATE) {
                throw new IllegalArgumentException("tickRate must be finite and in (0, 20]");
            }
        }
        if (status == Status.CURRENT && responsiveness == ServerResponsiveness.UNKNOWN) {
            throw new IllegalArgumentException("current lag evidence must have known responsiveness");
        }
        if (status != Status.CURRENT
                && (responsiveness != ServerResponsiveness.UNKNOWN
                || recentlyLagging
                || !lastPacketPosition.isUnknown()
                || lastPacketAgeNanos.isPresent()
                || tickRate.isPresent())) {
            throw new IllegalArgumentException("non-current lag evidence must be fully redacted");
        }
    }

    static LagSnapshot current(
            long worldEpoch,
            ServerResponsiveness responsiveness,
            boolean recentlyLagging,
            Observation<PositionSnapshot> lastPacketPosition,
            OptionalLong lastPacketAgeNanos,
            OptionalDouble tickRate
    ) {
        return new LagSnapshot(
                worldEpoch,
                Status.CURRENT,
                responsiveness,
                recentlyLagging,
                lastPacketPosition,
                lastPacketAgeNanos,
                tickRate);
    }

    static LagSnapshot unavailable(long worldEpoch, Status status) {
        if (status == Status.CURRENT) {
            throw new IllegalArgumentException("current status is not unavailable");
        }
        return new LagSnapshot(
                worldEpoch,
                status,
                ServerResponsiveness.UNKNOWN,
                false,
                Observation.unknown(),
                OptionalLong.empty(),
                OptionalDouble.empty());
    }
}
