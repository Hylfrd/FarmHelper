package dev.hylfrd.farmhelper.macro;

import java.util.Set;

/**
 * One observer for side effects that must follow the existing macro lifecycle target.
 *
 * <p>This is an observer only; {@link MacroLifecycle} remains the one state authority.</p>
 */
public interface MacroLifecycleParticipant {
    void started(long generation, long nowNanos);

    void paused(long generation, long nowNanos, Set<MacroPauseCause> causes);

    void resumed(long generation, long nowNanos);

    void stopped(long generation, MacroTerminalReason reason);
}
