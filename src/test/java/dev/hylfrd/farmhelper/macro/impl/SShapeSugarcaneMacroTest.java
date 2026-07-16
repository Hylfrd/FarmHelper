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

class SShapeSugarcaneMacroTest {
    private static final long EPOCH = 17L;
    private static final PositionSnapshot START = new PositionSnapshot(0.5D, 1.0D, 0.5D);
    private static final MotionSnapshot STILL = new MotionSnapshot(0.0D, 0.0D, 0.0D);
    private static final BlockPosition MINUS_WATER = new BlockPosition(-2, 1, 1);
    private static final BlockPosition MINUS_FRONT = new BlockPosition(0, 1, 1);
    private static final BlockPosition MINUS_LEFT = new BlockPosition(1, 1, 0);
    private static final BlockPosition PLUS_WATER = new BlockPosition(-1, 1, -2);
    private static final BlockPosition PLUS_FRONT = new BlockPosition(-1, 1, 0);
    private static final BlockPosition PLUS_RIGHT_REPAIRED = new BlockPosition(0, 1, -1);

    @Test
    void startupSamplesDefaultPitchThenDurationAndUsesBackEntropy() {
        QueueRandom leaf = new QueueRandom(0.0D, 0.0D);
        QueueRandom entropy = new QueueRandom(0.0D, 0.0D);
        SShapeSugarcaneMacro macro = new SShapeSugarcaneMacro(
                validSettings(), leaf, entropy);
        macro.onStart(0L);

        MacroDecision decision = step(macro, 0L, START, 0.0F, 0.0F,
                STILL, grounded(), Map.of());

        var request = decision.rotation().orElseThrow();
        assertEquals(SShapeSugarcaneMacro.State.NONE, macro.state());
        assertEquals(45.0F, request.yaw());
        assertEquals(-0.5F, request.pitch());
        assertEquals(450L, request.durationMillis());
        assertEquals(RotationProfile.BACK, request.profile());
        assertEquals(-0.25F, request.backModifier());
        assertEquals(2, leaf.draws(), "pitch then duration");
        assertEquals(2, entropy.draws(), "Back modifier then floor");
    }

    @Test
    void customTargetsAndInitialDontFixSuppressEveryRotationDraw() {
        MacroSettings settings = readySettings();
        QueueRandom leaf = new QueueRandom();
        QueueRandom entropy = new QueueRandom();
        SShapeSugarcaneMacro macro = new SShapeSugarcaneMacro(settings, leaf, entropy);
        macro.onStart(0L);

        MacroDecision decision = step(macro, 0L, START, 45.0F, 0.0F,
                STILL, grounded(), Map.of());

        assertEquals("initial-fix-suppressed", decision.status());
        assertTrue(decision.rotation().isEmpty());
        assertEquals(0, leaf.draws());
        assertEquals(0, entropy.draws());
    }

    @Test
    void minusWaterHasPriorityAndPlusWaterAloneStillChoosesS() {
        SShapeSugarcaneMacro minus = readyMacro(new QueueRandom(), new QueueRandom());
        MacroDecision choseA = choose(minus, Map.of(
                MINUS_WATER, Observation.present(water())));
        assertEquals(SShapeSugarcaneMacro.State.A, minus.state());
        assertEquals("direction-a", choseA.status());

        SShapeSugarcaneMacro plusOnly = readyMacro(new QueueRandom(), new QueueRandom());
        MacroDecision choseS = choose(plusOnly, Map.of(
                PLUS_WATER, Observation.present(water())));
        assertEquals(SShapeSugarcaneMacro.State.S, plusOnly.state());
        assertEquals("direction-s", choseS.status());
    }

    @Test
    void blockedMinusRouteFallsThroughToKnownPlusWaterAndD() {
        SShapeSugarcaneMacro macro = readyMacro(new QueueRandom(), new QueueRandom());

        choose(macro, Map.of(
                MINUS_WATER, Observation.present(water()),
                MINUS_FRONT, Observation.present(full()),
                MINUS_LEFT, Observation.present(full()),
                PLUS_WATER, Observation.present(water())));

        assertEquals(SShapeSugarcaneMacro.State.D, macro.state());
    }

