package dev.hylfrd.farmhelper.runtime.snapshot;

import java.util.Objects;

/** Immutable main-hand item summary without an ItemStack dependency. */
public record ItemSummary(ResourceIdentifier identifier, int count) {
    public ItemSummary {
        Objects.requireNonNull(identifier, "identifier");
        if (count <= 0) {
            throw new IllegalArgumentException("count must be positive");
        }
    }
}
