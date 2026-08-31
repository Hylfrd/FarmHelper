package dev.hylfrd.farmhelper.feature.lifecycle;

import java.util.Objects;

/** Stable, human-readable identity for one registered feature. */
public record FeatureId(String value) {
    public FeatureId {
        Objects.requireNonNull(value, "value");
        value = value.trim();
        if (value.isEmpty()) {
            throw new IllegalArgumentException("feature id must not be blank");
        }
    }

    @Override
    public String toString() {
        return value;
    }
}
