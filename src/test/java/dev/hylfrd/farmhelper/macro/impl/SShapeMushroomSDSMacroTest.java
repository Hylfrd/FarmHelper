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

class SShapeMushroomSDSMacroTest {
    private static final long EPOCH = 12L;
    private static final PositionSnapshot START = new PositionSnapshot(0.5D, 1.0D, 0.5D);
    private static final MotionSnapshot STILL = new MotionSnapshot(0.0D, 0.0D, 0.0D);

    @Test
    void startupUsesCardinalPitchRangeBackProfileAndSeparateEntropy() {
        QueueRandom lowLeaf = new QueueRandom(0.0D, 0.0D);
        QueueRandom lowEntropy = new QueueRandom(0.0D, 0.0D);
        SShapeMushroomSDSMacro low = new SShapeMushroomSDSMacro(
                validSettings(), lowLeaf, lowEntropy);
        low.onStart();

        MacroDecision lowStart = step(low, 0L, START, 44.0F, 0.0F,
                STILL, grounded(), Map.of());

        assertEquals(SShapeMushroomSDSMacro.State.NONE, low.state());
        assertEquals(0.0F, low.storedYaw());
        assertEquals(0.0F, low.cardinalYaw());
        assertEquals(RotationProfile.BACK, lowStart.rotation().orElseThrow().profile());
        assertEquals(0.0F, lowStart.rotation().orElseThrow().yaw());
        assertEquals(6.5F, lowStart.rotation().orElseThrow().pitch());
        assertEquals(385L, lowStart.rotation().orElseThrow().durationMillis());
        assertEquals(2, lowLeaf.draws(), "pitch then duration");
        assertEquals(2, lowEntropy.draws(), "Back modifier and minimum floor");

        QueueRandom highLeaf = new QueueRandom(Math.nextDown(1.0D), Math.nextDown(1.0D));
        SShapeMushroomSDSMacro high = new SShapeMushroomSDSMacro(
                validSettings(), highLeaf, zeros(2));
        high.onStart();
        MacroDecision highStart = step(high, 0L, START, 46.0F, 0.0F,
                STILL, grounded(), Map.of());
        assertEquals(90.0F, high.storedYaw());
        assertTrue(highStart.rotation().orElseThrow().pitch() >= 6.5F);
        assertTrue(highStart.rotation().orElseThrow().pitch() < 7.5F);
        assertTrue(highStart.rotation().orElseThrow().durationMillis() < 880L);
    }

    @Test
    void customAnglesAndDontFixSuppressStartupWithoutAnyDraw() {
        MacroSettings settings = validSettings();
        settings.customYaw(true);
        settings.customYawLevel(90.0F);
        settings.customPitch(true);
        settings.customPitchLevel(12.0F);
        settings.dontFixAfterWarping(true);
        QueueRandom leaf = new QueueRandom();
        QueueRandom entropy = new QueueRandom();
        SShapeMushroomSDSMacro macro = new SShapeMushroomSDSMacro(settings, leaf, entropy);
        macro.onStart();

        MacroDecision decision = step(macro, 0L, START, 90.0F, 0.0F,
                STILL, grounded(), Map.of());

        assertEquals("startup-fix-suppressed", decision.status());
        assertTrue(decision.rotation().isEmpty());
        assertEquals(90.0F, macro.storedYaw());
        assertEquals(90.0F, macro.cardinalYaw());
        assertEquals(0, leaf.draws());
        assertEquals(0, entropy.draws());
    }

    @Test
    void cropSlotsUseOnlySavedCardinalYZeroAndOne() {
        assertCropSlot(0, "red_mushroom", SShapeMushroomSDSMacro.State.RIGHT);
        assertCropSlot(1, "brown_mushroom", SShapeMushroomSDSMacro.State.RIGHT);

        SShapeMushroomSDSMacro absent = macro(validSettings());
        MacroDecision result = finishStartup(absent, 0.0F, Map.of(
                target(START, 0.0F, true, 2), Observation.present(crop("red_mushroom"))));

        assertEquals(SShapeMushroomSDSMacro.State.NONE, absent.state());
        assertEquals("mushroom-target-absent", result.status());
        assertEquals(SShapeMushroomSDSMacro.ScanPhase.RIGHT_OBSTACLE, absent.scanPhase());
        assertTrue(result.inputs().isEmpty());
    }

