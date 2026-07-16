package dev.hylfrd.farmhelper.macro.impl;

import dev.hylfrd.farmhelper.control.input.InputAction;
import dev.hylfrd.farmhelper.macro.FarmingContext;
import dev.hylfrd.farmhelper.macro.Macro;
import dev.hylfrd.farmhelper.macro.MacroDecision;
import dev.hylfrd.farmhelper.macro.MacroRandom;
import dev.hylfrd.farmhelper.macro.MacroSettings;
import dev.hylfrd.farmhelper.macro.MacroSpawnPose;
import dev.hylfrd.farmhelper.macro.MacroPauseCause;
import dev.hylfrd.farmhelper.macro.MacroRecoveryReason;
import dev.hylfrd.farmhelper.macro.MacroTerminalReason;
import dev.hylfrd.farmhelper.macro.MacroWarpRequest;
import dev.hylfrd.farmhelper.macro.CropCompatibility;
import dev.hylfrd.farmhelper.macro.PlayerPosture;
import dev.hylfrd.farmhelper.macro.ServerResponsiveness;
import dev.hylfrd.farmhelper.macro.VerticalCropMode;
import dev.hylfrd.farmhelper.macro.mechanism.CaptureIdentityLedger;
import dev.hylfrd.farmhelper.macro.mechanism.DropLedger;
import dev.hylfrd.farmhelper.macro.mechanism.OwnedRotationLedger;
import dev.hylfrd.farmhelper.macro.mechanism.RewarpLedger;
import dev.hylfrd.farmhelper.macro.mechanism.RowProgressLedger;
import dev.hylfrd.farmhelper.runtime.snapshot.MotionSnapshot;
import dev.hylfrd.farmhelper.runtime.snapshot.Observation;
import dev.hylfrd.farmhelper.runtime.snapshot.PlayerSnapshot;
import dev.hylfrd.farmhelper.runtime.snapshot.PositionSnapshot;
import dev.hylfrd.farmhelper.runtime.snapshot.RotationSnapshot;
import dev.hylfrd.farmhelper.runtime.spatial.BlockPosition;
import dev.hylfrd.farmhelper.runtime.spatial.BoxSnapshot;
import dev.hylfrd.farmhelper.runtime.spatial.CropObservation;
import dev.hylfrd.farmhelper.runtime.spatial.CropBlockKind;
import dev.hylfrd.farmhelper.runtime.spatial.RelativeFrame;
import dev.hylfrd.farmhelper.runtime.spatial.RewarpPosition;
import dev.hylfrd.farmhelper.runtime.spatial.SpaceStatus;
import dev.hylfrd.farmhelper.runtime.spatial.SpatialCaptureRequest;
import dev.hylfrd.farmhelper.runtime.spatial.SpatialQueries;
import dev.hylfrd.farmhelper.runtime.spatial.SpatialScanResult;
import dev.hylfrd.farmhelper.runtime.spatial.SpatialSnapshot;

import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/** Conservative, client-independent implementation of the vertical S-shape farming loop. */
public final class SShapeVerticalCropMacro implements Macro {
    static final long MIN_ROW_PROGRESS_MILLIS = 400L;
    static final long MAX_ROW_PROGRESS_MILLIS_EXCLUSIVE = 600L;
    static final long STARTUP_NANOS = TimeUnit.MILLISECONDS.toNanos(300L);
    static final long LAG_RETRY_NANOS = TimeUnit.MILLISECONDS.toNanos(500L);
    static final long LANE_TIMEOUT_NANOS = TimeUnit.SECONDS.toNanos(2L);
    static final long WARP_RETRY_NANOS = TimeUnit.SECONDS.toNanos(5L);
    static final long AFTER_WARP_NANOS = TimeUnit.MILLISECONDS.toNanos(1_500L);
    static final long POST_REWARP_NANOS = TimeUnit.MILLISECONDS.toNanos(600L);

    private final MacroSettings settings;
    private final MacroRandom leafRandom;
    private final RowProgressLedger rowProgress = new RowProgressLedger(
            LAG_RETRY_NANOS, 0.05D, 3);
    private final DropLedger drop = new DropLedger();
    private final RewarpLedger rewarp = new RewarpLedger();
    private final CaptureIdentityLedger<SpatialScanKey> captures = new CaptureIdentityLedger<>();
    private final OwnedRotationLedger rotation = new OwnedRotationLedger();
    private State state = State.STOPPED;
    private Direction direction;
    private CropBlockKind rowCropKind;
    private long laneDwellNanos;
    private long rotationDurationMillis;
    private PositionSnapshot laneStart;
    private InputAction laneAction = InputAction.FORWARD;
    private long stateAt;
    private long recoveryUntil;
    private MacroRecoveryReason recoveryReason;
    private Float targetYaw;
    private Float targetPitch;
    private Float farmingYaw;
    private Float farmingPitch;
    private long pausedAt = Long.MIN_VALUE;
    private long nextSpatialScanPhase = 1L;
    private long spatialScanWorldEpoch = Long.MIN_VALUE;
    private Direction nextSpatialScanDirection = Direction.RIGHT;
    private ScanAnchor spatialScanAnchor;
    private PendingSpatialScan pendingSpatialScan;
    private ObstacleEvidence rightObstacleEvidence;
    private ObstacleEvidence leftObstacleEvidence;

    public SShapeVerticalCropMacro(MacroSettings settings, MacroRandom random) {
        this(settings, random, random);
    }

    public SShapeVerticalCropMacro(
            MacroSettings settings,
            MacroRandom leafRandom,
            MacroRandom rotationRandom
    ) {
        this.settings = Objects.requireNonNull(settings, "settings").snapshot();
        this.leafRandom = Objects.requireNonNull(leafRandom, "leafRandom");
        Objects.requireNonNull(rotationRandom, "rotationRandom");
    }

    @Override
    public String id() {
        return "s-shape-vertical";
    }

    @Override
    public void onStart() {
        resetRun(State.ALIGNING);
    }

    @Override
    public void onStart(long nowNanos) {
        resetRun(State.STARTUP);
        stateAt = nowNanos;
    }

    @Override
    public void onStop() {
        resetRun(State.STOPPED);
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
    }

