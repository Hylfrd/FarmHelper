package dev.hylfrd.farmhelper.navigation.follow;

/** Follow-specific observable terminal diagnostics layered over the frozen navigation contract. */
public enum FollowTerminationReason {
    TARGET_LOST,
    TARGET_UNKNOWN,
    TARGET_CHANGED,
    WORLD_CHANGED,
    FOLLOWER_POSITION_UNAVAILABLE,
    NAVIGATION_REJECTED,
    NAVIGATION_TERMINATED,
    STALE_NAVIGATION,
    CANCELLED
}
