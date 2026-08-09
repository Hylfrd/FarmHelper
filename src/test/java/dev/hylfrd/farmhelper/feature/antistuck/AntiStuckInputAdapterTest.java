package dev.hylfrd.farmhelper.feature.antistuck;

import dev.hylfrd.farmhelper.control.input.ControlOwner;
import dev.hylfrd.farmhelper.control.input.InputAction;
import dev.hylfrd.farmhelper.control.input.InputConflictException;
import dev.hylfrd.farmhelper.control.input.InputController;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AntiStuckInputAdapterTest {
    private static final ControlOwner OWNER = new ControlOwner("antistuck-adapter");
    private static final ControlOwner OTHER_OWNER = new ControlOwner("other-owner");
    private static final AntiStuckIdentity ID = new AntiStuckIdentity(OWNER, 7L, 11L);
    private static final AntiStuckIdentity NEXT_ID = new AntiStuckIdentity(OWNER, 7L, 11L, 2L);
    private static final AntiStuckIdentity OLD_LIFECYCLE_ID = new AntiStuckIdentity(
            OWNER, 6L, 11L, 9L);
    private static final AntiStuckIdentity OTHER_ID = new AntiStuckIdentity(
            OTHER_OWNER, 7L, 11L);

    @Test
    void admissionIsRequiredAndStaleIdentitiesFailClosed() {
        InputController input = new InputController();
        AntiStuckInputAdapter adapter = new AntiStuckInputAdapter(input, OWNER);

        assertFalse(adapter.apply(ID, advanced(fullIntent(Set.of(InputAction.FORWARD)))));
        assertFalse(adapter.apply(OTHER_ID, startedDecision()));
        assertTrue(input.snapshot().emptyState());

        assertTrue(adapter.apply(ID, startedDecision()));
        assertFalse(adapter.apply(ID, staleDecision()));
        assertFalse(adapter.apply(NEXT_ID, advanced(fullIntent(Set.of(InputAction.FORWARD)))));
        assertTrue(adapter.apply(ID, stopDecision()));
        assertFalse(adapter.apply(OLD_LIFECYCLE_ID, startedDecision()));
        assertTrue(input.snapshot().emptyState());
    }

    @Test
    void completeIntentUsesReplaceAndPreservesCompetingOwnerClaims() {
        InputController input = new InputController();
        input.hold(OTHER_OWNER, InputAction.USE, InputAction.SPRINT);
        AntiStuckInputAdapter adapter = new AntiStuckInputAdapter(input, OWNER);

        assertTrue(adapter.apply(ID, startedDecision()));
        assertTrue(adapter.apply(ID,
                advanced(fullIntent(Set.of(InputAction.FORWARD, InputAction.ATTACK)))));
        assertEquals(Set.of(InputAction.FORWARD, InputAction.ATTACK),
                input.snapshot().actionsOwnedBy(OWNER));
        assertEquals(Set.of(InputAction.USE, InputAction.SPRINT),
                input.snapshot().actionsOwnedBy(OTHER_OWNER));

        assertTrue(adapter.apply(ID, completeDecision()));
        assertTrue(input.snapshot().actionsOwnedBy(OWNER).isEmpty());
        assertEquals(Set.of(InputAction.USE, InputAction.SPRINT),
                input.snapshot().actionsOwnedBy(OTHER_OWNER));
    }

    @Test
    void conflictingReplacementIsAtomicAndDoesNotReleaseExistingClaims() {
        InputController input = new InputController();
        input.hold(OTHER_OWNER, InputAction.ATTACK);
        AntiStuckInputAdapter adapter = new AntiStuckInputAdapter(input, OWNER);

        adapter.apply(ID, startedDecision());
        adapter.apply(ID, advanced(fullIntent(Set.of(InputAction.FORWARD))));

        assertThrows(InputConflictException.class,
                () -> adapter.apply(ID,
                        advanced(fullIntent(Set.of(InputAction.FORWARD, InputAction.ATTACK)))));
        assertEquals(Set.of(InputAction.FORWARD), input.snapshot().actionsOwnedBy(OWNER));
        assertEquals(Set.of(InputAction.ATTACK), input.snapshot().actionsOwnedBy(OTHER_OWNER));
    }

    @Test
    void partialIntentUsesOwnerScopedReleaseAndPreservesOtherOwnerClaims() {
        InputController input = new InputController();
        input.hold(OTHER_OWNER, InputAction.ATTACK);
        AntiStuckInputAdapter adapter = new AntiStuckInputAdapter(input, OWNER);

        adapter.apply(ID, startedDecision());
        adapter.apply(ID, advanced(fullIntent(Set.of(InputAction.BACKWARD))));
        AntiStuckInputIntent partial = AntiStuckInputIntent.fromSets(
                Set.of(InputAction.FORWARD),
                Set.of(InputAction.BACKWARD, InputAction.ATTACK));

        assertTrue(adapter.apply(ID, advanced(partial)));
        assertEquals(Set.of(InputAction.FORWARD), input.snapshot().actionsOwnedBy(OWNER));
        assertEquals(Set.of(InputAction.ATTACK), input.snapshot().actionsOwnedBy(OTHER_OWNER));

        assertTrue(adapter.apply(ID, stopDecision()));
        assertTrue(input.snapshot().actionsOwnedBy(OWNER).isEmpty());
        assertEquals(Set.of(InputAction.ATTACK), input.snapshot().actionsOwnedBy(OTHER_OWNER));
    }

    @Test
    void staleStopCannotClearSameOwnerNewerRunClaims() {
        InputController input = new InputController();
        AntiStuckInputAdapter adapter = new AntiStuckInputAdapter(input, OWNER);

        assertTrue(adapter.apply(ID, startedDecision()));
        assertTrue(adapter.apply(ID, advanced(fullIntent(Set.of(InputAction.ATTACK)))));
        assertTrue(adapter.apply(ID, stopDecision()));

        assertTrue(adapter.apply(NEXT_ID, startedDecision()));
        assertTrue(adapter.apply(NEXT_ID, advanced(fullIntent(Set.of(InputAction.FORWARD)))));
        assertFalse(adapter.apply(ID, stopDecision()));
        assertFalse(adapter.apply(ID, advanced(fullIntent(Set.of(InputAction.ATTACK)))));
        assertEquals(Set.of(InputAction.FORWARD), input.snapshot().actionsOwnedBy(OWNER));
    }

    @Test
    void lateLeaseCleanupCannotClearReplacementClaims() {
        InputController input = new InputController();
        AntiStuckInputAdapter oldRun = new AntiStuckInputAdapter(input, OWNER);
        AntiStuckInputAdapter replacementRun = new AntiStuckInputAdapter(input, OWNER);

        assertTrue(oldRun.apply(ID, startedDecision()));
        assertTrue(oldRun.apply(ID, advanced(fullIntent(Set.of(InputAction.ATTACK)))));

        assertTrue(replacementRun.apply(NEXT_ID, startedDecision()));
        assertTrue(replacementRun.apply(NEXT_ID,
                advanced(fullIntent(Set.of(InputAction.FORWARD)))));
        assertTrue(oldRun.apply(ID, stopDecision()));

        assertEquals(Set.of(InputAction.FORWARD), input.snapshot().actionsOwnedBy(OWNER));
    }

    @Test
    void completeFailClosedAndRewarpCleanupReleaseCurrentRunOnly() {
        for (AntiStuckDecision terminal : new AntiStuckDecision[] {
                completeDecision(),
                failClosedDecision(),
                rewarpDecision()
        }) {
            InputController input = new InputController();
            input.hold(OTHER_OWNER, InputAction.USE);
            AntiStuckInputAdapter adapter = new AntiStuckInputAdapter(input, OWNER);

            assertTrue(adapter.apply(ID, startedDecision()));
            assertTrue(adapter.apply(ID, advanced(fullIntent(Set.of(InputAction.SPRINT)))));
            assertTrue(adapter.apply(ID, terminal));

            assertTrue(input.snapshot().actionsOwnedBy(OWNER).isEmpty());
            assertEquals(Set.of(InputAction.USE), input.snapshot().actionsOwnedBy(OTHER_OWNER));
        }
    }

    private static AntiStuckDecision startedDecision() {
        AntiStuckController controller = new AntiStuckController(
                (originInclusive, boundExclusive) -> originInclusive, 5);
        return controller.start(
                AntiStuckRecoveryRequest.withoutDirectionTarget(ID), 0L);
    }

    private static AntiStuckDecision advanced(AntiStuckInputIntent intent) {
        return new AntiStuckDecision(
                AntiStuckDecisionKind.ADVANCED,
                AntiStuckDecisionReason.TARGET_SELECTED,
                AntiStuckState.PRESS,
                intent,
                OptionalLong.empty(),
                OptionalInt.empty(),
                false,
                0,
                0);
    }

    private static AntiStuckDecision staleDecision() {
        return new AntiStuckDecision(
                AntiStuckDecisionKind.STALE_TICK,
                AntiStuckDecisionReason.STALE_TICK,
                AntiStuckState.PRESS,
                AntiStuckInputIntent.preserveAll(),
                OptionalLong.empty(),
                OptionalInt.empty(),
                false,
                0,
                0);
    }

    private static AntiStuckDecision stopDecision() {
        return terminal(
                AntiStuckDecisionKind.STOPPED,
                AntiStuckDecisionReason.STOPPED,
                AntiStuckState.NONE,
                false);
    }

    private static AntiStuckDecision completeDecision() {
        return terminal(
                AntiStuckDecisionKind.STOPPED,
                AntiStuckDecisionReason.COMPLETE,
                AntiStuckState.DISABLE,
                false);
    }

    private static AntiStuckDecision failClosedDecision() {
        return terminal(
                AntiStuckDecisionKind.FAIL_CLOSED,
                AntiStuckDecisionReason.UNKNOWN_EVIDENCE,
                AntiStuckState.DISABLE,
                false);
    }

    private static AntiStuckDecision rewarpDecision() {
        return terminal(
                AntiStuckDecisionKind.REWARP,
                AntiStuckDecisionReason.RETRY_LIMIT,
                AntiStuckState.DISABLE,
                true);
    }

    private static AntiStuckDecision terminal(
            AntiStuckDecisionKind kind,
            AntiStuckDecisionReason reason,
            AntiStuckState state,
            boolean rewarpRequested
    ) {
        return new AntiStuckDecision(
                kind,
                reason,
                state,
                fullIntent(Set.of()),
                OptionalLong.empty(),
                OptionalInt.empty(),
                rewarpRequested,
                0,
                0);
    }

    private static AntiStuckInputIntent fullIntent(Set<InputAction> held) {
        EnumSet<InputAction> released = EnumSet.allOf(InputAction.class);
        released.removeAll(held);
        return AntiStuckInputIntent.fromSets(held, released);
    }
}
