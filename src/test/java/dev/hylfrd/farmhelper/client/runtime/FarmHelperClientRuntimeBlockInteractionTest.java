package dev.hylfrd.farmhelper.client.runtime;

import dev.hylfrd.farmhelper.runtime.interaction.BlockInteractionFace;
import dev.hylfrd.farmhelper.runtime.interaction.BlockInteractionSignal;
import dev.hylfrd.farmhelper.runtime.interaction.BlockInteractionSignalType;
import dev.hylfrd.farmhelper.runtime.snapshot.Observation;
import dev.hylfrd.farmhelper.runtime.snapshot.ResourceIdentifier;
import dev.hylfrd.farmhelper.runtime.spatial.BlockPosition;
import dev.hylfrd.farmhelper.runtime.spatial.BlockStateSnapshot;
import dev.hylfrd.farmhelper.runtime.spatial.BoxSnapshot;
import dev.hylfrd.farmhelper.runtime.spatial.ChunkPosition;
import dev.hylfrd.farmhelper.runtime.spatial.ChunkSnapshot;
import dev.hylfrd.farmhelper.runtime.spatial.CollisionShapeSnapshot;
import dev.hylfrd.farmhelper.runtime.spatial.SpatialCaptureRequest;
import dev.hylfrd.farmhelper.runtime.spatial.SpatialSnapshot;
import dev.hylfrd.farmhelper.runtime.spatial.SpatialSnapshotCapturePort;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FarmHelperClientRuntimeBlockInteractionTest {
    private static final BlockPosition POSITION = new BlockPosition(3, 70, -2);
    private static final BlockStateSnapshot PRE_BREAK = new BlockStateSnapshot(
            ResourceIdentifier.parse("minecraft:wheat"),
            Map.of("age", "7"),
            ResourceIdentifier.parse("minecraft:empty"),
            Observation.present(CollisionShapeSnapshot.EMPTY));

    @TempDir
    Path temporaryDirectory;

    @Test
    void rawClickIntentIsEmittedBeforeTheDesyncConfigurationGate() {
        List<BlockInteractionSignal> signals = new ArrayList<>();
        FarmHelperClientRuntime runtime = runtime(ignored -> Observation.unknown(), signals);
        runtime.core().config().setCheckDesync(false);

        runtime.recordClick(new BlockPos(POSITION.x(), POSITION.y(), POSITION.z()), Direction.UP);

        assertEquals(List.of(BlockInteractionSignalType.CLICK_INTENT),
                signals.stream().map(BlockInteractionSignal::type).toList());
    }

    @Test
    void destroyFalseIsSilentAndTruePublishesTheStateCapturedAtHead() {
        List<BlockInteractionSignal> signals = new ArrayList<>();
        FarmHelperClientRuntime runtime = runtime(
                request -> Observation.present(snapshot(request, PRE_BREAK)), signals);

        runtime.beginBlockBreak(new BlockPos(POSITION.x(), POSITION.y(), POSITION.z()));
        runtime.completeBlockBreak(false);
        assertTrue(signals.isEmpty());

        runtime.beginBlockBreak(new BlockPos(POSITION.x(), POSITION.y(), POSITION.z()));
        runtime.completeBlockBreak(true);

        assertEquals(1, signals.size());
        BlockInteractionSignal success = signals.getFirst();
        assertEquals(BlockInteractionSignalType.BREAK_SUCCESS, success.type());
        assertEquals(BlockInteractionFace.UNKNOWN, success.face());
        assertEquals(Observation.present(PRE_BREAK), success.preBreakState());
    }

    @Test
    void lifecycleBoundaryFencesAnInFlightDestroyReturn() {
        List<BlockInteractionSignal> signals = new ArrayList<>();
        FarmHelperClientRuntime runtime = runtime(
                request -> Observation.present(snapshot(request, PRE_BREAK)), signals);

        runtime.beginBlockBreak(new BlockPos(POSITION.x(), POSITION.y(), POSITION.z()));
        runtime.worldLoaded();
        runtime.completeBlockBreak(true);

        assertTrue(signals.isEmpty());
    }

    private FarmHelperClientRuntime runtime(
            SpatialSnapshotCapturePort spatial,
            List<BlockInteractionSignal> signals
    ) {
        return new FarmHelperClientRuntime(
                temporaryDirectory.resolve("block-interaction.json"),
                spatial,
                () -> false,
                signals::add);
    }

    private static SpatialSnapshot snapshot(
            SpatialCaptureRequest request,
            BlockStateSnapshot state
    ) {
        ChunkPosition chunkPosition = POSITION.chunk();
        ChunkSnapshot chunk = new ChunkSnapshot(
                chunkPosition, true, Map.of(POSITION, Observation.present(state)));
        return new SpatialSnapshot(
                request.worldEpoch(), request.requestToken(), request.bounds(),
                -64, 320, POSITION.unitBox(), Map.of(chunkPosition, chunk));
    }
}
