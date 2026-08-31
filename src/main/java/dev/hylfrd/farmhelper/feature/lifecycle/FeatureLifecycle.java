package dev.hylfrd.farmhelper.feature.lifecycle;

import dev.hylfrd.farmhelper.failsafe.FailsafeArbitrator;
import dev.hylfrd.farmhelper.failsafe.FailsafeCandidate;
import dev.hylfrd.farmhelper.macro.FeatureSuspension;
import dev.hylfrd.farmhelper.macro.MacroLifecycle;
import dev.hylfrd.farmhelper.macro.MacroPauseCause;
import dev.hylfrd.farmhelper.macro.MacroState;
import dev.hylfrd.farmhelper.macro.MacroTerminalReason;

import java.util.ArrayList;
import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Ordered, domain-only owner of feature runtime state.
 *
 * <p>Every active macro-pausing feature owns one {@link FeatureSuspension}. Detector admission and
 * selection must pass through this class so an explicit foreground feature policy can suppress
 * failsafes without teaching the arbitrator about features. Cross-object transitions are intended
 * for one client thread; synchronization only prevents the manager's own state from tearing.</p>
 */
public final class FeatureLifecycle {
    private final MacroLifecycle macroLifecycle;
    private final FailsafeArbitrator failsafeArbitrator;
    private final List<Entry> registrationOrder;
    private final Map<FeatureId, Entry> entriesById;
    private FeatureRunIdentity identity;
    private boolean macroPaused;
    private Set<MacroPauseCause> macroPauseCauses = Set.of();

    public FeatureLifecycle(
            MacroLifecycle macroLifecycle,
            FailsafeArbitrator failsafeArbitrator,
            FeatureRegistration... registrations
    ) {
        this(macroLifecycle, failsafeArbitrator, copyVarargs(registrations));
    }

    public FeatureLifecycle(
            MacroLifecycle macroLifecycle,
            FailsafeArbitrator failsafeArbitrator,
            Collection<FeatureRegistration> registrations
    ) {
        this.macroLifecycle = Objects.requireNonNull(macroLifecycle, "macroLifecycle");
        this.failsafeArbitrator = Objects.requireNonNull(failsafeArbitrator, "failsafeArbitrator");
        Objects.requireNonNull(registrations, "registrations");

        List<Entry> ordered = new ArrayList<>(registrations.size());
        java.util.LinkedHashMap<FeatureId, Entry> indexed = new java.util.LinkedHashMap<>();
        Set<FeatureLifecycleTarget> targets = java.util.Collections.newSetFromMap(
                new IdentityHashMap<>());
        for (FeatureRegistration registration : registrations) {
            Objects.requireNonNull(registration, "registration");
            if (!targets.add(registration.target())) {
                throw new IllegalArgumentException("duplicate feature target: " + registration.id());
            }
            Entry entry = new Entry(registration);
            if (indexed.putIfAbsent(registration.id(), entry) != null) {
                throw new IllegalArgumentException("duplicate feature id: " + registration.id());
            }
            ordered.add(entry);
        }
        registrationOrder = List.copyOf(ordered);
        entriesById = Map.copyOf(indexed);
    }

    public synchronized List<FeatureId> registeredFeatures() {
        return registrationOrder.stream().map(Entry::id).toList();
    }

    public synchronized Optional<FeatureRunIdentity> identity() {
        return Optional.ofNullable(identity);
    }

    public synchronized FeatureState state(FeatureId id) {
        return entry(id).state;
    }

    public synchronized List<FeatureId> activeFeatures() {
        return registrationOrder.stream()
                .filter(entry -> entry.state.active())
                .map(Entry::id)
                .toList();
    }

    public synchronized List<FeatureId> macroPausingFeatures() {
        return registrationOrder.stream()
                .filter(entry -> entry.state.active() && entry.registration.policy().pausesMacro())
                .map(Entry::id)
                .toList();
    }

    public synchronized List<FeatureId> failsafeSuppressors() {
        return suppressors();
    }

    public synchronized boolean canCheckFailsafes() {
        return identity != null && suppressors().isEmpty();
    }