    @Test
    void repairedOneZeroWallBlocksDWhileLegacyElevenZeroIsIrrelevant() {
        Map<BlockPosition, Observation<BlockStateSnapshot>> common = Map.of(
                MINUS_WATER, Observation.present(water()),
                MINUS_FRONT, Observation.present(full()),
                MINUS_LEFT, Observation.present(full()),
                PLUS_WATER, Observation.present(water()),
                PLUS_FRONT, Observation.present(full()));

        SShapeSugarcaneMacro repaired = readyMacro(new QueueRandom(), new QueueRandom());
        Map<BlockPosition, Observation<BlockStateSnapshot>> repairedBlocks = new HashMap<>(common);
        repairedBlocks.put(PLUS_RIGHT_REPAIRED, Observation.present(full()));
        choose(repaired, repairedBlocks);
        assertEquals(SShapeSugarcaneMacro.State.S, repaired.state());

        SShapeSugarcaneMacro legacy = readyMacro(new QueueRandom(), new QueueRandom());
        Map<BlockPosition, Observation<BlockStateSnapshot>> legacyBlocks = new HashMap<>(common);
        legacyBlocks.put(new BlockPosition(0, 1, -11), Observation.present(full()));
        choose(legacy, legacyBlocks);
        assertEquals(SShapeSugarcaneMacro.State.D, legacy.state());
    }

    @Test
    void sugarcaneEightWayTieStaysInLowerSectorAndNextFloatAdvances() {
        MacroSettings tieSettings = readySettings();
        tieSettings.customYawLevel(67.5F);
        SShapeSugarcaneMacro tie = readyMacro(
                tieSettings, new QueueRandom(), new QueueRandom());
        SpatialCaptureRequest tieRequest = tie.spatialRequest(
                player(START, 67.5F, 0.0F, STILL), EPOCH).orElseThrow();
        assertTrue(tieRequest.blocks().contains(new BlockPosition(-2, 1, 1)));

        float aboveTie = Math.nextUp(67.5F);
        MacroSettings aboveSettings = readySettings();
        aboveSettings.customYawLevel(aboveTie);
        SShapeSugarcaneMacro above = readyMacro(
                aboveSettings, new QueueRandom(), new QueueRandom());
        SpatialCaptureRequest aboveRequest = above.spatialRequest(
                player(START, aboveTie, 0.0F, STILL), EPOCH).orElseThrow();
        assertTrue(aboveRequest.blocks().contains(new BlockPosition(-3, 1, -1)));
    }

    @Test
    void exactInputsAndAOrDToSTransitionsReleaseControls() {
        SShapeSugarcaneMacro a = readyMacro(new QueueRandom(), new QueueRandom());
        choose(a, Map.of(MINUS_WATER, Observation.present(water())));
        assertEquals(Set.of(InputAction.LEFT, InputAction.ATTACK),
                step(a, 2L, START, 45.0F, 0.0F, STILL, grounded(), Map.of()).inputs());
        MacroDecision aTurn = step(a, TimeUnit.MILLISECONDS.toNanos(500L) + 1L,
                START, 45.0F, 0.0F, STILL, grounded(), Map.of());
        assertEquals(SShapeSugarcaneMacro.State.S, a.state());
        assertTrue(aTurn.inputs().isEmpty());
        assertEquals(Set.of(InputAction.BACKWARD, InputAction.ATTACK),
                step(a, TimeUnit.MILLISECONDS.toNanos(500L) + 2L,
                        START, 45.0F, 0.0F, STILL, grounded(), Map.of()).inputs());

        SShapeSugarcaneMacro d = readyMacro(new QueueRandom(), new QueueRandom());
        choose(d, blockedMinusThenPlus());
        assertEquals(Set.of(InputAction.RIGHT, InputAction.ATTACK),
                step(d, 2L, START, 45.0F, 0.0F, STILL, grounded(), Map.of()).inputs());
    }

