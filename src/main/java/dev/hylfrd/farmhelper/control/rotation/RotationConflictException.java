package dev.hylfrd.farmhelper.control.rotation;

import dev.hylfrd.farmhelper.control.input.ControlOwner;

/** Raised when a different input owner attempts to replace an active rotation. */
public final class RotationConflictException extends IllegalStateException {
    private final ControlOwner requestedOwner;
    private final ControlOwner currentOwner;

    RotationConflictException(ControlOwner requestedOwner, ControlOwner currentOwner) {
        super("Rotation owner " + requestedOwner.id()
                + " conflicts with active owner " + currentOwner.id());
        this.requestedOwner = requestedOwner;
        this.currentOwner = currentOwner;
    }

    public ControlOwner requestedOwner() {
        return requestedOwner;
    }

    public ControlOwner currentOwner() {
        return currentOwner;
    }
}
