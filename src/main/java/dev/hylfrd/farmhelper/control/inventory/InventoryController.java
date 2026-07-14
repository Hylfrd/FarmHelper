package dev.hylfrd.farmhelper.control.inventory;

import dev.hylfrd.farmhelper.control.input.InputConflictException;
import dev.hylfrd.farmhelper.control.input.InputLease;
import dev.hylfrd.farmhelper.runtime.snapshot.Observation;
import dev.hylfrd.farmhelper.runtime.time.ClientTaskQueue;
import dev.hylfrd.farmhelper.runtime.time.TaskOwner;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Threadless, queue-advanced inventory transaction coordinator.
 *
 * <p>Every step observes afresh, delegates the final atomic re-read and write to
 * {@link InventoryPort#executeGuardedClick(InventoryClickGuard)}, then verifies only on a later
 * queue advance. No executor, timer, or physical hotbar restoration is created here.</p>
 */
public final class InventoryController {
    private final ClientTaskQueue queue;
    private final InventoryHotbarPort hotbar;
    private final InventoryPort port;
    private final Consumer<InventoryDiagnostic> diagnostics;
    private long nextToken = 1L;
    private ActiveOperation active;

    public InventoryController(
            ClientTaskQueue queue,
            InventoryHotbarPort hotbar,
            InventoryPort port,
            Consumer<InventoryDiagnostic> diagnostics) {
        this.queue = Objects.requireNonNull(queue, "queue");
        this.hotbar = Objects.requireNonNull(hotbar, "hotbar");
        this.port = Objects.requireNonNull(port, "port");
        this.diagnostics = Objects.requireNonNull(diagnostics, "diagnostics");
    }

    /** Starts without preempting an existing operation or an existing T2 hotbar owner. */
    public synchronized InventoryOperationToken start(
            InventoryOperation operation, Consumer<InventoryOutcome> callback) {
        Objects.requireNonNull(operation, "operation");
        Objects.requireNonNull(callback, "callback");
        InventoryOperationToken token = newToken();
        if (active != null) {
            completeRejectedStart(token, InventoryCancelReason.OWNER_CONFLICT, callback);
            return token;
        }

        ActiveOperation created = new ActiveOperation(
                token,
                operation,
                callback,
                new TaskOwner("inventory-" + token.value()));
        active = created;
        try {
            if (operation.hotbarSelection().isPresent()) {
                created.hotbarLease = hotbar.select(operation.owner(), operation.hotbarSelection().orElseThrow());
            }
        } catch (InputConflictException exception) {
            cancelActive(created, InventoryCancelReason.OWNER_CONFLICT,
                    "hotbar is owned by another control owner", exception);
            return token;
        } catch (RuntimeException | Error exception) {
            cancelActive(created, InventoryCancelReason.ADAPTER_EXCEPTION,
                    "hotbar adapter failed", exception);
            return token;
        }

        queue.schedule(created.taskOwner, operation.timeoutNanos(),
                () -> timeout(created.token));
        queue.schedule(created.taskOwner, 0L, () -> executeNextStep(created.token));
        return token;
    }

    /** Cancels only if the token still denotes the active generation. */
    public synchronized boolean cancel(InventoryOperationToken token) {
        Objects.requireNonNull(token, "token");
        if (!isActive(token)) {
            emitStaleToken(token, "cancel ignored for a stale token");
            return false;
        }
        cancelActive(active, InventoryCancelReason.REQUESTED, "operation cancelled", null);
        return true;
    }

    /** Atomically closes only the screen identity currently bound to this operation. */
    public synchronized boolean closeScreen(InventoryOperationToken token) {
        Objects.requireNonNull(token, "token");
        if (!isActive(token)) {
            emitStaleToken(token, "close ignored for a stale token");
            return false;
        }
        ActiveOperation current = active;
        ScreenIdentity expected = current.boundScreen;
        if (expected == null) {
            Observation<InventoryScreenSnapshot> observation;
            try {
                observation = Objects.requireNonNull(port.observe(), "adapter observation");
            } catch (RuntimeException | Error exception) {
                cancelActive(current, InventoryCancelReason.ADAPTER_EXCEPTION,
                        "screen observation failed before close", exception);
                return false;
            }
            if (!observation.isPresent()) {
                cancelActive(current, InventoryCancelReason.SCREEN_CLOSED,
                        "no container screen to close", null);
                return false;
            }
            expected = observation.get().identity();
            current.boundScreen = expected;
        }

        try {
            Optional<InventoryCancelReason> rejection = Objects.requireNonNull(
                    port.closeScreen(expected), "close result");
            if (rejection.isPresent()) {
                cancelActive(current, rejection.orElseThrow(), "guarded screen close rejected", null);
                return false;
            }
        } catch (RuntimeException | Error exception) {
            cancelActive(current, InventoryCancelReason.ADAPTER_EXCEPTION,
                    "screen close adapter failed", exception);
            return false;
        }
        cancelActive(current, InventoryCancelReason.CLOSED_BY_OPERATION,
                "screen closed by active operation", null);
        return true;
    }

    public synchronized Optional<InventoryOperationToken> activeToken() {
        return active == null ? Optional.empty() : Optional.of(active.token);
    }

    private synchronized void timeout(InventoryOperationToken token) {
        if (isActive(token)) {
            cancelActive(active, InventoryCancelReason.TIMEOUT, "operation reached its timeout", null);
        }
    }

    private synchronized void executeNextStep(InventoryOperationToken token) {
        if (!isActive(token)) {
            return;
        }
        ActiveOperation current = active;
        List<InventoryStep> steps = current.operation.steps();
        if (current.stepIndex >= steps.size()) {
            completeActive(current);
            return;
        }

        Observation<InventoryScreenSnapshot> observation;
        try {
            observation = Objects.requireNonNull(port.observe(), "adapter observation");
        } catch (RuntimeException | Error exception) {
            cancelActive(current, InventoryCancelReason.ADAPTER_EXCEPTION,
                    "screen observation adapter failed", exception);
            return;
        }
        if (!observation.isPresent()) {
            cancelActive(current, InventoryCancelReason.SCREEN_CLOSED, "container screen is closed", null);
            return;
        }
        InventoryScreenSnapshot before = observation.get();
        if (current.boundScreen != null && !current.boundScreen.equals(before.identity())) {
            cancelActive(current, InventoryCancelReason.SCREEN_CHANGED,
                    "container screen identity changed", null);
            return;
        }
        current.boundScreen = before.identity();

        InventoryStep step = steps.get(current.stepIndex);
        List<InventorySlot> matches = before.find(step.query());
        if (matches.isEmpty()) {
            cancelActive(current, InventoryCancelReason.SLOT_NOT_FOUND, "query found no item slot", null);
            return;
        }
        InventorySlot slot = matches.getFirst();
        if (!slot.active()) {
            cancelActive(current, InventoryCancelReason.SLOT_INACTIVE, "target slot is inactive", null);
            return;
        }
        if (!slot.mayPickup()) {
            cancelActive(current, InventoryCancelReason.PICKUP_DENIED, "target slot denies pickup", null);
            return;
        }

        InventoryItem item = slot.item().get();
        ItemIdentity target = new ItemIdentity(before.identity(), before.revision(), slot.menuSlot(), item);
        InventoryClickGuard guard = new InventoryClickGuard(
                target, slot.active(), slot.mayPickup(), slot.hotbarSelection(), before.cursor(), step.click());
        InventoryExecutionResult result;
        try {
            result = Objects.requireNonNull(port.executeGuardedClick(guard), "guarded click result");
        } catch (RuntimeException | Error exception) {
            cancelActive(current, InventoryCancelReason.ADAPTER_EXCEPTION,
                    "guarded click adapter failed", exception);
            return;
        }
        if (!result.executed()) {
            cancelActive(current, result.rejection().orElseThrow(), "guarded click rejected", null);
            return;
        }

        current.beforeClick = before;
        current.target = target;
        queue.schedule(current.taskOwner, 0L, () -> verifyStep(token));
    }

    private synchronized void verifyStep(InventoryOperationToken token) {
        if (!isActive(token)) {
            return;
        }
        ActiveOperation current = active;
        Observation<InventoryScreenSnapshot> observation;
        try {
            observation = Objects.requireNonNull(port.observe(), "adapter observation");
        } catch (RuntimeException | Error exception) {
            cancelActive(current, InventoryCancelReason.ADAPTER_EXCEPTION,
                    "post-click observation adapter failed", exception);
            return;
        }
        if (!observation.isPresent()) {
            cancelActive(current, InventoryCancelReason.SCREEN_CLOSED,
                    "screen closed before postcondition", null);
            return;
        }
        InventoryScreenSnapshot after = observation.get();
        if (!after.identity().equals(current.beforeClick.identity())) {
            cancelActive(current, InventoryCancelReason.SCREEN_CHANGED,
                    "screen changed before postcondition", null);
            return;
        }
        if (!after.revision().advancedFrom(current.beforeClick.revision())) {
            cancelActive(current, InventoryCancelReason.REVISION_CHANGED,
                    "menu revision did not advance after click", null);
            return;
        }

        InventoryStep step = current.operation.steps().get(current.stepIndex);
        final boolean verified;
        try {
            verified = step.verifier().verify(current.beforeClick, after, current.target);
        } catch (RuntimeException | Error exception) {
            cancelActive(current, InventoryCancelReason.VERIFIER_EXCEPTION,
                    "postcondition verifier failed", exception);
            return;
        }
        if (!verified) {
            cancelActive(current, InventoryCancelReason.POSTCONDITION_FAILED,
                    "postcondition was not satisfied", null);
            return;
        }

        current.stepIndex++;
        current.beforeClick = null;
        current.target = null;
        queue.schedule(current.taskOwner, 0L, () -> executeNextStep(token));
    }

    private void completeRejectedStart(
            InventoryOperationToken token,
            InventoryCancelReason reason,
            Consumer<InventoryOutcome> callback) {
        InventoryOutcome outcome = InventoryOutcome.cancelled(token, reason, 0);
        Throwable diagnosticFailure = emitDiagnostic(
                new InventoryDiagnostic(token, reason, 0, "another inventory operation is active", Optional.empty()),
                null);
        deliverCallback(callback, outcome, diagnosticFailure);
    }

    private void completeActive(ActiveOperation current) {
        if (active != current) {
            return;
        }
        active = null;
        queue.cancel(current.taskOwner);
        Throwable cleanupFailure = releaseLease(current, null);
        if (cleanupFailure != null) {
            InventoryOutcome outcome = InventoryOutcome.cancelled(
                    current.token, InventoryCancelReason.ADAPTER_EXCEPTION, current.stepIndex);
            emitDiagnostic(new InventoryDiagnostic(
                    current.token,
                    InventoryCancelReason.ADAPTER_EXCEPTION,
                    current.stepIndex,
                    "hotbar lease release failed",
                    failure(cleanupFailure)), cleanupFailure);
            deliverCallback(current.callback, outcome, cleanupFailure);
            return;
        }
        deliverCallback(current.callback, InventoryOutcome.completed(current.token, current.stepIndex), null);
    }

    private void cancelActive(
            ActiveOperation current,
            InventoryCancelReason reason,
            String message,
            Throwable failure) {
        if (active != current) {
            return;
        }
        active = null;
        queue.cancel(current.taskOwner);
        Throwable primary = releaseLease(current, failure);
        InventoryDiagnostic diagnostic = new InventoryDiagnostic(
                current.token, reason, current.stepIndex, message, failure(primary));
        Throwable diagnosticFailure = emitDiagnostic(diagnostic, primary);
        deliverCallback(current.callback,
                InventoryOutcome.cancelled(current.token, reason, current.stepIndex),
                primary != null ? primary : diagnosticFailure);
    }

    private Throwable releaseLease(ActiveOperation current, Throwable primary) {
        if (current.hotbarLease == null) {
            return primary;
        }
        try {
            current.hotbarLease.close();
        } catch (RuntimeException | Error exception) {
            if (primary == null) {
                return exception;
            }
            primary.addSuppressed(exception);
        }
        return primary;
    }

    private void deliverCallback(
            Consumer<InventoryOutcome> callback, InventoryOutcome outcome, Throwable primary) {
        try {
            callback.accept(outcome);
        } catch (RuntimeException | Error exception) {
            if (primary != null) {
                primary.addSuppressed(exception);
            }
            emitDiagnostic(new InventoryDiagnostic(
                    outcome.token(),
                    InventoryCancelReason.CALLBACK_EXCEPTION,
                    outcome.completedSteps(),
                    "terminal callback failed after cleanup",
                    failure(exception)), exception);
        }
    }

    private Throwable emitDiagnostic(InventoryDiagnostic diagnostic, Throwable primary) {
        try {
            diagnostics.accept(diagnostic);
            return null;
        } catch (RuntimeException | Error exception) {
            if (primary != null) {
                primary.addSuppressed(exception);
            }
            return exception;
        }
    }

    private void emitStaleToken(InventoryOperationToken token, String message) {
        emitDiagnostic(new InventoryDiagnostic(
                token, InventoryCancelReason.STALE_TOKEN, 0, message, Optional.empty()), null);
    }

    private static Optional<InventoryDiagnostic.Failure> failure(Throwable failure) {
        return Optional.ofNullable(failure).map(InventoryDiagnostic.Failure::from);
    }

    private boolean isActive(InventoryOperationToken token) {
        return active != null && active.token.equals(token);
    }

    private InventoryOperationToken newToken() {
        if (nextToken == Long.MAX_VALUE) {
            throw new IllegalStateException("inventory token sequence exhausted");
        }
        return new InventoryOperationToken(nextToken++);
    }

    private static final class ActiveOperation {
        private final InventoryOperationToken token;
        private final InventoryOperation operation;
        private final Consumer<InventoryOutcome> callback;
        private final TaskOwner taskOwner;
        private InputLease hotbarLease;
        private ScreenIdentity boundScreen;
        private int stepIndex;
        private InventoryScreenSnapshot beforeClick;
        private ItemIdentity target;

        private ActiveOperation(
                InventoryOperationToken token,
                InventoryOperation operation,
                Consumer<InventoryOutcome> callback,
                TaskOwner taskOwner) {
            this.token = token;
            this.operation = operation;
            this.callback = callback;
            this.taskOwner = taskOwner;
        }
    }
}
