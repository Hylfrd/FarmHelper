package dev.hylfrd.farmhelper.macro.impl;

import dev.hylfrd.farmhelper.control.input.InputAction;
import dev.hylfrd.farmhelper.control.rotation.RotationProfile;
import dev.hylfrd.farmhelper.control.rotation.RotationTerminalReason;
import dev.hylfrd.farmhelper.macro.FarmingContext;
import dev.hylfrd.farmhelper.macro.MacroAngles;
import dev.hylfrd.farmhelper.macro.MacroDecision;
import dev.hylfrd.farmhelper.macro.MacroMode;
import dev.hylfrd.farmhelper.macro.MacroPauseCause;
import dev.hylfrd.farmhelper.macro.MacroRecoveryReason;
import dev.hylfrd.farmhelper.macro.MacroRotationLeaseState;
import dev.hylfrd.farmhelper.macro.MacroSettings;
import dev.hylfrd.farmhelper.macro.MacroTerminalReason;
import dev.hylfrd.farmhelper.macro.MacroTiming;
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
import dev.hylfrd.farmhelper.runtime.spatial.SpaceStatus;
import dev.hylfrd.farmhelper.runtime.spatial.SpatialCaptureRequest;
import dev.hylfrd.farmhelper.runtime.spatial.SpatialSnapshot;
import dev.hylfrd.farmhelper.runtime.spatial.UpstreamCurrentYawFrame;
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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SShapeCocoaBeanMacroTest {
    private static final long EPOCH = 41L;
    private static final PositionSnapshot START = new PositionSnapshot(0.5D, 1.0D, 0.5D);
    private static final MotionSnapshot STILL = new MotionSnapshot(0.0D, 0.0D, 0.0D);

    @Test
    void constructorAcceptsOnlyCocoaModesAndSnapshotsSettings() {
        MacroSettings wrong = validSettings(MacroMode.MELON_PUMPKIN_DEFAULT);
        assertThrows(IllegalArgumentException.class,
                () -> new SShapeCocoaBeanMacro(wrong, zeros(4)));

        MacroSettings source = validSettings(MacroMode.COCOA);
        SShapeCocoaBeanMacro macro = new SShapeCocoaBeanMacro(source, zeros(8));
        source.macroMode(MacroMode.COCOA_TRAPDOORS);

        assertEquals("s-shape-cocoa-beans", macro.id());
        macro.onStart();
        assertEquals(SShapeCocoaBeanMacro.State.STARTUP, macro.state());
    }

    @Test
    void noneDirectionMatchesExactExhaustiveTruthTable() {
        for (int mask = 0; mask < 16; mask++) {
            boolean front = (mask & 1) != 0;
            boolean back = (mask & 2) != 0;
            boolean right = (mask & 4) != 0;
            boolean left = (mask & 8) != 0;
            SShapeCocoaBeanMacro.State expected = front && right
                    ? SShapeCocoaBeanMacro.State.FORWARD
                    : back ? SShapeCocoaBeanMacro.State.BACKWARD
                    : front ? SShapeCocoaBeanMacro.State.FORWARD
                    : SShapeCocoaBeanMacro.State.NONE;
            assertEquals(expected, SShapeCocoaBeanMacro.nextState(
                    SShapeCocoaBeanMacro.State.NONE,
                    SShapeCocoaBeanMacro.Walkability.known(front, back, right, left)),
                    "mask=" + mask);
        }
    }

    @Test
    void transitionTablePreservesUpstreamPriorities() {
        assertEquals(SShapeCocoaBeanMacro.State.SWITCHING_LANE, next(
                SShapeCocoaBeanMacro.State.BACKWARD, true, false, true, false));
        assertEquals(SShapeCocoaBeanMacro.State.BACKWARD, next(
                SShapeCocoaBeanMacro.State.BACKWARD, true, true, true, true));
        assertEquals(SShapeCocoaBeanMacro.State.SWITCHING_SIDE, next(
                SShapeCocoaBeanMacro.State.FORWARD, false, true, true, false));
        assertEquals(SShapeCocoaBeanMacro.State.BACKWARD, next(
                SShapeCocoaBeanMacro.State.FORWARD, true, true, false, true));
        assertEquals(SShapeCocoaBeanMacro.State.FORWARD, next(
                SShapeCocoaBeanMacro.State.FORWARD, true, false, false, true));
        assertEquals(SShapeCocoaBeanMacro.State.NONE, next(
                SShapeCocoaBeanMacro.State.FORWARD, false, false, true, true));
        assertEquals(SShapeCocoaBeanMacro.State.BACKWARD, next(
                SShapeCocoaBeanMacro.State.SWITCHING_SIDE, true, true, false, true));
        assertEquals(SShapeCocoaBeanMacro.State.SWITCHING_SIDE, next(
                SShapeCocoaBeanMacro.State.SWITCHING_SIDE, true, true, true, true));
        assertEquals(SShapeCocoaBeanMacro.State.FORWARD, next(
                SShapeCocoaBeanMacro.State.SWITCHING_LANE, true, false, false, true));
        assertEquals(SShapeCocoaBeanMacro.State.SWITCHING_LANE, next(
                SShapeCocoaBeanMacro.State.SWITCHING_LANE, true, false, true, true));
        assertThrows(IllegalArgumentException.class, () ->
                SShapeCocoaBeanMacro.nextState(SShapeCocoaBeanMacro.State.NONE,
                        new SShapeCocoaBeanMacro.Walkability(
                                SpaceStatus.UNKNOWN, SpaceStatus.PASSABLE,
                                SpaceStatus.PASSABLE, SpaceStatus.PASSABLE)));
    }

    @Test
    void lineFractionsUseStrictCardinalBoundaries() {
        assertTrue(SShapeCocoaBeanMacro.lineFraction(180.0F, 2.489D, 0.5D));
        assertFalse(SShapeCocoaBeanMacro.lineFraction(180.0F, 2.488D, 0.5D));
        assertTrue(SShapeCocoaBeanMacro.lineFraction(270.0F, 0.5D, 2.489D));
        assertFalse(SShapeCocoaBeanMacro.lineFraction(270.0F, 0.5D, 2.488D));
        assertTrue(SShapeCocoaBeanMacro.lineFraction(90.0F, 0.5D, 2.511D));
        assertFalse(SShapeCocoaBeanMacro.lineFraction(90.0F, 0.5D, 2.512D));
        assertTrue(SShapeCocoaBeanMacro.lineFraction(0.0F, 2.511D, 0.5D));
        assertFalse(SShapeCocoaBeanMacro.lineFraction(0.0F, 2.512D, 0.5D));
        assertEquals(
                SShapeCocoaBeanMacro.lineFraction(0.0F, 2.511D, 0.5D),
                SShapeCocoaBeanMacro.lineFraction(360.0F, -2.511D, 0.5D));
    }

    @Test
    void wallHugWindowsAreStrictForBothModesAndSignedCoordinates() {
        assertWindow(7, 0.0F, 0.1D, false);
        assertWindow(7, 0.0F, 0.1001D, true);
        assertWindow(7, 0.0F, 0.6499D, true);
        assertWindow(7, 0.0F, 0.65D, false);
        assertWindow(7, 90.0F, -0.8999D, true);
        assertWindow(7, 90.0F, -0.35D, false);
        assertWindow(7, 180.0F, 0.3501D, true);
        assertWindow(7, 180.0F, 0.9D, false);
        assertWindow(7, 270.0F, -0.6499D, true);
        assertWindow(7, 270.0F, -0.1D, false);

        assertWindow(8, 0.0F, -0.8999D, true);
        assertWindow(8, 0.0F, -0.5D, false);
        assertWindow(8, 90.0F, 0.4999D, true);
        assertWindow(8, 90.0F, 0.5D, false);
        assertWindow(8, 180.0F, -0.4999D, true);
        assertWindow(8, 180.0F, -0.1D, false);
        assertWindow(8, 270.0F, 0.5001D, true);
        assertWindow(8, 270.0F, 0.9D, false);

        assertFalse(SShapeCocoaBeanMacro.hugWindow(7, 0.0F, -2.2D, 0.0D));
        assertTrue(SShapeCocoaBeanMacro.hugWindow(7, 0.0F, 2.2D, 0.0D));
    }

    @Test
    void lineCompletionAndUpdateUseObservedCardinalAcrossAllAxes() {
        for (float currentYaw : List.of(0.0F, 90.0F, 180.0F, 270.0F)) {
            float startupYaw = MacroAngles.closestCardinal(currentYaw + 90.0F);
            assertNotEquals(MacroAngles.closestCardinal(currentYaw), startupYaw);
            SShapeCocoaBeanMacro complete = alignedMacroAtYaw(
                    validSettings(MacroMode.COCOA), zeros(30), zeros(20), START, startupYaw);
            enterSwitchingLane(complete, START, startupYaw, currentYaw);
            Map<BlockPosition, Observation<BlockStateSnapshot>> completeEvidence = new HashMap<>(
                    walkability(START, startupYaw, true, false, true, true));
            completeEvidence.put(UpstreamCurrentYawFrame.from(currentYaw).blockAt(
                    START.x(), START.y(), START.z(), -1, 0, 1), Observation.present(full()));

            MacroDecision invoked = step(complete, 5L, START, currentYaw, -70.0F,
                    STILL, grounded(), completeEvidence);

            assertEquals(SShapeCocoaBeanMacro.State.FORWARD, complete.state(),
                    "invoke yaw=" + currentYaw);
            assertEquals("line-change-complete", invoked.status());
            assertTrue(invoked.inputs().isEmpty());

            SShapeCocoaBeanMacro update = alignedMacroAtYaw(
                    validSettings(MacroMode.COCOA), zeros(30), zeros(20), START, startupYaw);
            enterSwitchingLane(update, START, startupYaw, currentYaw);
            MacroDecision updated = step(update, 5L, START, currentYaw, -70.0F,
                    STILL, grounded(),
                    walkability(START, startupYaw, true, false, false, true));
            assertEquals(SShapeCocoaBeanMacro.State.FORWARD, update.state(),
                    "update yaw=" + currentYaw);
            assertTrue(updated.inputs().containsAll(
                    Set.of(InputAction.FORWARD, InputAction.ATTACK)));
            assertFalse(updated.inputs().contains(InputAction.RIGHT));
        }
    }

    @Test
    void productionWallHugUsesObservedCardinalForBothModesAcrossAllAxes() {
        for (MacroMode mode : List.of(MacroMode.COCOA, MacroMode.COCOA_TRAPDOORS)) {
            for (float currentYaw : List.of(0.0F, 90.0F, 180.0F, 270.0F)) {
                float startupYaw = MacroAngles.closestCardinal(currentYaw + 90.0F);
                PositionSnapshot position = currentYaw == 0.0F
                        ? new PositionSnapshot(0.2D, 1.0D, 0.5D)
                        : currentYaw == 90.0F
                                ? new PositionSnapshot(0.5D, 1.0D, 0.2D)
                                : currentYaw == 180.0F
                                        ? new PositionSnapshot(0.6D, 1.0D, 0.5D)
                                        : new PositionSnapshot(0.5D, 1.0D, 0.6D);
                SShapeCocoaBeanMacro macro = alignedMacroAtYaw(
                        validSettings(mode), zeros(20), zeros(20), position, startupYaw);
                Map<BlockPosition, Observation<BlockStateSnapshot>> evidence = new HashMap<>(
                        walkability(position, startupYaw, true, false, true, true));
                evidence.put(UpstreamCurrentYawFrame.from(currentYaw).blockAt(
                        position.x(), position.y(), position.z(), -1, 0, 0),
                        Observation.present(mode == MacroMode.COCOA_TRAPDOORS
                                ? trapdoorFull() : full()));

                MacroDecision decision = step(macro, 2L, position, currentYaw, -70.0F,
                        STILL, grounded(), evidence);

                assertEquals(SShapeCocoaBeanMacro.State.FORWARD, macro.state());
                assertEquals(Set.of(InputAction.FORWARD, InputAction.ATTACK, InputAction.LEFT),
                        decision.inputs(), "mode=" + mode + " yaw=" + currentYaw);
            }
        }
    }

    @Test
    void startupSamplesPitchThenDurationAndUsesSeparateBackEntropy() {
        QueueRandom leaf = new QueueRandom(0.5D, 0.999D);
        QueueRandom entropy = new QueueRandom(0.25D, 0.75D);
        SShapeCocoaBeanMacro macro = new SShapeCocoaBeanMacro(
                validSettings(MacroMode.COCOA), leaf, entropy);
        macro.onStart(0L);

        MacroDecision decision = step(macro, 0L, START, 88.0F, 12.0F,
                STILL, grounded(), Map.of(), MacroRotationLeaseState.idle(0L));

        var request = decision.rotation().orElseThrow();
        assertEquals(90.0F, request.yaw());
        assertEquals(-69.7F, request.pitch(), 0.0001F);
        assertEquals(RotationProfile.BACK, request.profile());
        assertEquals(-0.125F, request.backModifier(), 0.0001F);
        assertTrue(request.durationMillis() >= 50L);
        assertEquals(2, leaf.draws());
        assertEquals(2, entropy.draws());
        assertTrue(decision.inputs().isEmpty());
    }

    @Test
    void customPitchAndYawSkipPitchDrawWhileSuppressionSkipsDurationAndEntropy() {
        MacroSettings custom = validSettings(MacroMode.COCOA_TRAPDOORS);
        custom.customPitch(true);
        custom.customPitchLevel(-42.0F);
        custom.customYaw(true);
        custom.customYawLevel(-91.0F);
        QueueRandom leaf = new QueueRandom(0.5D);
        QueueRandom entropy = new QueueRandom(0.0D, 0.0D);
        SShapeCocoaBeanMacro macro = new SShapeCocoaBeanMacro(custom, leaf, entropy);
        macro.onStart();
        var request = step(macro, 0L, START, 0.0F, 0.0F,
                STILL, grounded(), Map.of()).rotation().orElseThrow();
        assertEquals(-91.0F, request.yaw());
        assertEquals(-42.0F, request.pitch());
        assertEquals(1, leaf.draws());
        assertEquals(2, entropy.draws());

        MacroSettings suppressed = validSettings(MacroMode.COCOA);
        suppressed.dontFixAfterWarping(true);
        QueueRandom suppressedLeaf = new QueueRandom(0.25D);
        QueueRandom suppressedEntropy = zeros(0);
        SShapeCocoaBeanMacro noFix = new SShapeCocoaBeanMacro(
                suppressed, suppressedLeaf, suppressedEntropy);
        noFix.onStart();
        MacroDecision result = step(noFix, 0L, START, 0.0F, 0.0F,
                STILL, grounded(), Map.of());
        assertEquals("startup-fix-suppressed", result.status());
        assertTrue(result.rotation().isEmpty());
        assertEquals(1, suppressedLeaf.draws());
        assertEquals(0, suppressedEntropy.draws());
    }

    @Test
    void productionTickTransitionsAndInputsMatchCocoaStateMachine() {
        SShapeCocoaBeanMacro macro = alignedMacro(MacroMode.COCOA, START);
        assertEquals(SShapeCocoaBeanMacro.State.FORWARD, macro.state());

        MacroDecision side = step(macro, 2L, START, 0.0F, -70.0F,
                STILL, grounded(), walkability(false, true, true, false));
        assertEquals(SShapeCocoaBeanMacro.State.SWITCHING_SIDE, macro.state());
        assertEquals(Set.of(InputAction.RIGHT), side.inputs());
        assertFalse(side.inputs().contains(InputAction.ATTACK));

        MacroDecision backward = step(macro, 3L, START, 0.0F, -70.0F,
                STILL, grounded(), walkability(true, true, false, true));
        assertEquals(SShapeCocoaBeanMacro.State.BACKWARD, macro.state());
        assertEquals(Set.of(InputAction.BACKWARD, InputAction.ATTACK), backward.inputs());

        MacroDecision lane = step(macro, 4L, START, 0.0F, -70.0F,
                STILL, grounded(), walkability(true, false, true, true));
        assertEquals(SShapeCocoaBeanMacro.State.SWITCHING_LANE, macro.state());
        assertEquals(Set.of(InputAction.RIGHT), lane.inputs());
        assertFalse(lane.inputs().contains(InputAction.ATTACK));
    }

    @Test
    void switchingLaneCompletesBeforeInputAndDoesNotRequireRightBlocked() {
        SShapeCocoaBeanMacro macro = alignedMacro(MacroMode.COCOA, START);
        step(macro, 2L, START, 0.0F, -70.0F,
                STILL, grounded(), walkability(false, true, true, false));
        step(macro, 3L, START, 0.0F, -70.0F,
                STILL, grounded(), walkability(true, true, false, true));
        step(macro, 4L, START, 0.0F, -70.0F,
                STILL, grounded(), walkability(true, false, true, true));
        assertEquals(SShapeCocoaBeanMacro.State.SWITCHING_LANE, macro.state());

        Map<BlockPosition, Observation<BlockStateSnapshot>> complete = new HashMap<>(
                walkability(true, false, true, true));
        complete.put(new BlockPosition(1, 1, 1), Observation.present(full()));
        MacroDecision decision = step(macro, 5L, START, 0.0F, -70.0F,
                STILL, grounded(), complete);

        assertEquals(SShapeCocoaBeanMacro.State.FORWARD, macro.state());
        assertTrue(decision.inputs().isEmpty());
        assertEquals("line-change-complete", decision.status());
    }

    @Test
    void forwardWallHugRequiresKnownSolidAndModeSpecificWindow() {
        PositionSnapshot insideMode7 = new PositionSnapshot(0.2D, 1.0D, 0.5D);
        SShapeCocoaBeanMacro mode7 = alignedMacro(MacroMode.COCOA, insideMode7);
        Map<BlockPosition, Observation<BlockStateSnapshot>> wall = new HashMap<>();
        wall.put(new BlockPosition(1, 1, 0), Observation.present(full()));
        wall.put(new BlockPosition(0, 1, -1), Observation.present(full()));
        MacroDecision hugging = step(mode7, 2L, insideMode7, 0.0F, -70.0F,
                STILL, grounded(), wall);
        assertEquals(Set.of(InputAction.FORWARD, InputAction.ATTACK, InputAction.LEFT),
                hugging.inputs());

        PositionSnapshot mode8Outside = new PositionSnapshot(0.6D, 1.0D, 0.5D);
        SShapeCocoaBeanMacro mode8 = alignedMacro(MacroMode.COCOA_TRAPDOORS, mode8Outside);
        wall = new HashMap<>();
        wall.put(new BlockPosition(1, 1, 0), Observation.present(trapdoorFull()));
        wall.put(new BlockPosition(0, 1, -1), Observation.present(full()));
        MacroDecision notHugging = step(mode8, 2L, mode8Outside, 0.0F, -70.0F,
                STILL, grounded(), wall);
        assertEquals(Set.of(InputAction.FORWARD, InputAction.ATTACK), notHugging.inputs());
    }

    @Test
    void unknownWalkabilityWallAndLineEvidenceFailClosed() {
        SShapeCocoaBeanMacro macro = alignedMacro(MacroMode.COCOA, START);
        MacroDecision unknownWalk = step(macro, 2L, START, 0.0F, -70.0F,
                STILL, grounded(), Map.of(
                        new BlockPosition(0, 1, 1), Observation.unknown()));
        assertTrue(unknownWalk.inputs().isEmpty());
        assertEquals("walkability-unknown", unknownWalk.status());

        macro = alignedMacro(MacroMode.COCOA, START);
        Map<BlockPosition, Observation<BlockStateSnapshot>> unknownWall = new HashMap<>(
                walkability(true, false, true, true));
        unknownWall.put(new BlockPosition(1, 1, 0), Observation.unknown());
        MacroDecision wall = step(macro, 2L, START, 0.0F, -70.0F,
                STILL, grounded(), unknownWall);
        assertTrue(wall.inputs().isEmpty());
        assertEquals("walkability-unknown", wall.status());

        macro = alignedMacro(MacroMode.COCOA, START);
        step(macro, 2L, START, 0.0F, -70.0F,
                STILL, grounded(), walkability(false, true, true, false));
        step(macro, 3L, START, 0.0F, -70.0F,
                STILL, grounded(), walkability(true, true, false, true));
        step(macro, 4L, START, 0.0F, -70.0F,
                STILL, grounded(), walkability(true, false, true, true));
        Map<BlockPosition, Observation<BlockStateSnapshot>> unknownLine = new HashMap<>(
                walkability(true, false, true, true));
        unknownLine.put(new BlockPosition(1, 1, 1), Observation.unknown());
        MacroDecision line = step(macro, 5L, START, 0.0F, -70.0F,
                STILL, grounded(), unknownLine);
        assertEquals("line-change-unknown", line.status());
        assertTrue(line.inputs().isEmpty());
    }

    @Test
    void cocoaMaturityAndUnrelatedBlocksDoNotGateBlindAttack() {
        PositionSnapshot inside = new PositionSnapshot(0.2D, 1.0D, 0.5D);
        SShapeCocoaBeanMacro youngMacro = alignedMacro(MacroMode.COCOA, inside);
        SShapeCocoaBeanMacro matureMacro = alignedMacro(MacroMode.COCOA, inside);
        BlockPosition wall = new BlockPosition(1, 1, 0);
        Map<BlockPosition, Observation<BlockStateSnapshot>> youngEvidence = new HashMap<>();
        youngEvidence.put(wall, Observation.present(cocoa(0)));
        youngEvidence.put(new BlockPosition(0, 1, -1), Observation.present(full()));
        Map<BlockPosition, Observation<BlockStateSnapshot>> matureEvidence = new HashMap<>();
        matureEvidence.put(wall, Observation.present(cocoa(2)));
        matureEvidence.put(new BlockPosition(0, 1, -1), Observation.present(full()));
        MacroDecision young = step(youngMacro, 2L, inside, 0.0F, -70.0F,
                STILL, grounded(), youngEvidence);
        MacroDecision mature = step(matureMacro, 2L, inside, 0.0F, -70.0F,
                STILL, grounded(), matureEvidence);
        assertEquals(young.inputs(), mature.inputs());
        assertEquals(Set.of(InputAction.FORWARD, InputAction.ATTACK), young.inputs());
    }

    @Test
    void dropImmediatelyReleasesAcrossAirborneTicksAndRefreshesOnLanding() {
        QueueRandom leaf = zeros(10);
        QueueRandom entropy = zeros(10);
        SShapeCocoaBeanMacro macro = alignedMacro(
                validSettings(MacroMode.COCOA), leaf, entropy, START);
        int leafDraws = leaf.draws();
        int entropyDraws = entropy.draws();

        MacroDecision entered = step(macro, 2L,
                new PositionSnapshot(0.5D, 1.76D, 0.5D), 0.0F, -70.0F,
                STILL, new PlayerPosture(false, false, false), Map.of());
        assertEquals(SShapeCocoaBeanMacro.State.DROPPING, macro.state());
        assertTrue(entered.inputs().isEmpty());
        assertTrue(entered.rotation().isEmpty());

        MacroDecision airborne = step(macro, 3L,
                new PositionSnapshot(0.5D, 2.5D, 0.5D), 0.0F, -70.0F,
                STILL, new PlayerPosture(false, false, false), Map.of());
        assertEquals("dropping", airborne.status());
        assertTrue(airborne.inputs().isEmpty());

        MacroDecision landed = step(macro, 4L,
                new PositionSnapshot(0.5D, 3.0D, 0.5D), 0.0F, -70.0F,
                STILL, grounded(), Map.of());
        assertEquals(SShapeCocoaBeanMacro.State.NONE, macro.state());
        assertEquals("drop-complete", landed.status());
        assertTrue(landed.rotation().isEmpty());
        assertEquals(leafDraws, leaf.draws());
        assertEquals(entropyDraws, entropy.draws());
    }

    @Test
    void pauseResumePreservesStateAndPendingRotationWithoutNewEntropy() {
        QueueRandom leaf = zeros(8);
        QueueRandom entropy = zeros(8);
        SShapeCocoaBeanMacro macro = new SShapeCocoaBeanMacro(
                validSettings(MacroMode.COCOA), leaf, entropy);
        macro.onStart();
        MacroDecision initial = step(macro, 0L, START, 0.0F, 0.0F,
                STILL, grounded(), Map.of(), MacroRotationLeaseState.idle(0L));
        var request = initial.rotation().orElseThrow();
        int leafDraws = leaf.draws();
        int entropyDraws = entropy.draws();

        macro.onPause(Set.of(MacroPauseCause.SCREEN_OPEN), 1L);
        macro.onResume(TimeUnit.SECONDS.toNanos(2L));
        MacroDecision resumed = step(macro, TimeUnit.SECONDS.toNanos(2L) + 1L,
                START, 0.0F, 0.0F, STILL, grounded(), Map.of(),
                MacroRotationLeaseState.terminal(
                        request.requestToken(), RotationTerminalReason.OWNER_CANCELLED, 2L));

        assertEquals(request, resumed.rotation().orElseThrow());
        assertEquals(leafDraws, leaf.draws());
        assertEquals(entropyDraws, entropy.draws());
    }

    @Test
    void pauseResumePreservesMovingStateAndProgressDeadline() {
        SShapeCocoaBeanMacro macro = alignedMacro(MacroMode.COCOA, START);
        macro.onPause(Set.of(MacroPauseCause.SCREEN_OPEN), 100L);
        long resumedAt = TimeUnit.SECONDS.toNanos(2L);
        macro.onResume(resumedAt);

        MacroDecision resumed = step(macro, resumedAt + TimeUnit.MILLISECONDS.toNanos(100L),
                START, 0.0F, -70.0F, STILL, grounded(),
                walkability(true, false, true, true));

        assertEquals(SShapeCocoaBeanMacro.State.FORWARD, macro.state());
        assertEquals(Set.of(InputAction.FORWARD, InputAction.ATTACK), resumed.inputs());
    }

    @Test
    void stopAndTerminalPathsReleaseControls() {
        SShapeCocoaBeanMacro macro = alignedMacro(MacroMode.COCOA, START);
        macro.onStop(MacroTerminalReason.WORLD_CHANGE);
        assertEquals(SShapeCocoaBeanMacro.State.STOPPED, macro.state());
        assertTrue(macro.spatialRequest(player(START, 0.0F, -70.0F, STILL), EPOCH).isEmpty());
        MacroDecision stopped = macro.tick(context(
                3L, player(START, 0.0F, -70.0F, STILL), Observation.unknown(), grounded(),
                MacroRotationLeaseState.idle(0L), EPOCH));
        assertTrue(stopped.inputs().isEmpty());
        assertEquals("stopped", stopped.status());
    }

    @Test
    void stalledMovementHandsOffWithoutPrivateRecovery() {
        SShapeCocoaBeanMacro macro = alignedMacro(MacroMode.COCOA, START);
        long window = SShapeCocoaBeanMacro.PROGRESS_WINDOW_NANOS;
        Map<BlockPosition, Observation<BlockStateSnapshot>> forwardOnly =
                walkability(true, false, true, true);
        MacroDecision first = step(macro, window + 2L, START, 0.0F, -70.0F,
                STILL, grounded(), forwardOnly);
        MacroDecision second = step(macro, window * 2L + 2L, START, 0.0F, -70.0F,
                STILL, grounded(), forwardOnly);
        MacroDecision third = step(macro, window * 3L + 2L, START, 0.0F, -70.0F,
                STILL, grounded(), forwardOnly);
        assertEquals("row-stall-observed-1", first.status());
        assertEquals("row-stall-observed-2", second.status());
        assertEquals(SShapeCocoaBeanMacro.State.RECOVERY_HANDOFF, macro.state());
        assertEquals(MacroRecoveryReason.ROW_STALLED,
                third.recovery().orElseThrow().reason());
        assertTrue(third.inputs().isEmpty());
        assertTrue(third.rotation().isEmpty());
    }

    @Test
    void sharedRewarpRestoresSavedRotationAndBlocksUntilCompleted() {
        QueueRandom leaf = zeros(20);
        QueueRandom entropy = zeros(10);
        Correction correction = reachPostRewarpCorrection(
                rewarpSettings(), leaf, entropy, 0.0F, -70.0F);
        var request = correction.decision().rotation().orElseThrow();
        assertEquals(0.0F, request.yaw());
        assertEquals(-70.0F, request.pitch());
        assertEquals(RotationProfile.BACK, request.profile());
        assertEquals(MacroTiming.scaledRotationMillis(
                500L, 50L, 0.0F, 0.0F), request.durationMillis());
        assertEquals(SShapeCocoaBeanMacro.State.POST_REWARP_ALIGNING,
                correction.macro().state());
        assertTrue(correction.decision().inputs().isEmpty());

        MacroDecision active = step(correction.macro(), correction.nowNanos() + 1L,
                correction.spawn(), 0.0F, -70.0F, STILL, grounded(), Map.of(),
                MacroRotationLeaseState.active(request.requestToken(), false, 2L));
        assertEquals(request, active.rotation().orElseThrow());
        MacroDecision completed = step(correction.macro(), correction.nowNanos() + 2L,
                correction.spawn(), request.yaw(), request.pitch(), STILL, grounded(), Map.of(),
                MacroRotationLeaseState.terminal(request.requestToken(),
                        RotationTerminalReason.COMPLETED, 3L));
        assertEquals(SShapeCocoaBeanMacro.State.NONE, correction.macro().state());
        assertEquals("post-rewarp-complete", completed.status());
        assertTrue(completed.inputs().isEmpty());
    }

    @Test
    void rotateAfterWarpTurnsSavedYawBy180AndDoublesLargeYawDuration() {
        MacroSettings settings = rewarpSettings();
        settings.rotateAfterWarped(true);
        Correction correction = reachPostRewarpCorrection(
                settings, zeros(20), zeros(10), 0.0F, -70.0F);

        var request = correction.decision().rotation().orElseThrow();
        assertEquals(-180.0F, request.yaw());
        assertEquals(-70.0F, request.pitch());
        assertEquals(MacroTiming.scaledRotationMillis(
                1_000L, 50L, -180.0F, 0.0F), request.durationMillis());
        assertTrue(correction.decision().inputs().isEmpty());
    }

    @Test
    void postRewarpSuppressionUsesCombinedStrictThresholdAndPitchMismatchRotates() {
        MacroSettings suppressed = rewarpSettings();
        suppressed.dontFixAfterWarping(true);
        QueueRandom suppressedLeaf = zeros(20);
        QueueRandom suppressedEntropy = zeros(10);
        Correction below = reachPostRewarpCorrection(
                suppressed, suppressedLeaf, suppressedEntropy, 0.5F, -69.5F);
        assertEquals("post-rewarp-fix-suppressed", below.decision().status());
        assertTrue(below.decision().rotation().isEmpty());
        assertEquals(SShapeCocoaBeanMacro.State.NONE, below.macro().state());
        assertEquals(3, suppressedLeaf.draws());
        assertEquals(2, suppressedEntropy.draws());

        MacroSettings threshold = rewarpSettings();
        threshold.dontFixAfterWarping(true);
        Correction exact = reachPostRewarpCorrection(
                threshold, zeros(20), zeros(10), 0.0F, -69.0F);
        assertTrue(exact.decision().rotation().isPresent());
        assertEquals(SShapeCocoaBeanMacro.State.POST_REWARP_ALIGNING, exact.macro().state());

        MacroSettings pitchMismatch = rewarpSettings();
        pitchMismatch.dontFixAfterWarping(true);
        Correction mismatch = reachPostRewarpCorrection(
                pitchMismatch, zeros(20), zeros(10), 0.1F, -68.9F);
        assertTrue(mismatch.decision().rotation().isPresent());
    }

    @Test
    void postRewarpAcknowledgementsRetryWithoutDuplicateDrawsAndCancelFailClosed() {
        QueueRandom leaf = zeros(20);
        QueueRandom entropy = zeros(10);
        Correction correction = reachPostRewarpCorrection(
                rewarpSettings(), leaf, entropy, 10.0F, -60.0F);
        var request = correction.decision().rotation().orElseThrow();
        int leafDraws = leaf.draws();
        int entropyDraws = entropy.draws();

        MacroDecision stale = step(correction.macro(), correction.nowNanos() + 1L,
                correction.spawn(), 10.0F, -60.0F, STILL, grounded(), Map.of(),
                MacroRotationLeaseState.terminal(request.requestToken() + 1L,
                        RotationTerminalReason.COMPLETED, 2L));
        assertEquals("post-rewarp-rotation-acknowledgement-stale", stale.status());
        assertTrue(stale.rotation().isEmpty());
        MacroDecision retry = step(correction.macro(), correction.nowNanos() + 2L,
                correction.spawn(), 10.0F, -60.0F, STILL, grounded(), Map.of(),
                MacroRotationLeaseState.terminal(request.requestToken(),
                        RotationTerminalReason.OWNER_CANCELLED, 3L));
        assertEquals(request, retry.rotation().orElseThrow());
        correction.macro().onPause(Set.of(MacroPauseCause.SCREEN_OPEN),
                correction.nowNanos() + 3L);
        correction.macro().onResume(correction.nowNanos() + 1_000L);
        MacroDecision afterPause = step(correction.macro(), correction.nowNanos() + 1_001L,
                correction.spawn(), 10.0F, -60.0F, STILL, grounded(), Map.of(),
                MacroRotationLeaseState.active(request.requestToken(), false, 4L));
        assertEquals(request, afterPause.rotation().orElseThrow());
        assertEquals(leafDraws, leaf.draws());
        assertEquals(entropyDraws, entropy.draws());

        Correction cancelled = reachPostRewarpCorrection(
                rewarpSettings(), zeros(20), zeros(10), 10.0F, -60.0F);
        var cancelledRequest = cancelled.decision().rotation().orElseThrow();
        MacroDecision rejected = step(cancelled.macro(), cancelled.nowNanos() + 1L,
                cancelled.spawn(), 10.0F, -60.0F, STILL, grounded(), Map.of(),
                MacroRotationLeaseState.terminal(cancelledRequest.requestToken(),
                        RotationTerminalReason.APPLICATION_FAILED, 2L));
        assertEquals("post-rewarp-rotation-cancelled", rejected.status());
        assertTrue(rejected.inputs().isEmpty());
        assertEquals(SShapeCocoaBeanMacro.State.POST_REWARP_ALIGNING,
                cancelled.macro().state());
        MacroDecision missing = step(cancelled.macro(), cancelled.nowNanos() + 2L,
                cancelled.spawn(), 10.0F, -60.0F, STILL, grounded(), Map.of(),
                MacroRotationLeaseState.idle(3L));
        assertEquals("post-rewarp-rotation-missing", missing.status());
        assertTrue(missing.inputs().isEmpty());
    }

    @Test
    void airborneRewarpSneakUsesSharedWindowAndThenReleases() {
        QueueRandom leaf = zeros(20);
        SShapeCocoaBeanMacro macro = new SShapeCocoaBeanMacro(
                rewarpSettings(), leaf, zeros(10));
        macro.onStart();
        var initialRotation = step(macro, 0L, START, 0.0F, 0.0F,
                STILL, grounded(), Map.of()).rotation().orElseThrow();
        step(macro, 1L, START, initialRotation.yaw(), initialRotation.pitch(),
                STILL, grounded(), Map.of(), MacroRotationLeaseState.terminal(
                        initialRotation.requestToken(), RotationTerminalReason.COMPLETED, 1L));
        long warpAt = 1L + TimeUnit.MILLISECONDS.toNanos(400L);
        step(macro, warpAt, START, 0.0F, -70.0F,
                STILL, grounded(), Map.of());
        PositionSnapshot moved = new PositionSnapshot(3.5D, 2.0D, 0.5D);
        long airborneAt = warpAt + 1L;
        MacroDecision sneak = step(macro, airborneAt, moved, 0.0F, -70.0F,
                STILL, new PlayerPosture(true, false, false), Map.of());
        assertEquals(Set.of(InputAction.SNEAK), sneak.inputs());
        MacroDecision released = step(macro,
                airborneAt + TimeUnit.MILLISECONDS.toNanos(350L),
                moved, 0.0F, -70.0F, STILL,
                new PlayerPosture(true, false, false), Map.of());
        assertTrue(released.inputs().isEmpty());
        assertEquals("rewarp-airborne", released.status());
    }

    @Test
    void staleCaptureTokenBodyWorldAndPauseGenerationFailClosed() {
        SShapeCocoaBeanMacro macro = new SShapeCocoaBeanMacro(
                validSettings(MacroMode.COCOA), zeros(10));
        macro.onStart();
        PlayerSnapshot player = player(START, 0.0F, 0.0F, STILL);
        SpatialCaptureRequest request = macro.spatialRequest(player, EPOCH).orElseThrow();
        SpatialSnapshot good = captured(request, START, Map.of());
        SpatialSnapshot staleToken = new SpatialSnapshot(
                EPOCH, request.requestToken() + 1L, request.bounds(), -64, 320,
                good.playerBox(), good.chunks());
        assertEquals("spatial-unknown-or-stale", macro.tick(context(
                0L, player, Observation.present(staleToken), grounded(),
                MacroRotationLeaseState.idle(0L), EPOCH)).status());

        request = macro.spatialRequest(player, EPOCH).orElseThrow();
        good = captured(request, START, Map.of());
        SpatialSnapshot wrongBody = new SpatialSnapshot(
                EPOCH, request.requestToken(), request.bounds(), -64, 320,
                good.playerBox().move(0.1D, 0.0D, 0.0D), good.chunks());
        assertEquals("spatial-unknown-or-stale", macro.tick(context(
                0L, player, Observation.present(wrongBody), grounded(),
                MacroRotationLeaseState.idle(0L), EPOCH)).status());

        request = macro.spatialRequest(player, EPOCH).orElseThrow();
        good = captured(request, START, Map.of());
        macro.onPause(Set.of(MacroPauseCause.SCREEN_OPEN), 0L);
        assertEquals("spatial-unknown-or-stale", macro.tick(context(
                0L, player, Observation.present(good), grounded(),
                MacroRotationLeaseState.idle(0L), EPOCH)).status());

        SpatialCaptureRequest current = macro.spatialRequest(player, EPOCH).orElseThrow();
        SpatialSnapshot wrongWorld = captured(current, START, Map.of());
        assertEquals("spatial-unknown-or-stale", macro.tick(context(
                0L, player, Observation.present(wrongWorld), grounded(),
                MacroRotationLeaseState.idle(0L), EPOCH + 1L)).status());
    }

    private static SShapeCocoaBeanMacro.State next(
            SShapeCocoaBeanMacro.State state,
            boolean front,
            boolean back,
            boolean right,
            boolean left
    ) {
        return SShapeCocoaBeanMacro.nextState(
                state, SShapeCocoaBeanMacro.Walkability.known(front, back, right, left));
    }

    private static void assertWindow(int mode, float yaw, double coordinate, boolean expected) {
        double x = yaw == 0.0F || yaw == 180.0F ? coordinate : 0.0D;
        double z = yaw == 90.0F || yaw == 270.0F ? coordinate : 0.0D;
        assertEquals(expected, SShapeCocoaBeanMacro.hugWindow(mode, yaw, x, z),
                "mode=" + mode + " yaw=" + yaw + " coordinate=" + coordinate);
    }

    private static SShapeCocoaBeanMacro alignedMacro(
            MacroMode mode,
            PositionSnapshot position
    ) {
        return alignedMacro(validSettings(mode), zeros(20), zeros(20), position);
    }

    private static SShapeCocoaBeanMacro alignedMacro(
            MacroSettings settings,
            QueueRandom leaf,
            QueueRandom entropy,
            PositionSnapshot position
    ) {
        return alignedMacroAtYaw(settings, leaf, entropy, position, 0.0F);
    }

    private static SShapeCocoaBeanMacro alignedMacroAtYaw(
            MacroSettings settings,
            QueueRandom leaf,
            QueueRandom entropy,
            PositionSnapshot position,
            float startupYaw
    ) {
        SShapeCocoaBeanMacro macro = new SShapeCocoaBeanMacro(settings, leaf, entropy);
        macro.onStart();
        MacroDecision initial = step(macro, 0L, position, startupYaw, 0.0F,
                STILL, grounded(), Map.of(), MacroRotationLeaseState.idle(0L));
        var request = initial.rotation().orElseThrow();
        MacroDecision aligned = step(macro, 1L, position, request.yaw(), request.pitch(),
                STILL, grounded(), Map.of(), MacroRotationLeaseState.terminal(
                        request.requestToken(), RotationTerminalReason.COMPLETED, 1L));
        assertEquals(Set.of(InputAction.FORWARD, InputAction.ATTACK), aligned.inputs());
        return macro;
    }

    private static void enterSwitchingLane(
            SShapeCocoaBeanMacro macro,
            PositionSnapshot position,
            float savedCardinal,
            float observedYaw
    ) {
        step(macro, 2L, position, observedYaw, -70.0F,
                STILL, grounded(),
                walkability(position, savedCardinal, false, true, true, false));
        step(macro, 3L, position, observedYaw, -70.0F,
                STILL, grounded(),
                walkability(position, savedCardinal, true, true, false, true));
        step(macro, 4L, position, observedYaw, -70.0F,
                STILL, grounded(),
                walkability(position, savedCardinal, true, false, true, true));
        assertEquals(SShapeCocoaBeanMacro.State.SWITCHING_LANE, macro.state());
    }

    private static Correction reachPostRewarpCorrection(
            MacroSettings settings,
            QueueRandom leaf,
            QueueRandom entropy,
            float observedYaw,
            float observedPitch
    ) {
        SShapeCocoaBeanMacro macro = new SShapeCocoaBeanMacro(settings, leaf, entropy);
        macro.onStart();
        MacroDecision initial = step(macro, 0L, START, 10.0F, 0.0F,
                STILL, grounded(), Map.of());
        var initialRotation = initial.rotation().orElseThrow();
        MacroDecision dwell = step(macro, 1L, START,
                initialRotation.yaw(), initialRotation.pitch(), STILL, grounded(), Map.of(),
                MacroRotationLeaseState.terminal(initialRotation.requestToken(),
                        RotationTerminalReason.COMPLETED, 1L));
        assertEquals("rewarp-dwell", dwell.status());
        long warpAt = 1L + TimeUnit.MILLISECONDS.toNanos(400L);
        assertTrue(step(macro, warpAt, START, 0.0F, -70.0F,
                STILL, grounded(), Map.of()).warp().isPresent());
        PositionSnapshot spawn = new PositionSnapshot(10.5D, 1.0D, 10.5D);
        long landedAt = warpAt + 1L;
        step(macro, landedAt, spawn, observedYaw, observedPitch,
                STILL, grounded(), Map.of());
        long postAt = landedAt + SShapeCocoaBeanMacro.AFTER_WARP_NANOS;
        assertEquals("post-rewarp", step(macro, postAt, spawn, observedYaw, observedPitch,
                STILL, grounded(), Map.of()).status());
        long correctionAt = postAt + SShapeCocoaBeanMacro.POST_REWARP_NANOS;
        MacroDecision correction = step(macro, correctionAt,
                spawn, observedYaw, observedPitch, STILL, grounded(), Map.of());
        return new Correction(macro, correction, spawn, correctionAt);
    }

    private static MacroSettings validSettings(MacroMode mode) {
        MacroSettings settings = new MacroSettings();
        settings.macroMode(mode);
        settings.spawn(new RewarpPosition(100, 70, 100));
        assertTrue(settings.addRewarp(new RewarpPosition(90, 70, 90)));
        return settings;
    }

    private static MacroSettings rewarpSettings() {
        MacroSettings settings = new MacroSettings();
        settings.macroMode(MacroMode.COCOA);
        settings.spawn(new RewarpPosition(10, 1, 10));
        assertTrue(settings.addRewarp(new RewarpPosition(0, 1, 0)));
        return settings;
    }

    private static Map<BlockPosition, Observation<BlockStateSnapshot>> walkability(
            boolean front,
            boolean back,
            boolean right,
            boolean left
    ) {
        Map<BlockPosition, Observation<BlockStateSnapshot>> overrides = new HashMap<>();
        if (!front) {
            overrides.put(new BlockPosition(0, 1, 1), Observation.present(full()));
        }
        if (!back) {
            overrides.put(new BlockPosition(0, 1, -1), Observation.present(full()));
        }
        if (!right) {
            overrides.put(new BlockPosition(-1, 1, 0), Observation.present(full()));
        }
        if (!left) {
            overrides.put(new BlockPosition(1, 1, 0), Observation.present(full()));
        }
        return overrides;
    }

    private static Map<BlockPosition, Observation<BlockStateSnapshot>> walkability(
            PositionSnapshot position,
            float savedCardinal,
            boolean front,
            boolean back,
            boolean right,
            boolean left
    ) {
        RelativeFrame frame = RelativeFrame.cardinal(savedCardinal);
        Map<BlockPosition, Observation<BlockStateSnapshot>> overrides = new HashMap<>();
        if (!front) {
            overrides.put(frame.blockAt(
                    position.x(), position.y(), position.z(), 0, 0, 1),
                    Observation.present(full()));
        }
        if (!back) {
            overrides.put(frame.blockAt(
                    position.x(), position.y(), position.z(), 0, 0, -1),
                    Observation.present(full()));
        }
        if (!right) {
            overrides.put(frame.blockAt(
                    position.x(), position.y(), position.z(), 1, 0, 0),
                    Observation.present(full()));
        }
        if (!left) {
            overrides.put(frame.blockAt(
                    position.x(), position.y(), position.z(), -1, 0, 0),
                    Observation.present(full()));
        }
        return overrides;
    }

    private static MacroDecision step(
            SShapeCocoaBeanMacro macro,
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
            SShapeCocoaBeanMacro macro,
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
                now, player, Observation.present(captured(request, position, overrides)),
                posture, lease, EPOCH));
    }

    private static FarmingContext context(
            long now,
            PlayerSnapshot player,
            Observation<SpatialSnapshot> spatial,
            PlayerPosture posture,
            MacroRotationLeaseState lease,
            long epoch
    ) {
        return new FarmingContext(
                now, epoch, Observation.present(player), spatial,
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
                new BoxSnapshot(player.x() - 0.3D, player.y(), player.z() - 0.3D,
                        player.x() + 0.3D, player.y() + 1.8D, player.z() + 0.3D),
                chunks);
    }

    private static BlockStateSnapshot cocoa(int age) {
        return new BlockStateSnapshot(
                ResourceIdentifier.parse("minecraft:cocoa"), Map.of("age", Integer.toString(age)),
                ResourceIdentifier.parse("minecraft:empty"),
                Observation.present(CollisionShapeSnapshot.EMPTY));
    }

    private static BlockStateSnapshot trapdoorFull() {
        return new BlockStateSnapshot(
                ResourceIdentifier.parse("minecraft:oak_trapdoor"),
                Map.of("open", "false", "facing", "north"),
                ResourceIdentifier.parse("minecraft:empty"),
                Observation.present(new CollisionShapeSnapshot(List.of(
                        new BoxSnapshot(0, 0, 0, 1, 1, 1)))));
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
        return new QueueRandom(new double[count]);
    }

    private record Correction(
            SShapeCocoaBeanMacro macro,
            MacroDecision decision,
            PositionSnapshot spawn,
            long nowNanos
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
