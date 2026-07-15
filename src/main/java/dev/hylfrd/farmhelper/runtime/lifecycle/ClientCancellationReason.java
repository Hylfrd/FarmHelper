package dev.hylfrd.farmhelper.runtime.lifecycle;

/** Global client boundaries that revoke transient automation ownership. */
public enum ClientCancellationReason {
    MANUAL_STOP,
    WORLD_LOAD,
    WORLD_UNLOAD,
    DISCONNECT,
    CONNECTION_UNAVAILABLE,
    SCREEN_CHANGED,
    EXCEPTION,
    CLIENT_STOP
}