    @Test
    void readyCropIsCompatibilityAndSideWalkabilityGatedAndUnknownFailsClosed() {
        SShapeMushroomSDSMacro stem = macro(validSettings());
        MacroDecision absent = finishStartup(stem, 0.0F, Map.of(
                target(START, 0.0F, true, 0), Observation.present(crop("red_mushroom_block"))));
        assertEquals("mushroom-target-absent", absent.status());

        SShapeMushroomSDSMacro blocked = macro(validSettings());
        Map<BlockPosition, Observation<BlockStateSnapshot>> blockedValues = new HashMap<>();
        blockedValues.put(target(START, 0.0F, true, 0),
                Observation.present(crop("brown_mushroom")));
        blockedValues.put(sideBodyBlock(START, 0.0F, true), Observation.present(full()));
        MacroDecision blockedDecision = finishStartup(blocked, 0.0F, blockedValues);
        assertEquals("mushroom-target-absent", blockedDecision.status());
        assertEquals(SShapeMushroomSDSMacro.State.NONE, blocked.state());

        SShapeMushroomSDSMacro unknownCrop = macro(validSettings());
        MacroDecision cropUnknown = finishStartup(unknownCrop, 0.0F, Map.of(
                target(START, 0.0F, true, 0), Observation.unknown()));
        assertEquals("right-mushroom-ready-unknown", cropUnknown.status());
        assertTrue(cropUnknown.inputs().isEmpty());

        SShapeMushroomSDSMacro unknownWalk = macro(validSettings());
        Map<BlockPosition, Observation<BlockStateSnapshot>> walkValues = new HashMap<>();
        walkValues.put(target(START, 0.0F, true, 0),
                Observation.present(crop("red_mushroom")));
        walkValues.put(sideBodyBlock(START, 0.0F, true), Observation.unknown());
        MacroDecision walkUnknown = finishStartup(unknownWalk, 0.0F, walkValues);
        assertEquals("right-mushroom-ready-unknown", walkUnknown.status());
        assertTrue(walkUnknown.inputs().isEmpty());
    }

    @Test
    void directionScanStartsAtOneEndsAt179AndChecksRightFirst() {
        SShapeMushroomSDSMacro rightPriority = scanningMacro();
        Map<BlockPosition, Observation<BlockStateSnapshot>> bothBlocked = Map.of(
                sideBodyBlock(START, 0.0F, true), Observation.present(full()),
                sideBodyBlock(START, 0.0F, false), Observation.present(full()));
        MacroDecision selected = stepObservations(rightPriority, 2L, START, 0.0F, 6.5F,
                STILL, grounded(), bothBlocked);
        assertEquals(SShapeMushroomSDSMacro.State.LEFT, rightPriority.state());
        assertEquals(Set.of(InputAction.LEFT, InputAction.ATTACK), selected.inputs());

        SShapeMushroomSDSMacro last = scanningMacro();
        long now = 2L;
        for (int distance = 1; distance < 179; distance++) {
            step(last, now++, START, 0.0F, 6.5F, STILL, grounded(), Map.of());
            step(last, now++, START, 0.0F, 6.5F, STILL, grounded(), Map.of());
        }
        assertEquals(179, last.scanDistance());
        assertEquals(SShapeMushroomSDSMacro.ScanPhase.RIGHT_OBSTACLE, last.scanPhase());
        BlockPosition right179 = sideBodyBlock(START, 0.0F, true, 179);
        MacroDecision at179 = stepObservations(last, now, START, 0.0F, 6.5F,
                STILL, grounded(), Map.of(right179, Observation.present(full())));
        assertEquals(SShapeMushroomSDSMacro.State.LEFT, last.state());
        assertEquals(Set.of(InputAction.LEFT, InputAction.ATTACK), at179.inputs());
    }

