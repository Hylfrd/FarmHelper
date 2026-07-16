package dev.hylfrd.farmhelper.navigation;

/** How a later executor may own player rotation while following a path. */
public enum NavigationRotationPolicy {
    NONE,
    FACE_PATH,
    FACE_GOAL
}
