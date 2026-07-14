package dev.hylfrd.farmhelper.control.inventory;

import java.util.Objects;
import java.util.Optional;

/** Result returned by an atomic guarded adapter call. */
public record InventoryExecutionResult(boolean executed, Optional<InventoryCancelReason> rejection) {
    public InventoryExecutionResult {
        Objects.requireNonNull(rejection, "rejection");
        if (executed == rejection.isPresent()) {
            throw new IllegalArgumentException("executed and rejection must be exclusive");
        }
    }

    public static InventoryExecutionResult success() {
        return new InventoryExecutionResult(true, Optional.empty());
    }

    public static InventoryExecutionResult rejected(InventoryCancelReason reason) {
        return new InventoryExecutionResult(false, Optional.of(Objects.requireNonNull(reason, "reason")));
    }
}
