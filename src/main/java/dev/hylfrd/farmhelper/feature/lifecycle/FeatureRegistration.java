package dev.hylfrd.farmhelper.feature.lifecycle;

import java.util.Objects;
import java.util.function.BooleanSupplier;

/** One ordered feature registration with an injected, configuration-agnostic eligibility check. */
public record FeatureRegistration(
        FeatureId id,
        FeaturePolicy policy,
        BooleanSupplier enabled,
        FeatureLifecycleTarget target
) {
    public FeatureRegistration {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(policy, "policy");
        Objects.requireNonNull(enabled, "enabled");
        Objects.requireNonNull(target, "target");
    }

    public FeatureRegistration(
            FeatureId id,
            FeaturePolicy policy,
            FeatureLifecycleTarget target
    ) {
        this(id, policy, () -> true, target);
    }

    public boolean enabledNow() {
        return enabled.getAsBoolean();
    }
}