    @Override
    public void onResume(long nowNanos) {
        if (pausedAt == Long.MIN_VALUE) {
            return;
        }
        long suspended = elapsed(nowNanos, pausedAt);
        switch (state) {
            case FARMING -> rowProgress.shift(suspended);
            case STARTUP, SWITCHING_LANE, DROPPING ->
                    stateAt = shift(stateAt, suspended);
            case REWARP_DWELL, WARP_LANDING -> rewarp.shiftState(suspended);
            case REWARPING -> rewarp.shiftRequest(suspended);
            case AFTER_WARP, POST_REWARP -> recoveryUntil = shift(recoveryUntil, suspended);
            case RECOVERY_HANDOFF -> { }
            case STOPPED, ALIGNING -> { }
        }
        pausedAt = Long.MIN_VALUE;
        invalidateSpatialScan();
    }

    @Override
    public Optional<SpatialCaptureRequest> spatialRequest(PlayerSnapshot player, long worldEpoch) {
        Objects.requireNonNull(player, "player");
        if (!player.position().isPresent() || !player.rotation().isPresent()) {
            return Optional.empty();
        }
        PositionSnapshot position = player.position().get();
        RotationSnapshot rotation = player.rotation().get();
        RelativeFrame cardinal = RelativeFrame.cardinal(rotation.yaw());
        ScanAnchor anchor = scanAnchor(position, cardinal);
        if (state == State.ALIGNING) {
            if (spatialScanWorldEpoch != worldEpoch
                    || spatialScanAnchor != null && !spatialScanAnchor.equals(anchor)) {
                invalidateSpatialScan();
                spatialScanWorldEpoch = worldEpoch;
            }
            if (spatialScanRoundComplete()) {
                invalidateSpatialScan();
                spatialScanWorldEpoch = worldEpoch;
            }
            spatialScanAnchor = anchor;
            if (pendingSpatialScan != null) {
                return Optional.of(pendingSpatialScan.request());
            }
        }
        Set<BlockPosition> blocks = new HashSet<>();
        addWalkabilityCells(blocks, body(position));
        addWalkabilityCells(blocks, body(position).move(cardinal.forwardX(), 0.0D,
                cardinal.forwardZ()));
        addWalkabilityCells(blocks, body(position).move(-cardinal.forwardX(), 0.0D,
                -cardinal.forwardZ()));
        for (Direction side : Direction.values()) {
            int sign = side == Direction.RIGHT ? 1 : -1;
            addWalkabilityCells(blocks, body(position).move(
                    cardinal.rightX() * sign, 0.0D, cardinal.rightZ() * sign));
            for (CropProbe probe : cropProbes(settings.mode(), sign)) {
                blocks.add(cardinal.blockAt(position.x(), position.y(), position.z(),
                        probe.right(), probe.up(), probe.forward()));
            }
        }
        Direction active = direction;
        if (state == State.ALIGNING) {
            Direction scanDirection = nextSpatialScanDirection;
            int scanSign = scanDirection == Direction.RIGHT ? 1 : -1;
            for (int distance = 1; distance <= 179; distance++) {
                addWalkabilityCells(blocks, body(position).move(
                        cardinal.rightX() * scanSign * (double) distance,
                        0.0D,
                        cardinal.rightZ() * scanSign * (double) distance));
            }
            int anchorY = RelativeFrame.gridAnchorY(position.y());
            if (anchorY > SpatialQueries.GARDEN_VOID_MIN_Y
                    && (long) anchorY + 4L - SpatialQueries.GARDEN_VOID_MIN_Y
                    <= (long) SpatialCaptureRequest.MAX_AXIS_SPAN) {
                for (int side : new int[]{-1, 1}) {
                    for (int y = SpatialQueries.GARDEN_VOID_MIN_Y; y <= anchorY; y++) {
                        blocks.add(cardinal.blockAt(position.x(), y, position.z(), side, 0, 0));
                    }
                }
            }
            long phase = nextSpatialScanPhase;
            SpatialScanKey key = new SpatialScanKey(
                    captures.generation(), phase, scanDirection, anchor);
            SpatialCaptureRequest request = captures.begin(
                    key, worldEpoch, bounds(blocks), blocks, body(position));
            pendingSpatialScan = new PendingSpatialScan(
                    captures.generation(), phase,
                    scanDirection, anchor, request);
            nextSpatialScanPhase = incrementPositive(nextSpatialScanPhase);
            return Optional.of(request);
        } else if (active != null && (state == State.FARMING || state == State.SWITCHING_LANE)) {
            int dx = active == Direction.RIGHT ? cardinal.rightX() : -cardinal.rightX();
            int dz = active == Direction.RIGHT ? cardinal.rightZ() : -cardinal.rightZ();
            for (int distance = 1; distance <= SpatialQueries.MAX_END_ROW_BLOCKS; distance++) {
                addWalkabilityCells(blocks,
                        body(position).move(dx * (double) distance, 0.0D, dz * (double) distance));
            }
        }
        return Optional.of(new SpatialCaptureRequest(worldEpoch, bounds(blocks), blocks));
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
        if (state == State.ALIGNING && context.spatial().isPresent()
                && context.spatial().get().requestToken() != 0L
                && context.spatial().get().worldEpoch() != context.worldEpoch()) {
            invalidateSpatialScan();
            return MacroDecision.failClosed("row-obstacle-scan-stale");
        }
        if (context.spatial().isUnknown()) {
            MacroDecision unknownCapture = consumeUnknownSpatialCapture(context);
            if (unknownCapture != null) {
                return unknownCapture;
            }
        }
        Observed observed = observe(context);
        if (observed == null) {
            return MacroDecision.failClosed("spatial-unknown");
        }
        if (!context.posture().isPresent()) {
            return MacroDecision.failClosed(state == State.WARP_LANDING
                    ? "rewarp-posture-unknown" : "player-posture-unknown");
        }
        PlayerPosture posture = context.posture().get();
        if (posture.suffocating() && state != State.WARP_LANDING) {
            return MacroDecision.failClosed("player-suffocating");
        }
        MacroDecision staleSpatialPhase = consumeSpatialScan(observed);
        if (staleSpatialPhase != null) {
            return staleSpatialPhase;
        }

        if (state == State.STARTUP) {
            if (elapsed(context.nowNanos(), stateAt) < STARTUP_NANOS) {
                return MacroDecision.idle("startup");
            }
            state = State.ALIGNING;
        }
        if (state == State.REWARP_DWELL) {
            return tickRewarpDwell(context, observed);
        }
        if (state == State.REWARPING) {
            return tickRewarp(context, observed);
        }
        if (state == State.WARP_LANDING) {
            return tickWarpLanding(context);
        }
        if (state == State.AFTER_WARP) {
            if (context.nowNanos() < recoveryUntil) {
                return MacroDecision.idle("after-warp");
            }
            state = State.POST_REWARP;
            recoveryUntil = context.nowNanos() + POST_REWARP_NANOS;
            return MacroDecision.idle("post-rewarp");
        }
        if (state == State.POST_REWARP) {
            if (context.nowNanos() < recoveryUntil) {
                return MacroDecision.idle("post-rewarp");
            }
            state = State.ALIGNING;
            if (farmingYaw != null && farmingPitch != null) {
                targetYaw = farmingYaw;
                targetPitch = farmingPitch;
                rotationDurationMillis = sampleRotationDurationMillis(randomUnit());
                rewarp.clear();
                rotation.clear();
            } else {
                return MacroDecision.failClosed("farming-rotation-unknown");
            }
        }
        if (state == State.RECOVERY_HANDOFF) {
            return MacroDecision.recoveryHandoff("recovery-handoff", recoveryReason);
        }
        if (state == State.DROPPING) {
            return tickDrop(observed, posture);
        }

        ensureFarmingTarget(observed);
        Optional<MacroDecision> rewarp = beginRewarp(context, observed.position());
        if (rewarp.isPresent()) {
            return rewarp.orElseThrow();
        }
        if (direction != null && drop.shouldDrop(posture, observed.position().y())) {
            state = State.DROPPING;
            stateAt = context.nowNanos();
            return MacroDecision.failClosed("dropping");
        }

        MacroDecision rotation = align(context, observed);
        if (rotation != null) {
            return rotation;
        }
        return switch (state) {
            case ALIGNING -> chooseDirection(context, observed);
            case FARMING -> farmRow(context, observed);
            case SWITCHING_LANE -> switchLane(context, observed);
            case STARTUP, DROPPING, REWARP_DWELL, REWARPING, WARP_LANDING, AFTER_WARP,
                    POST_REWARP, RECOVERY_HANDOFF, STOPPED ->
                    MacroDecision.idle(state.name().toLowerCase());
        };
    }

