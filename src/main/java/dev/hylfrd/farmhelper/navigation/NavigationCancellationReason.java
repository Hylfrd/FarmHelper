package dev.hylfrd.farmhelper.navigation;

/** Exact cancellation boundary retained by terminal results. */
public enum NavigationCancellationReason {
    STOPPED,
    SCREEN_CHANGED,
    WORLD_CHANGED,
    DISCONNECTED,
    CONNECTION_LOST,
    FAILURE,
    CLIENT_EXIT,
    REPLACED,
    OWNER_REQUESTED
}