    @Test
    void sSideScanChecksZeroThroughSevenAndDOverridesA() {
        SShapeSugarcaneMacro bothOpen = readyMacro(new QueueRandom(), new QueueRandom());
        choose(bothOpen, Map.of());
        MacroDecision choseD = step(bothOpen, TimeUnit.MILLISECONDS.toNanos(500L) + 1L,
                START, 45.0F, 0.0F, STILL, grounded(), Map.of(
                        new BlockPosition(1, 1, 0), Observation.present(full()),
                        new BlockPosition(0, 1, -1), Observation.present(full())));
        assertEquals(SShapeSugarcaneMacro.State.D, bothOpen.state());
        assertEquals("row-turn-d", choseD.status());

        SShapeSugarcaneMacro seven = readyMacro(new QueueRandom(), new QueueRandom());
        choose(seven, Map.of());
        step(seven, TimeUnit.MILLISECONDS.toNanos(500L) + 1L,
                START, 45.0F, 0.0F, STILL, grounded(), Map.of(
                        new BlockPosition(1, 1, 0), Observation.present(full()),
                        new BlockPosition(0, 1, -1), Observation.present(full()),
                        new BlockPosition(-7, 1, 0), Observation.present(full())));
        assertEquals(SShapeSugarcaneMacro.State.A, seven.state());

        SShapeSugarcaneMacro eight = readyMacro(new QueueRandom(), new QueueRandom());
        choose(eight, Map.of());
        step(eight, TimeUnit.MILLISECONDS.toNanos(500L) + 1L,
                START, 45.0F, 0.0F, STILL, grounded(), Map.of(
                        new BlockPosition(1, 1, 0), Observation.present(full()),
                        new BlockPosition(0, 1, -1), Observation.present(full()),
                        new BlockPosition(-8, 1, 0), Observation.present(full())));
        assertEquals(SShapeSugarcaneMacro.State.D, eight.state());
    }

    @Test
    void unknownWaterWallsAndSideScanFailClosedWithoutFalseTransitionOrDraw() {
        QueueRandom leaf = new QueueRandom();
        QueueRandom entropy = new QueueRandom();
        SShapeSugarcaneMacro waterUnknown = readyMacro(leaf, entropy);
        MacroDecision unknown = choose(waterUnknown, Map.of(
                MINUS_WATER, Observation.unknown()));
        assertEquals("sugarcane-water-unknown", unknown.status());
        assertEquals(SShapeSugarcaneMacro.State.NONE, waterUnknown.state());
        assertTrue(unknown.inputs().isEmpty());
        assertEquals(0, leaf.draws());
        assertEquals(0, entropy.draws());

        SShapeSugarcaneMacro scanUnknown = readyMacro(new QueueRandom(), new QueueRandom());
        choose(scanUnknown, Map.of());
        MacroDecision sideUnknown = step(scanUnknown,
                TimeUnit.MILLISECONDS.toNanos(500L) + 1L,
                START, 45.0F, 0.0F, STILL, grounded(), Map.of(
                        new BlockPosition(1, 1, 0), Observation.present(full()),
                        new BlockPosition(0, 1, -1), Observation.present(full()),
                        new BlockPosition(-1, 1, 0), Observation.unknown()));
        assertEquals("sugarcane-side-scan-unknown", sideUnknown.status());
        assertEquals(SShapeSugarcaneMacro.State.S, scanUnknown.state());
        assertTrue(sideUnknown.inputs().isEmpty());
    }

