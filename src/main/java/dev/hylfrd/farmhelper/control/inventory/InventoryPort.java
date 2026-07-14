package dev.hylfrd.farmhelper.control.inventory;

import dev.hylfrd.farmhelper.control.input.ControlOwner;
import dev.hylfrd.farmhelper.runtime.snapshot.Observation;

import java.util.Optional;

/** Client-thread adapter boundary; executeGuardedClick is the only inventory click write point. */
public interface InventoryPort {
    Observation<InventoryScreenSnapshot> observe(
            InventoryOperationToken token, ControlOwner owner);

    InventoryExecutionResult executeGuardedClick(InventoryClickGuard guard);

    /** Releases adapter-private guards belonging to this exact operation generation. */
    void releaseOperation(InventoryOperationToken token, ControlOwner owner);

    /** Returns empty on close success, otherwise the fail-closed rejection. */
    Optional<InventoryCancelReason> closeScreen(ScreenIdentity expected);
}