    @Test
    void leftAndRightInputsAreExactAndBackTakesPriority() {
        SShapeMushroomSDSMacro left = laneMacro(false, false);
        MacroDecision leftStay = stepObservations(left, 2L, START, 0.0F, 6.5F,
                STILL, grounded(), Map.of(backBodyBlock(START, 0.0F), Observation.present(full())));
        assertEquals(Set.of(InputAction.LEFT, InputAction.ATTACK), leftStay.inputs());
        assertEquals(SShapeMushroomSDSMacro.State.LEFT, left.state());

        SShapeMushroomSDSMacro right = laneMacro(true, false);
        MacroDecision rightStay = stepObservations(right, 2L, START, 0.0F, 6.5F,
                STILL, grounded(), Map.of(backBodyBlock(START, 0.0F), Observation.present(full())));
        assertEquals(Set.of(InputAction.RIGHT, InputAction.ATTACK), rightStay.inputs());
        assertEquals(SShapeMushroomSDSMacro.State.RIGHT, right.state());

        MacroDecision switching = step(right, 3L, START, 0.0F, 6.5F,
                STILL, grounded(), Map.of());
        assertEquals(SShapeMushroomSDSMacro.State.SWITCHING_LANE, right.state());
        assertEquals(Set.of(InputAction.BACKWARD, InputAction.ATTACK), switching.inputs());

        SShapeMushroomSDSMacro unknown = laneMacro(false, false);
        MacroDecision rejected = stepObservations(unknown, 2L, START, 0.0F, 6.5F,
                STILL, grounded(), Map.of(
                        backBodyBlock(START, 0.0F), Observation.unknown()));
        assertEquals("mushroom-sds-back-lane-unknown", rejected.status());
        assertEquals(SShapeMushroomSDSMacro.State.LEFT, unknown.state());
        assertTrue(rejected.inputs().isEmpty());
    }

    @Test
    void alwaysHoldWOnlyPreventsBackwardSwitchAndNeverAddsForward() {
        SShapeMushroomSDSMacro macro = laneMacro(true, true);

        MacroDecision decision = step(macro, 2L, START, 0.0F, 6.5F,
                STILL, grounded(), Map.of());

        assertEquals(SShapeMushroomSDSMacro.State.RIGHT, macro.state());
        assertEquals(Set.of(InputAction.RIGHT, InputAction.ATTACK), decision.inputs());
        assertFalse(decision.inputs().contains(InputAction.FORWARD));
        assertFalse(decision.inputs().contains(InputAction.BACKWARD));
    }

    @Test
    void blockedLateralReturnsNoneAndUnknownLateralFailsClosed() {
        SShapeMushroomSDSMacro blocked = laneMacro(true, true);
        MacroDecision ended = stepObservations(blocked, 2L, START, 0.0F, 6.5F,
                STILL, grounded(), Map.of(
                        sideBodyBlock(START, 0.0F, true), Observation.present(full())));
        assertEquals(SShapeMushroomSDSMacro.State.NONE, blocked.state());
        assertEquals("mushroom-sds-direction-recalculate", ended.status());
        assertTrue(ended.inputs().isEmpty());

        SShapeMushroomSDSMacro unknown = laneMacro(false, true);
        MacroDecision rejected = stepObservations(unknown, 2L, START, 0.0F, 6.5F,
                STILL, grounded(), Map.of(
                        sideBodyBlock(START, 0.0F, false), Observation.unknown()));
        assertEquals("mushroom-sds-left-lane-unknown", rejected.status());
        assertEquals(SShapeMushroomSDSMacro.State.LEFT, unknown.state());
        assertTrue(rejected.inputs().isEmpty());
    }

    @Test
    void switchingLanePassableBlockedAndUnknownAreFailClosedSafe() {
        SShapeMushroomSDSMacro passable = laneMacro(true, false);
        step(passable, 2L, START, 0.0F, 6.5F, STILL, grounded(), Map.of());
        MacroDecision moving = step(passable, 3L, START, 0.0F, 6.5F,
                STILL, grounded(), Map.of());
        assertEquals(Set.of(InputAction.BACKWARD, InputAction.ATTACK), moving.inputs());

        MacroDecision complete = stepObservations(passable, 4L, START, 0.0F, 6.5F,
                STILL, grounded(), Map.of(
                        backBodyBlock(START, 0.0F), Observation.present(full())));
        assertEquals(SShapeMushroomSDSMacro.State.NONE, passable.state());
        assertEquals("mushroom-sds-switch-complete", complete.status());
        assertTrue(complete.inputs().isEmpty());

        SShapeMushroomSDSMacro unknown = laneMacro(true, false);
        step(unknown, 2L, START, 0.0F, 6.5F, STILL, grounded(), Map.of());
        MacroDecision rejected = stepObservations(unknown, 3L, START, 0.0F, 6.5F,
                STILL, grounded(), Map.of(
                        backBodyBlock(START, 0.0F), Observation.unknown()));
        assertEquals("mushroom-sds-switch-back-unknown", rejected.status());
        assertTrue(rejected.inputs().isEmpty());
    }

