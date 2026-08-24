package dev.hylfrd.farmhelper.client.ui.settings;

import com.mojang.blaze3d.platform.InputConstants;
import dev.hylfrd.farmhelper.client.runtime.FarmHelperClientRuntime;
import dev.hylfrd.farmhelper.client.ui.command.SettingsScreenOpener;
import dev.hylfrd.farmhelper.platform.FarmHelper;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;

/** Owns the configurable key mapping and the native settings-screen opening boundary. */
public final class FarmHelperSettingsController implements SettingsScreenOpener {
    private static final KeyMapping.Category CATEGORY = KeyMapping.Category.register(
            Identifier.fromNamespaceAndPath(FarmHelper.MOD_ID, "settings"));
    private final FarmHelperClientRuntime runtime;
    private final KeyMapping openKey;
    private int appliedConfigKey;
    private boolean openRequested;

    private FarmHelperSettingsController(FarmHelperClientRuntime runtime) {
        this.runtime = runtime;
        openKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.farmhelper.open_settings",
                InputConstants.Type.KEYSYM,
                runtime.core().config().openSettingsKey(),
                CATEGORY));
        appliedConfigKey = runtime.core().config().openSettingsKey();
    }

    public static FarmHelperSettingsController register(FarmHelperClientRuntime runtime) {
        FarmHelperSettingsController controller = new FarmHelperSettingsController(runtime);
        ClientTickEvents.END_CLIENT_TICK.register(controller::tick);
        return controller;
    }

    @Override
    public boolean open() {
        openRequested = true;
        return true;
    }

    private void tick(Minecraft client) {
        syncConfiguredKey();
        if (openRequested) {
            openRequested = false;
            client.setScreen(new FarmHelperSettingsScreen(client.screen, runtime, openKey));
        }
        while (openKey.consumeClick()) {
            if (client.screen == null) {
                client.setScreen(new FarmHelperSettingsScreen(null, runtime, openKey));
            }
        }
    }

    private void syncConfiguredKey() {
        int configuredKey = runtime.core().config().openSettingsKey();
        if (!needsKeyMappingUpdate(appliedConfigKey, configuredKey)) {
            return;
        }
        openKey.setKey(InputConstants.Type.KEYSYM.getOrCreate(configuredKey));
        KeyMapping.resetMapping();
        appliedConfigKey = configuredKey;
    }

    static boolean needsKeyMappingUpdate(int appliedConfigKey, int configuredKey) {
        return appliedConfigKey != configuredKey;
    }
}
