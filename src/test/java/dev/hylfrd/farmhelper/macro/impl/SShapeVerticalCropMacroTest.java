package dev.hylfrd.farmhelper.macro.impl;

import dev.hylfrd.farmhelper.control.input.InputAction;
import dev.hylfrd.farmhelper.macro.FarmingContext;
import dev.hylfrd.farmhelper.macro.MacroDecision;
import dev.hylfrd.farmhelper.macro.MacroSettings;
import dev.hylfrd.farmhelper.macro.PlayerPosture;
import dev.hylfrd.farmhelper.macro.MacroRotationDisposition;
import dev.hylfrd.farmhelper.macro.MacroSpawnPose;
import dev.hylfrd.farmhelper.macro.ServerResponsiveness;
import dev.hylfrd.farmhelper.macro.VerticalCropMode;
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
import dev.hylfrd.farmhelper.runtime.spatial.SpatialSnapshot;
import org.junit.jupiter.api.Test;

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
        assertEquals(MacroRotationDisposition.RELEASE, mismatch.rotationDisposition());
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
        return new SpatialSnapshot(snapshot.worldEpoch(), snapshot.bounds(), snapshot.minY(),
                snapshot.maxY(), snapshot.playerBox(), chunks);
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