    @Test
    void switchingLaneStallUsesSharedRecoveryHandoff() {
        SShapeMushroomSDSMacro macro = laneMacro(true, false);
        step(macro, 1L, START, 0.0F, 6.5F, STILL, grounded(), Map.of());
        long window = TimeUnit.MILLISECONDS.toNanos(500L);
        step(macro, window + 1L, START, 0.0F, 6.5F, STILL, grounded(), Map.of());
        step(macro, window * 2L + 1L, START, 0.0F, 6.5F, STILL, grounded(), Map.of());
        MacroDecision recovery = step(macro, window * 3L + 1L,
                START, 0.0F, 6.5F, STILL, grounded(), Map.of());

        assertEquals(SShapeMushroomSDSMacro.State.RECOVERY_HANDOFF, macro.state());
        assertEquals(MacroRecoveryReason.LANE_STALLED,
                recovery.recovery().orElseThrow().reason());
        assertTrue(recovery.inputs().isEmpty());
        assertTrue(recovery.rotation().isEmpty());
    }

    @Test
    void lateralStallUsesSharedRowRecoveryHandoff() {
        SShapeMushroomSDSMacro macro = laneMacro(true, true);
        long window = TimeUnit.MILLISECONDS.toNanos(500L);
        step(macro, window + 1L, START, 0.0F, 6.5F,
                STILL, grounded(), Map.of());
        step(macro, window * 2L + 1L, START, 0.0F, 6.5F,
                STILL, grounded(), Map.of());
        MacroDecision recovery = step(macro, window * 3L + 1L,
                START, 0.0F, 6.5F, STILL, grounded(), Map.of());

        assertEquals(SShapeMushroomSDSMacro.State.RECOVERY_HANDOFF, macro.state());
        assertEquals(MacroRecoveryReason.ROW_STALLED,
                recovery.recovery().orElseThrow().reason());
        assertTrue(recovery.inputs().isEmpty());
    }

    @Test
    void dropReleasesImmediatelyStaysReleasedAndNeverRotates() {
        MacroSettings settings = validSettings();
        settings.rotateAfterDrop(true);
        QueueRandom leaf = zeros(20);
        QueueRandom entropy = zeros(20);
        SShapeMushroomSDSMacro macro = new SShapeMushroomSDSMacro(settings, leaf, entropy);
        macro.onStart();
        chooseLane(macro, true, 0.0F, START);
        int leafDraws = leaf.draws();
        int entropyDraws = entropy.draws();

        PositionSnapshot falling = new PositionSnapshot(0.5D, 2.0D, 0.5D);
        MacroDecision entry = step(macro, 2L, falling, 0.0F, 6.5F,
                STILL, airborne(), Map.of());
        MacroDecision airborne = step(macro, 3L,
                new PositionSnapshot(0.5D, 2.5D, 0.5D), 0.0F, 6.5F,
                STILL, airborne(), Map.of());
        PositionSnapshot landed = new PositionSnapshot(0.5D, 3.0D, 0.5D);
        MacroDecision grounded = step(macro, 4L, landed, 0.0F, 6.5F,
                STILL, grounded(), Map.of());

        for (MacroDecision decision : List.of(entry, airborne, grounded)) {
            assertTrue(decision.inputs().isEmpty());
            assertTrue(decision.rotation().isEmpty());
        }
        assertEquals("dropping", entry.status());
        assertEquals("dropping", airborne.status());
        assertEquals("drop-complete", grounded.status());
        assertEquals(SShapeMushroomSDSMacro.State.NONE, macro.state());
        SpatialCaptureRequest refreshed = macro.spatialRequest(
                player(landed, 0.0F, 6.5F, STILL), EPOCH).orElseThrow();
        assertTrue(refreshed.bounds().contains(body(landed)));
        assertEquals(leafDraws, leaf.draws(), "drop has no leaf entropy");
        assertEquals(entropyDraws, entropy.draws(), "drop has no rotation entropy");
    }

