package dev.hylfrd.farmhelper.control.inventory;

import dev.hylfrd.farmhelper.control.input.ControlOwner;
import dev.hylfrd.farmhelper.control.input.HotbarSelection;
import dev.hylfrd.farmhelper.control.input.InputAction;
import dev.hylfrd.farmhelper.control.input.InputController;
import dev.hylfrd.farmhelper.control.input.InputLease;
import dev.hylfrd.farmhelper.runtime.snapshot.ItemSummary;
import dev.hylfrd.farmhelper.runtime.snapshot.Observation;
import dev.hylfrd.farmhelper.runtime.snapshot.ResourceIdentifier;
import dev.hylfrd.farmhelper.runtime.snapshot.ScreenSnapshot;
import dev.hylfrd.farmhelper.runtime.time.ClientTaskQueue;
import dev.hylfrd.farmhelper.runtime.time.MonotonicClock;
import dev.hylfrd.farmhelper.runtime.time.TaskOwner;
import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;
import java.util.AbstractList;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InventorySafetyTest {
    private static final ControlOwner OWNER = new ControlOwner("safety-owner");
    private static final InventoryItem WHEAT = item("minecraft:wheat", 8, "Wheat");

    @Test
    void boundAuthorityRejectsCancellationAndReplacementImmediatelyBeforeWrite() {
        Fixture fixture = new Fixture();
        fixture.port.current = Observation.present(base());
        InventoryOperationToken[] old = new InventoryOperationToken[1];
        InventoryOperationToken[] replacement = new InventoryOperationToken[1];
        AtomicInteger callbacks = new AtomicInteger();
        fixture.port.beforeAuthority = () -> fixture.controller.cancel(old[0]);

        old[0] = fixture.controller.start(clickOperation(
                expectation(base()), InventoryPostcondition.TARGET_SLOT_EMPTY, 100), outcome -> {
            callbacks.incrementAndGet();
            replacement[0] = fixture.controller.start(
                    InventoryOperation.hotbar(OWNER, new HotbarSelection(3), 100), ignored -> { });
        });
        fixture.queue.advance();

        assertEquals(0, fixture.port.clicks);
        assertEquals(1, callbacks.get());
        assertFalse(fixture.port.lastGuard.authority().isActive());
        assertEquals(Optional.of(replacement[0]), fixture.controller.activeToken());
    }

    @Test
    void initialScreenExpectationRejectsWrongIdentityTypeTitleSizeAndSentinelWithoutClick() {
        InventoryScreenSnapshot expected = base();
        InventoryScreenSnapshot wrongType = new InventoryScreenSnapshot(
                expected.identity(),
                expected.revision(),
                new ScreenSnapshot(
                        Observation.present("minecraft:hopper"),
                        expected.screen().title()),
                expected.slots(),
                expected.cursor(),
                expected.selectedHotbar());
        assertInitialRejection(snapshot(2, 2, expected.identity().type(), "Chest",
                        List.of(slot(0, WHEAT))), expectation(expected),
                InventoryCancelReason.SCREEN_IDENTITY_MISMATCH);
        assertInitialRejection(wrongType, new ScreenExpectation(
                        Optional.of(expected.identity()), expected.identity().type(), "Chest", 1, List.of()),
                InventoryCancelReason.SCREEN_TYPE_MISMATCH);
        assertInitialRejection(snapshot(1, 2, expected.identity().type(), "Other",
                        List.of(slot(0, WHEAT))), new ScreenExpectation(
                        Optional.of(expected.identity()), expected.identity().type(), "Chest", 1, List.of()),
                InventoryCancelReason.SCREEN_TITLE_MISMATCH);
        assertInitialRejection(snapshot(1, 2, expected.identity().type(), "Chest", List.of()),
                new ScreenExpectation(Optional.of(expected.identity()),
                        expected.identity().type(), "Chest", 1, List.of()),
                InventoryCancelReason.SCREEN_SLOT_COUNT_MISMATCH);
        assertInitialRejection(snapshot(1, 2, expected.identity().type(), "Chest",
                        List.of(slot(0, item("minecraft:stone", 1, "Stone")))),
                new ScreenExpectation(Optional.of(expected.identity()), expected.identity().type(), "Chest", 1,
                        List.of(InventoryQuery.slot(0).withIdentifier("minecraft:wheat"))),
                InventoryCancelReason.SCREEN_SENTINEL_MISSING);
    }

    @Test
    void clickOperationsCannotOmitExpectationAndHotbarOnlyOperationsCannotSmuggleOne() {
        InventoryStep step = new InventoryStep(
                InventoryQuery.slot(0), InventoryClick.quickMove(),
                InventoryPostcondition.TARGET_SLOT_EMPTY);
        assertThrows(IllegalArgumentException.class, () -> new InventoryOperation(
                OWNER, Optional.empty(), Optional.empty(), List.of(step), 10));
        assertThrows(IllegalArgumentException.class, () -> new InventoryOperation(
                OWNER, Optional.of(new HotbarSelection(0)), Optional.of(expectation(base())),
                List.of(), 10));

        ScreenExpectation unbound = new ScreenExpectation(
                Optional.empty(), base().identity().type(), "Chest", 1, List.of());
        assertThrows(IllegalArgumentException.class, () -> InventoryOperation.clicks(
                OWNER, unbound, List.of(step), 10));

        InventoryOperation hotbarOnly = InventoryOperation.hotbar(
                OWNER, new HotbarSelection(0), 10);
        assertTrue(hotbarOnly.screenExpectation().isEmpty());
        assertTrue(hotbarOnly.steps().isEmpty());
    }

    @Test
    void operationConstructionSnapshotsMutableStepsRejectsNullAndExposesNoMutationSurface() {
        InventoryStep first = step(0);
        InventoryStep replacement = step(1);
        List<InventoryStep> mutable = new ArrayList<>(List.of(first));

        InventoryOperation operation = InventoryOperation.clicks(
                OWNER, expectation(base()), mutable, 10);
        mutable.clear();
        mutable.add(replacement);

        assertEquals(List.of(first), operation.steps());
        assertThrows(UnsupportedOperationException.class,
                () -> operation.steps().add(replacement));

        List<InventoryStep> withNull = new ArrayList<>();
        withNull.add(null);
        assertThrows(NullPointerException.class, () -> InventoryOperation.clicks(
                OWNER, expectation(base()), withNull, 10));
    }

    @Test
    void hostileStepListsAreRejectedOrBecomeCompleteConsistentImmutableSnapshots() {
        InventoryStep before = step(0);
        InventoryStep after = step(1);
        for (AccessStage stage : AccessStage.values()) {
            HostileStepList changing = new HostileStepList(
                    List.of(before), stage, HostileAction.REPLACE, List.of(after));
            assertSafeSnapshot(changing, List.of(before), List.of(after));

            HostileStepList returningNull = new HostileStepList(
                    List.of(before), stage, HostileAction.RETURN_NULL, List.of());
            assertSafeSnapshot(returningNull, List.of(before));

            HostileStepList throwing = new HostileStepList(
                    List.of(before), stage, HostileAction.THROW, List.of());
            assertSafeSnapshot(throwing, List.of(before));
        }
    }

    @Test
    void changingEmptyListsCannotPassAsHotbarOnlyThenRevealHiddenClicks() {
        InventoryStep hiddenClick = step(0);
        for (AccessStage stage : AccessStage.values()) {
            HostileStepList hostile = new HostileStepList(
                    List.of(), stage, HostileAction.REPLACE, List.of(hiddenClick));
            Optional<HotbarSelection> hotbarSelection = Optional.of(new HotbarSelection(2));
            Optional<ScreenExpectation> screenExpectation = Optional.empty();
            OperationFactory<InventoryOperation> factory = () -> new InventoryOperation(
                    OWNER, hotbarSelection, screenExpectation, hostile, 10);
            PostConstructionAssertions<InventoryOperation> assertions = operation -> {
                hostile.replaceContents(List.of(hiddenClick));
                assertTrue(operation.steps().isEmpty());
                assertTrue(operation.screenExpectation().isEmpty());
                assertThrows(UnsupportedOperationException.class,
                        () -> operation.steps().add(hiddenClick));
            };

            assertChangingEmptyListConstruction(hostile, factory, assertions);
        }
    }

    @Test
    void successfulConstructionCannotTreatLaterStepsFailureAsSafeRejection() {
        assertAll(
                () -> assertPostConstructionFailurePropagates(
                        "changing-empty-list wrapper",
                        InventorySafetyTest::assertChangingEmptyListConstruction),
                () -> assertPostConstructionFailurePropagates(
                        "snapshot wrapper",
                        InventorySafetyTest::assertSafeSnapshotConstruction));
    }

    @Test
    void mixedHotbarAndClickOperationsRequireExpectationAndExactIdentity() {
        InventoryStep step = step(0);
        HotbarSelection hotbar = new HotbarSelection(2);
        assertThrows(IllegalArgumentException.class, () -> new InventoryOperation(
                OWNER, Optional.of(hotbar), Optional.empty(), List.of(step), 10));

        ScreenExpectation withoutIdentity = new ScreenExpectation(
                Optional.empty(), base().identity().type(), "Chest", 1, List.of());
        assertThrows(IllegalArgumentException.class, () -> new InventoryOperation(
                OWNER, Optional.of(hotbar), Optional.of(withoutIdentity), List.of(step), 10));

        InventoryOperation operation = new InventoryOperation(
                OWNER, Optional.of(hotbar), Optional.of(expectation(base())), List.of(step), 10);
        assertEquals(Optional.of(hotbar), operation.hotbarSelection());
        assertTrue(operation.screenExpectation().orElseThrow().exactIdentity().isPresent());
        assertEquals(List.of(step), operation.steps());
    }

    @Test
    void isomorphicWrongContainerIdentityIsRejectedAtControllerGate() {
        InventoryScreenSnapshot expected = base();
        InventoryScreenSnapshot wrongIdentity = snapshot(
                expected.identity().epoch() + 1,
                expected.identity().containerId(),
                expected.identity().type(),
                expected.screen().title().get(),
                expected.slots());

        assertEquals(expected.revision(), wrongIdentity.revision());
        assertEquals(expected.screen(), wrongIdentity.screen());
        assertEquals(expected.slots(), wrongIdentity.slots());
        assertEquals(expected.cursor(), wrongIdentity.cursor());
        assertInitialRejection(
                wrongIdentity, expectation(expected), InventoryCancelReason.SCREEN_IDENTITY_MISMATCH);
    }

    @Test
    void equalButDistinctScreenIdentityUsesValueEqualityWhileDifferentEpochOrIdNeverClicks() {
        InventoryScreenSnapshot expected = base();
        InventoryScreenSnapshot equalButDistinct = snapshot(
                expected.identity().epoch(), expected.identity().containerId(),
                expected.identity().type(), expected.screen().title().get(), expected.slots());
        assertNotSame(expected.identity(), equalButDistinct.identity());
        assertEquals(expected.identity(), equalButDistinct.identity());

        Fixture accepted = new Fixture();
        accepted.port.scripted.add(Observation.present(equalButDistinct));
        accepted.port.scripted.add(Observation.present(revised(
                equalButDistinct, 1, 1, List.of(slot(0, null)))));
        List<InventoryOutcome> outcomes = new ArrayList<>();
        accepted.controller.start(clickOperation(
                expectation(expected), InventoryPostcondition.TARGET_SLOT_EMPTY, 10), outcomes::add);
        accepted.queue.advance();
        accepted.queue.advance();
        accepted.queue.advance();

        assertEquals(1, accepted.port.clicks);
        assertEquals(InventoryOutcome.Status.COMPLETED, outcomes.getFirst().status());

        assertInitialRejection(snapshot(
                        expected.identity().epoch() + 1, expected.identity().containerId(),
                        expected.identity().type(), expected.screen().title().get(), expected.slots()),
                expectation(expected), InventoryCancelReason.SCREEN_IDENTITY_MISMATCH);
        assertInitialRejection(snapshot(
                        expected.identity().epoch(), expected.identity().containerId() + 1,
                        expected.identity().type(), expected.screen().title().get(), expected.slots()),
                expectation(expected), InventoryCancelReason.SCREEN_IDENTITY_MISMATCH);
    }

    @Test
    void postconditionsRejectMissingSlotWrongIdentityAndRevisionOnlyNoise() {
        InventoryScreenSnapshot before = base();
        ItemIdentity target = new ItemIdentity(before.identity(), before.revision(), 0, WHEAT);
        InventoryScreenSnapshot missingSlot = snapshot(1, 2, before.identity().type(), "Chest", List.of());
        InventoryScreenSnapshot wrongItemWithLowerCount = snapshot(1, 2, before.identity().type(), "Chest",
                List.of(slot(0, item("minecraft:stone", 1, "Stone"))));

        assertFalse(InventoryPostcondition.TARGET_SLOT_CHANGED.verify(
                before, missingSlot, target, InventoryClick.quickMove()));
        assertFalse(InventoryPostcondition.TARGET_SLOT_CHANGED.verify(
                before, wrongItemWithLowerCount, target, InventoryClick.quickMove()));
        assertFalse(InventoryPostcondition.TARGET_COUNT_DECREASED.verify(
                before, wrongItemWithLowerCount, target, InventoryClick.quickMove()));

        Fixture fixture = new Fixture();
        InventoryItem carrot = item("minecraft:carrot", 2, "Carrot");
        InventoryScreenSnapshot twoSlots = snapshot(1, 2, before.identity().type(), "Chest",
                List.of(slot(0, WHEAT), slot(1, carrot)));
        InventoryScreenSnapshot unrelatedRevision = revised(twoSlots, 1, 1,
                List.of(slot(0, WHEAT), slot(1, null)));
        fixture.port.current = Observation.present(unrelatedRevision);
        fixture.port.scripted.add(Observation.present(twoSlots));
        fixture.port.scripted.add(Observation.present(unrelatedRevision));
        List<InventoryOutcome> outcomes = new ArrayList<>();
        fixture.controller.start(clickOperation(expectation(twoSlots),
                InventoryPostcondition.TARGET_SLOT_EMPTY, 10), outcomes::add);
        fixture.queue.advance();
        fixture.queue.advance();
        assertTrue(outcomes.isEmpty());
        fixture.clock.advance(10);
        fixture.queue.advance();
        assertReason(outcomes, InventoryCancelReason.TIMEOUT);
    }

    @Test
    void verificationWaitsAcrossSecondAndNthAdvanceThenSucceedsBeforeTimeout() {
        Fixture fixture = new Fixture();
        InventoryScreenSnapshot before = base();
        InventoryScreenSnapshot unchanged = before;
        InventoryScreenSnapshot revisionOnly = revised(before, 1, 1, List.of(slot(0, WHEAT)));
        InventoryScreenSnapshot satisfied = revised(before, 2, 2, List.of(slot(0, null)));
        fixture.port.scripted.add(Observation.present(before));
        fixture.port.scripted.add(Observation.present(unchanged));
        fixture.port.scripted.add(Observation.present(revisionOnly));
        fixture.port.scripted.add(Observation.present(satisfied));
        List<InventoryOutcome> outcomes = new ArrayList<>();
        fixture.controller.start(clickOperation(
                expectation(before), InventoryPostcondition.TARGET_SLOT_EMPTY, 10), outcomes::add);

        fixture.queue.advance();
        fixture.queue.advance();
        fixture.queue.advance();
        assertTrue(outcomes.isEmpty());
        assertEquals(2, fixture.queue.pendingTaskCount());
        fixture.clock.advance(9);
        fixture.queue.advance();
        fixture.queue.advance();

        assertEquals(InventoryOutcome.Status.COMPLETED, outcomes.getFirst().status());
        assertEquals(1, fixture.port.clicks);
    }

    @Test
    void exactTimeoutBoundaryWinsWhileOneNanosecondBeforeCanComplete() {
        Fixture beforeBoundary = new Fixture();
        beforeBoundary.port.scripted.add(Observation.present(base()));
        beforeBoundary.port.scripted.add(Observation.present(revised(base(), 1, 1, List.of(slot(0, null)))));
        List<InventoryOutcome> completed = new ArrayList<>();
        beforeBoundary.controller.start(clickOperation(
                expectation(base()), InventoryPostcondition.TARGET_SLOT_EMPTY, 10), completed::add);
        beforeBoundary.queue.advance();
        beforeBoundary.clock.advance(9);
        beforeBoundary.queue.advance();
        beforeBoundary.queue.advance();
        assertEquals(InventoryOutcome.Status.COMPLETED, completed.getFirst().status());

        Fixture boundary = new Fixture();
        boundary.port.scripted.add(Observation.present(base()));
        boundary.port.scripted.add(Observation.present(revised(base(), 1, 1, List.of(slot(0, null)))));
        List<InventoryOutcome> timedOut = new ArrayList<>();
        boundary.controller.start(clickOperation(
                expectation(base()), InventoryPostcondition.TARGET_SLOT_EMPTY, 10), timedOut::add);
        boundary.queue.advance();
        boundary.clock.advance(10);
        boundary.queue.advance();
        assertReason(timedOut, InventoryCancelReason.TIMEOUT);
    }

    @Test
    void runtimeExceptionAndErrorFromAdaptersVerifierAndClosePropagateAfterCleanup() {
        Fixture observe = new Fixture();
        IllegalStateException observeFailure = new IllegalStateException("observe failure");
        observe.port.observeFailure = observeFailure;
        AtomicInteger observeCallbacks = new AtomicInteger();
        observe.controller.start(clickOperation(
                expectation(base()), InventoryPostcondition.TARGET_SLOT_EMPTY, 10),
                ignored -> observeCallbacks.incrementAndGet());
        assertSame(observeFailure,
                assertThrows(IllegalStateException.class, observe.queue::advance));
        assertTrue(observe.controller.activeToken().isEmpty());
        assertEquals(1, observeCallbacks.get());
        assertEquals(1, observe.port.releaseCalls);

        Fixture execute = new Fixture();
        AssertionError executeFailure = new AssertionError("execute failure");
        execute.port.current = Observation.present(base());
        execute.port.executeFailure = executeFailure;
        execute.controller.start(clickOperation(
                expectation(base()), InventoryPostcondition.TARGET_SLOT_EMPTY, 10), ignored -> { });
        assertSame(executeFailure, assertThrows(AssertionError.class, execute.queue::advance));
        assertTrue(execute.controller.activeToken().isEmpty());

        Fixture verifier = new Fixture();
        AssertionError verifierFailure = new AssertionError("verifier failure");
        verifier.port.scripted.add(Observation.present(base()));
        verifier.port.scripted.add(Observation.present(revised(base(), 1, 1, List.of(slot(0, null)))));
        verifier.controller.start(clickOperation(expectation(base()),
                (before, after, target, click) -> { throw verifierFailure; }, 10), ignored -> { });
        verifier.queue.advance();
        assertSame(verifierFailure, assertThrows(AssertionError.class, verifier.queue::advance));
        assertTrue(verifier.controller.activeToken().isEmpty());

        Fixture close = new Fixture();
        close.port.current = Observation.present(base());
        AssertionError closeFailure = new AssertionError("close failure");
        close.port.closeFailure = closeFailure;
        InventoryOperationToken closeToken = close.controller.start(
                InventoryOperation.hotbar(OWNER, new HotbarSelection(1), 10), ignored -> { });
        assertSame(closeFailure,
                assertThrows(AssertionError.class, () -> close.controller.closeScreen(closeToken)));
        assertTrue(close.controller.activeToken().isEmpty());
    }

    @Test
    void diagnosticFailureIsSuppressedAndFailureMessagesAreRedacted() {
        ManualClock clock = new ManualClock();
        ClientTaskQueue queue = new ClientTaskQueue(clock);
        SafetyPort port = new SafetyPort();
        IllegalStateException primary = new IllegalStateException("do not expose this message");
        AssertionError diagnostic = new AssertionError("diagnostic failure");
        port.observeFailure = primary;
        List<InventoryOutcome> outcomes = new ArrayList<>();
        InventoryController controller = new InventoryController(
                queue, new InputController()::selectHotbar, port, ignored -> { throw diagnostic; });
        controller.start(clickOperation(
                expectation(base()), InventoryPostcondition.TARGET_SLOT_EMPTY, 10), outcomes::add);

        assertSame(primary, assertThrows(IllegalStateException.class, queue::advance));
        assertArrayEquals(new Throwable[]{diagnostic}, primary.getSuppressed());
        assertEquals(1, outcomes.size());
        assertTrue(InventoryDiagnostic.Failure.from(primary).message().isEmpty());
    }

    @Test
    void diagnosticAsFirstFailurePropagatesAfterCleanupAndCallbackDelivery() {
        ManualClock clock = new ManualClock();
        ClientTaskQueue queue = new ClientTaskQueue(clock);
        SafetyPort port = new SafetyPort();
        AssertionError diagnosticFailure = new AssertionError("diagnostic first failure");
        AtomicInteger callbacks = new AtomicInteger();
        InventoryController controller = new InventoryController(
                queue, new InputController()::selectHotbar, port,
                ignored -> { throw diagnosticFailure; });
        InventoryOperationToken token = controller.start(
                InventoryOperation.hotbar(OWNER, new HotbarSelection(1), 10),
                ignored -> callbacks.incrementAndGet());

        assertSame(diagnosticFailure,
                assertThrows(AssertionError.class, () -> controller.cancel(token)));
        assertTrue(controller.activeToken().isEmpty());
        assertEquals(1, callbacks.get());
        assertEquals(1, port.releaseCalls);
    }

    @Test
    void callbackFailurePropagatesAfterCleanupAndCannotLetOldCleanupEraseReentry() {
        Fixture fixture = new Fixture();
        AssertionError callbackFailure = new AssertionError("callback failure");
        InventoryOperationToken[] replacement = new InventoryOperationToken[1];
        fixture.controller.start(
                InventoryOperation.hotbar(OWNER, new HotbarSelection(1), 10), outcome -> {
                    replacement[0] = fixture.controller.start(
                            InventoryOperation.hotbar(OWNER, new HotbarSelection(2), 10), ignored -> { });
                    throw callbackFailure;
                });

        assertSame(callbackFailure, assertThrows(AssertionError.class, fixture.queue::advance));
        assertEquals(Optional.of(replacement[0]), fixture.controller.activeToken());
        assertTrue(fixture.diagnostics.stream()
                .anyMatch(value -> value.reason() == InventoryCancelReason.CALLBACK_EXCEPTION));
    }

    @Test
    void firstAndSecondScheduleFailureCancelOnlyThisOperationAndReleaseLease() {
        assertScheduleFailure(1);
        assertScheduleFailure(2);
    }

    @Test
    void hotbarReleaseErrorPropagatesAfterOwnerCleanup() {
        ManualClock clock = new ManualClock();
        ClientTaskQueue queue = new ClientTaskQueue(clock);
        AtomicBoolean failRelease = new AtomicBoolean();
        AtomicInteger releaseNotifications = new AtomicInteger();
        AssertionError releaseFailure = new AssertionError("release failure");
        InputController input = new InputController(snapshot -> {
            if (failRelease.get() && snapshot.hotbarOwner().isEmpty()) {
                if (releaseNotifications.incrementAndGet() == 1) {
                    throw releaseFailure;
                }
                throw new IllegalStateException("secondary release diagnostic");
            }
        });
        SafetyPort port = new SafetyPort();
        List<InventoryOutcome> outcomes = new ArrayList<>();
        InventoryController controller = new InventoryController(
                queue, input::selectHotbar, port, ignored -> { });
        controller.start(InventoryOperation.hotbar(
                OWNER, new HotbarSelection(4), 10), outcomes::add);
        failRelease.set(true);

        assertSame(releaseFailure, assertThrows(AssertionError.class, queue::advance));
        assertTrue(controller.activeToken().isEmpty());
        assertTrue(input.snapshot().hotbarOwner().isEmpty());
        assertEquals(InventoryCancelReason.ADAPTER_EXCEPTION,
                outcomes.getFirst().reason().orElseThrow());
    }

    @Test
    void publicTextProjectionHasNoRawDataSurfaceAndSanitizesFormattingControlsAndBounds() {
        List<String> componentNames = java.util.Arrays.stream(ItemComponentSummary.class.getRecordComponents())
                .map(RecordComponent::getName)
                .toList();
        assertEquals(List.of("customName", "itemName", "lore"), componentNames);
        assertTrue(java.util.Arrays.stream(InventoryQuery.class.getMethods())
                .noneMatch(method -> method.getName().toLowerCase(java.util.Locale.ROOT).contains("customdata")));

        String sectionSign = Character.toString(0x00A7);
        String formatted = sectionSign + "aVisible"
                + Character.toString(0)
                + Character.toString(7)
                + Character.toString(0x202E)
                + "x".repeat(200);
        List<String> lore = new ArrayList<>();
        for (int index = 0; index < 30; index++) {
            lore.add(formatted);
        }
        ItemComponentSummary summary = new ItemComponentSummary(
                Optional.of(formatted), Optional.of(formatted), lore);

        assertTrue(summary.customName().orElseThrow().startsWith("Visible"));
        assertEquals(ItemComponentSummary.MAX_NAME_CODE_POINTS,
                summary.customName().orElseThrow().codePointCount(
                        0, summary.customName().orElseThrow().length()));
        assertEquals(ItemComponentSummary.MAX_LORE_LINES, summary.lore().size());
        assertTrue(summary.toString().codePoints().noneMatch(Character::isISOControl));
        assertFalse(summary.toString().contains(sectionSign));
    }

    private static void assertScheduleFailure(int failingCall) {
        FailingScheduler scheduler = new FailingScheduler(failingCall);
        InputController input = new InputController();
        ControlOwner other = new ControlOwner("other-owner");
        InputLease otherLease = input.hold(other, InputAction.FORWARD);
        SafetyPort port = new SafetyPort();
        List<InventoryOutcome> outcomes = new ArrayList<>();
        InventoryController controller = new InventoryController(
                scheduler, input::selectHotbar, port, ignored -> { });
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> controller.start(InventoryOperation.hotbar(
                        OWNER, new HotbarSelection(5), 10), outcomes::add));

        assertSame(scheduler.failure, failure);
        assertEquals(1, outcomes.size());
        assertTrue(controller.activeToken().isEmpty());
        assertTrue(input.snapshot().hotbarOwner().isEmpty());
        assertEquals(Optional.of(other), input.snapshot().ownerOf(InputAction.FORWARD));
        assertEquals(1, scheduler.cancelCalls);
        assertEquals(0, scheduler.pendingOwnTasks);
        otherLease.close();
    }

    private static void assertInitialRejection(
            InventoryScreenSnapshot actual,
            ScreenExpectation expectation,
            InventoryCancelReason reason) {
        Fixture fixture = new Fixture();
        fixture.port.current = Observation.present(actual);
        List<InventoryOutcome> outcomes = new ArrayList<>();
        fixture.controller.start(clickOperation(
                expectation, InventoryPostcondition.TARGET_SLOT_EMPTY, 10), outcomes::add);
        fixture.queue.advance();
        assertReason(outcomes, reason);
        assertEquals(0, fixture.port.executeCalls);
        assertEquals(0, fixture.port.clicks);
    }

    private static InventoryOperation clickOperation(
            ScreenExpectation expectation, InventoryVerifier verifier, long timeoutNanos) {
        return InventoryOperation.clicks(OWNER, expectation, List.of(
                new InventoryStep(InventoryQuery.slot(0),
                        InventoryClick.quickMove(), verifier)), timeoutNanos);
    }

    @SafeVarargs
    private static void assertSafeSnapshot(
            HostileStepList hostile, List<InventoryStep>... completeSnapshots) {
        ScreenExpectation screenExpectation = expectation(base());
        OperationFactory<InventoryOperation> factory = () -> InventoryOperation.clicks(
                OWNER, screenExpectation, hostile, 10);
        PostConstructionAssertions<InventoryOperation> assertions = operation -> {
            assertSnapshotContents(operation::steps, completeSnapshots);
            assertTrue(operation.steps().stream().allMatch(java.util.Objects::nonNull));
            List<InventoryStep> captured = operation.steps();
            hostile.replaceContents(List.of(step(2)));
            assertEquals(captured, operation.steps());
            assertThrows(UnsupportedOperationException.class,
                    () -> operation.steps().add(step(3)));
        };

        assertSafeSnapshotConstruction(hostile, factory, assertions);
    }

    private static <T> void assertChangingEmptyListConstruction(
            HostileStepList hostile,
            OperationFactory<T> factory,
            PostConstructionAssertions<T> assertions) {
        T constructed = null;
        RuntimeException rejection = null;
        try {
            constructed = factory.construct();
        } catch (RuntimeException safeRejection) {
            rejection = safeRejection;
        }

        ConstructionAttempt<T> attempt = ConstructionAttempt.of(constructed, rejection);
        if (attempt.rejection().isPresent()) {
            assertEquals(IllegalArgumentException.class, attempt.rejection().orElseThrow().getClass());
            assertTrue(hostile.triggered());
            return;
        }

        assertions.verify(attempt.requireSuccess());
    }

    private static <T> void assertSafeSnapshotConstruction(
            HostileStepList hostile,
            OperationFactory<T> factory,
            PostConstructionAssertions<T> assertions) {
        T constructed = null;
        RuntimeException rejection = null;
        try {
            constructed = factory.construct();
        } catch (RuntimeException safeRejection) {
            rejection = safeRejection;
        }

        ConstructionAttempt<T> attempt = ConstructionAttempt.of(constructed, rejection);
        if (attempt.rejection().isPresent()) {
            RuntimeException safeRejection = attempt.rejection().orElseThrow();
            Class<? extends RuntimeException> expectedType = switch (hostile.action()) {
                case REPLACE -> IllegalArgumentException.class;
                case RETURN_NULL -> NullPointerException.class;
                case THROW -> IllegalStateException.class;
            };
            assertEquals(expectedType, safeRejection.getClass());
            assertTrue(hostile.triggered());
            return;
        }

        assertions.verify(attempt.requireSuccess());
    }

    private static void assertPostConstructionFailurePropagates(
            String wrapperName, ConstructionWrapper wrapper) {
        InventoryStep hiddenClick = step(0);
        HostileStepList hostile = new HostileStepList(
                List.of(), AccessStage.SIZE, HostileAction.REPLACE, List.of(hiddenClick));
        OperationFactory<StepAccess> factory = () -> () -> {
            throw new IllegalStateException(wrapperName);
        };
        PostConstructionAssertions<StepAccess> assertions = operation ->
                assertSnapshotContents(operation, List.of());

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> wrapper.verify(hostile, factory, assertions));
        assertEquals(wrapperName, failure.getMessage());
    }

    @SafeVarargs
    private static void assertSnapshotContents(
            StepAccess operation, List<InventoryStep>... completeSnapshots) {
        assertTrue(java.util.Arrays.stream(completeSnapshots)
                .anyMatch(snapshot -> snapshot.equals(operation.steps())));
    }

    private static InventoryStep step(int slot) {
        return new InventoryStep(
                InventoryQuery.slot(slot), InventoryClick.quickMove(),
                InventoryPostcondition.TARGET_SLOT_EMPTY);
    }

    private static ScreenExpectation expectation(InventoryScreenSnapshot snapshot) {
        return ScreenExpectation.exact(
                snapshot.identity(), snapshot.identity().type(), snapshot.screen().title().get(),
                snapshot.slots().size(),
                List.of(InventoryQuery.slot(0).withIdentifier("minecraft:wheat")));
    }

    private static InventoryScreenSnapshot base() {
        return snapshot(1, 2, "minecraft:generic_9x3", "Chest", List.of(slot(0, WHEAT)));
    }

    private static InventoryScreenSnapshot revised(
            InventoryScreenSnapshot base, int state, long local, List<InventorySlot> slots) {
        return new InventoryScreenSnapshot(
                base.identity(), new ScreenRevision(state, local), base.screen(), slots,
                base.cursor(), base.selectedHotbar());
    }

    private static InventoryScreenSnapshot snapshot(
            long epoch, int containerId, String type, String title, List<InventorySlot> slots) {
        return new InventoryScreenSnapshot(
                new ScreenIdentity(epoch, containerId, type),
                new ScreenRevision(0, 0),
                new ScreenSnapshot(Observation.present(type), Observation.present(title)),
                slots,
                Observation.absent(),
                Observation.present(new HotbarSelection(0)));
    }

    private static InventorySlot slot(int slot, InventoryItem item) {
        return new InventorySlot(slot,
                item == null ? Observation.absent() : Observation.present(item),
                true, true, Optional.empty());
    }

    private static InventoryItem item(String id, int count, String name) {
        return new InventoryItem(
                new ItemSummary(ResourceIdentifier.parse(id), count),
                new ItemComponentSummary(Optional.of(name), Optional.of(name), List.of("safe lore")));
    }

    private static void assertReason(List<InventoryOutcome> outcomes, InventoryCancelReason reason) {
        assertEquals(1, outcomes.size());
        assertEquals(reason, outcomes.getFirst().reason().orElseThrow());
    }

    private static final class Fixture {
        private final ManualClock clock = new ManualClock();
        private final ClientTaskQueue queue = new ClientTaskQueue(clock);
        private final SafetyPort port = new SafetyPort();
        private final List<InventoryDiagnostic> diagnostics = new ArrayList<>();
        private final InventoryController controller = new InventoryController(
                queue, new InputController()::selectHotbar, port, diagnostics::add);
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

    private static final class SafetyPort implements InventoryPort {
        private Observation<InventoryScreenSnapshot> current = Observation.absent();
        private final Deque<Observation<InventoryScreenSnapshot>> scripted = new ArrayDeque<>();
        private Throwable observeFailure;
        private Throwable executeFailure;
        private Throwable closeFailure;
        private Runnable beforeAuthority = () -> { };
        private InventoryClickGuard lastGuard;
        private int executeCalls;
        private int clicks;
        private int releaseCalls;

        @Override
        public Observation<InventoryScreenSnapshot> observe(
                InventoryOperationToken token, ControlOwner owner) {
            throwIfPresent(observeFailure);
            return scripted.isEmpty() ? current : scripted.removeFirst();
        }

        @Override
        public InventoryExecutionResult executeGuardedClick(InventoryClickGuard guard) {
            throwIfPresent(executeFailure);
            executeCalls++;
            lastGuard = guard;
            beforeAuthority.run();
            if (!guard.authority().isActive()) {
                return InventoryExecutionResult.rejected(InventoryCancelReason.STALE_TOKEN);
            }
            clicks++;
            return InventoryExecutionResult.success();
        }

        @Override
        public void releaseOperation(InventoryOperationToken token, ControlOwner owner) {
            releaseCalls++;
        }

        @Override
        public Optional<InventoryCancelReason> closeScreen(ScreenIdentity expected) {
            throwIfPresent(closeFailure);
            if (!current.isPresent()) {
                return Optional.of(InventoryCancelReason.SCREEN_CLOSED);
            }
            return current.get().identity().equals(expected)
                    ? Optional.empty()
                    : Optional.of(InventoryCancelReason.SCREEN_CHANGED);
        }

        private static void throwIfPresent(Throwable failure) {
            if (failure instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            if (failure instanceof Error error) {
                throw error;
            }
        }
    }

    private static final class FailingScheduler implements InventoryTaskScheduler {
        private final int failingCall;
        private final IllegalStateException failure = new IllegalStateException("schedule failure");
        private int scheduleCalls;
        private int cancelCalls;
        private int pendingOwnTasks;

        private FailingScheduler(int failingCall) {
            this.failingCall = failingCall;
        }

        @Override
        public void schedule(TaskOwner owner, long delayNanos, Runnable task) {
            scheduleCalls++;
            if (scheduleCalls == failingCall) {
                throw failure;
            }
            pendingOwnTasks++;
        }

        @Override
        public void cancel(TaskOwner owner) {
            cancelCalls++;
            pendingOwnTasks = 0;
        }
    }

    private enum AccessStage {
        SIZE,
        GET,
        TO_ARRAY,
        ITERATOR
    }

    private enum HostileAction {
        REPLACE,
        RETURN_NULL,
        THROW
    }

    @FunctionalInterface
    private interface StepAccess {
        List<InventoryStep> steps();
    }

    @FunctionalInterface
    private interface OperationFactory<T> {
        T construct();
    }

    @FunctionalInterface
    private interface PostConstructionAssertions<T> {
        void verify(T operation);
    }

    @FunctionalInterface
    private interface ConstructionWrapper {
        void verify(
                HostileStepList hostile,
                OperationFactory<StepAccess> factory,
                PostConstructionAssertions<StepAccess> assertions);
    }

    private record ConstructionAttempt<T>(Optional<T> value, Optional<RuntimeException> rejection) {
        private ConstructionAttempt {
            java.util.Objects.requireNonNull(value, "value");
            java.util.Objects.requireNonNull(rejection, "rejection");
            if (value.isPresent() == rejection.isPresent()) {
                throw new IllegalArgumentException("exactly one construction result is required");
            }
        }

        private static <T> ConstructionAttempt<T> of(T value, RuntimeException rejection) {
            return new ConstructionAttempt<>(Optional.ofNullable(value), Optional.ofNullable(rejection));
        }

        private T requireSuccess() {
            return value.orElseThrow(() -> new AssertionError("construction was rejected", rejection.orElse(null)));
        }
    }

    private static final class HostileStepList extends AbstractList<InventoryStep> {
        private final List<InventoryStep> values;
        private final AccessStage stage;
        private final HostileAction action;
        private final List<InventoryStep> replacement;
        private boolean triggered;

        private HostileStepList(
                List<InventoryStep> initial,
                AccessStage stage,
                HostileAction action,
                List<InventoryStep> replacement) {
            this.values = new ArrayList<>(initial);
            this.stage = stage;
            this.action = action;
            this.replacement = replacement;
        }

        @Override
        public InventoryStep get(int index) {
            if (before(AccessStage.GET)) {
                return null;
            }
            return values.get(index);
        }

        @Override
        public int size() {
            if (before(AccessStage.SIZE)) {
                replaceContents(java.util.Collections.singletonList(null));
            }
            return values.size();
        }

        @Override
        public Iterator<InventoryStep> iterator() {
            if (before(AccessStage.ITERATOR)) {
                return null;
            }
            return super.iterator();
        }

        @Override
        public Object[] toArray() {
            if (before(AccessStage.TO_ARRAY)) {
                return null;
            }
            return super.toArray();
        }

        @Override
        public <T> T[] toArray(T[] array) {
            if (before(AccessStage.TO_ARRAY)) {
                return null;
            }
            return super.toArray(array);
        }

        private boolean before(AccessStage observed) {
            if (triggered || stage != observed) {
                return false;
            }
            triggered = true;
            switch (action) {
                case REPLACE -> replaceContents(replacement);
                case RETURN_NULL -> {
                    return true;
                }
                case THROW -> throw new IllegalStateException("hostile list access");
            }
            return false;
        }

        private boolean triggered() {
            return triggered;
        }

        private HostileAction action() {
            return action;
        }

        private void replaceContents(List<InventoryStep> newValues) {
            values.clear();
            values.addAll(newValues);
        }
    }
}
