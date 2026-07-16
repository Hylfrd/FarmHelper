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

class SShapeMushroomMacroTest {
    private static final long EPOCH = 14L;
    private static final PositionSnapshot START = new PositionSnapshot(0.5D, 1.0D, 0.5D);
    private static final MotionSnapshot STILL = new MotionSnapshot(0.0D, 0.0D, 0.0D);

    @Test
    void startupUsesRepairedDiagonalDefaultPitchBackProfileAndSeparateEntropy() {
        QueueRandom leaf = new QueueRandom(0.0D, 0.0D);
        QueueRandom entropy = new QueueRandom(0.0D, 0.0D);
        SShapeMushroomMacro macro = new SShapeMushroomMacro(validSettings(), leaf, entropy);
        macro.onStart();

        MacroDecision decision = step(macro, 0L, START, 0.0F, 0.0F,
                STILL, grounded(), Map.of());

        assertEquals(SShapeMushroomMacro.State.NONE, macro.state());
        assertEquals(45.0F, macro.storedYaw());
        assertEquals(0.0F, macro.cardinalYaw());
        assertEquals(RotationProfile.BACK, decision.rotation().orElseThrow().profile());
        assertEquals(45.0F, decision.rotation().orElseThrow().yaw());
        assertEquals(-1.0F, decision.rotation().orElseThrow().pitch());
        assertEquals(450L, decision.rotation().orElseThrow().durationMillis());
        assertEquals(2, leaf.draws(), "default pitch then duration");
        assertEquals(2, entropy.draws(), "Back modifier and minimum floor are independent");
        assertTrue(decision.inputs().isEmpty());
    }

    @Test
    void customYawPitchAndDontFixSuppressStartupWithoutRotationEntropy() {
        MacroSettings settings = validSettings();
        settings.customYaw(true);
        settings.customYawLevel(90.0F);
        settings.customPitch(true);
        settings.customPitchLevel(12.0F);
        settings.dontFixAfterWarping(true);
        QueueRandom leaf = new QueueRandom();
        QueueRandom entropy = new QueueRandom();
        SShapeMushroomMacro macro = new SShapeMushroomMacro(settings, leaf, entropy);
        macro.onStart();

        MacroDecision decision = step(macro, 0L, START, 90.0F, 12.0F,
                STILL, grounded(), Map.of());

        assertEquals("startup-fix-suppressed", decision.status());
        assertTrue(decision.rotation().isEmpty());
        assertEquals(90.0F, macro.storedYaw());
        assertEquals(90.0F, macro.cardinalYaw());
        assertEquals(0, leaf.draws());
        assertEquals(0, entropy.draws());
    }

    @Test
    void repairedAngleSeamsCoverZeroCardinalTieNegativeAndWrap() {
        assertStartupAngles(0.0F, 45.0F, 0.0F);
        assertStartupAngles(315.0F, -45.0F, 0.0F);
        assertStartupAngles(-1.0F, -45.0F, 0.0F);
        assertStartupAngles(360.0F, 45.0F, 0.0F);
        assertStartupAngles(90.0F, 135.0F, 90.0F);
        assertStartupAngles(180.0F, -135.0F, -180.0F);
    }

    @Test
    void allThreeHeightsAndBothMushroomKindsAreTargets() {
        for (int up = 1; up <= 3; up++) {
            for (String kind : List.of("red_mushroom", "brown_mushroom")) {
                SShapeMushroomMacro macro = macro(0.0F);
                MacroDecision selected = finishStartupWithTargets(
                        macro, 0.0F, Map.of(target(0.0F, true, up), crop(kind)));

                assertEquals(SShapeMushroomMacro.State.RIGHT, macro.state(), kind + " y+" + up);
                assertEquals(Set.of(InputAction.FORWARD, InputAction.ATTACK), selected.inputs());
            }
        }
    }

    @Test
    void incompatibleNearestCannotHideCompatibleMushroomAndUnknownFailsClosed() {
        SShapeMushroomMacro repaired = macro(0.0F);
        MacroDecision selected = finishStartupWithTargets(repaired, 0.0F, Map.of(
                target(0.0F, true, 1), crop("melon"),
                target(0.0F, true, 2), crop("red_mushroom")));
        assertEquals(SShapeMushroomMacro.State.RIGHT, repaired.state());
        assertFalse(selected.inputs().isEmpty());

        SShapeMushroomMacro unknown = macro(0.0F);
        MacroDecision failed = finishStartupWithObservations(unknown, 0.0F, Map.of(
                target(0.0F, true, 1), Observation.unknown(),
                target(0.0F, false, 1), Observation.present(crop("wheat"))));
        assertEquals("right-mushroom-ready-unknown", failed.status());
        assertEquals(SShapeMushroomMacro.State.NONE, unknown.state());
        assertEquals(SShapeMushroomMacro.ScanPhase.CROP_TARGETS, unknown.scanPhase());
    }

