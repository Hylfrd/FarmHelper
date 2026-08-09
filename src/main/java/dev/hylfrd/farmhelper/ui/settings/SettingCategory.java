package dev.hylfrd.farmhelper.ui.settings;

/** Stable categories present in the native settings framework. */
public enum SettingCategory {
    MACRO("Macro"),
    ROTATION("Rotation"),
    FAILSAFE("Failsafe"),
    INTERFACE("Interface");

    private final String label;

    SettingCategory(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
