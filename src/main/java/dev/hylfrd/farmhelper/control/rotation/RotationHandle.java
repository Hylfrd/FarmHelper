package dev.hylfrd.farmhelper.control.rotation;

import dev.hylfrd.farmhelper.control.input.ControlOwner;

import java.util.Objects;

/** Lease-like handle that can only cancel the exact rotation it was created for. */
public final class RotationHandle {
    private final RotationController controller;
    private final ControlOwner owner;
    private final long leaseId;

    RotationHandle(RotationController controller, ControlOwner owner, long leaseId) {
        this.controller = Objects.requireNonNull(controller, "controller");
        this.owner = Objects.requireNonNull(owner, "owner");
        this.leaseId = leaseId;
    }

    public ControlOwner owner() {
        return owner;
    }

    public boolean cancel() {
        return cancel(RotationCancelReason.OWNER_CANCELLED);
    }

    public boolean cancel(RotationCancelReason reason) {
        return controller.cancel(owner, leaseId, Objects.requireNonNull(reason, "reason"));
    }
}
