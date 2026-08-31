package dev.hylfrd.farmhelper.feature.lifecycle;

import java.util.Objects;
import java.util.Optional;

/** Result of one best-effort lifecycle transition. */
public record FeatureLifecycleResult(boolean changed, Optional<RuntimeException> failure) {
    public FeatureLifecycleResult {
        Objects.requireNonNull(failure, "failure");
    }

    public boolean succeeded() {
        return failure.isEmpty();
    }

    public boolean failed() {
        return failure.isPresent();
    }

    static FeatureLifecycleResult unchanged() {
        return new FeatureLifecycleResult(false, Optional.empty());
    }
}
