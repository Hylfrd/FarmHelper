package dev.hylfrd.farmhelper.macro.impl;

import dev.hylfrd.farmhelper.control.RotationTask;
import dev.hylfrd.farmhelper.control.input.InputAction;
import dev.hylfrd.farmhelper.control.rotation.RotationProfile;
import dev.hylfrd.farmhelper.macro.FarmingContext;
import dev.hylfrd.farmhelper.macro.Macro;
import dev.hylfrd.farmhelper.macro.MacroAngles;
import dev.hylfrd.farmhelper.macro.MacroDecision;
import dev.hylfrd.farmhelper.macro.MacroPauseCause;
import dev.hylfrd.farmhelper.macro.MacroRandom;
import dev.hylfrd.farmhelper.macro.MacroRecoveryReason;
import dev.hylfrd.farmhelper.macro.MacroSettings;
import dev.hylfrd.farmhelper.macro.MacroSpawnPose;
import dev.hylfrd.farmhelper.macro.MacroTerminalReason;
import dev.hylfrd.farmhelper.macro.MacroWarpRequest;
import dev.hylfrd.farmhelper.macro.PlayerPosture;
import dev.hylfrd.farmhelper.macro.ServerResponsiveness;
import dev.hylfrd.farmhelper.macro.mechanism.CaptureIdentityLedger;
import dev.hylfrd.farmhelper.macro.mechanism.DropLedger;
import dev.hylfrd.farmhelper.macro.mechanism.OwnedRotationLedger;
import dev.hylfrd.farmhelper.macro.mechanism.RewarpLedger;
import dev.hylfrd.farmhelper.macro.mechanism.RotationEntropy;
import dev.hylfrd.farmhelper.macro.mechanism.RowProgressLedger;
import dev.hylfrd.farmhelper.runtime.snapshot.MotionSnapshot;
import dev.hylfrd.farmhelper.runtime.snapshot.Observation;
import dev.hylfrd.farmhelper.runtime.snapshot.PlayerSnapshot;
import dev.hylfrd.farmhelper.runtime.snapshot.PositionSnapshot;
import dev.hylfrd.farmhelper.runtime.snapshot.ResourceIdentifier;
import dev.hylfrd.farmhelper.runtime.snapshot.RotationSnapshot;
import dev.hylfrd.farmhelper.runtime.spatial.BlockPosition;
import dev.hylfrd.farmhelper.runtime.spatial.BlockStateSnapshot;
import dev.hylfrd.farmhelper.runtime.spatial.BoxSnapshot;
import dev.hylfrd.farmhelper.runtime.spatial.RelativeFrame;
import dev.hylfrd.farmhelper.runtime.spatial.RewarpPosition;
import dev.hylfrd.farmhelper.runtime.spatial.SpaceStatus;
import dev.hylfrd.farmhelper.runtime.spatial.SpatialCaptureRequest;
import dev.hylfrd.farmhelper.runtime.spatial.SpatialQueries;
import dev.hylfrd.farmhelper.runtime.spatial.SpatialSnapshot;

import java.util.EnumSet;
import java.util.HashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/** Upstream-parity mode 4 sugar-cane leaf over shared P2 lifecycle mechanisms. */
public final class SShapeSugarcaneMacro implements Macro {
    static final long PROGRESS_WINDOW_NANOS = TimeUnit.MILLISECONDS.toNanos(500L);
    static final long WARP_RETRY_NANOS = TimeUnit.SECONDS.toNanos(5L);
    static final long AFTER_WARP_NANOS = TimeUnit.MILLISECONDS.toNanos(1_500L);
    static final long POST_REWARP_NANOS = TimeUnit.MILLISECONDS.toNanos(600L);
    static final double MIN_PROGRESS = 0.05D;
    static final int MAX_NO_PROGRESS_WINDOWS = 3;
    private static final ResourceIdentifier WATER = ResourceIdentifier.parse("minecraft:water");

    private final MacroSettings settings;
    private final MacroRandom leafRandom;
    private final RotationEntropy rotationEntropy;
    private final RowProgressLedger rowProgress = new RowProgressLedger(
            PROGRESS_WINDOW_NANOS, MIN_PROGRESS, MAX_NO_PROGRESS_WINDOWS);
    private final DropLedger drop = new DropLedger();
    private final RewarpLedger rewarp = new RewarpLedger();
    private final CaptureIdentityLedger<CaptureKey> captures = new CaptureIdentityLedger<>();
    private final OwnedRotationLedger rotation = new OwnedRotationLedger();
    private State state = State.STOPPED;
    private float farmingYaw;
    private float farmingPitch;
    private float cardinalYaw;
    private long recoveryUntil;
    private MacroRecoveryReason recoveryReason;
    private long pausedAt = Long.MIN_VALUE;
    private long runGeneration;