    @Test
    void knownNoProgressInSReleasesThenHandsOffRowStalled() {
        SShapeSugarcaneMacro macro = readyMacro(new QueueRandom(), new QueueRandom());
        choose(macro, Map.of());

        MacroDecision first = step(macro, TimeUnit.MILLISECONDS.toNanos(500L) + 1L,
                START, 45.0F, 0.0F, STILL, grounded(), Map.of());
        MacroDecision second = step(macro, TimeUnit.MILLISECONDS.toNanos(1_000L) + 1L,
                START, 45.0F, 0.0F, STILL, grounded(), Map.of());
        MacroDecision third = step(macro, TimeUnit.MILLISECONDS.toNanos(1_500L) + 1L,
                START, 45.0F, 0.0F, STILL, grounded(), Map.of());

        assertEquals("row-stall-observed-1", first.status());
        assertEquals("row-stall-observed-2", second.status());
        assertTrue(first.inputs().isEmpty());
        assertTrue(second.inputs().isEmpty());
        assertEquals(MacroRecoveryReason.ROW_STALLED,
                third.recovery().orElseThrow().reason());
        assertEquals(SShapeSugarcaneMacro.State.RECOVERY_HANDOFF, macro.state());
    }

    @Test
    void dropUsesStrictThresholdsAndChangedLayerBackRotation() {
        MacroSettings settings = readySettings();
        settings.rotateAfterDrop(true);
        QueueRandom leaf = new QueueRandom(0.0D);
        QueueRandom entropy = new QueueRandom(0.0D, 0.0D);
        SShapeSugarcaneMacro macro = readyMacro(settings, leaf, entropy);
        choose(macro, Map.of());

        PositionSnapshot exact = new PositionSnapshot(0.5D, 0.25D, 0.5D);
        assertFalse(step(macro, 2L, exact, 45.0F, 0.0F, STILL,
                falling(), Map.of()).status().equals("dropping"));
        PositionSnapshot beyond = new PositionSnapshot(0.5D, 0.24D, 0.5D);
        MacroDecision dropping = step(macro, 3L, beyond, 45.0F, 0.0F,
                STILL, falling(), Map.of());
        assertEquals("dropping", dropping.status());
        assertTrue(dropping.inputs().isEmpty());

        PositionSnapshot landed = new PositionSnapshot(0.5D, -0.51D, 0.5D);
        MacroDecision rotated = step(macro, 4L, landed, 45.0F, 0.0F,
                STILL, grounded(), Map.of());
        assertEquals(-135.0F, rotated.rotation().orElseThrow().yaw());
        assertEquals(0.0F, rotated.rotation().orElseThrow().pitch());
        assertEquals(RotationProfile.BACK, rotated.rotation().orElseThrow().profile());
        assertEquals(1, leaf.draws());
        assertEquals(2, entropy.draws());
    }

    @Test
    void y80FlyingAndShallowLandingNeverRequestDropRotation() {
        MacroSettings settings = readySettings();
        settings.rotateAfterDrop(true);
        QueueRandom leaf = new QueueRandom();
        QueueRandom entropy = new QueueRandom();
        SShapeSugarcaneMacro y80 = readyMacro(settings, leaf, entropy);
        choose(y80, Map.of());
        assertFalse(step(y80, 2L, new PositionSnapshot(0.5D, 80.0D, 0.5D),
                45.0F, 0.0F, STILL, falling(), Map.of()).status().equals("dropping"));

        SShapeSugarcaneMacro flying = readyMacro(settings, new QueueRandom(), new QueueRandom());
        choose(flying, Map.of());
        assertFalse(step(flying, 2L, new PositionSnapshot(0.5D, -5.0D, 0.5D),
                45.0F, 0.0F, STILL, new PlayerPosture(true, false),
                Map.of()).status().equals("dropping"));

        SShapeSugarcaneMacro shallow = readyMacro(settings, new QueueRandom(), new QueueRandom());
        choose(shallow, Map.of());
        step(shallow, 2L, new PositionSnapshot(0.5D, 0.0D, 0.5D),
                45.0F, 0.0F, STILL, falling(), Map.of());
        MacroDecision landed = step(shallow, 3L,
                new PositionSnapshot(0.5D, -0.5D, 0.5D),
                45.0F, 0.0F, STILL, grounded(), Map.of());
        assertEquals("drop-too-shallow", landed.status());
        assertTrue(landed.rotation().isEmpty());
    }

