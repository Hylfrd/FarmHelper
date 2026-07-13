package dev.hylfrd.farmhelper.macro;

public record MacroContext(
        boolean playerReady,
        boolean screenOpen,
        PauseReason pauseReason,
        WorldMode worldMode,
        PlayerSnapshot player
) {
}
