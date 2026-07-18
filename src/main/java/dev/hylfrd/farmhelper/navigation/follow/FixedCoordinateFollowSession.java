package dev.hylfrd.farmhelper.navigation.follow;

import dev.hylfrd.farmhelper.control.input.ControlOwner;
import dev.hylfrd.farmhelper.navigation.NavigationCancellationReason;
import dev.hylfrd.farmhelper.navigation.NavigationController;
import dev.hylfrd.farmhelper.navigation.NavigationGoal;
import dev.hylfrd.farmhelper.navigation.NavigationHandle;
import dev.hylfrd.farmhelper.navigation.NavigationOptions;
import dev.hylfrd.farmhelper.navigation.NavigationPhase;
import dev.hylfrd.farmhelper.navigation.NavigationRequest;
import dev.hylfrd.farmhelper.navigation.NavigationStartObservation;
import dev.hylfrd.farmhelper.navigation.NavigationStatus;

import java.util.Objects;
import java.util.Optional;

/**
 * Fixed-coordinate follow state mapped to upstream {@code findPath(Vec3, follow=true, ...)}.
 * The raw goal is never projected or accumulated; the shared handle owns every replacement.
 */
public final class FixedCoordinateFollowSession {
    public static final int RECALCULATION_START_TICKS = 12;

    private final NavigationGoal goal;
    private final NavigationOptions options;
    private NavigationHandle handle;
    private int startTicks;
    private FollowTerminationReason terminationReason;

    private FixedCoordinateFollowSession(
            NavigationGoal goal,
            NavigationOptions options,
            NavigationHandle handle
    ) {
        this.goal = Objects.requireNonNull(goal, "goal");
        this.options = Objects.requireNonNull(options, "options");
        this.handle = Objects.requireNonNull(handle, "handle");
        if (handle.status().flatMap(NavigationStatus::terminalResult).isPresent()) {
            terminationReason = FollowTerminationReason.NAVIGATION_REJECTED;
        }
    }

    public static FixedCoordinateFollowSession start(
            NavigationController controller,
            ControlOwner owner,
            long worldEpoch,
            NavigationGoal goal,
            NavigationOptions options,
            NavigationStartObservation observation
    ) {
        Objects.requireNonNull(controller, "controller");
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(goal, "goal");
        Objects.requireNonNull(options, "options");
        Objects.requireNonNull(observation, "observation");
        if (!options.follow()) {
            throw new IllegalArgumentException("coordinate follow requires options.follow=true");
        }
        NavigationRequest request =
                new NavigationRequest(owner, worldEpoch, goal, options);
        return new FixedCoordinateFollowSession(
                goal, options, controller.start(request, observation));
    }

    public NavigationHandle handle() {
        return handle;
    }

    public NavigationGoal goal() {
        return goal;
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
    public FollowUpdate onStartTick(NavigationStartObservation startObservation) {
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

        startTicks++;
        if (startTicks < RECALCULATION_START_TICKS) {
            return FollowUpdate.waiting(
                    handle.ticket(), RECALCULATION_START_TICKS - startTicks);
        }
        startTicks = 0;

        if (isRecalculationBusy(current.phase())) {
            return FollowUpdate.deferred(handle.ticket());
        }

        NavigationRequest replacementRequest = new NavigationRequest(
                current.request().owner(),
                handle.ticket().worldEpoch(),
                goal,
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
        return terminateWithoutCancellation(terminalFromHandle(handle.status()));
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
        Optional<NavigationCancellationReason> cancellation = status
                .flatMap(NavigationStatus::terminalResult)
                .flatMap(result -> result.cancellationReason());
        if (cancellation.isPresent()) {
            return cancellation.orElseThrow() == NavigationCancellationReason.REPLACED
                    ? FollowTerminationReason.STALE_NAVIGATION
                    : FollowTerminationReason.CANCELLED;
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
