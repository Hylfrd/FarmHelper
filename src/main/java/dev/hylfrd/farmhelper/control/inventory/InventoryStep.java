package dev.hylfrd.farmhelper.control.inventory;

import java.util.Objects;

/** One query-bound guarded click and its later postcondition. */
public record InventoryStep(InventoryQuery query, InventoryClick click, InventoryVerifier verifier) {
    public InventoryStep {
        Objects.requireNonNull(query, "query");
        Objects.requireNonNull(click, "click");
        Objects.requireNonNull(verifier, "verifier");
    }
}