    public State state() {
        return state;
    }

    long spatialScanGeneration() {
        return captures.generation();
    }

    boolean spatialScanPending() {
        return pendingSpatialScan != null;
    }

    private MacroDecision align(FarmingContext context, Observed observed) {
        ensureFarmingTarget(observed);
        if (rotation.pending().isPresent()) {
            return switch (rotation.observe(context)) {
                case NONE, COMPLETED -> null;
                case ACTIVE, UNACKNOWLEDGED, RETRYABLE_CANCELLATION ->
                        rotationDecision("aligning");
                case CANCELLED -> MacroDecision.failClosed("rotation-cancelled");
                case STALE_ACKNOWLEDGEMENT ->
                        MacroDecision.failClosed("rotation-acknowledgement-stale");
            };
        }
        if (angleDistance(observed.rotation().yaw(), targetYaw) <= 1.0F
                && Math.abs(observed.rotation().pitch() - targetPitch) <= 1.0F) {
            return null;
        }
        long duration = rotationDurationMillis(rotationDurationMillis,
                angleDistance(observed.rotation().yaw(), targetYaw));
        rotation.beginLegacy(targetYaw, targetPitch, duration);
        return rotationDecision("aligning");
    }

    private MacroDecision rotationDecision(String status) {
        return new MacroDecision(Set.of(), rotation.pending(), Optional.empty(), status);
    }

    private void ensureFarmingTarget(Observed observed) {
        if (farmingYaw != null && farmingPitch != null) {
            if (targetYaw == null || targetPitch == null) {
                targetYaw = farmingYaw;
                targetPitch = farmingPitch;
                rotation.clear();
            }
            return;
        }
        farmingYaw = closestCardinal(observed.rotation().yaw());
        farmingPitch = settings.mode() == VerticalCropMode.COCOA
                ? settings.mode().targetPitch(0.0D)
                : settings.mode().targetPitch(randomUnit());
        targetYaw = farmingYaw;
        targetPitch = farmingPitch;
        rotationDurationMillis = sampleRotationDurationMillis(randomUnit());
    }

    private MacroDecision chooseDirection(FarmingContext context, Observed observed) {
        CropEvidence right = cropEvidence(observed, Direction.RIGHT);
        CropEvidence left = cropEvidence(observed, Direction.LEFT);
        SpaceStatus rightWalkable = sideWalkability(observed, Direction.RIGHT);
        SpaceStatus leftWalkable = sideWalkability(observed, Direction.LEFT);
        if (right.status() == CropStatus.INCOMPATIBLE
                || left.status() == CropStatus.INCOMPATIBLE) {
            return MacroDecision.failClosed("crop-mode-incompatible");
        }
        if (right.status() == CropStatus.UNKNOWN || left.status() == CropStatus.UNKNOWN
                || rightWalkable == SpaceStatus.UNKNOWN || leftWalkable == SpaceStatus.UNKNOWN) {
            return MacroDecision.failClosed("row-direction-unknown");
        }
        if (right.status() == CropStatus.READY && rightWalkable == SpaceStatus.PASSABLE) {
            beginRow(Direction.RIGHT, right.kind(), context, observed);
            return farmInputs(observed, "farming-right");
        }
        if (left.status() == CropStatus.READY && leftWalkable == SpaceStatus.PASSABLE) {
            beginRow(Direction.LEFT, left.kind(), context, observed);
            return farmInputs(observed, "farming-left");
        }
        if (observed.position().y() > SpatialQueries.GARDEN_VOID_MIN_Y) {
            SpaceStatus rightVoid = SpatialQueries.gardenVoid(
                    observed.spatial(), observed.worldEpoch(), sideBlock(observed, Direction.RIGHT));
            SpaceStatus leftVoid = SpatialQueries.gardenVoid(
                    observed.spatial(), observed.worldEpoch(), sideBlock(observed, Direction.LEFT));
            if (rightVoid == SpaceStatus.BLOCKED) {
                beginRow(Direction.LEFT, null, context, observed);
                return farmInputs(observed, "farming-left-void");
            }
            if (leftVoid == SpaceStatus.BLOCKED) {
                beginRow(Direction.RIGHT, null, context, observed);
                return farmInputs(observed, "farming-right-void");
            }
            if (rightVoid == SpaceStatus.UNKNOWN || leftVoid == SpaceStatus.UNKNOWN) {
                return MacroDecision.failClosed("row-void-unknown");
            }
        }
        if (rightObstacleEvidence != null || leftObstacleEvidence != null
                || observed.spatial().requestToken() != 0L) {
            return choosePhasedObstacleDirection(context, observed);
        }
        SpaceStatus rightObstacle = scanDirection(observed, Direction.RIGHT).status();
        if (rightObstacle == SpaceStatus.BLOCKED) {
            beginRow(Direction.LEFT, null, context, observed);
            return farmInputs(observed, "farming-left-obstacle");
        }
        SpaceStatus leftObstacle = scanDirection(observed, Direction.LEFT).status();
        if (leftObstacle == SpaceStatus.BLOCKED) {
            beginRow(Direction.RIGHT, null, context, observed);
            return farmInputs(observed, "farming-right-obstacle");
        }
        return MacroDecision.failClosed("row-direction-unresolved");
    }

