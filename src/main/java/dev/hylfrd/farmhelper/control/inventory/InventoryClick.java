package dev.hylfrd.farmhelper.control.inventory;

import dev.hylfrd.farmhelper.control.input.HotbarSelection;

import java.util.Objects;

/** Closed set of inventory clicks; raw button and mode integers never enter the domain layer. */
public sealed interface InventoryClick {
    enum PickupButton {
        PRIMARY,
        SECONDARY
    }

    record Pickup(PickupButton button) implements InventoryClick {
        public Pickup {
            Objects.requireNonNull(button, "button");
        }
    }

    record QuickMove() implements InventoryClick { }

    record SwapWithHotbar(HotbarSelection hotbar) implements InventoryClick {
        public SwapWithHotbar {
            Objects.requireNonNull(hotbar, "hotbar");
        }
    }

    static InventoryClick pickupPrimary() {
        return new Pickup(PickupButton.PRIMARY);
    }

    static InventoryClick pickupSecondary() {
        return new Pickup(PickupButton.SECONDARY);
    }

    static InventoryClick quickMove() {
        return new QuickMove();
    }

    static InventoryClick swapWithHotbar(HotbarSelection hotbar) {
        return new SwapWithHotbar(hotbar);
    }
}
