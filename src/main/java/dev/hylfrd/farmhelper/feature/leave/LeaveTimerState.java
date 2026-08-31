package dev.hylfrd.farmhelper.feature.leave;

/** Durable leave-timer phases; no phase owns a client or network object. */
public enum LeaveTimerState {
    STOPPED,
    COUNTING_DOWN,
    DISCONNECT_PENDING,
    COMPLETE
}
