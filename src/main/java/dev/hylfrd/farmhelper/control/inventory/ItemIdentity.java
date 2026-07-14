package dev.hylfrd.farmhelper.control.inventory;

import java.util.Objects;

/** Item identity scoped to one screen revision and menu slot. */
public record ItemIdentity(
        ScreenIdentity screen,
        ScreenRevision revision,
        int menuSlot,
        InventoryItem item) {
    public ItemIdentity {
        Objects.requireNonNull(screen, "screen");
        Objects.requireNonNull(revision, "revision");
        if (menuSlot < 0) {
            throw new IllegalArgumentException("menuSlot must not be negative");
        }
        Objects.requireNonNull(item, "item");
    }
}
