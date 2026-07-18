package dev.hylfrd.farmhelper.client.runtime;

import dev.hylfrd.farmhelper.feature.desync.DesyncCheckResult;
import dev.hylfrd.farmhelper.feature.desync.DesyncChecker;
import dev.hylfrd.farmhelper.macro.MacroPauseCause;
import dev.hylfrd.farmhelper.macro.MacroState;
import dev.hylfrd.farmhelper.runtime.snapshot.ConnectionSnapshot;
import dev.hylfrd.farmhelper.runtime.snapshot.Observation;
import dev.hylfrd.farmhelper.runtime.snapshot.ResourceIdentifier;
import dev.hylfrd.farmhelper.runtime.snapshot.ScreenSnapshot;
import dev.hylfrd.farmhelper.runtime.spatial.BlockPosition;
import dev.hylfrd.farmhelper.runtime.spatial.BlockStateSnapshot;
import dev.hylfrd.farmhelper.runtime.spatial.BoxSnapshot;
import dev.hylfrd.farmhelper.runtime.spatial.ChunkPosition;
import dev.hylfrd.farmhelper.runtime.spatial.ChunkSnapshot;
import dev.hylfrd.farmhelper.runtime.spatial.CollisionShapeSnapshot;
import dev.hylfrd.farmhelper.runtime.spatial.SpatialCaptureRequest;
import dev.hylfrd.farmhelper.runtime.spatial.SpatialSnapshot;
import dev.hylfrd.farmhelper.runtime.spatial.SpatialSnapshotCapturePort;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FarmHelperClientRuntimeDesyncTest {
    private static final BlockStateSnapshot SUGAR_CANE = new BlockStateSnapshot(
            ResourceIdentifier.parse("minecraft:sugar_cane"), Map.of(),
            ResourceIdentifier.parse("minecraft:empty"),
            Observation.present(CollisionShapeSnapshot.EMPTY));

    @TempDir
    Path temporaryDirectory;

    @Test
    void typedIngressUsesExactIdentityAndOneBoundedSpatialBatch() {
        RecordingSpatial spatial = RecordingSpatial.present(SUGAR_CANE);
        FarmHelperClientRuntime runtime = runtime("typed.json", spatial, () -> false);
        ready(runtime);
        assertTrue(runtime.setMacroMode(4));
        assertTrue(runtime.startMacro());

        long generation = runtime.core().macroManager().generation();
        long worldEpoch = runtime.lifecycle().worldEpoch();
        DesyncCheckResult result = DesyncCheckResult.STOPPED;
        for (int index = 0; index < DesyncChecker.WINDOW_SIZE; index++) {
            result = runtime.recordClick(new BlockPosition(index, 70, 0));
        }

        assertEquals(DesyncCheckResult.TRIGGERED, result);
        assertEquals(generation, runtime.desyncChecker().macroGeneration());
        assertEquals(worldEpoch, runtime.desyncChecker().worldEpoch());
        assertEquals(DesyncChecker.WINDOW_SIZE, spatial.calls);
        assertEquals(DesyncChecker.WINDOW_SIZE, spatial.requests.getLast().blocks().size());
        assertTrue(spatial.requests.stream().allMatch(request ->
                request.worldEpoch() == worldEpoch
                        && request.blocks().size() <= DesyncChecker.WINDOW_SIZE));
        assertEquals(DesyncChecker.State.RECOVERING, runtime.desyncChecker().state());
        assertEquals(MacroState.PAUSED, runtime.core().macroManager().state());
        assertEquals(Set.of(MacroPauseCause.FEATURE),
                runtime.core().macroManager().pauseCauses());

        assertEquals(DesyncCheckResult.RECOVERY_PENDING, runtime.tickDesync());
        assertEquals(DesyncCheckResult.RECOVERY_PENDING, runtime.tickDesync());
        assertEquals(DesyncChecker.State.RECOVERING, runtime.desyncChecker().state());
    }

    @Test
    void ambiguousMacroRunDoesNotGuessCropOrRecordClick() {
        RecordingSpatial spatial = RecordingSpatial.present(SUGAR_CANE);
        FarmHelperClientRuntime runtime = runtime("ambiguous.json", spatial, () -> false);
        ready(runtime);
        assertTrue(runtime.startMacro());

        assertEquals(DesyncCheckResult.ACTIVE_CROP_UNKNOWN,
                runtime.recordClick(new BlockPosition(0, 70, 0)));
        assertEquals(0, runtime.desyncChecker().acceptedClickCount());
        assertEquals(0, spatial.calls);
    }

    @Test
    void unknownAndCaptureErrorAreConservativeAtTypedIngress() {
        RecordingSpatial unknown = RecordingSpatial.unknown();
        FarmHelperClientRuntime unknownRuntime = runtime(
                "unknown.json", unknown, () -> false);
        ready(unknownRuntime);
        assertTrue(unknownRuntime.setMacroMode(4));
        assertTrue(unknownRuntime.startMacro());
        assertEquals(DesyncCheckResult.CLICK_BLOCK_UNKNOWN,
                unknownRuntime.recordClick(new BlockPosition(0, 70, 0)));
        assertEquals(0, unknownRuntime.desyncChecker().acceptedClickCount());

        RecordingSpatial failure = RecordingSpatial.failure();
        FarmHelperClientRuntime failureRuntime = runtime(
                "failure.json", failure, () -> false);
        ready(failureRuntime);
        assertTrue(failureRuntime.setMacroMode(4));
        assertTrue(failureRuntime.startMacro());
        assertEquals(DesyncCheckResult.CLICK_BLOCK_UNKNOWN,
                failureRuntime.recordClick(new BlockPosition(0, 70, 0)));
        assertEquals(0, failureRuntime.desyncChecker().acceptedClickCount());
    }

    @Test
    void macroFirstLifecycleStopsDesyncAcrossEveryTerminalBoundary() {
        for (Boundary boundary : Boundary.values()) {
            RecordingSpatial spatial = RecordingSpatial.present(SUGAR_CANE);
            FarmHelperClientRuntime runtime = runtime(
                    boundary.name().toLowerCase() + ".json", spatial, () -> false);
            ready(runtime);
            assertTrue(runtime.setMacroMode(4));
            assertTrue(runtime.startMacro());
            assertEquals(DesyncCheckResult.ACCEPTED,
                    runtime.recordClick(new BlockPosition(0, 70, 0)));
            assertEquals(1, runtime.desyncChecker().acceptedClickCount());

            boundary.apply(runtime);

            assertEquals(DesyncChecker.State.STOPPED, runtime.desyncChecker().state(),
                    boundary.name());
            assertFalse(runtime.core().macroManager().enabled(), boundary.name());
            assertTrue(runtime.core().macroManager().pauseCauses().isEmpty(), boundary.name());
        }
    }

    private FarmHelperClientRuntime runtime(
            String name,
            RecordingSpatial spatial,
            BooleanSupplier failsafe
    ) {
        return new FarmHelperClientRuntime(temporaryDirectory.resolve(name), spatial, failsafe);
    }

    private static void ready(FarmHelperClientRuntime runtime) {
        runtime.observeConnection(Observation.present(ConnectionSnapshot.multiplayer()));
    }

    private enum Boundary {
        MANUAL_STOP {
            @Override void apply(FarmHelperClientRuntime runtime) {
                assertTrue(runtime.stopMacro());
            }
        },
        WORLD_LOAD {
            @Override void apply(FarmHelperClientRuntime runtime) {
                runtime.worldLoaded();
            }
        },
        WORLD_UNLOAD {
            @Override void apply(FarmHelperClientRuntime runtime) {
                runtime.worldLoaded();
                ready(runtime);
                assertTrue(runtime.startMacro());
                runtime.worldUnloaded();
            }
        },
        CONNECTION_LOST {
            @Override void apply(FarmHelperClientRuntime runtime) {
                runtime.observeConnection(Observation.absent());
            }
        },
        DISCONNECT {
            @Override void apply(FarmHelperClientRuntime runtime) {
                runtime.disconnected();
            }
        },
        FAILURE {
            @Override void apply(FarmHelperClientRuntime runtime) {
                runtime.failed();
            }
        },
        CLIENT_STOP {
            @Override void apply(FarmHelperClientRuntime runtime) {
                runtime.clientStopping();
            }
        },
        SCREEN {
            @Override void apply(FarmHelperClientRuntime runtime) {
                runtime.observeMacroScreen(Observation.present(ScreenSnapshot.unknownDetails()));
                runtime.observeMacroScreen(Observation.absent());
                assertEquals(MacroState.RUNNING, runtime.core().macroManager().state());
                runtime.stopMacro();
            }
        };

        abstract void apply(FarmHelperClientRuntime runtime);
    }

    private static final class RecordingSpatial implements SpatialSnapshotCapturePort {
        private final Observation<BlockStateSnapshot> block;
        private final boolean failure;
        private final List<SpatialCaptureRequest> requests = new java.util.ArrayList<>();
        private int calls;

        private RecordingSpatial(Observation<BlockStateSnapshot> block, boolean failure) {
            this.block = block;
            this.failure = failure;
        }

        static RecordingSpatial present(BlockStateSnapshot block) {
            return new RecordingSpatial(Observation.present(block), false);
        }

        static RecordingSpatial unknown() {
            return new RecordingSpatial(Observation.unknown(), false);
        }

        static RecordingSpatial failure() {
            return new RecordingSpatial(Observation.unknown(), true);
        }

        @Override
        public Observation<SpatialSnapshot> capture(SpatialCaptureRequest request) {
            calls++;
            requests.add(request);
            if (failure) {
                throw new IllegalStateException("capture failed");
            }
            Map<ChunkPosition, Map<BlockPosition, Observation<BlockStateSnapshot>>> byChunk =
                    new LinkedHashMap<>();
            for (BlockPosition position : request.blocks()) {
                byChunk.computeIfAbsent(position.chunk(), ignored -> new LinkedHashMap<>())
                        .put(position, block);
            }
            Map<ChunkPosition, ChunkSnapshot> chunks = new HashMap<>();
            byChunk.forEach((position, blocks) ->
                    chunks.put(position, new ChunkSnapshot(position, true, blocks)));
            return Observation.present(new SpatialSnapshot(
                    request.worldEpoch(), request.requestToken(), request.bounds(),
                    0, 256, request.bounds(), chunks));
        }
    }
}
