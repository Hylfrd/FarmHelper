package dev.hylfrd.farmhelper.macro.mechanism;

import dev.hylfrd.farmhelper.macro.MacroSpawnPose;
import dev.hylfrd.farmhelper.runtime.spatial.RewarpPosition;

import java.util.Objects;
import java.util.Optional;

/** Shared rewarp origin, dwell, retry, landing, and airborne-sneak identity. */
public final class RewarpLedger {
    private RewarpPosition origin;
    private MacroSpawnPose confirmedSpawn;
    private long dwellNanos;
    private long stateAt;
    private long lastRequestAt;
    private long attempts;
    private long sneakNanos;
    private boolean sneakSampled;

    public void begin(RewarpPosition origin, long nowNanos, long dwellNanos) {
        this.origin = Objects.requireNonNull(origin, "origin");
        requireNonNegative(nowNanos, "nowNanos");
        requireNonNegative(dwellNanos, "dwellNanos");
        this.stateAt = nowNanos;
        this.dwellNanos = dwellNanos;
        lastRequestAt = 0L;
        attempts = 0L;
        confirmedSpawn = null;
        sneakNanos = 0L;
        sneakSampled = false;
    }

    public Optional<RewarpPosition> origin() {
        return Optional.ofNullable(origin);
    }

    public boolean dwellComplete(long nowNanos) {
        return elapsed(nowNanos, stateAt) >= dwellNanos;
    }

    public void requested(long nowNanos) {
        requireNonNegative(nowNanos, "nowNanos");
        lastRequestAt = nowNanos;
        attempts = attempts == Long.MAX_VALUE ? Long.MAX_VALUE : attempts + 1L;
    }

    public boolean retryDue(long nowNanos, long retryNanos) {
        requireNonNegative(retryNanos, "retryNanos");
        return elapsed(nowNanos, lastRequestAt) >= retryNanos;
    }

    public long attempts() {
        return attempts;
    }

    public void confirmed(MacroSpawnPose spawn, long nowNanos) {
        confirmedSpawn = Objects.requireNonNull(spawn, "spawn");
        requireNonNegative(nowNanos, "nowNanos");
        stateAt = nowNanos;
        sneakNanos = 0L;
        sneakSampled = false;
    }

    public Optional<MacroSpawnPose> confirmedSpawn() {
        return Optional.ofNullable(confirmedSpawn);
    }

    public boolean sneakSampled() {
        return sneakSampled;
    }

    public void beginSneak(long nowNanos, long durationNanos) {
        requireNonNegative(nowNanos, "nowNanos");
        requireNonNegative(durationNanos, "durationNanos");
        stateAt = nowNanos;
        sneakNanos = durationNanos;
        sneakSampled = true;
    }

    public boolean sneakComplete(long nowNanos) {
        return elapsed(nowNanos, stateAt) >= sneakNanos;
    }

    public void shiftState(long suspendedNanos) {
        requireNonNegative(suspendedNanos, "suspendedNanos");
        stateAt = saturatingAdd(stateAt, suspendedNanos);
    }

    public void shiftRequest(long suspendedNanos) {
        requireNonNegative(suspendedNanos, "suspendedNanos");
        lastRequestAt = saturatingAdd(lastRequestAt, suspendedNanos);
    }

    public void clear() {
        origin = null;
        confirmedSpawn = null;
        dwellNanos = 0L;
        stateAt = 0L;
        lastRequestAt = 0L;
        attempts = 0L;
        sneakNanos = 0L;
        sneakSampled = false;
    }

    private static long elapsed(long now, long then) {
        requireNonNegative(now, "now");
        try {
            return Math.max(0L, Math.subtractExact(now, then));
        } catch (ArithmeticException exception) {
            return Long.MAX_VALUE;
        }
    }

    private static long saturatingAdd(long value, long delta) {
        try {
            return Math.addExact(value, delta);
        } catch (ArithmeticException exception) {
            return Long.MAX_VALUE;
        }
    }

    private static void requireNonNegative(long value, String name) {
        if (value < 0L) {
            throw new IllegalArgumentException(name + " must not be negative");
        }
    }
}
