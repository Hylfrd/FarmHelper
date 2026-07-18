package dev.hylfrd.farmhelper.feature.desync;

import dev.hylfrd.farmhelper.config.FarmHelperConfig;
import dev.hylfrd.farmhelper.macro.FeatureSuspension;
import dev.hylfrd.farmhelper.macro.MacroCrop;
import dev.hylfrd.farmhelper.macro.MacroManager;
import dev.hylfrd.farmhelper.macro.ServerResponsiveness;
import dev.hylfrd.farmhelper.runtime.snapshot.Observation;
import dev.hylfrd.farmhelper.runtime.snapshot.ResourceIdentifier;
import dev.hylfrd.farmhelper.runtime.spatial.BlockPosition;
import dev.hylfrd.farmhelper.runtime.spatial.BlockStateSnapshot;
import dev.hylfrd.farmhelper.runtime.spatial.CropBlockKind;
import dev.hylfrd.farmhelper.runtime.spatial.CropObservation;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Objects;
import java.util.OptionalLong;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * Minecraft-free port of upstream DesyncChecker's accepted-click window and delayed recovery.
 *
 * <p>The caller supplies immutable block observations captured on the client thread. Lag readiness
 * must come from the shared P1 server-time tracker as {@link ServerResponsiveness}; this class does
 * not own a clock or infer server health. Macro pause ownership is the existing
 * {@link FeatureSuspension} lease from {@link MacroManager}.
 */
public final class DesyncChecker {
    public static final int WINDOW_SIZE = 60;
    public static final int TRIGGER_COUNT = 45;
    private static final String SUSPENSION_OWNER = "desync";

    public enum State {
        STOPPED,
        COLLECTING,
        RECOVERING
    }

    private final FarmHelperConfig config;
    private final MacroManager macroManager;
    private final Deque<DesyncClick> acceptedClicks = new ArrayDeque<>(WINDOW_SIZE);

    private State state = State.STOPPED;
    private long macroGeneration;
    private long worldEpoch;
    private long recoveryDueNanos = Long.MIN_VALUE;
    private FeatureSuspension recoverySuspension;

    public DesyncChecker(FarmHelperConfig config, MacroManager macroManager) {
        this.config = Objects.requireNonNull(config, "config");
        this.macroManager = Objects.requireNonNull(macroManager, "macroManager");
    }

    public State state() {
        return state;
    }

    public int acceptedClickCount() {
        return acceptedClicks.size();
    }

    public long macroGeneration() {
        return macroGeneration;
    }

    public long worldEpoch() {
        return worldEpoch;
    }

    public OptionalLong recoveryDueNanos() {
        return state == State.RECOVERING
                ? OptionalLong.of(recoveryDueNanos)
                : OptionalLong.empty();
    }

    /**
     * Starts a fresh collection window for the MacroManager's exact active generation.
     */
    public void start(long worldEpoch) {
        if (worldEpoch < 0L) {
            throw new IllegalArgumentException("world epoch must not be negative");
        }
        if (!macroManager.enabled()) {
            throw new IllegalStateException("cannot start DesyncChecker for a stopped macro");
        }
        stop();
        macroGeneration = macroManager.generation();
        this.worldEpoch = worldEpoch;
        state = State.COLLECTING;
    }

