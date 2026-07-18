package dev.hylfrd.farmhelper.macro.impl;

import dev.hylfrd.farmhelper.control.RotationTask;
import dev.hylfrd.farmhelper.control.input.InputAction;
import dev.hylfrd.farmhelper.control.rotation.RotationProfile;
import dev.hylfrd.farmhelper.macro.FarmingContext;
import dev.hylfrd.farmhelper.macro.LaneChangeDirection;
import dev.hylfrd.farmhelper.macro.Macro;
import dev.hylfrd.farmhelper.macro.MacroAngles;
import dev.hylfrd.farmhelper.macro.MacroCrop;
import dev.hylfrd.farmhelper.macro.MacroDecision;
import dev.hylfrd.farmhelper.macro.MacroPauseCause;
import dev.hylfrd.farmhelper.macro.MacroRandom;
import dev.hylfrd.farmhelper.macro.MacroRecoveryReason;
import dev.hylfrd.farmhelper.macro.MacroSettings;
import dev.hylfrd.farmhelper.macro.MacroSpawnPose;
import dev.hylfrd.farmhelper.macro.MacroTerminalReason;
import dev.hylfrd.farmhelper.macro.MacroWarpRequest;
import dev.hylfrd.farmhelper.macro.PlayerPosture;
import dev.hylfrd.farmhelper.macro.RowDirection;
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
import dev.hylfrd.farmhelper.runtime.snapshot.RotationSnapshot;
import dev.hylfrd.farmhelper.runtime.spatial.BlockPosition;
import dev.hylfrd.farmhelper.runtime.spatial.BoxSnapshot;
import dev.hylfrd.farmhelper.runtime.spatial.CropBlockKind;
import dev.hylfrd.farmhelper.runtime.spatial.CropObservation;
import dev.hylfrd.farmhelper.runtime.spatial.RelativeFrame;
import dev.hylfrd.farmhelper.runtime.spatial.RewarpPosition;
import dev.hylfrd.farmhelper.runtime.spatial.SpaceStatus;
import dev.hylfrd.farmhelper.runtime.spatial.SpatialCaptureRequest;
import dev.hylfrd.farmhelper.runtime.spatial.SpatialQueries;
import dev.hylfrd.farmhelper.runtime.spatial.SpatialSnapshot;
import dev.hylfrd.farmhelper.runtime.spatial.UpstreamCurrentYawFrame;

import java.util.EnumSet;
import java.util.HashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/** Upstream-parity mode 3 with conservative immutable spatial evidence. */
public final class SShapeMelonPumpkinDefaultMacro implements Macro {
    static final long STARTUP_NANOS = TimeUnit.MILLISECONDS.toNanos(300L);
    static final long PROGRESS_WINDOW_NANOS = TimeUnit.MILLISECONDS.toNanos(500L);
    static final long LANE_TIMEOUT_NANOS = TimeUnit.SECONDS.toNanos(2L);
    static final long WARP_RETRY_NANOS = TimeUnit.SECONDS.toNanos(5L);
    static final long AFTER_WARP_NANOS = TimeUnit.MILLISECONDS.toNanos(1_500L);
    static final long POST_REWARP_NANOS = TimeUnit.MILLISECONDS.toNanos(600L);
    static final double MIN_PROGRESS = 0.05D;
    static final int MAX_NO_PROGRESS_WINDOWS = 3;

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
    private RowDirection rowDirection;
    private LaneChangeDirection laneDirection;
    private float baseYaw;
    private float cardinalYaw;
    private Float farmingYaw;
    private Float farmingPitch;
    private PositionSnapshot laneStart;
    private long stateAt;
    private long laneDwellNanos;
    private long recoveryUntil;
    private MacroRecoveryReason recoveryReason;
    private long pausedAt = Long.MIN_VALUE;
    private long runGeneration;
    private int scanDistance;
    private ScanPhase scanPhase = ScanPhase.RIGHT_CROP;

    public SShapeMelonPumpkinDefaultMacro(MacroSettings settings, MacroRandom random) {
        this(settings, random, random);
    }

    public SShapeMelonPumpkinDefaultMacro(
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
        return "s-shape-melon-pumpkin-default";
    }

    /** Melon and pumpkin use the same non-air Desync evidence rule. */
    @Override
    public Optional<MacroCrop> activeCrop() {
        return state == State.STOPPED
                ? Optional.empty()
                : Optional.of(MacroCrop.MELON);
    }

