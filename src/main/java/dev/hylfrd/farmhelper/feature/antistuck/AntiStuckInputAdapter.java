package dev.hylfrd.farmhelper.feature.antistuck;

import dev.hylfrd.farmhelper.control.input.ControlOwner;
import dev.hylfrd.farmhelper.control.input.InputController;
import dev.hylfrd.farmhelper.control.input.InputLease;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Applies immutable AntiStuck input decisions to one owner-scoped input controller.
 *
 * <p>A run must be admitted by its STARTED decision. Later decisions must use that exact run
 * identity, and a replacement must be a strictly newer revision or lifecycle. Leases are kept
 * until replacement or terminal cleanup so a late lease close cannot remove a newer claim.</p>
 *
 * <p>This adapter has no client-state or tick knowledge; it only translates pure decisions into
 * {@link InputController} arbitration.</p>
 */
public final class AntiStuckInputAdapter {
    private final InputController input;
    private final ControlOwner owner;
    private final List<InputLease> leases = new ArrayList<>();
    private AntiStuckIdentity lastAcceptedIdentity;
    private boolean closed;

    public AntiStuckInputAdapter(InputController input, ControlOwner owner) {
        this.input = Objects.requireNonNull(input, "input");
        this.owner = Objects.requireNonNull(owner, "owner");
    }

    /**
     * Applies a decision, returning false when its identity is stale or the run has not been
     * admitted. Input conflicts are propagated without changing the existing owner claims.
     */
    public synchronized boolean apply(
            AntiStuckIdentity identity,
            AntiStuckDecision decision
    ) {
        Objects.requireNonNull(identity, "identity");
        Objects.requireNonNull(decision, "decision");
        if (!owner.equals(identity.owner())) {
            return false;
        }
        if (decision.kind() == AntiStuckDecisionKind.STALE_REQUEST
                || decision.kind() == AntiStuckDecisionKind.STALE_TICK) {
            return false;
        }

        if (decision.kind() == AntiStuckDecisionKind.STARTED) {
            if (!acceptStart(identity)) {
                return false;
            }
        } else if (closed
                || lastAcceptedIdentity == null
                || !lastAcceptedIdentity.equals(identity)) {
            return false;
        }

        if (isTerminal(decision)) {
            closeLeases();
            closed = true;
            return true;
        }

        applyIntent(decision.inputIntent());
        if (decision.kind() == AntiStuckDecisionKind.STARTED) {
            lastAcceptedIdentity = identity;
            closed = false;
        }
        return true;
    }

    private boolean acceptStart(AntiStuckIdentity identity) {
        if (lastAcceptedIdentity == null) {
            return true;
        }
        if (!closed) {
            return false;
        }
        if (lastAcceptedIdentity.sameLifecycle(identity)) {
            return identity.runRevision() > lastAcceptedIdentity.runRevision();
        }
        return isNewerLifecycle(lastAcceptedIdentity, identity);
    }

    private void applyIntent(AntiStuckInputIntent intent) {
        if (intent.preservedActions().isEmpty()) {
            InputLease next = input.replace(owner, intent.heldActions());
            List<InputLease> previous = List.copyOf(leases);
            leases.clear();
            leases.add(next);
            close(previous);
            return;
        }

        if (!intent.heldActions().isEmpty()) {
            leases.add(input.hold(owner, intent.heldActions()));
        }
        if (!intent.releasedActions().isEmpty()) {
            input.release(owner, intent.releasedActions());
        }
    }

    private void closeLeases() {
        List<InputLease> current = List.copyOf(leases);
        leases.clear();
        close(current);
    }

    private static void close(List<InputLease> leases) {
        for (InputLease lease : leases) {
            lease.close();
        }
    }

    private static boolean isTerminal(AntiStuckDecision decision) {
        return switch (decision.kind()) {
            case STOPPED, REWARP, FAIL_CLOSED, TERMINAL -> true;
            default -> false;
        };
    }

    private static boolean isNewerLifecycle(
            AntiStuckIdentity current,
            AntiStuckIdentity candidate
    ) {
        return candidate.macroGeneration() > current.macroGeneration()
                || (candidate.macroGeneration() == current.macroGeneration()
                && candidate.worldEpoch() > current.worldEpoch());
    }
}