    private MacroDecision choosePhasedObstacleDirection(FarmingContext context, Observed observed) {
        if (rightObstacleEvidence == null || leftObstacleEvidence == null) {
            return MacroDecision.failClosed("row-obstacle-scan-pending");
        }
        if (rightObstacleEvidence == ObstacleEvidence.UNKNOWN
                || leftObstacleEvidence == ObstacleEvidence.UNKNOWN) {
            invalidateSpatialScan();
            return MacroDecision.failClosed("row-obstacle-scan-unknown");
        }
        if (rightObstacleEvidence == ObstacleEvidence.BLOCKED) {
            beginRow(Direction.LEFT, null, context, observed);
            return farmInputs(observed, "farming-left-obstacle");
        }
        if (rightObstacleEvidence == ObstacleEvidence.EXHAUSTED
                && leftObstacleEvidence == ObstacleEvidence.BLOCKED) {
            beginRow(Direction.RIGHT, null, context, observed);
            return farmInputs(observed, "farming-right-obstacle");
        }
        if (rightObstacleEvidence == ObstacleEvidence.EXHAUSTED
                && leftObstacleEvidence == ObstacleEvidence.EXHAUSTED) {
            invalidateSpatialScan();
            return MacroDecision.failClosed("row-direction-unresolved");
        }
        invalidateSpatialScan();
        return MacroDecision.failClosed("row-direction-unresolved");
    }

    private static BlockPosition sideBlock(Observed observed, Direction direction) {
        return observed.frame().blockAt(
                observed.position().x(), observed.position().y(), observed.position().z(),
                direction == Direction.RIGHT ? 1 : -1, 0, 0);
    }

    private static SpatialScanResult scanDirection(Observed observed, Direction direction) {
        int sign = direction == Direction.RIGHT ? 1 : -1;
        int dx = observed.frame().rightX() * sign;
        int dz = observed.frame().rightZ() * sign;
        RelativeFrame movement = new RelativeFrame(-dz, dx, dx, dz);
        return SpatialQueries.scanForwardUntilBlocked(
                observed.spatial(), observed.worldEpoch(), observed.spatial().playerBox(),
                movement, 179);
    }

    private MacroDecision consumeSpatialScan(Observed observed) {
        if (state != State.ALIGNING) {
            return null;
        }
        SpatialSnapshot snapshot = observed.spatial();
        if (snapshot.requestToken() == 0L) {
            return null;
        }
        PendingSpatialScan pending = pendingSpatialScan;
        ScanAnchor currentAnchor = scanAnchor(observed.position(), observed.frame());
        if (pending == null
                || pending.generation() != captures.generation()
                || pending.phase() != (pending.direction() == Direction.RIGHT ? 1L : 2L)
                || !captures.accepts(new SpatialScanKey(
                        pending.generation(), pending.phase(), pending.direction(), pending.anchor()),
                        snapshot)
                || spatialScanAnchor == null
                || !spatialScanAnchor.equals(currentAnchor)
                || !pending.anchor().equals(spatialScanAnchor)
                || !pending.anchor().footprint().equals(bodyFootprint(snapshot.playerBox()))) {
            invalidateSpatialScan();
            return MacroDecision.failClosed("row-obstacle-scan-stale");
        }
        SpatialScanResult result = scanDirection(observed, pending.direction());
        ObstacleEvidence evidence;
        if (result.status() == SpaceStatus.BLOCKED) {
            evidence = ObstacleEvidence.BLOCKED;
        } else if (result.inspectedBlocks().size() == 179
                && walkabilityAtDistance(observed, pending.direction(), 179)
                == SpaceStatus.PASSABLE) {
            evidence = ObstacleEvidence.EXHAUSTED;
        } else {
            evidence = ObstacleEvidence.UNKNOWN;
        }
        recordObstacleEvidence(pending, evidence);
        return null;
    }

    private MacroDecision consumeUnknownSpatialCapture(FarmingContext context) {
        PendingSpatialScan pending = pendingSpatialScan;
        if (state != State.ALIGNING || pending == null) {
            return null;
        }
        if (!context.player().isPresent()
                || !context.player().get().position().isPresent()
                || !context.player().get().rotation().isPresent()) {
            invalidateSpatialScan();
            return MacroDecision.failClosed("spatial-unknown");
        }
        PositionSnapshot position = context.player().get().position().get();
        RelativeFrame frame = RelativeFrame.cardinal(
                context.player().get().rotation().get().yaw());
        ScanAnchor currentAnchor = scanAnchor(position, frame);
        if (pending.generation() != captures.generation()
                || pending.phase() != (pending.direction() == Direction.RIGHT ? 1L : 2L)
                || pending.request().worldEpoch() != context.worldEpoch()
                || spatialScanAnchor == null
                || !spatialScanAnchor.equals(currentAnchor)
                || !pending.anchor().equals(spatialScanAnchor)) {
            invalidateSpatialScan();
            return MacroDecision.failClosed("row-obstacle-scan-stale");
        }
        recordObstacleEvidence(pending, ObstacleEvidence.UNKNOWN);
        if (spatialScanRoundComplete()) {
            invalidateSpatialScan();
            return MacroDecision.failClosed("row-obstacle-scan-unknown");
        }
        return MacroDecision.failClosed("spatial-unknown");
    }

