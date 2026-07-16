package dev.hylfrd.farmhelper.macro;

import dev.hylfrd.farmhelper.control.RotationTask;

public final class MacroAngles {
    private MacroAngles() {
    }

    public static float closestCardinal(float yaw) {
        return RotationTask.normalizeYaw(Math.round(yaw / 90.0F) * 90.0F);
    }

    public static float closestDiagonal(float yaw) {
        return RotationTask.normalizeYaw(Math.round((yaw - 45.0F) / 90.0F) * 90.0F + 45.0F);
    }

    public static float shortestDelta(float from, float to) {
        return RotationTask.normalizeYaw(to - from);
    }
}