    public SShapeSugarcaneMacro(MacroSettings settings, MacroRandom random) {
        this(settings, random, random);
    }

    public SShapeSugarcaneMacro(
            MacroSettings settings,
            MacroRandom leafRandom,
            MacroRandom rotationRandom
    ) {
        this.settings = Objects.requireNonNull(settings, "settings").snapshot();
        this.leafRandom = Objects.requireNonNull(leafRandom, "leafRandom");
        rotationEntropy = new RotationEntropy(
                Objects.requireNonNull(rotationRandom, "rotationRandom"));
    }

    @Override
    public String id() {
        return "s-shape-sugarcane";
    }

    @Override
    public void onStart() {
        reset(State.STARTUP);
    }

    @Override
    public void onStart(long nowNanos) {
        if (nowNanos < 0L) {
            throw new IllegalArgumentException("start time must not be negative");
        }
        reset(State.STARTUP);
    }

    @Override
    public void onStop() {
        reset(State.STOPPED);
    }

    @Override
    public void onStop(MacroTerminalReason reason) {
        onStop();
    }

    @Override
    public void onPause(Set<MacroPauseCause> causes, long nowNanos) {
        Objects.requireNonNull(causes, "causes");
        if (pausedAt == Long.MIN_VALUE) {
            pausedAt = nowNanos;
        }
        invalidateCapture();
    }

    @Override
    public void onResume(long nowNanos) {
        if (pausedAt == Long.MIN_VALUE) {
            return;
        }
        long suspended = elapsed(nowNanos, pausedAt);
        switch (state) {
            case A, D, S -> rowProgress.shift(suspended);
            case REWARP_DWELL, WARP_LANDING -> rewarp.shiftState(suspended);
            case REWARPING -> rewarp.shiftRequest(suspended);
            case AFTER_WARP, POST_REWARP -> recoveryUntil = shift(recoveryUntil, suspended);
            case STOPPED, STARTUP, NONE, DROPPING, RECOVERY_HANDOFF -> { }
        }
        pausedAt = Long.MIN_VALUE;
        invalidateCapture();
    }

    @Override
    public Optional<SpatialCaptureRequest> spatialRequest(PlayerSnapshot player, long worldEpoch) {
        Objects.requireNonNull(player, "player");
        if (state == State.STOPPED || !player.position().isPresent() || !player.rotation().isPresent()) {
            return Optional.empty();
        }
        PositionSnapshot position = player.position().get();
        RotationSnapshot observedRotation = player.rotation().get();
        float geometryYaw = geometryYaw(observedRotation.yaw());
        CaptureAnchor anchor = new CaptureAnchor(
                block(position), body(position), observedRotation.yaw(), geometryYaw,
                state == State.STARTUP ? MacroAngles.closestCardinal(geometryYaw) : cardinalYaw);
        CaptureKey key = captureKey(worldEpoch, anchor);
        Optional<SpatialCaptureRequest> reusable = captures.reusable(key);
        if (reusable.isPresent()) {
            return reusable;
        }
        invalidateCapture();
        key = captureKey(worldEpoch, anchor);
        Set<BlockPosition> blocks = requestedBlocks(position, geometryYaw);
        if (blocks.isEmpty()) {
            blocks.add(block(position));
        }
        return Optional.of(captures.begin(
                key, worldEpoch, bounds(blocks), blocks, anchor.body()));
    }