    private void recordObstacleEvidence(
            PendingSpatialScan pending,
            ObstacleEvidence evidence
    ) {
        if (pending.direction() == Direction.RIGHT) {
            rightObstacleEvidence = evidence;
        } else {
            leftObstacleEvidence = evidence;
        }
        pendingSpatialScan = null;
        captures.complete();
        nextSpatialScanDirection = pending.direction().opposite();
    }

    private static SpaceStatus walkabilityAtDistance(
            Observed observed,
            Direction direction,
            int distance
    ) {
        int sign = direction == Direction.RIGHT ? 1 : -1;
        BoxSnapshot target = observed.spatial().playerBox().move(
                observed.frame().rightX() * sign * (double) distance,
                0.0D,
                observed.frame().rightZ() * sign * (double) distance);
        return SpatialQueries.walkability(
                observed.spatial(), observed.worldEpoch(), target);
    }

    private MacroDecision farmRow(FarmingContext context, Observed observed) {
        SpaceStatus next = nextStep(observed);
        CropEvidence crop = cropEvidence(observed, direction);
        if (crop.status() == CropStatus.INCOMPATIBLE) {
            return MacroDecision.failClosed("crop-mode-incompatible");
        }
        if (next == SpaceStatus.UNKNOWN || crop.status() == CropStatus.UNKNOWN) {
            return MacroDecision.failClosed("row-spatial-unknown");
        }
        if (next == SpaceStatus.BLOCKED) {
            return beginLaneChange(context, observed);
        }
        if (crop.status() == CropStatus.READY) {
            rowCropKind = crop.kind();
        }

        double coordinate = movementCoordinate(observed.position(), observed.frame(), direction);
        RowProgressLedger.Result progress =
                rowProgress.observeContinuous(context.nowNanos(), coordinate);
        if (progress == RowProgressLedger.Result.STALLED) {
            return enterRecoveryHandoff(MacroRecoveryReason.ROW_STALLED, "row-stalled");
        }
        if (progress == RowProgressLedger.Result.MISSED) {
            return MacroDecision.failClosed(
                    "row-stall-observed-" + rowProgress.misses());
        }
        return farmInputs(observed, direction == Direction.RIGHT ? "farming-right" : "farming-left");
    }

    private MacroDecision switchLane(FarmingContext context, Observed observed) {
        if (elapsed(context.nowNanos(), stateAt) < laneDwellNanos) {
            return MacroDecision.idle("row-change-dwell");
        }
        double forward = forwardDistance(laneStart, observed.position(), observed.frame())
                * (laneAction == InputAction.FORWARD ? 1.0D : -1.0D);
        if (forward >= 1.0D) {
            Direction next = direction.opposite();
            beginRow(next, null, context, observed);
            return farmInputs(observed, next == Direction.RIGHT ? "farming-right" : "farming-left");
        }
        if (elapsed(context.nowNanos(), stateAt) > LANE_TIMEOUT_NANOS) {
            return enterRecoveryHandoff(MacroRecoveryReason.LANE_STALLED, "lane-stalled");
        }
        return laneInputs("switching-lane");
    }

    private MacroDecision tickDrop(Observed observed, PlayerPosture posture) {
        if (drop.observeLanding(posture, observed.position().y()).isPresent()) {
            clearLaneState();
            state = State.ALIGNING;
            return MacroDecision.idle("drop-complete");
        }
        return MacroDecision.failClosed("dropping");
    }

    private Optional<MacroDecision> beginRewarp(FarmingContext context, PositionSnapshot position) {
        BlockPosition block = block(position);
        Optional<RewarpPosition> current = settings.rewarps().stream()
                .filter(rewarp -> rewarp.block().equals(block))
                .findFirst();
        if (current.isEmpty()) {
            return Optional.empty();
        }
        if (!stationary(context.player().get().motion().get())) {
            return Optional.of(MacroDecision.failClosed("rewarp-moving"));
        }
        invalidateSpatialScan();
        state = State.REWARP_DWELL;
        rewarp.begin(current.orElseThrow(), context.nowNanos(), TimeUnit.MILLISECONDS.toNanos(
                400L + (long) Math.floor(randomUnit() * 350.0D)));
        rotation.clear();
        return Optional.of(MacroDecision.idle("rewarp-dwell"));
    }

    private MacroDecision tickRewarpDwell(FarmingContext context, Observed observed) {
        MacroSpawnPose spawn = settings.spawn().orElse(null);
        RewarpPosition origin = rewarp.origin().orElse(null);
        if (spawn == null || origin == null
                || settings.rewarps().stream().noneMatch(origin::equals)) {
            return MacroDecision.failClosed("rewarp-config-lost");
        }
        if (!block(observed.position()).equals(origin.block())
                || !stationary(observed.motion())) {
            clearWarpState();
            state = State.ALIGNING;
            return MacroDecision.failClosed("rewarp-dwell-ineligible");
        }
        if (!rewarp.dwellComplete(context.nowNanos())) {
            return MacroDecision.idle("rewarp-dwell");
        }
        state = State.REWARPING;
        rewarp.requested(context.nowNanos());
        return warpDecision(context, spawn, "rewarp-request");
    }

    private MacroDecision tickRewarp(FarmingContext context, Observed observed) {
        MacroSpawnPose spawn = settings.spawn().orElse(null);
        RewarpPosition origin = rewarp.origin().orElse(null);
        if (spawn == null || origin == null
                || settings.rewarps().stream().noneMatch(origin::equals)) {
            return MacroDecision.failClosed("rewarp-config-lost");
        }
        BlockPosition current = block(observed.position());
        if (origin.squaredDistance(current) > 2.0D || spawn.block().equals(current)) {
            clearLaneState();
            rewarp.confirmed(spawn, context.nowNanos());
            state = State.WARP_LANDING;
            return tickWarpLanding(context);
        }
        if (!rewarp.retryDue(context.nowNanos(), WARP_RETRY_NANOS)) {
            return MacroDecision.idle("rewarp-waiting");
        }
        rewarp.requested(context.nowNanos());
        return warpDecision(context, spawn, "rewarp-retry-" + rewarp.attempts());
    }

    private MacroDecision tickWarpLanding(FarmingContext context) {
        if (!context.posture().isPresent()) {
            return MacroDecision.failClosed("rewarp-posture-unknown");
        }
        PlayerPosture posture = context.posture().get();
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
        return MacroDecision.idle("rewarp-confirmed plot="
                + rewarp.confirmedSpawn().map(MacroSpawnPose::plot).orElse(-1));
    }

