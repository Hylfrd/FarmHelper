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
import dev.hylfrd.farmhelper.runtime.snapshot.MotionSnapshot;
import dev.hylfrd.farmhelper.runtime.snapshot.Observation;
import dev.hylfrd.farmhelper.runtime.snapshot.PlayerSnapshot;
import dev.hylfrd.farmhelper.runtime.snapshot.PositionSnapshot;
import dev.hylfrd.farmhelper.runtime.snapshot.RotationSnapshot;
import dev.hylfrd.farmhelper.runtime.spatial.BlockPosition;
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

/** Upstream-parity mode 13 circular key cycle with fail-closed corner evidence. */
public final class CircularCropMacro implements Macro {
    static final long WARP_RETRY_NANOS = TimeUnit.SECONDS.toNanos(5L);
    static final long AFTER_WARP_NANOS = TimeUnit.MILLISECONDS.toNanos(1_500L);
    static final long POST_REWARP_NANOS = TimeUnit.MILLISECONDS.toNanos(600L);

    private final MacroSettings settings;
    private final MacroRandom leafRandom;
    private final RotationEntropy rotationEntropy;
    private final DropLedger drop = new DropLedger();
    private final RewarpLedger rewarp = new RewarpLedger();
    private final CaptureIdentityLedger<CaptureKey> captures = new CaptureIdentityLedger<>();
    private final OwnedRotationLedger rotation = new OwnedRotationLedger();
    private State state = State.STOPPED;
    private float storedYaw;
    private float cardinalYaw;
    private Float storedPitch;
    private BlockPosition circularAnchor;
    private long cornerDwellAt;
    private long cornerDwellNanos;
    private boolean cornerDwellActive;
    private long recoveryUntil;
    private long pausedAt = Long.MIN_VALUE;
    private boolean anchorRefreshPending;
    private long runGeneration;

    public CircularCropMacro(MacroSettings settings, MacroRandom random) {
        this(settings, random, random);
    }