    @Override
    public MacroDecision tick(FarmingContext context) {
        Objects.requireNonNull(context, "context");
        if (state == State.STOPPED) {
            return MacroDecision.idle("stopped");
        }
        MacroDecision prerequisite = prerequisiteFailure(context);
        if (prerequisite != null) {
            return prerequisite;
        }
        PlayerPosture posture = context.posture().get();
        Observed observed = consumeCapture(context);
        if (observed == null) {
            return MacroDecision.failClosed("spatial-unknown-or-stale");
        }

        if (state == State.STARTUP) {
            farmingPitch = settings.customPitch()
                    ? settings.customPitchLevel() : (float) (-0.5D + randomUnit());
            farmingYaw = settings.customYaw()
                    ? RotationTask.normalizeYaw(settings.customYawLevel())
                    : MacroAngles.closestDiagonal(observed.rotation().yaw());
            cardinalYaw = MacroAngles.closestCardinal(farmingYaw);
            state = State.NONE;
            drop.arm(observed.position().y());
            if (!(settings.dontFixAfterWarping()
                    && Math.abs(MacroAngles.shortestDelta(
                    observed.rotation().yaw(), farmingYaw)) < 0.1F)) {
                rotation.begin(observed.rotation().yaw(), observed.rotation().pitch(),
                        farmingYaw, farmingPitch, RotationProfile.BACK,
                        sampleRotationMillis(), rotationEntropy);
                invalidateCapture();
                return rotationDecision("initial-aligning");
            }
            invalidateCapture();
            return MacroDecision.failClosed("initial-fix-suppressed");
        }
        if (state == State.RECOVERY_HANDOFF) {
            return MacroDecision.recoveryHandoff("recovery-handoff", recoveryReason);
        }
        if (state == State.REWARP_DWELL) {
            return tickRewarpDwell(context, observed);
        }
        if (state == State.REWARPING) {
            return tickRewarp(context, observed);
        }
        if (state == State.WARP_LANDING) {
            return tickWarpLanding(context, posture);
        }
        if (state == State.AFTER_WARP) {
            if (context.nowNanos() < recoveryUntil) {
                return MacroDecision.failClosed("after-warp");
            }
            state = State.POST_REWARP;
            recoveryUntil = saturatingAdd(context.nowNanos(), POST_REWARP_NANOS);
            invalidateCapture();
            return MacroDecision.failClosed("post-rewarp");
        }
        if (state == State.POST_REWARP) {
            if (context.nowNanos() < recoveryUntil) {
                return MacroDecision.failClosed("post-rewarp");
            }
            return finishPostRewarp(observed);
        }
        if (state == State.DROPPING) {
            return tickDrop(observed, posture);
        }

        if (drop.shouldDrop(posture, observed.position().y())) {
            state = State.DROPPING;
            rotation.clear();
            rowProgress.clear();
            invalidateCapture();
            return MacroDecision.failClosed("dropping");
        }
        SpaceStatus footing = SpatialQueries.walkability(
                observed.spatial(), observed.worldEpoch(), body(observed.position()));
        if (footing == SpaceStatus.UNKNOWN) {
            return MacroDecision.failClosed("body-or-support-unknown");
        }
        if (footing == SpaceStatus.BLOCKED) {
            return MacroDecision.failClosed("body-or-support-blocked");
        }

        Optional<MacroDecision> rewarpDecision = beginRewarp(context, observed);
        if (rewarpDecision.isPresent()) {
            return rewarpDecision.orElseThrow();
        }

        MacroDecision alignment = align(context);
        if (alignment != null) {
            return alignment;
        }
        if (state == State.NONE) {
            return chooseDirection(observed);
        }
        return tickMovement(context, observed);
    }

    public State state() {
        return state;
    }

    Optional<dev.hylfrd.farmhelper.macro.MacroRotationRequest> pendingRotation() {
        return rotation.pending();
    }

    private MacroDecision prerequisiteFailure(FarmingContext context) {
        if (!context.inGarden().isPresent()) {
            return MacroDecision.failClosed("garden-unknown");
        }
        if (!context.inGarden().get() && !context.developmentGarden()) {
            return MacroDecision.failClosed("outside-garden");
        }
        if (context.serverResponsiveness() != ServerResponsiveness.RESPONSIVE) {
            return MacroDecision.failClosed(context.serverResponsiveness()
                    == ServerResponsiveness.LAGGING ? "server-lagging" : "server-unknown");
        }
        if (settings.spawn().isEmpty() || settings.rewarps().isEmpty()) {
            return MacroDecision.failClosed("rewarp-config-missing");
        }
        if (!context.posture().isPresent()) {
            return MacroDecision.failClosed(state == State.WARP_LANDING
                    ? "rewarp-posture-unknown" : "player-posture-unknown");
        }
        if (context.posture().get().suffocating() && state != State.WARP_LANDING) {
            return MacroDecision.failClosed("player-suffocating");
        }
        return null;
    }

    private MacroDecision chooseDirection(Observed observed) {
        RelativeFrame minus = RelativeFrame.eightWay(farmingYaw - 45.0F);
        RelativeFrame plus = RelativeFrame.eightWay(farmingYaw + 45.0F);
        Evidence minusWater = waterEvidence(observed, minus);
        if (minusWater == Evidence.UNKNOWN) {
            return MacroDecision.failClosed("sugarcane-water-unknown");
        }
        if (minusWater == Evidence.PRESENT) {
            SpaceStatus aBlocked = bothWalls(
                    wall(observed, minus, 0, 0, 1),
                    wall(observed, minus, -1, 0, 0));
            if (aBlocked == SpaceStatus.UNKNOWN) {
                return MacroDecision.failClosed("sugarcane-a-route-unknown");
            }
            if (aBlocked == SpaceStatus.PASSABLE) {
                return enterMovement(State.A, observed, "direction-a");
            }
            Evidence plusWater = waterEvidence(observed, plus);
            if (plusWater == Evidence.UNKNOWN) {
                return MacroDecision.failClosed("sugarcane-water-unknown");
            }
            if (plusWater == Evidence.PRESENT) {
                SpaceStatus dBlocked = bothWalls(
                        wall(observed, plus, 0, 0, 1),
                        wall(observed, plus, 1, 0, 0));
                if (dBlocked == SpaceStatus.UNKNOWN) {
                    return MacroDecision.failClosed("sugarcane-d-route-unknown");
                }
                if (dBlocked == SpaceStatus.PASSABLE) {
                    return enterMovement(State.D, observed, "direction-d");
                }
            }
        }
        return enterMovement(State.S, observed, "direction-s");
    }