    @Override
    public void onStart() {
        reset(State.ROW_SELECT, 0L);
    }

    @Override
    public void onStart(long nowNanos) {
        reset(State.STARTUP, nowNanos);
    }

    @Override
    public void onStop() {
        reset(State.STOPPED, 0L);
    }

    @Override
    public void onStop(MacroTerminalReason reason) {
        onStop();
    }

    @Override
    public void onPause(Set<MacroPauseCause> causes, long nowNanos) {
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
            case STARTUP, SWITCHING_LANE, DROPPING ->
                    stateAt = shift(stateAt, suspended);
            case FARMING_LEFT, FARMING_RIGHT -> rowProgress.shift(suspended);
            case REWARP_DWELL, WARP_LANDING -> rewarp.shiftState(suspended);
            case REWARPING -> rewarp.shiftRequest(suspended);
            case AFTER_WARP, POST_REWARP -> recoveryUntil = shift(recoveryUntil, suspended);
            case STOPPED, ROW_SELECT, RECOVERY_HANDOFF -> { }
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
        RotationSnapshot rotation = player.rotation().get();
        CaptureAnchor anchor = new CaptureAnchor(
                block(position), body(position), rotation.yaw(), cardinalYaw);
        CaptureKey key = captureKey(worldEpoch, anchor);
        Optional<SpatialCaptureRequest> reusable = captures.reusable(key);
        if (reusable.isPresent()) {
            return reusable;
        }
        if (captures.reusable(key).isEmpty()) {
            invalidateCapture();
            key = captureKey(worldEpoch, anchor);
        }

        Set<BlockPosition> blocks = requestedBlocks(position, rotation);
        if (blocks.isEmpty()) {
            blocks.add(block(position));
        }
        BoxSnapshot bounds = bounds(blocks);
        return Optional.of(captures.begin(key, worldEpoch, bounds, blocks, anchor.body()));
    }

    @Override
    public MacroDecision tick(FarmingContext context) {
        Objects.requireNonNull(context, "context");
        if (state == State.STOPPED) {
            return MacroDecision.idle("stopped");
        }
        if (!context.inGarden().isPresent()) {
            return MacroDecision.failClosed("garden-unknown");
        }
        if (!context.inGarden().get() && !context.developmentGarden()) {
            return MacroDecision.failClosed("outside-garden");
        }
        if (context.serverResponsiveness() != ServerResponsiveness.RESPONSIVE) {
            return MacroDecision.failClosed(context.serverResponsiveness() == ServerResponsiveness.LAGGING
                    ? "server-lagging" : "server-unknown");
        }
        if (settings.spawn().isEmpty() || settings.rewarps().isEmpty()) {
            return MacroDecision.failClosed("rewarp-config-missing");
        }
        if (!context.posture().isPresent()) {
            return MacroDecision.failClosed(state == State.WARP_LANDING
                    ? "rewarp-posture-unknown" : "player-posture-unknown");
        }
        PlayerPosture posture = context.posture().get();
        if (posture.suffocating() && state != State.WARP_LANDING) {
            return MacroDecision.failClosed("player-suffocating");
        }

        Observed observed = consumeCapture(context);
        if (observed == null) {
            return MacroDecision.failClosed("spatial-unknown-or-stale");
        }

        if (state == State.STARTUP) {
            if (elapsed(context.nowNanos(), stateAt) < STARTUP_NANOS) {
                return MacroDecision.idle("startup");
            }
            baseYaw = settings.customYaw()
                    ? settings.customYawLevel()
                    : MacroAngles.closestDiagonal(observed.rotation().yaw());
            cardinalYaw = MacroAngles.closestCardinal(baseYaw);
            enterRowSelect();
            observed = observed.withCardinalFrame(RelativeFrame.cardinal(cardinalYaw));
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
                return MacroDecision.idle("after-warp");
            }
            state = State.POST_REWARP;
            recoveryUntil = context.nowNanos() + POST_REWARP_NANOS;
            invalidateCapture();
            return MacroDecision.idle("post-rewarp");
        }
        if (state == State.POST_REWARP) {
            if (context.nowNanos() < recoveryUntil) {
                return MacroDecision.idle("post-rewarp");
            }
            return finishPostRewarp(observed);
        }
        if (state == State.DROPPING) {
            return tickDrop(observed, posture);
        }
        if (state == State.ROW_SELECT && rotation.pending().isEmpty()) {
            return selectRow(observed);
        }

