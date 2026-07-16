package dev.hylfrd.farmhelper.macro;

/** Shared upstream duration scaling with an independently sampled minimum floor. */
public final class MacroTiming {
    private MacroTiming() {
    }

    public static long scaledRotationMillis(
            long sampledMillis,
            long sampledFloorMillis,
            float yawDelta,
            float pitchDelta
    ) {
        if (sampledMillis < 0L || sampledFloorMillis < 0L) {
            throw new IllegalArgumentException("rotation durations must not be negative");
        }
        if (!Float.isFinite(yawDelta) || !Float.isFinite(pitchDelta)) {
            throw new IllegalArgumentException("rotation deltas must be finite");
        }
        double yaw = Math.max(Math.abs((double) yawDelta), 1.0D);
        double pitch = Math.max(Math.abs((double) pitchDelta), 1.0D);
        double distance = Math.sqrt(yaw * yaw + pitch * pitch);
        double scale = distance < 25.0D ? 0.65D
                : distance < 45.0D ? 0.77D
                : distance < 80.0D ? 0.9D
                : distance > 100.0D ? 1.1D : 1.0D;
        long scaled = (long) Math.floor((double) sampledMillis * scale);
        return Math.max(scaled, sampledFloorMillis);
    }
}
