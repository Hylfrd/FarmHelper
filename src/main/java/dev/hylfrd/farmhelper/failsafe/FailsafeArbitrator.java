package dev.hylfrd.farmhelper.failsafe;

import dev.hylfrd.farmhelper.runtime.time.MonotonicClock;
import dev.hylfrd.farmhelper.runtime.time.PausableTimer;
import dev.hylfrd.farmhelper.runtime.time.SystemMonotonicClock;

import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.random.RandomGenerator;

/**
 * Domain-only failsafe candidate arbitration.
 *
 * <p>The arbitrator has no detector, client, reaction, notification, or remote-service knowledge.
 * A caller supplies immutable candidates and explicitly advances the current lifecycle identity.
 * The first candidate also establishes the identity when an unbound arbitrator is used.</p>
 */
public final class FailsafeArbitrator {
    public static final long MIN_CANDIDATE_DELAY_MILLIS = 2_000L;
    public static final long MAX_CANDIDATE_DELAY_MILLIS_EXCLUSIVE = 2_500L;
    private static final long CANDIDATE_DELAY_RANGE_MILLIS =
            MAX_CANDIDATE_DELAY_MILLIS_EXCLUSIVE - MIN_CANDIDATE_DELAY_MILLIS;
    private static final long NANOS_PER_MILLISECOND = 1_000_000L;

    /** Injectable delay selection seam; the production constructor wraps a {@link RandomGenerator}. */
    @FunctionalInterface
    public interface DelaySource {
        long nextDelayMillis();
    }

    public enum SubmissionStatus {
        ACCEPTED,
        DUPLICATE,
        STALE_IDENTITY,
        UNAVAILABLE,
        ALREADY_TRIGGERED
    }

    public record Submission(SubmissionStatus status, FailsafeCandidate candidate) {
        public Submission {
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(candidate, "candidate");
        }

        public boolean accepted() {
            return status == SubmissionStatus.ACCEPTED;
        }

        public boolean rejected() {
            return !accepted();
        }
    }

    private static final Comparator<PendingCandidate> PENDING_ORDER =
            Comparator.comparingInt((PendingCandidate pending) -> pending.candidate().type().priority())
                    .thenComparingLong(PendingCandidate::admissionOrder);

    private final MonotonicClock clock;
    private final DelaySource delaySource;
    private final Map<FailsafeType, PendingCandidate> pending = new EnumMap<>(FailsafeType.class);
    private boolean identityBound;
    private long currentMacroGeneration;
    private long currentWorldEpoch;
    private long nextAdmissionOrder = 1L;
    private PausableTimer candidateDelay;
    private long candidateDelayMillis;
    private FailsafeCandidate triggered;

    public FailsafeArbitrator() {
        this(SystemMonotonicClock.INSTANCE,
                randomDelaySource(RandomGenerator.getDefault()), false, 0L, 0L);
    }

    public FailsafeArbitrator(MonotonicClock clock, RandomGenerator random) {
        this(clock, randomDelaySource(random), false, 0L, 0L);
    }

    public FailsafeArbitrator(
            MonotonicClock clock,
            RandomGenerator random,
            long macroGeneration,
            long worldEpoch
    ) {
        this(clock, randomDelaySource(random), true, macroGeneration, worldEpoch);
    }

    public static FailsafeArbitrator withDelaySource(
            MonotonicClock clock,
            DelaySource delaySource
    ) {
        return new FailsafeArbitrator(clock, delaySource, false, 0L, 0L);
    }

    public static FailsafeArbitrator withDelaySource(
            MonotonicClock clock,
            DelaySource delaySource,
            long macroGeneration,
            long worldEpoch
    ) {
        return new FailsafeArbitrator(clock, delaySource, true, macroGeneration, worldEpoch);
    }

