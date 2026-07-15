package dev.hylfrd.farmhelper.client.control;

import dev.hylfrd.farmhelper.control.rotation.RotationTerminalReason;
import dev.hylfrd.farmhelper.runtime.time.MonotonicClock;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClientRotationControllerTest {
    private static final float EPSILON = 0.0001F;

    @Test
    void screenPausesWithoutApplyingOrTerminatingAndResumeIsContinuous() {
        MutableClock clock = new MutableClock();
        ClientRotationController controller = new ClientRotationController(clock);
        TestView view = new TestView();
        view.yaw = 0.0F;
        view.pitch = 0.0F;
        assertTrue(controller.start(view, 100.0F, 80.0F, 100L));

        clock.advanceNanos(25_000_000L);
        controller.tick(view);
        float pausedYaw = view.yaw;
        float pausedPitch = view.pitch;
        int applicationsBeforeScreen = view.applications;

        view.screenOpen = true;
        controller.tick(view);
        clock.advanceNanos(1_000_000_000L);
        controller.tick(view);

        assertTrue(controller.rotating());
        assertTrue(controller.paused());
        assertTrue(controller.movementBlocked());
        assertEquals(applicationsBeforeScreen, view.applications);
        assertEquals(pausedYaw, view.yaw, EPSILON);
        assertEquals(pausedPitch, view.pitch, EPSILON);

        view.screenOpen = false;
        controller.tick(view);
        assertEquals(pausedYaw, view.yaw, EPSILON);
        assertEquals(pausedPitch, view.pitch, EPSILON);
        clock.advanceNanos(75_000_000L);
        controller.tick(view);

        assertEquals(100.0F, view.yaw, EPSILON);
        assertEquals(80.0F, view.pitch, EPSILON);
        assertFalse(controller.rotating());
        assertFalse(controller.paused());
        assertFalse(controller.movementBlocked());
        assertEquals(RotationTerminalReason.COMPLETED,
                controller.snapshot().terminalReason().orElseThrow());
    }

    @Test
    void missingPlayerCancelsSafelyWithExplicitReason() {
        MutableClock clock = new MutableClock();
        ClientRotationController controller = new ClientRotationController(clock);
        TestView view = new TestView();
        assertTrue(controller.start(view, 10.0F, 20.0F, 100L));

        view.playerPresent = false;
        controller.tick(view);

        assertFalse(controller.rotating());
        assertEquals(0, view.applications);
        assertEquals(RotationTerminalReason.PLAYER_MISSING,
                controller.snapshot().terminalReason().orElseThrow());
    }

    @Test
    void stopCompletionAndSameOwnerReplacementCoverCurrentTerminalPaths() {
        MutableClock clock = new MutableClock();
        ClientRotationController controller = new ClientRotationController(clock);
        TestView view = new TestView();
        controller.start(view, 10.0F, 0.0F, 100L);
        controller.start(view, 20.0F, 0.0F, 0L);
        assertTrue(controller.rotating());

        controller.tick(view);
        assertEquals(RotationTerminalReason.COMPLETED,
                controller.snapshot().terminalReason().orElseThrow());
        assertFalse(controller.rotating());

        assertTrue(controller.start(view, 30.0F, 0.0F, 100L));
        controller.stop();
        assertEquals(RotationTerminalReason.STOPPED,
                controller.snapshot().terminalReason().orElseThrow());
        assertFalse(controller.rotating());
    }

    @Test
    void startWithoutPlayerDoesNotCreateRotationOrApplyView() {
        MutableClock clock = new MutableClock();
        ClientRotationController controller = new ClientRotationController(clock);
        TestView view = new TestView();
        view.playerPresent = false;

        assertFalse(controller.start(view, 10.0F, 0.0F, 100L));
        controller.tick(view);

        assertFalse(controller.rotating());
        assertEquals(0, view.applications);
    }

    @Test
    void zeroDurationWaitsForFirstNonScreenTickBeforeApplyingAndCompleting() {
        MutableClock clock = new MutableClock();
        ClientRotationController controller = new ClientRotationController(clock);
        TestView view = new TestView();
        view.yaw = 5.0F;
        view.pitch = 6.0F;
        view.screenOpen = true;
        assertTrue(controller.start(view, 45.0F, 30.0F, 0L));

        controller.tick(view);

        assertTrue(controller.rotating());
        assertTrue(controller.paused());
        assertEquals(0, view.applications);
        assertEquals(5.0F, view.yaw);
        assertEquals(6.0F, view.pitch);

        view.screenOpen = false;
        controller.tick(view);

        assertEquals(1, view.applications);
        assertEquals(45.0F, view.yaw);
        assertEquals(30.0F, view.pitch);
        assertFalse(controller.rotating());
        assertEquals(RotationTerminalReason.COMPLETED,
                controller.snapshot().terminalReason().orElseThrow());
    }

    @Test
    void offClientThreadTickFailsBeforeReadingOrMutatingAnyPlayerRotationState() {
        MutableClock clock = new MutableClock();
        ClientRotationController controller = new ClientRotationController(clock);
        TestView initialView = new TestView();
        assertTrue(controller.start(initialView, 90.0F, 30.0F, 100L));

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> controller.tick(new UntouchableView(), () -> {
                    throw new IllegalStateException("not on client thread");
                }));

        assertEquals("not on client thread", failure.getMessage());
        assertEquals(0, initialView.applications);
        assertTrue(controller.rotating());
    }

    private static final class MutableClock implements MonotonicClock {
        private long nowNanos;

        @Override
        public long nowNanos() {
            return nowNanos;
        }

        private void advanceNanos(long deltaNanos) {
            nowNanos += deltaNanos;
        }
    }

    private static final class TestView implements ClientRotationController.RotationView {
        private boolean playerPresent = true;
        private boolean screenOpen;
        private float yaw;
        private float pitch;
        private int applications;

        @Override
        public boolean playerPresent() {
            return playerPresent;
        }

        @Override
        public boolean screenOpen() {
            return screenOpen;
        }

        @Override
        public float yaw() {
            return yaw;
        }

        @Override
        public float pitch() {
            return pitch;
        }

        @Override
        public void apply(float yaw, float pitch) {
            this.yaw = yaw;
            this.pitch = pitch;
            applications++;
        }
    }

    private static final class UntouchableView implements ClientRotationController.RotationView {
        @Override
        public boolean playerPresent() {
            throw new AssertionError("player state read before client-thread guard");
        }

        @Override
        public boolean screenOpen() {
            throw new AssertionError("screen state read before client-thread guard");
        }

        @Override
        public float yaw() {
            throw new AssertionError("yaw read before client-thread guard");
        }

        @Override
        public float pitch() {
            throw new AssertionError("pitch read before client-thread guard");
        }

        @Override
        public void apply(float yaw, float pitch) {
            throw new AssertionError("rotation mutated before client-thread guard");
        }
    }
}