    @Test
    void staleTokenEpochBoundsPlayerFootprintAndCompletedPhaseFailClosed() {
        SShapeSugarcaneMacro macro = readyMacro(new QueueRandom(), new QueueRandom());
        PlayerSnapshot player = player(START, 45.0F, 0.0F, STILL);
        SpatialCaptureRequest request = macro.spatialRequest(player, EPOCH).orElseThrow();
        SpatialSnapshot valid = captured(request, START, Map.of());
        SpatialSnapshot staleToken = new SpatialSnapshot(
                EPOCH, request.requestToken() + 1L, request.bounds(), -64, 320,
                valid.playerBox(), valid.chunks());
        assertEquals("spatial-unknown-or-stale",
                macro.tick(context(1L, player, staleToken, grounded())).status());

        SpatialCaptureRequest next = macro.spatialRequest(player, EPOCH).orElseThrow();
        SpatialSnapshot validNext = captured(next, START, Map.of());
        SpatialSnapshot wrongBody = new SpatialSnapshot(
                EPOCH, next.requestToken(), next.bounds(), -64, 320,
                validNext.playerBox().move(0.01D, 0.0D, 0.0D), validNext.chunks());
        assertEquals("spatial-unknown-or-stale",
                macro.tick(context(2L, player, wrongBody, grounded())).status());

        SpatialCaptureRequest boundsRequest = macro.spatialRequest(player, EPOCH).orElseThrow();
        SpatialSnapshot boundsValid = captured(boundsRequest, START, Map.of());
        BoxSnapshot expanded = new BoxSnapshot(
                boundsRequest.bounds().minX(), boundsRequest.bounds().minY(),
                boundsRequest.bounds().minZ(), boundsRequest.bounds().maxX() + 1.0D,
                boundsRequest.bounds().maxY(), boundsRequest.bounds().maxZ());
        SpatialSnapshot wrongBounds = new SpatialSnapshot(
                EPOCH, boundsRequest.requestToken(), expanded, -64, 320,
                boundsValid.playerBox(), boundsValid.chunks());
        assertEquals("spatial-unknown-or-stale",
                macro.tick(context(3L, player, wrongBounds, grounded())).status());

        SpatialCaptureRequest epochRequest = macro.spatialRequest(player, EPOCH).orElseThrow();
        SpatialSnapshot epochValid = captured(epochRequest, START, Map.of());
        SpatialSnapshot wrongEpoch = new SpatialSnapshot(
                EPOCH + 1L, epochRequest.requestToken(), epochRequest.bounds(), -64, 320,
                epochValid.playerBox(), epochValid.chunks());
        assertEquals("spatial-unknown-or-stale",
                macro.tick(context(4L, player, wrongEpoch, grounded())).status());

        SpatialCaptureRequest completedRequest = macro.spatialRequest(player, EPOCH).orElseThrow();
        SpatialSnapshot completed = captured(completedRequest, START, Map.of());
        assertEquals("direction-s",
                macro.tick(context(5L, player, completed, grounded())).status());
        assertEquals("spatial-unknown-or-stale",
                macro.tick(context(6L, player, completed, grounded())).status());
    }