    private MacroDecision warpDecision(FarmingContext context, MacroSpawnPose spawn, String status) {
        return new MacroDecision(Set.of(), Optional.empty(),
                Optional.of(new MacroWarpRequest(context.developmentGarden(), spawn)), status);
    }

    private CropEvidence cropEvidence(Observed observed, Direction side) {
        int sign = side == Direction.RIGHT ? 1 : -1;
        VerticalCropMode mode = settings.mode();
        boolean unknown = false;
        boolean incompatible = false;
        CropBlockKind readyKind = null;
        for (CropProbe probe : cropProbes(mode, sign)) {
            BlockPosition position = observed.frame().blockAt(
                    observed.position().x(), observed.position().y(), observed.position().z(),
                    probe.right(), probe.up(), probe.forward());
            Observation<dev.hylfrd.farmhelper.runtime.spatial.BlockStateSnapshot> block =
                    observed.spatial().block(observed.worldEpoch(), position);
            if (!block.isPresent()) {
                unknown = true;
                continue;
            }
            Observation<CropObservation> crop = CropObservation.observe(block.get());
            if (!crop.isPresent()) {
                unknown |= crop.isUnknown();
                continue;
            }
            CropObservation value = crop.get();
            if (value.mature() && value.directlyHarvestable()) {
                if (mode.compatibility(value.kind()) == CropCompatibility.COMPATIBLE) {
                    readyKind = value.kind();
                } else {
                    incompatible = true;
                }
            }
        }
        if (incompatible) {
            return new CropEvidence(CropStatus.INCOMPATIBLE, null);
        }
        if (readyKind != null) {
            return new CropEvidence(CropStatus.READY, readyKind);
        }
        return new CropEvidence(unknown ? CropStatus.UNKNOWN : CropStatus.ABSENT, null);
    }

    private static List<CropProbe> cropProbes(VerticalCropMode mode, int sign) {
        if (mode == VerticalCropMode.CACTUS || mode == VerticalCropMode.SUNTZU) {
            return List.of(
                    new CropProbe(sign, 1, 1),
                    new CropProbe(sign, 1, 2),
                    new CropProbe(sign * 2, 1, 1),
                    new CropProbe(sign * 2, 1, 2));
        }
        if (mode == VerticalCropMode.COCOA) {
            return List.of(new CropProbe(sign, 2, 0), new CropProbe(sign, 3, 0));
        }
        return List.of(new CropProbe(sign, 0, 1), new CropProbe(sign, 1, 1));
    }

    private static SpaceStatus sideWalkability(Observed observed, Direction direction) {
        int sign = direction == Direction.RIGHT ? 1 : -1;
        BoxSnapshot next = observed.spatial().playerBox().move(
                observed.frame().rightX() * sign,
                0.0D,
                observed.frame().rightZ() * sign);
        return SpatialQueries.walkability(observed.spatial(), observed.worldEpoch(), next);
    }

    private SpaceStatus nextStep(Observed observed) {
        int sign = direction == Direction.RIGHT ? 1 : -1;
        BoxSnapshot next = observed.spatial().playerBox().move(
                observed.frame().rightX() * sign,
                0.0D,
                observed.frame().rightZ() * sign);
        return SpatialQueries.walkability(observed.spatial(), observed.worldEpoch(), next);
    }

    private void beginRow(
            Direction next,
            CropBlockKind cropKind,
            FarmingContext context,
            Observed observed
    ) {
        invalidateSpatialScan();
        direction = next;
        rowCropKind = cropKind;
        state = State.FARMING;
        drop.arm(observed.position().y());
        rowProgress.begin(context.nowNanos(),
                movementCoordinate(observed.position(), observed.frame(), next));
    }

    private MacroDecision farmInputs(Observed observed, String status) {
        EnumSet<InputAction> inputs = EnumSet.of(InputAction.ATTACK,
                direction == Direction.RIGHT ? InputAction.RIGHT : InputAction.LEFT);
        ForwardPolicy forward = shouldWalkForwards(observed);
        if (forward == ForwardPolicy.UNKNOWN) {
            return MacroDecision.failClosed("forward-policy-unknown");
        }
        if (forward == ForwardPolicy.HOLD) {
            inputs.add(InputAction.FORWARD);
        }
        return decision(inputs, status);
    }

    private MacroDecision laneInputs(String status) {
        EnumSet<InputAction> inputs = EnumSet.of(laneAction);
        if (laneAction == InputAction.FORWARD) {
            inputs.add(InputAction.SPRINT);
        }
        if (settings.holdLeftClickWhenChangingRow()) {
            inputs.add(InputAction.ATTACK);
        }
        return decision(inputs, status);
    }

    private ForwardPolicy shouldWalkForwards(Observed observed) {
        if (settings.alwaysHoldW()) {
            return ForwardPolicy.HOLD;
        }
        boolean cactus = rowCropKind == CropBlockKind.CACTUS
                || settings.mode() == VerticalCropMode.CACTUS
                || settings.mode() == VerticalCropMode.SUNTZU;
        boolean cocoa = rowCropKind == CropBlockKind.COCOA
                || settings.mode() == VerticalCropMode.COCOA;
        boolean pumpkinOrMelon = rowCropKind == CropBlockKind.PUMPKIN
                || rowCropKind == CropBlockKind.MELON
                || settings.mode() == VerticalCropMode.PUMPKIN_MELON;
        if (cactus || cocoa || pumpkinOrMelon && settings.mode() != VerticalCropMode.MELONGKINGDE) {
            return ForwardPolicy.RELEASE;
        }
        BoxSnapshot front = observed.spatial().playerBox().move(
                observed.frame().forwardX(), 0.0D, observed.frame().forwardZ());
        SpaceStatus frontSpace = SpatialQueries.clearance(
                observed.spatial(), observed.worldEpoch(), front);
        if (frontSpace == SpaceStatus.UNKNOWN) {
            return ForwardPolicy.UNKNOWN;
        }
        if (frontSpace == SpaceStatus.PASSABLE) {
            return ForwardPolicy.RELEASE;
        }
        return inForwardAssistBand(observed.position(), farmingYaw == null
                ? observed.rotation().yaw() : farmingYaw)
                ? ForwardPolicy.HOLD : ForwardPolicy.RELEASE;
    }

