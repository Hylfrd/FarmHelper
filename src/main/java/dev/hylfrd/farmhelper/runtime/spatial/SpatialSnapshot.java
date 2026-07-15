package dev.hylfrd.farmhelper.runtime.spatial;

import dev.hylfrd.farmhelper.runtime.snapshot.Observation;

import java.util.Map;
import java.util.Objects;

/** Immutable, bounded spatial observation for one caller-managed world epoch. maxY is exclusive. */
public record SpatialSnapshot(
        long worldEpoch,
        long requestToken,
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
        if (requestToken < 0L) {
            throw new IllegalArgumentException("request token must not be negative");
        }
        if (!bounds.hasPositiveVolume()) {
            throw new IllegalArgumentException("bounds must have positive volume");
        }
        if (bounds.width() > SpatialCaptureRequest.MAX_AXIS_SPAN
                || bounds.height() > SpatialCaptureRequest.MAX_AXIS_SPAN
                || bounds.depth() > SpatialCaptureRequest.MAX_AXIS_SPAN) {
            throw new IllegalArgumentException("snapshot bounds exceed the per-axis limit");
        }
        if (maxY <= minY) {
            throw new IllegalArgumentException("maxY must be greater than minY");
        }
        long cellCount = 0L;
        for (Map.Entry<ChunkPosition, ChunkSnapshot> entry : chunks.entrySet()) {
            Objects.requireNonNull(entry.getKey(), "chunk key");
            Objects.requireNonNull(entry.getValue(), "chunk snapshot");
            if (!entry.getKey().equals(entry.getValue().position())) {
                throw new IllegalArgumentException("chunk key does not match snapshot position");
            }
            double chunkMinX = (double) entry.getKey().x() * 16.0D;
            double chunkMinZ = (double) entry.getKey().z() * 16.0D;
            if (bounds.maxX() <= chunkMinX || bounds.minX() >= chunkMinX + 16.0D
                    || bounds.maxZ() <= chunkMinZ || bounds.minZ() >= chunkMinZ + 16.0D) {
                throw new IllegalArgumentException("snapshot chunk lies outside bounds: " + entry.getKey());
            }
            try {
                cellCount = Math.addExact(cellCount, entry.getValue().blocks().size());
            } catch (ArithmeticException exception) {
                throw new IllegalArgumentException("snapshot cell count overflow", exception);
            }
            if (cellCount > SpatialCaptureRequest.MAX_BLOCKS) {
                throw new IllegalArgumentException("snapshot exceeds block limit");
            }
            for (BlockPosition block : entry.getValue().blocks().keySet()) {
                if (!bounds.intersects(block.unitBox())) {
                    throw new IllegalArgumentException("snapshot block lies outside bounds: " + block);
                }
            }
        }
        chunks = Map.copyOf(chunks);
    }

    public SpatialSnapshot(
            long worldEpoch,
            BoxSnapshot bounds,
            int minY,
            int maxY,
            BoxSnapshot playerBox,
            Map<ChunkPosition, ChunkSnapshot> chunks
    ) {
        this(worldEpoch, 0L, bounds, minY, maxY, playerBox, chunks);
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