    @Test
    void cropReadinessRequiresMatchingSideWalkabilityWithRightFirstUnknownSemantics() {
        SShapeMushroomMacro rightReady = macro(0.0F);
        MacroDecision rightSelected = finishStartupWithObservations(rightReady, 0.0F, Map.of(
                target(0.0F, true, 1), Observation.present(crop("red_mushroom")),
                sideBodyBlock(0.0F, false, 1), Observation.unknown()));
        assertEquals(SShapeMushroomMacro.State.RIGHT, rightReady.state());
        assertEquals(Set.of(InputAction.FORWARD, InputAction.ATTACK), rightSelected.inputs());

        SShapeMushroomMacro blockedRight = macro(0.0F);
        MacroDecision leftSelected = finishStartupWithObservations(blockedRight, 0.0F, Map.of(
                target(0.0F, true, 1), Observation.present(crop("red_mushroom")),
                target(0.0F, false, 1), Observation.present(crop("brown_mushroom")),
                sideBodyBlock(0.0F, true, 1), Observation.present(full())));
        assertEquals(SShapeMushroomMacro.State.LEFT, blockedRight.state());
        assertEquals(Set.of(InputAction.LEFT, InputAction.ATTACK), leftSelected.inputs());

        SShapeMushroomMacro unknownRight = macro(0.0F);
        MacroDecision rightUnknown = finishStartupWithObservations(unknownRight, 0.0F, Map.of(
                target(0.0F, true, 1), Observation.present(crop("red_mushroom")),
                target(0.0F, false, 1), Observation.present(crop("brown_mushroom")),
                sideBodyBlock(0.0F, true, 1), Observation.unknown()));
        assertEquals("right-mushroom-ready-unknown", rightUnknown.status());
        assertEquals(SShapeMushroomMacro.State.NONE, unknownRight.state());
        assertTrue(rightUnknown.inputs().isEmpty());

        SShapeMushroomMacro absentButUnknownRight = macro(0.0F);
        MacroDecision absentUnknown = finishStartupWithObservations(
                absentButUnknownRight, 0.0F, Map.of(
                        target(0.0F, false, 1), Observation.present(crop("brown_mushroom")),
                        sideBodyBlock(0.0F, true, 1), Observation.unknown()));
        assertEquals("right-mushroom-ready-unknown", absentUnknown.status());
        assertEquals(SShapeMushroomMacro.State.NONE, absentButUnknownRight.state());

        SShapeMushroomMacro blockedBoth = macro(0.0F);
        MacroDecision neitherSelected = finishStartupWithObservations(blockedBoth, 0.0F, Map.of(
                target(0.0F, true, 1), Observation.present(crop("red_mushroom")),
                target(0.0F, false, 1), Observation.present(crop("brown_mushroom")),
                sideBodyBlock(0.0F, true, 1), Observation.present(full()),
                sideBodyBlock(0.0F, false, 1), Observation.present(full())));
        assertEquals(SShapeMushroomMacro.State.NONE, blockedBoth.state());
        assertEquals(SShapeMushroomMacro.ScanPhase.RIGHT_OBSTACLE, blockedBoth.scanPhase());
        assertEquals("mushroom-target-absent", neitherSelected.status());

        SShapeMushroomMacro unknownLeft = macro(0.0F);
        MacroDecision leftUnknown = finishStartupWithObservations(unknownLeft, 0.0F, Map.of(
                target(0.0F, false, 1), Observation.present(crop("brown_mushroom")),
                sideBodyBlock(0.0F, false, 1), Observation.unknown()));
        assertEquals("left-mushroom-ready-unknown", leftUnknown.status());
        assertEquals(SShapeMushroomMacro.State.NONE, unknownLeft.state());
    }

    @Test
    void customYawUsesConfiguredCardinalAndPinnedUpstreamLookThresholds() {
        List<CustomLookCase> cases = List.of(
                new CustomLookCase(0.0F, 0.0F, SShapeMushroomMacro.LookSide.RIGHT),
                new CustomLookCase(-40.0F, 0.0F, SShapeMushroomMacro.LookSide.RIGHT),
                new CustomLookCase(-40.001F, 0.0F, SShapeMushroomMacro.LookSide.LEFT),
                new CustomLookCase(44.999F, 0.0F, SShapeMushroomMacro.LookSide.RIGHT),
                new CustomLookCase(45.0F, 90.0F, SShapeMushroomMacro.LookSide.LEFT),
                new CustomLookCase(90.0F, 90.0F, SShapeMushroomMacro.LookSide.LEFT),
                new CustomLookCase(130.0F, 90.0F, SShapeMushroomMacro.LookSide.LEFT),
                new CustomLookCase(Math.nextUp(130.0F), 90.0F, SShapeMushroomMacro.LookSide.RIGHT),
                new CustomLookCase(134.9F, 90.0F, SShapeMushroomMacro.LookSide.RIGHT),
                new CustomLookCase(135.0F, -180.0F, SShapeMushroomMacro.LookSide.LEFT),
                new CustomLookCase(179.0F, -180.0F, SShapeMushroomMacro.LookSide.LEFT),
                new CustomLookCase(180.0F, -180.0F, SShapeMushroomMacro.LookSide.RIGHT),
                new CustomLookCase(-180.0F, -180.0F, SShapeMushroomMacro.LookSide.RIGHT),
                new CustomLookCase(Math.nextDown(-135.0F), -180.0F,
                        SShapeMushroomMacro.LookSide.RIGHT),
                new CustomLookCase(-135.0F, -90.0F, SShapeMushroomMacro.LookSide.LEFT),
                new CustomLookCase(-50.0F, -90.0F, SShapeMushroomMacro.LookSide.LEFT),
                new CustomLookCase(-49.999F, -90.0F,
                        SShapeMushroomMacro.LookSide.RIGHT),
                new CustomLookCase(-45.0F, 0.0F, SShapeMushroomMacro.LookSide.LEFT));

        for (CustomLookCase testCase : cases) {
            SShapeMushroomMacro macro = customYawMacro(testCase.yaw());
            step(macro, 0L, START, 17.0F, 0.0F, STILL, grounded(), Map.of());
            assertEquals(testCase.cardinal(), macro.cardinalYaw(), "cardinal " + testCase.yaw());
            assertEquals(testCase.side(), macro.lookSide().orElseThrow(), "look " + testCase.yaw());
        }
    }