    static boolean inForwardAssistBand(PositionSnapshot position, float yaw) {
        int cardinal = Math.floorMod(Math.round(yaw / 90.0F), 4);
        double coordinate = cardinal == 0 || cardinal == 2
                ? position.z() % 1.0D : position.x() % 1.0D;
        return switch (cardinal) {
            case 0, 3 -> coordinate > -0.9D && coordinate < -0.35D
                    || coordinate > 0.1D && coordinate < 0.65D;
            case 1, 2 -> coordinate > -0.65D && coordinate < -0.1D
                    || coordinate > 0.35D && coordinate < 0.9D;
            default -> false;
        };
    }

    private MacroDecision beginLaneChange(FarmingContext context, Observed observed) {
        SpaceStatus front = laneWalkability(observed, 1);
        if (front == SpaceStatus.PASSABLE) {
            laneAction = InputAction.FORWARD;
        } else if (settings.mode().cactusMode()) {
            return front == SpaceStatus.UNKNOWN
                    ? MacroDecision.failClosed("cactus-lane-unknown")
                    : enterRecoveryHandoff(MacroRecoveryReason.LANE_BLOCKED,
                    "cactus-lane-blocked");
        } else {
            SpaceStatus back = laneWalkability(observed, -1);
            if (front == SpaceStatus.UNKNOWN || back == SpaceStatus.UNKNOWN) {
                return MacroDecision.failClosed("lane-direction-unknown");
            }
            if (back != SpaceStatus.PASSABLE) {
                return enterRecoveryHandoff(
                        MacroRecoveryReason.LANE_BLOCKED, "lane-direction-blocked");
            }
            laneAction = InputAction.BACKWARD;
        }
        state = State.SWITCHING_LANE;
        laneStart = observed.position();
        stateAt = context.nowNanos();
        laneDwellNanos = TimeUnit.MILLISECONDS.toNanos(MIN_ROW_PROGRESS_MILLIS
                + (long) Math.floor(randomUnit()
                * (MAX_ROW_PROGRESS_MILLIS_EXCLUSIVE - MIN_ROW_PROGRESS_MILLIS)));
        return MacroDecision.idle("row-change-dwell");
    }

    private static SpaceStatus laneWalkability(Observed observed, int sign) {
        BoxSnapshot next = observed.spatial().playerBox().move(
                observed.frame().forwardX() * sign,
                0.0D,
                observed.frame().forwardZ() * sign);
        return SpatialQueries.walkability(observed.spatial(), observed.worldEpoch(), next);
    }

    private MacroDecision enterRecoveryHandoff(MacroRecoveryReason reason, String status) {
        clearLaneState();
        recoveryReason = Objects.requireNonNull(reason, "reason");
        state = State.RECOVERY_HANDOFF;
        return MacroDecision.recoveryHandoff(status, reason);
    }

    private void clearWarpState() {
        rewarp.clear();
        stateAt = 0L;
    }

    private static boolean stationary(MotionSnapshot motion) {
        return Math.abs(motion.x()) <= 0.01D
                && Math.abs(motion.y()) <= 0.01D
                && Math.abs(motion.z()) <= 0.01D;
    }

    private void clearLaneState() {
        invalidateSpatialScan();
        direction = null;
        rowCropKind = null;
        drop.clear();
        rowProgress.clear();
        laneStart = null;
        laneAction = InputAction.FORWARD;
        clearWarpState();
        laneDwellNanos = TimeUnit.MILLISECONDS.toNanos(MIN_ROW_PROGRESS_MILLIS);
    }

    private static MacroDecision decision(Set<InputAction> inputs, String status) {
        return new MacroDecision(inputs, Optional.empty(), Optional.empty(), status);
    }

    private static Observed observe(FarmingContext context) {
        if (!context.player().isPresent() || !context.spatial().isPresent()) {
            return null;
        }
        PlayerSnapshot player = context.player().get();
        if (!player.position().isPresent() || !player.rotation().isPresent() || !player.motion().isPresent()) {
            return null;
        }
        SpatialSnapshot spatial = context.spatial().get();
        if (spatial.worldEpoch() != context.worldEpoch()) {
            return null;
        }
        RotationSnapshot rotation = player.rotation().get();
        return new Observed(
                context.worldEpoch(), player.position().get(), player.motion().get(), rotation,
                RelativeFrame.cardinal(rotation.yaw()), spatial);
    }

    private static BoxSnapshot body(PositionSnapshot position) {
        return new BoxSnapshot(position.x() - 0.3D, position.y(), position.z() - 0.3D,
                position.x() + 0.3D, position.y() + 1.8D, position.z() + 0.3D);
    }

    private static ScanAnchor scanAnchor(PositionSnapshot position, RelativeFrame frame) {
        BoxSnapshot body = body(position);
        BlockPosition grid = new BlockPosition(
                (int) Math.floor(position.x()), RelativeFrame.gridAnchorY(position.y()),
                (int) Math.floor(position.z()));
        return new ScanAnchor(grid, frame, bodyFootprint(body));
    }

    private static BodyFootprint bodyFootprint(BoxSnapshot body) {
        return new BodyFootprint(
                (int) Math.floor(body.minX()),
                (int) Math.floor(Math.nextDown(body.maxX())),
                (int) Math.floor(body.minY() - 0.01D),
                (int) Math.floor(Math.nextDown(body.maxY())),
                (int) Math.floor(body.minZ()),
                (int) Math.floor(Math.nextDown(body.maxZ())));
    }