    private MacroDecision tickMovement(FarmingContext context, Observed observed) {
        double coordinate = movementCoordinate(observed.position(), state);
        RowProgressLedger.Result progress = rowProgress.observeWindowed(
                context.nowNanos(), coordinate);
        if (progress == RowProgressLedger.Result.WAITING
                || progress == RowProgressLedger.Result.PROGRESSED) {
            return movementDecision(state);
        }
        if (state == State.A || state == State.D) {
            return enterMovement(State.S, observed, "row-turn-s");
        }
        MacroDecision transition = transitionFromS(observed);
        if (transition != null) {
            return transition;
        }
        if (progress == RowProgressLedger.Result.STALLED) {
            return enterRecovery(MacroRecoveryReason.ROW_STALLED, "row-stalled");
        }
        return MacroDecision.failClosed("row-stall-observed-" + rowProgress.misses());
    }

    private MacroDecision transitionFromS(Observed observed) {
        RelativeFrame plus = RelativeFrame.eightWay(farmingYaw + 45.0F);
        RelativeFrame minus = RelativeFrame.eightWay(farmingYaw - 45.0F);
        SpaceStatus plusRear = wall(observed, plus, 0, 0, -1);
        SpaceStatus minusRear = wall(observed, minus, 0, 0, -1);
        if (plusRear == SpaceStatus.UNKNOWN || minusRear == SpaceStatus.UNKNOWN) {
            return MacroDecision.failClosed("sugarcane-rear-unknown");
        }
        if (plusRear != SpaceStatus.BLOCKED || minusRear != SpaceStatus.BLOCKED) {
            return null;
        }

        SpaceStatus leftOpen = sideOpen(observed, plus, -1);
        SpaceStatus rightOpen = sideOpen(observed, minus, 1);
        if (rightOpen == SpaceStatus.PASSABLE) {
            return enterMovement(State.D, observed, "row-turn-d");
        }
        if (rightOpen == SpaceStatus.UNKNOWN) {
            return MacroDecision.failClosed("sugarcane-side-scan-unknown");
        }
        if (leftOpen == SpaceStatus.PASSABLE) {
            return enterMovement(State.A, observed, "row-turn-a");
        }
        if (leftOpen == SpaceStatus.UNKNOWN) {
            return MacroDecision.failClosed("sugarcane-side-scan-unknown");
        }
        return null;
    }

    private MacroDecision enterMovement(State next, Observed observed, String status) {
        state = Objects.requireNonNull(next, "next");
        rowProgress.begin(observed.nowNanos(), movementCoordinate(observed.position(), next));
        invalidateCapture();
        return MacroDecision.failClosed(status);
    }

    private static MacroDecision movementDecision(State state) {
        Set<InputAction> inputs = switch (state) {
            case A -> EnumSet.of(InputAction.LEFT, InputAction.ATTACK);
            case D -> EnumSet.of(InputAction.RIGHT, InputAction.ATTACK);
            case S -> EnumSet.of(InputAction.BACKWARD, InputAction.ATTACK);
            default -> throw new IllegalStateException("not a sugarcane movement state: " + state);
        };
        return decision(inputs, "farming-" + state.name().toLowerCase());
    }

    private MacroDecision align(FarmingContext context) {
        if (rotation.pending().isEmpty()) {
            return null;
        }
        return switch (rotation.observe(context)) {
            case NONE, COMPLETED -> null;
            case ACTIVE, UNACKNOWLEDGED, RETRYABLE_CANCELLATION ->
                    rotationDecision("aligning");
            case CANCELLED -> MacroDecision.failClosed("rotation-cancelled");
            case STALE_ACKNOWLEDGEMENT ->
                    MacroDecision.failClosed("rotation-acknowledgement-stale");
        };
    }

