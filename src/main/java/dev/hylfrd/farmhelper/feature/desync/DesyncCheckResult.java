package dev.hylfrd.farmhelper.feature.desync;

/** Observable result of one Desync click or deterministic recovery step. */
public enum DesyncCheckResult {
    DISABLED,
    STOPPED,
    STALE_IDENTITY,
    MACRO_INACTIVE,
    CLICK_BLOCK_UNKNOWN,
    NOT_CROP,
    FAILSAFE_ACTIVE,
    CONNECTION_UNAVAILABLE,
    SERVER_UNKNOWN,
    SERVER_LAGGING,
    ACCEPTED,
    TRIGGERED,
    RECOVERY_PENDING,
    RECOVERED
}
