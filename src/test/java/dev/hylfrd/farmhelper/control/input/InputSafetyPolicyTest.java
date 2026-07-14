package dev.hylfrd.farmhelper.control.input;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InputSafetyPolicyTest {
    @Test
    void exceptionAfterAcquisitionGloballyReleasesEveryInput() {
        InputController controller = new InputController();
        InputSafetyPolicy policy = new InputSafetyPolicy();
        ControlOwner owner = new ControlOwner("macro");

        IllegalStateException exception = assertThrows(IllegalStateException.class,
                () -> policy.runSafely(controller, () -> {
                    controller.hold(owner, InputAction.FORWARD, InputAction.USE);
                    controller.selectHotbar(owner, new HotbarSelection(3));
                    throw new IllegalStateException("boom");
                }));

        assertEquals("boom", exception.getMessage());
        assertTrue(controller.snapshot().emptyState());
        assertEquals(ReleaseReason.EXCEPTION, controller.snapshot().releaseReason().orElseThrow());
    }

    @Test
    void explicitSafetyReleaseUsesTheRequestedReason() {
        InputController controller = new InputController();
        InputSafetyPolicy policy = new InputSafetyPolicy();
        controller.hold(new ControlOwner("macro"), InputAction.SNEAK);

        policy.release(controller, ReleaseReason.SCREEN);

        assertTrue(controller.snapshot().emptyState());
        assertEquals(ReleaseReason.SCREEN, controller.snapshot().releaseReason().orElseThrow());
    }
}
