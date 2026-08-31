package dev.hylfrd.farmhelper.feature.usage;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.TimeUnit;

/**
 * Pure, client-thread usage accounting for explicit macro-active tick evidence.
 *
 * <p>Every accepted active interval is retained in a bounded journal for rolling views. Lifetime
 * and current-session totals are aggregate counters, so journal eviction does not change them.
 * A journal-capacity flag makes any incomplete rolling view visible to callers.</p>
 */
public final class UsageStatsTracker {
    public static final long DAY_NANOS = TimeUnit.DAYS.toNanos(1L);
    public static final long LAST_24_HOURS_NANOS = DAY_NANOS;
    public static final long LAST_7_DAYS_NANOS = TimeUnit.DAYS.toNanos(7L);
    public static final long LAST_30_DAYS_NANOS = TimeUnit.DAYS.toNanos(30L);
    public static final long DEFAULT_MAX_OBSERVATION_GAP_NANOS = TimeUnit.SECONDS.toNanos(5L);
    public static final int DEFAULT_MAX_RETAINED_EVENTS = 4_096;

    private static final long MAX_WINDOW_NANOS = LAST_30_DAYS_NANOS;
    private static final long UNSET = Long.MIN_VALUE;

    private final long maxObservationGapNanos;
    private final int maxRetainedEvents;
    private final Deque<UsageInterval> intervals = new ArrayDeque<>();

    private UsageState state = UsageState.STOPPED;
    private long lastNowNanos = UNSET;
    private long sessionNanos;
    private long lifetimeNanos;
    private long eventHistoryLimitedUntilNanos = UNSET;

    public UsageStatsTracker() {
        this(DEFAULT_MAX_OBSERVATION_GAP_NANOS, DEFAULT_MAX_RETAINED_EVENTS);
    }

    public UsageStatsTracker(long maxObservationGapNanos, int maxRetainedEvents) {
        if (maxObservationGapNanos <= 0L) {
            throw new IllegalArgumentException("maxObservationGapNanos must be positive");
        }
        if (maxRetainedEvents <= 0) {
            throw new IllegalArgumentException("maxRetainedEvents must be positive");
        }
        this.maxObservationGapNanos = maxObservationGapNanos;
        this.maxRetainedEvents = maxRetainedEvents;
    }

    public UsageState state() {
        return state;
    }

    public long maxObservationGapNanos() {
        return maxObservationGapNanos;
    }

    public int maxRetainedEvents() {
        return maxRetainedEvents;
    }

    public int retainedEventCount() {
        return intervals.size();
    }

    /** Starts a fresh session while preserving local lifetime and rolling evidence. */
    public UsageRecordResult start(long nowNanos) {
        if (nowNanos < 0L) {
            return UsageRecordResult.INVALID_TIME;
        }
        if (state == UsageState.INVALID) {
            return UsageRecordResult.INVALID;
        }
        if (state != UsageState.STOPPED) {
            return UsageRecordResult.ALREADY_ACTIVE;
        }
        if (lastNowNanos != UNSET && nowNanos < lastNowNanos) {
            return UsageRecordResult.CLOCK_REGRESSION;
        }
        state = UsageState.RUNNING;
        sessionNanos = 0L;
        lastNowNanos = nowNanos;
        return UsageRecordResult.STARTED;
    }

    /** Pauses at an explicit boundary, counting only the bounded active interval before it. */
    public UsageRecordResult pause(long nowNanos) {
        if (nowNanos < 0L) {
            return UsageRecordResult.INVALID_TIME;
        }
        if (state == UsageState.INVALID) {
            return UsageRecordResult.INVALID;
        }
        if (state == UsageState.STOPPED) {
            return UsageRecordResult.STOPPED;
        }
        if (state == UsageState.PAUSED) {
            return UsageRecordResult.ALREADY_PAUSED;
        }
        if (nowNanos < lastNowNanos) {
            return UsageRecordResult.CLOCK_REGRESSION;
        }

        UsageRecordResult boundary = countActiveUntil(nowNanos);
        if (boundary == UsageRecordResult.OVERFLOW) {
            return boundary;
        }
        state = UsageState.PAUSED;
        return boundary == UsageRecordResult.GAP_REJECTED
                ? boundary
                : UsageRecordResult.PAUSED;
    }

