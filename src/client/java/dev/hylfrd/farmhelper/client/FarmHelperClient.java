package dev.hylfrd.farmhelper.client;

import dev.hylfrd.farmhelper.client.platform.ClientTickAdapter;
import dev.hylfrd.farmhelper.client.runtime.FarmHelperClientRuntime;
import dev.hylfrd.farmhelper.client.ui.command.FarmHelperCommands;
import dev.hylfrd.farmhelper.client.ui.settings.FarmHelperSettingsController;
import dev.hylfrd.farmhelper.platform.FarmHelper;
import net.fabricmc.api.ClientModInitializer;

public final class FarmHelperClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        FarmHelperClientRuntime runtime = new FarmHelperClientRuntime();
        FarmHelperSettingsController settings = FarmHelperSettingsController.register(runtime);
        FarmHelperCommands.register(runtime, settings);
        ClientTickAdapter.register(runtime);
        FarmHelper.LOGGER.info("FarmHelper client initialized.");
    }
}
