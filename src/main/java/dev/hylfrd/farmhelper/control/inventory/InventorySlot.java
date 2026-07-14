package dev.hylfrd.farmhelper.control.inventory;

import dev.hylfrd.farmhelper.control.input.HotbarSelection;
import dev.hylfrd.farmhelper.runtime.snapshot.Observation;

import java.util.Objects;
import java.util.Optional;

/** Immutable menu-slot projection, including click eligibility observed on the client thread. */
public record InventorySlot(
        int menuSlot,
        Observation<InventoryItem> item,
        boolean active,
        boolean mayPickup,
        Optional<HotbarSelection> hotbarSelection) {
    public InventorySlot {
        if (menuSlot < 0) {
            throw new IllegalArgumentException("menuSlot must not be negative");
        }
        Objects.requireNonNull(item, "item");
        Objects.requireNonNull(hotbarSelection, "hotbarSelection");
        if (item.isUnknown()) {
            throw new IllegalArgumentException("slot item must be present or absent");
        }
    }
}
