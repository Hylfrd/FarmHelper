package dev.hylfrd.farmhelper.runtime.snapshot;

import java.util.Objects;

/** Immutable status-effect summary without a MobEffectInstance dependency. */
public record StatusEffectSummary(
        ResourceIdentifier identifier,
        int amplifier,
        int durationTicks,
        boolean ambient,
        boolean visible,
        boolean showIcon
) {
    public StatusEffectSummary {
        Objects.requireNonNull(identifier, "identifier");
        if (amplifier < 0) {
            throw new IllegalArgumentException("amplifier must not be negative");
        }
        if (durationTicks < -1) {
            throw new IllegalArgumentException("durationTicks must be -1 or greater");
        }
    }
}
