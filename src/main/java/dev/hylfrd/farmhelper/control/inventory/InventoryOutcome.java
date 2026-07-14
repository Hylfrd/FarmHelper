package dev.hylfrd.farmhelper.control.inventory;

import java.util.Objects;
import java.util.Optional;

/** Terminal operation result delivered once after resources have been released. */
public record InventoryOutcome(
        InventoryOperationToken token,
        Status status,
        Optional<InventoryCancelReason> reason,
        int completedSteps) {
    public enum Status {
        COMPLETED,
        CANCELLED
    }

    public InventoryOutcome {
        Objects.requireNonNull(token, "token");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(reason, "reason");
        if ((status == Status.COMPLETED) != reason.isEmpty()) {
            throw new IllegalArgumentException("only cancelled outcomes have a reason");
        }
        if (completedSteps < 0) {
            throw new IllegalArgumentException("completedSteps must not be negative");
        }
    }

    public static InventoryOutcome completed(InventoryOperationToken token, int completedSteps) {
        return new InventoryOutcome(token, Status.COMPLETED, Optional.empty(), completedSteps);
    }

    public static InventoryOutcome cancelled(
            InventoryOperationToken token, InventoryCancelReason reason, int completedSteps) {
        return new InventoryOutcome(token, Status.CANCELLED, Optional.of(reason), completedSteps);
    }
}
