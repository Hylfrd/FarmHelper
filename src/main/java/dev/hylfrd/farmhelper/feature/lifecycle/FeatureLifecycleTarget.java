package dev.hylfrd.farmhelper.feature.lifecycle;

import dev.hylfrd.farmhelper.macro.MacroPauseCause;

import java.util.Set;

/** Side-effect boundary driven by {@link FeatureLifecycle}; it does not own lifecycle state. */
public interface FeatureLifecycleTarget {
    default void enable(FeatureRunIdentity identity) {
    }

    default void pause(FeatureRunIdentity identity, Set<MacroPauseCause> causes) {
    }

    default void resume(FeatureRunIdentity identity) {
    }

    default void stop(FeatureRunIdentity identity, FeatureStopCause cause) {
    }

    default void reset() {
    }
}
