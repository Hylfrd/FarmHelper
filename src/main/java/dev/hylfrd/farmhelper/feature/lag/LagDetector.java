package dev.hylfrd.farmhelper.feature.lag;

import dev.hylfrd.farmhelper.macro.ServerResponsiveness;
import dev.hylfrd.farmhelper.macro.ServerTimeTracker;
import dev.hylfrd.farmhelper.runtime.snapshot.Observation;
import dev.hylfrd.farmhelper.runtime.snapshot.PositionSnapshot;
import dev.hylfrd.farmhelper.runtime.time.MonotonicClock;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Objects;
import java.util.OptionalDouble;
import java.util.OptionalLong;
import java.util.concurrent.TimeUnit;

/**
 * Client-thread lag evidence and TPS history built around the shared server heartbeat tracker.
 *
 * <p>The injected tracker and clock must be the same instances used by the owning runtime. This
 * class deliberately has no constructor that creates a private heartbeat authority.
 */
public final class LagDetector {
    public static final int TPS_HISTORY_SIZE = 20;
    public static final double MAX_TICK_RATE = 20.0d;
    public static final long RECENT_LAG_NANOS = TimeUnit.MILLISECONDS.toNanos(900L);

    private static final long NANOS_PER_SECOND = TimeUnit.SECONDS.toNanos(1L);
    private static final long NO_TIME = -1L;
    private static final long NO_WORLD = -1L;

    private final MonotonicClock clock;
    private final ServerTimeTracker serverTimeTracker;
    private final Deque<Double> tickRates = new ArrayDeque<>(TPS_HISTORY_SIZE);

    private long worldEpoch = NO_WORLD;
    private long lastClockAtNanos = NO_TIME;
    private long lastPacketAtNanos = NO_TIME;
    private long lastLagObservedAtNanos = NO_TIME;
    private Observation<PositionSnapshot> lastPacketPosition = Observation.unknown();

    public LagDetector(MonotonicClock clock, ServerTimeTracker serverTimeTracker) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.serverTimeTracker = Objects.requireNonNull(serverTimeTracker, "serverTimeTracker");
    }

    /** Starts a fresh, world-identity-fenced server session and clears all prior evidence. */
    public void joined(long worldEpoch) {
        requireWorldEpoch(worldEpoch);
        long nowNanos = readClock();
        serverTimeTracker.joined(nowNanos);
        this.worldEpoch = worldEpoch;
        lastClockAtNanos = nowNanos;
        lastPacketAtNanos = NO_TIME;
        lastLagObservedAtNanos = NO_TIME;
        lastPacketPosition = Observation.unknown();
        tickRates.clear();
    }

    /**
     * Records a heartbeat and its independently captured position evidence.
     *
     * <p>Unknown or absent positions replace older position evidence; the heartbeat itself remains
     * valid. A stale world epoch or regressed clock mutates neither the tracker nor local state.
     */
    public LagPacketResult recordTimePacket(LagPacketEvidence evidence) {
        Objects.requireNonNull(evidence, "evidence");
        if (worldEpoch == NO_WORLD) {
            return LagPacketResult.NOT_JOINED;
        }
        if (evidence.worldEpoch() != worldEpoch) {
            return LagPacketResult.STALE_IDENTITY;
        }

        long nowNanos = readClock();
        if (nowNanos < lastClockAtNanos) {
            return LagPacketResult.CLOCK_REGRESSION;
        }

        serverTimeTracker.receivedTimePacket(nowNanos);
        if (lastPacketAtNanos != NO_TIME && nowNanos > lastPacketAtNanos) {
            recordTickRate(nowNanos - lastPacketAtNanos);
        }
        lastClockAtNanos = nowNanos;
        lastPacketAtNanos = Math.max(lastPacketAtNanos, nowNanos);
        lastPacketPosition = evidence.playerPosition();
        return LagPacketResult.RECORDED;
    }

    /** Observes current lag state and advances the 900 ms recent-lag evidence window. */
    public LagSnapshot observe(LagStateEvidence evidence) {
        Objects.requireNonNull(evidence, "evidence");
        if (worldEpoch == NO_WORLD) {
            return LagSnapshot.unavailable(evidence.worldEpoch(), LagSnapshot.Status.NOT_JOINED);
        }
        if (evidence.worldEpoch() != worldEpoch) {
            return LagSnapshot.unavailable(
                    evidence.worldEpoch(), LagSnapshot.Status.STALE_IDENTITY);
        }
        if (evidence.connection().isUnknown()) {
            return LagSnapshot.unavailable(
                    evidence.worldEpoch(), LagSnapshot.Status.CONNECTION_UNKNOWN);
        }
        if (evidence.connection().isAbsent()) {
            return LagSnapshot.unavailable(
                    evidence.worldEpoch(), LagSnapshot.Status.CONNECTION_UNAVAILABLE);
        }

        long nowNanos = readClock();
        if (nowNanos < lastClockAtNanos) {
            return LagSnapshot.unavailable(
                    evidence.worldEpoch(), LagSnapshot.Status.CLOCK_REGRESSION);
        }
        lastClockAtNanos = nowNanos;

        ServerResponsiveness responsiveness = serverTimeTracker.observe(nowNanos, true);
        if (responsiveness == ServerResponsiveness.UNKNOWN) {
            return LagSnapshot.unavailable(
                    evidence.worldEpoch(), LagSnapshot.Status.SERVER_UNKNOWN);
        }
        if (responsiveness == ServerResponsiveness.LAGGING) {
            lastLagObservedAtNanos = nowNanos;
        }

        boolean recentlyLagging = lastLagObservedAtNanos != NO_TIME
                && nowNanos - lastLagObservedAtNanos < RECENT_LAG_NANOS;
        OptionalLong packetAge = lastPacketAtNanos == NO_TIME
                ? OptionalLong.empty()
                : OptionalLong.of(nowNanos - lastPacketAtNanos);
        return LagSnapshot.current(
                evidence.worldEpoch(),
                responsiveness,
                recentlyLagging,
                lastPacketPosition,
                packetAge,
                averageTickRate());
    }

    /** Clears the active identity, heartbeat, position, TPS, and recent-lag evidence. */
    public void reset() {
        serverTimeTracker.reset();
        worldEpoch = NO_WORLD;
        lastClockAtNanos = NO_TIME;
        lastPacketAtNanos = NO_TIME;
        lastLagObservedAtNanos = NO_TIME;
        lastPacketPosition = Observation.unknown();
        tickRates.clear();
    }

    private void recordTickRate(long elapsedNanos) {
        double tickRate = Math.min(
                MAX_TICK_RATE,
                MAX_TICK_RATE * NANOS_PER_SECOND / elapsedNanos);
        if (tickRates.size() == TPS_HISTORY_SIZE) {
            tickRates.removeFirst();
        }
        tickRates.addLast(tickRate);
    }

    private OptionalDouble averageTickRate() {
        if (tickRates.isEmpty()) {
            return OptionalDouble.empty();
        }
        double total = 0.0d;
        for (double tickRate : tickRates) {
            total += tickRate;
        }
        return OptionalDouble.of(total / tickRates.size());
    }

    private long readClock() {
        long nowNanos = clock.nowNanos();
        if (nowNanos < 0L) {
            throw new IllegalStateException("clock returned negative monotonic time");
        }
        return nowNanos;
    }

    private static void requireWorldEpoch(long worldEpoch) {
        if (worldEpoch < 0L) {
            throw new IllegalArgumentException("worldEpoch must not be negative");
        }
    }
}
