package dev.hylfrd.farmhelper.client.runtime;

import dev.hylfrd.farmhelper.control.expectation.ExpectedMotion;
import dev.hylfrd.farmhelper.control.input.ControlOwner;
import dev.hylfrd.farmhelper.control.input.InputAction;
import dev.hylfrd.farmhelper.control.rotation.RotationProfile;
import dev.hylfrd.farmhelper.navigation.NavigationCancellationReason;
import dev.hylfrd.farmhelper.navigation.NavigationGoal;
import dev.hylfrd.farmhelper.navigation.NavigationHandle;
import dev.hylfrd.farmhelper.navigation.NavigationOptions;
import dev.hylfrd.farmhelper.navigation.NavigationRequest;
import dev.hylfrd.farmhelper.navigation.NavigationStartObservation;
import dev.hylfrd.farmhelper.navigation.NavigationTaskOwner;
import dev.hylfrd.farmhelper.runtime.snapshot.ConnectionSnapshot;
import dev.hylfrd.farmhelper.runtime.snapshot.MotionSnapshot;
import dev.hylfrd.farmhelper.runtime.snapshot.Observation;
import dev.hylfrd.farmhelper.runtime.snapshot.PlayerSnapshot;
import dev.hylfrd.farmhelper.runtime.snapshot.ScreenSnapshot;
import dev.hylfrd.farmhelper.runtime.snapshot.WorldSnapshot;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FarmHelperClientRuntimeNavigationTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void everyClientBoundaryMapsToADistinctNavigationTerminalReason() {
        FarmHelperClientRuntime manual = runtime("manual.json");
        manual.toggle();
        NavigationHandle manualHandle = start(manual, "manual");
        assertTrue(manual.stopMacro());
        assertReason(manualHandle, NavigationCancellationReason.STOPPED);

        FarmHelperClientRuntime screen = runtime("screen.json");
        screen.lifecycle().observeScreen(Observation.absent());
        NavigationHandle screenHandle = start(screen, "screen");
        screen.lifecycle().observeScreen(Observation.present(ScreenSnapshot.unknownDetails()));
        assertReason(screenHandle, NavigationCancellationReason.SCREEN_CHANGED);

        FarmHelperClientRuntime load = runtime("world-load.json");
        NavigationHandle loadHandle = start(load, "load");
        load.worldLoaded();
        assertReason(loadHandle, NavigationCancellationReason.WORLD_CHANGED);

        FarmHelperClientRuntime unload = runtime("world-unload.json");
        unload.worldLoaded();
        ready(unload);
        NavigationHandle unloadHandle = start(unload, "unload");
        unload.worldUnloaded();
        assertReason(unloadHandle, NavigationCancellationReason.WORLD_CHANGED);

        FarmHelperClientRuntime disconnect = runtime("disconnect.json");
        NavigationHandle disconnectHandle = start(disconnect, "disconnect");
        disconnect.disconnected();
        assertReason(disconnectHandle, NavigationCancellationReason.DISCONNECTED);

        FarmHelperClientRuntime connection = runtime("connection.json");
        NavigationHandle connectionHandle = start(connection, "connection");
        connection.observeConnection(Observation.absent());
        assertReason(connectionHandle, NavigationCancellationReason.CONNECTION_LOST);

        FarmHelperClientRuntime failure = runtime("failure.json");
        NavigationHandle failureHandle = start(failure, "failure");
        failure.failed();
        assertReason(failureHandle, NavigationCancellationReason.FAILURE);

        FarmHelperClientRuntime exit = runtime("exit.json");
        NavigationHandle exitHandle = start(exit, "exit");
        exit.clientStopping();
        assertReason(exitHandle, NavigationCancellationReason.CLIENT_EXIT);
    }

    @Test
    void navigationTerminalReleasesItsExactTaskInputRotationAndExpectationOwner() {
        FarmHelperClientRuntime runtime = runtime("owner-cleanup.json");
        ControlOwner owner = new ControlOwner("owned-navigation");
        NavigationHandle handle = start(runtime, owner);
        long ownershipGeneration = runtime.ownershipGeneration();
        List<RuntimeException> blockedReentry = new ArrayList<>();
        runtime.input().hold(owner, InputAction.FORWARD, InputAction.SPRINT);
        runtime.rotation().start(owner, 0F, 0F, 90F, 5F, 1_000L,
                RotationProfile.BACK, 0F, result -> {
                    try {
                        runtime.input().hold(owner, InputAction.ATTACK);
                    } catch (RuntimeException failure) {
                        blockedReentry.add(failure);
                    }
                });
        runtime.core().taskQueue().schedule(
                NavigationTaskOwner.from(handle.ticket()), 1_000L, () -> { });
        runtime.core().expectedActions().publish(
                owner, handle.ticket().generation(), handle.ticket().worldEpoch(),
                runtime.core().nowNanos(), runtime.core().nowNanos() + 1_000_000L,
                new ExpectedMotion(new MotionSnapshot(1, 0, 0), 0.1D));

        assertTrue(handle.cancel());

        assertTrue(runtime.input().snapshot().emptyState());
        assertFalse(runtime.rotation().rotating());
        assertEquals(ownershipGeneration + 1L, runtime.ownershipGeneration());
        assertEquals(1, blockedReentry.size());
        assertEquals("client transient ownership is fenced",
                blockedReentry.getFirst().getMessage());
        assertEquals(0, runtime.core().taskQueue().pendingTaskCount());
        assertTrue(runtime.core().expectedActions().snapshot().actions().isEmpty());
    }

    @Test
    void globalFanoutClearsOrphanExpectationsEvenWithoutAnActiveNavigationRun() {
        FarmHelperClientRuntime runtime = runtime("global-ledger.json");
        long now = runtime.core().nowNanos();
        runtime.core().expectedActions().publish(
                new ControlOwner("orphan"), 99L, runtime.lifecycle().worldEpoch(),
                now, now + 1_000_000L,
                new ExpectedMotion(new MotionSnapshot(0, 0, 0), 0));

        runtime.failed();

        assertTrue(runtime.core().expectedActions().snapshot().actions().isEmpty());
    }

    private FarmHelperClientRuntime runtime(String name) {
        FarmHelperClientRuntime runtime = new FarmHelperClientRuntime(
                temporaryDirectory.resolve(name));
        ready(runtime);
        return runtime;
    }

    private static void ready(FarmHelperClientRuntime runtime) {
        runtime.observeConnection(Observation.present(ConnectionSnapshot.multiplayer()));
    }

    private static NavigationHandle start(FarmHelperClientRuntime runtime, String owner) {
        return start(runtime, new ControlOwner("navigation-" + owner));
    }

    private static NavigationHandle start(FarmHelperClientRuntime runtime, ControlOwner owner) {
        long epoch = runtime.lifecycle().worldEpoch();
        return runtime.core().navigationController().start(
                new NavigationRequest(owner, epoch, new NavigationGoal(1, 70, 1),
                        NavigationOptions.fly()),
                new NavigationStartObservation(
                        Observation.present(new WorldSnapshot(epoch, Observation.unknown())),
                        Observation.present(new PlayerSnapshot(
                                Observation.unknown(), Observation.unknown(), Observation.unknown(),
                                Observation.unknown(), Observation.unknown())),
                        Observation.present(ConnectionSnapshot.multiplayer()), Observation.absent()));
    }

    private static void assertReason(
            NavigationHandle handle,
            NavigationCancellationReason expected
    ) {
        assertEquals(expected, handle.status().orElseThrow()
                .terminalResult().orElseThrow().cancellationReason().orElseThrow());
    }
}
