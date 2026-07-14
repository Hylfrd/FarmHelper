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

/** Threadless, queue-advanced inventory transaction coordinator. */
public final class InventoryController {
    private final InventoryTaskScheduler scheduler;
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
        this(InventoryTaskScheduler.from(queue), hotbar, port, diagnostics);
    }

    public InventoryController(
            InventoryTaskScheduler scheduler,
            InventoryHotbarPort hotbar,
            InventoryPort port,
            Consumer<InventoryDiagnostic> diagnostics) {
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
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
            completeRejectedStart(token, callback);
            return token;
        }

        ActiveOperation created = new ActiveOperation(
                token, operation, callback, new TaskOwner("inventory-" + token.value()));
        active = created;
        try {
            if (operation.hotbarSelection().isPresent()) {
                created.hotbarLease = hotbar.select(
                        operation.owner(), operation.hotbarSelection().orElseThrow());
            }
        } catch (InputConflictException exception) {
            cancelActive(created, InventoryCancelReason.OWNER_CONFLICT,
                    "hotbar is owned by another control owner", null);
            return token;
        } catch (RuntimeException | Error exception) {
            failActive(created, InventoryCancelReason.ADAPTER_EXCEPTION,
                    "hotbar adapter failed", exception);
        }

        try {
            scheduler.schedule(created.taskOwner, operation.timeoutNanos(),
                    () -> timeout(created.token));
            scheduler.schedule(created.taskOwner, 0L,
                    () -> executeNextStep(created.token));
        } catch (RuntimeException | Error exception) {
            failActive(created, InventoryCancelReason.ADAPTER_EXCEPTION,
                    "inventory task scheduling failed", exception);
        }
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
                observation = Objects.requireNonNull(port.observe(
                        current.token, current.operation.owner()), "adapter observation");
            } catch (RuntimeException | Error exception) {
                failActive(current, InventoryCancelReason.ADAPTER_EXCEPTION,
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
            if (active != current) {
                return false;
            }
            if (rejection.isPresent()) {
                cancelActive(current, rejection.orElseThrow(),
                        "guarded screen close rejected", null);
                return false;
            }
        } catch (RuntimeException | Error exception) {
            failActive(current, InventoryCancelReason.ADAPTER_EXCEPTION,
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

    private synchronized boolean isAuthorized(
            InventoryOperationToken token, dev.hylfrd.farmhelper.control.input.ControlOwner owner) {
        return active != null
                && active.token.equals(token)
                && active.operation.owner().equals(owner);
    }

    private synchronized void timeout(InventoryOperationToken token) {
        if (isActive(token)) {
            cancelActive(active, InventoryCancelReason.TIMEOUT,
                    "operation reached its timeout", null);
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
            observation = Objects.requireNonNull(port.observe(
                    current.token, current.operation.owner()), "adapter observation");
        } catch (RuntimeException | Error exception) {
            failActive(current, InventoryCancelReason.ADAPTER_EXCEPTION,
                    "screen observation adapter failed", exception);
            return;
        }
        if (!observation.isPresent()) {
            cancelActive(current, InventoryCancelReason.SCREEN_CLOSED,
                    "container screen is closed", null);
            return;
        }
        InventoryScreenSnapshot before = observation.get();
        if (!current.screenValidated) {
            Optional<InventoryCancelReason> rejection = current.operation.screenExpectation()
                    .orElseThrow()
                    .rejection(before);
            if (rejection.isPresent()) {
                cancelActive(current, rejection.orElseThrow(),
                        "initial screen expectation was not satisfied", null);
                return;
            }
            current.screenValidated = true;
        }
        if (current.boundScreen != null && !current.boundScreen.equals(before.identity())) {
            cancelActive(current, InventoryCancelReason.SCREEN_CHANGED,
                    "container screen identity changed", null);
            return;
        }
        current.boundScreen = before.identity();

        InventoryStep step = steps.get(current.stepIndex);
        List<InventorySlot> matches = before.find(step.query());
        if (matches.isEmpty()) {
            cancelActive(current, InventoryCancelReason.SLOT_NOT_FOUND,
                    "query found no item slot", null);
            return;
        }
        InventorySlot slot = matches.getFirst();
        if (!slot.active()) {
            cancelActive(current, InventoryCancelReason.SLOT_INACTIVE,
                    "target slot is inactive", null);
            return;
        }
        if (!slot.mayPickup()) {
            cancelActive(current, InventoryCancelReason.PICKUP_DENIED,
                    "target slot denies pickup", null);
            return;
        }

        InventoryItem item = slot.item().get();
        ItemIdentity target = new ItemIdentity(
                before.identity(), before.revision(), slot.menuSlot(), item);
        InventoryClickGuard guard = new InventoryClickGuard(
                current.token,
                current.operation.owner(),
                () -> isAuthorized(current.token, current.operation.owner()),
                target,
                slot.active(),
                slot.mayPickup(),
                slot.hotbarSelection(),
                before.cursor(),
                step.click());
        InventoryExecutionResult result;
        try {
            result = Objects.requireNonNull(
                    port.executeGuardedClick(guard), "guarded click result");
        } catch (RuntimeException | Error exception) {
            failActive(current, InventoryCancelReason.ADAPTER_EXCEPTION,
                    "guarded click adapter failed", exception);
            return;
        }
        if (active != current) {
            return;
        }
        if (!result.executed()) {
            cancelActive(current, result.rejection().orElseThrow(),
                    "guarded click rejected", null);
            return;
        }

        current.beforeClick = before;
        current.target = target;
        scheduleVerification(current);
    }

    private synchronized void verifyStep(InventoryOperationToken token) {
        if (!isActive(token)) {
            return;
        }
        ActiveOperation current = active;
        Observation<InventoryScreenSnapshot> observation;
        try {
            observation = Objects.requireNonNull(port.observe(
                    current.token, current.operation.owner()), "adapter observation");
        } catch (RuntimeException | Error exception) {
            failActive(current, InventoryCancelReason.ADAPTER_EXCEPTION,
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
            scheduleVerification(current);
            return;
        }

        InventoryStep step = current.operation.steps().get(current.stepIndex);
        final boolean verified;
        try {
            verified = step.verifier().verify(
                    current.beforeClick, after, current.target, step.click());
        } catch (RuntimeException | Error exception) {
            failActive(current, InventoryCancelReason.VERIFIER_EXCEPTION,
                    "postcondition verifier failed", exception);
            return;
        }
        if (!verified) {
            scheduleVerification(current);
            return;
        }

        current.stepIndex++;
        current.beforeClick = null;
        current.target = null;
        try {
            scheduler.schedule(current.taskOwner, 0L,
                    () -> executeNextStep(token));
        } catch (RuntimeException | Error exception) {
            failActive(current, InventoryCancelReason.ADAPTER_EXCEPTION,
                    "next inventory step scheduling failed", exception);
        }
    }

    private void scheduleVerification(ActiveOperation current) {
        try {
            scheduler.schedule(current.taskOwner, 0L,
                    () -> verifyStep(current.token));
        } catch (RuntimeException | Error exception) {
            failActive(current, InventoryCancelReason.ADAPTER_EXCEPTION,
                    "postcondition scheduling failed", exception);
        }
    }

    private void completeRejectedStart(
            InventoryOperationToken token, Consumer<InventoryOutcome> callback) {
        InventoryOutcome outcome = InventoryOutcome.cancelled(
                token, InventoryCancelReason.OWNER_CONFLICT, 0);
        Throwable failure = emitDiagnostic(new InventoryDiagnostic(
                token,
                InventoryCancelReason.OWNER_CONFLICT,
                0,
                "another inventory operation is active",
                Optional.empty()), null);
        failure = deliverCallback(callback, outcome, failure);
        rethrow(failure);
    }

    private void completeActive(ActiveOperation current) {
        if (active != current) {
            return;
        }
        Throwable failure = cleanup(current, null);
        if (failure != null) {
            failure = emitDiagnostic(new InventoryDiagnostic(
                    current.token,
                    InventoryCancelReason.ADAPTER_EXCEPTION,
                    current.stepIndex,
                    "inventory operation cleanup failed",
                    failure(failure)), failure);
            failure = deliverCallback(current.callback, InventoryOutcome.cancelled(
                    current.token, InventoryCancelReason.ADAPTER_EXCEPTION, current.stepIndex), failure);
            rethrow(failure);
            return;
        }
        failure = deliverCallback(current.callback,
                InventoryOutcome.completed(current.token, current.stepIndex), null);
        rethrow(failure);
    }

    private void cancelActive(
            ActiveOperation current,
            InventoryCancelReason reason,
            String message,
            Throwable initialFailure) {
        if (active != current) {
            rethrow(initialFailure);
            return;
        }
        Throwable failure = cleanup(current, initialFailure);
        failure = emitDiagnostic(new InventoryDiagnostic(
                current.token, reason, current.stepIndex, message, failure(failure)), failure);
        failure = deliverCallback(current.callback,
                InventoryOutcome.cancelled(current.token, reason, current.stepIndex), failure);
        rethrow(failure);
    }

    private void failActive(
            ActiveOperation current,
            InventoryCancelReason reason,
            String message,
            Throwable failure) {
        Objects.requireNonNull(failure, "failure");
        if (active != current) {
            Throwable propagated = emitDiagnostic(new InventoryDiagnostic(
                    current.token, reason, current.stepIndex, message, failure(failure)), failure);
            rethrow(propagated);
            return;
        }
        cancelActive(current, reason, message, failure);
    }

    private Throwable cleanup(ActiveOperation current, Throwable primary) {
        active = null;
        try {
            scheduler.cancel(current.taskOwner);
        } catch (RuntimeException | Error exception) {
            primary = append(primary, exception);
        }
        try {
            port.releaseOperation(current.token, current.operation.owner());
        } catch (RuntimeException | Error exception) {
            primary = append(primary, exception);
        }
        if (current.hotbarLease != null) {
            try {
                current.hotbarLease.close();
            } catch (RuntimeException | Error exception) {
                primary = append(primary, exception);
            }
        }
        return primary;
    }

    private Throwable deliverCallback(
            Consumer<InventoryOutcome> callback, InventoryOutcome outcome, Throwable primary) {
        try {
            callback.accept(outcome);
            return primary;
        } catch (RuntimeException | Error exception) {
            primary = append(primary, exception);
            return emitDiagnostic(new InventoryDiagnostic(
                    outcome.token(),
                    InventoryCancelReason.CALLBACK_EXCEPTION,
                    outcome.completedSteps(),
                    "terminal callback failed after cleanup",
                    failure(exception)), primary);
        }
    }

    private Throwable emitDiagnostic(InventoryDiagnostic diagnostic, Throwable primary) {
        try {
            diagnostics.accept(diagnostic);
            return primary;
        } catch (RuntimeException | Error exception) {
            return append(primary, exception);
        }
    }

    private void emitStaleToken(InventoryOperationToken token, String message) {
        Throwable failure = emitDiagnostic(new InventoryDiagnostic(
                token,
                InventoryCancelReason.STALE_TOKEN,
                0,
                message,
                Optional.empty()), null);
        rethrow(failure);
    }

    private static Throwable append(Throwable primary, Throwable additional) {
        if (primary == null) {
            return additional;
        }
        if (primary != additional) {
            primary.addSuppressed(additional);
        }
        return primary;
    }

    private static Optional<InventoryDiagnostic.Failure> failure(Throwable failure) {
        return Optional.ofNullable(failure).map(InventoryDiagnostic.Failure::from);
    }

    private static void rethrow(Throwable failure) {
        if (failure instanceof RuntimeException runtimeException) {
            throw runtimeException;
        }
        if (failure instanceof Error error) {
            throw error;
        }
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
        private boolean screenValidated;
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
