package dev.hylfrd.farmhelper.macro.mechanism;

/** Reusable row-progress window with explicit continuous and sampled observation policies. */
public final class RowProgressLedger {
    private final long windowNanos;
    private final double minimumProgress;
    private final int maximumMisses;
    private long observedAt;
    private double coordinate;
    private int misses;
    private boolean initialized;

    public RowProgressLedger(long windowNanos, double minimumProgress, int maximumMisses) {
        if (windowNanos < 0L || !Double.isFinite(minimumProgress) || minimumProgress < 0.0D
                || maximumMisses <= 0) {
            throw new IllegalArgumentException("invalid row progress policy");
        }
        this.windowNanos = windowNanos;
        this.minimumProgress = minimumProgress;
        this.maximumMisses = maximumMisses;
    }

    public void begin(long nowNanos, double initialCoordinate) {
        if (nowNanos < 0L || !Double.isFinite(initialCoordinate)) {
            throw new IllegalArgumentException("invalid row progress origin");
        }
        observedAt = nowNanos;
        coordinate = initialCoordinate;
        misses = 0;
        initialized = true;
    }

    /** P1 policy: any absolute movement refreshes the window immediately. */
    public Result observeContinuous(long nowNanos, double currentCoordinate) {
        requireObservation(nowNanos, currentCoordinate);
        if (Math.abs(currentCoordinate - coordinate) >= minimumProgress) {
            coordinate = currentCoordinate;
            observedAt = nowNanos;
            misses = 0;
            return Result.PROGRESSED;
        }
        return elapsed(nowNanos, observedAt) < windowNanos
                ? Result.WAITING : miss(nowNanos, currentCoordinate);
    }

    /** Melon policy: compare signed forward progress only at fixed windows. */
    public Result observeWindowed(long nowNanos, double currentCoordinate) {
        requireObservation(nowNanos, currentCoordinate);
        if (elapsed(nowNanos, observedAt) < windowNanos) {
            return Result.WAITING;
        }
        if (currentCoordinate - coordinate >= minimumProgress) {
            coordinate = currentCoordinate;
            observedAt = nowNanos;
            misses = 0;
            return Result.PROGRESSED;
        }
        return miss(nowNanos, currentCoordinate);
    }

    public void shift(long suspendedNanos) {
        if (suspendedNanos < 0L) {
            throw new IllegalArgumentException("suspension must not be negative");
        }
        if (initialized) {
            observedAt = saturatingAdd(observedAt, suspendedNanos);
        }
    }

    public void clear() {
        observedAt = 0L;
        coordinate = 0.0D;
        misses = 0;
        initialized = false;
    }

    public int misses() {
        return misses;
    }

    private Result miss(long nowNanos, double currentCoordinate) {
        coordinate = currentCoordinate;
        observedAt = nowNanos;
        misses++;
        return misses >= maximumMisses ? Result.STALLED : Result.MISSED;
    }

    private void requireObservation(long nowNanos, double currentCoordinate) {
        if (!initialized) {
            throw new IllegalStateException("row progress has not begun");
        }
        if (nowNanos < 0L || !Double.isFinite(currentCoordinate)) {
            throw new IllegalArgumentException("invalid row progress observation");
        }
    }

    private static long elapsed(long now, long then) {
        try {
            return Math.max(0L, Math.subtractExact(now, then));
        } catch (ArithmeticException exception) {
            return Long.MAX_VALUE;
        }
    }

    private static long saturatingAdd(long value, long delta) {
        try {
            return Math.addExact(value, delta);
        } catch (ArithmeticException exception) {
            return Long.MAX_VALUE;
        }
    }

    public enum Result {
        WAITING,
        PROGRESSED,
        MISSED,
        STALLED
    }
}
