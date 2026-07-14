package dev.hylfrd.farmhelper.control.input;

import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Immutable view of all managed input ownership at one revision. */
public record InputSnapshot(
        Map<InputAction, ControlOwner> actionOwners,
        Optional<ControlOwner> hotbarOwner,
        Optional<HotbarSelection> hotbarSelection,
        Optional<ReleaseReason> releaseReason,
        long revision) {
    public InputSnapshot {
        Objects.requireNonNull(actionOwners, "actionOwners");
        Objects.requireNonNull(hotbarOwner, "hotbarOwner");
        Objects.requireNonNull(hotbarSelection, "hotbarSelection");
        Objects.requireNonNull(releaseReason, "releaseReason");
        if (revision < 0L) {
            throw new IllegalArgumentException("Revision must not be negative");
        }
        if (hotbarOwner.isPresent() != hotbarSelection.isPresent()) {
            throw new IllegalArgumentException("Hotbar owner and selection must be present together");
        }

        EnumMap<InputAction, ControlOwner> copiedOwners = new EnumMap<>(InputAction.class);
        actionOwners.forEach((action, owner) -> copiedOwners.put(
                Objects.requireNonNull(action, "action"),
                Objects.requireNonNull(owner, "owner")));
        actionOwners = Collections.unmodifiableMap(copiedOwners);
    }

    public static InputSnapshot empty() {
        return new InputSnapshot(Map.of(), Optional.empty(), Optional.empty(), Optional.empty(), 0L);
    }

    public boolean held(InputAction action) {
        return actionOwners.containsKey(Objects.requireNonNull(action, "action"));
    }

    public Optional<ControlOwner> ownerOf(InputAction action) {
        return Optional.ofNullable(actionOwners.get(Objects.requireNonNull(action, "action")));
    }

    public Set<InputAction> heldActions() {
        if (actionOwners.isEmpty()) {
            return Set.of();
        }
        return Collections.unmodifiableSet(EnumSet.copyOf(actionOwners.keySet()));
    }

    public Set<InputAction> actionsOwnedBy(ControlOwner owner) {
        Objects.requireNonNull(owner, "owner");
        EnumSet<InputAction> owned = EnumSet.noneOf(InputAction.class);
        actionOwners.forEach((action, actionOwner) -> {
            if (actionOwner.equals(owner)) {
                owned.add(action);
            }
        });
        return Collections.unmodifiableSet(owned);
    }

    public boolean emptyState() {
        return actionOwners.isEmpty() && hotbarOwner.isEmpty();
    }
}
