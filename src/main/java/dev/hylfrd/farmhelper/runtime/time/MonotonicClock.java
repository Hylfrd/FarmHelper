package dev.hylfrd.farmhelper.runtime.time;

/** A time source suitable for measuring elapsed time, but not wall-clock time. */
@FunctionalInterface
public interface MonotonicClock {
    long nowNanos();
}
