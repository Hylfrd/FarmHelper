package dev.hylfrd.farmhelper.macro.impl;

import dev.hylfrd.farmhelper.control.input.InputAction;
import dev.hylfrd.farmhelper.macro.FarmingContext;
import dev.hylfrd.farmhelper.macro.Macro;
import dev.hylfrd.farmhelper.macro.MacroDecision;
import dev.hylfrd.farmhelper.macro.MacroRandom;
import dev.hylfrd.farmhelper.macro.MacroRotationRequest;
import dev.hylfrd.farmhelper.macro.MacroSettings;
import dev.hylfrd.farmhelper.macro.MacroSpawnPose;
import dev.hylfrd.farmhelper.macro.MacroPauseCause;
import dev.hylfrd.farmhelper.macro.MacroTerminalReason;
import dev.hylfrd.farmhelper.macro.MacroWarpRequest;
import dev.hylfrd.farmhelper.macro.CropCompatibility;
import dev.hylfrd.farmhelper.macro.PlayerPosture;
import dev.hylfrd.farmhelper.macro.ServerResponsiveness;
import dev.hylfrd.farmhelper.macro.VerticalCropMode;
import dev.hylfrd.farmhelper.runtime.snapshot.MotionSnapshot;
import dev.hylfrd.farmhelper.runtime.snapshot.Observation;
import dev.hylfrd.farmhelper.runtime.snapshot.PlayerSnapshot;
import dev.hylfrd.farmhelper.runtime.snapshot.PositionSnapshot;
import dev.hylfrd.farmhelper.runtime.snapshot.RotationSnapshot;
import dev.hylfrd.farmhelper.runtime.spatial.BlockPosition;
import dev.hylfrd.farmhelper.runtime.spatial.BoxSnapshot;
import dev.hylfrd.farmhelper.runtime.spatial.CropObservation;
import dev.hylfrd.farmhelper.runtime.spatial.RelativeFrame;
import dev.hylfrd.farmhelper.runtime.spatial.RewarpPosition;
import dev.hylfrd.farmhelper.runtime.spatial.SpaceStatus;
import dev.hylfrd.farmhelper.runtime.spatial.SpatialCaptureRequest;
import dev.hylfrd.farmhelper.runtime.spatial.SpatialQueries;
import dev.hylfrd.farmhelper.runtime.spatial.SpatialScanResult;
import dev.hylfrd.farmhelper.runtime.spatial.SpatialSnapshot;

