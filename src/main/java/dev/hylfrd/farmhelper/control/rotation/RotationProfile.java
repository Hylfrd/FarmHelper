package dev.hylfrd.farmhelper.control.rotation;

/** Easing pairs preserved from the fixed upstream rotation handler. */
public enum RotationProfile {
    LEGACY_QUART,
    EXPO_QUART,
    BACK;

    public float yaw(float progress, float backModifier) {
        return switch (this) {
            case LEGACY_QUART -> quart(progress);
            case EXPO_QUART -> expo(progress);
            case BACK -> back(progress, backModifier);
        };
    }

    public float pitch(float progress, float backModifier) {
        return switch (this) {
            case LEGACY_QUART, EXPO_QUART -> quart(progress);
            case BACK -> back(progress, backModifier);
        };
    }

    private static float quart(float value) {
        double remaining = 1.0D - value;
        return (float) (1.0D - remaining * remaining * remaining * remaining);
    }

    private static float expo(float value) {
        return value == 1.0F ? 1.0F : (float) (1.0D - Math.pow(2.0D, -10.0D * value));
    }

    private static float back(float value, float modifier) {
        if (!Float.isFinite(modifier) || modifier < -0.25F || modifier >= 0.25F) {
            throw new IllegalArgumentException("back modifier must be in [-0.25, 0.25)");
        }
        double c1 = 1.70158D + modifier;
        double c3 = c1 + 1.0D;
        double shifted = value - 1.0D;
        return (float) (1.0D + c3 * shifted * shifted * shifted + c1 * shifted * shifted);
    }
}
