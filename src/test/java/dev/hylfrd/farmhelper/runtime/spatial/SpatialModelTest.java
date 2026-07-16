package dev.hylfrd.farmhelper.runtime.spatial;

import dev.hylfrd.farmhelper.runtime.snapshot.Observation;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static dev.hylfrd.farmhelper.runtime.spatial.SpatialTestFixtures.EPOCH;
import static dev.hylfrd.farmhelper.runtime.spatial.SpatialTestFixtures.empty;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SpatialModelTest {
    @Test
    void intersectionRequiresPositiveVolumeOnBothSidesAndExcludesBoundaryContact() {
        BoxSnapshot full = new BoxSnapshot(0, 0, 0, 1, 1, 1);
        BoxSnapshot point = new BoxSnapshot(0.5, 0.5, 0.5, 0.5, 0.5, 0.5);
        BoxSnapshot touching = new BoxSnapshot(1, 0, 0, 2, 1, 1);

        assertFalse(point.intersects(full));
        assertFalse(full.intersects(point));
        assertFalse(full.intersects(touching));
        assertFalse(touching.intersects(full));
    }

    @Test
    void threeValuedAndAndAlternativeOrHaveFrozenTruthTables() {
        for (SpaceStatus left : SpaceStatus.values()) {
            for (SpaceStatus right : SpaceStatus.values()) {
                SpaceStatus expectedAnd = left == SpaceStatus.BLOCKED || right == SpaceStatus.BLOCKED
                        ? SpaceStatus.BLOCKED
                        : left == SpaceStatus.UNKNOWN || right == SpaceStatus.UNKNOWN
                        ? SpaceStatus.UNKNOWN : SpaceStatus.PASSABLE;
                SpaceStatus expectedOr = left == SpaceStatus.PASSABLE || right == SpaceStatus.PASSABLE
                        ? SpaceStatus.PASSABLE
                        : left == SpaceStatus.UNKNOWN || right == SpaceStatus.UNKNOWN
                        ? SpaceStatus.UNKNOWN : SpaceStatus.BLOCKED;
                assertEquals(expectedAnd, SpaceStatus.allOf(left, right));
                assertEquals(expectedOr, SpaceStatus.anyOf(left, right));
            }
        }
    }

    @Test
    void collectionsAreCopiedAtEverySnapshotBoundary() {
        List<BoxSnapshot> mutableBoxes = new ArrayList<>();
        CollisionShapeSnapshot shape = new CollisionShapeSnapshot(mutableBoxes);
        mutableBoxes.add(new BoxSnapshot(0, 0, 0, 1, 1, 1));

        Map<String, String> mutableProperties = new HashMap<>();
        BlockStateSnapshot block = new BlockStateSnapshot(
                dev.hylfrd.farmhelper.runtime.snapshot.ResourceIdentifier.parse("minecraft:wheat"),
                mutableProperties,
                SpatialTestFixtures.EMPTY_FLUID,
                Observation.present(shape));
        mutableProperties.put("age", "7");

        Set<BlockPosition> mutableRequest = new HashSet<>(Set.of(new BlockPosition(0, 0, 0)));
        SpatialCaptureRequest request = new SpatialCaptureRequest(EPOCH,
                new BoxSnapshot(0, 0, 0, 2, 2, 2), mutableRequest);
        mutableRequest.clear();

        assertTrue(shape.boxes().isEmpty());
        assertTrue(block.properties().isEmpty());
        assertEquals(1, request.blocks().size());
        assertEquals(0L, request.requestToken());
        SpatialCaptureRequest tagged = new SpatialCaptureRequest(
                EPOCH, 19L, request.bounds(), request.blocks());
        assertEquals(19L, tagged.requestToken());
        assertThrows(UnsupportedOperationException.class,
                () -> request.blocks().add(new BlockPosition(1, 1, 1)));
    }

    @Test
    void collisionShapesEnforceTheAuditedMinecraftLocalBound() {
        BoxSnapshot maximum = new BoxSnapshot(
                CollisionShapeSnapshot.MIN_HORIZONTAL_LOCAL_COORDINATE,
                CollisionShapeSnapshot.MIN_VERTICAL_LOCAL_COORDINATE,
                CollisionShapeSnapshot.MIN_HORIZONTAL_LOCAL_COORDINATE,
                CollisionShapeSnapshot.MAX_HORIZONTAL_LOCAL_COORDINATE,
                CollisionShapeSnapshot.MAX_VERTICAL_LOCAL_COORDINATE,
                CollisionShapeSnapshot.MAX_HORIZONTAL_LOCAL_COORDINATE);
        assertEquals(List.of(maximum),
                new CollisionShapeSnapshot(List.of(maximum)).boxes());

        assertThrows(IllegalArgumentException.class, () -> new CollisionShapeSnapshot(List.of(
                new BoxSnapshot(
                        Math.nextDown(CollisionShapeSnapshot.MIN_HORIZONTAL_LOCAL_COORDINATE),
                        0, 0, 1, 1, 1))));
        assertThrows(IllegalArgumentException.class, () -> new CollisionShapeSnapshot(List.of(
                new BoxSnapshot(0, 0, 0,
                        Math.nextUp(CollisionShapeSnapshot.MAX_HORIZONTAL_LOCAL_COORDINATE),
                        1, 1))));
        assertThrows(IllegalArgumentException.class, () -> new CollisionShapeSnapshot(List.of(
                new BoxSnapshot(0,
                        Math.nextDown(CollisionShapeSnapshot.MIN_VERTICAL_LOCAL_COORDINATE),
                        0, 1, 1, 1))));
        assertThrows(IllegalArgumentException.class, () -> new CollisionShapeSnapshot(List.of(
                new BoxSnapshot(0, 0, 0, 1,
                        Math.nextUp(CollisionShapeSnapshot.MAX_VERTICAL_LOCAL_COORDINATE),
                        1))));
        assertThrows(IllegalArgumentException.class, () -> new CollisionShapeSnapshot(List.of(
                new BoxSnapshot(0, 0,
                        Math.nextDown(CollisionShapeSnapshot.MIN_HORIZONTAL_LOCAL_COORDINATE),
                        1, 1, 1))));
        assertThrows(IllegalArgumentException.class, () -> new CollisionShapeSnapshot(List.of(
                new BoxSnapshot(0, 0, 0, 1, 1,
                        Math.nextUp(CollisionShapeSnapshot.MAX_HORIZONTAL_LOCAL_COORDINATE)))));
        assertThrows(IllegalArgumentException.class, () -> new CollisionShapeSnapshot(List.of(
                new BoxSnapshot(0.5D, 0.5D, 0.5D, 0.5D, 0.5D, 0.5D))));
        assertThrows(IllegalArgumentException.class,
                () -> new BoxSnapshot(Double.NaN, 0, 0, 1, 1, 1));
    }

    @Test
    void epochUnloadedAndBoundsNeverFabricateBlockState() {
        BlockPosition known = new BlockPosition(0, 1, 0);
        ChunkPosition unloadedPosition = new ChunkPosition(1, 0);
        Map<ChunkPosition, ChunkSnapshot> chunks = Map.of(
                known.chunk(), new ChunkSnapshot(known.chunk(), true,
                        Map.of(known, Observation.present(empty()))),
                unloadedPosition, new ChunkSnapshot(unloadedPosition, false, Map.of()));
        SpatialSnapshot snapshot = new SpatialSnapshot(EPOCH, new BoxSnapshot(0, 0, 0, 32, 4, 2),
                0, 4, new BoxSnapshot(0.2, 1, 0.2, 0.8, 2.8, 0.8), chunks);

        assertTrue(snapshot.block(EPOCH, known).isPresent());
        assertTrue(snapshot.block(EPOCH + 1, known).isUnknown());
        assertTrue(snapshot.block(EPOCH, new BlockPosition(16, 1, 0)).isUnknown());
        assertTrue(snapshot.block(EPOCH, new BlockPosition(33, 1, 0)).isUnknown());
        assertTrue(snapshot.block(EPOCH, new BlockPosition(0, 4, 0)).isUnknown());
    }

    @Test
    void captureRequestRejectsUnboundedOrOutOfBoundsWork() {
        assertThrows(IllegalArgumentException.class, () -> new SpatialCaptureRequest(
                EPOCH, -1L, new BoxSnapshot(0, 0, 0, 1, 1, 1), Set.of()));
        assertThrows(IllegalArgumentException.class, () -> new SpatialCaptureRequest(EPOCH,
                new BoxSnapshot(0, 0, 0, 257, 1, 1), Set.of()));
        assertThrows(IllegalArgumentException.class, () -> new SpatialCaptureRequest(EPOCH,
                new BoxSnapshot(0, 0, 0, 2, 2, 2), Set.of(new BlockPosition(3, 0, 0))));
    }

    @Test
    void directSnapshotConstructionEnforcesBoundsCellBudgetAndCellConsistency() {
        BoxSnapshot maximumBounds = new BoxSnapshot(0, 0, 0, 256, 256, 256);
        SpatialSnapshot sparse = new SpatialSnapshot(EPOCH, maximumBounds, -64, 320,
                new BoxSnapshot(0.2, 1, 0.2, 0.8, 2.8, 0.8), Map.of());
        assertEquals(maximumBounds, sparse.bounds());

        assertThrows(IllegalArgumentException.class, () -> new SpatialSnapshot(EPOCH,
                new BoxSnapshot(0, 0, 0, 257, 1, 1), -64, 320,
                new BoxSnapshot(0.2, 1, 0.2, 0.8, 2.8, 0.8), Map.of()));

        Map<BlockPosition, Observation<BlockStateSnapshot>> cells = new HashMap<>();
        for (int x = 0; x < 21 && cells.size() <= SpatialCaptureRequest.MAX_BLOCKS; x++) {
            for (int y = 0; y < 21 && cells.size() <= SpatialCaptureRequest.MAX_BLOCKS; y++) {
                for (int z = 0; z < 21 && cells.size() <= SpatialCaptureRequest.MAX_BLOCKS; z++) {
                    cells.put(new BlockPosition(x, y, z), Observation.unknown());
                }
            }
        }
        assertEquals(SpatialCaptureRequest.MAX_BLOCKS + 1, cells.size());
        assertThrows(IllegalArgumentException.class,
                () -> SpatialTestFixtures.snapshot(new BoxSnapshot(0, 0, 0, 21, 21, 21), cells));
        cells.remove(cells.keySet().iterator().next());
        assertEquals(SpatialCaptureRequest.MAX_BLOCKS, cells.size());
        SpatialTestFixtures.snapshot(new BoxSnapshot(0, 0, 0, 21, 21, 21), cells);

        BlockPosition outside = new BlockPosition(2, 0, 0);
        ChunkSnapshot inconsistent = new ChunkSnapshot(outside.chunk(), true,
                Map.of(outside, Observation.unknown()));
        assertThrows(IllegalArgumentException.class, () -> new SpatialSnapshot(EPOCH,
                new BoxSnapshot(0, 0, 0, 1, 1, 1), -64, 320,
                new BoxSnapshot(0.2, 1, 0.2, 0.8, 2.8, 0.8),
                Map.of(outside.chunk(), inconsistent)));

        ChunkPosition outsideChunk = new ChunkPosition(2, 0);
        ChunkSnapshot emptyOutsideChunk = new ChunkSnapshot(outsideChunk, true, Map.of());
        assertThrows(IllegalArgumentException.class, () -> new SpatialSnapshot(EPOCH,
                new BoxSnapshot(0, 0, 0, 1, 1, 1), -64, 320,
                new BoxSnapshot(0.2, 1, 0.2, 0.8, 2.8, 0.8),
                Map.of(outsideChunk, emptyOutsideChunk)));
    }
}
