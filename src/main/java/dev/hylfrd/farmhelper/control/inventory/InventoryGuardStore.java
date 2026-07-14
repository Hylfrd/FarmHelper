package dev.hylfrd.farmhelper.control.inventory;

import dev.hylfrd.farmhelper.control.input.ControlOwner;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.UnaryOperator;

/**
 * Bounded, pure-Java lifecycle store for adapter-private item guards.
 * The type parameter is deliberately opaque to the inventory domain.
 */
public final class InventoryGuardStore<T> {
    private static final int MAX_GUARDS = 2_048;

    private final UnaryOperator<T> copier;
    private final Map<Integer, Entry<T>> entries = new LinkedHashMap<>();
    private ScreenIdentity screen;
    private ScreenRevision revision;

    public InventoryGuardStore(UnaryOperator<T> copier) {
        this.copier = Objects.requireNonNull(copier, "copier");
    }

    public void replaceObservation(
            ScreenIdentity observedScreen,
            ScreenRevision observedRevision,
            InventoryOperationToken token,
            ControlOwner owner,
            Map<Integer, T> observed) {
        Objects.requireNonNull(observedScreen, "observedScreen");
        Objects.requireNonNull(observedRevision, "observedRevision");
        Objects.requireNonNull(token, "token");
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(observed, "observed");
        if (observed.size() > MAX_GUARDS) {
            clear();
            throw new IllegalArgumentException("too many inventory item guards");
        }
        entries.clear();
        screen = observedScreen;
        revision = observedRevision;
        observed.forEach((slot, value) -> {
            if (slot == null || slot < 0) {
                throw new IllegalArgumentException("guard slot must not be negative");
            }
            T copied = Objects.requireNonNull(copier.apply(Objects.requireNonNull(value, "guard value")),
                    "copied guard value");
            entries.put(slot, new Entry<>(copied, token, owner));
        });
    }

    public Optional<T> claim(
            ScreenIdentity expectedScreen,
            ScreenRevision expectedRevision,
            int slot,
            InventoryOperationToken token,
            ControlOwner owner) {
        Objects.requireNonNull(expectedScreen, "expectedScreen");
        Objects.requireNonNull(expectedRevision, "expectedRevision");
        Objects.requireNonNull(token, "token");
        Objects.requireNonNull(owner, "owner");
        if (!expectedScreen.equals(screen) || !expectedRevision.equals(revision)) {
            return Optional.empty();
        }
        Entry<T> entry = entries.get(slot);
        if (entry == null) {
            return Optional.empty();
        }
        if (!entry.token.equals(token) || !entry.owner.equals(owner)) {
            return Optional.empty();
        }
        return Optional.of(entry.value);
    }

    public void retainOnly(ScreenIdentity currentScreen, ScreenRevision currentRevision) {
        if (!Objects.equals(screen, currentScreen) || !Objects.equals(revision, currentRevision)) {
            clear();
        }
    }

    public void clearOperation(InventoryOperationToken token, ControlOwner owner) {
        Objects.requireNonNull(token, "token");
        Objects.requireNonNull(owner, "owner");
        entries.entrySet().removeIf(entry -> token.equals(entry.getValue().token)
                && owner.equals(entry.getValue().owner));
    }

    public void clear() {
        entries.clear();
        screen = null;
        revision = null;
    }

    int size() {
        return entries.size();
    }

    private static final class Entry<T> {
        private final T value;
        private final InventoryOperationToken token;
        private final ControlOwner owner;

        private Entry(T value, InventoryOperationToken token, ControlOwner owner) {
            this.value = value;
            this.token = token;
            this.owner = owner;
        }
    }
}
