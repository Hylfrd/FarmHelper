package dev.hylfrd.farmhelper.control.inventory;

import dev.hylfrd.farmhelper.runtime.time.ClientTaskQueue;
import dev.hylfrd.farmhelper.runtime.time.TaskOwner;

import java.util.Objects;

/** Narrow scheduling seam over the shared T1 client task queue. */
public interface InventoryTaskScheduler {
    void schedule(TaskOwner owner, long delayNanos, Runnable task);

    void cancel(TaskOwner owner);

    static InventoryTaskScheduler from(ClientTaskQueue queue) {
        Objects.requireNonNull(queue, "queue");
        return new InventoryTaskScheduler() {
            @Override
            public void schedule(TaskOwner owner, long delayNanos, Runnable task) {
                queue.schedule(owner, delayNanos, task);
            }

            @Override
            public void cancel(TaskOwner owner) {
                queue.cancel(owner);
            }
        };
    }
}
