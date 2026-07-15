package dev.hylfrd.farmhelper.client;

import dev.hylfrd.farmhelper.client.platform.ClientTickAdapter;
import dev.hylfrd.farmhelper.client.platform.ClientCommandScreenCloseGuard;
import dev.hylfrd.farmhelper.client.runtime.FarmHelperClientRuntime;
import dev.hylfrd.farmhelper.client.ui.command.FarmHelperCommands;
import dev.hylfrd.farmhelper.client.ui.settings.FarmHelperSettingsController;
import dev.hylfrd.farmhelper.platform.FarmHelper;
import net.fabricmc.api.ClientModInitializer;

public final class FarmHelperClient implements ClientModInitializer {
    private static volatile FarmHelperClientRuntime activeRuntime;

    @Override
    public void onInitializeClient() {
        FarmHelperClientRuntime runtime = new FarmHelperClientRuntime();
        activeRuntime = runtime;
        ClientCommandScreenCloseGuard commandScreenClose = new ClientCommandScreenCloseGuard();
        FarmHelperSettingsController settings = FarmHelperSettingsController.register(runtime);
        FarmHelperCommands.register(runtime, settings, commandScreenClose);
        ClientTickAdapter.register(runtime, commandScreenClose);
        FarmHelper.LOGGER.info("FarmHelper client initialized.");
    }

    public static void recordServerTimePacket() {
        FarmHelperClientRuntime runtime = activeRuntime;
        if (runtime != null) {
            runtime.receivedServerTimePacket();
        }
    }
}