    /**
     * Binds one exact macro/world run, resets failsafe identity, and snapshots all auto-start
     * eligibility before invoking any feature callback.
     */
    public synchronized FeatureLifecycleResult macroStarted(long generation, long worldEpoch) {
        FeatureRunIdentity next = new FeatureRunIdentity(generation, worldEpoch);
        if (identity != null) {
            if (identity.equals(next)) {
                return FeatureLifecycleResult.unchanged();
            }
            throw new IllegalStateException("a feature macro run is already active: " + identity);
        }
        if (!macroLifecycle.accepts(generation)) {
            throw new IllegalArgumentException("macro generation is not active: " + generation);
        }

        FailureCollector failures = new FailureCollector();
        List<Entry> eligible = new ArrayList<>();
        for (Entry entry : registrationOrder) {
            if (!entry.registration.policy().startAtMacroStart()) {
                continue;
            }
            try {
                if (entry.registration.enabledNow()) {
                    eligible.add(entry);
                }
            } catch (RuntimeException | Error failure) {
                failures.add(entry.id(), "eligibility", failure);
            }
        }
        if (failures.failed()) {
            return failures.result(false);
        }

        identity = next;
        macroPaused = macroLifecycle.state() == MacroState.PAUSED;
        macroPauseCauses = macroPaused ? macroLifecycle.pauseCauses() : Set.of();
        failsafeArbitrator.reset(generation, worldEpoch);
        for (Entry entry : eligible) {
            FeatureLifecycleResult activation = activate(entry);
            failures.merge(activation);
            if (activation.failed()) {
                break;
            }
        }
        if (failures.failed()) {
            stopEntries(Set.of(), FeatureStopCause.START_ROLLBACK, failures, false);
            identity = null;
            macroPaused = false;
            macroPauseCauses = Set.of();
            failsafeArbitrator.cancel();
            return failures.result(false);
        }
        return failures.result(true);
    }

    public synchronized FeatureLifecycleResult macroPaused(
            long generation,
            Set<MacroPauseCause> causes
    ) {
        Objects.requireNonNull(causes, "causes");
        Set<MacroPauseCause> copiedCauses = Set.copyOf(causes);
        if (copiedCauses.isEmpty()) {
            throw new IllegalArgumentException("a paused macro must have at least one cause");
        }
        if (!matches(generation)) {
            return FeatureLifecycleResult.unchanged();
        }
        macroPauseCauses = copiedCauses;
        if (macroPaused) {
            return FeatureLifecycleResult.unchanged();
        }

        macroPaused = true;
        FailureCollector failures = new FailureCollector();
        boolean changed = false;
        for (Entry entry : registrationOrder) {
            if (entry.state == FeatureState.RUNNING
                    && entry.registration.policy().pauseWithMacro()) {
                changed = true;
                pause(entry, failures);
            }
        }
        return failures.result(changed);
    }

    public synchronized FeatureLifecycleResult macroResumed(long generation) {
        if (!matches(generation) || !macroPaused) {
            return FeatureLifecycleResult.unchanged();
        }
        macroPaused = false;
        macroPauseCauses = Set.of();
        FailureCollector failures = new FailureCollector();
        boolean changed = false;
        for (Entry entry : registrationOrder) {
            if (entry.state == FeatureState.PAUSED) {
                changed = true;
                resume(entry, failures);
            }
        }
        return failures.result(changed);
    }

    /** Commits the generation fence before best-effort feature cleanup. */
    public synchronized FeatureLifecycleResult macroStopped(
            long generation,
            MacroTerminalReason reason
    ) {
        Objects.requireNonNull(reason, "reason");
        if (!matches(generation)) {
            return FeatureLifecycleResult.unchanged();
        }

        FailureCollector failures = new FailureCollector();
        FeatureRunIdentity stoppedIdentity = identity;
        identity = null;
        macroPaused = false;
        macroPauseCauses = Set.of();
        stopEntries(
                Set.of(), FeatureStopCause.MACRO_STOPPED, failures, false, stoppedIdentity);
        failsafeArbitrator.cancel();
        return failures.result(true);
    }

    /** Enables one currently eligible feature; duplicate enable is inert. */
    public synchronized FeatureLifecycleResult enable(FeatureId id) {
        Entry entry = entry(id);
        requireActiveMacro();
        if (entry.state.active()) {
            return FeatureLifecycleResult.unchanged();
        }
        try {
            if (!entry.registration.enabledNow()) {
                return FeatureLifecycleResult.unchanged();
            }
        } catch (RuntimeException | Error failure) {
            FailureCollector failures = new FailureCollector();
            failures.add(entry.id(), "eligibility", failure);
            return failures.result(false);
        }
        return activate(entry);
    }

    public synchronized FeatureLifecycleResult stop(FeatureId id, FeatureStopCause cause) {
        Objects.requireNonNull(cause, "cause");
        Entry entry = entry(id);
        if (!entry.state.active()) {
            return FeatureLifecycleResult.unchanged();
        }
        FailureCollector failures = new FailureCollector();
        stop(entry, cause, failures, true);
        return failures.result(true);
    }

