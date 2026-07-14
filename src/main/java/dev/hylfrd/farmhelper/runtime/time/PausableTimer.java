package dev.hylfrd.farmhelper.runtime.time;

import java.util.Objects;

/** A duration timer whose elapsed time can be paused without consulting a wall clock. */
public final class PausableTimer {
    private final MonotonicClock clock;
    private final long durationNanos;
    private long accumulatedNanos;
    private long runningSinceNanos;
    private boolean paused;

    public PausableTimer(MonotonicClock clock, long durationNanos) {
        this.clock = Objects.requireNonNull(clock, "clock");
        if (durationNanos < 0L) {
            throw new IllegalArgumentException("durationNanos must not be negative");
        }
        this.durationNanos = durationNanos;
        runningSinceNanos = clock.nowNanos();
    }

    public long durationNanos() {
        return durationNanos;
    }

    public long elapsedNanos() {
        if (paused) {
            return accumulatedNanos;
        }
        return saturatingAdd(accumulatedNanos, elapsedSince(runningSinceNanos, clock.nowNanos()));
    }

    public long remainingNanos() {
        return Math.max(0L, durationNanos - Math.min(durationNanos, elapsedNanos()));
    }

    public boolean elapsed() {
        return elapsedNanos() >= durationNanos;
    }

    public boolean paused() {
        return paused;
    }

    public void pause() {
        if (paused) {
            return;
        }
        accumulatedNanos = elapsedNanos();
        paused = true;
    }

    public void resume() {
        if (!paused) {
            return;
        }
        runningSinceNanos = clock.nowNanos();
        paused = false;
    }

    /** Resets elapsed time to zero while preserving whether the timer is paused. */
    public void reset() {
        accumulatedNanos = 0L;
        if (!paused) {
            runningSinceNanos = clock.nowNanos();
        }
    }

    static long elapsedSince(long startNanos, long nowNanos) {
        return Math.max(0L, nowNanos - startNanos);
    }

    static long saturatingAdd(long left, long right) {
        if (right > Long.MAX_VALUE - left) {
            return Long.MAX_VALUE;
        }
        return left + right;
    }
}
