package dev.hylfrd.farmhelper.config;

import java.util.Objects;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Mutable, version-independent runtime view of FarmHelper's local settings. */
public final class FarmHelperConfig {
    public static final int CURRENT_SCHEMA_VERSION = 4;
    public static final int DEFAULT_OPEN_SETTINGS_KEY = 344;

    private float targetYaw = 0.0F;
    private float targetPitch = 0.0F;
    private int openSettingsKey = DEFAULT_OPEN_SETTINGS_KEY;
    private int macroMode;
    private MacroLocationConfig macroSpawn;
    private final List<MacroLocationConfig> macroRewarps = new ArrayList<>();

    public int schemaVersion() {
        return CURRENT_SCHEMA_VERSION;
    }

    public float targetYaw() {
        return targetYaw;
    }

    public void setTargetYaw(float targetYaw) {
        requireFinite(targetYaw, "targetYaw");
        this.targetYaw = normalizeYaw(targetYaw);
    }

    public float targetPitch() {
        return targetPitch;
    }

    public void setTargetPitch(float targetPitch) {
        requireFinite(targetPitch, "targetPitch");
        this.targetPitch = clamp(targetPitch, -90.0F, 90.0F);
    }

    public int openSettingsKey() {
        return openSettingsKey;
    }

    public void setOpenSettingsKey(int openSettingsKey) {
        if (openSettingsKey != -1 && (openSettingsKey < 32 || openSettingsKey > 348)) {
            throw new IllegalArgumentException("openSettingsKey must be -1 or a valid keyboard key code");
        }
        this.openSettingsKey = openSettingsKey;
    }

    public int macroMode() {
        return macroMode;
    }

    public void setMacroMode(int macroMode) {
        if (macroMode != 0 && macroMode != 1 && macroMode != 2
                && macroMode != 5 && macroMode != 6 && macroMode != 9) {
            throw new IllegalArgumentException("macroMode must be 0, 1, 2, 5, 6, or 9");
        }
        this.macroMode = macroMode;
    }

    public Optional<MacroLocationConfig> macroSpawn() {
        return Optional.ofNullable(macroSpawn);
    }

    public List<MacroLocationConfig> macroRewarps() {
        return List.copyOf(macroRewarps);
    }

    public void setMacroSpawn(MacroLocationConfig spawn) {
        macroSpawn = Objects.requireNonNull(spawn, "spawn");
        macroRewarps.removeIf(rewarp -> rewarp.squaredDistance(spawn) < 4.0D);
    }

    public boolean addMacroRewarp(MacroLocationConfig rewarp) {
        MacroLocationConfig next = Objects.requireNonNull(rewarp, "rewarp");
        if (macroSpawn != null && macroSpawn.squaredDistance(next) < 4.0D) {
            return false;
        }
        if (macroRewarps.stream().anyMatch(existing -> existing.squaredDistance(next) < 4.0D)) {
            return false;
        }
        macroRewarps.add(next);
        return true;
    }

    public boolean removeMacroRewarp(MacroLocationConfig position, double maximumDistance) {
        MacroLocationConfig nearest = macroRewarps.stream()
                .min(java.util.Comparator.comparingDouble(position::squaredDistance))
                .orElse(null);
        return nearest != null && nearest.squaredDistance(position) <= maximumDistance * maximumDistance
                && macroRewarps.remove(nearest);
    }

    public void clearMacroRewarps() {
        macroRewarps.clear();
    }

    public void reset() {
        targetYaw = 0.0F;
        targetPitch = 0.0F;
        openSettingsKey = DEFAULT_OPEN_SETTINGS_KEY;
        macroMode = 0;
        macroSpawn = null;
        macroRewarps.clear();
    }

    public FarmHelperConfig copy() {
        return fromPersisted(targetYaw, targetPitch, openSettingsKey,
                macroMode, macroSpawn, macroRewarps);
    }

    public void replaceWith(FarmHelperConfig replacement) {
        Objects.requireNonNull(replacement, "replacement");
        targetYaw = replacement.targetYaw;
        targetPitch = replacement.targetPitch;
        openSettingsKey = replacement.openSettingsKey;
        macroMode = replacement.macroMode;
        macroSpawn = replacement.macroSpawn;
        macroRewarps.clear();
        macroRewarps.addAll(replacement.macroRewarps);
    }

    static FarmHelperConfig fromPersisted(float targetYaw, float targetPitch) {
        return fromPersisted(targetYaw, targetPitch, DEFAULT_OPEN_SETTINGS_KEY);
    }

    static FarmHelperConfig fromPersisted(float targetYaw, float targetPitch, int openSettingsKey) {
        return fromPersisted(targetYaw, targetPitch, openSettingsKey, 0, null, List.of());
    }

    static FarmHelperConfig fromPersisted(
            float targetYaw,
            float targetPitch,
            int openSettingsKey,
            int macroMode,
            MacroLocationConfig macroSpawn,
            List<MacroLocationConfig> macroRewarps
    ) {
        requireFinite(targetYaw, "rotation.targetYaw");
        requireFinite(targetPitch, "rotation.targetPitch");
        if (targetYaw < -180.0F || targetYaw >= 180.0F) {
            throw new IllegalArgumentException("rotation.targetYaw must be at least -180 and less than 180");
        }
        if (targetPitch < -90.0F || targetPitch > 90.0F) {
            throw new IllegalArgumentException("rotation.targetPitch must be between -90 and 90");
        }

        FarmHelperConfig config = new FarmHelperConfig();
        config.targetYaw = targetYaw;
        config.targetPitch = targetPitch;
        config.setOpenSettingsKey(openSettingsKey);
        config.setMacroMode(macroMode);
        if (macroSpawn != null) {
            config.setMacroSpawn(macroSpawn);
        }
        for (MacroLocationConfig rewarp : List.copyOf(macroRewarps)) {
            if (!config.addMacroRewarp(rewarp)) {
                throw new IllegalArgumentException("macro rewarps overlap spawn or each other");
            }
        }
        return config;
    }

    private static float normalizeYaw(float value) {
        float yaw = value % 360.0F;
        if (yaw >= 180.0F) {
            yaw -= 360.0F;
        }
        if (yaw < -180.0F) {
            yaw += 360.0F;
        }
        return yaw;
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    private static void requireFinite(float value, String name) {
        if (!Float.isFinite(value)) {
            throw new IllegalArgumentException(name + " must be finite");
        }
    }
}
