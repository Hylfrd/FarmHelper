package dev.hylfrd.farmhelper.navigation.follow;

import dev.hylfrd.farmhelper.control.input.ControlOwner;
import dev.hylfrd.farmhelper.navigation.NavigationCancellationReason;
import dev.hylfrd.farmhelper.navigation.NavigationController;
import dev.hylfrd.farmhelper.navigation.NavigationHandle;
import dev.hylfrd.farmhelper.navigation.NavigationOptions;
import dev.hylfrd.farmhelper.navigation.NavigationPhase;
import dev.hylfrd.farmhelper.navigation.NavigationRequest;
import dev.hylfrd.farmhelper.navigation.NavigationStartObservation;
import dev.hylfrd.farmhelper.navigation.NavigationStatus;
import dev.hylfrd.farmhelper.runtime.snapshot.Observation;
import dev.hylfrd.farmhelper.runtime.snapshot.PositionSnapshot;

import java.util.Objects;
import java.util.Optional;

/**
 * Threadless target-follow state. The shared navigation handle remains the sole request,
 * generation, replacement, cancellation, and lifecycle authority.
 */
public final class TargetFollowSession {
    public static final int RECALCULATION_START_TICKS = 12;

    private final FollowTargetIdentity targetIdentity;
    private final NavigationOptions options;
    private NavigationHandle handle;
    private int startTicks;
    private FollowTerminationReason terminationReason;

    private TargetFollowSession(
            FollowTargetIdentity targetIdentity,
            NavigationOptions options,
            NavigationHandle handle
    ) {
        this.targetIdentity = Objects.requireNonNull(targetIdentity, "targetIdentity");
        this.options = Objects.requireNonNull(options, "options");
        this.handle = Objects.requireNonNull(handle, "handle");
        if (handle.status().flatMap(NavigationStatus::terminalResult).isPresent()) {
            terminationReason = FollowTerminationReason.NAVIGATION_REJECTED;
        }
    }

    public static TargetFollowSession start(
            NavigationController controller,
            ControlOwner owner,
            NavigationOptions options,
            FollowTargetSnapshot target,
            PositionSnapshot followerPosition,
            NavigationStartObservation observation
    ) {
        Objects.requireNonNull(controller, "controller");
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(options, "options");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(followerPosition, "followerPosition");
        Objects.requireNonNull(observation, "observation");
        if (!options.follow()) {
            throw new IllegalArgumentException("target follow requires options.follow=true");
        }
        NavigationRequest request = new NavigationRequest(
                owner, target.worldEpoch(), target.navigationGoal(followerPosition), options);
        return new TargetFollowSession(
                target.identity(), options, controller.start(request, observation));
    }

    public NavigationHandle handle() {
        return handle;
    }

    public FollowTargetIdentity targetIdentity() {
        return targetIdentity;
    }

    public NavigationOptions options() {
        return options;
    }

    public Optional<FollowTerminationReason> terminationReason() {
        return Optional.ofNullable(terminationReason);
    }

