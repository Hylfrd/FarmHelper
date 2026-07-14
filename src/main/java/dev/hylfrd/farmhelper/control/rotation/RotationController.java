package dev.hylfrd.farmhelper.control.rotation;

import dev.hylfrd.farmhelper.control.RotationTask;
import dev.hylfrd.farmhelper.control.input.ControlOwner;
import dev.hylfrd.farmhelper.runtime.time.MonotonicClock;
import dev.hylfrd.farmhelper.runtime.time.PausableTimer;

import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;

/** Pure Java owner of one explicit, pausable rotation lease at a time. */
public final class RotationController {
    private static final RotationCallback NO_CALLBACK = result -> { };

    private final MonotonicClock clock;
    private ActiveRotation active;
    private RotationSnapshot snapshot = RotationSnapshot.idle();
    private long nextLeaseId = 1L;
    private long revision;

    public RotationController(MonotonicClock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public RotationHandle start(
            ControlOwner owner,
            float startYaw,
            float startPitch,
            float targetYaw,
            float targetPitch,
            long durationMs) {
        return start(owner, startYaw, startPitch, targetYaw, targetPitch, durationMs, NO_CALLBACK);
    }

    public synchronized RotationHandle start(
            ControlOwner owner,
            float startYaw,
            float startPitch,
            float targetYaw,
            float targetPitch,
            long durationMs,
            RotationCallback callback) {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(callback, "callback");
        RotationTask nextTask = new RotationTask(startYaw, startPitch, targetYaw, targetPitch, durationMs);

        if (active != null && !active.owner.equals(owner)) {
            throw new RotationConflictException(owner, active.owner);
        }
        if (active != null) {
            terminate(active, RotationTerminalReason.REPLACED);
            if (active != null) {
                throw new RotationConflictException(owner, active.owner);
            }
        }

        long leaseId = nextLeaseId++;
        ActiveRotation next = new ActiveRotation(
                owner,
                leaseId,
                nextTask,
                new PausableTimer(clock, nextTask.durationNanos()),
                callback);
        active = next;
        revision++;
        snapshot = activeSnapshot(next);
        return new RotationHandle(this, owner, leaseId);
    }

    public synchronized boolean rotating() {
        return active != null;
    }

    public synchronized boolean paused() {
        return active != null && active.timer.paused();
    }

    public synchronized boolean movementBlocked() {
        return active != null;
    }

    public synchronized Optional<RotationTask> task() {
        return active == null ? Optional.empty() : Optional.of(active.task);
    }

    public synchronized RotationSnapshot snapshot() {
        if (active != null) {
            snapshot = activeSnapshot(active);
        }
        return snapshot;
    }

    public synchronized void pause() {
        if (active == null || active.timer.paused()) {
            return;
        }
        active.observeElapsed();
        active.timer.pause();
        revision++;
        snapshot = activeSnapshot(active);
    }

    public synchronized void resume() {
        if (active == null || !active.timer.paused()) {
            return;
        }
        active.timer.resume();
        revision++;
        snapshot = activeSnapshot(active);
    }

    /** Applies one sample and completes only after the final sample was accepted by the sink. */
    public synchronized boolean tick(Consumer<RotationFrame> sink) {
        Objects.requireNonNull(sink, "sink");
        if (active == null) {
            return false;
        }

        ActiveRotation current = active;
        RotationFrame frame = current.task.sample(current.observeElapsed());
        try {
            sink.accept(frame);
        } catch (RuntimeException | Error exception) {
            if (active == current) {
                try {
                    terminate(current, RotationTerminalReason.APPLICATION_FAILED);
                } catch (RuntimeException | Error callbackFailure) {
                    exception.addSuppressed(callbackFailure);
                }
            }
            throw exception;
        }

        if (frame.complete() && active == current) {
            terminate(current, RotationTerminalReason.COMPLETED);
        } else if (active == current) {
            snapshot = activeSnapshot(current);
        }
        return true;
    }

    public synchronized boolean cancel(ControlOwner owner, RotationCancelReason reason) {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(reason, "reason");
        if (active == null || !active.owner.equals(owner)) {
            return false;
        }
        terminate(active, RotationTerminalReason.fromCancelReason(reason));
        return true;
    }

    synchronized boolean cancel(ControlOwner owner, long leaseId, RotationCancelReason reason) {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(reason, "reason");
        if (active == null || active.leaseId != leaseId || !active.owner.equals(owner)) {
            return false;
        }
        terminate(active, RotationTerminalReason.fromCancelReason(reason));
        return true;
    }

    private void terminate(ActiveRotation terminated, RotationTerminalReason reason) {
        RotationFrame frame = terminated.task.sample(terminated.observeElapsed());
        active = null;
        revision++;
        snapshot = new RotationSnapshot(
                false,
                false,
                Optional.of(terminated.owner),
                Optional.of(terminated.task.targetYaw()),
                Optional.of(terminated.task.targetPitch()),
                reason == RotationTerminalReason.COMPLETED ? 1.0F : frame.progress(),
                Optional.of(reason),
                revision);
        terminated.callback.onTerminated(new RotationResult(
                terminated.owner,
                terminated.task.targetYaw(),
                terminated.task.targetPitch(),
                snapshot.progress(),
                reason));
    }

    private RotationSnapshot activeSnapshot(ActiveRotation current) {
        float progress = current.task.sample(current.observeElapsed()).progress();
        return new RotationSnapshot(
                true,
                current.timer.paused(),
                Optional.of(current.owner),
                Optional.of(current.task.targetYaw()),
                Optional.of(current.task.targetPitch()),
                progress,
                Optional.empty(),
                revision);
    }

    private static final class ActiveRotation {
        private final ControlOwner owner;
        private final long leaseId;
        private final RotationTask task;
        private final PausableTimer timer;
        private final RotationCallback callback;
        private long observedElapsedNanos;

        private ActiveRotation(
                ControlOwner owner,
                long leaseId,
                RotationTask task,
                PausableTimer timer,
                RotationCallback callback) {
            this.owner = owner;
            this.leaseId = leaseId;
            this.task = task;
            this.timer = timer;
            this.callback = callback;
        }

        private long observeElapsed() {
            observedElapsedNanos = Math.max(observedElapsedNanos, timer.elapsedNanos());
            return observedElapsedNanos;
        }
    }
}
