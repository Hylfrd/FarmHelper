package dev.hylfrd.farmhelper.macro;

import java.util.Set;

/** One generation-aware macro run controlled by {@link MacroLifecycle}. */
public interface MacroLifecycleTarget {
    void start(long generation, long nowNanos);

    void pause(long generation, long nowNanos, Set<MacroPauseCause> causes);

    void resume(long generation, long nowNanos);

    void stop(long generation, MacroTerminalReason reason);
}
