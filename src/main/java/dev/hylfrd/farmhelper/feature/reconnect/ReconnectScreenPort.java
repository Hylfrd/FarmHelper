package dev.hylfrd.farmhelper.feature.reconnect;

/** Screen action used only after a valid manual cancellation. */
@FunctionalInterface
public interface ReconnectScreenPort {
    boolean showTitleScreen();
}
