package dev.hylfrd.farmhelper.control.inventory;

import dev.hylfrd.farmhelper.control.input.ControlOwner;
import dev.hylfrd.farmhelper.control.input.HotbarSelection;
import dev.hylfrd.farmhelper.control.input.InputLease;

/** Narrow bridge to the existing T2 hotbar owner/lease implementation. */
@FunctionalInterface
public interface InventoryHotbarPort {
    InputLease select(ControlOwner owner, HotbarSelection selection);
}
