package dev.hylfrd.farmhelper.feature.usage;

import java.util.Objects;
import java.util.concurrent.TimeUnit;

/** Immutable local usage view captured at one explicit monotonic reading. */
public record UsageSnapshot(
        UsageState state,
        Status status,
        long sessionNanos,
        long last24HoursNanos,
        long last7DaysNanos,
        long last30DaysNanos,
        long lifetimeNanos,
        int retainedEventCount,
        boolean eventHistoryLimited
) {
    public enum Status {
        VALID,
        INVALID_TIME,
        CLOCK_REGRESSION,
        OVERFLOW
    }

    public static final UsageSnapshot ZERO = new UsageSnapshot(
            UsageState.STOPPED,
            Status.VALID,
            0L,
            0L,
            0L,
            0L,
            0L,
            0,
            false);

    public UsageSnapshot {
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(status, "status");
        if (sessionNanos < 0L
                || last24HoursNanos < 0L
                || last7DaysNanos < 0L
                || last30DaysNanos < 0L
                || lifetimeNanos < 0L) {
            throw new IllegalArgumentException("usage durations must be non-negative");
        }
        if (retainedEventCount < 0) {
            throw new IllegalArgumentException("retainedEventCount must be non-negative");
        }
        if (state == UsageState.INVALID && status == Status.VALID) {
            throw new IllegalArgumentException("invalid state requires an invalid status");
        }
    }

    public static UsageSnapshot invalid(UsageState state, Status status) {
        if (status == Status.VALID) {
            throw new IllegalArgumentException("invalid snapshot requires an invalid status");
        }
        return new UsageSnapshot(state, status, 0L, 0L, 0L, 0L, 0L, 0, false);
    }

    public boolean valid() {
        return state != UsageState.INVALID && status == Status.VALID;
    }

    public long todayNanos() {
        return last24HoursNanos;
    }

    public long totalNanos() {
        return lifetimeNanos;
    }

    public long sessionMillis() {
        return nanosToMillis(sessionNanos);
    }

    public long todayMillis() {
        return nanosToMillis(last24HoursNanos);
    }

    public long last7DaysMillis() {
        return nanosToMillis(last7DaysNanos);
    }

    public long last30DaysMillis() {
        return nanosToMillis(last30DaysNanos);
    }

    public long totalMillis() {
        return nanosToMillis(lifetimeNanos);
    }

    private static long nanosToMillis(long nanos) {
        return nanos / TimeUnit.MILLISECONDS.toNanos(1L);
    }
}
