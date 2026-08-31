package dev.hylfrd.farmhelper.feature.usage;

/** Observable outcome of one usage lifecycle transition or tick. */
public enum UsageRecordResult {
    STARTED,
    RESUMED,
    PAUSED,
    STOPPED,
    COUNTED,
    IDLE,
    NO_TIME,
    ALREADY_ACTIVE,
    ALREADY_PAUSED,
    GAP_REJECTED,
    CLOCK_REGRESSION,
    INVALID_TIME,
    INVALID_EVENT,
    OVERFLOW,
    INVALID
}
