package dev.hylfrd.farmhelper.runtime.time;

final class ManualClock implements MonotonicClock {
    private long nowNanos;

    @Override
    public long nowNanos() {
        return nowNanos;
    }

    void advanceNanos(long deltaNanos) {
        if (deltaNanos < 0L) {
            throw new IllegalArgumentException("deltaNanos must not be negative");
        }
        nowNanos += deltaNanos;
    }
}
