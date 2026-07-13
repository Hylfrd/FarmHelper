package dev.hylfrd.farmhelper.client.control;

import dev.hylfrd.farmhelper.control.RotationTask;
import net.minecraft.client.Minecraft;

import java.util.Optional;

public final class ClientRotationController {
    private RotationTask task;

    public boolean rotating() {
        return task != null;
    }

    public boolean paused() {
        return task != null && task.paused();
    }

    public Optional<RotationTask> task() {
        return Optional.ofNullable(task);
    }

    public boolean start(Minecraft client, float targetYaw, float targetPitch, long durationMs) {
        if (client.player == null) {
            return false;
        }
        task = new RotationTask(
                client.player.getYRot(),
                client.player.getXRot(),
                targetYaw,
                targetPitch,
                durationMs);
        return true;
    }

    public void stop() {
        task = null;
    }

    public void tick(Minecraft client) {
        if (task == null) {
            return;
        }
        if (client.player == null) {
            stop();
            return;
        }

        long now = System.currentTimeMillis();
        if (client.screen != null) {
            task.pause(now);
            return;
        }
        task.resume(now);

        if (task.finished(now)) {
            apply(client, task.targetYaw(), task.targetPitch());
            task = null;
            return;
        }

        apply(client, task.yaw(now), task.pitch(now));
    }

    private void apply(Minecraft client, float yaw, float pitch) {
        client.player.setYRot(yaw);
        client.player.setXRot(pitch);
        client.player.setYHeadRot(yaw);
    }
}