    @Test
    void stopStartGenerationAndPositionOrYawAnchorDriftRejectOldCapture() {
        QueueRandom restartLeaf = new QueueRandom();
        QueueRandom restartEntropy = new QueueRandom();
        SShapeSugarcaneMacro restarted = new SShapeSugarcaneMacro(
                readySettings(), restartLeaf, restartEntropy);
        restarted.onStart(0L);
        PlayerSnapshot stablePlayer = player(START, 45.0F, 0.0F, STILL);
        assertEquals(SShapeSugarcaneMacro.State.STARTUP, restarted.state());
        SpatialCaptureRequest oldRequest = restarted.spatialRequest(
                stablePlayer, EPOCH).orElseThrow();
        SpatialSnapshot oldCapture = captured(oldRequest, START, Map.of());
        restarted.onStop();
        restarted.onStart(1L);
        assertEquals(SShapeSugarcaneMacro.State.STARTUP, restarted.state());
        assertEquals("spatial-unknown-or-stale",
                restarted.tick(context(1L, stablePlayer, oldCapture, grounded())).status());
        assertEquals(0, restartLeaf.draws());
        assertEquals(0, restartEntropy.draws());

        SShapeSugarcaneMacro moved = readyMacro(new QueueRandom(), new QueueRandom());
        SpatialCaptureRequest positionRequest = moved.spatialRequest(
                stablePlayer, EPOCH).orElseThrow();
        SpatialSnapshot positionCapture = captured(positionRequest, START, Map.of());
        PositionSnapshot driftedPosition = new PositionSnapshot(0.6D, 1.0D, 0.5D);
        PlayerSnapshot driftedPlayer = player(driftedPosition, 45.0F, 0.0F, STILL);
        assertEquals("spatial-unknown-or-stale",
                moved.tick(context(2L, driftedPlayer, positionCapture, grounded())).status());

        SShapeSugarcaneMacro turned = readyMacro(new QueueRandom(), new QueueRandom());
        SpatialCaptureRequest yawRequest = turned.spatialRequest(
                stablePlayer, EPOCH).orElseThrow();
        SpatialSnapshot yawCapture = captured(yawRequest, START, Map.of());
        PlayerSnapshot turnedPlayer = player(START, 46.0F, 0.0F, STILL);
        assertEquals("spatial-unknown-or-stale",
                turned.tick(context(3L, turnedPlayer, yawCapture, grounded())).status());
    }

    @Test
    void missingCapturedChunkAndUnknownSupportReleaseAllControls() {
        SShapeSugarcaneMacro macro = readyMacro(new QueueRandom(), new QueueRandom());
        PlayerSnapshot player = player(START, 45.0F, 0.0F, STILL);
        SpatialCaptureRequest request = macro.spatialRequest(player, EPOCH).orElseThrow();
        SpatialSnapshot missing = new SpatialSnapshot(
                EPOCH, request.requestToken(), request.bounds(), -64, 320,
                new BoxSnapshot(0.2D, 1.0D, 0.2D, 0.8D, 2.8D, 0.8D), Map.of());

        MacroDecision decision = macro.tick(context(1L, player, missing, grounded()));

        assertEquals("body-or-support-unknown", decision.status());
        assertTrue(decision.inputs().isEmpty());
        assertTrue(decision.rotation().isEmpty());
    }

    @Test
    void pauseResumeAndOwnerCancellationReuseExactRequestWithoutRedraw() {
        QueueRandom leaf = new QueueRandom(0.0D, 0.0D);
        QueueRandom entropy = new QueueRandom(0.0D, 0.0D);
        SShapeSugarcaneMacro macro = new SShapeSugarcaneMacro(
                validSettings(), leaf, entropy);
        macro.onStart(0L);
        MacroDecision initial = step(macro, 0L, START, 0.0F, 0.0F,
                STILL, grounded(), Map.of());
        var request = initial.rotation().orElseThrow();
        int leafDraws = leaf.draws();
        int entropyDraws = entropy.draws();

        macro.onPause(Set.of(MacroPauseCause.SCREEN_OPEN), 1L);
        macro.onResume(TimeUnit.SECONDS.toNanos(10L));
        MacroDecision resumed = step(macro, TimeUnit.SECONDS.toNanos(10L) + 1L,
                START, 0.0F, 0.0F, STILL, grounded(), Map.of(),
                MacroRotationLeaseState.terminal(request.requestToken(),
                        RotationTerminalReason.OWNER_CANCELLED, 2L));

        assertEquals(request, resumed.rotation().orElseThrow());
        assertEquals(leafDraws, leaf.draws());
        assertEquals(entropyDraws, entropy.draws());

        macro.onStop();
        assertEquals(SShapeSugarcaneMacro.State.STOPPED, macro.state());
        assertTrue(macro.pendingRotation().isEmpty());
        assertTrue(macro.tick(contextWithoutSpatial()).inputs().isEmpty());
    }

