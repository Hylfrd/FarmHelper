package dev.hylfrd.farmhelper.feature.antistuck;

/** Observable result category for one start, tick, stop, or lag-back update. */
public enum AntiStuckDecisionKind {
    STARTED,
    WAITING,
    ADVANCED,
    STOPPED,
    REWARP,
    FAIL_CLOSED,
    STALE_REQUEST,
    STALE_TICK,
    ALREADY_ACTIVE,
    TERMINAL
}
