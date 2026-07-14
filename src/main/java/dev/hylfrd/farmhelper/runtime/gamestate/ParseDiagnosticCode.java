package dev.hylfrd.farmhelper.runtime.gamestate;

/** Privacy-safe classes of parser uncertainty. */
public enum ParseDiagnosticCode {
    MALFORMED,
    CONFLICT,
    INCOMPLETE,
    OVERFLOW,
    UNKNOWN_FORMAT,
    DUPLICATE_SEQUENCE,
    INPUT_LIMIT,
    CHAT_SEQUENCE_GAP,
    CHAT_SEQUENCE_CONFLICT,
    CHAT_SEQUENCE_REORDER
}
