package dev.hylfrd.farmhelper.runtime.time;

/** Production monotonic elapsed-time source backed by {@link System#nanoTime()}. */
public enum SystemMonotonicClock implements MonotonicClock {
    INSTANCE;

    private final long originNanos = System.nanoTime();

    @Override
    public long nowNanos() {
        return elapsedSinceOrigin(originNanos, System.nanoTime());
    }

    static long elapsedSinceOrigin(long originNanos, long currentNanos) {
        return PausableTimer.elapsedSince(originNanos, currentNanos);
    }
}
