package dev.hylfrd.farmhelper.navigation;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Threadless owner of one ticketed navigation run. It freezes ownership and lifecycle semantics;
 * search, following, and physical execution are deliberately supplied by later leaves.
 */
public final class NavigationController {
    private final Runnable acquisitionGuard;
    private final List<NavigationTerminalCleanup> terminalCleanups;
    private final long firstWorkRevision;
    private long lastGeneration;
    private ActiveRun active;
    private boolean terminating;
    private NavigationStatus lastTerminalStatus;
    private RuntimeException lastCleanupFailure;

    public NavigationController() {
        this(() -> { });
    }

    public NavigationController(
            Runnable acquisitionGuard,
            NavigationTerminalCleanup... terminalCleanups
    ) {
        this(0L, 1L, acquisitionGuard, terminalCleanups);
    }

    NavigationController(
            long lastGeneration,
            Runnable acquisitionGuard,
            NavigationTerminalCleanup... terminalCleanups
    ) {
        this(lastGeneration, 1L, acquisitionGuard, terminalCleanups);
    }

    NavigationController(
            long lastGeneration,
            long firstWorkRevision,
            Runnable acquisitionGuard,
            NavigationTerminalCleanup... terminalCleanups
    ) {
        if (lastGeneration < 0L) {
            throw new IllegalArgumentException("lastGeneration must be non-negative");
        }
        if (firstWorkRevision <= 0L) {
            throw new IllegalArgumentException("firstWorkRevision must be positive");
        }
        this.lastGeneration = lastGeneration;
        this.firstWorkRevision = firstWorkRevision;
        this.acquisitionGuard = Objects.requireNonNull(acquisitionGuard, "acquisitionGuard");
        Objects.requireNonNull(terminalCleanups, "terminalCleanups");
        List<NavigationTerminalCleanup> copy = new ArrayList<>(terminalCleanups.length);
        for (NavigationTerminalCleanup cleanup : terminalCleanups.clone()) {
            copy.add(Objects.requireNonNull(cleanup, "terminal cleanup"));
        }
        this.terminalCleanups = List.copyOf(copy);
    }

