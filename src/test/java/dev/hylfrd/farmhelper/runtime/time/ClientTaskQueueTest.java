package dev.hylfrd.farmhelper.runtime.time;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClientTaskQueueTest {
    @Test
    void schedulesOnlyWhenExplicitlyAdvancedAndRunsAtMostOnce() {
        ManualClock clock = new ManualClock();
        ClientTaskQueue queue = new ClientTaskQueue(clock);
        AtomicInteger callbacks = new AtomicInteger();
        TaskHandle handle = queue.schedule(new TaskOwner("macro"), 5L, callbacks::incrementAndGet);

        assertEquals(0, callbacks.get());
        assertEquals(0, queue.advance());
        clock.advanceNanos(5L);
        assertEquals(1, queue.advance());
        assertEquals(0, queue.advance());

        assertEquals(1, callbacks.get());
        assertTrue(handle.done());
        assertFalse(handle.cancelled());
        assertFalse(handle.cancel());
    }

    @Test
    void rejectsNegativeDelayAndAcceptsZeroAndMaximumDelay() {
        ManualClock clock = new ManualClock();
        ClientTaskQueue queue = new ClientTaskQueue(clock);
        TaskOwner owner = new TaskOwner("owner");
        List<String> calls = new ArrayList<>();

        assertThrows(IllegalArgumentException.class, () -> queue.schedule(owner, -1L, () -> { }));
        queue.schedule(owner, 0L, () -> calls.add("zero"));
        queue.schedule(owner, Long.MAX_VALUE, () -> calls.add("maximum"));

        assertEquals(1, queue.advance());
        assertEquals(List.of("zero"), calls);
        clock.advanceNanos(Long.MAX_VALUE);
        assertEquals(1, queue.advance());
        assertEquals(List.of("zero", "maximum"), calls);
    }

    @Test
    void maximumDelayScheduledAfterTimeHasPassedDoesNotFireEarly() {
        ManualClock clock = new ManualClock();
        ClientTaskQueue queue = new ClientTaskQueue(clock);
        AtomicInteger callbacks = new AtomicInteger();

        clock.advanceNanos(5L);
        queue.schedule(new TaskOwner("owner"), Long.MAX_VALUE, callbacks::incrementAndGet);
        clock.advanceNanos(Long.MAX_VALUE - 1L);
        assertEquals(0, queue.advance());
        clock.advanceNanos(1L);
        assertEquals(1, queue.advance());
        assertEquals(1, callbacks.get());
    }

    @Test
    void preservesInsertionOrderForTheSameDeadline() {
        ManualClock clock = new ManualClock();
        ClientTaskQueue queue = new ClientTaskQueue(clock);
        TaskOwner owner = new TaskOwner("owner");
        List<Integer> calls = new ArrayList<>();

        queue.schedule(owner, 3L, () -> calls.add(1));
        queue.schedule(owner, 3L, () -> calls.add(2));
        queue.schedule(owner, 3L, () -> calls.add(3));
        clock.advanceNanos(3L);
        queue.advance();

        assertEquals(List.of(1, 2, 3), calls);
    }

    @Test
    void cancelsOneHandleOrAllPendingTasksForAnOwner() {
        ManualClock clock = new ManualClock();
        ClientTaskQueue queue = new ClientTaskQueue(clock);
        TaskOwner firstOwner = new TaskOwner("first");
        TaskOwner secondOwner = new TaskOwner("second");
        AtomicInteger callbacks = new AtomicInteger();

        TaskHandle one = queue.schedule(firstOwner, 0L, callbacks::incrementAndGet);
        TaskHandle two = queue.schedule(firstOwner, 0L, callbacks::incrementAndGet);
        TaskHandle other = queue.schedule(secondOwner, 0L, callbacks::incrementAndGet);

        assertTrue(queue.cancel(one));
        assertFalse(queue.cancel(one));
        assertEquals(1, queue.cancel(firstOwner));
        assertEquals(0, queue.cancel(firstOwner));
        assertEquals(1, queue.pendingTaskCount());
        assertEquals(1, queue.advance());

        assertEquals(1, callbacks.get());
        assertTrue(one.cancelled());
        assertTrue(two.cancelled());
        assertFalse(other.cancelled());
    }

    @Test
    void lifecycleCancellationRevokesEveryOwnerWithoutRunningCallbacks() {
        ManualClock clock = new ManualClock();
        ClientTaskQueue queue = new ClientTaskQueue(clock);
        AtomicInteger callbacks = new AtomicInteger();
        TaskHandle first = queue.schedule(new TaskOwner("macro"), 0L, callbacks::incrementAndGet);
        TaskHandle second = queue.schedule(new TaskOwner("inventory"), 0L, callbacks::incrementAndGet);

        assertEquals(2, queue.cancelAll());
        assertEquals(0, queue.cancelAll());
        assertEquals(0, queue.advance());
        assertTrue(first.cancelled());
        assertTrue(second.cancelled());
        assertEquals(0, callbacks.get());
    }

    @Test
    void callbackSchedulingWaitsForNextAdvanceAndCancellationIsImmediate() {
        ManualClock clock = new ManualClock();
        ClientTaskQueue queue = new ClientTaskQueue(clock);
        TaskOwner owner = new TaskOwner("owner");
        List<String> calls = new ArrayList<>();
        TaskHandle[] cancelledFromCallback = new TaskHandle[1];

        queue.schedule(owner, 0L, () -> {
            calls.add("first");
            queue.schedule(owner, 0L, () -> calls.add("deferred"));
            cancelledFromCallback[0].cancel();
        });
        cancelledFromCallback[0] = queue.schedule(owner, 0L, () -> calls.add("cancelled"));

        assertEquals(1, queue.advance());
        assertEquals(List.of("first"), calls);
        assertEquals(1, queue.advance());
        assertEquals(List.of("first", "deferred"), calls);
    }

    @Test
    void rejectsReentrantAdvanceAndLeavesCallbackSchedulingForNextAdvance() {
        ManualClock clock = new ManualClock();
        ClientTaskQueue queue = new ClientTaskQueue(clock);
        TaskOwner owner = new TaskOwner("owner");
        List<String> calls = new ArrayList<>();

        queue.schedule(owner, 0L, () -> {
            calls.add("outer");
            queue.schedule(owner, 0L, () -> calls.add("deferred"));
            IllegalStateException error = assertThrows(IllegalStateException.class, queue::advance);
            assertEquals("advance already in progress", error.getMessage());
            assertEquals(List.of("outer"), calls);
        });

        assertEquals(1, queue.advance());
        assertEquals(List.of("outer"), calls);
        assertEquals(1, queue.pendingTaskCount());

        assertEquals(1, queue.advance());
        assertEquals(List.of("outer", "deferred"), calls);
        assertEquals(0, queue.pendingTaskCount());
    }

    @Test
    void callbackFailureDoesNotPermanentlyBlockAdvance() {
        ManualClock clock = new ManualClock();
        ClientTaskQueue queue = new ClientTaskQueue(clock);
        TaskOwner owner = new TaskOwner("owner");
        List<String> calls = new ArrayList<>();

        queue.schedule(owner, 0L, () -> {
            throw new IllegalStateException("callback failed");
        });
        queue.schedule(owner, 0L, () -> calls.add("recovered"));

        IllegalStateException error = assertThrows(IllegalStateException.class, queue::advance);
        assertEquals("callback failed", error.getMessage());
        assertEquals(1, queue.pendingTaskCount());

        assertEquals(1, queue.advance());
        assertEquals(List.of("recovered"), calls);
        assertEquals(0, queue.pendingTaskCount());
    }

    @Test
    void validatesOwnersTasksAndForeignHandles() {
        ManualClock clock = new ManualClock();
        ClientTaskQueue queue = new ClientTaskQueue(clock);
        ClientTaskQueue otherQueue = new ClientTaskQueue(clock);
        TaskOwner owner = new TaskOwner("owner");
        TaskHandle foreign = otherQueue.schedule(owner, 0L, () -> { });

        assertThrows(NullPointerException.class, () -> queue.schedule(null, 0L, () -> { }));
        assertThrows(NullPointerException.class, () -> queue.schedule(owner, 0L, null));
        assertThrows(NullPointerException.class, () -> queue.cancel((TaskHandle) null));
        assertThrows(NullPointerException.class, () -> queue.cancel((TaskOwner) null));
        assertThrows(IllegalArgumentException.class, () -> queue.cancel(foreign));
        assertThrows(IllegalArgumentException.class, () -> new TaskOwner("  "));
    }
}
