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
    private final Runnable acquisitionGuard;
    private InputSnapshot appliedSnapshot = InputSnapshot.empty();
    private HotbarSelection rollbackHotbar;
    private HotbarSelection managedHotbar;

    public ClientInputController() {
        this(new MinecraftInputSink(), () -> { });
    }

    public ClientInputController(Runnable acquisitionGuard) {
        this(new MinecraftInputSink(), acquisitionGuard);
    }

    /** Headless composition used by Minecraft-free runtime tests; it never mutates client state. */
    public static ClientInputController detached(Runnable acquisitionGuard) {
        return new ClientInputController(new DetachedInputSink(), acquisitionGuard);
    }

    ClientInputController(InputSink sink) {
        this(sink, () -> { });
    }

    ClientInputController(InputSink sink, Runnable acquisitionGuard) {
        this.sink = Objects.requireNonNull(sink, "sink");
        this.acquisitionGuard = Objects.requireNonNull(acquisitionGuard, "acquisitionGuard");
        controller = new InputController(this::apply);
    }

    public InputSnapshot snapshot() {
        return controller.snapshot();
    }

    public InputLease hold(ControlOwner owner, InputAction... actions) {
        acquisitionGuard.run();
        return controller.hold(owner, actions);
    }

    public InputLease hold(ControlOwner owner, Collection<InputAction> actions) {
        acquisitionGuard.run();
        return controller.hold(owner, actions);
    }

    public InputLease hold(
            ControlOwner owner,
            Collection<InputAction> actions,
            Optional<HotbarSelection> hotbarSelection) {
        acquisitionGuard.run();
        return controller.hold(owner, actions, hotbarSelection);
    }

    public InputLease replace(ControlOwner owner, Collection<InputAction> actions) {
        acquisitionGuard.run();
        return controller.replace(owner, actions);
    }

    public InputLease replace(
            ControlOwner owner,
            Collection<InputAction> actions,
            Optional<HotbarSelection> hotbarSelection) {
        acquisitionGuard.run();
        return controller.replace(owner, actions, hotbarSelection);
    }

    public InputLease selectHotbar(ControlOwner owner, HotbarSelection selection) {
        acquisitionGuard.run();
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

    /** Compatibility bridge for command callers that still pass the current client. */
    public void releaseAll(Minecraft client) {
        requireClientThread(client);
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
            Throwable failure = null;
            try {
                rollbackHotbarIfStillManaged();
            } catch (RuntimeException | Error exception) {
                failure = exception;
            }
            try {
                forceReleaseActions();
            } catch (RuntimeException | Error exception) {
                failure = append(failure, exception);
            } finally {
                appliedSnapshot = next;
            }
            rethrow(failure);
            return;
        }

        for (InputAction action : InputAction.values()) {
            boolean wasHeld = appliedSnapshot.held(action);
            boolean held = next.held(action);
            if (wasHeld != held) {
                sink.setAction(action, held);
            }
        }
        applyHotbar(next.hotbarSelection());
        appliedSnapshot = next;
    }

    private void applyHotbar(Optional<HotbarSelection> next) {
        if (next.isPresent()) {
            HotbarSelection desired = next.orElseThrow();
            if (managedHotbar == null) {
                rollbackHotbar = sink.currentHotbar().orElse(null);
            }
            if (!desired.equals(managedHotbar)) {
                managedHotbar = desired;
                sink.selectHotbar(desired);
            }
            return;
        }
        rollbackHotbarIfStillManaged();
    }

    private void rollbackHotbarIfStillManaged() {
        HotbarSelection managed = managedHotbar;
        HotbarSelection rollback = rollbackHotbar;
        managedHotbar = null;
        rollbackHotbar = null;
        if (managed == null || rollback == null || managed.equals(rollback)) {
            return;
        }
        if (sink.currentHotbar().filter(managed::equals).isPresent()) {
            sink.selectHotbar(rollback);
        }
    }

    private void forceReleaseActions() {
        Throwable failure = null;
        for (InputAction action : InputAction.values()) {
            try {
                sink.setAction(action, false);
            } catch (RuntimeException | Error exception) {
                if (failure == null) {
                    failure = exception;
                } else if (failure != exception) {
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

        Optional<HotbarSelection> currentHotbar();

        void selectHotbar(HotbarSelection selection);
    }

    private static final class MinecraftInputSink implements InputSink {
        @Override
        public void setAction(InputAction action, boolean down) {
            Minecraft client = Minecraft.getInstance();
            requireClientThread(client);
            mapping(client, action).setDown(down);
        }

        @Override
        public Optional<HotbarSelection> currentHotbar() {
            Minecraft client = Minecraft.getInstance();
            requireClientThread(client);
            return client.player == null
                    ? Optional.empty()
                    : Optional.of(new HotbarSelection(client.player.getInventory().getSelectedSlot()));
        }

        @Override
        public void selectHotbar(HotbarSelection selection) {
            Minecraft client = Minecraft.getInstance();
            requireClientThread(client);
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

    private static final class DetachedInputSink implements InputSink {
        private HotbarSelection hotbar;

        @Override
        public void setAction(InputAction action, boolean down) { }

        @Override
        public Optional<HotbarSelection> currentHotbar() {
            return Optional.ofNullable(hotbar);
        }

        @Override
        public void selectHotbar(HotbarSelection selection) {
            hotbar = selection;
        }
    }

    private static Throwable append(Throwable primary, Throwable additional) {
        if (primary == null) {
            return additional;
        }
        if (primary != additional) {
            primary.addSuppressed(additional);
        }
        return primary;
    }

    private static void rethrow(Throwable failure) {
        if (failure instanceof RuntimeException runtimeException) {
            throw runtimeException;
        }
        if (failure instanceof Error error) {
            throw error;
        }
    }

    private static void requireClientThread(Minecraft client) {
        Objects.requireNonNull(client, "client");
        if (!client.isSameThread()) {
            throw new IllegalStateException("Input mutation must run on the client thread");
        }
    }
}