    @Test
    void customYawTargetCaptureAndInputsUseConfiguredBasisNotObservedYaw() {
        SShapeMushroomMacro custom45 = customYawMacro(45.0F);
        MacroDecision right = finishStartupWithTargets(custom45, 0.0F,
                Map.of(target(90.0F, true, 1), crop("red_mushroom")));
        assertEquals(90.0F, custom45.cardinalYaw());
        assertEquals(SShapeMushroomMacro.State.RIGHT, custom45.state());
        assertEquals(Set.of(InputAction.RIGHT, InputAction.ATTACK), right.inputs());

        SShapeMushroomMacro custom90 = customYawMacro(90.0F);
        MacroDecision left = finishStartupWithTargets(custom90, 0.0F,
                Map.of(target(90.0F, false, 1), crop("brown_mushroom")));
        assertEquals(SShapeMushroomMacro.State.LEFT, custom90.state());
        assertEquals(Set.of(InputAction.FORWARD, InputAction.ATTACK), left.inputs());

        SShapeMushroomMacro custom0 = customYawMacro(0.0F);
        MacroDecision observedDifferent = finishStartupWithTargets(custom0, 90.0F,
                Map.of(target(0.0F, true, 1), crop("red_mushroom")));
        assertEquals(0.0F, custom0.cardinalYaw());
        assertEquals(Set.of(InputAction.FORWARD, InputAction.ATTACK), observedDifferent.inputs());
    }

    @Test
    void scanChecksRightFirstAtSameDistanceAndMapsBlockedSidesToOppositeLane() {
        SShapeMushroomMacro rightBlocked = scanningMacro();
        MacroDecision choseLeft = step(rightBlocked, 2L, START, 45.0F, -1.0F,
                STILL, grounded(), Map.of(sideBodyBlock(0.0F, true, 1), full()));
        assertEquals(SShapeMushroomMacro.State.LEFT, rightBlocked.state());
        assertEquals(Set.of(InputAction.LEFT, InputAction.ATTACK), choseLeft.inputs());

        SShapeMushroomMacro leftBlocked = scanningMacro();
        step(leftBlocked, 2L, START, 45.0F, -1.0F, STILL, grounded(), Map.of());
        MacroDecision choseRight = step(leftBlocked, 3L, START, 45.0F, -1.0F,
                STILL, grounded(), Map.of(sideBodyBlock(0.0F, false, 1), full()));
        assertEquals(SShapeMushroomMacro.State.RIGHT, leftBlocked.state());
        assertEquals(Set.of(InputAction.FORWARD, InputAction.ATTACK), choseRight.inputs());
    }

    @Test
    void scanCoversOneThrough179AndNeverAdvancesTo180() {
        SShapeMushroomMacro macro = scanningMacro();
        long now = 2L;
        for (int distance = 1; distance < 179; distance++) {
            assertEquals(distance, macro.scanDistance());
            step(macro, now++, START, 45.0F, -1.0F, STILL, grounded(), Map.of());
            step(macro, now++, START, 45.0F, -1.0F, STILL, grounded(), Map.of());
        }
        assertEquals(179, macro.scanDistance());
        assertEquals(SShapeMushroomMacro.ScanPhase.RIGHT_OBSTACLE, macro.scanPhase());
        step(macro, now++, START, 45.0F, -1.0F, STILL, grounded(), Map.of());
        MacroDecision exhausted = step(macro, now, START, 45.0F, -1.0F,
                STILL, grounded(), Map.of());

        assertEquals("direction-scan-budget-exhausted", exhausted.status());
        assertEquals(179, macro.scanDistance());
        assertEquals(SShapeMushroomMacro.ScanPhase.LEFT_OBSTACLE, macro.scanPhase());
        SpatialCaptureRequest request = macro.spatialRequest(
                player(START, 45.0F, -1.0F, STILL), EPOCH).orElseThrow();
        assertTrue(request.blocks().contains(sideBodyBlock(0.0F, false, 179)));
        assertFalse(request.blocks().contains(sideBodyBlock(0.0F, false, 180)));
    }

