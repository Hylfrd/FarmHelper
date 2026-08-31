package dev.hylfrd.farmhelper.navigation.simulation;

import java.util.Objects;
import java.util.Optional;

/** Read-only world evidence that distinguishes a known value from an unavailable value. */
public record FlyStoppingEvidence<T>(
        Optional<T> value,
        Optional<FlyStoppingFailure> failure
) {
    public FlyStoppingEvidence {
        Objects.requireNonNull(value, "value");
        Objects.requireNonNull(failure, "failure");
        if (value.isPresent() == failure.isPresent()) {
            throw new IllegalArgumentException("evidence must contain exactly one payload");
        }
        if (failure.isPresent() && !failure.get().evidenceFailure()) {
            throw new IllegalArgumentException("failure does not describe world evidence");
        }
    }

    public static <T> FlyStoppingEvidence<T> known(T value) {
        return new FlyStoppingEvidence<>(
                Optional.of(Objects.requireNonNull(value, "value")), Optional.empty());
    }

    public static <T> FlyStoppingEvidence<T> unknown(FlyStoppingFailure failure) {
        Objects.requireNonNull(failure, "failure");
        if (!failure.evidenceFailure()) {
            throw new IllegalArgumentException("failure does not describe world evidence");
        }
        return new FlyStoppingEvidence<>(Optional.empty(), Optional.of(failure));
    }

    public boolean isKnown() {
        return value.isPresent();
    }

    public boolean isUnknown() {
        return failure.isPresent();
    }
}
