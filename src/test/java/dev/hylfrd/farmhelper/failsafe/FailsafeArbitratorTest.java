package dev.hylfrd.farmhelper.failsafe;

import dev.hylfrd.farmhelper.runtime.time.MonotonicClock;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FailsafeArbitratorTest {
    private static final long MACRO_GENERATION = 4L;
    private static final long WORLD_EPOCH = 9L;

    @Test
    void catalogsAllUpstreamDetectorsInRegistrationOrderAndMarksBanwaveUnavailable() {
        assertEquals(List.of(
                FailsafeType.BAD_EFFECTS,
                FailsafeType.BANWAVE,
                FailsafeType.BEDROCK_CAGE,
                FailsafeType.COBWEB,
                FailsafeType.DIRT,
                FailsafeType.DISCONNECT,
                FailsafeType.EVACUATE,
                FailsafeType.FULL_INVENTORY,
                FailsafeType.GUEST_VISIT,
                FailsafeType.ITEM_CHANGE,
                FailsafeType.JACOB,
                FailsafeType.KNOCKBACK,
                FailsafeType.LOWER_AVG_BPS,
                FailsafeType.ROTATION,
                FailsafeType.TELEPORT,
                FailsafeType.WORLD_CHANGE), FailsafeType.upstreamRegistrationOrder());
        assertEquals(List.of(
                1, 6, 1, 3, 3, 1, 1, 3,
                1, 3, 7, 4, 9, 4, 5, 2),
                FailsafeType.upstreamRegistrationOrder().stream().map(FailsafeType::priority).toList());
        assertFalse(FailsafeType.BANWAVE.available());
        assertFalse(FailsafeType.availableRegistrationOrder().contains(FailsafeType.BANWAVE));
        assertEquals(15, FailsafeType.availableRegistrationOrder().size());
    }

    @Test
    void firstCandidateSchedulesOneSharedDelayAndDuplicatesDoNotResampleOrDuplicate() {
        TestClock clock = new TestClock();
        CountingDelaySource delays = new CountingDelaySource(2_499L);
        FailsafeArbitrator arbitrator = FailsafeArbitrator.withDelaySource(clock, delays);
        FailsafeCandidate first = candidate(FailsafeType.COBWEB);

        assertTrue(arbitrator.admit(first));
        assertEquals(1, delays.calls);
        assertEquals(2_499L, arbitrator.pendingDelayMillis().orElseThrow());
        assertEquals(FailsafeArbitrator.SubmissionStatus.DUPLICATE,
                arbitrator.submit(new FailsafeCandidate(FailsafeType.COBWEB,
                        MACRO_GENERATION, WORLD_EPOCH)).status());
        assertEquals(1, delays.calls);
        assertEquals(List.of(first), arbitrator.pending());

        FailsafeCandidate second = candidate(FailsafeType.DIRT);
        assertTrue(arbitrator.admit(second));
        assertEquals(1, delays.calls);
        assertEquals(List.of(first, second), arbitrator.pending());
    }

    @Test
    void selectionHonorsLowerPriorityThenFixedRegistrationOrderAfterDelay() {
        TestClock clock = new TestClock();
        FailsafeArbitrator arbitrator = FailsafeArbitrator.withDelaySource(clock, () -> 2_000L);
        FailsafeCandidate laterRegistered = candidate(FailsafeType.FULL_INVENTORY);
        FailsafeCandidate earlierRegistered = candidate(FailsafeType.COBWEB);
        FailsafeCandidate highestPriority = candidate(FailsafeType.BAD_EFFECTS);

        assertTrue(arbitrator.admit(laterRegistered));
        assertTrue(arbitrator.admit(earlierRegistered));
        assertTrue(arbitrator.admit(highestPriority));
        assertFalse(arbitrator.selectIfReady().isPresent());

        clock.advanceMillis(1_999L);
        assertFalse(arbitrator.selectIfReady().isPresent());
        clock.advanceMillis(1L);
        assertEquals(highestPriority, arbitrator.selectIfReady().orElseThrow());
        assertTrue(arbitrator.isTriggered());
        assertTrue(arbitrator.pending().isEmpty());

        arbitrator.reset();
        assertTrue(arbitrator.admit(laterRegistered));
        assertTrue(arbitrator.admit(earlierRegistered));
        clock.advanceMillis(2_000L);
        assertEquals(earlierRegistered, arbitrator.selectIfReady().orElseThrow());
    }

    @Test
    void timingSourceHonorsBothDelayBoundaries() {
        TestClock minimumClock = new TestClock();
        FailsafeArbitrator minimum = FailsafeArbitrator.withDelaySource(
                minimumClock, () -> 2_000L);
        assertTrue(minimum.admit(candidate(FailsafeType.DIRT)));
        minimumClock.advanceMillis(1_999L);
        assertFalse(minimum.delayDue());
        minimumClock.advanceMillis(1L);
        assertTrue(minimum.delayDue());

        TestClock maximumClock = new TestClock();
        FailsafeArbitrator maximum = FailsafeArbitrator.withDelaySource(
                maximumClock, () -> 2_499L);
        assertTrue(maximum.admit(candidate(FailsafeType.DIRT)));
        maximumClock.advanceMillis(2_498L);
        assertFalse(maximum.delayDue());
        maximumClock.advanceMillis(1L);
        assertTrue(maximum.delayDue());
    }

    @Test
    void staleIdentityIsRejectedAndExactIdentityCanCancelOnlyItsOwnCandidate() {
        TestClock clock = new TestClock();
        FailsafeArbitrator arbitrator = FailsafeArbitrator.withDelaySource(
                clock, () -> 2_000L, MACRO_GENERATION, WORLD_EPOCH);
        FailsafeCandidate staleMacro = new FailsafeCandidate(
                FailsafeType.TELEPORT, MACRO_GENERATION + 1L, WORLD_EPOCH);
        FailsafeCandidate staleWorld = new FailsafeCandidate(
                FailsafeType.TELEPORT, MACRO_GENERATION, WORLD_EPOCH + 1L);
        FailsafeCandidate current = candidate(FailsafeType.TELEPORT);

        assertEquals(FailsafeArbitrator.SubmissionStatus.STALE_IDENTITY,
                arbitrator.submit(staleMacro).status());
        assertEquals(FailsafeArbitrator.SubmissionStatus.STALE_IDENTITY,
                arbitrator.submit(staleWorld).status());
        assertTrue(arbitrator.admit(current));
        assertFalse(arbitrator.cancel(staleMacro));
        assertEquals(List.of(current), arbitrator.pending());
        assertTrue(arbitrator.cancel(current));
        assertTrue(arbitrator.pending().isEmpty());
        assertFalse(arbitrator.isDelayScheduled());
    }

    @Test
    void resetClearsDelayedAndTriggeredStateAndNewIdentityFencesOldCandidates() {
        TestClock clock = new TestClock();
        FailsafeArbitrator arbitrator = FailsafeArbitrator.withDelaySource(
                clock, () -> 2_000L);
        FailsafeCandidate oldCandidate = candidate(FailsafeType.ROTATION);

        assertTrue(arbitrator.admit(oldCandidate));
        arbitrator.reset(MACRO_GENERATION + 1L, WORLD_EPOCH + 1L);
        clock.advanceMillis(10_000L);
        assertFalse(arbitrator.selectIfReady().isPresent());
        assertFalse(arbitrator.isTriggered());
        assertEquals(FailsafeArbitrator.SubmissionStatus.STALE_IDENTITY,
                arbitrator.submit(oldCandidate).status());

        FailsafeCandidate fresh = new FailsafeCandidate(
                FailsafeType.ROTATION, MACRO_GENERATION + 1L, WORLD_EPOCH + 1L);
        assertTrue(arbitrator.admit(fresh));
        clock.advanceMillis(2_000L);
        assertEquals(fresh, arbitrator.selectIfReady().orElseThrow());
        arbitrator.cancel();
        assertFalse(arbitrator.isTriggered());
        assertTrue(arbitrator.pending().isEmpty());
    }

    @Test
    void banwaveIsExplicitlyUnavailableAndCannotScheduleSharedDelay() {
        TestClock clock = new TestClock();
        CountingDelaySource delays = new CountingDelaySource(2_000L);
        FailsafeArbitrator arbitrator = FailsafeArbitrator.withDelaySource(clock, delays);
        FailsafeCandidate banwave = candidate(FailsafeType.BANWAVE);

        assertEquals(FailsafeArbitrator.SubmissionStatus.UNAVAILABLE,
                arbitrator.submit(banwave).status());
        assertTrue(arbitrator.pending().isEmpty());
        assertFalse(arbitrator.isDelayScheduled());
        assertEquals(0, delays.calls);
    }

    private static FailsafeCandidate candidate(FailsafeType type) {
        return new FailsafeCandidate(type, MACRO_GENERATION, WORLD_EPOCH);
    }

    private static final class TestClock implements MonotonicClock {
        private long nowNanos;

        @Override
        public long nowNanos() {
            return nowNanos;
        }

        private void advanceMillis(long deltaMillis) {
            nowNanos += deltaMillis * 1_000_000L;
        }
    }

    private static final class CountingDelaySource implements FailsafeArbitrator.DelaySource {
        private final long delayMillis;
        private int calls;

        private CountingDelaySource(long delayMillis) {
            this.delayMillis = delayMillis;
        }

        @Override
        public long nextDelayMillis() {
            calls++;
            return delayMillis;
        }
    }
}
