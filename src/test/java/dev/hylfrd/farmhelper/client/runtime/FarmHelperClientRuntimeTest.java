package dev.hylfrd.farmhelper.client.runtime;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.hylfrd.farmhelper.config.FarmHelperConfigKey;
import dev.hylfrd.farmhelper.config.FarmHelperConfig;
import dev.hylfrd.farmhelper.config.MacroLocationConfig;
import dev.hylfrd.farmhelper.client.platform.ClientCommandScreenCloseGuard;
import dev.hylfrd.farmhelper.client.platform.TestClientTickAdapterAccess;
import dev.hylfrd.farmhelper.client.ui.command.ClientFarmHelperCommandService;
import dev.hylfrd.farmhelper.control.input.ControlOwner;
import dev.hylfrd.farmhelper.control.input.InputAction;
import dev.hylfrd.farmhelper.control.input.HotbarSelection;
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
import dev.hylfrd.farmhelper.macro.MacroState;
import dev.hylfrd.farmhelper.macro.MacroTerminalReason;
import dev.hylfrd.farmhelper.macro.MacroControlOwner;
import dev.hylfrd.farmhelper.macro.MacroPauseCause;
import dev.hylfrd.farmhelper.macro.FeatureSuspension;
import dev.hylfrd.farmhelper.runtime.gamestate.RawGameTextSnapshot;
import dev.hylfrd.farmhelper.runtime.snapshot.ClientSnapshot;
import dev.hylfrd.farmhelper.runtime.snapshot.ConnectionSnapshot;
import dev.hylfrd.farmhelper.runtime.snapshot.Observation;
import dev.hylfrd.farmhelper.runtime.snapshot.PlayerSnapshot;
import dev.hylfrd.farmhelper.runtime.snapshot.ScreenSnapshot;
import dev.hylfrd.farmhelper.runtime.snapshot.WorldSnapshot;
import dev.hylfrd.farmhelper.runtime.time.TaskHandle;
import dev.hylfrd.farmhelper.runtime.time.TaskOwner;
import dev.hylfrd.farmhelper.ui.command.FarmHelperCommandTree;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.nio.file.Files;
import java.io.IOException;
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

        assertTrue(runtime.setMacroMode(5));
        assertFalse(runtime.setMacroMode(14));
        assertEquals(5, runtime.core().config().macroMode());
        assertEquals(5, runtime.core().macroManager().settings().mode().code());
    }

    @Test
    void modeChangeWhileActiveIsRejectedWithoutMutation() {
        Path configPath = temporaryDirectory.resolve("active-mode.json");
        FarmHelperClientRuntime runtime = TestFarmHelperClientRuntimeFactory.create(configPath);
        assertTrue(runtime.setMacroMode(5));
        ready(runtime);
        assertTrue(runtime.startMacro());
        long generation = runtime.core().macroManager().generation();
        MacroState state = runtime.core().macroManager().state();

        assertFalse(runtime.setMacroMode(6));

        assertEquals(5, runtime.core().config().macroMode());
        assertEquals(5, runtime.core().macroManager().settings().mode().code());
        assertEquals(generation, runtime.core().macroManager().generation());
        assertEquals(state, runtime.core().macroManager().state());
        FarmHelperClientRuntime reloaded = TestFarmHelperClientRuntimeFactory.create(configPath);
        assertEquals(5, reloaded.core().config().macroMode());
        assertEquals(5, reloaded.core().macroManager().settings().mode().code());

        ClientFarmHelperCommandService service = new ClientFarmHelperCommandService(
                runtime, () -> false, new ClientCommandScreenCloseGuard());
        assertEquals("Stop the macro before changing mode.", service.setMacroMode(6).message());
    }

    @Test
    void everyRunSettingChangeIsRejectedBeforeMemoryOrDiskMutation() {
        Path configPath = temporaryDirectory.resolve("active-run-settings.json");
        FarmHelperClientRuntime runtime = TestFarmHelperClientRuntimeFactory.create(configPath);
        MacroLocationConfig original = new MacroLocationConfig(10, 72, -4, 0F, 0F, 2);
        assertTrue(runtime.setMacroSpawn(original));
        ready(runtime);
        assertTrue(runtime.startMacro());

        FarmHelperConfig changed = runtime.configSnapshot();
        changed.setRotateAfterDrop(true);
        changed.setCustomPitch(true);
        changed.setCustomPitchLevel(51.0F);
        assertFalse(runtime.saveConfig(changed));
        assertFalse(runtime.setMacroSpawn(
                new MacroLocationConfig(20, 80, 6, 0F, 0F, 3)));

        assertFalse(runtime.core().config().rotateAfterDrop());
        assertFalse(runtime.core().config().customPitch());
        assertEquals(original, runtime.core().config().macroSpawn().orElseThrow());
        FarmHelperClientRuntime reloaded =
                TestFarmHelperClientRuntimeFactory.create(configPath);
        assertFalse(reloaded.core().config().rotateAfterDrop());
        assertFalse(reloaded.core().config().customPitch());
        assertEquals(original, reloaded.core().config().macroSpawn().orElseThrow());
    }

    @Test
    void activeConfigResetIsRejectedBeforeEveryMutation() {
        Path configPath = temporaryDirectory.resolve("active-reset.json");
        FarmHelperClientRuntime runtime = TestFarmHelperClientRuntimeFactory.create(configPath);
        MacroLocationConfig spawn = new MacroLocationConfig(10, 72, -4, 91.5F, -12.25F, 7);
        MacroLocationConfig rewarp = new MacroLocationConfig(14, 72, -4, 0F, 0F, 7);
        assertTrue(runtime.setMacroMode(5));
        assertTrue(runtime.setMacroSpawn(spawn));
        assertTrue(runtime.addMacroRewarp(rewarp));
        ready(runtime);
        assertTrue(runtime.startMacro());
        long generation = runtime.core().macroManager().generation();
        MacroState state = runtime.core().macroManager().state();

        assertFalse(runtime.resetConfig());
        assertEquals(generation, runtime.core().macroManager().generation());
        assertEquals(state, runtime.core().macroManager().state());
        assertMacroLocations(runtime, 5, spawn, rewarp);
        assertMacroLocations(TestFarmHelperClientRuntimeFactory.create(configPath),
                5, spawn, rewarp);

        ClientFarmHelperCommandService commands = new ClientFarmHelperCommandService(
                runtime, () -> false, new ClientCommandScreenCloseGuard());
        List<String> feedback = new ArrayList<>();
        CommandDispatcher<String> dispatcher = new CommandDispatcher<>();
        var root = dispatcher.register(FarmHelperCommandTree.root(
                "farmhelper", commands, (source, message) -> feedback.add(message)));
        dispatcher.register(FarmHelperCommandTree.alias(
                "fh", root, commands, (source, message) -> feedback.add(message)));

        for (String command : List.of("farmhelper config reset", "fh config reset")) {
            assertEquals(0, execute(dispatcher, command));
            assertEquals("Stop the macro before resetting configuration.", feedback.getLast());
            assertEquals(generation, runtime.core().macroManager().generation());
            assertEquals(state, runtime.core().macroManager().state());
            assertMacroLocations(runtime, 5, spawn, rewarp);
            assertMacroLocations(TestFarmHelperClientRuntimeFactory.create(configPath),
                    5, spawn, rewarp);
        }
    }

    @Test
    void mappedModesSynchronizeAndInvalidModesNeverPersistOrThrow() {
        Path configPath = temporaryDirectory.resolve("mapped-modes.json");
        FarmHelperClientRuntime runtime = TestFarmHelperClientRuntimeFactory.create(configPath);
        for (int mode = 0; mode <= 13; mode++) {
            assertTrue(runtime.setMacroMode(mode));
            assertEquals(mode, runtime.core().config().macroMode());
            assertEquals(mode, runtime.core().macroManager().settings().macroMode().code());
            FarmHelperClientRuntime reloaded = TestFarmHelperClientRuntimeFactory.create(configPath);
            assertEquals(mode, reloaded.core().config().macroMode());
            assertEquals(mode, reloaded.core().macroManager().settings().macroMode().code());
        }
        int before = runtime.core().config().macroMode();
        for (int invalid : List.of(-1, 14)) {
            assertFalse(runtime.setMacroMode(invalid));
            assertEquals(before, runtime.core().config().macroMode());
            assertEquals(before, runtime.core().macroManager().settings().macroMode().code());
            assertEquals(before, TestFarmHelperClientRuntimeFactory.create(configPath)
                    .core().config().macroMode());
        }
    }

    @Test
    void failedMacroPersistenceLeavesConfigAndLiveSettingsUnchanged() throws IOException {
        Path configPath = temporaryDirectory.resolve("failed-save.json");
        FarmHelperClientRuntime runtime = TestFarmHelperClientRuntimeFactory.create(configPath);
        dev.hylfrd.farmhelper.config.MacroLocationConfig original =
                new dev.hylfrd.farmhelper.config.MacroLocationConfig(0, 70, 0, 10F, 5F, 2);
        assertTrue(runtime.setMacroSpawn(original));
        Files.delete(configPath);
        Files.createDirectory(configPath);

        assertFalse(runtime.setMacroSpawn(
                new dev.hylfrd.farmhelper.config.MacroLocationConfig(10, 80, 10, 20F, 6F, 3)));

        assertEquals(original, runtime.core().config().macroSpawn().orElseThrow());
        assertEquals(original.x(), runtime.core().macroManager().settings()
                .spawn().orElseThrow().position().x());
        assertEquals(original.plot(), runtime.core().macroManager().settings()
                .spawn().orElseThrow().plot());
    }

    @Test
    void failedP2MechanismPersistenceRollsBackConfigAndLiveSettings() throws IOException {
        Path configPath = temporaryDirectory.resolve("failed-p2-save.json");
        FarmHelperClientRuntime runtime = TestFarmHelperClientRuntimeFactory.create(configPath);
        FarmHelperConfig enabled = runtime.configSnapshot();
        enabled.setMacroMode(3);
        enabled.setRotateAfterDrop(true);
        enabled.setCustomPitch(true);
        enabled.setCustomPitchLevel(48.0F);
        assertTrue(runtime.saveConfig(enabled));
        Files.delete(configPath);
        Files.createDirectory(configPath);

        FarmHelperConfig rejected = runtime.configSnapshot();
        rejected.setRotateAfterDrop(false);
        rejected.setCustomPitchLevel(52.0F);
        assertFalse(runtime.saveConfig(rejected));

        assertEquals(3, runtime.core().config().macroMode());
        assertTrue(runtime.core().config().rotateAfterDrop());
        assertEquals(48.0F, runtime.core().config().customPitchLevel());
        assertTrue(runtime.core().macroManager().settings().rotateAfterDrop());
        assertEquals(48.0F, runtime.core().macroManager().settings().customPitchLevel());
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
                runtime, () -> false, new ClientCommandScreenCloseGuard());
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
    void taskCallbackManualStopFencesEveryReacquisitionAndLeavesNoSurvivingTask() {
        FarmHelperClientRuntime runtime = new FarmHelperClientRuntime(
                temporaryDirectory.resolve("farmhelper.json"));
        ready(runtime);
        ClientFarmHelperCommandService commands = new ClientFarmHelperCommandService(
                runtime, () -> false, new ClientCommandScreenCloseGuard());
        List<String> feedback = new ArrayList<>();
        CommandDispatcher<String> dispatcher = new CommandDispatcher<>();
        dispatcher.register(FarmHelperCommandTree.root(
                "farmhelper", commands, (source, message) -> feedback.add(message)));
        ControlOwner owner = new ControlOwner("callback-manual-stop");
        assertEquals(1, execute(dispatcher, "farmhelper toggle"));
        assertEquals("Macro enabled.", feedback.getLast());
        runtime.input().hold(owner, InputAction.FORWARD);
        runtime.rotation().start(owner, 0F, 0F, 30F, 5F, 1_000L);

        List<RuntimeException> blocked = new ArrayList<>();
        TaskHandle stoppingTask = runtime.core().taskQueue().schedule(
                new TaskOwner("manual-stop-callback"), 0L, () -> {
                    assertEquals(1, execute(dispatcher, "farmhelper toggle"));
                    assertEquals("Macro disabled.", feedback.getLast());
                    captureBlocked(blocked, runtime::toggle);
                    captureBlocked(blocked, () -> runtime.core().taskQueue().schedule(
                            new TaskOwner("reacquired-task"), 0L, () -> { }));
                    captureBlocked(blocked, () -> runtime.inventory().start(
                            operation(owner, 21L), ignored -> { }));
                    captureBlocked(blocked, () -> runtime.input().hold(owner, InputAction.USE));
                    captureBlocked(blocked, () -> runtime.rotation().start(
                            owner, 0F, 0F, 90F, 0F, 1_000L));
                });
        runtime.inventory().start(operation(owner, 20L), ignored -> { });
        TaskHandle pendingTask = runtime.core().taskQueue().schedule(
                new TaskOwner("must-be-cancelled"), 0L, () -> {
                    throw new AssertionError("task survived MANUAL_STOP");
                });
        TaskHandle deferredTask = runtime.core().taskQueue().schedule(
                new TaskOwner("deferred-must-be-cancelled"), 1_000_000L, () -> {
                    throw new AssertionError("deferred task survived MANUAL_STOP");
                });

        ClientSnapshot tickSnapshot = new ClientSnapshot(
                Observation.present(new PlayerSnapshot(
                        Observation.unknown(), Observation.unknown(), Observation.unknown(),
                        Observation.unknown(), Observation.unknown())),
                Observation.present(new WorldSnapshot(
                        runtime.lifecycle().worldEpoch(), Observation.unknown())),
                Observation.present(ConnectionSnapshot.multiplayer()),
                Observation.absent());
        assertTrue(TestClientTickAdapterAccess.tick(
                runtime, tickSnapshot, () -> { }).isEmpty());

        assertEquals(5, blocked.size());
        assertTrue(blocked.stream().allMatch(failure ->
                "client transient ownership is fenced".equals(failure.getMessage())));
        assertTrue(stoppingTask.done());
        assertFalse(stoppingTask.cancelled());
        assertTrue(pendingTask.cancelled());
        assertTrue(deferredTask.cancelled());
        assertEquals(0, runtime.core().taskQueue().pendingTaskCount());
        assertFalse(runtime.core().macroManager().enabled());
        assertTrue(runtime.inventory().activeToken().isEmpty());
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

    @Test
    void screenChangesPauseWithoutDiscardingTheMacroGeneration() {
        FarmHelperClientRuntime runtime = new FarmHelperClientRuntime(
                temporaryDirectory.resolve("screen-lifecycle.json"));
        ready(runtime);
        runtime.toggle();
        long generation = runtime.core().macroManager().generation();

        runtime.observeMacroScreen(Observation.present(ScreenSnapshot.unknownDetails()));
        runtime.lifecycle().observeScreen(Observation.present(ScreenSnapshot.unknownDetails()));

        assertEquals(MacroState.PAUSED, runtime.core().macroManager().state());
        assertEquals(generation, runtime.core().macroManager().generation());
        assertTrue(runtime.core().macroManager().lastTerminalReason().isEmpty());

        runtime.observeMacroScreen(Observation.absent());
        runtime.lifecycle().observeScreen(Observation.absent());

        assertEquals(MacroState.RUNNING, runtime.core().macroManager().state());
        assertEquals(generation, runtime.core().macroManager().generation());
    }

    @Test
    void disconnectPrecedenceFreezesTheStrongestTerminalReason() {
        FarmHelperClientRuntime runtime = new FarmHelperClientRuntime(
                temporaryDirectory.resolve("disconnect-precedence.json"));
        ready(runtime);
        runtime.toggle();

        runtime.disconnected();

        assertEquals(MacroTerminalReason.DISCONNECT,
                runtime.core().macroManager().lastTerminalReason().orElseThrow());
    }

    @Test
    void statusReportsExactTerminalReasonAndDisconnectOutranksWorldUnload() {
        FarmHelperClientRuntime manual = TestFarmHelperClientRuntimeFactory.create(
                temporaryDirectory.resolve("status-manual.json"));
        assertTrue(status(manual).contains("terminal: none"));
        ready(manual);
        assertTrue(manual.startMacro());
        assertTrue(manual.stopMacro());
        assertTrue(status(manual).contains("terminal: manual_stop"));

        FarmHelperClientRuntime world = TestFarmHelperClientRuntimeFactory.create(
                temporaryDirectory.resolve("status-world.json"));
        world.worldLoaded();
        ready(world);
        assertTrue(world.startMacro());
        world.worldUnloaded();
        assertTrue(status(world).contains("terminal: world_change"));

        FarmHelperClientRuntime disconnect = TestFarmHelperClientRuntimeFactory.create(
                temporaryDirectory.resolve("status-disconnect.json"));
        disconnect.worldLoaded();
        ready(disconnect);
        assertTrue(disconnect.startMacro());
        disconnect.disconnected();
        assertTrue(status(disconnect).contains("terminal: disconnect"));
        disconnect.worldLoaded();
        assertTrue(status(disconnect).contains("terminal: disconnect"));
    }

    @Test
    void clientReasonsMapToMacroReasonsExactly() {
        assertTerminal("manual.json", MacroTerminalReason.MANUAL_STOP, runtime -> runtime.toggle());
        assertTerminal("world-load.json", MacroTerminalReason.WORLD_CHANGE, FarmHelperClientRuntime::worldLoaded);
        assertTerminalAfterWorldLoad("world-unload.json", MacroTerminalReason.WORLD_CHANGE,
                FarmHelperClientRuntime::worldUnloaded);
        assertTerminal("disconnect.json", MacroTerminalReason.DISCONNECT,
                FarmHelperClientRuntime::disconnected);
        assertTerminal("connection.json", MacroTerminalReason.CONNECTION_LOST,
                runtime -> runtime.observeConnection(Observation.absent()));
        assertTerminal("exception.json", MacroTerminalReason.EXCEPTION, FarmHelperClientRuntime::failed);
        assertTerminal("client-stop.json", MacroTerminalReason.CLIENT_STOP,
                FarmHelperClientRuntime::clientStopping);
    }

    @Test
    void clientOwnershipAndMacroGenerationsRemainIndependent() {
        FarmHelperClientRuntime runtime = new FarmHelperClientRuntime(
                temporaryDirectory.resolve("independent-generations.json"));
        ready(runtime);
        runtime.toggle();
        long macroGeneration = runtime.core().macroManager().generation();
        long ownershipGeneration = runtime.ownershipGeneration();
        Observation<ScreenSnapshot> screen = Observation.present(ScreenSnapshot.unknownDetails());

        runtime.observeMacroScreen(screen);
        runtime.lifecycle().observeScreen(Observation.absent());
        runtime.lifecycle().observeScreen(screen);

        assertEquals(macroGeneration, runtime.core().macroManager().generation());
        assertTrue(runtime.ownershipGeneration() > ownershipGeneration);
        assertEquals(MacroState.PAUSED, runtime.core().macroManager().state());
    }

    @Test
    void manualPauseReleasesMacroControlsBeforeResumeCanRun() {
        FarmHelperClientRuntime runtime = new FarmHelperClientRuntime(
                temporaryDirectory.resolve("manual-pause-controls.json"));
        ready(runtime);
        runtime.toggle();
        long generation = runtime.core().macroManager().generation();
        runtime.input().hold(MacroControlOwner.S_SHAPE, List.of(InputAction.FORWARD),
                Optional.of(new HotbarSelection(3)));
        runtime.rotation().start(MacroControlOwner.S_SHAPE, 0F, 0F, 90F, 10F, 1_000L);

        assertTrue(runtime.pauseMacro());

        assertTrue(runtime.input().snapshot().emptyState());
        assertFalse(runtime.rotation().rotating());
        assertEquals(RotationTerminalReason.OWNER_CANCELLED,
                runtime.rotation().snapshot().terminalReason().orElseThrow());
        assertEquals(generation, runtime.core().macroManager().generation());
        assertTrue(runtime.resumeMacro());
        assertEquals(MacroState.RUNNING, runtime.core().macroManager().state());
        assertTrue(runtime.input().snapshot().emptyState());
        assertFalse(runtime.rotation().rotating());
    }

    @Test
    void nestedFeaturePauseReleasesOnceAndNeverResurrectsControls() {
        FarmHelperClientRuntime runtime = new FarmHelperClientRuntime(
                temporaryDirectory.resolve("feature-pause-controls.json"));
        ready(runtime);
        runtime.toggle();
        runtime.input().hold(MacroControlOwner.S_SHAPE, InputAction.ATTACK);
        runtime.rotation().start(MacroControlOwner.S_SHAPE, 0F, 0F, 45F, 5F, 1_000L);

        FeatureSuspension first = runtime.core().macroManager().suspendForFeature("first");
        FeatureSuspension second = runtime.core().macroManager().suspendForFeature("second");

        assertTrue(runtime.input().snapshot().emptyState());
        assertFalse(runtime.rotation().rotating());
        first.close();
        assertEquals(MacroState.PAUSED, runtime.core().macroManager().state());
        second.close();
        assertEquals(MacroState.RUNNING, runtime.core().macroManager().state());
        assertTrue(runtime.input().snapshot().emptyState());
        assertFalse(runtime.rotation().rotating());
    }

    @Test
    void manualPauseOverlapsScreenWithoutPrematureResume() {
        FarmHelperClientRuntime runtime = new FarmHelperClientRuntime(
                temporaryDirectory.resolve("overlapping-manual-screen.json"));
        ready(runtime);
        runtime.toggle();
        long generation = runtime.core().macroManager().generation();

        runtime.observeMacroScreen(Observation.present(ScreenSnapshot.unknownDetails()));
        assertTrue(runtime.pauseMacro());
        assertEquals(java.util.Set.of(MacroPauseCause.MANUAL, MacroPauseCause.SCREEN_OPEN),
                runtime.core().macroManager().pauseCauses());
        runtime.observeMacroScreen(Observation.absent());

        assertEquals(MacroState.PAUSED, runtime.core().macroManager().state());
        assertEquals(java.util.Set.of(MacroPauseCause.MANUAL),
                runtime.core().macroManager().pauseCauses());
        assertEquals(generation, runtime.core().macroManager().generation());
        assertTrue(runtime.resumeMacro());
        assertEquals(MacroState.RUNNING, runtime.core().macroManager().state());
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

    private void assertTerminal(
            String name,
            MacroTerminalReason expected,
            java.util.function.Consumer<FarmHelperClientRuntime> boundary
    ) {
        FarmHelperClientRuntime runtime = new FarmHelperClientRuntime(temporaryDirectory.resolve(name));
        ready(runtime);
        runtime.toggle();
        boundary.accept(runtime);
        assertEquals(expected, runtime.core().macroManager().lastTerminalReason().orElseThrow());
        assertTrue(status(runtime).contains("terminal: " + expected.name().toLowerCase()));
    }

    private void assertTerminalAfterWorldLoad(
            String name,
            MacroTerminalReason expected,
            java.util.function.Consumer<FarmHelperClientRuntime> boundary
    ) {
        FarmHelperClientRuntime runtime = new FarmHelperClientRuntime(temporaryDirectory.resolve(name));
        runtime.worldLoaded();
        ready(runtime);
        runtime.toggle();
        boundary.accept(runtime);
        assertEquals(expected, runtime.core().macroManager().lastTerminalReason().orElseThrow());
        assertTrue(status(runtime).contains("terminal: " + expected.name().toLowerCase()));
    }

    private static void captureBlocked(List<RuntimeException> failures, Runnable action) {
        try {
            action.run();
        } catch (RuntimeException failure) {
            failures.add(failure);
        }
    }

    private static int execute(CommandDispatcher<String> dispatcher, String command) {
        try {
            return dispatcher.execute(command, "test-source");
        } catch (CommandSyntaxException exception) {
            throw new AssertionError("command did not parse: " + command, exception);
        }
    }

    private static void ready(FarmHelperClientRuntime runtime) {
        runtime.observeConnection(Observation.present(ConnectionSnapshot.multiplayer()));
    }

    private static String status(FarmHelperClientRuntime runtime) {
        return new ClientFarmHelperCommandService(runtime, () -> false,
                new ClientCommandScreenCloseGuard()).status().getFirst();
    }

    private static void assertMacroLocations(
            FarmHelperClientRuntime runtime,
            int mode,
            MacroLocationConfig spawn,
            MacroLocationConfig rewarp
    ) {
        assertEquals(mode, runtime.core().config().macroMode());
        assertEquals(mode, runtime.core().macroManager().settings().mode().code());
        assertEquals(spawn, runtime.core().config().macroSpawn().orElseThrow());
        assertEquals(spawn.x(), runtime.core().macroManager().settings()
                .spawn().orElseThrow().position().x());
        assertEquals(spawn.yaw(), runtime.core().macroManager().settings()
                .spawn().orElseThrow().yaw());
        assertEquals(spawn.pitch(), runtime.core().macroManager().settings()
                .spawn().orElseThrow().pitch());
        assertEquals(spawn.plot(), runtime.core().macroManager().settings()
                .spawn().orElseThrow().plot());
        assertEquals(List.of(rewarp), runtime.core().config().macroRewarps());
        assertEquals(rewarp.x(), runtime.core().macroManager().settings()
                .rewarps().getFirst().x());
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
