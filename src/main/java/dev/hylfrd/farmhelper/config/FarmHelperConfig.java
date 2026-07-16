package dev.hylfrd.farmhelper.config;

import java.util.Objects;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import dev.hylfrd.farmhelper.macro.MacroMode;

/** Mutable, version-independent runtime view of FarmHelper's local settings. */
public final class FarmHelperConfig {
    public static final int CURRENT_SCHEMA_VERSION = 6;
    public static final int DEFAULT_OPEN_SETTINGS_KEY = 344;

    private float targetYaw = 0.0F;
    private float targetPitch = 0.0F;
    private int openSettingsKey = DEFAULT_OPEN_SETTINGS_KEY;
    private int macroMode;
    private MacroLocationConfig macroSpawn;
    private final List<MacroLocationConfig> macroRewarps = new ArrayList<>();
    private boolean alwaysHoldW;
    private boolean holdLeftClickWhenChangingRow = true;
    private boolean rotateAfterWarped;
    private boolean rotateAfterDrop;
    private boolean dontFixAfterWarping;
    private boolean customPitch;
    private float customPitchLevel;
    private boolean customYaw;
    private float customYawLevel;

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
        MacroMode.fromCode(macroMode).orElseThrow(() ->
                new IllegalArgumentException("macroMode must be in [0, 13]"));
        this.macroMode = macroMode;
    }

    public boolean alwaysHoldW() {
        return alwaysHoldW;
    }

    public void setAlwaysHoldW(boolean alwaysHoldW) {
        this.alwaysHoldW = alwaysHoldW;
    }

    public boolean holdLeftClickWhenChangingRow() {
        return holdLeftClickWhenChangingRow;
    }

    public void setHoldLeftClickWhenChangingRow(boolean holdLeftClickWhenChangingRow) {
        this.holdLeftClickWhenChangingRow = holdLeftClickWhenChangingRow;
    }

    public boolean rotateAfterWarped() {
        return rotateAfterWarped;
    }

    public void setRotateAfterWarped(boolean rotateAfterWarped) {
        this.rotateAfterWarped = rotateAfterWarped;
    }

    public boolean rotateAfterDrop() {
        return rotateAfterDrop;
    }

    public void setRotateAfterDrop(boolean rotateAfterDrop) {
        this.rotateAfterDrop = rotateAfterDrop;
    }

    public boolean dontFixAfterWarping() {
        return dontFixAfterWarping;
    }

    public void setDontFixAfterWarping(boolean dontFixAfterWarping) {
        this.dontFixAfterWarping = dontFixAfterWarping;
    }

    public boolean customPitch() {
        return customPitch;
    }

    public void setCustomPitch(boolean customPitch) {
        this.customPitch = customPitch;
    }

    public float customPitchLevel() {
        return customPitchLevel;
    }

    public void setCustomPitchLevel(float customPitchLevel) {
        this.customPitchLevel = requireRange(customPitchLevel, -90.0F, 90.0F,
                "customPitchLevel");
    }

    public boolean customYaw() {
        return customYaw;
    }

    public void setCustomYaw(boolean customYaw) {
        this.customYaw = customYaw;
    }

    public float customYawLevel() {
        return customYawLevel;
    }

    public void setCustomYawLevel(float customYawLevel) {
        this.customYawLevel = requireRange(customYawLevel, -180.0F, 180.0F,
                "customYawLevel");
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
        alwaysHoldW = false;
        holdLeftClickWhenChangingRow = true;
        rotateAfterWarped = false;
        rotateAfterDrop = false;
        dontFixAfterWarping = false;
        customPitch = false;
        customPitchLevel = 0.0F;
        customYaw = false;
        customYawLevel = 0.0F;
    }

    public FarmHelperConfig copy() {
        return fromPersisted(targetYaw, targetPitch, openSettingsKey,
                macroMode, macroSpawn, macroRewarps, alwaysHoldW,
                holdLeftClickWhenChangingRow, rotateAfterWarped, rotateAfterDrop,
                dontFixAfterWarping, customPitch, customPitchLevel, customYaw, customYawLevel);
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
        alwaysHoldW = replacement.alwaysHoldW;
        holdLeftClickWhenChangingRow = replacement.holdLeftClickWhenChangingRow;
        rotateAfterWarped = replacement.rotateAfterWarped;
        rotateAfterDrop = replacement.rotateAfterDrop;
        dontFixAfterWarping = replacement.dontFixAfterWarping;
        customPitch = replacement.customPitch;
        customPitchLevel = replacement.customPitchLevel;
        customYaw = replacement.customYaw;
        customYawLevel = replacement.customYawLevel;
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
        return fromPersisted(targetYaw, targetPitch, openSettingsKey, macroMode,
                macroSpawn, macroRewarps, false, true);
    }

    static FarmHelperConfig fromPersisted(
            float targetYaw,
            float targetPitch,
            int openSettingsKey,
            int macroMode,
            MacroLocationConfig macroSpawn,
            List<MacroLocationConfig> macroRewarps,
            boolean alwaysHoldW,
            boolean holdLeftClickWhenChangingRow
    ) {
        return fromPersisted(targetYaw, targetPitch, openSettingsKey, macroMode,
                macroSpawn, macroRewarps, alwaysHoldW, holdLeftClickWhenChangingRow,
                false, false, false, false, 0.0F, false, 0.0F);
    }

    static FarmHelperConfig fromPersisted(
            float targetYaw,
            float targetPitch,
            int openSettingsKey,
            int macroMode,
            MacroLocationConfig macroSpawn,
            List<MacroLocationConfig> macroRewarps,
            boolean alwaysHoldW,
            boolean holdLeftClickWhenChangingRow,
            boolean rotateAfterWarped,
            boolean rotateAfterDrop,
            boolean dontFixAfterWarping,
            boolean customPitch,
            float customPitchLevel,
            boolean customYaw,
            float customYawLevel
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
        config.alwaysHoldW = alwaysHoldW;
        config.holdLeftClickWhenChangingRow = holdLeftClickWhenChangingRow;
        config.rotateAfterWarped = rotateAfterWarped;
        config.rotateAfterDrop = rotateAfterDrop;
        config.dontFixAfterWarping = dontFixAfterWarping;
        config.customPitch = customPitch;
        config.setCustomPitchLevel(customPitchLevel);
        config.customYaw = customYaw;
        config.setCustomYawLevel(customYawLevel);
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

    private static float requireRange(float value, float min, float max, String name) {
        requireFinite(value, name);
        if (value < min || value > max) {
            throw new IllegalArgumentException(name + " must be between " + min + " and " + max);
        }
        return value;
    }
}