    /**
     * Advances exactly one START-phase client tick. END ticks must never call this method.
     */
    public FollowUpdate onStartTick(
            Observation<FollowTargetSnapshot> targetObservation,
            Observation<PositionSnapshot> followerPosition,
            NavigationStartObservation startObservation
    ) {
        Objects.requireNonNull(targetObservation, "targetObservation");
        Objects.requireNonNull(followerPosition, "followerPosition");
        Objects.requireNonNull(startObservation, "startObservation");
        if (terminationReason != null) {
            return FollowUpdate.terminated(terminationReason);
        }

        Optional<NavigationStatus> status = handle.status();
        if (status.isEmpty() || status.orElseThrow().terminalResult().isPresent()) {
            return terminateWithoutCancellation(terminalFromHandle(status));
        }
        NavigationStatus current = status.orElseThrow();

        if (!startObservation.world().isPresent()
                || startObservation.world().get().epoch() != handle.ticket().worldEpoch()) {
            return terminateWithCancellation(
                    FollowTerminationReason.WORLD_CHANGED,
                    NavigationCancellationReason.WORLD_CHANGED);
        }
        if (targetObservation.isUnknown()) {
            return terminateWithCancellation(
                    FollowTerminationReason.TARGET_UNKNOWN,
                    NavigationCancellationReason.FAILURE);
        }
        if (targetObservation.isAbsent()) {
            return terminateWithCancellation(
                    FollowTerminationReason.TARGET_LOST,
                    NavigationCancellationReason.FAILURE);
        }

        FollowTargetSnapshot target = targetObservation.get();
        if (target.worldEpoch() != handle.ticket().worldEpoch()) {
            return terminateWithCancellation(
                    FollowTerminationReason.WORLD_CHANGED,
                    NavigationCancellationReason.WORLD_CHANGED);
        }
        if (!target.identity().equals(targetIdentity)) {
            return terminateWithCancellation(
                    FollowTerminationReason.TARGET_CHANGED,
                    NavigationCancellationReason.FAILURE);
        }

        startTicks++;
        if (startTicks < RECALCULATION_START_TICKS) {
            return FollowUpdate.waiting(
                    handle.ticket(), RECALCULATION_START_TICKS - startTicks);
        }
        startTicks = 0;

        if (isRecalculationBusy(current.phase())) {
            return FollowUpdate.deferred(handle.ticket());
        }
        if (!followerPosition.isPresent()) {
            return terminateWithCancellation(
                    FollowTerminationReason.FOLLOWER_POSITION_UNAVAILABLE,
                    NavigationCancellationReason.FAILURE);
        }

        NavigationRequest replacementRequest = new NavigationRequest(
                current.request().owner(),
                target.worldEpoch(),
                target.navigationGoal(followerPosition.get()),
                options);
        Optional<NavigationHandle> replacement =
                handle.replace(replacementRequest, startObservation);
        if (replacement.isEmpty()) {
            return terminateWithoutCancellation(FollowTerminationReason.STALE_NAVIGATION);
        }
        handle = replacement.orElseThrow();
        if (handle.status().flatMap(NavigationStatus::terminalResult).isPresent()) {
            return terminateWithoutCancellation(FollowTerminationReason.NAVIGATION_REJECTED);
        }
        return FollowUpdate.recalculated(handle.ticket(), replacementRequest);
    }

    public FollowUpdate cancel(NavigationCancellationReason reason) {
        Objects.requireNonNull(reason, "reason");
        if (terminationReason != null) {
            return FollowUpdate.terminated(terminationReason);
        }
        if (handle.cancel(reason)) {
            return terminateWithoutCancellation(FollowTerminationReason.CANCELLED);
        }
        return terminateWithoutCancellation(FollowTerminationReason.NAVIGATION_TERMINATED);
    }

    private FollowUpdate terminateWithCancellation(
            FollowTerminationReason followReason,
            NavigationCancellationReason cancellationReason
    ) {
        handle.cancel(cancellationReason);
        return terminateWithoutCancellation(followReason);
    }

    private FollowUpdate terminateWithoutCancellation(FollowTerminationReason reason) {
        terminationReason = Objects.requireNonNull(reason, "reason");
        return FollowUpdate.terminated(reason);
    }

    private static FollowTerminationReason terminalFromHandle(
            Optional<NavigationStatus> status
    ) {
        if (status.flatMap(NavigationStatus::terminalResult)
                .flatMap(result -> result.cancellationReason()).isPresent()) {
            return FollowTerminationReason.CANCELLED;
        }
        return FollowTerminationReason.NAVIGATION_TERMINATED;
    }

    private static boolean isRecalculationBusy(NavigationPhase phase) {
        return switch (phase) {
            case REQUESTED, CAPTURING, SEARCHING -> true;
            case FOLLOWING, EXECUTING -> false;
        };
    }
}
