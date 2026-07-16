package dev.hylfrd.farmhelper.client.platform.spatial;

import dev.hylfrd.farmhelper.runtime.spatial.BlockPosition;
import dev.hylfrd.farmhelper.runtime.spatial.BoxSnapshot;
import dev.hylfrd.farmhelper.runtime.spatial.CollisionShapeSnapshot;
import dev.hylfrd.farmhelper.runtime.spatial.SpatialCaptureRequest;
import net.minecraft.world.phys.shapes.Shapes;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
    }
}
