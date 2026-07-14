package dev.hylfrd.farmhelper.control.input;

/** Safety events that globally revoke every managed input claim. */
public enum ReleaseReason {
    STOP,
    PAUSE,
    SCREEN,
    DISCONNECT,
    WORLD_CHANGE,
    ROTATION_CONFLICT,
    EXCEPTION,
    EXIT
}
