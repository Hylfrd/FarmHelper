package dev.hylfrd.farmhelper.control.inventory;

import java.util.Objects;

/** Identity of one concrete Screen/menu lifetime, even if a container id is later reused. */
public record ScreenIdentity(long epoch, int containerId, String type) {
    public ScreenIdentity {
        if (epoch <= 0L) {
            throw new IllegalArgumentException("epoch must be positive");
        }
        if (containerId < 0) {
            throw new IllegalArgumentException("containerId must not be negative");
        }
        Objects.requireNonNull(type, "type");
        if (type.isBlank()) {
            throw new IllegalArgumentException("type must not be blank");
        }
    }
}
