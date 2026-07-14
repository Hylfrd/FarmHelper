package dev.hylfrd.farmhelper.client.control;

import dev.hylfrd.farmhelper.control.MovementKey;
import dev.hylfrd.farmhelper.control.input.ControlOwner;
import dev.hylfrd.farmhelper.control.input.HotbarSelection;
import dev.hylfrd.farmhelper.control.input.InputAction;
import dev.hylfrd.farmhelper.control.input.InputController;
import dev.hylfrd.farmhelper.control.input.InputLease;
import dev.hylfrd.farmhelper.control.input.InputSnapshot;
import dev.hylfrd.farmhelper.control.input.ReleaseReason;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;

import java.util.Collection;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/** The sole adapter allowed to apply managed input state to the Minecraft client. */
public final class ClientInputController {
    private final InputSink sink;
    private final InputController controller;
    private InputSnapshot appliedSnapshot = InputSnapshot.empty();

    public ClientInputController() {
        this(new MinecraftInputSink());
    }

    ClientInputController(InputSink sink) {
        this.sink = Objects.requireNonNull(sink, "sink");
        controller = new InputController(this::apply);
    }

    public InputSnapshot snapshot() {
        return controller.snapshot();
    }

    public InputLease hold(ControlOwner owner, InputAction... actions) {
        return controller.hold(owner, actions);
    }

    public InputLease hold(ControlOwner owner, Collection<InputAction> actions) {
        return controller.hold(owner, actions);
    }

    public InputLease hold(
            ControlOwner owner,
            Collection<InputAction> actions,
            Optional<HotbarSelection> hotbarSelection) {
        return controller.hold(owner, actions, hotbarSelection);
    }

    public InputLease replace(ControlOwner owner, Collection<InputAction> actions) {
        return controller.replace(owner, actions);
    }

    public InputLease replace(
            ControlOwner owner,
            Collection<InputAction> actions,
            Optional<HotbarSelection> hotbarSelection) {
        return controller.replace(owner, actions, hotbarSelection);
    }

    public InputLease selectHotbar(ControlOwner owner, HotbarSelection selection) {
        return controller.selectHotbar(owner, selection);
    }

    public void release(ControlOwner owner, InputAction... actions) {
        controller.release(owner, actions);
    }

    public void release(ControlOwner owner) {
        controller.release(owner);
    }

    public void releaseHotbar(ControlOwner owner) {
        controller.releaseHotbar(owner);
    }

    public void releaseAll(ReleaseReason reason) {
        controller.releaseAll(reason);
    }

    /** Compatibility bridge for the current composition root until lifecycle wiring moves to S2-T8. */
    public void releaseAll(Minecraft client) {
        Objects.requireNonNull(client, "client");
        releaseAll(ReleaseReason.STOP);
    }

    public Set<MovementKey> heldKeys() {
        EnumSet<MovementKey> keys = EnumSet.noneOf(MovementKey.class);
        for (MovementKey key : MovementKey.values()) {
            if (snapshot().held(key.action())) {
                keys.add(key);
            }
        }
        return Set.copyOf(keys);
    }

    public String heldKeysText() {
        Set<InputAction> actions = snapshot().heldActions();
        if (actions.isEmpty()) {
            return "none";
        }
        return actions.stream()
                .map(action -> action.name().toLowerCase())
                .collect(Collectors.joining(", "));
    }

    static Set<InputAction> mappedActions() {
        return Set.copyOf(EnumSet.allOf(InputAction.class));
    }

    private void apply(InputSnapshot next) {
        if (next.releaseReason().filter(reason -> reason == ReleaseReason.EXCEPTION).isPresent()) {
            forceReleaseActions();
            appliedSnapshot = next;
            return;
        }

        for (InputAction action : InputAction.values()) {
            boolean wasHeld = appliedSnapshot.held(action);
            boolean held = next.held(action);
            if (wasHeld != held) {
                sink.setAction(action, held);
            }
        }
        if (next.hotbarSelection().isPresent()
                && !next.hotbarSelection().equals(appliedSnapshot.hotbarSelection())) {
            sink.selectHotbar(next.hotbarSelection().orElseThrow());
        }
        appliedSnapshot = next;
    }

    private void forceReleaseActions() {
        Throwable failure = null;
        for (InputAction action : InputAction.values()) {
            try {
                sink.setAction(action, false);
            } catch (RuntimeException | Error exception) {
                if (failure == null) {
                    failure = exception;
                } else {
                    failure.addSuppressed(exception);
                }
            }
        }
        if (failure instanceof RuntimeException runtimeException) {
            throw runtimeException;
        }
        if (failure instanceof Error error) {
            throw error;
        }
    }

    interface InputSink {
        void setAction(InputAction action, boolean down);

        void selectHotbar(HotbarSelection selection);
    }

    private static final class MinecraftInputSink implements InputSink {
        @Override
        public void setAction(InputAction action, boolean down) {
            Minecraft client = Minecraft.getInstance();
            mapping(client, action).setDown(down);
        }

        @Override
        public void selectHotbar(HotbarSelection selection) {
            Minecraft client = Minecraft.getInstance();
            if (client.player == null) {
                throw new IllegalStateException("Cannot select a hotbar slot without a player");
            }
            client.player.getInventory().setSelectedSlot(selection.slot());
        }

        private static KeyMapping mapping(Minecraft client, InputAction action) {
            Objects.requireNonNull(client, "client");
            return switch (Objects.requireNonNull(action, "action")) {
                case FORWARD -> client.options.keyUp;
                case BACKWARD -> client.options.keyDown;
                case LEFT -> client.options.keyLeft;
                case RIGHT -> client.options.keyRight;
                case JUMP -> client.options.keyJump;
                case SNEAK -> client.options.keyShift;
                case SPRINT -> client.options.keySprint;
                case ATTACK -> client.options.keyAttack;
                case USE -> client.options.keyUse;
            };
        }
    }
}