    private MacroDecision tickDrop(Observed observed, PlayerPosture posture) {
        Optional<DropLedger.Landing> landing = drop.observeLanding(
                posture, observed.position().y());
        if (landing.isEmpty()) {
            return MacroDecision.failClosed("dropping");
        }
        state = State.NONE;
        rowProgress.clear();
        if (landing.orElseThrow().changedLayer() && settings.rotateAfterDrop()) {
            farmingYaw = MacroAngles.closestDiagonal(farmingYaw + 180.0F);
            cardinalYaw = MacroAngles.closestCardinal(farmingYaw);
            rotation.begin(observed.rotation().yaw(), observed.rotation().pitch(),
                    farmingYaw, farmingPitch, RotationProfile.BACK,
                    sampleDropRotationMillis(), rotationEntropy);
            invalidateCapture();
            return rotationDecision("drop-rotate");
        }
        invalidateCapture();
        return MacroDecision.failClosed(landing.orElseThrow().changedLayer()
                ? "drop-complete" : "drop-too-shallow");
    }

    private Optional<MacroDecision> beginRewarp(FarmingContext context, Observed observed) {
        Optional<RewarpPosition> current = settings.rewarps().stream()
                .filter(position -> position.block().equals(block(observed.position())))
                .findFirst();
        if (current.isEmpty()) {
            return Optional.empty();
        }
        if (!stationary(observed.motion())) {
            return Optional.of(MacroDecision.failClosed("rewarp-moving"));
        }
        state = State.REWARP_DWELL;
        rewarp.begin(current.orElseThrow(), context.nowNanos(), TimeUnit.MILLISECONDS.toNanos(
                400L + (long) Math.floor(randomUnit() * 350.0D)));
        rotation.clear();
        rowProgress.clear();
        invalidateCapture();
        return Optional.of(MacroDecision.failClosed("rewarp-dwell"));
    }

    private MacroDecision tickRewarpDwell(FarmingContext context, Observed observed) {
        MacroSpawnPose spawn = validWarpConfig();
        if (spawn == null) {
            return MacroDecision.failClosed("rewarp-config-lost");
        }
        RewarpPosition origin = rewarp.origin().orElse(null);
        if (origin == null || !block(observed.position()).equals(origin.block())
                || !stationary(observed.motion())) {
            rewarp.clear();
            state = State.NONE;
            invalidateCapture();
            return MacroDecision.failClosed("rewarp-dwell-ineligible");
        }
        if (!rewarp.dwellComplete(context.nowNanos())) {
            return MacroDecision.failClosed("rewarp-dwell");
        }
        state = State.REWARPING;
        rewarp.requested(context.nowNanos());
        invalidateCapture();
        return warpDecision(context, spawn, "rewarp-request");
    }

    private MacroDecision tickRewarp(FarmingContext context, Observed observed) {
        MacroSpawnPose spawn = validWarpConfig();
        if (spawn == null) {
            return MacroDecision.failClosed("rewarp-config-lost");
        }
        BlockPosition current = block(observed.position());
        RewarpPosition origin = rewarp.origin().orElseThrow();
        if (origin.squaredDistance(current) > 2.0D || spawn.block().equals(current)) {
            rewarp.confirmed(spawn, context.nowNanos());
            state = State.WARP_LANDING;
            rowProgress.clear();
            invalidateCapture();
            return tickWarpLanding(context, context.posture().get());
        }
        if (!rewarp.retryDue(context.nowNanos(), WARP_RETRY_NANOS)) {
            return MacroDecision.failClosed("rewarp-waiting");
        }
        rewarp.requested(context.nowNanos());
        return warpDecision(context, spawn, "rewarp-retry-" + rewarp.attempts());
    }

    private MacroDecision tickWarpLanding(FarmingContext context, PlayerPosture posture) {
        if (posture.suffocating()) {
            return MacroDecision.failClosed("rewarp-suffocating");
        }
        if (posture.flying() && !posture.onGround()) {
            if (!rewarp.sneakSampled()) {
                rewarp.beginSneak(context.nowNanos(), TimeUnit.MILLISECONDS.toNanos(
                        350L + (long) Math.floor(randomUnit() * 300.0D)));
            }
            if (!rewarp.sneakComplete(context.nowNanos())) {
                return decision(EnumSet.of(InputAction.SNEAK), "rewarp-airborne-sneak");
            }
            return MacroDecision.failClosed("rewarp-airborne");
        }
        if (!posture.onGround()) {
            return MacroDecision.failClosed("rewarp-falling");
        }
        state = State.AFTER_WARP;
        recoveryUntil = saturatingAdd(context.nowNanos(), AFTER_WARP_NANOS);
        invalidateCapture();
        return MacroDecision.failClosed("rewarp-confirmed-plot-"
                + rewarp.confirmedSpawn().map(MacroSpawnPose::plot).orElse(-1));
    }

