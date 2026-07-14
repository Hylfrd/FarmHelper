package dev.hylfrd.farmhelper.control.inventory;

import dev.hylfrd.farmhelper.control.input.ControlOwner;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InventoryGuardStoreTest {
    private static final ScreenIdentity SCREEN =
            new ScreenIdentity(7, 2, "minecraft:generic_9x3");
    private static final ScreenRevision REVISION = new ScreenRevision(3, 4);
    private static final ControlOwner OWNER = new ControlOwner("guard-owner");

    @Test
    void storesAnObservedCopyAndBindsItToOneExactTokenOwnerPair() {
        InventoryGuardStore<StringBuilder> store =
                new InventoryGuardStore<>(value -> new StringBuilder(value.toString()));
        StringBuilder observed = new StringBuilder("observed");
        InventoryOperationToken token = new InventoryOperationToken(11);
        store.replaceObservation(SCREEN, REVISION, token, OWNER, Map.of(5, observed));
        observed.replace(0, observed.length(), "recaptured-current");

        StringBuilder guard = store.claim(SCREEN, REVISION, 5, token, OWNER).orElseThrow();

        assertEquals("observed", guard.toString());
        assertTrue(store.claim(SCREEN, REVISION, 5,
                new InventoryOperationToken(12), OWNER).isEmpty());
        assertTrue(store.claim(SCREEN, REVISION, 5, token,
                new ControlOwner("different-owner")).isEmpty());
    }

    @Test
    void screenRevisionReplacementAndOperationReleaseBoundTheLifecycle() {
        InventoryGuardStore<String> store = new InventoryGuardStore<>(String::new);
        InventoryOperationToken old = new InventoryOperationToken(21);
        store.replaceObservation(SCREEN, REVISION, old, OWNER, Map.of(0, "old"));
        assertEquals("old", store.claim(SCREEN, REVISION, 0, old, OWNER).orElseThrow());

        ScreenRevision replacement = new ScreenRevision(3, 5);
        InventoryOperationToken current = new InventoryOperationToken(22);
        store.replaceObservation(SCREEN, replacement, current, OWNER, Map.of(0, "new"));
        assertTrue(store.claim(SCREEN, REVISION, 0, old, OWNER).isEmpty());
        assertEquals("new", store.claim(SCREEN, replacement, 0,
                current, OWNER).orElseThrow());

        store.clearOperation(current, OWNER);
        assertEquals(0, store.size());

        store.replaceObservation(SCREEN, replacement, current, OWNER, Map.of(0, "again"));
        store.retainOnly(new ScreenIdentity(8, 2, SCREEN.type()), replacement);
        assertEquals(0, store.size());
    }

    @Test
    void observedGuardDoesNotAcceptRecapturedDamageEnchantOrCustomModelChanges() {
        InventoryGuardStore<String> store = new InventoryGuardStore<>(String::new);
        InventoryOperationToken token = new InventoryOperationToken(31);
        String observed = "item|damage=1|enchant=none|model=0";
        store.replaceObservation(SCREEN, REVISION, token, OWNER, Map.of(0, observed));
        String expected = store.claim(SCREEN, REVISION, 0, token, OWNER).orElseThrow();

        assertNotEquals(expected, "item|damage=2|enchant=none|model=0");
        assertNotEquals(expected, "item|damage=1|enchant=fortune|model=0");
        assertNotEquals(expected, "item|damage=1|enchant=none|model=7");
    }
}
