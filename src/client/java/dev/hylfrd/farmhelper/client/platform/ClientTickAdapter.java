package dev.hylfrd.farmhelper.client.platform;

import dev.hylfrd.farmhelper.client.runtime.FarmHelperClientRuntime;
import dev.hylfrd.farmhelper.macro.MacroContext;
import dev.hylfrd.farmhelper.macro.MacroState;
import dev.hylfrd.farmhelper.macro.PauseReason;
import dev.hylfrd.farmhelper.macro.PlayerSnapshot;
import dev.hylfrd.farmhelper.macro.WorldMode;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;

/** Adapts Fabric client ticks and Minecraft state into the common runtime model. */
public final class ClientTickAdapter {
    private ClientTickAdapter() {
    }

    public static void register(FarmHelperClientRuntime runtime) {
        ClientTickAdapter adapter = new ClientTickAdapter();
        ClientTickEvents.END_CLIENT_TICK.register(client -> adapter.tick(client, runtime));
    }

    private void tick(Minecraft client, FarmHelperClientRuntime runtime) {
        boolean worldReady = client.level != null;
        boolean playerReady = worldReady && client.player != null;
        boolean screenOpen = client.screen != null;
        PauseReason pauseReason = pauseReason(worldReady, playerReady, screenOpen);
        WorldMode worldMode = worldMode(client, worldReady);
        PlayerSnapshot playerSnapshot = playerReady
                ? new PlayerSnapshot(
                        client.player.getX(),
                        client.player.getY(),
                        client.player.getZ(),
                        client.player.getYRot(),
                        client.player.getXRot())
                : PlayerSnapshot.empty();

        runtime.core().macroManager().tick(new MacroContext(
                playerReady,
                screenOpen,
                pauseReason,
                worldMode,
                playerSnapshot));

        runtime.rotation().tick(client);
        if (shouldReleaseInputs(screenOpen, runtime)) {
            runtime.input().releaseAll(client);
        }
    }

    private boolean shouldReleaseInputs(boolean screenOpen, FarmHelperClientRuntime runtime) {
        return screenOpen
                || runtime.rotation().rotating()
                || runtime.core().macroManager().state() != MacroState.RUNNING;
    }

    private PauseReason pauseReason(boolean worldReady, boolean playerReady, boolean screenOpen) {
        if (!worldReady) {
            return PauseReason.NO_WORLD;
        }
        if (!playerReady) {
            return PauseReason.NO_PLAYER;
        }
        if (screenOpen) {
            return PauseReason.SCREEN_OPEN;
        }
        return PauseReason.NONE;
    }

    private WorldMode worldMode(Minecraft client, boolean worldReady) {
        if (!worldReady) {
            return WorldMode.NONE;
        }
        return client.hasSingleplayerServer() ? WorldMode.SINGLEPLAYER : WorldMode.MULTIPLAYER;
    }
}
