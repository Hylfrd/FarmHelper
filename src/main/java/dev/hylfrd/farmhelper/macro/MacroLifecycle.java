package dev.hylfrd.farmhelper.macro;

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
    private final Map<Long, String> featureLeases = new HashMap<>();
    private MacroState state = MacroState.STOPPED;
    private boolean manualPause;
    private boolean screenOpen;
    private long generation;
    private long nextFeatureLease = 1L;

    public MacroLifecycle(MacroLifecycleTarget target) {
        this.target = Objects.requireNonNull(target, "target");
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
        return Set.copyOf(causes);
    }

    public void start(long nowNanos) {
        if (state != MacroState.STOPPED) {
            throw new IllegalStateException("macro run is already active");
        }
        generation = nextGeneration();
        manualPause = false;
        screenOpen = false;
        featureLeases.clear();
        state = MacroState.RUNNING;
        try {
            target.start(generation, nowNanos);
        } catch (RuntimeException | Error failure) {
            state = MacroState.STOPPED;
            generation = nextGeneration();
            throw failure;
        }
    }

    public void manualPause(long nowNanos) {
        if (state == MacroState.STOPPED || manualPause) {
            return;
        }
        manualPause = true;
        refreshPause(nowNanos);
    }

    public void manualResume(long nowNanos) {
        if (state == MacroState.STOPPED || !manualPause) {
            return;
        }
        manualPause = false;
        refreshPause(nowNanos);
    }

    public void screenOpen(long nowNanos) {
        if (state == MacroState.STOPPED || screenOpen) {
            return;
        }
        screenOpen = true;
        refreshPause(nowNanos);
    }

    public void screenClosed(long nowNanos) {
        if (state == MacroState.STOPPED || !screenOpen) {
            return;
        }
        screenOpen = false;
        refreshPause(nowNanos);
    }

    public FeatureSuspension suspendForFeature(String owner, long nowNanos) {
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
        refreshPause(nowNanos);
        return new FeatureSuspension(normalized, () -> releaseFeature(leaseGeneration, lease, nowNanos));
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
        featureLeases.clear();
        generation = nextGeneration();
        target.stop(stoppedGeneration, reason);
    }

    public boolean accepts(long candidateGeneration) {
        return state != MacroState.STOPPED && generation == candidateGeneration;
    }

    private void releaseFeature(long leaseGeneration, long lease, long nowNanos) {
        if (state == MacroState.STOPPED || generation != leaseGeneration || featureLeases.remove(lease) == null) {
            return;
        }
        refreshPause(nowNanos);
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