    @Test
    void scanUnknownDoesNotAdvanceOrConsumeLeafRandomness() {
        QueueRandom leaf = new QueueRandom(0.0D, 0.0D);
        SShapeMushroomMacro macro = new SShapeMushroomMacro(validSettings(), leaf,
                new QueueRandom(0.0D, 0.0D));
        macro.onStart();
        MacroDecision startup = step(macro, 0L, START, 0.0F, 0.0F,
                STILL, grounded(), Map.of());
        step(macro, 1L, START, startup.rotation().orElseThrow().yaw(),
                startup.rotation().orElseThrow().pitch(), STILL, grounded(), Map.of());
        int draws = leaf.draws();

        MacroDecision unknown = stepObservations(macro, 2L, START, 45.0F, -1.0F,
                STILL, grounded(), Map.of(sideBodyBlock(0.0F, true, 1), Observation.unknown()));

        assertEquals("direction-scan-unknown", unknown.status());
        assertEquals(1, macro.scanDistance());
        assertEquals(SShapeMushroomMacro.ScanPhase.RIGHT_OBSTACLE, macro.scanPhase());
        assertEquals(draws, leaf.draws());
    }

    @Test
    void leftAndRightTruthTablesMatchUpstreamWalkabilityRules() {
        assertTransition(SShapeMushroomMacro.State.LEFT, false, false,
                SShapeMushroomMacro.State.RIGHT);
        assertTransition(SShapeMushroomMacro.State.LEFT, true, true,
                SShapeMushroomMacro.State.LEFT);
        assertTransition(SShapeMushroomMacro.State.LEFT, false, true,
                SShapeMushroomMacro.State.NONE);
        assertTransition(SShapeMushroomMacro.State.RIGHT, false, false,
                SShapeMushroomMacro.State.LEFT);
        assertTransition(SShapeMushroomMacro.State.RIGHT, true, true,
                SShapeMushroomMacro.State.RIGHT);
        assertTransition(SShapeMushroomMacro.State.RIGHT, true, false,
                SShapeMushroomMacro.State.NONE);
    }

    @Test
    void lanePriorityDoesNotRequireTheUnconsultedOppositeSide() {
        SShapeMushroomMacro left = laneMacro(SShapeMushroomMacro.State.LEFT, 0.0F, START);
        MacroDecision switchedRight = stepObservations(
                left, 2L, START, 45.0F, -1.0F, STILL, grounded(),
                Map.of(sideBodyBlock(0.0F, false, 1), Observation.unknown()));
        assertEquals(SShapeMushroomMacro.State.RIGHT, left.state());
        assertEquals(Set.of(InputAction.FORWARD, InputAction.ATTACK), switchedRight.inputs());

        SShapeMushroomMacro right = laneMacro(SShapeMushroomMacro.State.RIGHT, 0.0F, START);
        MacroDecision switchedLeft = stepObservations(
                right, 2L, START, 45.0F, -1.0F, STILL, grounded(),
                Map.of(sideBodyBlock(0.0F, true, 1), Observation.unknown()));
        assertEquals(SShapeMushroomMacro.State.LEFT, right.state());
        assertEquals(Set.of(InputAction.LEFT, InputAction.ATTACK), switchedLeft.inputs());
    }

    @Test
    void fourCardinalsAndBothDiagonalSidesMapInputsExactly() {
        for (float cardinal : new float[]{0.0F, 90.0F, 180.0F, -90.0F}) {
            assertLaneInputs(cardinal + 44.0F, true,
                    Set.of(InputAction.FORWARD, InputAction.ATTACK));
            assertLaneInputs(cardinal + 44.0F, false,
                    Set.of(InputAction.LEFT, InputAction.ATTACK));
            assertLaneInputs(cardinal - 1.0F, true,
                    Set.of(InputAction.RIGHT, InputAction.ATTACK));
            assertLaneInputs(cardinal - 1.0F, false,
                    Set.of(InputAction.FORWARD, InputAction.ATTACK));
        }
    }

    @Test
    void alwaysHoldWOverridesBothLaneAndLookSideMappings() {
        MacroSettings settings = validSettings();
        settings.alwaysHoldW(true);
        SShapeMushroomMacro right = new SShapeMushroomMacro(settings, zeros(10), zeros(10));
        right.onStart();
        MacroDecision rightDecision = finishStartupWithTargets(
                right, -1.0F, Map.of(target(0.0F, true, 1), crop("red_mushroom")));
        assertEquals(Set.of(InputAction.FORWARD, InputAction.ATTACK), rightDecision.inputs());

        SShapeMushroomMacro left = new SShapeMushroomMacro(settings, zeros(10), zeros(10));
        left.onStart();
        MacroDecision leftDecision = finishStartupWithTargets(
                left, 44.0F, Map.of(target(0.0F, false, 1), crop("brown_mushroom")));
        assertEquals(Set.of(InputAction.FORWARD, InputAction.ATTACK), leftDecision.inputs());
    }

    @Test
    void dropUsesStrictTriggerBoundariesAndReleasesWhileAirborne() {
        SShapeMushroomMacro macro = laneMacro(SShapeMushroomMacro.State.RIGHT, 0.0F, START);

        MacroDecision exact = step(macro, 2L, new PositionSnapshot(0.5D, 0.25D, 0.5D),
                45.0F, -1.0F, STILL, airborne(), blockedSides(0.0F));
        assertFalse(exact.status().equals("dropping"), "exact 0.75 is not a drop");
        MacroDecision flying = step(macro, 3L, new PositionSnapshot(0.5D, 0.0D, 0.5D),
                45.0F, -1.0F, STILL, flying(), blockedSides(0.0F));
        assertFalse(flying.status().equals("dropping"), "flying posture is excluded");
        MacroDecision dropping = step(macro, 4L, new PositionSnapshot(0.5D, 0.0D, 0.5D),
                45.0F, -1.0F, STILL, airborne(), blockedSides(0.0F));

        assertEquals("dropping", dropping.status());
        assertEquals(SShapeMushroomMacro.State.DROPPING, macro.state());
        assertTrue(dropping.inputs().isEmpty());
        assertTrue(dropping.rotation().isEmpty());
    }

