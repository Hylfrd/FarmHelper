package dev.hylfrd.farmhelper.macro.mechanism;

import dev.hylfrd.farmhelper.control.RotationTask;
import dev.hylfrd.farmhelper.control.rotation.RotationProfile;
import dev.hylfrd.farmhelper.control.rotation.RotationTerminalReason;
import dev.hylfrd.farmhelper.macro.FarmingContext;
import dev.hylfrd.farmhelper.macro.MacroAngles;
import dev.hylfrd.farmhelper.macro.MacroRotationLeaseState;
import dev.hylfrd.farmhelper.macro.MacroRotationRequest;
import dev.hylfrd.farmhelper.macro.MacroTiming;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

/** One exact macro-owned rotation request and its explicit client acknowledgement. */
public final class OwnedRotationLedger {
    private static final AtomicLong NEXT_TOKEN = new AtomicLong(1L);
    private MacroRotationRequest pending;

    public MacroRotationRequest begin(
            float currentYaw,
            float currentPitch,
            float targetYaw,
            float targetPitch,
            RotationProfile profile,
            long sampledMillis,
            RotationEntropy entropy
    ) {
        Objects.requireNonNull(entropy, "entropy");
        float modifier = entropy.backModifier();
        long floor = entropy.minimumFloorMillis();
        long duration = MacroTiming.scaledRotationMillis(
                sampledMillis, floor,
                MacroAngles.shortestDelta(currentYaw, targetYaw),
                targetPitch - currentPitch);
        return install(targetYaw, targetPitch, duration, profile, modifier);
    }

    public MacroRotationRequest beginLegacy(
            float targetYaw,
            float targetPitch,
            long durationMillis
    ) {
        return install(targetYaw, targetPitch, durationMillis,
                RotationProfile.LEGACY_QUART, 0.0F);
    }

    public Optional<MacroRotationRequest> pending() {
        return Optional.ofNullable(pending);
    }

    public Gate observe(FarmingContext context) {
        Objects.requireNonNull(context, "context");
        if (pending == null) {
            return Gate.NONE;
        }
        if (!context.rotationLease().isPresent()) {
            return Gate.UNACKNOWLEDGED;
        }
        MacroRotationLeaseState lease = context.rotationLease().get();
        if (lease.requestToken() != pending.requestToken()) {
            return Gate.STALE_ACKNOWLEDGEMENT;
        }
        if (lease.status() == MacroRotationLeaseState.Status.ACTIVE) {
            return Gate.ACTIVE;
        }
        if (lease.status() == MacroRotationLeaseState.Status.TERMINAL
                && lease.terminalReason().orElseThrow() == RotationTerminalReason.COMPLETED) {
            pending = null;
            return Gate.COMPLETED;
        }
        if (lease.status() == MacroRotationLeaseState.Status.TERMINAL) {
            if (lease.terminalReason().orElseThrow() == RotationTerminalReason.OWNER_CANCELLED) {
                return Gate.RETRYABLE_CANCELLATION;
            }
            pending = null;
            return Gate.CANCELLED;
        }
        return Gate.UNACKNOWLEDGED;
    }

    public void clear() {
        pending = null;
    }

    private MacroRotationRequest install(
            float targetYaw,
            float targetPitch,
            long durationMillis,
            RotationProfile profile,
            float modifier
    ) {
        if (pending != null) {
            throw new IllegalStateException("owned rotation is already pending");
        }
        long token = nextPositiveToken();
        pending = new MacroRotationRequest(
                RotationTask.normalizeYaw(targetYaw), targetPitch, durationMillis,
                Objects.requireNonNull(profile, "profile"), modifier, token);
        return pending;
    }

    public enum Gate {
        NONE,
        UNACKNOWLEDGED,
        ACTIVE,
        COMPLETED,
        RETRYABLE_CANCELLATION,
        CANCELLED,
        STALE_ACKNOWLEDGEMENT
    }

    private static long nextPositiveToken() {
        while (true) {
            long current = NEXT_TOKEN.get();
            long next = current == Long.MAX_VALUE ? 1L : current + 1L;
            if (NEXT_TOKEN.compareAndSet(current, next)) {
                return current;
            }
        }
    }
}
