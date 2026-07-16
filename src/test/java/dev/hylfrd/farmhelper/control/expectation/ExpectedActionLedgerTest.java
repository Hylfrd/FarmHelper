package dev.hylfrd.farmhelper.control.expectation;

import dev.hylfrd.farmhelper.control.input.ControlOwner;
import dev.hylfrd.farmhelper.runtime.snapshot.MotionSnapshot;
import dev.hylfrd.farmhelper.runtime.snapshot.PositionSnapshot;
import dev.hylfrd.farmhelper.runtime.snapshot.RotationSnapshot;
import dev.hylfrd.farmhelper.runtime.time.MonotonicClock;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExpectedActionLedgerTest {
    private static final ControlOwner OWNER = new ControlOwner("navigation");

    @Test
    void snapshotIsImmutableAndRetainsEveryTypedPayloadAndExactIdentity() {
        MutableClock clock = new MutableClock(10L);
        ExpectedActionLedger ledger = new ExpectedActionLedger(clock);
        ActionToken motion = ledger.publish(
                OWNER, 3L, 7L, 10L, 20L,
                new ExpectedMotion(new MotionSnapshot(1.0D, 0.0D, -1.0D), 0.1D));
        ActionToken rotation = ledger.publish(
                OWNER, 3L, 7L, 11L, 21L,
                new ExpectedRotation(new RotationSnapshot(45.0F, 5.0F), 2.0F, 1.0F));
        ActionToken teleport = ledger.publish(
                OWNER, 3L, 7L, 12L, 22L,
                new ExpectedTeleport(
                        new PositionSnapshot(0.0D, 70.0D, 0.0D),
                        new PositionSnapshot(5.0D, 71.0D, 5.0D), 0.5D));

        ExpectedActionSnapshot snapshot = ledger.snapshot();

        assertEquals(10L, snapshot.capturedAtNanos());
        assertEquals(3, snapshot.actions().size());
        assertEquals(motion, snapshot.actions().get(0).token());
        assertEquals(rotation, snapshot.actions().get(1).token());
        assertEquals(teleport, snapshot.actions().get(2).token());
        assertEquals(OWNER, snapshot.actions().getFirst().owner());
        assertEquals(3L, snapshot.actions().getFirst().generation());
        assertEquals(7L, snapshot.actions().getFirst().worldEpoch());
        assertInstanceOf(ExpectedMotion.class, snapshot.actions().get(0).payload());
        assertInstanceOf(ExpectedRotation.class, snapshot.actions().get(1).payload());
        assertInstanceOf(ExpectedTeleport.class, snapshot.actions().get(2).payload());
        assertThrows(UnsupportedOperationException.class,
                () -> snapshot.actions().clear());
    }

    @Test
    void staleTokenOwnerOrGenerationCannotClearAReplacement() {
        MutableClock clock = new MutableClock(0L);
        ExpectedActionLedger ledger = new ExpectedActionLedger(clock);
        ActionToken old = publish(ledger, OWNER, 1L, 10L);
        assertTrue(ledger.cancel(old, OWNER, 1L));
        ActionToken replacement = publish(ledger, OWNER, 2L, 20L);

        assertFalse(ledger.complete(old, OWNER, 1L));
        assertFalse(ledger.complete(replacement, new ControlOwner("other"), 2L));
        assertFalse(ledger.cancel(replacement, OWNER, 1L));
        assertEquals(1, ledger.snapshot().actions().size());
        assertEquals(replacement, ledger.snapshot().actions().getFirst().token());
        assertTrue(ledger.complete(replacement, OWNER, 2L));
        assertTrue(ledger.snapshot().actions().isEmpty());
    }

    @Test
    void exactGenerationOwnerAndGlobalClearsAreIndependent() {
        ExpectedActionLedger ledger = new ExpectedActionLedger(new MutableClock(0L));
        ControlOwner other = new ControlOwner("other");
        publish(ledger, OWNER, 1L, 10L);
        publish(ledger, OWNER, 2L, 20L);
        publish(ledger, other, 1L, 30L);

        assertEquals(1, ledger.clear(OWNER, 1L));
        assertEquals(2, ledger.snapshot().actions().size());
        assertEquals(1, ledger.clear(OWNER));
        assertEquals(other, ledger.snapshot().actions().getFirst().owner());
        assertEquals(1, ledger.clearAll());
        assertEquals(0, ledger.clearAll());
    }

    @Test
    void expiryIsDeterministicAtTheExclusiveDeadline() {
        MutableClock clock = new MutableClock(100L);
        ExpectedActionLedger ledger = new ExpectedActionLedger(clock);
        ActionToken token = ledger.publish(
                OWNER, 1L, 2L, 100L, 110L,
                new ExpectedMotion(new MotionSnapshot(0.0D, 0.0D, 0.0D), 0.0D));
        assertFalse(ledger.snapshot().actions().getFirst().activeAt(99L));
        assertTrue(ledger.snapshot().actions().getFirst().activeAt(100L));
        assertTrue(ledger.snapshot().actions().getFirst().activeAt(109L));

        clock.now = 110L;
        assertEquals(1, ledger.pruneExpired());
        assertTrue(ledger.snapshot().actions().isEmpty());
        assertFalse(ledger.complete(token, OWNER, 1L));
        assertThrows(IllegalArgumentException.class, () -> ledger.publish(
                OWNER, 1L, 2L, 100L, 110L,
                new ExpectedMotion(new MotionSnapshot(0, 0, 0), 0)));
    }

    @Test
    void hardLimitPrunesExpiredEntriesButRejectsMaxPlusOne() {
        MutableClock clock = new MutableClock(0L);
        ExpectedActionLedger ledger = new ExpectedActionLedger(clock, 2, 0L);
        publish(ledger, OWNER, 1L, 10L);
        publish(ledger, OWNER, 1L, 20L);
        assertEquals(2, ledger.snapshot().actions().size());
        assertThrows(IllegalStateException.class,
                () -> publish(ledger, OWNER, 1L, 30L));

        clock.now = 10L;
        ActionToken third = publish(ledger, OWNER, 1L, 30L);
        assertEquals(3L, third.value());
        assertEquals(2, ledger.snapshot().actions().size());
    }

    @Test
    void tokenOverflowNeverWrapsOrReusesAndInvalidEntriesNeverConsumeIt() {
        MutableClock clock = new MutableClock(0L);
        ExpectedActionLedger ledger = new ExpectedActionLedger(
                clock, 2, Long.MAX_VALUE - 1L);
        assertThrows(IllegalArgumentException.class, () -> ledger.publish(
                OWNER, 0L, 1L, 0L, 10L,
                new ExpectedMotion(new MotionSnapshot(0, 0, 0), 0)));

        ActionToken maximum = publish(ledger, OWNER, 1L, 10L);
        assertEquals(Long.MAX_VALUE, maximum.value());
        assertTrue(ledger.cancel(maximum, OWNER, 1L));
        assertThrows(IllegalStateException.class,
                () -> publish(ledger, OWNER, 1L, 20L));
        assertTrue(ledger.snapshot().actions().isEmpty());
    }

    @Test
    void publicationGuardRunsBeforeAnyMutation() {
        MutableClock clock = new MutableClock(0L);
        ExpectedActionLedger ledger = new ExpectedActionLedger(
                clock, () -> { throw new IllegalStateException("fenced"); }, 2, 0L);

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> publish(ledger, OWNER, 1L, 10L));

        assertEquals("fenced", failure.getMessage());
        assertTrue(ledger.snapshot().actions().isEmpty());
    }

    @Test
    void constructorsRejectNegativeOverflowAndInvalidTimingBoundaries() {
        MutableClock clock = new MutableClock(0L);
        ExpectedActionLedger ledger = new ExpectedActionLedger(clock);
        ExpectedMotion motion = new ExpectedMotion(new MotionSnapshot(0, 0, 0), 0);

        assertThrows(IllegalArgumentException.class, () -> new ActionToken(0L));
        assertThrows(IllegalArgumentException.class,
                () -> new ExpectedActionLedger(clock, 0, 0L));
        assertThrows(IllegalArgumentException.class,
                () -> new ExpectedActionLedger(clock, ExpectedActionLedger.MAX_ENTRIES + 1, 0L));
        assertThrows(IllegalArgumentException.class,
                () -> new ExpectedActionLedger(clock, 1, -1L));
        assertThrows(IllegalArgumentException.class,
                () -> ledger.publish(OWNER, 1L, -1L, 0L, 10L, motion));
        assertThrows(IllegalArgumentException.class,
                () -> ledger.publish(OWNER, 1L, 1L, 10L, 10L, motion));
        assertThrows(IllegalArgumentException.class,
                () -> ledger.publish(OWNER, 1L, 1L, 11L, 10L, motion));
        assertThrows(IllegalArgumentException.class,
                () -> new ExpectedMotion(new MotionSnapshot(0, 0, 0), -0.1D));
        assertThrows(IllegalArgumentException.class,
                () -> new ExpectedRotation(new RotationSnapshot(0, 0), -1F, 0F));
        assertThrows(IllegalArgumentException.class,
                () -> new ExpectedTeleport(new PositionSnapshot(0, 0, 0),
                        new PositionSnapshot(1, 1, 1), Double.NaN));
    }

    private static ActionToken publish(
            ExpectedActionLedger ledger,
            ControlOwner owner,
            long generation,
            long deadline
    ) {
        return ledger.publish(owner, generation, 4L, 0L, deadline,
                new ExpectedMotion(new MotionSnapshot(1.0D, 0.0D, 0.0D), 0.1D));
    }

    private static final class MutableClock implements MonotonicClock {
        private long now;

        private MutableClock(long now) {
            this.now = now;
        }

        @Override
        public long nowNanos() {
            return now;
        }
    }
}
