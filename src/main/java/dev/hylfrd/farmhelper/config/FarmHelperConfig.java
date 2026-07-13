package dev.hylfrd.farmhelper.config;

import java.util.Objects;

/** Mutable, version-independent runtime view of FarmHelper's local settings. */
public final class FarmHelperConfig {
    public static final int CURRENT_SCHEMA_VERSION = 3;
    public static final int DEFAULT_OPEN_SETTINGS_KEY = 344;

    private float targetYaw = 0.0F;
    private float targetPitch = 0.0F;
    private int openSettingsKey = DEFAULT_OPEN_SETTINGS_KEY;

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

    public void reset() {
        targetYaw = 0.0F;
        targetPitch = 0.0F;
        openSettingsKey = DEFAULT_OPEN_SETTINGS_KEY;
    }

    public FarmHelperConfig copy() {
        return fromPersisted(targetYaw, targetPitch, openSettingsKey);
    }

    public void replaceWith(FarmHelperConfig replacement) {
        Objects.requireNonNull(replacement, "replacement");
        targetYaw = replacement.targetYaw;
        targetPitch = replacement.targetPitch;
        openSettingsKey = replacement.openSettingsKey;
    }

    static FarmHelperConfig fromPersisted(float targetYaw, float targetPitch) {
        return fromPersisted(targetYaw, targetPitch, DEFAULT_OPEN_SETTINGS_KEY);
    }

    static FarmHelperConfig fromPersisted(float targetYaw, float targetPitch, int openSettingsKey) {
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
