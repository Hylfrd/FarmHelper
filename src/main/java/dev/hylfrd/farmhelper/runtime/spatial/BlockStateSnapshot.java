package dev.hylfrd.farmhelper.runtime.spatial;

import dev.hylfrd.farmhelper.runtime.snapshot.Observation;
import dev.hylfrd.farmhelper.runtime.snapshot.ResourceIdentifier;

import java.util.Map;
import java.util.Objects;

/** Immutable registry-free block state data needed by spatial decisions. */
public record BlockStateSnapshot(
        ResourceIdentifier blockId,
        Map<String, String> properties,
        ResourceIdentifier fluidId,
        Observation<CollisionShapeSnapshot> collision
) {
    public BlockStateSnapshot {
        Objects.requireNonNull(blockId, "blockId");
        Objects.requireNonNull(properties, "properties");
        Objects.requireNonNull(fluidId, "fluidId");
        Objects.requireNonNull(collision, "collision");
        properties = Map.copyOf(properties);
    }
}
