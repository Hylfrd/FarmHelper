package dev.hylfrd.farmhelper.control.input;

import java.util.Collection;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Sole domain owner of automated input claims.
 *
 * <p>All conflict checks are atomic. Closing a lease can only release claims that still belong to
 * that exact lease, while owner-scoped release operations can never affect another owner.</p>
 */
public final class InputController {
    private final EnumMap<InputAction, ActionClaim> actionClaims = new EnumMap<>(InputAction.class);
    private final Consumer<InputSnapshot> listener;
    private HotbarClaim hotbarClaim;
    private ReleaseReason releaseReason;
    private long revision;
    private long nextLeaseId = 1L;

    public InputController() {
        this(snapshot -> { });
    }

    public InputController(Consumer<InputSnapshot> listener) {
        this.listener = Objects.requireNonNull(listener, "listener");
    }

    public synchronized InputSnapshot snapshot() {
        return createSnapshot();
    }

    public InputLease hold(ControlOwner owner, InputAction... actions) {
        Objects.requireNonNull(actions, "actions");
        return hold(owner, Set.of(actions));
    }

    public synchronized InputLease hold(ControlOwner owner, Collection<InputAction> actions) {
        return acquire(owner, copyActions(actions), Optional.empty(), false, false);
    }

    public synchronized InputLease hold(
            ControlOwner owner,
            Collection<InputAction> actions,
            Optional<HotbarSelection> hotbarSelection) {
        return acquire(owner, copyActions(actions), hotbarSelection, false, false);
    }

    public synchronized InputLease selectHotbar(ControlOwner owner, HotbarSelection selection) {
        return acquire(owner, EnumSet.noneOf(InputAction.class), Optional.of(selection), false, false);
    }

    public synchronized InputLease replace(ControlOwner owner, Collection<InputAction> actions) {
        return acquire(owner, copyActions(actions), Optional.empty(), true, false);
    }

    public synchronized InputLease replace(
            ControlOwner owner,
            Collection<InputAction> actions,
            Optional<HotbarSelection> hotbarSelection) {
        return acquire(owner, copyActions(actions), hotbarSelection, true, true);
    }

    public synchronized void release(ControlOwner owner, InputAction... actions) {
        Objects.requireNonNull(actions, "actions");
        release(owner, Set.of(actions));
    }

    public synchronized void release(ControlOwner owner, Collection<InputAction> actions) {
        Objects.requireNonNull(owner, "owner");
        EnumSet<InputAction> requested = copyActions(actions);
        InputSnapshot before = createSnapshot();
        for (InputAction action : requested) {
            ActionClaim claim = actionClaims.get(action);
            if (claim != null && claim.owner().equals(owner)) {
                actionClaims.remove(action);
            }
        }
        publishIfChanged(before);
    }

    public synchronized void releaseHotbar(ControlOwner owner) {
        Objects.requireNonNull(owner, "owner");
        InputSnapshot before = createSnapshot();
        if (hotbarClaim != null && hotbarClaim.owner().equals(owner)) {
            hotbarClaim = null;
        }
        publishIfChanged(before);
    }

    public synchronized void release(ControlOwner owner) {
        Objects.requireNonNull(owner, "owner");
        InputSnapshot before = createSnapshot();
        actionClaims.entrySet().removeIf(entry -> entry.getValue().owner().equals(owner));
        if (hotbarClaim != null && hotbarClaim.owner().equals(owner)) {
            hotbarClaim = null;
        }
        publishIfChanged(before);
    }

    public synchronized void releaseAll(ReleaseReason reason) {
        releaseAllInternal(Objects.requireNonNull(reason, "reason"), true);
    }

