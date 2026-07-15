package dev.hylfrd.farmhelper.client.control;

import dev.hylfrd.farmhelper.control.input.ControlOwner;
import dev.hylfrd.farmhelper.control.input.HotbarSelection;
import dev.hylfrd.farmhelper.control.input.InputAction;
import dev.hylfrd.farmhelper.control.input.ReleaseReason;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

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
import static org.junit.jupiter.api.Assertions.assertSame;

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

    @Test
    void completedHotbarLeaseRestoresThePreLeasePhysicalSlot() {
        RecordingSink sink = new RecordingSink(new HotbarSelection(2));
        ClientInputController controller = new ClientInputController(sink);

        var lease = controller.selectHotbar(OWNER, new HotbarSelection(6));
        lease.close();

        assertEquals(new HotbarSelection(2), sink.physicalHotbar);
        assertEquals(List.of(new HotbarSelection(6), new HotbarSelection(2)),
                sink.hotbarSelections);
        assertTrue(controller.snapshot().hotbarOwner().isEmpty());
    }

    @ParameterizedTest
    @EnumSource(value = ReleaseReason.class, names = {
            "STOP", "SCREEN", "WORLD_CHANGE", "DISCONNECT", "EXCEPTION", "EXIT"
    })
    void everyLifecycleTerminalPathRestoresThePreLeasePhysicalSlot(ReleaseReason reason) {
        RecordingSink sink = new RecordingSink(new HotbarSelection(1));
        ClientInputController controller = new ClientInputController(sink);
        controller.selectHotbar(OWNER, new HotbarSelection(7));

        controller.releaseAll(reason);

        assertEquals(new HotbarSelection(1), sink.physicalHotbar);
        assertEquals(List.of(new HotbarSelection(7), new HotbarSelection(1)),
                sink.hotbarSelections);
        assertTrue(controller.snapshot().hotbarOwner().isEmpty());
        assertEquals(reason, controller.snapshot().releaseReason().orElseThrow());
    }

    @Test
    void releaseDoesNotOverwriteALaterExternalHotbarSelection() {
        RecordingSink sink = new RecordingSink(new HotbarSelection(2));
        ClientInputController controller = new ClientInputController(sink);
        controller.selectHotbar(OWNER, new HotbarSelection(6));
        sink.physicalHotbar = new HotbarSelection(4);

        controller.releaseAll(ReleaseReason.STOP);

        assertEquals(new HotbarSelection(4), sink.physicalHotbar);
        assertEquals(List.of(new HotbarSelection(6)), sink.hotbarSelections);
        assertTrue(controller.snapshot().hotbarOwner().isEmpty());
    }

    @Test
    void repeatedSameSinkFailureDoesNotMaskTheOriginalWithSelfSuppression() {
        RecordingSink sink = new RecordingSink(new HotbarSelection(2));
        ClientInputController controller = new ClientInputController(sink);
        controller.hold(OWNER, List.of(InputAction.FORWARD),
                Optional.of(new HotbarSelection(6)));
        IllegalStateException shared = new IllegalStateException("shared sink failure");
        sink.sharedReleaseFailure = shared;

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> controller.releaseAll(ReleaseReason.EXCEPTION));

        assertSame(shared, thrown);
        assertTrue(controller.snapshot().emptyState());
        assertEquals(ReleaseReason.EXCEPTION,
                controller.snapshot().releaseReason().orElseThrow());
    }

    private static final class RecordingSink implements ClientInputController.InputSink {
        private final Map<InputAction, Boolean> states = new EnumMap<>(InputAction.class);
        private final List<HotbarSelection> hotbarSelections = new ArrayList<>();
        private InputAction failWhenPressing;
        private HotbarSelection physicalHotbar;
        private IllegalStateException sharedReleaseFailure;

        private RecordingSink() {
            this(new HotbarSelection(0));
        }

        private RecordingSink(HotbarSelection physicalHotbar) {
            this.physicalHotbar = physicalHotbar;
            for (InputAction action : InputAction.values()) {
                states.put(action, false);
            }
        }

        @Override
        public void setAction(InputAction action, boolean down) {
            if (!down && sharedReleaseFailure != null) {
                throw sharedReleaseFailure;
            }
            if (down && action == failWhenPressing) {
                throw new IllegalStateException("simulated mapping failure");
            }
            states.put(action, down);
        }

        @Override
        public Optional<HotbarSelection> currentHotbar() {
            if (sharedReleaseFailure != null) {
                throw sharedReleaseFailure;
            }
            return Optional.of(physicalHotbar);
        }

        @Override
        public void selectHotbar(HotbarSelection selection) {
            physicalHotbar = selection;
            hotbarSelections.add(selection);
        }
    }
}