    public synchronized FeatureLifecycleResult stopAll(FeatureStopCause cause) {
        return stopAllExcept(Set.of(), cause);
    }

    /** Stops active features in registration order while retaining the named parent/peer set. */
    public synchronized FeatureLifecycleResult stopAllExcept(
            Set<FeatureId> retained,
            FeatureStopCause cause
    ) {
        Objects.requireNonNull(retained, "retained");
        Objects.requireNonNull(cause, "cause");
        Set<FeatureId> copied = Set.copyOf(retained);
        for (FeatureId id : copied) {
            entry(id);
        }
        FailureCollector failures = new FailureCollector();
        boolean changed = stopEntries(copied, cause, failures, true);
        return failures.result(changed);
    }

    /** Resets every registration in deterministic order; active features must be stopped first. */
    public synchronized FeatureLifecycleResult resetAll() {
        if (!activeFeatures().isEmpty()) {
            throw new IllegalStateException("active features must be stopped before reset");
        }
        FailureCollector failures = new FailureCollector();
        for (Entry entry : registrationOrder) {
            try {
                entry.registration.target().reset();
            } catch (RuntimeException | Error failure) {
                failures.add(entry.id(), "reset", failure);
            }
        }
        return failures.result(!registrationOrder.isEmpty());
    }

    /** Applies active feature policy before delegating to the identity-bound arbitrator. */
    public synchronized FeatureFailsafeSubmission submitFailsafe(FailsafeCandidate candidate) {
        Objects.requireNonNull(candidate, "candidate");
        if (identity == null) {
            return new FeatureFailsafeSubmission(
                    FeatureFailsafeSubmission.Status.INACTIVE,
                    candidate,
                    List.of(),
                    Optional.empty());
        }
        List<FeatureId> suppressors = suppressors();
        if (!suppressors.isEmpty()) {
            return new FeatureFailsafeSubmission(
                    FeatureFailsafeSubmission.Status.SUPPRESSED,
                    candidate,
                    suppressors,
                    Optional.empty());
        }
        return new FeatureFailsafeSubmission(
                FeatureFailsafeSubmission.Status.ARBITRATED,
                candidate,
                List.of(),
                Optional.of(failsafeArbitrator.submit(candidate)));
    }

    /** A selected candidate is never surfaced while a foreground feature suppresses checks. */
    public synchronized Optional<FailsafeCandidate> selectFailsafeIfReady() {
        return canCheckFailsafes()
                ? failsafeArbitrator.selectIfReady()
                : Optional.empty();
    }

    private FeatureLifecycleResult activate(Entry entry) {
        FailureCollector failures = new FailureCollector();
        FeatureRunIdentity run = requireActiveMacro();
        entry.state = FeatureState.RUNNING;

        if (entry.registration.policy().failsafePolicy()
                == FeatureFailsafePolicy.SUPPRESS_CHECKS) {
            // Evidence admitted before a foreground transition is no longer current.
            failsafeArbitrator.cancel();
        }
        try {
            if (entry.registration.policy().pausesMacro()) {
                entry.suspension = macroLifecycle.suspendForFeature(entry.id().value());
            }
            entry.registration.target().enable(run);
        } catch (RuntimeException | Error failure) {
            failures.add(entry.id(), "enable", failure);
            stop(entry, FeatureStopCause.CALLBACK_FAILED, failures, true);
            return failures.result(false);
        }

        failures.merge(reconcileMacroState());
        return failures.result(true);
    }

    private void pause(Entry entry, FailureCollector failures) {
        FeatureRunIdentity run = identity;
        entry.state = FeatureState.PAUSED;
        try {
            entry.registration.target().pause(run, macroPauseCauses);
        } catch (RuntimeException | Error failure) {
            failures.add(entry.id(), "pause", failure);
            stop(entry, FeatureStopCause.CALLBACK_FAILED, failures, true);
        }
    }

    private void resume(Entry entry, FailureCollector failures) {
        FeatureRunIdentity run = identity;
        entry.state = FeatureState.RUNNING;
        try {
            entry.registration.target().resume(run);
        } catch (RuntimeException | Error failure) {
            failures.add(entry.id(), "resume", failure);
            stop(entry, FeatureStopCause.CALLBACK_FAILED, failures, true);
        }
    }

    private void stop(
            Entry entry,
            FeatureStopCause cause,
            FailureCollector failures,
            boolean reconcile
    ) {
        stop(entry, cause, failures, reconcile, identity);
    }

