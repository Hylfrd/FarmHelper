package dev.hylfrd.farmhelper.runtime.time;

/** Production monotonic time source backed by {@link System#nanoTime()}. */
public enum SystemMonotonicClock implements MonotonicClock {
    INSTANCE;

    @Override
    public long nowNanos() {
        return System.nanoTime();
    }
}
