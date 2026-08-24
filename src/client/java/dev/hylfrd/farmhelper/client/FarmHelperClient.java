package dev.hylfrd.farmhelper.client;

import dev.hylfrd.farmhelper.client.platform.ClientTickAdapter;
import dev.hylfrd.farmhelper.client.platform.ClientCommandScreenCloseGuard;
import dev.hylfrd.farmhelper.client.platform.ClientScreenLifecycleAdapter;
import dev.hylfrd.farmhelper.client.runtime.FarmHelperClientRuntime;
import dev.hylfrd.farmhelper.client.ui.command.FarmHelperCommands;
import dev.hylfrd.farmhelper.client.ui.settings.FarmHelperSettingsController;
import dev.hylfrd.farmhelper.platform.FarmHelper;
import net.fabricmc.api.ClientModInitializer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.client.Minecraft;

public final class FarmHelperClient implements ClientModInitializer {
    private static volatile FarmHelperClientRuntime activeRuntime;
    private static volatile ClientScreenLifecycleAdapter activeScreenLifecycle;

    @Override
    public void onInitializeClient() {
        FarmHelperClientRuntime runtime = new FarmHelperClientRuntime();
        activeRuntime = runtime;
        ClientCommandScreenCloseGuard commandScreenClose = new ClientCommandScreenCloseGuard();
        FarmHelperSettingsController settings = FarmHelperSettingsController.register(runtime);
        FarmHelperCommands.register(runtime, settings, commandScreenClose);
        activeScreenLifecycle = ClientTickAdapter.register(runtime, commandScreenClose);
        FarmHelper.LOGGER.info("FarmHelper client initialized.");
    }

    /** Receives the real Minecraft#setScreen tail boundary from the client Mixin. */
    public static void recordScreenChange(Minecraft client) {
        ClientScreenLifecycleAdapter screenLifecycle = activeScreenLifecycle;
        if (screenLifecycle != null) {
            screenLifecycle.observeSetScreen(client);
        }
    }

    public static void recordServerTimePacket() {
        FarmHelperClientRuntime runtime = activeRuntime;
        if (runtime != null) {
            runtime.receivedServerTimePacket();
        }
    }

    /** Receives the typed client-thread block-click ingress from the narrow game-mode Mixin. */
    public static void recordClickedBlock(BlockPos position, Direction direction) {
        FarmHelperClientRuntime runtime = activeRuntime;
        if (runtime != null) {
            runtime.recordClick(position, direction);
        }
    }

    /** Receives the modern destroyBlock HEAD hook before the world is mutated. */
    public static void beginBlockBreak(BlockPos position) {
        FarmHelperClientRuntime runtime = activeRuntime;
        if (runtime != null) {
            runtime.beginBlockBreak(position);
        }
    }

    /** Receives the modern destroyBlock RETURN hook and preserves its boolean success result. */
    public static void completeBlockBreak(boolean destroySucceeded) {
        FarmHelperClientRuntime runtime = activeRuntime;
        if (runtime != null) {
            runtime.completeBlockBreak(destroySucceeded);
        }
    }
}