    private void stop(
            Entry entry,
            FeatureStopCause cause,
            FailureCollector failures,
            boolean reconcile,
            FeatureRunIdentity stoppedIdentity
    ) {
        if (!entry.state.active()) {
            return;
        }
        FeatureSuspension suspension = entry.suspension;
        entry.suspension = null;
        entry.state = FeatureState.STOPPED;
        try {
            if (stoppedIdentity != null) {
                entry.registration.target().stop(stoppedIdentity, cause);
            }
        } catch (RuntimeException | Error failure) {
            failures.add(entry.id(), "stop", failure);
        } finally {
            if (suspension != null) {
                try {
                    suspension.close();
                } catch (RuntimeException | Error failure) {
                    failures.add(entry.id(), "release macro suspension", failure);
                }
            }
        }
        if (reconcile) {
            failures.merge(reconcileMacroState());
        }
    }

    private boolean stopEntries(
            Set<FeatureId> retained,
            FeatureStopCause cause,
            FailureCollector failures,
            boolean reconcile
    ) {
        return stopEntries(retained, cause, failures, reconcile, identity);
    }

    private boolean stopEntries(
            Set<FeatureId> retained,
            FeatureStopCause cause,
            FailureCollector failures,
            boolean reconcile,
            FeatureRunIdentity stoppedIdentity
    ) {
        boolean changed = false;
        for (Entry entry : registrationOrder) {
            if (entry.state.active() && !retained.contains(entry.id())) {
                changed = true;
                stop(entry, cause, failures, false, stoppedIdentity);
            }
        }
        if (reconcile) {
            failures.merge(reconcileMacroState());
        }
        return changed;
    }

    private FeatureLifecycleResult reconcileMacroState() {
        if (identity == null) {
            return FeatureLifecycleResult.unchanged();
        }
        return switch (macroLifecycle.state()) {
            case RUNNING -> macroResumed(identity.macroGeneration());
            case PAUSED -> macroPaused(identity.macroGeneration(), macroLifecycle.pauseCauses());
            case STOPPED -> FeatureLifecycleResult.unchanged();
        };
    }

    private List<FeatureId> suppressors() {
        return registrationOrder.stream()
                .filter(entry -> entry.state.active()
                        && entry.registration.policy().failsafePolicy()
                        == FeatureFailsafePolicy.SUPPRESS_CHECKS)
                .map(Entry::id)
                .toList();
    }

    private boolean matches(long generation) {
        return identity != null && identity.matchesMacro(generation);
    }

    private FeatureRunIdentity requireActiveMacro() {
        if (identity == null) {
            throw new IllegalStateException("no feature macro run is active");
        }
        if (!macroLifecycle.accepts(identity.macroGeneration())) {
            throw new IllegalStateException("feature macro run is no longer accepted: " + identity);
        }
        return identity;
    }

    private Entry entry(FeatureId id) {
        Objects.requireNonNull(id, "id");
        Entry entry = entriesById.get(id);
        if (entry == null) {
            throw new IllegalArgumentException("feature is not registered: " + id);
        }
        return entry;
    }

    private static List<FeatureRegistration> copyVarargs(FeatureRegistration[] registrations) {
        Objects.requireNonNull(registrations, "registrations");
        return List.of(registrations.clone());
    }

    private static final class Entry {
        private final FeatureRegistration registration;
        private FeatureState state = FeatureState.STOPPED;
        private FeatureSuspension suspension;

        private Entry(FeatureRegistration registration) {
            this.registration = registration;
        }

        private FeatureId id() {
            return registration.id();
        }
    }

    private static final class FailureCollector {
        private RuntimeException aggregate;

        private void add(FeatureId id, String operation, Throwable failure) {
            if (aggregate == null) {
                aggregate = new RuntimeException("one or more feature lifecycle callbacks failed");
            }
            aggregate.addSuppressed(new RuntimeException(
                    "feature " + id + " failed during " + operation, failure));
        }

        private void merge(FeatureLifecycleResult result) {
            result.failure().ifPresent(failure -> {
                if (aggregate == null) {
                    aggregate = new RuntimeException(
                            "one or more feature lifecycle callbacks failed");
                }
                for (Throwable suppressed : failure.getSuppressed()) {
                    aggregate.addSuppressed(suppressed);
                }
            });
        }

        private boolean failed() {
            return aggregate != null;
        }

        private FeatureLifecycleResult result(boolean changed) {
            return new FeatureLifecycleResult(changed, Optional.ofNullable(aggregate));
        }
    }
}
