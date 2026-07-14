package dev.hylfrd.farmhelper.runtime.snapshot;

/** Exact player rotation. Zero yaw and pitch are valid observations. */
public record RotationSnapshot(float yaw, float pitch) {
    public RotationSnapshot {
        if (!Float.isFinite(yaw)) {
            throw new IllegalArgumentException("yaw must be finite");
        }
        if (!Float.isFinite(pitch)) {
            throw new IllegalArgumentException("pitch must be finite");
        }
    }
}
