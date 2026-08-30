package dev.hylfrd.farmhelper.client.platform;

import dev.hylfrd.farmhelper.client.runtime.FarmHelperClientRuntime;
import dev.hylfrd.farmhelper.client.runtime.TestFarmHelperClientRuntimeFactory;
import dev.hylfrd.farmhelper.control.input.ControlOwner;
import dev.hylfrd.farmhelper.control.input.HotbarSelection;
import dev.hylfrd.farmhelper.control.input.InputAction;
import dev.hylfrd.farmhelper.control.inventory.InventoryOperation;
import dev.hylfrd.farmhelper.control.rotation.RotationTerminalReason;
import dev.hylfrd.farmhelper.macro.MacroState;
import dev.hylfrd.farmhelper.macro.MacroTerminalReason;
import dev.hylfrd.farmhelper.runtime.snapshot.ConnectionSnapshot;
import dev.hylfrd.farmhelper.runtime.snapshot.Observation;
import dev.hylfrd.farmhelper.runtime.snapshot.ScreenSnapshot;
import dev.hylfrd.farmhelper.runtime.time.TaskHandle;
import dev.hylfrd.farmhelper.runtime.time.TaskOwner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClientScreenLifecycleAdapterTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void nonChatSetScreenBoundaryStopsMacroAndDrainsTransientOwners() {
        FarmHelperClientRuntime runtime = ready("pause-boundary.json");
        ClientScreenLifecycleAdapter adapter = adapter(runtime);
        adapter.observeBoundary(Observation.absent(), null, false);

        assertTrue(runtime.startMacro());
        runtime.inventory().start(
                InventoryOperation.hotbar(
                        new ControlOwner("inventory-screen"), new HotbarSelection(3), 1_000_000L),
                ignored -> { });
        runtime.input().hold(new ControlOwner("screen-input"), InputAction.FORWARD);
        runtime.rotation().start(
                new ControlOwner("screen-rotation"), 0F, 0F, 45F, 5F, 1_000L);
        TaskHandle task = runtime.core().taskQueue().schedule(
                new TaskOwner("screen-task"), 1_000_000L, () -> { });

        adapter.observeBoundary(
                screen(1L, "net.minecraft.client.gui.screens.inventory.AbstractContainerScreen"),
                new Object(), false);

        assertFalse(runtime.core().macroManager().enabled());
        assertEquals(MacroState.STOPPED, runtime.core().macroManager().state());
        assertEquals(MacroTerminalReason.MANUAL_STOP,
                runtime.core().macroManager().lastTerminalReason().orElseThrow());
        assertTrue(runtime.core().macroManager().pauseCauses().isEmpty());
        assertTrue(runtime.inventory().activeToken().isEmpty());
        assertTrue(task.cancelled());
        assertTrue(runtime.input().snapshot().emptyState());
        assertFalse(runtime.rotation().rotating());
        assertEquals(RotationTerminalReason.SCREEN_CHANGED,
                runtime.rotation().snapshot().terminalReason().orElseThrow());

        long afterOpen = runtime.ownershipGeneration();
        adapter.observeBoundary(
                screen(1L, "net.minecraft.client.gui.screens.inventory.AbstractContainerScreen"),
                new Object(), false);
        assertEquals(afterOpen, runtime.ownershipGeneration());

        adapter.observeBoundary(Observation.absent(), null, false);
        assertFalse(runtime.core().macroManager().enabled());
        assertTrue(runtime.core().macroManager().pauseCauses().isEmpty());
    }

    @Test
    void expectedChatCommandCloseRemovesOnlyTheScreenPauseLease() {
        FarmHelperClientRuntime runtime = ready("chat-close.json");
        ClientCommandScreenCloseGuard guard = new ClientCommandScreenCloseGuard();
        ClientScreenLifecycleAdapter adapter = new ClientScreenLifecycleAdapter(
                runtime, guard, new ClientSnapshotCapture());
        Object chat = new Object();

        adapter.observeBoundary(
                screen(10L, "net.minecraft.client.gui.screens.ChatScreen"), chat, true);
        assertTrue(runtime.startMacro());
        runtime.input().hold(new ControlOwner("chat-command-input"), InputAction.FORWARD);
        long generation = runtime.core().macroManager().generation();
        guard.armAfterCommand(chat, true, runtime.ownershipGeneration());

        adapter.observeBoundary(Observation.absent(), null, false);

        assertTrue(runtime.core().macroManager().enabled());
        assertEquals(MacroState.RUNNING, runtime.core().macroManager().state());
        assertEquals(generation, runtime.core().macroManager().generation());
        assertTrue(runtime.input().snapshot().held(InputAction.FORWARD));
        long afterClose = runtime.ownershipGeneration();
        adapter.observeBoundary(Observation.absent(), null, false);
        assertEquals(afterClose, runtime.ownershipGeneration());
    }

    @Test
    void settingsBoundaryStopsBeforeSaveAndLeavesNoStalePauseState() {
        FarmHelperClientRuntime runtime = ready("settings-boundary.json");
        ClientScreenLifecycleAdapter adapter = adapter(runtime);
        adapter.observeBoundary(Observation.absent(), null, false);
        assertTrue(runtime.startMacro());

        adapter.observeBoundary(
                screen(20L, "dev.hylfrd.farmhelper.client.ui.settings.FarmHelperSettingsScreen"),
                new Object(), false);

        assertFalse(runtime.core().macroManager().enabled());
        assertTrue(runtime.core().macroManager().pauseCauses().isEmpty());
        assertTrue(runtime.saveConfig(runtime.configSnapshot()));

        long afterSettingsOpen = runtime.ownershipGeneration();
        adapter.observeBoundary(
                screen(20L, "dev.hylfrd.farmhelper.client.ui.settings.FarmHelperSettingsScreen"),
                new Object(), false);
        assertEquals(afterSettingsOpen, runtime.ownershipGeneration());
        adapter.observeBoundary(Observation.absent(), null, false);
        assertFalse(runtime.core().macroManager().enabled());
        assertTrue(runtime.core().macroManager().pauseCauses().isEmpty());
    }

    @Test
    void pauseScreenBoundaryStopsAndRemainsIdempotentAcrossClose() {
        FarmHelperClientRuntime runtime = ready("pause-screen.json");
        ClientScreenLifecycleAdapter adapter = adapter(runtime);
        adapter.observeBoundary(Observation.absent(), null, false);
        assertTrue(runtime.startMacro());

        adapter.observeBoundary(
                screen(30L, "net.minecraft.client.gui.screens.PauseScreen"),
                new Object(), false);

        assertFalse(runtime.core().macroManager().enabled());
        assertEquals(MacroState.STOPPED, runtime.core().macroManager().state());
        assertTrue(runtime.core().macroManager().pauseCauses().isEmpty());

        long beforeClose = runtime.ownershipGeneration();
        adapter.observeBoundary(Observation.absent(), null, false);
        long afterClose = runtime.ownershipGeneration();
        assertEquals(beforeClose + 1L, afterClose);
        adapter.observeBoundary(Observation.absent(), null, false);
        assertEquals(afterClose, runtime.ownershipGeneration());
        assertFalse(runtime.core().macroManager().enabled());
    }

    private FarmHelperClientRuntime ready(String name) {
        FarmHelperClientRuntime runtime = TestFarmHelperClientRuntimeFactory.create(
                temporaryDirectory.resolve(name));
        runtime.worldLoaded();
        runtime.observeConnection(Observation.present(ConnectionSnapshot.multiplayer()));
        return runtime;
    }

    private static ClientScreenLifecycleAdapter adapter(FarmHelperClientRuntime runtime) {
        return new ClientScreenLifecycleAdapter(
                runtime, new ClientCommandScreenCloseGuard(), new ClientSnapshotCapture());
    }

    private static Observation<ScreenSnapshot> screen(long identity, String type) {
        return Observation.present(new ScreenSnapshot(
                identity, Observation.present(type), Observation.present(type)));
    }
}
