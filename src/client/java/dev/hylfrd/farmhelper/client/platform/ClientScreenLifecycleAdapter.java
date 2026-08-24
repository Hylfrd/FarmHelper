package dev.hylfrd.farmhelper.client.platform;

import dev.hylfrd.farmhelper.client.runtime.FarmHelperClientRuntime;
import dev.hylfrd.farmhelper.platform.FarmHelper;
import dev.hylfrd.farmhelper.runtime.snapshot.ClientSnapshot;
import dev.hylfrd.farmhelper.runtime.snapshot.Observation;
import dev.hylfrd.farmhelper.runtime.snapshot.ScreenSnapshot;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.Screen;

import java.util.Objects;

/**
 * Shares the screen lifecycle state between the real Minecraft#setScreen boundary and tick
 * snapshots. Non-chat openings are terminal; chat closes armed by a command remain exempt.
 */
public final class ClientScreenLifecycleAdapter {
    private final FarmHelperClientRuntime runtime;
    private final ClientCommandScreenCloseGuard commandScreenClose;
    private final ClientSnapshotCapture snapshots;

    ClientScreenLifecycleAdapter(
            FarmHelperClientRuntime runtime,
            ClientCommandScreenCloseGuard commandScreenClose,
            ClientSnapshotCapture snapshots
    ) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.commandScreenClose = Objects.requireNonNull(commandScreenClose, "commandScreenClose");
        this.snapshots = Objects.requireNonNull(snapshots, "snapshots");
    }

    /** Receives the client-thread tail of Minecraft#setScreen. */
    public void observeSetScreen(Minecraft client) {
        Objects.requireNonNull(client, "client");
        try {
            if (!client.isSameThread()) {
                client.execute(() -> observeSetScreen(client));
                return;
            }
            observeBoundary(snapshots.captureScreen(client.screen), client.screen,
                    client.screen instanceof ChatScreen);
        } catch (RuntimeException | Error failure) {
            ClientTickAdapter.fail(runtime, commandScreenClose);
            FarmHelper.LOGGER.error("FarmHelper screen lifecycle boundary failed", failure);
        }
    }

    void observeSnapshot(ClientSnapshot snapshot, Screen currentScreen) {
        Objects.requireNonNull(snapshot, "snapshot");
        observeBoundary(snapshot.screen(), currentScreen, currentScreen instanceof ChatScreen);
    }

    void observeBoundary(
            Observation<ScreenSnapshot> screen,
            Object currentScreen,
            boolean chatScreen
    ) {
        Objects.requireNonNull(screen, "screen");
        runtime.observeMacroScreen(screen);
        commandScreenClose.observeScreen(
                screen, currentScreen, chatScreen,
                runtime.lifecycle(), runtime.ownershipGeneration());
    }
}