    /** Resumes after excluding the entire explicit paused interval. */
    public UsageRecordResult resume(long nowNanos) {
        if (nowNanos < 0L) {
            return UsageRecordResult.INVALID_TIME;
        }
        if (state == UsageState.INVALID) {
            return UsageRecordResult.INVALID;
        }
        if (state == UsageState.STOPPED) {
            return UsageRecordResult.STOPPED;
        }
        if (state == UsageState.RUNNING) {
            return UsageRecordResult.ALREADY_ACTIVE;
        }
        if (nowNanos < lastNowNanos) {
            return UsageRecordResult.CLOCK_REGRESSION;
        }
        lastNowNanos = nowNanos;
        state = UsageState.RUNNING;
        return UsageRecordResult.RESUMED;
    }

    /** Stops the current session; a final tick must be supplied explicitly before this call. */
    public UsageRecordResult stop() {
        if (state == UsageState.INVALID) {
            return UsageRecordResult.INVALID;
        }
        if (state == UsageState.STOPPED) {
            return UsageRecordResult.STOPPED;
        }
        state = UsageState.STOPPED;
        return UsageRecordResult.STOPPED;
    }

    /** Clears all local history, aggregate totals, and lifecycle state. */
    public void reset() {
        intervals.clear();
        state = UsageState.STOPPED;
        lastNowNanos = UNSET;
        sessionNanos = 0L;
        lifetimeNanos = 0L;
        eventHistoryLimitedUntilNanos = UNSET;
    }

    /** Records one explicit tick without introducing any client or identity data. */
    public UsageRecordResult record(UsageTick tick) {
        if (tick == null) {
            return UsageRecordResult.INVALID_EVENT;
        }
        return record(tick.macroActive(), tick.nowNanos());
    }

    /**
     * Records one tick. An inactive tick advances the evidence baseline but never adds usage;
     * active gaps at least as large as {@link #maxObservationGapNanos} are discarded
     * conservatively.
     */
    public UsageRecordResult record(boolean macroActive, long nowNanos) {
        if (nowNanos < 0L) {
            return UsageRecordResult.INVALID_TIME;
        }
        if (state == UsageState.INVALID) {
            return UsageRecordResult.INVALID;
        }
        if (state == UsageState.STOPPED) {
            return UsageRecordResult.STOPPED;
        }
        if (nowNanos < lastNowNanos) {
            return UsageRecordResult.CLOCK_REGRESSION;
        }
        if (state == UsageState.PAUSED) {
            lastNowNanos = nowNanos;
            return UsageRecordResult.PAUSED;
        }
        if (!macroActive) {
            lastNowNanos = nowNanos;
            return UsageRecordResult.IDLE;
        }
        return countActiveUntil(nowNanos);
    }

