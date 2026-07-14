package dev.hylfrd.farmhelper.runtime.time;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PausableTimerTest {
    @Test
    void pauseKeepsElapsedAndRemainingStableUntilResume() {
        ManualClock clock = new ManualClock();
        PausableTimer timer = new PausableTimer(clock, 10L);

        clock.advanceNanos(4L);
        timer.pause();
        clock.advanceNanos(100L);

        assertTrue(timer.paused());
        assertEquals(4L, timer.elapsedNanos());
        assertEquals(6L, timer.remainingNanos());

        timer.resume();
        clock.advanceNanos(6L);

        assertFalse(timer.paused());
        assertEquals(10L, timer.elapsedNanos());
        assertEquals(0L, timer.remainingNanos());
        assertTrue(timer.elapsed());
    }

    @Test
    void resetPreservesRunningAndPausedState() {
        ManualClock clock = new ManualClock();
        PausableTimer timer = new PausableTimer(clock, 20L);

        clock.advanceNanos(7L);
        timer.reset();
        clock.advanceNanos(3L);
        assertEquals(3L, timer.elapsedNanos());

        timer.pause();
        timer.reset();
        clock.advanceNanos(9L);
        assertTrue(timer.paused());
        assertEquals(0L, timer.elapsedNanos());
        assertEquals(20L, timer.remainingNanos());
    }

    @Test
    void validatesDurationAndHandlesZeroAndMaximumBoundaries() {
        ManualClock clock = new ManualClock();

        assertThrows(IllegalArgumentException.class, () -> new PausableTimer(clock, -1L));
        assertTrue(new PausableTimer(clock, 0L).elapsed());

        PausableTimer maximum = new PausableTimer(clock, Long.MAX_VALUE);
        clock.advanceNanos(Long.MAX_VALUE);
        assertEquals(Long.MAX_VALUE, maximum.elapsedNanos());
        assertEquals(0L, maximum.remainingNanos());
        assertTrue(maximum.elapsed());
    }

    @Test
    void saturatesElapsedTimeAcrossClockWrapAndResume() {
        ManualClock clock = new ManualClock();
        PausableTimer timer = new PausableTimer(clock, Long.MAX_VALUE);

        clock.advanceNanos(Long.MAX_VALUE - 5L);
        timer.pause();
        timer.resume();
        clock.advanceNanos(10L);

        assertEquals(Long.MAX_VALUE, timer.elapsedNanos());
        assertEquals(0L, timer.remainingNanos());
    }
}
