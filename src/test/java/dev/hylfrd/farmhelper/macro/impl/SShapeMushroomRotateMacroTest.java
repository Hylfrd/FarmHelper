package dev.hylfrd.farmhelper.macro.impl;

import dev.hylfrd.farmhelper.control.input.InputAction;
import dev.hylfrd.farmhelper.control.rotation.RotationProfile;
import dev.hylfrd.farmhelper.control.rotation.RotationTerminalReason;
import dev.hylfrd.farmhelper.macro.FarmingContext;
import dev.hylfrd.farmhelper.macro.MacroDecision;
import dev.hylfrd.farmhelper.macro.MacroMode;
import dev.hylfrd.farmhelper.macro.MacroPauseCause;
import dev.hylfrd.farmhelper.macro.MacroRecoveryReason;
import dev.hylfrd.farmhelper.macro.MacroRotationLeaseState;
import dev.hylfrd.farmhelper.macro.MacroRotationRequest;
import dev.hylfrd.farmhelper.macro.MacroSettings;
import dev.hylfrd.farmhelper.macro.PlayerPosture;
import dev.hylfrd.farmhelper.macro.ServerResponsiveness;
import dev.hylfrd.farmhelper.runtime.snapshot.MotionSnapshot;
import dev.hylfrd.farmhelper.runtime.snapshot.Observation;
import dev.hylfrd.farmhelper.runtime.snapshot.PlayerSnapshot;
import dev.hylfrd.farmhelper.runtime.snapshot.PositionSnapshot;
import dev.hylfrd.farmhelper.runtime.snapshot.ResourceIdentifier;
import dev.hylfrd.farmhelper.runtime.snapshot.RotationSnapshot;
import dev.hylfrd.farmhelper.runtime.spatial.BlockPosition;
import dev.hylfrd.farmhelper.runtime.spatial.BlockStateSnapshot;
import dev.hylfrd.farmhelper.runtime.spatial.BoxSnapshot;
import dev.hylfrd.farmhelper.runtime.spatial.ChunkPosition;
import dev.hylfrd.farmhelper.runtime.spatial.ChunkSnapshot;
import dev.hylfrd.farmhelper.runtime.spatial.CollisionShapeSnapshot;
import dev.hylfrd.farmhelper.runtime.spatial.RelativeFrame;
import dev.hylfrd.farmhelper.runtime.spatial.RewarpPosition;
import dev.hylfrd.farmhelper.runtime.spatial.SpatialCaptureRequest;
import dev.hylfrd.farmhelper.runtime.spatial.SpatialSnapshot;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SShapeMushroomRotateMacroTest {
    private static final long EPOCH = 23L;
    private static final PositionSnapshot START = new PositionSnapshot(0.5D, 1.0D, 0.5D);
    private static final MotionSnapshot STILL = new MotionSnapshot(0.0D, 0.0D, 0.0D);

    @Test
    void startupUsesPitchJitterDurationBackProfileAndSeparateEntropy() {
        QueueRandom leaf = new QueueRandom(0.0D, 0.0D, 0.0D);
        QueueRandom entropy = new QueueRandom(0.0D, 0.0D);
        SShapeMushroomRotateMacro macro = new SShapeMushroomRotateMacro(
                validSettings(false), leaf, entropy);
        macro.onStart();

        MacroDecision decision = step(macro, 0L, START, 0.0F, 0.0F,
                STILL, grounded(), Map.of());
        MacroRotationRequest request = decision.rotation().orElseThrow();

        assertEquals(SShapeMushroomRotateMacro.State.NONE, macro.state());
        assertEquals(0.0F, macro.storedYaw());
        assertEquals(0.0F, macro.cardinalYaw());
        assertEquals(-1.0F, macro.storedPitch().orElseThrow());
        assertEquals(RotationProfile.BACK, request.profile());
        assertEquals(28.0F, request.yaw());
        assertEquals(-1.0F, request.pitch());
        assertEquals(385L, request.durationMillis());
        assertEquals(3, leaf.draws(), "pitch, yaw jitter, duration");
        assertEquals(2, entropy.draws(), "modifier and floor stay on independent entropy");
        assertTrue(decision.inputs().isEmpty());
    }

    @Test
    void customPitchYawSnapshotAndDontFixSuppressStartupWithoutAnyDraw() {
        MacroSettings source = validSettings(false);
        source.customYaw(true);
        source.customYawLevel(90.0F);
        source.customPitch(true);
        source.customPitchLevel(12.0F);
        source.dontFixAfterWarping(true);
        QueueRandom leaf = new QueueRandom();
        QueueRandom entropy = new QueueRandom();
        SShapeMushroomRotateMacro macro = new SShapeMushroomRotateMacro(source, leaf, entropy);
        source.customYawLevel(-90.0F);
        source.customPitchLevel(-12.0F);
        macro.onStart();

        MacroDecision decision = step(macro, 0L, START, 90.0F, 12.0F,
                STILL, grounded(), Map.of());

        assertEquals("startup-fix-suppressed", decision.status());
        assertTrue(decision.rotation().isEmpty());
        assertEquals(90.0F, macro.storedYaw());
        assertEquals(90.0F, macro.cardinalYaw());
        assertEquals(12.0F, macro.storedPitch().orElseThrow());
        assertEquals(0, leaf.draws());
        assertEquals(0, entropy.draws());
    }

    @Test
    void repairedCardinalSeamsMapZeroAndThreeFifteenWithoutWrapDrift() {
        assertStartupAngles(0.0F, 0.0F, 0.0F, 30.0F);
        assertStartupAngles(315.0F, 0.0F, 0.0F, 30.0F);
        assertStartupAngles(-1.0F, 0.0F, 0.0F, 30.0F);
        assertStartupAngles(45.0F, 90.0F, 90.0F, 120.0F);
        assertStartupAngles(180.0F, -180.0F, -180.0F, -150.0F);
    }

    @Test
    void mushroomSlotsKindsCompatibilityAndRightPriorityAreExact() {
        for (int up = 1; up <= 3; up++) {
            for (String kind : List.of("red_mushroom", "brown_mushroom")) {
                SShapeMushroomRotateMacro macro = macro(validSettings(false), zeros(20), zeros(20));
                MacroDecision selected = finishStartupWithTargets(macro, 0.0F, Map.of(
                        target(0.0F, true, up), crop(kind),
                        target(0.0F, false, up), crop("red_mushroom")));
                assertEquals(SShapeMushroomRotateMacro.State.RIGHT, macro.state(), kind + " y+" + up);
                assertEquals(28.0F, selected.rotation().orElseThrow().yaw());
                assertEquals(macro.storedPitch().orElseThrow(),
                        selected.rotation().orElseThrow().pitch());
            }
        }

        SShapeMushroomRotateMacro compatibleBehindNearest = macro(
                validSettings(false), zeros(20), zeros(20));
        MacroDecision selected = finishStartupWithTargets(compatibleBehindNearest, 0.0F, Map.of(
                target(0.0F, true, 1), crop("melon"),
                target(0.0F, true, 2), crop("brown_mushroom")));
        assertEquals(SShapeMushroomRotateMacro.State.RIGHT, compatibleBehindNearest.state());
        assertTrue(selected.rotation().isPresent());

        SShapeMushroomRotateMacro stems = macro(validSettings(false), zeros(20), zeros(20));
        MacroDecision absent = finishStartupWithTargets(stems, 0.0F, Map.of(
                target(0.0F, true, 1), crop("melon_stem"),
                target(0.0F, false, 1), crop("melon")));
        assertEquals("mushroom-target-absent", absent.status());
        assertEquals(SShapeMushroomRotateMacro.State.NONE, stems.state());
    }

    @Test
    void walkabilityGatesCropBeforeUnknownTargetAndUnknownEvidenceFailsClosed() {
        SShapeMushroomRotateMacro blockedRight = macro(
                validSettings(false), zeros(20), zeros(20));
        MacroDecision left = finishStartupWithObservations(blockedRight, 0.0F, Map.of(
                target(0.0F, true, 1), Observation.unknown(),
                sideBodyBlock(START, 0.0F, true, 1), Observation.present(full()),
                target(0.0F, false, 1), Observation.present(crop("brown_mushroom"))));
        assertEquals(SShapeMushroomRotateMacro.State.LEFT, blockedRight.state());
        assertEquals(-32.0F, left.rotation().orElseThrow().yaw());

        SShapeMushroomRotateMacro unknownRight = macro(
                validSettings(false), zeros(20), zeros(20));
        MacroDecision unknown = finishStartupWithObservations(unknownRight, 0.0F, Map.of(
                sideBodyBlock(START, 0.0F, true, 1), Observation.unknown(),
                target(0.0F, true, 1), Observation.present(crop("red_mushroom"))));
        assertEquals("right-mushroom-ready-unknown", unknown.status());
        assertEquals(SShapeMushroomRotateMacro.State.NONE, unknownRight.state());
        assertTrue(unknown.rotation().isEmpty());
    }

    @Test
    void directionScanIsRightFirstFromOneThroughOneSeventyNine() {
        SShapeMushroomRotateMacro rightBlocked = scanningMacro();
        MacroDecision left = step(rightBlocked, 2L, START, 30.0F, -1.0F,
                STILL, grounded(), Map.of(sideBodyBlock(START, 0.0F, true, 1), full()));
        assertEquals(SShapeMushroomRotateMacro.State.LEFT, rightBlocked.state());
        assertEquals(-32.0F, left.rotation().orElseThrow().yaw());

        SShapeMushroomRotateMacro exhausted = scanningMacro();
        long now = 2L;
        MacroDecision decision = MacroDecision.idle("not-run");
        while (exhausted.state() == SShapeMushroomRotateMacro.State.NONE) {
            decision = step(exhausted, now++, START, 30.0F, -1.0F,
                    STILL, grounded(), Map.of());
            if ("direction-scan-budget-exhausted".equals(decision.status())) {
                break;
            }
        }
        assertEquals("direction-scan-budget-exhausted", decision.status());
        assertEquals(SShapeMushroomRotateMacro.ScanPhase.LEFT_OBSTACLE, exhausted.scanPhase());
        assertEquals(179, exhausted.scanDistance());
        assertTrue(decision.inputs().isEmpty());
        assertTrue(decision.rotation().isEmpty());
    }

    @Test
    void bothLanesUseOnlyForwardAndAttackAfterOwnedRotationCompletes() {
        for (SShapeMushroomRotateMacro.State lane : List.of(
                SShapeMushroomRotateMacro.State.LEFT,
                SShapeMushroomRotateMacro.State.RIGHT)) {
            SShapeMushroomRotateMacro macro = macro(
                    validSettings(false), zeros(20), zeros(20));
            MacroDecision aligning = chooseLane(macro, lane, 0.0F, START);
            MacroRotationRequest request = aligning.rotation().orElseThrow();
            MacroDecision farming = step(macro, 2L, START, request.yaw(), request.pitch(),
                    STILL, grounded(), blockedSides(START, 0.0F),
                    MacroRotationLeaseState.terminal(
                            request.requestToken(), RotationTerminalReason.COMPLETED, 2L));
            assertEquals(Set.of(InputAction.FORWARD, InputAction.ATTACK), farming.inputs());
            assertTrue(farming.rotation().isEmpty());
        }
    }

    @Test
    void transitionTableUsesWalkabilityAndRepairedPitchFirstDrawOrder() {
        assertTransition(SShapeMushroomRotateMacro.State.LEFT, false, false,
                SShapeMushroomRotateMacro.State.RIGHT, 28.0F);
        assertTransition(SShapeMushroomRotateMacro.State.LEFT, false, true,
                SShapeMushroomRotateMacro.State.LEFT, -32.0F);
        assertTransition(SShapeMushroomRotateMacro.State.LEFT, true, true,
                SShapeMushroomRotateMacro.State.NONE, null);
        assertTransition(SShapeMushroomRotateMacro.State.RIGHT, false, false,
                SShapeMushroomRotateMacro.State.LEFT, -32.0F);
        assertTransition(SShapeMushroomRotateMacro.State.RIGHT, true, false,
                SShapeMushroomRotateMacro.State.RIGHT, 28.0F);
        assertTransition(SShapeMushroomRotateMacro.State.RIGHT, true, true,
                SShapeMushroomRotateMacro.State.NONE, null);

        QueueRandom leaf = new QueueRandom(
                0.5D, 0.5D, 0.0D, // startup pitch, jitter, duration
                0.5D, 0.0D,       // NONE lane jitter, duration
                0.75D, 0.25D, 0.5D); // update pitch, jitter, duration
        QueueRandom entropy = zeros(6);
        SShapeMushroomRotateMacro macro = macro(validSettings(false), leaf, entropy);
        MacroDecision lane = chooseLane(macro, SShapeMushroomRotateMacro.State.LEFT, 0.0F, START);
        acknowledge(macro, lane.rotation().orElseThrow(), 2L, START, blockedSides(START, 0.0F));
        MacroDecision update = step(macro, PROGRESS_AT, START, -30.0F, 0.0F,
                STILL, grounded(), Map.of());
        MacroRotationRequest request = update.rotation().orElseThrow();
        assertEquals(0.5F, macro.storedPitch().orElseThrow());
        assertEquals(0.5F, request.pitch(), "same repaired pitch must be saved and requested");
        assertEquals(29.0F, request.yaw());
        assertEquals(8, leaf.draws());
        assertEquals(6, entropy.draws());
    }

    @Test
    void noDirectionUpdateConsumesOnlyPitchAndNoYawDurationOrEntropy() {
        QueueRandom leaf = new QueueRandom(
                0.5D, 0.5D, 0.0D,
                0.5D, 0.0D,
                0.75D);
        QueueRandom entropy = zeros(4);
        SShapeMushroomRotateMacro macro = macro(validSettings(false), leaf, entropy);
        MacroDecision lane = chooseLane(macro, SShapeMushroomRotateMacro.State.LEFT, 0.0F, START);
        acknowledge(macro, lane.rotation().orElseThrow(), 2L, START, blockedSides(START, 0.0F));

        MacroDecision noDirection = step(macro, PROGRESS_AT, START, -30.0F, 0.0F,
                STILL, grounded(), blockedSides(START, 0.0F));

        assertEquals(SShapeMushroomRotateMacro.State.NONE, macro.state());
        assertEquals("mushroom-direction-recalculate", noDirection.status());
        assertEquals(0.5F, macro.storedPitch().orElseThrow());
        assertTrue(noDirection.rotation().isEmpty());
        assertEquals(6, leaf.draws(), "only the repaired pitch is consumed");
        assertEquals(4, entropy.draws(), "no row request means no rotation entropy");
    }

    @Test
    void dropUsesCapturedPreviousLaneAndExactThresholds() {
        assertDropTarget(SShapeMushroomRotateMacro.State.LEFT, 148.0F);
        assertDropTarget(SShapeMushroomRotateMacro.State.RIGHT, -152.0F);

        MacroSettings settings = validSettings(true);
        SShapeMushroomRotateMacro threshold = macro(settings, zeros(30), zeros(30));
        MacroDecision lane = chooseLane(threshold, SShapeMushroomRotateMacro.State.RIGHT, 0.0F, START);
        acknowledge(threshold, lane.rotation().orElseThrow(), 2L, START, blockedSides(START, 0.0F));
        MacroDecision exact = step(threshold, 3L,
                new PositionSnapshot(0.5D, 0.25D, 0.5D), 30.0F, -1.0F,
                STILL, airborne(), Map.of());
        assertFalse(exact.status().equals("dropping"), "exact 0.75 is not a drop");

        SShapeMushroomRotateMacro flyingMacro = macro(settings, zeros(30), zeros(30));
        MacroDecision flyingLane = chooseLane(
                flyingMacro, SShapeMushroomRotateMacro.State.RIGHT, 0.0F, START);
        acknowledge(flyingMacro, flyingLane.rotation().orElseThrow(), 2L,
                START, blockedSides(START, 0.0F));
        MacroDecision flyingDecision = step(flyingMacro, 3L,
                new PositionSnapshot(0.5D, 0.0D, 0.5D), 30.0F, -1.0F,
                STILL, flying(), Map.of());
        assertFalse(flyingDecision.status().equals("dropping"));

        SShapeMushroomRotateMacro shallow = macro(settings, zeros(30), zeros(30));
        MacroDecision shallowLane = chooseLane(
                shallow, SShapeMushroomRotateMacro.State.RIGHT, 0.0F, START);
        acknowledge(shallow, shallowLane.rotation().orElseThrow(), 2L,
                START, blockedSides(START, 0.0F));
        step(shallow, 3L, new PositionSnapshot(0.5D, 0.0D, 0.5D),
                30.0F, -1.0F, STILL, airborne(), Map.of());
        MacroDecision exactLanding = step(shallow, 4L,
                new PositionSnapshot(0.5D, -0.5D, 0.5D), 30.0F, -1.0F,
                STILL, grounded(), Map.of());
        assertEquals("drop-too-shallow", exactLanding.status(),
                "exact 1.5 landing delta must not rotate");
        assertTrue(exactLanding.rotation().isEmpty());

        PositionSnapshot high = new PositionSnapshot(0.5D, 81.0D, 0.5D);
        SShapeMushroomRotateMacro yEighty = macro(settings, zeros(30), zeros(30));
        MacroDecision highLane = chooseLane(
                yEighty, SShapeMushroomRotateMacro.State.RIGHT, 0.0F, high);
        acknowledge(yEighty, highLane.rotation().orElseThrow(), 2L,
                high, blockedSides(high, 0.0F));
        MacroDecision atEighty = step(yEighty, 3L,
                new PositionSnapshot(0.5D, 80.0D, 0.5D), 30.0F, -1.0F,
                STILL, airborne(), Map.of());
        assertFalse(atEighty.status().equals("dropping"), "y=80 is excluded");
    }

    @Test
    void rewarpAlwaysRefreshesPitchButNeverCreatesPostWarpRotation() {
        for (boolean configuredRotate : List.of(false, true)) {
            MacroSettings settings = rewarpSettings(configuredRotate);
            QueueRandom leaf = configuredRotate
                    ? new QueueRandom(
                            0.5D, 0.5D, 0.0D, // startup
                            0.0D,             // dwell 400
                            0.75D,            // actionAfterTeleport pitch 0.5
                            0.25D)            // doAfterRewarpRotation pitch -0.5
                    : new QueueRandom(
                            0.5D, 0.5D, 0.0D, // startup
                            0.0D,             // dwell 400
                            0.75D);           // actionAfterTeleport pitch 0.5
            QueueRandom entropy = zeros(2);
            SShapeMushroomRotateMacro macro = macro(settings, leaf, entropy);
            assertFalse(macro.shouldRotateAfterWarp());

            PositionSnapshot origin = new PositionSnapshot(2.5D, 1.0D, 0.5D);
            MacroDecision startup = step(macro, 0L, origin, 0.0F, 0.0F,
                    STILL, grounded(), Map.of());
            assertTrue(startup.rotation().isPresent());
            assertEquals("rewarp-dwell", step(macro, 1L, origin, 30.0F, 0.0F,
                    STILL, grounded(), Map.of()).status());
            long warpAt = 1L + TimeUnit.MILLISECONDS.toNanos(400L);
            assertTrue(step(macro, warpAt, origin, 30.0F, 0.0F,
                    STILL, grounded(), Map.of()).warp().isPresent());
            PositionSnapshot spawn = new PositionSnapshot(10.5D, 1.0D, 0.5D);
            long landedAt = warpAt + 1L;
            MacroDecision landed = step(macro, landedAt, spawn, 30.0F, 0.0F,
                    STILL, grounded(), Map.of());
            assertEquals("rewarp-confirmed-plot--1", landed.status());
            assertEquals(0.5F, macro.storedPitch().orElseThrow());
            long postAt = landedAt + TimeUnit.MILLISECONDS.toNanos(1_500L);
            step(macro, postAt, spawn, 30.0F, 0.0F, STILL, grounded(), Map.of());
            long finishAt = postAt + TimeUnit.MILLISECONDS.toNanos(600L);
            MacroDecision finished = step(macro, finishAt, spawn, 30.0F, 0.0F,
                    STILL, grounded(), Map.of());

            assertEquals(configuredRotate
                    ? "post-rewarp-rotate-disabled" : "post-rewarp-complete", finished.status());
            assertTrue(finished.rotation().isEmpty());
            assertEquals(configuredRotate ? -0.5F : 0.5F,
                    macro.storedPitch().orElseThrow());
            assertEquals(configuredRotate ? 6 : 5, leaf.draws(),
                    "only upstream pitch refresh is allowed; no yaw or duration draw");
            assertEquals(2, entropy.draws(), "only startup rotation consumes entropy");
            assertEquals(SShapeMushroomRotateMacro.State.NONE, macro.state());
        }
    }

    @Test
    void pauseStaleCaptureAndStaleOrCancelledRotationAcknowledgementsFailClosedWithoutRedraw() {
        QueueRandom leaf = zeros(12);
        QueueRandom entropy = zeros(4);
        SShapeMushroomRotateMacro macro = macro(validSettings(false), leaf, entropy);
        MacroDecision startup = step(macro, 0L, START, 0.0F, 0.0F,
                STILL, grounded(), Map.of());
        MacroRotationRequest pending = startup.rotation().orElseThrow();
        int leafDraws = leaf.draws();
        int entropyDraws = entropy.draws();

        PlayerSnapshot player = player(START, 0.0F, 0.0F, STILL);
        SpatialCaptureRequest beforePause = macro.spatialRequest(player, EPOCH).orElseThrow();
        macro.onPause(Set.of(MacroPauseCause.SCREEN_OPEN), 1L);
        macro.onResume(TimeUnit.SECONDS.toNanos(1L));
        SpatialSnapshot stale = captured(beforePause, START, Map.of());
        MacroDecision rejected = macro.tick(context(
                TimeUnit.SECONDS.toNanos(1L), player, stale, grounded(),
                MacroRotationLeaseState.idle(0L)));
        assertEquals("spatial-unknown-or-stale", rejected.status());

        MacroDecision staleAck = step(macro, TimeUnit.SECONDS.toNanos(1L) + 1L,
                START, 0.0F, 0.0F, STILL, grounded(), Map.of(),
                MacroRotationLeaseState.active(pending.requestToken() + 1L, false, 1L));
        assertEquals("rotation-acknowledgement-stale", staleAck.status());
        MacroDecision retry = step(macro, TimeUnit.SECONDS.toNanos(1L) + 2L,
                START, 0.0F, 0.0F, STILL, grounded(), Map.of(),
                MacroRotationLeaseState.terminal(
                        pending.requestToken(), RotationTerminalReason.OWNER_CANCELLED, 2L));
        assertEquals(pending, retry.rotation().orElseThrow());
        assertEquals(leafDraws, leaf.draws());
        assertEquals(entropyDraws, entropy.draws());
    }

    @Test
    void repeatedUnknownLaneEvidenceHandsOffRecoveryAndTerminalLifecycleReleasesEverything() {
        SShapeMushroomRotateMacro macro = macro(validSettings(false), zeros(30), zeros(30));
        MacroDecision lane = chooseLane(macro, SShapeMushroomRotateMacro.State.RIGHT, 0.0F, START);
        acknowledge(macro, lane.rotation().orElseThrow(), 2L, START, blockedSides(START, 0.0F));
        long window = TimeUnit.MILLISECONDS.toNanos(500L);
        Map<BlockPosition, Observation<BlockStateSnapshot>> unknownLeft = Map.of(
                sideBodyBlock(START, 0.0F, false, 1), Observation.unknown());
        assertEquals("mushroom-lane-unknown", stepObservations(macro, window + 2L,
                START, 30.0F, -1.0F, STILL, grounded(), unknownLeft).status());
        assertEquals("mushroom-lane-unknown", stepObservations(macro, window * 2L + 2L,
                START, 30.0F, -1.0F, STILL, grounded(), unknownLeft).status());
        MacroDecision recovery = stepObservations(macro, window * 3L + 2L,
                START, 30.0F, -1.0F, STILL, grounded(), unknownLeft);
        assertEquals(MacroRecoveryReason.ROW_STALLED,
                recovery.recovery().orElseThrow().reason());
        assertEquals(SShapeMushroomRotateMacro.State.RECOVERY_HANDOFF, macro.state());
        assertTrue(recovery.inputs().isEmpty());
        assertTrue(recovery.rotation().isEmpty());

        macro.onStop();
        assertEquals(SShapeMushroomRotateMacro.State.STOPPED, macro.state());
        assertTrue(macro.pendingRotation().isEmpty());
        assertTrue(macro.previousLane().isEmpty());
        assertTrue(macro.spatialRequest(player(START, 0.0F, 0.0F, STILL), EPOCH).isEmpty());
    }

    private static final long PROGRESS_AT = TimeUnit.MILLISECONDS.toNanos(500L) + 2L;

    private static void assertStartupAngles(
            float observedYaw,
            float expectedStored,
            float expectedCardinal,
            float expectedTarget
    ) {
        SShapeMushroomRotateMacro macro = macro(validSettings(false),
                new QueueRandom(0.5D, 0.5D, 0.0D), zeros(2));
        MacroDecision startup = step(macro, 0L, START, observedYaw, 0.0F,
                STILL, grounded(), Map.of());
        assertEquals(expectedStored, macro.storedYaw(), "stored yaw for " + observedYaw);
        assertEquals(expectedCardinal, macro.cardinalYaw(), "cardinal for " + observedYaw);
        assertEquals(expectedTarget, startup.rotation().orElseThrow().yaw(),
                "target for " + observedYaw);
    }

    private static void assertTransition(
            SShapeMushroomRotateMacro.State initial,
            boolean leftBlocked,
            boolean rightBlocked,
            SShapeMushroomRotateMacro.State expected,
            Float expectedYaw
    ) {
        SShapeMushroomRotateMacro macro = macro(validSettings(false), zeros(30), zeros(30));
        MacroDecision lane = chooseLane(macro, initial, 0.0F, START);
        acknowledge(macro, lane.rotation().orElseThrow(), 2L, START, blockedSides(START, 0.0F));
        Map<BlockPosition, BlockStateSnapshot> overrides = new HashMap<>();
        if (leftBlocked) {
            overrides.put(sideBodyBlock(START, 0.0F, false, 1), full());
        }
        if (rightBlocked) {
            overrides.put(sideBodyBlock(START, 0.0F, true, 1), full());
        }
        MacroDecision decision = step(macro, PROGRESS_AT, START,
                initial == SShapeMushroomRotateMacro.State.LEFT ? -30.0F : 30.0F,
                -1.0F, STILL, grounded(), overrides);
        assertEquals(expected, macro.state(), initial + " " + leftBlocked + "/" + rightBlocked);
        if (expectedYaw == null) {
            assertTrue(decision.rotation().isEmpty());
        } else {
            assertEquals(expectedYaw, decision.rotation().orElseThrow().yaw());
        }
    }

    private static void assertDropTarget(
            SShapeMushroomRotateMacro.State lane,
            float expectedYaw
    ) {
        MacroSettings settings = validSettings(true);
        QueueRandom leaf = zeros(40);
        SShapeMushroomRotateMacro macro = macro(settings, leaf, zeros(20));
        MacroDecision selected = chooseLane(macro, lane, 0.0F, START);
        MacroRotationRequest row = selected.rotation().orElseThrow();
        acknowledge(macro, row, 2L, START, blockedSides(START, 0.0F));

        PositionSnapshot falling = new PositionSnapshot(0.5D, 0.0D, 0.5D);
        MacroDecision dropping = step(macro, 3L, falling, row.yaw(), row.pitch(),
                STILL, airborne(), Map.of());
        assertEquals("dropping", dropping.status());
        assertEquals(lane, macro.previousLane().orElseThrow());
        PositionSnapshot landed = new PositionSnapshot(0.5D, -1.0D, 0.5D);
        MacroDecision rotated = step(macro, 4L, landed, row.yaw(), row.pitch(),
                STILL, grounded(), Map.of());
        assertEquals(expectedYaw, rotated.rotation().orElseThrow().yaw());
        assertEquals(row.pitch(), rotated.rotation().orElseThrow().pitch());
        assertTrue(macro.previousLane().isEmpty());
    }

    private static SShapeMushroomRotateMacro scanningMacro() {
        SShapeMushroomRotateMacro macro = macro(validSettings(false), zeros(1000), zeros(1000));
        MacroDecision startup = step(macro, 0L, START, 0.0F, 0.0F,
                STILL, grounded(), Map.of());
        step(macro, 1L, START, startup.rotation().orElseThrow().yaw(),
                startup.rotation().orElseThrow().pitch(), STILL, grounded(), Map.of(),
                MacroRotationLeaseState.terminal(
                        startup.rotation().orElseThrow().requestToken(),
                        RotationTerminalReason.COMPLETED, 1L));
        assertEquals(SShapeMushroomRotateMacro.ScanPhase.RIGHT_OBSTACLE, macro.scanPhase());
        return macro;
    }

    private static MacroDecision chooseLane(
            SShapeMushroomRotateMacro macro,
            SShapeMushroomRotateMacro.State lane,
            float observedYaw,
            PositionSnapshot position
    ) {
        MacroDecision startup = step(macro, 0L, position, observedYaw, 0.0F,
                STILL, grounded(), Map.of());
        MacroRotationRequest request = startup.rotation().orElseThrow();
        return step(macro, 1L, position, request.yaw(), request.pitch(),
                STILL, grounded(), Map.of(
                        target(position, macro.cardinalYaw(),
                                lane == SShapeMushroomRotateMacro.State.RIGHT, 1),
                        crop("red_mushroom")),
                MacroRotationLeaseState.terminal(
                        request.requestToken(), RotationTerminalReason.COMPLETED, 1L));
    }

    private static MacroDecision acknowledge(
            SShapeMushroomRotateMacro macro,
            MacroRotationRequest request,
            long now,
            PositionSnapshot position,
            Map<BlockPosition, BlockStateSnapshot> overrides
    ) {
        return step(macro, now, position, request.yaw(), request.pitch(),
                STILL, grounded(), overrides,
                MacroRotationLeaseState.terminal(
                        request.requestToken(), RotationTerminalReason.COMPLETED, now));
    }

    private static MacroDecision finishStartupWithTargets(
            SShapeMushroomRotateMacro macro,
            float observedYaw,
            Map<BlockPosition, BlockStateSnapshot> overrides
    ) {
        Map<BlockPosition, Observation<BlockStateSnapshot>> observations = new HashMap<>();
        overrides.forEach((position, state) -> observations.put(position, Observation.present(state)));
        return finishStartupWithObservations(macro, observedYaw, observations);
    }

    private static MacroDecision finishStartupWithObservations(
            SShapeMushroomRotateMacro macro,
            float observedYaw,
            Map<BlockPosition, Observation<BlockStateSnapshot>> overrides
    ) {
        MacroDecision startup = step(macro, 0L, START, observedYaw, 0.0F,
                STILL, grounded(), Map.of());
        MacroRotationRequest request = startup.rotation().orElseThrow();
        return stepObservations(macro, 1L, START, request.yaw(), request.pitch(),
                STILL, grounded(), overrides,
                MacroRotationLeaseState.terminal(
                        request.requestToken(), RotationTerminalReason.COMPLETED, 1L));
    }

    private static SShapeMushroomRotateMacro macro(
            MacroSettings settings,
            QueueRandom leaf,
            QueueRandom entropy
    ) {
        SShapeMushroomRotateMacro macro = new SShapeMushroomRotateMacro(settings, leaf, entropy);
        macro.onStart();
        return macro;
    }

    private static MacroSettings validSettings(boolean rotateAfterDrop) {
        MacroSettings settings = new MacroSettings();
        settings.macroMode(MacroMode.MUSHROOM_ROTATE);
        settings.rotateAfterDrop(rotateAfterDrop);
        settings.spawn(new RewarpPosition(100, 70, 100));
        assertTrue(settings.addRewarp(new RewarpPosition(90, 70, 90)));
        return settings;
    }

    private static MacroSettings rewarpSettings(boolean rotateAfterWarped) {
        MacroSettings settings = new MacroSettings();
        settings.macroMode(MacroMode.MUSHROOM_ROTATE);
        settings.rotateAfterWarped(rotateAfterWarped);
        settings.spawn(new RewarpPosition(10, 1, 0));
        assertTrue(settings.addRewarp(new RewarpPosition(2, 1, 0)));
        return settings;
    }

    private static MacroDecision step(
            SShapeMushroomRotateMacro macro,
            long now,
            PositionSnapshot position,
            float yaw,
            float pitch,
            MotionSnapshot motion,
            PlayerPosture posture,
            Map<BlockPosition, BlockStateSnapshot> overrides
    ) {
        Map<BlockPosition, Observation<BlockStateSnapshot>> observations = new HashMap<>();
        overrides.forEach((key, value) -> observations.put(key, Observation.present(value)));
        MacroRotationLeaseState lease = macro.pendingRotation()
                .map(pending -> MacroRotationLeaseState.active(
                        pending.requestToken(), false, Math.max(now, 0L)))
                .orElseGet(() -> MacroRotationLeaseState.idle(Math.max(now, 0L)));
        return stepObservations(macro, now, position, yaw, pitch, motion, posture,
                observations, lease);
    }

    private static MacroDecision step(
            SShapeMushroomRotateMacro macro,
            long now,
            PositionSnapshot position,
            float yaw,
            float pitch,
            MotionSnapshot motion,
            PlayerPosture posture,
            Map<BlockPosition, BlockStateSnapshot> overrides,
            MacroRotationLeaseState lease
    ) {
        Map<BlockPosition, Observation<BlockStateSnapshot>> observations = new HashMap<>();
        overrides.forEach((key, value) -> observations.put(key, Observation.present(value)));
        return stepObservations(macro, now, position, yaw, pitch, motion, posture,
                observations, lease);
    }

    private static MacroDecision stepObservations(
            SShapeMushroomRotateMacro macro,
            long now,
            PositionSnapshot position,
            float yaw,
            float pitch,
            MotionSnapshot motion,
            PlayerPosture posture,
            Map<BlockPosition, Observation<BlockStateSnapshot>> overrides
    ) {
        MacroRotationLeaseState lease = macro.pendingRotation()
                .map(pending -> MacroRotationLeaseState.active(
                        pending.requestToken(), false, Math.max(now, 0L)))
                .orElseGet(() -> MacroRotationLeaseState.idle(Math.max(now, 0L)));
        return stepObservations(macro, now, position, yaw, pitch, motion, posture,
                overrides, lease);
    }

    private static MacroDecision stepObservations(
            SShapeMushroomRotateMacro macro,
            long now,
            PositionSnapshot position,
            float yaw,
            float pitch,
            MotionSnapshot motion,
            PlayerPosture posture,
            Map<BlockPosition, Observation<BlockStateSnapshot>> overrides,
            MacroRotationLeaseState lease
    ) {
        PlayerSnapshot player = player(position, yaw, pitch, motion);
        SpatialCaptureRequest request = macro.spatialRequest(player, EPOCH).orElseThrow();
        return macro.tick(context(now, player, captured(request, position, overrides), posture, lease));
    }

    private static FarmingContext context(
            long now,
            PlayerSnapshot player,
            SpatialSnapshot spatial,
            PlayerPosture posture,
            MacroRotationLeaseState lease
    ) {
        return new FarmingContext(
                now, EPOCH, Observation.present(player), Observation.present(spatial),
                Observation.present(true), false, ServerResponsiveness.RESPONSIVE,
                Observation.present(posture), Observation.present(lease));
    }

    private static PlayerSnapshot player(
            PositionSnapshot position,
            float yaw,
            float pitch,
            MotionSnapshot motion
    ) {
        return new PlayerSnapshot(
                Observation.present(position), Observation.present(motion),
                Observation.present(new RotationSnapshot(yaw, pitch)),
                Observation.unknown(), Observation.unknown());
    }

    private static SpatialSnapshot captured(
            SpatialCaptureRequest request,
            PositionSnapshot player,
            Map<BlockPosition, Observation<BlockStateSnapshot>> overrides
    ) {
        int groundY = (int) Math.floor(player.y()) - 1;
        Map<ChunkPosition, Map<BlockPosition, Observation<BlockStateSnapshot>>> grouped =
                new HashMap<>();
        for (BlockPosition position : request.blocks()) {
            Observation<BlockStateSnapshot> value = overrides.getOrDefault(
                    position, Observation.present(position.y() == groundY ? full() : air()));
            grouped.computeIfAbsent(position.chunk(), ignored -> new LinkedHashMap<>())
                    .put(position, value);
        }
        Map<ChunkPosition, ChunkSnapshot> chunks = new HashMap<>();
        grouped.forEach((position, values) -> chunks.put(
                position, new ChunkSnapshot(position, true, values)));
        return new SpatialSnapshot(
                request.worldEpoch(), request.requestToken(), request.bounds(), -64, 320,
                body(player), chunks);
    }

    private static BoxSnapshot body(PositionSnapshot player) {
        return new BoxSnapshot(
                player.x() - 0.3D, player.y(), player.z() - 0.3D,
                player.x() + 0.3D, player.y() + 1.8D, player.z() + 0.3D);
    }

    private static BlockPosition target(float cardinal, boolean right, int up) {
        return target(START, cardinal, right, up);
    }

    private static BlockPosition target(
            PositionSnapshot origin,
            float cardinal,
            boolean right,
            int up
    ) {
        return RelativeFrame.cardinal(cardinal).blockAt(
                origin.x(), origin.y(), origin.z(), right ? 1 : -1, up, 1);
    }

    private static BlockPosition sideBodyBlock(
            PositionSnapshot origin,
            float cardinal,
            boolean right,
            int distance
    ) {
        RelativeFrame frame = RelativeFrame.cardinal(cardinal);
        int sign = right ? 1 : -1;
        return new BlockPosition(
                (int) Math.floor(origin.x() + frame.rightX() * (double) sign * distance),
                (int) Math.floor(origin.y()),
                (int) Math.floor(origin.z() + frame.rightZ() * (double) sign * distance));
    }

    private static Map<BlockPosition, BlockStateSnapshot> blockedSides(
            PositionSnapshot origin,
            float cardinal
    ) {
        return Map.of(
                sideBodyBlock(origin, cardinal, false, 1), full(),
                sideBodyBlock(origin, cardinal, true, 1), full());
    }

    private static BlockStateSnapshot crop(String name) {
        return new BlockStateSnapshot(
                ResourceIdentifier.parse("minecraft:" + name), Map.of(),
                ResourceIdentifier.parse("minecraft:empty"),
                Observation.present(CollisionShapeSnapshot.EMPTY));
    }

    private static BlockStateSnapshot air() {
        return crop("air");
    }

    private static BlockStateSnapshot full() {
        return new BlockStateSnapshot(
                ResourceIdentifier.parse("minecraft:stone"), Map.of(),
                ResourceIdentifier.parse("minecraft:empty"),
                Observation.present(new CollisionShapeSnapshot(List.of(
                        new BoxSnapshot(0, 0, 0, 1, 1, 1)))));
    }

    private static PlayerPosture grounded() {
        return new PlayerPosture(false, true, false);
    }

    private static PlayerPosture airborne() {
        return new PlayerPosture(false, false, false);
    }

    private static PlayerPosture flying() {
        return new PlayerPosture(true, false, false);
    }

    private static QueueRandom zeros(int count) {
        return new QueueRandom(new double[count]);
    }

    private static final class QueueRandom implements dev.hylfrd.farmhelper.macro.MacroRandom {
        private final List<Double> values;
        private int index;

        private QueueRandom(double... values) {
            this.values = new ArrayList<>(values.length);
            for (double value : values) {
                this.values.add(value);
            }
        }

        @Override
        public double nextUnit() {
            if (index >= values.size()) {
                throw new AssertionError("unexpected random draw " + index);
            }
            return values.get(index++);
        }

        int draws() {
            return index;
        }
    }
}
