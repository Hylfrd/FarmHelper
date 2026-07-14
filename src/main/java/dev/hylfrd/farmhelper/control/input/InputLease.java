package dev.hylfrd.farmhelper.control.input;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/** Idempotently releases only the claims acquired by this owner lease. */
public final class InputLease implements AutoCloseable {
    private final ControlOwner owner;
    private final Set<InputAction> actions;
    private final Optional<HotbarSelection> hotbarSelection;
    private final Runnable release;
    private final AtomicBoolean closed = new AtomicBoolean();

    InputLease(
            ControlOwner owner,
            Set<InputAction> actions,
            Optional<HotbarSelection> hotbarSelection,
            Runnable release) {
        this.owner = Objects.requireNonNull(owner, "owner");
        this.actions = actions.isEmpty()
                ? Set.of()
                : Collections.unmodifiableSet(EnumSet.copyOf(actions));
        this.hotbarSelection = Objects.requireNonNull(hotbarSelection, "hotbarSelection");
        this.release = Objects.requireNonNull(release, "release");
    }

    public ControlOwner owner() {
        return owner;
    }

    public Set<InputAction> actions() {
        return actions;
    }

    public Optional<HotbarSelection> hotbarSelection() {
        return hotbarSelection;
    }

    public boolean closed() {
        return closed.get();
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            release.run();
        }
    }
}