        Optional<MacroDecision> rewarp = beginRewarp(context, observed);
        if (rewarp.isPresent()) {
            return rewarp.orElseThrow();
        }
        if (rowDirection != null && drop.shouldDrop(posture, observed.position().y())) {
            state = State.DROPPING;
            stateAt = context.nowNanos();
            rotation.clear();
            invalidateCapture();
            return MacroDecision.failClosed("dropping");
        }

        MacroDecision alignment = align(context, observed);
        if (alignment != null) {
            return alignment;
        }
        return switch (state) {
            case FARMING_LEFT, FARMING_RIGHT -> farmRow(context, observed);
            case SWITCHING_LANE -> switchLane(context, observed);
            case STARTUP, ROW_SELECT, DROPPING, REWARP_DWELL, REWARPING, WARP_LANDING,
                    AFTER_WARP, POST_REWARP, RECOVERY_HANDOFF, STOPPED ->
                    MacroDecision.idle(state.name().toLowerCase());
        };
    }

    public State state() {
        return state;
    }

    public Optional<RowDirection> rowDirection() {
        return Optional.ofNullable(rowDirection);
    }

    public Optional<LaneChangeDirection> laneDirection() {
        return Optional.ofNullable(laneDirection);
    }

    Optional<dev.hylfrd.farmhelper.macro.MacroRotationRequest> pendingRotation() {
        return rotation.pending();
    }

    int scanDistance() {
        return scanDistance;
    }

    ScanPhase scanPhase() {
        return scanPhase;
    }

    private MacroDecision selectRow(Observed observed) {
        return switch (scanPhase) {
            case RIGHT_CROP -> inspectCropScan(observed, RowDirection.RIGHT, ScanPhase.LEFT_CROP);
            case LEFT_CROP -> inspectCropScan(observed, RowDirection.LEFT, ScanPhase.RIGHT_OBSTACLE);
            case RIGHT_OBSTACLE -> inspectObstacleScan(
                    observed, RowDirection.RIGHT, RowDirection.LEFT, ScanPhase.LEFT_OBSTACLE);
            case LEFT_OBSTACLE -> inspectObstacleScan(
                    observed, RowDirection.LEFT, RowDirection.RIGHT, ScanPhase.RIGHT_CROP);
        };
    }

    private MacroDecision inspectCropScan(
            Observed observed,
            RowDirection candidate,
            ScanPhase next
    ) {
        CropStatus crop = cropAt(observed, currentFrame(observed).blockAt(
                observed.position().x(), observed.position().y(), observed.position().z(),
                candidate.sign() * scanDistance, 0, 0));
        if (crop == CropStatus.UNKNOWN) {
            return MacroDecision.failClosed("row-crop-scan-unknown");
        }
        if (crop == CropStatus.FRUIT) {
            return beginInitialRow(candidate, observed);
        }
        advanceScan(next);
        return MacroDecision.idle("row-scan-" + scanDistance + "-" + next.name().toLowerCase());
    }

    private MacroDecision inspectObstacleScan(
            Observed observed,
            RowDirection scanned,
            RowDirection resultWhenBlocked,
            ScanPhase next
    ) {
        BoxSnapshot candidate = body(observed.position()).move(
                observed.cardinalFrame().rightX() * (double) scanned.sign() * scanDistance,
                0.0D,
                observed.cardinalFrame().rightZ() * (double) scanned.sign() * scanDistance);
        SpaceStatus status = SpatialQueries.walkability(
                observed.spatial(), observed.worldEpoch(), candidate);
        if (status == SpaceStatus.UNKNOWN) {
            return MacroDecision.failClosed("row-obstacle-scan-unknown");
        }
        if (status == SpaceStatus.BLOCKED) {
            return beginInitialRow(resultWhenBlocked, observed);
        }
        if (scanned == RowDirection.LEFT) {
            if (scanDistance == SpatialQueries.MAX_END_ROW_BLOCKS - 1) {
                return beginInitialRow(null, observed);
            }
            scanDistance++;
        }
        advanceScan(next);
        return MacroDecision.idle("row-scan-" + scanDistance + "-" + next.name().toLowerCase());
    }

    private MacroDecision beginInitialRow(RowDirection direction, Observed observed) {
        rowDirection = direction;
        drop.arm(observed.position().y());
        rowProgress.begin(observed.nowNanos(),
                rowCoordinate(observed.position(), observed.cardinalFrame(), direction));
        farmingPitch = targetPitch();
        double sideDraw = randomUnit();
        float rowTargetBaseYaw = direction == RowDirection.LEFT
                ? cardinalYaw - 45.0F
                : direction == RowDirection.RIGHT ? cardinalYaw + 45.0F : cardinalYaw;
        farmingYaw = direction == RowDirection.LEFT
                ? rowTargetBaseYaw - (float) (sideDraw * 2.0D)
                : direction == RowDirection.RIGHT
                        ? rowTargetBaseYaw + (float) (sideDraw * 2.0D)
                        : rowTargetBaseYaw + (float) (sideDraw * 2.0D - 1.0D);
        farmingYaw = RotationTask.normalizeYaw(farmingYaw);
        state = direction == RowDirection.LEFT ? State.FARMING_LEFT
                : direction == RowDirection.RIGHT ? State.FARMING_RIGHT : State.ROW_SELECT;
        resetScan();
        if (settings.dontFixAfterWarping()
                && Math.abs(MacroAngles.shortestDelta(observed.rotation().yaw(), baseYaw)) < 0.1F) {
            rotation.clear();
            return direction == null
                    ? MacroDecision.failClosed("row-direction-unresolved")
                    : farmInputs(observed, "farming-" + direction.name().toLowerCase());
        }
        rotation.begin(observed.rotation().yaw(), observed.rotation().pitch(),
                farmingYaw, farmingPitch,
                RotationProfile.EXPO_QUART, sampleRotationMillis(), rotationEntropy);
        invalidateCapture();
        return rotationDecision(direction == null ? "row-direction-unresolved" : "initial-aligning");
    }

    private MacroDecision align(FarmingContext context, Observed observed) {
        if (rotation.pending().isEmpty()) {
            return null;
        }
        return switch (rotation.observe(context)) {
            case NONE, COMPLETED -> null;
            case ACTIVE, UNACKNOWLEDGED, RETRYABLE_CANCELLATION -> rotationDecision("aligning");
            case CANCELLED -> MacroDecision.failClosed("rotation-cancelled");
            case STALE_ACKNOWLEDGEMENT -> MacroDecision.failClosed("rotation-acknowledgement-stale");
        };
    }

    private MacroDecision farmRow(FarmingContext context, Observed observed) {
        CropStatus left = cropAt(observed, observed.cardinalFrame().blockAt(
                observed.position().x(), observed.position().y(), observed.position().z(), -1, 0, 0));
        CropStatus right = cropAt(observed, observed.cardinalFrame().blockAt(
                observed.position().x(), observed.position().y(), observed.position().z(), 1, 0, 0));
        if (left == CropStatus.UNKNOWN || right == CropStatus.UNKNOWN) {
            return MacroDecision.failClosed("adjacent-crop-unknown");
        }
        if (left == CropStatus.FRUIT || right == CropStatus.FRUIT) {
            rowDirection = left == CropStatus.FRUIT ? RowDirection.LEFT : RowDirection.RIGHT;
            state = rowDirection == RowDirection.LEFT ? State.FARMING_LEFT : State.FARMING_RIGHT;
            MacroDecision stalled = observeRowProgress(context, observed);
            return stalled == null
                    ? farmInputs(observed, "farming-" + rowDirection.name().toLowerCase())
                    : stalled;
        }

        SpaceStatus front = walkability(observed, LaneChangeDirection.FORWARD);
        SpaceStatus back = walkability(observed, LaneChangeDirection.BACKWARD);
        if (front == SpaceStatus.UNKNOWN || front == SpaceStatus.BLOCKED && back == SpaceStatus.UNKNOWN) {
            return MacroDecision.failClosed("lane-direction-unknown");
        }
        LaneChangeDirection selected = front == SpaceStatus.PASSABLE
                ? LaneChangeDirection.FORWARD
                : back == SpaceStatus.PASSABLE ? LaneChangeDirection.BACKWARD : null;
        if (selected == null) {
            return MacroDecision.failClosed("lane-direction-blocked");
        }
        if (laneDirection != null && laneDirection != selected) {
            return enterRecovery(MacroRecoveryReason.LANE_BLOCKED, "lane-direction-reversed");
        }
        laneDirection = selected;
        laneDwellNanos = TimeUnit.MILLISECONDS.toNanos(400L + (long) Math.floor(randomUnit() * 200.0D));
        farmingPitch = targetPitch();
        double offset = 0.2D + randomUnit() * 0.4D;
        farmingYaw = RotationTask.normalizeYaw(cardinalYaw
                + (rowDirection == RowDirection.RIGHT ? -(float) offset : (float) offset));
        rotation.begin(observed.rotation().yaw(), observed.rotation().pitch(),
                farmingYaw, farmingPitch, RotationProfile.EXPO_QUART,
                sampleRotationMillis(), rotationEntropy);
        laneStart = observed.position();
        state = State.SWITCHING_LANE;
        stateAt = context.nowNanos();
        invalidateCapture();
        return rotationDecision("lane-cardinal-aligning");
    }

    private MacroDecision switchLane(FarmingContext context, Observed observed) {
        if (elapsed(context.nowNanos(), stateAt) < laneDwellNanos) {
            return MacroDecision.idle("row-change-dwell");
        }
        double displacement = forwardDistance(laneStart, observed.position(), observed.cardinalFrame())
                * laneDirection.sign();
        if (displacement >= 1.0D) {
            rowDirection = rowDirection.opposite();
            drop.arm(observed.position().y());
            rowProgress.begin(context.nowNanos(), rowCoordinate(
                    observed.position(), observed.cardinalFrame(), rowDirection));
            farmingPitch = targetPitch();
            double jitter = randomUnit() * 2.0D;
            farmingYaw = RotationTask.normalizeYaw(cardinalYaw
                    + (rowDirection == RowDirection.RIGHT
                    ? 45.0F + (float) jitter
                    : -45.0F - (float) jitter));
            rotation.begin(observed.rotation().yaw(), observed.rotation().pitch(),
                    farmingYaw, farmingPitch, RotationProfile.EXPO_QUART,
                    sampleRotationMillis(), rotationEntropy);
            state = rowDirection == RowDirection.LEFT ? State.FARMING_LEFT : State.FARMING_RIGHT;
            laneStart = null;
            invalidateCapture();
            return rotationDecision("new-row-aligning");
        }
        if (elapsed(context.nowNanos(), stateAt) >= LANE_TIMEOUT_NANOS) {
            return enterRecovery(MacroRecoveryReason.LANE_STALLED, "lane-stalled");
        }
        SpaceStatus path = walkability(observed, laneDirection);
        if (path == SpaceStatus.UNKNOWN) {
            return MacroDecision.failClosed("lane-path-unknown");
        }
        double velocity = Math.abs(observed.motion().x()) + Math.abs(observed.motion().z());
        if (velocity < 0.15D && path == SpaceStatus.BLOCKED) {
            return MacroDecision.failClosed("lane-obstructed");
        }
        EnumSet<InputAction> inputs = laneDirection == LaneChangeDirection.FORWARD
                ? EnumSet.of(InputAction.FORWARD, InputAction.SPRINT)
                : EnumSet.of(InputAction.BACKWARD);
        if (settings.holdLeftClickWhenChangingRow()) {
            inputs.add(InputAction.ATTACK);
        }
        return decision(inputs, "switching-lane-" + laneDirection.name().toLowerCase());
    }

    private MacroDecision observeRowProgress(FarmingContext context, Observed observed) {
        double coordinate = rowCoordinate(observed.position(), observed.cardinalFrame(), rowDirection);
        return switch (rowProgress.observeWindowed(context.nowNanos(), coordinate)) {
            case WAITING, PROGRESSED -> null;
            case MISSED -> MacroDecision.failClosed(
                    "row-stall-observed-" + rowProgress.misses());
            case STALLED -> enterRecovery(MacroRecoveryReason.ROW_STALLED, "row-stalled");
        };
    }

    private MacroDecision farmInputs(Observed observed, String status) {
        SpaceStatus back = walkability(observed, LaneChangeDirection.BACKWARD);
        if (back == SpaceStatus.UNKNOWN) {
            return MacroDecision.failClosed("back-wall-unknown");
        }
        EnumSet<InputAction> inputs = EnumSet.of(
                rowDirection == RowDirection.LEFT ? InputAction.LEFT : InputAction.RIGHT,
                InputAction.ATTACK);
        if (back == SpaceStatus.BLOCKED) {
            inputs.add(InputAction.FORWARD);
        }
        return decision(inputs, status);
    }

    private MacroDecision tickDrop(Observed observed, PlayerPosture posture) {
        Optional<DropLedger.Landing> landing = drop.observeLanding(
                posture, observed.position().y());
        if (landing.isEmpty()) {
            return MacroDecision.failClosed("dropping");
        }
        rowDirection = null;
        laneStart = null;
        enterRowSelect();
        if (landing.orElseThrow().changedLayer()) {
            laneDirection = null;
            if (settings.rotateAfterDrop()) {
                cardinalYaw = MacroAngles.closestCardinal(cardinalYaw + 180.0F);
                farmingYaw = cardinalYaw;
                farmingPitch = farmingPitch == null ? observed.rotation().pitch() : farmingPitch;
                rotation.begin(observed.rotation().yaw(), observed.rotation().pitch(),
                        farmingYaw, farmingPitch, RotationProfile.EXPO_QUART,
                        sampleRotationMillis(), rotationEntropy);
                return rotationDecision("drop-rotate");
            }
        }
        return MacroDecision.failClosed(
                landing.orElseThrow().changedLayer() ? "drop-complete" : "drop-too-shallow");
    }

    private Optional<MacroDecision> beginRewarp(FarmingContext context, Observed observed) {
        Optional<RewarpPosition> current = settings.rewarps().stream()
                .filter(rewarp -> rewarp.block().equals(block(observed.position())))
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
            clearWarpState();
            enterRowSelect();
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
            laneDirection = null;
            laneStart = null;
            rewarp.confirmed(spawn, context.nowNanos());
            state = State.WARP_LANDING;
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
        recoveryUntil = context.nowNanos() + AFTER_WARP_NANOS;
        invalidateCapture();
        return MacroDecision.failClosed("rewarp-confirmed-plot-"
                + rewarp.confirmedSpawn().map(MacroSpawnPose::plot).orElse(-1));
    }

    private MacroDecision finishPostRewarp(Observed observed) {
        final float targetYaw;
        final float targetPitch;
        if (settings.rotateAfterWarped()) {
            targetPitch = targetPitch();
            double jitter = randomUnit() * 2.0D;
            targetYaw = RotationTask.normalizeYaw(cardinalYaw
                    + (rowDirection == RowDirection.RIGHT
                    ? 45.0F + (float) jitter
                    : rowDirection == RowDirection.LEFT
                            ? -45.0F - (float) jitter
                            : (float) jitter - 1.0F));
        } else if (farmingYaw != null && farmingPitch != null) {
            targetYaw = farmingYaw;
            targetPitch = farmingPitch;
        } else {
            enterRowSelect();
            return MacroDecision.failClosed("farming-rotation-unknown");
        }
        clearWarpState();
        enterRowSelect();
        farmingYaw = targetYaw;
        farmingPitch = targetPitch;
        float yawDistance = Math.abs(MacroAngles.shortestDelta(
                observed.rotation().yaw(), targetYaw));
        float pitchDistance = Math.abs(observed.rotation().pitch() - targetPitch);
        if (settings.rotateAfterWarped()) {
            sampleRotationMillis();
        }
        if (settings.dontFixAfterWarping()
                && Math.hypot(yawDistance, pitchDistance) < 1.0D) {
            rotation.clear();
            return MacroDecision.failClosed("post-rewarp-fix-suppressed");
        }
        long correctionMillis = sampleRotationMillis();
        if (yawDistance > 90.0F) {
            correctionMillis = Math.multiplyExact(correctionMillis, 2L);
        }
        rotation.begin(observed.rotation().yaw(), observed.rotation().pitch(),
                targetYaw, targetPitch, RotationProfile.BACK,
                correctionMillis, rotationEntropy);
        return rotationDecision(settings.rotateAfterWarped()
                ? "post-rewarp-leaf-back" : "post-rewarp-saved-back");
    }

    private MacroDecision enterRecovery(MacroRecoveryReason reason, String status) {
        recoveryReason = Objects.requireNonNull(reason, "reason");
        state = State.RECOVERY_HANDOFF;
        rotation.clear();
        laneStart = null;
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

    private float targetPitch() {
        return settings.customPitch()
                ? settings.customPitchLevel()
                : (float) (47.0D + randomUnit() * 6.0D);
    }

    private long sampleRotationMillis() {
        return 500L + (long) Math.floor(randomUnit() * 300.0D);
    }

    private double randomUnit() {
        double value = leafRandom.nextUnit();
        if (!Double.isFinite(value) || value < 0.0D || value >= 1.0D) {
            throw new IllegalStateException("macro random draw must be in [0, 1)");
        }
        return value;
    }

    private Observed consumeCapture(FarmingContext context) {
        if (!context.player().isPresent() || !context.spatial().isPresent()) {
            return null;
        }
        PlayerSnapshot player = context.player().get();
        if (!player.position().isPresent() || !player.rotation().isPresent() || !player.motion().isPresent()) {
            return null;
        }
        PositionSnapshot position = player.position().get();
        RotationSnapshot rotation = player.rotation().get();
        CaptureAnchor current = new CaptureAnchor(block(position), body(position), rotation.yaw(), cardinalYaw);
        SpatialSnapshot spatial = context.spatial().get();
        CaptureKey key = captureKey(context.worldEpoch(), current);
        if (!captures.accepts(key, spatial, current.body())) {
            invalidateCapture();
            return null;
        }
        captures.complete();
        return new Observed(
                context.nowNanos(), context.worldEpoch(), position, player.motion().get(), rotation,
                RelativeFrame.cardinal(cardinalYaw), spatial);
    }

    private Set<BlockPosition> requestedBlocks(PositionSnapshot position, RotationSnapshot rotation) {
        Set<BlockPosition> blocks = new HashSet<>();
        addBoxBlocks(blocks, body(position));
        RelativeFrame cardinal = RelativeFrame.cardinal(cardinalYaw);
        switch (state) {
            case ROW_SELECT -> {
                RowDirection side = scanPhase == ScanPhase.RIGHT_CROP
                        || scanPhase == ScanPhase.RIGHT_OBSTACLE
                        ? RowDirection.RIGHT : RowDirection.LEFT;
                if (scanPhase == ScanPhase.RIGHT_CROP || scanPhase == ScanPhase.LEFT_CROP) {
                    blocks.add(UpstreamCurrentYawFrame.from(rotation.yaw()).blockAt(
                            position.x(), position.y(), position.z(),
                            side.sign() * scanDistance, 0, 0));
                } else {
                    BoxSnapshot candidate = body(position).move(
                            cardinal.rightX() * (double) side.sign() * scanDistance,
                            0.0D,
                            cardinal.rightZ() * (double) side.sign() * scanDistance);
                    addWalkabilityBlocks(blocks, candidate);
                }
            }
            case FARMING_LEFT, FARMING_RIGHT -> {
                blocks.add(cardinal.blockAt(position.x(), position.y(), position.z(), -1, 0, 0));
                blocks.add(cardinal.blockAt(position.x(), position.y(), position.z(), 1, 0, 0));
                addWalkabilityBlocks(blocks, movedBody(position, cardinal, LaneChangeDirection.FORWARD));
                addWalkabilityBlocks(blocks, movedBody(position, cardinal, LaneChangeDirection.BACKWARD));
            }
            case SWITCHING_LANE -> {
                if (laneDirection != null) {
                    addWalkabilityBlocks(blocks, movedBody(position, cardinal, laneDirection));
                }
            }
            case STARTUP, DROPPING, REWARP_DWELL, REWARPING, WARP_LANDING,
                    AFTER_WARP, POST_REWARP, RECOVERY_HANDOFF, STOPPED -> { }
        }
        return blocks;
    }

    private static BoxSnapshot movedBody(
            PositionSnapshot position,
            RelativeFrame frame,
            LaneChangeDirection direction
    ) {
        return body(position).move(
                frame.forwardX() * (double) direction.sign(), 0.0D,
                frame.forwardZ() * (double) direction.sign());
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

    private static CropStatus cropAt(Observed observed, BlockPosition position) {
        Observation<dev.hylfrd.farmhelper.runtime.spatial.BlockStateSnapshot> block =
                observed.spatial().block(observed.worldEpoch(), position);
        if (!block.isPresent()) {
            return CropStatus.UNKNOWN;
        }
        Observation<CropObservation> crop = CropObservation.observe(block.get());
        if (crop.isUnknown()) {
            return CropStatus.UNKNOWN;
        }
        if (!crop.isPresent()) {
            return CropStatus.ABSENT;
        }
        CropObservation value = crop.get();
        return value.directlyHarvestable()
                && (value.kind() == CropBlockKind.MELON || value.kind() == CropBlockKind.PUMPKIN)
                ? CropStatus.FRUIT : CropStatus.ABSENT;
    }

    private static SpaceStatus walkability(Observed observed, LaneChangeDirection direction) {
        return SpatialQueries.walkability(
                observed.spatial(), observed.worldEpoch(),
                movedBody(observed.position(), observed.cardinalFrame(), direction));
    }

    private static RelativeFrame currentFrame(Observed observed) {
        return UpstreamCurrentYawFrame.from(observed.rotation().yaw());
    }

    private void advanceScan(ScanPhase next) {
        scanPhase = next;
        captures.advancePhase();
    }

    private void enterRowSelect() {
        state = State.ROW_SELECT;
        rowDirection = null;
        laneStart = null;
        resetScan();
    }

    private void resetScan() {
        scanDistance = 0;
        scanPhase = ScanPhase.RIGHT_CROP;
        invalidateCapture();
    }

    private void clearWarpState() {
        rewarp.clear();
    }

    private void reset(State next, long nowNanos) {
        state = next;
        rowDirection = null;
        laneDirection = null;
        baseYaw = 0.0F;
        cardinalYaw = 0.0F;
        farmingYaw = null;
        farmingPitch = null;
        rotation.clear();
        drop.clear();
        laneStart = null;
        stateAt = nowNanos;
        laneDwellNanos = TimeUnit.MILLISECONDS.toNanos(400L);
        rowProgress.clear();
        clearWarpState();
        recoveryUntil = 0L;
        recoveryReason = null;
        pausedAt = Long.MIN_VALUE;
        runGeneration = incrementPositive(runGeneration);
        scanDistance = 0;
        scanPhase = ScanPhase.RIGHT_CROP;
        invalidateCapture();
    }

    private void invalidateCapture() {
        captures.invalidate();
    }

    private CaptureKey captureKey(long worldEpoch, CaptureAnchor anchor) {
        return new CaptureKey(
                runGeneration, captures.generation(), captures.phase(), state,
                scanDistance, scanPhase, worldEpoch, anchor);
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
        return new BoxSnapshot(minX, minY, minZ, (double) maxX + 1.0D,
                (double) maxY + 1.0D, (double) maxZ + 1.0D);
    }

    private static double rowCoordinate(
            PositionSnapshot position,
            RelativeFrame frame,
            RowDirection direction
    ) {
        if (direction == null) {
            return 0.0D;
        }
        return (position.x() * frame.rightX() + position.z() * frame.rightZ()) * direction.sign();
    }

    private static double forwardDistance(
            PositionSnapshot start,
            PositionSnapshot current,
            RelativeFrame frame
    ) {
        if (start == null) {
            return 0.0D;
        }
        return (current.x() - start.x()) * frame.forwardX()
                + (current.z() - start.z()) * frame.forwardZ();
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
        ROW_SELECT,
        FARMING_LEFT,
        FARMING_RIGHT,
        SWITCHING_LANE,
        DROPPING,
        REWARP_DWELL,
        REWARPING,
        WARP_LANDING,
        AFTER_WARP,
        POST_REWARP,
        RECOVERY_HANDOFF
    }

    enum ScanPhase {
        RIGHT_CROP,
        LEFT_CROP,
        RIGHT_OBSTACLE,
        LEFT_OBSTACLE
    }

    private enum CropStatus {
        FRUIT,
        ABSENT,
        UNKNOWN
    }

    private record CaptureAnchor(
            BlockPosition block,
            BoxSnapshot body,
            float currentYaw,
            float cardinalYaw
    ) {
    }

    private record CaptureKey(
            long runGeneration,
            long captureGeneration,
            long capturePhase,
            State state,
            int scanDistance,
            ScanPhase scanPhase,
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
            RelativeFrame cardinalFrame,
            SpatialSnapshot spatial
    ) {
        private Observed withCardinalFrame(RelativeFrame replacement) {
            return new Observed(
                    nowNanos, worldEpoch, position, motion, rotation, replacement, spatial);
        }
    }
}
