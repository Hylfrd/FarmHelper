package dev.hylfrd.farmhelper.control.inventory;

/** Answers only whether one exact inventory token/owner pair is active right now. */
@FunctionalInterface
public interface InventoryOperationAuthority {
    boolean isActive();
}
