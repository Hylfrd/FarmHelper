package dev.hylfrd.farmhelper.control.input;

import java.util.Objects;

/** Raised when one owner attempts to acquire an input held by another owner. */
public final class InputConflictException extends IllegalStateException {
    private final ControlOwner requestedOwner;
    private final ControlOwner currentOwner;
    private final String resource;

    InputConflictException(ControlOwner requestedOwner, ControlOwner currentOwner, String resource) {
        super("Input " + resource + " is owned by " + currentOwner.id()
                + ", not " + requestedOwner.id());
        this.requestedOwner = Objects.requireNonNull(requestedOwner, "requestedOwner");
        this.currentOwner = Objects.requireNonNull(currentOwner, "currentOwner");
        this.resource = Objects.requireNonNull(resource, "resource");
    }

    public ControlOwner requestedOwner() {
        return requestedOwner;
    }

    public ControlOwner currentOwner() {
        return currentOwner;
    }

    public String resource() {
        return resource;
    }
}
