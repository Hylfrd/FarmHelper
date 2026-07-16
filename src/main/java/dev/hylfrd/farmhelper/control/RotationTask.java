package dev.hylfrd.farmhelper.control;

import dev.hylfrd.farmhelper.control.rotation.RotationFrame;
import dev.hylfrd.farmhelper.control.rotation.RotationProfile;

import java.util.Objects;
import java.util.concurrent.TimeUnit;

/** Immutable, version-independent trajectory for one explicit rotation. */
public final class RotationTask {
    private final float startYaw;
    private final float startPitch;
    private final float targetYaw;
    private final float targetPitch;
    private final double yawDelta;
    private final long durationMs;
    private final long durationNanos;
    private final RotationProfile profile;
    private final float backModifier;

    public RotationTask(float startYaw, float startPitch, float targetYaw, float targetPitch, long durationMs) {
        this(startYaw, startPitch, targetYaw, targetPitch, durationMs,
                RotationProfile.LEGACY_QUART, 0.0F);
    }

    public RotationTask(
            float startYaw,
            float startPitch,
            float targetYaw,
            float targetPitch,
            long durationMs,
            RotationProfile profile,
            float backModifier
    ) {
        requireFinite(startYaw, "startYaw");
        requireFinite(startPitch, "startPitch");
        requireFinite(targetYaw, "targetYaw");
        requireFinite(targetPitch, "targetPitch");
        if (durationMs < 0L) {
            throw new IllegalArgumentException("durationMs must not be negative");
        }

        this.startYaw = normalizeYaw(startYaw);
        this.startPitch = clampPitch(startPitch);
        this.targetYaw = normalizeYaw(targetYaw);
        this.targetPitch = clampPitch(targetPitch);
        yawDelta = shortestYawDelta(this.startYaw, this.targetYaw);
        this.durationMs = durationMs;
        durationNanos = TimeUnit.MILLISECONDS.toNanos(durationMs);
        this.profile = Objects.requireNonNull(profile, "profile");
        if (!Float.isFinite(backModifier) || backModifier < -0.25F || backModifier >= 0.25F) {
            throw new IllegalArgumentException("back modifier must be in [-0.25, 0.25)");
        }
        this.backModifier = backModifier;
    }

    public float startYaw() {
        return startYaw;
    }

    public float startPitch() {
        return startPitch;
    }

    public float targetYaw() {
        return targetYaw;
    }

    public float targetPitch() {
        return targetPitch;
    }

    public long durationMs() {
        return durationMs;
    }

    public long durationNanos() {
        return durationNanos;
    }

    public RotationProfile profile() {
        return profile;
    }

    public float backModifier() {
        return backModifier;
    }

    /** Samples this trajectory by elapsed monotonic time, never by wall-clock time. */
    public RotationFrame sample(long elapsedNanos) {
        long safeElapsed = Math.max(0L, elapsedNanos);
        boolean complete = durationNanos == 0L || safeElapsed >= durationNanos;
        float progress = complete
                ? 1.0F
                : clamp((double) safeElapsed / durationNanos, 0.0F, Math.nextDown(1.0F));
        float easedYaw = complete ? 1.0F : profile.yaw(progress, backModifier);
        float easedPitch = complete ? 1.0F : profile.pitch(progress, backModifier);
        float pitchDelta = targetPitch - startPitch;
        float yaw = narrowYaw((double) startYaw + yawDelta * easedYaw);
        float pitch = clampPitch(startPitch + pitchDelta * easedPitch);
        if (complete) {
            yaw = targetYaw;
            pitch = targetPitch;
        } else if (profile != RotationProfile.BACK) {
            if (yawDelta != 0.0D && sameBits(yaw, targetYaw)) {
                double direction = yawDelta > 0.0D
                        ? Float.NEGATIVE_INFINITY
                        : Float.POSITIVE_INFINITY;
                yaw = normalizeYaw(Math.nextAfter(targetYaw, direction));
            }
            if (pitchDelta != 0.0F && sameBits(pitch, targetPitch)) {
                pitch = pitchDelta > 0.0F
                        ? Math.nextDown(targetPitch)
                        : Math.nextUp(targetPitch);
            }
        }
        return new RotationFrame(yaw, pitch, progress);
    }

    /** Normalizes yaw to {@code [-180, 180)}; exact 180-degree ties rotate negatively. */
    public static float normalizeYaw(float value) {
        requireFinite(value, "yaw");
        return narrowYaw(value);
    }

    private static float narrowYaw(double value) {
        float narrowed = (float) normalizeYaw(value);
        if (narrowed >= 180.0F) {
            narrowed -= 360.0F;
        }
        return narrowed == 0.0F ? 0.0F : narrowed;
    }

    private static double normalizeYaw(double value) {
        double wrapped = value % 360.0D;
        if (wrapped >= 180.0D) {
            wrapped -= 360.0D;
        }
        if (wrapped < -180.0D) {
            wrapped += 360.0D;
        }
        return wrapped == 0.0D ? 0.0D : wrapped;
    }

    private static double shortestYawDelta(float current, float target) {
        return normalizeYaw((double) target - current);
    }

    private static float clampPitch(float value) {
        return clamp(value, -90.0F, 90.0F);
    }

    private static float clamp(double value, float min, float max) {
        return (float) Math.max(min, Math.min(max, value));
    }

    private static boolean sameBits(float first, float second) {
        return Float.floatToRawIntBits(first) == Float.floatToRawIntBits(second);
    }

    private static void requireFinite(float value, String name) {
        if (!Float.isFinite(value)) {
            throw new IllegalArgumentException(name + " must be finite");
        }
    }
}
