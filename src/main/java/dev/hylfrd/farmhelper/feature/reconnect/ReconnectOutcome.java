package dev.hylfrd.farmhelper.feature.reconnect;

/** Whether one controller decision is active, terminal, or a rejected request. */
public enum ReconnectOutcome {
    IDLE,
    RUNNING,
    SUCCEEDED,
    CANCELLED,
    TIMED_OUT,
    FAILED,
    REJECTED
}
