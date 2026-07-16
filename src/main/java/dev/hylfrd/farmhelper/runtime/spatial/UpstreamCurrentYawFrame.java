package dev.hylfrd.farmhelper.runtime.spatial;

/**
 * Current-yaw block transform preserved from fixed upstream {@code BlockUtils#getUnitX/getUnitZ}
 * at commit {@code eacb323fbde3eff94d4f2ee7baacb059d84b8e3a}. The X and Z unit components
 * intentionally use different 30/60-degree seams.
 */
public final class UpstreamCurrentYawFrame {
    private UpstreamCurrentYawFrame() {
    }

    public static RelativeFrame from(double yaw) {
        double normalized = RelativeFrame.normalizeYaw(yaw);
        int forwardX;
        if (normalized < 30.0D) {
            forwardX = 0;
        } else if (normalized < 150.0D) {
            forwardX = -1;
        } else if (normalized < 210.0D) {
            forwardX = 0;
        } else if (normalized < 330.0D) {
            forwardX = 1;
        } else {
            forwardX = 0;
        }

        int forwardZ;
        if (normalized < 60.0D) {
            forwardZ = 1;
        } else if (normalized < 120.0D) {
            forwardZ = 0;
        } else if (normalized < 240.0D) {
            forwardZ = -1;
        } else if (normalized < 300.0D) {
            forwardZ = 0;
        } else {
            forwardZ = 1;
        }
        return new RelativeFrame(-forwardZ, forwardX, forwardX, forwardZ);
    }
}
