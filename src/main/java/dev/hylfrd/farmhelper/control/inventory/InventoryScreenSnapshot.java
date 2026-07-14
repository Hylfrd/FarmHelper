package dev.hylfrd.farmhelper.control.inventory;

import dev.hylfrd.farmhelper.control.input.HotbarSelection;
import dev.hylfrd.farmhelper.runtime.snapshot.Observation;
import dev.hylfrd.farmhelper.runtime.snapshot.ScreenSnapshot;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Complete immutable container observation using the shared T4 Screen snapshot. */
public record InventoryScreenSnapshot(
        ScreenIdentity identity,
        ScreenRevision revision,
        ScreenSnapshot screen,
        List<InventorySlot> slots,
        Observation<InventoryItem> cursor,
        Observation<HotbarSelection> selectedHotbar) {
    public InventoryScreenSnapshot {
        Objects.requireNonNull(identity, "identity");
        Objects.requireNonNull(revision, "revision");
        Objects.requireNonNull(screen, "screen");
        Objects.requireNonNull(slots, "slots");
        slots = List.copyOf(slots);
        for (int index = 0; index < slots.size(); index++) {
            if (slots.get(index).menuSlot() != index) {
                throw new IllegalArgumentException("slots must be contiguous menu order");
            }
        }
        Objects.requireNonNull(cursor, "cursor");
        Objects.requireNonNull(selectedHotbar, "selectedHotbar");
        if (cursor.isUnknown()) {
            throw new IllegalArgumentException("cursor must be present or absent");
        }
    }

    public Optional<InventorySlot> slot(int menuSlot) {
        return menuSlot >= 0 && menuSlot < slots.size()
                ? Optional.of(slots.get(menuSlot))
                : Optional.empty();
    }

    public List<InventorySlot> find(InventoryQuery query) {
        Objects.requireNonNull(query, "query");
        return slots.stream().filter(query::matches).toList();
    }
}
