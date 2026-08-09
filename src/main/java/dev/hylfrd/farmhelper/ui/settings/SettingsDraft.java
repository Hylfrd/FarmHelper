package dev.hylfrd.farmhelper.ui.settings;

import dev.hylfrd.farmhelper.config.FarmHelperConfig;

import java.util.Objects;

/** Isolated mutable draft. The live configuration is never edited through this type. */
public final class SettingsDraft {
    private FarmHelperConfig baseline;
    private final FarmHelperConfig value;

    public SettingsDraft(FarmHelperConfig liveConfig) {
        Objects.requireNonNull(liveConfig, "liveConfig");
        baseline = liveConfig.copy();
        value = liveConfig.copy();
    }

    public <T> T read(SettingDefinition<T> definition) {
        return definition.read(value);
    }

    public <T> void write(SettingDefinition<T> definition, T newValue) {
        definition.write(value, newValue);
    }

    public void activate(SettingDefinition<?> definition) {
        definition.activate(value);
    }

    public void reset(SettingDefinition<?> definition) {
        definition.reset(value);
    }

    public void resetCategory(SettingsCatalog catalog, SettingCategory category) {
        catalog.definitions().stream()
                .filter(definition -> definition.category() == category)
                .filter(definition -> definition.kind() != SettingKind.ACTION)
                .forEach(this::reset);
    }

    public boolean dirty() {
        return !equivalent(baseline, value);
    }

    public FarmHelperConfig snapshot() {
        return value.copy();
    }

    public void markSaved() {
        baseline = value.copy();
    }

    private static boolean equivalent(FarmHelperConfig left, FarmHelperConfig right) {
        return Float.compare(left.targetYaw(), right.targetYaw()) == 0
                && Float.compare(left.targetPitch(), right.targetPitch()) == 0
                && left.openSettingsKey() == right.openSettingsKey()
                && left.macroMode() == right.macroMode()
                && left.alwaysHoldW() == right.alwaysHoldW()
                && left.holdLeftClickWhenChangingRow() == right.holdLeftClickWhenChangingRow()
                && left.rotateAfterWarped() == right.rotateAfterWarped()
                && left.rotateAfterDrop() == right.rotateAfterDrop()
                && left.dontFixAfterWarping() == right.dontFixAfterWarping()
                && left.customPitch() == right.customPitch()
                && Float.compare(left.customPitchLevel(), right.customPitchLevel()) == 0
                && left.customYaw() == right.customYaw()
                && Float.compare(left.customYawLevel(), right.customYawLevel()) == 0
                && left.checkDesync() == right.checkDesync()
                && left.desyncPauseDelayMillis() == right.desyncPauseDelayMillis()
                && Objects.equals(left.macroSpawn(), right.macroSpawn())
                && left.macroRewarps().equals(right.macroRewarps());
    }
}
