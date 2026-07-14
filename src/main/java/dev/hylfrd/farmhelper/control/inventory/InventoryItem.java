package dev.hylfrd.farmhelper.control.inventory;

import dev.hylfrd.farmhelper.runtime.snapshot.ItemSummary;

import java.util.Objects;

/** Inventory item composed from the shared T4 item summary and component projection. */
public record InventoryItem(ItemSummary summary, ItemComponentSummary components) {
    public InventoryItem {
        Objects.requireNonNull(summary, "summary");
        Objects.requireNonNull(components, "components");
    }

    public int count() {
        return summary.count();
    }
}
