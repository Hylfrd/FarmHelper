package dev.hylfrd.farmhelper.navigation;

/** Navigation-specific evidence reason; non-passable unknowns are never interpreted as air. */
public enum SpaceEvidenceReason {
    PASSABLE,
    COLLISION,
    FLUID_OBSTRUCTION,
    NO_SUPPORT,
    UNKNOWN_EVIDENCE,
    MISSING_EVIDENCE,
    UNLOADED,
    COLLISION_ERROR,
    SEGMENT_GAP,
    CONFLICT,
    OUTSIDE_BOUNDS,
    STALE_TICKET,
    QUERY_TOO_LARGE
}
