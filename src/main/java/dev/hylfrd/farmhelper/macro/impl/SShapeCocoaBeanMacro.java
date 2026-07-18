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
import dev.hylfrd.farmhelper.runtime.spatial.BlockStateSnapshot;
import dev.hylfrd.farmhelper.runtime.spatial.BoxSnapshot;
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

/** Upstream-parity Cocoa modes 7 and 8 with conservative immutable evidence. */
public final class SShapeCocoaBeanMacro implements Macro {
    static final long PROGRESS_WINDOW_NANOS = TimeUnit.MILLISECONDS.toNanos(500L);
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
    private float cardinalYaw;
    private Float farmingYaw;
    private Float farmingPitch;
    private long recoveryUntil;
    private MacroRecoveryReason recoveryReason;
    private long pausedAt = Long.MIN_VALUE;
    private long runGeneration;

    public SShapeCocoaBeanMacro(MacroSettings settings, MacroRandom random) {
        this(settings, random, random);
    }

    public SShapeCocoaBeanMacro(
            MacroSettings settings,
            MacroRandom leafRandom,
            MacroRandom rotationRandom
    ) {
        this.settings = Objects.requireNonNull(settings, "settings").snapshot();
        this.leafRandom = Objects.requireNonNull(leafRandom, "leafRandom");
        rotationEntropy = new RotationEntropy(
                Objects.requireNonNull(rotationRandom, "rotationRandom"));
        if (this.settings.macroMode().code() != 7 && this.settings.macroMode().code() != 8) {
            throw new IllegalArgumentException("Cocoa macro requires mode 7 or 8");
        }
    }

    @Override
    public String id() {
        return "s-shape-cocoa-beans";
    }

    @Override
    public Optional<MacroCrop> activeCrop() {
        return state == State.STOPPED
                ? Optional.empty()
                : Optional.of(MacroCrop.COCOA);
    }

    @Override
    public void onStart() {
        reset(State.STARTUP);
    }

    @Override
    public void onStart(long nowNanos) {
        if (nowNanos < 0L) {
            throw new IllegalArgumentException("nowNanos must not be negative");
        }
        reset(State.STARTUP);
    }

    @Override
    public void onStop() {
        reset(State.STOPPED);
    }

    @Override
    public void onStop(MacroTerminalReason reason) {
        Objects.requireNonNull(reason, "reason");
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
        if (isMoving(state)) {
            rowProgress.shift(suspended);
        }
        switch (state) {
            case REWARP_DWELL, WARP_LANDING -> rewarp.shiftState(suspended);
            case REWARPING -> rewarp.shiftRequest(suspended);
            case AFTER_WARP, POST_REWARP -> recoveryUntil = shift(recoveryUntil, suspended);
            case STOPPED, STARTUP, NONE, BACKWARD, FORWARD, SWITCHING_SIDE,
                    SWITCHING_LANE, DROPPING, POST_REWARP_ALIGNING, RECOVERY_HANDOFF -> { }
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
                block(position), body(position), observedRotation.yaw(), cardinalYaw);
        CaptureKey key = captureKey(worldEpoch, anchor);
        Optional<SpatialCaptureRequest> reusable = captures.reusable(key);
        if (reusable.isPresent()) {
            return reusable;
        }
        invalidateCapture();
        key = captureKey(worldEpoch, anchor);
        Set<BlockPosition> blocks = requestedBlocks(position, observedRotation);
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
        MacroDecision prerequisite = prerequisites(context);
        if (prerequisite != null) {
            return prerequisite;
        }
        PlayerPosture posture = context.posture().get();
        Observed observed = consumeCapture(context);
        if (observed == null) {
            return MacroDecision.failClosed("spatial-unknown-or-stale");
        }

        if (state == State.STARTUP) {
            return beginStartup(observed);
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
            return beginPostRewarpCorrection(observed);
        }
        if (state == State.POST_REWARP_ALIGNING) {
            return tickPostRewarpAlignment(context, observed);
        }
        if (state == State.DROPPING) {
            return tickDrop(observed, posture);
        }

        Optional<MacroDecision> rewarpDecision = beginRewarp(context, observed);
        if (rewarpDecision.isPresent()) {
            return rewarpDecision.orElseThrow();
        }
        if (drop.shouldDrop(posture, observed.position().y())) {
            state = State.DROPPING;
            rowProgress.clear();
            rotation.clear();
            invalidateCapture();
            return MacroDecision.failClosed("dropping");
        }

        MacroDecision alignment = align(context);
        if (alignment != null) {
            return alignment;
        }
        return tickFarming(context, observed);
    }

