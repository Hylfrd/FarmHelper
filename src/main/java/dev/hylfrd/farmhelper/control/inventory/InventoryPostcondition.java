package dev.hylfrd.farmhelper.control.inventory;

import dev.hylfrd.farmhelper.runtime.snapshot.Observation;

/** Common verifier choices that avoid feature-specific callbacks. */
public enum InventoryPostcondition implements InventoryVerifier {
    REVISION_ADVANCED {
        @Override
        public boolean verify(InventoryScreenSnapshot before, InventoryScreenSnapshot after, ItemIdentity target) {
            return after.revision().advancedFrom(before.revision());
        }
    },
    TARGET_SLOT_CHANGED {
        @Override
        public boolean verify(InventoryScreenSnapshot before, InventoryScreenSnapshot after, ItemIdentity target) {
            return after.slot(target.menuSlot())
                    .map(slot -> !slot.item().equals(Observation.present(target.item())))
                    .orElse(true);
        }
    },
    TARGET_SLOT_EMPTY {
        @Override
        public boolean verify(InventoryScreenSnapshot before, InventoryScreenSnapshot after, ItemIdentity target) {
            return after.slot(target.menuSlot()).map(slot -> slot.item().isAbsent()).orElse(false);
        }
    },
    TARGET_COUNT_DECREASED {
        @Override
        public boolean verify(InventoryScreenSnapshot before, InventoryScreenSnapshot after, ItemIdentity target) {
            return after.slot(target.menuSlot()).map(slot -> slot.item().isAbsent()
                    || slot.item().get().count() < target.item().count()).orElse(false);
        }
    }
}
