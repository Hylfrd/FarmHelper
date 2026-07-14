package dev.hylfrd.farmhelper.control.inventory;

/** Common verifier choices that avoid feature-specific callbacks. */
public enum InventoryPostcondition implements InventoryVerifier {
    TARGET_SLOT_CHANGED {
        @Override
        public boolean verify(
                InventoryScreenSnapshot before,
                InventoryScreenSnapshot after,
                ItemIdentity target,
                InventoryClick click) {
            return after.slot(target.menuSlot())
                    .map(slot -> {
                        if (slot.item().isAbsent()) {
                            return true;
                        }
                        InventoryItem actual = slot.item().get();
                        return switch (click) {
                            case InventoryClick.Pickup ignored ->
                                    sameIdentity(actual, target.item())
                                            && actual.count() < target.item().count();
                            case InventoryClick.QuickMove ignored ->
                                    sameIdentity(actual, target.item())
                                            && actual.count() < target.item().count();
                            case InventoryClick.SwapWithHotbar ignored ->
                                    !actual.equals(target.item());
                        };
                    })
                    .orElse(false);
        }
    },
    TARGET_SLOT_EMPTY {
        @Override
        public boolean verify(
                InventoryScreenSnapshot before,
                InventoryScreenSnapshot after,
                ItemIdentity target,
                InventoryClick click) {
            return after.slot(target.menuSlot()).map(slot -> slot.item().isAbsent()).orElse(false);
        }
    },
    TARGET_COUNT_DECREASED {
        @Override
        public boolean verify(
                InventoryScreenSnapshot before,
                InventoryScreenSnapshot after,
                ItemIdentity target,
                InventoryClick click) {
            if (click instanceof InventoryClick.SwapWithHotbar) {
                return false;
            }
            return after.slot(target.menuSlot()).map(slot -> {
                if (slot.item().isAbsent()) {
                    return true;
                }
                InventoryItem actual = slot.item().get();
                return sameIdentity(actual, target.item())
                        && actual.count() < target.item().count();
            }).orElse(false);
        }
    };

    private static boolean sameIdentity(InventoryItem actual, InventoryItem expected) {
        return actual.summary().identifier().equals(expected.summary().identifier())
                && actual.components().equals(expected.components());
    }
}
