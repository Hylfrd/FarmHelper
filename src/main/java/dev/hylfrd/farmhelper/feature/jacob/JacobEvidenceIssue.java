package dev.hylfrd.farmhelper.feature.jacob;

/** Privacy-safe reasons why Jacob evidence cannot produce a trusted threshold decision. */
public enum JacobEvidenceIssue {
    NONE,
    SOURCE_UNKNOWN,
    INCOMPLETE,
    MALFORMED,
    UNKNOWN_FORMAT,
    OVERFLOW,
    INPUT_LIMIT,
    CONFLICT,
    THRESHOLD_UNAVAILABLE
}
