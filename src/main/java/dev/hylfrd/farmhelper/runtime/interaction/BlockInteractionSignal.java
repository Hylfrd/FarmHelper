package dev.hylfrd.farmhelper.runtime.interaction;

import dev.hylfrd.farmhelper.runtime.snapshot.Observation;
import dev.hylfrd.farmhelper.runtime.spatial.BlockPosition;
import dev.hylfrd.farmhelper.runtime.spatial.BlockStateSnapshot;

import java.util.Objects;

/**
 * One immutable block interaction observation. A click is an attempt observed at the attack seam;
 * a break-success signal is emitted only after modern {@code destroyBlock} returns true and keeps
 * the state observed before that method was allowed to mutate the world.
 */
public record BlockInteractionSignal(
        BlockInteractionSignalType type,
        BlockPosition position,
        BlockInteractionFace face,
        Observation<BlockStateSnapshot> preBreakState,
        long generation,
        long worldEpoch
) {
    public BlockInteractionSignal {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(position, "position");
        Objects.requireNonNull(face, "face");
        Objects.requireNonNull(preBreakState, "preBreakState");
        if (generation < 0L) {
            throw new IllegalArgumentException("generation must be non-negative");
        }
        if (worldEpoch < 0L) {
            throw new IllegalArgumentException("worldEpoch must be non-negative");
        }
        if (type == BlockInteractionSignalType.BREAK_SUCCESS
                && face != BlockInteractionFace.UNKNOWN) {
            throw new IllegalArgumentException("BREAK_SUCCESS must carry UNKNOWN face");
        }
    }
}