    @Test
    void dropRequiresYBelow80AndDeepLandingUsesOrdinary400To700Rotation() {
        PositionSnapshot high = new PositionSnapshot(0.5D, 81.0D, 0.5D);
        SShapeMushroomMacro boundary = laneMacro(SShapeMushroomMacro.State.RIGHT, 0.0F, high);
        MacroDecision y80 = step(boundary, 2L, new PositionSnapshot(0.5D, 80.0D, 0.5D),
                45.0F, -1.0F, STILL, airborne(), blockedSidesAt(0.0F, 80));
        assertFalse(y80.status().equals("dropping"));
        assertEquals("dropping", step(boundary, 3L,
                new PositionSnapshot(0.5D, 79.99D, 0.5D), 45.0F, -1.0F,
                STILL, airborne(), blockedSidesAt(0.0F, 79)).status());

        MacroSettings settings = validSettings();
        settings.rotateAfterDrop(true);
        QueueRandom leaf = zeros(20);
        QueueRandom entropy = zeros(20);
        SShapeMushroomMacro deep = new SShapeMushroomMacro(settings, leaf, entropy);
        deep.onStart();
        chooseLane(deep, SShapeMushroomMacro.State.RIGHT, 0.0F, START);
        step(deep, 2L, new PositionSnapshot(0.5D, 0.0D, 0.5D),
                45.0F, -1.0F, STILL, airborne(), blockedSides(0.0F));
        MacroDecision landed = step(deep, 3L, new PositionSnapshot(0.5D, -0.6D, 0.5D),
                45.0F, -1.0F, STILL, grounded(), Map.of());

        assertEquals("drop-rotate", landed.status());
        assertEquals(-90.0F, landed.rotation().orElseThrow().yaw());
        assertEquals(RotationProfile.EXPO_QUART, landed.rotation().orElseThrow().profile());
        assertTrue(landed.rotation().orElseThrow().durationMillis() >= 400L);
        assertTrue(landed.rotation().orElseThrow().durationMillis() < 770L);
        assertEquals(SShapeMushroomMacro.State.NONE, deep.state());

        MacroSettings shallowSettings = validSettings();
        shallowSettings.rotateAfterDrop(true);
        SShapeMushroomMacro shallow = new SShapeMushroomMacro(
                shallowSettings, zeros(20), zeros(20));
        shallow.onStart();
        chooseLane(shallow, SShapeMushroomMacro.State.RIGHT, 0.0F, START);
        step(shallow, 2L, new PositionSnapshot(0.5D, 0.2D, 0.5D),
                45.0F, -1.0F, STILL, airborne(), blockedSides(0.0F));
        MacroDecision shallowLanding = step(shallow, 3L,
                new PositionSnapshot(0.5D, 0.2D, 0.5D),
                45.0F, -1.0F, STILL, grounded(), Map.of());
        assertEquals("drop-too-shallow", shallowLanding.status());
        assertTrue(shallowLanding.rotation().isEmpty());
    }

    @Test
    void rewarpRotatesToOppositeDiagonalWithOneBackRequestAndNoDuplicateDraw() {
        MacroSettings settings = rewarpSettings();
        settings.rotateAfterWarped(true);
        QueueRandom leaf = zeros(20);
        QueueRandom entropy = zeros(20);
        SShapeMushroomMacro macro = new SShapeMushroomMacro(settings, leaf, entropy);
        macro.onStart();
        chooseLane(macro, SShapeMushroomMacro.State.RIGHT, 0.0F, START);

        MacroDecision correction = completeRewarp(macro, 134.0F, -1.0F);

        assertEquals("post-rewarp-mushroom-back", correction.status());
        assertEquals(1, correction.rotation().stream().count());
        assertEquals(-135.0F, correction.rotation().orElseThrow().yaw());
        assertEquals(-1.0F, correction.rotation().orElseThrow().pitch());
        assertEquals(RotationProfile.BACK, correction.rotation().orElseThrow().profile());
        assertEquals(1_000L, correction.rotation().orElseThrow().durationMillis());
        assertEquals(-90.0F, macro.cardinalYaw());
        assertEquals(5, leaf.draws(), "startup pitch/duration, dwell, rewarp pitch/duration");
        assertEquals(4, entropy.draws(), "startup and terminal Back requests only");
    }

