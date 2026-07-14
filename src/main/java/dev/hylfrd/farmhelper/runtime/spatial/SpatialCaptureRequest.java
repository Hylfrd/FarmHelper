package dev.hylfrd.farmhelper.runtime.spatial;

import java.util.Objects;
import java.util.Set;

/** Explicitly bounded set of blocks that a client-thread adapter may observe without loading chunks. */
public record SpatialCaptureRequest(long worldEpoch, BoxSnapshot bounds, Set<BlockPosition> blocks) {
    public static final int MAX_BLOCKS = 8_192;
    public static final double MAX_AXIS_SPAN = 256.0D;

    public SpatialCaptureRequest {
        Objects.requireNonNull(bounds, "bounds");
        Objects.requireNonNull(blocks, "blocks");
        blocks = Set.copyOf(blocks);
        if (!bounds.hasPositiveVolume()) {
            throw new IllegalArgumentException("bounds must have positive volume");
        }
        if (bounds.width() > MAX_AXIS_SPAN || bounds.height() > MAX_AXIS_SPAN
                || bounds.depth() > MAX_AXIS_SPAN) {
            throw new IllegalArgumentException("capture bounds exceed the per-axis limit");
        }
        if (blocks.size() > MAX_BLOCKS) {
            throw new IllegalArgumentException("capture request exceeds block limit");
        }
        for (BlockPosition block : blocks) {
            if (!bounds.intersects(block.unitBox())) {
                throw new IllegalArgumentException("requested block lies outside bounds: " + block);
            }
        }
    }
}
