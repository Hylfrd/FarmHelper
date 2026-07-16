package dev.hylfrd.farmhelper.macro;

import dev.hylfrd.farmhelper.control.rotation.RotationProfile;

import java.util.Objects;

public record MacroRotationRequest(
        float yaw,
        float pitch,
        long durationMillis,
        RotationProfile profile,
        float backModifier
) {
    public MacroRotationRequest {
        if (!Float.isFinite(yaw) || !Float.isFinite(pitch)) {
            throw new IllegalArgumentException("rotation angles must be finite");
        }
        if (durationMillis < 0L) {
            throw new IllegalArgumentException("rotation duration must not be negative");
        }
        Objects.requireNonNull(profile, "profile");
        if (!Float.isFinite(backModifier) || backModifier < -0.25F || backModifier >= 0.25F) {
            throw new IllegalArgumentException("back modifier must be in [-0.25, 0.25)");
        }
    }

    public MacroRotationRequest(float yaw, float pitch, long durationMillis) {
        this(yaw, pitch, durationMillis, RotationProfile.LEGACY_QUART, 0.0F);
    }
}
