package dev.hylfrd.farmhelper.macro;

/** Stable reasons that a later shared AntiStuck owner may consume. */
public enum MacroRecoveryReason {
    ROW_STALLED,
    LANE_BLOCKED,
    LANE_STALLED
}
