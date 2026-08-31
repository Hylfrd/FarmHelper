package dev.hylfrd.farmhelper.feature.bps;

import dev.hylfrd.farmhelper.macro.MacroCrop;
import dev.hylfrd.farmhelper.macro.MacroLifecycleParticipant;
import dev.hylfrd.farmhelper.macro.MacroPauseCause;
import dev.hylfrd.farmhelper.macro.MacroTerminalReason;
import dev.hylfrd.farmhelper.runtime.interaction.BlockInteractionSignal;
import dev.hylfrd.farmhelper.runtime.interaction.BlockInteractionSignalType;
import dev.hylfrd.farmhelper.runtime.snapshot.Observation;
import dev.hylfrd.farmhelper.runtime.snapshot.ResourceIdentifier;
import dev.hylfrd.farmhelper.runtime.spatial.BlockStateSnapshot;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Objects;
import java.util.OptionalLong;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Client-thread rolling blocks-per-second tracker for immutable interaction evidence.
 *
 * <p>The tracker is an uninstalled lifecycle participant: later composition may attach it to the
 * macro lifecycle and route block signals into {@link #record(BlockInteractionSignal, MacroCrop,
 * long)}. It owns no client callbacks, global state, wall clock, HUD formatting, or profit data.
 * Pause time is excluded from the rolling window, matching upstream's timestamp adjustment.</p>
 */
public final class BpsTracker implements MacroLifecycleParticipant {
    public static final long DEFAULT_WINDOW_NANOS = TimeUnit.SECONDS.toNanos(10L);
    public static final int DEFAULT_MAX_RETAINED_BLOCKS = 4_096;

    private static final long UNSET = Long.MIN_VALUE;
    private static final double NANOS_PER_SECOND = TimeUnit.SECONDS.toNanos(1L);

    public enum State {
        STOPPED,
        RUNNING,
        PAUSED
    }

    private final long windowNanos;
    private final int maxRetainedBlocks;
    private final Deque<Long> breakTimes = new ArrayDeque<>();

    private State state = State.STOPPED;
    private long generation;
    private long worldEpoch = UNSET;
    private long runStartedAtNanos = UNSET;
    private long lastNowNanos = UNSET;
    private long pauseStartedAtNanos = UNSET;
    private long pausedDurationNanos;
    private long windowStartedAtActiveNanos;
    private long capacityLimitedUntilActiveNanos = UNSET;

    public BpsTracker() {
        this(DEFAULT_WINDOW_NANOS, DEFAULT_MAX_RETAINED_BLOCKS);
    }

    public BpsTracker(long windowNanos, int maxRetainedBlocks) {
        if (windowNanos <= 0L) {
            throw new IllegalArgumentException("windowNanos must be positive");
        }
        if (maxRetainedBlocks <= 0) {
            throw new IllegalArgumentException("maxRetainedBlocks must be positive");
        }
        this.windowNanos = windowNanos;
        this.maxRetainedBlocks = maxRetainedBlocks;
    }

    public State state() {
        return state;
    }

    public long generation() {
        return generation;
    }

    public OptionalLong worldEpoch() {
        return worldEpoch == UNSET ? OptionalLong.empty() : OptionalLong.of(worldEpoch);
    }

    public long windowNanos() {
        return windowNanos;
    }

    public int maxRetainedBlocks() {
        return maxRetainedBlocks;
    }

    @Override
    public void started(long generation, long nowNanos) {
        if (generation <= 0L) {
            throw new IllegalArgumentException("generation must be positive");
        }
        requireNonNegative(nowNanos, "nowNanos");
        breakTimes.clear();
        state = State.RUNNING;
        this.generation = generation;
        worldEpoch = UNSET;
        runStartedAtNanos = nowNanos;
        lastNowNanos = nowNanos;
        pauseStartedAtNanos = UNSET;
        pausedDurationNanos = 0L;
        windowStartedAtActiveNanos = 0L;
        capacityLimitedUntilActiveNanos = UNSET;
    }

    @Override
    public void paused(
            long generation,
            long nowNanos,
            Set<MacroPauseCause> causes
    ) {
        Objects.requireNonNull(causes, "causes");
        if (!matchesActiveGeneration(generation)) {
            return;
        }
        observeNow(nowNanos);
        if (state == State.RUNNING) {
            pauseStartedAtNanos = nowNanos;
            state = State.PAUSED;
        }
    }

    @Override
    public void resumed(long generation, long nowNanos) {
        if (!matchesActiveGeneration(generation)) {
            return;
        }
        observeNow(nowNanos);
        if (state != State.PAUSED) {
            return;
        }
        pausedDurationNanos = Math.addExact(
                pausedDurationNanos,
                nowNanos - pauseStartedAtNanos);
        pauseStartedAtNanos = UNSET;
        state = State.RUNNING;
    }

    @Override
    public void stopped(long generation, MacroTerminalReason reason) {
        Objects.requireNonNull(reason, "reason");
        if (!matchesActiveGeneration(generation)) {
            return;
        }
        clearStopped();
    }

