package dev.hylfrd.farmhelper.control.expectation;

import dev.hylfrd.farmhelper.control.input.ControlOwner;
import dev.hylfrd.farmhelper.runtime.time.MonotonicClock;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Bounded, runtime-owned expected motion/rotation/teleport publication ledger. */
public final class ExpectedActionLedger {
    public static final int MAX_ENTRIES = 256;

    private final MonotonicClock clock;
    private final Runnable publicationGuard;
    private final int entryLimit;
    private final Map<ActionToken, ExpectedAction> entries = new LinkedHashMap<>();
    private long lastToken;

    public ExpectedActionLedger(MonotonicClock clock) {
        this(clock, () -> { });
    }

    public ExpectedActionLedger(MonotonicClock clock, Runnable publicationGuard) {
        this(clock, publicationGuard, MAX_ENTRIES, 0L);
    }

    ExpectedActionLedger(MonotonicClock clock, int entryLimit, long lastToken) {
        this(clock, () -> { }, entryLimit, lastToken);
    }

    ExpectedActionLedger(
            MonotonicClock clock,
            Runnable publicationGuard,
            int entryLimit,
            long lastToken
    ) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.publicationGuard = Objects.requireNonNull(publicationGuard, "publicationGuard");
        if (entryLimit < 1 || entryLimit > MAX_ENTRIES) {
            throw new IllegalArgumentException("entryLimit must be in [1, " + MAX_ENTRIES + "]");
        }
        if (lastToken < 0L) {
            throw new IllegalArgumentException("lastToken must be non-negative");
        }
        this.entryLimit = entryLimit;
        this.lastToken = lastToken;
    }

    public synchronized ActionToken publish(
            ControlOwner owner,
            long generation,
            long worldEpoch,
            long validFromNanos,
            long deadlineNanos,
            ExpectedActionPayload payload
    ) {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(payload, "payload");
        publicationGuard.run();
        long now = clock.nowNanos();
        pruneExpiredAt(now);
        if (deadlineNanos <= now) {
            throw new IllegalArgumentException("cannot publish an already-expired expectation");
        }
        if (generation <= 0L) {
            throw new IllegalArgumentException("generation must be positive");
        }
        if (worldEpoch < 0L) {
            throw new IllegalArgumentException("worldEpoch must be non-negative");
        }
        if (deadlineNanos <= validFromNanos) {
            throw new IllegalArgumentException("deadline must be after validFrom");
        }
        if (entries.size() >= entryLimit) {
            throw new IllegalStateException("expected action ledger is full");
        }
        if (lastToken == Long.MAX_VALUE) {
            throw new IllegalStateException("expected action token sequence exhausted");
        }
        ActionToken token = new ActionToken(++lastToken);
        ExpectedAction action = new ExpectedAction(
                token, owner, generation, worldEpoch, validFromNanos, deadlineNanos, payload);
        entries.put(token, action);
        return token;
    }

    public synchronized boolean complete(
            ActionToken token,
            ControlOwner owner,
            long generation
    ) {
        return removeExact(token, owner, generation);
    }

    public synchronized boolean cancel(
            ActionToken token,
            ControlOwner owner,
            long generation
    ) {
        return removeExact(token, owner, generation);
    }

    public synchronized int clear(ControlOwner owner, long generation) {
        Objects.requireNonNull(owner, "owner");
        int before = entries.size();
        entries.values().removeIf(action ->
                action.owner().equals(owner) && action.generation() == generation);
        return before - entries.size();
    }

    public synchronized int clear(ControlOwner owner) {
        Objects.requireNonNull(owner, "owner");
        int before = entries.size();
        entries.values().removeIf(action -> action.owner().equals(owner));
        return before - entries.size();
    }

    public synchronized int clearAll() {
        int count = entries.size();
        entries.clear();
        return count;
    }

    public synchronized int pruneExpired() {
        return pruneExpiredAt(clock.nowNanos());
    }

    public synchronized ExpectedActionSnapshot snapshot() {
        long now = clock.nowNanos();
        pruneExpiredAt(now);
        return new ExpectedActionSnapshot(now, new ArrayList<>(entries.values()));
    }

    private boolean removeExact(ActionToken token, ControlOwner owner, long generation) {
        Objects.requireNonNull(token, "token");
        Objects.requireNonNull(owner, "owner");
        ExpectedAction action = entries.get(token);
        if (action == null || !action.owner().equals(owner) || action.generation() != generation) {
            return false;
        }
        entries.remove(token);
        return true;
    }

    private int pruneExpiredAt(long nowNanos) {
        int before = entries.size();
        entries.values().removeIf(action -> action.expiredAt(nowNanos));
        return before - entries.size();
    }
}
