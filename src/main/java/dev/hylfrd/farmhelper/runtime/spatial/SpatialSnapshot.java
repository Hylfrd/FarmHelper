package dev.hylfrd.farmhelper.runtime.spatial;

import dev.hylfrd.farmhelper.runtime.snapshot.Observation;

import java.util.Map;
import java.util.Objects;

/** Immutable, bounded spatial observation for one caller-managed world epoch. maxY is exclusive. */
public record SpatialSnapshot(
        long worldEpoch,
        BoxSnapshot bounds,
        int minY,
        int maxY,
        BoxSnapshot playerBox,
        Map<ChunkPosition, ChunkSnapshot> chunks
) {
    public SpatialSnapshot {
        Objects.requireNonNull(bounds, "bounds");
        Objects.requireNonNull(playerBox, "playerBox");
        Objects.requireNonNull(chunks, "chunks");
        if (!bounds.hasPositiveVolume()) {
            throw new IllegalArgumentException("bounds must have positive volume");
        }
        if (maxY <= minY) {
            throw new IllegalArgumentException("maxY must be greater than minY");
        }
        chunks = Map.copyOf(chunks);
        for (Map.Entry<ChunkPosition, ChunkSnapshot> entry : chunks.entrySet()) {
            if (!entry.getKey().equals(entry.getValue().position())) {
                throw new IllegalArgumentException("chunk key does not match snapshot position");
            }
        }
    }

    public Observation<BlockStateSnapshot> block(long expectedWorldEpoch, BlockPosition position) {
        Objects.requireNonNull(position, "position");
        if (expectedWorldEpoch != worldEpoch || position.y() < minY || position.y() >= maxY
                || !bounds.intersects(position.unitBox())) {
            return Observation.unknown();
        }
        ChunkSnapshot chunk = chunks.get(position.chunk());
        return chunk == null ? Observation.unknown() : chunk.block(position);
    }
}
