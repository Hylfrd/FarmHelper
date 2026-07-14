package dev.hylfrd.farmhelper.client.control;

import dev.hylfrd.farmhelper.control.input.ControlOwner;
import dev.hylfrd.farmhelper.control.input.HotbarSelection;
import dev.hylfrd.farmhelper.control.input.InputAction;
import dev.hylfrd.farmhelper.control.input.ReleaseReason;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClientInputControllerTest {
    private static final ControlOwner OWNER = new ControlOwner("adapter-test");

    @Test
    void adapterMapsEveryActionAndHotbarSelectionThroughItsOnlySink() {
        RecordingSink sink = new RecordingSink();
        ClientInputController controller = new ClientInputController(sink);

        controller.hold(
                OWNER,
                EnumSet.allOf(InputAction.class),
                Optional.of(new HotbarSelection(6)));

        assertEquals(EnumSet.allOf(InputAction.class), ClientInputController.mappedActions());
        assertEquals(EnumSet.allOf(InputAction.class), controller.snapshot().heldActions());
        for (InputAction action : InputAction.values()) {
            assertTrue(sink.states.get(action), action.name());
        }
        assertEquals(List.of(new HotbarSelection(6)), sink.hotbarSelections);

        controller.releaseAll(ReleaseReason.PAUSE);

        for (InputAction action : InputAction.values()) {
            assertFalse(sink.states.get(action), action.name());
        }
        assertEquals(ReleaseReason.PAUSE, controller.snapshot().releaseReason().orElseThrow());
    }

    @Test
    void outputExceptionForcesAllActionsUpAndClearsDomainOwnership() {
        RecordingSink sink = new RecordingSink();
        sink.failWhenPressing = InputAction.BACKWARD;
        ClientInputController controller = new ClientInputController(sink);

        assertThrows(IllegalStateException.class,
                () -> controller.hold(OWNER, InputAction.FORWARD, InputAction.BACKWARD));

        assertTrue(controller.snapshot().emptyState());
        assertEquals(ReleaseReason.EXCEPTION, controller.snapshot().releaseReason().orElseThrow());
        for (InputAction action : InputAction.values()) {
            assertFalse(sink.states.get(action), action.name());
        }
    }

    private static final class RecordingSink implements ClientInputController.InputSink {
        private final Map<InputAction, Boolean> states = new EnumMap<>(InputAction.class);
        private final List<HotbarSelection> hotbarSelections = new ArrayList<>();
        private InputAction failWhenPressing;

        private RecordingSink() {
            for (InputAction action : InputAction.values()) {
                states.put(action, false);
            }
        }

        @Override
        public void setAction(InputAction action, boolean down) {
            if (down && action == failWhenPressing) {
                throw new IllegalStateException("simulated mapping failure");
            }
            states.put(action, down);
        }

        @Override
        public void selectHotbar(HotbarSelection selection) {
            hotbarSelections.add(selection);
        }
    }
}
