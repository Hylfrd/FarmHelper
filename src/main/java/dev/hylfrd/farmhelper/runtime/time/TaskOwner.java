package dev.hylfrd.farmhelper.runtime.time;

import java.util.Objects;

/** Stable owner identity used to cancel a related group of client tasks. */
public record TaskOwner(String id) {
    public TaskOwner {
        Objects.requireNonNull(id, "id");
        if (id.isBlank()) {
            throw new IllegalArgumentException("id must not be blank");
        }
    }
}