    public State state() {
        return state;
    }

    Optional<dev.hylfrd.farmhelper.macro.MacroRotationRequest> pendingRotation() {
        return rotation.pending();
    }

    private MacroDecision prerequisites(FarmingContext context) {
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

    private MacroDecision beginStartup(Observed observed) {
        farmingPitch = settings.customPitch()
                ? settings.customPitchLevel()
                : (float) (-70.0D + randomUnit() * 0.6D);
        cardinalYaw = settings.customYaw()
                ? RotationTask.normalizeYaw(settings.customYawLevel())
                : MacroAngles.closestCardinal(observed.rotation().yaw());
        farmingYaw = cardinalYaw;
        drop.arm(observed.position().y());
        state = State.NONE;
        invalidateCapture();
        if (settings.dontFixAfterWarping()
                && Math.abs(MacroAngles.shortestDelta(
                        observed.rotation().yaw(), farmingYaw)) < 0.1F) {
            return MacroDecision.failClosed("startup-fix-suppressed");
        }
        rotation.begin(
                observed.rotation().yaw(), observed.rotation().pitch(),
                farmingYaw, farmingPitch, RotationProfile.BACK,
                sampleRotationMillis(), rotationEntropy);
        return rotationDecision("startup-aligning");
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

    private MacroDecision tickFarming(FarmingContext context, Observed observed) {
        Walkability walkability = walkability(observed);
        if (!walkability.known()) {
            return MacroDecision.failClosed("walkability-unknown");
        }

        if (state == State.SWITCHING_LANE) {
            Evidence line = lineChanged(observed, walkability.front());
            if (line == Evidence.UNKNOWN) {
                return MacroDecision.failClosed("line-change-unknown");
            }
            if (line == Evidence.YES && !walkability.back() && walkability.left()) {
                transition(State.FORWARD, observed);
                return MacroDecision.failClosed("line-change-complete");
            }
        }

        State next = nextState(state, walkability);
        boolean changed = next != state;
        if (changed) {
            transition(next, observed);
        }
        if (state == State.NONE) {
            rowProgress.clear();
            return MacroDecision.idle("direction-unresolved");
        }

        if (!changed) {
            MacroDecision stalled = observeProgress(context, observed);
            if (stalled != null) {
                return stalled;
            }
        }
        if (state == State.BACKWARD) {
            return decision(EnumSet.of(InputAction.BACKWARD, InputAction.ATTACK), "backward");
        }
        if (state == State.FORWARD) {
            Evidence hug = shouldHugWall(observed);
            if (hug == Evidence.UNKNOWN) {
                return MacroDecision.failClosed("wall-hug-unknown");
            }
            EnumSet<InputAction> inputs = EnumSet.of(InputAction.FORWARD, InputAction.ATTACK);
            if (hug == Evidence.YES) {
                inputs.add(InputAction.LEFT);
            }
            return decision(inputs, hug == Evidence.YES ? "forward-wall-hug" : "forward");
        }
        return decision(EnumSet.of(InputAction.RIGHT),
                state == State.SWITCHING_LANE ? "switching-lane" : "switching-side");
    }

    static State nextState(State current, Walkability walkability) {
        Objects.requireNonNull(current, "current");
        Objects.requireNonNull(walkability, "walkability");
        if (!walkability.known()) {
            throw new IllegalArgumentException("walkability must be known");
        }
        boolean front = walkability.front();
        boolean back = walkability.back();
        boolean right = walkability.right();
        boolean left = walkability.left();
        return switch (current) {
            case NONE -> front && right ? State.FORWARD
                    : back ? State.BACKWARD
                    : front ? State.FORWARD : State.NONE;
            case BACKWARD -> front && !back && right ? State.SWITCHING_LANE : State.BACKWARD;
            case FORWARD -> !front && back && right && !left ? State.SWITCHING_SIDE
                    : back ? State.BACKWARD
                    : front ? State.FORWARD : State.NONE;
            case SWITCHING_SIDE -> back && !right && left ? State.BACKWARD
                    : State.SWITCHING_SIDE;
            case SWITCHING_LANE -> !back && !right && left ? State.FORWARD
                    : State.SWITCHING_LANE;
            case STOPPED, STARTUP, DROPPING, REWARP_DWELL, REWARPING, WARP_LANDING,
                    AFTER_WARP, POST_REWARP, POST_REWARP_ALIGNING, RECOVERY_HANDOFF -> current;
        };
    }

    static boolean lineFraction(float cardinalYaw, double x, double z) {
        double xFraction = Math.abs(x) % 1.0D;
        double zFraction = Math.abs(z) % 1.0D;
        int yaw = normalizedCardinal(cardinalYaw);
        return switch (yaw) {
            case 180 -> xFraction > 0.488D;
            case 270 -> zFraction > 0.488D;
            case 90 -> zFraction < 0.512D;
            case 0 -> xFraction < 0.512D;
            default -> throw new IllegalStateException("unreachable cardinal");
        };
    }

    static boolean hugWindow(int modeCode, float cardinalYaw, double x, double z) {
        if (modeCode != 7 && modeCode != 8) {
            throw new IllegalArgumentException("Cocoa wall hug requires mode 7 or 8");
        }
        double coordinate = switch (normalizedCardinal(cardinalYaw)) {
            case 0, 180 -> x % 1.0D;
            case 90, 270 -> z % 1.0D;
            default -> throw new IllegalStateException("unreachable cardinal");
        };
        int yaw = normalizedCardinal(cardinalYaw);
        if (modeCode == 8) {
            return yaw == 0 || yaw == 90
                    ? between(coordinate, -0.9D, -0.5D) || between(coordinate, 0.1D, 0.5D)
                    : between(coordinate, -0.5D, -0.1D) || between(coordinate, 0.5D, 0.9D);
        }
        return yaw == 0 || yaw == 90
                ? between(coordinate, -0.9D, -0.35D) || between(coordinate, 0.1D, 0.65D)
                : between(coordinate, -0.65D, -0.1D) || between(coordinate, 0.35D, 0.9D);
    }

    private static boolean between(double value, double minimum, double maximum) {
        return value > minimum && value < maximum;
    }

    private static int normalizedCardinal(float yaw) {
        float normalized = RotationTask.normalizeYaw(MacroAngles.closestCardinal(yaw));
        int cardinal = Math.round(normalized);
        return cardinal == -180 ? 180 : cardinal < 0 ? cardinal + 360 : cardinal;
    }

    private Walkability walkability(Observed observed) {
        RelativeFrame frame = observed.cardinalFrame();
        return new Walkability(
                walkability(observed, move(observed.position(), frame, 0, 1)),
                walkability(observed, move(observed.position(), frame, 0, -1)),
                walkability(observed, move(observed.position(), frame, 1, 0)),
                walkability(observed, move(observed.position(), frame, -1, 0)));
    }

    private static SpaceStatus walkability(Observed observed, BoxSnapshot candidate) {
        return SpatialQueries.walkability(
                observed.spatial(), observed.worldEpoch(), candidate);
    }

    private Evidence lineChanged(Observed observed, boolean frontWalkable) {
        BlockPosition diagonal = UpstreamCurrentYawFrame.from(observed.rotation().yaw()).blockAt(
                observed.position().x(), observed.position().y(), observed.position().z(),
                -1, 0, 1);
        Evidence solid = solidNonAir(observed, diagonal);
        if (solid == Evidence.UNKNOWN) {
            return Evidence.UNKNOWN;
        }
        float observedCardinal = MacroAngles.closestCardinal(observed.rotation().yaw());
        return solid == Evidence.YES && frontWalkable
                && lineFraction(observedCardinal, observed.position().x(), observed.position().z())
                ? Evidence.YES : Evidence.NO;
    }

    private Evidence shouldHugWall(Observed observed) {
        BlockPosition leftWall = UpstreamCurrentYawFrame.from(observed.rotation().yaw()).blockAt(
                observed.position().x(), observed.position().y(), observed.position().z(),
                -1, 0, 0);
        Evidence solid = solidNonAir(observed, leftWall);
        if (solid != Evidence.YES) {
            return solid;
        }
        float observedCardinal = MacroAngles.closestCardinal(observed.rotation().yaw());
        return hugWindow(settings.macroMode().code(), observedCardinal,
                observed.position().x(), observed.position().z()) ? Evidence.YES : Evidence.NO;
    }

    private static Evidence solidNonAir(Observed observed, BlockPosition position) {
        Observation<BlockStateSnapshot> block = observed.spatial().block(
                observed.worldEpoch(), position);
        if (!block.isPresent() || !block.get().collision().isPresent()) {
            return Evidence.UNKNOWN;
        }
        BlockStateSnapshot state = block.get();
        if ("minecraft".equals(state.blockId().namespace())
                && "air".equals(state.blockId().path())) {
            return Evidence.NO;
        }
        return state.collision().get().boxes().stream().anyMatch(BoxSnapshot::hasPositiveVolume)
                ? Evidence.YES : Evidence.NO;
    }

    private MacroDecision observeProgress(FarmingContext context, Observed observed) {
        return switch (rowProgress.observeContinuous(
                context.nowNanos(), progressCoordinate(observed.position(), state))) {
            case WAITING, PROGRESSED -> null;
            case MISSED -> MacroDecision.failClosed(
                    "row-stall-observed-" + rowProgress.misses());
            case STALLED -> enterRecovery(MacroRecoveryReason.ROW_STALLED, "row-stalled");
        };
    }

    private void transition(State next, Observed observed) {
        state = Objects.requireNonNull(next, "next");
        if (isMoving(next)) {
            rowProgress.begin(observed.nowNanos(),
                    progressCoordinate(observed.position(), next));
        } else {
            rowProgress.clear();
        }
        invalidateCapture();
    }

    private double progressCoordinate(PositionSnapshot position, State movingState) {
        RelativeFrame frame = RelativeFrame.cardinal(cardinalYaw);
        double forward = position.x() * frame.forwardX() + position.z() * frame.forwardZ();
        double right = position.x() * frame.rightX() + position.z() * frame.rightZ();
        return switch (movingState) {
            case FORWARD -> forward;
            case BACKWARD -> -forward;
            case SWITCHING_SIDE, SWITCHING_LANE -> right;
            default -> 0.0D;
        };
    }

    private MacroDecision tickDrop(Observed observed, PlayerPosture posture) {
        Optional<DropLedger.Landing> landing = drop.observeLanding(
                posture, observed.position().y());
        if (landing.isEmpty()) {
            return MacroDecision.failClosed("dropping");
        }
        rotation.clear();
        transition(State.NONE, observed);
        return MacroDecision.failClosed(landing.orElseThrow().changedLayer()
                ? "drop-complete" : "drop-too-shallow");
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
        rewarp.begin(current.orElseThrow(), context.nowNanos(), TimeUnit.MILLISECONDS.toNanos(
                400L + (long) Math.floor(randomUnit() * 350.0D)));
        rowProgress.clear();
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
            transition(State.NONE, observed);
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
        state = State.AFTER_WARP;
        recoveryUntil = saturatingAdd(context.nowNanos(), AFTER_WARP_NANOS);
        invalidateCapture();
        return MacroDecision.failClosed("rewarp-confirmed-plot-"
                + rewarp.confirmedSpawn().map(MacroSpawnPose::plot).orElse(-1));
    }

    private MacroDecision beginPostRewarpCorrection(Observed observed) {
        if (farmingYaw == null || farmingPitch == null) {
            return MacroDecision.failClosed("post-rewarp-target-unknown");
        }
        float targetYaw = farmingYaw;
        if (settings.rotateAfterWarped()) {
            targetYaw = RotationTask.normalizeYaw(targetYaw + 180.0F);
            farmingYaw = targetYaw;
            cardinalYaw = MacroAngles.closestCardinal(targetYaw);
        }
        float targetPitch = farmingPitch;
        float yawDistance = Math.abs(MacroAngles.shortestDelta(
                observed.rotation().yaw(), targetYaw));
        float pitchDistance = Math.abs(observed.rotation().pitch() - targetPitch);
        clearWarpState();
        drop.arm(observed.position().y());
        if (settings.dontFixAfterWarping()
                && Math.hypot(yawDistance, pitchDistance) < 1.0D) {
            transition(State.NONE, observed);
            return MacroDecision.failClosed("post-rewarp-fix-suppressed");
        }
        long sampledMillis = sampleRotationMillis();
        if (yawDistance > 90.0F) {
            sampledMillis = Math.multiplyExact(sampledMillis, 2L);
        }
        rotation.begin(
                observed.rotation().yaw(), observed.rotation().pitch(),
                targetYaw, targetPitch, RotationProfile.BACK,
                sampledMillis, rotationEntropy);
        state = State.POST_REWARP_ALIGNING;
        invalidateCapture();
        return rotationDecision("post-rewarp-aligning");
    }

    private MacroDecision tickPostRewarpAlignment(FarmingContext context, Observed observed) {
        if (rotation.pending().isEmpty()) {
            return MacroDecision.failClosed("post-rewarp-rotation-missing");
        }
        return switch (rotation.observe(context)) {
            case NONE -> MacroDecision.failClosed("post-rewarp-rotation-missing");
            case ACTIVE, UNACKNOWLEDGED, RETRYABLE_CANCELLATION ->
                    rotationDecision("post-rewarp-aligning");
            case COMPLETED -> {
                transition(State.NONE, observed);
                drop.arm(observed.position().y());
                yield MacroDecision.failClosed("post-rewarp-complete");
            }
            case CANCELLED -> MacroDecision.failClosed("post-rewarp-rotation-cancelled");
            case STALE_ACKNOWLEDGEMENT ->
                    MacroDecision.failClosed("post-rewarp-rotation-acknowledgement-stale");
        };
    }

    private MacroDecision enterRecovery(MacroRecoveryReason reason, String status) {
        recoveryReason = Objects.requireNonNull(reason, "reason");
        state = State.RECOVERY_HANDOFF;
        rowProgress.clear();
        rotation.clear();
        invalidateCapture();
        return MacroDecision.recoveryHandoff(status, reason);
    }

    private MacroSpawnPose validWarpConfig() {
        MacroSpawnPose spawn = settings.spawn().orElse(null);
        RewarpPosition origin = rewarp.origin().orElse(null);
        return spawn == null || origin == null
                || settings.rewarps().stream().noneMatch(origin::equals) ? null : spawn;
    }

    private static MacroDecision warpDecision(
            FarmingContext context,
            MacroSpawnPose spawn,
            String status
    ) {
        return new MacroDecision(Set.of(), Optional.empty(),
                Optional.of(new MacroWarpRequest(context.developmentGarden(), spawn)), status);
    }

    private MacroDecision rotationDecision(String status) {
        return new MacroDecision(Set.of(), rotation.pending(), Optional.empty(), status);
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
        if (!player.position().isPresent() || !player.motion().isPresent()
                || !player.rotation().isPresent()) {
            return null;
        }
        PositionSnapshot position = player.position().get();
        RotationSnapshot observedRotation = player.rotation().get();
        CaptureAnchor anchor = new CaptureAnchor(
                block(position), body(position), observedRotation.yaw(), cardinalYaw);
        SpatialSnapshot spatial = context.spatial().get();
        CaptureKey key = captureKey(context.worldEpoch(), anchor);
        if (!captures.accepts(key, spatial, anchor.body())) {
            invalidateCapture();
            return null;
        }
        captures.complete();
        return new Observed(
                context.nowNanos(), context.worldEpoch(), position,
                player.motion().get(), observedRotation,
                RelativeFrame.cardinal(cardinalYaw), spatial);
    }

    private Set<BlockPosition> requestedBlocks(
            PositionSnapshot position,
            RotationSnapshot observedRotation
    ) {
        Set<BlockPosition> blocks = new HashSet<>();
        addBoxBlocks(blocks, body(position));
        if (state == State.NONE || state == State.BACKWARD || state == State.FORWARD
                || state == State.SWITCHING_SIDE || state == State.SWITCHING_LANE) {
            RelativeFrame cardinal = RelativeFrame.cardinal(cardinalYaw);
            addWalkabilityBlocks(blocks, move(position, cardinal, 0, 1));
            addWalkabilityBlocks(blocks, move(position, cardinal, 0, -1));
            addWalkabilityBlocks(blocks, move(position, cardinal, 1, 0));
            addWalkabilityBlocks(blocks, move(position, cardinal, -1, 0));
            RelativeFrame current = UpstreamCurrentYawFrame.from(observedRotation.yaw());
            blocks.add(current.blockAt(position.x(), position.y(), position.z(), -1, 0, 0));
            if (state == State.SWITCHING_LANE) {
                blocks.add(current.blockAt(
                        position.x(), position.y(), position.z(), -1, 0, 1));
            }
        }
        return blocks;
    }

    private static BoxSnapshot move(
            PositionSnapshot position,
            RelativeFrame frame,
            int right,
            int forward
    ) {
        return body(position).move(
                frame.rightX() * (double) right + frame.forwardX() * (double) forward,
                0.0D,
                frame.rightZ() * (double) right + frame.forwardZ() * (double) forward);
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

    private void reset(State next) {
        state = next;
        cardinalYaw = 0.0F;
        farmingYaw = null;
        farmingPitch = null;
        rowProgress.clear();
        drop.clear();
        rewarp.clear();
        rotation.clear();
        recoveryUntil = 0L;
        recoveryReason = null;
        pausedAt = Long.MIN_VALUE;
        runGeneration = incrementPositive(runGeneration);
        invalidateCapture();
    }

    private void clearWarpState() {
        rewarp.clear();
    }

    private void invalidateCapture() {
        captures.invalidate();
    }

    private CaptureKey captureKey(long worldEpoch, CaptureAnchor anchor) {
        return new CaptureKey(
                runGeneration, captures.generation(), captures.phase(),
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

    private static boolean isMoving(State candidate) {
        return candidate == State.BACKWARD || candidate == State.FORWARD
                || candidate == State.SWITCHING_SIDE || candidate == State.SWITCHING_LANE;
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
        BACKWARD,
        FORWARD,
        SWITCHING_SIDE,
        SWITCHING_LANE,
        DROPPING,
        REWARP_DWELL,
        REWARPING,
        WARP_LANDING,
        AFTER_WARP,
        POST_REWARP,
        POST_REWARP_ALIGNING,
        RECOVERY_HANDOFF
    }

    private enum Evidence {
        YES,
        NO,
        UNKNOWN
    }

    record Walkability(
            SpaceStatus frontStatus,
            SpaceStatus backStatus,
            SpaceStatus rightStatus,
            SpaceStatus leftStatus
    ) {
        Walkability {
            Objects.requireNonNull(frontStatus, "frontStatus");
            Objects.requireNonNull(backStatus, "backStatus");
            Objects.requireNonNull(rightStatus, "rightStatus");
            Objects.requireNonNull(leftStatus, "leftStatus");
        }

        static Walkability known(boolean front, boolean back, boolean right, boolean left) {
            return new Walkability(
                    status(front), status(back), status(right), status(left));
        }

        boolean known() {
            return frontStatus != SpaceStatus.UNKNOWN && backStatus != SpaceStatus.UNKNOWN
                    && rightStatus != SpaceStatus.UNKNOWN && leftStatus != SpaceStatus.UNKNOWN;
        }

        boolean front() {
            return frontStatus == SpaceStatus.PASSABLE;
        }

        boolean back() {
            return backStatus == SpaceStatus.PASSABLE;
        }

        boolean right() {
            return rightStatus == SpaceStatus.PASSABLE;
        }

        boolean left() {
            return leftStatus == SpaceStatus.PASSABLE;
        }

        private static SpaceStatus status(boolean passable) {
            return passable ? SpaceStatus.PASSABLE : SpaceStatus.BLOCKED;
        }
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
