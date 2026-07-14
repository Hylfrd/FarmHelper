package dev.hylfrd.farmhelper.runtime.spatial;

import dev.hylfrd.farmhelper.runtime.snapshot.Observation;
import dev.hylfrd.farmhelper.runtime.snapshot.ResourceIdentifier;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static dev.hylfrd.farmhelper.runtime.spatial.SpatialTestFixtures.EPOCH;
import static dev.hylfrd.farmhelper.runtime.spatial.SpatialTestFixtures.EMPTY_FLUID;
import static dev.hylfrd.farmhelper.runtime.spatial.SpatialTestFixtures.empty;
import static dev.hylfrd.farmhelper.runtime.spatial.SpatialTestFixtures.full;
import static dev.hylfrd.farmhelper.runtime.spatial.SpatialTestFixtures.snapshot;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SpatialQueriesTest {
    private static final BoxSnapshot BLOCK_BOUNDS = new BoxSnapshot(0, 0, 0, 2, 2, 2);

    @Test
    void emptyFullPartialAndMultiBoxCollisionUsePositiveVolumeIntersection() {
        BoxSnapshot center = new BoxSnapshot(0.3, 0.2, 0.3, 0.7, 0.8, 0.7);
        assertEquals(SpaceStatus.PASSABLE, clearanceAt(empty(), center));
        assertEquals(SpaceStatus.BLOCKED, clearanceAt(full(), center));

        BlockStateSnapshot partial = shaped(List.of(new BoxSnapshot(0, 0, 0, 0.25, 1, 1)));
        assertEquals(SpaceStatus.PASSABLE, clearanceAt(partial, center));
        assertEquals(SpaceStatus.BLOCKED, clearanceAt(partial,
                new BoxSnapshot(0.2, 0.2, 0.2, 0.3, 0.8, 0.8)));

        BlockStateSnapshot multi = shaped(List.of(
                new BoxSnapshot(0, 0, 0, 0.2, 1, 1),
                new BoxSnapshot(0.8, 0, 0, 1, 1, 1)));
        assertEquals(SpaceStatus.PASSABLE, clearanceAt(multi,
                new BoxSnapshot(0.4, 0.2, 0.2, 0.6, 0.8, 0.8)));
        assertEquals(SpaceStatus.BLOCKED, clearanceAt(multi,
                new BoxSnapshot(0.85, 0.2, 0.2, 0.9, 0.8, 0.8)));
    }

    @Test
    void exactBoundaryContactDoesNotCollideOrDemandTheAdjacentBlock() {
        Map<BlockPosition, Observation<BlockStateSnapshot>> blocks = Map.of(
                new BlockPosition(0, 0, 0), Observation.present(full()),
                new BlockPosition(1, 0, 0), Observation.present(empty()));
        SpatialSnapshot snapshot = snapshot(new BoxSnapshot(0, 0, 0, 2, 1, 1), blocks);

        assertEquals(SpaceStatus.PASSABLE, SpatialQueries.clearance(snapshot, EPOCH,
                new BoxSnapshot(1, 0.2, 0.2, 1.5, 0.8, 0.8)));
    }

    @Test
    void dangerousFluidBlocksWaterUsesOnlyItsCollisionAndUnknownFluidStaysUnknown() {
        BlockStateSnapshot lava = SpatialTestFixtures.state("minecraft:air", Map.of(),
                ResourceIdentifier.parse("minecraft:lava"), CollisionShapeSnapshot.EMPTY);
        BlockStateSnapshot water = SpatialTestFixtures.state("minecraft:water", Map.of(),
                ResourceIdentifier.parse("minecraft:water"), CollisionShapeSnapshot.EMPTY);
        BlockStateSnapshot unknown = SpatialTestFixtures.state("minecraft:air", Map.of(),
                ResourceIdentifier.parse("example:mystery"), CollisionShapeSnapshot.EMPTY);

        assertEquals(SpaceStatus.BLOCKED, clearanceAt(lava,
                new BoxSnapshot(0.2, 0.2, 0.2, 0.8, 0.8, 0.8)));
        assertEquals(SpaceStatus.PASSABLE, clearanceAt(water,
                new BoxSnapshot(0.2, 0.2, 0.2, 0.8, 0.8, 0.8)));
        assertEquals(SpaceStatus.UNKNOWN, clearanceAt(unknown,
                new BoxSnapshot(0.2, 0.2, 0.2, 0.8, 0.8, 0.8)));
    }

    @Test
    void epochUnloadedOutOfBoundsAndMissingShapeAreUnknown() {
        BlockPosition position = new BlockPosition(0, 0, 0);
        BlockStateSnapshot missingShape = new BlockStateSnapshot(
                ResourceIdentifier.parse("minecraft:air"), Map.of(), EMPTY_FLUID, Observation.unknown());
        SpatialSnapshot knownSnapshot = snapshot(BLOCK_BOUNDS,
                Map.of(position, Observation.present(missingShape)));
        BoxSnapshot query = new BoxSnapshot(0.2, 0.2, 0.2, 0.8, 0.8, 0.8);

        assertEquals(SpaceStatus.UNKNOWN, SpatialQueries.clearance(knownSnapshot, EPOCH + 1, query));
        assertEquals(SpaceStatus.UNKNOWN, SpatialQueries.clearance(knownSnapshot, EPOCH,
                new BoxSnapshot(2, 0, 0, 2.5, 0.5, 0.5)));
        assertEquals(SpaceStatus.UNKNOWN, SpatialQueries.clearance(knownSnapshot, EPOCH, query));

        ChunkPosition chunkPosition = position.chunk();
        SpatialSnapshot unloaded = new SpatialSnapshot(EPOCH, BLOCK_BOUNDS, -64, 320,
                new BoxSnapshot(0.2, 1, 0.2, 0.8, 2.8, 0.8),
                Map.of(chunkPosition, new ChunkSnapshot(chunkPosition, false, Map.of())));
        assertEquals(SpaceStatus.UNKNOWN, SpatialQueries.clearance(unloaded, EPOCH, query));
    }

    @Test
    void nearbyObstacleScanReportsKnownBlocksWithoutErasingUnknownDominanceRules() {
        BlockPosition blocked = new BlockPosition(0, 0, 0);
        BlockPosition clear = new BlockPosition(1, 0, 0);
        SpatialSnapshot snapshot = snapshot(new BoxSnapshot(0, 0, 0, 2, 1, 1), Map.of(
                blocked, Observation.present(full()),
                clear, Observation.present(empty())));

        SpatialScanResult result = SpatialQueries.nearbyObstacles(snapshot, EPOCH,
                new BoxSnapshot(0, 0, 0, 2, 1, 1));

        assertEquals(SpaceStatus.BLOCKED, result.status());
        assertEquals(List.of(blocked), result.blockedBlocks());
        assertEquals(2, result.inspectedBlocks().size());
    }

    @Test
    void walkabilityRequiresBothBodyClearanceAndKnownSupport() {
        BoxSnapshot body = new BoxSnapshot(0.2, 1, 0.2, 0.8, 2.8, 0.8);
        Map<BlockPosition, Observation<BlockStateSnapshot>> walkable = new LinkedHashMap<>();
        walkable.put(new BlockPosition(0, 0, 0), Observation.present(full()));
        walkable.put(new BlockPosition(0, 1, 0), Observation.present(empty()));
        walkable.put(new BlockPosition(0, 2, 0), Observation.present(empty()));
        BoxSnapshot bounds = new BoxSnapshot(0, 0, 0, 1, 3, 1);

        assertEquals(SpaceStatus.PASSABLE,
                SpatialQueries.walkability(snapshot(bounds, walkable), EPOCH, body));

        walkable.put(new BlockPosition(0, 0, 0), Observation.present(empty()));
        assertEquals(SpaceStatus.BLOCKED,
                SpatialQueries.walkability(snapshot(bounds, walkable), EPOCH, body));

        walkable.put(new BlockPosition(0, 0, 0), Observation.present(full()));
        walkable.put(new BlockPosition(0, 1, 0), Observation.unknown());
        assertEquals(SpaceStatus.UNKNOWN,
                SpatialQueries.walkability(snapshot(bounds, walkable), EPOCH, body));
    }

    @Test
    void gardenVoidNeedsCompleteKnowledgeThroughYSixtyFive() {
        BoxSnapshot bounds = new BoxSnapshot(0, 65, 0, 1, 71, 1);
        Map<BlockPosition, Observation<BlockStateSnapshot>> emptyColumn =
                SpatialTestFixtures.column(0, 0, 65, 70, empty());

        assertEquals(SpaceStatus.BLOCKED, SpatialQueries.gardenVoid(
                snapshot(bounds, emptyColumn), EPOCH, new BlockPosition(0, 70, 0)));

        Map<BlockPosition, Observation<BlockStateSnapshot>> supported = new LinkedHashMap<>(emptyColumn);
        supported.put(new BlockPosition(0, 67, 0), Observation.present(full()));
        assertEquals(SpaceStatus.PASSABLE, SpatialQueries.gardenVoid(
                snapshot(bounds, supported), EPOCH, new BlockPosition(0, 70, 0)));

        Map<BlockPosition, Observation<BlockStateSnapshot>> incomplete = new LinkedHashMap<>(emptyColumn);
        incomplete.remove(new BlockPosition(0, 68, 0));
        assertEquals(SpaceStatus.UNKNOWN, SpatialQueries.gardenVoid(
                snapshot(bounds, incomplete), EPOCH, new BlockPosition(0, 70, 0)));
    }

    @Test
    void dropRequiresClearSweptVolumeAndKnownLandingSupport() {
        BoxSnapshot bounds = new BoxSnapshot(0, 0, 0, 1, 6, 1);
        BoxSnapshot start = new BoxSnapshot(0.2, 4, 0.2, 0.8, 5.8, 0.8);
        BoxSnapshot landing = new BoxSnapshot(0.2, 1, 0.2, 0.8, 2.8, 0.8);
        Map<BlockPosition, Observation<BlockStateSnapshot>> blocks =
                SpatialTestFixtures.column(0, 0, 0, 5, empty());
        blocks.put(new BlockPosition(0, 0, 0), Observation.present(full()));

        assertEquals(SpaceStatus.PASSABLE,
                SpatialQueries.drop(snapshot(bounds, blocks), EPOCH, start, landing));

        blocks.put(new BlockPosition(0, 3, 0), Observation.present(full()));
        assertEquals(SpaceStatus.BLOCKED,
                SpatialQueries.drop(snapshot(bounds, blocks), EPOCH, start, landing));

        blocks.put(new BlockPosition(0, 3, 0), Observation.unknown());
        assertEquals(SpaceStatus.UNKNOWN,
                SpatialQueries.drop(snapshot(bounds, blocks), EPOCH, start, landing));
    }

    @Test
    void endRowStopsOnBlockedUnknownAndHardBudget() {
        BoxSnapshot bounds = new BoxSnapshot(0, 0, 0, 1, 3, 4);
        BoxSnapshot body = new BoxSnapshot(0.2, 1, 0.2, 0.8, 2.8, 0.8);
        RelativeFrame forwardZ = RelativeFrame.cardinal(0);
        Map<BlockPosition, Observation<BlockStateSnapshot>> lane = SpatialTestFixtures.walkableLane(0, 3);
        lane.put(new BlockPosition(0, 1, 2), Observation.present(full()));

        SpatialScanResult blocked = SpatialQueries.scanForwardUntilBlocked(
                snapshot(bounds, lane), EPOCH, body, forwardZ, 3);
        assertEquals(SpaceStatus.BLOCKED, blocked.status());
        assertEquals(new BlockPosition(0, 1, 2), blocked.blockedBlocks().getFirst());
        assertEquals(2, blocked.inspectedBlocks().size());

        lane.put(new BlockPosition(0, 1, 1), Observation.unknown());
        SpatialScanResult unknown = SpatialQueries.scanForwardUntilBlocked(
                snapshot(bounds, lane), EPOCH, body, forwardZ, 3);
        assertEquals(SpaceStatus.UNKNOWN, unknown.status());
        assertEquals(1, unknown.inspectedBlocks().size());

        Map<BlockPosition, Observation<BlockStateSnapshot>> clearLane = SpatialTestFixtures.walkableLane(0, 3);
        SpatialScanResult exhausted = SpatialQueries.scanForwardUntilBlocked(
                snapshot(bounds, clearLane), EPOCH, body, forwardZ, 3);
        assertEquals(SpaceStatus.UNKNOWN, exhausted.status());
        assertEquals(3, exhausted.inspectedBlocks().size());
        assertTrue(exhausted.blockedBlocks().isEmpty());
    }

    private static SpaceStatus clearanceAt(BlockStateSnapshot state, BoxSnapshot query) {
        return SpatialQueries.clearance(snapshot(BLOCK_BOUNDS,
                Map.of(new BlockPosition(0, 0, 0), Observation.present(state))), EPOCH, query);
    }

    private static BlockStateSnapshot shaped(List<BoxSnapshot> boxes) {
        return SpatialTestFixtures.state("minecraft:stone", Map.of(), EMPTY_FLUID,
                new CollisionShapeSnapshot(boxes));
    }
}
