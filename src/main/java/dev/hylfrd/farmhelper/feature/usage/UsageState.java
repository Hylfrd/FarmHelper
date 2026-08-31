package dev.hylfrd.farmhelper.feature.usage;

/** Lifecycle state of the local usage accounting domain. */
public enum UsageState {
    STOPPED,
    RUNNING,
    PAUSED,
    INVALID
}
