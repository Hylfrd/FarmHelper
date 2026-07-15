package dev.hylfrd.farmhelper.macro.impl;

import dev.hylfrd.farmhelper.client.control.TestClientRotationControllerAccess;
import dev.hylfrd.farmhelper.client.platform.TestClientTickAdapterAccess;
import dev.hylfrd.farmhelper.client.runtime.FarmHelperClientRuntime;
import dev.hylfrd.farmhelper.client.runtime.TestFarmHelperClientRuntimeFactory;
import dev.hylfrd.farmhelper.control.input.InputAction;
import dev.hylfrd.farmhelper.control.rotation.RotationTerminalReason;
import dev.hylfrd.farmhelper.macro.FarmingContext;
import dev.hylfrd.farmhelper.macro.MacroDecision;
import dev.hylfrd.farmhelper.macro.MacroSettings;
import dev.hylfrd.farmhelper.macro.PlayerPosture;
import dev.hylfrd.farmhelper.macro.MacroRotationDisposition;
import dev.hylfrd.farmhelper.macro.MacroSpawnPose;
import dev.hylfrd.farmhelper.macro.ServerResponsiveness;
import dev.hylfrd.farmhelper.macro.VerticalCropMode;
import dev.hylfrd.farmhelper.runtime.snapshot.MotionSnapshot;
import dev.hylfrd.farmhelper.runtime.snapshot.ClientSnapshot;
import dev.hylfrd.farmhelper.runtime.snapshot.ConnectionSnapshot;
import dev.hylfrd.farmhelper.runtime.snapshot.Observation;
import dev.hylfrd.farmhelper.runtime.snapshot.PlayerSnapshot;
import dev.hylfrd.farmhelper.runtime.snapshot.PositionSnapshot;
import dev.hylfrd.farmhelper.runtime.snapshot.ResourceIdentifier;
import dev.hylfrd.farmhelper.runtime.snapshot.RotationSnapshot;
import dev.hylfrd.farmhelper.runtime.snapshot.WorldSnapshot;
import dev.hylfrd.farmhelper.runtime.spatial.BlockPosition;
import dev.hylfrd.farmhelper.runtime.spatial.BlockStateSnapshot;
import dev.hylfrd.farmhelper.runtime.spatial.BoxSnapshot;
import dev.hylfrd.farmhelper.runtime.spatial.ChunkPosition;
import dev.hylfrd.farmhelper.runtime.spatial.ChunkSnapshot;
import dev.hylfrd.farmhelper.runtime.spatial.CollisionShapeSnapshot;
import dev.hylfrd.farmhelper.runtime.spatial.RewarpPosition;
import dev.hylfrd.farmhelper.runtime.spatial.RelativeFrame;
import dev.hylfrd.farmhelper.runtime.spatial.SpatialCaptureRequest;
import dev.hylfrd.farmhelper.runtime.spatial.SpatialSnapshot;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SShapeVerticalCropMacroTest {
    private static final long EPOCH = 4L;
    private static final PositionSnapshot START = new PositionSnapshot(0.5D, 1.0D, 0.5D);

    @TempDir
    Path temporaryDirectory;

    @Test
    void allMappedModesSelectTheirCropAndFrozenPitch() {
        Map<VerticalCropMode, CropFixture> fixtures = Map.of(
                VerticalCropMode.NORMAL, new CropFixture("wheat", Map.of("age", "7"), 2.8F),
                VerticalCropMode.PUMPKIN_MELON, new CropFixture("melon", Map.of(), 28.0F),
                VerticalCropMode.MELONGKINGDE, new CropFixture("pumpkin", Map.of(), -59.2F),
                VerticalCropMode.CACTUS_NETHER_WART, new CropFixture("cactus", Map.of(), 0.0F),
                VerticalCropMode.SUNTZU, new CropFixture("cactus", Map.of(), -38.0F),
                VerticalCropMode.COCOA, new CropFixture("cocoa", Map.of("age", "2"), -90.0F));

        for (Map.Entry<VerticalCropMode, CropFixture> entry : fixtures.entrySet()) {
            MacroSettings settings = new MacroSettings();
            settings.mode(entry.getKey());
            SShapeVerticalCropMacro macro = new SShapeVerticalCropMacro(settings, () -> 0.0D);
            macro.onStart();

            MacroDecision decision = macro.tick(context(0L, START, 0.0D,
                    entry.getValue().pitch(), spatial(START, entry.getValue(), true)));

            assertTrue(decision.inputs().contains(InputAction.RIGHT), entry.getKey().name());
            assertTrue(decision.inputs().contains(InputAction.ATTACK), entry.getKey().name());
            assertEquals(entry.getKey().forwardAssist(),
                    decision.inputs().contains(InputAction.FORWARD), entry.getKey().name());
        }
    }

    @Test
    void unknownSpatialAndGardenTruthFailClosed() {
        SShapeVerticalCropMacro macro = macro(VerticalCropMode.NORMAL);
        PlayerSnapshot player = player(START, 0.0F, 2.8F, 0.0D);

        MacroDecision missingSpatial = macro.tick(new FarmingContext(
                0L, EPOCH, Observation.present(player), Observation.unknown(),
                Observation.present(true), false, ServerResponsiveness.RESPONSIVE));
        MacroDecision unknownGarden = macro.tick(new FarmingContext(
                1L, EPOCH, Observation.present(player), Observation.present(
                        spatial(START, wheat(), true)), Observation.unknown(),
                false, ServerResponsiveness.RESPONSIVE));

        assertTrue(missingSpatial.inputs().isEmpty());
        assertEquals("spatial-unknown", missingSpatial.status());
        assertTrue(unknownGarden.inputs().isEmpty());
        assertEquals("garden-unknown", unknownGarden.status());
    }

    @Test
    void directionPriorityIsCropThenVoidThenFirstKnownObstacle() {
        SShapeVerticalCropMacro crop = macro(VerticalCropMode.NORMAL);
        MacroDecision cropDecision = crop.tick(context(0L, START, 0.0D, 2.8F,
                spatial(START, wheat(), true)));
        assertEquals("farming-right", cropDecision.status());

        PositionSnapshot high = new PositionSnapshot(0.5D, 66.0D, 0.5D);
        SpatialSnapshot oneSidedVoid = withBlock(spatial(high, wheat(), false),
                new BlockPosition(-1, 65, 0), Observation.present(air()));
        SShapeVerticalCropMacro voidMacro = macro(VerticalCropMode.NORMAL);
        MacroDecision voidDecision = voidMacro.tick(context(0L, high, 0.0D, 2.8F,
                oneSidedVoid));
        assertEquals("farming-left-void", voidDecision.status());

        SpatialSnapshot rightObstacle = withBlock(spatial(START, wheat(), false),
                new BlockPosition(-1, 1, 0), Observation.present(full()));
        SShapeVerticalCropMacro obstacle = macro(VerticalCropMode.NORMAL);
        MacroDecision obstacleDecision = obstacle.tick(context(0L, START, 0.0D, 2.8F,
                rightObstacle));
        assertEquals("farming-left-obstacle", obstacleDecision.status());
    }

    @Test
    void rowEndSwitchesLaneAndReversesDirectionAfterOneBlock() {
        SShapeVerticalCropMacro macro = macro(VerticalCropMode.NORMAL);
        macro.tick(context(0L, START, 0.0D, 2.8F, spatial(START, wheat(), true)));

        MacroDecision end = macro.tick(context(1L, START, 0.0D, 2.8F,
                spatial(START, wheat(), false)));
        PositionSnapshot nextLane = new PositionSnapshot(0.5D, 1.0D, 1.6D);
        MacroDecision reversed = macro.tick(context(TimeUnit.MILLISECONDS.toNanos(400L) + 1L,
                nextLane, 0.0D, 2.8F,
                spatial(nextLane, wheat(), true)));

        assertEquals("row-change-dwell", end.status());
        assertTrue(end.inputs().isEmpty());
        assertTrue(reversed.inputs().contains(InputAction.LEFT));
        assertFalse(reversed.inputs().contains(InputAction.RIGHT));
    }

    @Test
    void threeNoProgressWindowsEnterFailClosedRecovery() {
        SShapeVerticalCropMacro macro = macro(VerticalCropMode.NORMAL);
        SpatialSnapshot spatial = spatial(START, wheat(), true);
        macro.tick(context(0L, START, 0.0D, 2.8F, spatial));

        MacroDecision first = macro.tick(context(
                TimeUnit.MILLISECONDS.toNanos(500L), START, 0.0D, 2.8F, spatial));
        MacroDecision second = macro.tick(context(
                TimeUnit.MILLISECONDS.toNanos(1_000L), START, 0.0D, 2.8F, spatial));
        MacroDecision third = macro.tick(context(
                TimeUnit.MILLISECONDS.toNanos(1_500L), START, 0.0D, 2.8F, spatial));

        assertEquals("row-retry-1", first.status());
        assertEquals("row-retry-2", second.status());
        assertEquals("row-recovery", third.status());
        assertTrue(third.inputs().isEmpty());
        assertEquals(SShapeVerticalCropMacro.State.RECOVERING, macro.state());

        PositionSnapshot changedHeight = new PositionSnapshot(0.5D, 2.6D, 0.5D);
        MacroDecision recovered = macro.tick(context(TimeUnit.MILLISECONDS.toNanos(2_100L),
                changedHeight, 0.0D, 2.8F, spatial(changedHeight, wheat(), true)));
        assertFalse(macro.state() == SShapeVerticalCropMacro.State.DROPPING);
        assertFalse(recovered.status().equals("dropping"));
    }

    @Test
    void rewarpRequiresMovementConfirmationAndRetriesAtFiveSeconds() {
        MacroSettings settings = new MacroSettings();
        settings.spawn(new RewarpPosition(10, 1, 10));
        assertTrue(settings.addRewarp(new RewarpPosition(0, 1, 0)));
        SShapeVerticalCropMacro macro = new SShapeVerticalCropMacro(settings, () -> 0.0D);
        macro.onStart();

        MacroDecision initial = macro.tick(context(0L, START, 0.0D, 2.8F,
                spatial(START, wheat(), true)));
        MacroDecision beforeDwell = macro.tick(context(TimeUnit.MILLISECONDS.toNanos(399L),
                START, 0.0D, 2.8F, spatial(START, wheat(), true)));
        MacroDecision first = macro.tick(context(TimeUnit.MILLISECONDS.toNanos(400L),
                START, 0.0D, 2.8F, spatial(START, wheat(), true)));
        MacroDecision waiting = macro.tick(context(TimeUnit.MILLISECONDS.toNanos(5_399L),
                START, 0.0D, 2.8F, spatial(START, wheat(), true)));
        MacroDecision retry = macro.tick(context(TimeUnit.MILLISECONDS.toNanos(5_400L),
                START, 0.0D, 2.8F, spatial(START, wheat(), true)));
        PositionSnapshot moved = new PositionSnapshot(2.0D, 1.0D, 0.5D);
        MacroDecision confirmed = macro.tick(context(TimeUnit.MILLISECONDS.toNanos(5_400L) + 1L,
                moved, 0.0D, 2.8F, spatial(moved, wheat(), true),
                new PlayerPosture(false, true)));

        assertEquals("rewarp-dwell", initial.status());
        assertEquals("rewarp-dwell", beforeDwell.status());
        assertTrue(first.warp().isPresent());
        assertEquals("rewarp-waiting", waiting.status());
        assertTrue(retry.warp().isPresent());
        assertEquals("rewarp-retry-2", retry.status());
        assertEquals("rewarp-confirmed plot=-1", confirmed.status());
        assertEquals(SShapeVerticalCropMacro.State.AFTER_WARP, macro.state());
    }

    @Test
    void settingsRejectOverlappingSpawnAndRewarps() {
        MacroSettings settings = new MacroSettings();
        settings.spawn(new RewarpPosition(0, 1, 0));

        assertFalse(settings.addRewarp(new RewarpPosition(1, 1, 0)));
        assertTrue(settings.addRewarp(new RewarpPosition(2, 1, 0)));
        assertFalse(settings.addRewarp(new RewarpPosition(3, 1, 0)));
        assertTrue(settings.removeNearest(new RewarpPosition(2, 1, 1), 2.0D));
    }

    @Test
    void verticalModesSelectSShapeAndConsumeExactPitchDraws() {
        for (VerticalCropMode mode : VerticalCropMode.values()) {
            float lower = mode.targetPitch(0.0D);
            float upper = mode.targetPitch(Math.nextDown(1.0D));
            switch (mode) {
                case NORMAL -> { assertEquals(2.8F, lower); assertTrue(upper < 3.3F); }
                case PUMPKIN_MELON -> { assertEquals(28.0F, lower); assertTrue(upper < 30.0F); }
                case MELONGKINGDE -> { assertEquals(-59.2F, lower); assertTrue(upper < -58.2F); }
                case CACTUS_NETHER_WART -> { assertEquals(0.0F, lower); assertTrue(upper < 0.5F); }
                case SUNTZU -> { assertEquals(-38.0F, lower); assertTrue(upper > -39.5F); }
                case COCOA -> { assertEquals(-90.0F, lower); assertEquals(-90.0F, upper); }
            }
        }
        AtomicInteger draws = new AtomicInteger();
        MacroSettings settings = new MacroSettings();
        SShapeVerticalCropMacro macro = new SShapeVerticalCropMacro(settings, () -> {
            draws.incrementAndGet();
            return 0.25D;
        });
        macro.onStart();
        macro.tick(context(0L, START, 0.0D, 0.0F, spatial(START, wheat(), true)));
        macro.tick(context(1L, START, 0.0D, 0.0F, spatial(START, wheat(), true)));
        assertEquals(2, draws.get());

        AtomicInteger cocoaDraws = new AtomicInteger();
        MacroSettings cocoaSettings = new MacroSettings();
        cocoaSettings.mode(VerticalCropMode.COCOA);
        SShapeVerticalCropMacro cocoa = new SShapeVerticalCropMacro(cocoaSettings, () -> {
            cocoaDraws.incrementAndGet();
            return 0.25D;
        });
        cocoa.onStart();
        cocoa.tick(context(0L, START, 0.0D, 0.0F,
                spatial(START, new CropFixture("cocoa", Map.of("age", "2"), -90F), true)));
        assertEquals(1, cocoaDraws.get(), "Cocoa consumes only the rotation timing draw");

        for (VerticalCropMode mode : VerticalCropMode.values()) {
            AtomicInteger modeDraws = new AtomicInteger();
            MacroSettings modeSettings = new MacroSettings();
            modeSettings.mode(mode);
            SShapeVerticalCropMacro counted = new SShapeVerticalCropMacro(modeSettings, () -> {
                modeDraws.incrementAndGet();
                return 0.5D;
            });
            counted.onStart();
            counted.tick(context(0L, START, 0.0D, 0.0F,
                    spatial(START, wheat(), false)));
            assertEquals(mode == VerticalCropMode.COCOA ? 1 : 2, modeDraws.get(), mode.name());
        }
    }

    @Test
    void startupAndRotationTimingBoundariesAreExact() {
        SShapeVerticalCropMacro macro = new SShapeVerticalCropMacro(new MacroSettings(), () -> 0.0D);
        long startedAt = 17L;
        macro.onStart(startedAt);
        assertEquals("startup", macro.tick(context(startedAt + TimeUnit.MILLISECONDS.toNanos(299L),
                START, 0.0D, 2.8F, spatial(START, wheat(), true))).status());
        assertFalse(macro.tick(context(startedAt + TimeUnit.MILLISECONDS.toNanos(300L),
                START, 0.0D, 2.8F, spatial(START, wheat(), true))).status().equals("startup"));

        assertEquals(500L, SShapeVerticalCropMacro.rotationDurationMillis(500L, 90.0F));
        assertEquals(799L, SShapeVerticalCropMacro.rotationDurationMillis(799L, 90.0F));
        assertEquals(500L, SShapeVerticalCropMacro.sampleRotationDurationMillis(0.0D));
        assertEquals(799L, SShapeVerticalCropMacro.sampleRotationDurationMillis(
                Math.nextDown(1.0D)));
        assertEquals(1_000L, SShapeVerticalCropMacro.rotationDurationMillis(500L, 90.01F));
        assertEquals(1_598L, SShapeVerticalCropMacro.rotationDurationMillis(799L, 180.0F));

        SShapeVerticalCropMacro paused = new SShapeVerticalCropMacro(
                new MacroSettings(), () -> 0.0D);
        paused.onStart(0L);
        paused.onPause(Set.of(dev.hylfrd.farmhelper.macro.MacroPauseCause.FEATURE),
                TimeUnit.MILLISECONDS.toNanos(100L));
        paused.onResume(TimeUnit.MILLISECONDS.toNanos(1_100L));
        assertEquals("startup", paused.tick(context(TimeUnit.MILLISECONDS.toNanos(1_299L),
                START, 0.0D, 2.8F, spatial(START, wheat(), true))).status());
        assertFalse(paused.tick(context(TimeUnit.MILLISECONDS.toNanos(1_300L),
                START, 0.0D, 2.8F, spatial(START, wheat(), true))).status().equals("startup"));
    }

    @Test
    void modeSixIsTypedCactusOnlyAndMismatchFailsClosed() {
        MacroSettings settings = new MacroSettings();
        settings.mode(VerticalCropMode.SUNTZU);
        SShapeVerticalCropMacro macro = new SShapeVerticalCropMacro(settings, () -> 0.0D);
        macro.onStart();

        MacroDecision mismatch = macro.tick(context(0L, START, 0.0D, -38.0F,
                spatial(START, new CropFixture("sugar_cane", Map.of(), -38F), true)));

        assertTrue(mismatch.inputs().isEmpty());
        assertEquals("crop-mode-incompatible", mismatch.status());
        assertEquals(MacroRotationDisposition.RELEASE, mismatch.rotationDisposition());
        assertEquals(SShapeVerticalCropMacro.State.ALIGNING, macro.state());
    }

    @Test
    void incompatibleMatureCropOutranksVoidAndObstacleFallbacks() {
        CropFixture sugarCane = new CropFixture("sugar_cane", Map.of(), -38F);
        PositionSnapshot high = new PositionSnapshot(0.5D, 66.0D, 0.5D);
        SpatialSnapshot withKnownVoid = withBlock(spatial(high, sugarCane, true),
                new BlockPosition(-1, 65, 0), Observation.present(air()));
        SpatialSnapshot withKnownObstacle = withBlock(spatial(START, sugarCane, true),
                new BlockPosition(-2, 1, 0), Observation.present(full()));

        for (SpatialSnapshot evidence : List.of(withKnownVoid, withKnownObstacle)) {
            SShapeVerticalCropMacro macro = macro(VerticalCropMode.SUNTZU);
            PositionSnapshot position = evidence == withKnownVoid ? high : START;
            MacroDecision decision = macro.tick(context(
                    0L, position, 0.0D, -38.0F, evidence));

            assertEquals("crop-mode-incompatible", decision.status());
            assertEquals(MacroRotationDisposition.RELEASE, decision.rotationDisposition());
            assertTrue(decision.inputs().isEmpty());
            assertEquals(SShapeVerticalCropMacro.State.ALIGNING, macro.state());
        }
    }

    @Test
    void persistentIncompatibleCropCompletesEachRoundWithoutFalseStale() {
        SShapeVerticalCropMacro macro = macro(VerticalCropMode.SUNTZU);
        PlayerSnapshot player = player(START, 0.0F, -38.0F, 0.0D);
        List<Boolean> expectedRightSide = List.of(true, false, true);

        for (int tick = 0; tick < expectedRightSide.size(); tick++) {
            SpatialCaptureRequest request = macro.spatialRequest(player, EPOCH).orElseThrow();
            assertEquals(expectedRightSide.get(tick), request.blocks().stream()
                    .anyMatch(block -> block.x() <= -179));
            SpatialSnapshot snapshot = withBlock(captured(request, START, null),
                    new BlockPosition(-1, 1, 0), Observation.present(crop(
                            new CropFixture("sugar_cane", Map.of(), -38F))));

            MacroDecision decision = macro.tick(context(
                    tick, START, 0.0D, -38.0F, snapshot));
            assertEquals("crop-mode-incompatible", decision.status());
            assertEquals(MacroRotationDisposition.RELEASE, decision.rotationDisposition());
            assertEquals(SShapeVerticalCropMacro.State.ALIGNING, macro.state());
        }
    }

    @Test
    void persistentAlignmentRotationCompletesRoundWithoutFalseStale() {
        SShapeVerticalCropMacro macro = macro(VerticalCropMode.NORMAL);
        PlayerSnapshot player = player(START, 30.0F, 2.8F, 0.0D);
        List<Boolean> expectedRightSide = List.of(true, false, true);

        for (int tick = 0; tick < expectedRightSide.size(); tick++) {
            SpatialCaptureRequest request = macro.spatialRequest(player, EPOCH).orElseThrow();
            assertEquals(expectedRightSide.get(tick), request.blocks().stream()
                    .anyMatch(block -> block.x() <= -179));
            MacroDecision decision = macro.tick(contextYaw(
                    tick, START, 30.0F, 2.8F, captured(request, START, null)));

            assertEquals("aligning", decision.status());
            assertTrue(decision.rotation().isPresent());
            assertEquals(MacroRotationDisposition.REPLACE, decision.rotationDisposition());
            assertEquals(SShapeVerticalCropMacro.State.ALIGNING, macro.state());
        }
    }

    @Test
    void realAlignmentDecisionsKeepOneAdapterRotationLeaseUntilCompletion() {
        SShapeVerticalCropMacro macro = macro(VerticalCropMode.NORMAL);
        PlayerSnapshot player = player(START, 30.0F, 2.8F, 0.0D);
        SpatialCaptureRequest right = macro.spatialRequest(player, EPOCH).orElseThrow();
        MacroDecision first = macro.tick(contextYaw(
                0L, START, 30.0F, 2.8F, captured(right, START, null)));
        SpatialCaptureRequest left = macro.spatialRequest(player, EPOCH).orElseThrow();
        MacroDecision second = macro.tick(contextYaw(
                1L, START, 30.0F, 2.8F, captured(left, START, null)));
        assertEquals(first.rotation(), second.rotation());

        FarmHelperClientRuntime runtime = TestFarmHelperClientRuntimeFactory.create(
                temporaryDirectory.resolve("real-align-lease.json"));
        runtime.worldLoaded();
        runtime.observeConnection(Observation.present(ConnectionSnapshot.multiplayer()));
        ClientSnapshot adapterSnapshot = new ClientSnapshot(
                Observation.present(player),
                Observation.present(new WorldSnapshot(
                        runtime.lifecycle().worldEpoch(), Observation.unknown())),
                Observation.present(ConnectionSnapshot.multiplayer()), Observation.absent());

        assertTrue(TestClientTickAdapterAccess.decisionBeforeRotation(
                runtime, adapterSnapshot, first, () -> { }).isEmpty());
        long revision = runtime.rotation().snapshot().revision();
        assertTrue(TestClientTickAdapterAccess.decisionBeforeRotation(
                runtime, adapterSnapshot, second, () -> { }).isEmpty());
        assertEquals(revision, runtime.rotation().snapshot().revision());
        assertEquals(first.rotation().orElseThrow().yaw(),
                runtime.rotation().snapshot().targetYaw().orElseThrow());
        assertEquals(first.rotation().orElseThrow().pitch(),
                runtime.rotation().snapshot().targetPitch().orElseThrow());

        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2L);
        while (runtime.rotation().snapshot().progress() < 1.0F && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
        assertEquals(revision, runtime.rotation().snapshot().revision());
        TestClientRotationControllerAccess.tick(runtime.rotation());
        assertFalse(runtime.rotation().rotating());
        assertEquals(RotationTerminalReason.COMPLETED,
                runtime.rotation().snapshot().terminalReason().orElseThrow());
    }

    @Test
    void bothCactusModesRequireKnownPassableFrontAndNeverSelectBackward() {
        CropFixture cactus = new CropFixture("cactus", Map.of(), 0F);
        BlockPosition frontBody = new BlockPosition(0, 1, 1);
        for (VerticalCropMode mode : List.of(
                VerticalCropMode.CACTUS_NETHER_WART, VerticalCropMode.SUNTZU)) {
            SShapeVerticalCropMacro passable = macro(mode);
            passable.tick(context(0L, START, 0.0D, mode.targetPitch(0.0D),
                    spatial(START, cactus, true)));
            assertEquals("row-change-dwell", passable.tick(context(1L, START, 0.0D,
                    mode.targetPitch(0.0D), spatial(START, cactus, false))).status());
            MacroDecision forward = passable.tick(context(1L + TimeUnit.MILLISECONDS.toNanos(400L),
                    START, 0.0D, mode.targetPitch(0.0D), spatial(START, cactus, false)));
            assertTrue(forward.inputs().contains(InputAction.FORWARD), mode.name());
            assertFalse(forward.inputs().contains(InputAction.BACKWARD), mode.name());

            SShapeVerticalCropMacro blocked = macro(mode);
            blocked.tick(context(0L, START, 0.0D, mode.targetPitch(0.0D),
                    spatial(START, cactus, true)));
            MacroDecision blockedDecision = blocked.tick(context(1L, START, 0.0D,
                    mode.targetPitch(0.0D), withBlock(spatial(START, cactus, false),
                    frontBody, Observation.present(full()))));
            assertEquals("cactus-lane-blocked", blockedDecision.status(), mode.name());
            assertTrue(blockedDecision.inputs().isEmpty(), mode.name());

            SShapeVerticalCropMacro unknown = macro(mode);
            unknown.tick(context(0L, START, 0.0D, mode.targetPitch(0.0D),
                    spatial(START, cactus, true)));
            MacroDecision unknownDecision = unknown.tick(context(1L, START, 0.0D,
                    mode.targetPitch(0.0D), withBlock(spatial(START, cactus, false),
                    frontBody, Observation.unknown())));
            assertEquals("cactus-lane-unknown", unknownDecision.status(), mode.name());
            assertTrue(unknownDecision.inputs().isEmpty(), mode.name());
        }
    }

    @Test
    void warpDwellRetriesAndPostWarpLedgersUseExactBoundaries() {
        assertWarpDwell(0.0D, 400L);
        assertWarpDwell(Math.nextDown(1.0D), 749L);

        MacroSettings settings = warpSettings();
        SShapeVerticalCropMacro macro = new SShapeVerticalCropMacro(settings, () -> 0.0D);
        macro.onStart();
        macro.tick(context(0L, START, 0.0D, 2.8F, spatial(START, wheat(), true)));
        long first = TimeUnit.MILLISECONDS.toNanos(400L);
        assertTrue(macro.tick(context(first, START, 0.0D, 2.8F,
                spatial(START, wheat(), true))).warp().isPresent());
        for (int attempt = 0; attempt < 5; attempt++) {
            first += TimeUnit.SECONDS.toNanos(5L);
            assertTrue(macro.tick(context(first, START, 0.0D, 2.8F,
                    spatial(START, wheat(), true))).warp().isPresent());
        }

        PositionSnapshot moved = new PositionSnapshot(2.0D, 1.0D, 0.5D);
        long confirmedAt = first + 1L;
        assertEquals("rewarp-confirmed plot=-1", macro.tick(context(confirmedAt, moved, 0.0D,
                2.8F, spatial(moved, wheat(), true), new PlayerPosture(false, true))).status());
        assertEquals("after-warp", macro.tick(context(confirmedAt
                + TimeUnit.MILLISECONDS.toNanos(1_500L) - 1L, moved, 0.0D, 2.8F,
                spatial(moved, wheat(), true), new PlayerPosture(false, true))).status());
        long postAt = confirmedAt + TimeUnit.MILLISECONDS.toNanos(1_500L);
        assertEquals("post-rewarp", macro.tick(context(postAt, moved, 0.0D, 2.8F,
                spatial(moved, wheat(), true), new PlayerPosture(false, true))).status());
        assertEquals("post-rewarp", macro.tick(context(postAt
                + TimeUnit.MILLISECONDS.toNanos(600L) - 1L, moved, 0.0D, 2.8F,
                spatial(moved, wheat(), true), new PlayerPosture(false, true))).status());
        assertFalse(macro.tick(context(postAt + TimeUnit.MILLISECONDS.toNanos(600L),
                moved, 0.0D, 2.8F, spatial(moved, wheat(), true),
                new PlayerPosture(false, true))).status().equals("post-rewarp"));
    }

    @Test
    void persistedSpawnPoseDrivesWarpAndPostWarpRotation() {
        MacroSettings settings = new MacroSettings();
        settings.spawn(new MacroSpawnPose(new RewarpPosition(10, 1, 10),
                91.5F, -12.25F, 7));
        assertTrue(settings.addRewarp(new RewarpPosition(0, 1, 0)));
        SShapeVerticalCropMacro macro = new SShapeVerticalCropMacro(settings, () -> 0.0D);
        macro.onStart();
        macro.tick(context(0L, START, 0.0D, 2.8F, spatial(START, wheat(), true)));
        long requestAt = TimeUnit.MILLISECONDS.toNanos(400L);
        MacroDecision request = macro.tick(context(requestAt, START, 0.0D, 2.8F,
                spatial(START, wheat(), true)));
        assertEquals(91.5F, request.warp().orElseThrow().spawn().yaw());
        assertEquals(-12.25F, request.warp().orElseThrow().spawn().pitch());
        assertEquals(7, request.warp().orElseThrow().spawn().plot());

        PositionSnapshot moved = new PositionSnapshot(2.0D, 1.0D, 0.5D);
        long confirmedAt = requestAt + 1L;
        assertEquals("rewarp-confirmed plot=7", macro.tick(context(confirmedAt, moved, 0.0D,
                2.8F, spatial(moved, wheat(), true), new PlayerPosture(false, true))).status());
        long postAt = confirmedAt + TimeUnit.MILLISECONDS.toNanos(1_500L);
        macro.tick(context(postAt, moved, 0.0D, 2.8F, spatial(moved, wheat(), true),
                new PlayerPosture(false, true)));
        MacroDecision rotation = macro.tick(context(postAt + TimeUnit.MILLISECONDS.toNanos(600L),
                moved, 0.0D, 2.8F, spatial(moved, wheat(), true),
                new PlayerPosture(false, true)));
        assertEquals(91.5F, rotation.rotation().orElseThrow().yaw());
        assertEquals(-12.25F, rotation.rotation().orElseThrow().pitch());
        assertEquals(1_000L, rotation.rotation().orElseThrow().durationMillis());
    }

    @Test
    void airborneWarpSneakUsesExactSampleAndPauseShift() {
        assertAirborneSneak(0.0D, 350L);
        assertAirborneSneak(Math.nextDown(1.0D), 649L);

        double[] draws = {0.0D, 0.0D};
        AtomicInteger index = new AtomicInteger();
        SShapeVerticalCropMacro macro = new SShapeVerticalCropMacro(
                warpSettings(), () -> draws[index.getAndIncrement()]);
        macro.onStart();
        macro.tick(context(0L, START, 0.0D, 2.8F, spatial(START, wheat(), true)));
        long requestAt = TimeUnit.MILLISECONDS.toNanos(400L);
        macro.tick(context(requestAt, START, 0.0D, 2.8F, spatial(START, wheat(), true)));
        PositionSnapshot moved = new PositionSnapshot(2.0D, 1.0D, 0.5D);
        long confirmedAt = requestAt + 1L;
        assertEquals(Set.of(InputAction.SNEAK), macro.tick(context(confirmedAt, moved, 0.0D,
                2.8F, spatial(moved, wheat(), true), new PlayerPosture(true, false))).inputs());
        long pausedAt = confirmedAt + TimeUnit.MILLISECONDS.toNanos(100L);
        macro.onPause(Set.of(dev.hylfrd.farmhelper.macro.MacroPauseCause.FEATURE), pausedAt);
        macro.onResume(pausedAt + TimeUnit.SECONDS.toNanos(1L));
        assertEquals(Set.of(InputAction.SNEAK), macro.tick(context(confirmedAt
                + TimeUnit.SECONDS.toNanos(1L) + TimeUnit.MILLISECONDS.toNanos(350L) - 1L,
                moved, 0.0D, 2.8F, spatial(moved, wheat(), true),
                new PlayerPosture(true, false))).inputs());
        assertTrue(macro.tick(context(confirmedAt + TimeUnit.SECONDS.toNanos(1L)
                + TimeUnit.MILLISECONDS.toNanos(350L), moved, 0.0D, 2.8F,
                spatial(moved, wheat(), true), new PlayerPosture(true, false))).inputs().isEmpty());
    }

    @Test
    void everyActiveTimingLedgerExcludesPausedTime() {
        SShapeVerticalCropMacro warp = new SShapeVerticalCropMacro(warpSettings(), () -> 0.0D);
        warp.onStart();
        warp.tick(context(0L, START, 0.0D, 2.8F, spatial(START, wheat(), true)));
        warp.onPause(Set.of(dev.hylfrd.farmhelper.macro.MacroPauseCause.FEATURE),
                TimeUnit.MILLISECONDS.toNanos(100L));
        warp.onResume(TimeUnit.MILLISECONDS.toNanos(1_100L));
        assertTrue(warp.tick(context(TimeUnit.MILLISECONDS.toNanos(1_400L) - 1L,
                START, 0.0D, 2.8F, spatial(START, wheat(), true))).warp().isEmpty());
        long requestAt = TimeUnit.MILLISECONDS.toNanos(1_400L);
        assertTrue(warp.tick(context(requestAt, START, 0.0D, 2.8F,
                spatial(START, wheat(), true))).warp().isPresent());

        warp.onPause(Set.of(dev.hylfrd.farmhelper.macro.MacroPauseCause.FEATURE),
                requestAt + TimeUnit.SECONDS.toNanos(1L));
        warp.onResume(requestAt + TimeUnit.SECONDS.toNanos(2L));
        assertTrue(warp.tick(context(requestAt + TimeUnit.SECONDS.toNanos(6L) - 1L,
                START, 0.0D, 2.8F, spatial(START, wheat(), true))).warp().isEmpty());
        long retryAt = requestAt + TimeUnit.SECONDS.toNanos(6L);
        assertTrue(warp.tick(context(retryAt, START, 0.0D, 2.8F,
                spatial(START, wheat(), true))).warp().isPresent());

        PositionSnapshot moved = new PositionSnapshot(2.0D, 1.0D, 0.5D);
        long confirmedAt = retryAt + 1L;
        warp.tick(context(confirmedAt, moved, 0.0D, 2.8F, spatial(moved, wheat(), true),
                new PlayerPosture(false, true)));
        warp.onPause(Set.of(dev.hylfrd.farmhelper.macro.MacroPauseCause.FEATURE),
                confirmedAt + TimeUnit.MILLISECONDS.toNanos(500L));
        warp.onResume(confirmedAt + TimeUnit.MILLISECONDS.toNanos(1_500L));
        assertEquals("after-warp", warp.tick(context(confirmedAt
                + TimeUnit.MILLISECONDS.toNanos(2_500L) - 1L, moved, 0.0D, 2.8F,
                spatial(moved, wheat(), true), new PlayerPosture(false, true))).status());
        long postAt = confirmedAt + TimeUnit.MILLISECONDS.toNanos(2_500L);
        assertEquals("post-rewarp", warp.tick(context(postAt, moved, 0.0D, 2.8F,
                spatial(moved, wheat(), true), new PlayerPosture(false, true))).status());
        warp.onPause(Set.of(dev.hylfrd.farmhelper.macro.MacroPauseCause.FEATURE),
                postAt + TimeUnit.MILLISECONDS.toNanos(100L));
        warp.onResume(postAt + TimeUnit.MILLISECONDS.toNanos(1_100L));
        assertEquals("post-rewarp", warp.tick(context(postAt
                + TimeUnit.MILLISECONDS.toNanos(1_600L) - 1L, moved, 0.0D, 2.8F,
                spatial(moved, wheat(), true), new PlayerPosture(false, true))).status());
        assertFalse(warp.tick(context(postAt + TimeUnit.MILLISECONDS.toNanos(1_600L),
                moved, 0.0D, 2.8F, spatial(moved, wheat(), true),
                new PlayerPosture(false, true))).status().equals("post-rewarp"));

        SShapeVerticalCropMacro lane = macro(VerticalCropMode.NORMAL);
        lane.tick(context(0L, START, 0.0D, 2.8F, spatial(START, wheat(), true)));
        lane.tick(context(1L, START, 0.0D, 2.8F, spatial(START, wheat(), false)));
        lane.onPause(Set.of(dev.hylfrd.farmhelper.macro.MacroPauseCause.FEATURE),
                TimeUnit.MILLISECONDS.toNanos(100L));
        lane.onResume(TimeUnit.MILLISECONDS.toNanos(1_100L));
        assertEquals("row-change-dwell", lane.tick(context(
                TimeUnit.MILLISECONDS.toNanos(1_400L), START, 0.0D, 2.8F,
                spatial(START, wheat(), false))).status());
        assertEquals("switching-lane", lane.tick(context(
                TimeUnit.MILLISECONDS.toNanos(1_400L) + 1L, START, 0.0D, 2.8F,
                spatial(START, wheat(), false))).status());

        SShapeVerticalCropMacro drop = macro(VerticalCropMode.NORMAL);
        drop.tick(context(0L, START, 0.0D, 2.8F, spatial(START, wheat(), true)));
        PositionSnapshot lowerRow = new PositionSnapshot(0.5D, 2.6D, 0.5D);
        drop.tick(context(1L, lowerRow, -0.1D, 2.8F,
                spatial(lowerRow, wheat(), true)));
        drop.onPause(Set.of(dev.hylfrd.farmhelper.macro.MacroPauseCause.FEATURE),
                TimeUnit.MILLISECONDS.toNanos(100L));
        drop.onResume(TimeUnit.MILLISECONDS.toNanos(1_100L));
        assertEquals("dropping", drop.tick(context(
                TimeUnit.MILLISECONDS.toNanos(1_350L), lowerRow, 0.0D, 2.8F,
                spatial(lowerRow, wheat(), true))).status());
        assertEquals("drop-complete", drop.tick(context(
                TimeUnit.MILLISECONDS.toNanos(1_350L) + 1L, lowerRow, 0.0D, 2.8F,
                spatial(lowerRow, wheat(), true))).status());

        SShapeVerticalCropMacro recovery = macro(VerticalCropMode.NORMAL);
        SpatialSnapshot row = spatial(START, wheat(), true);
        recovery.tick(context(0L, START, 0.0D, 2.8F, row));
        recovery.tick(context(TimeUnit.MILLISECONDS.toNanos(500L), START, 0.0D, 2.8F, row));
        recovery.tick(context(TimeUnit.MILLISECONDS.toNanos(1_000L), START, 0.0D, 2.8F, row));
        recovery.tick(context(TimeUnit.MILLISECONDS.toNanos(1_500L), START, 0.0D, 2.8F, row));
        recovery.onPause(Set.of(dev.hylfrd.farmhelper.macro.MacroPauseCause.FEATURE),
                TimeUnit.MILLISECONDS.toNanos(1_600L));
        recovery.onResume(TimeUnit.MILLISECONDS.toNanos(2_600L));
        assertEquals("recovering", recovery.tick(context(TimeUnit.MILLISECONDS.toNanos(3_100L) - 1L,
                START, 0.0D, 2.8F, row)).status());
        assertFalse(recovery.tick(context(TimeUnit.MILLISECONDS.toNanos(3_100L),
                START, 0.0D, 2.8F, row)).status().equals("recovering"));
    }

    @Test
    void mappedModesEmitExactSideInputClaims() {
        for (VerticalCropMode mode : VerticalCropMode.values()) {
            MacroSettings settings = new MacroSettings();
            settings.mode(mode);
            CropFixture fixture = switch (mode) {
                case NORMAL -> wheat();
                case PUMPKIN_MELON, MELONGKINGDE -> new CropFixture("melon", Map.of(), 0F);
                case CACTUS_NETHER_WART -> new CropFixture("cactus", Map.of(), 0F);
                case SUNTZU -> new CropFixture("cactus", Map.of(), 0F);
                case COCOA -> new CropFixture("cocoa", Map.of("age", "2"), 0F);
            };
            SShapeVerticalCropMacro macro = new SShapeVerticalCropMacro(settings, () -> 0.0D);
            macro.onStart();
            MacroDecision decision = macro.tick(context(0L, START, 0.0D,
                    mode.targetPitch(0.0D), spatial(START, fixture, true)));
            Set<InputAction> expected = mode.forwardAssist()
                    ? Set.of(InputAction.RIGHT, InputAction.ATTACK, InputAction.FORWARD)
                    : Set.of(InputAction.RIGHT, InputAction.ATTACK);
            assertEquals(expected, decision.inputs(), mode.name());
        }
    }

    @Test
    void rowChangeDelayUsesFourHundredThroughFiveHundredNinetyNineMilliseconds() {
        assertRowDelay(0.0D, 399L, 400L);
        assertRowDelay(Math.nextDown(1.0D), 598L, 599L);
    }

    @Test
    void dropThresholdMatrixIsStrictAndReleasesAirborneInput() {
        SShapeVerticalCropMacro exact = macro(VerticalCropMode.NORMAL);
        exact.tick(context(0L, START, 0.0D, 2.8F, spatial(START, wheat(), true)));
        PositionSnapshot exactThreshold = new PositionSnapshot(0.5D, 2.5D, 0.5D);
        MacroDecision exactDecision = exact.tick(context(1L, exactThreshold, -0.1D, 2.8F,
                spatial(exactThreshold, wheat(), true)));
        assertFalse(exact.state() == SShapeVerticalCropMacro.State.DROPPING);

        SShapeVerticalCropMacro above = macro(VerticalCropMode.NORMAL);
        above.tick(context(0L, START, 0.0D, 2.8F, spatial(START, wheat(), true)));
        PositionSnapshot overThreshold = new PositionSnapshot(0.5D, 2.501D, 0.5D);
        MacroDecision airborne = above.tick(context(1L, overThreshold, -0.1D, 2.8F,
                spatial(overThreshold, wheat(), true)));
        assertEquals(SShapeVerticalCropMacro.State.DROPPING, above.state());
        assertTrue(airborne.inputs().isEmpty());
        MacroDecision released = above.tick(context(TimeUnit.MILLISECONDS.toNanos(350L) + 1L,
                overThreshold, -0.1D, 2.8F, spatial(overThreshold, wheat(), true)));
        assertTrue(released.inputs().isEmpty());
    }

    @Test
    void pauseResumePreservesStateButTerminalStopClearsEverything() {
        SShapeVerticalCropMacro macro = macro(VerticalCropMode.NORMAL);
        macro.tick(context(0L, START, 0.0D, 2.8F, spatial(START, wheat(), true)));
        assertEquals(SShapeVerticalCropMacro.State.FARMING, macro.state());

        long pauseAt = TimeUnit.MILLISECONDS.toNanos(100L);
        long resumeAt = TimeUnit.MILLISECONDS.toNanos(1_100L);
        macro.onPause(Set.of(dev.hylfrd.farmhelper.macro.MacroPauseCause.FEATURE), pauseAt);
        macro.onResume(resumeAt);
        MacroDecision beforeActiveBoundary = macro.tick(context(
                TimeUnit.MILLISECONDS.toNanos(1_499L), START, 0.0D, 2.8F,
                spatial(START, wheat(), true)));
        MacroDecision atActiveBoundary = macro.tick(context(
                TimeUnit.MILLISECONDS.toNanos(1_500L), START, 0.0D, 2.8F,
                spatial(START, wheat(), true)));
        assertEquals("farming-right", beforeActiveBoundary.status());
        assertEquals("row-retry-1", atActiveBoundary.status());

        macro.onStop(dev.hylfrd.farmhelper.macro.MacroTerminalReason.DISCONNECT);
        assertEquals(SShapeVerticalCropMacro.State.STOPPED, macro.state());
        macro.onStart();
        assertEquals(SShapeVerticalCropMacro.State.ALIGNING, macro.state());
    }

    @Test
    void phasedCaptureExhaustsFirstSideThenFindsSecondSideObstacle() {
        SShapeVerticalCropMacro macro = macro(VerticalCropMode.NORMAL);
        PlayerSnapshot player = player(START, 0.0F, 2.8F, 0.0D);

        SpatialCaptureRequest right = macro.spatialRequest(player, EPOCH).orElseThrow();
        assertTrue(right.requestToken() > 0L);
        assertTrue(right.bounds().width() <= SpatialCaptureRequest.MAX_AXIS_SPAN);
        assertTrue(right.blocks().stream().anyMatch(block -> block.x() <= -179));
        MacroDecision first = macro.tick(context(0L, START, 0.0D, 2.8F,
                captured(right, START, null)));
        assertEquals("row-obstacle-scan-pending", first.status());
        assertEquals(SShapeVerticalCropMacro.State.ALIGNING, macro.state());

        SpatialCaptureRequest left = macro.spatialRequest(player, EPOCH).orElseThrow();
        assertTrue(left.bounds().width() <= SpatialCaptureRequest.MAX_AXIS_SPAN);
        assertTrue(left.blocks().stream().anyMatch(block -> block.x() >= 179));
        assertFalse(right.requestToken() == left.requestToken());
        MacroDecision second = macro.tick(context(1L, START, 0.0D, 2.8F,
                captured(left, START, new BlockPosition(10, 1, 0))));

        assertEquals("farming-right-obstacle", second.status());
        assertEquals(Set.of(InputAction.RIGHT, InputAction.ATTACK), second.inputs());
        assertEquals(SShapeVerticalCropMacro.State.FARMING, macro.state());
    }

    @Test
    void longSideRequestsCaptureBodyAndSupportAtBothDistanceBounds() {
        for (PositionSnapshot position : List.of(
                START, new PositionSnapshot(0.71D, 1.0D, 0.71D))) {
            for (float yaw : List.of(0.0F, 90.0F, 180.0F, 270.0F)) {
                SShapeVerticalCropMacro macro = macro(VerticalCropMode.NORMAL);
                SpatialCaptureRequest request = macro.spatialRequest(
                        player(position, yaw, 2.8F, 0.0D), EPOCH).orElseThrow();
                RelativeFrame frame = RelativeFrame.cardinal(yaw);
                for (int distance : List.of(1, 179)) {
                    BoxSnapshot body = new BoxSnapshot(
                            position.x() - 0.3D, position.y(), position.z() - 0.3D,
                            position.x() + 0.3D, position.y() + 1.8D, position.z() + 0.3D)
                            .move(frame.rightX() * (double) distance, 0.0D,
                                    frame.rightZ() * (double) distance);
                    assertCaptured(request, body);
                    assertCaptured(request, new BoxSnapshot(
                            body.minX(), body.minY() - 0.01D, body.minZ(),
                            body.maxX(), body.minY(), body.maxZ()));
                }
                assertTrue(request.blocks().size() <= SpatialCaptureRequest.MAX_BLOCKS);
                assertTrue(request.bounds().width() <= SpatialCaptureRequest.MAX_AXIS_SPAN);
                assertTrue(request.bounds().height() <= SpatialCaptureRequest.MAX_AXIS_SPAN);
                assertTrue(request.bounds().depth() <= SpatialCaptureRequest.MAX_AXIS_SPAN);
            }
        }
    }

    @Test
    void phasedCaptureFailsClosedAfterBothSidesExhaust() {
        SShapeVerticalCropMacro macro = macro(VerticalCropMode.NORMAL);
        PlayerSnapshot player = player(START, 0.0F, 2.8F, 0.0D);
        SpatialCaptureRequest right = macro.spatialRequest(player, EPOCH).orElseThrow();
        assertEquals("row-obstacle-scan-pending", macro.tick(context(
                0L, START, 0.0D, 2.8F, captured(right, START, null))).status());
        SpatialCaptureRequest left = macro.spatialRequest(player, EPOCH).orElseThrow();

        MacroDecision unresolved = macro.tick(context(
                1L, START, 0.0D, 2.8F, captured(left, START, null)));

        assertEquals("row-direction-unresolved", unresolved.status());
        assertEquals(MacroRotationDisposition.RELEASE, unresolved.rotationDisposition());
        assertTrue(unresolved.inputs().isEmpty());
        assertEquals(SShapeVerticalCropMacro.State.ALIGNING, macro.state());
        SpatialCaptureRequest restarted = macro.spatialRequest(player, EPOCH).orElseThrow();
        assertTrue(restarted.blocks().stream().anyMatch(block -> block.x() <= -179));
    }

    @Test
    void unknownFirstSideStillAdvancesThenRestartsAfterReconciliation() {
        SShapeVerticalCropMacro macro = macro(VerticalCropMode.NORMAL);
        PlayerSnapshot player = player(START, 0.0F, 2.8F, 0.0D);
        SpatialCaptureRequest right = macro.spatialRequest(player, EPOCH).orElseThrow();
        SpatialSnapshot unknownRight = withBlock(captured(right, START, null),
                new BlockPosition(-10, 1, 0), Observation.unknown());

        assertEquals("row-obstacle-scan-pending", macro.tick(context(
                0L, START, 0.0D, 2.8F, unknownRight)).status());
        SpatialCaptureRequest left = macro.spatialRequest(player, EPOCH).orElseThrow();
        assertTrue(left.blocks().stream().anyMatch(block -> block.x() >= 179));

        MacroDecision reconciled = macro.tick(context(
                1L, START, 0.0D, 2.8F, captured(left, START, null)));
        assertEquals("row-obstacle-scan-unknown", reconciled.status());
        assertEquals(MacroRotationDisposition.RELEASE, reconciled.rotationDisposition());
        SpatialCaptureRequest restarted = macro.spatialRequest(player, EPOCH).orElseThrow();
        assertTrue(restarted.blocks().stream().anyMatch(block -> block.x() <= -179));
    }

    @Test
    void unknownAtAnyScanBoundaryNeverBecomesExhausted() {
        PositionSnapshot scanPosition = new PositionSnapshot(0.29D, 1.0D, 0.5D);
        PlayerSnapshot player = player(scanPosition, 0.0F, 2.8F, 0.0D);
        for (int distance : List.of(1, 178, 179)) {
            SShapeVerticalCropMacro macro = macro(VerticalCropMode.NORMAL);
            SpatialCaptureRequest right = macro.spatialRequest(player, EPOCH).orElseThrow();
            SpatialSnapshot unknown = withBlock(captured(right, scanPosition, null),
                    new BlockPosition(-distance - 1, 1, 0), Observation.unknown());
            assertEquals("row-obstacle-scan-pending", macro.tick(context(
                    0L, scanPosition, 0.0D, 2.8F, unknown)).status(), "distance=" + distance);
            SpatialCaptureRequest left = macro.spatialRequest(player, EPOCH).orElseThrow();
            MacroDecision reconciled = macro.tick(context(
                    1L, scanPosition, 0.0D, 2.8F, captured(left, scanPosition, null)));
            assertEquals("row-obstacle-scan-unknown", reconciled.status(),
                    "distance=" + distance);
            assertTrue(reconciled.inputs().isEmpty());
        }

        SShapeVerticalCropMacro bothLastUnknown = macro(VerticalCropMode.NORMAL);
        SpatialCaptureRequest right = bothLastUnknown.spatialRequest(player, EPOCH).orElseThrow();
        bothLastUnknown.tick(context(0L, scanPosition, 0.0D, 2.8F,
                withBlock(captured(right, scanPosition, null),
                        new BlockPosition(-180, 1, 0), Observation.unknown())));
        SpatialCaptureRequest left = bothLastUnknown.spatialRequest(player, EPOCH).orElseThrow();
        MacroDecision finalDecision = bothLastUnknown.tick(context(
                1L, scanPosition, 0.0D, 2.8F,
                withBlock(captured(left, scanPosition, null),
                        new BlockPosition(179, 1, 0), Observation.unknown())));
        assertEquals("row-obstacle-scan-unknown", finalDecision.status());
        assertEquals(MacroRotationDisposition.RELEASE, finalDecision.rotationDisposition());
        assertTrue(finalDecision.inputs().isEmpty());
        assertEquals(SShapeVerticalCropMacro.State.ALIGNING, bothLastUnknown.state());
    }

    @Test
    void opaqueScanTokenRejectsStalePhaseAndRestartsFirstSide() {
        SShapeVerticalCropMacro macro = macro(VerticalCropMode.NORMAL);
        PlayerSnapshot player = player(START, 0.0F, 2.8F, 0.0D);
        SpatialCaptureRequest request = macro.spatialRequest(player, EPOCH).orElseThrow();
        SpatialSnapshot captured = captured(request, START, null);
        SpatialSnapshot stale = new SpatialSnapshot(
                captured.worldEpoch(), request.requestToken() + 1L, captured.bounds(),
                captured.minY(), captured.maxY(), captured.playerBox(), captured.chunks());

        MacroDecision rejected = macro.tick(context(0L, START, 0.0D, 2.8F, stale));

        assertEquals("row-obstacle-scan-stale", rejected.status());
        assertFalse(macro.spatialScanPending());
        SpatialCaptureRequest restarted = macro.spatialRequest(player, EPOCH).orElseThrow();
        assertTrue(restarted.blocks().stream().anyMatch(block -> block.x() <= -179));
        assertFalse(request.requestToken() == restarted.requestToken());
    }

    @Test
    void unknownCaptureAdvancesBothSidesButAbsentAndUnknownPlayerDoNot() {
        PlayerSnapshot player = player(START, 0.0F, 2.8F, 0.0D);
        SShapeVerticalCropMacro unknownCapture = macro(VerticalCropMode.NORMAL);
        SpatialCaptureRequest right = unknownCapture.spatialRequest(player, EPOCH).orElseThrow();
        assertEquals("spatial-unknown", unknownCapture.tick(contextSpatial(
                0L, Observation.present(player), Observation.unknown())).status());
        SpatialCaptureRequest left = unknownCapture.spatialRequest(player, EPOCH).orElseThrow();
        assertFalse(right.requestToken() == left.requestToken());
        assertTrue(left.blocks().stream().anyMatch(block -> block.x() >= 179));
        MacroDecision secondUnknown = unknownCapture.tick(contextSpatial(
                1L, Observation.present(player), Observation.unknown()));
        assertEquals("row-obstacle-scan-unknown", secondUnknown.status());
        assertEquals(MacroRotationDisposition.RELEASE, secondUnknown.rotationDisposition());
        SpatialCaptureRequest restarted = unknownCapture.spatialRequest(player, EPOCH).orElseThrow();
        assertTrue(restarted.blocks().stream().anyMatch(block -> block.x() <= -179));

        SShapeVerticalCropMacro absentCapture = macro(VerticalCropMode.NORMAL);
        SpatialCaptureRequest absentPending = absentCapture.spatialRequest(player, EPOCH).orElseThrow();
        assertEquals("spatial-unknown", absentCapture.tick(contextSpatial(
                0L, Observation.present(player), Observation.absent())).status());
        assertEquals(absentPending.requestToken(), absentCapture
                .spatialRequest(player, EPOCH).orElseThrow().requestToken());

        SShapeVerticalCropMacro unknownPlayer = macro(VerticalCropMode.NORMAL);
        SpatialCaptureRequest playerPending = unknownPlayer.spatialRequest(player, EPOCH).orElseThrow();
        assertEquals("spatial-unknown", unknownPlayer.tick(contextSpatial(
                0L, Observation.unknown(), Observation.unknown())).status());
        SpatialCaptureRequest playerRestarted = unknownPlayer.spatialRequest(player, EPOCH).orElseThrow();
        assertFalse(playerPending.requestToken() == playerRestarted.requestToken());
        assertTrue(playerRestarted.blocks().stream().anyMatch(block -> block.x() <= -179));
    }

    @Test
    void capturedSnapshotMustMatchPendingBoundsAndPlayerFootprint() {
        PlayerSnapshot player = player(START, 0.0F, 2.8F, 0.0D);
        SShapeVerticalCropMacro wrongBoundsMacro = macro(VerticalCropMode.NORMAL);
        SpatialCaptureRequest boundsRequest = wrongBoundsMacro
                .spatialRequest(player, EPOCH).orElseThrow();
        SpatialSnapshot normal = captured(boundsRequest, START, null);
        BoxSnapshot wrongBounds = new BoxSnapshot(
                normal.bounds().minX(), normal.bounds().minY(), normal.bounds().minZ(),
                normal.bounds().maxX(), normal.bounds().maxY(), normal.bounds().maxZ() + 1.0D);
        SpatialSnapshot wrongBoundsSnapshot = new SpatialSnapshot(
                normal.worldEpoch(), normal.requestToken(), wrongBounds,
                normal.minY(), normal.maxY(), normal.playerBox(), normal.chunks());
        assertEquals("row-obstacle-scan-stale", wrongBoundsMacro.tick(context(
                0L, START, 0.0D, 2.8F, wrongBoundsSnapshot)).status());
        assertTrue(wrongBoundsMacro.spatialRequest(player, EPOCH).orElseThrow()
                .blocks().stream().anyMatch(block -> block.x() <= -179));

        SShapeVerticalCropMacro wrongPlayerBoxMacro = macro(VerticalCropMode.NORMAL);
        SpatialCaptureRequest playerBoxRequest = wrongPlayerBoxMacro
                .spatialRequest(player, EPOCH).orElseThrow();
        SpatialSnapshot normalPlayerBox = captured(playerBoxRequest, START, null);
        SpatialSnapshot wrongPlayerBox = new SpatialSnapshot(
                normalPlayerBox.worldEpoch(), normalPlayerBox.requestToken(),
                normalPlayerBox.bounds(), normalPlayerBox.minY(), normalPlayerBox.maxY(),
                normalPlayerBox.playerBox().move(1.0D, 0.0D, 0.0D), normalPlayerBox.chunks());
        assertEquals("row-obstacle-scan-stale", wrongPlayerBoxMacro.tick(context(
                0L, START, 0.0D, 2.8F, wrongPlayerBox)).status());

        SShapeVerticalCropMacro productionShape = macro(VerticalCropMode.NORMAL);
        SpatialCaptureRequest productionRequest = productionShape
                .spatialRequest(player, EPOCH).orElseThrow();
        assertEquals("row-obstacle-scan-pending", productionShape.tick(context(
                0L, START, 0.0D, 2.8F,
                captured(productionRequest, START, null))).status());
    }

    @Test
    void staleWorldDoesNotAdvancePhaseAndHighCaptureStaysBounded() {
        SShapeVerticalCropMacro staleMacro = macro(VerticalCropMode.NORMAL);
        PlayerSnapshot player = player(START, 0.0F, 2.8F, 0.0D);
        SpatialCaptureRequest request = staleMacro.spatialRequest(player, EPOCH).orElseThrow();
        SpatialSnapshot captured = captured(request, START, null);
        SpatialSnapshot staleWorld = new SpatialSnapshot(
                EPOCH + 1L, captured.requestToken(), captured.bounds(), captured.minY(),
                captured.maxY(), captured.playerBox(), captured.chunks());
        assertEquals("row-obstacle-scan-stale", staleMacro.tick(context(
                0L, START, 0.0D, 2.8F, staleWorld)).status());
        SpatialCaptureRequest restarted = staleMacro.spatialRequest(player, EPOCH).orElseThrow();
        assertTrue(restarted.blocks().stream().anyMatch(block -> block.x() <= -179));

        PositionSnapshot high = new PositionSnapshot(0.5D, 319.0D, 0.5D);
        SShapeVerticalCropMacro highMacro = macro(VerticalCropMode.NORMAL);
        SpatialCaptureRequest highRequest = highMacro.spatialRequest(
                player(high, 0.0F, 2.8F, 0.0D), EPOCH).orElseThrow();
        assertTrue(highRequest.bounds().height() <= SpatialCaptureRequest.MAX_AXIS_SPAN);
        MacroDecision highDecision = highMacro.tick(context(
                0L, high, 0.0D, 2.8F, captured(highRequest, high, null)));
        assertEquals(MacroRotationDisposition.RELEASE, highDecision.rotationDisposition());
        assertTrue(highDecision.inputs().isEmpty());
    }

    @Test
    void scanRoundAnchorAllowsStableEpsilonAndRejectsShapeOrFrameChanges() {
        SShapeVerticalCropMacro macro = macro(VerticalCropMode.NORMAL);
        SpatialCaptureRequest initial = macro.spatialRequest(
                player(START, 0.0F, 2.8F, 0.0D), EPOCH).orElseThrow();
        long initialGeneration = macro.spatialScanGeneration();

        PositionSnapshot epsilon = new PositionSnapshot(
                START.x() + 0.000_001D, START.y(), START.z());
        assertEquals(initial.requestToken(), macro.spatialRequest(
                player(epsilon, 0.0F, 2.8F, 0.0D), EPOCH).orElseThrow().requestToken());
        assertEquals(initialGeneration, macro.spatialScanGeneration());

        PositionSnapshot footprintBoundary = new PositionSnapshot(0.71D, 1.0D, 0.5D);
        SpatialCaptureRequest shifted = macro.spatialRequest(
                player(footprintBoundary, 0.0F, 2.8F, 0.0D), EPOCH).orElseThrow();
        assertFalse(initial.requestToken() == shifted.requestToken());
        assertTrue(macro.spatialScanGeneration() > initialGeneration);
        assertTrue(shifted.blocks().stream().anyMatch(block -> block.x() <= -179));

        PositionSnapshot nextBlock = new PositionSnapshot(1.5D, 1.0D, 0.5D);
        SpatialCaptureRequest moved = macro.spatialRequest(
                player(nextBlock, 0.0F, 2.8F, 0.0D), EPOCH).orElseThrow();
        assertFalse(shifted.requestToken() == moved.requestToken());

        SpatialCaptureRequest rotated = macro.spatialRequest(
                player(nextBlock, 90.0F, 2.8F, 0.0D), EPOCH).orElseThrow();
        assertFalse(moved.requestToken() == rotated.requestToken());
        assertTrue(rotated.blocks().stream().anyMatch(block -> block.z() <= -179));
    }

    @Test
    void secondPhaseNeverCombinesEvidenceFromAnotherAnchor() {
        SShapeVerticalCropMacro macro = macro(VerticalCropMode.NORMAL);
        PlayerSnapshot initialPlayer = player(START, 0.0F, 2.8F, 0.0D);
        SpatialCaptureRequest first = macro.spatialRequest(initialPlayer, EPOCH).orElseThrow();
        assertEquals("row-obstacle-scan-pending", macro.tick(context(
                0L, START, 0.0D, 2.8F, captured(first, START, null))).status());
        long cachedGeneration = macro.spatialScanGeneration();

        PositionSnapshot moved = new PositionSnapshot(1.5D, 1.0D, 0.5D);
        SpatialCaptureRequest restarted = macro.spatialRequest(
                player(moved, 0.0F, 2.8F, 0.0D), EPOCH).orElseThrow();

        assertTrue(macro.spatialScanGeneration() > cachedGeneration);
        assertTrue(restarted.blocks().stream().anyMatch(block -> block.x() <= -178));
        MacroDecision newFirstSide = macro.tick(context(
                1L, moved, 0.0D, 2.8F, captured(restarted, moved, null)));
        assertEquals("row-obstacle-scan-pending", newFirstSide.status());
        assertEquals(SShapeVerticalCropMacro.State.ALIGNING, macro.state());
    }

    @Test
    void resumeAndTerminalBoundariesInvalidateTheWholeScanRound() {
        SShapeVerticalCropMacro macro = macro(VerticalCropMode.NORMAL);
        PlayerSnapshot player = player(START, 0.0F, 2.8F, 0.0D);
        SpatialCaptureRequest beforePause = macro.spatialRequest(player, EPOCH).orElseThrow();
        long beforeGeneration = macro.spatialScanGeneration();
        macro.onPause(Set.of(dev.hylfrd.farmhelper.macro.MacroPauseCause.FEATURE), 10L);
        macro.onResume(20L);

        PositionSnapshot movedDuringPause = new PositionSnapshot(1.5D, 1.0D, 0.5D);
        SpatialCaptureRequest afterResume = macro.spatialRequest(
                player(movedDuringPause, 90.0F, 2.8F, 0.0D), EPOCH).orElseThrow();
        assertFalse(beforePause.requestToken() == afterResume.requestToken());
        assertTrue(macro.spatialScanGeneration() > beforeGeneration);
        assertTrue(afterResume.blocks().stream().anyMatch(block -> block.z() <= -179));
        assertEquals("row-obstacle-scan-stale", macro.tick(contextYaw(
                21L, movedDuringPause, 90.0F, 2.8F,
                captured(beforePause, START, null))).status());

        long beforeStop = macro.spatialScanGeneration();
        macro.onStop(dev.hylfrd.farmhelper.macro.MacroTerminalReason.DISCONNECT);
        assertFalse(macro.spatialScanPending());
        assertTrue(macro.spatialScanGeneration() > beforeStop);
        macro.onStart();
        SpatialCaptureRequest afterStart = macro.spatialRequest(player, EPOCH).orElseThrow();
        assertTrue(afterStart.blocks().stream().anyMatch(block -> block.x() <= -179));
    }

    @Test
    void rowRecoveryAndRewarpInvalidateTheWholeScanRound() {
        SShapeVerticalCropMacro farming = macro(VerticalCropMode.NORMAL);
        PlayerSnapshot player = player(START, 0.0F, 2.8F, 0.0D);
        SpatialCaptureRequest rowRequest = farming.spatialRequest(player, EPOCH).orElseThrow();
        SpatialSnapshot rowCapture = withBlock(captured(rowRequest, START, null),
                new BlockPosition(-1, 1, 0), Observation.present(crop(wheat())));
        long beforeRow = farming.spatialScanGeneration();
        assertEquals("farming-right", farming.tick(context(
                0L, START, 0.0D, 2.8F, rowCapture)).status());
        assertTrue(farming.spatialScanGeneration() > beforeRow);
        assertFalse(farming.spatialScanPending());

        long beforeRecovery = farming.spatialScanGeneration();
        SpatialSnapshot activeRow = spatial(START, wheat(), true);
        farming.tick(context(TimeUnit.MILLISECONDS.toNanos(500L),
                START, 0.0D, 2.8F, activeRow));
        farming.tick(context(TimeUnit.MILLISECONDS.toNanos(1_000L),
                START, 0.0D, 2.8F, activeRow));
        assertEquals("row-recovery", farming.tick(context(TimeUnit.MILLISECONDS.toNanos(1_500L),
                START, 0.0D, 2.8F, activeRow)).status());
        assertTrue(farming.spatialScanGeneration() > beforeRecovery);

        SShapeVerticalCropMacro rewarp = new SShapeVerticalCropMacro(warpSettings(), () -> 0.0D);
        rewarp.onStart();
        SpatialCaptureRequest warpRequest = rewarp.spatialRequest(player, EPOCH).orElseThrow();
        long beforeWarp = rewarp.spatialScanGeneration();
        assertEquals("rewarp-dwell", rewarp.tick(context(
                0L, START, 0.0D, 2.8F, captured(warpRequest, START, null))).status());
        assertTrue(rewarp.spatialScanGeneration() > beforeWarp);
        assertFalse(rewarp.spatialScanPending());
    }

    private static void assertRowDelay(double delayDraw, long beforeMillis, long atMillis) {
        double[] draws = {0.0D, 0.0D, delayDraw};
        AtomicInteger index = new AtomicInteger();
        MacroSettings settings = new MacroSettings();
        SShapeVerticalCropMacro macro = new SShapeVerticalCropMacro(
                settings, () -> draws[index.getAndIncrement()]);
        macro.onStart();
        macro.tick(context(0L, START, 0.0D, 2.8F, spatial(START, wheat(), true)));
        assertEquals("row-change-dwell", macro.tick(context(1L,
                START, 0.0D, 2.8F, spatial(START, wheat(), false))).status());
        assertEquals("row-change-dwell", macro.tick(context(TimeUnit.MILLISECONDS.toNanos(beforeMillis),
                START, 0.0D, 2.8F, spatial(START, wheat(), false))).status());
        assertEquals("switching-lane", macro.tick(context(TimeUnit.MILLISECONDS.toNanos(atMillis) + 1L,
                START, 0.0D, 2.8F, spatial(START, wheat(), false))).status());
    }

    private static MacroSettings warpSettings() {
        MacroSettings settings = new MacroSettings();
        settings.spawn(new RewarpPosition(10, 1, 10));
        assertTrue(settings.addRewarp(new RewarpPosition(0, 1, 0)));
        return settings;
    }

    private static void assertWarpDwell(double draw, long durationMillis) {
        SShapeVerticalCropMacro macro = new SShapeVerticalCropMacro(warpSettings(), () -> draw);
        macro.onStart();
        macro.tick(context(11L, START, 0.0D, 2.8F, spatial(START, wheat(), true)));
        assertTrue(macro.tick(context(11L + TimeUnit.MILLISECONDS.toNanos(durationMillis) - 1L,
                START, 0.0D, 2.8F, spatial(START, wheat(), true))).warp().isEmpty());
        assertTrue(macro.tick(context(11L + TimeUnit.MILLISECONDS.toNanos(durationMillis),
                START, 0.0D, 2.8F, spatial(START, wheat(), true))).warp().isPresent());
    }

    private static void assertAirborneSneak(double holdDraw, long durationMillis) {
        double[] draws = {0.0D, holdDraw};
        AtomicInteger index = new AtomicInteger();
        SShapeVerticalCropMacro macro = new SShapeVerticalCropMacro(
                warpSettings(), () -> draws[index.getAndIncrement()]);
        macro.onStart();
        macro.tick(context(0L, START, 0.0D, 2.8F, spatial(START, wheat(), true)));
        long requestAt = TimeUnit.MILLISECONDS.toNanos(400L);
        macro.tick(context(requestAt, START, 0.0D, 2.8F, spatial(START, wheat(), true)));
        PositionSnapshot moved = new PositionSnapshot(2.0D, 1.0D, 0.5D);
        long confirmedAt = requestAt + 1L;
        assertEquals(Set.of(InputAction.SNEAK), macro.tick(context(confirmedAt, moved, 0.0D,
                2.8F, spatial(moved, wheat(), true), new PlayerPosture(true, false))).inputs());
        assertEquals(Set.of(InputAction.SNEAK), macro.tick(context(confirmedAt
                + TimeUnit.MILLISECONDS.toNanos(durationMillis) - 1L, moved, 0.0D, 2.8F,
                spatial(moved, wheat(), true), new PlayerPosture(true, false))).inputs());
        assertTrue(macro.tick(context(confirmedAt + TimeUnit.MILLISECONDS.toNanos(durationMillis),
                moved, 0.0D, 2.8F, spatial(moved, wheat(), true),
                new PlayerPosture(true, false))).inputs().isEmpty());
        assertEquals(2, index.get());
        assertEquals("rewarp-confirmed plot=-1", macro.tick(context(confirmedAt
                + TimeUnit.MILLISECONDS.toNanos(durationMillis) + 1L, moved, 0.0D, 2.8F,
                spatial(moved, wheat(), true), new PlayerPosture(true, true))).status());
    }

    private static SShapeVerticalCropMacro macro(VerticalCropMode mode) {
        MacroSettings settings = new MacroSettings();
        settings.mode(mode);
        SShapeVerticalCropMacro macro = new SShapeVerticalCropMacro(settings, () -> 0.0D);
        macro.onStart();
        return macro;
    }

    private static FarmingContext context(
            long now,
            PositionSnapshot position,
            double motionY,
            float pitch,
            SpatialSnapshot spatial
    ) {
        return new FarmingContext(now, EPOCH,
                Observation.present(player(position, 0.0F, pitch, motionY)),
                Observation.present(spatial), Observation.present(true), false,
                ServerResponsiveness.RESPONSIVE);
    }

    private static FarmingContext contextYaw(
            long now,
            PositionSnapshot position,
            float yaw,
            float pitch,
            SpatialSnapshot spatial
    ) {
        return new FarmingContext(now, EPOCH,
                Observation.present(player(position, yaw, pitch, 0.0D)),
                Observation.present(spatial), Observation.present(true), false,
                ServerResponsiveness.RESPONSIVE);
    }

    private static FarmingContext contextSpatial(
            long now,
            Observation<PlayerSnapshot> player,
            Observation<SpatialSnapshot> spatial
    ) {
        return new FarmingContext(now, EPOCH, player, spatial,
                Observation.present(true), false, ServerResponsiveness.RESPONSIVE);
    }

    private static FarmingContext context(
            long now,
            PositionSnapshot position,
            double motionY,
            float pitch,
            SpatialSnapshot spatial,
            PlayerPosture posture
    ) {
        return new FarmingContext(now, EPOCH,
                Observation.present(player(position, 0.0F, pitch, motionY)),
                Observation.present(spatial), Observation.present(true), false,
                ServerResponsiveness.RESPONSIVE, Observation.present(posture));
    }

    private static PlayerSnapshot player(
            PositionSnapshot position,
            float yaw,
            float pitch,
            double motionY
    ) {
        return new PlayerSnapshot(Observation.present(position),
                Observation.present(new MotionSnapshot(0.0D, motionY, 0.0D)),
                Observation.present(new RotationSnapshot(yaw, pitch)),
                Observation.unknown(), Observation.unknown());
    }

    private static SpatialSnapshot spatial(
            PositionSnapshot player,
            CropFixture crop,
            boolean rightCrop
    ) {
        int groundY = (int) Math.floor(player.y()) - 1;
        int minY = groundY - 1;
        int maxY = groundY + 6;
        BoxSnapshot bounds = new BoxSnapshot(-4, minY, -4, 5, maxY, 6);
        Map<BlockPosition, Observation<BlockStateSnapshot>> blocks = new LinkedHashMap<>();
        for (int x = -4; x < 5; x++) {
            for (int y = minY; y < maxY; y++) {
                for (int z = -4; z < 6; z++) {
                    blocks.put(new BlockPosition(x, y, z),
                            Observation.present(y == groundY ? full() : air()));
                }
            }
        }
        if (rightCrop) {
            int x = (int) Math.floor(player.x()) - 1;
            int z = (int) Math.floor(player.z());
            blocks.put(new BlockPosition(x, (int) Math.floor(player.y()), z),
                    Observation.present(crop(crop)));
        }
        Map<ChunkPosition, Map<BlockPosition, Observation<BlockStateSnapshot>>> grouped = new HashMap<>();
        blocks.forEach((position, value) -> grouped
                .computeIfAbsent(position.chunk(), ignored -> new LinkedHashMap<>())
                .put(position, value));
        Map<ChunkPosition, ChunkSnapshot> chunks = new HashMap<>();
        grouped.forEach((position, values) -> chunks.put(position,
                new ChunkSnapshot(position, true, values)));
        return new SpatialSnapshot(EPOCH, bounds, -64, 320,
                new BoxSnapshot(player.x() - 0.3D, player.y(), player.z() - 0.3D,
                        player.x() + 0.3D, player.y() + 1.8D, player.z() + 0.3D), chunks);
    }

    /** Mirrors the production capture shape by observing exactly the request's bounded block set. */
    private static SpatialSnapshot captured(
            SpatialCaptureRequest request,
            PositionSnapshot player,
            BlockPosition obstacle
    ) {
        Map<ChunkPosition, Map<BlockPosition, Observation<BlockStateSnapshot>>> grouped = new HashMap<>();
        int groundY = (int) Math.floor(player.y()) - 1;
        for (BlockPosition position : request.blocks()) {
            BlockStateSnapshot state = position.y() == groundY ? full() : air();
            if (position.equals(obstacle)) {
                state = full();
            }
            grouped.computeIfAbsent(position.chunk(), ignored -> new LinkedHashMap<>())
                    .put(position, Observation.present(state));
        }
        Map<ChunkPosition, ChunkSnapshot> chunks = new HashMap<>();
        grouped.forEach((position, values) -> chunks.put(position,
                new ChunkSnapshot(position, true, values)));
        return new SpatialSnapshot(
                request.worldEpoch(), request.requestToken(), request.bounds(), -64, 320,
                new BoxSnapshot(player.x() - 0.3D, player.y(), player.z() - 0.3D,
                        player.x() + 0.3D, player.y() + 1.8D, player.z() + 0.3D),
                chunks);
    }

    private static void assertCaptured(SpatialCaptureRequest request, BoxSnapshot box) {
        for (int x = (int) Math.floor(box.minX());
                x <= (int) Math.floor(Math.nextDown(box.maxX())); x++) {
            for (int y = (int) Math.floor(box.minY());
                    y <= (int) Math.floor(Math.nextDown(box.maxY())); y++) {
                for (int z = (int) Math.floor(box.minZ());
                        z <= (int) Math.floor(Math.nextDown(box.maxZ())); z++) {
                    assertTrue(request.blocks().contains(new BlockPosition(x, y, z)),
                            "missing requested cell " + new BlockPosition(x, y, z));
                }
            }
        }
    }

    private static SpatialSnapshot withBlock(
            SpatialSnapshot snapshot,
            BlockPosition position,
            Observation<BlockStateSnapshot> value
    ) {
        Map<ChunkPosition, ChunkSnapshot> chunks = new HashMap<>(snapshot.chunks());
        ChunkSnapshot original = chunks.get(position.chunk());
        Map<BlockPosition, Observation<BlockStateSnapshot>> blocks = new HashMap<>(original.blocks());
        blocks.put(position, value);
        chunks.put(position.chunk(), new ChunkSnapshot(position.chunk(), true, blocks));
        return new SpatialSnapshot(snapshot.worldEpoch(), snapshot.requestToken(), snapshot.bounds(),
                snapshot.minY(), snapshot.maxY(), snapshot.playerBox(), chunks);
    }

    private static BlockStateSnapshot crop(CropFixture fixture) {
        return new BlockStateSnapshot(ResourceIdentifier.parse("minecraft:" + fixture.id()),
                fixture.properties(), ResourceIdentifier.parse("minecraft:empty"),
                Observation.present(CollisionShapeSnapshot.EMPTY));
    }

    private static BlockStateSnapshot air() {
        return new BlockStateSnapshot(ResourceIdentifier.parse("minecraft:air"), Map.of(),
                ResourceIdentifier.parse("minecraft:empty"),
                Observation.present(CollisionShapeSnapshot.EMPTY));
    }

    private static BlockStateSnapshot full() {
        return new BlockStateSnapshot(ResourceIdentifier.parse("minecraft:stone"), Map.of(),
                ResourceIdentifier.parse("minecraft:empty"),
                Observation.present(new CollisionShapeSnapshot(List.of(
                        new BoxSnapshot(0, 0, 0, 1, 1, 1)))));
    }

    private static CropFixture wheat() {
        return new CropFixture("wheat", Map.of("age", "7"), 2.8F);
    }

    private record CropFixture(String id, Map<String, String> properties, float pitch) {
    }
}