    @Test
    void rewarpSavedCorrectionAndDontFixUseSharedSemantics() {
        MacroSettings savedSettings = rewarpSettings();
        SShapeMushroomMacro saved = new SShapeMushroomMacro(
                savedSettings, zeros(20), zeros(20));
        saved.onStart();
        chooseLane(saved, SShapeMushroomMacro.State.RIGHT, 0.0F, START);
        MacroDecision correction = completeRewarp(saved, 45.0F, -1.0F);
        assertEquals("post-rewarp-saved-back", correction.status());
        assertEquals(45.0F, correction.rotation().orElseThrow().yaw());

        MacroSettings suppressedSettings = rewarpSettings();
        suppressedSettings.rotateAfterWarped(true);
        suppressedSettings.dontFixAfterWarping(true);
        SShapeMushroomMacro suppressed = new SShapeMushroomMacro(
                suppressedSettings, zeros(20), zeros(20));
        suppressed.onStart();
        chooseLane(suppressed, SShapeMushroomMacro.State.RIGHT, 0.0F, START);
        MacroDecision noFix = completeRewarp(suppressed, -135.0F, -1.0F);
        assertEquals("post-rewarp-fix-suppressed", noFix.status());
        assertTrue(noFix.rotation().isEmpty());
    }

    @Test
    void rewarpHonorsCustomPitchWithoutSamplingDefaultPitch() {
        MacroSettings settings = rewarpSettings();
        settings.rotateAfterWarped(true);
        settings.customPitch(true);
        settings.customPitchLevel(12.0F);
        settings.customYaw(true);
        settings.customYawLevel(45.0F);
        QueueRandom leaf = zeros(20);
        SShapeMushroomMacro macro = new SShapeMushroomMacro(settings, leaf, zeros(20));
        macro.onStart();
        chooseLane(macro, SShapeMushroomMacro.State.RIGHT, 0.0F, START);

        MacroDecision correction = completeRewarp(macro, 134.0F, 12.0F);

        assertEquals(-135.0F, correction.rotation().orElseThrow().yaw());
        assertEquals(12.0F, correction.rotation().orElseThrow().pitch());
        assertEquals(3, leaf.draws(), "startup duration, dwell, terminal duration only");
    }

    @Test
    void staleCapturePauseAndOwnerCancellationNeverRedraw() {
        QueueRandom leaf = zeros(10);
        QueueRandom entropy = zeros(10);
        SShapeMushroomMacro macro = new SShapeMushroomMacro(validSettings(), leaf, entropy);
        macro.onStart();
        MacroDecision initial = step(macro, 0L, START, 0.0F, 0.0F,
                STILL, grounded(), Map.of());
        var pending = initial.rotation().orElseThrow();
        int leafDraws = leaf.draws();
        int entropyDraws = entropy.draws();
        SpatialCaptureRequest staleRequest = macro.spatialRequest(
                player(START, 0.0F, 0.0F, STILL), EPOCH).orElseThrow();
        SpatialSnapshot stale = captured(staleRequest, START, Map.of());

        macro.onPause(Set.of(MacroPauseCause.SCREEN_OPEN), 1L);
        macro.onResume(2L);
        MacroDecision rejected = macro.tick(context(3L,
                player(START, 0.0F, 0.0F, STILL), stale, grounded(),
                MacroRotationLeaseState.idle(0L)));
        assertEquals("spatial-unknown-or-stale", rejected.status());

        MacroDecision retried = step(macro, 4L, START, 0.0F, 0.0F,
                STILL, grounded(), Map.of(), MacroRotationLeaseState.terminal(
                        pending.requestToken(), RotationTerminalReason.OWNER_CANCELLED, 4L));
        assertEquals(pending, retried.rotation().orElseThrow());
        assertEquals(leafDraws, leaf.draws());
        assertEquals(entropyDraws, entropy.draws());
    }

    @Test
    void rowStallHandsOffRecoveryAndStopWorldMismatchReleaseEverything() {
        SShapeMushroomMacro stalled = laneMacro(
                SShapeMushroomMacro.State.RIGHT, 0.0F, START);
        Map<BlockPosition, BlockStateSnapshot> blocked = blockedSides(0.0F);
        long window = TimeUnit.MILLISECONDS.toNanos(500L);
        step(stalled, window + 1L, START, 45.0F, -1.0F,
                STILL, grounded(), blocked);
        step(stalled, window * 2L + 1L, START, 45.0F, -1.0F,
                STILL, grounded(), blocked);
        MacroDecision recovery = step(stalled, window * 3L + 1L,
                START, 45.0F, -1.0F, STILL, grounded(), blocked);
        assertEquals(MacroRecoveryReason.ROW_STALLED,
                recovery.recovery().orElseThrow().reason());
        assertTrue(recovery.inputs().isEmpty());
        assertTrue(recovery.rotation().isEmpty());
        assertEquals(SShapeMushroomMacro.State.RECOVERY_HANDOFF, stalled.state());

        SShapeMushroomMacro world = macro(0.0F);
        MacroDecision startup = step(world, 0L, START, 0.0F, 0.0F,
                STILL, grounded(), Map.of());
        PlayerSnapshot player = player(START, startup.rotation().orElseThrow().yaw(),
                startup.rotation().orElseThrow().pitch(), STILL);
        SpatialCaptureRequest request = world.spatialRequest(player, EPOCH).orElseThrow();
        SpatialSnapshot wrongWorld = new SpatialSnapshot(
                EPOCH + 1L, request.requestToken(), request.bounds(), -64, 320,
                body(START), Map.of());
        MacroDecision failClosed = world.tick(new FarmingContext(
                1L, EPOCH + 1L, Observation.present(player), Observation.present(wrongWorld),
                Observation.present(true), false, ServerResponsiveness.RESPONSIVE,
                Observation.present(grounded()), Observation.present(MacroRotationLeaseState.idle(0L))));
        assertEquals("spatial-unknown-or-stale", failClosed.status());
        assertTrue(failClosed.inputs().isEmpty());

        world.onStop();
        assertEquals(SShapeMushroomMacro.State.STOPPED, world.state());
        assertTrue(world.spatialRequest(player, EPOCH).isEmpty());
        assertEquals("stopped", world.tick(new FarmingContext(
                2L, EPOCH, Observation.unknown(), Observation.unknown(), Observation.unknown(),
                false, ServerResponsiveness.UNKNOWN)).status());
    }

