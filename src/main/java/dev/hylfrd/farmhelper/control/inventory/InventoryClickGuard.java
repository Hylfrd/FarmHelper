package dev.hylfrd.farmhelper.control.inventory;

import dev.hylfrd.farmhelper.control.input.HotbarSelection;
import dev.hylfrd.farmhelper.runtime.snapshot.Observation;

import java.util.Objects;
import java.util.Optional;

/** Full precondition the client adapter must atomically re-read before its sole click write. */
public record InventoryClickGuard(
        ItemIdentity target,
        boolean slotActive,
        boolean mayPickup,
        Optional<HotbarSelection> hotbarSelection,
        Observation<InventoryItem> cursor,
        InventoryClick click) {
    public InventoryClickGuard {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(hotbarSelection, "hotbarSelection");
        Objects.requireNonNull(cursor, "cursor");
        Objects.requireNonNull(click, "click");
        if (cursor.isUnknown()) {
            throw new IllegalArgumentException("cursor must be present or absent");
        }
    }
}