import java.util.EnumSet;
import java.util.HashSet;
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
    private static final long DROP_SETTLE_NANOS = TimeUnit.MILLISECONDS.toNanos(350L);

    private final MacroSettings settings;
    private final MacroRandom random;
    private State state = State.STOPPED;
    private Direction direction;
    private double rowY;
    private double lastProgressCoordinate;
    private long progressAt;
    private int noProgressAttempts;
    private long laneDwellNanos;
    private long rewarpDwellNanos;
    private long warpSneakNanos;
    private boolean warpSneakSampled;
    private long rotationDurationMillis;
    private PositionSnapshot laneStart;
    private InputAction laneAction = InputAction.FORWARD;
    private long stateAt;
    private RewarpPosition warpOrigin;
    private long lastWarpRequestAt;
    private long warpAttempts;
    private MacroSpawnPose confirmedSpawn;
    private long recoveryUntil;
    private Float targetYaw;
    private Float targetPitch;
    private long pausedAt = Long.MIN_VALUE;
    private long spatialScanGeneration = 1L;
    private long nextSpatialScanPhase = 1L;
    private long nextSpatialRequestToken = 1L;
    private long spatialScanWorldEpoch = Long.MIN_VALUE;
    private Direction nextSpatialScanDirection = Direction.RIGHT;
    private ScanAnchor spatialScanAnchor;
    private PendingSpatialScan pendingSpatialScan;
    private ObstacleEvidence rightObstacleEvidence;
    private ObstacleEvidence leftObstacleEvidence;

    public SShapeVerticalCropMacro(MacroSettings settings, MacroRandom random) {
        this.settings = Objects.requireNonNull(settings, "settings");
        this.random = Objects.requireNonNull(random, "random");
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
            case FARMING -> progressAt = shift(progressAt, suspended);
            case STARTUP, SWITCHING_LANE, DROPPING, REWARP_DWELL, WARP_LANDING ->
                    stateAt = shift(stateAt, suspended);
            case REWARPING -> lastWarpRequestAt = shift(lastWarpRequestAt, suspended);
            case AFTER_WARP, POST_REWARP, RECOVERING -> recoveryUntil = shift(recoveryUntil, suspended);
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
        for (int right = -3; right <= 3; right++) {
            for (int forward = -2; forward <= 3; forward++) {
                for (int up = -2; up <= 3; up++) {
                    blocks.add(cardinal.blockAt(position.x(), position.y(), position.z(), right, up, forward));
                }
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
            long requestToken = nextSpatialRequestToken;
            nextSpatialRequestToken = incrementPositive(nextSpatialRequestToken);
            SpatialCaptureRequest request = new SpatialCaptureRequest(
                    worldEpoch, requestToken, bounds(blocks), blocks);
            pendingSpatialScan = new PendingSpatialScan(
                    spatialScanGeneration, nextSpatialScanPhase,
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
            return tickRewarpDwell(context);
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
            MacroSpawnPose pose = confirmedSpawn;
            if (pose != null) {
                targetYaw = pose.yaw();
                targetPitch = pose.pitch();
                rotationDurationMillis = sampleRotationDurationMillis(randomUnit());
                confirmedSpawn = null;
            } else {
                targetYaw = null;
                targetPitch = null;
            }
        }
        if (state == State.RECOVERING) {
            if (context.nowNanos() < recoveryUntil) {
                return MacroDecision.idle("recovering");
            }
            state = State.ALIGNING;
            targetYaw = null;
            targetPitch = null;
        }
        if (state == State.DROPPING) {
            return tickDrop(context, observed);
        }

        Optional<MacroDecision> rewarp = beginRewarp(context, observed.position());
        if (rewarp.isPresent()) {
            return rewarp.orElseThrow();
        }
        if (direction != null && Math.abs(observed.position().y() - rowY) > 1.5D) {
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
                    POST_REWARP, RECOVERING, STOPPED ->
                    MacroDecision.idle(state.name().toLowerCase());
        };
    }

    public State state() {
        return state;
    }

    long spatialScanGeneration() {
        return spatialScanGeneration;
    }

    boolean spatialScanPending() {
        return pendingSpatialScan != null;
    }

    private MacroDecision align(FarmingContext context, Observed observed) {
        if (targetYaw == null) {
            targetYaw = closestCardinal(observed.rotation().yaw());
            targetPitch = settings.mode() == VerticalCropMode.COCOA
                    ? settings.mode().targetPitch(0.0D)
                    : settings.mode().targetPitch(randomUnit());
            rotationDurationMillis = sampleRotationDurationMillis(randomUnit());
        }
        if (angleDistance(observed.rotation().yaw(), targetYaw) <= 1.0F
                && Math.abs(observed.rotation().pitch() - targetPitch) <= 1.0F) {
            return null;
        }
        long duration = rotationDurationMillis(rotationDurationMillis,
                angleDistance(observed.rotation().yaw(), targetYaw));
        return new MacroDecision(Set.of(),
                Optional.of(new MacroRotationRequest(targetYaw, targetPitch, duration)),
                Optional.empty(), "aligning");
    }

    private MacroDecision chooseDirection(FarmingContext context, Observed observed) {
        CropStatus right = cropStatus(observed, Direction.RIGHT);
        CropStatus left = cropStatus(observed, Direction.LEFT);
        if (right == CropStatus.READY) {
            beginRow(Direction.RIGHT, context, observed);
            return farmInputs("farming-right");
        }
        if (left == CropStatus.READY) {
            beginRow(Direction.LEFT, context, observed);
            return farmInputs("farming-left");
        }
        if (right == CropStatus.INCOMPATIBLE || left == CropStatus.INCOMPATIBLE) {
            return MacroDecision.failClosed("crop-mode-incompatible");
        }
        if (right == CropStatus.UNKNOWN || left == CropStatus.UNKNOWN) {
            return MacroDecision.failClosed("row-direction-unknown");
        }
        if (observed.position().y() > SpatialQueries.GARDEN_VOID_MIN_Y) {
            SpaceStatus rightVoid = SpatialQueries.gardenVoid(
                    observed.spatial(), observed.worldEpoch(), sideBlock(observed, Direction.RIGHT));
            SpaceStatus leftVoid = SpatialQueries.gardenVoid(
                    observed.spatial(), observed.worldEpoch(), sideBlock(observed, Direction.LEFT));
            if (rightVoid == SpaceStatus.BLOCKED) {
                beginRow(Direction.LEFT, context, observed);
                return farmInputs("farming-left-void");
            }
            if (leftVoid == SpaceStatus.BLOCKED) {
                beginRow(Direction.RIGHT, context, observed);
                return farmInputs("farming-right-void");
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
            beginRow(Direction.LEFT, context, observed);
            return farmInputs("farming-left-obstacle");
        }
        SpaceStatus leftObstacle = scanDirection(observed, Direction.LEFT).status();
        if (leftObstacle == SpaceStatus.BLOCKED) {
            beginRow(Direction.RIGHT, context, observed);
            return farmInputs("farming-right-obstacle");
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
            beginRow(Direction.LEFT, context, observed);
            return farmInputs("farming-left-obstacle");
        }
        if (rightObstacleEvidence == ObstacleEvidence.EXHAUSTED
                && leftObstacleEvidence == ObstacleEvidence.BLOCKED) {
            beginRow(Direction.RIGHT, context, observed);
            return farmInputs("farming-right-obstacle");
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
                || pending.generation() != spatialScanGeneration
                || pending.phase() != (pending.direction() == Direction.RIGHT ? 1L : 2L)
                || pending.request().requestToken() != snapshot.requestToken()
                || pending.request().worldEpoch() != snapshot.worldEpoch()
                || !pending.request().bounds().equals(snapshot.bounds())
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
        if (pending.generation() != spatialScanGeneration
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
        CropStatus crop = cropStatus(observed, direction);
        if (crop == CropStatus.INCOMPATIBLE) {
            return MacroDecision.failClosed("crop-mode-incompatible");
        }
        if (next == SpaceStatus.UNKNOWN || crop == CropStatus.UNKNOWN) {
            return MacroDecision.failClosed("row-spatial-unknown");
        }
        if (next == SpaceStatus.BLOCKED || crop == CropStatus.ABSENT) {
            return beginLaneChange(context, observed);
        }

        double coordinate = movementCoordinate(observed.position(), observed.frame(), direction);
        if (Math.abs(coordinate - lastProgressCoordinate) >= 0.05D) {
            lastProgressCoordinate = coordinate;
            progressAt = context.nowNanos();
            noProgressAttempts = 0;
        } else if (elapsed(context.nowNanos(), progressAt) >= LAG_RETRY_NANOS) {
            progressAt = context.nowNanos();
            noProgressAttempts++;
            if (noProgressAttempts >= 3) {
                enterRecovery(context.nowNanos());
                return MacroDecision.failClosed("row-recovery");
            }
            EnumSet<InputAction> recovery = EnumSet.of(InputAction.JUMP, InputAction.ATTACK);
            recovery.add(direction == Direction.RIGHT ? InputAction.LEFT : InputAction.RIGHT);
            return decision(recovery, "row-retry-" + noProgressAttempts);
        }
        return farmInputs(direction == Direction.RIGHT ? "farming-right" : "farming-left");
    }

    private MacroDecision switchLane(FarmingContext context, Observed observed) {
        if (elapsed(context.nowNanos(), stateAt) < laneDwellNanos) {
            return MacroDecision.idle("row-change-dwell");
        }
        double forward = forwardDistance(laneStart, observed.position(), observed.frame())
                * (laneAction == InputAction.FORWARD ? 1.0D : -1.0D);
        if (forward >= 1.0D) {
            beginRow(direction.opposite(), context, observed);
            return farmInputs(direction == Direction.RIGHT ? "farming-right" : "farming-left");
        }
        if (elapsed(context.nowNanos(), stateAt) > LANE_TIMEOUT_NANOS) {
            enterRecovery(context.nowNanos());
            return MacroDecision.failClosed("lane-recovery");
        }
        return laneInputs("switching-lane");
    }

    private MacroDecision tickDrop(FarmingContext context, Observed observed) {
        MotionSnapshot motion = observed.motion();
        long elapsed = elapsed(context.nowNanos(), stateAt);
        if (elapsed >= DROP_SETTLE_NANOS && Math.abs(motion.y()) < 0.05D) {
            clearLaneState();
            state = State.ALIGNING;
            return MacroDecision.idle("drop-complete");
        }
        return MacroDecision.failClosed("dropping");
    }

    private Optional<MacroDecision> beginRewarp(FarmingContext context, PositionSnapshot position) {
        Optional<MacroSpawnPose> spawn = settings.spawn();
        if (spawn.isEmpty()) {
            return Optional.empty();
        }
        BlockPosition block = block(position);
        Optional<RewarpPosition> current = settings.rewarps().stream()
                .filter(rewarp -> rewarp.block().equals(block))
                .findFirst();
        if (current.isEmpty()) {
            return Optional.empty();
        }
        invalidateSpatialScan();
        state = State.REWARP_DWELL;
        warpOrigin = current.orElseThrow();
        stateAt = context.nowNanos();
        rewarpDwellNanos = TimeUnit.MILLISECONDS.toNanos(
                400L + (long) Math.floor(randomUnit() * 350.0D));
        warpAttempts = 0L;
        return Optional.of(MacroDecision.idle("rewarp-dwell"));
    }

    private MacroDecision tickRewarpDwell(FarmingContext context) {
        MacroSpawnPose spawn = settings.spawn().orElse(null);
        if (spawn == null || warpOrigin == null) {
            enterRecovery(context.nowNanos());
            return MacroDecision.failClosed("rewarp-config-lost");
        }
        if (elapsed(context.nowNanos(), stateAt) < rewarpDwellNanos) {
            return MacroDecision.idle("rewarp-dwell");
        }
        state = State.REWARPING;
        lastWarpRequestAt = context.nowNanos();
        warpAttempts = 1L;
        return warpDecision(context, spawn, "rewarp-request");
    }

    private MacroDecision tickRewarp(FarmingContext context, Observed observed) {
        MacroSpawnPose spawn = settings.spawn().orElse(null);
        if (spawn == null || warpOrigin == null) {
            enterRecovery(context.nowNanos());
            return MacroDecision.failClosed("rewarp-config-lost");
        }
        BlockPosition current = block(observed.position());
        if (warpOrigin.squaredDistance(current) > 2.0D || spawn.block().equals(current)) {
            clearLaneState();
            confirmedSpawn = spawn;
            state = State.WARP_LANDING;
            stateAt = context.nowNanos();
            warpSneakSampled = false;
            return tickWarpLanding(context);
        }
        if (elapsed(context.nowNanos(), lastWarpRequestAt) < WARP_RETRY_NANOS) {
            return MacroDecision.idle("rewarp-waiting");
        }
        if (warpAttempts < Long.MAX_VALUE) {
            warpAttempts++;
        }
        lastWarpRequestAt = context.nowNanos();
        return warpDecision(context, spawn, "rewarp-retry-" + warpAttempts);
    }

    private MacroDecision tickWarpLanding(FarmingContext context) {
        if (!context.posture().isPresent()) {
            return MacroDecision.failClosed("rewarp-posture-unknown");
        }
        PlayerPosture posture = context.posture().get();
        if (posture.flying() && !posture.onGround()) {
            if (!warpSneakSampled) {
                warpSneakNanos = TimeUnit.MILLISECONDS.toNanos(
                        350L + (long) Math.floor(randomUnit() * 300.0D));
                stateAt = context.nowNanos();
                warpSneakSampled = true;
            }
            if (elapsed(context.nowNanos(), stateAt) < warpSneakNanos) {
                return decision(EnumSet.of(InputAction.SNEAK), "rewarp-airborne-sneak");
            }
            return MacroDecision.failClosed("rewarp-airborne");
        }
        state = State.AFTER_WARP;
        recoveryUntil = context.nowNanos() + AFTER_WARP_NANOS;
        return MacroDecision.idle("rewarp-confirmed plot="
                + (confirmedSpawn == null ? -1 : confirmedSpawn.plot()));
    }

    private MacroDecision warpDecision(FarmingContext context, MacroSpawnPose spawn, String status) {
        return new MacroDecision(Set.of(), Optional.empty(),
                Optional.of(new MacroWarpRequest(context.developmentGarden(), spawn)), status);
    }

    private CropStatus cropStatus(Observed observed, Direction side) {
        int right = side == Direction.RIGHT ? 1 : -1;
        VerticalCropMode mode = settings.mode();
        boolean unknown = false;
        boolean incompatible = false;
        for (int up = mode == VerticalCropMode.COCOA ? 0 : -1; up <= 2; up++) {
            for (int forward = -1; forward <= 1; forward++) {
                BlockPosition position = observed.frame().blockAt(
                        observed.position().x(), observed.position().y(), observed.position().z(),
                        right, up, forward);
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
                        return CropStatus.READY;
                    }
                    incompatible = true;
                }
            }
        }
        if (incompatible) {
            return CropStatus.INCOMPATIBLE;
        }
        return unknown ? CropStatus.UNKNOWN : CropStatus.ABSENT;
    }

    private SpaceStatus nextStep(Observed observed) {
        int sign = direction == Direction.RIGHT ? 1 : -1;
        BoxSnapshot next = observed.spatial().playerBox().move(
                observed.frame().rightX() * sign,
                0.0D,
                observed.frame().rightZ() * sign);
        return SpatialQueries.walkability(observed.spatial(), observed.worldEpoch(), next);
    }

    private void beginRow(Direction next, FarmingContext context, Observed observed) {
        invalidateSpatialScan();
        direction = next;
        state = State.FARMING;
        rowY = observed.position().y();
        lastProgressCoordinate = movementCoordinate(observed.position(), observed.frame(), next);
        progressAt = context.nowNanos();
        noProgressAttempts = 0;
    }

    private MacroDecision farmInputs(String status) {
        EnumSet<InputAction> inputs = EnumSet.of(InputAction.ATTACK,
                direction == Direction.RIGHT ? InputAction.RIGHT : InputAction.LEFT);
        if (settings.mode().forwardAssist()) {
            inputs.add(InputAction.FORWARD);
        }
        return decision(inputs, status);
    }

    private MacroDecision laneInputs(String status) {
        EnumSet<InputAction> inputs = EnumSet.of(laneAction, InputAction.SPRINT);
        if (settings.mode().forwardAssist()) {
            inputs.add(InputAction.ATTACK);
        }
        return decision(inputs, status);
    }

    private MacroDecision beginLaneChange(FarmingContext context, Observed observed) {
        SpaceStatus front = laneWalkability(observed, 1);
        if (front == SpaceStatus.PASSABLE) {
            laneAction = InputAction.FORWARD;
        } else if (settings.mode().cactusMode()) {
            enterRecovery(context.nowNanos());
            return MacroDecision.failClosed(front == SpaceStatus.UNKNOWN
                    ? "cactus-lane-unknown" : "cactus-lane-blocked");
        } else {
            SpaceStatus back = laneWalkability(observed, -1);
            if (front == SpaceStatus.UNKNOWN || back == SpaceStatus.UNKNOWN) {
                return MacroDecision.failClosed("lane-direction-unknown");
            }
            if (back != SpaceStatus.PASSABLE) {
                return MacroDecision.failClosed("lane-direction-blocked");
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

    private void enterRecovery(long nowNanos) {
        clearLaneState();
        state = State.RECOVERING;
        recoveryUntil = nowNanos + POST_REWARP_NANOS;
    }

    private void clearLaneState() {
        invalidateSpatialScan();
        direction = null;
        rowY = 0.0D;
        lastProgressCoordinate = 0.0D;
        progressAt = 0L;
        noProgressAttempts = 0;
        laneStart = null;
        laneAction = InputAction.FORWARD;
        warpOrigin = null;
        lastWarpRequestAt = 0L;
        warpAttempts = 0L;
        confirmedSpawn = null;
        targetYaw = null;
        targetPitch = null;
        stateAt = 0L;
        laneDwellNanos = TimeUnit.MILLISECONDS.toNanos(MIN_ROW_PROGRESS_MILLIS);
        rewarpDwellNanos = TimeUnit.MILLISECONDS.toNanos(400L);
        warpSneakNanos = TimeUnit.MILLISECONDS.toNanos(350L);
        warpSneakSampled = false;
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
        double value = random.nextUnit();
        if (!Double.isFinite(value) || value < 0.0D || value >= 1.0D) {
            throw new IllegalStateException("macro random draw must be in [0, 1)");
        }
        return value;
    }

    private void resetRun(State next) {
        state = next;
        direction = null;
        rowY = 0.0D;
        lastProgressCoordinate = 0.0D;
        progressAt = 0L;
        noProgressAttempts = 0;
        laneDwellNanos = TimeUnit.MILLISECONDS.toNanos(MIN_ROW_PROGRESS_MILLIS);
        rewarpDwellNanos = TimeUnit.MILLISECONDS.toNanos(400L);
        warpSneakNanos = TimeUnit.MILLISECONDS.toNanos(350L);
        warpSneakSampled = false;
        rotationDurationMillis = 500L;
        laneStart = null;
        laneAction = InputAction.FORWARD;
        stateAt = 0L;
        warpOrigin = null;
        lastWarpRequestAt = 0L;
        warpAttempts = 0L;
        confirmedSpawn = null;
        recoveryUntil = 0L;
        targetYaw = null;
        targetPitch = null;
        pausedAt = Long.MIN_VALUE;
        invalidateSpatialScan();
    }

    private void invalidateSpatialScan() {
        spatialScanGeneration = incrementPositive(spatialScanGeneration);
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
        RECOVERING
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
