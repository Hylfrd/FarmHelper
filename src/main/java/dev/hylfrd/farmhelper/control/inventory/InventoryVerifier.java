package dev.hylfrd.farmhelper.control.inventory;

/** Verifies one click's postcondition against a later, revision-advanced observation. */
@FunctionalInterface
public interface InventoryVerifier {
    boolean verify(
            InventoryScreenSnapshot before,
            InventoryScreenSnapshot after,
            ItemIdentity target,
            InventoryClick click);
}
