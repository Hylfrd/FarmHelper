package dev.hylfrd.farmhelper.client.ui.command;

/** Injection point for S1-T4's native settings screen without making commands own Screen logic. */
@FunctionalInterface
public interface SettingsScreenOpener {
    boolean open();

    static SettingsScreenOpener unavailable() {
        return () -> false;
    }
}
