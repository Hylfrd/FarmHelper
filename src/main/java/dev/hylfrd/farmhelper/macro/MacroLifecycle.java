package dev.hylfrd.farmhelper.macro;

import dev.hylfrd.farmhelper.runtime.time.MonotonicClock;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Reason-sensitive macro lifecycle. It deliberately has a private run generation and does not
 * duplicate the client-wide ownership fence.
 */
public final class MacroLifecycle {
    private final MacroLifecycleTarget target;
    private final MonotonicClock clock;
    private final Map<Long, String> featureLeases = new HashMap<>();
    private MacroState state = MacroState.STOPPED;
    private boolean manualPause;
    private boolean screenOpen;
    private boolean environmentUnavailable;
    private long generation;
    private long nextFeatureLease = 1L;

    public MacroLifecycle(MacroLifecycleTarget target, MonotonicClock clock) {
        this.target = Objects.requireNonNull(target, "target");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public MacroState state() {
        return state;
    }

    public boolean running() {
        return state == MacroState.RUNNING;
    }

    public long generation() {
        return generation;
    }

    public Set<MacroPauseCause> pauseCauses() {
        java.util.EnumSet<MacroPauseCause> causes = java.util.EnumSet.noneOf(MacroPauseCause.class);
        if (manualPause) {
            causes.add(MacroPauseCause.MANUAL);
        }
        if (screenOpen) {
            causes.add(MacroPauseCause.SCREEN_OPEN);
        }
        if (!featureLeases.isEmpty()) {
            causes.add(MacroPauseCause.FEATURE);
        }
        if (environmentUnavailable) {
            causes.add(MacroPauseCause.ENVIRONMENT);
        }
        return Set.copyOf(causes);
    }

    public void start() {
        if (state != MacroState.STOPPED) {
            throw new IllegalStateException("macro run is already active");
        }
        generation = nextGeneration();
        manualPause = false;
        screenOpen = false;
        environmentUnavailable = false;
        featureLeases.clear();
        state = MacroState.RUNNING;
        try {
            target.start(generation, clock.nowNanos());
        } catch (RuntimeException | Error failure) {
            state = MacroState.STOPPED;
            generation = nextGeneration();
            throw failure;
        }
    }

    public void manualPause() {
        if (state == MacroState.STOPPED || manualPause) {
            return;
        }
        manualPause = true;
        refreshPause(clock.nowNanos());
    }

    public void manualResume() {
        if (state == MacroState.STOPPED || !manualPause) {
            return;
        }
        manualPause = false;
        refreshPause(clock.nowNanos());
    }

    public void screenOpen() {
        if (state == MacroState.STOPPED || screenOpen) {
            return;
        }
        screenOpen = true;
        refreshPause(clock.nowNanos());
    }

    public void screenClosed() {
        if (state == MacroState.STOPPED || !screenOpen) {
            return;
        }
        screenOpen = false;
        refreshPause(clock.nowNanos());
    }

    public void environmentUnavailable() {
        if (state == MacroState.STOPPED || environmentUnavailable) {
            return;
        }
        environmentUnavailable = true;
        refreshPause(clock.nowNanos());
    }

    public void environmentAvailable() {
        if (state == MacroState.STOPPED || !environmentUnavailable) {
            return;
        }
        environmentUnavailable = false;
        refreshPause(clock.nowNanos());
    }

    public FeatureSuspension suspendForFeature(String owner) {
        Objects.requireNonNull(owner, "owner");
        String normalized = owner.trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("feature suspension owner must not be blank");
        }
        if (state == MacroState.STOPPED) {
            throw new IllegalStateException("cannot suspend a stopped macro");
        }
        long lease = nextFeatureLease++;
        long leaseGeneration = generation;
        featureLeases.put(lease, normalized);
        refreshPause(clock.nowNanos());
        return new FeatureSuspension(normalized, () -> releaseFeature(leaseGeneration, lease));
    }

    public void stop(MacroTerminalReason reason) {
        Objects.requireNonNull(reason, "reason");
        if (state == MacroState.STOPPED) {
            return;
        }
        long stoppedGeneration = generation;
        state = MacroState.STOPPED;
        manualPause = false;
        screenOpen = false;
        environmentUnavailable = false;
        featureLeases.clear();
        generation = nextGeneration();
        target.stop(stoppedGeneration, reason);
    }

    public boolean accepts(long candidateGeneration) {
        return state != MacroState.STOPPED && generation == candidateGeneration;
    }

    private void releaseFeature(long leaseGeneration, long lease) {
        if (state == MacroState.STOPPED || generation != leaseGeneration || featureLeases.remove(lease) == null) {
            return;
        }
        refreshPause(clock.nowNanos());
    }

    private void refreshPause(long nowNanos) {
        Set<MacroPauseCause> causes = pauseCauses();
        if (!causes.isEmpty() && state == MacroState.RUNNING) {
            state = MacroState.PAUSED;
            target.pause(generation, nowNanos, causes);
        } else if (causes.isEmpty() && state == MacroState.PAUSED) {
            state = MacroState.RUNNING;
            target.resume(generation, nowNanos);
        }
    }

    private long nextGeneration() {
        if (generation == Long.MAX_VALUE) {
            throw new IllegalStateException("macro run generation exhausted");
        }
        return generation + 1L;
    }
}
