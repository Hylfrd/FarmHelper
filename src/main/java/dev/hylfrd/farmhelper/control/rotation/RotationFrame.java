package dev.hylfrd.farmhelper.control.rotation;

/** One finite, normalized output sample from a rotation trajectory. */
public record RotationFrame(float yaw, float pitch, float progress) {
    public RotationFrame {
        if (!Float.isFinite(yaw) || !Float.isFinite(pitch) || !Float.isFinite(progress)) {
            throw new IllegalArgumentException("Rotation frame values must be finite");
        }
        if (yaw < -180.0F || yaw >= 180.0F) {
            throw new IllegalArgumentException("yaw must be in [-180, 180)");
        }
        if (pitch < -90.0F || pitch > 90.0F) {
            throw new IllegalArgumentException("pitch must be in [-90, 90]");
        }
        if (progress < 0.0F || progress > 1.0F) {
            throw new IllegalArgumentException("progress must be in [0, 1]");
        }
    }

    public boolean complete() {
        return progress >= 1.0F;
    }
}