    private MacroDecision finishPostRewarp(Observed observed) {
        if (settings.rotateAfterWarped()) {
            farmingYaw = MacroAngles.closestDiagonal(farmingYaw + 180.0F);
            cardinalYaw = MacroAngles.closestCardinal(farmingYaw);
        }
        rewarp.clear();
        state = State.NONE;
        drop.arm(observed.position().y());
        rowProgress.clear();
        float yawDistance = Math.abs(MacroAngles.shortestDelta(
                observed.rotation().yaw(), farmingYaw));
        float pitchDistance = Math.abs(observed.rotation().pitch() - farmingPitch);
        if (settings.dontFixAfterWarping()
                && Math.hypot(yawDistance, pitchDistance) < 1.0D) {
            rotation.clear();
            invalidateCapture();
            return MacroDecision.failClosed("post-rewarp-fix-suppressed");
        }
        long duration = sampleRotationMillis();
        if (yawDistance > 90.0F) {
            duration = Math.multiplyExact(duration, 2L);
        }
        rotation.begin(observed.rotation().yaw(), observed.rotation().pitch(),
                farmingYaw, farmingPitch, RotationProfile.BACK,
                duration, rotationEntropy);
        invalidateCapture();
        return rotationDecision(settings.rotateAfterWarped()
                ? "post-rewarp-reversed-back" : "post-rewarp-saved-back");
    }

    private MacroDecision enterRecovery(MacroRecoveryReason reason, String status) {
        recoveryReason = Objects.requireNonNull(reason, "reason");
        state = State.RECOVERY_HANDOFF;
        rotation.clear();
        rowProgress.clear();
        invalidateCapture();
        return MacroDecision.recoveryHandoff(status, reason);
    }

    private MacroSpawnPose validWarpConfig() {
        MacroSpawnPose spawn = settings.spawn().orElse(null);
        RewarpPosition origin = rewarp.origin().orElse(null);
        return spawn == null || origin == null
                || settings.rewarps().stream().noneMatch(origin::equals) ? null : spawn;
    }

    private MacroDecision warpDecision(FarmingContext context, MacroSpawnPose spawn, String status) {
        return new MacroDecision(Set.of(), Optional.empty(),
                Optional.of(new MacroWarpRequest(context.developmentGarden(), spawn)), status);
    }

    private MacroDecision rotationDecision(String status) {
        return new MacroDecision(Set.of(), rotation.pending(), Optional.empty(), status);
    }

    private Evidence waterEvidence(Observed observed, RelativeFrame frame) {
        boolean unknown = false;
        int[][] probes = {{2, -1, 1}, {2, 0, 1}, {-1, -1, 1}, {-1, 0, 1}};
        for (int[] probe : probes) {
            BlockPosition position = frame.blockAt(
                    observed.position().x(), observed.position().y(), observed.position().z(),
                    probe[0], probe[1], probe[2]);
            Observation<BlockStateSnapshot> block = observed.spatial().block(
                    observed.worldEpoch(), position);
            if (!block.isPresent()) {
                unknown = true;
            } else if (WATER.equals(block.get().fluidId())
                    || WATER.equals(block.get().blockId())) {
                return Evidence.PRESENT;
            }
        }
        return unknown ? Evidence.UNKNOWN : Evidence.ABSENT;
    }

    private SpaceStatus wall(
            Observed observed,
            RelativeFrame frame,
            int right,
            int up,
            int forward
    ) {
        BlockPosition position = frame.blockAt(
                observed.position().x(), observed.position().y(), observed.position().z(),
                right, up, forward);
        Observation<BlockStateSnapshot> block = observed.spatial().block(
                observed.worldEpoch(), position);
        if (!block.isPresent() || !block.get().collision().isPresent()) {
            return SpaceStatus.UNKNOWN;
        }
        return block.get().collision().get().boxes().stream().anyMatch(BoxSnapshot::hasPositiveVolume)
                ? SpaceStatus.BLOCKED : SpaceStatus.PASSABLE;
    }

    /** PASSABLE means the route is not blocked by both walls. */
    private static SpaceStatus bothWalls(SpaceStatus first, SpaceStatus second) {
        if (first == SpaceStatus.PASSABLE || second == SpaceStatus.PASSABLE) {
            return SpaceStatus.PASSABLE;
        }
        if (first == SpaceStatus.BLOCKED && second == SpaceStatus.BLOCKED) {
            return SpaceStatus.BLOCKED;
        }
        return SpaceStatus.UNKNOWN;
    }

    /** PASSABLE means every offset 0..7 is known open. */
    private SpaceStatus sideOpen(Observed observed, RelativeFrame frame, int direction) {
        boolean unknown = false;
        for (int distance = 0; distance < 8; distance++) {
            SpaceStatus status = wall(observed, frame, direction * distance, 0, 0);
            if (status == SpaceStatus.BLOCKED) {
                return SpaceStatus.BLOCKED;
            }
            unknown |= status == SpaceStatus.UNKNOWN;
        }
        return unknown ? SpaceStatus.UNKNOWN : SpaceStatus.PASSABLE;
    }

