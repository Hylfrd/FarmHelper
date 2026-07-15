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
    void overlappingPauseCausesResumeOnlyAfterEveryCauseClears() {
        RecordingTarget target = new RecordingTarget();
        MacroLifecycle lifecycle = new MacroLifecycle(target);

        lifecycle.start(10L);
        lifecycle.manualPause(20L);
        lifecycle.screenOpen(30L);
        FeatureSuspension first = lifecycle.suspendForFeature("visitors", 40L);
        FeatureSuspension second = lifecycle.suspendForFeature("autosell", 50L);

        lifecycle.manualResume(60L);
        lifecycle.screenClosed(70L);
        first.close();
        assertEquals(MacroState.PAUSED, lifecycle.state());
        assertEquals(0, target.resumeCount);

        second.close();
        assertEquals(MacroState.RUNNING, lifecycle.state());
        assertEquals(1, target.resumeCount);
        assertEquals(List.of("start", "pause", "resume"), target.events);
    }

    @Test
    void terminalStopInvalidatesOldGenerationAndStaleLease() {
        RecordingTarget target = new RecordingTarget();
        MacroLifecycle lifecycle = new MacroLifecycle(target);
        lifecycle.start(0L);
        long activeGeneration = lifecycle.generation();
        FeatureSuspension suspension = lifecycle.suspendForFeature("scheduler", 1L);

        lifecycle.stop(MacroTerminalReason.DISCONNECT);
        suspension.close();

        assertEquals(MacroState.STOPPED, lifecycle.state());
        assertFalse(lifecycle.accepts(activeGeneration));
        assertTrue(lifecycle.pauseCauses().isEmpty());
        assertEquals(List.of("start", "pause", "stop:DISCONNECT"), target.events);
    }

    private static final class RecordingTarget implements MacroLifecycleTarget {
        private final List<String> events = new ArrayList<>();
        private int resumeCount;

        @Override
        public void start(long generation, long nowNanos) {
            events.add("start");
        }

        @Override
        public void pause(long generation, long nowNanos, Set<MacroPauseCause> causes) {
            events.add("pause");
        }

        @Override
        public void resume(long generation, long nowNanos) {
            resumeCount++;
            events.add("resume");
        }

        @Override
        public void stop(long generation, MacroTerminalReason reason) {
            events.add("stop:" + reason);
        }
    }
}
