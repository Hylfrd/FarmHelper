package dev.hylfrd.farmhelper.client.platform;

import dev.hylfrd.farmhelper.client.runtime.FarmHelperClientRuntime;
import dev.hylfrd.farmhelper.client.runtime.TestFarmHelperClientRuntimeFactory;
import dev.hylfrd.farmhelper.control.input.ControlOwner;
import dev.hylfrd.farmhelper.control.input.InputAction;
import dev.hylfrd.farmhelper.control.inventory.InventoryClick;
import dev.hylfrd.farmhelper.control.inventory.InventoryOperation;
import dev.hylfrd.farmhelper.control.inventory.InventoryPostcondition;
import dev.hylfrd.farmhelper.control.inventory.InventoryQuery;
import dev.hylfrd.farmhelper.control.inventory.InventoryStep;
import dev.hylfrd.farmhelper.control.inventory.ScreenExpectation;
import dev.hylfrd.farmhelper.control.inventory.ScreenIdentity;
import dev.hylfrd.farmhelper.runtime.gamestate.RawChatMessage;
import dev.hylfrd.farmhelper.runtime.gamestate.RawGameTextSnapshot;
import dev.hylfrd.farmhelper.runtime.snapshot.ClientSnapshot;
import dev.hylfrd.farmhelper.runtime.snapshot.ConnectionSnapshot;
import dev.hylfrd.farmhelper.runtime.snapshot.Observation;
import dev.hylfrd.farmhelper.runtime.snapshot.ScreenSnapshot;
import dev.hylfrd.farmhelper.runtime.time.TaskHandle;
import dev.hylfrd.farmhelper.runtime.time.TaskOwner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClientLifecycleSequencerTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void levelAndChatBoundariesPreserveExactEpochResetAndAcquisitionSequence() {
        FarmHelperClientRuntime runtime = runtime("boundary-sequence.json");
        ResetCountingGameTextSource gameText = new ResetCountingGameTextSource();
        ClientLifecycleSequencer sequencer = new ClientLifecycleSequencer(runtime, gameText);
        Observation<ConnectionSnapshot> present = Observation.present(ConnectionSnapshot.multiplayer());
        ControlOwner owner = new ControlOwner("boundary-sequence");

        assertEquals(0L, runtime.lifecycle().worldEpoch());
        assertEquals(0, gameText.resets);

        sequencer.observeLevel(new Object(), true);
        assertEquals(1L, runtime.lifecycle().worldEpoch());
        assertEquals(1, gameText.resets);
        assertFenced(runtime, owner, 50L);
        sequencer.observeConnection(present);
        assertTrue(runtime.toggle());

        sequencer.observeLevel(new Object(), true);
        assertEquals(3L, runtime.lifecycle().worldEpoch());
        assertEquals(3, gameText.resets);
        assertFalse(runtime.core().macroManager().enabled());
        assertFenced(runtime, owner, 51L);
        sequencer.observeConnection(present);
        assertTrue(runtime.toggle());

        sequencer.observeLevel(null, false);
        assertEquals(4L, runtime.lifecycle().worldEpoch());
        assertEquals(4, gameText.resets);
        assertFalse(runtime.core().macroManager().enabled());
        assertFenced(runtime, owner, 52L);

        sequencer.observeLevel(new Object(), true);
        assertEquals(5L, runtime.lifecycle().worldEpoch());
        assertEquals(5, gameText.resets);
        assertFenced(runtime, owner, 53L);
        sequencer.observeConnection(present);
        assertTrue(runtime.toggle());

        gameText.acceptChat("chat", "You were spawned in Limbo.");
        sequencer.disconnect();
        assertEquals(6L, runtime.lifecycle().worldEpoch());
        assertEquals(6, gameText.resets);
        assertFalse(runtime.core().macroManager().enabled());
        Observation<List<RawChatMessage>> disconnectedBatch = gameText.drain();
        assertTrue(disconnectedBatch.isPresent());
        assertTrue(disconnectedBatch.get().isEmpty());
        sequencer.observeConnection(present);
        assertFenced(runtime, owner, 54L);
    }

    @Test
    void disconnectLatchRejectsStalePresentSnapshotUntilExplicitWorldLoad() {
        FarmHelperClientRuntime runtime = runtime("disconnect.json");
        ResetCountingGameTextSource gameText = new ResetCountingGameTextSource();
        ClientLifecycleSequencer sequencer = new ClientLifecycleSequencer(runtime, gameText);
        Observation<ConnectionSnapshot> present = Observation.present(ConnectionSnapshot.multiplayer());
        Object firstLevel = new Object();
        sequencer.observeLevel(firstLevel, true);
        sequencer.observeConnection(present);

        ControlOwner owner = new ControlOwner("disconnect-latch");
        assertTrue(runtime.toggle());
        TaskHandle originalTask = runtime.core().taskQueue().schedule(
                new TaskOwner("disconnect-original"), 1_000_000L, () -> { });
        runtime.input().hold(owner, InputAction.FORWARD);
        runtime.rotation().start(owner, 0F, 0F, 45F, 5F, 1_000L);
        runtime.inventory().start(operation(owner, 30L), ignored -> { });

        sequencer.disconnect();

        assertFalse(runtime.core().macroManager().enabled());
        assertTrue(originalTask.cancelled());
        assertTrue(runtime.inventory().activeToken().isEmpty());
        assertFalse(runtime.rotation().rotating());
        assertTrue(runtime.input().snapshot().emptyState());
        assertTrue(runtime.core().taskQueue().pendingTaskCount() == 0);

        sequencer.observeConnection(present);
        assertFenced(runtime, owner, 31L);

        sequencer.observeLevel(null, true);
        sequencer.observeConnection(present);
        assertFenced(runtime, owner, 32L);

        sequencer.observeLevel(new Object(), false);
        sequencer.observeConnection(present);
        assertFenced(runtime, owner, 33L);

        Object freshLevel = new Object();
        sequencer.observeLevel(freshLevel, true);
        assertFenced(runtime, owner, 34L);

        sequencer.observeConnection(present);
        assertTrue(runtime.toggle());
        TaskHandle freshTask = runtime.core().taskQueue().schedule(
                new TaskOwner("fresh-task"), 1_000_000L, () -> { });
        runtime.input().hold(owner, InputAction.FORWARD);
        runtime.rotation().start(owner, 0F, 0F, 90F, 10F, 1_000L);
        runtime.inventory().start(operation(owner, 35L), ignored -> { });

        assertTrue(runtime.core().macroManager().enabled());
        assertFalse(freshTask.cancelled());
        assertFalse(runtime.input().snapshot().emptyState());
        assertTrue(runtime.rotation().rotating());
        assertTrue(runtime.inventory().activeToken().isPresent());

        sequencer.disconnect();
        assertTrue(gameText.resets > 0);
    }

    @Test
    void clientStoppingPermanentlyRejectsStalePresentAndEveryLaterLevelEvent() {
        FarmHelperClientRuntime runtime = runtime("stopping.json");
        ClientLifecycleSequencer sequencer = new ClientLifecycleSequencer(
                runtime, new ResetCountingGameTextSource());
        Observation<ConnectionSnapshot> present = Observation.present(ConnectionSnapshot.multiplayer());
        sequencer.observeLevel(new Object(), true);
        sequencer.observeConnection(present);
        assertTrue(runtime.toggle());
        TaskHandle task = runtime.core().taskQueue().schedule(
                new TaskOwner("stopping-task"), 1_000_000L, () -> { });

        sequencer.clientStopping();
        sequencer.observeConnection(present);
        sequencer.observeLevel(null, true);
        sequencer.observeLevel(new Object(), true);
        sequencer.observeConnection(present);

        assertFalse(runtime.core().macroManager().enabled());
        assertTrue(task.cancelled());
        assertTrue(runtime.core().taskQueue().pendingTaskCount() == 0);
        assertFenced(runtime, new ControlOwner("stopping-fenced"), 40L);
    }

    @Test
    void generationMismatchExpiresExpectedCommandCloseBeforeAbsent() {
        FarmHelperClientRuntime runtime = runtime("screen-generation.json");
        ClientCommandScreenCloseGuard guard = new ClientCommandScreenCloseGuard();

        assertExpectedCloseInvalidated(runtime, guard, runtime.lifecycle()::failed, 60L);
    }

    @Test
    void worldLoadAndUnloadExpireExpectedCommandCloseBeforeAbsent() {
        FarmHelperClientRuntime loadRuntime = runtime("screen-world-load.json");
        ClientCommandScreenCloseGuard loadGuard = new ClientCommandScreenCloseGuard();
        ClientLifecycleSequencer loadSequencer = new ClientLifecycleSequencer(
                loadRuntime, new ResetCountingGameTextSource(), loadGuard::clear);
        assertExpectedCloseInvalidated(
                loadRuntime, loadGuard, () -> loadSequencer.observeLevel(new Object(), true), 61L);

        FarmHelperClientRuntime unloadRuntime = runtime("screen-world-unload.json");
        ClientCommandScreenCloseGuard unloadGuard = new ClientCommandScreenCloseGuard();
        ClientLifecycleSequencer unloadSequencer = new ClientLifecycleSequencer(
                unloadRuntime, new ResetCountingGameTextSource(), unloadGuard::clear);
        unloadSequencer.observeLevel(new Object(), true);
        assertExpectedCloseInvalidated(
                unloadRuntime, unloadGuard, () -> unloadSequencer.observeLevel(null, false), 62L);
    }

    @Test
    void disconnectAndClientStoppingExpireExpectedCommandCloseBeforeAbsent() {
        FarmHelperClientRuntime disconnectRuntime = runtime("screen-disconnect.json");
        ClientCommandScreenCloseGuard disconnectGuard = new ClientCommandScreenCloseGuard();
        ClientLifecycleSequencer disconnectSequencer = new ClientLifecycleSequencer(
                disconnectRuntime, new ResetCountingGameTextSource(), disconnectGuard::clear);
        disconnectSequencer.observeLevel(new Object(), true);
        assertExpectedCloseInvalidated(
                disconnectRuntime, disconnectGuard, disconnectSequencer::disconnect, 63L);

        FarmHelperClientRuntime stoppingRuntime = runtime("screen-stopping.json");
        ClientCommandScreenCloseGuard stoppingGuard = new ClientCommandScreenCloseGuard();
        ClientLifecycleSequencer stoppingSequencer = new ClientLifecycleSequencer(
                stoppingRuntime, new ResetCountingGameTextSource(), stoppingGuard::clear);
        stoppingSequencer.observeLevel(new Object(), true);
        assertExpectedCloseInvalidated(
                stoppingRuntime, stoppingGuard, stoppingSequencer::clientStopping, 64L);
    }

    @Test
    void adapterFailureExpiresExpectedCommandCloseBeforeAbsent() {
        FarmHelperClientRuntime runtime = runtime("screen-adapter-failure.json");
        ClientCommandScreenCloseGuard guard = new ClientCommandScreenCloseGuard();

        assertExpectedCloseInvalidated(
                runtime, guard, () -> ClientTickAdapter.fail(runtime, guard), 65L);
    }

    private FarmHelperClientRuntime runtime(String name) {
        return TestFarmHelperClientRuntimeFactory.create(temporaryDirectory.resolve(name));
    }

    private static void assertExpectedCloseInvalidated(
            FarmHelperClientRuntime runtime,
            ClientCommandScreenCloseGuard guard,
            Runnable boundary,
            long identity
    ) {
        Object chatScreen = new Object();
        guard.observeScreen(
                Observation.present(new ScreenSnapshot(
                        identity, Observation.present("chat"), Observation.present("Chat"))),
                chatScreen, true, runtime.lifecycle(), runtime.ownershipGeneration());
        guard.armAfterCommand(chatScreen, true, runtime.ownershipGeneration());
        long beforeBoundary = runtime.ownershipGeneration();

        boundary.run();

        long afterBoundary = runtime.ownershipGeneration();
        assertTrue(afterBoundary > beforeBoundary);
        guard.observeScreen(Observation.absent(), null, false,
                runtime.lifecycle(), runtime.ownershipGeneration());
        assertEquals(afterBoundary + 1L, runtime.ownershipGeneration());
    }

    private static void assertFenced(
            FarmHelperClientRuntime runtime,
            ControlOwner owner,
            long screenIdentity
    ) {
        assertThrows(IllegalStateException.class, runtime::toggle);
        assertThrows(IllegalStateException.class, () -> runtime.core().taskQueue().schedule(
                new TaskOwner("fenced-" + screenIdentity), 0L, () -> { }));
        assertThrows(IllegalStateException.class,
                () -> runtime.input().hold(owner, InputAction.FORWARD));
        assertThrows(IllegalStateException.class,
                () -> runtime.rotation().start(owner, 0F, 0F, 1F, 1F, 1L));
        assertThrows(IllegalStateException.class,
                () -> runtime.inventory().start(operation(owner, screenIdentity), ignored -> { }));
        assertFalse(runtime.core().macroManager().enabled());
        assertTrue(runtime.core().taskQueue().pendingTaskCount() == 0);
    }

    private static InventoryOperation operation(ControlOwner owner, long identityValue) {
        ScreenIdentity identity = new ScreenIdentity(identityValue, 1, "minecraft:generic_9x3");
        return InventoryOperation.clicks(
                owner,
                ScreenExpectation.exact(identity, identity.type(), "Container", 1, List.of()),
                List.of(new InventoryStep(InventoryQuery.slot(0), new InventoryClick.QuickMove(),
                        InventoryPostcondition.TARGET_SLOT_CHANGED)),
                1_000_000L);
    }

    private static final class ResetCountingGameTextSource implements ClientGameTextSource {
        private final BoundedChatBuffer chat = new BoundedChatBuffer();
        private int resets;

        @Override
        public void acceptChat(String channel, String text) {
            chat.accept(channel, text);
        }

        @Override
        public void resetChat() {
            resets++;
            chat.reset();
        }

        private Observation<List<RawChatMessage>> drain() {
            return chat.drain();
        }

        @Override
        public RawGameTextSnapshot snapshot(ClientSnapshot client) {
            return RawGameTextSnapshot.unknown(0L);
        }
    }
}