    private Set<BlockPosition> requestedBlocks(PositionSnapshot position, float geometryYaw) {
        Set<BlockPosition> blocks = new HashSet<>();
        addWalkabilityBlocks(blocks, body(position));
        RelativeFrame minus = RelativeFrame.eightWay(geometryYaw - 45.0F);
        RelativeFrame plus = RelativeFrame.eightWay(geometryYaw + 45.0F);
        if (state == State.STARTUP || state == State.NONE) {
            addDirectionBlocks(blocks, position, minus, -1);
            addDirectionBlocks(blocks, position, plus, 1);
        }
        if (state == State.S) {
            blocks.add(plus.blockAt(position.x(), position.y(), position.z(), 0, 0, -1));
            blocks.add(minus.blockAt(position.x(), position.y(), position.z(), 0, 0, -1));
            for (int distance = 0; distance < 8; distance++) {
                blocks.add(plus.blockAt(position.x(), position.y(), position.z(),
                        -distance, 0, 0));
                blocks.add(minus.blockAt(position.x(), position.y(), position.z(),
                        distance, 0, 0));
            }
        }
        return blocks;
    }

    private static void addDirectionBlocks(
            Set<BlockPosition> blocks,
            PositionSnapshot position,
            RelativeFrame frame,
            int sideWall
    ) {
        int[][] probes = {{2, -1, 1}, {2, 0, 1}, {-1, -1, 1}, {-1, 0, 1}};
        for (int[] probe : probes) {
            blocks.add(frame.blockAt(position.x(), position.y(), position.z(),
                    probe[0], probe[1], probe[2]));
        }
        blocks.add(frame.blockAt(position.x(), position.y(), position.z(), 0, 0, 1));
        blocks.add(frame.blockAt(position.x(), position.y(), position.z(), sideWall, 0, 0));
    }

    private Observed consumeCapture(FarmingContext context) {
        if (!context.player().isPresent() || !context.spatial().isPresent()) {
            return null;
        }
        PlayerSnapshot player = context.player().get();
        if (!player.position().isPresent() || !player.rotation().isPresent()
                || !player.motion().isPresent()) {
            return null;
        }
        PositionSnapshot position = player.position().get();
        RotationSnapshot observedRotation = player.rotation().get();
        float geometryYaw = geometryYaw(observedRotation.yaw());
        CaptureAnchor anchor = new CaptureAnchor(
                block(position), body(position), observedRotation.yaw(), geometryYaw,
                state == State.STARTUP ? MacroAngles.closestCardinal(geometryYaw) : cardinalYaw);
        SpatialSnapshot spatial = context.spatial().get();
        CaptureKey key = captureKey(context.worldEpoch(), anchor);
        if (!captures.accepts(key, spatial, anchor.body())) {
            invalidateCapture();
            return null;
        }
        captures.complete();
        return new Observed(context.nowNanos(), context.worldEpoch(), position,
                player.motion().get(), observedRotation, spatial);
    }

    private float geometryYaw(float observedYaw) {
        if (state != State.STARTUP) {
            return farmingYaw;
        }
        return settings.customYaw()
                ? RotationTask.normalizeYaw(settings.customYawLevel())
                : MacroAngles.closestDiagonal(observedYaw);
    }

    private double movementCoordinate(PositionSnapshot position, State movementState) {
        RelativeFrame frame = RelativeFrame.eightWay(farmingYaw);
        return switch (movementState) {
            case A -> -(position.x() * frame.rightX() + position.z() * frame.rightZ());
            case D -> position.x() * frame.rightX() + position.z() * frame.rightZ();
            case S -> -(position.x() * frame.forwardX() + position.z() * frame.forwardZ());
            default -> throw new IllegalStateException("not a movement state: " + movementState);
        };
    }

    private long sampleRotationMillis() {
        return 500L + (long) Math.floor(randomUnit() * 300.0D);
    }

    private long sampleDropRotationMillis() {
        return 400L + (long) Math.floor(randomUnit() * 300.0D);
    }

    private double randomUnit() {
        double value = leafRandom.nextUnit();
        if (!Double.isFinite(value) || value < 0.0D || value >= 1.0D) {
            throw new IllegalStateException("macro random draw must be in [0, 1)");
        }
        return value;
    }

