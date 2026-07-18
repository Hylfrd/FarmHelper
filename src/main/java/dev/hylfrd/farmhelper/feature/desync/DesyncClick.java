package dev.hylfrd.farmhelper.feature.desync;

import dev.hylfrd.farmhelper.runtime.snapshot.Observation;
import dev.hylfrd.farmhelper.runtime.spatial.BlockPosition;
import dev.hylfrd.farmhelper.runtime.spatial.BlockStateSnapshot;

import java.util.Objects;

/**
 * One client-thread block-click observation fenced to the exact macro run and world that produced
 * it. The clicked block is captured at ingress; recovery evaluation deliberately rereads the
 * current block through {@link DesyncChecker}.
 */
public record DesyncClick(
        long macroGeneration,
        long worldEpoch,
        BlockPosition position,
        Observation<BlockStateSnapshot> clickedBlock
) {
    public DesyncClick {
        if (macroGeneration <= 0L) {
            throw new IllegalArgumentException("macro generation must be positive");
        }
        if (worldEpoch < 0L) {
            throw new IllegalArgumentException("world epoch must not be negative");
        }
        Objects.requireNonNull(position, "position");
        Objects.requireNonNull(clickedBlock, "clickedBlock");
    }
}
