package dev.hylfrd.farmhelper.client.runtime;

import dev.hylfrd.farmhelper.config.FarmHelperConfigKey;
import dev.hylfrd.farmhelper.client.ui.command.ClientFarmHelperCommandService;
import dev.hylfrd.farmhelper.control.input.ControlOwner;
import dev.hylfrd.farmhelper.control.input.InputAction;
import dev.hylfrd.farmhelper.control.input.ReleaseReason;
import dev.hylfrd.farmhelper.control.inventory.InventoryCancelReason;
import dev.hylfrd.farmhelper.control.inventory.InventoryClick;
import dev.hylfrd.farmhelper.control.inventory.InventoryOperation;
import dev.hylfrd.farmhelper.control.inventory.InventoryPostcondition;
import dev.hylfrd.farmhelper.control.inventory.InventoryPort;
import dev.hylfrd.farmhelper.control.inventory.InventoryExecutionResult;
import dev.hylfrd.farmhelper.control.inventory.InventoryClickGuard;
import dev.hylfrd.farmhelper.control.inventory.InventoryOperationToken;
import dev.hylfrd.farmhelper.control.inventory.InventoryScreenSnapshot;
import dev.hylfrd.farmhelper.control.inventory.InventoryQuery;
import dev.hylfrd.farmhelper.control.inventory.InventoryStep;
import dev.hylfrd.farmhelper.control.inventory.ScreenExpectation;
import dev.hylfrd.farmhelper.control.inventory.ScreenIdentity;
import dev.hylfrd.farmhelper.control.rotation.RotationTerminalReason;
import dev.hylfrd.farmhelper.runtime.gamestate.RawGameTextSnapshot;
import dev.hylfrd.farmhelper.runtime.snapshot.ClientSnapshot;
import dev.hylfrd.farmhelper.runtime.snapshot.ConnectionSnapshot;
import dev.hylfrd.farmhelper.runtime.snapshot.Observation;
import dev.hylfrd.farmhelper.runtime.time.TaskHandle;
import dev.hylfrd.farmhelper.runtime.time.TaskOwner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

class FarmHelperClientRuntimeTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void configCommandOperationsPersistAndResetOnlyTheRequestedKey() {
        Path configPath = temporaryDirectory.resolve("farmhelper.json");
        FarmHelperClientRuntime runtime = new FarmHelperClientRuntime(configPath);

        assertTrue(runtime.setConfig(FarmHelperConfigKey.TARGET_YAW, 45.0F));
        assertTrue(runtime.setConfig(FarmHelperConfigKey.TARGET_PITCH, 30.0F));
        assertTrue(runtime.resetConfig(FarmHelperConfigKey.TARGET_YAW));