    @Test
    void rewarpFalseKeepsTargetTrueTurnsOldCardinalAndDoublesBeforeScaling() {
        RewarpResult saved = completeRewarp(false, 0.0F, 6.5F);
        assertEquals("post-rewarp-saved-back", saved.decision().status());
        assertEquals(0.0F, saved.decision().rotation().orElseThrow().yaw());
        assertEquals(6.5F, saved.decision().rotation().orElseThrow().pitch());
        assertEquals(325L, saved.decision().rotation().orElseThrow().durationMillis());
        assertEquals(RotationProfile.BACK,
                saved.decision().rotation().orElseThrow().profile());

        RewarpResult turned = completeRewarp(true, 0.0F, 6.5F);
        assertEquals("post-rewarp-mushroom-sds-back", turned.decision().status());
        assertEquals(-180.0F, turned.decision().rotation().orElseThrow().yaw());
        assertEquals(6.5F, turned.decision().rotation().orElseThrow().pitch());
        assertEquals(1_100L, turned.decision().rotation().orElseThrow().durationMillis());
        assertEquals(4, turned.leaf().draws(), "pitch, startup duration, dwell, Back duration");
        assertEquals(4, turned.entropy().draws(), "startup and rewarp Back entropy");
    }

    @Test
    void rewarpOwnedBackRequestIsStableUntilOneTerminalAcknowledgement() {
        RewarpResult result = completeRewarp(true, 0.0F, 6.5F);
        var request = result.decision().rotation().orElseThrow();
        int leafDraws = result.leaf().draws();
        int entropyDraws = result.entropy().draws();
        PositionSnapshot spawn = new PositionSnapshot(10.5D, 1.0D, 0.5D);

        MacroDecision active = step(result.macro(), result.now() + 1L,
                spawn, request.yaw(), request.pitch(), STILL, grounded(), Map.of(),
                MacroRotationLeaseState.active(request.requestToken(), false, 8L));
        assertEquals(request, active.rotation().orElseThrow());
        assertEquals(leafDraws, result.leaf().draws());
        assertEquals(entropyDraws, result.entropy().draws());

        MacroDecision completed = step(result.macro(), result.now() + 2L,
                spawn, request.yaw(), request.pitch(), STILL, grounded(), Map.of(),
                MacroRotationLeaseState.terminal(
                        request.requestToken(), RotationTerminalReason.COMPLETED, 9L));
        assertTrue(completed.rotation().isEmpty());
        assertEquals(leafDraws, result.leaf().draws());
        assertEquals(entropyDraws, result.entropy().draws());
    }

    @Test
    void staleCapturePauseStopAndWorldChangeInvalidateSafelyWithoutRedraw() {
        QueueRandom leaf = zeros(20);
        QueueRandom entropy = zeros(20);
        SShapeMushroomSDSMacro macro = new SShapeMushroomSDSMacro(
                validSettings(), leaf, entropy);
        macro.onStart();
        PlayerSnapshot initialPlayer = player(START, 0.0F, 0.0F, STILL);
        SpatialCaptureRequest request = macro.spatialRequest(initialPlayer, EPOCH).orElseThrow();
        SpatialSnapshot valid = captured(request, START, Map.of());
        SpatialSnapshot stale = new SpatialSnapshot(
                EPOCH, request.requestToken() + 1L, request.bounds(), -64, 320,
                valid.playerBox(), valid.chunks());
        MacroDecision rejected = macro.tick(context(
                0L, EPOCH, initialPlayer, stale, grounded(), MacroRotationLeaseState.idle(0L)));
        assertEquals("spatial-unknown-or-stale", rejected.status());
        assertEquals(0, leaf.draws());
        assertEquals(0, entropy.draws());

        MacroDecision startup = step(macro, 1L, START, 0.0F, 0.0F,
                STILL, grounded(), Map.of());
        var pending = startup.rotation().orElseThrow();
        int leafDraws = leaf.draws();
        int entropyDraws = entropy.draws();
        macro.onPause(Set.of(MacroPauseCause.SCREEN_OPEN), 2L);
        macro.onResume(10L);
        MacroDecision resumed = step(macro, 11L, START, 0.0F, 0.0F,
                STILL, grounded(), Map.of(), MacroRotationLeaseState.terminal(
                        pending.requestToken(), RotationTerminalReason.OWNER_CANCELLED, 3L));
        assertEquals(pending, resumed.rotation().orElseThrow());
        assertEquals(leafDraws, leaf.draws());
        assertEquals(entropyDraws, entropy.draws());

        PlayerSnapshot player = player(START, 0.0F, 0.0F, STILL);
        SpatialCaptureRequest next = macro.spatialRequest(player, EPOCH).orElseThrow();
        SpatialSnapshot wrongWorld = captured(next, START, Map.of());
        MacroDecision worldRejected = macro.tick(context(
                12L, EPOCH + 1L, player, wrongWorld, grounded(),
                MacroRotationLeaseState.idle(0L)));
        assertEquals("spatial-unknown-or-stale", worldRejected.status());
        assertTrue(worldRejected.inputs().isEmpty());

        macro.onStop();
        assertEquals(SShapeMushroomSDSMacro.State.STOPPED, macro.state());
        assertTrue(macro.spatialRequest(player, EPOCH).isEmpty());
        assertEquals("stopped", macro.tick(new FarmingContext(
                13L, EPOCH, Observation.unknown(), Observation.unknown(), Observation.unknown(),
                false, ServerResponsiveness.UNKNOWN)).status());
    }

