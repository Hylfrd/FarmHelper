package dev.hylfrd.farmhelper.feature.lifecycle;

import java.util.Objects;

/** Immutable lifecycle behavior for one feature registration. */
public record FeaturePolicy(
        boolean startAtMacroStart,
        boolean pauseWithMacro,
        boolean pausesMacro,
        FeatureFailsafePolicy failsafePolicy
) {
    public FeaturePolicy {
        Objects.requireNonNull(failsafePolicy, "failsafePolicy");
        if (pauseWithMacro && pausesMacro) {
            throw new IllegalArgumentException(
                    "a macro-pausing feature must remain active while its suspension is held");
        }
    }
}
