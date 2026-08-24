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
    private final KeyPressState keyPressState = new KeyPressState();
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
            openSettings(client, client.screen);
        }
        boolean mappedClick = consumeClicks(openKey);
        boolean keyPress = keyPressState.observe(
                openKey.isDown() || isConfiguredKeyDown(client, runtime.core().config().openSettingsKey()));
        if (shouldOpenFromKey(mappedClick, keyPress, client.screen != null)) {
            openSettings(client, null);
        }
    }

    private void openSettings(Minecraft client, net.minecraft.client.gui.screens.Screen parent) {
        client.setScreen(new FarmHelperSettingsScreen(parent, runtime, openKey));
    }

    private static boolean isConfiguredKeyDown(Minecraft client, int configuredKey) {
        return configuredKey >= 0 && InputConstants.isKeyDown(client.getWindow(), configuredKey);
    }

    static boolean consumeClicks(KeyMapping mapping) {
        boolean clicked = false;
        while (mapping.consumeClick()) {
            clicked = true;
        }
        return clicked;
    }

    static boolean shouldOpenFromKey(boolean mappedClick, boolean keyPress, boolean screenOpen) {
        return !screenOpen && (mappedClick || keyPress);
    }

    private void syncConfiguredKey() {
        int configuredKey = runtime.core().config().openSettingsKey();
        if (!needsKeyMappingUpdate(appliedConfigKey, configuredKey)) {
            return;
        }
        openKey.setKey(InputConstants.Type.KEYSYM.getOrCreate(configuredKey));
        KeyMapping.resetMapping();
        keyPressState.reset();
        appliedConfigKey = configuredKey;
    }

    static boolean needsKeyMappingUpdate(int appliedConfigKey, int configuredKey) {
        return appliedConfigKey != configuredKey;
    }

    static final class KeyPressState {
        private boolean wasDown;

        boolean observe(boolean isDown) {
            boolean pressed = isDown && !wasDown;
            wasDown = isDown;
            return pressed;
        }

        void reset() {
            wasDown = false;
        }
    }
}
