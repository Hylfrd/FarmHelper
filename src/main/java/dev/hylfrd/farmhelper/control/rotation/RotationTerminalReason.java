package dev.hylfrd.farmhelper.control.rotation;

/** Closed diagnostic reason for the most recent rotation termination. */
public enum RotationTerminalReason {
    COMPLETED,
    OWNER_CANCELLED,
    STOPPED,
    SCREEN_CHANGED,
    PLAYER_MISSING,
    WORLD_CHANGED,
    DISCONNECTED,
    EXCEPTION,
    CLIENT_SHUTDOWN,
    REPLACED,
    APPLICATION_FAILED;

    static RotationTerminalReason fromCancelReason(RotationCancelReason reason) {
        return switch (reason) {
            case OWNER_CANCELLED -> OWNER_CANCELLED;
            case STOPPED -> STOPPED;
            case SCREEN_CHANGED -> SCREEN_CHANGED;
            case PLAYER_MISSING -> PLAYER_MISSING;
            case WORLD_CHANGED -> WORLD_CHANGED;
            case DISCONNECTED -> DISCONNECTED;
            case EXCEPTION -> EXCEPTION;
            case CLIENT_SHUTDOWN -> CLIENT_SHUTDOWN;
        };
    }
}
