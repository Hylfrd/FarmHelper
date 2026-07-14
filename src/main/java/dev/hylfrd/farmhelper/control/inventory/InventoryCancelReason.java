package dev.hylfrd.farmhelper.control.inventory;

/** Closed fail-closed terminal and rejection reasons for inventory work. */
public enum InventoryCancelReason {
    REQUESTED,
    TIMEOUT,
    OWNER_CONFLICT,
    STALE_TOKEN,
    SCREEN_CLOSED,
    SCREEN_CHANGED,
    REVISION_CHANGED,
    SLOT_NOT_FOUND,
    SLOT_OUT_OF_BOUNDS,
    SLOT_CHANGED,
    SLOT_INACTIVE,
    PICKUP_DENIED,
    ITEM_CHANGED,
    COUNT_CHANGED,
    CURSOR_CHANGED,
    POSTCONDITION_FAILED,
    CLOSED_BY_OPERATION,
    ADAPTER_EXCEPTION,
    VERIFIER_EXCEPTION,
    CALLBACK_EXCEPTION,
    DIAGNOSTIC_EXCEPTION
}
