package dev.hylfrd.farmhelper.runtime.interaction;

import dev.hylfrd.farmhelper.runtime.snapshot.Observation;
import dev.hylfrd.farmhelper.runtime.spatial.BlockPosition;
import dev.hylfrd.farmhelper.runtime.spatial.BlockStateSnapshot;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/**
 * Client-thread bridge for the two fixed-upstream block interaction signals.
 *
 * <p>{@link #emitClickIntent(BlockPosition, BlockInteractionFace)} is deliberately raw and has no
 * feature/configuration gate: the upstream attack seam observes an attempt. A destroy invocation
 * captures its pre-state at HEAD and is completed at RETURN; only a true return becomes
 * {@link BlockInteractionSignalType#BREAK_SUCCESS}. The captured generation and world epoch fence
 * that delayed return so a lifecycle boundary cannot publish a stale success.</p>
 */
public final class BlockInteractionSignalBridge {
    private final LongSupplier currentGeneration;
    private final LongSupplier currentWorldEpoch;
    private final Consumer<BlockInteractionSignal> sink;
    private final Deque<PendingBreak> pendingBreaks = new ArrayDeque<>();

    public BlockInteractionSignalBridge(
            LongSupplier currentGeneration,
            LongSupplier currentWorldEpoch,
            Consumer<BlockInteractionSignal> sink
    ) {
        this.currentGeneration = Objects.requireNonNull(currentGeneration, "currentGeneration");
        this.currentWorldEpoch = Objects.requireNonNull(currentWorldEpoch, "currentWorldEpoch");
        this.sink = Objects.requireNonNull(sink, "sink");
    }

    /** Emits every attack attempt, including attempts rejected by later feature/config gates. */
    public void emitClickIntent(BlockPosition position, BlockInteractionFace face) {
        Objects.requireNonNull(position, "position");
        Objects.requireNonNull(face, "face");
        sink.accept(new BlockInteractionSignal(
                BlockInteractionSignalType.CLICK_INTENT,
                position,
                face,
                Observation.unknown(),
                generation(),
                worldEpoch()));
    }

    /** Captures one destroy invocation's pre-mutation observation at its HEAD seam. */
    public void beginBreak(
            BlockPosition position,
            Observation<BlockStateSnapshot> preBreakState
    ) {
        pendingBreaks.addLast(new PendingBreak(
                Objects.requireNonNull(position, "position"),
                Objects.requireNonNull(preBreakState, "preBreakState"),
                generation(),
                worldEpoch()));
    }

    /**
     * Closes the most recent destroy invocation. False returns and stale lifecycle generations are
     * intentionally silent; each true, current-generation invocation publishes independently.
     */
    public void completeBreak(boolean destroySucceeded) {
        PendingBreak pending = pendingBreaks.pollLast();
        if (pending == null || !destroySucceeded) {
            return;
        }
        if (pending.generation() != generation() || pending.worldEpoch() != worldEpoch()) {
            return;
        }
        sink.accept(new BlockInteractionSignal(
                BlockInteractionSignalType.BREAK_SUCCESS,
                pending.position(),
                BlockInteractionFace.UNKNOWN,
                pending.preBreakState(),
                pending.generation(),
                pending.worldEpoch()));
    }

    private long generation() {
        return nonNegative(currentGeneration.getAsLong(), "generation");
    }

    private long worldEpoch() {
        return nonNegative(currentWorldEpoch.getAsLong(), "worldEpoch");
    }

    private static long nonNegative(long value, String name) {
        if (value < 0L) {
            throw new IllegalStateException(name + " must be non-negative");
        }
        return value;
    }

    private record PendingBreak(
            BlockPosition position,
            Observation<BlockStateSnapshot> preBreakState,
            long generation,
            long worldEpoch
    ) {
    }
}