    private static void assertCropSlot(
            int up,
            String block,
            SShapeMushroomSDSMacro.State expected
    ) {
        SShapeMushroomSDSMacro macro = macro(validSettings());
        MacroDecision decision = finishStartup(macro, 0.0F, Map.of(
                target(START, 0.0F, true, up), Observation.present(crop(block))));
        assertEquals(expected, macro.state());
        assertEquals(Set.of(InputAction.RIGHT, InputAction.ATTACK), decision.inputs());
    }

    private static SShapeMushroomSDSMacro scanningMacro() {
        SShapeMushroomSDSMacro macro = macro(validSettings());
        MacroDecision decision = finishStartup(macro, 0.0F, Map.of());
        assertEquals("mushroom-target-absent", decision.status());
        assertEquals(1, macro.scanDistance());
        assertEquals(SShapeMushroomSDSMacro.ScanPhase.RIGHT_OBSTACLE, macro.scanPhase());
        return macro;
    }

    private static SShapeMushroomSDSMacro laneMacro(boolean right, boolean alwaysHoldW) {
        MacroSettings settings = validSettings();
        settings.alwaysHoldW(alwaysHoldW);
        SShapeMushroomSDSMacro macro = macro(settings);
        chooseLane(macro, right, 0.0F, START);
        return macro;
    }

    private static MacroDecision chooseLane(
            SShapeMushroomSDSMacro macro,
            boolean right,
            float yaw,
            PositionSnapshot position
    ) {
        MacroDecision startup = step(macro, 0L, position, yaw, 0.0F,
                STILL, grounded(), Map.of());
        return stepObservations(macro, 1L, position,
                startup.rotation().orElseThrow().yaw(),
                startup.rotation().orElseThrow().pitch(), STILL, grounded(), Map.of(
                        target(position, macro.cardinalYaw(), right, 0),
                        Observation.present(crop("red_mushroom"))));
    }

    private static MacroDecision finishStartup(
            SShapeMushroomSDSMacro macro,
            float yaw,
            Map<BlockPosition, Observation<BlockStateSnapshot>> overrides
    ) {
        MacroDecision startup = step(macro, 0L, START, yaw, 0.0F,
                STILL, grounded(), Map.of());
        return stepObservations(macro, 1L, START,
                startup.rotation().orElseThrow().yaw(),
                startup.rotation().orElseThrow().pitch(), STILL, grounded(), overrides);
    }

    private static RewarpResult completeRewarp(
            boolean rotateAfterWarped,
            float observedYaw,
            float observedPitch
    ) {
        MacroSettings settings = new MacroSettings();
        settings.macroMode(MacroMode.MUSHROOM_SDS);
        settings.spawn(new RewarpPosition(10, 1, 0));
        assertTrue(settings.addRewarp(new RewarpPosition(2, 1, 0)));
        settings.rotateAfterWarped(rotateAfterWarped);
        QueueRandom leaf = zeros(20);
        QueueRandom entropy = zeros(20);
        SShapeMushroomSDSMacro macro = new SShapeMushroomSDSMacro(settings, leaf, entropy);
        macro.onStart();
        PositionSnapshot origin = new PositionSnapshot(2.5D, 1.0D, 0.5D);
        MacroDecision startup = step(macro, 0L, origin, 0.0F, 0.0F,
                STILL, grounded(), Map.of());
        assertTrue(startup.rotation().isPresent());
        assertEquals("rewarp-dwell", step(macro, 1L, origin, 0.0F, 6.5F,
                STILL, grounded(), Map.of()).status());
        long warpAt = 1L + TimeUnit.MILLISECONDS.toNanos(400L);
        assertTrue(step(macro, warpAt, origin, 0.0F, 6.5F,
                STILL, grounded(), Map.of()).warp().isPresent());
        PositionSnapshot spawn = new PositionSnapshot(10.5D, 1.0D, 0.5D);
        long landedAt = warpAt + 1L;
        step(macro, landedAt, spawn, observedYaw, observedPitch,
                STILL, grounded(), Map.of());
        long postAt = landedAt + TimeUnit.MILLISECONDS.toNanos(1_500L);
        step(macro, postAt, spawn, observedYaw, observedPitch,
                STILL, grounded(), Map.of());
        long correctionAt = postAt + TimeUnit.MILLISECONDS.toNanos(600L);
        MacroDecision correction = step(macro, correctionAt,
                spawn, observedYaw, observedPitch, STILL, grounded(), Map.of());
        return new RewarpResult(macro, correction, leaf, entropy, correctionAt);
    }