    /**
     * Applies the upstream acceptance order, followed by the required conservative connection and
     * shared server-readiness gates.
     */
    public DesyncCheckResult recordClick(
            DesyncClick click,
            MacroCrop activeCrop,
            boolean failsafeActive,
            boolean connectionReady,
            ServerResponsiveness serverResponsiveness,
            Function<BlockPosition, Observation<BlockStateSnapshot>> currentBlocks,
            long nowNanos
    ) {
        Objects.requireNonNull(click, "click");
        Objects.requireNonNull(activeCrop, "activeCrop");
        Objects.requireNonNull(serverResponsiveness, "serverResponsiveness");
        Objects.requireNonNull(currentBlocks, "currentBlocks");
        requireNonNegative(nowNanos, "nowNanos");

        if (!config.checkDesync()) {
            return DesyncCheckResult.DISABLED;
        }
        if (state == State.STOPPED) {
            return DesyncCheckResult.STOPPED;
        }
        if (!matchesActiveIdentity(click.macroGeneration(), click.worldEpoch())) {
            return DesyncCheckResult.STALE_IDENTITY;
        }
        if (!macroManager.enabled() || macroManager.generation() != macroGeneration) {
            stop();
            return DesyncCheckResult.MACRO_INACTIVE;
        }

        Observation<CropObservation> clickedCrop = crop(click.clickedBlock());
        if (clickedCrop.isUnknown()) {
            return DesyncCheckResult.CLICK_BLOCK_UNKNOWN;
        }
        if (!clickedCrop.isPresent() || !clickedCrop.get().directlyHarvestable()) {
            return DesyncCheckResult.NOT_CROP;
        }
        if (failsafeActive) {
            return DesyncCheckResult.FAILSAFE_ACTIVE;
        }
        if (!connectionReady) {
            connectionLost();
            return DesyncCheckResult.CONNECTION_UNAVAILABLE;
        }
        if (serverResponsiveness == ServerResponsiveness.UNKNOWN) {
            acceptedClicks.clear();
            return DesyncCheckResult.SERVER_UNKNOWN;
        }
        if (serverResponsiveness == ServerResponsiveness.LAGGING) {
            acceptedClicks.clear();
            return DesyncCheckResult.SERVER_LAGGING;
        }
        if (state == State.RECOVERING) {
            return DesyncCheckResult.RECOVERY_PENDING;
        }

        if (acceptedClicks.size() == WINDOW_SIZE) {
            acceptedClicks.removeFirst();
        }
        acceptedClicks.addLast(click);
        if (acceptedClicks.size() < WINDOW_SIZE) {
            return DesyncCheckResult.ACCEPTED;
        }

        int matching = 0;
        for (DesyncClick accepted : acceptedClicks) {
            Observation<BlockStateSnapshot> current = currentBlock(currentBlocks, accepted.position());
            if (matchesUpstreamDesyncEvidence(activeCrop, current)) {
                matching++;
            }
        }
        if (matching < TRIGGER_COUNT) {
            return DesyncCheckResult.ACCEPTED;
        }

        beginRecovery(nowNanos);
        return DesyncCheckResult.TRIGGERED;
    }

    /**
     * Advances delayed recovery without an off-thread scheduler. The caller must pass the same
     * shared server-readiness observation used by the macro tick.
     */
    public DesyncCheckResult tickRecovery(
            long nowNanos,
            long macroGeneration,
            long worldEpoch,
            boolean connectionReady,
            ServerResponsiveness serverResponsiveness
    ) {
        requireNonNegative(nowNanos, "nowNanos");
        Objects.requireNonNull(serverResponsiveness, "serverResponsiveness");
        if (state == State.STOPPED) {
            return DesyncCheckResult.STOPPED;
        }
        if (state != State.RECOVERING) {
            return DesyncCheckResult.ACCEPTED;
        }
        if (!matchesActiveIdentity(macroGeneration, worldEpoch)
                || !macroManager.enabled()
                || macroManager.generation() != this.macroGeneration) {
            stop();
            return DesyncCheckResult.STALE_IDENTITY;
        }
        if (!connectionReady) {
            connectionLost();
            return DesyncCheckResult.CONNECTION_UNAVAILABLE;
        }
        if (nowNanos < recoveryDueNanos) {
            return DesyncCheckResult.RECOVERY_PENDING;
        }
        if (serverResponsiveness == ServerResponsiveness.UNKNOWN) {
            return DesyncCheckResult.SERVER_UNKNOWN;
        }
        if (serverResponsiveness == ServerResponsiveness.LAGGING) {
            return DesyncCheckResult.SERVER_LAGGING;
        }

        FeatureSuspension suspension = recoverySuspension;
        recoverySuspension = null;
        recoveryDueNanos = Long.MIN_VALUE;
        acceptedClicks.clear();
        state = State.COLLECTING;
        if (suspension != null) {
            suspension.close();
        }
        return DesyncCheckResult.RECOVERED;
    }

    /** Upstream clears the accepted-click FIFO whenever the macro pauses. */
    public void pause() {
        acceptedClicks.clear();
    }

    /** Upstream Feature.resume has no Desync-specific transition. */
    public void resume() {
        // Intentionally no-op. Releasing this feature's exact suspension is tickRecovery's job.
    }

