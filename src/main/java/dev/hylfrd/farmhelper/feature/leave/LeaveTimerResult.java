package dev.hylfrd.farmhelper.feature.leave;

/** Observable result of one explicit leave-timer tick. */
public enum LeaveTimerResult {
    STOPPED,
    COUNTING_DOWN,
    FAILSAFE_ACTIVE,
    OTHER_FEATURE_ACTIVE,
    MACRO_INACTIVE,
    STALE_MACRO,
    MACRO_STOP_REQUESTED,
    DISCONNECT_PENDING,
    DISCONNECT_REQUESTED,
    COMPLETE
}
