package dev.hylfrd.farmhelper.ui.settings;

import dev.hylfrd.farmhelper.config.FarmHelperConfig;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Stable list of settings currently backed by the typed configuration model. */
public final class SettingsCatalog {
    public static final SettingDefinition<Double> TARGET_YAW = SettingDefinition.decimal(
            "rotation.targetYaw", SettingCategory.ROTATION, "Target yaw",
            "Yaw used by the explicit rotation test.", -180.0, 179.999,
            config -> (double) config.targetYaw(), (config, value) -> config.setTargetYaw(value.floatValue()),
            "angle", "horizontal");
    public static final SettingDefinition<Double> TARGET_PITCH = SettingDefinition.decimal(
            "rotation.targetPitch", SettingCategory.ROTATION, "Target pitch",
            "Pitch used by the explicit rotation test.", -90.0, 90.0,
            config -> (double) config.targetPitch(), (config, value) -> config.setTargetPitch(value.floatValue()),
            "angle", "vertical");
    public static final SettingDefinition<Void> RESET_ROTATION = SettingDefinition.action(
            "rotation.reset", SettingCategory.ROTATION, "Reset rotation",
            "Restore the rotation draft to its default values.", config -> {
                config.setTargetYaw(0.0F);
                config.setTargetPitch(0.0F);
            }, "default");
    public static final SettingDefinition<Integer> OPEN_SETTINGS_KEY = SettingDefinition.keybind(
            "interface.openSettingsKey", SettingCategory.INTERFACE, "Open settings key",
            "Keyboard key used to open this screen while playing.",
            FarmHelperConfig::openSettingsKey, FarmHelperConfig::setOpenSettingsKey,
            "keyboard", "shortcut", "bind");

    private final List<SettingDefinition<?>> definitions;

    public SettingsCatalog(List<SettingDefinition<?>> definitions) {
        this.definitions = List.copyOf(definitions);
        Set<String> ids = new HashSet<>();
        for (SettingDefinition<?> definition : this.definitions) {
            if (!ids.add(definition.id())) {
                throw new IllegalArgumentException("Duplicate setting id " + definition.id());
            }
        }
    }

    public static SettingsCatalog standard() {
        return new SettingsCatalog(List.of(TARGET_YAW, TARGET_PITCH, RESET_ROTATION, OPEN_SETTINGS_KEY));
    }

    public List<SettingDefinition<?>> definitions() {
        return definitions;
    }
}
