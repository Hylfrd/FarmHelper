package dev.hylfrd.farmhelper.runtime.time;

/** Cancellation state for one scheduled task. */
public interface Cancellation {
    boolean cancel();

    boolean cancelled();

    boolean done();
}
