package dev.hylfrd.farmhelper.feature.leave;

/** Supplies one client-thread status snapshot without exposing runtime or platform ownership. */
@FunctionalInterface
public interface LeaveTimerStatusSource {
    LeaveTimerStatus currentStatus();
}