        FarmHelperClientRuntime reloaded = new FarmHelperClientRuntime(configPath);
        assertEquals(0.0F, reloaded.configValue(FarmHelperConfigKey.TARGET_YAW));
        assertEquals(30.0F, reloaded.configValue(FarmHelperConfigKey.TARGET_PITCH));
    }

    @Test
    void rejectedConfigUpdateRestoresThePreviousValue() {
        Path configPath = temporaryDirectory.resolve("farmhelper.json");
        FarmHelperClientRuntime runtime = new FarmHelperClientRuntime(configPath);
        assertTrue(runtime.setConfig(FarmHelperConfigKey.TARGET_YAW, 60.0F));

        assertFalse(runtime.setConfig(FarmHelperConfigKey.TARGET_YAW, Float.NaN));

        assertEquals(60.0F, runtime.configValue(FarmHelperConfigKey.TARGET_YAW));
        FarmHelperClientRuntime reloaded = new FarmHelperClientRuntime(configPath);
        assertEquals(60.0F, reloaded.configValue(FarmHelperConfigKey.TARGET_YAW));
    }

    @Test
    void disconnectCompositionCancelsEveryOwnedServiceAndInvalidatesPublishedState() {
        FarmHelperClientRuntime runtime = new FarmHelperClientRuntime(
                temporaryDirectory.resolve("farmhelper.json"));
        ready(runtime);
        ControlOwner owner = new ControlOwner("composition-test");
        TaskHandle task = runtime.core().taskQueue().schedule(
                new TaskOwner("composition-test"), 0L, () -> { });
        runtime.core().macroManager().start();
        runtime.core().parseGameState(ClientSnapshot.unknown(), RawGameTextSnapshot.unknown(1L));
        runtime.rotation().start(owner, 0F, 0F, 90F, 10F, 1_000L);
        runtime.input().hold(owner, InputAction.FORWARD);

        List<InventoryCancelReason> inventoryReasons = new ArrayList<>();
        ScreenIdentity identity = new ScreenIdentity(1L, 1, "minecraft:generic_9x3");
        InventoryOperation operation = InventoryOperation.clicks(
                owner,
                ScreenExpectation.exact(identity, identity.type(), "Container", 1, List.of()),
                List.of(new InventoryStep(InventoryQuery.slot(0), new InventoryClick.QuickMove(),
                        InventoryPostcondition.TARGET_SLOT_CHANGED)),
                1_000L);
        runtime.inventory().start(operation,
                outcome -> inventoryReasons.add(outcome.reason().orElseThrow()));

        runtime.disconnected();

        assertFalse(runtime.core().macroManager().enabled());
        assertTrue(task.cancelled());
        assertEquals(0, runtime.core().taskQueue().pendingTaskCount());
        assertTrue(runtime.inventory().activeToken().isEmpty());
        assertEquals(List.of(InventoryCancelReason.DISCONNECTED), inventoryReasons);
        assertFalse(runtime.rotation().rotating());
        assertEquals(RotationTerminalReason.DISCONNECTED,
                runtime.rotation().snapshot().terminalReason().orElseThrow());
        assertEquals(ReleaseReason.DISCONNECT,
                runtime.input().snapshot().releaseReason().orElseThrow());
        assertTrue(runtime.input().snapshot().emptyState());
        assertTrue(runtime.core().currentGameState().isEmpty());
    }

    @Test
    void toggleOffUsesManualStopToReleaseEveryOwnerAndTerminalReason() {
        FarmHelperClientRuntime runtime = new FarmHelperClientRuntime(
                temporaryDirectory.resolve("farmhelper.json"));
        ready(runtime);
        ClientFarmHelperCommandService commands = new ClientFarmHelperCommandService(
                runtime, () -> false);
        ControlOwner owner = new ControlOwner("manual-toggle");
        assertEquals("Macro enabled.", commands.toggle().message());
        TaskHandle task = runtime.core().taskQueue().schedule(
                new TaskOwner("manual-toggle"), 0L, () -> { });
        runtime.input().hold(owner, InputAction.FORWARD);
        runtime.rotation().start(owner, 0F, 0F, 30F, 5F, 1_000L);
        List<InventoryCancelReason> inventoryReasons = new ArrayList<>();
        runtime.inventory().start(operation(owner, 2L), outcome ->
                inventoryReasons.add(outcome.reason().orElseThrow()));

        assertEquals("Macro disabled.", commands.toggle().message());

        assertFalse(runtime.core().macroManager().enabled());
        assertTrue(task.cancelled());
        assertEquals(0, runtime.core().taskQueue().pendingTaskCount());
        assertTrue(runtime.inventory().activeToken().isEmpty());
        assertEquals(List.of(InventoryCancelReason.REQUESTED), inventoryReasons);
        assertFalse(runtime.rotation().rotating());
        assertEquals(RotationTerminalReason.STOPPED,
                runtime.rotation().snapshot().terminalReason().orElseThrow());
        assertTrue(runtime.input().snapshot().emptyState());
        assertEquals(ReleaseReason.STOP,
                runtime.input().snapshot().releaseReason().orElseThrow());
    }

    @Test
    void cancellationFenceRejectsCallbackReentryAndStillReleasesAllOwners() {
        List<RuntimeException> blocked = new ArrayList<>();
        FarmHelperClientRuntime runtime = new FarmHelperClientRuntime(
                temporaryDirectory.resolve("farmhelper.json"),
                new PassiveInventoryPort(),
                diagnostic -> { throw new IllegalStateException("diagnostic failed"); });
        ready(runtime);
        ControlOwner owner = new ControlOwner("reentry");
        runtime.toggle();
        TaskHandle originalTask = runtime.core().taskQueue().schedule(
                new TaskOwner("reentry-original"), 0L, () -> { });
        runtime.input().hold(owner, InputAction.FORWARD);
        runtime.rotation().start(owner, 0F, 0F, 45F, 0F, 1_000L);
        InventoryOperation operation = operation(owner, 3L);
        runtime.inventory().start(operation, outcome -> {
            captureBlocked(blocked, () -> runtime.inventory().start(operation, ignored -> { }));
            captureBlocked(blocked, () -> runtime.core().taskQueue().schedule(
                    new TaskOwner("reentered"), 0L, () -> { }));
            captureBlocked(blocked, () -> runtime.core().macroManager().start());
            captureBlocked(blocked, () -> runtime.input().hold(owner, InputAction.USE));
            captureBlocked(blocked, () -> runtime.rotation().start(
                    owner, 0F, 0F, 90F, 0F, 1_000L));
            throw new IllegalStateException("callback failed");
        });
        runtime.core().parseGameState(ClientSnapshot.unknown(), RawGameTextSnapshot.unknown(4L));

        runtime.disconnected();

        assertEquals(5, blocked.size());
        assertTrue(blocked.stream().allMatch(failure ->
                "client transient ownership is fenced".equals(failure.getMessage())));
        assertFalse(runtime.core().macroManager().enabled());
        assertTrue(originalTask.cancelled());
        assertEquals(0, runtime.core().taskQueue().pendingTaskCount());
        assertTrue(runtime.inventory().activeToken().isEmpty());
        assertFalse(runtime.rotation().rotating());
        assertTrue(runtime.input().snapshot().emptyState());
        assertTrue(runtime.core().currentGameState().isEmpty());
    }

    @Test
    void absentOrUnknownConnectionKeepsEveryAcquisitionClosed() {
        FarmHelperClientRuntime runtime = new FarmHelperClientRuntime(
                temporaryDirectory.resolve("farmhelper.json"));

        assertFenced(runtime);
        runtime.observeConnection(Observation.unknown());
        assertFenced(runtime);
        runtime.observeConnection(Observation.absent());
        assertFenced(runtime);
        assertFalse(runtime.core().macroManager().enabled());
        assertEquals(0, runtime.core().taskQueue().pendingTaskCount());
        assertTrue(runtime.input().snapshot().emptyState());
        assertFalse(runtime.rotation().rotating());
        assertTrue(runtime.inventory().activeToken().isEmpty());
    }

    private static void assertFenced(FarmHelperClientRuntime runtime) {
        ControlOwner owner = new ControlOwner("fenced");
        assertThrows(IllegalStateException.class, runtime::toggle);
        assertThrows(IllegalStateException.class, () -> runtime.core().taskQueue().schedule(
                new TaskOwner("fenced"), 0L, () -> { }));
        assertThrows(IllegalStateException.class,
                () -> runtime.input().hold(owner, InputAction.FORWARD));
        assertThrows(IllegalStateException.class,
                () -> runtime.rotation().start(owner, 0F, 0F, 1F, 1F, 1L));
        assertThrows(IllegalStateException.class,
                () -> runtime.inventory().start(operation(owner, 10L), ignored -> { }));
    }

    private static void captureBlocked(List<RuntimeException> failures, Runnable action) {
        try {
            action.run();
        } catch (RuntimeException failure) {
            failures.add(failure);
        }
    }

    private static void ready(FarmHelperClientRuntime runtime) {
        runtime.observeConnection(Observation.present(ConnectionSnapshot.multiplayer()));
    }

    private static InventoryOperation operation(ControlOwner owner, long identityValue) {
        ScreenIdentity identity = new ScreenIdentity(identityValue, 1, "minecraft:generic_9x3");
        return InventoryOperation.clicks(
                owner,
                ScreenExpectation.exact(identity, identity.type(), "Container", 1, List.of()),
                List.of(new InventoryStep(InventoryQuery.slot(0), new InventoryClick.QuickMove(),
                        InventoryPostcondition.TARGET_SLOT_CHANGED)),
                1_000L);
    }

    private static final class PassiveInventoryPort implements InventoryPort {
        @Override
        public Observation<InventoryScreenSnapshot> observe(
                InventoryOperationToken token, ControlOwner owner) {
            return Observation.unknown();
        }

        @Override
        public InventoryExecutionResult executeGuardedClick(InventoryClickGuard guard) {
            return InventoryExecutionResult.rejected(InventoryCancelReason.ADAPTER_EXCEPTION);
        }

        @Override
        public void releaseOperation(InventoryOperationToken token, ControlOwner owner) { }

        @Override
        public Optional<InventoryCancelReason> closeScreen(ScreenIdentity expected) {
            return Optional.empty();
        }
    }
}
