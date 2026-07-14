package dev.hylfrd.farmhelper.runtime.spatial;

import dev.hylfrd.farmhelper.runtime.snapshot.Observation;

import java.util.Map;
import java.util.Objects;

/** Immutable result for one requested chunk; an unloaded chunk contains no fabricated air. */
public record ChunkSnapshot(
        ChunkPosition position,
        boolean loaded,
        Map<BlockPosition, Observation<BlockStateSnapshot>> blocks
) {
    public ChunkSnapshot {
        Objects.requireNonNull(position, "position");
        Objects.requireNonNull(blocks, "blocks");
        blocks = Map.copyOf(blocks);
        for (BlockPosition block : blocks.keySet()) {
            if (!position.equals(block.chunk())) {
                throw new IllegalArgumentException("block does not belong to chunk: " + block);
            }
        }
        if (!loaded && blocks.values().stream().anyMatch(Observation::isPresent)) {
            throw new IllegalArgumentException("an unloaded chunk cannot contain known block states");
        }
    }

    public Observation<BlockStateSnapshot> block(BlockPosition position) {
        Objects.requireNonNull(position, "position");
        if (!loaded || !this.position.equals(position.chunk())) {
            return Observation.unknown();
        }
        return blocks.getOrDefault(position, Observation.unknown());
    }
}
