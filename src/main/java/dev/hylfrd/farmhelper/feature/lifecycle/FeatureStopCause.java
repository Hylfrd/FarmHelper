package dev.hylfrd.farmhelper.feature.lifecycle;

/** Why a manager-owned feature run is being terminated. */
public enum FeatureStopCause {
    REQUESTED,
    EXCLUDED,
    FAILSAFE,
    MACRO_STOPPED,
    CALLBACK_FAILED,
    START_ROLLBACK
}
