package dev.hylfrd.farmhelper.macro.impl;

import dev.hylfrd.farmhelper.control.input.InputAction;
import dev.hylfrd.farmhelper.control.rotation.RotationProfile;
import dev.hylfrd.farmhelper.control.rotation.RotationTerminalReason;
import dev.hylfrd.farmhelper.macro.FarmingContext;
import dev.hylfrd.farmhelper.macro.MacroDecision;
import dev.hylfrd.farmhelper.macro.MacroMode;
import dev.hylfrd.farmhelper.macro.MacroPauseCause;
import dev.hylfrd.farmhelper.macro.MacroRotationLeaseState;
import dev.hylfrd.farmhelper.macro.MacroSettings;
import dev.hylfrd.farmhelper.macro.MacroTerminalReason;
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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CircularCropMacroTest {
    private static final long EPOCH = 41L;
    private static final PositionSnapshot START = new PositionSnapshot(0.5D, 1.0D, 0.5D);
    private static final MotionSnapshot STILL = new MotionSnapshot(0.0D, 0.0D, 0.0D);

    @Test
    void exactCycleAndInputsIgnoreAlwaysHoldW() {
        MacroSettings settings = quietSettings();
        settings.alwaysHoldW(true);
        QueueRandom leaf = zeros(8);
        CircularCropMacro macro = started(settings, leaf, zeros(8));
        long now = enterD(macro, START);

        assertDirection(macro, CircularCropMacro.State.D);
        now = completeCorner(macro, now, CircularCropMacro.State.D, START);
        assertDirection(macro, CircularCropMacro.State.S);
        assertEquals(Set.of(InputAction.BACKWARD, InputAction.ATTACK),
                step(macro, ++now, START, 45.0F, 3.0F,
                        STILL, grounded(), Map.of()).inputs());
        now = completeCorner(macro, now, CircularCropMacro.State.S, START);
        assertDirection(macro, CircularCropMacro.State.A);
        assertEquals(Set.of(InputAction.LEFT, InputAction.ATTACK),
                step(macro, ++now, START, 45.0F, 3.0F,
                        STILL, grounded(), Map.of()).inputs());
        now = completeCorner(macro, now, CircularCropMacro.State.A, START);
        assertDirection(macro, CircularCropMacro.State.W);
        assertEquals(Set.of(InputAction.FORWARD, InputAction.ATTACK),
                step(macro, ++now, START, 45.0F, 3.0F,
                        STILL, grounded(), Map.of()).inputs());
        now = completeCorner(macro, now, CircularCropMacro.State.W, START);
        assertDirection(macro, CircularCropMacro.State.D);
        assertEquals(Set.of(InputAction.RIGHT, InputAction.ATTACK),
                step(macro, now + 1L, START, 45.0F, 3.0F,
                        STILL, grounded(), Map.of()).inputs());
        assertEquals(4, leaf.draws(), "only the four row-dwell samples are leaf draws");
    }

    @Test
    void turnsOnlyWithKnownBlockedBodySupportAndStrictStoppedMotion() {
        QueueRandom leaf = zeros(4);
        CircularCropMacro macro = started(quietSettings(), leaf, zeros(4));
        long entered = enterD(macro, START);
        Map<BlockPosition, Observation<BlockStateSnapshot>> blocked = blocked(
                CircularCropMacro.State.D);

        MacroDecision xBoundary = step(macro, entered + 1L, START, 45.0F, 3.0F,
                new MotionSnapshot(0.01D, 0.0D, 0.0D), grounded(), blocked);
        MacroDecision zBoundary = step(macro, entered + 2L, START, 45.0F, 3.0F,
                new MotionSnapshot(0.0D, 0.0D, -0.01D), grounded(), blocked);
        MacroDecision yBoundary = step(macro, entered + 3L, START, 45.0F, 3.0F,
                new MotionSnapshot(0.0D, 0.01D, 0.0D), grounded(), blocked);

        assertEquals("circular-corner-moving", xBoundary.status());
        assertEquals("circular-corner-moving", zBoundary.status());
        assertEquals("circular-corner-moving", yBoundary.status());
        assertEquals(0, leaf.draws());

        long dwellAt = entered + 4L;
        MacroDecision dwell = step(macro, dwellAt, START, 45.0F, 3.0F,
                new MotionSnapshot(0.009D, -0.009D, 0.009D), grounded(), blocked);
        assertEquals("circular-corner-dwell", dwell.status());
        assertEquals(TimeUnit.MILLISECONDS.toNanos(400L), macro.cornerDwellNanos());
        assertEquals(CircularCropMacro.State.D, macro.state());

        MacroDecision early = step(macro,
                dwellAt + TimeUnit.MILLISECONDS.toNanos(400L) - 1L,
                START, 45.0F, 3.0F, STILL, grounded(), blocked);
        assertEquals(CircularCropMacro.State.D, macro.state());
        assertEquals("circular-corner-dwell", early.status());

        MacroDecision boundary = step(macro,
                dwellAt + TimeUnit.MILLISECONDS.toNanos(400L),
                START, 45.0F, 3.0F, STILL, grounded(), blocked);
        assertEquals(CircularCropMacro.State.S, macro.state());
        assertEquals(Set.of(InputAction.BACKWARD, InputAction.ATTACK), boundary.inputs());

        QueueRandom upperLeaf = new QueueRandom(0.999D);
        CircularCropMacro upper = started(quietSettings(), upperLeaf, zeros(2));
        long upperEntered = enterD(upper, START);
        long upperDwellAt = upperEntered + 1L;
        step(upper, upperDwellAt, START, 45.0F, 3.0F,
                STILL, grounded(), blocked(CircularCropMacro.State.D));
        assertEquals(TimeUnit.MILLISECONDS.toNanos(599L), upper.cornerDwellNanos());
        step(upper, upperDwellAt + TimeUnit.MILLISECONDS.toNanos(599L) - 1L,
                START, 45.0F, 3.0F, STILL, grounded(),
                blocked(CircularCropMacro.State.D));
        assertEquals(CircularCropMacro.State.D, upper.state());
        step(upper, upperDwellAt + TimeUnit.MILLISECONDS.toNanos(599L),
                START, 45.0F, 3.0F, STILL, grounded(),
                blocked(CircularCropMacro.State.D));
        assertEquals(CircularCropMacro.State.S, upper.state());
    }

    @Test
    void passableAndUnknownNeverCountAsCornerCompletion() {
        QueueRandom leaf = zeros(4);
        CircularCropMacro macro = started(quietSettings(), leaf, zeros(4));
        long entered = enterD(macro, START);

        MacroDecision passable = step(macro, entered + 1L, START, 45.0F, 3.0F,
                STILL, grounded(), Map.of());
        assertEquals(Set.of(InputAction.RIGHT, InputAction.ATTACK), passable.inputs());
        assertFalse(macro.cornerDwellActive());

        Map<BlockPosition, Observation<BlockStateSnapshot>> unknown = Map.of(
                directionBlock(CircularCropMacro.State.D), Observation.unknown());
        MacroDecision uncertain = step(macro, entered + 2L, START, 45.0F, 3.0F,
                STILL, grounded(), unknown);
        assertEquals("circular-direction-unknown", uncertain.status());
        assertTrue(uncertain.inputs().isEmpty());
        assertFalse(macro.cornerDwellActive());
        assertEquals(0, leaf.draws());

        step(macro, entered + 3L, START, 45.0F, 3.0F,
                STILL, grounded(), blocked(CircularCropMacro.State.D));
        assertTrue(macro.cornerDwellActive());
        MacroDecision interrupted = step(macro,
                entered + TimeUnit.SECONDS.toNanos(2L),
                START, 45.0F, 3.0F, STILL, grounded(), unknown);
        assertEquals("circular-direction-unknown", interrupted.status());
        assertEquals(CircularCropMacro.State.D, macro.state());
        assertFalse(macro.cornerDwellActive(), "unknown evidence cancels rather than ages dwell");
    }

    @Test
    void everyDirectionRequestsItsProductionRelativeBodyAndSupportProbe() {
        CircularCropMacro macro = started(quietSettings(), zeros(8), zeros(8));
        long now = enterD(macro, START);
        for (CircularCropMacro.State direction : List.of(
                CircularCropMacro.State.D,
                CircularCropMacro.State.S,
                CircularCropMacro.State.A,
                CircularCropMacro.State.W)) {
            SpatialCaptureRequest request = macro.spatialRequest(
                    player(START, 45.0F, 3.0F, STILL), EPOCH).orElseThrow();
            BlockPosition body = directionBlock(direction);
            assertTrue(request.blocks().contains(body), direction + " body probe");
            assertTrue(request.blocks().contains(
                    new BlockPosition(body.x(), body.y() - 1, body.z())),
                    direction + " support probe");
            now = completeCorner(macro, now, direction, START);
        }
    }

    @Test
    void startupUsesCircularPitchDiagonalYawBackEntropyAndSettingsSnapshot() {
        MacroSettings settings = validSettings();
        QueueRandom leaf = new QueueRandom(0.0D, 0.999D);
        QueueRandom rotation = new QueueRandom(0.0D, 0.999D);
        CircularCropMacro macro = started(settings, leaf, rotation);

        MacroDecision decision = step(macro, 0L, START, 20.0F, 0.0F,
                STILL, grounded(), Map.of());
        var request = decision.rotation().orElseThrow();
        assertEquals(45.0F, request.yaw());
        assertEquals(2.8F, request.pitch());
        assertEquals(RotationProfile.BACK, request.profile());
        assertEquals(-0.25F, request.backModifier());
        assertEquals(615L, request.durationMillis());
        assertEquals(2, leaf.draws());
        assertEquals(2, rotation.draws());
        assertEquals(90.0F, macro.cardinalYaw());

        MacroSettings snapshotted = quietSettings();
        snapshotted.customYawLevel(30.0F);
        QueueRandom noLeaf = new QueueRandom();
        CircularCropMacro custom = started(snapshotted, noLeaf, new QueueRandom());
        snapshotted.customYawLevel(120.0F);
        MacroDecision suppressed = step(custom, 0L, START, 30.0F, 3.0F,
                STILL, grounded(), Map.of());
        assertEquals("startup-fix-suppressed", suppressed.status());
        assertEquals(30.0F, custom.storedYaw());
        assertEquals(0, noLeaf.draws());
    }

    @Test
    void dropThresholdsReleaseAndDeepLandingUsesSeparateBackRotation() {
        PositionSnapshot layer = new PositionSnapshot(0.5D, 3.0D, 0.5D);
        QueueRandom leaf = zeros(8);
        QueueRandom rotation = zeros(8);
        MacroSettings settings = quietSettings();
        settings.rotateAfterDrop(true);
        CircularCropMacro macro = started(settings, leaf, rotation);
        long now = enterD(macro, layer);

        CircularCropMacro exactMacro = started(quietSettings(), zeros(2), zeros(2));
        long exactNow = enterD(exactMacro, layer);
        PositionSnapshot exact = new PositionSnapshot(0.5D, 2.25D, 0.5D);
        MacroDecision exactThreshold = step(exactMacro, exactNow + 1L, exact, 45.0F, 3.0F,
                STILL, airborne(), Map.of());
        assertNotEquals(CircularCropMacro.State.DROPPING, exactMacro.state());
        assertNotEquals("dropping", exactThreshold.status());

        PositionSnapshot falling = new PositionSnapshot(0.5D, 2.0D, 0.5D);
        MacroDecision entry = step(macro, now + 1L, falling, 45.0F, 3.0F,
                STILL, airborne(), Map.of());
        assertEquals("dropping", entry.status());
        assertTrue(entry.inputs().isEmpty());
        assertEquals(CircularCropMacro.State.DROPPING, macro.state());

        PositionSnapshot landed = new PositionSnapshot(0.5D, 1.0D, 0.5D);
        MacroDecision deep = step(macro, now + 2L, landed, 45.0F, 3.0F,
                STILL, grounded(), Map.of());
        var request = deep.rotation().orElseThrow();
        assertEquals(CircularCropMacro.State.NONE, macro.state());
        assertEquals(-135.0F, request.yaw());
        assertEquals(3.0F, request.pitch());
        assertEquals(RotationProfile.BACK, request.profile());
        assertEquals(new BlockPosition(0, 1, 0), macro.spatialAnchor().orElseThrow());
        assertEquals(1, leaf.draws(), "only deep-drop duration is sampled");
        assertEquals(2, rotation.draws());

        CircularCropMacro flying = started(quietSettings(), zeros(2), zeros(2));
        enterD(flying, layer);
        step(flying, 3L, new PositionSnapshot(0.5D, 1.0D, 0.5D),
                45.0F, 3.0F, STILL, new PlayerPosture(true, false, false), Map.of());
        assertEquals(CircularCropMacro.State.D, flying.state());

        PositionSnapshot high = new PositionSnapshot(0.5D, 81.0D, 0.5D);
        CircularCropMacro yEighty = started(quietSettings(), zeros(2), zeros(2));
        enterD(yEighty, high);
        step(yEighty, 3L, new PositionSnapshot(0.5D, 80.0D, 0.5D),
                45.0F, 3.0F, STILL, airborne(), Map.of());
        assertEquals(CircularCropMacro.State.D, yEighty.state());
    }

    @Test
    void shallowLandingRefreshesAnchorWithoutRotation() {
        PositionSnapshot layer = new PositionSnapshot(0.5D, 3.0D, 0.5D);
        QueueRandom leaf = zeros(2);
        CircularCropMacro macro = started(quietSettings(), leaf, zeros(2));
        long now = enterD(macro, layer);
        PositionSnapshot falling = new PositionSnapshot(0.5D, 2.0D, 0.5D);
        step(macro, now + 1L, falling, 45.0F, 3.0F, STILL, airborne(), Map.of());

        MacroDecision shallow = step(macro, now + 2L, falling, 45.0F, 3.0F,
                STILL, grounded(), Map.of());

        assertEquals("drop-too-shallow", shallow.status());
        assertTrue(shallow.rotation().isEmpty());
        assertEquals(CircularCropMacro.State.NONE, macro.state());
        assertEquals(new BlockPosition(0, 2, 0), macro.spatialAnchor().orElseThrow());
        assertEquals(0, leaf.draws());
    }

    @Test
    void genericRewarpRotatesStoredDiagonalByOneEightyAndEmitsOneBackRequest() {
        MacroSettings settings = quietSettings();
        settings.clearRewarps();
        settings.spawn(new RewarpPosition(10, 1, 10));
        assertTrue(settings.addRewarp(new RewarpPosition(0, 1, 0)));
        settings.rotateAfterWarped(true);
        QueueRandom leaf = zeros(8);
        QueueRandom rotation = zeros(8);
        CircularCropMacro macro = started(settings, leaf, rotation);

        step(macro, 0L, START, 45.0F, 3.0F, STILL, grounded(), Map.of());
        long dwellAt = 1L;
        assertEquals("rewarp-dwell", step(macro, dwellAt, START,
                45.0F, 3.0F, STILL, grounded(), Map.of()).status());
        long warpAt = dwellAt + TimeUnit.MILLISECONDS.toNanos(400L);
        assertTrue(step(macro, warpAt, START, 45.0F, 3.0F,
                STILL, grounded(), Map.of()).warp().isPresent());

        PositionSnapshot spawn = new PositionSnapshot(10.5D, 1.0D, 10.5D);
        long landedAt = warpAt + 1L;
        step(macro, landedAt, spawn, 0.0F, 3.0F, STILL, grounded(), Map.of());
        long postAt = landedAt + TimeUnit.MILLISECONDS.toNanos(1_500L);
        step(macro, postAt, spawn, 0.0F, 3.0F, STILL, grounded(), Map.of());
        MacroDecision correction = step(macro,
                postAt + TimeUnit.MILLISECONDS.toNanos(600L),
                spawn, 0.0F, 3.0F, STILL, grounded(), Map.of());

        var request = correction.rotation().orElseThrow();
        assertEquals("post-rewarp-circular-back", correction.status());
        assertEquals(-135.0F, request.yaw());
        assertEquals(RotationProfile.BACK, request.profile());
        assertEquals(1_100L, request.durationMillis(), "raw 500ms doubles before shared scaling");
        assertEquals(-90.0F, macro.cardinalYaw());
        assertEquals(new BlockPosition(10, 1, 10), macro.spatialAnchor().orElseThrow());
        assertEquals(2, leaf.draws(), "dwell and one terminal Back duration");
        assertEquals(2, rotation.draws());

        MacroDecision active = step(macro,
                postAt + TimeUnit.MILLISECONDS.toNanos(600L) + 1L,
                spawn, 0.0F, 3.0F, STILL, grounded(), Map.of(),
                MacroRotationLeaseState.active(request.requestToken(), false, 9L));
        assertEquals(request, active.rotation().orElseThrow());
        assertEquals(2, leaf.draws());
        assertEquals(2, rotation.draws());

        MacroDecision completed = step(macro,
                postAt + TimeUnit.MILLISECONDS.toNanos(600L) + 2L,
                spawn, -135.0F, 3.0F, STILL, grounded(), Map.of(),
                MacroRotationLeaseState.terminal(
                        request.requestToken(), RotationTerminalReason.COMPLETED, 10L));
        assertEquals(Set.of(InputAction.RIGHT, InputAction.ATTACK), completed.inputs());
        assertTrue(completed.rotation().isEmpty());
        assertEquals(CircularCropMacro.State.D, macro.state());
        assertEquals(2, leaf.draws());
        assertEquals(2, rotation.draws());
    }

    @Test
    void rewarpRetryAndAirborneSneakUseExactSharedBoundaries() {
        MacroSettings settings = quietSettings();
        settings.clearRewarps();
        settings.spawn(new RewarpPosition(10, 1, 10));
        assertTrue(settings.addRewarp(new RewarpPosition(0, 1, 0)));
        QueueRandom leaf = zeros(6);
        CircularCropMacro macro = started(settings, leaf, zeros(2));

        step(macro, 0L, START, 45.0F, 3.0F, STILL, grounded(), Map.of());
        long dwellAt = 1L;
        step(macro, dwellAt, START, 45.0F, 3.0F, STILL, grounded(), Map.of());
        long warpAt = dwellAt + TimeUnit.MILLISECONDS.toNanos(400L);
        assertTrue(step(macro, warpAt, START, 45.0F, 3.0F,
                STILL, grounded(), Map.of()).warp().isPresent());

        MacroDecision waiting = step(macro,
                warpAt + TimeUnit.SECONDS.toNanos(5L) - 1L,
                START, 45.0F, 3.0F, STILL, grounded(), Map.of());
        assertEquals("rewarp-waiting", waiting.status());
        assertTrue(waiting.warp().isEmpty());
        long retryAt = warpAt + TimeUnit.SECONDS.toNanos(5L);
        assertTrue(step(macro, retryAt, START, 45.0F, 3.0F,
                STILL, grounded(), Map.of()).warp().isPresent());

        PositionSnapshot spawn = new PositionSnapshot(10.5D, 1.0D, 10.5D);
        long airborneAt = retryAt + 1L;
        MacroDecision airborne = step(macro, airborneAt, spawn, 45.0F, 3.0F,
                STILL, new PlayerPosture(true, false, false), Map.of());
        assertEquals(Set.of(InputAction.SNEAK), airborne.inputs());
        assertEquals("rewarp-airborne-sneak", airborne.status());
        MacroDecision before = step(macro,
                airborneAt + TimeUnit.MILLISECONDS.toNanos(350L) - 1L,
                spawn, 45.0F, 3.0F, STILL,
                new PlayerPosture(true, false, false), Map.of());
        assertEquals(Set.of(InputAction.SNEAK), before.inputs());
        MacroDecision exact = step(macro,
                airborneAt + TimeUnit.MILLISECONDS.toNanos(350L),
                spawn, 45.0F, 3.0F, STILL,
                new PlayerPosture(true, false, false), Map.of());
        assertEquals("rewarp-airborne", exact.status());
        assertTrue(exact.inputs().isEmpty());
        assertEquals(2, leaf.draws(), "only dwell and one sneak duration are sampled");
    }

    @Test
    void pauseResumeRefreshesAnchorAndPreservesSampledDwellAndRotation() {
        QueueRandom leaf = zeros(6);
        QueueRandom rotation = zeros(6);
        CircularCropMacro macro = started(quietSettings(), leaf, rotation);
        long now = enterD(macro, START);
        MacroDecision dwell = step(macro, now + 1L, START, 45.0F, 3.0F,
                STILL, grounded(), blocked(CircularCropMacro.State.D));
        long sampled = macro.cornerDwellNanos();
        int draws = leaf.draws();
        macro.onPause(Set.of(MacroPauseCause.SCREEN_OPEN), now + 2L);
        macro.onResume(TimeUnit.SECONDS.toNanos(10L));

        PositionSnapshot resumed = new PositionSnapshot(2.5D, 1.0D, 0.5D);
        MacroDecision waiting = step(macro, TimeUnit.SECONDS.toNanos(10L) + 1L,
                resumed, 45.0F, 3.0F, STILL, grounded(),
                blockedAt(CircularCropMacro.State.D, resumed));

        assertEquals("circular-corner-dwell", dwell.status());
        assertEquals("circular-corner-dwell", waiting.status());
        assertEquals(sampled, macro.cornerDwellNanos());
        assertEquals(draws, leaf.draws());
        assertEquals(new BlockPosition(2, 1, 0), macro.spatialAnchor().orElseThrow());

        MacroSettings rotatingSettings = validSettings();
        QueueRandom rotatingLeaf = zeros(4);
        QueueRandom rotatingEntropy = zeros(4);
        CircularCropMacro rotating = started(rotatingSettings, rotatingLeaf, rotatingEntropy);
        var initial = step(rotating, 0L, START, 0.0F, 0.0F,
                STILL, grounded(), Map.of()).rotation().orElseThrow();
        int leafBefore = rotatingLeaf.draws();
        int entropyBefore = rotatingEntropy.draws();
        rotating.onPause(Set.of(MacroPauseCause.SCREEN_OPEN), 1L);
        rotating.onResume(2L);
        MacroDecision retry = step(rotating, 3L, START, 0.0F, 0.0F,
                STILL, grounded(), Map.of(), MacroRotationLeaseState.terminal(
                        initial.requestToken(), RotationTerminalReason.OWNER_CANCELLED, 2L));
        assertEquals(initial, retry.rotation().orElseThrow());
        assertEquals(leafBefore, rotatingLeaf.draws());
        assertEquals(entropyBefore, rotatingEntropy.draws());
    }

    @Test
    void staleTokenWorldBoundsBodyAndPhaseFailClosedAndTerminalStopInvalidatesRun() {
        assertStale(StaleKind.TOKEN);
        assertStale(StaleKind.WORLD);
        assertStale(StaleKind.BOUNDS);
        assertStale(StaleKind.BODY);

        QueueRandom leaf = new QueueRandom();
        CircularCropMacro phase = started(quietSettings(), leaf, new QueueRandom());
        PlayerSnapshot player = player(START, 45.0F, 3.0F, STILL);
        SpatialCaptureRequest old = phase.spatialRequest(player, EPOCH).orElseThrow();
        SpatialSnapshot oldSnapshot = captured(old, START, Map.of());
        phase.tick(context(0L, player, oldSnapshot, grounded(), MacroRotationLeaseState.idle(0L)));
        MacroDecision stalePhase = phase.tick(context(
                1L, player, oldSnapshot, grounded(), MacroRotationLeaseState.idle(0L)));
        assertEquals("spatial-unknown-or-stale", stalePhase.status());
        assertEquals(0, leaf.draws());

        phase.onStop(MacroTerminalReason.WORLD_CHANGE);
        assertEquals(CircularCropMacro.State.STOPPED, phase.state());
        assertTrue(phase.spatialRequest(player, EPOCH + 1L).isEmpty());
        assertTrue(phase.tick(context(2L, player, oldSnapshot,
                grounded(), MacroRotationLeaseState.idle(0L))).inputs().isEmpty());
    }

    private static long enterD(CircularCropMacro macro, PositionSnapshot position) {
        step(macro, 0L, position, 45.0F, 3.0F, STILL, grounded(), Map.of());
        MacroDecision d = step(macro, 1L, position, 45.0F, 3.0F,
                STILL, grounded(), Map.of());
        assertEquals(Set.of(InputAction.RIGHT, InputAction.ATTACK), d.inputs());
        assertEquals(CircularCropMacro.State.D, macro.state());
        return 1L;
    }

    private static long completeCorner(
            CircularCropMacro macro,
            long now,
            CircularCropMacro.State direction,
            PositionSnapshot position
    ) {
        long dwellAt = now + 1L;
        step(macro, dwellAt, position, 45.0F, 3.0F,
                STILL, grounded(), blockedAt(direction, position));
        long completeAt = dwellAt + TimeUnit.MILLISECONDS.toNanos(400L);
        step(macro, completeAt, position, 45.0F, 3.0F,
                STILL, grounded(), blockedAt(direction, position));
        return completeAt;
    }

    private static void assertDirection(
            CircularCropMacro macro,
            CircularCropMacro.State expected
    ) {
        assertEquals(expected, macro.state());
    }

    private static Map<BlockPosition, Observation<BlockStateSnapshot>> blocked(
            CircularCropMacro.State direction
    ) {
        return Map.of(directionBlock(direction), Observation.present(full()));
    }

    private static Map<BlockPosition, Observation<BlockStateSnapshot>> blockedAt(
            CircularCropMacro.State direction,
            PositionSnapshot position
    ) {
        BlockPosition relative = directionBlock(direction);
        int dx = (int) Math.floor(position.x()) - (int) Math.floor(START.x());
        int dy = (int) Math.floor(position.y()) - (int) Math.floor(START.y());
        int dz = (int) Math.floor(position.z()) - (int) Math.floor(START.z());
        return Map.of(new BlockPosition(
                relative.x() + dx, relative.y() + dy, relative.z() + dz),
                Observation.present(full()));
    }

    private static BlockPosition directionBlock(CircularCropMacro.State direction) {
        return switch (direction) {
            case D -> new BlockPosition(0, 1, -1);
            case S -> new BlockPosition(1, 1, 0);
            case A -> new BlockPosition(0, 1, 1);
            case W -> new BlockPosition(-1, 1, 0);
            default -> throw new IllegalArgumentException("not a direction");
        };
    }

    private static CircularCropMacro started(
            MacroSettings settings,
            QueueRandom leaf,
            QueueRandom rotation
    ) {
        CircularCropMacro macro = new CircularCropMacro(settings, leaf, rotation);
        macro.onStart();
        return macro;
    }

    private static MacroSettings quietSettings() {
        MacroSettings settings = validSettings();
        settings.customPitch(true);
        settings.customPitchLevel(3.0F);
        settings.customYaw(true);
        settings.customYawLevel(45.0F);
        settings.dontFixAfterWarping(true);
        return settings;
    }

    private static MacroSettings validSettings() {
        MacroSettings settings = new MacroSettings();
        settings.macroMode(MacroMode.CIRCULAR);
        settings.spawn(new RewarpPosition(100, 70, 100));
        assertTrue(settings.addRewarp(new RewarpPosition(90, 70, 90)));
        return settings;
    }

    private static MacroDecision step(
            CircularCropMacro macro,
            long now,
            PositionSnapshot position,
            float yaw,
            float pitch,
            MotionSnapshot motion,
            PlayerPosture posture,
            Map<BlockPosition, Observation<BlockStateSnapshot>> overrides
    ) {
        return step(macro, now, position, yaw, pitch, motion, posture, overrides,
                MacroRotationLeaseState.idle(0L));
    }

    private static MacroDecision step(
            CircularCropMacro macro,
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
                new BoxSnapshot(player.x() - 0.3D, player.y(), player.z() - 0.3D,
                        player.x() + 0.3D, player.y() + 1.8D, player.z() + 0.3D),
                chunks);
    }

    private static void assertStale(StaleKind kind) {
        QueueRandom leaf = new QueueRandom();
        CircularCropMacro macro = started(quietSettings(), leaf, new QueueRandom());
        PlayerSnapshot player = player(START, 45.0F, 3.0F, STILL);
        SpatialCaptureRequest request = macro.spatialRequest(player, EPOCH).orElseThrow();
        SpatialSnapshot valid = captured(request, START, Map.of());
        SpatialSnapshot stale = switch (kind) {
            case TOKEN -> new SpatialSnapshot(
                    EPOCH, request.requestToken() + 1L, request.bounds(), -64, 320,
                    valid.playerBox(), valid.chunks());
            case WORLD -> new SpatialSnapshot(
                    EPOCH + 1L, request.requestToken(), request.bounds(), -64, 320,
                    valid.playerBox(), valid.chunks());
            case BOUNDS -> new SpatialSnapshot(
                    EPOCH, request.requestToken(), new BoxSnapshot(
                    request.bounds().minX(), request.bounds().minY(), request.bounds().minZ(),
                    request.bounds().maxX() + 1.0D,
                    request.bounds().maxY(), request.bounds().maxZ()),
                    -64, 320, valid.playerBox(), valid.chunks());
            case BODY -> new SpatialSnapshot(
                    EPOCH, request.requestToken(), request.bounds(), -64, 320,
                    valid.playerBox().move(0.01D, 0.0D, 0.0D), valid.chunks());
        };
        MacroDecision decision = macro.tick(context(
                0L, player, stale, grounded(), MacroRotationLeaseState.idle(0L)));
        assertEquals("spatial-unknown-or-stale", decision.status(), kind.name());
        assertTrue(decision.inputs().isEmpty());
        assertEquals(0, leaf.draws());
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

    private static PlayerPosture airborne() {
        return new PlayerPosture(false, false, false);
    }

    private static QueueRandom zeros(int count) {
        return new QueueRandom(new double[count]);
    }

    private enum StaleKind {
        TOKEN,
        WORLD,
        BOUNDS,
        BODY
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
