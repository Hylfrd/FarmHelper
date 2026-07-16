package dev.hylfrd.farmhelper.navigation;

/** Deterministic failures, distinct from caller and lifecycle cancellation. */
public enum NavigationFailureReason {
    WORLD_UNAVAILABLE,
    PLAYER_UNAVAILABLE,
    CONNECTION_UNAVAILABLE,
    SCREEN_PRESENT,
    SCREEN_UNKNOWN,
    WORLD_EPOCH_MISMATCH,
    INVALID_CAPTURE,
    NO_PATH,
    TIMEOUT,
    EXECUTION_FAILED,
    INTERNAL_FAILURE
}
