package dev.hylfrd.farmhelper.runtime.spatial;

import java.util.List;
import java.util.Objects;

/** Immutable details from a tri-state spatial scan. */
public record SpatialScanResult(
        SpaceStatus status,
        List<BlockPosition> inspectedBlocks,
        List<BlockPosition> blockedBlocks
) {
    public SpatialScanResult {
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(inspectedBlocks, "inspectedBlocks");
        Objects.requireNonNull(blockedBlocks, "blockedBlocks");
        inspectedBlocks = List.copyOf(inspectedBlocks);
        blockedBlocks = List.copyOf(blockedBlocks);
        if (status == SpaceStatus.PASSABLE && !blockedBlocks.isEmpty()) {
            throw new IllegalArgumentException("a passable scan cannot contain blocked blocks");
        }
    }
}
