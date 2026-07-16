package dev.hylfrd.farmhelper.navigation;

import dev.hylfrd.farmhelper.control.input.ControlOwner;

import java.util.Objects;

/** Immutable request and its complete ownership, epoch, destination, and execution policy. */
public record NavigationRequest(
        ControlOwner owner,
        long worldEpoch,
        NavigationGoal goal,
        NavigationOptions options
) {
    public NavigationRequest {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(goal, "goal");
        Objects.requireNonNull(options, "options");
        if (worldEpoch < 0L) {
            throw new IllegalArgumentException("worldEpoch must be non-negative");
        }
    }

    /** Returns a new resolved value each time; the stored goal is never cumulatively adjusted. */
    public NavigationGoal effectiveGoal() {
        return goal.withYOffset(options.yOffset());
    }
}
