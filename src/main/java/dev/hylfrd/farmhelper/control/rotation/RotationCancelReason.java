package dev.hylfrd.farmhelper.control.rotation;

/** Explicit external reasons that may cancel an active rotation. */
public enum RotationCancelReason {
    OWNER_CANCELLED,
    STOPPED,
    PLAYER_MISSING,
    WORLD_CHANGED,
    DISCONNECTED,
    CLIENT_SHUTDOWN
}
