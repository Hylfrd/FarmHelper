package dev.hylfrd.farmhelper.control.input;

/** A zero-based Minecraft hotbar slot. */
public record HotbarSelection(int slot) {
    public static final int SLOT_COUNT = 9;

    public HotbarSelection {
        if (slot < 0 || slot >= SLOT_COUNT) {
            throw new IllegalArgumentException("Hotbar slot must be between 0 and 8: " + slot);
        }
    }

    public static HotbarSelection fromOneBased(int slot) {
        return new HotbarSelection(slot - 1);
    }

    public int displaySlot() {
        return slot + 1;
    }
}
