package dev.hylfrd.farmhelper.control.inventory;

/** Opaque generation token; controllers reject stale tokens instead of touching newer work. */
public record InventoryOperationToken(long value) {
    public InventoryOperationToken {
        if (value <= 0L) {
            throw new IllegalArgumentException("token must be positive");
        }
    }
}