    private void reset(State next) {
        state = next;
        farmingYaw = 0.0F;
        farmingPitch = 0.0F;
        cardinalYaw = 0.0F;
        rotation.clear();
        rowProgress.clear();
        drop.clear();
        rewarp.clear();
        recoveryUntil = 0L;
        recoveryReason = null;
        pausedAt = Long.MIN_VALUE;
        runGeneration = incrementPositive(runGeneration);
        invalidateCapture();
    }

    private void invalidateCapture() {
        captures.invalidate();
    }

    private CaptureKey captureKey(long worldEpoch, CaptureAnchor anchor) {
        return new CaptureKey(runGeneration, captures.generation(), captures.phase(),
                state, worldEpoch, anchor);
    }

    private static BoxSnapshot body(PositionSnapshot position) {
        return new BoxSnapshot(
                position.x() - 0.3D, position.y(), position.z() - 0.3D,
                position.x() + 0.3D, position.y() + 1.8D, position.z() + 0.3D);
    }

    private static BlockPosition block(PositionSnapshot position) {
        return new BlockPosition(
                (int) Math.floor(position.x()), (int) Math.floor(position.y()),
                (int) Math.floor(position.z()));
    }

    private static BoxSnapshot bounds(Set<BlockPosition> blocks) {
        int minX = Integer.MAX_VALUE;
        int minY = Integer.MAX_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxY = Integer.MIN_VALUE;
        int maxZ = Integer.MIN_VALUE;
        for (BlockPosition block : blocks) {
            minX = Math.min(minX, block.x());
            minY = Math.min(minY, block.y());
            minZ = Math.min(minZ, block.z());
            maxX = Math.max(maxX, block.x());
            maxY = Math.max(maxY, block.y());
            maxZ = Math.max(maxZ, block.z());
        }
        return new BoxSnapshot(minX, minY, minZ,
                (double) maxX + 1.0D, (double) maxY + 1.0D, (double) maxZ + 1.0D);
    }

    private static void addWalkabilityBlocks(Set<BlockPosition> blocks, BoxSnapshot body) {
        addBoxBlocks(blocks, body);
        addBoxBlocks(blocks, new BoxSnapshot(
                body.minX(), body.minY() - 1.0D / 1024.0D, body.minZ(),
                body.maxX(), body.minY(), body.maxZ()));
    }

    private static void addBoxBlocks(Set<BlockPosition> blocks, BoxSnapshot box) {
        int minX = (int) Math.floor(box.minX());
        int minY = (int) Math.floor(box.minY());
        int minZ = (int) Math.floor(box.minZ());
        int maxX = (int) Math.ceil(box.maxX()) - 1;
        int maxY = (int) Math.ceil(box.maxY()) - 1;
        int maxZ = (int) Math.ceil(box.maxZ()) - 1;
        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    blocks.add(new BlockPosition(x, y, z));
                }
            }
        }
    }

    private static boolean stationary(MotionSnapshot motion) {
        return Math.abs(motion.x()) <= 0.01D
                && Math.abs(motion.y()) <= 0.01D
                && Math.abs(motion.z()) <= 0.01D;
    }

    private static long elapsed(long now, long then) {
        try {
            return Math.max(0L, Math.subtractExact(now, then));
        } catch (ArithmeticException exception) {
            return Long.MAX_VALUE;
        }
    }

    private static long shift(long value, long delta) {
        return saturatingAdd(value, delta);
    }

    private static long saturatingAdd(long value, long delta) {
        try {
            return Math.addExact(value, delta);
        } catch (ArithmeticException exception) {
            return Long.MAX_VALUE;
        }
    }

    private static long incrementPositive(long value) {
        return value == Long.MAX_VALUE ? 1L : value + 1L;
    }

    private static MacroDecision decision(Set<InputAction> inputs, String status) {
        return new MacroDecision(inputs, Optional.empty(), Optional.empty(), status);
    }

    public enum State {
        STOPPED,
        STARTUP,
        NONE,
        A,
        D,
        S,
        DROPPING,
        REWARP_DWELL,
        REWARPING,
        WARP_LANDING,
        AFTER_WARP,
        POST_REWARP,
        RECOVERY_HANDOFF
    }

    private enum Evidence {
        PRESENT,
        ABSENT,
        UNKNOWN
    }

    private record CaptureAnchor(
            BlockPosition block,
            BoxSnapshot body,
            float currentYaw,
            float geometryYaw,
            float cardinalYaw
    ) {
    }

    private record CaptureKey(
            long runGeneration,
            long captureGeneration,
            long capturePhase,
            State state,
            long worldEpoch,
            CaptureAnchor anchor
    ) {
    }

    private record Observed(
            long nowNanos,
            long worldEpoch,
            PositionSnapshot position,
            MotionSnapshot motion,
            RotationSnapshot rotation,
            SpatialSnapshot spatial
    ) {
    }
}
