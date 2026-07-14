package dev.hylfrd.farmhelper.runtime.snapshot;

import java.util.Objects;

/** Immutable Screen summary. Component conversion is performed by the client adapter. */
public record ScreenSnapshot(Observation<String> type, Observation<String> title) {
    public ScreenSnapshot {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(title, "title");
    }

    public static ScreenSnapshot unknownDetails() {
        return new ScreenSnapshot(Observation.unknown(), Observation.unknown());
    }
}
