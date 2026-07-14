package dev.hylfrd.farmhelper.control.inventory;

import dev.hylfrd.farmhelper.runtime.snapshot.Observation;

import java.util.Optional;

/** Client-thread adapter boundary; executeGuardedClick is the only inventory click write point. */
public interface InventoryPort {
    Observation<InventoryScreenSnapshot> observe();

    InventoryExecutionResult executeGuardedClick(InventoryClickGuard guard);

    /** Returns empty on close success, otherwise the fail-closed rejection. */
    Optional<InventoryCancelReason> closeScreen(ScreenIdentity expected);
}