    private static void assertStartupAngles(float yaw, float expectedDiagonal, float expectedCardinal) {
        SShapeMushroomMacro macro = macro(yaw);
        MacroDecision startup = step(macro, 0L, START, yaw, 0.0F,
                STILL, grounded(), Map.of());
        assertEquals(expectedDiagonal, macro.storedYaw(), "diagonal for " + yaw);
        assertEquals(expectedCardinal, macro.cardinalYaw(), "cardinal for " + yaw);
        assertEquals(expectedDiagonal, startup.rotation().orElseThrow().yaw());
    }

    private static void assertLaneInputs(
            float observedYaw,
            boolean right,
            Set<InputAction> expected
    ) {
        float cardinal = dev.hylfrd.farmhelper.macro.MacroAngles.closestCardinal(observedYaw);
        SShapeMushroomMacro macro = macro(observedYaw);
        MacroDecision decision = finishStartupWithTargets(macro, observedYaw,
                Map.of(target(cardinal, right, 1), crop("red_mushroom")));
        assertEquals(expected, decision.inputs(), "yaw=" + observedYaw + " right=" + right);
    }

    private static void assertTransition(
            SShapeMushroomMacro.State initial,
            boolean leftBlocked,
            boolean rightBlocked,
            SShapeMushroomMacro.State expected
    ) {
        SShapeMushroomMacro macro = laneMacro(initial, 0.0F, START);
        Map<BlockPosition, BlockStateSnapshot> overrides = new HashMap<>();
        if (leftBlocked) {
            overrides.put(sideBodyBlock(0.0F, false, 1), full());
        }
        if (rightBlocked) {
            overrides.put(sideBodyBlock(0.0F, true, 1), full());
        }
        MacroDecision decision = step(macro, 2L, START, 45.0F, -1.0F,
                STILL, grounded(), overrides);
        assertEquals(expected, macro.state(), initial + " " + leftBlocked + "/" + rightBlocked);
        if (expected == SShapeMushroomMacro.State.NONE) {
            assertEquals("mushroom-direction-recalculate", decision.status());
            assertTrue(decision.inputs().isEmpty());
        }
    }

    private static SShapeMushroomMacro scanningMacro() {
        SShapeMushroomMacro macro = macro(0.0F);
        MacroDecision startup = step(macro, 0L, START, 0.0F, 0.0F,
                STILL, grounded(), Map.of());
        step(macro, 1L, START, startup.rotation().orElseThrow().yaw(),
                startup.rotation().orElseThrow().pitch(), STILL, grounded(), Map.of());
        assertEquals(SShapeMushroomMacro.ScanPhase.RIGHT_OBSTACLE, macro.scanPhase());
        return macro;
    }

    private static SShapeMushroomMacro laneMacro(
            SShapeMushroomMacro.State lane,
            float yaw,
            PositionSnapshot position
    ) {
        SShapeMushroomMacro macro = macro(yaw);
        chooseLane(macro, lane, yaw, position);
        return macro;
    }

    private static MacroDecision chooseLane(
            SShapeMushroomMacro macro,
            SShapeMushroomMacro.State lane,
            float yaw,
            PositionSnapshot position
    ) {
        MacroDecision startup = step(macro, 0L, position, yaw, 0.0F,
                STILL, grounded(), Map.of());
        float cardinal = dev.hylfrd.farmhelper.macro.MacroAngles.closestCardinal(yaw);
        return step(macro, 1L, position, startup.rotation().orElseThrow().yaw(),
                startup.rotation().orElseThrow().pitch(), STILL, grounded(),
                Map.of(target(position, cardinal, lane == SShapeMushroomMacro.State.RIGHT, 1),
                        crop("red_mushroom")));
    }

    private static MacroDecision completeRewarp(
            SShapeMushroomMacro macro,
            float observedYaw,
            float observedPitch
    ) {
        PositionSnapshot origin = new PositionSnapshot(2.5D, 1.0D, 0.5D);
        long dwellAt = 2L;
        assertEquals("rewarp-dwell", step(macro, dwellAt, origin, 45.0F, -1.0F,
                STILL, grounded(), Map.of()).status());
        long warpAt = dwellAt + TimeUnit.MILLISECONDS.toNanos(400L);
        assertEquals("rewarp-dwell", step(macro, warpAt - 1L, origin, 45.0F, -1.0F,
                STILL, grounded(), Map.of()).status());
        assertTrue(step(macro, warpAt, origin, 45.0F, -1.0F,
                STILL, grounded(), Map.of()).warp().isPresent());
        PositionSnapshot spawn = new PositionSnapshot(10.5D, 1.0D, 0.5D);
        long landedAt = warpAt + 1L;
        step(macro, landedAt, spawn, observedYaw, observedPitch,
                STILL, grounded(), Map.of());
        long postAt = landedAt + TimeUnit.MILLISECONDS.toNanos(1_500L);
        assertEquals("after-warp", step(macro, postAt - 1L, spawn,
                observedYaw, observedPitch, STILL, grounded(), Map.of()).status());
        step(macro, postAt, spawn, observedYaw, observedPitch,
                STILL, grounded(), Map.of());
        long correctionAt = postAt + TimeUnit.MILLISECONDS.toNanos(600L);
        assertEquals("post-rewarp", step(macro, correctionAt - 1L, spawn,
                observedYaw, observedPitch, STILL, grounded(), Map.of()).status());
        return step(macro, correctionAt,
                spawn, observedYaw, observedPitch, STILL, grounded(), Map.of());
    }

