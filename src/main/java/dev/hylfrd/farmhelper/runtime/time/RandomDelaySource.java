package dev.hylfrd.farmhelper.runtime.time;

import java.util.Objects;
import java.util.random.RandomGenerator;

/** Inclusive, bounded random delay source with an injectable generator. */
public final class RandomDelaySource {
    private final RandomGenerator random;
    private final long minimumNanos;
    private final long maximumNanos;

    public RandomDelaySource(RandomGenerator random, long minimumNanos, long maximumNanos) {
        this.random = Objects.requireNonNull(random, "random");
        if (minimumNanos < 0L) {
            throw new IllegalArgumentException("minimumNanos must not be negative");
        }
        if (maximumNanos < minimumNanos) {
            throw new IllegalArgumentException("maximumNanos must not be less than minimumNanos");
        }
        this.minimumNanos = minimumNanos;
        this.maximumNanos = maximumNanos;
    }

    public long minimumNanos() {
        return minimumNanos;
    }

    public long maximumNanos() {
        return maximumNanos;
    }

    public long nextDelayNanos() {
        if (minimumNanos == maximumNanos) {
            return minimumNanos;
        }
        long range = maximumNanos - minimumNanos + 1L;
        if (range > 0L) {
            return minimumNanos + random.nextLong(range);
        }
        return random.nextLong() & Long.MAX_VALUE;
    }
}
