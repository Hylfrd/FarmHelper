package dev.hylfrd.farmhelper.feature.antistuck;

/** Stable explanation for one pure AntiStuck decision. */
public enum AntiStuckDecisionReason {
    START,
    DELAY,
    INITIAL_DELAY,
    NO_TARGET,
    TARGET_SELECTED,
    RELEASE,
    COME_BACK,
    COMPLETE,
    RETRY_LIMIT,
    OVERDUE,
    UNKNOWN_EVIDENCE,
    ERROR_EVIDENCE,
    LAG_BACK_RECORDED,
    STOPPED,
    STALE_REQUEST,
    STALE_TICK,
    ALREADY_ACTIVE,
    TERMINAL
}