    /** Adds every clearance and support-probe cell read by {@link SpatialQueries#walkability}. */
    private static void addWalkabilityCells(Set<BlockPosition> blocks, BoxSnapshot body) {
        int minX = (int) Math.floor(body.minX());
        int maxX = (int) Math.floor(Math.nextDown(body.maxX()));
        int minY = (int) Math.floor(body.minY() - 0.01D);
        int maxY = (int) Math.floor(Math.nextDown(body.maxY()));
        int minZ = (int) Math.floor(body.minZ());
        int maxZ = (int) Math.floor(Math.nextDown(body.maxZ()));
        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    blocks.add(new BlockPosition(x, y, z));
                }
            }
        }
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

    private static BlockPosition block(PositionSnapshot position) {
        return new BlockPosition((int) Math.floor(position.x()), (int) Math.floor(position.y()),
                (int) Math.floor(position.z()));
    }

    private static float closestCardinal(float yaw) {
        return Math.round(yaw / 90.0F) * 90.0F;
    }

    private static float angleDistance(float first, float second) {
        float difference = (first - second) % 360.0F;
        if (difference > 180.0F) {
            difference -= 360.0F;
        } else if (difference < -180.0F) {
            difference += 360.0F;
        }
        return Math.abs(difference);
    }

    static long rotationDurationMillis(long sampledMillis, float yawDelta) {
        if (sampledMillis < 500L || sampledMillis > 799L || !Float.isFinite(yawDelta)
                || yawDelta < 0.0F) {
            throw new IllegalArgumentException("invalid rotation timing input");
        }
        return yawDelta > 90.0F ? sampledMillis * 2L : sampledMillis;
    }

    static long sampleRotationDurationMillis(double randomUnit) {
        if (!Double.isFinite(randomUnit) || randomUnit < 0.0D || randomUnit >= 1.0D) {
            throw new IllegalArgumentException("randomUnit must be in [0, 1)");
        }
        return 500L + (long) Math.floor(randomUnit * 300.0D);
    }

    private static double movementCoordinate(
            PositionSnapshot position,
            RelativeFrame frame,
            Direction direction
    ) {
        int sign = direction == Direction.RIGHT ? 1 : -1;
        return (position.x() * frame.rightX() + position.z() * frame.rightZ()) * sign;
    }

    private static double forwardDistance(
            PositionSnapshot start,
            PositionSnapshot current,
            RelativeFrame frame
    ) {
        return (current.x() - start.x()) * frame.forwardX()
                + (current.z() - start.z()) * frame.forwardZ();
    }

    private static long elapsed(long now, long then) {
        try {
            return Math.max(0L, Math.subtractExact(now, then));
        } catch (ArithmeticException exception) {
            return Long.MAX_VALUE;
        }
    }

    private static long shift(long value, long delta) {
        if (delta == 0L) {
            return value;
        }
        try {
            return Math.addExact(value, delta);
        } catch (ArithmeticException exception) {
            return Long.MAX_VALUE;
        }
    }

    private double randomUnit() {
        double value = leafRandom.nextUnit();
        if (!Double.isFinite(value) || value < 0.0D || value >= 1.0D) {
            throw new IllegalStateException("macro random draw must be in [0, 1)");
        }
        return value;
    }

    private void resetRun(State next) {
        state = next;
        direction = null;
        rowCropKind = null;
        drop.clear();
        rowProgress.clear();
        laneDwellNanos = TimeUnit.MILLISECONDS.toNanos(MIN_ROW_PROGRESS_MILLIS);
        rewarp.clear();
        rotationDurationMillis = 500L;
        rotation.clear();
        laneStart = null;
        laneAction = InputAction.FORWARD;
        stateAt = 0L;
        recoveryUntil = 0L;
        recoveryReason = null;
        targetYaw = null;
        targetPitch = null;
        farmingYaw = null;
        farmingPitch = null;
        pausedAt = Long.MIN_VALUE;
        invalidateSpatialScan();
    }

    private void invalidateSpatialScan() {
        captures.invalidate();
        nextSpatialScanPhase = 1L;
        spatialScanWorldEpoch = Long.MIN_VALUE;
        nextSpatialScanDirection = Direction.RIGHT;
        spatialScanAnchor = null;
        pendingSpatialScan = null;
        rightObstacleEvidence = null;
        leftObstacleEvidence = null;
    }

    private boolean spatialScanRoundComplete() {
        return rightObstacleEvidence != null && leftObstacleEvidence != null;
    }

    private static long incrementPositive(long value) {
        return value == Long.MAX_VALUE ? 1L : value + 1L;
    }

    public enum State {
        STOPPED,
        STARTUP,
        ALIGNING,
        FARMING,
        SWITCHING_LANE,
        DROPPING,
        REWARP_DWELL,
        REWARPING,
        WARP_LANDING,
        AFTER_WARP,
        POST_REWARP,
        RECOVERY_HANDOFF
    }

    private enum Direction {
        LEFT,
        RIGHT;

        private Direction opposite() {
            return this == LEFT ? RIGHT : LEFT;
        }
    }

    private enum CropStatus {
        READY,
        INCOMPATIBLE,
        ABSENT,
        UNKNOWN
    }

    private enum ForwardPolicy {
        HOLD,
        RELEASE,
        UNKNOWN
    }

    private record CropEvidence(CropStatus status, CropBlockKind kind) {
        private CropEvidence {
            Objects.requireNonNull(status, "status");
            if (status == CropStatus.READY && kind == null) {
                throw new IllegalArgumentException("ready crop evidence requires a kind");
            }
            if (status != CropStatus.READY && kind != null) {
                throw new IllegalArgumentException("non-ready crop evidence must not carry a kind");
            }
        }
    }

    private record CropProbe(int right, int up, int forward) {
    }

    private enum ObstacleEvidence {
        BLOCKED,
        EXHAUSTED,
        UNKNOWN
    }

    private record PendingSpatialScan(
            long generation,
            long phase,
            Direction direction,
            ScanAnchor anchor,
            SpatialCaptureRequest request
    ) {
        private PendingSpatialScan {
            if (generation <= 0L || phase <= 0L) {
                throw new IllegalArgumentException("scan generation and phase must be positive");
            }
            Objects.requireNonNull(direction, "direction");
            Objects.requireNonNull(anchor, "anchor");
            Objects.requireNonNull(request, "request");
        }
    }

    private record SpatialScanKey(
            long generation,
            long phase,
            Direction direction,
            ScanAnchor anchor
    ) {
    }

    private record ScanAnchor(
            BlockPosition grid,
            RelativeFrame frame,
            BodyFootprint footprint
    ) {
        private ScanAnchor {
            Objects.requireNonNull(grid, "grid");
            Objects.requireNonNull(frame, "frame");
            Objects.requireNonNull(footprint, "footprint");
        }
    }

    private record BodyFootprint(
            int minX,
            int maxX,
            int minY,
            int maxY,
            int minZ,
            int maxZ
    ) {
    }

    private record Observed(
            long worldEpoch,
            PositionSnapshot position,
            MotionSnapshot motion,
            RotationSnapshot rotation,
            RelativeFrame frame,
            SpatialSnapshot spatial
    ) {
    }
}