    public CircularCropMacro(
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
        return "circular-crop";
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
        if (cornerDwellActive) {
            cornerDwellAt = shift(cornerDwellAt, suspended);
        }
        switch (state) {
            case REWARP_DWELL, WARP_LANDING -> rewarp.shiftState(suspended);
            case REWARPING -> rewarp.shiftRequest(suspended);
            case AFTER_WARP, POST_REWARP -> recoveryUntil = shift(recoveryUntil, suspended);
            case STOPPED, STARTUP, NONE, A, D, S, W, DROPPING -> { }
        }
        pausedAt = Long.MIN_VALUE;
        anchorRefreshPending = true;
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
        RotationSnapshot playerRotation = player.rotation().get();
        CaptureAnchor anchor = new CaptureAnchor(
                block(position), body(position), playerRotation.yaw(), cardinalYaw,
                storedYaw, circularAnchor);
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
        if (anchorRefreshPending) {
            refreshAnchor(observed.position());
        }

        if (state == State.STARTUP) {
            return initialize(observed);
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
            recoveryUntil = shift(context.nowNanos(), POST_REWARP_NANOS);
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
        if (drop.shouldDrop(posture, observed.position().y())) {
            state = State.DROPPING;
            clearCornerDwell();
            rotation.clear();
            invalidateCapture();
            return MacroDecision.failClosed("dropping");
        }

        MacroDecision alignment = align(context);
        if (alignment != null) {
            return alignment;
        }
        if (state == State.NONE) {
            state = State.D;
            clearCornerDwell();
            invalidateCapture();
            return directionDecision(State.D);
        }
        return tickDirection(context, observed);
    }

    public State state() {
        return state;
    }

    Optional<dev.hylfrd.farmhelper.macro.MacroRotationRequest> pendingRotation() {
        return rotation.pending();
    }

    Optional<BlockPosition> spatialAnchor() {
        return Optional.ofNullable(circularAnchor);
    }

    float storedYaw() {
        return storedYaw;
    }

    float cardinalYaw() {
        return cardinalYaw;
    }

    boolean cornerDwellActive() {
        return cornerDwellActive;
    }

    long cornerDwellNanos() {
        return cornerDwellNanos;
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
        storedPitch = settings.customPitch()
                ? settings.customPitchLevel()
                : (float) (2.8D + randomUnit() * 0.5D);
        storedYaw = settings.customYaw()
                ? RotationTask.normalizeYaw(settings.customYawLevel())
                : MacroAngles.closestDiagonal(observed.rotation().yaw());
        cardinalYaw = MacroAngles.closestCardinal(storedYaw);
        state = State.NONE;
        refreshAnchor(observed.position());
        if (settings.dontFixAfterWarping()
                && Math.abs(MacroAngles.shortestDelta(
                observed.rotation().yaw(), storedYaw)) < 0.1F) {
            rotation.clear();
            return MacroDecision.failClosed("startup-fix-suppressed");
        }
        rotation.begin(observed.rotation().yaw(), observed.rotation().pitch(),
                storedYaw, storedPitch, RotationProfile.BACK,
                sampleRotationMillis(), rotationEntropy);
        invalidateCapture();
        return rotationDecision("startup-aligning");
    }

    private MacroDecision tickDirection(FarmingContext context, Observed observed) {
        if (!state.direction()) {
            return MacroDecision.idle(state.name().toLowerCase());
        }
        SpaceStatus ahead = directionalWalkability(observed, state);
        if (ahead == SpaceStatus.UNKNOWN) {
            clearCornerDwell();
            return MacroDecision.failClosed("circular-direction-unknown");
        }
        if (ahead == SpaceStatus.PASSABLE) {
            clearCornerDwell();
            return directionDecision(state);
        }
        if (!upstreamStopped(observed.motion())) {
            clearCornerDwell();
            return MacroDecision.failClosed("circular-corner-moving");
        }
        if (!cornerDwellActive) {
            cornerDwellActive = true;
            cornerDwellAt = context.nowNanos();
            cornerDwellNanos = TimeUnit.MILLISECONDS.toNanos(
                    400L + (long) Math.floor(randomUnit() * 200.0D));
            invalidateCapture();
            return MacroDecision.failClosed("circular-corner-dwell");
        }
        if (elapsed(context.nowNanos(), cornerDwellAt) < cornerDwellNanos) {
            return MacroDecision.failClosed("circular-corner-dwell");
        }
        state = nextDirection(state);
        clearCornerDwell();
        invalidateCapture();
        return directionDecision(state);
    }

    private MacroDecision tickDrop(Observed observed, PlayerPosture posture) {
        Optional<DropLedger.Landing> landing = drop.observeLanding(
                posture, observed.position().y());
        if (landing.isEmpty()) {
            return MacroDecision.failClosed("dropping");
        }
        state = State.NONE;
        clearCornerDwell();
        refreshAnchor(observed.position());
        if (landing.orElseThrow().changedLayer() && settings.rotateAfterDrop()) {
            storedYaw = MacroAngles.closestDiagonal(storedYaw + 180.0F);
            cardinalYaw = MacroAngles.closestCardinal(storedYaw);
            float pitch = storedPitch == null ? observed.rotation().pitch() : storedPitch;
            rotation.begin(observed.rotation().yaw(), observed.rotation().pitch(),
                    storedYaw, pitch, RotationProfile.BACK,
                    sampleDropRotationMillis(), rotationEntropy);
            invalidateCapture();
            return rotationDecision("drop-rotate-back");
        }
        return MacroDecision.failClosed(
                landing.orElseThrow().changedLayer() ? "drop-complete" : "drop-too-shallow");
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
        clearCornerDwell();
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
            state = State.NONE;
            refreshAnchor(observed.position());
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
            refreshAnchor(observed.position());
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
        recoveryUntil = shift(context.nowNanos(), AFTER_WARP_NANOS);
        invalidateCapture();
        return MacroDecision.failClosed("rewarp-confirmed-plot-"
                + rewarp.confirmedSpawn().map(MacroSpawnPose::plot).orElse(-1));
    }

    private MacroDecision finishPostRewarp(Observed observed) {
        if (settings.rotateAfterWarped()) {
            storedYaw = RotationTask.normalizeYaw(storedYaw + 180.0F);
        }
        cardinalYaw = MacroAngles.closestCardinal(storedYaw);
        float targetPitch = storedPitch == null ? observed.rotation().pitch() : storedPitch;
        clearWarpState();
        state = State.NONE;
        refreshAnchor(observed.position());
        float yawDistance = Math.abs(MacroAngles.shortestDelta(
                observed.rotation().yaw(), storedYaw));
        float pitchDistance = Math.abs(observed.rotation().pitch() - targetPitch);
        if (settings.dontFixAfterWarping()
                && Math.hypot(yawDistance, pitchDistance) < 1.0D) {
            rotation.clear();
            return MacroDecision.failClosed("post-rewarp-fix-suppressed");
        }
        long duration = sampleRotationMillis();
        if (yawDistance > 90.0F) {
            duration = Math.multiplyExact(duration, 2L);
        }
        rotation.begin(observed.rotation().yaw(), observed.rotation().pitch(),
                storedYaw, targetPitch, RotationProfile.BACK, duration, rotationEntropy);
        invalidateCapture();
        return rotationDecision(settings.rotateAfterWarped()
                ? "post-rewarp-circular-back" : "post-rewarp-saved-back");
    }

    private MacroDecision align(FarmingContext context) {
        if (rotation.pending().isEmpty()) {
            return null;
        }
        return switch (rotation.observe(context)) {
            case NONE, COMPLETED -> null;
            case ACTIVE, UNACKNOWLEDGED, RETRYABLE_CANCELLATION -> rotationDecision("aligning");
            case CANCELLED -> MacroDecision.failClosed("rotation-cancelled");
            case STALE_ACKNOWLEDGEMENT -> MacroDecision.failClosed(
                    "rotation-acknowledgement-stale");
        };
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

    private MacroDecision directionDecision(State direction) {
        EnumSet<InputAction> inputs = EnumSet.of(InputAction.ATTACK);
        inputs.add(switch (direction) {
            case A -> InputAction.LEFT;
            case D -> InputAction.RIGHT;
            case S -> InputAction.BACKWARD;
            case W -> InputAction.FORWARD;
            default -> throw new IllegalArgumentException("not a circular direction: " + direction);
        });
        return decision(inputs, "circular-" + direction.name().toLowerCase());
    }

    private SpaceStatus directionalWalkability(Observed observed, State direction) {
        return SpatialQueries.walkability(
                observed.spatial(), observed.worldEpoch(),
                movedBody(observed.position(), observed.cardinalFrame(), direction));
    }

    private Set<BlockPosition> requestedBlocks(PositionSnapshot position) {
        Set<BlockPosition> blocks = new HashSet<>();
        addBoxBlocks(blocks, body(position));
        if (state.direction()) {
            addWalkabilityBlocks(blocks,
                    movedBody(position, RelativeFrame.cardinal(cardinalYaw), state));
        }
        return blocks;
    }

    private static BoxSnapshot movedBody(
            PositionSnapshot position,
            RelativeFrame frame,
            State direction
    ) {
        double forward = switch (direction) {
            case W -> 1.0D;
            case S -> -1.0D;
            case A, D -> 0.0D;
            default -> throw new IllegalArgumentException("not a circular direction: " + direction);
        };
        double right = switch (direction) {
            case D -> 1.0D;
            case A -> -1.0D;
            case S, W -> 0.0D;
            default -> throw new IllegalArgumentException("not a circular direction: " + direction);
        };
        return body(position).move(
                frame.forwardX() * forward + frame.rightX() * right,
                0.0D,
                frame.forwardZ() * forward + frame.rightZ() * right);
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

    private void refreshAnchor(PositionSnapshot position) {
        circularAnchor = block(position);
        drop.arm(position.y());
        anchorRefreshPending = false;
        invalidateCapture();
    }

    private void clearCornerDwell() {
        cornerDwellAt = 0L;
        cornerDwellNanos = 0L;
        cornerDwellActive = false;
    }

    private void clearWarpState() {
        rewarp.clear();
    }

    private void reset(State next) {
        state = next;
        storedYaw = 0.0F;
        cardinalYaw = 0.0F;
        storedPitch = null;
        circularAnchor = null;
        clearCornerDwell();
        recoveryUntil = 0L;
        pausedAt = Long.MIN_VALUE;
        anchorRefreshPending = true;
        runGeneration = incrementPositive(runGeneration);
        rotation.clear();
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
                cornerDwellActive, worldEpoch, anchor);
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
        RotationSnapshot playerRotation = player.rotation().get();
        CaptureAnchor anchor = new CaptureAnchor(
                block(position), body(position), playerRotation.yaw(), cardinalYaw,
                storedYaw, circularAnchor);
        CaptureKey key = captureKey(context.worldEpoch(), anchor);
        SpatialSnapshot spatial = context.spatial().get();
        if (!captures.accepts(key, spatial, anchor.body())) {
            invalidateCapture();
            return null;
        }
        captures.complete();
        return new Observed(
                context.nowNanos(), context.worldEpoch(), position, player.motion().get(),
                playerRotation, RelativeFrame.cardinal(cardinalYaw), spatial);
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

    private static boolean upstreamStopped(MotionSnapshot motion) {
        return Math.abs(motion.x()) < 0.01D
                && Math.abs(motion.y()) < 0.01D
                && Math.abs(motion.z()) < 0.01D;
    }

    private static State nextDirection(State current) {
        return switch (current) {
            case D -> State.S;
            case S -> State.A;
            case A -> State.W;
            case W -> State.D;
            default -> throw new IllegalArgumentException("not a circular direction: " + current);
        };
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
        NONE,
        A,
        D,
        S,
        W,
        DROPPING,
        REWARP_DWELL,
        REWARPING,
        WARP_LANDING,
        AFTER_WARP,
        POST_REWARP;

        private boolean direction() {
            return this == A || this == D || this == S || this == W;
        }
    }

    private record CaptureAnchor(
            BlockPosition block,
            BoxSnapshot body,
            float currentYaw,
            float cardinalYaw,
            float storedYaw,
            BlockPosition circularAnchor
    ) {
    }

    private record CaptureKey(
            long runGeneration,
            long captureGeneration,
            long capturePhase,
            State state,
            boolean cornerDwellActive,
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
