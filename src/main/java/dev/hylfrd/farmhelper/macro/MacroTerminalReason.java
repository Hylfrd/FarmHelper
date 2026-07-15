package dev.hylfrd.farmhelper.macro;

/** Terminal boundaries that discard a macro run and invalidate its private generation. */
public enum MacroTerminalReason {
    MANUAL_STOP,
    WORLD_CHANGE,
    DISCONNECT,
    CONNECTION_LOST,
    CLIENT_STOP,
    EXCEPTION
}
