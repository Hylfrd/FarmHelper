package dev.hylfrd.farmhelper.navigation;

import dev.hylfrd.farmhelper.control.input.ControlOwner;
import dev.hylfrd.farmhelper.runtime.snapshot.ConnectionSnapshot;
import dev.hylfrd.farmhelper.runtime.snapshot.Observation;
import dev.hylfrd.farmhelper.runtime.snapshot.PlayerSnapshot;
import dev.hylfrd.farmhelper.runtime.snapshot.ScreenSnapshot;
import dev.hylfrd.farmhelper.runtime.snapshot.WorldSnapshot;
import dev.hylfrd.farmhelper.runtime.spatial.BoxSnapshot;
import dev.hylfrd.farmhelper.runtime.spatial.SpatialSnapshot;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NavigationControllerTest {
    private static final ControlOwner OWNER = new ControlOwner("navigation-test");

    @Test
    void requestKeepsEveryOptionAndAppliesYOffsetExactlyOnce() {
        NavigationOptions options = new NavigationOptions(
                NavigationMode.WALK, true, false, 2.5D, 0.75D, false,
                NavigationRotationPolicy.FACE_GOAL);
        NavigationRequest request = new NavigationRequest(
                OWNER, 7L, new NavigationGoal(1.0D, 64.0D, -3.0D), options);

        assertEquals(new NavigationGoal(1.0D, 66.5D, -3.0D), request.effectiveGoal());
        assertEquals(request.effectiveGoal(), request.effectiveGoal());
        assertEquals(64.0D, request.goal().y());
        assertTrue(request.options().follow());
        assertFalse(request.options().smooth());
        assertFalse(request.options().sprint());
        assertThrows(IllegalArgumentException.class, () -> new NavigationOptions(
                NavigationMode.FLY, false, false, Double.NaN, 1.0D, false,
                NavigationRotationPolicy.NONE));
        assertThrows(IllegalArgumentException.class, () -> new NavigationOptions(
                NavigationMode.FLY, false, false, 0.0D, -0.1D, false,
                NavigationRotationPolicy.NONE));
    }

    @Test
    void explicitReplacementTerminatesOldAndEveryStaleMutationFailsClosed() {
        NavigationController controller = new NavigationController();
        NavigationRequest firstRequest = request(OWNER, 3L, 10.0D);
        NavigationHandle first = controller.start(firstRequest, eligible(3L));
        assertTrue(first.advance(NavigationPhase.REQUESTED, NavigationPhase.CAPTURING));
        SegmentedSpatialSnapshot firstCapture = capture(first.ticket(), 1L);

        assertThrows(NavigationConflictException.class,
                () -> controller.start(request(OWNER, 3L, 20.0D), eligible(3L)));
        assertThrows(NavigationConflictException.class,
                () -> controller.start(
                        request(new ControlOwner("different-owner"), 3L, 20.0D), eligible(3L)));

        NavigationHandle replacement = first.replace(
                request(OWNER, 3L, 20.0D), eligible(3L)).orElseThrow();

        assertEquals(first.ticket().generation() + 1L, replacement.ticket().generation());
        assertEquals(NavigationCancellationReason.REPLACED,
                first.status().orElseThrow().terminalResult().orElseThrow()
                        .cancellationReason().orElseThrow());
        assertFalse(first.cancel());
        assertFalse(first.advance(NavigationPhase.CAPTURING, NavigationPhase.SEARCHING));
        assertFalse(first.acceptCapture(firstCapture));
        assertFalse(first.complete());
        assertFalse(first.fail(NavigationFailureReason.NO_PATH));
        assertTrue(first.replace(request(OWNER, 3L, 30.0D), eligible(3L)).isEmpty());
        assertEquals(replacement.ticket(), controller.activeTicket().orElseThrow());

        assertTrue(replacement.advance(NavigationPhase.REQUESTED, NavigationPhase.CAPTURING));
        SegmentedSpatialSnapshot replacementCapture = capture(replacement.ticket(), 2L);
        assertThrows(IllegalArgumentException.class,
                () -> controller.acceptCapture(replacement.ticket(), firstCapture));
        assertTrue(replacement.acceptCapture(replacementCapture));
        assertEquals(NavigationPhase.SEARCHING,
                replacement.status().orElseThrow().phase());
        assertTrue(replacement.advance(NavigationPhase.SEARCHING, NavigationPhase.EXECUTING));
        assertTrue(replacement.advance(NavigationPhase.EXECUTING, NavigationPhase.CAPTURING));
        assertTrue(replacement.status().orElseThrow().spatialSnapshot().isEmpty());
        assertFalse(replacement.advance(NavigationPhase.CAPTURING, NavigationPhase.SEARCHING));
        SegmentedSpatialSnapshot recapture = capture(replacement.ticket(), 3L);
        assertTrue(replacement.acceptCapture(recapture));
        assertTrue(replacement.complete());
        assertEquals(NavigationTerminalState.COMPLETED,
                replacement.status().orElseThrow().terminalResult().orElseThrow().state());
        assertEquals(NavigationCancellationReason.REPLACED,
                first.status().orElseThrow().terminalResult().orElseThrow()
                        .cancellationReason().orElseThrow());
    }

    @Test
    void everyStartEligibilityFailureIsTypedAndConsumesANeverReusedTicket() {
        NavigationController controller = new NavigationController();
        List<Long> generations = new ArrayList<>();

        generations.add(assertStartFailure(controller, request(OWNER, 9L, 0.0D),
                new NavigationStartObservation(
                        Observation.unknown(), player(), connection(), Observation.absent()),
                NavigationFailureReason.WORLD_UNAVAILABLE));
        generations.add(assertStartFailure(controller, request(OWNER, 9L, 0.0D),
                new NavigationStartObservation(
                        world(9L), Observation.absent(), connection(), Observation.absent()),
                NavigationFailureReason.PLAYER_UNAVAILABLE));
        generations.add(assertStartFailure(controller, request(OWNER, 9L, 0.0D),
                new NavigationStartObservation(
                        world(9L), player(), Observation.unknown(), Observation.absent()),
                NavigationFailureReason.CONNECTION_UNAVAILABLE));
        generations.add(assertStartFailure(controller, request(OWNER, 9L, 0.0D),
                new NavigationStartObservation(
                        world(9L), player(), connection(),
                        Observation.present(ScreenSnapshot.unknownDetails())),
                NavigationFailureReason.SCREEN_PRESENT));
        generations.add(assertStartFailure(controller, request(OWNER, 9L, 0.0D),
                new NavigationStartObservation(
                        world(9L), player(), connection(), Observation.unknown()),
                NavigationFailureReason.SCREEN_UNKNOWN));
        generations.add(assertStartFailure(controller, request(OWNER, 9L, 0.0D),
                eligible(8L), NavigationFailureReason.WORLD_EPOCH_MISMATCH));

        assertEquals(List.of(1L, 2L, 3L, 4L, 5L, 6L), generations);
    }

    @Test
    void generationOverflowFailsBeforeDiscardingAnActiveRun() {
        NavigationController controller = new NavigationController(
                Long.MAX_VALUE - 1L, () -> { });
        NavigationHandle maximum = controller.start(request(OWNER, 1L, 0.0D), eligible(1L));
        assertEquals(Long.MAX_VALUE, maximum.ticket().generation());

        assertThrows(IllegalStateException.class, () -> maximum.replace(
                request(new ControlOwner("replacement"), 1L, 1.0D), eligible(1L)));
        assertEquals(maximum.ticket(), controller.activeTicket().orElseThrow());
        assertTrue(maximum.cancel());
        assertThrows(IllegalStateException.class,
                () -> controller.start(request(OWNER, 1L, 2.0D), eligible(1L)));
    }

    @Test
    void terminalCleanupCommitsFirstAndAttemptsEveryParticipant() {
        List<String> attempts = new ArrayList<>();
        List<RuntimeException> blocked = new ArrayList<>();
        NavigationController[] holder = new NavigationController[1];
        holder[0] = new NavigationController(
                () -> { },
                (ticket, result) -> {
                    attempts.add("tasks");
                    assertFalse(holder[0].cancel(ticket, NavigationCancellationReason.FAILURE));
                    try {
                        holder[0].start(request(OWNER, 2L, 99.0D), eligible(2L));
                    } catch (RuntimeException failure) {
                        blocked.add(failure);
                    }
                },
                (ticket, result) -> {
                    attempts.add("input");
                    throw new IllegalStateException("release failed");
                },
                (ticket, result) -> attempts.add("expectations"));
        NavigationHandle handle = holder[0].start(request(OWNER, 2L, 0.0D), eligible(2L));

        assertTrue(handle.cancel());

        assertEquals(List.of("tasks", "input", "expectations"), attempts);
        assertEquals(1, blocked.size());
        assertEquals("navigation terminal cleanup is in progress", blocked.getFirst().getMessage());
        assertTrue(holder[0].activeTicket().isEmpty());
        assertEquals(1, holder[0].lastCleanupFailure().orElseThrow().getSuppressed().length);
        assertEquals(NavigationCancellationReason.OWNER_REQUESTED,
                holder[0].lastResult().orElseThrow().cancellationReason().orElseThrow());
        NavigationHandle next = holder[0].start(request(OWNER, 2L, 100.0D), eligible(2L));
        assertEquals(handle.ticket().generation() + 1L, next.ticket().generation());
    }

    @Test
    void canonicalTaskOwnerUsesOwnerAndGenerationWithoutCallerStrings() {
        NavigationTicket first = new NavigationTicket(OWNER, 4L, 2L);
        NavigationTicket sameRunDifferentEpoch = new NavigationTicket(OWNER, 4L, 3L);
        NavigationTicket next = new NavigationTicket(OWNER, 5L, 2L);

        assertNotEquals(NavigationTaskOwner.from(first), NavigationTaskOwner.from(sameRunDifferentEpoch));
        assertNotEquals(NavigationTaskOwner.from(first), NavigationTaskOwner.from(next));
        assertEquals("navigation/navigation-test/4/world/2", NavigationTaskOwner.from(first).id());
    }

    @Test
    void ticketAndTerminalResultRejectNonExactOrContradictoryIdentity() {
        assertThrows(IllegalArgumentException.class, () -> new NavigationTicket(OWNER, 0L, 1L));
        assertThrows(IllegalArgumentException.class, () -> new NavigationTicket(OWNER, 1L, -1L));
        NavigationTicket ticket = new NavigationTicket(OWNER, 1L, 1L);
        assertThrows(IllegalArgumentException.class, () -> new NavigationResult(
                ticket, NavigationTerminalState.FAILED,
                java.util.Optional.empty(), java.util.Optional.empty()));
        assertThrows(IllegalArgumentException.class, () -> new NavigationResult(
                ticket, NavigationTerminalState.CANCELLED,
                java.util.Optional.of(NavigationFailureReason.INTERNAL_FAILURE),
                java.util.Optional.of(NavigationCancellationReason.FAILURE)));
    }

    private static long assertStartFailure(
            NavigationController controller,
            NavigationRequest request,
            NavigationStartObservation observation,
            NavigationFailureReason expected
    ) {
        NavigationHandle handle = controller.start(request, observation);
        NavigationResult result = handle.status().orElseThrow().terminalResult().orElseThrow();
        assertEquals(NavigationTerminalState.FAILED, result.state());
        assertEquals(expected, result.failureReason().orElseThrow());
        assertTrue(controller.activeTicket().isEmpty());
        return handle.ticket().generation();
    }

    private static NavigationRequest request(ControlOwner owner, long epoch, double x) {
        return new NavigationRequest(
                owner, epoch, new NavigationGoal(x, 70.0D, 0.0D), NavigationOptions.fly());
    }

    private static NavigationStartObservation eligible(long epoch) {
        return new NavigationStartObservation(
                world(epoch), player(), connection(), Observation.absent());
    }

    private static Observation<WorldSnapshot> world(long epoch) {
        return Observation.present(new WorldSnapshot(epoch, Observation.unknown()));
    }

    private static Observation<PlayerSnapshot> player() {
        return Observation.present(new PlayerSnapshot(
                Observation.unknown(), Observation.unknown(), Observation.unknown(),
                Observation.unknown(), Observation.unknown()));
    }

    private static Observation<ConnectionSnapshot> connection() {
        return Observation.present(ConnectionSnapshot.multiplayer());
    }

    private static SegmentedSpatialSnapshot capture(NavigationTicket ticket, long token) {
        BoxSnapshot bounds = new BoxSnapshot(0.0D, 0.0D, 0.0D, 1.0D, 2.0D, 1.0D);
        SpatialSnapshot snapshot = new SpatialSnapshot(
                ticket.worldEpoch(), token, bounds, 0, 2,
                new BoxSnapshot(0.2D, 0.0D, 0.2D, 0.8D, 1.8D, 0.8D), Map.of());
        return new SegmentedSpatialSnapshot(
                ticket, bounds, List.of(new SpatialSegment(0, ticket, token, snapshot)));
    }
}
