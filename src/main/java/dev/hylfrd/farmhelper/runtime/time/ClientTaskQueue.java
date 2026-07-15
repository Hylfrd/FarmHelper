package dev.hylfrd.farmhelper.runtime.time;

import java.math.BigInteger;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.PriorityQueue;
import java.util.Set;

/**
 * A threadless delayed task queue intended to be advanced explicitly by the client thread.
 * Tasks scheduled from a callback are never run during the same {@link #advance()} call.
 */
public final class ClientTaskQueue {
    private static final Comparator<Entry> ENTRY_ORDER = Comparator
            .comparing(Entry::deadlineNanos)
            .thenComparingLong(Entry::sequence);

    private final MonotonicClock clock;
    private final PriorityQueue<Entry> entries = new PriorityQueue<>(ENTRY_ORDER);
    private final Map<TaskOwner, Set<TaskHandle>> pendingByOwner = new HashMap<>();
    private long lastClockNanos;
    private BigInteger logicalNowNanos = BigInteger.ZERO;
    private long nextSequence;
    private boolean advancing;

    public ClientTaskQueue(MonotonicClock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
        lastClockNanos = clock.nowNanos();
    }

    public TaskHandle schedule(TaskOwner owner, long delayNanos, Runnable task) {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(task, "task");
        if (delayNanos < 0L) {
            throw new IllegalArgumentException("delayNanos must not be negative");
        }
        if (nextSequence == Long.MAX_VALUE) {
            throw new IllegalStateException("task sequence exhausted");
        }
        observeClock();
        TaskHandle handle = new TaskHandle(this, owner);
        BigInteger deadlineNanos = logicalNowNanos.add(BigInteger.valueOf(delayNanos));
        entries.add(new Entry(deadlineNanos, nextSequence++, handle, task));
        pendingByOwner.computeIfAbsent(owner, ignored -> new LinkedHashSet<>()).add(handle);
        return handle;
    }

    public boolean cancel(TaskHandle handle) {
        Objects.requireNonNull(handle, "handle");
        if (handle.queue() != this) {
            throw new IllegalArgumentException("handle belongs to another queue");
        }
        if (handle.state() != TaskHandle.State.PENDING) {
            return false;
        }
        handle.state(TaskHandle.State.CANCELLED);
        removeFromOwner(handle);
        return true;
    }

    public int cancel(TaskOwner owner) {
        Objects.requireNonNull(owner, "owner");
        Set<TaskHandle> handles = pendingByOwner.remove(owner);
        if (handles == null) {
            return 0;
        }
        int cancelled = 0;
        for (TaskHandle handle : handles) {
            if (handle.state() == TaskHandle.State.PENDING) {
                handle.state(TaskHandle.State.CANCELLED);
                cancelled++;
            }
        }
        return cancelled;
    }

    /** Cancels every pending callback, regardless of owner, at a client lifecycle boundary. */
    public int cancelAll() {
        int cancelled = 0;
        for (Set<TaskHandle> handles : pendingByOwner.values()) {
            for (TaskHandle handle : handles) {
                if (handle.state() == TaskHandle.State.PENDING) {
                    handle.state(TaskHandle.State.CANCELLED);
                    cancelled++;
                }
            }
        }
        pendingByOwner.clear();
        return cancelled;
    }

    /** Runs due callbacks and returns their count. This method never waits for time to pass. */
    public int advance() {
        if (advancing) {
            throw new IllegalStateException("advance already in progress");
        }
        advancing = true;
        try {
            BigInteger cutoffNanos = observeClock();
            long sequenceLimit = nextSequence;
            int executed = 0;
            while (true) {
                discardCancelledHead();
                Entry entry = entries.peek();
                if (entry == null
                        || entry.deadlineNanos().compareTo(cutoffNanos) > 0
                        || entry.sequence() >= sequenceLimit) {
                    return executed;
                }
                entries.remove();
                TaskHandle handle = entry.handle();
                if (handle.state() != TaskHandle.State.PENDING) {
                    continue;
                }
                removeFromOwner(handle);
                handle.state(TaskHandle.State.RUNNING);
                try {
                    entry.task().run();
                    executed++;
                } finally {
                    handle.state(TaskHandle.State.COMPLETED);
                }
            }
        } finally {
            advancing = false;
        }
    }

    public int pendingTaskCount() {
        int count = 0;
        for (Set<TaskHandle> handles : pendingByOwner.values()) {
            count += handles.size();
        }
        return count;
    }

    private BigInteger observeClock() {
        long nowNanos = clock.nowNanos();
        long deltaNanos = PausableTimer.elapsedSince(lastClockNanos, nowNanos);
        lastClockNanos = nowNanos;
        logicalNowNanos = logicalNowNanos.add(BigInteger.valueOf(deltaNanos));
        return logicalNowNanos;
    }

    private void discardCancelledHead() {
        while (!entries.isEmpty() && entries.peek().handle().state() == TaskHandle.State.CANCELLED) {
            entries.remove();
        }
    }

    private void removeFromOwner(TaskHandle handle) {
        Set<TaskHandle> handles = pendingByOwner.get(handle.owner());
        if (handles == null) {
            return;
        }
        handles.remove(handle);
        if (handles.isEmpty()) {
            pendingByOwner.remove(handle.owner());
        }
    }

    private record Entry(BigInteger deadlineNanos, long sequence, TaskHandle handle, Runnable task) {
    }
}
