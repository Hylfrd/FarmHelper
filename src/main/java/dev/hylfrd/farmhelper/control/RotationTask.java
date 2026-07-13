package dev.hylfrd.farmhelper.control;

/** Version-independent state for the existing one-shot rotation behavior. */
public final class RotationTask {
    private final float startYaw;
    private final float startPitch;
    private final float targetYaw;
    private final float targetPitch;
    private long startTime;
    private final long durationMs;
    private long pausedAt;

    public RotationTask(float startYaw, float startPitch, float targetYaw, float targetPitch, long durationMs) {
        this.startYaw = startYaw;
        this.startPitch = startPitch;
        this.targetYaw = normalizeYaw(targetYaw);
        this.targetPitch = clamp(targetPitch, -90.0F, 90.0F);
        this.startTime = System.currentTimeMillis();
        this.durationMs = Math.max(50L, durationMs);
    }

    public boolean finished(long now) {
        return now - startTime >= durationMs;
    }

    public boolean paused() {
        return pausedAt > 0L;
    }

    public void pause(long now) {
        if (!paused()) {
            pausedAt = now;
        }
    }

    public void resume(long now) {
        if (!paused()) {
            return;
        }
        startTime += now - pausedAt;
        pausedAt = 0L;
    }

    public float yaw(long now) {
        float progress = progress(now);
        return normalizeYaw(startYaw + shortestYawDelta(startYaw, targetYaw) * easeOutQuart(progress));
    }

    public float pitch(long now) {
        float progress = progress(now);
        return startPitch + (targetPitch - startPitch) * easeOutQuart(progress);
    }

    public float targetYaw() {
        return targetYaw;
    }

    public float targetPitch() {
        return targetPitch;
    }

    private float progress(long now) {
        return clamp((float) (now - startTime) / durationMs, 0.0F, 1.0F);
    }

    private static float easeOutQuart(float value) {
        return (float) (1.0D - Math.pow(1.0D - value, 4.0D));
    }

    private static float shortestYawDelta(float current, float target) {
        return normalizeYaw(target - current);
    }

    private static float normalizeYaw(float value) {
        float wrapped = value % 360.0F;
        if (wrapped >= 180.0F) {
            wrapped -= 360.0F;
        }
        if (wrapped < -180.0F) {
            wrapped += 360.0F;
        }
        return wrapped;
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }
}