    private FailsafeArbitrator(
            MonotonicClock clock,
            DelaySource delaySource,
            boolean identityBound,
            long macroGeneration,
            long worldEpoch
    ) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.delaySource = Objects.requireNonNull(delaySource, "delaySource");
        validateIdentity(macroGeneration, worldEpoch);
        this.identityBound = identityBound;
        this.currentMacroGeneration = macroGeneration;
        this.currentWorldEpoch = worldEpoch;
    }

    public synchronized Optional<FailsafeCandidate.Identity> currentIdentity() {
        return identityBound
                ? Optional.of(new FailsafeCandidate.Identity(currentMacroGeneration, currentWorldEpoch))
                : Optional.empty();
    }

    public synchronized long currentMacroGeneration() {
        return currentMacroGeneration;
    }

    public synchronized long currentWorldEpoch() {
        return currentWorldEpoch;
    }

    public synchronized boolean identityBound() {
        return identityBound;
    }

    public synchronized boolean acceptsIdentity(FailsafeCandidate candidate) {
        Objects.requireNonNull(candidate, "candidate");
        return identityBound && candidate.matchesIdentity(currentMacroGeneration, currentWorldEpoch);
    }

    /**
     * Submits one candidate. The first accepted candidate schedules the shared delay; later
     * candidates join that same window and cannot resample it.
     */
    public synchronized Submission submit(FailsafeCandidate candidate) {
        Objects.requireNonNull(candidate, "candidate");
        if (!candidate.type().available()) {
            return new Submission(SubmissionStatus.UNAVAILABLE, candidate);
        }
        if (!identityBound) {
            identityBound = true;
            currentMacroGeneration = candidate.macroGeneration();
            currentWorldEpoch = candidate.worldEpoch();
        } else if (!candidate.matchesIdentity(currentMacroGeneration, currentWorldEpoch)) {
            return new Submission(SubmissionStatus.STALE_IDENTITY, candidate);
        }
        if (triggered != null) {
            return new Submission(SubmissionStatus.ALREADY_TRIGGERED, candidate);
        }
        if (pending.containsKey(candidate.type())) {
            return new Submission(SubmissionStatus.DUPLICATE, candidate);
        }

        if (pending.isEmpty()) {
            candidateDelayMillis = checkedDelayMillis(delaySource.nextDelayMillis());
            candidateDelay = new PausableTimer(clock, toNanos(candidateDelayMillis));
        }
        pending.put(candidate.type(), new PendingCandidate(candidate, nextAdmissionOrder()));
        return new Submission(SubmissionStatus.ACCEPTED, candidate);
    }

    public synchronized boolean admit(FailsafeCandidate candidate) {
        return submit(candidate).accepted();
    }

    public synchronized boolean offer(FailsafeCandidate candidate) {
        return admit(candidate);
    }

    public synchronized List<FailsafeCandidate> pending() {
        return pending.values().stream()
                .sorted(PENDING_ORDER)
                .map(PendingCandidate::candidate)
                .toList();
    }

    public synchronized List<FailsafeCandidate> queued() {
        return pending();
    }

    public synchronized boolean hasPending() {
        return !pending.isEmpty();
    }

    public synchronized boolean isDelayScheduled() {
        return candidateDelay != null;
    }

    public synchronized boolean delayDue() {
        return candidateDelay != null && candidateDelay.elapsed();
    }

    public synchronized OptionalLong pendingDelayMillis() {
        return candidateDelay == null
                ? OptionalLong.empty()
                : OptionalLong.of(candidateDelayMillis);
    }

    public synchronized OptionalLong remainingDelayMillis() {
        if (candidateDelay == null) {
            return OptionalLong.empty();
        }
        long remainingNanos = candidateDelay.remainingNanos();
        long roundedUp = remainingNanos == 0L
                ? 0L
                : (remainingNanos - 1L) / NANOS_PER_MILLISECOND + 1L;
        return OptionalLong.of(roundedUp);
    }

    /** Selects the lowest-priority pending detector once the shared delay has elapsed. */
    public synchronized Optional<FailsafeCandidate> selectIfReady() {
        if (triggered != null || pending.isEmpty() || !delayDue()) {
            return Optional.empty();
        }
        PendingCandidate selected = pending.values().stream().min(PENDING_ORDER).orElseThrow();
        pending.clear();
        candidateDelay = null;
        candidateDelayMillis = 0L;
        triggered = selected.candidate();
        return Optional.of(triggered);
    }

    public synchronized Optional<FailsafeCandidate> poll() {
        return selectIfReady();
    }

    public synchronized Optional<FailsafeCandidate> triggered() {
        return Optional.ofNullable(triggered);
    }

    public synchronized boolean isTriggered() {
        return triggered != null;
    }

    /** Cancels all queued and triggered state while retaining the current lifecycle identity. */
    public synchronized void cancel() {
        clearState();
    }

    /** Cancels only an exact candidate, so a stale callback cannot clear a newer lifecycle. */
    public synchronized boolean cancel(FailsafeCandidate candidate) {
        Objects.requireNonNull(candidate, "candidate");
        if (triggered != null && triggered.equals(candidate)) {
            triggered = null;
            return true;
        }
        PendingCandidate current = pending.get(candidate.type());
        if (current == null || !current.candidate().equals(candidate)) {
            return false;
        }
        pending.remove(candidate.type());
        if (pending.isEmpty()) {
            candidateDelay = null;
            candidateDelayMillis = 0L;
        }
        return true;
    }

    /** Clears all state and reuses the currently bound identity for a fresh admission window. */
    public synchronized void reset() {
        clearState();
    }

    /** Advances the accepted lifecycle identity and clears every state from the previous run. */
    public synchronized void reset(long macroGeneration, long worldEpoch) {
        validateIdentity(macroGeneration, worldEpoch);
        currentMacroGeneration = macroGeneration;
        currentWorldEpoch = worldEpoch;
        identityBound = true;
        clearState();
    }

    public synchronized void setIdentity(long macroGeneration, long worldEpoch) {
        reset(macroGeneration, worldEpoch);
    }

    public synchronized void updateIdentity(long macroGeneration, long worldEpoch) {
        reset(macroGeneration, worldEpoch);
    }

    private void clearState() {
        pending.clear();
        candidateDelay = null;
        candidateDelayMillis = 0L;
        triggered = null;
    }

    private long nextAdmissionOrder() {
        if (nextAdmissionOrder == Long.MAX_VALUE) {
            throw new IllegalStateException("failsafe admission sequence exhausted");
        }
        return nextAdmissionOrder++;
    }

    private static DelaySource randomDelaySource(RandomGenerator random) {
        Objects.requireNonNull(random, "random");
        return () -> MIN_CANDIDATE_DELAY_MILLIS + random.nextLong(CANDIDATE_DELAY_RANGE_MILLIS);
    }

    private static long checkedDelayMillis(long delayMillis) {
        if (delayMillis < MIN_CANDIDATE_DELAY_MILLIS
                || delayMillis >= MAX_CANDIDATE_DELAY_MILLIS_EXCLUSIVE) {
            throw new IllegalArgumentException(
                    "candidate delay must be in [2000,2500) ms: " + delayMillis);
        }
        return delayMillis;
    }

    private static long toNanos(long delayMillis) {
        return Math.multiplyExact(delayMillis, NANOS_PER_MILLISECOND);
    }

    private static void validateIdentity(long macroGeneration, long worldEpoch) {
        if (macroGeneration < 0L) {
            throw new IllegalArgumentException("macroGeneration must be non-negative");
        }
        if (worldEpoch < 0L) {
            throw new IllegalArgumentException("worldEpoch must be non-negative");
        }
    }

    private record PendingCandidate(FailsafeCandidate candidate, long admissionOrder) {
        private PendingCandidate {
            Objects.requireNonNull(candidate, "candidate");
            if (admissionOrder <= 0L) {
                throw new IllegalArgumentException("admissionOrder must be positive");
            }
        }
    }
}
