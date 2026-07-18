package dev.hylfrd.farmhelper.macro.impl;

import dev.hylfrd.farmhelper.control.RotationTask;
import dev.hylfrd.farmhelper.control.input.InputAction;
import dev.hylfrd.farmhelper.control.rotation.RotationProfile;
import dev.hylfrd.farmhelper.macro.FarmingContext;
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

import java.util.EnumSet;
import java.util.HashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/** Upstream-parity mode 11 (30-degree rotating mushroom) with owned mechanisms. */
public final class SShapeMushroomRotateMacro implements Macro {
    static final long PROGRESS_WINDOW_NANOS = TimeUnit.MILLISECONDS.toNanos(500L);
    static final long WARP_RETRY_NANOS = TimeUnit.SECONDS.toNanos(5L);
    static final long AFTER_WARP_NANOS = TimeUnit.MILLISECONDS.toNanos(1_500L);
    static final long POST_REWARP_NANOS = TimeUnit.MILLISECONDS.toNanos(600L);
    static final double MIN_PROGRESS = 0.05D;
    static final int MAX_NO_PROGRESS_WINDOWS = 3;
    static final int FIRST_SCAN_DISTANCE = 1;
    static final int LAST_SCAN_DISTANCE = 179;

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
    private State previousLane;
    private ScanPhase scanPhase = ScanPhase.CROP_TARGETS;
    private int scanDistance = FIRST_SCAN_DISTANCE;
    private float storedYaw;
    private float cardinalYaw;
    private Float storedPitch;
    private boolean laneAligned;
    private long recoveryUntil;
    private MacroRecoveryReason recoveryReason;
    private long pausedAt = Long.MIN_VALUE;
    private long runGeneration;

    public SShapeMushroomRotateMacro(MacroSettings settings, MacroRandom random) {
        this(settings, random, random);
    }

    public SShapeMushroomRotateMacro(
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
        return "s-shape-mushroom-rotate";
    }

    /** Both mushroom colors share the same Desync matching rule. */
    @Override
    public Optional<MacroCrop> activeCrop() {
        return state == State.STOPPED
                ? Optional.empty()
                : Optional.of(MacroCrop.RED_MUSHROOM);
    }

    @Override
    public void onStart() {
        reset(State.STARTUP);
    }

