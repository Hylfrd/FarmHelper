package dev.hylfrd.farmhelper.control.inventory;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Immutable initial container constraint required by every click operation. */
public record ScreenExpectation(
        Optional<ScreenIdentity> exactIdentity,
        String exactType,
        String exactTitle,
        int minimumSlotCount,
        List<InventoryQuery> sentinels) {
    public ScreenExpectation {
        Objects.requireNonNull(exactIdentity, "exactIdentity");
        Objects.requireNonNull(exactType, "exactType");
        Objects.requireNonNull(exactTitle, "exactTitle");
        if (exactType.isBlank()) {
            throw new IllegalArgumentException("exactType must not be blank");
        }
        if (minimumSlotCount < 0) {
            throw new IllegalArgumentException("minimumSlotCount must not be negative");
        }
        Objects.requireNonNull(sentinels, "sentinels");
        sentinels = List.copyOf(sentinels);
        sentinels.forEach(sentinel -> Objects.requireNonNull(sentinel, "sentinel"));
    }

    public static ScreenExpectation exact(
            ScreenIdentity identity, String type, String title, int minimumSlotCount,
            List<InventoryQuery> sentinels) {
        return new ScreenExpectation(Optional.of(identity), type, title, minimumSlotCount, sentinels);
    }

    public Optional<InventoryCancelReason> rejection(InventoryScreenSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        if (exactIdentity.isPresent() && !exactIdentity.orElseThrow().equals(snapshot.identity())) {
            return Optional.of(InventoryCancelReason.SCREEN_IDENTITY_MISMATCH);
        }
        if (!snapshot.screen().type().isPresent()
                || !exactType.equals(snapshot.screen().type().get())) {
            return Optional.of(InventoryCancelReason.SCREEN_TYPE_MISMATCH);
        }
        if (!snapshot.screen().title().isPresent()
                || !exactTitle.equals(snapshot.screen().title().get())) {
            return Optional.of(InventoryCancelReason.SCREEN_TITLE_MISMATCH);
        }
        if (snapshot.slots().size() < minimumSlotCount) {
            return Optional.of(InventoryCancelReason.SCREEN_SLOT_COUNT_MISMATCH);
        }
        if (sentinels.stream().anyMatch(query -> snapshot.find(query).isEmpty())) {
            return Optional.of(InventoryCancelReason.SCREEN_SENTINEL_MISSING);
        }
        return Optional.empty();
    }
}