    /** Captures stable rolling and aggregate values at one explicit monotonic reading. */
    public UsageSnapshot snapshot(long nowNanos) {
        if (nowNanos < 0L) {
            return UsageSnapshot.invalid(state, UsageSnapshot.Status.INVALID_TIME);
        }
        if (state == UsageState.INVALID) {
            return UsageSnapshot.invalid(state, UsageSnapshot.Status.OVERFLOW);
        }
        if (lastNowNanos != UNSET && nowNanos < lastNowNanos) {
            return UsageSnapshot.invalid(state, UsageSnapshot.Status.CLOCK_REGRESSION);
        }
        if (state != UsageState.STOPPED) {
            lastNowNanos = nowNanos;
        }

        prune(nowNanos);
        WindowSum last24Hours = sumWindow(nowNanos, LAST_24_HOURS_NANOS);
        WindowSum last7Days = sumWindow(nowNanos, LAST_7_DAYS_NANOS);
        WindowSum last30Days = sumWindow(nowNanos, LAST_30_DAYS_NANOS);
        if (last24Hours.overflowed || last7Days.overflowed || last30Days.overflowed) {
            state = UsageState.INVALID;
            return UsageSnapshot.invalid(state, UsageSnapshot.Status.OVERFLOW);
        }

        boolean historyLimited = eventHistoryLimitedUntilNanos != UNSET
                && nowNanos <= eventHistoryLimitedUntilNanos;
        return new UsageSnapshot(
                state,
                UsageSnapshot.Status.VALID,
                sessionNanos,
                last24Hours.value,
                last7Days.value,
                last30Days.value,
                lifetimeNanos,
                intervals.size(),
                historyLimited);
    }

    private UsageRecordResult countActiveUntil(long nowNanos) {
        long deltaNanos = nowNanos - lastNowNanos;
        lastNowNanos = nowNanos;
        if (deltaNanos == 0L) {
            return UsageRecordResult.NO_TIME;
        }
        if (deltaNanos >= maxObservationGapNanos) {
            return UsageRecordResult.GAP_REJECTED;
        }

        long nextSession;
        long nextLifetime;
        try {
            nextSession = Math.addExact(sessionNanos, deltaNanos);
            nextLifetime = Math.addExact(lifetimeNanos, deltaNanos);
        } catch (ArithmeticException exception) {
            state = UsageState.INVALID;
            return UsageRecordResult.OVERFLOW;
        }

        sessionNanos = nextSession;
        lifetimeNanos = nextLifetime;
        prune(nowNanos);
        if (intervals.size() == maxRetainedEvents) {
            UsageInterval evicted = intervals.removeFirst();
            eventHistoryLimitedUntilNanos = Math.max(
                    eventHistoryLimitedUntilNanos,
                    saturatedAdd(evicted.endNanos, MAX_WINDOW_NANOS));
        }
        intervals.addLast(new UsageInterval(nowNanos - deltaNanos, nowNanos));
        return UsageRecordResult.COUNTED;
    }

    private void prune(long nowNanos) {
        long cutoff = nowNanos > MAX_WINDOW_NANOS
                ? nowNanos - MAX_WINDOW_NANOS
                : 0L;
        while (!intervals.isEmpty() && intervals.peekFirst().endNanos <= cutoff) {
            intervals.removeFirst();
        }
        if (eventHistoryLimitedUntilNanos != UNSET && nowNanos > eventHistoryLimitedUntilNanos) {
            eventHistoryLimitedUntilNanos = UNSET;
        }
    }

    private WindowSum sumWindow(long nowNanos, long windowNanos) {
        long cutoff = nowNanos > windowNanos ? nowNanos - windowNanos : 0L;
        long total = 0L;
        for (UsageInterval interval : intervals) {
            if (interval.endNanos <= cutoff) {
                continue;
            }
            long overlapStart = Math.max(interval.startNanos, cutoff);
            long overlapEnd = Math.min(interval.endNanos, nowNanos);
            if (overlapEnd <= overlapStart) {
                continue;
            }
            try {
                total = Math.addExact(total, overlapEnd - overlapStart);
            } catch (ArithmeticException exception) {
                return new WindowSum(0L, true);
            }
        }
        return new WindowSum(total, false);
    }

    private static long saturatedAdd(long left, long right) {
        try {
            return Math.addExact(left, right);
        } catch (ArithmeticException exception) {
            return Long.MAX_VALUE;
        }
    }

    private record UsageInterval(long startNanos, long endNanos) {
        private UsageInterval {
            if (startNanos < 0L || endNanos < startNanos) {
                throw new IllegalArgumentException("usage interval must be ordered and non-negative");
            }
        }
    }

    private record WindowSum(long value, boolean overflowed) {
    }
}
