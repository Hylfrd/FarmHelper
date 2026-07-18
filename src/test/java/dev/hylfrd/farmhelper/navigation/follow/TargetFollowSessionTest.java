package dev.hylfrd.farmhelper.navigation.follow;

import dev.hylfrd.farmhelper.control.input.ControlOwner;
import dev.hylfrd.farmhelper.navigation.NavigationCancellationReason;
import dev.hylfrd.farmhelper.navigation.NavigationController;
import dev.hylfrd.farmhelper.navigation.NavigationGoal;
import dev.hylfrd.farmhelper.navigation.NavigationHandle;
import dev.hylfrd.farmhelper.navigation.NavigationMode;
import dev.hylfrd.farmhelper.navigation.NavigationOptions;
import dev.hylfrd.farmhelper.navigation.NavigationPhase;
import dev.hylfrd.farmhelper.navigation.NavigationRotationPolicy;
import dev.hylfrd.farmhelper.navigation.NavigationStartObservation;
import dev.hylfrd.farmhelper.navigation.NavigationWorkTicket;
import dev.hylfrd.farmhelper.navigation.SegmentedSpatialSnapshot;
import dev.hylfrd.farmhelper.navigation.SpatialSegment;
import dev.hylfrd.farmhelper.runtime.snapshot.ConnectionSnapshot;
import dev.hylfrd.farmhelper.runtime.snapshot.MotionSnapshot;
import dev.hylfrd.farmhelper.runtime.snapshot.Observation;
import dev.hylfrd.farmhelper.runtime.snapshot.PlayerSnapshot;
import dev.hylfrd.farmhelper.runtime.snapshot.PositionSnapshot;
import dev.hylfrd.farmhelper.runtime.snapshot.WorldSnapshot;
import dev.hylfrd.farmhelper.runtime.spatial.BoxSnapshot;
import dev.hylfrd.farmhelper.runtime.spatial.SpatialSnapshot;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TargetFollowSessionTest {
    private static final long EPOCH = 7L;
    private static final ControlOwner OWNER = new ControlOwner("follow-test");
    private static final FollowTargetIdentity TARGET_ID =
            new FollowTargetIdentity("entity:stable-uuid");
    private static final PositionSnapshot FOLLOWER = new PositionSnapshot(0.0D, 64.0D, 0.0D);
    private static final NavigationOptions OPTIONS = new NavigationOptions(
            NavigationMode.FLY, true, false, 2.25D, 0.6D, false,
            NavigationRotationPolicy.FACE_GOAL);

    @Test
    void recalculatesOnEveryTwelfthStartTickAndResetsAcrossBusyReplacement() {
        NavigationController controller = new NavigationController();
        FollowTargetSnapshot initial = stationary(10.0D, 64.0D, 0.0D);
        TargetFollowSession session = start(controller, initial, OPTIONS);
        advanceToFollowing(session.handle(), 1L);
        NavigationHandle oldHandle = session.handle();
        FollowTargetSnapshot moved = stationary(12.0D, 65.0D, 2.0D);

        for (int tick = 1; tick < TargetFollowSession.RECALCULATION_START_TICKS; tick++) {
            FollowUpdate update = session.onStartTick(
                    Observation.present(moved), Observation.present(FOLLOWER), eligible(EPOCH));
            assertEquals(FollowUpdate.Action.WAITING, update.action());
            assertEquals(TargetFollowSession.RECALCULATION_START_TICKS - tick,
                    update.startTicksUntilRecalculation());
            assertEquals(oldHandle.ticket(), update.activeTicket().orElseThrow());
        }

        FollowUpdate replacement = session.onStartTick(
                Observation.present(moved), Observation.present(FOLLOWER), eligible(EPOCH));
        assertEquals(FollowUpdate.Action.RECALCULATED, replacement.action());
        assertEquals(oldHandle.ticket().generation() + 1L,
                replacement.activeTicket().orElseThrow().generation());
        assertEquals(NavigationCancellationReason.REPLACED,
                oldHandle.status().orElseThrow().terminalResult().orElseThrow()
                        .cancellationReason().orElseThrow());
        assertEquals(OPTIONS, replacement.replacementRequest().orElseThrow().options());
        NavigationGoal movedGoal = moved.navigationGoal(FOLLOWER);
        assertEquals(movedGoal, replacement.replacementRequest().orElseThrow().goal());
        assertEquals(movedGoal.withYOffset(OPTIONS.yOffset()),
                replacement.replacementRequest().orElseThrow().effectiveGoal());

        NavigationHandle busy = session.handle();
        for (int tick = 1; tick < TargetFollowSession.RECALCULATION_START_TICKS; tick++) {
            assertEquals(FollowUpdate.Action.WAITING,
                    session.onStartTick(
                            Observation.present(moved),
                            Observation.present(FOLLOWER),
                            eligible(EPOCH)).action());
        }
        FollowUpdate deferred = session.onStartTick(
                Observation.present(moved), Observation.present(FOLLOWER), eligible(EPOCH));
        assertEquals(FollowUpdate.Action.RECALCULATION_DEFERRED, deferred.action());
        assertEquals(busy.ticket(), deferred.activeTicket().orElseThrow());

        advanceToFollowing(busy, 2L);
        for (int tick = 1; tick < TargetFollowSession.RECALCULATION_START_TICKS; tick++) {
            assertEquals(FollowUpdate.Action.WAITING,
                    session.onStartTick(
                            Observation.present(moved),
                            Observation.present(FOLLOWER),
                            eligible(EPOCH)).action());
        }
        assertEquals(FollowUpdate.Action.RECALCULATED,
                session.onStartTick(
                        Observation.present(moved),
                        Observation.present(FOLLOWER),
                        eligible(EPOCH)).action());
    }

    @Test
    void movingTargetUsesOneVelocityStepAndRetainsEveryRequestOption() {
        NavigationController controller = new NavigationController();
        FollowTargetSnapshot moving = new FollowTargetSnapshot(
                TARGET_ID, EPOCH,
                new PositionSnapshot(10.0D, 64.0D, -3.0D),
                new MotionSnapshot(0.2D, 0.3D, -0.4D));
        TargetFollowSession session = start(controller, moving, OPTIONS);

        var request = session.handle().status().orElseThrow().request();
        assertEquals(new NavigationGoal(10.2D, 64.3D, -3.4D), request.goal());
        assertEquals(new NavigationGoal(10.2D, 66.55D, -3.4D), request.effectiveGoal());
        assertEquals(OPTIONS, request.options());
        assertFalse(request.options().smooth());
        assertFalse(request.options().sprint());
        assertEquals(0.6D, request.options().completionThreshold());
    }

    @Test
    void stationaryProjectionUsesFollowerSideAndKeepsZeroDistanceUpstreamDirection() {
        FollowTargetSnapshot target = stationary(10.0D, 64.0D, 0.0D);
        assertEquals(new NavigationGoal(8.8D, 64.5D, 0.0D),
                target.navigationGoal(FOLLOWER));
        assertEquals(new NavigationGoal(11.2D, 64.5D, 0.0D),
                target.navigationGoal(new PositionSnapshot(10.0D, 64.0D, 0.0D)));
    }

    @Test
    void targetLossUnknownAndIdentityChangeFailClosedWithExactLeafReason() {
        assertTargetTermination(
                Observation.absent(),
                FollowTerminationReason.TARGET_LOST,
                NavigationCancellationReason.FAILURE);
        assertTargetTermination(
                Observation.unknown(),
                FollowTerminationReason.TARGET_UNKNOWN,
                NavigationCancellationReason.FAILURE);
        assertTargetTermination(
                Observation.present(new FollowTargetSnapshot(
                        new FollowTargetIdentity("entity:replacement"), EPOCH,
                        new PositionSnapshot(10.0D, 64.0D, 0.0D),
                        new MotionSnapshot(0.0D, 0.0D, 0.0D))),
                FollowTerminationReason.TARGET_CHANGED,
                NavigationCancellationReason.FAILURE);
    }

    @Test
    void targetOrObservedWorldEpochChangeCancelsTheOwnedRun() {
        TargetFollowSession targetWorldSession =
                start(new NavigationController(), stationary(10.0D, 64.0D, 0.0D), OPTIONS);
        FollowTargetSnapshot otherWorld = new FollowTargetSnapshot(
                TARGET_ID, EPOCH + 1L,
                new PositionSnapshot(10.0D, 64.0D, 0.0D),
                new MotionSnapshot(0.0D, 0.0D, 0.0D));
        assertEquals(FollowTerminationReason.WORLD_CHANGED,
                targetWorldSession.onStartTick(
                        Observation.present(otherWorld),
                        Observation.present(FOLLOWER),
                        eligible(EPOCH)).terminationReason().orElseThrow());
        assertEquals(NavigationCancellationReason.WORLD_CHANGED,
                cancellation(targetWorldSession.handle()));

        TargetFollowSession observedWorldSession =
                start(new NavigationController(), stationary(10.0D, 64.0D, 0.0D), OPTIONS);
        assertEquals(FollowTerminationReason.WORLD_CHANGED,
                observedWorldSession.onStartTick(
                        Observation.present(stationary(10.0D, 64.0D, 0.0D)),
                        Observation.present(FOLLOWER),
                        eligible(EPOCH + 1L)).terminationReason().orElseThrow());
        assertEquals(NavigationCancellationReason.WORLD_CHANGED,
                cancellation(observedWorldSession.handle()));
    }

    @Test
    void unknownFollowerPositionTerminatesOnlyAtARecalculationBoundary() {
        TargetFollowSession session =
                start(new NavigationController(), stationary(10.0D, 64.0D, 0.0D), OPTIONS);
        advanceToFollowing(session.handle(), 3L);
        for (int tick = 1; tick < TargetFollowSession.RECALCULATION_START_TICKS; tick++) {
            assertEquals(FollowUpdate.Action.WAITING,
                    session.onStartTick(
                            Observation.present(stationary(10.0D, 64.0D, 0.0D)),
                            Observation.unknown(),
                            eligible(EPOCH)).action());
        }

        FollowUpdate terminal = session.onStartTick(
                Observation.present(stationary(10.0D, 64.0D, 0.0D)),
                Observation.unknown(),
                eligible(EPOCH));
        assertEquals(FollowTerminationReason.FOLLOWER_POSITION_UNAVAILABLE,
                terminal.terminationReason().orElseThrow());
        assertEquals(NavigationCancellationReason.FAILURE, cancellation(session.handle()));
    }

    @Test
    void explicitAndExternalCancellationHaveDeterministicOutcomes() {
        TargetFollowSession explicit =
                start(new NavigationController(), stationary(10.0D, 64.0D, 0.0D), OPTIONS);
        assertEquals(FollowTerminationReason.CANCELLED,
                explicit.cancel(NavigationCancellationReason.OWNER_REQUESTED)
                        .terminationReason().orElseThrow());
        assertEquals(NavigationCancellationReason.OWNER_REQUESTED, cancellation(explicit.handle()));
        assertEquals(FollowTerminationReason.CANCELLED,
                explicit.cancel(NavigationCancellationReason.FAILURE)
                        .terminationReason().orElseThrow());

        TargetFollowSession external =
                start(new NavigationController(), stationary(10.0D, 64.0D, 0.0D), OPTIONS);
        assertTrue(external.handle().cancel(NavigationCancellationReason.CLIENT_EXIT));
        FollowUpdate update = external.onStartTick(
                Observation.present(stationary(10.0D, 64.0D, 0.0D)),
                Observation.present(FOLLOWER),
                eligible(EPOCH));
        assertEquals(FollowTerminationReason.CANCELLED,
                update.terminationReason().orElseThrow());
        assertTrue(external.terminationReason().isPresent());
    }

    @Test
    void externalReplacementIsStaleAndNeverTouchesTheReplacement() {
        NavigationController controller = new NavigationController();
        TargetFollowSession session =
                start(controller, stationary(10.0D, 64.0D, 0.0D), OPTIONS);
        NavigationHandle oldHandle = session.handle();
        var externalRequest = new dev.hylfrd.farmhelper.navigation.NavigationRequest(
                OWNER, EPOCH, new NavigationGoal(99.0D, 70.0D, -4.0D), OPTIONS);
        NavigationHandle replacement =
                oldHandle.replace(externalRequest, eligible(EPOCH)).orElseThrow();

        FollowUpdate update = session.onStartTick(
                Observation.present(stationary(10.0D, 64.0D, 0.0D)),
                Observation.present(FOLLOWER),
                eligible(EPOCH));

        assertEquals(FollowTerminationReason.STALE_NAVIGATION,
                update.terminationReason().orElseThrow());
        assertEquals(NavigationCancellationReason.REPLACED, cancellation(oldHandle));
        assertEquals(replacement.ticket(), controller.activeTicket().orElseThrow());
        assertTrue(replacement.status().orElseThrow().active());
        assertEquals(FollowTerminationReason.STALE_NAVIGATION,
                session.cancel(NavigationCancellationReason.FAILURE)
                        .terminationReason().orElseThrow());
        assertEquals(replacement.ticket(), controller.activeTicket().orElseThrow());
        assertTrue(replacement.status().orElseThrow().active());

        NavigationController directCancelController = new NavigationController();
        TargetFollowSession directCancelSession = start(
                directCancelController, stationary(10.0D, 64.0D, 0.0D), OPTIONS);
        NavigationHandle directCancelReplacement = directCancelSession.handle()
                .replace(externalRequest, eligible(EPOCH)).orElseThrow();
        assertEquals(FollowTerminationReason.STALE_NAVIGATION,
                directCancelSession.cancel(NavigationCancellationReason.FAILURE)
                        .terminationReason().orElseThrow());
        assertEquals(directCancelReplacement.ticket(),
                directCancelController.activeTicket().orElseThrow());
        assertTrue(directCancelReplacement.status().orElseThrow().active());
    }

    @Test
    void rejectsNonFollowOptionsAndInitialWorldMismatchThroughSharedController() {
        NavigationOptions notFollowing = new NavigationOptions(
                NavigationMode.FLY, false, true, 0.0D, 1.0D, true,
                NavigationRotationPolicy.FACE_PATH);
        assertThrows(IllegalArgumentException.class, () -> start(
                new NavigationController(), stationary(10.0D, 64.0D, 0.0D), notFollowing));

        TargetFollowSession rejected = TargetFollowSession.start(
                new NavigationController(), OWNER, OPTIONS,
                stationary(10.0D, 64.0D, 0.0D), FOLLOWER, eligible(EPOCH + 1L));
        assertEquals(FollowTerminationReason.NAVIGATION_REJECTED,
                rejected.terminationReason().orElseThrow());
        assertTrue(rejected.handle().status().orElseThrow().terminalResult().isPresent());
    }

    private static void assertTargetTermination(
            Observation<FollowTargetSnapshot> target,
            FollowTerminationReason expectedFollow,
            NavigationCancellationReason expectedNavigation
    ) {
        TargetFollowSession session =
                start(new NavigationController(), stationary(10.0D, 64.0D, 0.0D), OPTIONS);
        FollowUpdate update =
                session.onStartTick(target, Observation.present(FOLLOWER), eligible(EPOCH));
        assertEquals(expectedFollow, update.terminationReason().orElseThrow());
        assertEquals(expectedNavigation, cancellation(session.handle()));
    }

    private static NavigationCancellationReason cancellation(NavigationHandle handle) {
        return handle.status().orElseThrow().terminalResult().orElseThrow()
                .cancellationReason().orElseThrow();
    }

    private static TargetFollowSession start(
            NavigationController controller,
            FollowTargetSnapshot target,
            NavigationOptions options
    ) {
        return TargetFollowSession.start(
                controller, OWNER, options, target, FOLLOWER, eligible(EPOCH));
    }

    private static FollowTargetSnapshot stationary(double x, double y, double z) {
        return new FollowTargetSnapshot(
                TARGET_ID, EPOCH, new PositionSnapshot(x, y, z),
                new MotionSnapshot(0.0D, 0.0D, 0.0D));
    }

    private static NavigationStartObservation eligible(long epoch) {
        return new NavigationStartObservation(
                Observation.present(new WorldSnapshot(epoch, Observation.unknown())),
                Observation.present(new PlayerSnapshot(
                        Observation.present(FOLLOWER),
                        Observation.unknown(),
                        Observation.unknown(),
                        Observation.unknown(),
                        Observation.unknown())),
                Observation.present(ConnectionSnapshot.multiplayer()),
                Observation.absent());
    }

    private static void advanceToFollowing(NavigationHandle handle, long token) {
        NavigationWorkTicket requested =
                handle.status().orElseThrow().workTicket();
        assertTrue(handle.advance(requested, NavigationPhase.CAPTURING));
        NavigationWorkTicket capturing =
                handle.status().orElseThrow().workTicket();
        assertTrue(handle.acceptCapture(capturing, capture(capturing, token)));
        NavigationWorkTicket searching =
                handle.status().orElseThrow().workTicket();
        assertTrue(handle.advance(searching, NavigationPhase.FOLLOWING));
    }

    private static SegmentedSpatialSnapshot capture(
            NavigationWorkTicket workTicket,
            long token
    ) {
        BoxSnapshot bounds =
                new BoxSnapshot(0.0D, 0.0D, 0.0D, 1.0D, 2.0D, 1.0D);
        SpatialSnapshot snapshot = new SpatialSnapshot(
                workTicket.worldEpoch(), token, bounds, 0, 2,
                new BoxSnapshot(0.2D, 0.0D, 0.2D, 0.8D, 1.8D, 0.8D),
                Map.of());
        return new SegmentedSpatialSnapshot(
                workTicket, bounds,
                List.of(new SpatialSegment(0, workTicket, token, snapshot)));
    }
}
