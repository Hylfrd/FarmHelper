package dev.hylfrd.farmhelper.ui.settings;

import dev.hylfrd.farmhelper.config.FarmHelperConfig;
import dev.hylfrd.farmhelper.macro.MacroMode;

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
    public static final SettingDefinition<MacroMode> MACRO_MODE = SettingDefinition.enumeration(
            "macro.mode", SettingCategory.MACRO, "Macro mode",
            "Farm type selected by the fixed macro dispatch table.",
            List.of(MacroMode.values()),
            config -> MacroMode.fromCode(config.macroMode()).orElseThrow(
                    () -> new IllegalStateException("Unknown persisted macro mode")),
            (config, value) -> config.setMacroMode(value.code()),
            "macro", "mode", "farm", "crop");
    public static final SettingDefinition<Boolean> ALWAYS_HOLD_W = SettingDefinition.bool(
            "macro.alwaysHoldW", SettingCategory.MACRO, "Always hold W while farming",
            "Always hold W while farming.", FarmHelperConfig::alwaysHoldW,
            FarmHelperConfig::setAlwaysHoldW, "macro", "forward", "movement");
    public static final SettingDefinition<Boolean> HOLD_LEFT_CLICK_WHEN_CHANGING_ROW =
            SettingDefinition.bool(
                    "macro.holdLeftClickWhenChangingRow", SettingCategory.MACRO,
                    "Hold left click when changing row", "Hold left click when changing row.",
                    FarmHelperConfig::holdLeftClickWhenChangingRow,
                    FarmHelperConfig::setHoldLeftClickWhenChangingRow,
                    "macro", "row", "attack", "click");
    public static final SettingDefinition<Boolean> ROTATE_AFTER_WARPED = SettingDefinition.bool(
            "rotation.rotateAfterWarped", SettingCategory.ROTATION, "Rotate after warped",
            "Rotates the player after re-warping.", FarmHelperConfig::rotateAfterWarped,
            FarmHelperConfig::setRotateAfterWarped, "rotation", "warp", "rewarp");
    public static final SettingDefinition<Boolean> ROTATE_AFTER_DROP = SettingDefinition.bool(
            "rotation.rotateAfterDrop", SettingCategory.ROTATION, "Rotate after drop",
            "Rotates after the player falls down.", FarmHelperConfig::rotateAfterDrop,
            FarmHelperConfig::setRotateAfterDrop, "rotation", "drop", "fall");
    public static final SettingDefinition<Boolean> DONT_FIX_AFTER_WARPING = SettingDefinition.bool(
            "rotation.dontFixAfterWarping", SettingCategory.ROTATION,
            "Don't fix micro rotations after warp",
            "The macro does not do micro-rotations after rewarp when the current and target yaw match.",
            FarmHelperConfig::dontFixAfterWarping, FarmHelperConfig::setDontFixAfterWarping,
            "rotation", "warp", "rewarp", "micro");
    public static final SettingDefinition<Boolean> CUSTOM_PITCH = SettingDefinition.bool(
            "rotation.customPitch", SettingCategory.ROTATION, "Custom pitch",
            "Set pitch to a custom level after starting the macro.", FarmHelperConfig::customPitch,
            FarmHelperConfig::setCustomPitch, "rotation", "pitch", "custom");
    public static final SettingDefinition<Double> CUSTOM_PITCH_LEVEL = SettingDefinition.decimal(
            "rotation.customPitchLevel", SettingCategory.ROTATION, "Custom pitch level",
            "Custom pitch level used after starting the macro.", -90.0, 90.0,
            config -> (double) config.customPitchLevel(),
            (config, value) -> config.setCustomPitchLevel(value.floatValue()),
            "rotation", "pitch", "custom", "angle");
    public static final SettingDefinition<Boolean> CUSTOM_YAW = SettingDefinition.bool(
            "rotation.customYaw", SettingCategory.ROTATION, "Custom yaw",
            "Set yaw to a custom level after starting the macro.", FarmHelperConfig::customYaw,
            FarmHelperConfig::setCustomYaw, "rotation", "yaw", "custom");
    public static final SettingDefinition<Double> CUSTOM_YAW_LEVEL = SettingDefinition.decimal(
            "rotation.customYawLevel", SettingCategory.ROTATION, "Custom yaw level",
            "Custom yaw level used after starting the macro.", -180.0, 180.0,
            config -> (double) config.customYawLevel(),
            (config, value) -> config.setCustomYawLevel(value.floatValue()),
            "rotation", "yaw", "custom", "angle");
    public static final SettingDefinition<Boolean> CHECK_DESYNC = SettingDefinition.bool(
            "desync.checkDesync", SettingCategory.FAILSAFE, "Check desync",
            "If client desynchronization is detected, it activates a failsafe. Turn this off if the network is weak or if it happens frequently.",
            FarmHelperConfig::checkDesync, FarmHelperConfig::setCheckDesync,
            "desync", "failsafe", "synchronization");
    public static final SettingDefinition<Integer> DESYNC_PAUSE_DELAY = SettingDefinition.integer(
            "desync.pauseDelayMillis", SettingCategory.FAILSAFE,
            "Pause for X milliseconds after desync triggered",
            "The delay to pause after desync triggered, in milliseconds.",
            FarmHelperConfig.MIN_DESYNC_PAUSE_DELAY_MILLIS,
            FarmHelperConfig.MAX_DESYNC_PAUSE_DELAY_MILLIS,
            FarmHelperConfig::desyncPauseDelayMillis,
            FarmHelperConfig::setDesyncPauseDelayMillis,
            "desync", "failsafe", "delay", "milliseconds");

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
        return new SettingsCatalog(List.of(
                TARGET_YAW, TARGET_PITCH, RESET_ROTATION, OPEN_SETTINGS_KEY,
                MACRO_MODE, ALWAYS_HOLD_W, HOLD_LEFT_CLICK_WHEN_CHANGING_ROW,
                ROTATE_AFTER_WARPED, ROTATE_AFTER_DROP, DONT_FIX_AFTER_WARPING,
                CUSTOM_PITCH, CUSTOM_PITCH_LEVEL, CUSTOM_YAW, CUSTOM_YAW_LEVEL,
                CHECK_DESYNC, DESYNC_PAUSE_DELAY));
    }

    public List<SettingDefinition<?>> definitions() {
        return definitions;
    }
}
