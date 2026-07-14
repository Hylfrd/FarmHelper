package dev.hylfrd.farmhelper.runtime.spatial;

import dev.hylfrd.farmhelper.runtime.snapshot.Observation;
import dev.hylfrd.farmhelper.runtime.snapshot.ResourceIdentifier;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class SpatialTestFixtures {
    static final long EPOCH = 41L;
    static final ResourceIdentifier EMPTY_FLUID = ResourceIdentifier.parse("minecraft:empty");

    private SpatialTestFixtures() {
    }

    static BlockStateSnapshot empty() {
        return state("minecraft:air", Map.of(), EMPTY_FLUID, CollisionShapeSnapshot.EMPTY);
    }

    static BlockStateSnapshot full() {
        return state("minecraft:stone", Map.of(), EMPTY_FLUID,
                new CollisionShapeSnapshot(List.of(new BoxSnapshot(0, 0, 0, 1, 1, 1))));
    }

    static BlockStateSnapshot state(
            String blockId,
            Map<String, String> properties,
            ResourceIdentifier fluidId,
            CollisionShapeSnapshot collision
    ) {
        return new BlockStateSnapshot(
                ResourceIdentifier.parse(blockId),
                properties,
                fluidId,
                Observation.present(collision));
    }

    static SpatialSnapshot snapshot(BoxSnapshot bounds, Map<BlockPosition, Observation<BlockStateSnapshot>> blocks) {
        Map<ChunkPosition, Map<BlockPosition, Observation<BlockStateSnapshot>>> grouped = new LinkedHashMap<>();
        for (Map.Entry<BlockPosition, Observation<BlockStateSnapshot>> entry : blocks.entrySet()) {
            grouped.computeIfAbsent(entry.getKey().chunk(), ignored -> new LinkedHashMap<>())
                    .put(entry.getKey(), entry.getValue());
        }
        Map<ChunkPosition, ChunkSnapshot> chunks = new HashMap<>();
        grouped.forEach((position, states) -> chunks.put(position, new ChunkSnapshot(position, true, states)));
        return new SpatialSnapshot(EPOCH, bounds, -64, 320,
                new BoxSnapshot(0.2, 1, 0.2, 0.8, 2.8, 0.8), chunks);
    }

    static Map<BlockPosition, Observation<BlockStateSnapshot>> column(
            int x,
            int z,
            int minY,
            int maxY,
            BlockStateSnapshot state
    ) {
        Map<BlockPosition, Observation<BlockStateSnapshot>> blocks = new LinkedHashMap<>();
        for (int y = minY; y <= maxY; y++) {
            blocks.put(new BlockPosition(x, y, z), Observation.present(state));
        }
        return blocks;
    }

    static Map<BlockPosition, Observation<BlockStateSnapshot>> walkableLane(int fromZ, int toZ) {
        Map<BlockPosition, Observation<BlockStateSnapshot>> blocks = new LinkedHashMap<>();
        for (int z = fromZ; z <= toZ; z++) {
            blocks.put(new BlockPosition(0, 0, z), Observation.present(full()));
            blocks.put(new BlockPosition(0, 1, z), Observation.present(empty()));
            blocks.put(new BlockPosition(0, 2, z), Observation.present(empty()));
        }
        return blocks;
    }

    static List<BlockPosition> positions(Map<BlockPosition, Observation<BlockStateSnapshot>> blocks) {
        return new ArrayList<>(blocks.keySet());
    }
}