    @Test
    void sharedRewarpDwellTeleportLandingAndSingleCorrectionRequest() {
        MacroSettings settings = readySettings();
        settings.clearRewarps();
        settings.spawn(new RewarpPosition(10, 1, 10));
        assertTrue(settings.addRewarp(new RewarpPosition(0, 1, 0)));
        QueueRandom leaf = new QueueRandom(0.0D, 0.0D);
        QueueRandom entropy = new QueueRandom(0.0D, 0.0D);
        SShapeSugarcaneMacro macro = readyMacro(settings, leaf, entropy);

        MacroDecision dwell = step(macro, 1L, START, 45.0F, 0.0F,
                STILL, grounded(), Map.of());
        assertEquals("rewarp-dwell", dwell.status());
        assertEquals(SShapeSugarcaneMacro.State.REWARP_DWELL, macro.state());
        assertTrue(step(macro, TimeUnit.MILLISECONDS.toNanos(400L) + 1L,
                START, 45.0F, 0.0F, STILL, grounded(), Map.of()).warp().isPresent());

        PositionSnapshot moved = new PositionSnapshot(2.5D, 1.0D, 0.5D);
        long landedAt = TimeUnit.MILLISECONDS.toNanos(400L) + 2L;
        step(macro, landedAt, moved, 0.0F, 0.0F, STILL, grounded(), Map.of());
        step(macro, landedAt + TimeUnit.MILLISECONDS.toNanos(1_500L),
                moved, 0.0F, 0.0F, STILL, grounded(), Map.of());
        MacroDecision correction = step(macro,
                landedAt + TimeUnit.MILLISECONDS.toNanos(2_100L),
                moved, 0.0F, 0.0F, STILL, grounded(), Map.of());
        var request = correction.rotation().orElseThrow();
        int leafDraws = leaf.draws();
        int entropyDraws = entropy.draws();

        MacroDecision waiting = step(macro,
                landedAt + TimeUnit.MILLISECONDS.toNanos(2_100L) + 1L,
                moved, 0.0F, 0.0F, STILL, grounded(), Map.of(),
                MacroRotationLeaseState.active(request.requestToken(), false, 3L));
        assertEquals(request, waiting.rotation().orElseThrow());
        assertEquals(leafDraws, leaf.draws());
        assertEquals(entropyDraws, entropy.draws());
    }

    @Test
    void rotateAfterWarpKeepsCustomYawAndUsesOneUnmultipliedDurationSample() {
        MacroSettings settings = readySettings();
        settings.customYawLevel(12.0F);
        settings.rotateAfterWarped(true);
        settings.clearRewarps();
        settings.spawn(new RewarpPosition(10, 1, 10));
        assertTrue(settings.addRewarp(new RewarpPosition(0, 1, 0)));
        QueueRandom leaf = new QueueRandom(0.0D, 0.0D);
        QueueRandom entropy = new QueueRandom(0.0D, 0.0D);
        SShapeSugarcaneMacro macro = readyMacro(settings, leaf, entropy);

        assertEquals("rewarp-dwell", step(macro, 1L, START, 12.0F, 0.0F,
                STILL, grounded(), Map.of()).status());
        assertTrue(step(macro, TimeUnit.MILLISECONDS.toNanos(400L) + 1L,
                START, 12.0F, 0.0F, STILL, grounded(), Map.of()).warp().isPresent());
        PositionSnapshot moved = new PositionSnapshot(2.5D, 1.0D, 0.5D);
        long landedAt = TimeUnit.MILLISECONDS.toNanos(400L) + 2L;
        step(macro, landedAt, moved, 12.0F, 0.0F, STILL, grounded(), Map.of());
        step(macro, landedAt + TimeUnit.MILLISECONDS.toNanos(1_500L),
                moved, 12.0F, 0.0F, STILL, grounded(), Map.of());
        MacroDecision correction = step(macro,
                landedAt + TimeUnit.MILLISECONDS.toNanos(2_100L),
                moved, 12.0F, 0.0F, STILL, grounded(), Map.of());

        var request = correction.rotation().orElseThrow();
        assertEquals(-168.0F, request.yaw());
        assertEquals(0.0F, request.pitch());
        assertEquals(550L, request.durationMillis(),
                "one 500ms leaf sample receives only the shared 180-degree scaling");
        assertEquals(RotationProfile.BACK, request.profile());
        assertEquals(-0.25F, request.backModifier());
        assertEquals(2, leaf.draws(), "rewarp dwell then rotation duration");
        assertEquals(2, entropy.draws(), "Back modifier then minimum floor");
    }

