package dev.hylfrd.farmhelper.client.ui.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.hylfrd.farmhelper.client.platform.ClientCommandScreenCloseGuard;
import dev.hylfrd.farmhelper.client.platform.TestClientTickAdapterAccess;
import dev.hylfrd.farmhelper.client.runtime.FarmHelperClientRuntime;
import dev.hylfrd.farmhelper.client.runtime.TestFarmHelperClientRuntimeFactory;
import dev.hylfrd.farmhelper.control.input.ControlOwner;
import dev.hylfrd.farmhelper.control.input.InputAction;
import dev.hylfrd.farmhelper.control.input.ReleaseReason;
import dev.hylfrd.farmhelper.control.rotation.RotationTerminalReason;
import dev.hylfrd.farmhelper.config.MacroLocationConfig;
import dev.hylfrd.farmhelper.macro.MacroState;
import dev.hylfrd.farmhelper.macro.MacroPauseCause;
import dev.hylfrd.farmhelper.runtime.snapshot.ConnectionSnapshot;
import dev.hylfrd.farmhelper.runtime.snapshot.ClientSnapshot;
import dev.hylfrd.farmhelper.runtime.snapshot.Observation;
import dev.hylfrd.farmhelper.runtime.snapshot.PlayerSnapshot;
import dev.hylfrd.farmhelper.runtime.snapshot.ScreenSnapshot;
import dev.hylfrd.farmhelper.runtime.snapshot.WorldSnapshot;
import dev.hylfrd.farmhelper.ui.command.FarmHelperCommandTree;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClientCommandScreenCloseTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void chatCommandClosePreservesToggleAndRotationCreatedOwners() {
        Fixture fixture = fixture("created-owners.json");
        Object toggleChat = new Object();
        fixture.observePresent(toggleChat, true, 11L);

        assertEquals(1, fixture.execute("farmhelper toggle"));
        assertEquals("Macro enabled.", fixture.feedback.getLast());
        fixture.endTick();
        assertTrue(fixture.runtime.core().macroManager().enabled());

        Object rotationChat = new Object();
        fixture.observePresent(rotationChat, true, 12L);
        assertTrue(fixture.runtime.core().macroManager().enabled());
        assertEquals(MacroState.PAUSED, fixture.runtime.core().macroManager().state());
        assertEquals(1, fixture.execute("farmhelper rotation test 90 10 100"));
        assertEquals("Rotation test started.", fixture.feedback.getLast());
        fixture.endTick();
        assertTrue(fixture.runtime.rotation().rotating());
    }

    @Test
    void toggleOffStopAndResetKeepManualStopTerminalReasonsAfterChatClose() {
        Fixture fixture = fixture("manual-stop.json");

        fixture.observePresent(new Object(), true, 21L);
        fixture.acquireOwners("toggle-off");
        assertEquals(1, fixture.execute("farmhelper toggle"));
        assertEquals("Macro disabled.", fixture.feedback.getLast());
        fixture.assertManualStopReasons();
        fixture.endTick();
        fixture.assertManualStopReasons();
        fixture.assertStoppedAndDrained();

        fixture.observePresent(new Object(), true, 22L);
        fixture.acquireOwners("stop");
        assertEquals(1, fixture.execute("farmhelper stop"));
        assertEquals("Macro disabled and controls released.", fixture.feedback.getLast());
        fixture.assertManualStopReasons();
        fixture.endTick();
        fixture.assertManualStopReasons();
        fixture.assertStoppedAndDrained();

        fixture.observePresent(new Object(), true, 23L);
        fixture.acquireOwners("reset");
        assertEquals(1, fixture.execute("farmhelper reset"));
        assertEquals("Runtime stopped and configuration reset.", fixture.feedback.getLast());
        fixture.assertManualStopReasons();
        fixture.endTick();
        fixture.assertManualStopReasons();
        fixture.assertStoppedAndDrained();
    }

    @Test
    void nonCommandAndMismatchedScreenBoundariesStillCancelOwners() {
        Fixture fixture = fixture("ordinary-boundaries.json");

        fixture.observePresent(new Object(), true, 31L);
        fixture.acquireOwners("status-close");
        assertEquals(1, fixture.execute("farmhelper status"));
        fixture.endTick();
        fixture.assertScreenReasons();

        fixture.observePresent(new Object(), true, 37L);
        fixture.acquireOwners("config-close");
        assertEquals(1, fixture.execute("farmhelper config get targetYaw"));
        fixture.endTick();
        fixture.assertScreenReasons();

        fixture.disableMacro();
        Object replacementChat = new Object();
        fixture.observePresent(replacementChat, true, 32L);
        assertEquals(1, fixture.execute("farmhelper toggle"));
        fixture.observePresent(new Object(), false, 33L);
        assertTrue(fixture.runtime.core().macroManager().enabled());

        fixture.disableMacro();
        Object unknownChat = new Object();
        fixture.observePresent(unknownChat, true, 34L);
        assertEquals(1, fixture.execute("farmhelper toggle"));
        fixture.observeUnknown();
        assertTrue(fixture.runtime.core().macroManager().enabled());

        fixture.disableMacro();
        Object nonChat = new Object();
        fixture.observePresent(nonChat, false, 35L);
        assertEquals(1, fixture.execute("farmhelper toggle"));
        fixture.observeAbsent();
        assertTrue(fixture.runtime.core().macroManager().enabled());

        fixture.disableMacro();
        Object delayedCloseChat = new Object();
        fixture.observePresent(delayedCloseChat, true, 36L);
        assertEquals(1, fixture.execute("farmhelper toggle"));
        fixture.observePresent(delayedCloseChat, true, 36L);
        assertTrue(fixture.runtime.core().macroManager().enabled());
        fixture.observeAbsent();
        assertTrue(fixture.runtime.core().macroManager().enabled());
        assertEquals(MacroState.RUNNING, fixture.runtime.core().macroManager().state());
    }

    @Test
    void macroPauseCommandOverlapsScreenAndExpectedCloseKeepsManualCause() {
        Fixture fixture = fixture("macro-pause-chat.json");
        assertTrue(fixture.runtime.startMacro());
        long generation = fixture.runtime.core().macroManager().generation();
        fixture.observePresent(new Object(), true, 41L);
        assertEquals(MacroState.PAUSED, fixture.runtime.core().macroManager().state());

        assertEquals(1, fixture.execute("farmhelper macro pause"));
        assertEquals(java.util.Set.of(MacroPauseCause.SCREEN_OPEN, MacroPauseCause.MANUAL),
                fixture.runtime.core().macroManager().pauseCauses());
        fixture.endTick();

        assertEquals(MacroState.PAUSED, fixture.runtime.core().macroManager().state());
        assertEquals(java.util.Set.of(MacroPauseCause.MANUAL),
                fixture.runtime.core().macroManager().pauseCauses());
        assertEquals(generation, fixture.runtime.core().macroManager().generation());

        fixture.observePresent(new Object(), true, 42L);
        assertEquals(1, fixture.execute("farmhelper macro resume"));
        fixture.endTick();
        assertEquals(MacroState.RUNNING, fixture.runtime.core().macroManager().state());
        assertEquals(generation, fixture.runtime.core().macroManager().generation());
    }

    @Test
    void clientServiceDelegatesSpawnAndRewarpsToSameRuntimeSettings() {
        FarmHelperClientRuntime runtime = TestFarmHelperClientRuntimeFactory.create(
                temporaryDirectory.resolve("locations.json"));
        AtomicReference<MacroLocationConfig> position = new AtomicReference<>(
                new MacroLocationConfig(0, 70, 0, 91.5F, -12.25F, 7));
        ClientFarmHelperCommandService service = new ClientFarmHelperCommandService(
                runtime, () -> false, new ClientCommandScreenCloseGuard(),
                () -> null, ignored -> false, () -> { }, () -> true,
                (yaw, pitch, duration) -> false, position::get);

        assertTrue(service.setSpawn().successful());
        assertEquals(7, runtime.core().macroManager().settings().spawn().orElseThrow().plot());
        assertTrue(service.status().stream().anyMatch(line ->
                line.contains("yaw=91.5") && line.contains("pitch=-12.25") && line.contains("plot=7")));
        FarmHelperClientRuntime reloaded = TestFarmHelperClientRuntimeFactory.create(
                temporaryDirectory.resolve("locations.json"));
        assertEquals(position.get(), reloaded.core().config().macroSpawn().orElseThrow());
        assertEquals(7, reloaded.core().macroManager().settings().spawn().orElseThrow().plot());

        position.set(new MacroLocationConfig(1, 70, 0, 0F, 0F, 7));
        assertFalse(service.addRewarp().successful());
        position.set(new MacroLocationConfig(3, 70, 0, 0F, 0F, 7));
        assertTrue(service.addRewarp().successful());
        assertEquals(1, runtime.core().config().macroRewarps().size());
        assertEquals(1, runtime.core().macroManager().settings().rewarps().size());

        assertTrue(service.setSpawn().successful());
        assertTrue(runtime.core().config().macroRewarps().isEmpty());
        assertTrue(runtime.core().macroManager().settings().rewarps().isEmpty());

        assertTrue(runtime.resetConfig());
        assertTrue(runtime.core().config().macroSpawn().isEmpty());
        assertTrue(runtime.core().macroManager().settings().spawn().isEmpty());
        assertTrue(service.status().stream().anyMatch(line -> line.contains("spawn: unset")));
        FarmHelperClientRuntime resetReloaded = TestFarmHelperClientRuntimeFactory.create(
                temporaryDirectory.resolve("locations.json"));
        assertTrue(resetReloaded.core().config().macroSpawn().isEmpty());
        assertTrue(resetReloaded.core().macroManager().settings().spawn().isEmpty());
    }

    @Test
    void bothAliasesRunExactLifecycleCommandsThroughExpectedChatClose() {
        for (String root : List.of("farmhelper", "fh")) {
            Fixture fixture = fixture("lifecycle-" + root + ".json");
            fixture.observePresent(new Object(), true, 51L);
            assertEquals(1, fixture.execute(root + " macro start"));
            fixture.endTick();
            long generation = fixture.runtime.core().macroManager().generation();
            assertEquals(MacroState.RUNNING, fixture.runtime.core().macroManager().state());

            fixture.observePresent(new Object(), true, 52L);
            assertEquals(1, fixture.execute(root + " macro pause"));
            fixture.endTick();
            assertEquals(java.util.Set.of(MacroPauseCause.MANUAL),
                    fixture.runtime.core().macroManager().pauseCauses());

            fixture.observePresent(new Object(), true, 53L);
            assertEquals(1, fixture.execute(root + " macro resume"));
            fixture.endTick();
            assertEquals(MacroState.RUNNING, fixture.runtime.core().macroManager().state());
            assertEquals(generation, fixture.runtime.core().macroManager().generation());

            fixture.observePresent(new Object(), true, 54L);
            assertEquals(1, fixture.execute(root + " macro stop"));
            fixture.endTick();
            assertEquals(MacroState.STOPPED, fixture.runtime.core().macroManager().state());
            assertEquals(dev.hylfrd.farmhelper.macro.MacroTerminalReason.MANUAL_STOP,
                    fixture.runtime.core().macroManager().lastTerminalReason().orElseThrow());
        }
    }

    @Test
    void lifecycleDuplicatesAndMissingLocationFailuresReturnZeroWithExactFeedback() {
        Fixture fixture = fixture("command-failures.json");
        assertEquals(1, fixture.execute("farmhelper macro start"));
        assertEquals(0, fixture.execute("farmhelper macro start"));
        assertEquals("Macro is already active.", fixture.feedback.getLast());
        assertEquals(1, fixture.execute("farmhelper macro pause"));
        assertEquals(0, fixture.execute("farmhelper macro pause"));
        assertEquals("Macro is not active or is already manually paused.", fixture.feedback.getLast());
        assertEquals(1, fixture.execute("farmhelper macro resume"));
        assertEquals(0, fixture.execute("farmhelper macro resume"));
        assertEquals("Macro is not manually paused.", fixture.feedback.getLast());
        assertEquals(1, fixture.execute("farmhelper macro stop"));
        assertEquals(0, fixture.execute("farmhelper macro stop"));
        assertEquals("Macro is already stopped.", fixture.feedback.getLast());

        FarmHelperClientRuntime runtime = TestFarmHelperClientRuntimeFactory.create(
                temporaryDirectory.resolve("no-player.json"));
        ClientFarmHelperCommandService service = new ClientFarmHelperCommandService(
                runtime, () -> false, new ClientCommandScreenCloseGuard(),
                () -> null, ignored -> false, () -> { }, () -> true,
                (yaw, pitch, duration) -> false, () -> null);
        assertEquals("Spawn was not set: no player.", service.setSpawn().message());
        assertEquals("Rewarp was not added: no player.", service.addRewarp().message());
        assertEquals("Rewarp was not removed: no player.", service.removeRewarp().message());
    }

    private Fixture fixture(String name) {
        FarmHelperClientRuntime runtime = TestFarmHelperClientRuntimeFactory.create(
                temporaryDirectory.resolve(name));
        runtime.worldLoaded();
        runtime.observeConnection(Observation.present(ConnectionSnapshot.multiplayer()));
        return new Fixture(runtime);
    }

    private static final class Fixture {
        private final FarmHelperClientRuntime runtime;
        private final ClientCommandScreenCloseGuard guard = new ClientCommandScreenCloseGuard();
        private final AtomicReference<Object> screen = new AtomicReference<>();
        private final AtomicReference<Boolean> chat = new AtomicReference<>(false);
        private final List<String> feedback = new ArrayList<>();
        private final CommandDispatcher<String> dispatcher = new CommandDispatcher<>();

        private Fixture(FarmHelperClientRuntime runtime) {
            this.runtime = runtime;
            ClientFarmHelperCommandService service = new ClientFarmHelperCommandService(
                    runtime,
                    () -> false,
                    guard,
                    screen::get,
                    current -> current != null && chat.get(),
                    this::manualStop,
                    () -> {
                        manualStop();
                        return true;
                    },
                    (yaw, pitch, durationMs) -> {
                        runtime.rotation().start(new ControlOwner("rotation-command"),
                                0F, 0F, yaw, pitch, durationMs);
                        return true;
                    });
            var root = dispatcher.register(FarmHelperCommandTree.root(
                    "farmhelper", service, (source, message) -> feedback.add(message)));
            dispatcher.register(FarmHelperCommandTree.alias(
                    "fh", root, service, (source, message) -> feedback.add(message)));
        }

        private void observePresent(Object current, boolean isChat, long identity) {
            screen.set(current);
            chat.set(isChat);
            Observation<ScreenSnapshot> observed = Observation.present(new ScreenSnapshot(identity,
                            Observation.present(isChat ? "chat" : "screen"),
                            Observation.present("Screen")));
            runtime.observeMacroScreen(observed);
            guard.observeScreen(
                    observed,
                    current, isChat, runtime.lifecycle(), runtime.ownershipGeneration());
        }

        private void observeAbsent() {
            screen.set(null);
            chat.set(false);
            runtime.observeMacroScreen(Observation.absent());
            guard.observeScreen(Observation.absent(), null, false,
                    runtime.lifecycle(), runtime.ownershipGeneration());
        }

        private void endTick() {
            screen.set(null);
            chat.set(false);
            ClientSnapshot snapshot = new ClientSnapshot(
                    Observation.present(new PlayerSnapshot(
                            Observation.unknown(), Observation.unknown(), Observation.unknown(),
                            Observation.unknown(), Observation.unknown())),
                    Observation.present(new WorldSnapshot(
                            runtime.lifecycle().worldEpoch(), Observation.unknown())),
                    Observation.present(ConnectionSnapshot.multiplayer()),
                    Observation.absent());
            assertTrue(TestClientTickAdapterAccess.tick(runtime, snapshot, () ->
            {
                runtime.observeMacroScreen(snapshot.screen());
                guard.observeScreen(snapshot.screen(), screen.get(), chat.get(),
                        runtime.lifecycle(), runtime.ownershipGeneration());
            }).isEmpty());
        }

        private void observeUnknown() {
            screen.set(null);
            chat.set(false);
            runtime.observeMacroScreen(Observation.unknown());
            guard.observeScreen(Observation.unknown(), null, false,
                    runtime.lifecycle(), runtime.ownershipGeneration());
        }

        private void acquireOwners(String ownerId) {
            if (!runtime.core().macroManager().enabled()) {
                assertTrue(runtime.toggle());
            }
            ControlOwner owner = new ControlOwner(ownerId);
            runtime.input().hold(owner, InputAction.FORWARD);
            runtime.rotation().start(owner, 0F, 0F, 45F, 5F, 1_000L);
        }

        private void manualStop() {
            if (!runtime.core().macroManager().enabled()) {
                runtime.toggle();
            }
            assertFalse(runtime.toggle());
        }

        private void disableMacro() {
            if (runtime.core().macroManager().enabled()) {
                assertFalse(runtime.toggle());
            }
        }

        private int execute(String command) {
            try {
                return dispatcher.execute(command, "source");
            } catch (CommandSyntaxException exception) {
                throw new AssertionError(exception);
            }
        }

        private void assertManualStopReasons() {
            assertFalse(runtime.rotation().rotating());
            assertEquals(RotationTerminalReason.STOPPED,
                    runtime.rotation().snapshot().terminalReason().orElseThrow());
            assertEquals(ReleaseReason.STOP,
                    runtime.input().snapshot().releaseReason().orElseThrow());
        }

        private void assertScreenReasons() {
            assertFalse(runtime.rotation().rotating());
            assertEquals(RotationTerminalReason.SCREEN_CHANGED,
                    runtime.rotation().snapshot().terminalReason().orElseThrow());
            assertEquals(ReleaseReason.SCREEN,
                    runtime.input().snapshot().releaseReason().orElseThrow());
        }

        private void assertStoppedAndDrained() {
            assertFalse(runtime.core().macroManager().enabled());
            assertEquals(0, runtime.core().taskQueue().pendingTaskCount());
        }
    }
}