    public synchronized NavigationHandle start(
            NavigationRequest request,
            NavigationStartObservation observation
    ) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(observation, "observation");
        acquisitionGuard.run();
        requireNotTerminating();
        if (active != null) {
            throw new NavigationConflictException(active.ticket());
        }
        NavigationTicket ticket = allocate(request);
        return begin(ticket, request, observation);
    }

    synchronized Optional<NavigationHandle> replace(
            NavigationTicket expectedActive,
            NavigationRequest request,
            NavigationStartObservation observation
    ) {
        Objects.requireNonNull(expectedActive, "expectedActive");
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(observation, "observation");
        acquisitionGuard.run();
        requireNotTerminating();
        if (active == null || !active.ticket().equals(expectedActive)) {
            return Optional.empty();
        }

        // Allocation happens before terminating the current run, so exhaustion cannot discard it.
        NavigationTicket replacement = allocate(request);
        terminate(active, NavigationResult.cancelled(
                active.status().workTicket(), NavigationCancellationReason.REPLACED));
        return Optional.of(begin(replacement, request, observation));
    }

    public synchronized Optional<NavigationStatus> status() {
        return Optional.ofNullable(active == null ? lastTerminalStatus : active.status());
    }

    synchronized Optional<NavigationStatus> status(NavigationTicket ticket) {
        Objects.requireNonNull(ticket, "ticket");
        if (active != null && active.ticket().equals(ticket)) {
            return Optional.of(active.status());
        }
        return lastTerminalStatus != null && lastTerminalStatus.ticket().equals(ticket)
                ? Optional.of(lastTerminalStatus) : Optional.empty();
    }

    public synchronized Optional<NavigationTicket> activeTicket() {
        return active == null ? Optional.empty() : Optional.of(active.ticket());
    }

    public synchronized Optional<NavigationResult> lastResult() {
        return lastTerminalStatus == null
                ? Optional.empty() : lastTerminalStatus.terminalResult();
    }

    public synchronized Optional<RuntimeException> lastCleanupFailure() {
        return Optional.ofNullable(lastCleanupFailure);
    }

    public synchronized boolean advance(
            NavigationWorkTicket expected,
            NavigationPhase next
    ) {
        Objects.requireNonNull(expected, "expected");
        Objects.requireNonNull(next, "next");
        if (!matches(expected) || !allowed(expected.phase(), next)) {
            return false;
        }
        issue(next, next == NavigationPhase.CAPTURING
                ? Optional.empty() : active.status().spatialSnapshot());
        return true;
    }

    public synchronized boolean acceptCapture(
            NavigationWorkTicket expected,
            SegmentedSpatialSnapshot snapshot
    ) {
        Objects.requireNonNull(expected, "expected");
        Objects.requireNonNull(snapshot, "snapshot");
        if (!matches(expected) || expected.phase() != NavigationPhase.CAPTURING) {
            return false;
        }
        if (!snapshot.workTicket().equals(expected)) {
            throw new IllegalArgumentException("capture has a stale work ticket");
        }
        issue(NavigationPhase.SEARCHING, Optional.of(snapshot));
        return true;
    }

    public synchronized boolean complete(NavigationWorkTicket expected) {
        Objects.requireNonNull(expected, "expected");
        return matches(expected) && terminate(active, NavigationResult.completed(expected));
    }

    public synchronized boolean fail(
            NavigationWorkTicket expected,
            NavigationFailureReason reason
    ) {
        Objects.requireNonNull(expected, "expected");
        Objects.requireNonNull(reason, "reason");
        return matches(expected) && terminate(active, NavigationResult.failed(expected, reason));
    }

    public synchronized boolean cancel(
            NavigationTicket ticket,
            NavigationCancellationReason reason
    ) {
        Objects.requireNonNull(ticket, "ticket");
        Objects.requireNonNull(reason, "reason");
        return matches(ticket) && terminate(active, NavigationResult.cancelled(
                active.status().workTicket(), reason));
    }

    /** Cancels only the current ticket, if any, for a global lifecycle boundary. */
    public synchronized boolean cancelActive(NavigationCancellationReason reason) {
        Objects.requireNonNull(reason, "reason");
        return active != null && terminate(
                active, NavigationResult.cancelled(active.status().workTicket(), reason));
    }

    private NavigationHandle begin(
            NavigationTicket ticket,
            NavigationRequest request,
            NavigationStartObservation observation
    ) {
        NavigationWorkTicket workTicket = new NavigationWorkTicket(
                ticket, NavigationPhase.REQUESTED, firstWorkRevision);
        NavigationStatus status = new NavigationStatus(
                ticket, request, workTicket, Optional.empty(), Optional.empty());
        NavigationHandle handle = new NavigationHandle(this, ticket);
        active = new ActiveRun(status, handle);
        observation.failureFor(request).ifPresent(reason ->
                terminate(active, NavigationResult.failed(workTicket, reason)));
        return handle;
    }

    private NavigationTicket allocate(NavigationRequest request) {
        if (lastGeneration == Long.MAX_VALUE) {
            throw new IllegalStateException("navigation generation exhausted");
        }
        lastGeneration++;
        return new NavigationTicket(request.owner(), lastGeneration, request.worldEpoch());
    }

    private boolean matches(NavigationTicket ticket) {
        return active != null && active.ticket().equals(ticket);
    }

    private boolean matches(NavigationWorkTicket workTicket) {
        return active != null && active.status().workTicket().equals(workTicket);
    }

    private void issue(
            NavigationPhase phase,
            Optional<SegmentedSpatialSnapshot> snapshot
    ) {
        NavigationStatus current = active.status();
        if (current.workTicket().revision() == Long.MAX_VALUE) {
            throw new IllegalStateException("navigation work revision exhausted");
        }
        NavigationWorkTicket next = new NavigationWorkTicket(
                current.ticket(), phase, current.workTicket().revision() + 1L);
        NavigationStatus updated = new NavigationStatus(
                current.ticket(), current.request(), next, snapshot, Optional.empty());
        active = new ActiveRun(updated, active.handle());
    }

    private boolean terminate(ActiveRun terminated, NavigationResult result) {
        NavigationStatus current = terminated.status();
        if (active != terminated || !current.workTicket().equals(result.workTicket())) {
            return false;
        }
        NavigationStatus terminal = new NavigationStatus(
                current.ticket(), current.request(), current.workTicket(),
                current.spatialSnapshot(), Optional.of(result));
        // Commit the fence before callbacks. Reentrant/stale callbacks cannot touch a replacement.
        active = null;
        lastTerminalStatus = terminal;
        terminated.handle().terminated(terminal);
        terminating = true;
        try {
            lastCleanupFailure = runCleanups(result);
        } finally {
            terminating = false;
        }
        return true;
    }

    private RuntimeException runCleanups(NavigationResult result) {
        RuntimeException aggregate = null;
        for (NavigationTerminalCleanup cleanup : terminalCleanups) {
            try {
                cleanup.cleanup(result.ticket(), result);
            } catch (RuntimeException | Error failure) {
                if (aggregate == null) {
                    aggregate = new RuntimeException("one or more navigation terminal cleanups failed");
                }
                aggregate.addSuppressed(failure);
            }
        }
        return aggregate;
    }

    private static boolean allowed(NavigationPhase current, NavigationPhase next) {
        return switch (current) {
            case REQUESTED -> next == NavigationPhase.CAPTURING;
            case CAPTURING -> false; // Only acceptCapture may publish CAPTURING -> SEARCHING.
            case SEARCHING -> next == NavigationPhase.SEARCHING
                    || next == NavigationPhase.FOLLOWING
                    || next == NavigationPhase.EXECUTING;
            case FOLLOWING, EXECUTING -> next == NavigationPhase.CAPTURING
                    || next == NavigationPhase.SEARCHING
                    || next == NavigationPhase.FOLLOWING
                    || next == NavigationPhase.EXECUTING;
        };
    }

    private void requireNotTerminating() {
        if (terminating) {
            throw new IllegalStateException("navigation terminal cleanup is in progress");
        }
    }

    private record ActiveRun(NavigationStatus status, NavigationHandle handle) {
        private NavigationTicket ticket() {
            return status.ticket();
        }
    }
}