    private static SShapeMushroomMacro macro(float yaw) {
        SShapeMushroomMacro macro = new SShapeMushroomMacro(
                validSettings(), zeros(20), zeros(20));
        macro.onStart();
        return macro;
    }

    private static SShapeMushroomMacro customYawMacro(float yaw) {
        MacroSettings settings = validSettings();
        settings.customYaw(true);
        settings.customYawLevel(yaw);
        settings.customPitch(true);
        settings.customPitchLevel(0.0F);
        SShapeMushroomMacro macro = new SShapeMushroomMacro(
                settings, zeros(20), zeros(20));
        macro.onStart();
        return macro;
    }

    private static MacroDecision finishStartupWithTargets(
            SShapeMushroomMacro macro,
            float observedYaw,
            Map<BlockPosition, BlockStateSnapshot> overrides
    ) {
        Map<BlockPosition, Observation<BlockStateSnapshot>> observations = new HashMap<>();
        overrides.forEach((position, state) -> observations.put(position, Observation.present(state)));
        return finishStartupWithObservations(macro, observedYaw, observations);
    }

    private static MacroDecision finishStartupWithObservations(
            SShapeMushroomMacro macro,
            float observedYaw,
            Map<BlockPosition, Observation<BlockStateSnapshot>> overrides
    ) {
        MacroDecision startup = step(macro, 0L, START, observedYaw, 0.0F,
                STILL, grounded(), Map.of());
        return stepObservations(macro, 1L, START,
                startup.rotation().orElseThrow().yaw(), startup.rotation().orElseThrow().pitch(),
                STILL, grounded(), overrides);
    }

    private static MacroSettings validSettings() {
        MacroSettings settings = new MacroSettings();
        settings.macroMode(MacroMode.MUSHROOM);
        settings.spawn(new RewarpPosition(100, 70, 100));
        assertTrue(settings.addRewarp(new RewarpPosition(90, 70, 90)));
        return settings;
    }

    private static MacroSettings rewarpSettings() {
        MacroSettings settings = new MacroSettings();
        settings.macroMode(MacroMode.MUSHROOM);
        settings.spawn(new RewarpPosition(10, 1, 0));
        assertTrue(settings.addRewarp(new RewarpPosition(2, 1, 0)));
        return settings;
    }

    private static MacroDecision step(
            SShapeMushroomMacro macro,
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
                .map(pending -> Math.abs(dev.hylfrd.farmhelper.macro.MacroAngles.shortestDelta(
                                yaw, pending.yaw())) <= 0.1F
                                && Math.abs(pitch - pending.pitch()) <= 0.1F
                        ? MacroRotationLeaseState.terminal(
                                pending.requestToken(), RotationTerminalReason.COMPLETED, 2L)
                        : MacroRotationLeaseState.active(pending.requestToken(), false, 1L))
                .orElseGet(() -> MacroRotationLeaseState.idle(0L));
        return stepObservations(macro, now, position, yaw, pitch, motion, posture,
                observations, lease);
    }

    private static MacroDecision step(
            SShapeMushroomMacro macro,
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
            SShapeMushroomMacro macro,
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
        return stepObservations(macro, now, position, yaw, pitch, motion, posture,
                overrides, lease);
    }

    private static MacroDecision stepObservations(
            SShapeMushroomMacro macro,
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

    private static BlockPosition sideBodyBlock(float cardinal, boolean right, int distance) {
        RelativeFrame frame = RelativeFrame.cardinal(cardinal);
        int sign = right ? 1 : -1;
        return new BlockPosition(
                (int) Math.floor(START.x() + frame.rightX() * (double) sign * distance),
                (int) Math.floor(START.y()),
                (int) Math.floor(START.z() + frame.rightZ() * (double) sign * distance));
    }

    private static Map<BlockPosition, BlockStateSnapshot> blockedSides(float cardinal) {
        return blockedSidesAt(cardinal, 1);
    }

    private static Map<BlockPosition, BlockStateSnapshot> blockedSidesAt(float cardinal, int y) {
        RelativeFrame frame = RelativeFrame.cardinal(cardinal);
        return Map.of(
                new BlockPosition(
                        (int) Math.floor(START.x() + frame.rightX()), y,
                        (int) Math.floor(START.z() + frame.rightZ())), full(),
                new BlockPosition(
                        (int) Math.floor(START.x() - frame.rightX()), y,
                        (int) Math.floor(START.z() - frame.rightZ())), full());
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

    private record CustomLookCase(
            float yaw,
            float cardinal,
            SShapeMushroomMacro.LookSide side
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
