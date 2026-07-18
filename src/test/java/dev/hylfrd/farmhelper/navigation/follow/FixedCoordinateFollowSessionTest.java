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
import dev.hylfrd.farmhelper.runtime.snapshot.Observation;
import dev.hylfrd.farmhelper.runtime.snapshot.PlayerSnapshot;
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

class FixedCoordinateFollowSessionTest {
    private static final long EPOCH = 13L;
    private static final ControlOwner OWNER = new ControlOwner("fixed-follow-test");
    private static final NavigationGoal GOAL =
            new NavigationGoal(123.25D, 70.5D, -45.75D);
    private static final NavigationOptions OPTIONS = new NavigationOptions(
            NavigationMode.FLY, true, false, -1.25D, 0.35D, false,
            NavigationRotationPolicy.NONE);

    @Test
    void keepsExactRawGoalAndAllOptionsAcrossTheEleventhAndTwelfthStartTicks() {
        NavigationController controller = new NavigationController();
        FixedCoordinateFollowSession session = start(controller, OPTIONS);
        NavigationHandle oldHandle = session.handle();
        advanceToFollowing(oldHandle, 1L);

        assertEquals(GOAL, oldHandle.status().orElseThrow().request().goal());
        assertEquals(new NavigationGoal(GOAL.x(), GOAL.y() - 1.25D, GOAL.z()),
                oldHandle.status().orElseThrow().request().effectiveGoal());
        assertEquals(OPTIONS, oldHandle.status().orElseThrow().request().options());
        assertFalse(OPTIONS.smooth());
        assertFalse(OPTIONS.sprint());
        assertEquals(0.35D, OPTIONS.completionThreshold());
        assertEquals(NavigationRotationPolicy.NONE, OPTIONS.rotationPolicy());

        for (int tick = 1;
                tick < FixedCoordinateFollowSession.RECALCULATION_START_TICKS;
                tick++) {
            FollowUpdate update = session.onStartTick(eligible(EPOCH));
            assertEquals(FollowUpdate.Action.WAITING, update.action());
            assertEquals(FixedCoordinateFollowSession.RECALCULATION_START_TICKS - tick,
                    update.startTicksUntilRecalculation());
            assertEquals(oldHandle.ticket(), update.activeTicket().orElseThrow());
        }

        FollowUpdate replacement = session.onStartTick(eligible(EPOCH));
        assertEquals(FollowUpdate.Action.RECALCULATED, replacement.action());
        assertEquals(oldHandle.ticket().generation() + 1L,
                replacement.activeTicket().orElseThrow().generation());
        assertEquals(NavigationCancellationReason.REPLACED, cancellation(oldHandle));
        assertEquals(GOAL, replacement.replacementRequest().orElseThrow().goal());
        assertEquals(new NavigationGoal(GOAL.x(), GOAL.y() - 1.25D, GOAL.z()),
                replacement.replacementRequest().orElseThrow().effectiveGoal());
        assertEquals(OPTIONS, replacement.replacementRequest().orElseThrow().options());
    }

    @Test
    void busyBoundaryResetsAndRequiresTwelveFreshStartTicks() {
        FixedCoordinateFollowSession session =
                start(new NavigationController(), OPTIONS);
        NavigationHandle initial = session.handle();

        for (int tick = 1;
                tick < FixedCoordinateFollowSession.RECALCULATION_START_TICKS;
                tick++) {
            assertEquals(FollowUpdate.Action.WAITING,
                    session.onStartTick(eligible(EPOCH)).action());
        }
        FollowUpdate deferred = session.onStartTick(eligible(EPOCH));
        assertEquals(FollowUpdate.Action.RECALCULATION_DEFERRED, deferred.action());
        assertEquals(initial.ticket(), deferred.activeTicket().orElseThrow());

        advanceToFollowing(initial, 2L);
        for (int tick = 1;
                tick < FixedCoordinateFollowSession.RECALCULATION_START_TICKS;
                tick++) {
            assertEquals(FollowUpdate.Action.WAITING,
                    session.onStartTick(eligible(EPOCH)).action());
        }
        assertEquals(FollowUpdate.Action.RECALCULATED,
                session.onStartTick(eligible(EPOCH)).action());
    }

    @Test
    void rejectsNonFollowOptionsAndUsesSharedWorldAndCancellationBoundaries() {
        NavigationOptions notFollowing = new NavigationOptions(
                NavigationMode.FLY, false, true, 0.0D, 1.0D, true,
                NavigationRotationPolicy.FACE_PATH);
        assertThrows(IllegalArgumentException.class,
                () -> start(new NavigationController(), notFollowing));

        FixedCoordinateFollowSession worldChanged =
                start(new NavigationController(), OPTIONS);
        FollowUpdate terminal = worldChanged.onStartTick(eligible(EPOCH + 1L));
        assertEquals(FollowTerminationReason.WORLD_CHANGED,
                terminal.terminationReason().orElseThrow());
        assertEquals(NavigationCancellationReason.WORLD_CHANGED,
                cancellation(worldChanged.handle()));

        FixedCoordinateFollowSession cancelled =
                start(new NavigationController(), OPTIONS);
        assertEquals(FollowTerminationReason.CANCELLED,
                cancelled.cancel(NavigationCancellationReason.OWNER_REQUESTED)
                        .terminationReason().orElseThrow());
        assertEquals(NavigationCancellationReason.OWNER_REQUESTED,
                cancellation(cancelled.handle()));
    }

    private static FixedCoordinateFollowSession start(
            NavigationController controller,
            NavigationOptions options
    ) {
        return FixedCoordinateFollowSession.start(
                controller, OWNER, EPOCH, GOAL, options, eligible(EPOCH));
    }

    private static NavigationCancellationReason cancellation(NavigationHandle handle) {
        return handle.status().orElseThrow().terminalResult().orElseThrow()
                .cancellationReason().orElseThrow();
    }

    private static NavigationStartObservation eligible(long epoch) {
        return new NavigationStartObservation(
                Observation.present(new WorldSnapshot(epoch, Observation.unknown())),
                Observation.present(new PlayerSnapshot(
                        Observation.unknown(),
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
