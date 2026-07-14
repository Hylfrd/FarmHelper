package dev.hylfrd.farmhelper.control.rotation;

import dev.hylfrd.farmhelper.control.input.ControlOwner;
import dev.hylfrd.farmhelper.runtime.time.MonotonicClock;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RotationControllerTest {
    private static final ControlOwner OWNER = new ControlOwner("rotation-test");
    private static final ControlOwner OTHER_OWNER = new ControlOwner("other-rotation-test");
    private static final float EPSILON = 0.0001F;

    @Test
    void zeroDurationAppliesFinalFrameAndCompletesOnFirstExplicitTick() {
        MutableClock clock = new MutableClock();
        RotationController controller = new RotationController(clock);
        List<RotationFrame> frames = new ArrayList<>();
        List<RotationResult> results = new ArrayList<>();

        controller.start(OWNER, 10.0F, 20.0F, 30.0F, 40.0F, 0L, results::add);

        assertTrue(controller.rotating());
        assertTrue(controller.movementBlocked());
        assertTrue(controller.tick(frames::add));
        assertEquals(List.of(new RotationFrame(30.0F, 40.0F, 1.0F)), frames);
        assertEquals(1, results.size());
        assertTrue(results.getFirst().completed());
        assertFalse(controller.rotating());
        assertFalse(controller.movementBlocked());
        assertEquals(RotationTerminalReason.COMPLETED,
                controller.snapshot().terminalReason().orElseThrow());
    }

    @Test
    void elapsedProgressNeverRegressesIfInjectedClockMovesBackward() {
        MutableClock clock = new MutableClock();
        RotationController controller = new RotationController(clock);
        List<RotationFrame> frames = new ArrayList<>();
        controller.start(OWNER, 0.0F, 0.0F, 100.0F, 0.0F, 100L);

        controller.tick(frames::add);
        clock.setNanos(50_000_000L);
        controller.tick(frames::add);
        clock.setNanos(25_000_000L);
        controller.tick(frames::add);
        clock.setNanos(100_000_000L);
        controller.tick(frames::add);

        assertEquals(0.0F, frames.get(0).progress(), EPSILON);
        assertEquals(0.5F, frames.get(1).progress(), EPSILON);
        assertEquals(0.5F, frames.get(2).progress(), EPSILON);
        assertEquals(frames.get(1).yaw(), frames.get(2).yaw(), EPSILON);
        assertEquals(1.0F, frames.get(3).progress(), EPSILON);
        assertFalse(controller.rotating());
    }

    @Test
    void pauseAndResumeAreIdempotentAndExcludePausedTime() {
        MutableClock clock = new MutableClock();
        RotationController controller = new RotationController(clock);
        List<RotationFrame> frames = new ArrayList<>();
        controller.start(OWNER, 0.0F, 0.0F, 100.0F, 0.0F, 100L);

        clock.advanceNanos(20_000_000L);
        controller.tick(frames::add);
        controller.pause();
        long pausedRevision = controller.snapshot().revision();
        controller.pause();
        clock.advanceNanos(1_000_000_000L);
        controller.tick(frames::add);

        assertTrue(controller.paused());
        assertTrue(controller.rotating());
        assertTrue(controller.movementBlocked());
        assertEquals(pausedRevision, controller.snapshot().revision());
        assertEquals(0.2F, frames.get(0).progress(), EPSILON);
        assertEquals(0.2F, frames.get(1).progress(), EPSILON);
        assertEquals(frames.get(0).yaw(), frames.get(1).yaw(), EPSILON);

        controller.resume();
        long resumedRevision = controller.snapshot().revision();
        controller.resume();
        clock.advanceNanos(10_000_000L);
        controller.tick(frames::add);

        assertFalse(controller.paused());
        assertEquals(resumedRevision, controller.snapshot().revision());
        assertEquals(0.3F, frames.get(2).progress(), EPSILON);
    }

    @Test
    void ownerConflictReplacementAndLeaseIdentityAreDeterministic() {
        MutableClock clock = new MutableClock();
        RotationController controller = new RotationController(clock);
        List<RotationResult> oldResults = new ArrayList<>();
        List<RotationResult> newResults = new ArrayList<>();
        RotationHandle oldHandle = controller.start(
                OWNER, 0.0F, 0.0F, 10.0F, 0.0F, 100L, oldResults::add);

        RotationConflictException conflict = assertThrows(
                RotationConflictException.class,
                () -> controller.start(OTHER_OWNER, 0.0F, 0.0F, 20.0F, 0.0F, 100L));
        assertEquals(OTHER_OWNER, conflict.requestedOwner());
        assertEquals(OWNER, conflict.currentOwner());
        assertTrue(controller.rotating());
        assertFalse(controller.cancel(OTHER_OWNER, RotationCancelReason.OWNER_CANCELLED));

        RotationHandle newHandle = controller.start(
                OWNER, 0.0F, 0.0F, 30.0F, 0.0F, 100L, newResults::add);

        assertEquals(1, oldResults.size());
        assertEquals(RotationTerminalReason.REPLACED, oldResults.getFirst().reason());
        assertFalse(oldHandle.cancel());
        assertTrue(controller.rotating());
        assertTrue(newHandle.cancel());
        assertFalse(newHandle.cancel());
        assertEquals(1, newResults.size());
        assertEquals(RotationTerminalReason.OWNER_CANCELLED, newResults.getFirst().reason());
    }

    @Test
    void everyExternalCancelReasonProducesOneMatchingTerminalCallback() {
        for (RotationCancelReason cancelReason : RotationCancelReason.values()) {
            MutableClock clock = new MutableClock();
            RotationController controller = new RotationController(clock);
            List<RotationResult> results = new ArrayList<>();
            RotationHandle handle = controller.start(
                    OWNER, 0.0F, 0.0F, 10.0F, 0.0F, 100L, results::add);

            assertTrue(handle.cancel(cancelReason), cancelReason.name());
            assertFalse(handle.cancel(cancelReason), cancelReason.name());
            assertEquals(1, results.size(), cancelReason.name());
            assertEquals(
                    RotationTerminalReason.valueOf(cancelReason.name()),
                    results.getFirst().reason(),
                    cancelReason.name());
            assertFalse(controller.rotating(), cancelReason.name());
        }
    }

    @Test
    void runtimeExceptionAndErrorCallbacksLeaveTerminalReusableController() {
        MutableClock clock = new MutableClock();
        RotationController controller = new RotationController(clock);
        controller.start(
                OWNER,
                0.0F,
                0.0F,
                10.0F,
                0.0F,
                0L,
                result -> { throw new IllegalStateException("completion failure"); });

        assertThrows(IllegalStateException.class, () -> controller.tick(frame -> { }));
        assertFalse(controller.rotating());
        assertEquals(RotationTerminalReason.COMPLETED,
                controller.snapshot().terminalReason().orElseThrow());

        RotationHandle handle = controller.start(
                OWNER,
                0.0F,
                0.0F,
                20.0F,
                0.0F,
                100L,
                result -> { throw new AssertionError("cancel failure"); });
        assertThrows(AssertionError.class, handle::cancel);
        assertFalse(controller.rotating());
        assertEquals(RotationTerminalReason.OWNER_CANCELLED,
                controller.snapshot().terminalReason().orElseThrow());

        controller.start(OWNER, 0.0F, 0.0F, 30.0F, 0.0F, 100L);
        assertTrue(controller.rotating());
    }

    @Test
    void sinkFailureTerminatesBeforePropagatingAndInvokesCallbackOnce() {
        MutableClock clock = new MutableClock();
        RotationController controller = new RotationController(clock);
        List<RotationResult> results = new ArrayList<>();
        controller.start(OWNER, 0.0F, 0.0F, 10.0F, 0.0F, 100L, results::add);

        assertThrows(IllegalArgumentException.class,
                () -> controller.tick(frame -> { throw new IllegalArgumentException("sink failure"); }));

        assertFalse(controller.rotating());
        assertEquals(1, results.size());
        assertEquals(RotationTerminalReason.APPLICATION_FAILED, results.getFirst().reason());
        assertEquals(RotationTerminalReason.APPLICATION_FAILED,
                controller.snapshot().terminalReason().orElseThrow());
    }

    @Test
    void sinkErrorAlsoFailsClosedBeforePropagation() {
        MutableClock clock = new MutableClock();
        RotationController controller = new RotationController(clock);
        List<RotationResult> results = new ArrayList<>();
        controller.start(OWNER, 0.0F, 0.0F, 10.0F, 0.0F, 100L, results::add);

        assertThrows(AssertionError.class,
                () -> controller.tick(frame -> { throw new AssertionError("sink error"); }));

        assertFalse(controller.rotating());
        assertEquals(1, results.size());
        assertEquals(RotationTerminalReason.APPLICATION_FAILED, results.getFirst().reason());
        assertEquals(RotationTerminalReason.APPLICATION_FAILED,
                controller.snapshot().terminalReason().orElseThrow());
    }

    @Test
    void completionCallbackMayStartNewRotationWithoutOldTickClearingIt() {
        MutableClock clock = new MutableClock();
        RotationController controller = new RotationController(clock);
        List<RotationResult> results = new ArrayList<>();
        controller.start(
                OWNER,
                0.0F,
                0.0F,
                10.0F,
                0.0F,
                0L,
                result -> {
                    results.add(result);
                    controller.start(OTHER_OWNER, 10.0F, 0.0F, 20.0F, 0.0F, 100L);
                });

        controller.tick(frame -> { });

        assertEquals(1, results.size());
        assertEquals(RotationTerminalReason.COMPLETED, results.getFirst().reason());
        assertTrue(controller.rotating());
        assertEquals(OTHER_OWNER, controller.snapshot().owner().orElseThrow());
        assertTrue(controller.movementBlocked());
    }

    @Test
    void replacementCallbackReentryCannotBeSilentlyOverwritten() {
        MutableClock clock = new MutableClock();
        RotationController controller = new RotationController(clock);
        controller.start(
                OWNER,
                0.0F,
                0.0F,
                10.0F,
                0.0F,
                100L,
                result -> controller.start(
                        OTHER_OWNER, 10.0F, 0.0F, 70.0F, 0.0F, 100L));

        RotationConflictException conflict = assertThrows(
                RotationConflictException.class,
                () -> controller.start(OWNER, 0.0F, 0.0F, 20.0F, 0.0F, 100L));

        assertEquals(OWNER, conflict.requestedOwner());
        assertEquals(OTHER_OWNER, conflict.currentOwner());
        assertTrue(controller.rotating());
        assertEquals(OTHER_OWNER, controller.snapshot().owner().orElseThrow());
        assertEquals(70.0F, controller.snapshot().targetYaw().orElseThrow());
    }

    @Test
    void diagnosticsRemainImmutableAcrossStateTransitions() {
        MutableClock clock = new MutableClock();
        RotationController controller = new RotationController(clock);
        RotationSnapshot idle = controller.snapshot();
        RotationHandle handle = controller.start(OWNER, 0.0F, 0.0F, 45.0F, 120.0F, 100L);
        RotationSnapshot active = controller.snapshot();

        assertFalse(idle.active());
        assertTrue(active.active());
        assertEquals(OWNER, active.owner().orElseThrow());
        assertEquals(45.0F, active.targetYaw().orElseThrow());
        assertEquals(90.0F, active.targetPitch().orElseThrow());
        assertTrue(active.movementBlocked());

        handle.cancel();
        RotationSnapshot terminal = controller.snapshot();
        assertTrue(active.active());
        assertFalse(terminal.active());
        assertFalse(terminal.movementBlocked());
        assertEquals(RotationTerminalReason.OWNER_CANCELLED,
                terminal.terminalReason().orElseThrow());
        assertTrue(terminal.revision() > active.revision());
    }

    @Test
    void completionWaitsForExactElapsedDurationAtFloatRoundingBoundaries() {
        assertCompletesOnlyAtExactDuration(100L, 99_999_999L, 100_000_000L);
        assertCompletesOnlyAtExactDuration(1_000L, 999_999_971L, 1_000_000_000L);
        assertCompletesOnlyAtExactDuration(
                Long.MAX_VALUE,
                Long.MAX_VALUE - 200_000_000_000L,
                Long.MAX_VALUE);
    }

    private static void assertCompletesOnlyAtExactDuration(
            long durationMs,
            long earlyElapsedNanos,
            long exactElapsedNanos) {
        MutableClock clock = new MutableClock();
        RotationController controller = new RotationController(clock);
        List<RotationFrame> frames = new ArrayList<>();
        List<RotationResult> results = new ArrayList<>();
        RotationHandle handle = controller.start(
                OWNER, 0.0F, 0.0F, 100.0F, 80.0F, durationMs, results::add);

        clock.setNanos(earlyElapsedNanos);
        assertTrue(controller.tick(frames::add));

        assertEquals(1, frames.size());
        assertFalse(frames.getFirst().complete());
        assertTrue(frames.getFirst().progress() < 1.0F);
        assertNotEquals(100.0F, frames.getFirst().yaw());
        assertNotEquals(80.0F, frames.getFirst().pitch());
        assertTrue(controller.rotating());
        assertTrue(controller.movementBlocked());
        assertEquals(OWNER, controller.snapshot().owner().orElseThrow());
        assertTrue(results.isEmpty());
        assertThrows(
                RotationConflictException.class,
                () -> controller.start(OTHER_OWNER, 0.0F, 0.0F, 10.0F, 0.0F, 1L));

        clock.setNanos(exactElapsedNanos);
        assertTrue(controller.tick(frames::add));

        assertEquals(2, frames.size());
        RotationFrame finalFrame = frames.get(1);
        assertTrue(finalFrame.complete());
        assertEquals(new RotationFrame(100.0F, 80.0F, 1.0F), finalFrame);
        assertFalse(controller.rotating());
        assertFalse(controller.movementBlocked());
        assertFalse(handle.cancel());
        assertEquals(1, results.size());
        assertTrue(results.getFirst().completed());
        assertEquals(1.0F, results.getFirst().progress());
        assertEquals(RotationTerminalReason.COMPLETED,
                controller.snapshot().terminalReason().orElseThrow());
    }

    private static final class MutableClock implements MonotonicClock {
        private long nowNanos;

        @Override
        public long nowNanos() {
            return nowNanos;
        }

        private void setNanos(long nowNanos) {
            this.nowNanos = nowNanos;
        }

        private void advanceNanos(long deltaNanos) {
            nowNanos += deltaNanos;
        }
    }
}
