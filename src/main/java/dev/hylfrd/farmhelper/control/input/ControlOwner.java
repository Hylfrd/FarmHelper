package dev.hylfrd.farmhelper.control.input;

import java.util.Objects;

/** Stable identity for one feature that may own managed client input. */
public record ControlOwner(String id) {
    public ControlOwner {
        Objects.requireNonNull(id, "id");
        id = id.trim();
        if (id.isEmpty()) {
            throw new IllegalArgumentException("Control owner id must not be blank");
        }
    }
}
