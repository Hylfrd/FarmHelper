package dev.hylfrd.farmhelper.ui.settings;

/** Stable categories present in the Stage 1 settings framework. */
public enum SettingCategory {
    ROTATION("Rotation"),
    INTERFACE("Interface");

    private final String label;

    SettingCategory(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