    @Override
    public void onStart(long nowNanos) {
        onStart();
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
            case LEFT, RIGHT -> rowProgress.shift(suspended);
            case REWARP_DWELL, WARP_LANDING -> rewarp.shiftState(suspended);
            case REWARPING -> rewarp.shiftRequest(suspended);
            case AFTER_WARP, POST_REWARP -> recoveryUntil = saturatingAdd(recoveryUntil, suspended);
            case STOPPED, STARTUP, NONE, DROPPING, RECOVERY_HANDOFF -> { }
        }
        pausedAt = Long.MIN_VALUE;
        invalidateCapture();
    }

    @Override
    public Optional<SpatialCaptureRequest> spatialRequest(PlayerSnapshot player, long worldEpoch) {
        Objects.requireNonNull(player, "player");
        if (state == State.STOPPED || !player.position().isPresent()
                || !player.rotation().isPresent()) {
            return Optional.empty();
        }
        PositionSnapshot position = player.position().get();
        RotationSnapshot observedRotation = player.rotation().get();
        CaptureAnchor anchor = new CaptureAnchor(
                block(position), body(position), observedRotation.yaw(), cardinalYaw, storedYaw);
        CaptureKey key = captureKey(worldEpoch, anchor);
        Optional<SpatialCaptureRequest> reusable = captures.reusable(key);
        if (reusable.isPresent()) {
            return reusable;
        }
        invalidateCapture();
        key = captureKey(worldEpoch, anchor);

        Set<BlockPosition> blocks = requestedBlocks(position);
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
        MacroDecision prerequisite = prerequisiteDecision(context);
        if (prerequisite != null) {
            return prerequisite;
        }
        PlayerPosture posture = context.posture().get();
        Observed observed = consumeCapture(context);
        if (observed == null) {
            return MacroDecision.failClosed("spatial-unknown-or-stale");
        }

        if (state == State.STARTUP) {
            return initialize(observed);
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
            recoveryUntil = saturatingAdd(context.nowNanos(), POST_REWARP_NANOS);
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

        Optional<MacroDecision> rewarpDecision = beginRewarp(context, observed);
        if (rewarpDecision.isPresent()) {
            return rewarpDecision.orElseThrow();
        }
        if ((state == State.LEFT || state == State.RIGHT)
                && drop.shouldDrop(posture, observed.position().y())) {
            previousLane = state;
            state = State.DROPPING;
            laneAligned = false;
            rotation.clear();
            rowProgress.clear();
            invalidateCapture();
            return MacroDecision.failClosed("dropping");
        }

        MacroDecision alignment = align(context, observed);
        if (alignment != null) {
            return alignment;
        }
        return switch (state) {
            case NONE -> selectDirection(observed);
            case LEFT, RIGHT -> updateAndFarm(context, observed);
            case STOPPED, STARTUP, DROPPING, REWARP_DWELL, REWARPING, WARP_LANDING,
                    AFTER_WARP, POST_REWARP, RECOVERY_HANDOFF ->
                    MacroDecision.idle(state.name().toLowerCase());
        };
    }

    public State state() {
        return state;
    }

    int scanDistance() {
        return scanDistance;
    }

    ScanPhase scanPhase() {
        return scanPhase;
    }

    float storedYaw() {
        return storedYaw;
    }

    float cardinalYaw() {
        return cardinalYaw;
    }

    Optional<Float> storedPitch() {
        return Optional.ofNullable(storedPitch);
    }

    Optional<State> previousLane() {
        return Optional.ofNullable(previousLane);
    }

    Optional<dev.hylfrd.farmhelper.macro.MacroRotationRequest> pendingRotation() {
        return rotation.pending();
    }

    /** Fixed upstream explicitly disables the generic post-warp rotation path for this leaf. */
    public boolean shouldRotateAfterWarp() {
        return false;
    }

    private MacroDecision prerequisiteDecision(FarmingContext context) {
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
        if (context.posture().get().suffocating() && state != State.WARP_LANDING) {
            return MacroDecision.failClosed("player-suffocating");
        }
        return null;
    }

    private MacroDecision initialize(Observed observed) {
        storedPitch = targetPitch();
        storedYaw = settings.customYaw()
                ? RotationTask.normalizeYaw(settings.customYawLevel())
                : MacroAngles.closestCardinal(observed.rotation().yaw());
        cardinalYaw = MacroAngles.closestCardinal(
                settings.customYaw() ? storedYaw : observed.rotation().yaw());
        state = State.NONE;
        resetScan();
        if (settings.dontFixAfterWarping()
                && Math.abs(MacroAngles.shortestDelta(
                observed.rotation().yaw(), storedYaw)) < 0.1F) {
            rotation.clear();
            return MacroDecision.failClosed("startup-fix-suppressed");
        }
        float targetYaw = rowTarget(State.RIGHT, sampleYawJitter());
        rotation.begin(observed.rotation().yaw(), observed.rotation().pitch(),
                targetYaw, storedPitch, RotationProfile.BACK,
                sampleStartupRotationMillis(), rotationEntropy);
        return rotationDecision("startup-aligning");
    }

    private MacroDecision selectDirection(Observed observed) {
        return switch (scanPhase) {
            case CROP_TARGETS -> inspectCropTargets(observed);
            case RIGHT_OBSTACLE -> inspectObstacle(
                    observed, Side.RIGHT, State.LEFT, ScanPhase.LEFT_OBSTACLE);
            case LEFT_OBSTACLE -> inspectLeftObstacle(observed);
        };
    }

    private MacroDecision inspectCropTargets(Observed observed) {
        ReadyStatus right = readyStatus(observed, Side.RIGHT);
        if (right == ReadyStatus.UNKNOWN) {
            return MacroDecision.failClosed("right-mushroom-ready-unknown");
        }
        if (right == ReadyStatus.READY) {
            return enterLane(State.RIGHT, observed);
        }
        ReadyStatus left = readyStatus(observed, Side.LEFT);
        if (left == ReadyStatus.UNKNOWN) {
            return MacroDecision.failClosed("left-mushroom-ready-unknown");
        }
        if (left == ReadyStatus.READY) {
            return enterLane(State.LEFT, observed);
        }
        scanPhase = ScanPhase.RIGHT_OBSTACLE;
        captures.advancePhase();
        return MacroDecision.idle("mushroom-target-absent");
    }

    private MacroDecision inspectObstacle(
            Observed observed,
            Side side,
            State blockedResult,
            ScanPhase next
    ) {
        SpaceStatus status = sideWalkability(observed, side, scanDistance);
        if (status == SpaceStatus.UNKNOWN) {
            return MacroDecision.failClosed("direction-scan-unknown");
        }
        if (status == SpaceStatus.BLOCKED) {
            return enterLane(blockedResult, observed);
        }
        scanPhase = next;
        captures.advancePhase();
        return MacroDecision.idle("direction-scan-" + scanDistance + "-" + next.name().toLowerCase());
    }

    private MacroDecision inspectLeftObstacle(Observed observed) {
        SpaceStatus status = sideWalkability(observed, Side.LEFT, scanDistance);
        if (status == SpaceStatus.UNKNOWN) {
            return MacroDecision.failClosed("direction-scan-unknown");
        }
        if (status == SpaceStatus.BLOCKED) {
            return enterLane(State.RIGHT, observed);
        }
        if (scanDistance >= LAST_SCAN_DISTANCE) {
            return MacroDecision.failClosed("direction-scan-budget-exhausted");
        }
        scanDistance++;
        scanPhase = ScanPhase.RIGHT_OBSTACLE;
        captures.advancePhase();
        return MacroDecision.idle("direction-scan-" + scanDistance + "-right_obstacle");
    }

    private MacroDecision updateAndFarm(FarmingContext context, Observed observed) {
        if (laneAligned) {
            double coordinate = forwardCoordinate(observed.position(), observed.cardinalFrame());
            switch (rowProgress.observeContinuous(context.nowNanos(), coordinate)) {
                case WAITING, PROGRESSED -> {
                    return farmInputs();
                }
                case STALLED -> {
                    return enterRecovery(MacroRecoveryReason.ROW_STALLED, "row-stalled");
                }
                case MISSED -> { }
            }
        }

        State next = transitionDirection(observed);
        if (next == null) {
            return MacroDecision.failClosed("mushroom-lane-unknown");
        }

        // Approved repair: sample and save pitch before any row yaw/duration draw.
        storedPitch = targetPitch();
        if (next == State.NONE) {
            enterNone();
            return MacroDecision.failClosed("mushroom-direction-recalculate");
        }
        state = next;
        beginProgress(context.nowNanos(), observed);
        float targetYaw = rowTarget(next, sampleYawJitter());
        rotation.begin(observed.rotation().yaw(), observed.rotation().pitch(),
                targetYaw, storedPitch, RotationProfile.BACK,
                sampleRowRotationMillis(), rotationEntropy);
        laneAligned = false;
        invalidateCapture();
        return rotationDecision("mushroom-row-aligning-" + next.name().toLowerCase());
    }

    private State transitionDirection(Observed observed) {
        if (state == State.LEFT) {
            SpaceStatus right = sideWalkability(observed, Side.RIGHT, 1);
            if (right == SpaceStatus.UNKNOWN) {
                return null;
            }
            if (right == SpaceStatus.PASSABLE) {
                return State.RIGHT;
            }
            SpaceStatus left = sideWalkability(observed, Side.LEFT, 1);
            if (left == SpaceStatus.UNKNOWN) {
                return null;
            }
            return left == SpaceStatus.PASSABLE ? State.LEFT : State.NONE;
        }
        SpaceStatus left = sideWalkability(observed, Side.LEFT, 1);
        if (left == SpaceStatus.UNKNOWN) {
            return null;
        }
        if (left == SpaceStatus.PASSABLE) {
            return State.LEFT;
        }
        SpaceStatus right = sideWalkability(observed, Side.RIGHT, 1);
        if (right == SpaceStatus.UNKNOWN) {
            return null;
        }
        return right == SpaceStatus.PASSABLE ? State.RIGHT : State.NONE;
    }

    private MacroDecision enterLane(State direction, Observed observed) {
        if (direction != State.LEFT && direction != State.RIGHT) {
            throw new IllegalArgumentException("rotating mushroom lane must be LEFT or RIGHT");
        }
        state = direction;
        drop.arm(observed.position().y());
        beginProgress(observed.nowNanos(), observed);
        resetScanValues();
        float targetYaw = rowTarget(direction, sampleYawJitter());
        rotation.begin(observed.rotation().yaw(), observed.rotation().pitch(),
                targetYaw, requiredPitch(observed), RotationProfile.BACK,
                sampleRowRotationMillis(), rotationEntropy);
        laneAligned = false;
        invalidateCapture();
        return rotationDecision("mushroom-lane-aligning-" + direction.name().toLowerCase());
    }

    private MacroDecision farmInputs() {
        return decision(EnumSet.of(InputAction.FORWARD, InputAction.ATTACK),
                "farming-mushroom-rotate-" + state.name().toLowerCase());
    }

    private MacroDecision align(FarmingContext context, Observed observed) {
        if (rotation.pending().isEmpty()) {
            return null;
        }
        return switch (rotation.observe(context)) {
            case NONE -> null;
            case COMPLETED -> {
                if (state == State.LEFT || state == State.RIGHT) {
                    laneAligned = true;
                    beginProgress(context.nowNanos(), observed);
                    yield farmInputs();
                }
                yield null;
            }
            case ACTIVE, UNACKNOWLEDGED, RETRYABLE_CANCELLATION -> rotationDecision("aligning");
            case CANCELLED -> MacroDecision.failClosed("rotation-cancelled");
            case STALE_ACKNOWLEDGEMENT ->
                    MacroDecision.failClosed("rotation-acknowledgement-stale");
        };
    }

    private MacroDecision tickDrop(Observed observed, PlayerPosture posture) {
        Optional<DropLedger.Landing> landing = drop.observeLanding(posture, observed.position().y());
        if (landing.isEmpty()) {
            return MacroDecision.failClosed("dropping");
        }
        boolean changedLayer = landing.orElseThrow().changedLayer();
        State landedLane = previousLane;
        enterNone();
        drop.arm(observed.position().y());
        previousLane = null;
        if (!changedLayer || !settings.rotateAfterDrop()) {
            return MacroDecision.failClosed(changedLayer ? "drop-complete" : "drop-too-shallow");
        }
        if (landedLane != State.LEFT && landedLane != State.RIGHT) {
            return MacroDecision.failClosed("drop-lane-unknown");
        }
        storedYaw = MacroAngles.closestCardinal(storedYaw + 180.0F);
        cardinalYaw = MacroAngles.closestCardinal(storedYaw);
        float targetYaw = rowTarget(landedLane, sampleYawJitter());
        rotation.begin(observed.rotation().yaw(), observed.rotation().pitch(),
                targetYaw, requiredPitch(observed), RotationProfile.BACK,
                sampleRowRotationMillis(), rotationEntropy);
        invalidateCapture();
        return rotationDecision("drop-rotate-" + landedLane.name().toLowerCase());
    }

    private Optional<MacroDecision> beginRewarp(FarmingContext context, Observed observed) {
        Optional<RewarpPosition> current = settings.rewarps().stream()
                .filter(candidate -> candidate.block().equals(block(observed.position())))
                .findFirst();
        if (current.isEmpty()) {
            return Optional.empty();
        }
        if (!stationary(observed.motion())) {
            return Optional.of(MacroDecision.failClosed("rewarp-moving"));
        }
        state = State.REWARP_DWELL;
        previousLane = null;
        laneAligned = false;
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
            clearWarpState();
            enterNone();
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
        // Fixed upstream actionAfterTeleport always refreshes this pitch, even with custom pitch.
        storedPitch = sampleRawPitch();
        state = State.AFTER_WARP;
        recoveryUntil = saturatingAdd(context.nowNanos(), AFTER_WARP_NANOS);
        invalidateCapture();
        return MacroDecision.failClosed("rewarp-confirmed-plot-"
                + rewarp.confirmedSpawn().map(MacroSpawnPose::plot).orElse(-1));
    }

    private MacroDecision finishPostRewarp(Observed observed) {
        if (settings.rotateAfterWarped()) {
            cardinalYaw = MacroAngles.closestCardinal(
                    MacroAngles.closestCardinal(observed.rotation().yaw()) + 180.0F);
            // Fixed upstream doAfterRewarpRotation refreshes pitch a second time when enabled.
            storedPitch = sampleRawPitch();
        }
        clearWarpState();
        enterNone();
        rotation.clear();
        return MacroDecision.failClosed(settings.rotateAfterWarped()
                ? "post-rewarp-rotate-disabled" : "post-rewarp-complete");
    }

    private MacroDecision enterRecovery(MacroRecoveryReason reason, String status) {
        recoveryReason = Objects.requireNonNull(reason, "reason");
        state = State.RECOVERY_HANDOFF;
        previousLane = null;
        laneAligned = false;
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

    private float targetPitch() {
        return settings.customPitch() ? settings.customPitchLevel() : sampleRawPitch();
    }

    private float sampleRawPitch() {
        return (float) (randomUnit() * 2.0D - 1.0D);
    }

    private float sampleYawJitter() {
        return (float) (randomUnit() * 4.0D - 2.0D);
    }

    private long sampleStartupRotationMillis() {
        return 500L + (long) Math.floor(randomUnit() * 300.0D);
    }

    private long sampleRowRotationMillis() {
        return 400L + (long) Math.floor(randomUnit() * 300.0D);
    }

    private float rowTarget(State lane, float jitter) {
        if (lane != State.LEFT && lane != State.RIGHT) {
            throw new IllegalArgumentException("row target requires a lateral lane");
        }
        return RotationTask.normalizeYaw(
                cardinalYaw + (lane == State.LEFT ? -30.0F : 30.0F) + jitter);
    }

    private float requiredPitch(Observed observed) {
        if (storedPitch == null) {
            storedPitch = observed.rotation().pitch();
        }
        return storedPitch;
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
        if (!player.position().isPresent() || !player.rotation().isPresent()
                || !player.motion().isPresent()) {
            return null;
        }
        PositionSnapshot position = player.position().get();
        RotationSnapshot observedRotation = player.rotation().get();
        CaptureAnchor anchor = new CaptureAnchor(
                block(position), body(position), observedRotation.yaw(), cardinalYaw, storedYaw);
        CaptureKey key = captureKey(context.worldEpoch(), anchor);
        SpatialSnapshot spatial = context.spatial().get();
        if (!captures.accepts(key, spatial, anchor.body())) {
            invalidateCapture();
            return null;
        }
        captures.complete();
        return new Observed(
                context.nowNanos(), context.worldEpoch(), position, player.motion().get(),
                observedRotation, RelativeFrame.cardinal(cardinalYaw), spatial);
    }

    private Set<BlockPosition> requestedBlocks(PositionSnapshot position) {
        Set<BlockPosition> blocks = new HashSet<>();
        addBoxBlocks(blocks, body(position));
        RelativeFrame cardinal = RelativeFrame.cardinal(cardinalYaw);
        switch (state) {
            case NONE -> {
                if (scanPhase == ScanPhase.CROP_TARGETS) {
                    addMushroomTargets(blocks, position, cardinal, Side.RIGHT);
                    addMushroomTargets(blocks, position, cardinal, Side.LEFT);
                    addWalkabilityBlocks(blocks, movedBody(position, cardinal, 1));
                    addWalkabilityBlocks(blocks, movedBody(position, cardinal, -1));
                } else {
                    Side side = scanPhase == ScanPhase.RIGHT_OBSTACLE ? Side.RIGHT : Side.LEFT;
                    addWalkabilityBlocks(blocks, movedBody(position, cardinal,
                            side.sign() * scanDistance));
                }
            }
            case LEFT, RIGHT -> {
                addWalkabilityBlocks(blocks, movedBody(position, cardinal, -1));
                addWalkabilityBlocks(blocks, movedBody(position, cardinal, 1));
            }
            case STOPPED, STARTUP, DROPPING, REWARP_DWELL, REWARPING, WARP_LANDING,
                    AFTER_WARP, POST_REWARP, RECOVERY_HANDOFF -> { }
        }
        return blocks;
    }

    private static void addMushroomTargets(
            Set<BlockPosition> blocks,
            PositionSnapshot position,
            RelativeFrame frame,
            Side side
    ) {
        for (int up = 1; up <= 3; up++) {
            blocks.add(frame.blockAt(position.x(), position.y(), position.z(),
                    side.sign(), up, 1));
        }
    }

    private TargetStatus targetStatus(Observed observed, Side side) {
        boolean unknown = false;
        for (int up = 1; up <= 3; up++) {
            BlockPosition position = observed.cardinalFrame().blockAt(
                    observed.position().x(), observed.position().y(), observed.position().z(),
                    side.sign(), up, 1);
            Observation<dev.hylfrd.farmhelper.runtime.spatial.BlockStateSnapshot> block =
                    observed.spatial().block(observed.worldEpoch(), position);
            if (!block.isPresent()) {
                unknown = true;
                continue;
            }
            Observation<CropObservation> crop = CropObservation.observe(block.get());
            if (crop.isUnknown()) {
                unknown = true;
                continue;
            }
            if (crop.isPresent() && isMushroom(crop.get().kind())) {
                return TargetStatus.MUSHROOM;
            }
        }
        return unknown ? TargetStatus.UNKNOWN : TargetStatus.ABSENT;
    }

    /** Compatibility/walkability is intentionally checked before nearest-target inspection. */
    private ReadyStatus readyStatus(Observed observed, Side side) {
        SpaceStatus walkability = sideWalkability(observed, side, 1);
        if (walkability == SpaceStatus.UNKNOWN) {
            return ReadyStatus.UNKNOWN;
        }
        if (walkability == SpaceStatus.BLOCKED) {
            return ReadyStatus.NOT_READY;
        }
        TargetStatus target = targetStatus(observed, side);
        if (target == TargetStatus.UNKNOWN) {
            return ReadyStatus.UNKNOWN;
        }
        return target == TargetStatus.MUSHROOM ? ReadyStatus.READY : ReadyStatus.NOT_READY;
    }

    private static boolean isMushroom(CropBlockKind kind) {
        return kind == CropBlockKind.RED_MUSHROOM || kind == CropBlockKind.BROWN_MUSHROOM;
    }

    private static SpaceStatus sideWalkability(Observed observed, Side side, int distance) {
        return SpatialQueries.walkability(
                observed.spatial(), observed.worldEpoch(),
                movedBody(observed.position(), observed.cardinalFrame(), side.sign() * distance));
    }

    private static BoxSnapshot movedBody(
            PositionSnapshot position,
            RelativeFrame frame,
            int rightDistance
    ) {
        return body(position).move(
                frame.rightX() * (double) rightDistance,
                0.0D,
                frame.rightZ() * (double) rightDistance);
    }

    private static void addWalkabilityBlocks(Set<BlockPosition> blocks, BoxSnapshot candidate) {
        addBoxBlocks(blocks, candidate);
        addBoxBlocks(blocks, new BoxSnapshot(
                candidate.minX(), candidate.minY() - 1.0D / 1024.0D, candidate.minZ(),
                candidate.maxX(), candidate.minY(), candidate.maxZ()));
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

    private void beginProgress(long nowNanos, Observed observed) {
        rowProgress.begin(nowNanos,
                forwardCoordinate(observed.position(), observed.cardinalFrame()));
    }

    private static double forwardCoordinate(PositionSnapshot position, RelativeFrame frame) {
        return position.x() * frame.forwardX() + position.z() * frame.forwardZ();
    }

    private void enterNone() {
        state = State.NONE;
        laneAligned = false;
        rowProgress.clear();
        resetScan();
    }

    private void resetScan() {
        resetScanValues();
        invalidateCapture();
    }

    private void resetScanValues() {
        scanDistance = FIRST_SCAN_DISTANCE;
        scanPhase = ScanPhase.CROP_TARGETS;
    }

    private void clearWarpState() {
        rewarp.clear();
    }

    private void reset(State next) {
        state = next;
        previousLane = null;
        scanPhase = ScanPhase.CROP_TARGETS;
        scanDistance = FIRST_SCAN_DISTANCE;
        storedYaw = 0.0F;
        cardinalYaw = 0.0F;
        storedPitch = null;
        laneAligned = false;
        recoveryUntil = 0L;
        recoveryReason = null;
        pausedAt = Long.MIN_VALUE;
        runGeneration = incrementPositive(runGeneration);
        rotation.clear();
        rowProgress.clear();
        drop.clear();
        rewarp.clear();
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
        for (BlockPosition candidate : blocks) {
            minX = Math.min(minX, candidate.x());
            minY = Math.min(minY, candidate.y());
            minZ = Math.min(minZ, candidate.z());
            maxX = Math.max(maxX, candidate.x());
            maxY = Math.max(maxY, candidate.y());
            maxZ = Math.max(maxZ, candidate.z());
        }
        return new BoxSnapshot(minX, minY, minZ,
                (double) maxX + 1.0D, (double) maxY + 1.0D, (double) maxZ + 1.0D);
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
        LEFT,
        RIGHT,
        DROPPING,
        REWARP_DWELL,
        REWARPING,
        WARP_LANDING,
        AFTER_WARP,
        POST_REWARP,
        RECOVERY_HANDOFF
    }

    enum ScanPhase {
        CROP_TARGETS,
        RIGHT_OBSTACLE,
        LEFT_OBSTACLE
    }

    private enum Side {
        LEFT(-1),
        RIGHT(1);

        private final int sign;

        Side(int sign) {
            this.sign = sign;
        }

        int sign() {
            return sign;
        }
    }

    private enum TargetStatus {
        MUSHROOM,
        ABSENT,
        UNKNOWN
    }

    private enum ReadyStatus {
        READY,
        NOT_READY,
        UNKNOWN
    }

    private record CaptureAnchor(
            BlockPosition block,
            BoxSnapshot body,
            float currentYaw,
            float cardinalYaw,
            float storedYaw
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
    }
}
