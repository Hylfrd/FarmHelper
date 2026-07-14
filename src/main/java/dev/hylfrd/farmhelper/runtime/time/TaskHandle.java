package dev.hylfrd.farmhelper.runtime.time;

/** Handle returned by {@link ClientTaskQueue#schedule(TaskOwner, long, Runnable)}. */
public final class TaskHandle implements Cancellation {
    enum State {
        PENDING,
        RUNNING,
        COMPLETED,
        CANCELLED
    }

    private final ClientTaskQueue queue;
    private final TaskOwner owner;
    private State state = State.PENDING;

    TaskHandle(ClientTaskQueue queue, TaskOwner owner) {
        this.queue = queue;
        this.owner = owner;
    }

    public TaskOwner owner() {
        return owner;
    }

    @Override
    public boolean cancel() {
        return queue.cancel(this);
    }

    @Override
    public boolean cancelled() {
        return state == State.CANCELLED;
    }

    @Override
    public boolean done() {
        return state == State.COMPLETED || state == State.CANCELLED;
    }

    State state() {
        return state;
    }

    void state(State state) {
        this.state = state;
    }

    ClientTaskQueue queue() {
        return queue;
    }
}
