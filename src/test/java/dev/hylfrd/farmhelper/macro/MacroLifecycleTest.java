package dev.hylfrd.farmhelper.macro;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MacroLifecycleTest {
    @Test
    void featureCloseWaitsForManualScreenAndEnvironmentPauseCauses() {
        RecordingTarget target = new RecordingTarget();
        MutableClock clock = new MutableClock();
        MacroLifecycle lifecycle = new MacroLifecycle(target, clock);

        clock.now = 10L;
        lifecycle.start();
        clock.now = 20L;
        lifecycle.manualPause();
        clock.now = 30L;
        lifecycle.screenOpen();
        clock.now = 35L;
        lifecycle.environmentUnavailable();
        clock.now = 40L;
        FeatureSuspension first = lifecycle.suspendForFeature("visitors");
        clock.now = 50L;
        FeatureSuspension second = lifecycle.suspendForFeature("autosell");

        clock.now = 60L;
        lifecycle.manualResume();
        clock.now = 70L;
        lifecycle.screenClosed();
        first.close();
        assertEquals(MacroState.PAUSED, lifecycle.state());
        assertEquals(0, target.resumeCount);

        clock.now = 80L;
        lifecycle.environmentAvailable();
        assertEquals(MacroState.PAUSED, lifecycle.state());

        clock.now = 90L;
        second.close();
        assertEquals(MacroState.RUNNING, lifecycle.state());
        assertEquals(1, target.resumeCount);
        assertEquals(List.of("start", "pause", "resume"), target.events);
        assertEquals(90L, target.resumeNanos);
    }

    @Test
    void staleFeatureLeaseCannotResumeReplacementRun() {
        RecordingTarget target = new RecordingTarget();
        MutableClock clock = new MutableClock();
        MacroLifecycle lifecycle = new MacroLifecycle(target, clock);
        lifecycle.start();
        long activeGeneration = lifecycle.generation();
        clock.now = 1L;
        FeatureSuspension suspension = lifecycle.suspendForFeature("scheduler");

        lifecycle.stop(MacroTerminalReason.DISCONNECT);
        lifecycle.start();
        suspension.close();

        assertEquals(MacroState.RUNNING, lifecycle.state());
        assertFalse(lifecycle.accepts(activeGeneration));
        assertTrue(lifecycle.pauseCauses().isEmpty());
        assertEquals(List.of("start", "pause", "stop:DISCONNECT", "start"), target.events);
    }

    @Test
    void doubleCloseUsesReleaseTimeOnlyOnce() {
        RecordingTarget target = new RecordingTarget();
        MutableClock clock = new MutableClock();
        MacroLifecycle lifecycle = new MacroLifecycle(target, clock);
        lifecycle.start();
        clock.now = 10L;
        FeatureSuspension suspension = lifecycle.suspendForFeature("scheduler");

        clock.now = 75L;
        suspension.close();
        clock.now = 100L;
        suspension.close();

        assertEquals(1, target.resumeCount);
        assertEquals(75L, target.resumeNanos);
    }

    @Test
    void lastNestedFeatureCloseUsesReleaseClockAndExcludesSuspendedTimerTime() {
        RecordingTarget target = new RecordingTarget();
        MutableClock clock = new MutableClock();
        MacroLifecycle lifecycle = new MacroLifecycle(target, clock);
        lifecycle.start();
        clock.now = 10L;
        FeatureSuspension outer = lifecycle.suspendForFeature("outer");
        clock.now = 40L;
        FeatureSuspension inner = lifecycle.suspendForFeature("inner");
        clock.now = 70L;
        outer.close();
        clock.now = 110L;
        inner.close();

        assertEquals(10L, target.pauseNanos);
        assertEquals(110L, target.resumeNanos);
        assertEquals(100L, target.resumeNanos - target.pauseNanos);
    }

    @Test
    void terminalStopClearsLeasesAndLateDoubleCloseIsInert() {
        RecordingTarget target = new RecordingTarget();
        MutableClock clock = new MutableClock();
        MacroLifecycle lifecycle = new MacroLifecycle(target, clock);
        lifecycle.start();
        FeatureSuspension suspension = lifecycle.suspendForFeature("feature");

        lifecycle.stop(MacroTerminalReason.CLIENT_STOP);
        suspension.close();
        suspension.close();

        assertEquals(MacroState.STOPPED, lifecycle.state());
        assertTrue(lifecycle.pauseCauses().isEmpty());
        assertEquals(0, target.resumeCount);
        assertEquals(List.of("start", "pause", "stop:CLIENT_STOP"), target.events);
    }

    @Test
    void terminalReasonsStopExactlyOnceAndInvalidateGeneration() {
        for (MacroTerminalReason reason : MacroTerminalReason.values()) {
            RecordingTarget target = new RecordingTarget();
            MutableClock clock = new MutableClock();
            MacroLifecycle lifecycle = new MacroLifecycle(target, clock);
            lifecycle.start();
            long generation = lifecycle.generation();

            lifecycle.stop(reason);
            lifecycle.stop(reason);

            assertFalse(lifecycle.accepts(generation), reason.name());
            assertEquals(1, target.stopCount, reason.name());
        }
    }

    private static final class MutableClock implements dev.hylfrd.farmhelper.runtime.time.MonotonicClock {
        private long now;

        @Override
        public long nowNanos() {
            return now;
        }
    }

    private static final class RecordingTarget implements MacroLifecycleTarget {
        private final List<String> events = new ArrayList<>();
        private int resumeCount;
        private long resumeNanos;
        private long pauseNanos;
        private int stopCount;

        @Override
        public void start(long generation, long nowNanos) {
            events.add("start");
        }

        @Override
        public void pause(long generation, long nowNanos, Set<MacroPauseCause> causes) {
            pauseNanos = nowNanos;
            events.add("pause");
        }

        @Override
        public void resume(long generation, long nowNanos) {
            resumeCount++;
            resumeNanos = nowNanos;
            events.add("resume");
        }

        @Override
        public void stop(long generation, MacroTerminalReason reason) {
            stopCount++;
            events.add("stop:" + reason);
        }
    }
}
