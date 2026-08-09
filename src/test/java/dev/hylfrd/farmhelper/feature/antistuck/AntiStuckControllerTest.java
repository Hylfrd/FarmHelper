package dev.hylfrd.farmhelper.feature.antistuck;

import dev.hylfrd.farmhelper.control.input.ControlOwner;
import dev.hylfrd.farmhelper.control.input.InputAction;
import dev.hylfrd.farmhelper.runtime.spatial.BlockPosition;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AntiStuckControllerTest {
    private static final AntiStuckIdentity ID = new AntiStuckIdentity(
            new ControlOwner("antistuck-test"), 7L, 11L);
    private static final AntiStuckIdentity OTHER_ID = new AntiStuckIdentity(
            new ControlOwner("other"), 8L, 11L);
    private static final AntiStuckIdentity NEXT_ID = new AntiStuckIdentity(
            new ControlOwner("antistuck-test"), 7L, 11L, 2L);
    private static final AntiStuckPlayerPose PLAYER =
            new AntiStuckPlayerPose(0.5D, 64.0D, 0.0D, 0.0D);

    @Test
    void noTargetUsesExactDelaySequenceAndTerminalIsIdempotent() {
        RecordingRandom random = new RecordingRandom();
        AntiStuckController controller = new AntiStuckController(random, 5);
        AntiStuckRecoveryRequest request =
                AntiStuckRecoveryRequest.withoutDirectionTarget(ID);
        AntiStuckRecoveryEvidence evidence = known(
                AntiStuckTargetEvidence.absent(), AntiStuckRecoveryEvidence.SideStatus.BLOCKED);

        assertEquals(AntiStuckDecisionKind.STARTED, controller.start(request, 0L).kind());
        AntiStuckDecision press = controller.tick(tick(0L, evidence));
        assertEquals(AntiStuckState.PRESS, press.state());
        assertEquals(150, press.scheduledDelayMillis().orElseThrow());
        assertEquals(Set.of(
                InputAction.FORWARD,
                InputAction.BACKWARD,
                InputAction.LEFT,
                InputAction.RIGHT,
                InputAction.JUMP,
                InputAction.SNEAK,
                InputAction.ATTACK), press.inputIntent().releasedActions());
        assertEquals(Set.of(InputAction.USE, InputAction.SPRINT),
                press.inputIntent().preservedActions());

        assertEquals(AntiStuckDecisionKind.WAITING,
                controller.tick(tick(149L, evidence)).kind());
        AntiStuckDecision noTarget = controller.tick(tick(150L, evidence));
        assertEquals(AntiStuckDecisionReason.NO_TARGET, noTarget.reason());
        assertEquals(AntiStuckState.RELEASE, noTarget.state());
        assertEquals(Set.of(InputAction.BACKWARD, InputAction.SNEAK),
                noTarget.inputIntent().heldActions());
        assertEquals(250L, noTarget.dueAtMillis().orElseThrow());

        AntiStuckDecision released = controller.tick(tick(250L, evidence));
        assertEquals(AntiStuckState.COME_BACK, released.state());
        assertEquals(AntiStuckDecisionReason.RELEASE, released.reason());
        assertTrue(released.inputIntent().releasedActions().contains(InputAction.SNEAK));

        AntiStuckDecision returned = controller.tick(tick(300L, evidence));
        assertEquals(AntiStuckState.DISABLE, returned.state());
        assertTrue(returned.inputIntent().preservedActions().contains(InputAction.USE));
        assertTrue(returned.inputIntent().preservedActions().contains(InputAction.SPRINT));
        assertTrue(returned.inputIntent().heldActions().isEmpty());
        assertEquals(380L, returned.dueAtMillis().orElseThrow());

        AntiStuckDecision terminal = controller.tick(tick(380L, evidence));
        assertEquals(AntiStuckDecisionKind.STOPPED, terminal.kind());
        assertTrue(terminal.terminal());
        assertEquals(1, terminal.unstuckTries());
        assertEquals(terminal, controller.tick(tick(10_000L, evidence)));
        assertEquals(1, controller.unstuckTries());
        assertEquals(4, random.calls().size());
        assertEquals(List.of(
                List.of(150, 300),
                List.of(100, 200),
                List.of(50, 100),
                List.of(80, 160)), random.calls());
    }

    @Test
    void targetPressesAttackAndReturnsOppositeMovementWithExactRanges() {
        RecordingRandom random = RecordingRandom.maximums();
        AntiStuckController controller = new AntiStuckController(random, 5);
        AntiStuckRecoveryRequest request =
                AntiStuckRecoveryRequest.withoutDirectionTarget(ID);
        AntiStuckRecoveryEvidence evidence = knownFor(
                ID,
                AntiStuckTargetEvidence.present(new BlockPosition(0, 64, 0)),
                sideStatuses(AntiStuckSide.SOUTH, AntiStuckRecoveryEvidence.SideStatus.CLEAR));

        controller.start(request, 0L);
        controller.tick(tick(0L, evidence));
        AntiStuckDecision target = controller.tick(tick(299L, evidence));

        assertEquals(AntiStuckDecisionReason.TARGET_SELECTED, target.reason());
        assertEquals(458L, target.dueAtMillis().orElseThrow());
        assertEquals(Set.of(InputAction.FORWARD, InputAction.SNEAK, InputAction.ATTACK),
                target.inputIntent().heldActions());
        assertEquals(AntiStuckInputDecision.PRESERVE,
                target.inputIntent().decision(InputAction.USE));
        assertEquals(AntiStuckInputDecision.PRESERVE,
                target.inputIntent().decision(InputAction.SPRINT));

        AntiStuckDecision release = controller.tick(tick(458L, evidence));
        assertEquals(AntiStuckState.COME_BACK, release.state());
        assertEquals(557L, release.dueAtMillis().orElseThrow());
        AntiStuckDecision returned = controller.tick(tick(557L, evidence));
        assertEquals(Set.of(InputAction.BACKWARD, InputAction.SNEAK, InputAction.ATTACK),
                returned.inputIntent().heldActions());
        assertEquals(716L, returned.dueAtMillis().orElseThrow());
        assertEquals(AntiStuckDecisionKind.WAITING,
                controller.tick(tick(715L, evidence)).kind());

        AntiStuckDecision terminal = controller.tick(tick(716L, evidence));
        assertEquals(AntiStuckDecisionKind.STOPPED, terminal.kind());
        assertTrue(terminal.terminal());
        assertTrue(terminal.inputIntent().heldActions().isEmpty());
        assertTrue(terminal.dueAtMillis().isEmpty());
        assertTrue(terminal.scheduledDelayMillis().isEmpty());
        assertEquals(terminal, controller.tick(tick(10_000L, evidence)));
    }

    @Test
    void directionTargetSkipsComeBackAfterRelease() {
        RecordingRandom random = new RecordingRandom();
        AntiStuckController controller = new AntiStuckController(random, 5);
        AntiStuckRecoveryRequest request = new AntiStuckRecoveryRequest(
                ID,
                AntiStuckTargetEvidence.present(new BlockPosition(0, 64, 1)),
                0,
                0);
        AntiStuckRecoveryEvidence evidence = known(
                AntiStuckTargetEvidence.absent(), AntiStuckRecoveryEvidence.SideStatus.BLOCKED);

        controller.start(request, 0L);
        controller.tick(tick(0L, evidence));
        AntiStuckDecision target = controller.tick(tick(150L, evidence));
        assertEquals(AntiStuckState.RELEASE, target.state());
        assertEquals(Set.of(InputAction.FORWARD, InputAction.SNEAK, InputAction.ATTACK),
                target.inputIntent().heldActions());

        AntiStuckDecision release = controller.tick(tick(230L, evidence));
        assertEquals(AntiStuckState.DISABLE, release.state());
        assertEquals(AntiStuckDecisionReason.RELEASE, release.reason());
        AntiStuckDecision terminal = controller.tick(tick(280L, evidence));
        assertEquals(AntiStuckDecisionKind.STOPPED, terminal.kind());
        assertEquals(1, terminal.unstuckTries());
    }

    @Test
    void geometryKeepsExactMultipliersNearestTieAndRelativeKeys() {
        BlockPosition obstacle = new BlockPosition(10, 20, 30);
        assertEquals(new AntiStuckPoint(0.5D, 0.5D, 1.0D), AntiStuckSide.SOUTH.multiplier());
        assertEquals(new AntiStuckPoint(0.0D, 0.5D, 0.5D), AntiStuckSide.WEST.multiplier());
        assertEquals(new AntiStuckPoint(0.5D, 0.5D, 0.0D), AntiStuckSide.NORTH.multiplier());
        assertEquals(new AntiStuckPoint(1.0D, 0.5D, 0.5D), AntiStuckSide.EAST.multiplier());
        assertEquals(new AntiStuckPoint(10.5D, 20.5D, 32.0D),
                AntiStuckSide.SOUTH.movementTarget(obstacle));

        AntiStuckPlayerPose center = new AntiStuckPlayerPose(10.5D, 20.0D, 30.5D, 0.0D);
        assertEquals(AntiStuckSide.SOUTH, AntiStuckGeometry.nearestClearSide(
                obstacle, center, EnumSet.allOf(AntiStuckSide.class)).orElseThrow());
        assertEquals(Set.of(InputAction.FORWARD), AntiStuckGeometry.neededMovementKeys(
                new AntiStuckPlayerPose(0.0D, 0.0D, 0.0D, 0.0D),
                new AntiStuckPoint(0.0D, 0.0D, 1.0D)));
        assertEquals(Set.of(InputAction.BACKWARD), AntiStuckGeometry.neededMovementKeys(
                new AntiStuckPlayerPose(0.0D, 0.0D, 0.0D, 0.0D),
                new AntiStuckPoint(0.0D, 0.0D, -1.0D)));
        assertEquals(Set.of(InputAction.LEFT), AntiStuckGeometry.neededMovementKeys(
                new AntiStuckPlayerPose(0.0D, 0.0D, 0.0D, 0.0D),
                new AntiStuckPoint(1.0D, 0.0D, 0.0D)));
        assertEquals(Set.of(InputAction.RIGHT), AntiStuckGeometry.neededMovementKeys(
                new AntiStuckPlayerPose(0.0D, 0.0D, 0.0D, 0.0D),
                new AntiStuckPoint(-1.0D, 0.0D, 0.0D)));
    }

    @Test
    void retryThresholdRewarpAndLagBackAreDeterministic() {
        RecordingRandom random = new RecordingRandom();
        AntiStuckController controller = new AntiStuckController(random, 2);
        AntiStuckRecoveryRequest request = new AntiStuckRecoveryRequest(
                ID, AntiStuckTargetEvidence.absent(), 3, 4);
        AntiStuckRecoveryEvidence evidence = known(
                AntiStuckTargetEvidence.absent(), AntiStuckRecoveryEvidence.SideStatus.BLOCKED);

        controller.start(request, 0L);
        assertEquals(5, controller.recordLagBack(ID).lagBackCounter());
        controller.tick(tick(0L, evidence));
        AntiStuckDecision rewarp = controller.tick(tick(150L, evidence));

        assertEquals(AntiStuckDecisionKind.REWARP, rewarp.kind());
        assertTrue(rewarp.rewarpRequested());
        assertEquals(0, rewarp.unstuckTries());
        assertEquals(5, rewarp.lagBackCounter());
        assertEquals(rewarp, controller.tick(tick(1_000L, evidence)));
        assertEquals(rewarp, controller.recordLagBack(ID));
    }

    @Test
    void overdueClockAndUnknownOrErrorEvidenceFailClosed() {
        AntiStuckRecoveryEvidence known = known(
                AntiStuckTargetEvidence.absent(), AntiStuckRecoveryEvidence.SideStatus.BLOCKED);

        AntiStuckController overdue = new AntiStuckController(RecordingRandom.minimums(), 5);
        overdue.start(AntiStuckRecoveryRequest.withoutDirectionTarget(ID), 0L);
        overdue.tick(tick(0L, known));
        AntiStuckDecision overdueDecision = overdue.tick(tick(1_151L, known));
        assertEquals(AntiStuckDecisionReason.OVERDUE, overdueDecision.reason());
        assertTrue(overdueDecision.terminal());

        AntiStuckController unknown = new AntiStuckController(RecordingRandom.minimums(), 5);
        unknown.start(AntiStuckRecoveryRequest.withoutDirectionTarget(ID), 0L);
        unknown.tick(tick(0L, known));
        AntiStuckDecision unknownDecision = unknown.tick(
                tick(150L, AntiStuckRecoveryEvidence.unknown(ID)));
        assertEquals(AntiStuckDecisionReason.UNKNOWN_EVIDENCE, unknownDecision.reason());
        assertEquals(unknownDecision, unknown.tick(
                tick(500L, AntiStuckRecoveryEvidence.unknown(ID))));

        AntiStuckController error = new AntiStuckController(RecordingRandom.minimums(), 5);
        error.start(AntiStuckRecoveryRequest.withoutDirectionTarget(ID), 0L);
        error.tick(tick(0L, known));
        AntiStuckDecision errorDecision = error.tick(
                tick(150L, AntiStuckRecoveryEvidence.error(ID)));
        assertEquals(AntiStuckDecisionReason.ERROR_EVIDENCE, errorDecision.reason());
    }

    @Test
    void backwardTicksAreStaleEqualTicksWaitAndSaturatedDeadlineIsSafe() {
        RecordingRandom random = RecordingRandom.minimums();
        AntiStuckController controller = new AntiStuckController(random, 5);
        AntiStuckRecoveryRequest request =
                AntiStuckRecoveryRequest.withoutDirectionTarget(ID);
        AntiStuckRecoveryEvidence evidence = known(
                AntiStuckTargetEvidence.absent(), AntiStuckRecoveryEvidence.SideStatus.BLOCKED);

        controller.start(request, 100L);
        assertEquals(AntiStuckDecisionKind.STALE_TICK,
                controller.tick(tick(99L, evidence)).kind());
        AntiStuckDecision initial = controller.tick(tick(100L, evidence));
        assertEquals(250L, initial.dueAtMillis().orElseThrow());
        assertEquals(AntiStuckDecisionKind.WAITING,
                controller.tick(tick(100L, evidence)).kind());
        assertEquals(AntiStuckDecisionKind.STALE_TICK,
                controller.tick(tick(99L, evidence)).kind());
        assertEquals(1, random.calls().size());

        AntiStuckController overflow = new AntiStuckController(
                RecordingRandom.minimums(), 5);
        overflow.start(request, Long.MAX_VALUE - 1L);
        AntiStuckDecision saturated = overflow.tick(
                tick(Long.MAX_VALUE - 1L, evidence));
        assertEquals(Long.MAX_VALUE, saturated.dueAtMillis().orElseThrow());
        assertEquals(AntiStuckDecisionKind.WAITING,
                overflow.tick(tick(Long.MAX_VALUE - 1L, evidence)).kind());
        assertEquals(AntiStuckDecisionKind.STALE_TICK,
                overflow.tick(tick(Long.MAX_VALUE - 2L, evidence)).kind());
        AntiStuckDecision exactMaximum = overflow.tick(tick(Long.MAX_VALUE, evidence));
        assertEquals(AntiStuckDecisionReason.NO_TARGET, exactMaximum.reason());
        assertEquals(Long.MAX_VALUE, exactMaximum.dueAtMillis().orElseThrow());
    }

    @Test
    void completedRunCanRestartWithNewRevisionAndRejectOldRunEvents() {
        AntiStuckController controller = new AntiStuckController(
                RecordingRandom.minimums(), 5);
        AntiStuckRecoveryRequest request =
                AntiStuckRecoveryRequest.withoutDirectionTarget(ID);
        AntiStuckRecoveryEvidence evidence = known(
                AntiStuckTargetEvidence.absent(), AntiStuckRecoveryEvidence.SideStatus.BLOCKED);

        controller.start(request, 0L);
        controller.tick(tick(0L, evidence));
        controller.tick(tick(150L, evidence));
        controller.tick(tick(250L, evidence));
        controller.tick(tick(300L, evidence));
        AntiStuckDecision terminal = controller.tick(tick(380L, evidence));
        assertEquals(AntiStuckDecisionKind.STOPPED, terminal.kind());
        assertEquals(terminal, controller.start(request, 381L));

        AntiStuckRecoveryRequest restart =
                AntiStuckRecoveryRequest.withoutDirectionTarget(NEXT_ID);
        assertEquals(AntiStuckDecisionKind.STARTED, controller.start(restart, 0L).kind());
        assertEquals(AntiStuckDecisionKind.STALE_REQUEST,
                controller.start(request, 1L).kind());

        AntiStuckDecision staleOldTick = controller.tick(
                tick(ID, 10_000L, evidence));
        assertEquals(AntiStuckDecisionKind.STALE_TICK, staleOldTick.kind());
        assertEquals(AntiStuckState.NONE, staleOldTick.state());
        assertTrue(staleOldTick.dueAtMillis().isEmpty());

        AntiStuckRecoveryEvidence restartEvidence = knownFor(
                NEXT_ID,
                AntiStuckTargetEvidence.absent());
        AntiStuckDecision initial = controller.tick(
                tick(NEXT_ID, 0L, restartEvidence));
        assertEquals(AntiStuckDecisionReason.INITIAL_DELAY, initial.reason());
        assertEquals(150L, initial.dueAtMillis().orElseThrow());
    }

    @Test
    void staleRequestsAndTicksCannotChangeActiveRun() {
        RecordingRandom random = new RecordingRandom();
        AntiStuckController controller = new AntiStuckController(random, 5);
        AntiStuckRecoveryRequest request =
                AntiStuckRecoveryRequest.withoutDirectionTarget(ID);
        AntiStuckRecoveryEvidence evidence = known(
                AntiStuckTargetEvidence.absent(), AntiStuckRecoveryEvidence.SideStatus.BLOCKED);

        controller.start(request, 0L);
        AntiStuckDecision before = controller.tick(tick(0L, evidence));
        assertEquals(AntiStuckDecisionKind.STALE_REQUEST,
                controller.start(AntiStuckRecoveryRequest.withoutDirectionTarget(OTHER_ID), 1L).kind());
        assertEquals(AntiStuckDecisionKind.STALE_TICK,
                controller.tick(new AntiStuckTick(OTHER_ID, 150L,
                        knownFor(OTHER_ID, AntiStuckTargetEvidence.absent()))).kind());
        AntiStuckDecision waiting = controller.tick(tick(149L, evidence));
        assertEquals(AntiStuckState.PRESS, waiting.state());
        assertEquals(before.dueAtMillis(), waiting.dueAtMillis());
        assertThrows(IllegalArgumentException.class,
                () -> new AntiStuckTick(ID, 0L, knownFor(OTHER_ID, AntiStuckTargetEvidence.absent())));
        assertEquals(AntiStuckDecisionKind.STOPPED, controller.stop(ID).kind());
        assertEquals(AntiStuckDecisionKind.STALE_REQUEST, controller.stop(ID).kind());
    }

    @Test
    void validatedValuesRejectMissingIdentityEvidenceAndIntentEntries() {
        assertThrows(IllegalArgumentException.class,
                () -> new AntiStuckIdentity(new ControlOwner("x"), 0L, 0L));
        assertThrows(IllegalArgumentException.class,
                () -> new AntiStuckIdentity(new ControlOwner("x"), 1L, 0L, 0L));
        assertThrows(IllegalArgumentException.class,
                () -> new AntiStuckRecoveryRequest(ID, AntiStuckTargetEvidence.absent(), -1, 0));
        assertThrows(IllegalArgumentException.class,
                () -> new AntiStuckRecoveryEvidence(
                        ID,
                        AntiStuckRecoveryEvidence.Status.KNOWN,
                        java.util.Optional.empty(),
                        AntiStuckTargetEvidence.absent(),
                        AntiStuckRecoveryEvidence.allSides(
                                AntiStuckRecoveryEvidence.SideStatus.BLOCKED)));
        assertThrows(IllegalArgumentException.class,
                () -> AntiStuckInputIntent.fromSets(
                        Set.of(InputAction.ATTACK), Set.of(InputAction.ATTACK)));
    }

    private static AntiStuckTick tick(long nowMillis, AntiStuckRecoveryEvidence evidence) {
        return tick(ID, nowMillis, evidence);
    }

    private static AntiStuckTick tick(
            AntiStuckIdentity identity,
            long nowMillis,
            AntiStuckRecoveryEvidence evidence
    ) {
        return new AntiStuckTick(identity, nowMillis, evidence);
    }

    private static AntiStuckRecoveryEvidence known(
            AntiStuckTargetEvidence intersection,
            AntiStuckRecoveryEvidence.SideStatus defaultStatus
    ) {
        return knownFor(ID, intersection, allSides(defaultStatus));
    }

    private static AntiStuckRecoveryEvidence knownFor(
            AntiStuckIdentity identity,
            AntiStuckTargetEvidence intersection
    ) {
        return knownFor(identity, intersection, allSides(AntiStuckRecoveryEvidence.SideStatus.BLOCKED));
    }

    private static AntiStuckRecoveryEvidence knownFor(
            AntiStuckIdentity identity,
            AntiStuckTargetEvidence intersection,
            Map<AntiStuckSide, AntiStuckRecoveryEvidence.SideStatus> sides
    ) {
        return AntiStuckRecoveryEvidence.known(identity, PLAYER, intersection, sides);
    }

    private static Map<AntiStuckSide, AntiStuckRecoveryEvidence.SideStatus> allSides(
            AntiStuckRecoveryEvidence.SideStatus status
    ) {
        return AntiStuckRecoveryEvidence.allSides(status);
    }

    private static Map<AntiStuckSide, AntiStuckRecoveryEvidence.SideStatus> sideStatuses(
            AntiStuckSide clear,
            AntiStuckRecoveryEvidence.SideStatus clearStatus
    ) {
        EnumMap<AntiStuckSide, AntiStuckRecoveryEvidence.SideStatus> sides =
                new EnumMap<>(AntiStuckSide.class);
        for (AntiStuckSide side : AntiStuckSide.values()) {
            sides.put(side, side == clear ? clearStatus
                    : AntiStuckRecoveryEvidence.SideStatus.BLOCKED);
        }
        return sides;
    }

    private static final class RecordingRandom implements AntiStuckRandom {
        private final Queue<Integer> values = new ArrayDeque<>();
        private final List<List<Integer>> calls = new java.util.ArrayList<>();
        private final boolean maximum;

        private RecordingRandom() {
            this(false);
        }

        private RecordingRandom(boolean maximum) {
            this.maximum = maximum;
        }

        static RecordingRandom minimums() {
            return new RecordingRandom(false);
        }

        static RecordingRandom maximums() {
            return new RecordingRandom(true);
        }

        @Override
        public int nextInt(int originInclusive, int boundExclusive) {
            calls.add(List.of(originInclusive, boundExclusive));
            return values.isEmpty()
                    ? (maximum ? boundExclusive - 1 : originInclusive)
                    : values.remove();
        }

        List<List<Integer>> calls() {
            return List.copyOf(calls);
        }
    }
}
