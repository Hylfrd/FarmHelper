package dev.hylfrd.farmhelper.config;

/** Persisted macro pose and Garden plot identity; plot -1 means conservatively unknown. */
public record MacroLocationConfig(int x, int y, int z, float yaw, float pitch, int plot) {
    public MacroLocationConfig {
        if (!Float.isFinite(yaw) || !Float.isFinite(pitch)) {
            throw new IllegalArgumentException("macro pose angles must be finite");
        }
        if (pitch < -90.0F || pitch > 90.0F) {
            throw new IllegalArgumentException("macro pose pitch must be in [-90, 90]");
        }
        if (plot < -1 || plot > 24) {
            throw new IllegalArgumentException("macro plot must be -1 or in [0, 24]");
        }
    }

    public double squaredDistance(MacroLocationConfig other) {
        double dx = (double) other.x - x;
        double dy = (double) other.y - y;
        double dz = (double) other.z - z;
        return dx * dx + dy * dy + dz * dz;
    }
}
