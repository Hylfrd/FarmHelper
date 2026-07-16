package dev.hylfrd.farmhelper.macro.impl;

import dev.hylfrd.farmhelper.control.input.InputAction;
import dev.hylfrd.farmhelper.control.rotation.RotationProfile;
import dev.hylfrd.farmhelper.macro.FarmingContext;
import dev.hylfrd.farmhelper.macro.LaneChangeDirection;
import dev.hylfrd.farmhelper.macro.MacroDecision;
import dev.hylfrd.farmhelper.macro.MacroMode;
import dev.hylfrd.farmhelper.macro.MacroRecoveryReason;
import dev.hylfrd.farmhelper.macro.MacroRotationLeaseState;
import dev.hylfrd.farmhelper.macro.MacroSettings;
import dev.hylfrd.farmhelper.macro.PlayerPosture;
import dev.hylfrd.farmhelper.macro.RowDirection;
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
import dev.hylfrd.farmhelper.runtime.spatial.RewarpPosition;
import dev.hylfrd.farmhelper.runtime.spatial.SpatialCaptureRequest;
import dev.hylfrd.farmhelper.runtime.spatial.SpatialSnapshot;
import dev.hylfrd.farmhelper.control.rotation.RotationTerminalReason;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SShapeMelonPumpkinDefaultMacroTest {
    private static final long EPOCH = 9L;
    private static final PositionSnapshot START = new PositionSnapshot(0.5D, 1.0D, 0.5D);
    private static final MotionSnapshot STILL = new MotionSnapshot(0.0D, 0.0D, 0.0D);

    @Test
    void defaultStartupYawUsesCurrentDiagonalThenCardinalForRightTarget() {
        QueueRandom leaf = new QueueRandom(0.0D, 0.0D, 0.0D);
        QueueRandom rotationEntropy = new QueueRandom(0.0D, 0.0D);
        SShapeMelonPumpkinDefaultMacro macro = new SShapeMelonPumpkinDefaultMacro(
                validSettings(), leaf, rotationEntropy);
        macro.onStart(0L);

        MacroDecision decision = step(macro, TimeUnit.MILLISECONDS.toNanos(300L),
                START, 0.0F, 0.0F,
                new MotionSnapshot(0.0D, 0.0D, 0.0D), grounded(),
                Map.of(new BlockPosition(0, 1, 0), Observation.present(melon())));

        assertEquals(RowDirection.RIGHT, macro.rowDirection().orElseThrow());
        assertEquals(SShapeMelonPumpkinDefaultMacro.State.FARMING_RIGHT, macro.state());
        assertEquals(3, leaf.draws());
        assertEquals(2, rotationEntropy.draws());
        var rotation = decision.rotation().orElseThrow();
        assertEquals(135.0F, rotation.yaw());
        assertEquals(47.0F, rotation.pitch());
        assertEquals(550L, rotation.durationMillis());
        assertEquals(RotationProfile.EXPO_QUART, rotation.profile());
        assertEquals(-0.25F, rotation.backModifier());
        assertTrue(decision.inputs().isEmpty());
    }

    @Test
    void customPitchAndYawUseConfiguredBaseWithExactJitter() {
        MacroSettings settings = validSettings();
        settings.customPitch(true);
        settings.customPitchLevel(52.0F);
        settings.customYaw(true);
        settings.customYawLevel(90.0F);
        QueueRandom leaf = new QueueRandom(0.5D, 0.0D);
        QueueRandom rotationEntropy = new QueueRandom(0.0D, 0.0D);
        SShapeMelonPumpkinDefaultMacro macro =
                new SShapeMelonPumpkinDefaultMacro(settings, leaf, rotationEntropy);
        macro.onStart(0L);

        assertEquals("startup", step(macro, TimeUnit.MILLISECONDS.toNanos(299L),
                START, 12.0F, 0.0F, STILL, grounded(), Map.of()).status());
        MacroDecision decision = step(macro, TimeUnit.MILLISECONDS.toNanos(300L),
                START, 12.0F, 0.0F, STILL, grounded(),
                Map.of(new BlockPosition(0, 1, 0), Observation.present(pumpkin())));

        assertEquals(2, leaf.draws());
        assertEquals(2, rotationEntropy.draws());
        assertEquals(136.0F, decision.rotation().orElseThrow().yaw());
        assertEquals(52.0F, decision.rotation().orElseThrow().pitch());
    }

    @Test
    void defaultStartupYawTracksTheObservedCurrentSector() {
        MacroSettings settings = validSettings();
        settings.customPitch(true);
        settings.customPitchLevel(52.0F);
        QueueRandom leaf = new QueueRandom(0.0D, 0.0D);
        QueueRandom rotationEntropy = new QueueRandom(0.0D, 0.0D);
        SShapeMelonPumpkinDefaultMacro macro =
                new SShapeMelonPumpkinDefaultMacro(settings, leaf, rotationEntropy);
        macro.onStart(0L);

        MacroDecision decision = step(macro, TimeUnit.MILLISECONDS.toNanos(300L),
                START, 179.0F, 0.0F, STILL, grounded(),
                Map.of(new BlockPosition(0, 1, 0), Observation.present(melon())));

        assertEquals(-135.0F, decision.rotation().orElseThrow().yaw());
        assertEquals(2, leaf.draws(), "side jitter then duration");
        assertEquals(2, rotationEntropy.draws());
    }

    @Test
    void scanReachesDistance179AndKeepsCropBeforeObstacleOrdering() {
        QueueRandom random = new QueueRandom(0.0D, 0.0D, 0.0D, 0.0D, 0.0D);
        SShapeMelonPumpkinDefaultMacro macro = macro(random);

        for (int distance = 0; distance < 179; distance++) {
            assertEquals(distance, macro.scanDistance());
            step(macro, distance * 4L, START, 0.0F, 0.0F,
                    STILL, grounded(), Map.of());
            step(macro, distance * 4L + 1L, START, 0.0F, 0.0F,
                    STILL, grounded(), Map.of());
            step(macro, distance * 4L + 2L, START, 0.0F, 0.0F,
                    STILL, grounded(), Map.of());
            step(macro, distance * 4L + 3L, START, 0.0F, 0.0F,
                    STILL, grounded(), Map.of());
        }
        assertEquals(179, macro.scanDistance());
        BlockPosition rightAt179 = new BlockPosition(-179, 1, 0);

        MacroDecision selected = step(macro, 1_000L, START, 0.0F, 0.0F,
                STILL, grounded(),
                Map.of(rightAt179, Observation.present(pumpkin())));

        assertEquals(RowDirection.RIGHT, macro.rowDirection().orElseThrow());
        assertTrue(selected.rotation().isPresent());
        assertEquals(5, random.draws());
    }

    @Test
    void stemsAndUnknownBlocksNeverBecomeFruitOrConsumeRandomness() {
        QueueRandom stemRandom = new QueueRandom();
        SShapeMelonPumpkinDefaultMacro stem = macro(stemRandom);
        MacroDecision stemDecision = step(stem, 0L, START, 0.0F, 0.0F,
                STILL, grounded(),
                Map.of(new BlockPosition(0, 1, 0), Observation.present(melonStem())));
        assertTrue(stemDecision.rotation().isEmpty());
        assertEquals(SShapeMelonPumpkinDefaultMacro.ScanPhase.LEFT_CROP, stem.scanPhase());
        assertEquals(0, stemRandom.draws());

        QueueRandom unknownRandom = new QueueRandom();
        QueueRandom unknownRotation = new QueueRandom();
        SShapeMelonPumpkinDefaultMacro unknown = new SShapeMelonPumpkinDefaultMacro(
                validSettings(), unknownRandom, unknownRotation);
        unknown.onStart();
        MacroDecision unknownDecision = step(unknown, 0L, START, 0.0F, 0.0F,
                STILL, grounded(),
                Map.of(new BlockPosition(0, 1, 0), Observation.unknown()));
        assertEquals("row-crop-scan-unknown", unknownDecision.status());
        assertEquals(SShapeMelonPumpkinDefaultMacro.ScanPhase.RIGHT_CROP, unknown.scanPhase());
        assertTrue(unknownDecision.rotation().isEmpty());
        assertEquals(0, unknownRandom.draws());
        assertEquals(0, unknownRotation.draws());
    }

    @Test
    void rightAndLeftObstaclePhasesChooseTheOppositeRow() {
        QueueRandom rightBlockedRandom = zeros(10);
        SShapeMelonPumpkinDefaultMacro rightBlocked = macro(rightBlockedRandom);
        step(rightBlocked, 0L, START, 0.0F, 0.0F, STILL, grounded(), Map.of());
        step(rightBlocked, 1L, START, 0.0F, 0.0F, STILL, grounded(), Map.of());
        MacroDecision choseLeft = step(rightBlocked, 2L, START, 0.0F, 0.0F,
                STILL, grounded(),
                Map.of(new BlockPosition(0, 1, 0), Observation.present(full())));
        assertEquals(RowDirection.LEFT, rightBlocked.rowDirection().orElseThrow());
        assertTrue(choseLeft.rotation().isPresent());

        QueueRandom leftBlockedRandom = zeros(10);
        SShapeMelonPumpkinDefaultMacro leftBlocked = macro(leftBlockedRandom);
        step(leftBlocked, 0L, START, 0.0F, 0.0F, STILL, grounded(), Map.of());
        step(leftBlocked, 1L, START, 0.0F, 0.0F, STILL, grounded(), Map.of());
        step(leftBlocked, 2L, START, 0.0F, 0.0F, STILL, grounded(), Map.of());
        MacroDecision choseRight = step(leftBlocked, 3L, START, 0.0F, 0.0F,
                STILL, grounded(),
                Map.of(new BlockPosition(0, 1, 0), Observation.present(full())));
        assertEquals(RowDirection.RIGHT, leftBlocked.rowDirection().orElseThrow());
        assertTrue(choseRight.rotation().isPresent());
    }

    @Test
    void cropScanUsesCurrentYawWhileObstacleFrameRemainsStoredCardinal() {
        QueueRandom random = zeros(10);
        SShapeMelonPumpkinDefaultMacro macro = macro(random);
        for (long now = 0L; now < 4L; now++) {
            step(macro, now, START, 90.0F, 0.0F, STILL, grounded(), Map.of());
        }

        MacroDecision decision = step(macro, 4L, START, 90.0F, 0.0F, STILL, grounded(),
                Map.of(new BlockPosition(0, 1, -1), Observation.present(melon())));

        assertEquals(RowDirection.RIGHT, macro.rowDirection().orElseThrow());
        assertTrue(decision.rotation().isPresent());
    }

    @Test
    void currentYawSectorUsesExactUpstreamSeamsThroughCapturedDistanceZeroAnd179() {
        List<SectorCase> sectors = List.of(
                new SectorCase(Math.nextDown(30.0F), new BlockPosition(-179, 1, 0)),
                new SectorCase(30.0F, new BlockPosition(-179, 1, -179)),
                new SectorCase(Math.nextUp(30.0F), new BlockPosition(-179, 1, -179)),
                new SectorCase(Math.nextDown(60.0F), new BlockPosition(-179, 1, -179)),
                new SectorCase(60.0F, new BlockPosition(0, 1, -179)),
                new SectorCase(Math.nextUp(60.0F), new BlockPosition(0, 1, -179)),
                new SectorCase(-0.5F, new BlockPosition(-179, 1, 0)),
                new SectorCase(390.0F, new BlockPosition(-179, 1, -179)));
        for (SectorCase sector : sectors) {
            SShapeMelonPumpkinDefaultMacro zero = macro(new QueueRandom(
                    0.0D, 0.0D, 0.0D, 0.0D, 0.0D));
            MacroDecision selected = step(zero, 0L, START, sector.yaw(), 0.0F, STILL, grounded(),
                    Map.of(new BlockPosition(0, 1, 0), Observation.present(melon())));
            assertEquals(RowDirection.RIGHT, zero.rowDirection().orElseThrow());
            assertTrue(selected.rotation().isPresent());

            SShapeMelonPumpkinDefaultMacro distant = macro(new QueueRandom());
            advanceEmptyScanTo179(distant, sector.yaw());
            SpatialCaptureRequest request = distant.spatialRequest(
                    player(START, sector.yaw(), 0.0F, STILL), EPOCH).orElseThrow();
            assertTrue(request.blocks().contains(sector.rightAt179()),
                    () -> "yaw " + sector.yaw() + " requested " + request.blocks());
        }
    }

    @Test
    void initialDontFixComparesObservedYawWithStoredBaseNotJitteredRowTarget() {
        MacroSettings settings = validSettings();
        settings.dontFixAfterWarping(true);
        settings.customPitch(true);
        settings.customPitchLevel(47.0F);
        settings.customYaw(true);
        settings.customYawLevel(90.0F);
        QueueRandom suppressedLeaf = new QueueRandom(0.5D);
        QueueRandom suppressedRotation = new QueueRandom();
        SShapeMelonPumpkinDefaultMacro suppressed = new SShapeMelonPumpkinDefaultMacro(
                settings, suppressedLeaf, suppressedRotation);
        suppressed.onStart(0L);

        MacroDecision decision = step(suppressed, TimeUnit.MILLISECONDS.toNanos(300L),
                START, 90.0F, 47.0F, STILL, grounded(),
                Map.of(new BlockPosition(0, 1, 0), Observation.present(melon())));

        assertEquals(1, suppressedLeaf.draws(), "the final row target is 136 after jitter");
        assertEquals(0, suppressedRotation.draws());
        assertTrue(decision.rotation().isEmpty());
        assertTrue(decision.inputs().isEmpty());

        QueueRandom alignedLeaf = new QueueRandom(0.5D, 0.0D);
        QueueRandom alignedRotation = new QueueRandom(0.0D, 0.0D);
        SShapeMelonPumpkinDefaultMacro alignedWithFinalTarget =
                new SShapeMelonPumpkinDefaultMacro(settings, alignedLeaf, alignedRotation);
        alignedWithFinalTarget.onStart(0L);
        MacroDecision request = step(alignedWithFinalTarget,
                TimeUnit.MILLISECONDS.toNanos(300L), START, 136.0F, 47.0F,
                STILL, grounded(),
                Map.of(new BlockPosition(0, 1, 0), Observation.present(melon())));
        assertEquals(136.0F, request.rotation().orElseThrow().yaw());
        assertEquals(2, alignedLeaf.draws(), "jitter then duration when base yaw differs");
        assertEquals(2, alignedRotation.draws());
    }

    @Test
    void farmingUsesLeftPriorityAndHugsOnlyAKnownBackWall() {
        QueueRandom random = new QueueRandom(0.0D, 0.0D, 0.0D, 0.0D, 0.0D);
        SShapeMelonPumpkinDefaultMacro macro = macro(random);
        MacroDecision initial = chooseRight(macro, 0L);
        float yaw = initial.rotation().orElseThrow().yaw();
        float pitch = initial.rotation().orElseThrow().pitch();

        MacroDecision leftPriority = step(macro, 1L, START, yaw, pitch,
                STILL, grounded(), Map.of(
                        new BlockPosition(1, 1, 0), Observation.present(pumpkin()),
                        new BlockPosition(-1, 1, 0), Observation.present(melon()),
                        new BlockPosition(0, 1, -1), Observation.present(full())));

        assertEquals(RowDirection.LEFT, macro.rowDirection().orElseThrow());
        assertEquals(Set.of(InputAction.LEFT, InputAction.ATTACK, InputAction.FORWARD),
                leftPriority.inputs());
    }

    @Test
    void threeHalfSecondNoProgressWindowsReleaseInputThenHandOffRecovery() {
        QueueRandom random = zeros(10);
        SShapeMelonPumpkinDefaultMacro macro = macro(random);
        MacroDecision initial = chooseRight(macro, 0L);
        float yaw = initial.rotation().orElseThrow().yaw();
        float pitch = initial.rotation().orElseThrow().pitch();
        Map<BlockPosition, Observation<BlockStateSnapshot>> crop = Map.of(
                new BlockPosition(-1, 1, 0), Observation.present(melon()));

        MacroDecision first = step(macro, TimeUnit.MILLISECONDS.toNanos(500L),
                START, yaw, pitch, STILL, grounded(), crop);
        MacroDecision second = step(macro, TimeUnit.MILLISECONDS.toNanos(1_000L),
                START, yaw, pitch, STILL, grounded(), crop);
        MacroDecision third = step(macro, TimeUnit.MILLISECONDS.toNanos(1_500L),
                START, yaw, pitch, STILL, grounded(), crop);

        assertEquals("row-stall-observed-1", first.status());
        assertEquals("row-stall-observed-2", second.status());
        assertTrue(first.inputs().isEmpty());
        assertTrue(second.inputs().isEmpty());
        assertEquals(MacroRecoveryReason.ROW_STALLED,
                third.recovery().orElseThrow().reason());
    }

    @Test
    void melonLeafIgnoresAlwaysHoldWAndHonorsLaneAttackSetting() {
        MacroSettings settings = validSettings();
        settings.alwaysHoldW(true);
        settings.holdLeftClickWhenChangingRow(false);
        QueueRandom leaf = zeros(20);
        QueueRandom rotationEntropy = zeros(20);
        SShapeMelonPumpkinDefaultMacro macro =
                new SShapeMelonPumpkinDefaultMacro(settings, leaf, rotationEntropy);
        macro.onStart();
        MacroDecision initial = chooseRight(macro, 0L);
        float yaw = initial.rotation().orElseThrow().yaw();
        float pitch = initial.rotation().orElseThrow().pitch();

        MacroDecision farming = step(macro, 1L, START, yaw, pitch, STILL, grounded(),
                Map.of(new BlockPosition(-1, 1, 0), Observation.present(melon())));
        assertEquals(Set.of(InputAction.RIGHT, InputAction.ATTACK), farming.inputs());

        MacroDecision laneStart = step(macro, 2L, START, yaw, pitch,
                STILL, grounded(), Map.of());
        assertEquals(7, leaf.draws(), "row end draws dwell, pitch, offset, and duration");
        assertEquals(4, rotationEntropy.draws(), "row end draws handler entropy only here");
        MacroDecision lane = step(macro, TimeUnit.MILLISECONDS.toNanos(400L) + 2L,
                START, laneStart.rotation().orElseThrow().yaw(),
                laneStart.rotation().orElseThrow().pitch(),
                new MotionSnapshot(0.0D, 0.0D, 0.15D), grounded(), Map.of());
        assertEquals(Set.of(InputAction.FORWARD, InputAction.SPRINT), lane.inputs());
        assertEquals(7, leaf.draws());
        assertEquals(4, rotationEntropy.draws());
    }

    @Test
    void recordedBackwardLaneControlsInputAndObstructionProbeAtVelocityBoundary() {
        QueueRandom random = new QueueRandom(
                0.0D, 0.0D, 0.0D, 0.0D, 0.0D,
                0.0D, 0.0D, 0.0D, 0.0D, 0.0D, 0.0D);
        SShapeMelonPumpkinDefaultMacro macro = macro(random);
        MacroDecision initial = chooseRight(macro, 0L);
        float rowYaw = initial.rotation().orElseThrow().yaw();
        float rowPitch = initial.rotation().orElseThrow().pitch();
        MacroDecision laneStart = step(macro, 1L, START, rowYaw, rowPitch,
                STILL, grounded(),
                Map.of(new BlockPosition(0, 1, 1), Observation.present(full())));
        assertEquals(LaneChangeDirection.BACKWARD, macro.laneDirection().orElseThrow());
        float laneYaw = laneStart.rotation().orElseThrow().yaw();
        float lanePitch = laneStart.rotation().orElseThrow().pitch();

        MacroDecision before = step(macro, TimeUnit.MILLISECONDS.toNanos(400L),
                START, laneYaw, lanePitch, STILL, grounded(),
                Map.of(new BlockPosition(0, 1, -1), Observation.present(full())));
        MacroDecision boundary = step(macro, TimeUnit.MILLISECONDS.toNanos(400L) + 1L,
                START, laneYaw, lanePitch, new MotionSnapshot(0.15D, 0.0D, 0.0D), grounded(),
                Map.of(new BlockPosition(0, 1, -1), Observation.present(full())));

        assertEquals("row-change-dwell", before.status());
        assertTrue(before.inputs().isEmpty());
        assertEquals(Set.of(InputAction.BACKWARD, InputAction.ATTACK), boundary.inputs());

        MacroDecision obstructed = step(macro, TimeUnit.MILLISECONDS.toNanos(401L),
                START, laneYaw, lanePitch, STILL, grounded(),
                Map.of(new BlockPosition(0, 1, -1), Observation.present(full())));
        assertEquals("lane-obstructed", obstructed.status());
        assertTrue(obstructed.inputs().isEmpty());
    }

    @Test
    void oneBlockFlipsRowAndExactTwoSecondsHandsOffLaneStall() {
        QueueRandom completedLeaf = zeros(20);
        QueueRandom completedRotation = zeros(20);
        SShapeMelonPumpkinDefaultMacro completed = new SShapeMelonPumpkinDefaultMacro(
                validSettings(), completedLeaf, completedRotation);
        completed.onStart();
        MacroDecision initial = chooseRight(completed, 0L);
        MacroDecision laneStart = step(completed, 1L, START,
                initial.rotation().orElseThrow().yaw(), initial.rotation().orElseThrow().pitch(),
                STILL, grounded(), Map.of());
        PositionSnapshot oneForward = new PositionSnapshot(0.5D, 1.0D, 1.5D);
        MacroDecision flipped = step(completed, TimeUnit.MILLISECONDS.toNanos(400L) + 1L,
                oneForward, laneStart.rotation().orElseThrow().yaw(),
                laneStart.rotation().orElseThrow().pitch(), STILL, grounded(), Map.of());
        assertEquals(RowDirection.LEFT, completed.rowDirection().orElseThrow());
        assertEquals(-45.0F, flipped.rotation().orElseThrow().yaw());
        assertEquals(10, completedLeaf.draws(),
                "lane completion draws pitch, row jitter, and duration");
        assertEquals(6, completedRotation.draws());

        QueueRandom stalledRandom = zeros(20);
        SShapeMelonPumpkinDefaultMacro stalled = macro(stalledRandom);
        MacroDecision stalledInitial = chooseRight(stalled, 0L);
        MacroDecision stalledLane = step(stalled, 1L, START,
                stalledInitial.rotation().orElseThrow().yaw(),
                stalledInitial.rotation().orElseThrow().pitch(),
                STILL, grounded(), Map.of());
        MacroDecision recovery = step(stalled, 1L + TimeUnit.SECONDS.toNanos(2L),
                START, stalledLane.rotation().orElseThrow().yaw(),
                stalledLane.rotation().orElseThrow().pitch(),
                STILL, grounded(), Map.of());
        assertEquals(MacroRecoveryReason.LANE_STALLED,
                recovery.recovery().orElseThrow().reason());
        assertEquals(SShapeMelonPumpkinDefaultMacro.State.RECOVERY_HANDOFF, stalled.state());
    }

    @Test
    void dropThresholdsReleaseControlsAndLargeLandingUsesDurationOnlyRotation() {
        MacroSettings settings = validSettings();
        settings.rotateAfterDrop(true);
        QueueRandom leaf = zeros(20);
        QueueRandom rotationEntropy = zeros(20);
        SShapeMelonPumpkinDefaultMacro macro =
                new SShapeMelonPumpkinDefaultMacro(settings, leaf, rotationEntropy);
        macro.onStart();
        MacroDecision initial = chooseRight(macro, 0L);
        int beforeDropLeafDraws = leaf.draws();
        int beforeDropRotationDraws = rotationEntropy.draws();
        float yaw = initial.rotation().orElseThrow().yaw();
        float pitch = initial.rotation().orElseThrow().pitch();

        PositionSnapshot falling = new PositionSnapshot(0.5D, 0.24D, 0.5D);
        MacroDecision dropping = step(macro, 1L, falling, yaw, pitch,
                new MotionSnapshot(0.0D, -0.1D, 0.0D),
                new PlayerPosture(false, false, false), Map.of());
        assertEquals("dropping", dropping.status());
        assertTrue(dropping.inputs().isEmpty());
        assertTrue(dropping.rotation().isEmpty());

        PositionSnapshot landed = new PositionSnapshot(0.5D, -1.0D, 0.5D);
        MacroDecision rotated = step(macro, 2L, landed, yaw, pitch,
                STILL, grounded(), Map.of());
        assertEquals(beforeDropLeafDraws + 1, leaf.draws(),
                "drop leaf consumes duration only");
        assertEquals(beforeDropRotationDraws + 2, rotationEntropy.draws(),
                "ordinary rotation consumes unused modifier and duration floor");
        assertEquals(-180.0F, rotated.rotation().orElseThrow().yaw());
        assertEquals(RotationProfile.EXPO_QUART, rotated.rotation().orElseThrow().profile());
        assertTrue(macro.laneDirection().isEmpty());
    }

    @Test
    void startupAtRewarpFailsClosedUntilRowEvidenceIsKnown() {
        MacroSettings settings = rewarpSettings();
        QueueRandom leaf = new QueueRandom();
        QueueRandom rotationEntropy = new QueueRandom();
        SShapeMelonPumpkinDefaultMacro macro =
                new SShapeMelonPumpkinDefaultMacro(settings, leaf, rotationEntropy);
        macro.onStart(0L);

        MacroDecision decision = step(macro, TimeUnit.MILLISECONDS.toNanos(300L),
                START, 0.0F, 0.0F, STILL, grounded(),
                Map.of(new BlockPosition(0, 1, 0), Observation.unknown()));

        assertEquals("row-crop-scan-unknown", decision.status());
        assertEquals(SShapeMelonPumpkinDefaultMacro.State.ROW_SELECT, macro.state());
        assertTrue(macro.rowDirection().isEmpty());
        assertTrue(decision.warp().isEmpty());
        assertEquals(0, leaf.draws());
        assertEquals(0, rotationEntropy.draws());
    }

    @Test
    void startupAtRewarpSelectsAndSavesTargetBeforeDwell() {
        MacroSettings settings = rewarpSettings();
        settings.customPitch(true);
        settings.customPitchLevel(52.0F);
        settings.customYaw(true);
        settings.customYawLevel(90.0F);
        QueueRandom leaf = new QueueRandom(0.5D, 0.0D, 0.0D, 0.0D);
        QueueRandom rotationEntropy = zeros(4);
        SShapeMelonPumpkinDefaultMacro macro =
                new SShapeMelonPumpkinDefaultMacro(settings, leaf, rotationEntropy);
        macro.onStart(0L);
        long selectedAt = TimeUnit.MILLISECONDS.toNanos(300L);

        MacroDecision selected = step(macro, selectedAt,
                START, 12.0F, 0.0F, STILL, grounded(),
                Map.of(new BlockPosition(0, 1, 0), Observation.present(melon())));
        assertEquals(136.0F, selected.rotation().orElseThrow().yaw());
        assertEquals(52.0F, selected.rotation().orElseThrow().pitch());

        long dwellAt = selectedAt + 1L;
        assertEquals("rewarp-dwell", step(macro, dwellAt,
                START, 12.0F, 0.0F, STILL, grounded(), Map.of()).status());
        long warpAt = dwellAt + TimeUnit.MILLISECONDS.toNanos(400L);
        assertTrue(step(macro, warpAt, START, 12.0F, 0.0F,
                STILL, grounded(), Map.of()).warp().isPresent());
        PositionSnapshot moved = new PositionSnapshot(2.5D, 1.0D, 0.5D);
        long landedAt = warpAt + 1L;
        step(macro, landedAt, moved, 0.0F, 0.0F, STILL, grounded(), Map.of());
        long postAt = landedAt + TimeUnit.MILLISECONDS.toNanos(1_500L);
        step(macro, postAt, moved, 0.0F, 0.0F, STILL, grounded(), Map.of());
        MacroDecision correction = step(macro,
                postAt + TimeUnit.MILLISECONDS.toNanos(600L),
                moved, 0.0F, 0.0F, STILL, grounded(), Map.of());

        assertEquals("post-rewarp-saved-back", correction.status());
        assertEquals(136.0F, correction.rotation().orElseThrow().yaw());
        assertEquals(52.0F, correction.rotation().orElseThrow().pitch());
        assertEquals(4, leaf.draws(), "initial jitter/duration, dwell, saved correction duration");
        assertEquals(4, rotationEntropy.draws());
    }

    @Test
    void postRewarpLeafBackEmitsOneRequestAndUsesBackProfile() {
        MacroSettings settings = validSettings();
        settings.clearRewarps();
        settings.spawn(new RewarpPosition(10, 1, 10));
        assertTrue(settings.addRewarp(new RewarpPosition(0, 1, 0)));
        settings.rotateAfterWarped(true);
        QueueRandom leaf = zeros(20);
        QueueRandom rotationEntropy = zeros(20);
        SShapeMelonPumpkinDefaultMacro macro =
                new SShapeMelonPumpkinDefaultMacro(settings, leaf, rotationEntropy);
        MacroDecision correction = completeRewarp(macro, 134.0F, 47.0F);

        assertEquals("post-rewarp-leaf-back", correction.status());
        assertEquals(1, correction.rotation().stream().count());
        assertEquals(RotationProfile.BACK, correction.rotation().orElseThrow().profile());
        assertEquals(135.0F, correction.rotation().orElseThrow().yaw());
        assertEquals(47.0F, correction.rotation().orElseThrow().pitch());
        assertEquals(325L, correction.rotation().orElseThrow().durationMillis());
        assertEquals(8, leaf.draws(),
                "initial target, dwell, post target, traced duration, correction duration");
        assertEquals(4, rotationEntropy.draws(),
                "initial and Back handlers each own their entropy");
    }

    @Test
    void postRewarpCorrectionDoublesOnlyBeyondNinetyDegrees() {
        MacroDecision exact = rewarpCorrection(45.0F, 47.0F, false);
        MacroDecision beyond = rewarpCorrection(44.0F, 47.0F, false);

        assertEquals(500L, exact.rotation().orElseThrow().durationMillis());
        assertEquals(1_000L, beyond.rotation().orElseThrow().durationMillis());
    }

    @Test
    void dontFixUsesCombinedYawPitchDistanceAndBackRequestIsNotDuplicated() {
        MacroSettings suppressedSettings = rewarpSettings();
        suppressedSettings.rotateAfterWarped(true);
        suppressedSettings.dontFixAfterWarping(true);
        QueueRandom suppressedLeaf = zeros(20);
        QueueRandom suppressedRotation = zeros(20);
        SShapeMelonPumpkinDefaultMacro suppressedMacro =
                new SShapeMelonPumpkinDefaultMacro(
                        suppressedSettings, suppressedLeaf, suppressedRotation);
        MacroDecision suppressed = completeRewarp(suppressedMacro, 135.0F, 46.5F);
        assertEquals("post-rewarp-fix-suppressed", suppressed.status());
        assertTrue(suppressed.rotation().isEmpty());
        assertEquals(7, suppressedLeaf.draws(),
                "suppressed path still consumes the traced leaf duration draw");
        assertEquals(2, suppressedRotation.draws(),
                "suppression emits no Back request or Back entropy draws");

        MacroSettings settings = rewarpSettings();
        settings.rotateAfterWarped(true);
        settings.dontFixAfterWarping(true);
        QueueRandom leaf = zeros(20);
        QueueRandom rotationEntropy = zeros(20);
        SShapeMelonPumpkinDefaultMacro macro =
                new SShapeMelonPumpkinDefaultMacro(settings, leaf, rotationEntropy);
        MacroDecision correction = completeRewarp(macro, 135.0F, 45.5F);
        var request = correction.rotation().orElseThrow();
        int leafDraws = leaf.draws();
        int rotationDraws = rotationEntropy.draws();

        MacroDecision waiting = step(
                macro, TimeUnit.SECONDS.toNanos(3L), new PositionSnapshot(2.5D, 1.0D, 0.5D),
                request.yaw(), request.pitch(), STILL, grounded(), Map.of(),
                MacroRotationLeaseState.active(request.requestToken(), false, 8L));

        assertEquals(request, waiting.rotation().orElseThrow());
        assertEquals(leafDraws, leaf.draws());
        assertEquals(rotationDraws, rotationEntropy.draws());
    }

    @Test
    void staleTokenAndChangedPlayerFootprintFailClosedWithoutDraws() {
        QueueRandom random = new QueueRandom();
        SShapeMelonPumpkinDefaultMacro macro = macro(random);
        PlayerSnapshot player = player(START, 0.0F, 0.0F, STILL);
        SpatialCaptureRequest request = macro.spatialRequest(player, EPOCH).orElseThrow();
        SpatialSnapshot valid = captured(request, START, Map.of());
        SpatialSnapshot staleToken = new SpatialSnapshot(
                EPOCH, request.requestToken() + 1L, request.bounds(), -64, 320,
                valid.playerBox(), valid.chunks());

        MacroDecision stale = macro.tick(context(0L, player, staleToken, grounded()));

        assertEquals("spatial-unknown-or-stale", stale.status());
        assertTrue(stale.inputs().isEmpty());
        assertTrue(stale.rotation().isEmpty());
        assertEquals(0, random.draws());

        SpatialCaptureRequest next = macro.spatialRequest(player, EPOCH).orElseThrow();
        SpatialSnapshot captured = captured(next, START, Map.of());
        SpatialSnapshot wrongBody = new SpatialSnapshot(
                EPOCH, next.requestToken(), next.bounds(), -64, 320,
                captured.playerBox().move(0.01D, 0.0D, 0.0D), captured.chunks());
        assertEquals("spatial-unknown-or-stale",
                macro.tick(context(1L, player, wrongBody, grounded())).status());
        assertEquals(0, random.draws());
    }

    @Test
    void pauseResumeKeepsSampledTargetAndDoesNotRedraw() {
        QueueRandom random = zeros(10);
        SShapeMelonPumpkinDefaultMacro macro = macro(random);
        MacroDecision initial = chooseRight(macro, 0L);
        int draws = random.draws();
        macro.onPause(Set.of(dev.hylfrd.farmhelper.macro.MacroPauseCause.SCREEN_OPEN), 1L);
        macro.onResume(TimeUnit.SECONDS.toNanos(10L));

        MacroDecision resumed = step(macro, TimeUnit.SECONDS.toNanos(10L) + 1L,
                START, 0.0F, 0.0F, STILL, grounded(), Map.of());

        assertEquals(initial.rotation(), resumed.rotation());
        assertEquals(draws, random.draws());
    }

    @Test
    void observedTargetCrossingAndOvershootCannotAdvanceAnActiveOwnedLease() {
        QueueRandom leaf = zeros(10);
        QueueRandom rotation = zeros(10);
        SShapeMelonPumpkinDefaultMacro macro = new SShapeMelonPumpkinDefaultMacro(
                validSettings(), leaf, rotation);
        macro.onStart();
        MacroDecision initial = chooseRight(macro, 0L);
        var request = initial.rotation().orElseThrow();
        Map<BlockPosition, Observation<BlockStateSnapshot>> fruit = Map.of(
                new BlockPosition(1, 1, 0), Observation.present(melon()));

        MacroDecision crossing = step(
                macro, 1L, START, request.yaw(), request.pitch(), STILL, grounded(), fruit,
                MacroRotationLeaseState.active(request.requestToken(), false, 1L));
        MacroDecision overshoot = step(
                macro, 2L, START, request.yaw() + 12.0F, request.pitch(), STILL, grounded(), fruit,
                MacroRotationLeaseState.active(request.requestToken(), false, 2L));

        assertEquals("aligning", crossing.status());
        assertEquals("aligning", overshoot.status());
        assertTrue(crossing.inputs().isEmpty());
        assertTrue(overshoot.inputs().isEmpty());
        assertEquals(request, crossing.rotation().orElseThrow());
        assertEquals(request, overshoot.rotation().orElseThrow());

        MacroDecision endpoint = step(
                macro, 3L, START, request.yaw(), request.pitch(), STILL, grounded(), fruit,
                MacroRotationLeaseState.terminal(
                        request.requestToken(), RotationTerminalReason.COMPLETED, 3L));
        assertEquals(Set.of(InputAction.LEFT, InputAction.ATTACK), endpoint.inputs());
    }

    @Test
    void pauseCancellationReusesExactRequestWithoutEitherEntropySourceRedrawing() {
        QueueRandom leaf = zeros(10);
        QueueRandom rotation = zeros(10);
        SShapeMelonPumpkinDefaultMacro macro = new SShapeMelonPumpkinDefaultMacro(
                validSettings(), leaf, rotation);
        macro.onStart();
        MacroDecision initial = chooseRight(macro, 0L);
        var request = initial.rotation().orElseThrow();
        int leafDraws = leaf.draws();
        int rotationDraws = rotation.draws();
        macro.onPause(Set.of(dev.hylfrd.farmhelper.macro.MacroPauseCause.SCREEN_OPEN), 1L);
        macro.onResume(2L);

        MacroDecision retried = step(
                macro, 3L, START, 0.0F, 0.0F, STILL, grounded(), Map.of(),
                MacroRotationLeaseState.terminal(
                        request.requestToken(), RotationTerminalReason.OWNER_CANCELLED, 2L));

        assertEquals(request, retried.rotation().orElseThrow());
        assertEquals(leafDraws, leaf.draws());
        assertEquals(rotationDraws, rotation.draws());
    }

    private static MacroDecision chooseRight(SShapeMelonPumpkinDefaultMacro macro, long now) {
        return step(macro, now, START, 0.0F, 0.0F,
                STILL, grounded(),
                Map.of(new BlockPosition(0, 1, 0), Observation.present(melon())));
    }

    private static MacroDecision rewarpCorrection(
            float observedYaw,
            float observedPitch,
            boolean dontFix
    ) {
        MacroSettings settings = rewarpSettings();
        settings.rotateAfterWarped(true);
        settings.dontFixAfterWarping(dontFix);
        SShapeMelonPumpkinDefaultMacro macro = new SShapeMelonPumpkinDefaultMacro(
                settings, zeros(20), zeros(20));
        return completeRewarp(macro, observedYaw, observedPitch);
    }

    private static MacroDecision completeRewarp(
            SShapeMelonPumpkinDefaultMacro macro,
            float observedYaw,
            float observedPitch
    ) {
        macro.onStart(0L);
        long selectedAt = TimeUnit.MILLISECONDS.toNanos(300L);
        MacroDecision selected = step(macro, selectedAt,
                START, 0.0F, 0.0F, STILL, grounded(),
                Map.of(new BlockPosition(0, 1, 0), Observation.present(melon())));
        assertEquals(RowDirection.RIGHT, macro.rowDirection().orElseThrow());
        assertEquals(135.0F, selected.rotation().orElseThrow().yaw());
        long dwellAt = selectedAt + 1L;
        assertEquals("rewarp-dwell", step(macro, dwellAt,
                START, 0.0F, 0.0F, STILL, grounded(), Map.of()).status());
        long warpAt = dwellAt + TimeUnit.MILLISECONDS.toNanos(400L);
        assertTrue(step(macro, warpAt, START, 0.0F, 0.0F,
                STILL, grounded(), Map.of()).warp().isPresent());
        PositionSnapshot moved = new PositionSnapshot(2.5D, 1.0D, 0.5D);
        long landedAt = warpAt + 1L;
        step(macro, landedAt, moved, observedYaw, observedPitch,
                STILL, grounded(), Map.of());
        long postAt = landedAt + TimeUnit.MILLISECONDS.toNanos(1_500L);
        step(macro, postAt, moved, observedYaw, observedPitch,
                STILL, grounded(), Map.of());
        return step(macro, postAt + TimeUnit.MILLISECONDS.toNanos(600L),
                moved, observedYaw, observedPitch, STILL, grounded(), Map.of());
    }

    private static MacroSettings rewarpSettings() {
        MacroSettings settings = validSettings();
        settings.clearRewarps();
        settings.spawn(new RewarpPosition(10, 1, 10));
        assertTrue(settings.addRewarp(new RewarpPosition(0, 1, 0)));
        return settings;
    }

    private static void advanceEmptyScanTo179(
            SShapeMelonPumpkinDefaultMacro macro,
            float yaw
    ) {
        for (int distance = 0; distance < 179; distance++) {
            for (int phase = 0; phase < 4; phase++) {
                step(macro, distance * 4L + phase, START, yaw, 0.0F,
                        STILL, grounded(), Map.of());
            }
        }
        assertEquals(179, macro.scanDistance());
        assertEquals(SShapeMelonPumpkinDefaultMacro.ScanPhase.RIGHT_CROP, macro.scanPhase());
    }

    private static SShapeMelonPumpkinDefaultMacro macro(QueueRandom random) {
        SShapeMelonPumpkinDefaultMacro macro = new SShapeMelonPumpkinDefaultMacro(
                validSettings(), random);
        macro.onStart();
        return macro;
    }

    private static MacroSettings validSettings() {
        MacroSettings settings = new MacroSettings();
        settings.macroMode(MacroMode.MELON_PUMPKIN_DEFAULT);
        settings.spawn(new RewarpPosition(100, 70, 100));
        assertTrue(settings.addRewarp(new RewarpPosition(90, 70, 90)));
        return settings;
    }

    private static MacroDecision step(
            SShapeMelonPumpkinDefaultMacro macro,
            long now,
            PositionSnapshot position,
            float yaw,
            float pitch,
            MotionSnapshot motion,
            PlayerPosture posture,
            Map<BlockPosition, Observation<BlockStateSnapshot>> overrides
    ) {
        MacroRotationLeaseState lease = macro.pendingRotation()
                .map(pending -> Math.abs(dev.hylfrd.farmhelper.macro.MacroAngles.shortestDelta(
                                yaw, pending.yaw())) <= 0.1F
                                && Math.abs(pitch - pending.pitch()) <= 0.1F
                        ? MacroRotationLeaseState.terminal(
                                pending.requestToken(), RotationTerminalReason.COMPLETED, 2L)
                        : MacroRotationLeaseState.active(pending.requestToken(), false, 1L))
                .orElseGet(() -> MacroRotationLeaseState.idle(0L));
        return step(macro, now, position, yaw, pitch, motion, posture, overrides, lease);
    }

    private static MacroDecision step(
            SShapeMelonPumpkinDefaultMacro macro,
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
        return macro.tick(context(
                now, player, captured(request, position, overrides), posture, lease));
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

    private static FarmingContext context(
            long now,
            PlayerSnapshot player,
            SpatialSnapshot spatial,
            PlayerPosture posture
    ) {
        return context(now, player, spatial, posture, MacroRotationLeaseState.idle(0L));
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
                new BoxSnapshot(player.x() - 0.3D, player.y(), player.z() - 0.3D,
                        player.x() + 0.3D, player.y() + 1.8D, player.z() + 0.3D),
                chunks);
    }

    private static BlockStateSnapshot melon() {
        return crop("melon");
    }

    private static BlockStateSnapshot pumpkin() {
        return crop("pumpkin");
    }

    private static BlockStateSnapshot melonStem() {
        return crop("melon_stem");
    }

    private static BlockStateSnapshot crop(String name) {
        return new BlockStateSnapshot(
                ResourceIdentifier.parse("minecraft:" + name), Map.of(),
                ResourceIdentifier.parse("minecraft:empty"),
                Observation.present(CollisionShapeSnapshot.EMPTY));
    }

    private static BlockStateSnapshot air() {
        return new BlockStateSnapshot(
                ResourceIdentifier.parse("minecraft:air"), Map.of(),
                ResourceIdentifier.parse("minecraft:empty"),
                Observation.present(CollisionShapeSnapshot.EMPTY));
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

    private static QueueRandom zeros(int count) {
        double[] values = new double[count];
        return new QueueRandom(values);
    }

    private record SectorCase(float yaw, BlockPosition rightAt179) {
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