    private static Map<BlockPosition, Observation<BlockStateSnapshot>> blockedMinusThenPlus() {
        return Map.of(
                MINUS_WATER, Observation.present(water()),
                MINUS_FRONT, Observation.present(full()),
                MINUS_LEFT, Observation.present(full()),
                PLUS_WATER, Observation.present(water()));
    }

    private static MacroDecision choose(
            SShapeSugarcaneMacro macro,
            Map<BlockPosition, Observation<BlockStateSnapshot>> overrides
    ) {
        return step(macro, 1L, START, 45.0F, 0.0F,
                STILL, grounded(), overrides);
    }

    private static SShapeSugarcaneMacro readyMacro(QueueRandom leaf, QueueRandom entropy) {
        return readyMacro(readySettings(), leaf, entropy);
    }

    private static SShapeSugarcaneMacro readyMacro(
            MacroSettings settings,
            QueueRandom leaf,
            QueueRandom entropy
    ) {
        SShapeSugarcaneMacro macro = new SShapeSugarcaneMacro(settings, leaf, entropy);
        macro.onStart(0L);
        float startupYaw = settings.customYaw() ? settings.customYawLevel() : 45.0F;
        assertEquals("initial-fix-suppressed",
                step(macro, 0L, START, startupYaw, 0.0F,
                        STILL, grounded(), Map.of()).status());
        return macro;
    }

    private static MacroSettings readySettings() {
        MacroSettings settings = validSettings();
        settings.customPitch(true);
        settings.customPitchLevel(0.0F);
        settings.customYaw(true);
        settings.customYawLevel(45.0F);
        settings.dontFixAfterWarping(true);
        return settings;
    }

    private static MacroSettings validSettings() {
        MacroSettings settings = new MacroSettings();
        settings.macroMode(MacroMode.SUGAR_CANE);
        settings.spawn(new RewarpPosition(100, 70, 100));
        assertTrue(settings.addRewarp(new RewarpPosition(90, 70, 90)));
        return settings;
    }

    private static MacroDecision step(
            SShapeSugarcaneMacro macro,
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
                        ? MacroRotationLeaseState.terminal(pending.requestToken(),
                                RotationTerminalReason.COMPLETED, 2L)
                        : MacroRotationLeaseState.active(
                                pending.requestToken(), false, 1L))
                .orElseGet(() -> MacroRotationLeaseState.idle(0L));
        return step(macro, now, position, yaw, pitch, motion, posture, overrides, lease);
    }

    private static MacroDecision step(
            SShapeSugarcaneMacro macro,
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
            PlayerPosture posture
    ) {
        return context(now, player, spatial, posture, MacroRotationLeaseState.idle(0L));
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

    private static FarmingContext contextWithoutSpatial() {
        return new FarmingContext(
                0L, EPOCH, Observation.unknown(), Observation.unknown(),
                Observation.present(true), false, ServerResponsiveness.RESPONSIVE,
                Observation.present(grounded()), Observation.present(
                        MacroRotationLeaseState.idle(0L)));
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

    private static BlockStateSnapshot water() {
        return new BlockStateSnapshot(
                ResourceIdentifier.parse("minecraft:water"), Map.of(),
                ResourceIdentifier.parse("minecraft:water"),
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

    private static PlayerPosture falling() {
        return new PlayerPosture(false, false, false);
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
