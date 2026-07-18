package dev.hylfrd.farmhelper.navigation.search;

/** Typed terminal state for one immutable path-search attempt. */
public enum PathSearchOutcome {
    FOUND,
    PARTIAL,
    ALREADY_AT_GOAL,
    NO_PATH,
    TIMEOUT,
    BUDGET_EXHAUSTED
}
