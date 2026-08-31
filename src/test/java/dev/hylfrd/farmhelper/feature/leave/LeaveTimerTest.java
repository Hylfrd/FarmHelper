package dev.hylfrd.farmhelper.feature.leave;

import dev.hylfrd.farmhelper.runtime.time.MonotonicClock;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LeaveTimerTest {
    private static final long GENERATION = 7L;

    @Test
    void exactBoundaryStopsMacroThenDisconnectsOnceAfterFiveHundredMilliseconds() {
        Harness harness = harness();
        long duration = TimeUnit.MINUTES.toNanos(60L);
        harness.timer.start(duration);

        assertEquals(LeaveTimerState.COUNTING_DOWN, harness.timer.state());
        assertEquals(GENERATION, harness.timer.macroGeneration());
        assertEquals(duration, harness.timer.remainingNanos().orElseThrow());

        harness.clock.advance(duration - 1L);
        assertEquals(LeaveTimerResult.COUNTING_DOWN, harness.timer.tick());
        assertTrue(harness.port.actions.isEmpty());

        harness.clock.advance(1L);
        assertEquals(LeaveTimerResult.MACRO_STOP_REQUESTED, harness.timer.tick());
        assertEquals(LeaveTimerState.DISCONNECT_PENDING, harness.timer.state());
        assertEquals(List.of("stop:" + GENERATION), harness.port.actions);
        assertEquals(LeaveTimer.DISCONNECT_DELAY_NANOS,
                harness.timer.remainingNanos().orElseThrow());

        harness.status.current = LeaveTimerStatus.inactive();
        harness.clock.advance(LeaveTimer.DISCONNECT_DELAY_NANOS - 1L);
        assertEquals(LeaveTimerResult.DISCONNECT_PENDING, harness.timer.tick());
        harness.clock.advance(1L);
        assertEquals(LeaveTimerResult.DISCONNECT_REQUESTED, harness.timer.tick());
        assertEquals(LeaveTimerState.COMPLETE, harness.timer.state());
        assertTrue(harness.timer.remainingNanos().isEmpty());
        assertEquals(List.of(
                "stop:" + GENERATION,
                "disconnect:" + LeaveTimer.DISCONNECT_REASON), harness.port.actions);

        assertEquals(LeaveTimerResult.COMPLETE, harness.timer.tick());
        assertEquals(2, harness.port.actions.size());
    }

    @Test
    void failsafeAndOtherFeatureDeferExpiryWithoutPausingCountdown() {
        Harness harness = harness();
        harness.timer.start(10L);
        harness.clock.advance(10L);

        harness.status.current = LeaveTimerStatus.active(GENERATION, true, true);
        assertEquals(LeaveTimerResult.FAILSAFE_ACTIVE, harness.timer.tick());
        harness.clock.advance(90L);

        harness.status.current = LeaveTimerStatus.active(GENERATION, false, true);
        assertEquals(LeaveTimerResult.OTHER_FEATURE_ACTIVE, harness.timer.tick());
        harness.status.current = LeaveTimerStatus.active(GENERATION, false, false);
        assertEquals(LeaveTimerResult.MACRO_STOP_REQUESTED, harness.timer.tick());
        assertEquals(List.of("stop:" + GENERATION), harness.port.actions);
        assertEquals(LeaveTimer.DISCONNECT_DELAY_NANOS,
                harness.timer.remainingNanos().orElseThrow());
    }

    @Test
    void inactiveOrReplacementMacroCancelsOnlyTheOwnedCountdown() {
        Harness inactive = harness();
        inactive.timer.start(10L);
        inactive.status.current = LeaveTimerStatus.inactive();
        assertEquals(LeaveTimerResult.MACRO_INACTIVE, inactive.timer.tick());
        assertEquals(LeaveTimerState.STOPPED, inactive.timer.state());
        assertTrue(inactive.port.actions.isEmpty());

        Harness replacement = harness();
        replacement.timer.start(10L);
        replacement.status.current = LeaveTimerStatus.active(GENERATION + 1L, false, false);
        assertEquals(LeaveTimerResult.STALE_MACRO, replacement.timer.tick());
        assertEquals(LeaveTimerState.STOPPED, replacement.timer.state());
        assertTrue(replacement.port.actions.isEmpty());
    }

    @Test
    void explicitStopAndRestartCancelCountdownOrPendingDisconnect() {
        Harness harness = harness();
        harness.timer.start(10L);
        harness.clock.advance(5L);
        harness.timer.stop();
        harness.clock.advance(100L);
        assertEquals(LeaveTimerResult.STOPPED, harness.timer.tick());
        assertTrue(harness.port.actions.isEmpty());

        harness.timer.start(2L);
        harness.clock.advance(2L);
        assertEquals(LeaveTimerResult.MACRO_STOP_REQUESTED, harness.timer.tick());
        harness.timer.stop();
        harness.clock.advance(LeaveTimer.DISCONNECT_DELAY_NANOS);
        assertEquals(LeaveTimerResult.STOPPED, harness.timer.tick());
        assertEquals(List.of("stop:" + GENERATION), harness.port.actions);

        harness.status.current = LeaveTimerStatus.active(GENERATION + 1L, false, false);
        harness.timer.start(20L);
        harness.clock.advance(19L);
        assertEquals(LeaveTimerResult.COUNTING_DOWN, harness.timer.tick());
        harness.timer.start(30L);
        harness.clock.advance(29L);
        assertEquals(LeaveTimerResult.COUNTING_DOWN, harness.timer.tick());
        harness.clock.advance(1L);
        assertEquals(LeaveTimerResult.MACRO_STOP_REQUESTED, harness.timer.tick());
        assertEquals(List.of("stop:" + GENERATION, "stop:" + (GENERATION + 1L)),
                harness.port.actions);
    }

    @Test
    void zeroDurationNeedsAnExplicitTickAndClockRollbackCannotAdvanceTime() {
        Harness immediate = harness();
        immediate.timer.start(0L);
        assertTrue(immediate.port.actions.isEmpty());
        assertEquals(LeaveTimerResult.MACRO_STOP_REQUESTED, immediate.timer.tick());

        MutableClock clock = new MutableClock(1_000L);
        MutableStatus status = new MutableStatus(
                LeaveTimerStatus.active(GENERATION, false, false));
        RecordingPort port = new RecordingPort();
        LeaveTimer timer = new LeaveTimer(clock, status, port);
        timer.start(100L);

        clock.set(900L);
        assertEquals(LeaveTimerResult.COUNTING_DOWN, timer.tick());
        assertEquals(100L, timer.remainingNanos().orElseThrow());
        clock.set(1_099L);
        assertEquals(LeaveTimerResult.COUNTING_DOWN, timer.tick());
        clock.set(1_100L);
        assertEquals(LeaveTimerResult.MACRO_STOP_REQUESTED, timer.tick());
    }

    @Test
    void invalidConstructionStatusAndDurationAreRejectedWithoutReplacingAValidRun() {
        MutableClock clock = new MutableClock();
        MutableStatus status = new MutableStatus(
                LeaveTimerStatus.active(GENERATION, false, false));
        RecordingPort port = new RecordingPort();

        assertThrows(NullPointerException.class, () -> new LeaveTimer(null, status, port));
        assertThrows(NullPointerException.class, () -> new LeaveTimer(clock, null, port));
        assertThrows(NullPointerException.class, () -> new LeaveTimer(clock, status, null));
        assertThrows(IllegalArgumentException.class,
                () -> new LeaveTimerStatus(true, 0L, false, false));
        assertThrows(IllegalArgumentException.class,
                () -> new LeaveTimerStatus(false, -1L, false, false));

        LeaveTimer timer = new LeaveTimer(clock, status, port);
        timer.start(10L);
        assertThrows(IllegalArgumentException.class, () -> timer.start(-1L));
        assertEquals(LeaveTimerState.COUNTING_DOWN, timer.state());
        assertEquals(10L, timer.remainingNanos().orElseThrow());

        status.current = LeaveTimerStatus.inactive();
        assertThrows(IllegalStateException.class, () -> timer.start(1L));
        assertEquals(LeaveTimerState.COUNTING_DOWN, timer.state());

        status.current = null;
        assertThrows(NullPointerException.class, () -> timer.start(1L));
        assertEquals(LeaveTimerState.COUNTING_DOWN, timer.state());
    }

    @Test
    void throwingActionsLeaveTheirPhaseRetryable() {
        Harness harness = harness();
        harness.timer.start(0L);
        harness.port.stopFailures = 1;

        assertThrows(IllegalStateException.class, harness.timer::tick);
        assertEquals(LeaveTimerState.COUNTING_DOWN, harness.timer.state());
        assertEquals(1, harness.port.stopCalls);
        assertEquals(LeaveTimerResult.MACRO_STOP_REQUESTED, harness.timer.tick());
        assertEquals(2, harness.port.stopCalls);

        harness.clock.advance(LeaveTimer.DISCONNECT_DELAY_NANOS);
        harness.port.disconnectFailures = 1;
        assertThrows(IllegalStateException.class, harness.timer::tick);
        assertEquals(LeaveTimerState.DISCONNECT_PENDING, harness.timer.state());
        assertEquals(1, harness.port.disconnectCalls);
        assertEquals(LeaveTimerResult.DISCONNECT_REQUESTED, harness.timer.tick());
        assertEquals(2, harness.port.disconnectCalls);
        assertEquals(LeaveTimerState.COMPLETE, harness.timer.state());
    }

    private static Harness harness() {
        MutableClock clock = new MutableClock();
        MutableStatus status = new MutableStatus(
                LeaveTimerStatus.active(GENERATION, false, false));
        RecordingPort port = new RecordingPort();
        return new Harness(clock, status, port, new LeaveTimer(clock, status, port));
    }

    private record Harness(
            MutableClock clock,
            MutableStatus status,
            RecordingPort port,
            LeaveTimer timer
    ) {
    }

    private static final class MutableStatus implements LeaveTimerStatusSource {
        private LeaveTimerStatus current;

        private MutableStatus(LeaveTimerStatus current) {
            this.current = current;
        }

        @Override
        public LeaveTimerStatus currentStatus() {
            return current;
        }
    }

    private static final class RecordingPort implements LeaveTimerDisconnectPort {
        private final List<String> actions = new ArrayList<>();
        private int stopCalls;
        private int disconnectCalls;
        private int stopFailures;
        private int disconnectFailures;

        @Override
        public void stopMacro(long expectedMacroGeneration) {
            stopCalls++;
            if (stopFailures-- > 0) {
                throw new IllegalStateException("stop failed");
            }
            actions.add("stop:" + expectedMacroGeneration);
        }

        @Override
        public void disconnect(String reason) {
            disconnectCalls++;
            if (disconnectFailures-- > 0) {
                throw new IllegalStateException("disconnect failed");
            }
            actions.add("disconnect:" + reason);
        }
    }

    private static final class MutableClock implements MonotonicClock {
        private long now;

        private MutableClock() {
            this(0L);
        }

        private MutableClock(long now) {
            this.now = now;
        }

        @Override
        public long nowNanos() {
            return now;
        }

        private void advance(long nanos) {
            now = Math.addExact(now, nanos);
        }

        private void set(long now) {
            this.now = now;
        }
    }
}