    public void reset() {
        stop();
    }

    public void stop() {
        acceptedClicks.clear();
        FeatureSuspension suspension = recoverySuspension;
        recoverySuspension = null;
        recoveryDueNanos = Long.MIN_VALUE;
        state = State.STOPPED;
        macroGeneration = 0L;
        worldEpoch = 0L;
        if (suspension != null) {
            suspension.close();
        }
    }

    public void worldChanged(long nextWorldEpoch) {
        if (nextWorldEpoch < 0L) {
            throw new IllegalArgumentException("world epoch must not be negative");
        }
        stop();
    }

    public void connectionLost() {
        stop();
    }

    private boolean matchesActiveIdentity(long macroGeneration, long worldEpoch) {
        return macroGeneration == this.macroGeneration && worldEpoch == this.worldEpoch;
    }

    private void beginRecovery(long nowNanos) {
        acceptedClicks.clear();
        recoveryDueNanos = saturatedAdd(nowNanos,
                TimeUnit.MILLISECONDS.toNanos(config.desyncPauseDelayMillis()));
        state = State.RECOVERING;
        try {
            recoverySuspension = macroManager.suspendForFeature(SUSPENSION_OWNER);
        } catch (RuntimeException | Error failure) {
            recoveryDueNanos = Long.MIN_VALUE;
            state = State.COLLECTING;
            throw failure;
        }
    }

    private static Observation<CropObservation> crop(
            Observation<BlockStateSnapshot> block
    ) {
        if (block.isUnknown()) {
            return Observation.unknown();
        }
        if (block.isAbsent()) {
            return Observation.absent();
        }
        return CropObservation.observe(block.get());
    }

    private static Observation<BlockStateSnapshot> currentBlock(
            Function<BlockPosition, Observation<BlockStateSnapshot>> currentBlocks,
            BlockPosition position
    ) {
        Observation<BlockStateSnapshot> observed = currentBlocks.apply(position);
        return observed == null ? Observation.unknown() : observed;
    }

    private static boolean matchesUpstreamDesyncEvidence(
            MacroCrop activeCrop,
            Observation<BlockStateSnapshot> block
    ) {
        if (!block.isPresent()) {
            return false;
        }
        BlockStateSnapshot state = block.get();
        if (activeCrop == MacroCrop.MELON || activeCrop == MacroCrop.PUMPKIN) {
            return !isAir(state.blockId());
        }
        Observation<CropObservation> observed = CropObservation.observe(state);
        if (!observed.isPresent()) {
            return false;
        }
        CropObservation crop = observed.get();
        return switch (activeCrop) {
            case NETHER_WART -> crop.kind() == CropBlockKind.NETHER_WART && crop.mature();
            case SUGAR_CANE -> crop.kind() == CropBlockKind.SUGAR_CANE;
            case CACTUS -> crop.kind() == CropBlockKind.CACTUS;
            case RED_MUSHROOM, BROWN_MUSHROOM ->
                    crop.kind() == CropBlockKind.RED_MUSHROOM
                            || crop.kind() == CropBlockKind.BROWN_MUSHROOM;
            case COCOA -> crop.kind() == CropBlockKind.COCOA && crop.mature();
            case CARROT -> crop.kind() == CropBlockKind.CARROT && crop.mature();
            case POTATO -> crop.kind() == CropBlockKind.POTATO && crop.mature();
            case WHEAT -> crop.kind() == CropBlockKind.WHEAT && crop.mature();
            case MELON, PUMPKIN -> throw new IllegalStateException("handled above");
        };
    }

    private static boolean isAir(ResourceIdentifier identifier) {
        if (!"minecraft".equals(identifier.namespace())) {
            return false;
        }
        return switch (identifier.path()) {
            case "air", "cave_air", "void_air" -> true;
            default -> false;
        };
    }

    private static long saturatedAdd(long first, long second) {
        try {
            return Math.addExact(first, second);
        } catch (ArithmeticException exception) {
            return Long.MAX_VALUE;
        }
    }

    private static void requireNonNegative(long value, String name) {
        if (value < 0L) {
            throw new IllegalArgumentException(name + " must not be negative");
        }
    }
}
