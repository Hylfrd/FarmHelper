package dev.hylfrd.farmhelper.client.platform;

import dev.hylfrd.farmhelper.client.runtime.FarmHelperClientRuntime;
import dev.hylfrd.farmhelper.client.runtime.TestFarmHelperClientRuntimeFactory;
import dev.hylfrd.farmhelper.control.input.ControlOwner;
import dev.hylfrd.farmhelper.control.input.HotbarSelection;
import dev.hylfrd.farmhelper.control.input.InputAction;
import dev.hylfrd.farmhelper.control.input.InputSnapshot;
import dev.hylfrd.farmhelper.control.input.ReleaseReason;
import dev.hylfrd.farmhelper.control.rotation.RotationTerminalReason;
import dev.hylfrd.farmhelper.macro.MacroWarpRequest;
import dev.hylfrd.farmhelper.macro.MacroDecision;
import dev.hylfrd.farmhelper.macro.MacroSpawnPose;
import dev.hylfrd.farmhelper.macro.MacroControlOwner;
import dev.hylfrd.farmhelper.macro.FeatureSuspension;
import dev.hylfrd.farmhelper.runtime.snapshot.ConnectionSnapshot;
import dev.hylfrd.farmhelper.runtime.snapshot.ClientSnapshot;
import dev.hylfrd.farmhelper.runtime.snapshot.Observation;
import dev.hylfrd.farmhelper.runtime.snapshot.PlayerSnapshot;
import dev.hylfrd.farmhelper.runtime.snapshot.ScreenSnapshot;
import dev.hylfrd.farmhelper.runtime.snapshot.WorldSnapshot;
import dev.hylfrd.farmhelper.runtime.spatial.RewarpPosition;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClientTickAdapterTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void inputSafetyWithNoManagedClaimsPreservesTerminalReasonAndRevision() {
        FarmHelperClientRuntime runtime = runtime("empty-input.json");
        ClientSnapshot unsafe = safeSnapshot(Observation.absent());

        for (ReleaseReason reason : List.of(
                ReleaseReason.STOP, ReleaseReason.DISCONNECT, ReleaseReason.EXCEPTION)) {
            runtime.input().releaseAll(reason);
            InputSnapshot before = runtime.input().snapshot();

            ClientTickAdapter.enforceInputSafety(runtime, unsafe);

            assertEquals(before, runtime.input().snapshot());
        }
    }

    @Test
    void inputSafetyReleasesManagedActionAndHotbarForEveryUnsafeBoundary() {
        assertManagedRelease("screen.json", ReleaseReason.SCREEN,
                safeSnapshot(Observation.present(new ScreenSnapshot(
                        1L, Observation.present("screen"), Observation.present("Screen")))),
                runtime -> runtime.toggle());
        assertManagedRelease("world.json", ReleaseReason.WORLD_CHANGE,
                snapshot(Observation.absent(), Observation.present(ConnectionSnapshot.multiplayer()),
                        Observation.absent()), runtime -> runtime.toggle());
        assertManagedRelease("connection.json", ReleaseReason.WORLD_CHANGE,
                snapshot(Observation.present(new WorldSnapshot(1L, Observation.unknown())),
                        Observation.absent(), Observation.absent()), runtime -> runtime.toggle());
        assertManagedRelease("rotation.json", ReleaseReason.ROTATION_CONFLICT,
                safeSnapshot(Observation.absent()), runtime -> {
                    runtime.toggle();
                    runtime.rotation().start(new ControlOwner("rotation"),
                            0F, 0F, 45F, 5F, 1_000L);
                });
    }

    @Test
    void runningSafeTickLeavesManagedClaimsUntouched() {
        FarmHelperClientRuntime runtime = runtime("safe-running.json");
        runtime.toggle();
        ControlOwner owner = new ControlOwner("safe-running");
        runtime.input().hold(owner, List.of(InputAction.FORWARD),
                Optional.of(new HotbarSelection(2)));
        InputSnapshot before = runtime.input().snapshot();

        ClientTickAdapter.enforceInputSafety(runtime, safeSnapshot(Observation.absent()));

        assertEquals(before, runtime.input().snapshot());
        assertFalse(runtime.input().snapshot().emptyState());
    }

    @Test
    void requiresAllThreeDevelopmentConditions() {
        assertTrue(ClientTickAdapter.developmentGarden(true, true, true));
        assertFalse(ClientTickAdapter.developmentGarden(false, true, true));
        assertFalse(ClientTickAdapter.developmentGarden(true, false, true));
        assertFalse(ClientTickAdapter.developmentGarden(true, true, false));
    }

    @Test
    void productionAlwaysUsesWarpGarden() {
        RewarpPosition spawn = new RewarpPosition(10, 70, -3);
        assertEquals("warp garden", ClientTickAdapter.warpCommand(
                new MacroWarpRequest(false, new MacroSpawnPose(spawn, 91.5F, -12.25F, 4))));
        assertEquals("tp 10 70 -3 91.5 -12.25", ClientTickAdapter.warpCommand(
                new MacroWarpRequest(true, new MacroSpawnPose(spawn, 91.5F, -12.25F, 4))));
    }

    @Test
    void featurePausePreservesFeatureOwnersAcrossSafeTick() {
        FarmHelperClientRuntime runtime = runtime("feature-owner.json");
        runtime.toggle();
        runtime.input().hold(MacroControlOwner.S_SHAPE, InputAction.ATTACK);
        runtime.rotation().start(MacroControlOwner.S_SHAPE, 0F, 0F, 45F, 5F, 1_000L);
        FeatureSuspension suspension = runtime.core().macroManager().suspendForFeature("feature");
        ControlOwner feature = new ControlOwner("feature-owner");
        runtime.input().hold(feature, List.of(InputAction.USE),
                Optional.of(new HotbarSelection(4)));
        runtime.rotation().start(feature, 0F, 0F, 10F, 2F, 1_000L);

        ClientTickAdapter.enforceInputSafety(runtime, safeSnapshot(Observation.absent()));

        assertEquals(feature, runtime.input().snapshot().ownerOf(InputAction.USE).orElseThrow());
        assertEquals(feature, runtime.input().snapshot().hotbarOwner().orElseThrow());
        assertEquals(feature, runtime.rotation().snapshot().owner().orElseThrow());
        assertTrue(runtime.rotation().rotating());
        suspension.close();
        assertEquals(feature, runtime.input().snapshot().ownerOf(InputAction.USE).orElseThrow());
        assertEquals(feature, runtime.rotation().snapshot().owner().orElseThrow());
    }

    @Test
    void everyUnknownMacroDecisionCancelsOnlyMacroRotationBeforeFrame() {
        for (String status : List.of(
                "garden-unknown", "server-unknown", "spatial-unknown",
                "player-unknown", "row-spatial-unknown")) {
            FarmHelperClientRuntime runtime = runtime(status + ".json");
            runtime.input().hold(MacroControlOwner.S_SHAPE, InputAction.ATTACK);
            runtime.rotation().start(MacroControlOwner.S_SHAPE,
                    0F, 0F, 45F, 5F, 1_000L);

            TestClientTickAdapterAccess.applyManagedDecision(
                    runtime, MacroDecision.failClosed(status));

            assertFalse(runtime.rotation().rotating(), status);
            assertEquals(RotationTerminalReason.OWNER_CANCELLED,
                    runtime.rotation().snapshot().terminalReason().orElseThrow(), status);
            assertTrue(runtime.input().snapshot().emptyState(), status);

            ControlOwner feature = new ControlOwner("feature-" + status);
            runtime.input().hold(feature, List.of(InputAction.USE),
                    Optional.of(new HotbarSelection(7)));
            runtime.rotation().start(feature, 0F, 0F, 10F, 2F, 1_000L);
            TestClientTickAdapterAccess.applyManagedDecision(
                    runtime, MacroDecision.failClosed(status));
            assertTrue(runtime.rotation().rotating(), status);
            assertEquals(feature, runtime.rotation().snapshot().owner().orElseThrow(), status);
            assertEquals(feature, runtime.input().snapshot().ownerOf(InputAction.USE).orElseThrow());
            assertEquals(feature, runtime.input().snapshot().hotbarOwner().orElseThrow());
        }
    }

    @Test
    void ordinaryDecisionWithoutNewRotationKeepsActiveMacroRotation() {
        FarmHelperClientRuntime runtime = runtime("rotation-keep.json");
        runtime.rotation().start(MacroControlOwner.S_SHAPE,
                0F, 0F, 45F, 5F, 1_000L);

        TestClientTickAdapterAccess.applyMacroRotationDisposition(
                runtime, MacroDecision.idle("ordinary-wait"));

        assertTrue(runtime.rotation().rotating());
        assertEquals(MacroControlOwner.S_SHAPE,
                runtime.rotation().snapshot().owner().orElseThrow());
    }

    @Test
    void pipelineAppliesTypedReleaseBeforeRotationAndPreservesFeatureOwner() {
        FarmHelperClientRuntime runtime = runtime("pipeline-release.json");
        runtime.rotation().start(MacroControlOwner.S_SHAPE,
                0F, 0F, 45F, 5F, 1_000L);
        runtime.input().hold(MacroControlOwner.S_SHAPE, InputAction.ATTACK);

        assertTrue(TestClientTickAdapterAccess.decisionBeforeRotation(runtime,
                safeSnapshot(Observation.absent()), MacroDecision.failClosed("garden-unknown"),
                () -> assertFalse(runtime.rotation().rotating())).isEmpty());

        ControlOwner feature = new ControlOwner("feature-pipeline");
        runtime.rotation().start(feature, 0F, 0F, 10F, 2F, 1_000L);
        runtime.input().hold(feature, InputAction.USE);
        assertTrue(TestClientTickAdapterAccess.decisionBeforeRotation(runtime,
                safeSnapshot(Observation.absent()), MacroDecision.failClosed("server-unknown"),
                () -> {
                    assertTrue(runtime.rotation().rotating());
                    assertEquals(feature, runtime.rotation().snapshot().owner().orElseThrow());
                }).isEmpty());
        assertEquals(feature, runtime.input().snapshot().ownerOf(InputAction.USE).orElseThrow());
    }

    private void assertManagedRelease(
            String config,
            ReleaseReason expected,
            ClientSnapshot snapshot,
            java.util.function.Consumer<FarmHelperClientRuntime> setup
    ) {
        FarmHelperClientRuntime runtime = runtime(config);
        setup.accept(runtime);
        ControlOwner owner = new ControlOwner(config);
        runtime.input().hold(owner, List.of(InputAction.FORWARD),
                Optional.of(new HotbarSelection(3)));
        long beforeRevision = runtime.input().snapshot().revision();

        ClientTickAdapter.enforceInputSafety(runtime, snapshot);

        assertTrue(runtime.input().snapshot().emptyState());
        assertEquals(expected, runtime.input().snapshot().releaseReason().orElseThrow());
        assertTrue(runtime.input().snapshot().revision() > beforeRevision);
    }

    private FarmHelperClientRuntime runtime(String name) {
        FarmHelperClientRuntime runtime = TestFarmHelperClientRuntimeFactory.create(
                temporaryDirectory.resolve(name));
        runtime.worldLoaded();
        runtime.observeConnection(Observation.present(ConnectionSnapshot.multiplayer()));
        return runtime;
    }

    private static ClientSnapshot safeSnapshot(Observation<ScreenSnapshot> screen) {
        return snapshot(
                Observation.present(new WorldSnapshot(1L, Observation.unknown())),
                Observation.present(ConnectionSnapshot.multiplayer()), screen);
    }

    private static ClientSnapshot snapshot(
            Observation<WorldSnapshot> world,
            Observation<ConnectionSnapshot> connection,
            Observation<ScreenSnapshot> screen
    ) {
        return new ClientSnapshot(
                Observation.present(new PlayerSnapshot(
                        Observation.unknown(), Observation.unknown(), Observation.unknown(),
                        Observation.unknown(), Observation.unknown())),
                world, connection, screen);
    }
}
