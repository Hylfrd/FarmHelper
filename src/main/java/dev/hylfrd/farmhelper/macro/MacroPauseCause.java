package dev.hylfrd.farmhelper.macro;

/** State-preserving causes which may overlap without prematurely resuming a macro. */
public enum MacroPauseCause {
    MANUAL,
    SCREEN_OPEN,
    FEATURE
}