    /**
     * Records one successful crop break. Every accepted success is counted; position and timestamp
     * equality do not deduplicate the bridge's distinct successful destroy calls.
     */
    public BpsRecordResult record(
            BlockInteractionSignal signal,
            MacroCrop activeCrop,
            long nowNanos
    ) {
        Objects.requireNonNull(signal, "signal");
        Objects.requireNonNull(activeCrop, "activeCrop");
        requireNonNegative(nowNanos, "nowNanos");
        if (state == State.STOPPED) {
            return BpsRecordResult.STOPPED;
        }
        if (signal.generation() != generation) {
            return BpsRecordResult.STALE_GENERATION;
        }

        observeNow(nowNanos);
        long activeNowNanos = activeTime(nowNanos);
        if (worldEpoch != UNSET && signal.worldEpoch() < worldEpoch) {
            return BpsRecordResult.STALE_WORLD_EPOCH;
        }
        if (worldEpoch == UNSET) {
            worldEpoch = signal.worldEpoch();
        } else if (signal.worldEpoch() > worldEpoch) {
            beginWorldEpoch(signal.worldEpoch(), activeNowNanos);
        }
        prune(activeNowNanos);

        if (state == State.PAUSED) {
            return BpsRecordResult.PAUSED;
        }
        if (signal.type() != BlockInteractionSignalType.BREAK_SUCCESS) {
            return BpsRecordResult.NOT_BREAK_SUCCESS;
        }
        Observation<BlockStateSnapshot> preBreakState = signal.preBreakState();
        if (!preBreakState.isPresent()) {
            return BpsRecordResult.BLOCK_STATE_UNAVAILABLE;
        }
        if (!matchesCrop(activeCrop, preBreakState.get())) {
            return BpsRecordResult.CROP_MISMATCH;
        }

        if (breakTimes.size() == maxRetainedBlocks) {
            long evictedAt = breakTimes.removeFirst();
            capacityLimitedUntilActiveNanos = Math.max(
                    capacityLimitedUntilActiveNanos,
                    saturatedAdd(evictedAt, windowNanos));
        }
        breakTimes.addLast(activeNowNanos);
        return BpsRecordResult.COUNTED;
    }

    /** Returns the current rolling rate, pruning evidence older than the active-time window. */
    public BpsSnapshot snapshot(long nowNanos) {
        requireNonNegative(nowNanos, "nowNanos");
        if (state == State.STOPPED) {
            return BpsSnapshot.ZERO;
        }
        observeNow(nowNanos);
        long activeNowNanos = activeTime(nowNanos);
        prune(activeNowNanos);

        long rollingStart = activeNowNanos > windowNanos
                ? activeNowNanos - windowNanos
                : 0L;
        long effectiveStart = Math.max(windowStartedAtActiveNanos, rollingStart);
        long elapsedNanos = activeNowNanos - effectiveStart;
        double blocksPerSecond = elapsedNanos == 0L
                ? 0.0D
                : breakTimes.size() * NANOS_PER_SECOND / elapsedNanos;
        boolean capacityLimited = capacityLimitedUntilActiveNanos != UNSET
                && activeNowNanos <= capacityLimitedUntilActiveNanos;
        return new BpsSnapshot(
                blocksPerSecond,
                breakTimes.size(),
                elapsedNanos,
                capacityLimited);
    }

    private boolean matchesActiveGeneration(long candidateGeneration) {
        return state != State.STOPPED && candidateGeneration == generation;
    }

    private void beginWorldEpoch(long nextWorldEpoch, long activeNowNanos) {
        breakTimes.clear();
        worldEpoch = nextWorldEpoch;
        windowStartedAtActiveNanos = activeNowNanos;
        capacityLimitedUntilActiveNanos = UNSET;
    }

    private long activeTime(long nowNanos) {
        long effectiveNowNanos = state == State.PAUSED ? pauseStartedAtNanos : nowNanos;
        return effectiveNowNanos - runStartedAtNanos - pausedDurationNanos;
    }

    private void prune(long activeNowNanos) {
        if (activeNowNanos > windowNanos) {
            long cutoff = activeNowNanos - windowNanos;
            while (!breakTimes.isEmpty() && breakTimes.getFirst() < cutoff) {
                breakTimes.removeFirst();
            }
        }
        if (capacityLimitedUntilActiveNanos != UNSET
                && activeNowNanos > capacityLimitedUntilActiveNanos) {
            capacityLimitedUntilActiveNanos = UNSET;
        }
    }

    private void observeNow(long nowNanos) {
        requireNonNegative(nowNanos, "nowNanos");
        if (lastNowNanos != UNSET && nowNanos < lastNowNanos) {
            throw new IllegalArgumentException("nowNanos must not move backwards");
        }
        lastNowNanos = nowNanos;
    }

    private void clearStopped() {
        breakTimes.clear();
        state = State.STOPPED;
        generation = 0L;
        worldEpoch = UNSET;
        runStartedAtNanos = UNSET;
        lastNowNanos = UNSET;
        pauseStartedAtNanos = UNSET;
        pausedDurationNanos = 0L;
        windowStartedAtActiveNanos = 0L;
        capacityLimitedUntilActiveNanos = UNSET;
    }

    private static boolean matchesCrop(MacroCrop activeCrop, BlockStateSnapshot state) {
        ResourceIdentifier blockId = state.blockId();
        if (!"minecraft".equals(blockId.namespace())) {
            return false;
        }
        String path = blockId.path();
        return switch (activeCrop) {
            case WHEAT -> "wheat".equals(path);
            case CARROT -> "carrots".equals(path);
            case POTATO -> "potatoes".equals(path);
            case NETHER_WART -> "nether_wart".equals(path);
            case PUMPKIN -> "pumpkin".equals(path);
            case MELON -> "melon".equals(path);
            case CACTUS -> "cactus".equals(path);
            case SUGAR_CANE -> "sugar_cane".equals(path);
            case COCOA -> "cocoa".equals(path);
            case RED_MUSHROOM, BROWN_MUSHROOM ->
                    "red_mushroom".equals(path) || "brown_mushroom".equals(path);
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
            throw new IllegalArgumentException(name + " must be non-negative");
        }
    }
}
