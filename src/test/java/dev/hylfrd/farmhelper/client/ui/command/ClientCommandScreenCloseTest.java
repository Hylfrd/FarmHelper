package dev.hylfrd.farmhelper.client.ui.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.hylfrd.farmhelper.client.platform.ClientCommandScreenCloseGuard;
import dev.hylfrd.farmhelper.client.runtime.FarmHelperClientRuntime;
import dev.hylfrd.farmhelper.client.runtime.TestFarmHelperClientRuntimeFactory;
import dev.hylfrd.farmhelper.control.input.ControlOwner;
import dev.hylfrd.farmhelper.control.input.InputAction;
import dev.hylfrd.farmhelper.control.input.ReleaseReason;
import dev.hylfrd.farmhelper.control.rotation.RotationTerminalReason;
import dev.hylfrd.farmhelper.runtime.snapshot.ConnectionSnapshot;
import dev.hylfrd.farmhelper.runtime.snapshot.Observation;
import dev.hylfrd.farmhelper.runtime.snapshot.ScreenSnapshot;
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
        fixture.observeAbsent();
        assertTrue(fixture.runtime.core().macroManager().enabled());

        Object rotationChat = new Object();
        fixture.observePresent(rotationChat, true, 12L);
        assertFalse(fixture.runtime.core().macroManager().enabled());
        assertEquals(1, fixture.execute("farmhelper rotation test 90 10 100"));
        assertEquals("Rotation test started.", fixture.feedback.getLast());
        fixture.observeAbsent();
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
        fixture.observeAbsent();
        fixture.assertManualStopReasons();

        fixture.observePresent(new Object(), true, 22L);
        fixture.acquireOwners("stop");
        assertEquals(1, fixture.execute("farmhelper stop"));
        assertEquals("Macro disabled and controls released.", fixture.feedback.getLast());
        fixture.assertManualStopReasons();
        fixture.observeAbsent();
        fixture.assertManualStopReasons();

        fixture.observePresent(new Object(), true, 23L);
        fixture.acquireOwners("reset");
        assertEquals(1, fixture.execute("farmhelper reset"));
        assertEquals("Runtime stopped and configuration reset.", fixture.feedback.getLast());
        fixture.assertManualStopReasons();
        fixture.observeAbsent();
        fixture.assertManualStopReasons();
    }

    @Test
    void nonCommandAndMismatchedScreenBoundariesStillCancelOwners() {
        Fixture fixture = fixture("ordinary-boundaries.json");

        fixture.observePresent(new Object(), true, 31L);
        fixture.acquireOwners("status-close");
        assertEquals(1, fixture.execute("farmhelper status"));
        fixture.observeAbsent();
        fixture.assertScreenReasons();

        Object replacementChat = new Object();
        fixture.observePresent(replacementChat, true, 32L);
        assertEquals(1, fixture.execute("farmhelper toggle"));
        fixture.observePresent(new Object(), false, 33L);
        assertFalse(fixture.runtime.core().macroManager().enabled());

        Object unknownChat = new Object();
        fixture.observePresent(unknownChat, true, 34L);
        assertEquals(1, fixture.execute("farmhelper toggle"));
        fixture.observeUnknown();
        assertFalse(fixture.runtime.core().macroManager().enabled());

        Object nonChat = new Object();
        fixture.observePresent(nonChat, false, 35L);
        assertEquals(1, fixture.execute("farmhelper toggle"));
        fixture.observeAbsent();
        assertFalse(fixture.runtime.core().macroManager().enabled());

        Object delayedCloseChat = new Object();
        fixture.observePresent(delayedCloseChat, true, 36L);
        assertEquals(1, fixture.execute("farmhelper toggle"));
        fixture.observePresent(delayedCloseChat, true, 36L);
        assertTrue(fixture.runtime.core().macroManager().enabled());
        fixture.observeAbsent();
        assertFalse(fixture.runtime.core().macroManager().enabled());
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
            dispatcher.register(FarmHelperCommandTree.root(
                    "farmhelper", service, (source, message) -> feedback.add(message)));
        }

        private void observePresent(Object current, boolean isChat, long identity) {
            screen.set(current);
            chat.set(isChat);
            guard.observeScreen(
                    Observation.present(new ScreenSnapshot(identity,
                            Observation.present(isChat ? "chat" : "screen"),
                            Observation.present("Screen"))),
                    current, isChat, runtime.lifecycle(), runtime.ownershipGeneration());
        }

        private void observeAbsent() {
            screen.set(null);
            chat.set(false);
            guard.observeScreen(Observation.absent(), null, false,
                    runtime.lifecycle(), runtime.ownershipGeneration());
        }

        private void observeUnknown() {
            screen.set(null);
            chat.set(false);
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
    }
}
