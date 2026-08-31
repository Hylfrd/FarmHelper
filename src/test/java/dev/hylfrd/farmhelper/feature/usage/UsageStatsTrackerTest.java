package dev.hylfrd.farmhelper.feature.usage;

import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UsageStatsTrackerTest {
    private static final long SECOND = TimeUnit.SECONDS.toNanos(1L);

    @Test
    void countsOnlyExplicitActiveIntervalsAndExposesStableTotals() {
        UsageStatsTracker tracker = new UsageStatsTracker(5L * SECOND, 16);

        assertEquals(UsageRecordResult.STARTED, tracker.start(0L));
        assertEquals(UsageRecordResult.NO_TIME, tracker.record(UsageTick.active(0L)));
        assertEquals(UsageRecordResult.COUNTED, tracker.record(UsageTick.active(SECOND)));
        assertEquals(UsageRecordResult.IDLE, tracker.record(UsageTick.idle(2L * SECOND)));
        assertEquals(UsageRecordResult.COUNTED, tracker.record(true, 3L * SECOND));

        UsageSnapshot snapshot = tracker.snapshot(3L * SECOND);
        assertEquals(UsageState.RUNNING, snapshot.state());
        assertEquals(UsageSnapshot.Status.VALID, snapshot.status());
        assertEquals(2L * SECOND, snapshot.sessionNanos());
        assertEquals(2L * SECOND, snapshot.last24HoursNanos());
        assertEquals(2L * SECOND, snapshot.last7DaysNanos());
        assertEquals(2L * SECOND, snapshot.last30DaysNanos());
        assertEquals(2L * SECOND, snapshot.lifetimeNanos());
        assertEquals(2, snapshot.retainedEventCount());
        assertFalse(snapshot.eventHistoryLimited());
        assertEquals(2_000L, snapshot.totalMillis());
        assertEquals(snapshot, tracker.snapshot(3L * SECOND));
    }

    @Test
    void rejectsOversizedActiveGapsWithoutAddingUnknownTime() {
        UsageStatsTracker tracker = new UsageStatsTracker(5L * SECOND, 16);
        tracker.start(0L);

        assertEquals(UsageRecordResult.COUNTED, tracker.record(true, SECOND));
        assertEquals(UsageRecordResult.GAP_REJECTED, tracker.record(true, 6L * SECOND));
        assertEquals(UsageRecordResult.COUNTED, tracker.record(true, 7L * SECOND));
        assertEquals(UsageRecordResult.GAP_REJECTED, tracker.record(true, 13L * SECOND));
        assertEquals(UsageRecordResult.COUNTED, tracker.record(true, 14L * SECOND));

        UsageSnapshot snapshot = tracker.snapshot(14L * SECOND);
        assertEquals(3L * SECOND, snapshot.sessionNanos());
        assertEquals(3L * SECOND, snapshot.lifetimeNanos());
        assertEquals(3, snapshot.retainedEventCount());
    }

    @Test
    void pauseAndResumeExcludeTheExplicitPausedInterval() {
        UsageStatsTracker tracker = new UsageStatsTracker(5L * SECOND, 16);
        tracker.start(0L);
        tracker.record(true, SECOND);

        assertEquals(UsageRecordResult.PAUSED, tracker.pause(2L * SECOND));
        assertEquals(UsageState.PAUSED, tracker.state());
        assertEquals(UsageRecordResult.PAUSED, tracker.record(true, 100L * SECOND));
        assertEquals(UsageRecordResult.RESUMED, tracker.resume(103L * SECOND));
        assertEquals(UsageRecordResult.COUNTED, tracker.record(true, 104L * SECOND));

        UsageSnapshot snapshot = tracker.snapshot(104L * SECOND);
        assertEquals(3L * SECOND, snapshot.sessionNanos());
        assertEquals(3L * SECOND, snapshot.lifetimeNanos());
        assertEquals(3, snapshot.retainedEventCount());
    }

    @Test
    void rollingWindowsRolloverFromMonotonicEvidence() {
        long day = UsageStatsTracker.DAY_NANOS;
        UsageStatsTracker tracker = new UsageStatsTracker(40L * day, 16);
        tracker.start(0L);

        tracker.record(true, SECOND);
        tracker.record(false, 2L * SECOND);
        tracker.record(false, 8L * day + 2L * SECOND);
        tracker.record(true, 8L * day + 3L * SECOND);

        UsageSnapshot snapshot = tracker.snapshot(8L * day + 3L * SECOND);
        assertEquals(2L * SECOND, snapshot.lifetimeNanos());
        assertEquals(SECOND, snapshot.last24HoursNanos());
        assertEquals(SECOND, snapshot.last7DaysNanos());
        assertEquals(2L * SECOND, snapshot.last30DaysNanos());
        assertEquals(2, snapshot.retainedEventCount());
    }

    @Test
    void eventCapacityIsObservableUntilEvictedEvidenceExpires() {
        UsageStatsTracker tracker = new UsageStatsTracker(10L * SECOND, 2);
        tracker.start(0L);
        tracker.record(true, SECOND);
        tracker.record(false, 2L * SECOND);
        tracker.record(true, 3L * SECOND);
        tracker.record(false, 4L * SECOND);
        tracker.record(true, 5L * SECOND);

        UsageSnapshot limited = tracker.snapshot(5L * SECOND);
        assertEquals(3L * SECOND, limited.lifetimeNanos());
        assertEquals(2L * SECOND, limited.last24HoursNanos());
        assertEquals(2, limited.retainedEventCount());
        assertTrue(limited.eventHistoryLimited());

        UsageSnapshot rolledOver = tracker.snapshot(
                UsageStatsTracker.LAST_30_DAYS_NANOS + 6L * SECOND);
        assertEquals(0, rolledOver.retainedEventCount());
        assertFalse(rolledOver.eventHistoryLimited());
        assertEquals(3L * SECOND, rolledOver.lifetimeNanos());
    }

    @Test
    void resetClearsHistoryAndStartsANewSession() {
        UsageStatsTracker tracker = new UsageStatsTracker(5L * SECOND, 16);
        tracker.start(0L);
        tracker.record(true, SECOND);
        tracker.stop();

        assertEquals(SECOND, tracker.snapshot(SECOND).lifetimeNanos());
        tracker.reset();
        assertEquals(UsageSnapshot.ZERO, tracker.snapshot(0L));
        assertEquals(UsageRecordResult.STARTED, tracker.start(0L));
        tracker.record(true, SECOND);
        assertEquals(SECOND, tracker.snapshot(SECOND).lifetimeNanos());
    }

    @Test
    void invalidAndRegressingEvidenceFailsClosedWithoutMutation() {
        UsageStatsTracker tracker = new UsageStatsTracker(5L * SECOND, 16);

        assertEquals(UsageRecordResult.INVALID_TIME, tracker.record(true, -1L));
        assertEquals(UsageRecordResult.INVALID_EVENT, tracker.record(null));
        assertEquals(UsageSnapshot.Status.INVALID_TIME, tracker.snapshot(-1L).status());
        assertEquals(UsageRecordResult.STOPPED, tracker.record(true, 0L));

        tracker.start(0L);
        tracker.record(true, SECOND);
        assertEquals(UsageRecordResult.CLOCK_REGRESSION, tracker.record(true, 0L));
        UsageSnapshot invalid = tracker.snapshot(0L);
        assertEquals(UsageSnapshot.Status.CLOCK_REGRESSION, invalid.status());
        assertFalse(invalid.valid());

        UsageSnapshot recovered = tracker.snapshot(2L * SECOND);
        assertEquals(UsageSnapshot.Status.VALID, recovered.status());
        assertEquals(SECOND, recovered.lifetimeNanos());
    }

    @Test
    void invalidBoundsAndTickTimeAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new UsageStatsTracker(0L, 1));
        assertThrows(IllegalArgumentException.class, () -> new UsageStatsTracker(1L, 0));
        assertThrows(IllegalArgumentException.class, () -> new UsageTick(-1L, true));

        UsageStatsTracker tracker = new UsageStatsTracker();
        assertEquals(UsageRecordResult.INVALID_TIME, tracker.start(-1L));
        assertEquals(UsageRecordResult.STARTED, tracker.start(0L));
        assertEquals(UsageRecordResult.ALREADY_ACTIVE, tracker.start(0L));
        assertEquals(UsageRecordResult.ALREADY_ACTIVE, tracker.resume(0L));
        assertEquals(UsageRecordResult.PAUSED, tracker.pause(0L));
        assertEquals(UsageRecordResult.ALREADY_PAUSED, tracker.pause(0L));
    }
}
