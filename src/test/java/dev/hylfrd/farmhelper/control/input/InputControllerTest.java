package dev.hylfrd.farmhelper.control.input;

import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InputControllerTest {
    private static final ControlOwner MACRO = new ControlOwner("macro");
    private static final ControlOwner FAILSAFE = new ControlOwner("failsafe");

    @Test
    void oneOwnerCannotReleaseAnotherOwnersActions() {
        InputController controller = new InputController();
        controller.hold(MACRO, InputAction.FORWARD, InputAction.SPRINT);

        controller.release(FAILSAFE, InputAction.FORWARD, InputAction.SPRINT);
        controller.release(FAILSAFE);

        assertEquals(Set.of(InputAction.FORWARD, InputAction.SPRINT), controller.snapshot().heldActions());
        assertEquals(MACRO, controller.snapshot().ownerOf(InputAction.FORWARD).orElseThrow());
    }

    @Test
    void closingLeaseIsIdempotentAndCannotReleaseTransferredClaim() {
        InputController controller = new InputController();
        InputLease first = controller.hold(MACRO, InputAction.JUMP);
        InputLease replacement = controller.hold(MACRO, InputAction.JUMP);

        first.close();
        first.close();

        assertTrue(first.closed());
        assertTrue(controller.snapshot().held(InputAction.JUMP));

        replacement.close();

        assertFalse(controller.snapshot().held(InputAction.JUMP));
    }

    @Test
    void replaceAtomicallyChangesOnlyTheRequestingOwnersClaims() {
        InputController controller = new InputController();
        controller.hold(MACRO, InputAction.FORWARD, InputAction.LEFT);
        controller.hold(FAILSAFE, InputAction.ATTACK);

        InputLease lease = controller.replace(MACRO, Set.of(InputAction.BACKWARD, InputAction.RIGHT));

        assertEquals(Set.of(InputAction.BACKWARD, InputAction.RIGHT),
                controller.snapshot().actionsOwnedBy(MACRO));
        assertEquals(Set.of(InputAction.ATTACK), controller.snapshot().actionsOwnedBy(FAILSAFE));

        lease.close();

        assertEquals(Set.of(), controller.snapshot().actionsOwnedBy(MACRO));
        assertTrue(controller.snapshot().held(InputAction.ATTACK));
    }

    @Test
    void conflictsAreRejectedWithoutPartialReplacement() {
        InputController controller = new InputController();
        controller.hold(MACRO, InputAction.FORWARD);
        controller.hold(FAILSAFE, InputAction.ATTACK);

        InputConflictException exception = assertThrows(InputConflictException.class,
                () -> controller.replace(MACRO, Set.of(InputAction.BACKWARD, InputAction.ATTACK)));

        assertEquals(MACRO, exception.requestedOwner());
        assertEquals(FAILSAFE, exception.currentOwner());
        assertEquals("ATTACK", exception.resource());
        assertEquals(Set.of(InputAction.FORWARD), controller.snapshot().actionsOwnedBy(MACRO));
        assertEquals(Set.of(InputAction.ATTACK), controller.snapshot().actionsOwnedBy(FAILSAFE));
    }

    @Test
    void hotbarSelectionHasExclusiveOwnerScopedLeasing() {
        InputController controller = new InputController();
        HotbarSelection slot = new HotbarSelection(4);
        InputLease lease = controller.selectHotbar(MACRO, slot);

        assertEquals(MACRO, controller.snapshot().hotbarOwner().orElseThrow());
        assertEquals(slot, controller.snapshot().hotbarSelection().orElseThrow());
        assertEquals(5, slot.displaySlot());
        assertEquals(slot, HotbarSelection.fromOneBased(5));
        assertThrows(InputConflictException.class,
                () -> controller.selectHotbar(FAILSAFE, new HotbarSelection(2)));

        controller.releaseHotbar(FAILSAFE);
        assertEquals(slot, controller.snapshot().hotbarSelection().orElseThrow());

        lease.close();
        assertTrue(controller.snapshot().hotbarSelection().isEmpty());
    }

    @Test
    void allInputActionsCanBeHeldAndGloballyReleasedForEveryReason() {
        for (ReleaseReason reason : ReleaseReason.values()) {
            InputController controller = new InputController();
            controller.hold(MACRO, EnumSet.allOf(InputAction.class), Optional.of(new HotbarSelection(8)));

            assertEquals(EnumSet.allOf(InputAction.class), controller.snapshot().heldActions());

            controller.releaseAll(reason);

            assertTrue(controller.snapshot().emptyState());
            assertEquals(reason, controller.snapshot().releaseReason().orElseThrow());
        }
    }

    @Test
    void snapshotsDefensivelyCopyOwnershipAndExposeUnmodifiableViews() {
        Map<InputAction, ControlOwner> mutable = new java.util.EnumMap<>(InputAction.class);
        mutable.put(InputAction.USE, MACRO);
        InputSnapshot snapshot = new InputSnapshot(
                mutable,
                Optional.of(MACRO),
                Optional.of(new HotbarSelection(0)),
                Optional.empty(),
                7L);

        mutable.clear();

        assertTrue(snapshot.held(InputAction.USE));
        assertThrows(UnsupportedOperationException.class,
                () -> snapshot.actionOwners().put(InputAction.ATTACK, FAILSAFE));
        assertThrows(UnsupportedOperationException.class,
                () -> snapshot.heldActions().add(InputAction.ATTACK));
        assertThrows(IllegalArgumentException.class,
                () -> new InputSnapshot(Map.of(), Optional.of(MACRO), Optional.empty(), Optional.empty(), 0L));
        assertThrows(IllegalArgumentException.class, () -> new HotbarSelection(9));
    }
}
