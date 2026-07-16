package dev.hylfrd.farmhelper.navigation;

/** Observable contract phases; later search/follow/execution leaves own their algorithms. */
public enum NavigationPhase {
    REQUESTED,
    CAPTURING,
    SEARCHING,
    FOLLOWING,
    EXECUTING
}
