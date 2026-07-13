package dev.hylfrd.farmhelper.client.ui.command;

import dev.hylfrd.farmhelper.client.runtime.FarmHelperClientRuntime;
import dev.hylfrd.farmhelper.platform.FarmHelper;
import dev.hylfrd.farmhelper.ui.command.FarmHelperCommandService;
import dev.hylfrd.farmhelper.ui.command.FarmHelperCommandTree;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.network.chat.Component;

/** Fabric registration and feedback adapter for the version-independent command tree. */
public final class FarmHelperCommands {
    private FarmHelperCommands() {
    }

    public static void register(FarmHelperClientRuntime runtime) {
        register(runtime, SettingsScreenOpener.unavailable());
    }

    public static void register(FarmHelperClientRuntime runtime, SettingsScreenOpener settingsScreenOpener) {
        FarmHelperCommandService service = new ClientFarmHelperCommandService(runtime, settingsScreenOpener);
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            var root = dispatcher.register(FarmHelperCommandTree.root(
                    "farmhelper",
                    service,
                    FarmHelperCommands::send));
            dispatcher.register(FarmHelperCommandTree.alias(
                    "fh",
                    root,
                    service,
                    FarmHelperCommands::send));
        });
        FarmHelper.LOGGER.info("FarmHelper client commands registered.");
    }

    private static void send(FabricClientCommandSource source, String message) {
        source.sendFeedback(Component.literal("[FarmHelper] " + message));
    }
}
