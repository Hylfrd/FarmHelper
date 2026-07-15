package dev.hylfrd.farmhelper.runtime.snapshot;

import java.util.Objects;

/** Immutable Screen summary with a client-session-stable Screen/Menu lifetime identity. */
public record ScreenSnapshot(long identity, Observation<String> type, Observation<String> title) {
    public ScreenSnapshot {
        if (identity < 0L) {
            throw new IllegalArgumentException("identity must be non-negative");
        }
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(title, "title");
    }

    /** Compatibility constructor for pure fixtures that do not model platform lifetimes. */
    public ScreenSnapshot(Observation<String> type, Observation<String> title) {
        this(0L, type, title);
    }

    public static ScreenSnapshot unknownDetails() {
        return new ScreenSnapshot(0L, Observation.unknown(), Observation.unknown());
    }
}