    private InputLease acquire(
            ControlOwner owner,
            EnumSet<InputAction> actions,
            Optional<HotbarSelection> selection,
            boolean replaceActions,
            boolean replaceHotbar) {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(selection, "selection");
        checkConflicts(owner, actions, selection);

        InputSnapshot before = createSnapshot();
        long leaseId = nextLeaseId++;
        if (replaceActions) {
            actionClaims.entrySet().removeIf(entry -> entry.getValue().owner().equals(owner));
        }
        if (replaceHotbar && hotbarClaim != null && hotbarClaim.owner().equals(owner)) {
            hotbarClaim = null;
        }
        for (InputAction action : actions) {
            actionClaims.put(action, new ActionClaim(owner, leaseId));
        }
        selection.ifPresent(value -> hotbarClaim = new HotbarClaim(owner, value, leaseId));
        releaseReason = null;
        publishIfChanged(before);

        Set<InputAction> leasedActions = Set.copyOf(actions);
        return new InputLease(owner, leasedActions, selection, () -> closeLease(owner, leaseId));
    }

    private void checkConflicts(
            ControlOwner owner,
            Set<InputAction> actions,
            Optional<HotbarSelection> selection) {
        for (InputAction action : actions) {
            ActionClaim claim = actionClaims.get(action);
            if (claim != null && !claim.owner().equals(owner)) {
                throw new InputConflictException(owner, claim.owner(), action.name());
            }
        }
        if (selection.isPresent() && hotbarClaim != null && !hotbarClaim.owner().equals(owner)) {
            throw new InputConflictException(owner, hotbarClaim.owner(), "HOTBAR");
        }
    }

    private synchronized void closeLease(ControlOwner owner, long leaseId) {
        InputSnapshot before = createSnapshot();
        Iterator<Map.Entry<InputAction, ActionClaim>> iterator = actionClaims.entrySet().iterator();
        while (iterator.hasNext()) {
            ActionClaim claim = iterator.next().getValue();
            if (claim.leaseId() == leaseId && claim.owner().equals(owner)) {
                iterator.remove();
            }
        }
        if (hotbarClaim != null
                && hotbarClaim.leaseId() == leaseId
                && hotbarClaim.owner().equals(owner)) {
            hotbarClaim = null;
        }
        publishIfChanged(before);
    }

    private void releaseAllInternal(ReleaseReason reason, boolean alwaysPublish) {
        InputSnapshot before = createSnapshot();
        actionClaims.clear();
        hotbarClaim = null;
        releaseReason = reason;
        if (alwaysPublish) {
            revision++;
            notifyListener(createSnapshot());
        } else {
            publishIfChanged(before);
        }
    }

    private void publishIfChanged(InputSnapshot before) {
        InputSnapshot after = createSnapshot();
        if (before.equals(after)) {
            return;
        }
        revision++;
        notifyListener(createSnapshot());
    }

    private void notifyListener(InputSnapshot snapshot) {
        try {
            listener.accept(snapshot);
        } catch (RuntimeException | Error exception) {
            actionClaims.clear();
            hotbarClaim = null;
            releaseReason = ReleaseReason.EXCEPTION;
            revision++;
            try {
                listener.accept(createSnapshot());
            } catch (RuntimeException | Error releaseFailure) {
                if (exception != releaseFailure) {
                    exception.addSuppressed(releaseFailure);
                }
            }
            throw exception;
        }
    }

    private InputSnapshot createSnapshot() {
        EnumMap<InputAction, ControlOwner> owners = new EnumMap<>(InputAction.class);
        actionClaims.forEach((action, claim) -> owners.put(action, claim.owner()));
        Optional<ControlOwner> currentHotbarOwner = hotbarClaim == null
                ? Optional.empty()
                : Optional.of(hotbarClaim.owner());
        Optional<HotbarSelection> currentSelection = hotbarClaim == null
                ? Optional.empty()
                : Optional.of(hotbarClaim.selection());
        return new InputSnapshot(
                owners,
                currentHotbarOwner,
                currentSelection,
                Optional.ofNullable(releaseReason),
                revision);
    }

    private static EnumSet<InputAction> copyActions(Collection<InputAction> actions) {
        Objects.requireNonNull(actions, "actions");
        EnumSet<InputAction> copy = EnumSet.noneOf(InputAction.class);
        for (InputAction action : actions) {
            copy.add(Objects.requireNonNull(action, "action"));
        }
        return copy;
    }

    private record ActionClaim(ControlOwner owner, long leaseId) { }

    private record HotbarClaim(ControlOwner owner, HotbarSelection selection, long leaseId) { }
}
