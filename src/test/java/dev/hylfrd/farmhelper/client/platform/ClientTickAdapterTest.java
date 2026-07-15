package dev.hylfrd.farmhelper.client.platform;

import dev.hylfrd.farmhelper.macro.MacroContext;
import dev.hylfrd.farmhelper.macro.MacroManager;
import dev.hylfrd.farmhelper.macro.MacroState;
import dev.hylfrd.farmhelper.macro.PauseReason;
import dev.hylfrd.farmhelper.client.runtime.FarmHelperClientRuntime;
import dev.hylfrd.farmhelper.client.runtime.TestFarmHelperClientRuntimeFactory;
import dev.hylfrd.farmhelper.control.input.ControlOwner;
import dev.hylfrd.farmhelper.control.input.HotbarSelection;
import dev.hylfrd.farmhelper.control.input.InputAction;
import dev.hylfrd.farmhelper.control.input.InputSnapshot;
import dev.hylfrd.farmhelper.control.input.ReleaseReason;
import dev.hylfrd.farmhelper.runtime.snapshot.ConnectionSnapshot;
import dev.hylfrd.farmhelper.runtime.snapshot.ClientSnapshot;
import dev.hylfrd.farmhelper.runtime.snapshot.Observation;
import dev.hylfrd.farmhelper.runtime.snapshot.PlayerSnapshot;
import dev.hylfrd.farmhelper.runtime.snapshot.ScreenSnapshot;
import dev.hylfrd.farmhelper.runtime.snapshot.WorldSnapshot;
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
    void absentAndUnknownConnectionsPauseBeforeMacroDelivery() {
        assertConnectionUnavailable(Observation.absent());
        assertConnectionUnavailable(Observation.unknown());
    }

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
        assertManagedRelease("pause.json", ReleaseReason.PAUSE,
                safeSnapshot(Observation.absent()), runtime -> { });
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

    private static void assertConnectionUnavailable(
            Observation<dev.hylfrd.farmhelper.runtime.snapshot.ConnectionSnapshot> connection) {
        ClientSnapshot snapshot = new ClientSnapshot(
                Observation.present(new PlayerSnapshot(
                        Observation.unknown(), Observation.unknown(), Observation.unknown(),
                        Observation.unknown(), Observation.unknown())),
                Observation.present(new WorldSnapshot(1L, Observation.unknown())),
                connection,
                Observation.absent());
        MacroContext context = ClientTickAdapter.macroContext(snapshot);
        MacroManager manager = new MacroManager();
        manager.start();

        manager.tick(snapshot, context);

        assertFalse(context.playerReady());
        assertEquals(PauseReason.NO_CONNECTION, context.pauseReason());
        assertEquals(MacroState.PAUSED, manager.state());
        assertEquals(0L, manager.runningTicks());
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
