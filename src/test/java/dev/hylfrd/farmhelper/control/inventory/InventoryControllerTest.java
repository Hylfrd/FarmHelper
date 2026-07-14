package dev.hylfrd.farmhelper.control.inventory;

import dev.hylfrd.farmhelper.control.input.ControlOwner;
import dev.hylfrd.farmhelper.control.input.HotbarSelection;
import dev.hylfrd.farmhelper.control.input.InputController;
import dev.hylfrd.farmhelper.control.input.InputLease;
import dev.hylfrd.farmhelper.runtime.snapshot.ItemSummary;
import dev.hylfrd.farmhelper.runtime.snapshot.Observation;
import dev.hylfrd.farmhelper.runtime.snapshot.ResourceIdentifier;
import dev.hylfrd.farmhelper.runtime.snapshot.ScreenSnapshot;
import dev.hylfrd.farmhelper.runtime.time.ClientTaskQueue;
import dev.hylfrd.farmhelper.runtime.time.MonotonicClock;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.LinkedList;
import java.util.Optional;
import java.util.Queue;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InventoryControllerTest {
    private static final ControlOwner OWNER = new ControlOwner("inventory-test");
    private static final InventoryItem WHEAT = item("minecraft:wheat", 8, "Wheat", "crop");

    @Test
    void snapshotsAndComponentSummariesDefensivelyCopyCollections() {
        List<String> lore = new ArrayList<>(List.of("line one"));
        ItemComponentSummary components = new ItemComponentSummary(
                Optional.of("Custom Wheat"), Optional.of("Wheat"), lore);
        lore.add("mutated");
        InventoryItem item = new InventoryItem(
                new ItemSummary(ResourceIdentifier.parse("minecraft:wheat"), 2), components);
        List<InventorySlot> slots = new ArrayList<>(List.of(slot(0, item, true, true)));
        InventoryScreenSnapshot snapshot = snapshot(1, 4, 2, 0, slots, Observation.absent(), "Chest");
        slots.clear();

        assertEquals(List.of("line one"), components.lore());
        assertEquals(1, snapshot.slots().size());
        assertThrows(UnsupportedOperationException.class,
                () -> snapshot.slots().add(slot(1, item, true, true)));
    }

    @Test
    void screenEpochDistinguishesSameTitleAndReusedContainerId() {
        InventoryScreenSnapshot first = snapshot(10, 7, 0, 0, List.of(slot(0, WHEAT, true, true)),
                Observation.absent(), "Bazaar");
        InventoryScreenSnapshot sameTitleNewScreen = snapshot(11, 7, 0, 0,
                List.of(slot(0, WHEAT, true, true)), Observation.absent(), "Bazaar");

        assertEquals(first.screen(), sameTitleNewScreen.screen());
        assertNotEquals(first.identity(), sameTitleNewScreen.identity());
        assertNotEquals(first.identity().epoch(), sameTitleNewScreen.identity().epoch());
        assertEquals(first.identity().containerId(), sameTitleNewScreen.identity().containerId());
    }

    @Test
    void revisionsAdvanceForEitherMenuStateOrLocalContent() {
        ScreenRevision initial = new ScreenRevision(4, 8);

        assertTrue(new ScreenRevision(5, 8).advancedFrom(initial));
        assertTrue(new ScreenRevision(4, 9).advancedFrom(initial));
        assertFalse(new ScreenRevision(4, 8).advancedFrom(initial));
    }

    @Test
    void queriesModernNamesLoreAndHotbarWithoutMinecraftTypes() {
        InventorySlot hotbar = new InventorySlot(
                0,
                Observation.present(WHEAT),
                true,
                true,
                Optional.of(new HotbarSelection(2)));
        InventoryScreenSnapshot snapshot = snapshot(
                1, 1, 0, 0, List.of(hotbar), Observation.absent(), "Farming Menu");
        InventoryQuery query = InventoryQuery.anyItem()
                .withIdentifier("MINECRAFT:WHEAT")
                .withDisplayName("whe", InventoryQuery.MatchMode.CONTAINS)
                .withLoreContaining("crop")
                .inHotbar();

        assertEquals(List.of(hotbar), snapshot.find(query));
        assertEquals("Farming Menu", snapshot.screen().title().get());
    }

    @Test
    void executesEveryAllowedClickAndVerifiesOnLaterAdvances() {
        List<InventoryClick> clicks = List.of(
                InventoryClick.pickupPrimary(),
                InventoryClick.pickupSecondary(),
                InventoryClick.quickMove(),
                InventoryClick.swapWithHotbar(new HotbarSelection(4)));

        for (InventoryClick click : clicks) {
            Fixture fixture = new Fixture();
            fixture.port.current = Observation.present(baseSnapshot());
            fixture.port.afterClicks.add(changedSnapshot(1, 1));
            InventoryOperation operation = oneStep(click, InventoryPostcondition.TARGET_SLOT_CHANGED, 100);
            List<InventoryOutcome> outcomes = new ArrayList<>();

            fixture.controller.start(operation, outcomes::add);
            advance(fixture.queue, 3);

            assertEquals(List.of(click), fixture.port.clicks);
            assertEquals(InventoryOutcome.Status.COMPLETED, outcomes.getFirst().status());
            assertEquals(1, outcomes.getFirst().completedSteps());
        }
    }

    @Test
    void atomicGuardRejectsSameIdWithDifferentComponentsCountAndCursor() {
        assertAtomicMutationRejected(
                snapshotWithItem(item("minecraft:wheat", 8, "Renamed", "crop"), Observation.absent(), 0, 1),
                InventoryCancelReason.ITEM_CHANGED);
        assertAtomicMutationRejected(
                snapshotWithItem(item("minecraft:wheat", 7, "Wheat", "crop"), Observation.absent(), 0, 1),
                InventoryCancelReason.COUNT_CHANGED);
        assertAtomicMutationRejected(
                snapshotWithItem(WHEAT, Observation.present(item("minecraft:stone", 1, "Stone", "")), 0, 1),
                InventoryCancelReason.CURSOR_CHANGED);
    }

    @Test
    void atomicGuardRejectsRevisionOutOfBoundsInactiveAndPickupDenied() {
        assertAtomicMutationRejected(snapshotWithItem(WHEAT, Observation.absent(), 1, 0),
                InventoryCancelReason.REVISION_CHANGED);

        FakeInventoryPort port = new FakeInventoryPort();
        InventoryScreenSnapshot before = baseSnapshot();
        ItemIdentity missing = new ItemIdentity(before.identity(), before.revision(), 5, WHEAT);
        InventoryExecutionResult outOfBounds = port.executeWithCurrent(
                before, new InventoryClickGuard(
                        new InventoryOperationToken(1), OWNER, () -> true,
                        missing, true, true, Optional.empty(), before.cursor(), InventoryClick.quickMove()));
        assertEquals(InventoryCancelReason.SLOT_OUT_OF_BOUNDS, outOfBounds.rejection().orElseThrow());

        assertControllerPrecheck(snapshot(1, 2, 0, 0, List.of(slot(0, WHEAT, false, true)),
                Observation.absent(), "Chest"), InventoryCancelReason.SLOT_INACTIVE);
        assertControllerPrecheck(snapshot(1, 2, 0, 0, List.of(slot(0, WHEAT, true, false)),
                Observation.absent(), "Chest"), InventoryCancelReason.PICKUP_DENIED);
    }

    @Test
    void unsatisfiedPostconditionWaitsUntilTimeoutAndMultiStepOperationRebasesEachStep() {
        Fixture failed = new Fixture();
        failed.port.current = Observation.present(baseSnapshot());
        failed.port.afterClicks.add(changedSnapshot(1, 1));
        List<InventoryOutcome> failedOutcome = new ArrayList<>();
        failed.controller.start(oneStep(
                InventoryClick.quickMove(), (before, after, target, click) -> false, 100),
                failedOutcome::add);
        advance(failed.queue, 2);
        assertTrue(failedOutcome.isEmpty());
        failed.clock.advance(100);
        failed.queue.advance();
        assertReason(failedOutcome, InventoryCancelReason.TIMEOUT);

        Fixture multi = new Fixture();
        InventoryItem second = item("minecraft:carrot", 3, "Carrot", "crop");
        InventoryScreenSnapshot initial = snapshot(1, 2, 0, 0,
                List.of(slot(0, WHEAT, true, true), slot(1, second, true, true)),
                Observation.absent(), "Chest");
        InventoryScreenSnapshot afterFirst = snapshot(1, 2, 1, 1,
                List.of(slot(0, null, true, true), slot(1, second, true, true)),
                Observation.absent(), "Chest");
        InventoryScreenSnapshot afterSecond = snapshot(1, 2, 2, 2,
                List.of(slot(0, null, true, true), slot(1, null, true, true)),
                Observation.absent(), "Chest");
        multi.port.current = Observation.present(initial);
        multi.port.afterClicks.add(afterFirst);
        multi.port.afterClicks.add(afterSecond);
        InventoryOperation operation = InventoryOperation.clicks(OWNER, expectation(initial), List.of(
                new InventoryStep(InventoryQuery.slot(0), InventoryClick.quickMove(),
                        InventoryPostcondition.TARGET_SLOT_EMPTY),
                new InventoryStep(InventoryQuery.slot(1), InventoryClick.pickupPrimary(),
                        InventoryPostcondition.TARGET_SLOT_EMPTY)), 100);
        List<InventoryOutcome> outcomes = new ArrayList<>();

        multi.controller.start(operation, outcomes::add);
        advance(multi.queue, 5);

        assertEquals(2, multi.port.clicks.size());
        assertEquals(new ScreenRevision(1, 1), multi.port.guards.get(1).target().revision());
        assertEquals(InventoryOutcome.Status.COMPLETED, outcomes.getFirst().status());
    }

    @Test
    void timeoutWinsAtExactBoundaryBeforePostClickVerification() {
        Fixture fixture = new Fixture();
        fixture.port.current = Observation.present(baseSnapshot());
        fixture.port.afterClicks.add(changedSnapshot(1, 1));
        List<InventoryOutcome> outcomes = new ArrayList<>();
        fixture.controller.start(oneStep(
                InventoryClick.quickMove(), InventoryPostcondition.TARGET_SLOT_EMPTY, 5),
                outcomes::add);
        fixture.queue.advance();

        fixture.clock.advance(5);
        fixture.queue.advance();

        assertReason(outcomes, InventoryCancelReason.TIMEOUT);
        assertEquals(1, fixture.port.clicks.size());
    }

    @Test
    void hotbarConflictDoesNotPreemptAndLeaseReleasesOnlyItsOperation() {
        ManualClock clock = new ManualClock();
        ClientTaskQueue queue = new ClientTaskQueue(clock);
        InputController input = new InputController();
        FakeInventoryPort port = new FakeInventoryPort();
        port.current = Observation.present(baseSnapshot());
        List<InventoryDiagnostic> diagnostics = new ArrayList<>();
        InventoryController controller = new InventoryController(queue, input::selectHotbar, port, diagnostics::add);
        ControlOwner other = new ControlOwner("other");
        InputLease otherLease = input.selectHotbar(other, new HotbarSelection(8));
        List<InventoryOutcome> conflict = new ArrayList<>();
        InventoryOperation selecting = InventoryOperation.hotbar(
                OWNER, new HotbarSelection(2), 100);

        controller.start(selecting, conflict::add);

        assertReason(conflict, InventoryCancelReason.OWNER_CONFLICT);
        assertEquals(other, input.snapshot().hotbarOwner().orElseThrow());
        assertFalse(otherLease.closed());
        otherLease.close();

        List<InventoryOutcome> selected = new ArrayList<>();
        controller.start(selecting, selected::add);
        queue.advance();
        assertEquals(InventoryOutcome.Status.COMPLETED, selected.getFirst().status());
        assertTrue(input.snapshot().hotbarOwner().isEmpty());
    }

    @Test
    void staleTokenCannotCancelNewOperationAndScreenCloseIsIdentityGuarded() {
        Fixture fixture = new Fixture();
        fixture.port.current = Observation.present(baseSnapshot());
        InventoryOperation selecting = InventoryOperation.hotbar(
                OWNER, new HotbarSelection(1), 100);
        InventoryOperationToken old = fixture.controller.start(selecting, ignored -> { });
        fixture.queue.advance();
        InventoryOperationToken current = fixture.controller.start(selecting, ignored -> { });

        assertFalse(fixture.controller.cancel(old));
        assertEquals(Optional.of(current), fixture.controller.activeToken());
        assertTrue(fixture.controller.closeScreen(current));
        assertTrue(fixture.port.current.isAbsent());
        assertTrue(fixture.diagnostics.stream()
                .anyMatch(diagnostic -> diagnostic.reason() == InventoryCancelReason.STALE_TOKEN));
    }

    @Test
    void screenChangeAndCloseAreTerminalWithClosedReasons() {
        Fixture changed = new Fixture();
        changed.port.current = Observation.present(baseSnapshot());
        changed.port.afterClicks.add(snapshot(2, 2, 1, 1,
                List.of(slot(0, null, true, true)), Observation.absent(), "Chest"));
        List<InventoryOutcome> changedOutcome = new ArrayList<>();
        changed.controller.start(oneStep(InventoryClick.quickMove(), InventoryPostcondition.TARGET_SLOT_EMPTY, 100),
                changedOutcome::add);
        advance(changed.queue, 2);
        assertReason(changedOutcome, InventoryCancelReason.SCREEN_CHANGED);

        Fixture closed = new Fixture();
        closed.port.current = Observation.present(baseSnapshot());
        closed.port.afterClicks.add(null);
        List<InventoryOutcome> closedOutcome = new ArrayList<>();
        closed.controller.start(oneStep(InventoryClick.quickMove(), InventoryPostcondition.TARGET_SLOT_EMPTY, 100),
                closedOutcome::add);
        advance(closed.queue, 2);
        assertReason(closedOutcome, InventoryCancelReason.SCREEN_CLOSED);
    }

    @Test
    void adapterVerifierCallbackAndDiagnosticExceptionsFailClosed() {
        Fixture adapter = new Fixture();
        IllegalStateException adapterFailure = new IllegalStateException("observe failed");
        adapter.port.observeFailure = adapterFailure;
        List<InventoryOutcome> adapterOutcome = new ArrayList<>();
        adapter.controller.start(oneStep(
                InventoryClick.quickMove(), InventoryPostcondition.TARGET_SLOT_EMPTY, 100),
                adapterOutcome::add);
        assertSame(adapterFailure,
                assertThrows(IllegalStateException.class, adapter.queue::advance));
        assertReason(adapterOutcome, InventoryCancelReason.ADAPTER_EXCEPTION);
        assertTrue(adapter.controller.activeToken().isEmpty());

        Fixture verifier = new Fixture();
        verifier.port.current = Observation.present(baseSnapshot());
        verifier.port.afterClicks.add(changedSnapshot(1, 1));
        List<InventoryOutcome> verifierOutcome = new ArrayList<>();
        verifier.controller.start(oneStep(InventoryClick.quickMove(), (before, after, target, click) -> {
            throw new IllegalArgumentException("verifier failed");
        }, 100), verifierOutcome::add);
        verifier.queue.advance();
        assertThrows(IllegalArgumentException.class, verifier.queue::advance);
        assertReason(verifierOutcome, InventoryCancelReason.VERIFIER_EXCEPTION);

        Fixture callback = new Fixture();
        callback.controller.start(InventoryOperation.hotbar(
                OWNER, new HotbarSelection(0), 100), outcome -> {
                    throw new IllegalStateException("callback failed");
                });
        assertThrows(IllegalStateException.class, callback.queue::advance);
        assertTrue(callback.controller.activeToken().isEmpty());
        assertTrue(callback.diagnostics.stream()
                .anyMatch(diagnostic -> diagnostic.reason() == InventoryCancelReason.CALLBACK_EXCEPTION));

        ManualClock clock = new ManualClock();
        ClientTaskQueue queue = new ClientTaskQueue(clock);
        FakeInventoryPort failingPort = new FakeInventoryPort();
        IllegalStateException primary = new IllegalStateException("adapter primary");
        IllegalArgumentException diagnosticFailure = new IllegalArgumentException("diagnostic failed");
        failingPort.observeFailure = primary;
        InventoryController suppressed = new InventoryController(
                queue, new InputController()::selectHotbar, failingPort, diagnostic -> {
                    throw diagnosticFailure;
                });
        suppressed.start(oneStep(
                InventoryClick.quickMove(), InventoryPostcondition.TARGET_SLOT_EMPTY, 100),
                ignored -> { });
        assertSame(primary, assertThrows(IllegalStateException.class, queue::advance));
        assertArrayEquals(new Throwable[]{diagnosticFailure}, primary.getSuppressed());
        assertTrue(suppressed.activeToken().isEmpty());
    }

    private static void assertAtomicMutationRejected(
            InventoryScreenSnapshot changed, InventoryCancelReason expected) {
        Fixture fixture = new Fixture();
        fixture.port.current = Observation.present(baseSnapshot());
        fixture.port.beforeClick = () -> fixture.port.current = Observation.present(changed);
        List<InventoryOutcome> outcomes = new ArrayList<>();
        fixture.controller.start(oneStep(
                InventoryClick.quickMove(), InventoryPostcondition.TARGET_SLOT_EMPTY, 100),
                outcomes::add);

        fixture.queue.advance();

        assertReason(outcomes, expected);
        assertTrue(fixture.port.clicks.isEmpty());
    }

    private static void assertControllerPrecheck(
            InventoryScreenSnapshot snapshot, InventoryCancelReason expected) {
        Fixture fixture = new Fixture();
        fixture.port.current = Observation.present(snapshot);
        List<InventoryOutcome> outcomes = new ArrayList<>();
        fixture.controller.start(oneStep(
                InventoryClick.quickMove(), InventoryPostcondition.TARGET_SLOT_EMPTY, 100),
                outcomes::add);
        fixture.queue.advance();
        assertReason(outcomes, expected);
        assertTrue(fixture.port.guards.isEmpty());
    }

    private static InventoryOperation oneStep(
            InventoryClick click, InventoryVerifier verifier, long timeoutNanos) {
        InventoryScreenSnapshot expected = baseSnapshot();
        return InventoryOperation.clicks(OWNER, expectation(expected), List.of(
                new InventoryStep(InventoryQuery.slot(0), click, verifier)), timeoutNanos);
    }

    private static ScreenExpectation expectation(InventoryScreenSnapshot snapshot) {
        return ScreenExpectation.exact(
                snapshot.identity(),
                snapshot.screen().type().get(),
                snapshot.screen().title().get(),
                snapshot.slots().size(),
                List.of(InventoryQuery.slot(0).withIdentifier("minecraft:wheat")));
    }

    private static void assertReason(List<InventoryOutcome> outcomes, InventoryCancelReason reason) {
        assertEquals(1, outcomes.size());
        assertEquals(InventoryOutcome.Status.CANCELLED, outcomes.getFirst().status());
        assertEquals(reason, outcomes.getFirst().reason().orElseThrow());
    }

    private static void advance(ClientTaskQueue queue, int times) {
        for (int count = 0; count < times; count++) {
            queue.advance();
        }
    }

    private static InventoryScreenSnapshot baseSnapshot() {
        return snapshotWithItem(WHEAT, Observation.absent(), 0, 0);
    }

    private static InventoryScreenSnapshot changedSnapshot(int state, long local) {
        return snapshot(1, 2, state, local, List.of(slot(0, null, true, true)),
                Observation.absent(), "Chest");
    }

    private static InventoryScreenSnapshot snapshotWithItem(
            InventoryItem item, Observation<InventoryItem> cursor, int state, long local) {
        return snapshot(1, 2, state, local, List.of(slot(0, item, true, true)), cursor, "Chest");
    }

    private static InventoryScreenSnapshot snapshot(
            long epoch,
            int containerId,
            int state,
            long local,
            List<InventorySlot> slots,
            Observation<InventoryItem> cursor,
            String title) {
        return new InventoryScreenSnapshot(
                new ScreenIdentity(epoch, containerId, "minecraft:generic_9x3"),
                new ScreenRevision(state, local),
                new ScreenSnapshot(Observation.present("minecraft:generic_9x3"), Observation.present(title)),
                slots,
                cursor,
                Observation.present(new HotbarSelection(0)));
    }

    private static InventorySlot slot(
            int slot, InventoryItem item, boolean active, boolean mayPickup) {
        return new InventorySlot(
                slot,
                item == null ? Observation.absent() : Observation.present(item),
                active,
                mayPickup,
                Optional.empty());
    }

    private static InventoryItem item(String id, int count, String name, String lore) {
        return new InventoryItem(
                new ItemSummary(ResourceIdentifier.parse(id), count),
                new ItemComponentSummary(Optional.of(name), Optional.of(name),
                        lore.isEmpty() ? List.of() : List.of(lore)));
    }

    private static final class Fixture {
        private final ManualClock clock = new ManualClock();
        private final ClientTaskQueue queue = new ClientTaskQueue(clock);
        private final InputController input = new InputController();
        private final FakeInventoryPort port = new FakeInventoryPort();
        private final List<InventoryDiagnostic> diagnostics = new ArrayList<>();
        private final InventoryController controller = new InventoryController(
                queue, input::selectHotbar, port, diagnostics::add);
    }

    private static final class ManualClock implements MonotonicClock {
        private long now;

        @Override
        public long nowNanos() {
            return now;
        }

        private void advance(long nanos) {
            now += nanos;
        }
    }

    private static final class FakeInventoryPort implements InventoryPort {
        private Observation<InventoryScreenSnapshot> current = Observation.absent();
        private final Queue<InventoryScreenSnapshot> afterClicks = new LinkedList<>();
        private final List<InventoryClick> clicks = new ArrayList<>();
        private final List<InventoryClickGuard> guards = new ArrayList<>();
        private Runnable beforeClick = () -> { };
        private RuntimeException observeFailure;
        private RuntimeException executeFailure;
        private RuntimeException closeFailure;

        @Override
        public Observation<InventoryScreenSnapshot> observe(
                InventoryOperationToken token, ControlOwner owner) {
            if (observeFailure != null) {
                throw observeFailure;
            }
            return current;
        }

        @Override
        public InventoryExecutionResult executeGuardedClick(InventoryClickGuard guard) {
            if (executeFailure != null) {
                throw executeFailure;
            }
            beforeClick.run();
            guards.add(guard);
            if (!current.isPresent()) {
                return InventoryExecutionResult.rejected(InventoryCancelReason.SCREEN_CLOSED);
            }
            InventoryScreenSnapshot snapshot = current.get();
            if (!snapshot.identity().equals(guard.target().screen())) {
                return InventoryExecutionResult.rejected(InventoryCancelReason.SCREEN_CHANGED);
            }
            Optional<InventorySlot> maybeSlot = snapshot.slot(guard.target().menuSlot());
            if (maybeSlot.isEmpty()) {
                return InventoryExecutionResult.rejected(InventoryCancelReason.SLOT_OUT_OF_BOUNDS);
            }
            InventorySlot slot = maybeSlot.orElseThrow();
            if (!slot.item().isPresent()) {
                return InventoryExecutionResult.rejected(InventoryCancelReason.ITEM_CHANGED);
            }
            InventoryItem actual = slot.item().get();
            InventoryItem expected = guard.target().item();
            if (!actual.summary().identifier().equals(expected.summary().identifier())
                    || !actual.components().equals(expected.components())) {
                return InventoryExecutionResult.rejected(InventoryCancelReason.ITEM_CHANGED);
            }
            if (actual.count() != expected.count()) {
                return InventoryExecutionResult.rejected(InventoryCancelReason.COUNT_CHANGED);
            }
            if (!slot.hotbarSelection().equals(guard.hotbarSelection())) {
                return InventoryExecutionResult.rejected(InventoryCancelReason.SLOT_CHANGED);
            }
            if (!snapshot.cursor().equals(guard.cursor())) {
                return InventoryExecutionResult.rejected(InventoryCancelReason.CURSOR_CHANGED);
            }
            if (!snapshot.revision().equals(guard.target().revision())) {
                return InventoryExecutionResult.rejected(InventoryCancelReason.REVISION_CHANGED);
            }
            if (!slot.active()) {
                return InventoryExecutionResult.rejected(InventoryCancelReason.SLOT_INACTIVE);
            }
            if (!slot.mayPickup()) {
                return InventoryExecutionResult.rejected(InventoryCancelReason.PICKUP_DENIED);
            }
            if (!guard.authority().isActive()) {
                return InventoryExecutionResult.rejected(InventoryCancelReason.STALE_TOKEN);
            }
            clicks.add(guard.click());
            if (!afterClicks.isEmpty()) {
                InventoryScreenSnapshot after = afterClicks.remove();
                current = after == null ? Observation.absent() : Observation.present(after);
            }
            return InventoryExecutionResult.success();
        }

        @Override
        public void releaseOperation(InventoryOperationToken token, ControlOwner owner) {
        }

        private InventoryExecutionResult executeWithCurrent(
                InventoryScreenSnapshot snapshot, InventoryClickGuard guard) {
            current = Observation.present(snapshot);
            return executeGuardedClick(guard);
        }

        @Override
        public Optional<InventoryCancelReason> closeScreen(ScreenIdentity expected) {
            if (closeFailure != null) {
                throw closeFailure;
            }
            if (!current.isPresent()) {
                return Optional.of(InventoryCancelReason.SCREEN_CLOSED);
            }
            if (!current.get().identity().equals(expected)) {
                return Optional.of(InventoryCancelReason.SCREEN_CHANGED);
            }
            current = Observation.absent();
            return Optional.empty();
        }
    }
}
