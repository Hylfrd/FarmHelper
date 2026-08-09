package dev.hylfrd.farmhelper.client.platform.spatial;

import dev.hylfrd.farmhelper.runtime.spatial.BlockPosition;
import dev.hylfrd.farmhelper.runtime.snapshot.Observation;
import dev.hylfrd.farmhelper.runtime.snapshot.ResourceIdentifier;
import dev.hylfrd.farmhelper.runtime.spatial.BlockStateSnapshot;
import dev.hylfrd.farmhelper.runtime.spatial.BoxSnapshot;
import dev.hylfrd.farmhelper.runtime.spatial.ChunkPosition;
import dev.hylfrd.farmhelper.runtime.spatial.ChunkSnapshot;
import dev.hylfrd.farmhelper.runtime.spatial.CollisionShapeSnapshot;
import dev.hylfrd.farmhelper.runtime.spatial.SpatialCaptureRequest;
import dev.hylfrd.farmhelper.runtime.spatial.SpatialSnapshot;
import net.minecraft.world.phys.shapes.Shapes;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClientSpatialSnapshotCaptureTest {
    @Test
    void productionSnapshotPreservesOpaqueRequestToken() {
        BoxSnapshot bounds = new BoxSnapshot(0, 0, 0, 2, 3, 2);
        SpatialCaptureRequest request = new SpatialCaptureRequest(
                7L, 41L, bounds, Set.of(new BlockPosition(0, 1, 0)));

        var snapshot = ClientSpatialSnapshotCapture.capturedSnapshot(
                request, -64, 320,
                new BoxSnapshot(0.2D, 1.0D, 0.2D, 0.8D, 2.8D, 0.8D), Map.of());

        assertEquals(request.worldEpoch(), snapshot.worldEpoch());
        assertEquals(request.requestToken(), snapshot.requestToken());
        assertEquals(request.bounds(), snapshot.bounds());
    }

    @Test
    void collisionCaptureKeepsTheAuditedMaximumAndRejectsTheNextValue() {
        var maximum = ClientSpatialSnapshotCapture.captureCollision(Shapes.box(
                CollisionShapeSnapshot.MIN_HORIZONTAL_LOCAL_COORDINATE,
                CollisionShapeSnapshot.MIN_VERTICAL_LOCAL_COORDINATE,
                CollisionShapeSnapshot.MIN_HORIZONTAL_LOCAL_COORDINATE,
                CollisionShapeSnapshot.MAX_HORIZONTAL_LOCAL_COORDINATE,
                CollisionShapeSnapshot.MAX_VERTICAL_LOCAL_COORDINATE,
                CollisionShapeSnapshot.MAX_HORIZONTAL_LOCAL_COORDINATE));
        assertTrue(maximum.isPresent());
        assertEquals(CollisionShapeSnapshot.MIN_HORIZONTAL_LOCAL_COORDINATE,
                maximum.get().boxes().getFirst().minX());
        assertEquals(CollisionShapeSnapshot.MAX_VERTICAL_LOCAL_COORDINATE,
                maximum.get().boxes().getFirst().maxY());

        var outside = ClientSpatialSnapshotCapture.captureCollision(Shapes.box(
                0, 0, 0,
                1, Math.nextUp(CollisionShapeSnapshot.MAX_VERTICAL_LOCAL_COORDINATE), 1));
        assertTrue(outside.isUnknown());
        assertTrue(ClientSpatialSnapshotCapture.captureCollision(Shapes.box(
                0, Math.nextDown(CollisionShapeSnapshot.MIN_VERTICAL_LOCAL_COORDINATE), 0,
                1, 1, 1)).isUnknown());
        assertTrue(ClientSpatialSnapshotCapture.captureCollision(Shapes.box(
                0, 0, 0,
                Math.nextUp(CollisionShapeSnapshot.MAX_HORIZONTAL_LOCAL_COORDINATE),
                1, 1)).isUnknown());
        assertTrue(ClientSpatialSnapshotCapture.captureCollision(Shapes.box(
                0, 0, 0,
                1, 1,
                Math.nextUp(CollisionShapeSnapshot.MAX_HORIZONTAL_LOCAL_COORDINATE)))
                .isUnknown());
    }

    @Test
    void pointInTimeLookupDoesNotReuseStaleChunkAcrossUnknownMissingAndOutOfView() {
        long epoch = 23L;
        ChunkPosition chunkPosition = new ChunkPosition(0, 0);
        BlockPosition inWorld = new BlockPosition(0, 1, 0);
        BlockPosition outOfView = new BlockPosition(0, 4, 0);
        BoxSnapshot bounds = new BoxSnapshot(0, 0, 0, 16, 5, 16);
        AtomicReference<ChunkSnapshot> current = new AtomicReference<>();
        List<Boolean> loadArguments = new ArrayList<>();
        FullChunkLookup lookup = (position, requested, load) -> {
            assertEquals(chunkPosition, position);
            assertFalse(load);
            loadArguments.add(load);
            return current.get();
        };

        BlockStateSnapshot stateA = state("minecraft:stone");
        BlockStateSnapshot stateB = state("minecraft:dirt");
        current.set(loaded(chunkPosition, inWorld, stateA));
        SpatialSnapshot presentA = capture(epoch, bounds, inWorld, lookup);

        current.set(new ChunkSnapshot(chunkPosition, false,
                Map.of(inWorld, Observation.unknown())));
        SpatialSnapshot unknown = capture(epoch, bounds, inWorld, lookup);

        current.set(null);
        SpatialSnapshot missing = capture(epoch, bounds, inWorld, lookup);

        current.set(new ChunkSnapshot(chunkPosition, true,
                Map.of(outOfView, Observation.unknown())));
        SpatialSnapshot outOfViewSnapshot = capture(epoch, bounds, outOfView, lookup);

        current.set(loaded(chunkPosition, inWorld, stateB));
        SpatialSnapshot presentB = capture(epoch, bounds, inWorld, lookup);

        assertEquals(stateA, presentA.block(epoch, inWorld).get());
        assertTrue(unknown.block(epoch, inWorld).isUnknown());
        assertTrue(missing.block(epoch, inWorld).isUnknown());
        assertTrue(outOfViewSnapshot.block(epoch, outOfView).isUnknown());
        assertEquals(stateB, presentB.block(epoch, inWorld).get());
        assertEquals(List.of(false, false, false, false, false), loadArguments);
        for (SpatialSnapshot snapshot : List.of(
                presentA, unknown, missing, outOfViewSnapshot, presentB)) {
            assertEquals(epoch, snapshot.worldEpoch());
        }
    }

    private static SpatialSnapshot capture(
            long epoch,
            BoxSnapshot bounds,
            BlockPosition block,
            FullChunkLookup lookup
    ) {
        return ClientSpatialSnapshotCapture.capturedSnapshot(
                new SpatialCaptureRequest(epoch, bounds, Set.of(block)),
                0, 4,
                new BoxSnapshot(0.2D, 1.0D, 0.2D, 0.8D, 2.8D, 0.8D),
                lookup);
    }

    private static ChunkSnapshot loaded(
            ChunkPosition position,
            BlockPosition block,
            BlockStateSnapshot state
    ) {
        return new ChunkSnapshot(position, true, Map.of(block, Observation.present(state)));
    }

    private static BlockStateSnapshot state(String blockId) {
        return new BlockStateSnapshot(
                ResourceIdentifier.parse(blockId),
                Map.of(),
                ResourceIdentifier.parse("minecraft:empty"),
                Observation.present(CollisionShapeSnapshot.EMPTY));
    }
}
