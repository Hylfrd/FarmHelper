package dev.hylfrd.farmhelper.macro;

public record MacroRotationRequest(float yaw, float pitch, long durationMillis) {
    public MacroRotationRequest {
        if (!Float.isFinite(yaw) || !Float.isFinite(pitch)) {
            throw new IllegalArgumentException("rotation angles must be finite");
        }
        if (durationMillis < 0L) {
            throw new IllegalArgumentException("rotation duration must not be negative");
        }
    }
}
