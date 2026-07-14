package dev.hylfrd.farmhelper.client.control.inventory;

import dev.hylfrd.farmhelper.client.control.ClientInputController;
import dev.hylfrd.farmhelper.control.input.ControlOwner;
import dev.hylfrd.farmhelper.control.input.HotbarSelection;
import dev.hylfrd.farmhelper.control.input.InputLease;
import dev.hylfrd.farmhelper.control.inventory.InventoryHotbarPort;

import java.util.Objects;

/** Narrow inventory delegation to the existing T2 client input controller. */
public final class ClientInventoryHotbarPort implements InventoryHotbarPort {
    private final ClientInputController input;

    public ClientInventoryHotbarPort(ClientInputController input) {
        this.input = Objects.requireNonNull(input, "input");
    }

    @Override
    public InputLease select(ControlOwner owner, HotbarSelection selection) {
        return input.selectHotbar(owner, selection);
    }
}