    private static SShapeMushroomSDSMacro macro(MacroSettings settings) {
        SShapeMushroomSDSMacro macro = new SShapeMushroomSDSMacro(
                settings, zeros(20), zeros(20));
        macro.onStart();
        return macro;
    }

    private static MacroSettings validSettings() {
        MacroSettings settings = new MacroSettings();
        settings.macroMode(MacroMode.MUSHROOM_SDS);
        settings.spawn(new RewarpPosition(100, 70, 100));
        assertTrue(settings.addRewarp(new RewarpPosition(90, 70, 90)));
        return settings;
    }

    private static MacroDecision step(
            SShapeMushroomSDSMacro macro,
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
        MacroRotationLeaseState lease = automaticLease(macro, yaw, pitch);
        return stepObservations(macro, now, position, yaw, pitch, motion, posture,
                observations, lease);
    }

    private static MacroDecision step(
            SShapeMushroomSDSMacro macro,
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
            SShapeMushroomSDSMacro macro,
            long now,
            PositionSnapshot position,
            float yaw,
            float pitch,
            MotionSnapshot motion,
            PlayerPosture posture,
            Map<BlockPosition, Observation<BlockStateSnapshot>> overrides
    ) {
        return stepObservations(macro, now, position, yaw, pitch, motion, posture,
                overrides, automaticLease(macro, yaw, pitch));
    }

    private static MacroDecision stepObservations(
            SShapeMushroomSDSMacro macro,
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
                now, EPOCH, player, captured(request, position, overrides), posture, lease));
    }

    private static MacroRotationLeaseState automaticLease(
            SShapeMushroomSDSMacro macro,
            float yaw,
            float pitch
    ) {
        return macro.pendingRotation()
                .map(pending -> Math.abs(dev.hylfrd.farmhelper.macro.MacroAngles.shortestDelta(
                                yaw, pending.yaw())) <= 0.1F
                                && Math.abs(pitch - pending.pitch()) <= 0.1F
                        ? MacroRotationLeaseState.terminal(
                                pending.requestToken(), RotationTerminalReason.COMPLETED, 2L)
                        : MacroRotationLeaseState.active(pending.requestToken(), false, 1L))
                .orElseGet(() -> MacroRotationLeaseState.idle(0L));
    }

    private static FarmingContext context(
            long now,
            long epoch,
            PlayerSnapshot player,
            SpatialSnapshot spatial,
            PlayerPosture posture,
            MacroRotationLeaseState lease
    ) {
        return new FarmingContext(
                now, epoch, Observation.present(player), Observation.present(spatial),
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
            boolean right
    ) {
        return sideBodyBlock(origin, cardinal, right, 1);
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

    private static BlockPosition backBodyBlock(PositionSnapshot origin, float cardinal) {
        RelativeFrame frame = RelativeFrame.cardinal(cardinal);
        return new BlockPosition(
                (int) Math.floor(origin.x() - frame.forwardX()),
                (int) Math.floor(origin.y()),
                (int) Math.floor(origin.z() - frame.forwardZ()));
    }

    private static BoxSnapshot body(PositionSnapshot player) {
        return new BoxSnapshot(
                player.x() - 0.3D, player.y(), player.z() - 0.3D,
                player.x() + 0.3D, player.y() + 1.8D, player.z() + 0.3D);
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

    private static QueueRandom zeros(int count) {
        return new QueueRandom(new double[count]);
    }

    private record RewarpResult(
            SShapeMushroomSDSMacro macro,
            MacroDecision decision,
            QueueRandom leaf,
            QueueRandom entropy,
            long now
    ) {
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
