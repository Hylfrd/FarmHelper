package dev.hylfrd.farmhelper.client.control;

import dev.hylfrd.farmhelper.control.RotationTask;
import dev.hylfrd.farmhelper.control.input.ControlOwner;
import dev.hylfrd.farmhelper.control.rotation.RotationCancelReason;
import dev.hylfrd.farmhelper.control.rotation.RotationController;
import dev.hylfrd.farmhelper.control.rotation.RotationHandle;
import dev.hylfrd.farmhelper.control.rotation.RotationSnapshot;
import dev.hylfrd.farmhelper.runtime.time.MonotonicClock;
import dev.hylfrd.farmhelper.runtime.time.SystemMonotonicClock;
import net.minecraft.client.Minecraft;

import java.util.Objects;
import java.util.Optional;

/** Minecraft adapter for explicit, one-shot rotation leases. */
public final class ClientRotationController {
    private static final ControlOwner EXPLICIT_COMMAND_OWNER = new ControlOwner("explicit-rotation-command");

    private final RotationController controller;
    private final Runnable acquisitionGuard;

    public ClientRotationController() {
        this(SystemMonotonicClock.INSTANCE, () -> { });
    }

    public ClientRotationController(Runnable acquisitionGuard) {
        this(SystemMonotonicClock.INSTANCE, acquisitionGuard);
    }

    ClientRotationController(MonotonicClock clock) {
        this(clock, () -> { });
    }

    ClientRotationController(MonotonicClock clock, Runnable acquisitionGuard) {
        controller = new RotationController(Objects.requireNonNull(clock, "clock"));
        this.acquisitionGuard = Objects.requireNonNull(acquisitionGuard, "acquisitionGuard");
    }

    public boolean rotating() {
        return controller.rotating();
    }

    public boolean movementBlocked() {
        return controller.movementBlocked();
    }

    public boolean paused() {
        return controller.paused();
    }

    /** Compatibility view; {@link RotationTask} is immutable and cannot mutate controller state. */
    public Optional<RotationTask> task() {
        return controller.task();
    }

    public RotationSnapshot snapshot() {
        return controller.snapshot();
    }

    public boolean start(Minecraft client, float targetYaw, float targetPitch, long durationMs) {
        requireClientThread(client);
        return start(new MinecraftRotationView(client), targetYaw, targetPitch, durationMs);
    }

    /** Owned domain entry used by macro/navigation consumers after capturing the current frame. */
    public RotationHandle start(
            ControlOwner owner,
            float startYaw,
            float startPitch,
            float targetYaw,
            float targetPitch,
            long durationMs) {
        acquisitionGuard.run();
        return controller.start(Objects.requireNonNull(owner, "owner"), startYaw, startPitch,
                targetYaw, targetPitch, durationMs);
    }

    boolean start(RotationView view, float targetYaw, float targetPitch, long durationMs) {
        Objects.requireNonNull(view, "view");
        if (!view.playerPresent()) {
            return false;
        }
        acquisitionGuard.run();
        controller.start(
                EXPLICIT_COMMAND_OWNER,
                view.yaw(),
                view.pitch(),
                targetYaw,
                targetPitch,
                durationMs);
        return true;
    }

    public void stop() {
        cancel(RotationCancelReason.STOPPED);
    }

    /** Cancels the active owned rotation for a concrete lifecycle reason. */
    public boolean cancel(RotationCancelReason reason) {
        Objects.requireNonNull(reason, "reason");
        return controller.snapshot().owner()
                .filter(owner -> controller.cancel(owner, reason))
                .isPresent();
    }

    public void tick(Minecraft client) {
        tick(new MinecraftRotationView(client));
    }

    void tick(RotationView view) {
        Objects.requireNonNull(view, "view");
        if (!controller.rotating()) {
            return;
        }
        if (!view.playerPresent()) {
            cancel(RotationCancelReason.PLAYER_MISSING);
            return;
        }
        if (view.screenOpen()) {
            controller.pause();
            return;
        }

        controller.resume();
        controller.tick(frame -> view.apply(frame.yaw(), frame.pitch()));
    }

    interface RotationView {
        boolean playerPresent();

        boolean screenOpen();

        float yaw();

        float pitch();

        void apply(float yaw, float pitch);
    }

    private static final class MinecraftRotationView implements RotationView {
        private final Minecraft client;

        private MinecraftRotationView(Minecraft client) {
            this.client = Objects.requireNonNull(client, "client");
        }

        @Override
        public boolean playerPresent() {
            return client.player != null;
        }

        @Override
        public boolean screenOpen() {
            return client.screen != null;
        }

        @Override
        public float yaw() {
            return client.player.getYRot();
        }

        @Override
        public float pitch() {
            return client.player.getXRot();
        }

        @Override
        public void apply(float yaw, float pitch) {
            client.player.setYRot(yaw);
            client.player.setXRot(pitch);
            client.player.setYHeadRot(yaw);
        }
    }

    private static void requireClientThread(Minecraft client) {
        Objects.requireNonNull(client, "client");
        if (!client.isSameThread()) {
            throw new IllegalStateException("Rotation mutation must run on the client thread");
        }
    }
}
