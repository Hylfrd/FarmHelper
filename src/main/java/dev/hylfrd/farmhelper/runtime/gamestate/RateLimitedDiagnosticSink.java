package dev.hylfrd.farmhelper.runtime.gamestate;

import dev.hylfrd.farmhelper.runtime.time.MonotonicClock;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/** Per-key diagnostic limiting driven solely by the injected monotonic clock and window. */
public final class RateLimitedDiagnosticSink implements DiagnosticSink {
    private final DiagnosticSink delegate;
    private final MonotonicClock clock;
    private final long windowNanos;
    private final Map<String, Long> lastEmission = new HashMap<>();

    public RateLimitedDiagnosticSink(
            DiagnosticSink delegate,
            MonotonicClock clock,
            long windowNanos
    ) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.clock = Objects.requireNonNull(clock, "clock");
        if (windowNanos < 0) {
            throw new IllegalArgumentException("windowNanos must be non-negative");
        }
        this.windowNanos = windowNanos;
    }

    @Override
    public synchronized void accept(ParseDiagnostic diagnostic) {
        Objects.requireNonNull(diagnostic, "diagnostic");
        long now = clock.nowNanos();
        Long previous = lastEmission.get(diagnostic.key());
        if (previous != null && !windowElapsed(now, previous)) {
            return;
        }
        lastEmission.put(diagnostic.key(), now);
        delegate.accept(diagnostic);
    }

    private boolean windowElapsed(long now, long previous) {
        if (now < previous) {
            return false;
        }
        long difference = now - previous;
        return difference < 0 || difference >= windowNanos;
    }
}
